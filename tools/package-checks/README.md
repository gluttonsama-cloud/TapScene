# 静态观看包离线安全检查

这组检查直接编译 `com.tapscene.packageformat` 的纯 Java 代码。无需 Gradle、Android SDK、网络、测试框架或额外安装。合成画面仅含一个 `#336699` 不透明像素，无用户数据。AWT / ImageIO 只用于桌面检查，未进入 Android 源码。

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
- `results.txt`：逐项结果；`fixtures/results.txt`：汇总及失败原因。
- `compiler.txt`：实际编译方式及 Java 运行时版本。

成功必须出现 `TAPSCENE_PACKAGE_CHECKS_OK` 且退出码为 0；任一反例被接受、任一正常包无法重读、任何非预期异常或编译错误都使脚本失败。检查需要支持本地符号链接的文件系统；该项无法执行会报失败，不会伪装成通过。命令找不到 Java 或编译器时输出 `TAPSCENE_PACKAGE_CHECKS_NOT_RUN`，退出 77，且不会安装软件。

## 编译环境与结论边界

完整 JDK 优先走 `javac --release 17`，强制 Java 17 API、语言和字节码版本。若安装的精简运行时缺少 `javac` 启动器、但仍带 `jdk.compiler` 模块，脚本使用 `java -m jdk.compiler/com.sun.tools.javac.Main -source 17 -target 17`。后者在日志中明确标记 `Java 17 API-surface check NOT_RUN`，不把 source/target 编译等同于完整 `--release 17` 验证。

本次本地实际运行：Java 21.0.12.1 的已安装编译器模块，source/target 17；148 项全部通过，输出 `TAPSCENE_PACKAGE_CHECKS_OK: passed=148 failed=0`。完整 Java 17 API / Java 17 运行时验证仍待 JDK 17 CI。Android 平台 PNG 解码、像素不透明检查、导入库事务、SAF 导入导出、页面操作、飞行模式播放及真机运行均不由这组桌面检查代替。

CI 也可以直接执行（JDK 17）：

```sh
mkdir -p /tmp/tapscene-checks/classes /tmp/tapscene-checks/fixtures
javac --release 17 -encoding UTF-8 -Xlint:all -Werror \
  -d /tmp/tapscene-checks/classes \
  android/app/src/main/java/com/tapscene/packageformat/*.java \
  tools/package-checks/PackageSecurityChecks.java
java -Djava.awt.headless=true -cp /tmp/tapscene-checks/classes \
  PackageSecurityChecks /tmp/tapscene-checks/fixtures
```

每次运行使用新的 fixtures 目录。主类名为 `PackageSecurityChecks`，唯一参数是输出目录。

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
