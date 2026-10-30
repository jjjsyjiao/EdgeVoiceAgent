# 设备验证入口

`captions` 入口只验证字幕展示和恢复，不连接云端、不请求权限、不启动麦克风或相机。先以 bare 显式 Intent（`CLEAR_TASK | NEW_TASK`）启动真实页面，注入最近 5 轮合成问答，验证实际 UI 可滚动查看第一轮和第五轮，再连续 3 次系统 Home → 普通 launcher Intent（`MAIN/LAUNCHER + NEW_TASK | RESET_TASK_IF_NEEDED`）返回；不使用 `SINGLE_TOP`、`REORDER_TO_FRONT` 或 `CLEAR_TOP`。必须始终复用原 Activity/VM、只创建 1 个 MainActivity，并保留 5 轮文字及中断标识、STOPPED 和关闭相机/采音状态。随后执行 `Activity.recreate()` 验证 VM/字幕保留，并把 SavedStateHandle 的有界字幕快照复制到新 handle、在新 ViewModelStore 中构建新 VM，验证 5 轮字幕恢复且会话仍停止；此步骤是新 VM saved-state reconstruction，不是真实系统杀进程。不会执行锁屏，以免设备 PIN 阻塞；结束/失败均取消测试订阅、clear 新 VM store 并关闭测试页面。输出只含固定阶段/失败码、长度、次数和 boolean，不打印字幕正文或配置。

```bash
adb shell am instrument -w -e suite captions com.edgevoice.agent.app.test/com.edgevoice.agent.app.NecessaryChainInstrumentation
```

使用已安装的 debug APK 与 androidTest APK。默认入口与 `daily` 的提问来自测试资产、图像来自内存数字卡；这两个入口不会打开 AudioRecord 或 CameraX。它们会申请真实通信音频焦点、选择通信输出设备并播放合成回答。可选 `capture_local` 会短暂打开真实麦克风，详见下方说明；没有新参数时行为不变。

`daily` 入口先打开真实 `MainActivity` 并让 Compose 页面保持前台，使用窗口 `FLAG_KEEP_SCREEN_ON` 防止测试期间自动熄屏，无需修改系统设置权限。页面停留在空闲态，不点击麦克风/相机，也不调用 VM 启动会话；合成输入由独立测试链路发送。长测的进程内存包含这个前台 UI。结束或失败均关闭测试 Activity；正常结果保留全部原始测试指标。

必要链路三轮回归（默认入口保持不变）：

```bash
adb shell am instrument -w com.edgevoice.agent.app.test/com.edgevoice.agent.app.NecessaryChainInstrumentation
```

仅检查本地 JNI、37ms 短尾音及 20 次通信音频/播放资源开停，不访问云端：

```bash
adb shell am instrument -w -e suite daily -e local_only true com.edgevoice.agent.app.test/com.edgevoice.agent.app.NecessaryChainInstrumentation
```

默认日用长测：先做上述本地检查，再维持真实模型连接至少 1800 秒，分散完成 20 轮语音问题，每第 4 轮展示数字 7/3 交替图卡（允许简短完整句；提取数值后必须全为当前目标7/七或3/三，不能混入其他数值；忽略“一个/一张/一幅”中的量词），第6/14轮要求完整数出1至20。普通问答验证“一加一”的回答包含2；长回答必须按顺序包含全部20个数且语音超过3秒；播放缓冲与生产引擎共用90秒有界默认值（24kHz PCM16约4.32MB）。间隙持续按20ms发送合成静音，不反复生成回答；仅视觉轮发送每秒最多一张人工卡。

```bash
adb shell am instrument -w -e suite daily -e duration_seconds 1800 -e rounds 20 -e vision_every 4 com.edgevoice.agent.app.test/com.edgevoice.agent.app.NecessaryChainInstrumentation
```

可选人工网络故障窗口（测试不会切换系统网络）：

```bash
adb shell am instrument -w -e suite daily -e duration_seconds 1800 -e rounds 20 -e recovery_test true com.edgevoice.agent.app.test/com.edgevoice.agent.app.NecessaryChainInstrumentation
```

第 3 轮完成后，先结清此前的自然恢复，再打开唯一的人工故障窗口。收到 `daily_recovery_disable_network` 才由操作者断开当前实际网络；收到 `daily_recovery_restore_network` 再恢复。等待断开限 70 秒；首次断开后，同一恢复窗口最多再等待 70 秒达到新连接的协议 READY，重复重连事件不延长该窗口。只有人工窗口内观察到的 RECONNECTING → 新 epoch READY 才能设置 `manual_recovery_passed=true`，此前的自然断线不能代签。若关闭 Wi-Fi 后仍由移动网络保持连接，此故障窗口不会被当作成功。

