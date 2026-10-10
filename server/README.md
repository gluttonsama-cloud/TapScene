# 本地托管与网页观看

Fastify + PostgreSQL + 私有文件存储。只接受 loopback 和 `@example.test` 合成账号；验证码写入本机私有收件箱，不发邮件。不是公网部署方案。Android 发布入口尚未接线。

## 启动

需要 Node 22.12+、PostgreSQL 17、FFmpeg/FFprobe。以下命令从仓库根目录执行：

```sh
npm ci
npm run build:viewer
# 自备本机 PostgreSQL，或：docker compose -f server/compose.local.yml up -d
export DATABASE_URL=postgres://tapscene:tapscene-local-only@127.0.0.1:5432/tapscene
npm run migrate:hosted
npm run start:hosted
```

另一个终端执行 `npm run fixture:hosted`，读取输出的本机观看链接。这个开发客户端生成纯合成 PNG，通过邮箱验证码、创建项目、scene 声明、逐资产上传和 commit API 发布，未直接插入演示数据库。私有收件箱和回执位于 `server/.local-data/`。全部数据可在停止服务后删除；不要放入真实用户数据。

`PORT` 默认为 4173；`HOST` 只允许 `127.0.0.1`。`TAPSCENE_DATA_DIR`、`TAPSCENE_WEB_DIR` 可覆盖私有存储和已构建播放器目录。`NODE_ENV=production`、非本机数据库或非本机请求会被拒绝。开发密钥为公开固定合成值，绝不能迁移到生产。

## 边界与恢复

- 按 [既定 API](../docs/architecture.md#http-api) 实现 scene + 逐资产上传，没有 ZIP 上传或浏览器解包；只接受内置观看 profile 1/2/3。
- 上传与 commit 各保留幂等键；缺失单资产可重传。`202 validating` 不是成功，需查询上传状态取得正式回执。
- PostgreSQL 项目行锁维护五个有效版本/预留槽位，上传预留一小时；1/7/30 天有效期从正式提交开始。内容和期限不可更新，撤销追加持久记录。
- commit 意图、校验租约和重试状态持久化；进程重开恢复未完成校验。实际媒体完整校验后写入不可变私有文件，短事务登记版本。无法确认文件/数据库时不返回成功。
- HTML、manifest、图片、视频及 HEAD/Range 每次读取数据库授权，媒体不经公共静态目录。响应禁止缓存；前台恢复重新授权。已授权传输与已获取的副本不能远程收回。
- 网页播放支持热点分支、实际历史返回、重来、视频失败重试/跳过；目标图片实际解码后才推进。没有离线 service worker、第三方统计或外链媒体。

## 检查

```sh
npm run check:hosted
npm run build:viewer
TEST_DATABASE_URL="$DATABASE_URL" npm run test:hosted
npm run test:browser -w web-player
TEST_DATABASE_URL="$DATABASE_URL" npm run test:browser -w server
```

PostgreSQL 测试使用独立临时 schema，结束后清理。缺少 `TEST_DATABASE_URL` 明确为 NOT_RUN。浏览器脚本使用 Playwright 官方 headless shell（先 `npx playwright install chromium`，`CHROMIUM_PATH` 可覆盖），自身启动本机预览，仅使用合成响应；`server` 的浏览器检查另建隔离 PostgreSQL schema，通过真实 HTTP 上传和提交，再验证浏览器分支/返回/手机版与服务端撤销。

真实邮件、云 PostgreSQL/S3、HTTPS 公网服务、资源隔离解码容器、生产备份/撤销水位恢复和对象保留清理尚未实现。生产服务商、地区、预算及数据保留/删除策略仍需确认。开发文件适配器不承诺数据库与文件的跨介质灾备一致性。暂存/孤儿文件按本地 1 GiB 总预算阻止继续写入，尚无自动保留清理；容量用尽应停止服务后重置合成开发数据。
