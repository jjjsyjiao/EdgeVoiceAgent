# 历史检索离线评测（第一批交付）

这里评测“给定已经保存、仍然可用的证据，检索能否找到相关事件”。不评测采集覆盖率、Realtime 最终回答正确性、手机语音播放或完整找物成功率。

当前仓库只有 **synthetic_contract 合成元数据契约样例**。它验证评测代码、现有规则检索和未来适配器的接口，**没有真实照片，不是多模态模型评测，不能用它宣称高召回率**。真实集需要按 [采集与标注说明](templates/README.md) 另行建立。

## 从 App 记录开始建立真实集

已生成私有空工作区 `server/data/visual-retrieval-eval-private/`。2026-10-28 对当前 `local-device` owner 的首次只读快照为 `exports/20261028-initial/`：**source_record_count=0、exported_record_count=0、status=no_data**。这不是评测集，尚无真实召回率。

2026-10-29 排除 USB 端口转发故障后，服务端确认收到 1 条新的真实记录并完成索引；该记录的物品名称查询在 hybrid 检索中返回了同一记录。只读快照保存于 `exports/20261029-confirmed-one/`，导出 1 条、跳过 0 条、创建标签 0 条。这个单条自查询只验证链路，不能用于估计真实召回率；原先尝试的五条记录并未出现在当前服务端数据库中。

用户通过 App 主动保存照片后，在 `server/` 运行（每次选择新的输出目录）：

```bash
.venv/bin/python -m evals.visual_retrieval.export_store \
  --store data/visual-memory-local \
  --owner local-device \
  --owner-alias pilot001 \
  --output data/visual-retrieval-eval-private/exports/session-001
```

`--owner` 是服务端所有者标识，**不是 API token**。导出器使用 SQLite `mode=ro`，不会调用会清理记录的 Store 构造器，不会改变源存储。仅复制该 owner 的实际 JPEG 与元数据；图片须通过哈希、字节数和真实 JPEG 解码检查。缺失/损坏图片单列为 skipped，不伪造记录。可显式传入 `--retention-days` 在快照中排除过期记录，源记录不会被删除；不传则不猜测生产保留期。

导出结果是 `records.pending.jsonl`，保留源会话和记录 ID 以便复核，一条现有记录暂对应一个候选事件。split 与 leakage_group_id 留空，正式 `records.jsonl`、`queries.jsonl`、`gold.jsonl` 仍为空。没有自动生成查询、相关性标签或测试切分。

复核 `assignments.template.jsonl`，填写每条记录的 event_id、split、leakage_group_id 后另存为 assignments.jsonl，再执行：

```bash
.venv/bin/python -m evals.visual_retrieval.finalize_records \
  --dataset data/visual-retrieval-eval-private/exports/session-001 \
  --assignments data/visual-retrieval-eval-private/exports/session-001/assignments.jsonl
```

它只把人工分组结果写入空 records 模板，不覆盖已有记录或标签。接下来仍需要按采集协议人工写真实问题、完整 gold，并更新 manifest。私有导出/报告目录权限 0700、数据和 JPEG 0600；重复输出目录会拒绝覆盖。

## 运行

从 `server/` 执行，无网络或模型调用：

```bash
.venv/bin/python -m evals.visual_retrieval.run \
  --dataset evals/visual_retrieval/fixtures/synthetic_contract \
  --adapter metadata \
  --output evals/visual_retrieval/reports/synthetic-contract-metadata-v1.json \
  --save-ranked-results evals/visual_retrieval/reports/synthetic-contract-metadata-v1.ranked.jsonl
```

该适配器直接调用当前 `app.visual_memory_store.metadata_score`，复现 owner、显式时间范围和分数/时间排序。评测切分还要求记录与查询处于同一 split。默认输出候选前 50 个 record、最终前 3 个 record。它不读取生产数据库，不读 `local.properties`，不加载图片，不验证 HTTP、存储 TTL 或 Android 链路。

