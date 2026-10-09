# TapScene 点演

点演拟将已有手机录屏整理为可点击、可分支的有限演示。Android 负责本地创作、导出和回流；浏览器负责观看；受信 Remotion 模板在电脑或明确选择的云环境制作线性动画。

当前是规范 Proposal 阶段，架构及七项 ADR 均为 Proposed。仓库只有完整产品/工程文档和最小文档检查，没有 Android、Web、服务端或动画适配器实现。文档 CI 通过不代表产品验收通过。

## 阅读入口

1. [产品规格 v0.1 全文](docs/product/product-spec-v0.1.md)与[45项需求追溯](docs/product/requirements-traceability.md)
2. [架构提案](docs/proposals/0001-architecture.md)与[七项 Proposed ADR](docs/adr/README.md)
3. [工程规范](docs/engineering/engineering-standard.md)与[质量及安全门禁](docs/testing/quality-gates.md)
4. [执行指引](AGENTS.md)、[模板](docs/templates/README.md)、[合同状态](docs/contracts/README.md)及[运维状态](docs/operations/README.md)
5. [入库适配与治理现状](docs/engineering/repository-adaptation.md)、[文档 CI](docs/testing/docs-ci.md)

## 本地验证

需要 Python 3.12 或兼容的 Python 3 标准库，无第三方 Python 依赖。仓库根运行：

```sh
python3 scripts/check_docs.py
python3 tests/test_checks.py  # 仅检查器、测试或 workflow 变更时
```

纯文档变更只运行文档检查；检查器、测试或 workflow 变化才运行检查器回归。ci/gate 汇总实际内容检查，原生 Draft 状态由 GitHub 阻止合并；仅修改标题、正文或 Draft 状态不重跑内容检查。转为 ready、合并、变更保护、发布和部署均需各自授权。main 当前保护不足，详见入库记录，不能把 workflow 当成服务器强制保护。

## 许可

保留仓库初始化时的 [Apache-2.0 LICENSE](LICENSE)。依赖、模型、字体、图标、素材及 Remotion 许可仍需单独核对。
