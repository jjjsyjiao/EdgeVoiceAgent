# 历史画面工具服务

`/visual-memory` 为主动记忆保存照片和用户提供的名称、别名、位置、便签。默认保留元数据规则基线；可选开启图文向量、关键词混合检索和多模态重排。客户端把召回候选和当前画面交给当前 Realtime 会话比较，再由该会话生成文字与语音。

只检索主动保存且仍留存的照片，不会连续记录生活。开启向量检索后，未写进备注的视觉内容也参与候选检索，但真实场景召回率尚未测定。`source=user_recorded` 表示位置和便签来自用户记录，不代表模型确认物品现在仍在该位置。

## 图文检索（2026-10-28 第一批交付）

在现有存储上设置 `VISUAL_RETRIEVAL_ENABLED=true` 和 `VISUAL_EMBEDDING_API_KEY`。默认模型 `qwen3-vl-embedding`，2560 维；`VISUAL_RERANK_ENABLED=true` 开启 `qwen3-vl-rerank`。它们是检索模型，最终比较/回答仍由同一 Realtime 完成。

- `VISUAL_EMBEDDING_ENDPOINT` / `VISUAL_RERANK_ENDPOINT` 分别配置完整 REST URL；不能填写 Realtime WebSocket URL。默认通用 DashScope HTTPS 地址已用真实接口验证；也可显式配置对应业务空间地址。
- 保存事务通过 SQLite 触发器建立持久索引任务；图片与文字独立向量，旧记录启动回填，失败退避重试，最多8次后进入 failed。`GET /visual-memory/status` 查询自己记录的索引状态，`POST /visual-memory/reindex` 重新排队自己失败的任务。两者同样要求 Bearer。
- 一套 SQLite 只运行一个配置中的 worker/namespace；不使用 uvicorn 多 worker。更换模型/维度生成隔离 namespace 并回填，不混合旧向量。删除、TTL、容量淘汰同步清除索引；回填中的旧任务不能复活删除/重建的记录。
- 原问与工具 query 保留，固定生活词典最多扩展到3个查询；关键词 FTS5 使用中文二字词与英文数字完整词，**不是已完成中文语言分词或生成式查询改写**。
- 元数据规则、关键词BM25、文字搜文字、文字搜图片、可选图片搜图片分别排名，RRF融合；每路最多50，候选100，最多30张进入重排，最终最多3张。图片查询时分别进行文字/图片重排再融合。
- “最近一次”在重排预算前为最新名称/别名候选保留位置；不建立未经用户确认的实物身份关联。关键词不足、索引不完整或分数未校准时，不能声称已找到目标的最新位置。
- 最终证据重新检查拍摄范围和内容版本；低排名坏图补位，部分缺失明确提示。当前查询图片仅在内存参与检索，不保存为新历史。
- 查询状态附 `retrieval`：`mode=hybrid`，`status=ready/indexing/degraded/unavailable`，pending/failed计数与固定warning码。即使顶层为`no_match`，indexing/degraded/unavailable也不得解释为没有历史记录。
- 语义相似度阈值尚未用真实集校准：返回的是待核对候选，`uncalibrated_similarity`要求Realtime看图核对。不能用matched数量、规则score或合成探针计算真实召回率。

最小查询扩展字段：`raw_query`（最多2048字符）、`query_image_base64`（可选，256KiB JPEG）、`intent`（recall/find_last_location/compare），原`query/from_ts_ms/to_ts_ms/limit`保持兼容。当前自然语言时间仍由Android确定性解析器处理；更复杂的模糊时间规划尚未实现。

评测入口见 [评测与真实集采集](evals/visual_retrieval/README.md)。`metadata`基线报告中的合成契约测试不表示真实召回率。生产不默认记录原话、照片或检索轨迹；只有显式离线评测才导出逐查询排名。

旧`/healthz`的`embedding`字段属于文字偏好服务，可能显示`hashing`；新视觉索引的模型/维度看带鉴权的`/visual-memory/status`中`index.namespace`。

## 启动

```sh
python3 -m venv .venv
.venv/bin/python -m pip install -r requirements.txt
export EMBEDDING_PROVIDER=hashing
export VISUAL_MEMORY_DIR=./data/visual-memory
# 先在本机生成随机凭据，替换下面的示例值；同一个 owner 可配置多个轮换 token。
export VISUAL_MEMORY_AUTH_TOKENS='{"replace-with-a-long-random-token":"my-private-owner"}'
.venv/bin/python -m uvicorn app.main:app --host 127.0.0.1 --port 8000
```

