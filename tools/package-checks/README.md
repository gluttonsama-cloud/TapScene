# 离线观看包与受限视频过渡检查

这组检查直接编译 `com.tapscene.packageformat` 的纯 Java 代码。使用已安装的 JDK、FFprobe 和 FFmpeg，不使用 Gradle、Android SDK、网络或测试框架，不安装工具。PNG 是一个 `#336699` 不透明像素；MP4 是 FFmpeg 本机生成的 64×64 测试图案，含音反例用合成正弦波，均无用户数据。AWT / ImageIO、进程调用与 FFmpeg 验证器仅用于桌面检查，未进入 Android 源码。

## 运行

```sh
bash tools/package-checks/run-checks.sh
```

默认使用临时目录并在结束后清理。需要保留合成包、恶意反例及完整日志时，传入一个尚不存在的输出目录：

```sh
bash tools/package-checks/run-checks.sh /tmp/tapscene-package-checks
```

输出：

- `fixtures/synthetic.tapscene`：三状态观看包，含两个点击分支、一个作者编排的继续动作及明确结束；与 `branch-viewer.tapscene` 字节相同。
- `fixtures/smallest-viewer.tapscene`：本静态 profile 的最少对象示例，一张 1×1 PNG、一个终点状态、scene 与 manifest。并非声称最少压缩字节。
- `fixtures/rejected-*.zip`：拒绝反例，全部是本次生成的合成数据。
- `fixtures/video/video-viewer.tapscene`：schema 2 的真实无声 H.264 SDR 过渡包，写出和读入均经完整解码。
- `fixtures/video/boundary-122-files.tapscene`：40 步、80 条视频边、120 个资产与 122 个 ZIP 文件，按边计共 60 秒；所有视频是同一份 750 ms 合成字节。主机边界检查按真实 SHA-256、尺寸和时长缓存一次完整解码结果，每个资产仍重新核验字节。
- `fixtures/video/rejected-*.tapscene`：正确重算资产与清单 hash 的视频反例；`fixtures/video/*.mp4`：本机生成的素材。
- `results.txt`：逐项结果；`fixtures/results.txt` 和 `fixtures/video/results.txt`：汇总及失败原因。
- `compiler.txt`：实际编译方式及 Java 运行时版本。

成功必须出现 `TAPSCENE_PACKAGE_CHECKS_OK` 且退出码为 0；任一反例被接受、任一正常包无法重读、任何非预期异常或编译错误都使脚本失败。检查需要支持本地符号链接的文件系统；该项无法执行会报失败，不会伪装成通过。命令找不到 Java、编译器、FFprobe 或 FFmpeg 时输出 `TAPSCENE_PACKAGE_CHECKS_NOT_RUN`，退出 77，且不会安装软件。

## 编译环境与结论边界

完整 JDK 优先走 `javac --release 17`，强制 Java 17 API、语言和字节码版本。若安装的精简运行时缺少 `javac` 启动器、但仍带 `jdk.compiler` 模块，脚本使用 `java -m jdk.compiler/com.sun.tools.javac.Main -source 17 -target 17`。后者在日志中明确标记 `Java 17 API-surface check NOT_RUN`，不把 source/target 编译等同于完整 `--release 17` 验证。

本次本地主机检查使用 Java 21.0.12.1 的已安装编译器模块（source/target 17）及 FFmpeg/FFprobe 7.1.5，静态回归、真实视频包与播放器路径均通过；Java 17 API 面检查为 `NOT_RUN`。最终提交的 JDK 17 CI 状态须单独核对。Android 平台轨道/完整解码、PNG 解码与不透明检查、导入库事务、SAF、页面操作、飞行模式与真机运行均不由桌面检查代替。

CI 也可以直接执行（JDK 17）：

```sh
mkdir -p /tmp/tapscene-checks/classes /tmp/tapscene-checks/fixtures
javac --release 17 -encoding UTF-8 -Xlint:all -Werror \
  -d /tmp/tapscene-checks/classes \
  android/app/src/main/java/com/tapscene/packageformat/*.java \
  tools/package-checks/*.java
java -Djava.awt.headless=true -cp /tmp/tapscene-checks/classes \
  PackageSecurityChecks /tmp/tapscene-checks/fixtures
```

每次运行使用新的 fixtures 目录。主类名为 `PackageSecurityChecks`，唯一参数是输出目录；它会自动执行 `VideoPackageChecks`。只重跑视频部分可执行 `java -Djava.awt.headless=true -cp <classes> VideoPackageChecks <new-output-directory>`。

## 检查内容

正常路径：

