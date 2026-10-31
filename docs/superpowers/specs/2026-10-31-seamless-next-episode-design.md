# 无感下一集：单播放器队列与两层预加载探索

日期：2026-10-31。状态：用户已批准实现与自主测试；实现已接入，Android 实测结果见 docs/SEAMLESS_0.11.0.md。
目标：自动连播时减少本集最后一帧到下一集第一帧之间的等待，保留0.10.0的稳定读取、失败恢复、原名称、双目Surface和内置输入。

## 已核实的瓶颈

MainActivity目前收到STATE_ENDED后调用playEpisode(next)。即便reuse=true，仍执行player.stop、clearMediaItems、关闭旧RemoteVideo，然后等待下一集资源，setMediaSource、seekTo、prepare。缓存解决了数据等待，但没有移除播放器重新准备这段路径。

事件监听器active()绑定创建时requestedId，首帧计时firstFrame也只覆盖该次播放；saveProgress()从当前player读位置。改为队列后，这几处需要按实际mediaId更新，否则可能忽略下一集事件或把新集位置写进旧集记录。sleepTimer.episodeEnded和preloadClock.episodeCompleted也不能再依赖每集STATE_ENDED——有下一项时播放器不一定在边界进入ENDED。

## 官方能力与版本

已读取androidx/media标签1.8.0的ExoPlayer.java和DefaultLoadControl.java：
- addMediaSource支持运行时添加下一条MediaSource。
- setPreloadConfiguration(PreloadConfiguration(targetPreloadDurationUs))已存在；默认关闭。
- DefaultLoadControl.shouldContinuePreloading在当前播放还需要加载时返回false。

参考：
- https://developer.android.com/media/media3/exoplayer/playlists
- https://android-developers.googleblog.com/2025/01/media3-150-whats-new.html
- https://github.com/androidx/media/blob/1.8.0/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/ExoPlayer.java
- https://github.com/androidx/media/blob/1.8.0/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/DefaultLoadControl.java

这里的提前准备是解析和音视频样本队列，不等于另一视频解码器提前解码首帧。

## 三种路线

A. 推荐：单播放器、连续播放队列、只预备紧邻下一集。保留现有视频Surface和当前解码渲染链，用Media3队列自动转换，移除正常自动切集中的stop/clear/prepare。现有磁盘预加载负责更远的集。最贴合同一短剧顺序播放。

B. DefaultPreloadManager：适合上下滑动、多个候选下一条的视频流。当前产品以线性自动下一集为主，第一阶段不为随机浏览引入另一套候选源管理。后续真有此交互需求再评估。

C. 双播放器/双视频解码预热：可能进一步压低异构格式的首帧等待，但增加眼镜内存、功耗、音频焦点和双目Surface交接复杂度。第一阶段不采用。

## 推荐实现边界

1. 保留一个ExoPlayer和既有StereoVideoView/Surface。滑动方向、确认、返回、双指菜单与输入法完全沿用现有适配。
2. 保留“时间/集数”磁盘储备；新增紧邻下一集的播放器准备层，初始试验目标为3秒媒体样本。只维持当前+下一集两个媒体源，转换后滚动补一个，不把五分钟或十集全部展开成播放器样本。
3. 首个原型只把“已完整缓存、解析验证成功”的下一集提前交给队列。完整条件包含准备成功、total>0、缓存字节覆盖全资源及本地实体有效；解析成功仅指MP4/样本边界验证，不冒充Android解码验证。转交使用原子claimCompleted检查，候选数据源强制cache-only，任何缺失/损坏直接候选失败，禁止偷偷回退网络。这样下一条MediaSource准备只读本地缓存，不会与当前播放或更远集下载争网络，也不需要立刻重写队列期RemoteVideo网络优先级。没有完整缓存则沿用0.10.0接管部分缓存/前台准备路径。之后若结果表明完整缓存条件限制太大，再独立支持部分缓存队列读取与缺失区间调度。
4. 从EpisodePreloader转交资源时使用同一个原子完成检查与claim；不得仅为查看进度就claim未完成任务、提前终止其下载。队列持有键保留在滚动desired范围中，以pin排除重复下载，并继续计入预加载时长/集数和磁盘/元数据预算，避免移除后重复下载。每项用带标识的MediaSource所有权封装追踪source/period释放；DataSource.close只表示一次读取结束，不能据此关闭RemoteVideo。移除项等待真正source释放确认后才关闭文件；整播放器退出先完成player.release，再清理残余项。覆盖正在读取时关闭开关、内存压力、切清晰度及生命周期停止。
5. 自动边界不调用playEpisode重建源；用onMediaItemTransition和onPositionDiscontinuity的旧/新mediaId识别集数、完成旧集进度与更新新集标题/清晰度、重置每集恢复预算、补后续队列。UI保持原页，不闪“取址中”或重置整个播放器视图。
6. 自然完成来源分为自动transition、本集结束暂停的onPlayWhenReadyChanged(END_OF_MEDIA_ITEM)、最后项STATE_ENDED，按播放实例/队列项去重。旧集历史由旧PositionInfo与保存的旧时长显式写入，不在转换后读player当前位置来替代。手动跳集不算自然播完，不消耗“播完N集”定时计数；暂停继续不重复计数，明确从头重播建立新播放实例。定时仅剩一集时在边界前启用pauseAtEndOfMediaItems。暂停、停止自动连播、定时“本集结束停止”需要显式阻止跨集自动播放。使用pauseAtEndOfMediaItems及事件状态机确保不先播下一集再回退。手动下一集命中队列时seekToNextMediaItem；跳到任意集、改清晰度、换剧用原有完整切换与取消逻辑。
7. 下一集后台解析/预备失败不升级为当前播放错误；保留当前播放，清理失败的候选，结束时降级现有恢复。下一集已经进入播放器队列后的MediaSource错误需要用Analytics事件的period/window映射或带标识的source错误路径判断归属；不能只读currentMediaItem，普通Player.Listener.onPlayerError没有mediaId参数。避免把未来项错误写成当前项失败；首原型只排入完整且验证过的本地资源降低该风险，但仍要测损坏/文件失效。
8. 首个实验版本复用现有“预加载”开关和自动连播设置：二者都开启时才启用下一集队列准备，关闭预加载则取消下一项预备，当前播放继续，不额外增加一组技术参数设置。稳定0.10.0原包保留用于对照；正式是否默认启用仍由数据与实机结果决定。
9. 不改变内容速度、不裁剪片头片尾、不交叉混音。内容本身黑帧/静音与播放器额外空隙分别统计。

