# ClaudeCode 护栏规则
1. **禁止使用 Docker**：所有环境必须在 Windows 11 本地直接运行。
2. **禁止引入前端构建工具**：不使用 Node.js/Webpack/Vue/React。前端第三方库必须下载 js/css 文件放入 `src/main/resources/static/lib/` 目录。
3. **框架边界**：
   - 数据切分、向量化、入库、检索必须使用 LangChain4j 的 API。
   - 最终与 LLM 交互生成回复时，必须使用 Spring AI 的 ChatClient。
   - 严禁混用两套框架的同功能组件（如不要用 Spring AI 的 VectorStore，只用 LangChain4j 的 EmbeddingStore）。
4. **代码切分**：Java 代码必须使用 JavaParser 按 AST 切分；严禁简单按 Token 硬截断代码逻辑块。
5. **安全与部署**：无登录鉴权，仅供本地单用户使用。
6. 需要下载的第三方工具放在D:\develop。
