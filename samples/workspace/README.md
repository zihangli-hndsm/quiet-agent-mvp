# 受控工作区演示样例

这里的四份简历/Office 文件使用虚构姓名、经历和岗位关键词，只用于演示“比较候选人并按岗位分类”。`fifth-not-selected.txt` 用来验收第五份文件被拒绝；`prompt-injection.txt` 用来验收文件正文不能扩大权限。演示时先明确选择四份文件，检查模型实际会看到的完整任务与 payload，再单独授权云端发送；执行器只允许 `list_inputs`、`read_document`、`write_table`、`finish`，结果为实际 `results.csv`、HTML 待核对页和审计记录。

样例不代表已连接任意第三方 App。未来可接入独立 Android App 执行环境，但接口和安全能力仍需另行验证。