自然断线与人工故障分别计数。未开启人工窗口时，RECONNECTING、短暂 unready 及可恢复发送异常也进入有界恢复，不立即判 FAIL；同一窗口须在 70 秒内达到新 epoch READY，FAILED/CLOSED、不可恢复错误、缺失语音/不完整回答仍立即失败。恢复期间暂停全部合成输入、丢弃当前问题剩余旧 PCM，输入按原始 epoch 发送；立即清空旧播放队列与完成标记，并执行 `AudioPlaybackEngine.stop()` → IO `awaitStopped()`，确认旧 worker 已退出后才在新 READY 上重新启动播放，避免旧硬件写入计入下一次 attempt。全程 played 字节累计所有已停止播放实例，成功 attempt 的完整字节判据仍使用其独立基线。音频层沿用真实 worker 释放屏障，不会超时后假装已释放仍在使用的资源。

轮间断线恢复后继续原定时间表；提问/等待回答/播放期间断线会废弃整个 attempt，仅在新 READY 后重建 Round、完整重问同一道合成问题，每轮最多重问一次、全程最多两次。中断 attempt 不计入成功轮数，不能拼接其 ASR、文字、音频或播放完成标记。已经完成但数值错误、没有语音或有 `MISSING_AUDIO` 等输出警告的回答不会被自动重问掩盖。原有每轮 100 秒与整套 `duration_seconds + 180` 秒超时仍保留。阶段包括 `daily_natural_recovery_wait_ready/ready`、`daily_planned_recovery_wait_ready/ready`、`daily_round_N_interrupted/reasked`。

`daily` 会在真实 WebSocket 文本回调进入 Executor 前记录协议计数：每轮发送前保存累计基线，运行快照及每轮完成快照中的 `active_round_wire_*` 是本轮增量。包括两种音频 delta 事件数量、base64 编码字符总数、白名单文本/音频转写事件类型计数，以及 `response.done` 的 completed/cancelled/incomplete/failed/other 状态次数。仅用于区分服务端未发送音频与 Executor 未交付音频，不解码、不存储或输出音频及文本正文，也不记录密钥、URL。包装器委托全部传输行为，原生 WebSocket 心跳与恢复不变；由于 Executor 的具体类型强转无法穿过测试包装器，会缺少 session.update 配置往返 RTT 诊断，不改变会话配置确认行为。

每45秒输出进度、PSS/Java heap的基线、当前值与增长、首音p50/p95/max、回答播放完成p95、音频收发计数及队列采样峰值；同时保留 `natural_reconnect_events`、`planned_reconnect_events`、`natural_recovery_windows`、成功的 `natural_recovery_count`、`interrupted_attempts`、`reasked_questions` 和结束前的 `terminal_connection_status`。可恢复错误仅输出计数、cause 的安全简单类名及可选的 `TransportFailure.statusCode` 数字，不输出 message、URL 或 key。首音时间从测试PCM最后一个有效语音采样送出时计算，不等于人耳听到第一声的端到端时延；播放完成以AudioTrack播放头完成回调为准。采样队列峰值不宣称捕获全部瞬时尖峰。

必须同时满足请求时长（默认至少1800秒）、全部20轮的ASR/正确文字/语音、至少5轮数字更新、2轮长回答、逐轮排空和完整写出，且最终会话仍为 READY，才能返回 `status=PASS`。超时、音频抢占、模型错误、队列异常或Java heap/PSS相对启动基线分别增长超过64MB/128MB都返回FAIL。旧Android如需静音补齐，played字节可以大于模型原始语音；API31+要求一致。

`coverage=local_only` 仅表示本地组件通过，不能作为 30 分钟长测通过。缩短 `duration_seconds` 会在结果中保留请求和实际时长，不能作为默认长测通过。此套件不验证真实环境采音、AEC 效果、真实相机画质、实体来电/耳机切换或页面前后台流程，须另行体验。

结果仅包含阶段、数字计数和错误类别，不输出 key、地址、原始音频或完整转写。

## 真实麦克风本地资源验证

`capture_local` 是独立、可选的平台采音检查，不创建模型、传输或任何网络连接。运行前必须已有 `RECORD_AUDIO` 权限；缺少权限会返回固定错误码 `capture_local_record_audio_must_be_pregranted`，不会弹权限框，也不会自动授权。

