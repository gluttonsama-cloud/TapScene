# TapScene（点演）v0.1 技术架构提案

- 状态：PROPOSED，供后续规范 PR 讨论，未批准、未实现、未做真机验证。
- 日期：2026-10-09。
- 输入依据：《点演 产品规格说明 v0.1》，重点覆盖 F01–F29、A01–A16。
- 命名：正式英文名 TapScene，中文名点演，仓库名 TapScene。Android applicationId、组织命名空间与域名仍待确定；不能据仓库名推断所有权或已注册状态。
- 本轮边界：将完整基线与既有架构/工程提案纳入规范 Draft PR，并加入文档检查；不写产品实现，不开通托管或渲染服务。远端状态与适配见[入库记录](../engineering/repository-adaptation.md)。

## 1. 推荐结论

采用“Android 本地创作 + 纯数据有限状态演示 + 可选托管 + 独立桌面动画适配器”的架构。原始素材、原始 OCR、编辑历史、遮挡前像素只进入本机私有域；同一安全编译管线产生不可变交付版本，供离线包、网页观看和 AI 工程包复用。

明确推荐栈如下。这里选择技术方向；依赖的精确版本、最低兼容系统、浏览器矩阵在风险验证后锁定，不把文档示例版本当成兼容结论。

| 子系统 | 推荐栈 | 选择理由与限制 |
|---|---|---|
| Android 创作与离线播放器 | Kotlin、Jetpack Compose、Navigation、ViewModel、Coroutines/Flow；Room；kotlinx.serialization；手动构造注入 | 符合原生 Android 范围，直接使用系统媒体和文件能力；领域模块保持纯 Kotlin，不依赖 Compose/Room/Android SDK |
| 媒体读取与安全输出 | MediaExtractor/MediaCodec 处理时间戳和受限解码；Media3 ExoPlayer 播放；Media3 Transformer + 固定不透明 GL 遮挡效果导出；静图 Bitmap/Canvas 重编码 | 不引入整个移动端视频编辑引擎。必须验证所有导出实际经过像素管线，不能把预览覆盖层或裁剪指令当成脱敏 |
| 本地辅助分析 | Kotlin 规则、降采样画面变化/相似度；随安装打包的中英文 OCR，首选 ML Kit Text Recognition，受 V02 门禁约束 | 推荐捆绑模型以避免首用下载阻断。OCR 只是候选来源；SDK 非必要指标外传必须能禁用并实测达标，否则替换可控端侧实现，不能仅靠披露放行，见第 5 节 |
| 长任务 | 持久化 Job 状态机 + Coroutines；WorkManager 用于可恢复调度；需要时使用符合系统约束的前台执行 | WorkManager 是调度器，不能作为“永不中断”的保证；恢复单位为阶段或单资产，而非任意视频帧 |
| Web 播放器 | TypeScript、React、Vite；浏览器原生图片/video；独立纯 TS 播放状态机 | 网页只做观看；无编辑器、无任意脚本解释器、无原 App 接口；不使用 Remotion Player 取代交互图 |
| API 与后台任务 | TypeScript、Node.js、Fastify；PostgreSQL；SQL 迁移；同一代码库的 API/worker 两种进程入口 | 模块化单体，避免微服务、消息总线和分布式事务。worker 只隔离媒体校验和清理，不形成独立业务服务 |
| 对象存储与访问 | 私有 S3 兼容存储，TLS 反向代理；经 API 网关授权后流式读；数据库控制发布状态 | 不把对象存储下载链接或 CDN 公开 URL 交给观看者。P0 无公开媒体 CDN、无 Redis 授权缓存 |
| 账号 | 邮箱一次性验证码；服务端可撤销会话；事务型邮件供应商通过端口接入 | 只服务发布管理；供应商、部署地区和保留政策需上线前确定，本轮不代为开通 |
| 契约与测试 | JSON Schema Draft 2020-12；OpenAPI 3.1；共享 fixtures；TS Ajv 2020 校验；Kotlin 显式 DTO/校验器；Vitest、Playwright、JUnit/Compose 测试 | Schema 是交换格式权威；生成类型不替代运行时与图语义校验。具体库版本进入锁文件后验证 |
| 动画适配器 | 独立 TypeScript/React/Remotion 工程，本地 Node/Chromium 渲染 | 固定 Composition ID、适配器版本和效果白名单；不在 Android、API 进程或导入的数据包内运行 |

基础运行拓扑只需：一个 Android 安装包；一个 Web 静态构建；一个 API/worker 构建；一套 PostgreSQL；一个私有对象存储；一个独立 Remotion 模板。先保证单实例和故障恢复，扩容通过无状态 API 多副本及数据库任务租约完成，不提前拆微服务。

## 2. 五层视图：职责分层与依赖反转

“基础设施、数据、业务、接口、交互”是解释系统的五个视角，不是五台服务器，也不是要求每次调用机械地逐层穿越。

| 层 | 职责 | 代表内容 | 不应承担 |
|---|---|---|---|
| 基础设施 | 操作系统、编解码、网络、数据库连接、对象存储、时钟与文件系统 | Android 媒体 API、Room/SQLite 驱动、HTTP、PostgreSQL、S3、任务调度器 | 图语义、复核有效性、发布是否允许 |
| 数据 | 持久化布局、查询映射、事务、文件索引与迁移 | Room Entity/DAO、服务 SQL、资产索引、Job 检查点 | 把数据库行直接变成公共 DTO；因“状态已存”自行授予发布权限 |
| 业务 | 实体、不变量、纯计算与用例编排 | 有限图、来源确认、复核指纹、版本编译、发布/撤销、播放 reducer | 依赖 UI、HTTP 请求对象、数据库实体或媒体库类 |
| 接口 | 入站和出站适配边界 | 入站：ViewModel 调用、HTTP controller、导入命令。出站：Repository、MediaCompiler、AssetStore、Clock、MailSender 等端口实现 | 只理解成 REST 层；让 UI 或插件绕过业务用例直接改存储 |
| 交互 | 面向人的表现、输入反馈、无障碍与错误恢复 | Compose 步骤卡/热点编辑、React 播放、发布摘要、Remotion 画面编排 | 决定“已脱敏”“可发布”或通过修改按钮状态绕过校验 |

