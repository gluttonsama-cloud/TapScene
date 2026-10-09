#include "ocr_core.h"
#include <chrono>
#include <thread>
#include <unistd.h>
namespace {
template<typename F> bool Fails(const char* expected, F action) {
  try { action(); return false; }
  catch (const tapscene::OcrFailure& error) { return std::string(error.what()) == expected; }
  catch (...) { return false; }
}
}
int main(int argc, char** argv) {
  if (argc != 2) return 2;
  const std::string models(argv[1]);
  std::atomic_bool cancel(false);
  const uint8_t pixel = 255;
  if (!Fails("INVALID_INPUT", [&]{ tapscene::Recognize(nullptr, 1, 1, models, cancel); }) ||
      !Fails("INVALID_INPUT", [&]{ tapscene::Recognize(&pixel, 0, 1, models, cancel); }) ||
      !Fails("INVALID_INPUT", [&]{ tapscene::Recognize(&pixel, 2401, 1, models, cancel); }) ||
      !Fails("INVALID_INPUT", [&]{ tapscene::Recognize(&pixel, 2400, 2400, models, cancel); }) ||
      !Fails("INVALID_INPUT", [&]{ tapscene::Recognize(&pixel, 1, 1, "", cancel); })) return 1;
  cancel.store(true);
  if (!Fails("CANCELLED", [&]{ tapscene::Recognize(&pixel, 1, 1, models, cancel); })) return 1;
  cancel.store(false);
  if (!Fails("MODEL_UNAVAILABLE", [&]{ tapscene::Recognize(&pixel, 1, 1, "/does-not-exist/PRIVATE_PATH_CANARY", cancel); })) return 1;
  std::vector<uint8_t> blank(640 * 360 * 3, 255);
  try {
    const auto result = tapscene::Recognize(blank.data(), 640, 360, models, cancel);
    if (!result.words.empty() || result.truncated) return 1;
  } catch (...) { return 1; }
  std::thread trigger([&]{ std::this_thread::sleep_for(std::chrono::milliseconds(1)); cancel.store(true); });
  const bool cancelled = Fails("CANCELLED", [&]{ tapscene::Recognize(blank.data(), 640, 360, models, cancel); });
  trigger.join();
  if (!cancelled) return 1;
  constexpr char ok[] = "OCR_CORE_TESTS_PASSED\n";
  return write(STDOUT_FILENO, ok, sizeof(ok) - 1) == sizeof(ok) - 1 ? 0 : 1;
}
