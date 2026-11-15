# 设置页自定义背景图功能

## Context

用户想在设置界面加「自定义背景图」：选一张图替换启动器背景（卡片/模块外的背景层）。已确认视觉效果为**磨砂隐约透出**（保留 .shell 半透明磨砂，图片在底层隐约可见），并带三个附加功能：缩略图预览、暗色蒙版透明度调节、恢复默认按钮。

## 改动文件

1. [App.java](c:\Users\32934\IdeaProjects\YHCL\src\main\java\com\deepslate\yhcl\App.java)
2. [darkslate.css](c:\Users\32934\IdeaProjects\YHCL\src\main\resources\darkslate.css)

## 实现

### 1. 背景图层（App.java L375-383 root 组装区）

```java
Pane bgLayer = new Pane();  bgLayer.getStyleClass().add("bg-image");   // 背景图，CSS cover 自适配缩放
Pane bgDim   = new Pane();  bgDim.getStyleClass().add("bg-dim");       // 暗色蒙版
StackPane root = new StackPane(bgLayer, bgDim, blobs, shell, downloadBall);
```

互斥逻辑：设了自定义图 → `blobs.setVisible(false)`、bgLayer/bgDim 可见；恢复默认反之。blobs 的 clip 绑定（L386-393）不动。

### 2. applyCustomBg() 新方法（对齐 refreshSkinPreview L508 模式）

- 路径非空且 `Files.exists` → 同步 `new Image(uri, false)` 探测 `!isError()` → 成功则 `bgLayer.setStyle("-fx-background-image: url('" + Path.of(customBgFile).toUri() + "');")`（用 toUri() 转义空格/中文，勿手拼）
- 失败（格式不支持/文件被删）→ 回退 blobs、清空 customBgFile、提示「背景图无法加载，已恢复默认」
- `bgDim.setOpacity(customBgDim)`，调 `refreshBgPreview()` 更新缩略图

### 3. 设置卡片（skinCard 之后插入，panel 组装 L2743）

bgCard 结构（.card 样式）：
- 标题「自定义背景图」+ 说明文字
- `HBox(bgThumb, VBox(按钮行, bgNote))`：bgThumb = ImageView 120×68 preserveRatio；按钮「选择背景图…」（onChooseBg）+「恢复默认」（onResetBg）
- 「蒙版」Slider(0–80，默认 35) + 百分比 Label：拖动时 `bgDim.setOpacity(v/100)` 实时预览；`!isValueChanging()` 时才持久化（防频繁写盘）
- onChooseBg 复用 onImportSkin L480 模式：FileChooser 过滤 `*.png *.jpg *.jpeg *.bmp` → `new Image` 校验 isError → 复制到 `Launcher.appDir()/bg/custom-bg.<原扩展名>`（REPLACE_EXISTING）→ 赋值 customBgFile → `applyCustomBg(); saveConfig()`，异常走 alert()

### 4. 持久化（Config L4565 区）

```java
public String customBgFile = "";   // 背景图路径（YHCL/bg/）
public double customBgDim = 0.35;  // 蒙版不透明度 0~0.8
```

- 字段赋值放 customSkinFile 旁（L171/L4649 模式）
- loadConfig 同步赋值后 `Platform.runLater(this::applyCustomBg)`（对齐 L4671 refreshSkinPreview 时机）
- saveConfig 序列化两字段（L4728 旁）
- 恢复默认按钮：清 customBgFile、customBgDim=0.35、Slider 归位、`applyCustomBg(); saveConfig()`

### 5. CSS 新增（darkslate.css，.shell 等现有样式不动）

```css
.bg-image { -fx-background-size: cover; -fx-background-position: center; }
.bg-dim   { -fx-background-color: #000; }
```

## 边界

- 图片经 shell 磨砂渐变隐约透出（用户选的效果），蒙版上限 0.8 防压暗整窗
- cover 是 CSS 逻辑坐标，150% DPI/窗口缩放无问题
- 全链路 try-catch 走 alert()（同 onImportSkin）

## 打包部署（手动管线）

javac 全量编译 → pack.ps1 → stub.cs BuildStamp 改 `"2026-10-24-03"` → csc → PatchIcon → 拷桌面

## 验证

1. 冒烟：桌面 exe 启动，读 `C:\Users\32934\Desktop\YHCL\build.stamp` = 2026-10-24-03
2. 窗口自动化截图：设置页出现「自定义背景图」卡片（缩略图占位 + 两个按钮 + 蒙版 Slider）；因 FileChooser 无法自动化，需生成测试图手动验证——或用 PowerShell 直接把测试图放到 `YHCL\bg\custom-bg.png` 并写 config.json 的 customBgFile 字段后重启验证背景生效、蒙版 Slider 生效、恢复默认生效
3. 回归：无背景图时界面与之前一致（blobs 装饰正常）