依赖规则：

1. domain 是最内核，只含值对象、实体、不变量、领域错误、纯函数；不 import 框架。
2. application/use-cases 依赖 domain，定义所需出站端口并组织事务；不自行选择 Room/S3/Media3。
3. 入站适配器把请求翻译成用例输入；出站适配器实现端口，依赖业务接口和具体基础设施。
4. composition root 负责连接真实实现；UI 通过入站调用用例，不直接持有 DAO、文件路径或 S3 客户端。
5. 网络调用和函数调用不是同一概念。播放器 reducer 可以直接依赖纯 domain；无需为一次点击穿过数据库和服务端业务。
6. 安全编译横跨多个底层能力，但由一个可恢复用例统一调度；不能让每个技术层各自决定资产是否可交付。

建议用构建依赖检查禁止循环和反向 import，而不是仅靠文件夹命名维持边界。Android 与 TypeScript 不为“代码共享率”引入 Kotlin Multiplatform/WASM；共享协议、规范与样例，分别实现少量纯规则，并做跨语言一致性测试。

## 3. 模块与部署边界

建议未来仓库按运行产物和契约组织，以下只是提案目录：

- contracts：交换 schema、OpenAPI、版本/容量政策、错误码、有效/无效 fixtures、坐标和播放轨迹基准。
- android：app；core-domain；core-application；data-local；media；analysis；package-io；publish-client；feature-editor；feature-player；feature-delivery。初期可在少量 Gradle 模块内按包分界，不为每个页面建模块。
- web-player：React 视图与浏览器媒体适配器。
- runtime-ts：图校验、播放 reducer、坐标换算、render-plan 解析等纯 TS 函数；不得依赖 React、DOM、Fastify 或 Node 文件系统。
- server：identity、publication、asset-access、upload、retention、operations 模块；API 与 worker 共用领域规则和部署镜像。
- remotion-adapter：受信模板、资源导入 CLI、固定组合注册、字体与图标清单、渲染验收用例。
- fixtures：虚构校园报名样例、坏包、短暂敏感帧、可变帧率/旋转元数据样例；禁止真实个人素材进入仓库。
- docs：规范、ADR、威胁模型、迁移和恢复手册、兼容矩阵、验收证据。

不得形成的耦合：Web 不读取本地 Draft；服务器不依赖 Android 原片路径；Remotion 不直接读取 Room 或发布管理凭据；离线导入器不执行模板；业务 domain 不识别屏幕 dp 或 CSS px。

## 4. 数据模型与关键不变量

### 4.1 本地私有模型

- Project：稳定 UUID、标题、目标、createdAt/updatedAt；显示顺序和标题不构成身份。
- Draft：projectId、revision、可变图、交付配置；一次编辑用事务更新 revision 和相关失效项。
- Source：内部资产 ID、私有副本、编码/尺寸/旋转/颜色信息、实际时间戳映射、摘要；真实原名只用于本地来源查看。
- State：稳定 stateId、代表帧锚点或安全导入资产、标题/说明、来源类别、明确的结束属性。
- Edge：稳定 edgeId、sourceStateId、targetStateId 或结束结果、trigger、可选过渡、来源类别、确认记录。一个热点有多个结果时关联多个带标签的 edge，由人选，不存业务条件表达式。
- Hotspot：相对状态内容画布的矩形与标签、可选 edgeIds；不把边复制成热点内第二套图。
- RedactionSpec：状态或视频范围、固定不透明矩形集合、坐标依据、revision。
- Region：只依赖最终安全底图，存像素 bbox、分组、zIndex、局部锚点；是 screenshotCrop，不是恢复的控件。
- Review：subjectId、reviewKind、inputFingerprint、reviewedOutputDigest、policyVersion、confirmedAt。不是永久布尔值。
- LocalJob：jobId、kind、固定 draftRevision、inputFingerprint、stage、attempt、检查点、错误码、临时产物引用。
- UndoCommand：有界编辑事务的逆操作/必要快照；本地保存并排除备份，不把整个工程实现成无限事件溯源。

### 4.2 安全交付与服务端模型

- SafeAsset：assetId、受限相对路径、媒体类型、尺寸/时长、bytes、SHA-256；没有原路径/原 OCR/遮挡前资产引用。
- Release：随机稳定 releaseId、schemaVersion、policyVersion、immutable payload、contentDigest、createdAt；一旦 sealed，不可修改。
- HostedProject：ownerId、serverProjectId、关联本地 projectId 的最小映射、发布序号；不含本地草稿。
- Publication/Share：shareId、releaseId、ownerId、status、expiresAt、revokedAt、versionOrdinal、tokenHash 与加密保存的持链 token。账号管理接口可重取链接；数据库与日志不明文暴露 token。
- UploadSession：ownerId、releaseId、manifestDigest、所需资产清单、每资产上传状态、quotaReservation、deadline、idempotencyKey。
- ServerJob：检查/清理任务、租约、attempt、nextAttemptAt；同库事务写入，worker 按租约领取。
- RevocationLedger：追加式撤销事实及恢复校验水位，供数据库恢复时防止旧备份复活链接。

### 4.3 不变量