- 最少对象包、点击分支、作者编排继续、显式回访且有出口、六位小数导出、纯文本文案往返；精确 40 状态 / 40 资产 / 42 文件以及 80 条边的上限正例。
- 独立手写 ZIP 生成器构造 STORED、DEFLATE、带签名和不带签名的数据描述符，均须读回。
- 使用 JDK `ZipFile` 独立读取导出包，检查文件集合；另一个仅用于检查的 JSON reader / writer 核对字段、规范化键顺序、SHA-256、实际字节数、release 与 manifest 的关系。
- ImageIO 独立完整解码合成 PNG，核对尺寸和真实像素；独立图遍历检查可达性和所有分支可结束，按已选边检查访问历史及返回。
- 在 `readPackage` 实际读回的场景上运行 Android 共用的 `ViewerTraversal`：走完两条分支；继续动作不会自动执行；终点状态和结束动作的不同返回语义；真实历史回退；重来保留覆盖；起点即终点；显式回访达到 256 次上限后仍可结束；返回释放访问容量；拒绝错误来源动作、未知动作及伪造断点；历史 / 覆盖集合不可变并防御性复制。

拒绝反例：

- JSON 重复 / 未知键、版本不兼容、脚本 / 表达式字段、外部 URL、非白名单资产、错误 trigger、非法坐标、超过六位小数、坐标指数、非有限数、超安全整数、深度 / 节点 / 字符串 / 文件字节预算、错误 UTF-8 和不成对 UTF-16 surrogate。
- 重复 / 悬空 ID、缺图片、不可达、死路、闭合自环或强连通分量、终点有出口、热点与源状态不一致、同热点多个动作、同状态多个继续、未使用热点，及 40 状态 / 80 边 / 每状态 6 热点限额。
- ZIP 重复、路径穿越、绝对路径、Windows 路径、目录、符号链接、未声明脚本、嵌套归档、缺文件、文件数过限、local/central 视图不一致、加密、多磁盘、ZIP64、前后隐藏载荷、截断、损坏 deflate、压缩流尾载荷、异常膨胀率，以及包 / 解压预算。
- manifest 重复键 / 文件名、摘要不一致、实际文件哈希或尺寸不一致、manifest 超额。
- PNG CRC、签名、实际尺寸、尺寸炸弹、未知 / 重复 / 非法颜色元数据、APNG、隐藏尾载荷、缺 IEND、损坏 zlib、错误扫描行 filter 或膨胀像素数。PNG 深层反例重新计算资产及 manifest 摘要，避免只在外层哈希处被挡住。
- 缺失 / 符号链接资产、非空导入目的地保护、导入导出取消，以及开始写入后取消时清除本次半成品。路径穿越额外放置外部已有文件并确认其字节保持原样。

反例要求明确拒绝，不依赖错误文案。某些畸形输入会同时违反多条规则，因此单个 `PASS` 表示拒绝该输入，不表示独立证明每一个内部校验分支。这是可重复的回归语料，不替代安全审计或 Android 真机验收。


## schema 与平台边界

- 旧构造器、schema 1、`static-viewer-1`、`tapscene-android-1` 和旧 canonical JSON 保持不变。固定最小/分支包的 SHA-256 与改动前基线逐字节相同；读旧包不自动迁移版本或破坏旧 digest。
- 新包显式用 schema 2、`video-viewer-2`、`tapscene-android-1`，manifest 与 scene 的 schema 必须一致。PNG 角色是 `state-image`，无 duration；视频角色是 `transition`，路径仅 `assets/<uuid>.mp4`，`video/mp4` 和正整数 `durationMs`；边通过 nullable `transitionAssetId` 绑定。图片仍只支持 PNG，区域/脚本/外链等仍拒绝。
- 每视频真实时长 ≤10 秒，`ceil(actualUs / 1000)` 必须等于声明时长。按边引用累计 ≤60 秒，同一资产被多条边引用时重复计入。状态/边、包与解压预算仍为 40/80、50 MiB；schema 2 最多 120 个已引用资产、122 个 ZIP 文件，schema 1 仍最多 40 个图片资产。
- `ViewerPackageCodec.VideoValidator.validate(File, Asset, CancelCheck)` 是必须执行的平台信任边界。新 `readPackage`、`writePackage`、`validateDirectory` 重载接收 validator；旧重载遇视频明确拒绝。纯 Java 不实现自制 MP4/AVC parser，也不把文件后缀、MIME 或元数据 probe 当作解码成功。
- Android 回调核验真实 MP4 只有一个未加密 H.264 SDR 8-bit 4:2:0 视频轨；通过既有 Media3 解析实际 SPS 位深，并核对色度及每个样本的新 SPS，限制 Baseline/Main/Extended/High profile。缺省或未识别的色彩标签按 SDR 默认解释，明确 PQ/HLG 拒绝；这不限制普通手机录屏导入。视频无音频/字幕/数据轨、旋转或动态格式变化，核对声明尺寸与严格时长，完整解码至 EOS，尊重取消。外部引用不允许访问。全部资产通过后才能原子登记；任何失败/取消清理隔离解包区。导出回调前后仍核验视频 hash。
- 桌面回调由 FFprobe 核对真实容器、全部轨道、解码帧格式和时间线，再由 `ffmpeg -xerror -err_detect explode` 完整解码并确认 EOS 与帧数。仅允许 file 协议且关闭外部 data references，输出与运行时间有界。含音轨、非 AVC、High10、4:2:2、PQ、伪 MP4、错误尺寸/时长、超过 10 秒与损坏实际样本均重算包 hash 后测试拒绝。不能以此声称 Android 解码器也已通过。