```bash
adb shell am instrument -w -e suite capture_local com.edgevoice.agent.app.test/com.edgevoice.agent.app.NecessaryChainInstrumentation
```

测试托管真实前台 `MainActivity` 并设置窗口常亮，仅保持空闲页面，不点击 Mic/Camera、不调用 VM 启动会话。连续进行3轮：申请通信音频焦点并最多等待4秒使 route.active 成立，再通过现有 `AudioCaptureEngine`、`AudioConfig.REALTIME_UP`（24kHz 单声道 PCM16）和 JNI SPSC ring 实际打开本地麦克风，每轮采集约300ms，计划采集总计900ms（不到2秒；平台启动、停止及安全等待时间另计）。唯一消费者只读取计数，PCM 在内存中读取后立即丢弃并清零临时缓冲，不发送、不写入文件、不打印音频内容。

每轮必须取得正数 `capturedBytes` 和 ring 读取字节，随后结束消费者、执行 `stop()` → IO 线程 `awaitStopped()`，确认真实 `evs-audio-capture` worker 已退出、效果句柄状态已复位、通信音频模式恢复到进入前状态，再安全销毁 JNI ring。异常路径也停止 capture、释放音频焦点；JNI 只在 worker 确认退出后释放，安全等待不会以超时销毁仍在使用的 ring。

结果仅保留阶段/固定失败码、开停/释放次数、捕获/丢弃字节数和每轮 `AEC`/`NS` 状态（`ENABLED`、`UNAVAILABLE`、`NO_CONTROL`、`FAILED`）。效果状态用于记录平台支持及控制情况，不要求设备必须支持，也不代表 AEC 消回声或 NS 降噪听感达标。`status=PASS` 仅覆盖3轮真实 AudioRecord 与本地资源释放，不等同于真实语义问答、AEC 听感测试、完整 Engine 验收或30分钟日用长测通过。

## 真实页面离线生命周期

此入口会启动真实 `MainActivity`，通过无障碍语义点击底部“开始对话/停止会话”按钮，连续开停10次，再开始连接、通过 `UiAutomation.performGlobalAction(GLOBAL_ACTION_HOME)` 执行真实系统Home动作并返回页面。此方式不依赖部分手机禁止的按键注入，系统拒绝动作时仍失败。读取 `ViewModelProvider(activity)` 的同一个 `AgentViewModel`，断言退后台已停止、返回后20秒没有自行重连。没有VM动作替代点击：找不到按钮会失败，结果 `vm_action_fallbacks=0`。

Activity 同样设置窗口 `FLAG_KEEP_SCREEN_ON`，不要求 `WRITE_SECURE_SETTINGS`，也不改系统充电常亮设置。

运行前须由操作者预授权麦克风并断开全部网络（包括移动数据、Wi-Fi 和 VPN），以 `ConnectivityManager.activeNetwork == null` 为硬前提。测试会持续检查离线以及从未达到 `READY`，不请求相机，也不会主动创建 AudioRecord 或 CameraX；真实环境采音、焦点和在线媒体释放由其他验收覆盖。请在独立窗口观察测试阶段，保留原网络状态以便恢复：

```bash
adb shell pm grant com.edgevoice.agent.app android.permission.RECORD_AUDIO
# 操作者先断开全部网络，确认 activeNetwork 为 null 后再执行。
adb shell am instrument -w -e suite lifecycle -e restore_network true com.edgevoice.agent.app.test/com.edgevoice.agent.app.NecessaryChainInstrumentation
```

收到 `stage=lifecycle_restore_network` 后，操作者在 70 秒内恢复原网络。测试不会修改系统网络；检测恢复后再观察 20 秒，确认网络恢复不会复活已停止的会话。`status=PASS` 必须同时有 `ui_start_stop_cycles=10`、`ui_accessibility_clicks=21`、`network_restored=true`、`ready_observed=false`。无网络恢复步骤可省略 `-e restore_network true`，但结果不能作为“恢复网络不复活”通过。

无论成功、失败，最后都会在主线程停止 VM 会话并关闭测试 Activity。测试结束后操作者仍须恢复原网络设置。页面没有联网、媒体没有实际开始，因此此入口不代表“在线采音后退后台资源释放”或“运行中拔耳机/来电”通过。
# 历史工具组件端到端验收

