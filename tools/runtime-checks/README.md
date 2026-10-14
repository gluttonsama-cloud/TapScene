# 有限云端 Android 运行检查

本入口只在一次性 GitHub `ubuntu-24.04` runner 的合成 Android 设备运行。它执行真实 Android OS、生产无障碍点击与 MediaProjection/EGL/MediaCodec 管线；不是实体手机、OEM 或性能验收。本轮固定使用已审查并完成局部编译/回归的 PR43 生产源码（`0d5c179453870cb0e16e5825e99a6d40395afadf` / tree `54b9b633d1b38b912649c6910285f59112638d51`）。本次固定云端结果见文末，不能外推实体设备；每次批准独立使用，不能沿用此前已消耗的批准。

本轮为第四次单独批准的一次硬件加速检查。维护者审核精确提交后，已仅创建 `test/approved-kvm-once-20261014-0928` 分支一次作为手动触发。workflow 只接收 `create`，唯一 job 同时核对仓库、事件、branch 类型、精确 ref 和首次 attempt；其他 ref 创建只跳过 job，分支更新和 PR 不触发。不得删除后重建该分支或重新运行；后续权限变更检查须另行授权。它不是发布分支，不生成 release，也不合并任何 PR。

旧软件模拟多轮系统 ANR/Watchdog 已阻挡完整场景；两次生产静态探针的局部通过不能证明三点击、隐藏定位点或长链通过。本轮禁用软件回退。只有新的完整 runtime 产物可以证明本轮通过。

## 本次 KVM 权限边界

- 在 setup、构建或启动 AVD 前检查官方 GitHub-hosted Ubuntu24 身份、镜像版本格式、已有 getfacl/setfacl、真实非符号链接的 `/dev/kvm` 字符设备。只接受 `root:kvm`、0660 和无命名用户/组、无额外 mask 的简单 rw/rw/none ACL，异常立即停止。
- `getfacl -p /dev/kvm` 原样保存在 RUNNER_TEMP 的 0700 私有目录/0600 文件，记录设备 inode 身份。只增加当前 runner UID 的 rw ACL：`sudo setfacl -m "u:$(id -u):rw" /dev/kvm`。不 chmod 666、不改组/udev/持久权限、不用 root 启动模拟器。
- 在构建前要求官方 emulator 的 `-accel-check` 成功且明确 KVM usable；启动前复核，使用 `-accel on`，移除 TCG 环境变量。没有软件回退、矩阵或重试。
- runtime 退出先停止自己启动的 emulator，再由 workflow 的 always 清理步骤执行 `setfacl --restore` 并逐字核对原 ACL。未确认停止、恢复失败/无法核对均显式失败；整机强制终止时只可说明依赖一次性 VM 销毁，不能声称已恢复。
- 该目录不进入 artifact；只上传通过/失败的最小诊断。新 SDK 许可仍不能自动接受。

## 旧软件模拟路线与本轮变更依据

