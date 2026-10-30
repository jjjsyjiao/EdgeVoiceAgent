# 多模态检索真实云组件探针

在项目根目录执行：

```sh
server/.venv/bin/python tools/smoke/visual_retrieval_smoke.py --live
```

不加 `--live` 不会调用云模型。探针从忽略提交的 `local.properties` 读取
`history.retrieval.api.key`，未配置时使用 `openai.api.key`；密钥只保留在内存。
默认调用 `qwen3-vl-embedding`（2560 维）和 `qwen3-vl-rerank`，也读取
`history.retrieval.embedding.*` 和 `history.retrieval.rerank.*` 中的模型和 HTTP URL 配置。
超时默认 12 秒，实际调用会消耗相应服务额度。

探针使用 Pillow 画出的红色杯子、蓝色碗、黄色台灯和绿色方块；没有实拍照片。
图片在临时 SQLite/图片目录中保存，元数据只有“记录 A/B/C/D”等通用名称，
检索目标词没有写进记录。索引和搜索走正式 provider、index、worker、service 和 reranker。
检查文字搜图、同图搜图、最终三个证据包含目标、用户隔离、时间半开区间和删除清理。
结果包含逐通道候选、最终证据 ID、namespace、真实 HTTP 请求数和延迟，不包含图片或密钥。

默认报告：`tools/smoke/artifacts/visual-retrieval-20261028.json`。
可用 `--output` 指定另一报告路径，`--timeout` 调整单请求超时。
临时数据无论成功或失败都会随上下文退出清理，不写入现有用户记录库。

这是 `synthetic_component_e2e`，用于确认真实云模型与检索组件接通。
少量简单合成图不能证明真实生活照片召回率，也没有验证 Realtime 语音、摄像头或播放。
删除目标后可能仍返回相似候选；无答案拒答阈值仍需要独立的真实标注评测。
