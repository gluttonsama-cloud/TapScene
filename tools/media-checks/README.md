# 媒体增量检查

素材由 FFmpeg 的 `testsrc2` / `sine` 在本机生成，不含用户内容；160×288、10 fps、1 秒。
`fixtures/manifest.json` 记录实际编码、字节量及 SHA-256；`.b64` 仅为便于审阅和传输的原始 MP4/CSD 编码。

- `hevc-sdr`：Main、8 位、4:2:0、BT.709 limited，另有 AAC 音轨。
- `avc-sdr`：Constrained Baseline、8 位 BT.709，回归原有输入。
- `hevc-10bit-sdr`：Main10，验证不能因解码器降位深而放行。
- `hevc-hdr`：Main8 但有 PQ / BT.2020，验证不能仅按 profile 判定 SDR。
- `hevc-unknown-colour`：Main8 但无明确来源色彩，不能把解码器默认值当成 SDR 证据。

五段均已用 FFprobe 检查并由 FFmpeg 完整解码。生成使用 `libx265` / `libx264`，
明确设置编码器参数 `colorprim=1:transfer=1:colormatrix=1:range=limited`；HDR 使用 `9:16:9`。
单独设置 FFmpeg 色彩选项不足以保证编码器将参数写入 SPS。

CI 先还原并检查摘要，用项目的纯 Kotlin 解析器验证真实参数集、损坏数据与中途参数替换；
随后运行平台 Instrumentation，实际执行 Android 解码、取帧、遮挡、H.264 去音重编码及取消检查。
没有引入另一个测试框架。系统文件选择器和手机硬件编解码器仍需设备验证。
