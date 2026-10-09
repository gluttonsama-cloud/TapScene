# 离线 OCR 小样检查

六张固定的自制灰度 UI 图覆盖中文标题、描边按钮、中英数字、深色、小字、低对比与图标。它们不是用户素材，也不代表真实录屏或 Android 性能。PGM 像素、文字真值、字形框和字体信息固定在 `fixtures/manifest.json`；识别器只接收整张图，不接收真值。

先按 [原生引擎说明](../ocr-native/README.md) 准备锁定模型并构建同一生产 C++ 核心，然后运行：

```sh
python3 tools/ocr-checks/check-store.py
python3 tools/ocr-checks/check-metrics.py
python3 tools/ocr-checks/check-ocr.py \
  --native-runner android/app/build/ocr-host/tapscene_ocr_fixture \
  --model-dir android/app/build/ocr/assets/ocr \
  --output /tmp/tapscene-ocr-check --repeat 2
```

- 模型按 `tools/ocr-native/dependencies.lock.json` 核对实际字节，输入按固定 PGM 摘要核对。主机 runner 使用生产预处理、检测、裁切、识别和 CTC；stdout 返回合成图文字 TSV，stderr 必须为空。实际 App 不输出此 TSV。
- 五张有字图逐行比较去空白后的 Unicode CER，保留大小写/标点差异及未匹配插入；要求 CER ≤15%、每行非空且字形框 IoU ≥0.5。所有输出框须有效且在原图像素内，两次结果一致。小字/低对比也在本轮检查范围内。
- 图标例保留全部原始输出及未匹配字数。加号可以被识别为符号；此例验证不会生成具语义长度的标题建议，不要求引擎屏蔽符号，也不声称已检测点击。候选标题至少含三个字母/数字/汉字、每项置信度 ≥80、最多80个 UTF-16 单元；原始文字仍可人工检查。
- `report.json`、TSV 和空 stderr 保存实际输出、置信度、像素框、CER/IoU、重复一致性和耗时。耗时包含新进程/模型加载，是共享主机观察值，不能推断手机延迟或内存。
- `check-store.py` 检查真实 SQLite 表与删除隔离；Android 持久化/取消/切换竞态另有增量 instrumentation，只有编译不算运行。`check-apk.py` 检查真正 APK 中模型、许可证、官方 runtime 字节、动态依赖、16 KiB ELF/ZIP 对齐和包体。

重新生成合成图才需 Pillow 和已有 Noto Sans CJK SC 字体；生成器保存其摘要。不能通过改真值、隐藏原始错误或逐图调参把失败变成通过。前期 Tesseract 对照漏描边按钮；官方 Paddle Python 对照用于选型，不能替代本次生产原生核心检查。手机离线运行、内存、温度、延迟、取消与日志仍需真实设备验证。
