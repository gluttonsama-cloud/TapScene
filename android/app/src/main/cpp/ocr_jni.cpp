#include "ocr_core.h"
#include <jni.h>
#include <algorithm>
#include <cstring>
#include <limits>
#include <memory>
#include <mutex>
#include <unordered_map>
namespace {
std::mutex signal_mutex;
std::unordered_map<jlong, std::shared_ptr<std::atomic_bool>> signals;
jlong next_id = 1;
void Fail(JNIEnv* env, const char* code) {
  if (env->ExceptionCheck()) return;
  jclass type = env->FindClass("java/lang/IllegalStateException");
  if (type) { env->ThrowNew(type, code); env->DeleteLocalRef(type); }
}
std::shared_ptr<std::atomic_bool> Signal(jlong id) {
  std::lock_guard<std::mutex> guard(signal_mutex);
  auto found = signals.find(id);
  if (found == signals.end()) throw tapscene::OcrFailure("INVALID_INPUT");
  return found->second;
}
struct PrivateBytes {
  std::vector<uint8_t> value;
  explicit PrivateBytes(size_t size = 0) : value(size) {}
  ~PrivateBytes() { std::fill(value.begin(), value.end(), 0); }
};
struct UtfChars {
  JNIEnv* env; jstring text; const char* value;
  UtfChars(JNIEnv* e, jstring s) : env(e), text(s), value(e->GetStringUTFChars(s, nullptr)) {}
  ~UtfChars() { if (value) env->ReleaseStringUTFChars(text, value); }
};
void Int(std::vector<uint8_t>& bytes, uint32_t value) {
  bytes.push_back(static_cast<uint8_t>(value >> 24));
  bytes.push_back(static_cast<uint8_t>(value >> 16));
  bytes.push_back(static_cast<uint8_t>(value >> 8));
  bytes.push_back(static_cast<uint8_t>(value));
}
}
extern "C" JNIEXPORT jlong JNICALL Java_com_tapscene_ocr_OcrNative_createSignal(JNIEnv* env, jobject) {
  try {
    std::lock_guard<std::mutex> guard(signal_mutex);
    if (!signals.empty() || next_id == std::numeric_limits<jlong>::max()) {
      Fail(env, "ENGINE_BUSY"); return 0;
    }
    const jlong id = next_id++;
    signals.emplace(id, std::make_shared<std::atomic_bool>(false));
    return id;
  } catch (...) { Fail(env, "RECOGNITION_FAILED"); return 0; }
}
extern "C" JNIEXPORT void JNICALL Java_com_tapscene_ocr_OcrNative_cancelSignal(JNIEnv*, jobject, jlong id) {
  try {
    std::lock_guard<std::mutex> guard(signal_mutex);
    auto found = signals.find(id);
    if (found != signals.end()) found->second->store(true, std::memory_order_relaxed);
  } catch (...) { /* Never throw a C++ exception across JNI, including cancellation. */ }
}
extern "C" JNIEXPORT void JNICALL Java_com_tapscene_ocr_OcrNative_releaseSignal(JNIEnv*, jobject, jlong id) {
  try {
    std::lock_guard<std::mutex> guard(signal_mutex);
    signals.erase(id);
  } catch (...) { /* Never throw a C++ exception across JNI during cleanup. */ }
}
extern "C" JNIEXPORT jbyteArray JNICALL Java_com_tapscene_ocr_OcrNative_recognizeRgb(
    JNIEnv* env, jobject, jbyteArray rgb, jint width, jint height, jstring model_dir, jlong signal_id) {
  try {
    if (!rgb || !model_dir || width < 1 || height < 1 || width > tapscene::kMaxEdge || height > tapscene::kMaxEdge ||
        static_cast<int64_t>(width) * height > tapscene::kMaxPixels ||
        env->GetArrayLength(rgb) != static_cast<int64_t>(width) * height * 3 || env->GetStringUTFLength(model_dir) > 4096) {
      throw tapscene::OcrFailure("INVALID_INPUT");
    }
    auto signal = Signal(signal_id);
    UtfChars path(env, model_dir);
    if (!path.value) return nullptr; // preserve the JVM's pending allocation exception.
    PrivateBytes private_pixels(static_cast<size_t>(width) * height * 3);
    auto& pixels = private_pixels.value;
    env->GetByteArrayRegion(rgb, 0, static_cast<jsize>(pixels.size()), reinterpret_cast<jbyte*>(pixels.data()));
    if (env->ExceptionCheck()) return nullptr;
    auto result = tapscene::Recognize(pixels.data(), width, height, path.value, *signal);
    std::fill(pixels.begin(), pixels.end(), 0);
    PrivateBytes private_output;
    auto& output = private_output.value;
    output.reserve(tapscene::kMaxTextBytes + tapscene::kMaxWords * 36 + 20);
    Int(output, 0x54534f31); Int(output, static_cast<uint32_t>(width)); Int(output, static_cast<uint32_t>(height));
    Int(output, result.truncated ? 1 : 0); Int(output, static_cast<uint32_t>(result.words.size()));
    for (const auto& word : result.words) {
      Int(output, word.left); Int(output, word.top); Int(output, word.right); Int(output, word.bottom);
      uint32_t confidence_bits; static_assert(sizeof(confidence_bits) == sizeof(word.confidence));
      std::memcpy(&confidence_bits, &word.confidence, sizeof(confidence_bits));
      Int(output, confidence_bits); Int(output, word.block_index); Int(output, word.line_index);
      Int(output, static_cast<uint32_t>(word.text.size()));
      output.insert(output.end(), word.text.begin(), word.text.end());
    }
    if (signal->load(std::memory_order_relaxed)) throw tapscene::OcrFailure("CANCELLED");
    jbyteArray bytes = env->NewByteArray(static_cast<jsize>(output.size()));
    if (!bytes) return nullptr;
    env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(output.size()), reinterpret_cast<const jbyte*>(output.data()));
    std::fill(output.begin(), output.end(), 0);
    return env->ExceptionCheck() ? nullptr : bytes;
  } catch (const tapscene::OcrFailure& error) {
    Fail(env, error.what());
  } catch (const std::bad_alloc&) {
    Fail(env, "RESOURCE_LIMIT");
  } catch (...) {
    // Do not forward upstream messages: they may contain OCR or a private path.
    Fail(env, "RECOGNITION_FAILED");
  }
  return nullptr;
}
