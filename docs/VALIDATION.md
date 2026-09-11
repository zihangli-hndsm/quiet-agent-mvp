# MVP 验收记录 · 2026-09-11

设备：Motorola edge S30 / XT2175-2，Android 12 / API 31。所有手机任务走 USB 与本地存储；未安装 VPN、未打开外部网站。APK 仅声明前台服务和唤醒锁权限，没有 INTERNET。测试内容为明确生成的样例，没有读取用户原有文件内容。

## 已完成

| 验收项 | 证据与结果 |
|---|---|
| 核心规则、去重、内容变化拒绝、取消、数量限制 | `CoreTests OK (30 assertions)` |
| JVM 实际归档 + 独立 Python 校验 | small：9 选中 / 8 写入 / 1 跳过；stress：100 / 98 / 2，32 MiB；均 PASS |
| 同应用输入并发 | 最终包：23 次焦点采样，焦点丢失 0、输入不可用 0、键盘隐藏 0；运行中 18 次输入变化 |
| **独立前台应用并发** | 前台 UID 10055，Agent UID 10047；16 次采样，焦点/键盘丢失 0；运行中 15 次输入变化，48 MiB 样例完成 |
| 真实结果 | 30 个选中文件、29 个 ZIP 条目、1 个重复跳过；手机逐项回读，电脑另行核对原件 SHA-256、ZIP payload、路径、计数、重复映射，PASS |
| 取消 | `CANCELLED`，未生成 `.complete`；未宣称取消竞速为成功 |
| 系统目录授权与真实按钮路径 | 通过系统选择器只读授权新建样例目录，点击预览/开始；2 个文件写入 1 个、跳过 1 个，报告页面实际打开；电脑独立校验 PASS |
| 导出 | 独立 UID 读取 50,351,635 字节，SHA-256 与任务一致；写入被拒绝 |
| 通知 | 系统记录 importance=2、sound=null、vibration=false；服务没有调用启动 Activity 或键盘的代码 |
| 安装与渲染 | 最终 APK 已安装；主页面和真实报告已截图检查，长哈希可换行，表格可横向滚动 |

最终 APK SHA-256：`A49B8490A9707EF16ACFA0894E36EF3DCD1900037E934322E6E9DC9F6DE99E9C`。

机器可读结果见 [evidence](../evidence/)。最终独立前台任务：`job-1789094059320-04a5c1cd`，ZIP SHA-256：`82b7225ca1d51aecb4bc874c8b086b107332c915ab9ce0b8f8b814055727a90e`。实际页面任务：`job-1789094824908-bdfb24b2`。报告截图见 [report.png](../evidence/report.png)。

## 可复现

设置 README 中的 JDK / Android SDK 路径后：

```powershell
pwsh scripts/test-integration.ps1
pwsh scripts/test-device.ps1 -Adb C:\path\to\adb.exe
```

设备脚本会安装主 APK 和测试 APK，顺序执行 smoke、cancel、external、export。每一步读取结构化结果并在失败时中止；结果保存在 `build/device-tests-*/`。测试需要已授权 USB 调试。`external` 使用单独测试应用，不安装第三方输入服务或改变默认键盘。

`ui` 是本机额外验收：仅当系统选择器已授权指定的 `Documents/QuietAgentMVP-SAF-20260911` 样例目录时才运行；默认测试脚本不自动请求用户私人目录权限。`collect_device.py` 只采集本应用样例目录或上述固定 SAF 样例目录和指定 job。

## 限制与失败记录

最初两次输入检测失败：第一版未等待 IME 就绪，第二版在异步输入回显前采样。未当作通过；修复后使用跨采样长度变化，最终结果如上。此前一次 smoke 日志曾重复统计同一输入，最终脚本已移除重复计数。旧记录不用于最终统计。

测试时系统报告 `deviceSecure=true`、`keyguardLocked=false`。测试 APK 可以将自己的测试页显示在锁屏上方，但未调用解锁凭据、未修改安全锁；主 APK 不使用此能力。没有让用户参与这一轮测试。

上述是自动注入输入的短时真机证据，不是“任意 App、任意负载、长期零卡顿”的证明；未测真人长期使用、强杀恢复、磁盘耗尽、权限撤销、全厂商保活。CPU/磁盘仍共享。MVP 使用有限关键词规划、只读文件能力，不含 LLM，也不提供通用 GUI 操作。调试包与商店发布版的边界见 [架构](ARCHITECTURE.md)。