## 视频播放器检查

`ViewerTraversal.advance` 对视频只固定 pending 边及目标，不推进当前步、历史、覆盖或完成路径。重复点击锁定；EOS 或显式 skip 才提交目标。错误保留原步，可 retry（新 `mediaRunId`）或静态前进。Back 取消本次过渡并留在来源步；Restart/Close 清除 pending；进程内不同会话的 mediaRunId 不复用。过时、重复、失败后或退出后的回调无效；播放期间替换 scene 并改动既定目标会被拒绝。静态边和历史返回语义保持旧行为。

检查覆盖上述选择/完成/错误/重试/跳过/取消/退出路径，以及 manifest/scene 版本白名单、角色引用与孤立资产、重复边引用的 60 秒边界、缺 validator 的默认拒绝、validator 抛错/取消/改写字节后的隔离清理。主机结果是回归证据，不代替 Android 交互与生命周期实测。

## 静态 AI 包的 Android 主机回流闭环

已配置 `AiRoundTripHostTest`（API 35、Robolectric 4.16.1、NATIVE graphics 和 NATIVE SQLite），实际执行结果以同次 CI 的 JUnit XML、`HOST_AI_ROUNDTRIP` 和 `TAPSCENE_AI_ROUNDTRIP_TS_OK` 为准。它沿用真实 32×48 PNG / 10×12 裁片和完整三步图，经生产 prepare、明确基线差异、commit、重新复核、封存、AI 导出，再由现有 Java 和 TS reader 读回。外部包修改未选分支标题/标签、区域层级及停留；完整分支、回访、区域和逐项效果必须保真。

检查复用取消、过期确认、重复提交与声明视频拒绝反例，逐字段核对原项目 SQL 值（包括未保存文字与动画计划），并核对原图片、输入包和旧 release 每个文件的 SHA-256。新项目加入后共享 SQLite 文件自然改变，不能称整个数据库文件字节不变。CI 保留原六次数据执行，另加这一闭环；不使用 fake PNG、默认空实现或视频解码替身。

安装既有 JDK17、Gradle8.13、Android SDK 与依赖后，可复现：

```sh
export TAPSCENE_AI_ROUNDTRIP_OUTPUT="$(mktemp -d)/result"
gradle -p android --no-daemon --max-workers=2 -PcompatibilityPreview=true \
  :app:testDebugUnitTest --tests com.tapscene.data.AiRoundTripHostTest
(cd remotion-adapter && npm ci --ignore-scripts --no-fund --no-audit && \
  node --import tsx test/roundtrip.verify.ts "$TAPSCENE_AI_ROUNDTRIP_OUTPUT")
```

输出为同一次 Android 运行的 `incoming.tapscene-ai`、`round-trip.tapscene-ai` 和预期 scene/plan。TS 步骤只消费真实输出，不另造可替代包。Robolectric 的 stock Linux shadow 不支持本流程的目录 open/fstat；仅此测试的四个 Os 调用通过限域 test-only 桥接使用真实 Linux/JDK FileChannel、文件描述符、`/proc/self/fd` 类型信息和 `FileDescriptor.sync()`，失败传播并核对关闭释放。目录与普通文件的实际同步有执行记录，但不称 Android 原生 fsync 或设备断电验证；新增模块开口只作用测试 JVM，无新增依赖。

主机真实 PNG/SQLite 执行与合成自动复核不代表真人隐私判断、真机触控/界面、MediaProjection、Surface、视频硬解码或断电恢复通过；不需要 KVM，也不启动软件模拟器。纯测试增量不改变 APK 版本19。
