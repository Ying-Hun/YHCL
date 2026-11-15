# iOS 26 Liquid Glass 液态玻璃面板实现计划

## Context

用户要求把启动器的模块卡片（.card）与左侧导航栏（.rail）升级为 Apple iOS 26 Liquid Glass 质感：半透明折射玻璃、柔和边缘高光、边缘细微色散、悬浮感、自适应色调——明确"不是简单玻璃morphism"。

用户已确认三项取舍：
- **范围**：仅卡片+导航栏；按钮/输入框保留青绿描边风格不动
- **边缘光效**：接受设计高光（内部严格均匀，仅最外圈 1-2px 有意做柔和亮边+冷暖彩边）
- **强度**：克制档（轻磨砂、折射带窄而浅、更有光泽）

历史教训（必须规避）：旧液态玻璃管线（逐卡片快照 PNG + localToScene 监听逐帧 setStyle + 500ms ticker）造成 ①边缘白边 ②切页掉帧，已整体拆除。新实现**全部静态分层、按状态一次性 setStyle、零逐帧更新**。

## 核心方案：单一共享模糊背景图 + 统一多层背景栈

每个玻璃节点的背景由 `applyGlassStyle()` 一次性生成完整 `-fx-background-*` 层栈（Java 端 setStyle 统一生成，层序完全可控）；darkslate.css 的 .card/.rail 只保留 padding/effect/圆角/border 0 等非 background 属性。

### 分层（自顶向下，实现时先用小样确认 JavaFX 层序方向——W3C 风格 first-on-top）

| # | 层 | 内容 | insets | 作用 |
|---|---|---|---|---|
| 1 | 主高光 | `linear-gradient(to bottom right, rgba(255,255,255,0.32), rgba(255,255,255,0) 16%)` | 0 | 左上柔光 |
| 2 | 反弹光 | `linear-gradient(to top left, rgba(255,255,255,0.13), rgba(255,255,255,0) 14%)` | 0 | 右下反弹 |
| 3 | 暖色散环 | `rgba(255,190,150,0.13)` | 1.2 | 色散暖边 |
| 4 | 冷色散环 | `rgba(150,222,255,0.16)` | 0 | 色散冷边（外圈 0-1.2px） |
| 5 | tint | `rgba(239,241,244,α)` | 2.2 | 面板本色，内部严格均匀 |
| 6 | 模糊背景图 | 共享模糊图，`background-size: 窗口×1.03`（轻微放大=全局微折射） | 0 | 真磨砂透出身后背景 |

- α 自适应：有背景图 0.55、无背景图 0.75；bgAvgColor 偏暗时 +0.08 提亮（复用现有 avgColor）
- 全层 `-fx-background-radius` 与面板圆角一致（18/26）；dropshadow 保留（悬浮感）
- **内部均匀性**：2.2px 往内只有 tint 层（+均匀模糊图），颜色/alpha 严格一致；0-2.2px 是有意的光效带（用户已接受）
- 无自定义背景时：省略层 6，tint 0.75 → 现有外观 + 光效/色散升级

### 共享模糊背景图管线（一次性、内存缓存、不落盘）

- `regenGlassBackdrop()`：`bgWrap.snapshot()`（含背景图+蒙版，正好是面板身后内容）→ 用 GraphicsContext 缩放到 0.5 分辨率 → `ImageView.setEffect(GaussianBlur(14))` 再 snapshot → 得到内存 `Image` 字段
- 每个玻璃节点的层 6 用 `background-position: -sceneX×1.03补正 -sceneY×1.03补正` 对准自身身后的区域（scene 坐标用 `node.localToScene(0,0)`，一次性计算）
- resize/背景切换时重建；内存即缓存（旧管线落盘 PNG 无必要）

## 修改文件

### 1. `src/main/java/com/deepslate/yhcl/App.java`

