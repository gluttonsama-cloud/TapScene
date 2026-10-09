#pragma once
#include <atomic>
#include <cstdint>
#include <stdexcept>
#include <string>
#include <vector>
namespace tapscene {
constexpr int kMaxEdge = 2400;
constexpr int kMaxPixels = 1080 * 2400;
constexpr size_t kMaxWords = 256;
constexpr size_t kMaxWordBytes = 2048;
constexpr size_t kMaxTextBytes = 32768;
constexpr size_t kMaxTextUnits = 8192;
constexpr int kDeadlineMs = 20000;
struct Word {
  std::string text;
  int left, top, right, bottom;
  float confidence;
  int block_index, line_index;
};
struct Result { std::vector<Word> words; bool truncated = false; };
class OcrFailure final : public std::runtime_error {
 public: explicit OcrFailure(const char* code) : std::runtime_error(code) {}
};
// Exactly width*height*3 bytes in RGB order. No codecs, user configuration or model import.
Result Recognize(const uint8_t* rgb, int width, int height,
                 const std::string& model_dir, const std::atomic_bool& cancelled);
}