## 内存/带宽

现有15–30秒前台缓冲、12MiB负载控制目标字节数（不是总内存硬上限）以及磁盘/元数据上限保留为起点；3秒下一集样本是额外准备目标，需记录allocator总分配、RSS和解码器数量，明确总上限后才默认开启。若当前播放补缓冲，停止下一集样本准备；本地全缓存原型不增加下一集网络请求。完整缓存被队列持有后应计入总缓存预算，而不因从RollingPrefetch移除而在总量统计中消失。

## 实际素材与证据

已本地ffprobe三个此前真实下载、完整解码通过的分集：均HEVC Main、854x480、30fps、yuv420p，视频初始化参数SHA256一致；音轨均AAC、44100Hz、2声道，音频初始化参数SHA256一致。它们具备有利的兼容条件，但不能由此证明Android实际解码器必然无缝复用。

证据目录：D:/Backup/Desktop/hongguo/outputs/seamless-exploration-20261031（官方1.8.0源码快照）。当前0.10.0已安装版本不被本探索更改。

## 验收计划

- 先用同样的三份真实已解密素材，比较现有结束后重新prepare与单播放器连续队列，排除网络变量。
- 在真实生产Media3路径记录：旧集最后视频帧与新集第一视频帧的单调时间差；音频欠载；STATE_BUFFERING次数/持续时间；decoder初始化/释放；Surface创建次数；队列期缓存读取与网络请求；总内存高水位。记录至少20个切换，先报告探索样本分布、中位数和最差值；稳定P95结论需扩大样本。兼容格式切集未必每次触发onRenderedFirstFrame，因此需用帧元数据/媒体项映射关联GL updateTexImage与绘制时间，并验证映射；GL绘制时间仍不等于面板实际可见时刻。音频欠载计数也不等于无缝，补输出PCM边界/音频捕获对照和设备听感。
- 本批30fps、1倍速视频正常帧间隔约33ms（异构格式/变速按实际边界帧时长计算），计算额外帧间隙时扣除正常间隔；先以兼容素材P95额外间隙低于100ms为试验目标，不提前承诺达到。判断额外黑帧要对照原素材与渲染输出，避免把内容黑帧算成播放器问题。
- 对照全缓存、部分缓存、未命中、弱网、候选失败、编码/分辨率变化；核验暂停、手动上下集、重播、结束定时、禁用自动连播、退出/后台、质量变化、云端进度集数归属。
- JVM本地测试继续验证缓存与状态机，但它不运行Android真实MediaCodec/Surface。首帧和音频衔接必须用正式应用的Android播放器路径验证，优先本机Android模拟器，再在眼镜上确认实际体验。没有新建独立测试APP的必要。
- 先产出可单独关闭的实验版本，达到边界正确且测量收益后再发布三版本。用户后续已授权实现新版本；保留0.10.0发布包作为对照与回退。

本机环境已补齐：独立API30 Android模拟器运行正式应用的Media3、MediaCodec和Surface路径；未改变Windows虚拟化配置，也未重启主机。FFmpeg仅用于素材检查，不代替Android播放验证。

设计审查：已完成两轮只读审查，完整缓存强制本地读取、队列预算/去重、释放所有权、边界事件和测量区分已补齐；实现与审查已完成；测量结果及尚未覆盖的物理面板、实际音频听感边界另行记录，不宣称绝对零间隙。
