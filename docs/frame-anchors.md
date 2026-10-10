# 点击链共帧证据

此增量保留真实无声 MP4。手动录屏继续使用 MediaRecorder；点击链使用同一个 MediaProjection / VirtualDisplay，经 SurfaceTexture OES、规范化 FBO、MediaCodec AVC Surface 与 MediaMuxer 保存视频。原图是同一采集帧的编码前 PNG，不是有损 H.264 的逐像素解码副本。

## 动作与画面

- 第一点仍须满足目标窗口、完整默认屏幕和原有几何守卫，并收到至少一个真实编码样本；不以编码器 start 返回冒充就绪。
- before 在实际派发前原子固定已经发布的 FBO 租约。后续 GL 读回使用该租约，不能改取后来画面；PNG 压缩、磁盘写入和编码确认均不延迟点击。
- 进入源采集前在短锁中预留序号，Completed 回调到达时记录含在途采集的保守下界，包括同步回调早于接受日志落盘的情况。waitAfter 结束时取其后观察到、下一动作之前的末个真实帧；这是观察顺序，不能证明业务动作已成功，也不证明生产者在回调后才生成像素。
- 静态画面没有新帧时 after 为 NoNewFrame；PNG 队列、数量、空间或压缩失败仅使辅助证据 Missing。不会额外等待、补图、重放或改变已配置的点击节奏。
- 暂停、非目标窗口、几何变化和停止关闭当前 epoch。已经固定的 ticket 可以异步补齐原证据；迟到回调不能改变终态动作事实或填进新一轮。
- sessionId + sourceFrameId 表示同一个真实采集帧。不同 before/after ticket 可以共享它；消费端不能按 action 数量重复创建同一画面。StaticReuse 保留原始帧身份，不更换时间戳。

## 三条时间线

SurfaceTexture 的时间起点属于其生产者，只用于真实源身份和源内差值，不与 uptime、elapsed 或 wallclock 对齐。视频采用独立的显式呈现时钟，从第一份实际呈现意图开始使用 elapsedRealtimeNanos 的相对差值，通过 eglPresentationTimeANDROID 送入编码器。时钟倒退、微秒量化冲突或帧率门限明确跳过，不用“上一帧加一”修补。

静态页使用同一个规范化 FBO 的明确重复显示样本维持视频时长。重复显示不调用 updateTexImage，不新增 sourceFrameId/captureSequence/sourceTimestampNs，不生成 after。每个真实源帧只有一份固定的首个呈现绑定；后续重复显示不能替换它，即使首个样本未获编码匹配。尚未呈现的新源帧缺少锚点，不捏造其 PTS。

真实非配置、非空编码输出到达时先冻结精确的已知关联，muxer 写成功后才分配实际样本序号。写入期间后来出现的同 PTS 不能认领未知输出；EGL 成功返回与编码输出可先后交错，但最终必须两者齐全。没有最近时间猜配；分片、重复和逆序压缩样本失败，不排序重写视频。封口后 MediaExtractor 逐样本读取实际序号/containerPtsUs 并核对样本数量。容器舍入值独立保存，不默认等于 encoderPtsUs，也不用毫秒取帧器乘回微秒作为校验。

停止门禁当场冻结显式呈现终点，不把 VD/GL/EOS 清理时间算入视频。实际 codec EOS 后，通过额外空 EOS 标记设置末样本时长；此标记不增加源观察、编码样本或样本序号。封口后独立检查真实容器时长与呈现区间，最多容许 1ms 容器舍入差，未通过不登记视频。该合同的实际 OEM 行为仍须真机验证，详见[静态呈现时钟](recording-presentation.md)。

## 停止、恢复和资源

停止先关闭点击/新帧门禁，独立清理线程断开 VD 和投影，再停止 GL 提交、发送 EOS、有限排空、停止并释放 muxer/codec，之后才 fsync/原子封口 MP4、校验证据、登记稳定 sourceId。EOS 等待最多五秒，外层驱动等待七秒；超时不代表驱动已释放，不标 sealed、不删仍被持有的文件，也不开放新录制。仍保留原所有者，迟到释放后只清理失败片段，不恢复点击或录制。

三份规范化 FBO、最多两个在途截图、一条有界 PNG 队列；每会话最多 80 个边界、100 MiB 原 PNG，单张最多 12 MiB。实际 .part 文件计入预算，写图前另保留当前视频独立登记副本和 64 MiB 空间。耗尽只缺少证据；主视频低空间门禁仍负责停止。读回仍有 GPU 同步和内存峰值成本，不因异步压缩而消失。

证据在 noBackup 私有目录，独立于录制临时 MP4 清理。登记完成只清理临时视频，不删有效 PNG。明确删除源/项目且提交已确认后，以精确归属和持久撤销记录清理；迟到写入不能复活。未知提交、损坏归属或不明文件保留，不猜测清除。恢复不会重启投影、手势或 PNG 任务；旧 v1 录制/点击日志继续可读。

## 后续候选消费合同

FrameEvidenceStore.listBoundaries(sessionId) 仅返回动作身份、边界、epoch、ticketId 或 Missing 原因，无原图路径。readCandidate 与 SourceRepository.frameEvidenceSourceAccessor 重新核对当前登记归属、真实 MP4 长度/SHA、实际样本序号、PNG SHA/完整像素和几何，全部满足才返回可用于私有复核的元数据。

withDecodedFrameSuspending 提供自动回收的 Bitmap 作用域，供现有 SafeMediaWriter 生成真实遮挡 PNG；不能把原 Bitmap 传到导出或托管资产。正式步骤仍须显示并由作者确认实际派生 PNG。此增量不自动建立步骤、热点、分支或隐私复核标志，候选 UI 接入为后续独立增量。

固定几何为显式等比居中变换：frameX = displayX × scale + offsetX，Y 同理。SurfaceTexture 变换矩阵逐帧用于规范化采样。点坐标可转换，按钮范围仍需作者调整；缺 after、跨 epoch 或失效来源不能猜目的状态。已有点击日志的 Unknown/空帧字段保留兼容，独立证据侧车不回写终态动作。

## 核验范围

只增加三组必要检查：真实时间差/精确匹配/样本序号；动作窗口/静态 Missing/迟到回调/至多一次及有限租约；私有存储/旧日志兼容/崩溃与归属删除。Android 构建、lint、主机原生 PNG/SQLite 及必要合成检查以最终 PR 精确 CI 为准。

无真机/KVM 时，实际 OES→Codec、静态首帧、长屏方向/颜色、坐标/system bars、读回延迟/温度/内存、锁屏/系统停止、驱动 EOS 和进程断电均未验证。云端不会开启无障碍或投影权限，不执行真实外部点击，不生成新的交付签名或上传 APK。

官方接口依据：[MediaProjection](https://developer.android.com/media/grow/media-projection)、[SurfaceTexture](https://developer.android.com/reference/android/graphics/SurfaceTexture)、[MediaCodec](https://developer.android.com/reference/android/media/MediaCodec)、[MediaMuxer](https://developer.android.com/reference/android/media/MediaMuxer)、[EGL 时间戳](https://registry.khronos.org/EGL/extensions/ANDROID/EGL_ANDROID_presentation_time.txt)。
