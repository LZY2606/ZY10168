# 句轨裁决所（Sentence-Track Arbitration Office）

语言学团队会对**同一个句子**的词形、句法头、关系和不连续成分做多轮修订。
本服务把两个分析版本按 **token 谱系（genealogy）** 对齐，让两位操作者：

- 确认某组 token 的对齐（1:1）或为**拆词 / 合词**选择粒度；
- 对两侧不一致的**依存弧**选择采纳哪一侧（含关系标签冲突）；
- 对**不连续成分**选择采纳哪一侧，或将冲突**保留为待决（PENDING）**；
- 修订 token 对齐基础（加 / 删谱系链接）。

一次「合并」会产生一个**不可变的新版本 + 完整决策日志 + 逐条 provenance**，
**来源版本永不被覆盖**；新版本里的每一个 token、弧、成分都能反查它来自哪个版本、
经由哪一次人工决定（或为自动一致采纳）。

技术栈：**Kotlin + Ktor（Netty）+ SQLite + 原生 Web UI**，Maven 构建，无前端构建链。

---

## 运行

前置：JDK 17+、Maven 3.8+（在 JDK 24/26 上已验证可编译运行）。

安装（构建，跳过测试）：

```bash
mvn -q -DskipTests package
```

演示（跑自动化测试，再启动本地服务）：

```bash
mvn -q test && mvn -q exec:java -Dexec.mainClass=app.MainKt -Dexec.args='--port 5508'
```

然后访问 <http://127.0.0.1:5508> ，页面标题为 **「句轨裁决所」**。

可选参数：

- `--port 5508`：监听端口，默认 5508，仅绑定 127.0.0.1；
- `--db data/arbitration.db`：SQLite 文件路径（默认相对当前目录，自动建目录与建表）。

首次启动会自动写入固定 fixture；之后数据持久化在该 SQLite 文件里。

---

## 数据口径（固定三版短语料）

语料是一句**人造诱发句**（刻意覆盖所有需要裁决的现象，不主张它是唯一正确的语言分析；
其中非常规挂弧已在代码 / 本 README 中显式标注为“诱发”）：

```
这些 书 ， 张三 在 上海 市 读 不 读 老 书 ？
```

| 版本 | 粒度处理 | 特殊点 |
|---|---|---|
| `s1-v1` 粗分 | 把 A-not-A 的「读 + 不读」**合为一个 token**「读不读」 | 含交叉依存弧、不连续 VP |
| `s1-v2` 细分 | 「读不读」**拆成**「读 / 不 / 读」 | 交叉弧、三段不连续 VP |
| `s1-v3` 合词 + 空节点 | 「上海 + 市」**合为**「上海市」；宾语位置补一个**空节点** `∅ pro` | 空节点、交叉弧；「在」的词类（ADP/SCONJ）与格标签（case/mark）与 v2 故意冲突；「老」lemma 标注为「旧」制造属性差异 |

预置两个基线会话：`sess-v1-v2`（拆词）与 `sess-v2-v3`（合词 + 空节点）。

### 现象如何被表达

- **拆词 / 合词 / 空节点**：谱系是 token 间的多对多边（`corr_link`）。并查集把
  互相连通的 token 聚成「对齐组」。`读不读 ↔ 读+不+读` 是 1↔3 组；`上海+市 ↔ 上海市`
  是 2↔1 组。v3 的空节点 `∅ pro` **故意不连任何谱系边**，始终作为“未对应”保留，
  页面与接口都明确显示这种**不确定对应**。
- **交叉依存弧（非投射）**：三个版本都含至少一对真交叉弧，例如话题「书」的弧
  与主语「张三」的弧在位置区间上交叉。交叉**不是错误**：校验器只产出
  `CROSSING_INFO`（INFO 级），页面照常画弧。
- **不连续成分**：成分由**若干有序区段**（`constituent_part`）组成，区段多于一个即
  不连续。每个区段自身必须连续，但**区段之间保留真实缺口**。系统**绝不**把“最小位置
  到最大位置”粗暴填满——见下方校验 `GAP_FILLED`。

---

## 四项硬校验（不允许自动掩盖）

对任何版本（人工版 / 合并版）运行 `Validator`，结果随版本接口与页面一并展示：

