# 受控工作区构建状态

最后一次 host 构建使用 `scripts/build.ps1`，结果为 `BUILD SUCCESSFUL`。

APK：`build/quiet-agent-demo.apk`  
SHA-256：`27AA97C9152528D4975D68666F5E97FA0021D04D1AB5EC3F206D6A8D23229376`

纯 JVM 边界检查：`scripts/test-workspace.ps1` → `WorkspaceTests OK`。

未执行真机安装、真机自动化或真实 LLM 连通测试。`OfficeText` 的 Android host 解析未单独执行；样例仅覆盖受控 OOXML 输入结构。手动验收见 [`WORKSPACE-MANUAL-TEST.md`](WORKSPACE-MANUAL-TEST.md)。
