# Quiet Agent MVP 架构与边界

## 目标

这是一个 Android 12 目标环境上的本地文件助手。用户在前台正常使用手机，输入请求并先预览有限规则解析结果；开始后应用在后台前台服务中读取源文件，通过静音持续通知显示进度。源目录只能是用户授予读取权限的 SAF tree，或应用创建并标明为样例的私有目录。归档结果固定写入应用私有 `filesDir/jobs/<job-id>/`：`archive.zip`、`manifest.json`、`summary.html`。应用内的 Engine 会回读 ZIP 条目；`verify_archive.py` 是应用外的独立验收工具。

主流程是：

```text
用户请求
  -> 本地 Plan.parse（有限关键词规则，不调用网络/LLM）
  -> SAF tree URI（只读）或 app-private 样例目录
  -> 前台服务 + 静音 ongoing notification
  -> 快照源文件（相对路径、size、mtime、SHA-256）
  -> 读取/计算 hash，按计划去重和分组
  -> ZIP + manifest.json + summary.html
  -> Engine 回读 ZIP 并核对内容 hash
  -> 外部 verify_archive.py 对源快照、manifest 和 ZIP 再复算
```

## 能力与边界

`Plan` 只识别有限关键词：去重或保留重复、按类型/月份分组、PDF/图片/文档/音频/视频过滤、最近 N 天，以及明确的非破坏性措辞。它不是自然语言理解器；模糊或不支持的请求可能无法解析，用户必须查看预览后再开始。删除、移动、重命名、上传、发送、分享等操作不属于 Engine 能力。此 MVP 不是通用 GUI 自动化器，不模拟点击，不启动 Activity/IME 输入，也不把 LLM 当作文件操作权限或执行器。

SAF 目录只通过 `ContentResolver.openInputStream` 读取；应用不向源目录写入、删除或移动文件。应用私有样例目录也只作为输入，归档仍写入独立的 job 目录。Engine 先写 `.part` 文件，回读 ZIP 后再原子放置三个结果；TaskService 再写 `.complete` 标记，只有完整结果才由 ShareProvider 暴露。撤销 URI 权限、源文件改变、文件不可读、磁盘空间不足、服务被杀、前台通知受限等情况需要真机记录；服务不会在 `onStartCommand(null)` 时自动重跑上次任务。

## 当前 manifest 契约

当前 core 产物使用 `scanned`、`selected`、`unique`、`duplicates`、`archiveSha256` 与 `files[]`；验证器也兼容下列 snake_case 形式。`unique` 表示实际写入 ZIP 的条目数，启用去重时小于或等于 `selected`；`duplicates` 表示因 hash 去重而跳过的 selected 文件数。每个 selected 源文件一条 `files[]` 记录，包含 `included` 和重复映射：

```json
{
  "schema":"quiet-agent-manifest-v1",
  "plan":"已按本地规则处理：去重；按类型；类型=全部",
  "scanned":9,
  "selected":9,
  "unique":8,
  "duplicates":1,
  "archiveSha256":"<64 lowercase hex>",
  "files": [
    {
      "id":"docs/duplicate-a.txt",
      "sourcePath": "docs/duplicate-a.txt",
      "archivePath": "document/duplicate-a.txt",
      "size": 35,
      "sha256": "<64 lowercase hex>",
      "included":true,
      "duplicateOf":null
    },
    {
      "id":"copies/duplicate-b.txt",
      "sourcePath":"copies/duplicate-b.txt",
      "archivePath":null,
      "size":35,
      "sha256":"<same hash>",
      "included":false,
      "duplicateOf":"docs/duplicate-a.txt"
    }
  ]
}
```

`sourcePath` 必须是源目录内的 POSIX 相对路径；有 `included=true` 的 `archivePath` 必须是 ZIP 内的相对路径。当前 Engine 为每个 selected 源文件写一条 record；去重后不写入 ZIP 的 record 使用 `included=false` 和 `duplicateOf`，并可能保留指向保留项的 `archivePath` 供展示。验证时 excluded record 不计为第二个 ZIP entry。ZIP payload 条目必须与 included records 一一对应。每个 record 的 `sha256` 是源与 included payload 的内容 hash；顶层 `archiveSha256` 是整个 `archive.zip` 文件 hash。

## 数据安全与恢复

读取阶段不改变源文件；Engine 的文件写入只发生在应用私有 job 目录。ZIP entry 使用相对路径并经过去重命名；Engine 回读时核对 entry 内容 hash。ShareProvider 只允许 `archive.zip`、`manifest.json`、`summary.html`，且要求 `.complete`、manifest、summary 和目标文件同时存在；ReportActivity 关闭 JavaScript、文件/内容访问和网络加载。应用 manifest 未声明 `INTERNET`，代码中没有 HTTP 客户端；SAF provider 本身是否触发外部 provider 行为仍需设备实测。Store 用 AtomicFile 保存状态，服务被杀后把遗留 `RUNNING` 标为 `INTERRUPTED`，不会自动重跑同一计划。

## 方案选择与验证边界

MVP 把整理任务表达为无需屏幕的文件能力，通过只读目录授权执行。屏幕、输入和焦点一直由前台应用持有；CPU 与存储仍然共享，通过后台线程优先级和大小限制控制负载。这个方向能完成真实文件工作，但不提供任意第三方 App 的 GUI 自动化。虚拟显示无法自动保证独立键盘和焦点，因此不作为首版执行通道。

完成标记已改为 `.complete.part` 写入、同步并关闭后再改名为 `.complete`，避免写标记失败留下完成状态。仅允许 `com.android.externalstorage.documents`；Downloads 和云端提供方不接受。APK 没有网络权限，报告 WebView 禁止网络加载。

本版是可调试侧载 MVP，保留 `debuggable=true` 供 USB 验收；调试签名不用于商店发布。唤醒锁最多持有 15 分钟；取消是协作式的，底层读取阻塞会延迟停止。撤权、磁盘耗尽、强杀进程与厂商长时保活未做完整故障注入。真实的界面授权、独立应用输入并发、取消、跨 UID 只读导出及归档独立回读已经执行，证据见 [验收记录](VALIDATION.md)。自动注入不能代替真人长期使用。