1. **词序 `WORD_ORDER`**：`ord` 唯一且严格递增；空节点也占有独立逻辑位。
2. **唯一句法头 `MULTI_HEAD`**：每个 token 至多被一条非根弧作为从属；弧引用必须存在。
3. **无环 `CYCLE`**：着色 DFS 检测依存环。
4. **单根 `BROKEN_ROOT`**：恰好一条 `root` 弧，且每个 token 沿头链可达该根。
   缺根 / 多根 / 存在孤岛都报错。**系统不会自动补一个根来“修好”断裂结构。**
5. **不连续成分不得填满 `GAP_FILLED`**：区段内部不得有缺口却被声明为连续；
   多区段成分的声明集合不得等于整个 `min..max`（那等于抹平缺口）。

另有 `CROSSING_INFO`（交叉弧数量）仅为提示，不是 ERROR。

合并时若某组粒度 / 弧仍待决，相关结构**不会被偷偷悬挂或改挂**，而是进入合并报告的
`unresolved` 列表，并通常导致合并版出现 `BROKEN_ROOT` 等硬错误——错误被**显式呈现**，
不掩盖。

---

## 裁决与合并语义

- 每个对齐组派生待决项：
  - `GRANULARITY`：组内 token 数不是 1↔1（拆 / 合）。
  - `ATTR`：1↔1 但 `text / lemma / pos` 有差异。
  - 依存弧按 `(头组, 从属组)` 聚合成 `EDGE` 项，左右各自带来源弧与关系标签。
  - 成分按“组序列 + 标签”匹配成 `CONSTITUENT` 项（含仅一侧存在的成分）。
- 决定是**只追加（append-only）**的：`decision` 表带单调 `seq`，永不 UPDATE / DELETE。
  同一对象上后来的相反决定会生效，但**旧决定仍完整保留**，并被标记为
  **重放冲突（replay conflict）**，要求人工重放，而不是被最后一次保存悄悄覆盖。
- 合并粒度：
  - `USE_A / KEEP_A`（或 `USE_B / KEEP_B`）：按所选侧的 token **逐个**产出新 token；
    对侧成员映射到该组首个新 token，词内弧（如「不」的 `advmod`）按所选侧重建。
  - `CONFIRM`：确认现状对应（1↔1 时等价确认；多对多时按左側表层连写产出一个 token）。
  - `PENDING / REJECT`：该组不产出 token，相关弧与成分进入 `unresolved`。
- 仅一侧出现的**非空** token 不会被自动并入新版本（那等于替操作者做了合 / 弃决定），
  它进入 `unresolved`；**空节点例外**，默认保留以延续空占位分析。
- 不同源弧塌缩到同一 `(头, 从属, 关系)` 时去重，但 provenance 保留全部来源。

### 并发审阅与“对齐基础变更”

- 每个会话有单调的 `basisRevision`（basis r0、r1……）。每条决定记录其做出时的 basis。
- 加 / 删谱系链接会 `basisRevision + 1`。接口要求请求带 `expectedBasis`：
  - 若与当前不符，返回 **HTTP 409 `BASIS_CHANGED`**，拒绝在过期基础上保存或合并。
- 读取会话时，引擎把所有在旧 basis 上做出、而**对象签名已消失 / 改变**的旧决定
  显式列为 **重放冲突**（含操作者、原选择、原因、前后 basis），日志依旧完整可查。

### 验收场景（与自动化测试 `ServiceIntegrationTest` 一致）

1. 甲、乙从**同一基线 r0**出发，对拆词组分别选 `USE_A` 与 `USE_B`
   → 甲的旧决定立即进入“相反决定”重放冲突，两条决定都在日志里。
2. 乙拆除该组的谱系链接（basis r0→r3）→ 甲、乙关于该组的旧决定都进入
   “对齐基础已修订、对象已消失”的重放冲突。
3. 甲仍持 `expectedBasis=0` 再决定 / 合并 → **409**，不会被最后一次保存覆盖。
4. 在干净基线上对拆词组选 `USE_B` 后合并 → 新版本正确出现两个「读」+「不」，
   且每个新 token 的 provenance 都指向 v2 源 token、记录经由决定 #1。

---

## 页面说明

- 顶部选择句子 / 会话；显示当前 `basis rN` 与两侧版本校验徽标。
- 左右两栏分别绘制版本 A / B：token（空节点虚线红框）、SVG 依存弧、成分区段
  （多区段之间以 `…` 标示真实缺口）、校验结果。
