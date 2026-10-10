# Android 本机托管增量检查

只使用合成 scene、PNG 和 `@example.test` 账号，不发真实邮件，不部署公网服务。

- `bash tools/hosting-checks/run-core-checks.sh`：生产 Java 任务日志/HTTP 引擎的失败恢复与安全边界，不需要 Android SDK。
- `bash tools/hosting-checks/run-contract-checks.sh`：同一组 schema 1/2/3 合成 scene 在 TypeScript 和 Java 的 canonical 字节及 SHA-256 完全一致，含中文、Unicode 与边界小数。
- `TEST_DATABASE_URL=postgres://...@127.0.0.1:5432/... bash tools/hosting-checks/run-local-integration.sh`：启动 `127.0.0.1:4173` 的实际 Fastify、独立临时 PostgreSQL schema 和生产 Java 引擎；模拟创建、资产 PUT、commit 已接受但响应丢失，重开日志恢复，核对数据库仅一个发布，再检查账号隔离、项目分页、提交后取消、撤销和登出。测试进程仅读取对应 challenge 的合成验证码文件，Android 不含此读取代码。
- `python3 tools/hosting-checks/check-manifests.py`：构建默认 debug 与 hostedDebug 后检查真实合并 Manifest，确保默认产物仍离线，开发变体仅允许回环明文。

需要 JDK 17+、Node 22.12+、根目录 `npm ci`；真实服务检查另需 PostgreSQL 和 FFmpeg。裁剪 JRE 缺少 `javac` 时使用已有 `jdk.compiler` 的 source/target 17，只检查语法/字节码目标，明确不等于 `--release 17` API 面检查。CI 使用 JDK17 的 `javac --release 17`。

Android `HostedReleaseHostTest` 通过生产 native PNG/SQLite 封存链路检查来源绑定、完整声明快照、导入版本拒绝及源项目删除隔离；合成自动确认不代表真人隐私审阅。

主机 Java 网络、native SQLite、Layoutlib 和 Manifest 静态检查均不替代真机：Android 网络安全策略实际执行、Keystore 生命周期、adb reverse、后台限制、真实进程死亡和视频解码仍需分别验证。
