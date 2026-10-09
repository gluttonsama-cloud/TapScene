# 最小文档 CI 与证据边界

本 PR 只验证仓库文档、需求索引和检查器自身。它没有产品模块，不运行 Android、Web、媒体编解码、托管或 Remotion 验收，也不把这些项目标为通过。

## 本地入口

在仓库根使用 Python 3.12 或兼容 Python 3 标准库，无需安装额外包：

```sh
python3 scripts/check_docs.py
python3 tests/test_checks.py  # 仅检查器、测试或 workflow 变更时
```

[检查器](../../scripts/check_docs.py)验证 UTF-8、文档结构、仓库内 Markdown 链接和标题锚点、产品基线字节摘要、F01–F29 与 A01–A16 唯一定义、45项追溯的完整字段、七项 Proposed ADR。它不联网逐一请求外部引用，所以外部 URL 可达性及其最新内容未由此证明；原规范中的历史来源日期保持不变。

文档采用 ATX 标题、围栏代码块、简单表格和内联链接；检查器明确拒绝其不支持的链接语法，不能因解析器不认识就漏检。测试入口明确拒绝空测试集。单元测试使用临时合成仓库及故意错误输入，验证断链、坏锚点、缺失/重复/越界 ID、缺追溯字段、错误基线摘要和 ADR 状态等会失败。fixtures 不进入产品路径，也不使用真实用户数据。

## Actions 与聚合阻断

[workflow](../../.github/workflows/docs.yml)仅由 pull_request 的 opened、synchronize、reopened 内容事件触发，覆盖所有路径；标题、正文及 Draft 状态修改不重跑内容检查。不使用 pull_request_target。PR token 只有 contents:read，checkout 不持久化凭据，不提供生产 secrets。并发更新取消旧运行，任务设置超时，不上传原始素材或启用部署/付费服务。

仅使用官方 actions/checkout，固定完整 commit SHA 3d3c42e5aac5ba805825da76410c181273ba90b1（v7.0.1）。2026-10-09 已核对[官方稳定发布](https://github.com/actions/checkout/releases/tag/v7.0.1)和[对应 tag](https://api.github.com/repos/actions/checkout/git/ref/tags/v7.0.1)，并读取该固定提交的 [action.yml](https://github.com/actions/checkout/blob/3d3c42e5aac5ba805825da76410c181273ba90b1/action.yml)，确认原生使用 Node24。版本于2026-07-20发布，非 draft 或 prerelease；选择依据是受支持运行时及当前工作流兼容性，不是使用浮动 latest 标签。

首次远端运行使用 v4.2.2，出现 Node20 弃用并由 Runner 强制切换 Node24 的提醒，因此在同一 Draft PR 内升级，避免保留依赖兼容重写的工具链配置。v7 的高信任事件 fork 检出保护保持默认关闭危险检出，本流程仍只用 pull_request，不添加 allow-unsafe-pr-checkout 绕过。凭据持久化继续关闭。

[官方 README](https://github.com/actions/checkout/blob/3d3c42e5aac5ba805825da76410c181273ba90b1/README.md)要求 Node24 至少 Actions Runner 2.327.1；首次实际运行版本2.337.0已满足。README中2.329.0要求专指Docker容器Action内认证Git命令，本流程不使用该路径。Runner继续为 ubuntu-24.04，使用已有Python标准库，不动态安装包。完整SHA固定与兼容核查不等于全部源码安全审计；后续升级仍须PR、同步workflow合同测试并实际运行。

- docs-checks 每次内容事件运行一次文档检查。用当前 PR 的 base 到待合并内容比较路径；仅 scripts/、tests/、.github/workflows/ 或需求登记表变化时运行检查器回归。按整份 PR 范围判断，不只看末次提交；无法判断范围则失败。
- ci/gate 无条件汇总实际依赖结果；失败、取消、skipped 和缺失均拒绝，只有明确 success 可继续。相关反例保留在[聚合脚本测试](../../tests/fixtures/gate_cases.json)。
- 原生 Draft 由 GitHub 阻止合并，ci/gate 不重复判断；标题与正文保留有效需求关联，编辑后直接回读核对。工程登记表仍由文档检查验证。

本 PR 保持 Draft，内容检查与 ci/gate 可以成功，这不等于可以合并。终态、当前 SHA 和真实执行结果保留在 Actions 日志，不要求复制到 PR 正文或另发核验评论；不能用旧提交绿色结果代替。

## 仍需维护者处理

workflow 文件不会自动设置 required status checks。main protected=false，rulesets 返回空；详细保护读取受限，见[入库记录](../engineering/repository-adaptation.md)。只有维护者另行授权设置并实际验证 ci/gate 为必需后，才能把这些检查称为平台强制合并门禁。没有修改保护、自动合并、发布或部署。

任何后续产品模块首次加入时，必须把真实构建与测试纳入全关联聚合；当前文档基线 gate 不能永久替代产品 CI。Proposal 状态接受、基线有意变更或 ADR 转 Accepted 必须连同检查政策经审查更新，不应把本次 Proposed 断言当成永久冻结。