1. 未确认的候选、缺失素材和待补录目标不能进入交付图；作者编排必须显式标识。
2. 交付图只有有限、枚举的动作；无脚本、表达式、真实输入、原 App 调用、外部跳转。
3. 可达状态都可到达结束或明确出口；允许显式动作环，拒绝纯自动环。只有 startState 不足以证明全部分支可完成。
4. 草稿可保留不完整/不可达内容，但交付需要明确纳入子图；对被排除节点给出摘要且不导出其媒体。未解释的不可达内容视为阻断，不能静默丢弃。
5. 复核指纹覆盖直接内容与派生依赖；只复核原图不能授权其后改过的裁片或缩略图。
6. 同一 releaseId 的 bytes/graph/digest 永不改变；服务端遇到同 ID 不同摘要必须冲突拒绝。
7. 删除 Project、撤销 Share、删除服务器资产是三种操作，互不隐含。
8. 传输重试沿用幂等键；相同键不同请求体返回冲突，不能复用旧成功结果。
9. 哈希用于检测一致性，不证明作者身份、素材授权或隐私充分性。

## 5. Android 本地媒体、OCR 与安全编译

### 5.1 导入和分析

通过系统 Photo Picker/Storage Access Framework/明确分享导入读取特定文件；不请求无障碍、通讯录或全盘照片权限。官方 Photo Picker 的范围是用户选定图片和视频；URI 不等于持久私有副本。[Android Photo Picker](https://developer.android.com/training/data-storage/shared/photo-picker)

先读头部和文件大小，再把内容流式复制至 app 私有暂存；复制完成、摘要和可解码性通过后原子转为 Source。复制前空间估算包括源副本、解码工作区、安全输出和打包暂存，不能仅比较“文件大小 < 剩余空间”。来源在云相册尚未落地、访问授权撤回或复制中断时保留可恢复错误，不创建虚假成功素材。

只承诺规格内 MP4/H.264/SDR、固定竖屏及对应上限。检查容器实际轨道和解码器能力，不只看扩展名；把旋转元数据归一到明确内容画布。源定位使用 presentation timestamp，不能用 nominal fps × time 猜帧；可变帧率样例必须测试。持续小批量解码，限制 in-flight Bitmap/ByteBuffer 数量，避免一次缓存五分钟所有帧。

候选检测使用低分辨率帧差、稳定区段和 OCR 边界；全分辨率仅用于代表帧、安全输出和精确编辑。原 OCR 不自动变成可交付文字，拷入说明后仍需复核。失败可手动取帧、建状态、加热点；新的分析结果作为 suggestions 合并，禁止覆盖人工修改。

### 5.2 OCR 明确决策及保留风险

条件推荐 ML Kit 捆绑式中文和 Latin 模型；不把 SDK 隐私门禁尚未通过表述为选型已经批准。官方说明捆绑模型随构建提供、可立即使用；当前接入要求段写 API 23+，但页面摘要存在更旧要求，不能据此直接冻结产品最低系统。建议以 Android API 26 作为首轮验证候选，最终最低版本由实际 Compose/Media3/ML Kit 组合和机型测试确定。[ML Kit Android 接入](https://developers.google.com/ml-kit/vision/text-recognition/v2/android)

官方说明输入和输出留在设备，同时 SDK 会联系服务器并发送性能/使用指标。因此“本地 OCR”不能写成“整个 SDK 零网络”。默认本地制作不允许非必要指标外传；只在能通过受支持配置禁用该行为并经实际流量与依赖审计验证后，才允许采用此 SDK。不得声称存在未经核实的通用关闭开关，隐私声明也不等于已通过验证。若不能满足，则在实现前改用可控的随包端侧 OCR 引擎并完成中文样例、模型许可和性能验证；此时 ML Kit 方案不获批准，不静默放宽隐私底线。离线首次运行、无 GMS 目标机、APK 合并权限、SDK 子域请求和后台任务需分别测试。手工闭环是出错时兜底，不能用它永久删掉 P0 的 OCR 承诺。托管模块本来需要网络，因此不承诺整个 App 零联网，而是以发起条件、网络目的地与实际数据流划定最小权限边界。[ML Kit Terms & Privacy](https://developers.google.com/ml-kit/terms)

### 5.3 独立安全编译管线

固定 draftRevision → 校验图/作者确认 → 生成安全媒体 → 从安全媒体生成缩略图/封面/区域 → 重解码和文件清单检查 → 作者复核实际输出 → sealed Release。

- 静图：解码所选帧、应用不透明矩形、规范化旋转/尺寸/色彩、写出全新的 PNG/JPEG。丢弃原 EXIF、位置、文件名和额外元数据；不复制带隐藏数据的原始容器。
- 视频：只读取批准时间范围；每一输出帧经过固定遮挡 shader 后重新编码为兼容 H.264/SDR MP4；去掉全部音频轨、字幕/数据轨和未允许元数据。遮挡跟踪不进入 P0，移动信息要求遮挡整个运动包络或改静态。
- 重要禁用项：不使用 edit-list trim 隐藏前后片段；不使用 trim optimization 拼接原压缩样本；不得因输入输出同格式自动走 transmux。Media3 官方明确指出 edit-list pre-roll 可能保留隐私数据，也说明格式相同可自动复制压缩样本。实现须验证强制转码路线及实际 ExportResult，不能只设置 MIME。若所选 Media3 版本不能可靠保证，安全编译端口改用显式 MediaCodec 流程并重新验证，不以速度绕过。[Media3 transformations](https://developer.android.com/media/media3/transformer/transformations)
- 重解码读取最终文件的所有视频帧并检查轨道、时长、尺寸、遮挡覆盖和尾部；OCR/规则扫描只能发现线索，不能自动证明安全。测试需放置仅一帧出现的假隐私。作者必须审阅完整片段，有暂停、逐帧定位和重放能力。
- 缩略图、封面、region crop、最终文案全部走 allowlist 输出；未复核项目在项目首页用中性封面。
- 输出失败不产生 sealed 状态；从 staging 到已验证资产使用临时文件 + 完成记录 + 原子文件迁移，DB 提交与文件系统之间用可恢复 journal 对账，不能假称两者在一个事务里。

### 5.4 复核失效的依赖图

Source/裁剪/代表帧/尺寸改变 → 画面与视频输出、热点定位、相关 edge 过渡、regions、缩略图、封面、复核失效。遮挡改变 → 输出字节、全部派生图、相应复核失效。文案改变 → 对应文字与交付摘要复核失效。分支改变 → 图检查、边覆盖、selectedPath 和 renderPlan 失效。

复核依据是已输出字节摘要 + 审阅文字 + 相关配置 + 编译器/策略版本。即使恢复旧草稿内容，也重新判定依赖与复核；不从外部包继承可信 review。若重新编码产生不同字节，只能重新审核该新输出，不能把相同输入等同于相同成品。

### 5.5 调度、保存与本地边界

媒体处理采用单个重编译任务及有界解码队列，缩略图/OCR 各有内存预算。任务前与每个检查点检查剩余磁盘、电量和可用热状态；低电量或严重热压力停止接入新资产，已完成资产保留，当前未完成片段必要时取消并回到上一个检查点。恢复由用户或符合条件的调度触发，不能不断重启造成过热循环。Thermal API 不可用或信号未知时采用保守并发和可暂停流程，并明确状态未知，不假定温度正常；不在未验证前承诺后台总能完成。阈值由最低支持机的小样基线冻结。[Android Thermal API](https://developer.android.com/games/optimize/adpf/thermal)

每次确认编辑后用 Room 事务保存；一次手势连续移动可以合并成一个提交，界面必须准确区分编辑中/已保存。分析、编译、导出、上传分别建 Job，长任务绑定固定快照，作者继续编辑生成新 revision，不污染在运行任务。

WorkManager 负责调度和恢复意图，媒体任务按单资产建立完成检查点；进程终止后重做当前资产，不假称可以在任意编码帧续传。Android 官方说明长运行 worker 使用前台执行，且新系统仍有配额/类型约束，具体后台策略需随 target SDK 实测。操作系统回收与用户 force-stop 分别测试；不承诺 force-stop 后自动续跑，用户重新打开 App 时根据任务表恢复。[长运行任务约束](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running)

源素材、DB、原 OCR、预览缓存、undo、任务上下文和凭据均排除云备份和设备迁移规则；敏感文件使用 noBackupFilesDir/受控私有目录，数据库及其他域显式排除。不能仅设 allowBackup=false：官方提示部分厂商设备迁移行为不同。跨版本/厂商恢复测试是门禁，卸载和设备丢失不承诺恢复草稿。[Android Auto Backup](https://developer.android.com/identity/data/autobackup)

## 6. 离线包、AI 包及跨语言契约

### 6.1 格式权威与版本轴

产品规格 v0.1、wire schemaVersion、policyVersion、adapterVersion、appVersion、数据库版本、发布显示序号各自独立。建议第一个正式可交换 schema 为 1.0.0；这是待批准的协议编号，不意味着产品已稳定。

交换格式由仓库内 JSON Schema Draft 2020-12 定义，API 由 OpenAPI 3.1 定义；公共 schema 引用采用打包的本地 $ref，不运行外部包自带 schema，也不在线解析任意 $ref。包内附 schema 仅帮助第三方阅读，接收器始终使用自己受信的版本注册表。JSON Schema 本身不检查有向图可达性、路径语义和资产真实内容，必须再走语义校验。[JSON Schema](https://json-schema.org/draft/2020-12)、[OpenAPI 3.1.1](https://spec.openapis.org/oas/v3.1.1.html)

TypeScript 采用 Ajv 2020 及从权威 schema 生成的类型；Fastify 使用显式配置的受信校验器，不能默认假定其 schema 方言或开关与交换格式一致。Kotlin 采用受限 DTO 和显式校验实现，跨语言 fixtures 在共同支持的 schema、policy 和语义范围比较相同的接受/拒绝结果及错误位置；观看包、AI 包与不同 runtime 的能力差异另列预期矩阵，不要求网页接受其不支持的 AI 导入操作。初期不引入未验证的 Android 全功能 JSON Schema 引擎。Fastify 官方提醒 schema 编译涉及动态代码，不能将用户提交的 schema 当配置执行。[Fastify validation](https://fastify.dev/docs/latest/Reference/Validation-and-Serialization/)

强制拒绝重复 JSON key、NaN/Infinity、非法 Unicode、未知必需能力、越界安全整数、过长字符串、超深结构。未知字段默认拒绝；未来扩展仅在受限 extensions 命名空间且明确可忽略时允许。首次 P0 只接收已支持版本，未来支持 N-1 必须存在确定的迁移器和测试，不能笼统“向前兼容”。

contentDigest 采用 SHA-256(JCS(payload))，payload 的包含字段必须在 schema 文档列明，排除 digest 自身、传输状态和 Share 元数据；assets 按稳定 ID 排序后参加摘要，资源 bytes 各自计算 SHA-256。使用 RFC 8785 的标准规范化而非两端自行 sort + stringify；特别测试负零、指数、Unicode、属性顺序。摘要不包含作者真实来源信息。[RFC 8785 JCS](https://www.rfc-editor.org/info/rfc8785/)

### 6.2 两类包分开

- 观看包：manifest、有限状态图、已使用安全媒体；没有播放器代码、HTML、JS、真实源名、原 OCR、编辑历史或外链媒体。
- AI 工程包：相同安全域的图与媒体，加 selectedPath/renderPlan、regions、版本化 schema 与 README。保留来源类别和必要的安全相对时码，不附真实本地 source 路径。没有受信模板代码，也不授予自动上传权限。
- 只导出纳入范围的媒体引用闭包，不把整个缓存目录打包。分支图如完整导出则包括其已使用媒体；未使用私密资产一律排除。

### 6.3 安全导入

输入视为不可信数据。先验证压缩文件体积，再于随机、私有、空隔离目录流式解包；实时累计实际解压 bytes、条目数、单文件大小和压缩比，不能相信 ZIP header 自报大小。规格的压缩包与解压资产总量均 50 MiB 是一致硬约束；JSON、条目数、最大嵌套深度等防滥用限制另在 policy 文件明确并实测冻结。

拒绝绝对路径、..、驱动器前缀、反斜杠歧义、NUL、非规范分隔符、重复/大小写折叠冲突路径、符号链接、嵌套归档、加密归档、可执行入口、未列入 manifest 的文件及不匹配的媒体 magic。建议资产路径使用受限 ASCII ID，使跨平台规范化没有隐藏等价路径。Android 官方明确说明 java.util.zip 不替应用完成路径穿越检查。[Android Zip Path Traversal](https://developer.android.com/privacy-and-security/risks/zip-path-traversal)

随后校验 schema → ID 唯一/引用 → 哈希 → 媒体可解码/尺寸/时长/无音轨 → 图结构 → 路径与坐标 → 配额。解码前先以头部尺寸执行单图和累计像素预算，解码时仍以真实结果复核；不能因压缩文件很小就允许巨幅 Bitmap 分配。全部通过后才原子登记到独立演示库；重名不覆盖。同包摘要重复可提示已有副本。作为新草稿导入时生成新 Project/Draft ID，保留来源 releaseId 仅作关联，并重置全部隐私复核。

### 6.4 坐标与时间

- Hotspot 使用状态内容矩形归一化坐标 x/y/w/h ∈ `[0,1]`；宽高大于 0，x+w 和 y+h 不超过 1。点击坐标先去除留白再映射，不能用整个播放器容器尺寸。
- Region bbox 使用最终安全图像的整数像素，sourceWidth/sourceHeight 与资产真实尺寸相等；anchor 使用裁片局部 0–1 坐标。不同坐标类型使用不同值对象，不让一个 Rect 承载两种单位。
- 渲染等比缩放 scale=min(canvasW/sourceW, canvasH/sourceH)，offset 为居中留白；热点先乘源尺寸，再做同一仿射变换。UI 可放大触摸目标但不能修改语义热点；冲突时用文字操作列表。
- 源时码毫秒、输出固定 fps 的整数帧；区间统一左闭右开。毫秒到帧使用明确的非负 half-up 规则 floor(ms×fps/1000+0.5)，仅转换一次；零帧片段拒绝，不偷偷改时长。禁止在不同模块重复浮点舍入。
- selectedPath 是有序 visits，不是简单 stateId 数组。每次回访有唯一 visitId，selectedEdgeId 必须属于该访问状态且指向下一状态；末次访问明确结束。禁止无限循环展开。

## 7. 播放器：一份语义、两种实现

Kotlin 与 TS 各实现同一规范的纯 reducer。状态含 releaseId/contentDigest、当前 stateId、visit 历史、选中 edge、mediaRunId、phase（ready/transition/ended/error），不把 React/Compose 组件生命周期当业务状态。

- 每次有效选择创建唯一 mediaRunId；过渡中禁止重复触发，旧媒体 ended/error 回调只有 runId 匹配才可完成跳转。
- 上一步弹出实际访问栈；画面内作者设置的返回热点按其 edge 执行，两者不是一回事。
- 重新开始清空本次轨迹，取消旧过渡及异步资源任务。
- 视频失败允许重试或跳过至本次已选 edge 的既有目标，不能请求原片或猜下个状态。
- 仅文字动作列表、键盘、屏幕阅读器即可走完；热点至少提供对应可访问标签，移动端触控区域按 48 dp 设计。
- Android 交付效果预览和离线成品使用同一个 reducer；调试覆盖层只存在预览 adapter，不写入包。作者的原片回看、遮挡编辑和来源对比属于单独的本机私有编辑视图，可以读取 Source，但不能通过成品 AssetResolver、包导出或网络层暴露。
- Web 使用语义 button、可见焦点、muted/playsInline 视频、明确播放/跳过控制；自动播放受限时提示操作，不假报已播放。
- 作者/AI 提供的标题、标签和说明全部按纯文本渲染；不进入 innerHTML、模板、CSS、URL 或动态 import。配置 CSP 限制脚本与媒体来源；浏览器用构建期预编译的受信校验器，避免为数据校验开启 unsafe-eval。服务不根据包内文本发起任意 URL 请求。
- 在线断点只保存 releaseId、digest、状态与有限历史，不缓存整包；恢复/重新进入页面先重新检查 Share。撤销后不借断点继续发起新资源请求。
- 页面的 pagehide/pageshow、visibility 恢复及过期计时点重验访问；不能把 Cache-Control:no-store 当成浏览器绝不恢复历史页面的保证。Chrome 官方已支持部分 no-store 页面进入 bfcache。[Chrome bfcache 说明](https://developer.chrome.com/docs/web-platform/bfcache-ccns)

服务端撤销只保证后续请求被拒绝；已送到浏览器内存的像素、已下载文件与截图无法收回。界面收到拒绝后停止展示/清空本会话资源引用是一项体验措施，不是 DRM 承诺。

## 8. API、发布一致性与撤销

### 8.1 身份与授权

本地流程无账号依赖。邮箱验证码需要限速、短时有效、一次使用、尝试次数上限及统一响应避免账户枚举；验证码只存加盐/带服务端密钥的摘要，不进日志。管理会话可撤销；Android 凭据由平台安全存储保护。账号恢复恢复的是 owner 下发布管理，不是原素材/本地工程。

持链观看 token 是 bearer capability，不是观看者身份验证；使用足够熵的随机值，网关按摘要查 Share。token 可加密保存以供本人重新复制，密钥与数据库分离。公开 URL、Referer、代理日志、错误跟踪全部做 token 脱敏；观看页 Referrer-Policy:no-referrer，无第三方统计/远程字体。管理与观看权限使用不同端点和检查逻辑，UUID 或难猜地址不能代替 owner 授权。

### 8.2 最小 API 面

以下为待 PR 冻结的接口形状，不是已可调用地址：

| 操作 | 端点示意 | 关键约束 |
|---|---|---|
| 发起/验证验证码 | POST /api/v1/auth/challenges；POST /api/v1/auth/sessions | 反滥用、一次使用、无素材数据 |
| 建立上传 | POST /api/v1/publication-uploads | owner、serverProjectId、releaseId、摘要、有效期枚举、Idempotency-Key |
| 上传单资产 | PUT /api/v1/publication-uploads/{uploadId}/assets/{assetId} | 受认证、预声明 size/hash、流量限制、流式写 staging；P0 单资产失败重传，不增加分块协议 |
| 查看/取消上传 | GET/DELETE /api/v1/publication-uploads/{uploadId} | 恢复检查、取消仅作用未发布 staging |
| 完成发布 | POST /api/v1/publication-uploads/{uploadId}/commit | 检查完成且与最终摘要一致；幂等，回执丢失可查回 |
| 本人发布列表 | GET /api/v1/projects/{id}/publications | 必须 owner filter；显示服务端序号/状态/到期时间 |
| 撤销某版 | POST /api/v1/publications/{id}/revoke | 幂等、单调，不影响其他版本；返回 serverConfirmedAt |
| 观看页/manifest/媒体 | GET /s/{shareToken}；GET /s/{shareToken}/manifest；GET /s/{shareToken}/assets/{assetId} | 每次检查同一 Share、关联 Release 和资产归属，含 HEAD/Range 请求 |

响应给机器可识别 code、pointer/subjectId、retryable、traceId，不返回内部路径或媒体 OCR。常见类别：VALIDATION_FAILED、SCHEMA_UNSUPPORTED、REVIEW_STALE、ASSET_MISMATCH、QUOTA_EXCEEDED、UPLOAD_INCOMPLETE、IDEMPOTENCY_CONFLICT、SHARE_EXPIRED、SHARE_REVOKED。公开错误避免泄露所有者信息。

### 8.3 发布两阶段及并发

1. 客户端只从 sealed Release 建立 upload，记录确定的 releaseId、摘要、有效期和幂等键；初次发布确认授权此次安全资产上传。后续重试使用同一请求，不重新编译或偷偷追加新资产。
2. API 在 PostgreSQL 事务内锁定 HostedProject 行，检查有效版本 + 未过期预留槽位是否小于 5，创建有限期 reservation。仅先 count 再 insert 会出现并发超限，必须串行化项目额度决策。[PostgreSQL 行锁](https://www.postgresql.org/docs/current/explicit-locking.html)
3. 上传仅写 private staging。每资产完成后校验实际长度/摘要，任务失败不会写 visible=true。50 MiB 上限由客户端、流式上传及服务端校验重复执行。
4. worker 以租约执行 schema/图/媒体格式/全文件完整性与无音轨检查；安全媒体同样视为不可信输入，解码在受资源限制、无外网的进程内执行。服务器不能证明人确已复核，更不能认证不存在所有隐私，只能执行确定性政策和拒绝危险格式。
5. 在 commit 前将已验证 bytes 放入不可变私有 final key，验证可读性；可以产生暂时孤儿对象，不产生可访问半成品。
6. 短数据库事务重新锁项目及上传记录，核对 reservation、assets、摘要与未取消状态，分配发布序号，写不可变 Release 和 active Share，commit 是唯一对外可见切换点。到期按服务器 commit 时间加选择的天数计算。
7. 回执丢失时 GET 状态或同键重试返回同一 publication/share。owner+releaseId 与 owner+idempotencyKey 建唯一约束；同一 releaseId 不允许二次延长或复活旧 Share。确要再次发布相同内容也创建新 Release/Share 并重新确认。

数据库与对象存储没有跨系统原子事务。上述“先物化不可变对象、后数据库引用”配合 staging 清理/孤儿清理解决问题；禁止先创建有效链接再补媒体。清理只删除确定无人引用且超过保留窗的对象，上传/commit 持有引用或租约时不能删除。

### 8.4 统一访问与撤销语义

API 媒体网关每次请求从强一致数据库连接读取 Share 状态和服务器时间，确认 active 且 now<expiresAt，再读取该 Release 引用的 asset。P0 不缓存正向授权、不读有复制延迟的只读副本、不直接 302 到预签名下载地址。授权服务/数据库不可用则失败关闭；不能“先放行再补查”。

受保护 HTML、manifest、封面、缩略图、媒体和范围响应设置 private/no-store；不注册可持久缓存演示的 Service Worker。公共程序 JS/CSS 可独立做长期缓存，但不得把用户标题、封面或 manifest 嵌入公共静态构建。304、HEAD、Range、错误重试、旧页面恢复都必须先鉴权；不能以 ETag 命中跳过 Share。

撤销事务写 revokedAt 并持久确认后再返回成功，revoke 幂等且不可逆；新请求按最新状态拒绝。定义并发边界为“在撤销提交之后开始授权检查的请求必拒绝”；已在撤销前批准且正在传输的响应不承诺收回。API/代理不能把整包预取当默认优化。

到期依赖每次请求实时判断，后台过期任务只做状态标记和清理，不作为安全门。App 离线点撤销只显示待提交；服务端确认前显示仍可能可访问。静态包没有远程撤销能力。

## 9. Remotion 与 AI 回流

受信模板与数据包完全分离。模板由开发方构建并锁依赖、字体、图标授权和 adapterVersion；用户的数据包不能包含 package.json、安装脚本、JS、任意 CSS、组件路径或表达式。README 的说明文本不是指令执行入口。

适配流程：安全导入包 → 校验受支持 schema/效果/配额 → 选定有限 visits → 解析完整 renderPlan → 将核实媒体复制到模板受控本地 public 资源目录 → 以固定 Composition 渲染。建议将规格示例 Composition ID“DianyanDemo”统一为“TapSceneDemo”；这是随已定英文名提出的接口标识变更，须在首份 schema/adapterVersion 契约中评审冻结，此后不能随营销名任意变动。

- 30 fps；1080×1920 与 1920×1080 预设；原画面等比留白。
- 每个 visit 保存 stateId、selectedEdgeId、holdFrames；解析后提供绝对 startFrame、durationFrames、过渡/叠加段和 totalFrames。
- 页面停留、已录短视频、点击指示、聚焦、页面切换、框选高亮、文字标注为枚举白名单。
- 时间轴总长由解析器单次计算，交叠转场扣除重叠帧；普通标注不延长总长。两个画布只改变布局，不改变路径或隐式增删 visit。
- calculateMetadata 从受校验计划返回 durationInFrames/尺寸/fps；不在渲染期间随机生成路径或下载缺失素材。[Remotion calculateMetadata](https://www.remotion.dev/docs/calculate-metadata)
- 媒体通过 staticFile 访问模板本地 public 资产；先验证路径再复制，不能把任意包内字符串当 URL。关闭渲染环境外网，并禁止使用外部媒体回退。[Remotion staticFile](https://www.remotion.dev/docs/staticfile)
- 输出先写临时文件，完成并验证帧数/编码/尺寸才标记成功；渲染中断保留诊断，不把残缺 MP4 当交付。[Remotion renderMedia](https://www.remotion.dev/docs/renderer/render-media)
- Region 默认 screenshotCrop。无干净底板时做聚焦引出/并排/弱化底图，不宣称真正移除页面组件，也不补造被遮挡背景。透明层只能引用已确认的现成透明资产。
- 模板默认电脑本地运行；云渲染需单独选择环境、告知数据传输及成本。上线前按实际使用核对 Remotion 许可，不提前认定免费或已获商用授权。[Remotion License](https://github.com/remotion-dev/remotion/blob/main/LICENSE.md)

回流 JSON 只接受兼容文字、连接、有限路径、时长建议和区域数据；新建独立 Draft，显示与来源版本差异。资产、配额、图和坐标全部重新校验；外部 review 标记无效。回流永不覆盖现有草稿、永不自动发布，外部动画工程也不保证可反向还原。

## 10. 迁移、恢复、版本兼容和运维

### 10.1 本机升级/中断

- Room schema 导出进版本管理，每条升级路径有 migration test；禁止 destructive migration 作为生产兜底。迁移先确认空间、备份本机私有 DB 与必要索引；备份本身也排除系统备份。官方迁移机制不能替代应用数据兼容测试。[Room migrations](https://developer.android.com/training/data-storage/room/migrating-db-versions)
- 文件与 DB 的 journal 在启动时对账：未完成临时文件清理或继续，完整无记录资产登记为待回收，不误删任何仍引用的源文件。
- 已 sealed Release 保留原 schema 与字节，不因打开工程被原地“升级”。导入旧包时使用明确迁移器生成新对象，旧文件保持不变。
- 编译器/隐私政策有影响的升级使相关复核失效；只改 UI 的补丁无需自动废掉全部项目，但要有明确影响判定。
- 卸载/设备丢失：原工程可能永久丢失，账号不能恢复。作者的安全包可以新建草稿，但不能恢复遮挡前信息或源时间轴。

### 10.2 服务升级/备份恢复

- SQL 使用 expand → backfill → switch → contract；读写短暂兼容前后版本，滚动发布不要求同时更改所有客户端。
- 数据库定期备份/PITR，对象存储有独立备份或版本保留；明确记录两者恢复点与引用完整性。RPO/RTO 在部署选择后由恢复演练测定，本提案不编造 SLA。
- 恢复最危险的情况是较旧 DB 备份把已撤销 Share 恢复为 active。恢复期间停止全部观看访问；重放独立保存的撤销账本/WAL 及水位验证。无法证明撤销事实完整时，旧 Share 统一保持禁用，要求所有者重新发布；不能按旧快照自动恢复访问。
- 对象丢失或摘要不符时该版本失败关闭、通知管理端；不能取最新版资产补旧版本，以免破坏不可变语义。
- 删除/清理先满足引用、保留与恢复窗口规则；撤销立即限制访问，不等于立刻物理删除所有备份。

### 10.3 兼容策略

服务端支持的 schema 和 policy 列表可查询；客户端发布前知道是否需要升级。新增安全必需字段/效果必须升能力版本，旧播放器明确拒绝，不悄悄忽略。发布网页可以逐步升级程序，但旧 Release 的语义不能随程序改变；保留历史 fixtures 和必要的版本解释器。

基础容量政策复用规格：40 状态、80 边、每状态 6 热点、3 段源录屏/总 5 分钟/单段 3 分钟、200 MiB 单源/500 MiB 总源、20 截图/单图 12 MP 与 10 MiB、10 秒单过渡/60 秒总过渡、50 MiB 单包及解压资产、每项目 5 个活动版本。以上是设计上限，性能是否足够待真机测量；AI visits/timeline 还需单独有限计数和输出时长防滥用上限，不可让合法图无限展开。

### 10.4 最小可观察性

记录阶段耗时、失败码、资产数量/总 bytes、编解码器类别、任务重试、发布/撤销事件与授权拒绝计数。不要记录原片、OCR、截图、用户文案、邮箱验证码、bearer token、完整 URL、绝对源路径或任意 payload。P0 不加第三方行为分析、会话录像或自动上传崩溃附件。服务端日志也需要脱敏和保留期限，不能因“已脱敏媒体”就无限留存。

## 11. 首轮验证与批准门禁

先做验证，后冻结兼容基线。以下都尚未执行：

| 门禁 | 要取得的证据 | 失败时处理 |
|---|---|---|
| V01 本地编解码与真脱敏 | 指定低/中档真机对标准样例、上限素材、连续多次处理的成功率、PSS/峰值内存、临时盘峰值、耗时、热状态和电量变化；低电量/过热/磁盘不足可安全暂停；逐帧无敏感样本、无音轨、无 pre-roll 原样本 | 缩小兼容矩阵/输出参数或改显式 codec 管线，不降低隐私门槛 |
| V02 本地模式与迁移边界 | 首次安装断网 OCR 与手工闭环；SDK 网络抓取；备份/厂商迁移后的实际数据检查 | 默认无非必要外传需实测满足；无法满足则替换可控端侧 OCR，披露不能代替门禁；不能宣传已全面排除 |
| V03 包攻击样例 | 路径穿越、重复路径、zip bomb、伪类型、缺媒体、脚本、外链、超限、未知版本全部拒绝且旧项目不变 | 作为发布阻断漏洞修复 |
| V04 跨端同语义 | Android/TS 在共同支持的 schema/能力范围对相同图、坐标、访问历史、循环与错误 fixtures 输出一致 | 不发布不一致的 schema/runtime |
| V05 发布与撤销竞争 | 并发第 5/6 个发布、回执丢失、commit 崩溃、直接媒体/Range/HEAD/304、恢复旧备份，均满足可见性与撤销边界 | 禁止托管上线；离线闭环可独立继续 |
| V06 动画交付 | 两种画布实际 MP4、帧数/锚点/路径一致；未知效果拒绝；JSON 回流新草稿 | 模板未真实渲染通过前不能声称 P0 完成 |
| V07 可用性与无障碍 | 手机独立完成创作、隐私复核、离线/AI 导出、链接发布与 JSON 回流；F28 实际渲染由 V06 在桌面验证；文字替代操作、屏幕阅读器、键盘和异常恢复 | 调整交互/提示并重测，而非只通过单元测试 |

本节 V01–V07 是按子系统组织的验证计划，与独立审查文档的 G01–G10 不重号；V01 对应 G02/G07，V02 对应 G01/G02，V03 对应 G03，V04 对应 G06/G09，V05 对应 G04/G05/G08，V06 对应 G06/G09，V07 补充交互可用性。PR 治理另依 G10 执行。

实施顺序建议：

1. 契约、威胁模型、样例与最小手工静态链；同时验证危险媒体路径。
2. Android 本地保存、精确取帧、静态真脱敏、包导入、单路径播放，建立离线闭环。
3. 分支/历史/复核依赖/任务恢复；加入短视频逐帧安全导出。
4. OCR 和相似状态候选；以人工修正成本与错误合并率验证辅助价值。
5. AI 包、region 与受信 Remotion 模板、兼容回流；不能推迟到“首版以后”。
6. 最小账号、不可变发布、统一访问网关、撤销与恢复演练；再确定部署、成本与运营保留政策。

## 12. 拟提交的决策记录与待决项

建议拆分 ADR，但在一个规范 PR 中一起评审：

- ADR-001：本地优先与原始/安全资产双域。
- ADR-002：原生 Android + React Web + Fastify 模块化单体。
- ADR-003：版本化纯数据协议及 Kotlin/TS 一致性测试。
- ADR-004：逐帧重编码脱敏与内容指纹复核。
- ADR-005：不可变发布、两阶段物化与逐请求撤销。
- ADR-006：不可信 AI 数据包与受信 Remotion 模板分离。
- ADR-007：备份排除、恢复水位和撤销不复活。

需要批准或进一步验证的事项：

1. 接受本提案的技术方向及模块边界，才进入实现；当前仍为 PROPOSED。
2. 英文名 TapScene、中文点演及仓库名 TapScene 已定；Android applicationId/命名空间、域名和建议 Composition ID TapSceneDemo 仍需按所有权与契约评审冻结。
3. ML Kit 能否通过默认无非必要指标外传门禁；若无法禁用并实测满足，必须替换可控端侧实现，不能以披露或永久手工流程代替 P0 OCR。
4. 首轮机型、最低 Android、浏览器矩阵、固定依赖版本与编码配置。
5. 托管地区、邮件服务、账号/资产/日志/备份保留期限、恢复目标、成本预算及相关法律/许可核验。
6. AI visits/渲染总时长、解包条目数/JSON 深度等补充防滥用限额；与原规格一致后进入同一 policyVersion。

批准证据应记录到 PR/ADR；“已有规格”“工具可用”“原型能跑”都不自动等于架构已批准或产品已验收。
