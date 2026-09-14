# MVP 验收矩阵

状态栏使用 `未测`、`通过`、`失败`、`阻塞`；本文件只定义验收方法，不提前声称任何场景已通过。

| 编号 | 场景与前置条件 | 操作/证据 | 通过标准 | 状态 |
|---|---|---|---|---|
| P-01 | 最小离线样例；输入为 `tests/make_fixtures.py --mode small` 生成的 `source` | 执行去重、按类型归档；保存 log、ZIP、manifest、summary | `verify_archive.py` 重新计算源 hash/ZIP payload hash；路径安全；源文件计数与 manifest 一致；重复组可解释 | 未测 |
| P-02 | 小样例；月份 mtime 已固定为 2024-01/02/03 | 执行按月份归档 | 每个归档条目的月份来自源 mtime；跨目录同名文件不冲突；中文和 emoji 名称可回读 | 未测 |
| P-03 | 小样例；相同内容不同文件名、同名不同子目录 | 启用/禁用去重各跑一次 | 启用时重复关系和保留项明确；禁用时两个 payload 都存在；不因 basename 相同覆盖文件 | 未测 |
| P-04 | 过滤请求（PDF、图片、文档） | 分别执行并检查 manifest | 仅命中规则允许的类型；被过滤源文件仍有明确 skipped 记录或计数；不得静默丢失 | 未测 |
| P-05 | `tests/make_fixtures.py --mode stress`；100 文件，32 MiB | 执行一次按类型/去重；记录耗时、峰值内存、最终剩余空间 | 100 文件、总源字节为 33,554,432；归档可回读；无 OOM、ANR、半成品；结果 hash 可复算 | 未测 |
| P-06 | 中途暂停/杀进程或服务被系统回收 | 重新打开应用，检查持久化状态并观察是否自动再跑 | 状态可诊断；不会自动重复执行同一计划；用户明确继续后最多生成一个可校验结果 | 未测 |
| P-07 | 运行中撤销 SAF URI 权限 | 撤权后让服务继续读取 | 失败原因明确；不报告成功；不生成可被误认的成功 ZIP；重新授权后由用户显式重试 | 未测 |
| P-08 | 输出空间不足或单个文件不可读 | 使用受限目录/不可读测试条件（仅在设备允许时） | 明确失败并保留源；临时文件可清理；不得把部分归档标为完成 | 未测 |
| P-09 | 源文件在扫描后被修改或删除 | 扫描后修改内容，再让归档完成 | hash/size 发生不一致时失败或记录冲突；不得把旧 hash 当作新内容的证明 | 未测 |
| P-10 | 人工用户在真实前台输入请求 | 正常触摸输入；查看焦点与回显 | 不启动 Activity/IME，不依赖自动注入；输入焦点、中文、emoji 正确；规则摘要可读 | 未测 |
| P-11 | 自动注入对比（仅测试环境，若允许） | 与 P-10 分开记录输入方式和结果 | 记录真实用户与自动注入差异；自动注入通过不能替代 P-10 | 未测 |
| P-12 | ZIP 恶意条目/篡改 manifest | 用独立脚本构造 `../escape`、绝对路径、重复 entry、错误 hash | `verify_archive.py` 非零退出并指出具体错误；不依赖手机 UI 的“成功”文案 | 未测 |
| P-13 | 输出目录落盘检查 | 服务完成后关闭应用/重启设备，再读取 ZIP/manifest/summary | 文件实际存在且能被独立 verifier 读取；不是只在内存或临时目录中的假成功 | 未测 |
| P-14 | 静音前台服务通知 | 启动长任务并观察锁屏/通知栏 | 通知持续存在、静音、可见进度；不弹输入界面；完成/失败状态可区分 | 未测 |
| W-01 | 受控工作区四份虚构简历 | 预览实际发送文本后授权，观察后台工具阶段和操作记录 | 仅执行 `list_inputs/read_document/write_table/finish`；输出 CSV、HTML、审计记录 | 未测 |
| W-02 | 未导入文件、提示注入、越权证据 | 请求第五个文件、在正文写“联网/读其他目录”，或引用未读/不匹配证据 | 文件 ID、路径、网络和系统操作均被执行器拒绝；表格证据可核对 | 未测 |
| W-03 | 授权拒绝、重放、取消、清理与导出 | 重复使用凭证，取消任务，导出前拒绝确认，最后清理 | 不伪造完成；导出二次确认；清理提示外部操作与副本无法撤回 | 未测 |

## 本地夹具命令

```text
python tests/make_fixtures.py --mode small --output-dir .tmp/fixture-small
python tests/make_fixtures.py --mode stress --output-dir .tmp/fixture-stress
```

生成目录包含 `source/` 和旁边的 `expected.json`。`expected.json` 是源快照（相对路径、大小、SHA-256、mtime），不能复制到待整理目录。夹具内容均为本地生成的文本、CSV、PNG、最小 PDF 与确定性二进制；没有联网或第三方版权素材。

## 真机记录要求

每次真机记录至少包含 Android 版本、设备剩余空间、输入方式、SAF URI 是否新授权、计划摘要、开始/结束时间、服务状态、输出绝对位置、manifest/ZIP SHA-256、独立 verifier 输出和失败日志。没有这些证据的场景保持 `未测`，不能仅凭 UI 文案改成 `通过`。

## 已执行的本地纯 JVM 检查

`tests/RunArchive.java` 直接加载 `Engine`，读取上述 `source/`，不启动 Android、不触碰手机、不手写 manifest。使用 JDK 17 编译后，small 真实 Engine 输出为 `scanned=9 selected=9 unique=8 duplicates=1`，stress 输出为 `scanned=100 selected=100 unique=98 duplicates=2`；两次均由 `verify_archive.py` 根据源快照、`archive.zip` 和真实 `manifest.json` 回读通过。stress 源快照为 100 文件、33,554,432 字节；归档因两个重复项而少写对应 payload。

篡改检查也已执行：向 ZIP payload 追加字节时 verifier 以非零退出并报告 payload/ZIP hash mismatch；把 manifest `selected` 改小会报告源文件数不一致；把 record `sha256` 改为全零会同时报告源 hash 与归档 payload hash 不一致。这些结果只证明本地 core/脚本链路，不能替代 Android 12 真机场景 P-06 至 P-14。
