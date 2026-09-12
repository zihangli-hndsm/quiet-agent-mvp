# 只读短信整理验收 · 2026-09-12

设备：Android 12，ZY22F65J2Z。安装包 SHA-256：`0A70A44BB1A5F636D031BB9A05ABE590515ED534502F147A0EF96DDB1B18AF42`。

入口：主页工作方式 → 短信整理。选择最近1–365天、可选精确发送方号码，输入提取要求。确认本地读取并授予 READ_SMS 后，展示最新30条收件短信；选择条目并确认实际云端载荷后，后台生成字段表格与垃圾短信建议。结果可按疑似垃圾筛选、回看来源、导出 CSV、打开原短信应用处理和清除本地数据。

真机 `sms` 场景通过：
- 原默认短信应用为 `com.android.messaging`，测试前后相同；无发送/写入/删除短信权限和代码。
- 未授予 READ_SMS 时实际读取被拒绝。测试临时采用 shell 的读取权限查询随机不存在的发送方，返回为空，随后释放临时权限；未读取用户真实短信内容。
- 虚构面试短信提取出公司、时间、地点；虚构促销短信标为疑似垃圾，物流通知保留。验证码短信不进入载荷。真实 DeepSeek 请求仅含虚构样例。
- 未授权、篡改载荷、授权重放、取消不发布结果；验证表格字段必须在原短信中逐字出现，未知来源编号被拒绝，CSV 单元格防公式执行。
- 实际 SmsAnalysisService 完成3条分析并生成 CSV；结果提供方允许 r，拒绝 rw；处理中输入文件在成功后清理。
- 原短信应用按钮实际启动 ConversationListActivity。息屏显示占据可见窗口时，测试通过系统 mFocusedApp 核实目标 Activity；这不代表已删除或处理任何短信。
- 核心35、安全41、票据36项现有断言通过，Demo 构建通过。

边界：只处理 SMS 收件箱，不含彩信、RCS、其他聊天应用或发件箱。验证码检测为关键词规则，不保证识别所有秘密；上传前必须检查实际载荷。其他所选正文在明确确认后发送到模型，不做隐含的全面脱敏承诺。模型输出为待核对建议，无自动删除、发送或默认短信角色切换；会话深链接不受目标短信应用支持时回退到应用首页。只读导出确认不代表接收方已经收到。测试没有逐一验证所有 OEM 短信客户端或真实收件业务。

运行：构建并安装主 APK 与测试 APK 后执行 `adb shell am instrument -w -e scenario sms app.quietagent.test/app.quietagent.test.QuietInstrumentation`。测试会调用模型接口，样例文本在测试代码中明确标为虚构。

平台参考：[默认短信应用查询及可见性](https://developer.android.com/reference/android/provider/Telephony.Sms)、[短信提供方写入限制](https://developer.android.com/reference/android/provider/Telephony)。
