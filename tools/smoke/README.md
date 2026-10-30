**可选电脑端协议烟测**

此工具用于在不连接 Android 手机时，单独检查百炼模型鉴权、语音识别、文字/音频返回和图像理解。它不参与 Gradle 构建，也不进入 Android 安装包；日常开发可直接使用手机端 Instrumentation 和单元测试。

当前脚本从项目根目录 `local.properties` 读取与 App 相同的 provider、完整接入 URL、模型及 API Key，不依赖 `QWEN_WORKSPACE_ID` 或额外 `.env`。输入为系统合成语音和人工数字卡，不采集麦克风或摄像头。

脚本依赖 macOS 的 `say`、`afconvert` 与 Tingting 普通话音色，以及 Python 的 websocket-client 和 Pillow。下面的命令均在 Android 项目根目录执行：

```bash
python3 -m venv tools/smoke/.venv
tools/smoke/.venv/bin/python -m pip install -r tools/smoke/requirements.txt
tools/smoke/.venv/bin/python tools/smoke/necessary_chain_smoke.py --help
```

明确需要调用真实模型时执行（产生少量模型费用）：

```bash
tools/smoke/.venv/bin/python tools/smoke/necessary_chain_smoke.py --live
```

三轮分别验证纯语音、数字7、同一会话画面更新为3，结果与合成提问对应的回答音频写入本目录的 `artifacts/`，已加入忽略规则。它验证模型协议，手机 AudioRecord、CameraX、AudioTrack 仍需 Android 设备验证。

旧协议探索脚本、旧虚拟环境和历史人工测试产物已移出项目；本目录仅保留当前可复用的烟测工具。

**同一个 Realtime 模型的历史画面对比验证**

`realtime_history_smoke.py` 独立验证实际 Function Calling、本地 HTTP 历史样本检索，以及当前图/历史图送回同一 Realtime 模型后的文字与语音。它不修改 Android 安装包、界面或历史开关，不调用第二个多模态模型。合成随机编号卡片只用于核验图片是否被正确理解，不代表真实台灯身份判断或语义检索精度。

使用独立依赖文件（已在 Python 3.9 验证），先运行不调用云端的检查：

```bash
python3 -m venv /private/tmp/edgevoice-realtime-history-venv
/private/tmp/edgevoice-realtime-history-venv/bin/python -m pip install -r tools/smoke/realtime_history_requirements.txt
/private/tmp/edgevoice-realtime-history-venv/bin/python tools/smoke/realtime_history_smoke.py --offline-check
```

显式调用当前配置的百炼 Realtime：

```bash
/private/tmp/edgevoice-realtime-history-venv/bin/python tools/smoke/realtime_history_smoke.py --live --mode controlled --layout collage
```

每组新建连接；组内实际工具调用、检索结果回传、比较回答保持同一连接/会话/模型。工具只返回记录 ID、标签和时间，随机四位编号仅存在图片像素中。`controlled` 会把原问题音频与当前/历史图作为新的音画输入项提交，随后创建比较回答；不代表给已提交的旧问题原生追加图片。用户不需要重新说话。

默认覆盖相同、不同、缺图三组；需要同时满足文字判据、非静音 PCM、`audio.done` 和 `response.done=completed`。各组保存合成图片、回答 WAV、脱敏事件日志、结果 JSON 与执行时源码哈希。`--layout frames` 为独立帧对照；`--mode direct` 是不提交新媒体项的探索模式，不应与受控回合的结果混记。实测结果和限制见 [历史画面对比最小验证记录](realtime_history_proof.md)。

**手机验收的可选操作脚本**

`run_device_acceptance.py` 只依赖 Python 标准库和 ADB，不读取模型密钥。先在手机安装主 APK/测试 APK、授权麦克风并解锁屏幕。生命周期套件强制离线，因此不会启动实际收音。脚本记录原 Wi-Fi、移动数据设置，在正常结束或异常退出时恢复；测试窗口通过普通 App 窗口标志保持亮屏，不修改系统充电常亮设置。

```bash
python3 tools/smoke/run_device_acceptance.py --suite lifecycle --log tools/smoke/artifacts/lifecycle.log
python3 tools/smoke/run_device_acceptance.py --suite daily --detached --recovery-test --log tools/smoke/artifacts/daily.log
```

生命周期套件会短暂关闭 Wi-Fi 和移动数据；日用套件仅在第3轮后的明确恢复窗口断网，整套默认至少30分钟。测试期间让手机连接电脑并保持解锁，不同时操作通话或音频应用。

日用套件建议加 `--detached`：通过手机的 `nohup setsid sh` 启动测试，将日志和结束标记写入 `/data/local/tmp/edgevoice-acceptance-<唯一编号>.log/.result`，具体路径打印在本地日志中。电脑每5秒增量镜像手机日志，短暂 USB/ADB 断开后会继续轮询同一次测试；ADB 恢复不会算作云端重连成功。断网与恢复阶段按日志去重处理，测试自身的 READY、断网与回答断言保持不变。必须同时取得 `DONE:0` 结束标记与 `status=PASS` 才通过，最长等待为指定测试时长加300秒。

手机重启或重开USB调试会改变adbd服务生命周期，仍可能终止远端测试；这类中断不会记作通过，需要完整重试。

异常或取消时会尽力停止失败的测试，并在最多90秒内重试恢复原 Wi-Fi、移动数据设置。恢复仍失败会明确返回失败，需要重新连接手机后恢复；不会把设置恢复失败当作验收通过。手机端受控日志保留供排查，不含密钥或真实媒体。

结果是合成输入组件验收，真实外放打断、AEC、CameraX及实体耳机/来电仍需体验，详见 `app/src/androidTest/README.md`。
