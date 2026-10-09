# ADR 002 原生 Android 与模块化单体

状态：Proposed。日期：2026-10-09。决定者、接受 PR 与生效日期尚未建立，不代表已批准。

## 方案摘记

Android 采用 Kotlin/Compose，Web 采用 React/TS，托管采用 Fastify/TS、PostgreSQL 和私有对象存储；API/worker 是同代码库的不同入口。

## 替代与代价

首版不以跨端 UI、KMP/WASM 或微服务拆分为目标。

需维护少量跨语言纯规则；最低系统、版本与性能待验证。

## 唯一详细依据

见[架构提案](../proposals/0001-architecture.md)第 1 至 3 节；G06、G07，以及[质量门禁](../testing/quality-gates.md)。本条仅为决策摘记；细节修改先改架构提案，不复制出另一套规则。

## 接受与重审

由真实提案 PR 记录审查人、提交、条件和接受时间后，才可改为 Accepted。若产品边界、平台能力、隐私政策或验证结果使本选择不再成立，提交修订提案或替代 ADR。运行验证仍为未运行。
