// Copyright (c) 2026 PaddlePaddle Authors. All Rights Reserved.
// Modified C++ port for TapScene, licensed under Apache-2.0.
// Pre/postprocessing: PaddleOCR dab3fe35379033fdcb2d0e9572fac0b36c9a9ebf,
// deploy/ppocr-android/ppocr-sdk. See tools/ocr-native/licenses and README.
#include "ocr_core.h"
#include <onnxruntime_cxx_api.h>
#include <opencv2/core.hpp>
#include <opencv2/core/utils/logger.hpp>
#include <opencv2/imgproc.hpp>
#include <algorithm>
#include <array>
#include <chrono>
#include <cmath>
#include <condition_variable>
#include <fstream>
#include <mutex>
#include <numeric>
#include <thread>

namespace tapscene {
namespace {
using Clock = std::chrono::steady_clock;
using Quad = std::array<cv::Point2f, 4>;
constexpr int kMaxDetEdge = 1280;
constexpr int kMaxDetPixels = 786432;
constexpr int kMaxCropPixels = 3000000;
constexpr int kClasses = 6906;
constexpr int kMaxRecWidth = 3200;
constexpr size_t kMaxContourRuns = 32768;
constexpr double kPi = 3.14159265358979323846;
std::mutex process_mutex;

void ORT_API_CALL DiscardOrtLog(void*, OrtLoggingLevel, const char*, const char*, const char*, const char*) {}
int DiscardCvError(int, const char*, const char*, const char*, int, void*) { return 0; }

class Guard {
 public:
  Guard(const std::atomic_bool& cancelled, Ort::RunOptions& options)
      : cancelled_(cancelled), deadline_(Clock::now() + std::chrono::milliseconds(kDeadlineMs)), options_(options),
        watcher_([this] {
          std::unique_lock<std::mutex> lock(mutex_);
          while (!done_) {
            if (cancelled_.load(std::memory_order_relaxed) || Clock::now() >= deadline_) {
              // SetTerminate is documented as callable from another thread during Run.
              // The thread is joined before RunOptions or sessions can be destroyed.
              try { options_.SetTerminate(); } catch (...) {}
              return;
            }
            cv_.wait_for(lock, std::chrono::milliseconds(5), [this] { return done_; });
          }
        }) {}
  ~Guard() {
    { std::lock_guard<std::mutex> lock(mutex_); done_ = true; }
    cv_.notify_all();
    watcher_.join();
  }
  void Check() const {
    if (cancelled_.load(std::memory_order_relaxed)) throw OcrFailure("CANCELLED");
    if (Clock::now() >= deadline_) throw OcrFailure("TIMEOUT");
  }
 private:
  const std::atomic_bool& cancelled_;
  Clock::time_point deadline_;
  Ort::RunOptions& options_;
  std::mutex mutex_;
  std::condition_variable cv_;
  bool done_ = false;
  std::thread watcher_;
};

struct PrivateFloats {
  std::vector<float> values;
  explicit PrivateFloats(size_t size) : values(size) {}
  ~PrivateFloats() { std::fill(values.begin(), values.end(), 0.f); }
};

std::vector<std::string> Dictionary(const std::string& directory, const Guard& guard) {
  std::ifstream stream(directory + "/characters.txt", std::ios::binary);
  if (!stream) throw OcrFailure("MODEL_UNAVAILABLE");
  std::vector<std::string> entries;
  entries.reserve(kClasses - 1);
  std::string line;
  size_t bytes = 0;
  // The APK dictionary is length/SHA checked by Kotlin and generated from locked YAML.
  // Parse bounded lines without a general YAML/configuration engine at runtime.
  while (stream && entries.size() < static_cast<size_t>(kClasses)) {
    guard.Check();
    line.clear();
    char ch;
    bool newline = false;
    while (stream.get(ch)) {
      if (++bytes > 27158) throw OcrFailure("MODEL_UNAVAILABLE");
      if (ch == '\n') { newline = true; break; }
      if (line.size() == 4 || ch == '\r' || ch == '\0') throw OcrFailure("MODEL_UNAVAILABLE");
      line += ch;
    }
    if (!newline) break;
    if (line.empty()) throw OcrFailure("MODEL_UNAVAILABLE");
    entries.push_back(line);
  }
  if (bytes != 27158 || entries.size() != kClasses - 1 || !stream.eof()) throw OcrFailure("MODEL_UNAVAILABLE");
  return entries;
}

int RoundEven(double value) {
  const double base = std::floor(value);
  const double fraction = value - base;
  return static_cast<int>(base + (fraction > .5 || (fraction == .5 && std::fmod(base, 2.) != 0.) ? 1. : 0.));
}

Quad Order(cv::RotatedRect rectangle) {
  Quad p;
  rectangle.points(p.data());
  std::stable_sort(p.begin(), p.end(), [](const auto& a, const auto& b) { return a.x < b.x; });
  const auto tl = p[1].y > p[0].y ? p[0] : p[1];
  const auto bl = p[1].y > p[0].y ? p[1] : p[0];
  const auto tr = p[3].y > p[2].y ? p[2] : p[3];
  const auto br = p[3].y > p[2].y ? p[3] : p[2];
  return {tl, tr, br, bl};
}

std::vector<cv::Point2f> Unclip(const Quad& points) {
  double area = 0., perimeter = 0.;
  std::array<cv::Point2d, 4> normals;
  for (size_t i = 0; i < 4; ++i) {
    const cv::Point2d a(points[i]), b(points[(i + 1) % 4]);
    area += a.x * b.y - b.x * a.y;
    perimeter += cv::norm(b - a);
  }
  area /= 2.;
  if (!std::isfinite(area) || std::abs(area) <= 1e-6 || perimeter <= 1e-6) return {points.begin(), points.end()};
  const double distance = std::abs(area) * 1.5 / perimeter;
  for (size_t i = 0; i < 4; ++i) {
    const cv::Point2d edge = cv::Point2d(points[(i + 1) % 4]) - cv::Point2d(points[i]);
    const double length = cv::norm(edge);
    if (length <= 1e-6) return {points.begin(), points.end()};
    normals[i] = area > 0 ? cv::Point2d(edge.y / length, -edge.x / length) : cv::Point2d(-edge.y / length, edge.x / length);
  }
  double step_angle = 2. * std::acos(std::clamp(1. - .25 / distance, -1., 1.));
  if (!std::isfinite(step_angle) || step_angle <= 1e-6) step_angle = kPi / 8.;
  std::vector<cv::Point2f> result;
  result.reserve(256);
  for (size_t i = 0; i < 4; ++i) {
    const auto& from = normals[(i + 3) % 4];
    const auto& to = normals[i];
    const double start = std::atan2(from.y, from.x);
    double end = std::atan2(to.y, to.x);
    if (area > 0) { while (end < start) end += 2. * kPi; }
    else { while (end > start) end -= 2. * kPi; }
    const int steps = std::max(1, static_cast<int>(std::ceil(std::abs(end - start) / step_angle)));
    if (steps > 4096) throw OcrFailure("RESOURCE_LIMIT");
    for (int n = 0; n <= steps; ++n) {
      const double angle = start + (end - start) * n / steps;
      const cv::Point2f q(static_cast<float>(points[i].x + std::cos(angle) * distance), static_cast<float>(points[i].y + std::sin(angle) * distance));
      if (result.empty() || cv::norm(q - result.back()) > 1e-6) result.push_back(q);
    }
  }
  return result;
}

float BoxScore(const cv::Mat& probabilities, const Quad& points) {
  float xmin = points[0].x, xmax = xmin, ymin = points[0].y, ymax = ymin;
  for (const auto& p : points) { xmin = std::min(xmin, p.x); xmax = std::max(xmax, p.x); ymin = std::min(ymin, p.y); ymax = std::max(ymax, p.y); }
  const int x0 = std::clamp(static_cast<int>(std::floor(xmin)), 0, probabilities.cols - 1);
  const int x1 = std::clamp(static_cast<int>(std::ceil(xmax)), 0, probabilities.cols - 1);
  const int y0 = std::clamp(static_cast<int>(std::floor(ymin)), 0, probabilities.rows - 1);
  const int y1 = std::clamp(static_cast<int>(std::ceil(ymax)), 0, probabilities.rows - 1);
  cv::Mat mask(y1 - y0 + 1, x1 - x0 + 1, CV_8UC1, cv::Scalar(0));
  std::vector<cv::Point> polygon;
  for (const auto& p : points) polygon.emplace_back(static_cast<int>(p.x - x0), static_cast<int>(p.y - y0));
  cv::fillPoly(mask, std::vector<std::vector<cv::Point>>{polygon}, cv::Scalar(1));
  return static_cast<float>(cv::mean(probabilities(cv::Rect(x0, y0, mask.cols, mask.rows)), mask)[0]);
}

std::vector<Quad> Boxes(const float* probabilities, int ph, int pw, int height, int width, const Guard& guard, bool& truncated) {
  cv::Mat probability(ph, pw, CV_32FC1, const_cast<float*>(probabilities));
  cv::Mat mask(ph, pw, CV_8UC1);
  size_t runs = 0;
  for (int y = 0; y < ph; ++y) {
    guard.Check();
    auto* row = mask.ptr<uint8_t>(y);
    bool previous = false;
    for (int x = 0; x < pw; ++x) {
      const float value = probabilities[static_cast<size_t>(y) * pw + x];
      if (!std::isfinite(value)) throw OcrFailure("RECOGNITION_FAILED");
      const bool foreground = value > .3f;
      if (foreground && !previous && ++runs > kMaxContourRuns) throw OcrFailure("RESOURCE_LIMIT");
      row[x] = foreground ? 255 : 0;
      previous = foreground;
    }
  }
  std::vector<std::vector<cv::Point>> contours;
  cv::findContours(mask, contours, cv::RETR_LIST, cv::CHAIN_APPROX_SIMPLE);
  guard.Check();
  if (contours.size() > 3000) truncated = true;
  std::vector<Quad> boxes;
  boxes.reserve(kMaxWords);
  for (size_t index = 0; index < std::min<size_t>(3000, contours.size()); ++index) {
    guard.Check();
    const auto rectangle = cv::minAreaRect(contours[index]);
    if (std::min(rectangle.size.width, rectangle.size.height) < 3.f) continue;
    const auto points = Order(rectangle);
    if (BoxScore(probability, points) < .6f) continue;
    const auto expanded = cv::minAreaRect(Unclip(points));
    if (std::min(expanded.size.width, expanded.size.height) < 5.f) continue;
    auto box = Order(expanded);
    for (auto& p : box) {
      p.x = static_cast<float>(std::clamp(RoundEven(static_cast<double>(p.x) * width / pw), 0, width));
      p.y = static_cast<float>(std::clamp(RoundEven(static_cast<double>(p.y) * height / ph), 0, height));
    }
    if (cv::norm(box[1] - box[0]) <= 3 || cv::norm(box[3] - box[0]) <= 3) continue;
    if (boxes.size() == kMaxWords) { truncated = true; break; }
    boxes.push_back(box);
  }
  std::stable_sort(boxes.begin(), boxes.end(), [](const Quad& a, const Quad& b) { return a[0].y == b[0].y ? a[0].x < b[0].x : a[0].y < b[0].y; });
  for (size_t i = 1; i < boxes.size(); ++i) {
    size_t j = i;
    while (j > 0 && std::abs(boxes[j][0].y - boxes[j - 1][0].y) < 10 && boxes[j][0].x < boxes[j - 1][0].x) { std::swap(boxes[j], boxes[j - 1]); --j; }
  }
  return boxes;
}

cv::Mat Crop(const cv::Mat& src, const Quad& box) {
  const auto points = Order(cv::minAreaRect(std::vector<cv::Point2f>(box.begin(), box.end())));
  const int width = std::max(1, static_cast<int>(std::max(cv::norm(points[0] - points[1]), cv::norm(points[2] - points[3]))));
  const int height = std::max(1, static_cast<int>(std::max(cv::norm(points[0] - points[3]), cv::norm(points[1] - points[2]))));
  // A rotated quad can have sides larger than an input edge. Bound its allocation.
  if (width > 3400 || height > 3400 || static_cast<int64_t>(width) * height > kMaxCropPixels) throw OcrFailure("RESOURCE_LIMIT");
  const Quad destination = {cv::Point2f(0, 0), cv::Point2f(static_cast<float>(width), 0), cv::Point2f(static_cast<float>(width), static_cast<float>(height)), cv::Point2f(0, static_cast<float>(height))};
  cv::Mat result;
  cv::warpPerspective(src, result, cv::getPerspectiveTransform(points.data(), destination.data()), cv::Size(width, height), cv::INTER_CUBIC, cv::BORDER_REPLICATE);
  if (static_cast<double>(height) / width >= 1.5) cv::rotate(result, result, cv::ROTATE_90_COUNTERCLOCKWISE);
  return result;
}

void Tensor(const cv::Mat& image, PrivateFloats& tensor, bool detector, const Guard& guard) {
  const size_t plane = static_cast<size_t>(image.rows) * image.cols;
  const std::array<float, 3> mean = {.485f, .456f, .406f}, deviation = {.229f, .224f, .225f};
  for (int y = 0; y < image.rows; ++y) {
    guard.Check();
    const auto* row = image.ptr<cv::Vec3b>(y);
    for (int x = 0; x < image.cols; ++x) {
      for (int c = 0; c < 3; ++c) {
        // Input is RGB; the detector's official default is BGR and recognizer is RGB.
        const float value = row[x][detector ? 2 - c : c];
        tensor.values[static_cast<size_t>(c) * plane + static_cast<size_t>(y) * image.cols + x] = detector ? (value * (1.f / 255.f) - mean[c]) / deviation[c] : value / 127.5f - 1.f;
      }
    }
  }
}

std::vector<Ort::Value> Run(Ort::Session& session, PrivateFloats& input, const std::array<int64_t, 4>& shape, Ort::RunOptions& options, const Guard& guard) {
  guard.Check();
  auto memory = Ort::MemoryInfo::CreateCpu(OrtArenaAllocator, OrtMemTypeDefault);
  auto tensor = Ort::Value::CreateTensor<float>(memory, input.values.data(), input.values.size(), shape.data(), shape.size());
  // Both names and the single float input/output are fixed by the SHA-locked models.
  const char* inputs[] = {"x"};
  const char* outputs[] = {"fetch_name_0"};
  try {
    auto result = session.Run(options, inputs, &tensor, 1, outputs, 1);
    guard.Check();
    return result;
  } catch (const Ort::Exception&) { guard.Check(); throw OcrFailure("RECOGNITION_FAILED"); }
}

Result Pipeline(const uint8_t* rgb, int width, int height, const std::string& directory, const std::atomic_bool& cancelled) {
  Ort::RunOptions run_options;
  run_options.SetRunLogSeverityLevel(ORT_LOGGING_LEVEL_FATAL);
  Guard guard(cancelled, run_options);
  guard.Check();
  cv::setNumThreads(1);
  cv::utils::logging::setLogLevel(cv::utils::logging::LOG_LEVEL_SILENT);
  cv::redirectError(DiscardCvError);
  Ort::Env environment(ORT_LOGGING_LEVEL_FATAL, "TapSceneOfflineOCR", DiscardOrtLog, nullptr);
  environment.DisableTelemetryEvents();
  Ort::SessionOptions options;
  options.SetIntraOpNumThreads(1).SetInterOpNumThreads(1).SetExecutionMode(ExecutionMode::ORT_SEQUENTIAL);
  options.SetGraphOptimizationLevel(GraphOptimizationLevel::ORT_ENABLE_ALL);
  options.SetLogSeverityLevel(ORT_LOGGING_LEVEL_FATAL).DisableCpuMemArena().DisableMemPattern();
  options.AddConfigEntry("session.intra_op.allow_spinning", "0");
  options.AddConfigEntry("session.inter_op.allow_spinning", "0");
  const auto dictionary = Dictionary(directory, guard);
  Ort::Session detector(nullptr), recognizer(nullptr);
  try {
    detector = Ort::Session(environment, (directory + "/det.onnx").c_str(), options);
    guard.Check();
    recognizer = Ort::Session(environment, (directory + "/rec.onnx").c_str(), options);
    guard.Check();
  } catch (const Ort::Exception&) { guard.Check(); throw OcrFailure("MODEL_UNAVAILABLE"); }
  cv::Mat src(height, width, CV_8UC3, const_cast<uint8_t*>(rgb));
  double ratio = std::min(height, width) < 64 ? 64. / std::min(height, width) : 1.;
  int dh = static_cast<int>(height * ratio), dw = static_cast<int>(width * ratio);
  // Apply a single global edge/area budget, never a per-image quality heuristic.
  ratio = std::min({1., static_cast<double>(kMaxDetEdge) / std::max(dh, dw),
                    std::sqrt(static_cast<double>(kMaxDetPixels) / (static_cast<double>(dh) * dw))});
  dh = static_cast<int>(dh * ratio); dw = static_cast<int>(dw * ratio);
  dh = std::max(32, RoundEven(dh / 32.) * 32); dw = std::max(32, RoundEven(dw / 32.) * 32);
  // Nearest-even 32 alignment can exceed the area budget. Proportionally reduce
  // both sides and align downward once; do not reject an otherwise legal input.
  if (static_cast<int64_t>(dh) * dw > kMaxDetPixels) {
    ratio = std::sqrt(static_cast<double>(kMaxDetPixels) / (static_cast<double>(dh) * dw));
    dh = std::max(32, static_cast<int>(dh * ratio / 32.) * 32);
    dw = std::max(32, static_cast<int>(dw * ratio / 32.) * 32);
  }
  if (dh > kMaxDetEdge || dw > kMaxDetEdge || static_cast<int64_t>(dh) * dw > kMaxDetPixels) throw OcrFailure("RESOURCE_LIMIT");
  Result result;
  std::vector<Quad> boxes;
  {
    cv::Mat resized;
    cv::resize(src, resized, cv::Size(dw, dh), 0., 0., cv::INTER_LINEAR);
    PrivateFloats input(static_cast<size_t>(dh) * dw * 3);
    Tensor(resized, input, true, guard);
    auto outputs = Run(detector, input, {1, 3, dh, dw}, run_options, guard);
    const auto type = outputs[0].GetTensorTypeAndShapeInfo();
    const auto shape = type.GetShape();
    if (type.GetElementType() != ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT || shape.size() != 4 || shape[0] != 1 || shape[1] != 1 || shape[2] != dh || shape[3] != dw) throw OcrFailure("RECOGNITION_FAILED");
    boxes = Boxes(outputs[0].GetTensorData<float>(), dh, dw, height, width, guard, result.truncated);
  }
  // Release detector working allocations before the per-line recognizer loop.
  detector = Ort::Session(nullptr);
  size_t text_bytes = 0, text_units = 0;
  for (size_t index = 0; index < boxes.size(); ++index) {
    guard.Check();
    cv::Mat crop = Crop(src, boxes[index]), resized;
    const int rw = std::clamp(static_cast<int>(std::ceil(48. * crop.cols / crop.rows)), 1, kMaxRecWidth);
    cv::resize(crop, resized, cv::Size(rw, 48), 0., 0., cv::INTER_LINEAR);
    crop.release();
    PrivateFloats input(static_cast<size_t>(rw) * 48 * 3);
    Tensor(resized, input, false, guard);
    auto outputs = Run(recognizer, input, {1, 3, 48, rw}, run_options, guard);
    const auto type = outputs[0].GetTensorTypeAndShapeInfo();
    const auto shape = type.GetShape();
    if (type.GetElementType() != ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT || shape.size() != 3 || shape[0] != 1 || shape[1] < 1 || shape[1] > 800 || shape[2] != kClasses) throw OcrFailure("RECOGNITION_FAILED");
    const float* probabilities = outputs[0].GetTensorData<float>();
    std::string text;
    int previous = -1, kept = 0;
    double sum = 0.;
    size_t units = 0;
    bool overflow = false;
    for (int64_t step = 0; step < shape[1]; ++step) {
      guard.Check();
      const float* row = probabilities + step * kClasses;
      int best = 0;
      for (int c = 0; c < kClasses; ++c) {
        if (!std::isfinite(row[c])) throw OcrFailure("RECOGNITION_FAILED");
        if (row[c] > row[best]) best = c;
      }
      if (best != 0 && best != previous) {
        const auto& entry = dictionary[static_cast<size_t>(best - 1)];
        if (text.size() + entry.size() > kMaxWordBytes || ++units > 512) overflow = true;
        if (!overflow) { text += entry; sum += row[best]; ++kept; }
      }
      previous = best;
    }
    if (overflow || text_bytes + text.size() > kMaxTextBytes || text_units + units > kMaxTextUnits) { result.truncated = true; continue; }
    if (text.empty()) continue;
    float left = static_cast<float>(width), top = static_cast<float>(height), right = 0.f, bottom = 0.f;
    for (const auto& p : boxes[index]) { left = std::min(left, p.x); top = std::min(top, p.y); right = std::max(right, p.x); bottom = std::max(bottom, p.y); }
    if (!(left < right && top < bottom)) continue;
    const float confidence = std::clamp(static_cast<float>(sum / kept * 100.), 0.f, 100.f);
    text_bytes += text.size(); text_units += units;
    result.words.push_back({std::move(text), static_cast<int>(left), static_cast<int>(top), static_cast<int>(right), static_cast<int>(bottom), confidence, 0, static_cast<int>(index)});
  }
  guard.Check();
  return result;
}
} // namespace

Result Recognize(const uint8_t* rgb, int width, int height, const std::string& directory, const std::atomic_bool& cancelled) {
  if (!rgb || width < 1 || height < 1 || width > kMaxEdge || height > kMaxEdge || static_cast<int64_t>(width) * height > kMaxPixels || directory.empty() || directory.size() > 4096) throw OcrFailure("INVALID_INPUT");
  if (cancelled.load(std::memory_order_relaxed)) throw OcrFailure("CANCELLED");
  std::unique_lock<std::mutex> lock(process_mutex, std::try_to_lock);
  if (!lock.owns_lock()) throw OcrFailure("ENGINE_BUSY");
  try { return Pipeline(rgb, width, height, directory, cancelled); }
  catch (const OcrFailure&) { throw; }
  catch (const std::bad_alloc&) { throw OcrFailure("RESOURCE_LIMIT"); }
  catch (...) { if (cancelled.load(std::memory_order_relaxed)) throw OcrFailure("CANCELLED"); throw OcrFailure("RECOGNITION_FAILED"); }
}
} // namespace tapscene