仓库此前 [CI #16](https://github.com/gluttonsama-cloud/TapScene/actions/runs/37909197077) 使用 `-accel off`、Emulator 37.2.12 / build 16428233 和 `x86_64-35_r02.zip`，日志明确记录 `Boot completed in 410981 ms`；两个 APK 安装成功，随后旧全量 instrumentation 进程崩溃。该次没有保留 logcat，不能把崩溃归因于 KVM，也不能把它当作点击/录屏验证通过。

本入口复用该 API 35 `default;x86_64` 系统镜像和模拟器。`sdk-lock.py` 先检查 Google 官方 stable feed 中的版本、文件大小和 SHA-1，版本变化时失败，安装后再检查 `source.properties`。不升级到新镜像；本轮唯一主机权限变更为上述经批准、结束后恢复的 runner UID KVM ACL。不改 SELinux 或其他主机/网络设置。

官方将 [CPU 软件模拟](https://developer.android.com/studio/run/emulator-commandline) 标为 unsupported / very slow；旧 lane 使用其要求的 `ANDROID_I_WANT_MY_TCG=yes`；本轮明确移除。图形用同一官方模拟器内的 SwiftShader。一次成功仅证明该固定云端组合，不外推实体机兼容。

## 已有 SDK 与许可

旧 workflow 和现有 `docs.yml` 都使用 GitHub runner 的 SDK 和 `sdkmanager --install`，没有仓库新增的 `sdkmanager --licenses`。旧 run 的 runner image 为 `ubuntu24/20261004.327`，其[官方构建脚本](https://github.com/actions/runner-images/blob/ubuntu24/20261004.327/images/ubuntu/scripts/build/install-android-sdk.sh#L103-L104) 已包含组件许可输入；旧 run 的同版本 emulator/image 安装日志没有许可提示。

独立 lane 继续使用现有 JDK 17、Gradle 8.13、AGP 8.13.2、NDK 28.2.13676358、CMake 3.22.1 及已锁定 OCR 构建来源。`sdkmanager` 的 stdin 固定为 `/dev/null`，不自动答复新许可、不写入许可哈希。任何缺少的接受步骤都失败并保留安装日志，需另行判断；这不是新账号、云服务或用户设备授权流程。

## 入口与边界

- `.github/workflows/android-runtime.yml` 是独立非部署 workflow。当前连接器没有 dispatch 能力；本轮使用 GitHub 官方 [create 事件](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows#create)与精确分支/首次 attempt 门禁。仅通过已有 create_branch 能力创建审查后的精确 SHA，不扩展仓库权限、不依赖合并到 main。
- `-PtapsceneRuntimeSmoke=true -PtapsceneAbi=x86_64 -PcompatibilityPreview=true` 才选择专用测试 runner 和 `:runtime-target`。默认构建保持原配置；合成目标不属于产品依赖或手机交付。
- 不运行旧 `MediaCompatibilityInstrumentation`，不运行旧全量媒体/数据 suite；只编译并执行本入口。无新增第三方测试框架。
- 仅一个隔离 AVD、一个独立 Android 用户目录与临时 ADB 身份；均在 runner 临时目录，不使用用户账户/手机/持久调试凭据。运行只安装主 app、专用 test APK 和无网络的目标 APK。
- 先 EGL → AVC Surface → MP4 探针，随后一次生产点击链。先通过正常系统设置启用本次测试服务，再通过正常通知/录屏授权 UI。测试控制器使用 `FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES`，否则 [UiAutomation 默认会关闭被测服务](https://developer.android.com/reference/android/app/UiAutomation#FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)。不使用 `pm grant`、`appops`、`settings put`、root 或复用投影令牌。
- 固定 API35 英文系统镜像的授权控制器按确切包、资源 ID、当前应用/服务身份及期望授权内容匹配：服务主开关与快捷开关分开，通知只允许本次通知提示。新版录屏框须看到已选 `Entire screen`；若平台关闭局部共享功能，仅支持该版本完整标题/警告匹配的旧式全屏授权框，记录实际分支。按钮显示大小写不作为选择依据；未知布局或授权失败即保留证据，不用泛化正向按钮兜底。读取限制仍只在本应用和已列明的系统设置/授权窗口，不读取目标 App 节点。
- `MainActivity` 的首次进入与返回使用普通启动和有界的本应用可操作控件等待，避免 `singleTask` 复用时等待不存在的新 `onCreate`。主线程调度使用可取消协程；拖动检查共用一个截止时间。页面选择、复选框、系统授权均核对实际后续状态，动作接受本身不算通过；生产点击、录制及十二项业务断言保持原样。
- 目标 App 两个大触控区只接受真实 MotionEvent，记录事件时间与接收时间；控制 IPC 不能伪造触摸。演示按钮只有普通合成页面效果，不包含登录、支付或个人内容。
- 真实首页点击“悬浮编排”、选择合成目标、进入系统设置开启服务、确认后才创建项目并附着空浮层；通过浮层真实触摸加入三点，核对未向目标泄漏事件。浏览模式拖动的是小控制器，不是已保存点位；核对控制器实际位移、点位未变及目标零触摸。仅长等待参数通过生产 store 配置，不声称等待时长编辑 UI 已验证。随后正常生产路径隐藏整个定位层、请求同意、核对目标窗口、派发三次短按并录制。初次场景约 20 秒，含长静态等待及尾段。保留定位层曾可见的截图与真正编码的视频；截图不代替录屏。
- 实际事件顺序/时间、生产计划/执行日志、真正 MP4 样本和时长、录帧中的定位点残留、有效帧证据分别核对。不能把手势回调当成目标实际收到了点击，不能把 elapsed/uptime 强行当作源 PTS，静态像素也不证明系统没有新源帧回调。
- 生产 15 秒目标臂定时、5–7 秒录制停止边界不放宽；超时或 codec/GL 错误使本轮失败，按保存的证据修复后再决定重试。

## 资源、诊断与清理

固定 480×800、160 dpi、2 个模拟 CPU、2560 MiB guest RAM；没有矩阵、并行 AVD 或自动重试。构建独立于模拟器运行，避免编译和图形同时抢内存。总体 job 最多 50 分钟；runtime 步骤 25 分钟，其中启动最多 900 秒，单 harness 最多 480 秒，全部运行命令共同受 22 分钟预算限制，余时留给诊断和清理。

[第二次精确 CI](https://github.com/gluttonsama-cloud/TapScene/actions/runs/38061310417) 已真正启动 Android，日志为 `Boot completed in 427082 ms`。首个 APK 安装尚未完成即触及原 120 秒部署限额，未进入测试；保留的 logcat 显示 PackageInstaller 校验路径 `streamValidateAndCommit` 持锁 44.233 秒。该证据说明未完成和锁争用，不单独证明安装健康推进。部署限额因此调整为主 APK 360 秒、harness 180 秒、轻量目标 120 秒，仍共用原 22 分钟总预算。每个部署阶段保留 UTC 起始时间、APK 大小和 adb 输出；不跳过签名/包校验、不自动重试安装，也不延长任何产品运行时限。

[第三次精确 CI](https://github.com/gluttonsama-cloud/TapScene/actions/runs/38062731254) 的三个 APK 已全部安装，主 APK 用时约 132 秒。专用 runner 在写出 `onStart` 首份报告和进入 EGL 探针前被系统以启动 ANR 杀死，`exit-info` 明确为 `failed to complete startup`；EGL 探针尚未运行。当时首启 `BOOT_COMPLETED` 广播仍在派发，CPU pressure 的 10 秒均值为 88.38，系统 Phone/MediaProvider 也在 ANR/重启。下一轮只在安装后用 [Android 15 自带的广播队列同步](https://android.googlesource.com/platform/frameworks/base/+/android-15.0.0_r1/services/core/java/com/android/server/am/ActivityManagerService.java)最多等待 180 秒，要求实际返回 `All broadcast queues are idle!`，并保存 CPU/应用 ANR 诊断。此标准测试同步会在等待期间促进可运行广播处理，不能称为完全被动观察；成功仅确认广播队列，不证明整个系统已空闲或排除应用自身启动问题。不使用 `--flush-broadcast-loopers` 选项、不预编译或重启应用、不调大 OS/产品时限，仍受原总预算约束；等待超时即退出并销毁 AVD，不继续运行测试。

[第四次精确 CI](https://github.com/gluttonsama-cloud/TapScene/actions/runs/38064296590) 的广播同步成功，启动 ANR 仍发生；新增 DropBox 栈准确落在 `libz.inflate` → `DexFileLoader.OpenFromZipEntry` → `LoadedApk` 类加载 → `ActivityThread.handleBindApplication`，仍未进入 EGL 探针。该栈没有给出正在解压的具体 APK 路径。针对该采样，只有 `tapsceneRuntimeSmoke=true` 的构建启用 [AGP 官方 DEX 非压缩封装](https://developer.android.com/reference/tools/gradle-api/8.13/com/android/build/api/dsl/DexPackaging)，构建后检查主 APK 的所有 DEX 为 ZIP STORED，并保留三 APK 的 DEX 压缩方式、大小及摘要。它不改变 DEX 源码、minSDK、manifest、ART 策略或超时；默认手机构建不受影响。该测试包因此不能代表默认手机 APK 的压缩 DEX 冷启动性能，且这一采样不足以证明解压是唯一瓶颈。

[第五次精确 CI](https://github.com/gluttonsama-cloud/TapScene/actions/runs/38066025600) 已通过真实生产 EGL/AVC 探针：一个源 buffer 产生 32 个实际 MP4 样本、时长约 3.092 秒，约 1.614 秒静态观察段保留，生产校验与全部样本解码通过。随后正常 Accessibility 设置被首启已出现的 `System UI isn't responding` 对话框遮挡，未进入点击/投影场景。测试仅在此精确系统框保存 UI 树后按一次现成的 `Wait`，在结果中记录尝试和成功标志；不按 Close app，不循环清除 ANR，不处理其他应用的 ANR。旧框尚未消失时只观察，已看见设置页后再出现同一 ANR 即失败。即使后续业务断言通过，也应连同这项环境异常一起解读。

[第六次精确 CI](https://github.com/gluttonsama-cloud/TapScene/actions/runs/38067672180) 再次通过探针，正常 Wait 已成功，设置页也已恢复；日志显示仅绘制 Settings 就耗时 17.939 秒，服务子页又需 6.873 秒；从启动设置到子页显示已约 36 秒，超出原来合并冷启动/恢复/授权的 30 秒准备预算。该测试环境准备改为单次最多 120 秒，记录打开设置、实际窗口包、选服务、开关及确认动作的时间；实际服务连接仍是唯一通过依据。此阶段尚未臂定生产点击，不改产品 15 秒/停止时限，仍共用原总预算；动作拒绝、再次同类 ANR 或准备超时都不会当成连接成功。

[第七次精确 CI](https://github.com/gluttonsama-cloud/TapScene/actions/runs/38069578962) 再次遇到启动 ANR，尚未执行新的设置窗口。采样现在位于 `DexFileVerifier`，CPU pressure 约 86%，不能把非压缩 DEX 当作已解决环境瓶颈。日志也记录旧 `android.test.runner/mock/base` 三库的重复类加载上下文失配：[AGP 8.13.2 官方源码包](https://dl.google.com/dl/android/maven2/com/android/tools/build/builder/8.13.2/builder-8.13.2-sources.jar)的测试 manifest 模板会自动注入 runner，而[这组库只服务已弃用 JUnit3 类](https://developer.android.com/training/testing/instrumented-tests/androidx-test-libraries/test-setup)。专用入口继承 `android.app.Instrumentation`，不引用这些类，因此仅 opt-in test manifest 移除未用声明，并用 `aapt2` 检查已打包 test APK，默认测试/手机 manifest 保持原样。不会关闭 ART 验证、重写业务或放宽系统阈值；若仍被环境启动 ANR/授权界面阻挡且没有新代码故障，则保留失败证据，不靠反复重跑凑通过。

[首次精确 CI](https://github.com/gluttonsama-cloud/TapScene/actions/runs/38060445119) 已完成三个 APK 构建，但在模拟器加载阶段发现 `libpulse.so.0` 缺失，未启动 AVD。独立 lane 因此只从 [Ubuntu 官方仓库](https://packages.ubuntu.com/noble/libpulse0)安装 `libpulse0` 及其必要依赖，保存 `ldd` 诊断并用官方启动器的 `-version` 实际加载结果把关；裸 `ldd` 不代表启动器设置的内置库路径。`-no-audio` 不能免除 ELF 加载依赖。没有安装音频服务、全套桌面或新的模拟器版本。

无论通过、断言失败还是进程崩溃，保留 7 天的 `android-runtime-smoke` artifact：准确提交/tree、SDK/构建日志、emulator 全日志、运行期全 buffer logcat、crash buffer、app exit-info、服务状态，以及由 `run-as` 只读取本次合成测试目录的 `app-evidence.tar`。它包含真实视频和测试断言，未包含 SDK/AVD 用户盘或 ADB 私钥。

退出处理先取诊断，再停止测试 app、关闭模拟器/ADB，最后只删除本轮固定临时目录。超时不报告成功；只有专用 runner 完成全部断言的 `TAPSCENE_RUNTIME_SMOKE_OK` 才通过。GitHub 强制终止整机时无法保证退出钩子运行，runner 销毁仍会清除一次性设备；不能把缺失清理日志称作已核验清理。

此前状态：[第三次单独批准的加速运行](https://github.com/gluttonsama-cloud/TapScene/actions/runs/38535382196) 已结束，精确提交 `7854ecbcd56f6f180a2f59c5ed7053f4f7329d7a`。KVM、正常设置启用服务、真实首页目标选择、空定位浮层及生产 EGL/AVC 探针通过，十二项中前四项通过；首个实际浮层触点未保存，后续拖动、生产点击及投影录制没有执行。完整模拟器进程组停止、AVD 删除与原 ACL 精确恢复均有产物证据，批准和分支均不能复用。

局部主机对照已确认生产浮层重建会自行发出窗口状态事件，经明确的 AOSP 服务端显示器映射桥接后可把守卫改为 Blocked 并阻止保存；同一计划/触摸不传入这些自有事件时可保存。候选只在精确自有根抑制该公告，收到的未知/非目标窗口仍阻断，并补充保存结果分类与保守清账。主机回归不代替 Android 服务端路由；第三次产物没有保存异常分类，不能追认该次设备失败的确定根因。TalkBack 自动发现/播报与原子替换后的 fsync 故障仍未验；完整点击/录屏的后续实测见下文，局部主机通过不代替设备验收。

[第四次单独批准的检查](https://github.com/gluttonsama-cloud/TapScene/actions/runs/38543050905) 已结束，精确提交 `6af82b010148fecc1ef5c0afb7f07f47234eb75c` / tree `a66604f5c3c11193390e4e9a9a02b20fef1dc1e0`。十二项中十一项通过：真实入口、三次浮层加点、小控制器拖动、三次目标 DOWN/UP、等待时长、定位层移除、生产 MP4 封口登记及全部 600 样本解码/无定位点残留均通过。最终完整点击链关联失败，首个 Before 为 `EncoderUnmatched`。其余五个边界可由生产 `readCandidate` 解析；最终逐 PNG 的目标状态/定位点检查在完整路线断言后，故本次没有执行，不能追认通过。模拟器进程组停止、AVD 删除与原 ACL 精确恢复均已核实。第四次批准已消耗，不覆盖之后的运行。

本次 `anchors.json` 未导出缺失边界的 ticketId，不能区分“选边界时最新 FBO 尚未成功呈现”和“已有 ticket 的原始编码样本最后未精确匹配”。这两条可能路径由独立主机交错回归保留；当前没有据此修改生产时序或映射。后续专用 harness 仅补出已有 `ticketId` / `storedMissing`，不导出私有路径、节点内容或全量证据清单。

可离线运行 `python3 tools/runtime-checks/inspect-captured-anchors.py <解包后的 app/files/runtime-smoke>`。它按原视频 SHA、完整解码顺序、实际容器 tick 与明确样本序号核对导出数据，并读取已有锚点对应的合成状态，不使用最近 PTS 或 uptime 换算。本次五个已有边界准确落在样本 10 / 249 / 495，状态分别为 1 / 2 / 3；600 个样本全部解码。输出始终保留首个 Missing、`allBoundaryBindingsPresent=false` 与 `privateSameFboPngsVerified=false`。这些是有损 MP4 的离线一致性检查，不是原 FBO PNG 的替代，更不能补出首个源身份或给 runtime 改判通过。
