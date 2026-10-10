# TapScene 网页观看端

只读固定版本播放器。React 19.3.0 / Vite 7.3.7，导入浏览器安全的 `@tapscene/runtime-ts`，不包含 Android 原素材、作者账号或发布管理功能。

## 本机启动

在仓库根目录先安装 workspace 依赖，然后：

```sh
npm run build:viewer
npm run start:hosted
```

由服务端鉴权后的 `/s/{shareToken}` 页面加载 `web-player/dist/index.html`。构建产物中的通用 JS / CSS 在 `/player-assets/`，可与受保护内容分开缓存。开发模式 `npm run dev -w web-player` 只绑定 `127.0.0.1`；manifest 和媒体代理到 `http://127.0.0.1:4173`。必须使用真实 `/s/{shareToken}` 路径，首页不伪造演示。

观看 API：

- `GET /s/{shareToken}/manifest` → `{releaseId,contentDigest,versionOrdinal,expiresAt,scene}`；`expiresAt` 使用服务端 ISO 日期字符串。
- 所有图片 / video 的 `src` 仅使用 `/s/{shareToken}/assets/{assetId}`，不使用 scene 内的文件路径或外链。
- 观看端接受 404 / 410 / 503 等访问失败并显示不含受保护内容的状态页。服务端仍须对 HTML、manifest、媒体、HEAD、Range 每次独立鉴权，响应 `Cache-Control: private, no-store`、`Referrer-Policy: no-referrer`。

## 播放与恢复

- 沿用 Android ShellTheme 的暖白 / 墨色 / 朱红色板。当前画面按原比例显示；热点使用画面归一化坐标，文字操作按钮提供完整键盘替代入口。
- 手机宽度默认将画面与底部操作适配到当前视口，上一步 / 重新开始无需翻过整张长图；可明确放大画面滚动查看，导航仍固定可用。长讲解通过展开入口查看，文字动作不会因热点缩小而丢失。
- 实际目标图片完成加载和解码后才提交 runtime 返回的新状态 / 访问历史，并展示同一个已解码的 Image 元素。加载失败保持源步骤，可重试；取消、重来和页面隐藏使迟到的异步回调失效。
- 视频完整结束或明确跳过后才准备目标画面；播放失败可重试或跳过。事件携带捕获的 `mediaRunId`，旧视频不能推进新流程。
- “上一步”沿本次实际访问历史；“重新开始”回起点。无浏览器 localStorage、sessionStorage 或持久断点。
- 页面隐藏 / pagehide 时同步遮住全部保护内容、清空图片并停止视频。再可见和 bfcache 恢复后先重新验证 manifest，再加载当前画面。临时错误也失败关闭，不显示缓存内容；视频在恢复时取消至源步骤。
- fetch 使用 `cache: no-store`、`credentials: omit`、`redirect: error`、`referrerPolicy: no-referrer`。无第三方请求、统计、远程字体、service worker、整包预取或媒体预加载。
- 前台到期时间触发重新验证；客户端时钟已超过返回期限时隐藏内容。服务器授权是最终访问边界，已获得的数据或截图无法从接收者设备撤回。

## 检查

```sh
npm run check -w web-player
npm run build -w web-player
npm run test:browser -w web-player
```

`check` 包含 TypeScript 和独立 controller/API 测试，覆盖解码前不记历史、重复点击、取消后迟到图片、真实分支返回、坏图重试、视频失败/重试/跳过/旧 EOS、隐藏恢复及 404/410/503 失败关闭。

`test:browser` 需要 Playwright Chromium Headless Shell（`npx playwright install --only-shell chromium`；或用 `CHROMIUM_PATH` 指定本机 Chromium）、FFmpeg 和允许本机监听的执行环境。它在同一进程启动合成 fixture 网关，使用浏览器实际 PNG 解码、可用时的 H.264 解码、键盘/热点操作、390 / 320 宽度及生命周期事件回归，截图保存在 `web-player/playwright-results/`。这是观看端的本机浏览器检查，不替代真实服务端数据库 / 邮件 / 对象存储 / 设备验收，也不证明实际用户浏览器兼容性。若 Chromium 启动被环境限制，须明确记录未执行，不能把脚本存在当作通过。

本轮 Chromium Headless Shell 实测不提供 H.264 解码，真实 AVC 播放完成（EOS）为 `NOT_RUN`；浏览器已验证失败 / 重试 / 跳过，纯 runtime 已验证 EOS 与旧回调。生命周期测试触发真实应用的 visibilitychange / pageshow 处理器，不等于真机后台恢复或真实 BFCache 验收。
