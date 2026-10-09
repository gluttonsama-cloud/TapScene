# 最小文档 CI 与证据边界

本 PR 只验证仓库文档、需求索引和检查器自身。它没有产品模块，不运行 Android、Web、媒体编解码、托管或 Remotion 验收，也不把这些项目标为通过。

## 本地入口

在仓库根使用 Python 3.12 或兼容 Python 3 标准库，无需安装额外包：

```sh
python3 scripts/check_docs.py
python3 tests/test_checks.py
```

[检查器](../../scripts/check_docs.py)验证 UTF-8、文档结构、仓库内 Markdown 链接和标题锚点、产品基线字节摘要、F01–F29 与 A01–A16 唯一定义、45项追溯的完整字段、七项 Proposed ADR。它不联网逐一请求外部引用，所以外部 URL 可达性及其最新内容未由此证明；原规范中的历史来源日期保持不变。

文档采用 ATX 标题、围栏代码块、简单表格和内联链接；检查器明确拒绝其不支持的链接语法，不能因解析器不认识就漏检。测试入口明确拒绝空测试集。单元测试使用临时合成仓库及故意错误输入，验证断链、坏锚点、缺失/重复/越界 ID、缺追溯字段、错误基线摘要和 ADR 状态等会失败。fixtures 不进入产品路径，也不使用真实用户数据。

## Actions 与聚合阻断

[workflow](../../.github/workflows/docs.yml)仅由 pull_request 触发，覆盖所有路径；不使用 pull_request_target。PR token 只有 contents:read，checkout 不持久化凭据，不提供生产 secrets。并发更新取消旧运行，任务设置超时，不上传原始素材或启用部署/付费服务。

仅使用官方 actions/checkout，固定完整 commit SHA 11bd71901bbe5b1630ceea73d27597364c9af683；该 SHA 已从[官方 v4.2.2 tag](https://api.github.com/repos/actions/checkout/git/ref/tags/v4.2.2)核实。Runner 使用 ubuntu-24.04 已有 Python 标准库，不动态安装包。固定 Action 不等于已审计其全部源代码，后续升级仍须独立 PR。

- docs-checks 运行文档检查和检查器测试，Draft 也运行。
- ci/gate 无条件汇总实际依赖结果；成功、失败、取消、skipped 和缺失结果分别处理，只有明确 success 可继续。
- ci/gate 同时要求 PR 标题和正文含有效 F/A 需求 ID，或[工程登记表](../engineering/requirements.json)中真实存在的 ENG/BUG ID，且 PR 已非 Draft。Draft 应明确失败，不用 skipped/neutral 假通过。
- [聚合脚本](../../scripts/check_gate.py)有故意失败测试：依赖失败/取消/跳过/缺失、Draft、缺需求 ID、未知工程 ID 或无效登记表均不得成功。

本 PR 保持 Draft，所以预期 docs-checks 成功，而 ci/gate 因 Draft 状态阻断；这是治理结果，不是产品失败。若 docs-checks 失败，须修复并重跑。终态、当前 SHA 和真实日志链接记录到 PR，不能用旧提交绿色结果代替。

## 仍需维护者处理

workflow 文件不会自动设置 required status checks。main protected=false，rulesets 返回空；详细保护读取受限，见[入库记录](../engineering/repository-adaptation.md)。只有维护者另行授权设置并实际验证 ci/gate 为必需后，才能把这些检查称为平台强制合并门禁。没有修改保护、自动合并、发布或部署。

任何后续产品模块首次加入时，必须把真实构建与测试纳入全关联聚合；当前文档基线 gate 不能永久替代产品 CI。Proposal 状态接受、基线有意变更或 ADR 转 Accepted 必须连同检查政策经审查更新，不应把本次 Proposed 断言当成永久冻结。
