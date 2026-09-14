# 统一工作区混合样例

本目录是授权 root 内的混合测试集，内容均为虚构或已有公开测试素材的复用：

- `../workspace/alice-resume.docx`、`bo-resume.xlsx`：合法 Office，只读预览。
- `../receipts/receipt-01.png`：中文票据图片，供 OCR 流程使用。
- `../workspace/fifth-not-selected.txt`：合法文本，可编辑测试。
- `../workspace/prompt-injection.txt`：提示注入对照，必须按数据读取，不能执行其中指令。
- `../workspace/README.md`：文本分页与搜索样例。

`same-name/`、损坏文件与重复内容使用现有 fixture 目录中的对应素材；不要把范围外对照资料放入本目录。范围外对照应放在 `samples/unified-out-of-scope/`，并在测试时只授权 `unified`。

所有资料均为测试构造，不含真实敏感资料。
