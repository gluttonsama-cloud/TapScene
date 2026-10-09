# 首个规范 PR 入库适配记录

日期：2026-10-09。状态：Proposed；本记录不是批准、合并或发布证明。

## 仓库基线与治理核查

- 仓库：[gluttonsama-cloud/TapScene](https://github.com/gluttonsama-cloud/TapScene)，个人 public 仓库，默认分支 main。
- 核查基线：[d9bfb81b0a632bd01e0c9852a91eee0b1bf91b6a](https://github.com/gluttonsama-cloud/TapScene/commit/d9bfb81b0a632bd01e0c9852a91eee0b1bf91b6a)，Git tree 为 8f57a99980891ccc68701b94b94342f7ae0e02d6。递归树完整且只有 LICENSE；没有既有代码或 PR。
- LICENSE 的 Git blob 为 261eeb9e9f8b2b4b0d119366dda99c6fd7d35c64，本次逐字保留，未替用户选择或修改许可。
- 分支读取返回 protected=false、保护摘要 enabled=false、required checks 为空；仓库 rulesets 读取返回空数组。详细 branch protection 读取返回 403，集成不具有该读取能力，所以完整保护细节仍未知。没有修改规则集、权限、可见性或安全设置。
- 连接器身份经核查为 gluttonsama-cloud，仓库返回 push/admin 权限；写入仍受连接器实际权限限制。新提交由 GitHub 连接器产生，以返回的真实作者/提交者为准，不人工伪造身份或签名。
- 核查时 Actions runs 为零，无法由此判断完整 Actions 权限/预算；workflows 设置列表不在连接器受支持读取端点内。实际可执行性由本 Draft PR 的运行结果验证，结果记录在 PR。
- 原生 Draft 创建接口可用；只有创建后回读 draft=true 才算已建立。真实 PR 地址、head/base 和检查结果放在 PR 及交付记录，本文不预填未知编号。
- 真实独立人类审查与维护者接受均尚未进行。AI 协助独立检查必须标为 AI 辅助，不制造 GitHub Approve。

## 来源与完整性

- 产品来源：已交付《点演 产品规格说明 v0.1》，日期 2026-10-09。原 DOCX 文件名、SHA-256 与转换结果见[产品基线清单](../product/baseline-manifest.json)。不将 Word 包中的作者元数据或缓存缩略图纳入仓库。
- Markdown 按原顺序保留264个正文块、521个段落/表格单元文本、16节、12张表、F01–F29、A01–A16以及16个超链接。原文与转换后的文本单元逐项一致；省去页面排版与页码字段。没有删改 P0、P1、容量、安全或验收含义。
- 工程来源：已交付 TapScene 开发规范与架构文档 v0.1 ZIP，共22项。原归档及各项摘要见[导入清单](source-import.json)；解包后21个文档均通过原 SHA256SUMS 校验。原清单作为来源信息保存，不把旧摘要冒充适配后文件校验。
- 架构仍 Proposed，七项 ADR 原文不变。产品示例 DianyanDemo 与架构提议 TapSceneDemo 的区别保留，待合同 PR 明确，不静默修改规格。

## 相对交付稿的有意修改

1. 根 README 从交付包说明变为仓库导航，明确当前仅文档及检查、无产品实现。
2. 根 AGENTS 的“未来落位”改为本 PR 状态，并链接完整产品、追溯和真实检查命令；保留任务分支、PR、真实验证和授权边界。
3. 工程规范更新已核实的仓库、既有 Apache-2.0 LICENSE 与当前 Proposal 状态；保留保护和产品门禁为目标要求。文首 Markdown 硬换行改为空行以避免尾随空白；将不存在的 architecture-review.md 引用改为实际的[质量门禁](../testing/quality-gates.md)。
4. 架构提案仅更新仓库大小写及本次入库状态，没有批准技术选择或修改设计边界；坐标区间 `[0,1]` 仅加行内代码标记以消除 Markdown 链接歧义。质量门禁同步区分文档核查与仍未运行的产品验证。
5. 产品目录增加完整 Markdown、基线清单和45项需求追溯；README说明来源、转换和状态。追溯的依赖、F/A 与 ADR 关联是供审查的初始映射。
6. 模板索引同步实际路径；PR 模板复制到 .github/pull_request_template.md，两份内容一致；本次没有虚构 CODEOWNERS 或审查者。
7. 登记 [ENG-001](requirements.json) 对应本次规范基线与文档门禁；纯工程/缺陷 PR 可关联真实登记的 ENG/BUG ID，不强迫附会功能 ID。增加 Python 标准库文档检查、故意错误测试样例及最小 Actions workflow，详见[文档 CI](../testing/docs-ci.md)。没有加入 App 骨架、业务代码、运行 schema 或产品假测试。
8. 首次 Draft 的真实 Actions 日志暴露 checkout v4 的 Node20 弃用提醒；同一 PR 内核实并固定 Node24 的官方稳定 checkout v7.0.1，同步workflow合同测试及兼容依据，重跑最终提交。产品原文、ADR、授权与保护范围均未变化。

首批全文入库超过通常建议的400行审查提示。大部分为已交付规范的完整导入，不能为行数删掉产品需求；可按产品、工程/架构、检查脚本分块审查。后续实现继续按小 PR 与依赖拆分。

## 合并阻断与后续

- 本 PR 必须保持 Draft，直到维护者接受 Proposal 并明确授权下一步。没有本次合并或部署授权。
- 即使文档检查通过，仍须在获明确授权后配置并实际验证 main 的 PR/ci/gate 强制保护；当前不能宣称服务器强制门禁已经存在。
- Draft 的 ci/gate 应失败，防止把“可检查”当成“可合并”。准备转为 ready 时须重新核对当前 head/base、风险、ID、审查和全部适用检查。
- A01–A16、G01–G09、V01–V07以及构建、真机、媒体、渲染、网络和恢复均未运行；G10 仅完成本次适用的文档与仓库核查部分。
- Proposal 接受、合同冻结、真机媒体小样、工程骨架和功能实现仍为后续阶段。本 PR 合并也不自动批准架构中每个未知项。
