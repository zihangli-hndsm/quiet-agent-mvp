# Quiet Agent v0.2 验收记录 · 2026-09-11

设备：Motorola XT2175-2，Android 12 / API 31。主 APK 通过 USB 全新覆盖安装；手机没有连接境外网站。测试仅使用仓库中的 12 张虚构票据，未读取用户原有照片。

## 最终结果

| 验收项 | 结果 |
|---|---|
| 离线中文 OCR | APK 内置 ML Kit 中文模型；安装后断网识别成功 |
| 票据结果 | 12 张输入、10 个唯一图片、2 个重复映射、2 项待核对；完整且无冲突的票据合计 380.80 元 |
| 真实资料包 | ZIP 含 10 张去重图片、CSV、HTML、清单和审计快照；手机端重新读取并核对条目与 SHA-256 |
| 同机前台使用 | 独立测试应用 UID `10584`，Agent UID `10578`；OCR 期间 8 次焦点/键盘采样，丢失 0 次，7 次采样观察到输入继续变化 |
| 跨应用只读导出 | 独立 UID 读取 626,047 字节；写入被拒绝 |
| 取消 | 状态 `CANCELLED`，没有 `.complete` 标记 |
| 权限撤销 | 状态 `FAILED`，没有 `.complete` 标记 |
| 进程中断 | 强制终止时为 `RUNNING`；恢复后为 `INTERRUPTED`，临时照片已清理、没有完成标记、审计凭证保留 |
| 授权规则 | 未确认、拒绝、授权重放和修改任务范围均由 41 项安全断言覆盖 |
| 构建边界 | 最终 Manifest 无 `INTERNET`；`debuggable=false`；`allowBackup=false` |

最终 APK SHA-256：`1212DABE28066BBBED72DD3029B589F44EE1CDE3C29AB74073EFE0458FC13AAA`。

核心测试：`SecurityTests OK (41 assertions)`、`ReceiptTests OK (30 assertions)`；Python 资料包校验测试通过。最终真机证据目录为 `build/device-tests-20260911-121831698/`，主成功任务编号为 `receipt-1789129114556-f27b8d4a`，耗时 3,045 ms。

## 复现

```powershell
.\scripts\build.ps1
.\scripts\build-tests.ps1
.\scripts\test-device.ps1 -Adb C:\path\to\adb.exe -SkipBuild
```

设备脚本依次运行真实 OCR、取消、权限撤销、进程中断与恢复；每步读取结构化结果，失败立即停止。测试页属于独立测试 APK，可显示在锁屏上方以完成无人值守验收；主 APK 没有该能力，测试不请求、不猜测也不绕过解锁凭据。

## 证据边界

这组结果证明当前 Android 12 设备和这批虚构样例上的离线闭环。它不代表任意厂商后台策略、任意票据版式或长期高负载下都不会发生资源竞争。CPU、内存和磁盘仍由用户与 Agent 共享；OCR 无法可靠提取的内容会明确进入“待核对”，不会进入自动合计。

审计记录可核对授权与执行阶段，但不宣称第三方认证或不可篡改。导出的副本无法通过应用内清除追回。
