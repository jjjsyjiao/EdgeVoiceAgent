# 真实生活记录集：采集、标注和切分

不要复制合成 fixture 后改成 `real_labeled`。它没有真实媒体或独立查询，不能成为真实评测集。

## 创建空模板

在 `server/` 中执行下面命令，把输出放在代码仓库之外的新私有目录。工具仅创建空文件，不会读取手机、生产服务、密钥或照片。

```bash
.venv/bin/python -m evals.visual_retrieval.make_template \
  --output /absolute/path/to/new-private-dataset
```

生成目录权限 0700、数据文件 0600，包含 manifest、空 records/queries/gold JSONL、images 目录和 schemas.json。已存在的目录不会被覆盖。空数据集会被评测器拒绝，这是未完成采集的状态，不是一份“零召回”报告。

## 第一轮样本计划

目标先收集 300～500 个观察事件、约 50 类物品、300 条可回答问题和 100 条无答案问题。它们是采集目标，当前尚未完成。小规模预演可以先做 30～50 个真实事件，检验标注协议，再扩充数据。

每位参与者主动保存日用品，覆盖多个地点、光线、距离、部分遮挡和移动前后。至少包括钥匙、眼镜、充电器、杯子、遥控器、证件包等易混物品，同款不同实物作为困难负例。镜头里未被口述的物品、没有名称别名的画面也要保留。

一次“记一下”形成一个 `event_id`；全景/近景可以是多个 `record_id`。物品后来移动并再次观察形成新事件。不要把同一物品全部日期合成一个事件，也不要把同一连拍的每帧都当成独立相关事件。

查询应来自实际任务，例如“我把那条黑色线放哪里了”“上次钥匙在哪”“这个杯子之前见过吗”。让提问者先提出问题，再查看记录和标签，避免直接复制数据库名称。保留原问，改写作为同一个 query_family；当前图查询记录真实提问时的照片。

## 文件字段

### records.jsonl

每行包含：

| 字段 | 含义 |
|---|---|
| record_id / event_id | 单张图片标识 / 观察事件标识，全数据集唯一 |
| owner_id | 脱敏所有者标识，不能使用真实姓名/邮箱 |
| captured_at_ms | 实际拍摄时间；时间查询按用户时区先转换为毫秒范围 |
| object_name / aliases / location / note | 保存当时已有的信息，不可根据测试查询事后补齐关键词 |
| split | train、dev 或 test |
| leakage_group_id | 不可跨 split 的主体/家庭/物品关联组 |
| image_path / image_sha256 | 数据集相对路径及实际文件 SHA-256 |

例如结构如下；占位符必须换成真实采集结果，不能把本示例计入真实样本：

```json
{"record_id":"r001","event_id":"e001","owner_id":"p001","captured_at_ms":1793106000000,"object_name":"备用钥匙","aliases":[],"location":"玄关托盘","note":"记一下，备用钥匙放在玄关托盘","split":"test","leakage_group_id":"household001","image_path":"images/r001.jpg","image_sha256":"<实际64位sha256>"}
```

### queries.jsonl

必须有 query_id、owner_id、text、intent（lookup/latest/compare/recall）、split、leakage_group_id、query_family_id、tags。可选明确时间范围 from_ts_ms/to_ts_ms，以及 current_image_path/current_image_sha256。

建议 tags 至少覆盖 exact、alias、synonym、appearance、current_image、similar_objects、moved_object、time、no_note、no_answer。标签用于细分表现，不能由检索成功与否决定。

```json
{"query_id":"q001","owner_id":"p001","text":"我上次把钥匙放哪了","intent":"latest","split":"test","leakage_group_id":"household001","query_family_id":"family001","tags":["moved_object","latest"]}
```

### gold.jsonl

每个查询必须且只能有一个 gold：query_id、coverage、relevant_event_ids、annotation_note。available 的 latest 查询还必须给出 latest_event_id。

```json
{"query_id":"q001","coverage":"available","relevant_event_ids":["e001","e002"],"latest_event_id":"e002","annotation_note":"复核两次钥匙记录，e002为查询时间范围内最后一次观察。"}
```

gold 的所有相关事件必须存在于记录快照，属于同一 owner/split 且至少有一帧满足时间范围。latest 标注须确实是相关事件中的最新时间；并列最新可以选定一个作为预先约定的主证据，不能等看完排名后选择有利标签。

## 标注流程

1. 冻结一份记录快照。原话/时间/图片不能随查询或检索结果修改；新增信息必须形成新的版本。
2. 先写问题，再由标注者审查该 owner 和时间范围内的全部事件。相关性表示“能帮助回答这个问题”，不是单纯外观相似。
3. 标签尽可能列全相关事件。可用多种检索器做候选辅助，但不能只查看当前检索器返回的结果，否则会把它的漏召从分母中消除。
4. “最后放在哪”列出全部相关观察，并单独标记最近事件；同款不同实物须根据采集事实区分。无法确认实物身份的证据可以支持“不确定”的回答，不能强标为同一个。
5. 无答案问题要有审查依据：未采集/过期/确实不存在必须分开。有疑问用 unknown，不能为了凑负例数量写 verified_absent。
6. 至少双人复核测试集，记录分歧和最终裁定；问题过于含糊时保留备注、单列 ambiguity 标签或重新采集，不能根据模型答案倒推标签。
7. 运行 schema/哈希/泄漏检查后冻结 test。调参只看 dev；失败分析若用于调参，应在下一轮换新的冻结 test。

## 防止切分泄漏

优先按参与者/家庭划分 train/dev/test。样本不足时至少按物理物品及其所有观察分组；同一背景、同一连拍、同一图片变换和改写问题必须归入同一组。分组关系交叉时先合并连通组，再分配 split，不能逐条随机划分。

`leakage_group_id` 是人工定义的防泄漏边界，不是简单 session_id。自动检查只认识已提供的组和相同 SHA；它不会自动知道两张裁剪图来自同一原图，也不会识别同一个实物。保存人工分组依据和复核记录。

## 冻结与报告

记录 dataset_id、采集日期范围、模型/算法版本、标注者复核方式、分组策略和查询类别数量。报表必须同时给出样本量、置信区间、各类查询表现、错误率和约束检查。

初始验收目标可用 candidate Hit@50≥95%、final Hit@3≥90%、latest Hit@1≥90%、无答案误报≤5%，但不要把点估计到线当成样本量已充分。发现相关事件超过 K 时继续按完整事件数计算 Recall，长时间回忆另做分页/时间线验收。

参与者真实内容留在私有目录；提交到代码仓库的只能是匿名化汇总、经过审查的合成样例和不含敏感内容的失败类别。不得提交原始语音、私有照片、访问令牌或完整个人原话。