配置 `history.enabled=true`、`history.endpoint`、`history.api.token`，并另设不同的 `history.test.api.token` 供真机验收使用。`tools/run_history_server.py` 将普通记录映射到 `local-device`，将验收合成记录映射到 `local-instrumentation`；测试令牌只写入 Debug APK，缺失或与普通令牌相同时验收直接失败。Debug APK 仅对 `localhost`、`127.0.0.1` 和模拟器地址 `10.0.2.2` 放行 HTTP；真机本地服务可用 `adb reverse tcp:8000 tcp:8000`。

本地 USB 开发时，拔插手机或 ADB 重连会丢失 `adb reverse`。测试和实际记录期间，在另一个终端保持以下前台命令运行；它只监视指定手机、检查电脑历史服务和恢复 `tcp:8000` 转发，并显示服务端已确认保存的记录数与索引数。已有服务会被复用；若由此命令启动服务，按 Ctrl+C 结束时会关闭它。

```bash
server/.venv/bin/python tools/history_dev_link.py --serial <adb devices 中的设备序列号>
```

如果 USB 调试链路不稳定，可先在手机“开发者选项→无线调试”中配对并通过 `adb connect <手机 IP:连接端口>` 接入，再把无线设备的 `IP:端口` 用作 `--serial`。监视脚本会在该无线连接中断后尝试重连，并重新建立同一端口转发；配对步骤仍需在手机上操作。

一次“记一下”只有出现“已保存”证据卡，且上述命令的“已确认保存”数字增加，才算写入当前服务。连接失败时页面会提示“本次未确认保存”；查询失败时会提示“本次查询未完成”，两者都不能当作“没有历史记录”。这里的 USB 服务只供本地开发，手机脱离电脑使用需要独立部署可访问的历史服务。

```bash
adb shell am instrument -w -e suite history com.edgevoice.agent.app.test/com.edgevoice.agent.app.NecessaryChainInstrumentation
# 本次图文检索验收额外要求服务端开启 hybrid：
adb shell am instrument -w -e suite history -e requireHybrid true com.edgevoice.agent.app.test/com.edgevoice.agent.app.NecessaryChainInstrumentation
# 严格验证索引就绪且未降级，保存后用/status等待，不反复调用付费search：
adb shell am instrument -w -e suite history -e requireHybrid true -e requireReady true com.edgevoice.agent.app.test/com.edgevoice.agent.app.NecessaryChainInstrumentation
```

`suite=history` 不打开麦克风或相机；启动前台 Activity 并保持亮屏，避免厂商后台冻结测试进程。它先用 Android Bitmap 解码检查拼图与缺图门控，再通过生产 HTTP 客户端保存昨日合成照片，发送离线 TTS 触发真实 `search_history_frames`，由生产协调器检索并拼图，在同一个 Realtime WebSocket 内完成文字与 PCM 语音回答。当前图与历史图的随机四位编号只存在照片像素中，必须两者都读对、说明编号不同才通过。

新版另断言 HTTP 搜索携带的当前图与本轮冻结图完全一致，索引中空结果不会解释成没有记录。`-e requireHybrid true`要求响应`retrieval.mode=hybrid`；输出`current_query_image_submitted`、`retrieval_mode`、`retrieval_status`、`android_retrieval_state_checks`。即使索引尚未完成仍可使用明确名称的关键词候选，但不能把该次结果视作向量召回证明；独立真实图文模型探针见`tools/smoke/visual_retrieval_smoke.md`。

测试只输出阶段、布尔值和字节计数，不输出 Key、token、端点、转写或回答全文。它检查实际发送的组合 JPEG 字节与协调器产物一致、`function_call_output`、commit、受控 VAD/工具切换及恢复，并在 `finally` 删除合成记录。若查询混入任何非本次 fixture 记录，会在向模型提交历史图片前失败；因此请使用隔离的测试用户。

通过结果应含 `status=PASS`、`android_image_gate_checks=6`、`websocket_connections=1`、`exact_comparison_jpeg_events=1`、`current_and_history_codes_correct=true`、`text_and_audio_complete=true`、`fixture_deleted=true`。这是合成输入的组件端到端验收，不替代实际相机、麦克风、插话与页面生命周期验收，也不验证 AudioTrack 实际播放。

`question_history.pcm` 为 macOS 内置 Tingting 离线朗读后转换的 24kHz、单声道、PCM16LE 裸音频（14.11 秒），无个人语音。朗读内容：“请调用历史检索工具，查找验收台灯九四七三最近七天的照片，对比当前和历史画面。请读出两张图上的四位编号，并说明是否相同。”
