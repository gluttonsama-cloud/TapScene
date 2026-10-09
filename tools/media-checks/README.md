# 媒体兼容增量检查

这里只保存 FFmpeg 合成的公开测试画面，不含用户素材。`fixtures/manifest.json` 记录原始 MP4 的字节数、SHA-256、流信息、FFprobe 实际逐帧 PTS 和预期 Surface 尺寸；`.b64` 是这些原始 MP4 的编码。没有手写 SPS/SEI 解析器或输入白名单测试。

## 素材与预期

- `avc-sdr`：160×288、8 位 BT.709，普通 SDR / API 26 基线。
- `hevc-sdr`：同尺寸 HEVC Main SDR，带 AAC，输出必须去音。
- `hevc-unknown-colour`：无完整色彩标记的 HEVC Main SDR，属于可解码正例，不按缺字段拒绝。
- `hevc-10bit-sdr`：HEVC Main10、BT.709 SDR。设备声明支持对应 profile/尺寸时必须通过实际链路；查无匹配解码器则单列 `NOT_SUPPORTED`，不声称通过，也不把 10 位等同 HDR。
- `avc-long-screen-unknown-colour`：1440×3200、无色彩字段，4 帧；Surface 缩至 1080×2400。原 MP4 为 15,350 字节。
- `avc-rotated-sar-vfr`：180×320、SAR 3:2、旋转矩阵逆时针 90°（Android rotation 270°），实际显示为横屏 320×270。6 帧 PTS 为 0、66,667、233,333、466,667、500,000、900,000 µs；MP4 为 13,927 字节。帧身份来自真实样本，不由 nominal fps 推算。
- `hevc-hdr`：HEVC Main8、PQ / BT.2020。API <29 校验明确的 HDR 能力错误及无残留；API ≥29 且有匹配解码器才尝试实际 Surface / GL tone-map。实际转换失败必须记录具体阶段并使检查失败，不因 profile、API 或解码器声明就声称 HDR 可用。此样例覆盖 PQ；HLG 与手机具体色彩效果仍待设备验证。

## 运行

```sh
python3 tools/media-checks/materialize-fixtures.py /tmp/tapscene-fixtures --verify-media
```

默认检查离线 manifest 结构、Base64、大小及 SHA-256，并将 MP4 放入 Android 测试 assets。`--verify-media` 额外使用已经安装的 FFprobe 核对流与真实 PTS，并由 FFmpeg 完整解码所有视频；不会安装软件或下载素材。

构建测试 APK 后，使用现有平台 Instrumentation（预览包名示例）：

```sh
adb shell am instrument -w \
  com.tapscene.preview.hevc.test/com.tapscene.media.MediaCompatibilityInstrumentation
```

输入 `validate` 只试取首、中、末 Surface 帧。取帧检查将真实 `Frame.presentationTimeMs` 与 Android extractor 的真实 PTS 对照，明确 `timePrecisionUs=1000`，允许毫秒截断差值 0–999 µs，不能声称微秒精度。首末帧、旋转、SAR 与 GPU 缩放尺寸分别核对。

输出检查 PNG 实际黑色不透明遮挡，以及单轨无声 H.264 的每一帧、PTS、实际尺寸、色彩标记和摘要。编码器可能按自身 alignment/尺寸能力缩小画布，检查使用实际输出尺寸及对应遮挡位置；VFR 保留真实选中样本及末帧，不以 n/fps 或编码器 rate hint 合成时间。取消检查覆盖已编码/已验证但尚未发布的时刻，要求删除本次临时产物、保留早先候选及原片。

`TAPSCENE_MEDIA_CHECKS_OK` 要结合各项 `PASS` / `NOT_SUPPORTED` 阅读，不表示每台手机支持 Main10 或 HDR。系统选择器、provider 复制/登记、HLG 与真机硬件表现未由本检查代替。没有引入新测试框架或模拟器方案。

## 新增样例生成方式

使用 FFmpeg 7.1.5 / libx264；旋转使用 `-display_rotation` 写实际 display matrix。

```sh
ffmpeg -f lavfi -i 'color=c=0x304080:size=1440x3200:rate=4:duration=1,drawbox=x=100:y=100:w=600:h=1200:color=yellow:t=fill,drawbox=x=800:y=2000:w=440:h=800:color=green:t=fill' \
  -an -c:v libx264 -profile:v baseline -preset medium -crf 30 -pix_fmt yuv420p \
  -movflags +faststart avc-long-screen-unknown-colour.mp4
ffmpeg -f lavfi -i 'testsrc2=size=180x320:rate=30:duration=1' \
  -vf "select='eq(n,0)+eq(n,2)+eq(n,7)+eq(n,14)+eq(n,15)+eq(n,27)',setsar=3/2" \
  -fps_mode vfr -an -c:v libx264 -profile:v baseline -preset medium -crf 28 \
  -pix_fmt yuv420p -video_track_timescale 30000 -movflags +faststart rotation-base.mp4
ffmpeg -display_rotation:v:0 90 -i rotation-base.mp4 -map 0:v -c copy \
  -movflags +faststart avc-rotated-sar-vfr.mp4
```

本次本地已通过七段摘要、FFprobe 事实核对及 FFmpeg 完整解码；Android 构建与设备 Instrumentation 尚未在此工作环境运行，不能将离线素材验证当成手机兼容结论。
