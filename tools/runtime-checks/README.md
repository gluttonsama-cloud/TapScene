# 有限云端 Android 运行检查

本入口只在一次性 GitHub `ubuntu-24.04` runner 的合成 Android 设备运行。它执行真实 Android OS、生产无障碍点击与 MediaProjection/EGL/MediaCodec 管线；不是实体手机、OEM 或性能验收。应用仍为开发版本 31，产品代码、权限和超时不因测试修改。

## 为什么保留软件模拟路线

仓库此前 [CI #16](https://github.com/gluttonsama-cloud/TapScene/actions/runs/37909197077) 使用 `-accel off`、Emulator 37.2.12 / build 16428233 和 `x86_64-35_r02.zip`，日志明确记录 `Boot completed in 410981 ms`；两个 APK 安装成功，随后旧全量 instrumentation 进程崩溃。该次没有保留 logcat，不能把崩溃归因于 KVM，也不能把它当作点击/录屏验证通过。

本入口复用该 API 35 `default;x86_64` 系统镜像和模拟器。`sdk-lock.py` 先检查 Google 官方 stable feed 中的版本、文件大小和 SHA-1，版本变化时失败，安装后再检查 `source.properties`。不升级到新镜像，不改 `/dev/kvm`、SELinux、主机权限或网络安全设置。

官方将 [CPU 软件模拟](https://developer.android.com/studio/run/emulator-commandline) 标为 unsupported / very slow；使用其要求的 `ANDROID_I_WANT_MY_TCG=yes`。图形用同一官方模拟器内的 SwiftShader。一次成功仅证明该固定云端组合，不外推实体机兼容。

## 已有 SDK 与许可

旧 workflow 和现有 `docs.yml` 都使用 GitHub runner 的 SDK 和 `sdkmanager --install`，没有仓库新增的 `sdkmanager --licenses`。旧 run 的 runner image 为 `ubuntu24/20261004.327`，其[官方构建脚本](https://github.com/actions/runner-images/blob/ubuntu24/20261004.327/images/ubuntu/scripts/build/install-android-sdk.sh#L103-L104) 已包含组件许可输入；旧 run 的同版本 emulator/image 安装日志没有许可提示。

独立 lane 继续使用现有 JDK 17、Gradle 8.13、AGP 8.13.2、NDK 28.2.13676358、CMake 3.22.1 及已锁定 OCR 构建来源。`sdkmanager` 的 stdin 固定为 `/dev/null`，不自动答复新许可、不写入许可哈希。任何缺少的接受步骤都失败并保留安装日志，需另行判断；这不是新账号、云服务或用户设备授权流程。

## 入口与边界

- `.github/workflows/android-runtime.yml` 是独立非部署 workflow：相关录制/点击/测试路径的 PR 变更触发，也定义 `workflow_dispatch`。新 workflow 的手动入口受 GitHub [默认分支要求](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows#workflow_dispatch) 限制，不能假设未进入默认分支便有 UI 按钮。
- `-PtapsceneRuntimeSmoke=true -PtapsceneAbi=x86_64 -PcompatibilityPreview=true` 才选择专用测试 runner 和 `:runtime-target`。默认构建保持原配置；合成目标不属于产品依赖或手机交付。
- 不运行旧 `MediaCompatibilityInstrumentation`，不运行旧全量媒体/数据 suite；只编译并执行本入口。无新增第三方测试框架。
- 仅一个隔离 AVD、一个独立 Android 用户目录与临时 ADB 身份；均在 runner 临时目录，不使用用户账户/手机/持久调试凭据。运行只安装主 app、专用 test APK 和无网络的目标 APK。
- 先 EGL → AVC Surface → MP4 探针，随后一次生产点击链。先通过正常系统设置启用本次测试服务，再通过正常通知/录屏授权 UI。测试控制器使用 `FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES`，否则 [UiAutomation 默认会关闭被测服务](https://developer.android.com/reference/android/app/UiAutomation#FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)。不使用 `pm grant`、`appops`、`settings put`、root 或复用投影令牌。
- 目标 App 两个大触控区只接受真实 MotionEvent，记录事件时间与接收时间；控制 IPC 不能伪造触摸。演示按钮只有普通合成页面效果，不包含登录、支付或个人内容。
- 正常生产路径显示定位点、隐藏整个定位层、请求同意、核对目标窗口、派发一次短按并录制。初次场景约 20 秒，含长静态等待及尾段。保留定位层曾可见的截图与真正编码的视频；截图不代替录屏。
- 实际事件顺序/时间、生产计划/执行日志、真正 MP4 样本和时长、录帧中的定位点残留、有效帧证据分别核对。不能把手势回调当成目标实际收到了点击，不能把 elapsed/uptime 强行当作源 PTS，静态像素也不证明系统没有新源帧回调。
- 生产 15 秒目标臂定时、5–7 秒录制停止边界不放宽；超时或 codec/GL 错误使本轮失败，按保存的证据修复后再决定重试。

## 资源、诊断与清理

固定 480×800、160 dpi、2 个模拟 CPU、2560 MiB guest RAM；没有矩阵、并行 AVD 或自动重试。构建独立于模拟器运行，避免编译和软件图形同时抢内存。总体 job 最多 50 分钟；runtime 步骤 25 分钟，其中启动最多 900 秒，单 harness 最多 480 秒，全部运行命令共同受 22 分钟预算限制，余时留给诊断和清理。

[第二次精确 CI](https://github.com/gluttonsama-cloud/TapScene/actions/runs/38061310417) 已真正启动 Android，日志为 `Boot completed in 427082 ms`。首个 APK 安装尚未完成即触及原 120 秒部署限额，未进入测试；保留的 logcat 显示 PackageInstaller 校验路径 `streamValidateAndCommit` 持锁 44.233 秒。该证据说明未完成和锁争用，不单独证明安装健康推进。部署限额因此调整为主 APK 360 秒、harness 180 秒、轻量目标 120 秒，仍共用原 22 分钟总预算。每个部署阶段保留 UTC 起始时间、APK 大小和 adb 输出；不跳过签名/包校验、不自动重试安装，也不延长任何产品运行时限。

[第三次精确 CI](https://github.com/gluttonsama-cloud/TapScene/actions/runs/38062731254) 的三个 APK 已全部安装，主 APK 用时约 132 秒。专用 runner 在写出 `onStart` 首份报告和进入 EGL 探针前被系统以启动 ANR 杀死，`exit-info` 明确为 `failed to complete startup`；EGL 探针尚未运行。当时首启 `BOOT_COMPLETED` 广播仍在派发，CPU pressure 的 10 秒均值为 88.38，系统 Phone/MediaProvider 也在 ANR/重启。下一轮只在安装后用 [Android 15 自带的广播队列同步](https://android.googlesource.com/platform/frameworks/base/+/android-15.0.0_r1/services/core/java/com/android/server/am/ActivityManagerService.java)最多等待 180 秒，要求实际返回 `All broadcast queues are idle!`，并保存 CPU/应用 ANR 诊断。此标准测试同步会在等待期间促进可运行广播处理，不能称为完全被动观察；成功仅确认广播队列，不证明整个系统已空闲或排除应用自身启动问题。不使用 `--flush-broadcast-loopers` 选项、不预编译或重启应用、不调大 OS/产品时限，仍受原总预算约束；等待超时即退出并销毁 AVD，不继续运行测试。

[第四次精确 CI](https://github.com/gluttonsama-cloud/TapScene/actions/runs/38064296590) 的广播同步成功，启动 ANR 仍发生；新增 DropBox 栈准确落在 `libz.inflate` → `DexFileLoader.OpenFromZipEntry` → `LoadedApk` 类加载 → `ActivityThread.handleBindApplication`，仍未进入 EGL 探针。该栈没有给出正在解压的具体 APK 路径。针对该采样，只有 `tapsceneRuntimeSmoke=true` 的构建启用 [AGP 官方 DEX 非压缩封装](https://developer.android.com/reference/tools/gradle-api/8.13/com/android/build/api/dsl/DexPackaging)，构建后检查主 APK 的所有 DEX 为 ZIP STORED，并保留三 APK 的 DEX 压缩方式、大小及摘要。它不改变 DEX 源码、minSDK、manifest、ART 策略或超时；默认手机构建不受影响。该测试包因此不能代表默认手机 APK 的压缩 DEX 冷启动性能，且这一采样不足以证明解压是唯一瓶颈。

[第五次精确 CI](https://github.com/gluttonsama-cloud/TapScene/actions/runs/38066025600) 已通过真实生产 EGL/AVC 探针：一个源 buffer 产生 32 个实际 MP4 样本、时长约 3.092 秒，约 1.614 秒静态观察段保留，生产校验与全部样本解码通过。随后正常 Accessibility 设置被首启已出现的 `System UI isn't responding` 对话框遮挡，未进入点击/投影场景。测试仅在此精确系统框保存 UI 树后按一次现成的 `Wait`，在结果中记录尝试和成功标志；不按 Close app，不循环清除 ANR，不处理其他应用的 ANR，不延长原 30 秒 UI 界限。旧框尚未消失时只观察，已看见设置页后再出现同一 ANR 即失败。即使后续业务断言通过，也应连同这项环境异常一起解读。

[首次精确 CI](https://github.com/gluttonsama-cloud/TapScene/actions/runs/38060445119) 已完成三个 APK 构建，但在模拟器加载阶段发现 `libpulse.so.0` 缺失，未启动 AVD。独立 lane 因此只从 [Ubuntu 官方仓库](https://packages.ubuntu.com/noble/libpulse0)安装 `libpulse0` 及其必要依赖，保存 `ldd` 诊断并用官方启动器的 `-version` 实际加载结果把关；裸 `ldd` 不代表启动器设置的内置库路径。`-no-audio` 不能免除 ELF 加载依赖。没有安装音频服务、全套桌面或新的模拟器版本。

无论通过、断言失败还是进程崩溃，保留 7 天的 `android-runtime-smoke` artifact：准确提交/tree、SDK/构建日志、emulator 全日志、运行期全 buffer logcat、crash buffer、app exit-info、服务状态，以及由 `run-as` 只读取本次合成测试目录的 `app-evidence.tar`。它包含真实视频和测试断言，未包含 SDK/AVD 用户盘或 ADB 私钥。

退出处理先取诊断，再停止测试 app、关闭模拟器/ADB，最后只删除本轮固定临时目录。超时不报告成功；只有专用 runner 完成全部断言的 `TAPSCENE_RUNTIME_SMOKE_OK` 才通过。GitHub 强制终止整机时无法保证退出钩子运行，runner 销毁仍会清除一次性设备；不能把缺失清理日志称作已核验清理。

当前状态：三个 APK 编译/安装、隔离 Android 启动及真实生产 EGL/AVC 单源帧静态探针已通过；正常系统授权后的生产点击/投影场景仍待验证。局部通过不等于整条 runtime 通过，也不替代实体手机 120 秒静态录制、生命周期和 OEM 兼容检查。