- 「待决项」面板对每个对象提供：按 A / 按 B / 确认 / 保留待决，并显示当前生效决定。
- 「修订对齐基础」进入编辑模式：点选两侧 token 建立链接，或在链接列表里删链；
  任何改动都会推进 basis。
- 「执行合并」生成新版本；顶部「合并产物 provenance 反查」可展开查看每个新
  token / 弧 / 成分的来源版本、源对象、所选侧与经由的人工决定序号。
- 顶部可**导出运行记录**（JSON）、**导入复核**、**清库重灌 fixture**。

---

## 运行记录导出 / 清空 / 重新导入复核

- `GET /api/export` 下载完整快照：句子、所有版本（含合并版）、会话、谱系链接、
  **全部决定**、合并运行（含 `unresolved` 与 token/弧/成分 provenance）、系统事件日志。
- `POST /api/import` 接收该 JSON：**先清空全部表**，再原样恢复，随后可重新浏览、
  继续裁决或再次合并，用于离线复核。
- `POST /api/reset`：清空数据库并重新写入固定 fixture。

典型复核流程：

```bash
curl -s localhost:5508/api/export -o run.json
# 在页面点“清库重灌 fixture”，或直接：
curl -s -X POST localhost:5508/api/reset
# 再导入之前的运行记录：
curl -s -X POST localhost:5508/api/import --data-binary @run.json
```

SQLite 数据库本身也可直接删除文件后重启（等价于空库，会自动重新建表 + 灌 fixture）。

---

## HTTP 接口一览

| 方法 | 路径 | 作用 |
|---|---|---|
| GET | `/` | 操作页面 |
| GET | `/api/state` | 句子与会话列表 |
| GET | `/api/session/{id}` | 会话完整裁决状态（组、待决项、生效决定、重放冲突） |
| GET | `/api/version/{id}` | 单个版本分析 + 校验 |
| POST | `/api/session/{id}/decide` | 追加一条决定（body: operatorId/ref/choice/expectedBasis） |
| POST | `/api/session/{id}/basis` | 修订对齐基础（ADD_LINK / REMOVE_LINK，expectedBasis 乐观锁） |
| POST | `/api/session/{id}/merge` | 执行合并，产生新版本 + provenance + 未决报告 |
| GET | `/api/session/{id}/logs` | 该会话系统事件日志 |
| GET | `/api/runs/{sessionId}` | 合并运行记录与未决项 |
| GET | `/api/provenance/{versionId}` | 合并版逐条来源反查 |
| GET | `/api/export` / POST `/api/import` / POST `/api/reset` | 导出 / 导入复核 / 清库重灌 |

冲突统一返回 JSON：`409 BASIS_CHANGED`、`404 NOT_FOUND`、`400 BAD_REQUEST`。

---

## 测试

```bash
mvn -q test
```

- `ValidatorTest`：单根 / 多頭 / 环 / 缺根不自动加根 / 词序 / 伪不连续成分被抓 / 交叉弧仅 INFO。
- `EngineTest`：拆词 1↔3 组、合词 2↔1 组、空节点保持无对应、属性与 case/mark 冲突、
  改基础后旧决定进入重放冲突、同基础上相反决定并存且旧决定进冲突。
- `MergerTest`：按侧粒度合并并保留 provenance、待决粒度导致弧落地失败且不自动加根、
  不连续成分跨合并不被填满、空节点随合并保留且其弧可落地。
- `ServiceIntegrationTest`：两位操作者相反决定 + 基础修订 → 409 与可解释重放冲突；
  导出 → 清空 → 重新导入后决定、合并版本与 provenance 均可复核。

## 代码布局

```
src/main/kotlin/app/
  Models.kt      领域模型（token/弧/成分/版本/对应/决定/provenance）
  Validator.kt   四项硬校验 + 交叉弧提示
  Engine.kt      token 谱系并查集对齐、待决项派生、决策重放冲突（纯函数）
  Merger.kt      合并产出新版本、provenance、未决项（纯函数）
  Fixture.kt     固定三版短语料与种子谱系
  Database.kt    SQLite 连接、建表、事务
  Repository.kt  查询（分析/会话/链接/决定/provenance）
  Writes.kt      写入（分析/会话/决定/合并落库/清空）
  Backup.kt      全量导出与清空后重导
  Service.kt     事务编排、乐观锁（409）、重放判定
  Server.kt      Ktor 路由与 provenance 接口
  Main.kt        命令行入口
src/main/resources/web/index.html  原生 Web UI（无构建步骤）
```
