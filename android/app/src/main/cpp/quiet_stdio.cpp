// Only this library's linked dependency references are wrapped. No process-wide
// stdout/stderr redirection and no Android log dependency.
#include <cstdarg>
#include <cstdio>
#include <cstring>
extern "C" {
int __real_vfprintf(FILE*, const char*, va_list);
int __real_fputs(const char*, FILE*);
int __real_fputc(int, FILE*);
size_t __real_fwrite(const void*, size_t, size_t, FILE*);
static bool private_console(FILE* stream) { return stream == stdout || stream == stderr; }
int __wrap_printf(const char*, ...) { return 0; }
int __wrap_vprintf(const char*, va_list) { return 0; }
int __wrap_vfprintf(FILE* stream, const char* format, va_list args) {
  return private_console(stream) ? 0 : __real_vfprintf(stream, format, args);
}
int __wrap_fprintf(FILE* stream, const char* format, ...) {
  if (private_console(stream)) return 0;
  va_list args; va_start(args, format);
  int result = __real_vfprintf(stream, format, args);
  va_end(args); return result;
}
int __wrap_puts(const char*) { return 0; }
int __wrap_putchar(int c) { return c; }
int __wrap_fputs(const char* text, FILE* stream) {
  return private_console(stream) ? 0 : __real_fputs(text, stream);
}
int __wrap_fputc(int c, FILE* stream) {
  return private_console(stream) ? c : __real_fputc(c, stream);
}
size_t __wrap_fwrite(const void* data, size_t size, size_t count, FILE* stream) {
  return private_console(stream) ? count : __real_fwrite(data, size, count, stream);
}
}