Android 调试配置使用服务根地址与同一 token；USB 真机可通过 `adb reverse tcp:8000 tcp:8000` 访问电脑的 `http://127.0.0.1:8000`。远程部署使用 HTTPS。凭据不要提交到版本库，也不要放在图片地址或查询参数中。

`VISUAL_MEMORY_AUTH_TOKENS` 未配置或为空对象时，新接口返回 `503 visual_memory_not_configured`，不会悄悄使用公共 owner。无凭据/凭据错误返回 `401`。服务端从 Bearer token 确定 owner，不接受客户端传入 `user_id`。旧 `/memory`、`/session` 是独立的开发原型接口，不会访问新照片目录；远程部署应在入口限制这些旧接口。

| Environment variable | Default | Meaning |
| --- | --- | --- |
| `VISUAL_MEMORY_AUTH_TOKENS` | empty / disabled | JSON mapping of bearer tokens (16–4096 characters) to private owner identifiers. |
| `VISUAL_MEMORY_DIR` | `./data/visual-memory` | SQLite metadata and owner-isolated JPEG storage directory. |
| `VISUAL_MEMORY_RETENTION_DAYS` | `7` | Retention from server insertion time, in days. |
| `VISUAL_MEMORY_MAX_BYTES` | `536870912` | Global retained JPEG byte budget; oldest records are evicted first. |
| `VISUAL_MEMORY_OWNER_MAX_BYTES` | `134217728` | Per-owner retained JPEG byte budget; oldest records for that owner are evicted first. |

保留期和容量限制可能让记录提前不在查询范围内；查询无命中只能表示“现存记录中未找到”。服务启动、保存和查询时会清理过期记录。当前实现是单节点本地文件 + SQLite，部署需保留该目录；多副本分布式存储不在本次范围内。

## 接口

所有新接口必须带 `Authorization: Bearer <token>`。图片为裸 Base64 JPEG，最多 256 KiB 二进制、约 350 KiB Base64，最多 16,777,216 像素；服务端真正解码验证图片，不接受仅有 JPEG 魔数的空壳文件。

`POST /visual-memory/records`：

```json
{
  "record_id": "client-generated-uuid",
  "session_id": "capture-session-uuid",
  "captured_at_ms": 1793100000000,
  "object_name": "备用钥匙",
  "aliases": ["备份钥匙"],
  "location": "玄关柜上层",
  "note": "口述：蓝色托盘里面",
  "image_base64": "<bare JPEG base64>"
}
```

响应 `{ "ok": true, "record": { ... } }`；record 包含请求字段及 `recorded_at_ms`（服务端入库时刻）、`source="user_recorded"`、`score=1.0`。相同 owner/record_id/内容可幂等重试，内容不一致返回 `409 record_id_conflict`。单张图片超过存储预算返回 `507`，存储故障返回 `503`，不会声称保存成功。

`POST /visual-memory/search`：

```json
{
  "query": "备用钥匙",
  "from_ts_ms": 1793030400000,
  "to_ts_ms": 1793116800000,
  "limit": 3
}
```

时间可省略。查询不绑定 session，同一 owner 可跨会话召回。时间使用 `captured_at_ms`，区间左闭右开。客户端根据用户时区把“昨天”转换为昨天零点到今天零点；服务端不把“昨天”擅自解释成最近 24 小时。`limit` 为 1–3。

响应包含：

- `status`：`matched`、`no_match` 或 `evidence_unavailable`。最后一种表示命中元数据但证据图片丢失、被修改或无法解码；不能继续做无图比较。
- `records`：带真实 `image_base64`、拍摄时刻、用户备注、来源和匹配分数的证据，按匹配程度和新旧排序。
- `coverage`：当前 owner 全部现存记录的最早/最新拍摄时刻、数量和保留天数；`continuous=false` 表示离散照片，不能据此推断两个时刻之间都被记录。
- `unavailable_records_count`：本次命中中不可用的证据数。即使返回其他正常图片，也应让用户知道存在缺失证据。

匹配分数是排名信号（基线为元数据规则，hybrid为融合分数），不是识别同一物品的概率。无匹配、缺图、网络/鉴权错误必须分别处理，均不能让 Realtime 编造历史画面。

`DELETE /visual-memory/records/{record_id}` 返回 `{ "ok": true, "deleted": true|false }`。删除同时移除当前 owner 的元数据与图片；不存在或属于其他 owner 的 id 均返回 `deleted=false`。

## 验证

```sh
.venv/bin/python -m pytest tests -q
```

测试使用临时目录、真实可解码 JPEG 和测试 token，不读取真实个人照片、不调用云模型。覆盖鉴权、owner 隔离、跨会话自然日边界、名称/别名检索、幂等保存、损坏证据、删除、重启清理、保留期与容量淘汰。