报告保存 dataset 内容 SHA-256、评分函数源码 SHA-256、候选预算、完整分组统计和每条查询的排名/命中/违规。源码 SHA 用于发现基线算法变动；它不是对整个运行环境的完整复现保证。

## 未来检索方案适配

任何模型或服务只需输出以下 JSONL，字段严格校验，行顺序无要求：

```json
{"query_id":"q1","candidate_record_ids":["r8","r3","r2"],"final_record_ids":["r3","r8"],"abstained":false,"error":null}
```

- `candidate_record_ids` 是进入最后证据选择之前的有序候选，应记录实际候选池，不可根据 gold 补入答案。
- `final_record_ids` 是实际交给 Realtime 的最多 3 条证据，必须来自候选池。排序可以与候选池不同。
- `abstained=true` 必须对应空 `final_record_ids`。调用失败以 `error` 标记，最终证据必须为空；错误查询仍保留在分母。
- 每个查询必须有且仅有一行。不能跳过困难、超时、空结果或未知覆盖的查询。
- ID 不可重复；多个 record 可以属于同一个事件，评测按 event 去重。
- 非法 owner、时间、split 或未知 record 不计为命中，仍占据原始排名位置，并使约束检查失败、CLI 退出码为 2。

```bash
.venv/bin/python -m evals.visual_retrieval.run \
  --dataset /absolute/path/to/private-dataset \
  --ranked-results /absolute/path/to/ranked_results.jsonl \
  --verify-media \
  --output /absolute/path/to/report.json
```

同一个固定测试集可比较 metadata、embedding、hybrid、hybrid+rerank 四份结果。数据集 fingerprint、K 和候选预算不一致时，不应直接比较数字。调参使用 dev，冻结 test 后只做验收。模型版本、参数、耗时、费用、降级原因由适配器另存运行说明；`ranked_results` 中不写图片、密钥或 HTTP 请求头。

## 运行实际服务的隔离适配器

`pipeline_adapter` 在临时私有 Store 内重建已选数据集，复用生产服务的 query expansion、FTS、融合、图片校验与证据选择。它不会连接或修改生产服务。

默认模式没有 embedding 实现，不会调用模型或网络，但要求数据集里有真实声明的可解码 JPEG，不会把元数据样例补成虚假图片：

```bash
.venv/bin/python -m evals.visual_retrieval.pipeline_adapter \
  --dataset /absolute/path/to/labeled-dataset \
  --output /absolute/path/to/new-keyword-report
```

仅在显式选定的图片/文字允许发送到云端、且已在环境变量配置密钥后，手动开启云模型。工具从不读取 local.properties，也不把 key 放入命令参数或报告：

```bash
.venv/bin/python -m evals.visual_retrieval.pipeline_adapter \
  --dataset /absolute/path/to/labeled-dataset \
  --output /absolute/path/to/new-cloud-report \
  --mode cloud --allow-cloud --rerank
```

默认读取 `VISUAL_EMBEDDING_API_KEY` 环境变量；可用 `--api-key-env` 更换变量名。cloud 默认预算最多 50 条记录、25 个查询，每条记录最多一次索引尝试；超过预算会在请求前拒绝。大规模验收需先审查调用量，再明确设置 `--cloud-record-limit` / `--cloud-query-limit`。`--rerank` 是额外显式开关，不传则只做混合召回。

输出包括通用 `ranked_results.jsonl`、服务 `traces.jsonl` 和完整 `report.json`。trace 记录各通道排名、重排排名、候选/最终排名、耗时与降级状态；不含原图、原话或凭据。report 同时报告 keyword_only、indexing_failed、embedding_unavailable 等次数：降级成功的结果仍可参与指标，但不能当成云语义检索成功。自动回退、无答案误报与拒答阈值必须在真实测试集上一起检查。

该适配器不测 Android、Realtime、最终生成回答或连续录像。默认本地运行仅衡量关键词与融合链路；纯元数据 fixture 仍用前面的 metadata 基线入口。

## 指标定义

