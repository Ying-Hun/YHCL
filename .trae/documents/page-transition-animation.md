# 页面切换过渡动画（淡入 + 轻微上滑）

## Context

用户希望点开每个界面时都有动画过渡。方案：新页面 opacity 0→1、translateY +12px→0，220ms，Interpolator.EASE_OUT。只做入场动画、出场瞬切（setAll 瞬时换内容，做出场会让切换响应变慢一倍且旧节点脱离场景后动画不可见）。覆盖全部三个切换点。

## 改动文件

仅 [App.java](c:\Users\32934\IdeaProjects\YHCL\src\main\java\com\deepslate\yhcl\App.java)：

### 1. 新增 import（L3-43 区）

- `javafx.animation.ParallelTransition`
- `javafx.animation.TranslateTransition`
- `javafx.animation.Interpolator`
- `javafx.scene.Node`

（FadeTransition/Duration 已有）

### 2. 新增字段 + 动画辅助方法

- 字段 `ParallelTransition pageAnim`、`Node animNode`（放 pageHolder 字段 L107 区附近）
- `animateIn(Node n)`（放 syncRail 之后，约 L760）：

```java
private void animateIn(Node n) {
    if (pageAnim != null) { pageAnim.stop(); resetNode(animNode); } // stop 不触发 onFinished，须手动复位
    n.setOpacity(0); n.setTranslateY(12);
    FadeTransition ft = new FadeTransition(Duration.millis(220), n);
    ft.setToValue(1); ft.setInterpolator(Interpolator.EASE_OUT);
    TranslateTransition tt = new TranslateTransition(Duration.millis(220), n);
    tt.setToY(0); tt.setInterpolator(Interpolator.EASE_OUT);
    ParallelTransition pt = new ParallelTransition(ft, tt);
    pt.setOnFinished(e -> { resetNode(n); if (pageAnim == pt) { pageAnim = null; animNode = null; } });
    pageAnim = pt; animNode = n; pt.play();
}
private void resetNode(Node n) { if (n != null) { n.setOpacity(1); n.setTranslateY(0); } }
```

### 3. 三个切换点接入（setAll/setContent 之后紧接 animateIn(新节点)）

1. `showPage` L734 `pageHolder.getChildren().setAll(page)` 后 → `animateIn(page)`（启动 L419 首次调用同路径，220ms 无感）
2. `switchDownloadHubTab` L1431/L1434 后 → `animateIn(modDlPage)` / `animateIn(versionsPage)`
3. Mod 二级页：L1989 返回 `modDlScroll.setContent(modDlListContent)` 后 → `animateIn(modDlListContent)`；L2039 进详情 `modDlScroll.setContent(detail)` 后 → `animateIn(detail)`

## 防御与边界

- **快速连击**：新动画前 stop 旧动画并 resetNode(animNode)，杜绝 opacity<1/translateY≠0 残影；onFinished 用 `pageAnim == pt` 判等防旧回调误清新引用
- **节点搬移**：animateIn 只作用于 detail/modDlListContent 两个 VBox 根，不碰「下载到」三控件（搬移在 setContent 之前，与动画无交集）
- **ScrollPane**：translateY 是渲染变换不改布局边界；进详情/返回时 `modDlScroll.setVvalue(0)` 定滚动起点
- **悬浮球动画**（L910-932 showBall/hideBall）不动

## 打包部署（手动管线，记忆中有完整命令）

javac 全量编译 → pack.ps1 → stub.cs BuildStamp 改 `"2026-10-24-01"` → csc → PatchIcon → 拷桌面

## 验证

1. 冒烟：桌面 exe 启动，读 `C:\Users\32934\Desktop\YHCL\build.stamp` = 2026-10-24-01
2. 窗口自动化截图验证功能不回归：左侧导航切页（首页↔下载）、下载 Tab（版本下载↔Mod 下载）、Mod 卡片进详情/返回列表，页面内容均正常显示（动画本身是瞬时过程，截图验证最终状态无残影——opacity=1、无位移）
3. 快速连续点击导航数次后截图，确认最终页面完整、无半透明/错位残影