新增（集中一个方法组，约 100 行）：
- 字段：`Image glassBackdrop`、`Timeline glassResizeDebounce`（字段防 GC）
- `regenGlassBackdrop()`：如上，无自定义背景置 null
- `applyGlassStyle()`：遍历 root 收集 styleClass 含 `card`/`rail` 的节点（可复用 walkAdaptiveText 的遍历模式），逐个生成层栈字符串 setStyle；层 6 仅当 glassBackdrop 非 null 且节点不在 ScrollPane 内（见风险）时挂
- `scheduleGlassRefresh()`：resize 防抖 300ms 后 regen + apply

接入点（全部一次性调用，无逐帧）：
- `applyCustomBg()` 末尾（[App.java L640](file:///c:/Users/32934/IdeaProjects/YHCL/src/main/java/com/deepslate/yhcl/App.java#L640) 附近）→ regen + apply
- `animateIn()` 的 `pt.setOnFinished`（[App.java L1010-1044](file:///c:/Users/32934/IdeaProjects/YHCL/src/main/java/com/deepslate/yhcl/App.java#L1010-L1044)）→ `resetNode` 后 apply（校正动画期间的 12px 位移）
- stage width/height 监听 → `scheduleGlassRefresh()`
- 启动布局完成后一次（width 监听首次触发天然覆盖）

**禁止**：localToScene 监听、周期 ticker、动画期间任何 setStyle。

### 2. `src/main/resources/darkslate.css`

- `.card`/`.rail`：删除 `-fx-background-color: rgba(239,241,244,0.75)`（background 全部改由 Java 端生成），保留 padding/圆角/border 0/dropshadow；**保留一份纯 CSS 的 tint 兜底**（Java 未及调用时不至于透明）
- `.custom-bg .shell/.panel` 覆盖块不动

## 光效参数（克制档）

- 折射：模糊图整体放大 1.03 + 边缘色散环，无独立宽透镜带
- 磨砂：0.5 分辨率 + GaussianBlur 14（≈28 全分辨率等效），柔和
- 高光：主 0.32 / 反弹 0.13，快速衰减（14-16% 处归零）——多像素柔和渐变，**绝无 1px 硬边**
- 色散：冷 0.16 / 暖 0.13，仅 0-2.2px 环带

## 风险与规避

1. **白边回潮**：色散环虽是边缘亮/彩带，但是四周对称、柔和、有意的设计（与旧 bug 的"仅顶/左硬白边"不同）。交付前必须放大截图人工确认观感
2. **JavaFX 层序方向**：background 多层的叠放方向需先做小样确认（一个 10 行测试样式），再定列表顺序
3. **滚动容器内卡片**（版本选择页 ScrollPane 挂 .card，App.java L790-834）：滚动时 scene 坐标变化，层 6 会轻微错位。模糊图低频平缓，错位不可见；实现时若截图发现异常，降级为"ScrollPane 内卡片跳过层 6"
4. **性能**：全部静态 CSS 层 + 一次性快照；动画期间零 setStyle；resize 防抖——不得回归切页掉帧
5. **setStyle 覆盖 CSS**：background 相关属性统一由 Java 生成，CSS 不再定义 background-color，避免内联/样式表打架

## 验证流程

1. `target\probe\build.ps1` 编译打包
2. 拷 jar 到 `C:\Users\32934\Desktop\YHCL\app\yhcl.jar` → `launch-test.ps1` → `shot.ps1` 截图
3. 像素校验（System.Drawing 临时脚本，放 %TEMP%）：面板内部 tint 严格均匀（= α×(239,241,244)+(1-α)×背景）；外圈 0-2px 出现冷/暖环；无单侧硬白边；放大 4x 人工确认光效观感
4. 无背景图模式（恢复默认背景）截图确认光效正常
5. 快速切页流畅度不回退（既有动画缓存机制保留）
6. 打包交付：Edit `target\sfx\stub.cs` BuildStamp → `"2026-10-24-22"` → csc（`/resource:payload.zip /r:System.IO.Compression.dll /r:System.IO.Compression.FileSystem.dll`）→ PatchIcon → 拷 `C:\Users\32934\Desktop\YHCL.exe`，提醒先关旧实例
7. 更新项目记忆（液态玻璃分层方案 + 层序验证结论）
