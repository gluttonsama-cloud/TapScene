# TapScene 独立动画消费者

受信 React / TypeScript / Remotion 模板。Android 只导出 `.tapscene-ai` 纯数据；电脑上的此目录校验包并实际渲染 MP4。模板、依赖和执行入口不在数据包内，也不接受包内脚本、表达式、动态组件、字体文件或外部媒体 URL。

## 运行

需要 Node 22+（CI 锁定 24.19.0）、FFmpeg/FFprobe、Chromium 的系统依赖及中文字体。安装 npm 锁文件依赖后运行：

```sh
cd remotion-adapter
npm ci --ignore-scripts --no-fund --no-audit
npm run check
npm run render -- /path/to/fixed-release.tapscene-ai /path/to/new-output.mp4
```

首次执行按 Remotion 官方机制下载锁定的 Chrome Headless Shell。输出路径和旁边的 `.json` 报告必须不存在。报告记录 release/contentDigest、计划摘要、成片摘要、真实帧数、画布、帧率、音轨数和完整解码结果。失败不发布半成品。无外部 AI、账号或远程渲染服务。

## 输入与资源边界

- 只接受 ZIP 内的 `manifest.json`、`scene.json`、`render-plan.json`、`README.txt`、`schema.json` 和受控 UUID 资产路径；包与解压量各 ≤50 MiB。拒绝穿越、链接、加密、重复项、ZIP64、评论、额外字段和未声明载荷。
- 使用消费者内置受信 schema。包里的 schema/README 只作为说明与摘要对象，不执行、不递归解析外部引用。严格 JSON 拒绝重复键、未知字段、非有限数、指数、过深结构及不合法 Unicode。
- 完整图保留全部分支和安全媒体。计划只绑定 `releaseId + contentDigest`；更换画布、路径和效果不改变已封存 scene。最多 40 状态、80 边、每状态 6 热点、80 个区域（每状态 12 个）、200 资产。
- 30 fps；1080×1920 / 1920×1080；最多 256 次有限访问和 256 效果；单次停留 1–1800 帧，总长 ≤18000 帧。重访状态必须使用新 visitId；从起点沿真实边到明确终点。时间轴重新推导并逐项比对，每次访问的入场与退场叠化合计小于其停留，保留至少一帧独立停留，同一帧最多两个访问。
- 每个 PNG 实际解压、检查元数据、CRC、尺寸和不透明像素；裁片逐行对比安全底图的 bbox 像素。正确 hash 不允许替换成另一张绘制或生成的图。
- 视频限唯一无声音 H.264 SDR 8-bit 4:2:0 track，单段 ≤10 秒、绑定总量 ≤60 秒。FFprobe 逐帧核对时序/格式，FFmpeg 完整解码到 EOS。外部数据引用禁用。
- 渲染并发 2、媒体与视频缓存各 64 MiB、单帧等待 30 秒、整体渲染 30 分钟取消上限。输出需再次真实计帧及完整解码。

## 呈现语义

安全截图始终来自原有复核图片。focus 可抬起真实裁片，底图仍保留原像素；highlight/click 显示明确边框，annotation 只显示纯文本。周围用统一中性画布。没有“原生组件拆解”或遮挡后背景恢复；没有干净底板时不挪走底图内容。手机不运行 Remotion。

## 验证与许可

`tools/package-checks/AiPackageChecks.java` 生成标明“合成样例”的三步分支/回访图与真实 PNG 裁片。CI 用同一 sealed scene 的两份计划输出竖横 MP4、报告和抽帧，再由人/助手看实际画面。主机 Compose 图片只用于 Android 布局审查，不能代替动画或设备验收。

2026-10-09 核对 Remotion 官方 [许可 FAQ](https://www.remotion.dev/docs/license/faq)、[软件许可](https://github.com/remotion-dev/remotion/blob/main/packages/core/LICENSE.md) 及安装的 4.0.534 LICENSE：个人或最多 3 人组织可使用 Free License（包括商业用途），无需注册；用户明确的两人团队在该范围内。Remotion 是 source-available，不是 Apache/MIT。采用 Node 服务端渲染，官方说明该路径无自动遥测；没有启用浏览器客户端渲染或付费服务。团队/使用范围变化时重新核对；不自动购买或接受新显式商业协议。第三方组件与编解码器保留各自许可。

当前助手受限执行器无法读取系统网络接口，Remotion 返回 `uv_interface_addresses`；没有修改系统安全/网络设置或猴子补丁。标准 GitHub runner 是本增量的实际渲染验证位置，实际完成状态以对应 CI/产物为准。