| 字段 | 分母与解释 |
|---|---|
| `candidate_hit_at_K` | coverage=available 的查询数；前 K 条记录至少有一个相关事件 |
| `event_recall_at_K.macro_estimate` | 对每个 available 查询计算“找回的不同相关事件数 / 全部相关事件数”，再平均 |
| `event_recall_at_K.micro_estimate` | 所有 available 查询的已找回相关事件数之和 / 标签相关事件数之和 |
| `final_hit_at_3` | available 查询数；最终最多 3 条证据至少命中一个相关事件 |
| `latest_event_hit_at_1` | available 且 intent=latest 的查询数；最终首条是标注的最新相关事件 |
| `no_answer_false_positive_rate` | verified_absent/not_captured/expired 查询数；其中返回了任何最终证据的比例 |
| `error_rate` | 全部查询数；适配器明确报错的比例 |
| `abstention_rate` | 全部查询数；没有输出最终证据的比例 |

Recall 分母不会被 K 截断。例如一问有 80 个相关事件，前 50 条只找到 40 个事件时 Recall@50=40/80，不能按 40/50 计算。候选中的相同事件多张图不会增加相关事件数，仍占用 record 排名位置，能暴露重复图片挤占预算的问题。

Hit、最新事件命中、无答案误报等查询级二项比例给出样本量、命中数和 95% Wilson 区间。分母为 0 时结果为 null，不能写作 0% 或 100%。事件 Recall 是分数且存在相关性，不套用二项 Wilson 区间。来自同一家庭、物品或改写家族的问题也不独立，Wilson 只作描述性参考；上线结论需要按参与者/家族分组重采样。

按 `intent`、`tags`、`split` 分组输出相同指标；tag 组可重叠，不能相加当总样本量。只看总体平均会掩盖同义词、当前图指代、相似物品或时间问题的失败。

## 采集覆盖与检索分开

gold 的 `coverage` 必须明确标注：

- `available`：相关、可用的历史证据存在于评测快照，必须给出完整相关事件列表。
- `verified_absent`：人工审查确认在约定范围内没有相关记录。
- `not_captured`：有依据确认相应生活事件发生过，但没有保存画面。
- `expired`：有依据确认保存过，但在当前快照已到期/删除。
- `unknown`：无法确认是否曾采集或是否完整标注；不混入正例/无答案分母。

报告单独给出上述数量，以及 available/(available+not_captured+expired) 的“已审计采集可用比例”。这只描述标注样本，不能推断用户全天的生活覆盖。模型把无关图片作为无答案查询的证据返回，仍计误报；全部超时/全部拒答不会被误写为高召回，错误率、拒答率与正例命中同时报告。

## 数据与泄漏检查

契约定义见 `schema.py`；生成真实集空模板时会同时导出 JSON Schema。字段类型严格、禁止未知字段；时间是毫秒、范围为 `[from_ts_ms, to_ts_ms)`。ID 为脱敏 ASCII 标识。

自动拒绝重复 ID、gold 不完整、gold 越过用户/时间/split、错误最新事件，以及以下跨 split 情况：

1. 同一 `leakage_group_id`。
2. 同一原图 SHA-256，包括查询当前图与历史记录。
3. 同一 `query_family_id`（原问及其改写）。

图片路径必须相对于数据集目录，禁止 URL、绝对路径及 `..`；有图片路径必须带 SHA-256。`--verify-media` 只检查声明的文件与哈希，并拒绝越界 symlink，不会扫描其它照片。真实标注集的历史记录必须有图片路径及哈希。

SHA 无法发现裁剪、缩放、相近连拍或同一个实物：这些必须通过人工分组解决。媒体检查只验证内容哈希；JPEG 解码、缺图处理、模型请求和实际图片编码应另做适配器与服务集成测试。

## 当前交付边界

已交付离线基线、统一适配器结果格式、按事件统计、逐查询诊断、严格范围检查、真实集空模板与标注协议。合成样例的数字仅作回归快照。下一步是采集并复核真实记录与查询，在冻结集合上比较不同检索阶段；真实 Recall@K 仍然未知。
