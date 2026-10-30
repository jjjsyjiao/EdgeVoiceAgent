# Realtime 音频时间轴验证（2026-10-28）

运行 `realtime_timeline_smoke.py --live`，使用当前配置的 Qwen Realtime 模型、合成中文语音、每次 1 条 WebSocket，不读取用户画面，不调用第二个模型。产物只保留毫秒数、字节统计、item ID 的 SHA-256 前 16 位与状态。

真实结果：

| 阶段 | 发送前总音频 ms | 发送前 VAD 音频 ms | speech_started.audio_start_ms | speech_stopped.audio_end_ms |
| --- | ---: | ---: | ---: | ---: |
| 初始 VAD，前置 2000ms 静音 | 0 | 0 | 1740 | 4360 |
| clear 后 VAD，前置 1000ms 静音 | 5176.21 | 5176.21 | 5900 | 8540 |
| manual 追加 3176.21ms 后 clear、恢复 VAD | 12528.63 | 9352.42 | 10080 | 12720 |

两次独立连接得到相同边界值。观察结论仅针对本次实际配置的 Qwen 模型：

- `input_audio_buffer.clear` 不重置 VAD 音频时间轴。
- manual 模式提交的内部原声不推进后续 VAD 的 `audio_start_ms/audio_end_ms`。
- 每次真实 VAD 的 start、stop、committed 和 ASR completed 均关联同一个 item ID；manual committed 与其 ASR item ID 也一致。
- start 包含配置的前置缓冲，因此比音频中正文起点略早，定位时应直接使用服务端偏移。

因此客户端分别维护总成功追加音频字节 `inputAudioBytesSent` 与仅 live/VAD 音频字节 `inputVadAudioBytesSent`。历史比较原声定位使用后者；清缓冲保留计数，连接换代清零。找不到对应完整 PCM 范围时拒绝比较。

第一次验证按“所有 append 均推进 VAD 时间轴”假设判定为 false，原样保存在 `artifacts/realtime-timeline-20261028.json`。修正假设后的第二次真实验证通过，保存在 `artifacts/realtime-timeline-20261028-r2.json`；没有覆盖失败记录。产物中的 `total_appended_ms_at_receive` 是应用消费事件队列时的累计值，不是服务端墙钟。

官方字段定义：https://help.aliyun.com/zh/model-studio/server-events 。时间轴在 manual 和 clear 后的行为以上述真实验证为依据。
