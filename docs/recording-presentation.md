# 录制的显式呈现时钟

点击链保留真实 MP4，在源画面不变化时继续呈现已观察到的规范化 FBO。手动 MediaRecorder 路径不变。此增量依次接在点击链便利性、共帧证据和候选复核之后，开发版本 31；不因本地主机模型测试通过而宣称设备管线已通过。

## 两种身份

- VideoSourceObservation：真正 latch 的源画面身份、采集序号和生产者时间戳。源的最后一次变化不会因帧率丢弃；归一化开销须在高刷新率设备验证，不保存整段 RGBA。
- VideoPresentationSample：一次明确呈现意图，有独立 sampleId 和 presentationPtsUs，可重复显示同一源。时钟是第一次呈现以来的 elapsedRealtimeNanos 差，不从生产者时间戳推断 CPU 时间。
- FrameTicket 保留该源第一次实际 EGL 提交的 canonical PTS；一份源的多个 before/after ticket 始终同一绑定。重复显示增加 MP4 样本，不新增源帧，不补造 after，不修复缺失 canonical 编码匹配。

新的帧归一化、重复呈现、PNG 读回均在同一 GL worker，呈现时固定 FBO 租约。源采集前短锁预留序号；手势完成前已在途的采集也被保守排除在 after 范围外，驱动调用不持有边界锁。暂停/窗口/几何变化仍关闭动作 epoch。

## 调度与编码确认

只保留一个按 deadline 调度的呈现任务。延迟唤醒使用实际当前时钟，不补发过期帧，不按整数 33ms 轮询推定 30fps。重复显示直接使用同一 FBO，不再次观察 SurfaceTexture。

三项事实分开记录：呈现意图、eglSwapBuffers 成功、真实非空 codec 输出成功写入 muxer。codec 回调在 native mux 写入之前冻结关联；若当时未知，后来创建相同 PTS 的意图也不能追认。允许 codec 输出早于 EGL 调用返回；只有原关联的真实 EGL 成功补齐后，才准许对应锚点。

压缩输出严格按驱动输出顺序写入，禁止重复/逆序/分片样本，不进行最近 PTS 匹配。每份源的 canonical 绑定不可被重复显示覆盖。实际 mux ordinal 包括重复显示；空 EOS 时长标记不占序号。呈现意图和输出各限制 12,000 条。

## 尾长与封口

停止门禁立即冻结终点。先断 VD，结束 GL 提交，再 codec EOS/有限排空；全部真实样本后写入独立的空 EOS duration 标记，再停止、释放和原子封口。驱动清理的 5–7 秒不计入片尾，超时仍保留原所有者与未封口文件。未请求停止的 EOS 立即关闭录制/点击门禁；正常提前 EOS 只记录终态，必须等 GL 清理返回后才能释放 codec Surface，已有 EOS 不再重复发送停止信号。

封口后 MediaExtractor 检查实际样本数、顺序、格式和真实轨道时长。轨道时长与“冻结终点减第一个真实编码 PTS”的呈现区间独立比较，最多 1ms 舍入差；异常不登记主视频。PNG 证据仍是弱依赖，缺失不会使健康视频变成截图工具。

官方依据：[MediaMuxer.writeSampleData](https://developer.android.com/reference/android/media/MediaMuxer#writeSampleData(int,%20java.nio.ByteBuffer,%20android.media.MediaCodec.BufferInfo)) 支持在 MP4 的真实样本之后使用空 EOS 指定最后样本时长。

## 尚未设备验证

主机模型覆盖 120 秒静态显示只有一个源观察、许多独立呈现、deadline/量化/倒退、停止后延迟清理、canonical 缺失、两种 EGL/codec 先后顺序、写入中出现未来源、重复/逆序输出与预算。实际 OES/MediaCodec、OEM EOS 尾长、播放到尾、采集方向/颜色、120Hz 高刷新率、锁屏、阻塞停止、温度和内存峰值仍需真实设备授权后人工核验。
