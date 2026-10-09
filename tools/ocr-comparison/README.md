# 官方 Paddle 小样对照

`run-paddle.py` 是固定官方 Android Bitmap 路径的主机 Python 移植，只读取 `../ocr-checks/fixtures` 的六张合成灰度图；不是 Android 实现、真机成绩或真实录屏验收。它不联网、不解码 PNG、不读用户图片，也不把文字真值或真值框传给识别器。复用 `check-ocr.py` 的 CER/IoU 评分；此选型脚本保留当时的原始无文字负例门槛。正式产品检查另见 `../ocr-checks/README.md`，不覆盖本次原始结果。

## 复现

预先准备 `paddle-locks.json` 列出的两个官方模型目录及 `inference.yml`。下载源、commit、字节数和 SHA-256 已锁定；模型和 Python 工具安装在仓库外，不提交二进制。主机环境使用官方 PyPI 的 `onnxruntime==1.31.0`、`opencv-python-headless==5.0.0.93`；既有 numpy/PyYAML 版本也记入锁文件。

```sh
PYTHONPATH=/path/to/isolated/python python tools/ocr-comparison/run-paddle.py \
  --models /path/to/paddle-models --output /tmp/paddle-comparison
```

该命令为每例启动三个独立进程，保存原始行文字、CTC 均分、四边形/外接框、逐字符错误、IoU、耗时、峰值 RSS 和稳定性。模型字节不符、模型不可运行或执行失败均拒绝继续；原始质量 gate 未通过时退出 1。主机 RSS 包含 Python/numpy/OpenCV/ORT，不可当作 Android 占用；完整子进程耗时包含导入、校验、字典解析和模型加载，阶段耗时另列。两引擎的置信度粒度不同，不能直接比较数值。

流水线是 [官方源码](https://github.com/PaddlePaddle/PaddleOCR/tree/dab3fe35379033fdcb2d0e9572fac0b36c9a9ebf/deploy/ppocr-android) 的 DetPreprocessor/ImageUtils、DBPostProcessor/PolygonUnclip、QuadTextCrop、RecPreprocessor、CTCDecoder 和 BoxSorter 对照移植。采用官方 Android 默认参数：检测 min-side 64/max-side 4000、阈值 0.3/0.6、unclip 1.5、BGR；识别 RGB 高48、batch1、保留非空输出。检测模型 YAML 的阈值与 Android 默认值不同；本次固定后者，没有按单图调参。CPU 单线程，创建任何会话前调用官方 `disable_telemetry_events()`。

## 使用边界

2026-10-09 初筛中，五张有字图共73字全部正确，16行框 IoU 均≥0.5；图标例输出一个 `+`；按当时“不得输出文字”的门槛原始 gate 为 FAIL。加号本身可以是正确符号文本，因此正式产品保留原始结果并检查它不会成为语义标题，不为消除符号做引擎特判。三次输出相同。这支持选择 v6 tiny 继续做人工确认的标题候选，不证明按钮、触点、敏感内容检测或自动遮挡可靠。保留完整原始结果，不能把应用候选过滤当成原始引擎通过。没有继续换引擎或调参。

## Android 依赖代价

`android-dependency-inspection.json` 记录从官方 Maven 取得的 ORT1.31.0 与 OpenCV5.0.0.1 AAR SHA、每 ABI 原生库大小及 ELF LOAD 对齐值。仅解包检查，没有接入应用。OpenCV 5.0.0.1 是 [上游确认的16KiB修复版](https://github.com/opencv/opencv/issues/29375)；不能用元数据中仍指向5.0.0的 latest。

完整两套 AAR 的 arm64 原生库加模型/字典已约71.8MiB，不含应用代码和资源；armv7+arm64约117.1MiB。模型本身只有6,243,229字节，不能用模型大小代替 APK 代价。ZIP压缩字节和解压后原生字节均保留；实际APK是否压缩/按ABI拆包仍需构建后检查。64位 ELF LOAD 均0x4000，不等于最终APK ZIP对齐或真机兼容性已通过。

正式实现采用同一官方 ORT Maven 二进制与 OpenCV 4.14.0 官方源码的 core/imgproc 子集；关闭 imgcodecs、DNN、video、Java、OpenCL及非必要第三方后端。见 [固定输入与原生流水线](../ocr-native/README.md)。C++ 私有环境调用 `DisableTelemetryEvents()`，模型随 APK 离线打包；无网络权限。上面的完整 AAR 大小仅用于选型，实际 APK 代价由构建后检查报告。

移植代码的上游 Apache-2.0 许可见 `LICENSE-PaddleOCR.txt`。
