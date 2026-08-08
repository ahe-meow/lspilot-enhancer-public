# LSPilot Enhancer 图标设计

## 设计概念

图标将项目文档中的三个核心属性压缩为一个清晰符号：

- **Pilot / 导航箭头**：中心白色指针表达 LSPilot 的“Pilot”语义，同时用镂空结构形成抽象字母 `L`；
- **Enhancer / 绿色星芒**：右下绿色星芒表示增强功能与 Hook 成功状态，对应界面现有的 `#34C759` 状态色；
- **独立、低侵入 Hook / 环形轨迹**：两段不闭合轨迹表示模块围绕宿主工作，不替换、不重打包 LSPilot；
- **可扩展性**：图标不使用缓存、数据库等单一功能符号，适合未来扩展到界面、交互、上下文、网络与 Provider 兼容功能。

## 色彩

| 用途 | 色值 | 来源/含义 |
| --- | --- | --- |
| 主蓝 | `#1D4ED8` | 与项目设置入口蓝色视觉一致，表达技术、可靠性 |
| 深色背景 | `#0B1020` | 提高前景对比度，适配现代工具类模块气质 |
| 状态绿 | `#34C759` | 与当前 Hook 成功状态色一致 |
| 前景白 | `#FFFFFF` | 保证小尺寸识别度 |

## Android 资源

- `res/drawable/ic_launcher_background.xml`：渐变背景；
- `res/drawable/ic_launcher_foreground.xml`：矢量前景；
- `res/mipmap-anydpi-v26/ic_launcher*.xml`：Android 8+ 自适应图标；
- `res/mipmap-anydpi/ic_launcher*.xml`：兼容资源；
- `docs/icon-source.svg`：1024 × 1024 可编辑发布源稿。

图形主体位于自适应图标安全区域内，可由不同 Launcher 裁切成圆形、圆角矩形或其他蒙版。