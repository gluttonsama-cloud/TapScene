# 初始快照、人工编排与导出依据

首次点击使用的初始快照，在准备首次点击时选定并固定，待其真实视频样本核对完成后才消费。选择后仍可能出现新画面，因此它不表示实际点击瞬间的最新画面。选择、消费与确认截止时间属于私有诊断记录，不换算为视频时间。

## 候选与人工确认

- 候选列表、代表画面、画面复核和动作复核展示“首次点击前的初始快照。画面在准备点击时已固定，实际点击前仍可能变化。”
- 同一源画面在多个边界复用时，像素身份仍可合并；初始快照的说明保留，互相冲突的选择记录拒绝读取。
- 画面确认仍须查看实际生成的安全图片；动作仍须人工确认点击区域与去向。说明不会自动确认任何内容，也不把编排位置变成真实点击识别结果。
- 独立项目导入的重试摘要纳入所有所选画面和动作边界的初始快照元数据。仅动作边界含有该信息时也不能漏掉。全为空的旧输入保持原 v1 摘要字节不变。

## 观看包与 AI 包的现有合同

导出的 `recorded` 表示画面来自录制素材，不表示它是某次手势派发瞬间的画面。步骤保留真实 `VideoFrame` 来源和原始样本位置；[ReleaseCompiler](../android/app/src/main/java/com/tapscene/data/ReleaseCompiler.kt) 输出的热点与下一步连线均为 `authored`，即作者核对后编排的通路。

[ViewerScene](../android/app/src/main/java/com/tapscene/packageformat/ViewerScene.java) 的步骤和连线没有 Before/After 边界、选择时刻或手势派发时间字段。[Java 包解析器](../android/app/src/main/java/com/tapscene/packageformat/ViewerPackageCodec.java)、[Remotion 合同](../remotion-adapter/src/contracts.ts) 与[网页播放器合同](../runtime-ts/src/contracts.ts) 均严格使用这一白名单。

[RenderPlan](../android/app/src/main/java/com/tapscene/packageformat/RenderPlan.java) 的停留、效果和时间轴是另行编排的输出动画时间；`click` 效果只引用选定热点，不重放原始手势时钟。因此初始快照可继续作为 `recorded` 画面导出，人工通路仍为 `authored`，不新增数据库或包版本，也不阻断现有导出。

私有点击链来源沿革仍是现有历史文本记录，不是成品字段或可恢复的执行权限；本次不把它扩展成新的项目时序模型。导出不声称保留或认证原始动作边界证据。

## 验证边界

新增主机回归使用合成录屏记录和真实生成的 PNG，覆盖完整初始快照导入、重读、实际 AI 包写出/读回、`recorded` 步骤与 `authored` 连线、严格导出字段、旧摘要兼容，以及仅改变私有选择记录时的重试拒绝。现有布局样例展示说明，不增加截图数量。

这些源码与测试须在集成后的精确提交运行；主机回归不代表设备采集、真实手势、人工隐私复核或系统录屏已通过。
