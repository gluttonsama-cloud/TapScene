# 架构与开发约定

[基础设施](#基础设施层) · [数据表](#数据层) · [业务规则](#业务层) · [API/SDK](#应用接口层) · [页面安排](../README.md#页面与流转) · [开发约定](#两个人的开发方式)

产品范围和逐页安排见 [README](../README.md)，当前进度见 [任务清单](tasks.md)。
以下均为待实现设计；本项目的数据表、API、适配器及第三方 SDK 接入尚未完成，兼容组合尚未验证，不是已上线接口。

## 五层分别解决什么

| 层 | 负责什么 |
| --- | --- |
| 基础设施层 | 支撑产品运行的技术、设备和服务。 |
| 数据层 | 数据存什么、怎么存，表字段和关联关系。 |
| 业务层 | 素材、编辑、脱敏、播放、交付和发布的核心逻辑。 |
| 应用接口层 | App 功能调用、HTTP API、数据包与 SDK/模板接入方式。 |
| 交互层 | 用户实际操作的 Android App 与观看网页。 |

## 基础设施层

- Android：Kotlin/Compose 原生创作和离线播放，Room 本地持久化；系统媒体能力处理录屏，WorkManager 调度可恢复任务。
- Web：TypeScript、React、Vite、原生图片/video，只观看，不增加网页编辑器。
- 托管：Node.js/TypeScript/Fastify、PostgreSQL、私有 S3 兼容存储；API/worker 共用代码，先做模块化单体。
- 动画：独立 TypeScript/React/Remotion 模板，在电脑或用户明确选择的云环境运行。
- SDK 的实际选择和限制见应用接口层；版本、最低系统、编码配置和服务商在使用前验证并固定，不提前开通收费服务。

模块按真实功能建立：`android/` 放 App，`contracts/` 放 schema/API，`runtime-ts/` 放纯图与播放规则，
`web-player/` 放观看界面，`server/` 放托管服务，`remotion-adapter/` 放受信模板。
Android 初期按包分开，必要时再拆 Gradle 模块；不提前搭微服务、消息总线、复杂注入或通用插件体系。

## 数据层

先看这张关系总览，逐字段类型、主外键和索引在下方对应表名中展开。
原片、原 OCR、敏感草稿及缓存只在本机私有域；服务端只持有主动发布的安全内容和必要账号数据。

| 位置 / 表 | 用途 | 主要关系 |
| --- | --- | --- |
| 本机 `projects` | 项目与当前活动草稿 | 一项目多来源、状态和任务，起点指向本项目状态 |
| 本机 `sources` | 私有原片/截图及分析结果 | 属于项目，被状态与录制边引用 |
| 本机 `states` | 具体画面状态 | 引用来源帧或导入安全资产，持有热点与区域 |
| 本机 `hotspots` | 点击矩形和标签 | 属于状态，通过 edges 关联目标 |
| 本机 `edges` | 分支动作和可选过渡 | 从状态指向目标状态或结束结果 |
| 本机 `redactions` | 固定遮挡块 | 每块属于一个状态或一条视频边 |
| 本机 `regions` | 安全图上的可见裁片 | 属于状态，绑定安全底图及摘要 |
| 本机 `local_assets` | 重检后的固定安全文件 | 由候选、区域和已封存版本引用 |
| 本机 `reviews` | 绑定输出字节的复核 | 关联项目、任务及固定快照内对象 |
| 本机 `local_jobs` | 长任务与恢复检查点 | 固定草稿修订或版本，不随继续编辑变化 |
| 本机 `local_releases` | 不可变版本、离线库和断点 | 可关联来源项目，引用安全资产 |
| 服务端 `accounts` | 邮箱发布管理账号 | 拥有会话和托管项目 |
| 服务端 `auth_challenges` | 短期一次性验证码挑战 | 绑定规范化邮箱，成功验证可创建会话 |
| 服务端 `sessions` | 可过期/撤销的管理会话 | 归属账号，token 仅存摘要 |
| 服务端 `hosted_projects` | 托管版本归组 | 归属账号，映射本机项目 ID |
| 服务端 `publication_uploads` | 上传、配额预留、校验任务 | 关联所有者/托管项目，生成一个固定版本 |
| 服务端 `publication_assets` | 上传与固定版本所需资产 | 经 upload 关联版本，对象始终私有 |
| 服务端 `releases` | 不可变发布内容 | 属于项目，经 upload 定位安全资产 |
| 服务端 `shares` | 一个版本的持链访问记录 | 指向 release，保存准确到期及撤销时间 |
| 服务端 `share_revocations` | 防止灾备恢复复活旧链接 | 保留 share 撤销事实，不随业务删除级联清除 |

### 1. Android 本机：一份可变草稿，多份固定版本

Project 与单活动 Draft 合并到 `projects`，不另建 `drafts`。状态、边等是该项目当前草稿的行；`draft_revision` 每次编辑事务递增。任务保存固定输入快照；已封存版本保存在 `local_releases`，不随草稿更新。复制项目、脱敏包复制、AI 回流都新建 `project_id`，保留导入对象的稳定 ID 以比较差异，绝不覆盖旧草稿。

字段表明确“必填/可空”；未写默认值的必填字段由创建操作提供。Room/SQLite 用 `TEXT` 存 UUID、枚举、SHA-256 十六进制串和 JSON，`INTEGER` 存 Long、Int 和 0/1，`REAL` 存有限数。时间 `*_at` 为 UTC epoch 毫秒；源帧 `*_pts_us` 为解码得到的真实 presentation timestamp，不能按名义帧率推算。所有文件列只存受控私有目录的相对路径。源片、DB、原 OCR、预览缓存、撤销历史和凭据排除系统云备份及设备迁移；账号不会恢复这些数据。

#### 1.1 项目、素材、状态

<details>
<summary>projects：本地项目与活动草稿</summary>

| 字段 | 类型 / 必填 | 用途与字段约束 |
|---|---|---|
| `project_id` | `TEXT` / 必填 | 本地项目/活动草稿的主键 |
| `title` | `TEXT` / 必填 | 显示标题 |
| `goal` | `TEXT` / 必填 | 一句演示目标 |
| `created_at` | `INTEGER` / 必填 | 创建时间 |
| `updated_at` | `INTEGER` / 必填 | 最后修改时间 |
| `draft_revision` | `INTEGER` / 必填 | 当前草稿版本，编辑事务递增； 默认 1 |
| `start_state_id` | `TEXT` / 可空 | 起点状态，可暂缺但不能交付 |
| `origin_release_id` | `TEXT` / 可空 | 复制/回流所依据的版本标识 |
| `draft_config_json` | `TEXT` / 必填 | 纳入子图、关键路径、AI 配置，结构见 JSON 表 |
| `undo_json` | `TEXT` / 可空 | 最近一次编辑的逆操作，结构见 JSON 表 |

键、索引与关系：PK `project_id`；索引 `updated_at`。`(project_id,start_state_id)` 延迟 FK → `states`，允许尚无起点。`origin_release_id` 只记导入来源，不要求该外部版本仍在本机。保存状态由待提交编辑与当前 revision 得出，不存永久“已保存”布尔值。

</details>

<details>
<summary>sources：私有源素材</summary>

| 字段 | 类型 / 必填 | 用途与字段约束 |
|---|---|---|
| `project_id` | `TEXT` / 必填 | 所属本地项目 |
| `source_id` | `TEXT` / 必填 | 私有源素材标识 |
| `kind` | `TEXT` / 必填 | 对象类别，取值见本行；`video/image` |
| `private_relpath` | `TEXT` / 可空 | 私有源副本路径；缺失时可空 |
| `display_name` | `TEXT` / 必填 | 原文件名，只在本机显示 |
| `mime` | `TEXT` / 必填 | 检测并允许的真实媒体类型 |
| `byte_length` | `INTEGER` / 必填 | 文件实际字节数 |
| `sha256` | `TEXT` / 必填 | 实际文件字节摘要 |
| `width` | `INTEGER` / 必填 | 图像/视频内容宽度（像素） |
| `height` | `INTEGER` / 必填 | 图像/视频内容高度（像素） |
| `rotation_deg` | `INTEGER` / 必填 | 源素材旋转元数据 |
| `duration_ms` | `INTEGER` / 可空 | 媒体时长毫秒；静态图为空 |
| `trim_start_ms` | `INTEGER` / 可空 | 引用区间起点，包含 |
| `trim_end_ms` | `INTEGER` / 可空 | 引用区间终点，不包含 |
| `analysis_json` | `TEXT` / 可空 | 原 OCR 与候选结果，只在本机 |
| `imported_at` | `INTEGER` / 必填 | 导入完成时间 |
| `availability` | `TEXT` / 必填 | 是否还有可用源文件；`present/missing` |

键、索引与关系：PK `(project_id,source_id)`；FK `project_id` → `projects`。视频必须有时长和有效裁剪区间，截图这些列为空。缺失原片仍保留来源记录，阻止重新取帧，不伪造源文件。真实文件名及原 OCR 仅本机可见。

</details>

<details>
<summary>states：画面状态</summary>

| 字段 | 类型 / 必填 | 用途与字段约束 |
|---|---|---|
| `project_id` | `TEXT` / 必填 | 所属本地项目 |
| `state_id` | `TEXT` / 必填 | 画面状态的稳定标识 |
| `sort_order` | `INTEGER` / 必填 | 显示顺序，不是身份 |
| `title` | `TEXT` / 必填 | 显示标题 |
| `description` | `TEXT` / 必填 | 步骤说明，纯文本 |
| `source_kind` | `TEXT` / 必填 | 录制、作者编排、导入或待补录依据；`recorded/authored/imported/missing` |
| `source_id` | `TEXT` / 可空 | 私有源素材标识 |
| `frame_pts_us` | `INTEGER` / 可空 | 实际选中代表帧的源时间戳 |
| `input_asset_id` | `TEXT` / 可空 | 从脱敏包导入时的安全底图 |
| `canvas_width` | `INTEGER` / 必填 | 规范化内容画布宽度 |
| `canvas_height` | `INTEGER` / 必填 | 规范化内容画布高度 |
| `is_terminal` | `INTEGER` / 必填 | 是否明确结束状态； 默认 0 |
| `confirmed_at` | `INTEGER` / 可空 | 作者确认该对象/复核的时间 |
| `content_revision` | `INTEGER` / 必填 | 本对象内容版本，用于关联失效 |

键、索引与关系：PK `(project_id,state_id)`；FK 项目、`(project_id,source_id)` → `sources`、`input_asset_id` → `local_assets`；索引 `(project_id,sort_order)`、`(project_id,source_id)`。录制帧有 source+PTS；作者截图有 source；安全包导入用 input_asset；待补录不假装已有画面。排序不改 ID。确认或素材变化须更新 revision；依据/目标/热点坐标变化时清空受影响对象的 confirmed_at，要求重新确认。

</details>

#### 1.2 热点、边、遮挡与区域

<details>
<summary>hotspots：点击区域</summary>

| 字段 | 类型 / 必填 | 用途与字段约束 |
|---|---|---|
| `project_id` | `TEXT` / 必填 | 所属本地项目 |
| `hotspot_id` | `TEXT` / 必填 | 热点标识；边可以只通过文字选择 |
| `state_id` | `TEXT` / 必填 | 画面状态的稳定标识 |
| `label` | `TEXT` / 必填 | 可读操作标签 |
| `x` | `REAL` / 必填 | 归一化矩形左上角横坐标 |
| `y` | `REAL` / 必填 | 归一化矩形左上角纵坐标 |
| `width` | `REAL` / 必填 | 热点矩形归一化宽度 |
| `height` | `REAL` / 必填 | 热点矩形归一化高度 |
| `confirmed_at` | `INTEGER` / 可空 | 作者确认该对象/复核的时间 |
| `content_revision` | `INTEGER` / 必填 | 本对象内容版本，用于关联失效 |

键、索引与关系：PK `(project_id,hotspot_id)`；FK `(project_id,state_id)` → `states`；索引同 FK。矩形相对内容画布，0–1、正面积且不越界；没有脚本或业务条件。一个热点对应几条边由 `edges.hotspot_id` 查询，不另存重复 edgeIds。

</details>

<details>
<summary>edges：动作、分支与录制过渡</summary>

| 字段 | 类型 / 必填 | 用途与字段约束 |
|---|---|---|
| `project_id` | `TEXT` / 必填 | 所属本地项目 |
| `edge_id` | `TEXT` / 必填 | 稳定动作/边标识 |
| `from_state_id` | `TEXT` / 必填 | 源状态 |
| `to_state_id` | `TEXT` / 可空 | 目标状态，与 end_label 二选一 |
| `end_label` | `TEXT` / 可空 | 直接结束结果，与 to_state_id 二选一 |
| `hotspot_id` | `TEXT` / 可空 | 热点标识；边可以只通过文字选择 |
| `label` | `TEXT` / 必填 | 可读操作标签 |
| `trigger` | `TEXT` / 必填 | 显式触发形式，无任意条件表达式；`tap/choice/continue` |
| `source_kind` | `TEXT` / 必填 | 录制、作者编排、导入或待补录依据；`recorded/authored/imported` |
| `source_id` | `TEXT` / 可空 | 私有源素材标识 |
| `source_start_pts_us` | `INTEGER` / 可空 | 录制证据/过渡的真实起点 |
| `source_end_pts_us` | `INTEGER` / 可空 | 录制证据/过渡的真实终点，不包含 |
| `transition_input_asset_id` | `TEXT` / 可空 | 安全包中已存在的过渡资产 |
| `use_transition` | `INTEGER` / 必填 | 是否播放绑定短视频； 默认 0 |
| `confirmed_at` | `INTEGER` / 可空 | 作者确认该对象/复核的时间 |
| `content_revision` | `INTEGER` / 必填 | 本对象内容版本，用于关联失效 |

键、索引与关系：PK `(project_id,edge_id)`；复合 FK 到本项目的 from/to state、hotspot、source；导入过渡 FK → `local_assets`。索引 `(project_id,from_state_id)`、`(project_id,to_state_id)`、`(project_id,hotspot_id)`、`(project_id,source_id)`。`to_state_id` 与 `end_label` 恰有一个；同热点多结果显示选择面板。录制边保留证据区间；过渡可省略，不能因此丢掉来源证据。

</details>

<details>
<summary>redactions：固定不透明遮挡</summary>

| 字段 | 类型 / 必填 | 用途与字段约束 |
|---|---|---|
| `project_id` | `TEXT` / 必填 | 所属本地项目 |
| `redaction_id` | `TEXT` / 必填 | 一块固定遮挡的标识 |
| `state_id` | `TEXT` / 可空 | 画面状态的稳定标识 |
| `edge_id` | `TEXT` / 可空 | 稳定动作/边标识 |
| `x` | `REAL` / 必填 | 归一化矩形左上角横坐标 |
| `y` | `REAL` / 必填 | 归一化矩形左上角纵坐标 |
| `width` | `REAL` / 必填 | 遮挡矩形的归一化宽度 |
| `height` | `REAL` / 必填 | 遮挡矩形的归一化高度 |
| `color_argb` | `INTEGER` / 必填 | 遮挡颜色，alpha 必须 255 |
| `content_revision` | `INTEGER` / 必填 | 本对象内容版本，用于关联失效 |

键、索引与关系：PK `(project_id,redaction_id)`；两个目标恰有一个非空，分别复合 FK → `states/edges`；索引两个目标 FK。每行一块遮挡，多块用多行；坐标按目标内容画布归一化，alpha 必须 255。视频遮挡覆盖所选整段，不提供逐帧运动跟踪配置。

</details>

<details>
<summary>regions：安全画面的可见区域</summary>

| 字段 | 类型 / 必填 | 用途与字段约束 |
|---|---|---|
| `project_id` | `TEXT` / 必填 | 所属本地项目 |
| `region_id` | `TEXT` / 必填 | 可见区域标识 |
| `state_id` | `TEXT` / 必填 | 画面状态的稳定标识 |
| `base_asset_id` | `TEXT` / 必填 | 已经脱敏的底图 |
| `base_sha256` | `TEXT` / 必填 | 区域所依赖底图的字节摘要 |
| `name` | `TEXT` / 必填 | 区域名称，纯文本 |
| `group_name` | `TEXT` / 可空 | 可选的区域分组 |
| `x_px` | `INTEGER` / 必填 | 源安全底图中的像素左坐标 |
| `y_px` | `INTEGER` / 必填 | 源安全底图中的像素上坐标 |
| `width_px` | `INTEGER` / 必填 | 裁片像素宽度 |
| `height_px` | `INTEGER` / 必填 | 裁片像素高度 |
| `source_width` | `INTEGER` / 必填 | 安全底图真实宽度 |
| `source_height` | `INTEGER` / 必填 | 安全底图真实高度 |
| `z_index` | `INTEGER` / 必填 | 展示层级次序 |
| `anchor_x` | `REAL` / 必填 | 裁片局部 0–1 横向锚点 |
| `anchor_y` | `REAL` / 必填 | 裁片局部 0–1 纵向锚点 |
| `content_revision` | `INTEGER` / 必填 | 本对象内容版本，用于关联失效 |

键、索引与关系：PK `(project_id,region_id)`；FK state 与 `base_asset_id` → `local_assets`；索引 `(project_id,state_id)`。bbox 是安全底图像素；anchor 是裁片局部 0–1。base 哈希/真实尺寸必须匹配；底图变化则区域待重做。类型固定为 `screenshotCrop`，不为一个常量另建表。

</details>

state、edge、hotspot、source 等草稿对象的跨项目引用都拒绝。`local_assets` 是全局不可变安全资源池，复制项目可显式共享同一安全资产；只在所有草稿、版本、任务和撤销快照的引用均释放后清理。封存时校验纳入图的每个分支均有终点或明确出口；允许显式回访，未确认候选、待补录目标和未解释的不可达节点均阻断交付。热点与 edge 必须属于同一个源状态；由编辑事务和图校验共同检查。删除节点先在同一事务明确处理引用，或保留 `missing` 待补录节点；外键不能通过静默级联删掉分支。项目整体删除按引用顺序处理本地行，不发送撤销 API。引用资产仍被版本使用时不得清理。

#### 1.3 实际成品、复核、任务与版本

<details>
<summary>local_assets：固定安全派生文件</summary>

| 字段 | 类型 / 必填 | 用途与字段约束 |
|---|---|---|
| `asset_id` | `TEXT` / 必填 | 安全派生资产标识 |
| `relative_path` | `TEXT` / 必填 | 受控相对路径，不接受外链 |
| `role` | `TEXT` / 必填 | 安全资产用途；`state_image/transition/thumbnail/cover/region_crop` |
| `mime` | `TEXT` / 必填 | 检测并允许的真实媒体类型 |
| `byte_length` | `INTEGER` / 必填 | 文件实际字节数 |
| `sha256` | `TEXT` / 必填 | 实际文件字节摘要 |
| `width` | `INTEGER` / 必填 | 图像/视频内容宽度（像素） |
| `height` | `INTEGER` / 必填 | 图像/视频内容高度（像素） |
| `duration_ms` | `INTEGER` / 可空 | 媒体时长毫秒；静态图为空 |
| `input_fingerprint` | `TEXT` / 必填 | 输入与配置依赖指纹，不能替代输出摘要 |
| `created_at` | `INTEGER` / 必填 | 创建时间 |
| `verified_at` | `INTEGER` / 必填 | 实际文件重检成功时间 |

键、索引与关系：PK `asset_id`；唯一 `relative_path`；索引 `sha256`。仅完整输出并重检后入表，临时文件留在 job 工作区。每行对应固定字节，重编码成不同 bytes 就是新资产。输入指纹只用于判断依赖，不能代替输出哈希。

</details>

<details>
<summary>reviews：实际输出的复核记录</summary>

| 字段 | 类型 / 必填 | 用途与字段约束 |
|---|---|---|
| `review_id` | `TEXT` / 必填 | 本机复核记录标识 |
| `project_id` | `TEXT` / 可空 | 所属本地项目；独立成品导出复核可空 |
| `job_id` | `TEXT` / 必填 | 固定快照任务标识 |
| `kind` | `TEXT` / 必填 | 对象类别，取值见本行；`state/transition/graph/delivery` |
| `subject_id` | `TEXT` / 必填 | 对应快照内的复核对象 |
| `input_fingerprint` | `TEXT` / 必填 | 输入与配置依赖指纹，不能替代输出摘要 |
| `output_digest` | `TEXT` / 必填 | 实际派生文件、文字及范围的组合摘要 |
| `policy_version` | `TEXT` / 必填 | 使用的安全/容量政策版本 |
| `compiler_version` | `TEXT` / 必填 | 生成派生资产的编译器版本 |
| `scope_json` | `TEXT` / 必填 | 复核的实际资产、文字与图覆盖 |
| `confirmed_at` | `INTEGER` / 必填 | 作者确认该对象/复核的时间 |
| `invalidated_at` | `INTEGER` / 可空 | 已知失效时间；仍须实时比较摘要 |
| `invalidated_reason` | `TEXT` / 可空 | 用于定位的失效原因 |

键、索引与关系：PK `review_id`；可空项目 FK、必填 job FK；索引 `(project_id,kind,subject_id)`、`job_id`。subject 按 kind 指向固定快照内的 state/edge/图/交付，不是可变行的 FK。通过与否用当前依赖、实际输出、策略版本比较；失效原因用于 UI 定位。output_digest 对按 assetId 排序的实际资产清单、textDigest/configDigest 及该 kind 的范围字段做规范化摘要；确认后这些范围字段不可改写。

</details>

<details>
<summary>local_jobs：固定快照与可恢复任务</summary>

| 字段 | 类型 / 必填 | 用途与字段约束 |
|---|---|---|
| `job_id` | `TEXT` / 必填 | 固定快照任务标识 |
| `project_id` | `TEXT` / 可空 | 所属本地项目 |
| `kind` | `TEXT` / 必填 | 对象类别，取值见本行；`analyze/compile/export/upload/import` |
| `draft_revision` | `INTEGER` / 可空 | 当前草稿版本，编辑事务递增 |
| `release_id` | `TEXT` / 可空 | 不可变成品版本标识 |
| `owner_account_id` | `TEXT` / 可空 | 该上传任务绑定的账号，无凭据 |
| `input_fingerprint` | `TEXT` / 必填 | 输入与配置依赖指纹，不能替代输出摘要 |
| `input_snapshot_json` | `TEXT` / 必填 | 固定输入快照，不能读取正在变动的草稿代替 |
| `status` | `TEXT` / 必填 | 对象状态，取值见本行；`queued/running/paused/waiting_review/succeeded/failed/cancelled` |
| `stage` | `TEXT` / 必填 | 当前任务步骤 |
| `completed_units` | `INTEGER` / 必填 | 已实际完成的资产/步骤数 |
| `total_units` | `INTEGER` / 可空 | 已知的总单位数；未知时空 |
| `attempt` | `INTEGER` / 必填 | 尝试次数 |
| `checkpoint_json` | `TEXT` / 必填 | 已完成资产、临时文件和服务器回执 |
| `idempotency_key` | `TEXT` / 可空 | 上传创建的稳定重试键 |
| `error_code` | `TEXT` / 可空 | 机器可读失败原因 |
| `retryable` | `INTEGER` / 必填 | 是否可在当前输入下重试 |
| `created_at` | `INTEGER` / 必填 | 创建时间 |
| `updated_at` | `INTEGER` / 必填 | 最后修改时间 |

键、索引与关系：PK `job_id`；项目 FK；release 是任务输入/预分配输出标识，未封存时不作 FK。索引 `(status,updated_at)`、`(project_id,created_at)`；上传幂等键按账号唯一。`stage` 只使用当前 kind 的步骤，见下表；完成数是实际资产/步骤数，不假装精确编码百分比。

</details>

<details>
<summary>local_releases：不可变版本与离线库</summary>

| 字段 | 类型 / 必填 | 用途与字段约束 |
|---|---|---|
| `release_id` | `TEXT` / 必填 | 不可变成品版本标识 |
| `project_id` | `TEXT` / 可空 | 所属本地项目 |
| `source_draft_revision` | `INTEGER` / 可空 | 成品所依据的本机草稿版本 |
| `origin` | `TEXT` / 必填 | 本机生成或包导入；`local/imported` |
| `schema_version` | `TEXT` / 必填 | 支持的交换格式版本 |
| `policy_version` | `TEXT` / 必填 | 使用的安全/容量政策版本 |
| `content_digest` | `TEXT` / 必填 | 规范化 scene 的摘要，含资产摘要 |
| `scene_json` | `TEXT` / 必填 | 安全 Scene，结构见第 2 节；不是包 manifest |
| `asset_ids_json` | `TEXT` / 必填 | 版本引用的完整安全资产 ID 清单 |
| `sealed_at` | `INTEGER` / 必填 | 固定版本封存时间 |
| `last_opened_at` | `INTEGER` / 可空 | 演示库最近打开时间，不属于成品内容 |
| `playback_checkpoint_json` | `TEXT` / 可空 | 播放断点，不属于成品内容 |

键、索引与关系：PK `release_id`；项目 FK `ON DELETE SET NULL`；索引 `(project_id,sealed_at)`、`last_opened_at`。只保存封存/验证过的版本，不把 candidate 当成 release。除列表/播放断点元数据外，标识、内容、摘要、资产引用不可更新。导入同 ID 不同摘要必须拒绝。

</details>

任务阶段：`analyze`＝`inspect/extract/ocr/suggest`；`compile`＝`snapshot/media/derived_assets/recheck/review/seal`；`export`＝`assemble/verify/write`；`upload`＝`create/transfer/commit`；`import`＝`unpack/validate/install`。取消只清本次临时文件；进程中断重做未完成单资产。恢复后重新鉴权、核对输入指纹和已完成文件，不默认任务已经成功。

#### 1.4 JSON 列内到底保存什么

下表是上述 JSON 的固定结构草案；未知字段不作为可执行指令，也不能绕过校验。所有 ID 引用仍做语义校验。

| JSON 列 | 结构与字段 |
|---|---|
| `projects.draft_config_json` | `{includedStateIds:[id], excludedStates:[{stateId,reason}], criticalPaths:[{pathId,edgeIds:[id]}], ai:{canvasPreset:"portrait1080"\|"landscape1080", fps:30, visits:[{visitId,stateId,selectedEdgeId:null\|id,holdFrames}], effects:[Effect]}}`；ai 可空。显示被排除节点，不能静默遗漏。Effect 结构见第 2 节。 |
| `projects.undo_json` | `{beforeRevision,afterRevision,changes:[{table,key,previousRow}]}`。table 只允许本节可编辑的 projects/sources/states/edges/hotspots/redactions/regions；key 是该表完整 PK，previousRow 是上表完整行或 null（表示原先不存在）。保留最近一次可撤销编辑；其中 projects 行不递归保存 undo_json。只由本地代码产生。文件延迟清理到撤销引用释放后。 |
| `sources.analysis_json` | `{engine,modelVersion,samples:[{ptsUs,changeScore,blocks:[{text,bboxPx:{x,y,width,height},confidence:null\|number}]}], suggestions:[{kind:"state"\|"hotspot"\|"merge", sourcePtsUs, otherStateId:null\|id, rect:null\|{x,y,width,height}, label}]}`。仅分析采样点，不存整片每帧；原 OCR、原建议留本地。候选写入编辑对象后仍须人工确认。 |
| `reviews.scope_json` | 通用 `{assets:[{assetId,sha256,byteLength}],textDigest,configDigest}`；state 包含底图/缩略图/区域与对应文案；transition 另有 `{durationMs,fullClipReviewed:true,audioTracks:0}`；graph 另有 `{graphDigest,visitedEdgeIds:[id],completedCriticalPathIds:[id]}`；delivery 另有 `{sceneDigest,packageFileListDigest,coverAssetId:null\|id}`。这些是本机确认记录，不能从外部 JSON 继承信任。 |
| `local_jobs.input_snapshot_json` | 编译/分析：`{project:{projectId,revision,title,goal,startStateId,config},sources:[SourceRow（不复制 analysis_json）],states:[StateRow],edges:[EdgeRow],hotspots:[HotspotRow],redactions:[RedactionRow],regions:[RegionRow]}`，Row 对应上表字段；导出/上传：`{releaseId,contentDigest,exportKind,renderConfig,expiryDays,serverProjectId}`（只保留该任务需要的字段）；导入：`{inputRelativePath,inputSha256,importAs:"library"\|"draft"}`。 |
| `local_jobs.checkpoint_json` | `{completedAssets:[{assetId,sha256}],tempFiles:[relativePath],candidateReleaseId:null\|id,sceneDigest:null\|hash,uploadId:null\|id,receivedAssetIds:[id],serverReceipt:null\|{publicationId,releaseId,shareUrl,expiresAt},outputFile:null\|{relativePath,sha256,byteLength}}`。账号 token 不进此列；shareUrl 在私有 DB 内保护且不写日志。 |
| `local_releases.asset_ids_json` | `[assetId]`，必须与 scene 资产清单一一对应；引用只能指向完整 `local_assets`。Room 无法给 JSON 元素加 FK，由封存/导入事务及清理前引用扫描保证。 |
| `local_releases.playback_checkpoint_json` | `{contentDigest,currentStateId,visits:[{stateId,selectedEdgeId:null\|id}],phase:"ready"\|"ended"}`。不持久化半段视频播放回调；恢复从明确状态开始。在线恢复先重验 share，不能仅凭断点继续取媒体。 |

`BuildCandidate → ReviewCandidate → SealRelease` 的数据落点：compile job 固定 revision → 生成 immutable local_assets → 人审真实图片、完整视频及文案/派生物 → 写对应 reviews → 校验全部边与关键路径覆盖及摘要一致 → 插入 local_releases。任何重编码变更都必须重新比较实际 bytes；不能用“输入相同”沿用另一份输出的复核。任务运行中继续编辑只增加草稿 revision，交付页清楚显示 candidate 基于哪一版。

### 2. 交付 JSON：不把数据库行直接导出去

#### 2.1 固定版本与包的区别

`releaseId + contentDigest` 标识固定 `scene.json` 及其资产。contentDigest＝SHA-256(JCS 规范化的 scene JSON UTF-8)，scene 内包含全部资产 SHA-256，但不包含 contentDigest 自身。数据库 JSONB 的内部顺序或普通 stringify 结果不能直接拿来做摘要；采用 [RFC 8785 JCS](https://www.rfc-editor.org/rfc/rfc8785)。图指纹同样只针对明确字段的规范化结构计算。

离线包/AI 包的 `manifest.json` 是包外壳：`{schemaVersion,exportKind:"viewer"|"ai",releaseId,contentDigest,files:[{path,byteLength,sha256}]}`；必有 `scene.json` 和它引用的安全媒体。AI 包另外含 schema、README、`render-plan.json`。files 枚举除 manifest 本身外的所有文件，未声明文件也拒绝。导出包可另计算 packageDigest，不能把 AI README/路径配置变化偷换成原 release 的内容变化。只改有限访问路径可生成新的 AI 导出文件；改图、文案、区域底图或媒体则生成新 release。

#### 2.2 公开结构

| 对象 | 必须给消费方的字段 |
|---|---|
| `scene` | `schemaVersion, policyVersion, compilerVersion, releaseId, title, goal, createdAt, startStateId, states[], edges[], hotspots[], regions[], assets[]`；regions 可以空。 |
| `states[]` | `{id,imageAssetId,width,height,title,description,sourceKind:"recorded"\|"authored"\|"imported",terminal}`。没有原文件名、源路径、原 OCR、编辑历史或原片链接。 |
| `edges[]` | `{id,fromStateId,to:{stateId}\|{endLabel},hotspotId:null\|id,label,trigger:"tap"\|"choice"\|"continue",transitionAssetId:null\|id,sourceKind}`。仅输出已确认边；不输出待补录节点、脚本、真实输入规则。 |
| `hotspots[]` | `{id,stateId,label,coordinateSpace:"state-normalized",rect:{x,y,width,height}}`，0–1 坐标仅相对画面，不含播放器留白。 |
| `regions[]` | `{id,stateId,baseAssetId,assetId,name,kind:"screenshotCrop",coordinateSpace:"source-pixels",sourceWidth,sourceHeight,bbox:{x,y,width,height},group:null\|string,zIndex,anchor:{coordinateSpace:"layer-normalized",x,y}}`；底图和裁片都必须在 assets 中。 |
| `assets[]` | `{id,path,role,mime,byteLength,sha256,width,height,durationMs:null\|integer}`。path 只允许包内安全相对路径；图像 PNG/JPEG、视频受限 H.264/SDR MP4，无原音轨、字幕或任意数据轨。 |
| `render-plan.json` | `{schemaVersion,adapterVersion,compositionId,releaseId,contentDigest,fps:30,canvas:{width,height},visits:[{visitId,stateId,selectedEdgeId:null\|id,holdFrames}],effects:[Effect],timeline:[{visitId,startFrame,durationFrames,transitionFrames,overlapFrames}],totalFrames}`。两个画布预设为 1080×1920、1920×1080；末次访问明确结束，回访有不同 visitId。 |
| `Effect` | `{type:"click"\|"focus"\|"transition"\|"highlight"\|"annotation",visitId,startFrame,durationFrames,hotspotId:null\|id,regionId:null\|id,text:null\|string,rect:null\|{x,y,width,height}}`。rect 如存在统一为 state-normalized；不同 type 只接受其必需字段，拒绝未知效果、表达式与动态组件。 |

所有输出区间左闭右开；毫秒转输出帧统一 `floor(ms*fps/1000+0.5)`，转换一次，零帧区间拒绝。timeline 的 startFrame/durationFrames/totalFrames 由校验后的 visits、视频长度及 overlapFrames 重新推导并比对，不信任 AI 给定总时长。页面切换的重叠帧从总长扣除，普通标注不延长视频。图允许显式点击回访；线性动画必须是有限 visits。

离线包没有 HTML、JS、package.json、模板脚本、动态安装依赖或外部媒体 URL。导入时先隔离解包，拒绝路径穿越/重复路径/符号链接/嵌套归档、缺文件、哈希不符及异常膨胀；成功后才安装到演示库。AI 回流按兼容白名单字段构造新项目、新草稿，文字/连线/路径/区域差异可供查看；复核全部重新开始。P0 的 40 状态、80 边、每状态 6 热点、10 秒单过渡、60 秒过渡总量、50 MiB 包与解压资产上限沿用产品；解析深度/文件数/AI visits 上限由同一 policyVersion 补齐，不在此虚构已测容量。

### 3. PostgreSQL：只存账号、脱敏发布与上传状态

服务端没有 Source、原 OCR、本地草稿或原片备份表。UUID 为 `uuid`，摘要为 32 字节 `bytea`，时间为 `timestamptz`，JSON 为 `jsonb`。必填/可空按字段表。表中 `owner_id` 均由管理会话取得，不接受客户端冒填。

<details>
<summary>accounts：发布管理账号</summary>

| 字段 | 类型 / 必填 | 用途与字段约束 |
|---|---|---|
| `account_id` | `uuid` / 必填 | 发布管理账号标识 |
| `email` | `text` / 必填 | 账号邮箱，仅用于账号服务 |
| `email_normalized` | `text` / 必填 | 按约定规则规范化的邮箱 |
| `created_at` | `timestamptz` / 必填 | 创建时间 |
| `disabled_at` | `timestamptz` / 可空 | 账号禁用时间，空表示未禁用 |

键、索引与关系：PK account_id；唯一 email_normalized。规范化只做约定处理，不擅自合并邮箱的点号或 + 别名；邮箱不进入公开 manifest。

</details>

<details>
<summary>auth_challenges：一次性验证码</summary>

| 字段 | 类型 / 必填 | 用途与字段约束 |
|---|---|---|
| `challenge_id` | `uuid` / 必填 | 一次邮箱验证码挑战标识 |
| `email_normalized` | `text` / 必填 | 按约定规则规范化的邮箱 |
| `code_mac` | `bytea` / 必填 | 服务端密钥和挑战上下文生成的验证码摘要 |
| `attempt_count` | `smallint` / 必填 | 已尝试验证码次数 |
| `max_attempts` | `smallint` / 必填 | 本挑战允许的尝试上限 |
| `expires_at` | `timestamptz` / 必填 | 绝对到期时间，服务器判定 |
| `consumed_at` | `timestamptz` / 可空 | 验证码成功消费时间 |
| `created_at` | `timestamptz` / 必填 | 创建时间 |

键、索引与关系：PK challenge_id；索引 `(email_normalized,created_at)`、expires_at。验证码用服务器密钥及 challenge 上下文计算 MAC，不存明文；验证时锁行、限尝试、一次消费。到期和限速参数由服务配置返回。

</details>

<details>
<summary>sessions：可撤销管理会话</summary>

| 字段 | 类型 / 必填 | 用途与字段约束 |
|---|---|---|
| `session_id` | `uuid` / 必填 | 管理会话标识 |
| `account_id` | `uuid` / 必填 | 发布管理账号标识 |
| `token_hash` | `bytea` / 必填 | 查找/验证 bearer token 的摘要 |
| `created_at` | `timestamptz` / 必填 | 创建时间 |
| `expires_at` | `timestamptz` / 必填 | 绝对到期时间，服务器判定 |
| `revoked_at` | `timestamptz` / 可空 | 首次撤销生效时间，不能恢复为空 |

键、索引与关系：PK session_id；FK account_id；唯一 token_hash；索引 account_id、expires_at。P0 一个可撤销 bearer 会话即可，到期重新验证码登录，不额外承诺 refresh-token 协议。

</details>

<details>
<summary>hosted_projects：本人托管项目归组</summary>

| 字段 | 类型 / 必填 | 用途与字段约束 |
|---|---|---|
| `project_id` | `uuid` / 必填 | 服务端项目主键，即 API serverProjectId |
| `owner_id` | `uuid` / 必填 | 由管理会话确定的账号 |
| `client_project_id` | `uuid` / 必填 | 本机 project_id，仅用于归组映射 |
| `title` | `text` / 必填 | 显示标题 |
| `next_version` | `integer` / 必填 | 下一次服务器发布序号； 默认 1 |
| `created_at` | `timestamptz` / 必填 | 创建时间 |

键、索引与关系：PK project_id；FK owner_id；唯一 `(owner_id,client_project_id)` 和 `(project_id,owner_id)`。这是托管归组，不是草稿同步；异机仅靠 owner 列表即可管理。

</details>

<details>
<summary>publication_uploads：上传、配额预留与校验</summary>

| 字段 | 类型 / 必填 | 用途与字段约束 |
|---|---|---|
| `upload_id` | `uuid` / 必填 | 本次安全资产上传/校验标识 |
| `project_id` | `uuid` / 必填 | 所属托管项目 |
| `owner_id` | `uuid` / 必填 | 由管理会话确定的账号 |
| `release_id` | `uuid` / 必填 | 不可变成品版本标识 |
| `scene_json` | `jsonb` / 必填 | 安全 Scene，结构见第 2 节；不是包 manifest |
| `content_digest` | `bytea` / 必填 | 规范化 scene 的摘要，含资产摘要 |
| `expiry_days` | `smallint` / 必填 | 1、7、30 天之一 |
| `state` | `text` / 必填 | 实际上传/资产状态，取值见本行 |
| `idempotency_key` | `uuid` / 必填 | 上传创建的稳定重试键 |
| `request_digest` | `bytea` / 必填 | 创建请求摘要，防同键换请求 |
| `commit_key` | `uuid` / 可空 | commit 独立幂等键 |
| `commit_request_digest` | `bytea` / 可空 | commit 请求摘要 |
| `reserved_until` | `timestamptz` / 必填 | 上传截止与额度预留到期时间 |
| `created_at` | `timestamptz` / 必填 | 创建时间 |
| `commit_requested_at` | `timestamptz` / 可空 | 已接受发布意图的时间 |
| `committed_at` | `timestamptz` / 可空 | 正式发布事务完成时间 |
| `lease_token` | `uuid` / 可空 | worker 当前租约身份 |
| `lease_until` | `timestamptz` / 可空 | worker 租约有效期 |
| `attempt` | `integer` / 必填 | 尝试次数 |
| `next_attempt_at` | `timestamptz` / 可空 | 恢复/重试调度时间 |
| `error_code` | `text` / 可空 | 机器可读失败原因 |

键、索引与关系：PK upload_id；复合 FK `(project_id,owner_id)` → hosted_projects；唯一 `(owner_id,idempotency_key)`。部分唯一索引 `(owner_id,release_id) WHERE state IN ('receiving','validating')`；项目行锁下检查/关闭过期记录后创建。索引 `(project_id,state,reserved_until)`、`(state,next_attempt_at,lease_until)`。state 仅 `receiving/validating/committed/cancelled/rejected/expired`；expiry_days 仅 1/7/30。校验任务租约放在上传行，不另建通用服务任务平台。

</details>

<details>
<summary>publication_assets：每次上传的安全资产</summary>

| 字段 | 类型 / 必填 | 用途与字段约束 |
|---|---|---|
| `upload_id` | `uuid` / 必填 | 本次安全资产上传/校验标识 |
| `asset_id` | `uuid` / 必填 | 安全派生资产标识 |
| `relative_path` | `text` / 必填 | 受控相对路径，不接受外链 |
| `role` | `text` / 必填 | 安全资产用途 |
| `mime` | `text` / 必填 | 检测并允许的真实媒体类型 |
| `byte_length` | `bigint` / 必填 | 文件实际字节数 |
| `sha256` | `bytea` / 必填 | 实际文件字节摘要 |
| `width` | `integer` / 必填 | 图像/视频内容宽度（像素） |
| `height` | `integer` / 必填 | 图像/视频内容高度（像素） |
| `duration_ms` | `integer` / 可空 | 媒体时长毫秒；静态图为空 |
| `staging_key` | `text` / 可空 | 私有暂存对象 key，不对外暴露 |
| `final_key` | `text` / 可空 | 固定成品对象 key，不对外暴露 |
| `state` | `text` / 必填 | 实际上传/资产状态，取值见本行；`declared/received/verified` |
| `received_at` | `timestamptz` / 可空 | 实际字节接收完成时间 |
| `verified_at` | `timestamptz` / 可空 | 实际文件重检成功时间 |

键、索引与关系：PK `(upload_id,asset_id)`；FK upload_id；唯一 `(upload_id,relative_path)`、非空 final_key。声明来自 scene.assets，实际 bytes 必须匹配。release 经其 upload_id 定位资产；commit 后整组行和对象不可改写，避免重复一套 release_assets 表。存储 key 从不返回给观看者。

</details>

<details>
<summary>releases：服务端不可变版本</summary>

| 字段 | 类型 / 必填 | 用途与字段约束 |
|---|---|---|
| `release_id` | `uuid` / 必填 | 不可变成品版本标识 |
| `project_id` | `uuid` / 必填 | 所属托管项目 |
| `owner_id` | `uuid` / 必填 | 由管理会话确定的账号 |
| `upload_id` | `uuid` / 必填 | 本次安全资产上传/校验标识 |
| `version_ordinal` | `integer` / 必填 | 同托管项目的服务端显示序号 |
| `schema_version` | `text` / 必填 | 支持的交换格式版本 |
| `policy_version` | `text` / 必填 | 使用的安全/容量政策版本 |
| `scene_json` | `jsonb` / 必填 | 安全 Scene，结构见第 2 节；不是包 manifest |
| `content_digest` | `bytea` / 必填 | 规范化 scene 的摘要，含资产摘要 |
| `created_at` | `timestamptz` / 必填 | 创建时间 |

键、索引与关系：PK release_id；FK 上传与 `(project_id,owner_id)`；唯一 upload_id、`(project_id,version_ordinal)`。只在发布成功事务插入，所有内容列只写一次。客户端 ID 重用但摘要不同返回冲突，不覆盖旧版。

</details>

<details>
<summary>shares：观看链接、到期与撤销</summary>

| 字段 | 类型 / 必填 | 用途与字段约束 |
|---|---|---|
| `share_id` | `uuid` / 必填 | 分享/Publication 标识 |
| `release_id` | `uuid` / 必填 | 不可变成品版本标识 |
| `token_hash` | `bytea` / 必填 | 查找/验证 bearer token 的摘要 |
| `token_ciphertext` | `bytea` / 必填 | 供本人重取链接的加密 token |
| `token_key_version` | `text` / 必填 | 解密所用密钥版本 |
| `created_at` | `timestamptz` / 必填 | 创建时间 |
| `expires_at` | `timestamptz` / 必填 | commit 时间加 expiry_days，创建后不延期 |
| `revoked_at` | `timestamptz` / 可空 | 首次撤销生效时间，不能恢复为空 |

键、索引与关系：PK share_id；唯一 release_id、token_hash；FK release_id；索引 expires_at。P0 每个 release 只有一条分享。状态由 revoked_at/服务器时间推导 `active/revoked/expired`，无需双写 status。加密 token 用于本人异机重取链接，密钥不在数据库。

</details>

<details>
<summary>share_revocations：持久撤销事实</summary>

| 字段 | 类型 / 必填 | 用途与字段约束 |
|---|---|---|
| `sequence` | `bigint` / 必填 | 追加式撤销记录序号； 自增 |
| `share_id` | `uuid` / 必填 | 分享/Publication 标识 |
| `revoked_at` | `timestamptz` / 必填 | 首次撤销生效时间，不能恢复为空 |

键、索引与关系：PK sequence；唯一 share_id；撤销时与 shares 同事务追加。保留撤销事实，不因删业务行而级联删除。独立 WAL/撤销归档与恢复水位才能防旧备份复活链接；无法证明水位完整，恢复后旧链接全部保持禁用。单独多一张同库表本身不能解决灾备问题。

</details>

`scene_json` 就是第 2 节 scene；它含公开字段与资产清单，不包含本地 review、原素材来源和执行模板。GET /manifest 的返回字段是 scene，不是离线包外壳 manifest.json。资产索引列必须与 scene.assets 一致。服务端可验证格式/图/哈希/轨道/容量，不能证明人确实看过或保证所有隐私均被识别。

#### 上传、额度、封存的一个闭环

1. 建 upload 时锁 hosted_projects，计算“未撤销且未到期的 shares + 未到期的未完成预留”，最多 5。不能 count 后无锁 insert。每个 upload 占一个有期限的槽位；过期按服务器时间即失效，不能等清理任务。
2. 上传只写 private staging，单资产按声明长度流式限流并算哈希；总包与解压后资产均沿用 50 MiB 限额。失败重传该资产，P0 不另做分块续传协议。
3. commit 接受后，worker 用上传行租约校验完整 scene 与所有实际媒体；受资源限制的解码进程无外网。先把已验证 bytes 物化到不可变 private final key 并确认可读，再短事务锁项目与 upload，重验预留、取消状态、当前 lease_token、全部资产和摘要，分配序号、插入 release/share、标记 committed。该事务是链接可见的唯一时刻。
4. 校验中取消与最终提交争同一上传行锁：取消先成功就不得发布；提交先成功则取消返回已发布回执，必须另点撤销。临时/孤儿对象在确认无引用且过保留窗后清理，清理不替代访问鉴权。
5. 同幂等键同请求返回原结果；不同请求摘要返回冲突。过期且从未发布的上传允许重新预留并重传相同 release；已发布的 release 不能延长、复活或换字节。新一轮发布生成新 release/share。

## 业务层

业务功能的输入、动作和产出见 [产品模块](../README.md#业务模块)。这里保留跨模块必须一致的核心规则。

### 安全编译与恢复

项目持有可变草稿和私有源素材；状态、热点和边使用稳定 ID，排序和改标题不改变身份。
每个状态/过渡保留实际素材时间戳或作者编排来源；每个长任务绑定固定草稿版本，重试不覆盖后续编辑。

安全编译顺序：确认图 → 重编码脱敏媒体 → 从安全媒体生成封面/裁片 → 重解码检查 → 作者复核 → 固定版本。

- 复核依据是实际输出摘要、文字和相关配置；依赖改变就失效，不接受永久勾选或外部包的“已复核”声明。
- 对外交付只允许已复核安全资产；不能复制原片、仅裁剪容器索引或透传压缩帧。视频去掉全部原音频及未允许轨道。
- 成品预览与交付共用安全资产；仅本机来源回看和遮挡编辑可读取原素材。安全封面未就绪时显示中性占位。
- 可达路径必须有终点或明确出口；候选边、缺失目标、未解释的不可达内容及自动循环阻止交付。
- 包在隔离目录检查 schema、版本、引用、真实类型、大小和摘要，拒绝路径穿越、重复路径、嵌套归档、解压炸弹、脚本和外链。
- 全部通过后原子加入库；未知不兼容版本明确拒绝，不静默丢字段。哈希只证明一致性，不证明身份或隐私安全。
- 文案按纯文本显示，不进入 HTML、表达式或动态组件。JSON 大小/深度、条目数及动画总长在实现对应导入器时定额。
- 本地敏感数据排除云备份和迁移；SDK 流量和平台行为要实测。日志不记画面、OCR、路径、凭据或带凭据的完整链接。

文件与数据库没有共同事务：临时输出完成后原子迁移，记录检查点并在恢复时对账。
空间不足、取消、后台中断或过热时保留已保存内容，不生成损坏成品；重试和上传使用幂等标识。
数据库升级要验证迁移与中断恢复，不以清空用户数据解决迁移失败。


## 应用接口层

### 4. HTTP API：账号 → 上传 → 发布 → 管理 → 访问

以下路径和字段是拟议接口，不是已部署地址。管理鉴权统一 `Authorization: Bearer <sessionToken>`，每次校验会话和 owner；用另一个账号的 ID 请求资源统一 404。JSON 字段采用 camelCase；表字段用 snake_case。上传创建与 commit 使用 `Idempotency-Key`，客户端重试保持原键与请求。

| 方法与路径 | 鉴权、请求 | 成功返回 | 特有错误 |
|---|---|---|---|
| `GET /api/v1/capabilities` | 无账号；无 body | `schemaVersions,policyVersions,limits,expiryDays:[1,7,30],defaultExpiryDays:7` | 服务不可用 |
| `POST /api/v1/auth/challenges` | 无账号；`{email}` | 202 `{challengeId,expiresAt,resendAfterSeconds}`；已存在/新账号外观一致 | `RATE_LIMITED,INVALID_EMAIL` |
| `POST /api/v1/auth/sessions` | 无账号；`{challengeId,code}` | 201 `{sessionToken,expiresAt,account:{accountId,email}}`；首次验证可建账号 | `INVALID_CODE,CHALLENGE_EXPIRED,CHALLENGE_LOCKED` |
| `GET /api/v1/me` | 管理会话 | 200 `{accountId,email,sessionExpiresAt}` | `UNAUTHENTICATED` |
| `DELETE /api/v1/auth/sessions/current` | 管理会话 | 204；当前会话撤销后本机清 token | `UNAUTHENTICATED`；不删除本地项目 |
| `POST /api/v1/projects` | 管理会话；`{clientProjectId,title}`，以 clientProjectId 幂等；title 只用于首次创建 | 201 新建或 200 已有 `{serverProjectId,clientProjectId,title}`；已有项目返回已存 title | `VALIDATION_FAILED` |
| `GET /api/v1/projects?cursor=&limit=` | 管理会话 | 200 `{items:[{serverProjectId,clientProjectId,title,activePublicationCount,latestVersionOrdinal}],nextCursor}` | `INVALID_CURSOR` |
| `POST /api/v1/publication-uploads` | 管理会话、幂等键；`{serverProjectId,releaseId,contentDigest,expiryDays,scene:Scene}` | 201 `{uploadId,state:"receiving",reservedUntil,missingAssetIds}`；重试返回同 upload | `QUOTA_EXCEEDED,RELEASE_CONFLICT,SCHEMA_UNSUPPORTED,ASSET_MISMATCH` |
| `PUT /api/v1/publication-uploads/{uploadId}/assets/{assetId}` | 管理会话；原始媒体 bytes；`Content-Type,Content-Length,X-Content-SHA256` 与声明一致 | 200 `{assetId,state:"received",byteLength,sha256}`；同内容可重放 | `ASSET_NOT_DECLARED,ASSET_MISMATCH,UPLOAD_CLOSED` |
| `GET /api/v1/publication-uploads/{uploadId}` | 管理会话 | 200 `{uploadId,releaseId,state,stage,reservedUntil,assets:[{assetId,state}],missingAssetIds,error:null\|Error,publication:null\|Publication}` | 不存在/其他 owner 为 404 |
| `DELETE /api/v1/publication-uploads/{uploadId}` | 管理会话 | 200 `{uploadId,state:"cancelled"}`，重复取消仍成功 | `ALREADY_COMMITTED` 并附本人发布回执；不会暗中撤销 |
| `POST /api/v1/publication-uploads/{uploadId}/commit` | 管理会话、幂等键；`{releaseId,contentDigest}` | 202 `{uploadId,state:"validating",statusUrl,retryAfterSeconds}`；已完成则 200/首次同步完成 201 `Publication`；GET 最终取同一回执 | `UPLOAD_INCOMPLETE,UPLOAD_EXPIRED,ASSET_MISMATCH,VALIDATION_FAILED,IDEMPOTENCY_CONFLICT` |
| `GET /api/v1/projects/{serverProjectId}/publications?cursor=&limit=` | 管理会话 | 200 `{items:[Publication],nextCursor}`，含有效/已到期/已撤销版本 | 其他 owner 为 404 |
| `GET /api/v1/publications/{publicationId}` | 管理会话 | 200 `Publication`，用于回执恢复/重新复制链接 | 其他 owner 为 404 |
| `POST /api/v1/publications/{publicationId}/revoke` | 管理会话；无 body | 200 `{publicationId,status:"revoked",revokedAt,serverConfirmedAt}`，重复返回首次 revokedAt | `SERVICE_UNAVAILABLE` 时不能显示已撤销 |
| `GET /s/{shareToken}` | 持链 token，无管理账号 | 200 播放器页面；加载前检查 share/release | `SHARE_EXPIRED,SHARE_REVOKED,NOT_FOUND` |
| `GET /s/{shareToken}/manifest` | 同上 | 200 `{releaseId,contentDigest,versionOrdinal,expiresAt,scene:Scene}` | 同上；缺失成品失败关闭 |
| `GET/HEAD /s/{shareToken}/assets/{assetId}` | 同上，支持 `Range` | 200/206 媒体或 HEAD 元数据；只从该 release 的资产集合读 | 同上、`ASSET_NOT_FOUND,RANGE_NOT_SATISFIABLE` |

`Publication`＝`{publicationId,serverProjectId,releaseId,versionOrdinal,contentDigest,title,createdAt,expiresAt,status:"active"|"revoked"|"expired",shareUrl,revokedAt:null|timestamp}`。`publicationId` 就是 shares.share_id，不再引入另一种 publication 身份。hosted project 的首建幂等由 `(owner_id,client_project_id)` 实现，相同 clientProjectId 始终返回已建 serverProjectId 和已存 title；title 仅首建使用，后续本地重命名不会卡住发布，也不会自动改服务端归组名。

通用失败体：`{error:{code,message,subjectId:null|string,pointer:null|string,retryable,traceId}}`。常用 HTTP 映射：400 输入错误；401 会话失效；404 不存在/无权管理；409 幂等冲突、上传不完整、资源状态冲突或配额已满；410 分享到期/撤销、上传到期；413 超大小；422 schema/图/媒体校验失败；429 限速并给 Retry-After；503 依赖不可用。`REVIEW_STALE` 是本地交付阻断，服务端不凭客户端复核标记发放可信认证。

#### 一次 commit 的短例子

以下 ID、域名和摘要只用于说明形状，不能当成已上线链接。客户端已经上传全部声明资产，提交后即允许服务继续校验并发布，无需再点第二次确认。

```http
POST /api/v1/publication-uploads/41efba86-9bb0-4ee5-a057-a94494933fa9/commit
Authorization: Bearer <session-token>
Idempotency-Key: 948d087f-c975-4e24-87f6-4b73b2343d2e
Content-Type: application/json

{"releaseId":"33360065-5837-4355-83df-4e595c9cd38d","contentDigest":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}
```

```json
{
  "publicationId": "b8dc3547-e225-41d9-bb6b-573469353b3e",
  "serverProjectId": "53a5fef3-e0d4-44e4-a96d-e9b843595e74",
  "releaseId": "33360065-5837-4355-83df-4e595c9cd38d",
  "versionOrdinal": 2,
  "contentDigest": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
  "title": "报名流程",
  "createdAt": "2026-10-09T08:00:00Z",
  "expiresAt": "2026-10-16T08:00:00Z",
  "status": "active",
  "shareUrl": "https://tapscene.example/s/example-token",
  "revokedAt": null
}
```

初次可能先返回 202；上例是校验完成后的同一 Publication 回执。回执丢失，GET upload 或同键重发 commit 都返回这一个版本。7 天从服务器真正 commit 的时间算，不能从开始上传算。

#### 每条资产请求都检查撤销

HTML、manifest、封面、缩略图、图片、视频，以及 HEAD/Range/条件请求，都先读强一致数据库确认 `revoked_at IS NULL AND now() < expires_at`，再检查资产确属该 release。拒绝 302 到对象存储下载地址，P0 不用缓存正向授权或绕过网关的公开 CDN。保护内容 `Cache-Control: private, no-store`，无离线缓存服务工作线程；程序 JS/CSS 可独立缓存。退出历史缓存/恢复断点/页面再可见时重新检查。

撤销成功只在数据库事务提交后报告；撤销提交后才开始授权检查的新请求必拒绝。撤销前已批准的传输、已下载包或截图不能收回。token 是持链权限而非观看者身份；URL/Referer/日志不泄露 token，观看页不加载第三方统计或远程字体。数据库或授权服务不可用即失败关闭。

### 5. SDK：采用哪些现成库，对外又提供什么

#### 5.1 实际依赖选择

| 位置 | 拟采用的真实库/API | 本项目用法与边界 |
|---|---|---|
| Android UI/并发 | Kotlin、Jetpack Compose、Navigation、ViewModel、kotlinx.coroutines/Flow | 原生编辑/播放器，UI 调用本地用例；不为每个动作造一套独立模块。 |
| 本机存储 | AndroidX Room、kotlinx.serialization | Room Entity/DAO/事务与迁移；serialization 只做 DTO 编解码，不等于已经完成 schema、图、路径及容量校验。[Room 官方文档](https://developer.android.com/training/data-storage/room/defining-data) |
| 媒体 | Android MediaExtractor/MediaCodec、Bitmap/Canvas；AndroidX Media3 ExoPlayer、Transformer | 解码真实 PTS；静图重新编码；视频逐帧烧录固定不透明遮挡并去音轨。Transformer 有自动 transmux 和裁剪优化，不能仅设 MIME 就称真脱敏；禁用原样本复制/编辑列表保留路径，不能保证时改显式 codec 管线。[Media3 官方文档](https://developer.android.com/media/media3/transformer/transformations) |
| OCR | 条件首选 ML Kit Text Recognition 的 bundled Latin/中文模型 | 模型随安装以支持首用离线；SDK 官方说明会发送性能/使用指标。只有能按支持方式禁用非必要外传并实际验证才采用；不能满足就换可控端侧 OCR，不暗中转云，也不把手工兜底当永久删除 OCR。[模型接入](https://developers.google.com/ml-kit/vision/text-recognition/v2/android)、[隐私说明](https://developers.google.com/ml-kit/terms) |
| 任务调度 | AndroidX WorkManager＋Coroutines | 本项目 local_jobs 是恢复依据；WorkManager 只负责符合系统约束的调度，不承诺 force-stop 后继续或无限后台运行。[长任务约束](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running) |
| HTTP/服务端 | Android HTTP 客户端拟用 OkHttp；Web 原生 fetch；TypeScript/Node.js/Fastify、PostgreSQL、Ajv | 客户端只上传固定安全 release；服务模块化单体，API/校验 worker 共用构建；不为 P0 引入 Redis、消息总线或公开媒体 CDN。 |
| 动画 | 独立 TypeScript/React/Remotion adapter | 用固定 Composition、calculateMetadata、staticFile 与 Node renderMedia；与数据包、Android 安装包分开分发和运行。[元数据](https://www.remotion.dev/docs/calculate-metadata)、[本地资产](https://www.remotion.dev/docs/staticfile)、[渲染](https://www.remotion.dev/docs/renderer/render-media) |

精确依赖版本、Android min/target SDK、编码配置、浏览器与设备范围在实现中锁定；目前没有经验证的版本组合。不编造“已完成”的方法、基准或兼容结论。Remotion 许可需按实际使用方式另核对，默认本机运行不等于许可天然免费。

#### 5.2 自有消费接口草案，不承诺 P0 通用 SDK

P0 对外交付是纯数据包、格式说明和独立受信 Remotion 模板。下列是准备实现的契约名称，不是已发布 npm/Maven 包或已可调用 SDK；Android/TS 可在内部实现少量对应函数，不建立插件体系。

| 契约 | 输入 | 输出/错误 | 必须满足 |
|---|---|---|---|
| `loadPackage(input, limits)` | 本机包文件/受控文件流 | `LoadedPackage{manifest,scene,assets,renderPlan?}` 或路径/容量/文件清单错误 | 在隔离区读取；不执行任何包内文件，不联网补缺媒体；assets 是受控句柄而非任意 URL。 |
| `validatePackage(loaded, supportedVersions)` | load 结果＋消费方内置能力/策略 | `ValidatedScene` 或 `issues[{code,subjectId,pointer,message}]` | 使用消费方自己固定的受信 schema；AI 包所附 schema 只作说明，不编译任意包内 schema，不解析远程 `$ref`。校验资产、图、坐标、有限 visits 和效果白名单。 |
| `buildRenderPlan(validatedScene, config)` | 固定 release＋选中 visits/画布/节奏/效果 | 解析后的 `RenderPlan{timeline,totalFrames,...}` 或路径/时长错误 | 固定 30fps、明确 Composition ID 与 adapterVersion；不随机选分支，不自行补隐藏背景。 |
| `render(validatedScene, resolvedPlan, outputPath)` | 已校验数据、已解析计划、受控输出路径 | `RenderResult{outputPath,byteLength,sha256,fps,width,height,totalFrames}` 或缺媒体/字体/渲染失败 | adapter 先复制核实资产到自己 public 目录，再由 staticFile 取本地文件；calculateMetadata 使用固定计划；renderMedia 输出完成并核验后才标成功。数据包不能指定动态组件/脚本/安装依赖。 |
| `importAsDraft(validatedPackage)` | 兼容 scene/renderPlan 与安全资源 | `{newProjectId,sourceReleaseId,diff:{texts,edges,path,regions},issues}` | 新 project、新 revision；沿用来源 ID 仅为差异比较；外部 review 一律无效，不自动发布。 |

Composition ID 拟为 `TapSceneDemo`，首份 adapter 契约中冻结；不是目前已存在的注册项。两个画布只变布局，不改变所选路径。导出 AI 文件仍在本地；交给任何外部 AI/云渲染都是用户另选的下一步。

#### 5.3 与页面直接相连的本地调用

用例名是清楚的调用边界，不要求每个名字建类或模块。字段与页面统一：

- `CreateProject/CopyProject/DeleteProject`、`InspectSource/ImportSource/TrimSource/AnalyzeSources/ConfirmCandidates`：文件选择取消不留空项目；返回 projectId、revision、来源与候选问题。
- `UpdateState/MergeStates/UndoEdit/UpdateHotspot/UpdateEdge/UpdateRedaction/UpdateRegion/ReplaceSource`：一笔 Room 事务更新对象和 revision，返回受影响 subjectIds/复核失效项。
- `PreviewDraft/ValidateDraft`：可用未最终复核的安全候选/局部安全快照试走，明确标“未复核”，不读原片、不登记 sealed release；试走的边覆盖绑定 graphDigest，修改图后失效。原素材回看单独走私有编辑功能。
- `BuildCandidate/ReviewCandidate/SealRelease`：返回 jobId、candidate 所基于 revision、真实 outputDigest、可定位 issues；复核完成前不能封存或发布。草稿继续编辑不改变旧 candidate。
- `ExportOfflinePackage/ExportAiPackage/ImportPackageAsLibrary/ImportPackageAsDraft`：离线观看与新建可编辑草稿分开。AI 只改路径/画布/停留/效果时，导出 job 保存独立配置并对最终 render-plan/文件清单复核，不修改基础 release；改区域、图或文案则重新封存 release。
- `ListHostedProjects/StartPublication/GetPublicationUpload/ResumePublication/CancelPublication/ListPublications/RevokePublication`：用 serverProjectId 管理异机发布；撤销只有 serverConfirmedAt 后显示成功，离线请求显示待提交。
- `ListLibrary/DeleteLibraryItem/GetStorageUsage/CleanTemporaryFiles`：只清理明确选定的本地库项或无引用临时文件；不联动服务器撤销，先报告空间及影响。
- `BuildRenderPlan`：对应上节 buildRenderPlan 契约，用已封存 scene 与独立 AI 配置生成有限时间轴。
- `ObserveJob/RetryJob/CancelJob`、`PlayRelease`：UI 观察实际任务阶段和错误；成品/安全预览复用播放 reducer，保存真实访问历史，过渡回调带 mediaRunId 防重复和过期回调跳转。

## 交互层

Android 承担全部手机创作与离线播放，Web 只承担持链观看。
各页的顶部/主体/底部、按钮、跳转、弹层及必要失败状态统一维护在 [README 的页面安排](../README.md#页面与流转)，不再复制一套。
UI 的已保存、已脱敏、已发布状态来自真实业务结果；安全预览与正式播放器复用播放规则，原片回看保持本机私有入口。

## 两个人的开发方式

- 日常只维护 README、本文和任务清单；不要求需求编号、Proposal、ADR、追溯矩阵或重复模板。
- 小变更用小 PR，标题为英文类型加中文，如 `docs: 明确产品范围与开发方式`；正文只写具体改动。
- 涉及产品范围、隐私、费用、破坏性迁移等重大取舍，先用几句话说明影响并确认，再改相应文档，不自动扩范围。
- 在任务分支开发，先开 Draft，不直接改 main、不强推或绕过保护；按已有授权执行合并、部署和设置变更。
- 目标保护是 main 经 PR、分支保持最新、最新提交的必需 `ci/gate` 通过、审查讨论已解决，禁止强推/删除；两人项目不伪造第二位审查者，不强制不存在的独立 Approve。
- UI 接真实数据和任务状态，不放营销文案、假按钮、假进度或假成功；样例明确标识，不能冒充用户数据。
- 按实际影响做必要增量检查，优先直接运行功能、看真实效果；纯文案不跑整套测试，不为数量添用例。
- 隐私、包导入、撤销、数据迁移等改动补对应反例；UI 检查相关失败、取消、返回和重复操作。
- 测试用虚构或授权去标识素材；依赖固定版本并核对许可，密钥和私有素材不进仓库、日志或外部模型。
- 结果只说实际完成的部分，未运行就说明；CI、真机验收、合并、发布分别判断，不互相代替。

当前 CI 只有一个只读 `ci/gate`：检查 Markdown 的 UTF-8 编码及相对目标分支的变更空白。
GitHub 原生 Draft 阻止草稿合并，标题/正文变化不重跑内容检查；有代码模块后再加入真实构建和必要测试。
更细的历史规格可查 [旧版产品说明](https://github.com/gluttonsama-cloud/TapScene/blob/3e776fbdf487f0beb70d7c108ac1b461c114c110/docs/product/product-spec-v0.1.md)，仅供追溯；当前三份文档取代旧流程。
