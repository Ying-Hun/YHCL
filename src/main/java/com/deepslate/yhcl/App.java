package com.deepslate.yhcl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.deepslate.yhcl.MinecraftService.DownloadMonitor;
import javafx.animation.Animation;

import javafx.animation.AnimationTimer;

import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.ParallelTransition;
import javafx.animation.PauseTransition;
import javafx.animation.ScaleTransition;
import javafx.animation.Timeline;
import javafx.animation.TranslateTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.geometry.VPos;
import javafx.scene.CacheHint;
import javafx.scene.Cursor;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.*;
import javafx.scene.control.skin.VirtualFlow;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.DragEvent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.ScrollEvent;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.Arc;
import javafx.scene.shape.ArcType;

import javafx.scene.effect.GaussianBlur;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.SVGPath;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.shape.StrokeLineJoin;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;

import java.awt.Desktop;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.imageio.ImageIO;

public final class App extends Application {

    private final MinecraftService mc = new MinecraftService();
    private final FrpManager frp = new FrpManager();
    private final GameProfile profile = new GameProfile();
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private final ObjectMapper mapper = new ObjectMapper();
    /** 每个版本的独立设置（版本 ID → 设置） */
    private final Map<String, VerSetting> verSettings = new LinkedHashMap<>();
    private static final Path CONFIG = Launcher.appDir().resolve("config.json");
    private static final String DARKSLATE_CSS = "/darkslate.css";
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    /* ---- 账户状态（持久化到 config.json） ---- */
    private String accountName = "";
    private String accountUuid = "";
    private String accountToken = "";
    private String accountRefresh = "";
    private String accountType = "";          // microsoft | yggdrasil（空 = 未登录）
    private String authApi = "";              // 第三方登录 Yggdrasil API 地址
    private String authClientToken = "";      // 第三方登录 clientToken
    private final AtomicBoolean loginCancelled = new AtomicBoolean(false);
    private Stage loginStage;
    private boolean loading = false;

    /* ---- UI 控件 ---- */
    private TextArea logArea;
    private Label crumb, memLabel, selLabel, statusLabel, manifestInfo, javaScanInfo;
    private Label accountLabel;
    private Label yggdrNote;   // 版本设置「第三方登录」状态行
    private TextField userField, javaField, dirField, jvmField, searchField;
    private Slider memSlider;
    /* ---- 设置页内存分配卡（PCL2 风格） ---- */
    private String globalMemMode = "auto";   // 全局内存分配模式：auto | custom
    private boolean globalIsolation = true;  // 全局版本隔离（默认开启）：各版本独立游戏目录（mods/存档互不干扰），版本设置可单独覆盖
    private ToggleButton glMemAuto, glMemCustom;
    private Label glMemUsed, glMemAlloc;
    private ProgressBar glMemBar, glMemAllocBar;   // 深色=已用 / 浅色=已用+游戏分配
    /* ---- 内存范围（启动时检测本机物理内存） ---- */
    private int totalMemMB = 0;   // 本机物理内存总量；0 = 未检测到
    private int minMemMB = 1024;  // MC 所需最低内存
    private int maxMemMB = 16384; // 可调上限 = 本机物理内存（向下取整到 256 的倍数）
    private ComboBox<String> installedVerBox;
    private CheckBox autoJavaBox;
    /* ---- 设置页「下载」区块（PCL2 风格：下载源 / 线程数 / 速度限制 / 目标文件夹 / SSL） ---- */
    private ComboBox<String> fileSrcBox, metaSrcBox;
    private Slider threadSlider, speedSlider;
    private Label threadValLb, speedValLb;
    private CheckBox sslBox;
    private ListView<VersionEntry> manifestList;
    private ProgressBar launchProg;
    private Button launchBtn, manifestBtn, msLoginBtn, msLogoutBtn;
    private VBox homePage, versionsPage, settingsPage, logsPage, detailPage;
    private StackPane pageHolder;
    private Pane currentPage;
    private ParallelTransition pageAnim;   // 当前页面入场动画（快速连击时先停旧的）
    private Node animNode;                 // 正在做入场动画的节点（stop 后须手动复位）
    private ToggleButton railHome, railDl, railFrp, railShots, railSett, railLogs;
    private StackPane rootPane;
    private Label toast;
    private PauseTransition toastHide;
    private List<VersionEntry> manifestCache = List.of();
    private ToggleButton typeAll, typeRelease, typeSnapshot, typeLegacy;
    private String typeFilter = "all";

    /* ---- 版本详情页（二级界面） ---- */
    private VersionEntry detailVersion;
    private Label detailTitle, detailType, detailTime, detailJava, detailSize, detailInstalled, detailStatus;
    private ToggleButton loaderVanilla, loaderForge, loaderNeo, loaderFabric;
    private ComboBox<String> loaderVerCombo;
    private Button detailBack, detailInstallBtn;
    private ProgressBar detailProg;
    /** 加载器版本列表缓存：key = kind|mcVer，30 分钟内直接复用；future 同键并发去重 */
    private record LoaderList(CompletableFuture<List<String>> future, long at) { }
    private static final long LOADER_LIST_TTL_MS = 30 * 60 * 1000L;
    private final Map<String, LoaderList> loaderListCache = new ConcurrentHashMap<>();

    /* ---- Mods 页（二级界面） ---- */
    private ListView<MinecraftService.ModEntry> modList;
    private Label modDirLabel, modStatus;

    /* ---- 截图（一级页面） ---- */
    private VBox shotsPage;
    private FlowPane shotsGrid;          // 缩略图网格
    private Label shotsStatus;           // 状态行（目录 + 张数）
    private final List<Path> shotsList = new ArrayList<>();   // 当前展示的截图文件（与网格一一对应）

    /* ---- 联机模块：一级入口 + 樱花二级子页 ---- */
    private VBox frpHubPage;
    /** 直连联机页（UPnP/IPv6，零下载） */
    private VBox directPage;
    private Label directStatusLb, directMapStatusLb;
    private TextField directPortField, directAddrField;
    private Button directMapBtn;
    private final LanTunnel directTunnel = new LanTunnel();
    private volatile boolean directMapped;

    /* ---- 联机（陶瓦 Terracotta）页 ---- */
    private VBox terraPage;
    private Label terraStatusLb;
    private Button terraToggleBtn, terraOpenBtn;
    private Process terraProc;
    private volatile String terraUrl;

    /* ---- 联机（VoxLink Mod）页 ---- */
    private VBox voxPage;
    private Label voxStatusLb;
    private ComboBox<String> voxVer;
    private Button voxInstallBtn;

    /* ---- 设置页：游戏窗口 / 启动行为 / 高级选项 ---- */
    private Stage mainStage;
    private TextField winTitleField, customInfoField, gameArgsField, preCmdField;
    private ComboBox<String> visBox, priBox, sizeBox, gcBox, skinBox;
    private CheckBox memOptBox, chkHighGpu, chkLaunchWrap, chkLwjglAgent;
    private ImageView skinPreview;
    private Label skinFileNote;
    private Button skinImportBtn;   // 仅皮肤模式为「自定义」时可点
    // 运行时设置值（随 config.json 持久化）
    private String windowTitle = "", customInfo = "", launcherVis = "keep",
            processPriority = "normal", windowSize = "default", gameArgs = "",
            preLaunchCmd = "", gcMode = "g1", offlineSkin = "random",
            customSkinFile = "", customBgFile = "";
    private double customBgDim = 0.35;   // 背景暗色蒙版不透明度 0~0.8
    private boolean memOptimize = false, highPerfGpu = false,
            disableLaunchWrapper = false, disableLwjglAgent = false;
    private Pane bgLayer, bgDim, bgTint;   // 自定义背景图层 + 暗色蒙版层 + 全局色调层（root 最底层）
    private Color bgDomColor;                 // 背景图主打色（直方图主色，全 UI 文字配色依据）
    private String textThemeMemo;             // 当前文字主题变量串（防重复 setStyle 触发全树样式失效）
    private final java.util.Map<Label, String> adaptiveTextStyles = new java.util.WeakHashMap<>();
    private ImageView bgThumb;           // 设置页背景图缩略图
    private Label bgNote;                // 设置页背景图状态说明
    private Slider dimSlider;            // 背景蒙版滑块（字段：调试导航钩子需触达）

    /* ---- 背景容器（自定义背景图+蒙版，root 最底层） ---- */
    private StackPane bgWrap;

    /* ---- iOS 26 液态玻璃（模块卡片/导航栏）：所有卡片共用同一套实时玻璃渲染 ---- */

    private Image glassBackdrop;                       // 实时渲染的卡片后背景图（壁纸+蒙版），null=无背景（默认纯色底）

    private Image glassFrost;                          // 背景磨砂图（blur+saturate，liquid-glass-react 等价 backdrop-filter）

    private Timeline glassDebounce;                    // 窗口尺寸变化防抖（字段防 GC）

    private Timeline glassApplyDebounce;               // 动态节点增删防抖（只补套不重建背景）

    private Timeline glassSettle;                      // 滚动停稳补一次全质量重绘（字段防 GC）

    private long glassDimTick;                         // 蒙版拖动时玻璃重合成节流时间戳


    private int glassVer;                              // 背景版本号，重建后自增

    // 玻璃画布/兜底图/几何缓存挂在 node.getProperties()（不占全局 Map，节点销毁即释放）

    private double glassTw, glassTh;                   // 快照瞬间 bgWrap 的逻辑尺寸：采样换算只认快照基准，杜绝布局时序错位

    private Rectangle bgClip;                          // bgWrap 的圆角裁剪（与 shell 同界，快照会被它裁掉一圈）
    private double glassCropX, glassCropY;             // 快照裁剪偏移（裁剪框在 bgWrap 坐标里的原点）
    private double glassSnapW, glassSnapH;             // 快照内容区的逻辑尺寸（下载剪范围，比整窗小一圈）
    private static final boolean GLASS_DBG = false;   // 玻璃诊断日志（定位错位时临时开启）
    private long glassDrawCount;                       // 诊断用：面板绘制计数

    private long glassLiveFire;                       // 诊断用：滚动脉冲触发计数（vvalue 变化）
        private long glassLiveReq;                       // 诊断用：requestGlassLive 调用计数
    private long glassStyleN;                        // 诊断用：applyGlassStyle 调用计数
    private long glassWRec;                          // 诊断用：walk 卡记录计数

    private void glassDiag(String s) {
        // "noclip" 是 verify-jar 校验标记：drawGlassPanel 绝不用 g.clip()（OpenJFX JDK-8295062/8352209
        // clip 泄漏 VRAM 致渲染循环 NPE 永久中断）。注意：GLASS_DBG=false 时 javac 会把 guard 后的
        // 死代码全部折叠消除，所以标记只能放在 guard 之前的无条件语句里。
        s = "filter-only noclip " + s;
        if (!GLASS_DBG) return;
        try {
            Files.writeString(Path.of("glass-diag.log").toAbsolutePath(),
                    LocalTime.now().format(TIME) + " " + s + System.lineSeparator(),
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception ignored) { }
    }

    private static void dumpImagePpm(Image img, String name) {
        if (img == null) return;
        try {
            int w = (int) img.getWidth(), h = (int) img.getHeight();
            PixelReader pr = img.getPixelReader();
            if (pr == null || w < 2 || h < 2) return;
            byte[] px = new byte[w * h * 3];
            int i = 0;
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    Color c = pr.getColor(x, y);
                    px[i++] = (byte) Math.round(c.getRed() * 255);
                    px[i++] = (byte) Math.round(c.getGreen() * 255);
                    px[i++] = (byte) Math.round(c.getBlue() * 255);
                }
            }
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            bo.write(("P6\n" + w + " " + h + "\n255\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            bo.write(px);
            Files.write(Path.of(name).toAbsolutePath(), bo.toByteArray());
        } catch (Exception ignored) { }
    }



    /* ---- 联机（SakuraFrp）页 ---- */
    private VBox frpPage;
        private Label frpStatusLabel;
    private TextField frpAccessKeyField, frpTunnelInput;
    private PasswordField frpAccessKeyPw;
    private Button frpToggleBtn, frpAddTunnelBtn, frpDelTunnelBtn;
    private ListView<String> frpTunnelList;
    private TextArea frpLogArea;
    private Timeline frpTicker;

    /* ---- 下载模块：一级容器 + 两个二级子页 ---- */
    private VBox downloadHubPage, modDlPage;
    private StackPane downloadHubContentHolder;   // 下载模块内 Tab 切换的内容容器
    private ToggleButton dlHubTabVers, dlHubTabMods;
    private TextField modDlSearch;
    private ComboBox<String> modDlMcVersion, modDlLoader, modDlSort, modDlTargetVer;
    private ComboBox<String> modDlCat;   // 分类下拉（Modrinth/CurseForge 共通分类）
    private String modDlCategory = "";   // 选中的分类（Modrinth slug，"" = 全部）
    private boolean modDlVerQuiet;               // 程序化改写版本下拉值时不触发搜索
    private boolean mcVerLoading, mcVerLoaded;   // 游戏版本清单获取状态（点击下载模块自动获取）
    private VBox modDlCards;   // Mod 搜索结果卡片容器（与头部同在一个 ScrollPane 内滚动）
    private Label modDlStatus;
    private ScrollPane modDlScroll;      // 统一滚动容器（一级列表页 / 二级版本页切换内容）
    /* ---- 收藏（模组/整合包/材质包/光影） ---- */
    private final List<FavEntry> favList = new ArrayList<>();   // 持久化到 config.json
    private VBox favPage, favCards;
    private Label favStatus;
    private VBox modDlListContent;       // 一级页：搜索 + 过滤 + 卡片列表
    private HBox modDlFilterRow;         // 过滤行（进二级页时「下载到」控件会临时搬走）
    private HBox modDlPageBar;           // 分页栏：上一页 + 页码 + 下一页
    private int modDlCurPage = 1;        // 当前页码（1 起）
    private long modDlTotal;             // 搜索命中总数（分页用）
    private long modDlSeq;               // 搜索请求序号（丢弃过期响应）
    private static final int MOD_PAGE_SIZE = 30;   // 每页条数（与旧版单次加载条数一致）
    private Label modDlTargetTip;
    private Button modDlBrowseBtn;
    /* ---- 下载子模块：整合包 / 光影 / 材质包（共用一套构建逻辑） ---- */
    private PackDl packModpack, packShader, packRes;
    private ToggleButton dlHubTabPack, dlHubTabShader, dlHubTabRes, dlHubTabFav;
    private List<String> mcVerIds = List.of();   // 游戏版本清单缓存（填进各子模块版本下拉）

    /* ---- 版本设置页（单版本独立设置 + Mod 管理） ---- */
    private VBox verSetPage;
    private VBox vsContent;                 // 有选中版本时显示的设置内容
    private Label vsNoVer;                  // 未扫描到版本时的占位提示
    private ComboBox<String> vsIsolation, vsJava, vsMemOpt, vsLogin;
    private ToggleButton vsMemGlobal, vsMemAuto, vsMemCustom;
    private TextField vsServer, vsJvm, vsEnv;
    private Slider vsMemSlider;
    private Label vsJavaHint, vsMemUsed, vsMemAlloc, vsMemAutoNote;
    private ProgressBar vsMemBar;          // 深色段：已使用内存
    private ProgressBar vsMemAllocBar;     // 浅色段：已使用 + 游戏分配（叠在深色段下方）
    private boolean vsLoading;

    /* ---- 版本选择二级页面 + 自绘标题栏 ---- */
    private Button verSelBtn;
    private VBox verSelPage;
    private VBox verSelGroups;          // 版本分组卡片容器
    private double dragDX, dragDY;
    private Label appTitleLabel;        // 标题栏文字（含自定义信息，随设置刷新）

    /* ---- 下载监控：右下角悬浮球 + 下载详情页（二级界面） ---- */
    private StackPane downloadBall;
    private Arc ballRing;
    private SVGPath ballIcon;
    private VBox downloadPage;
    private ProgressBar dlProg;
    private Label dlPhase, dlStats, dlSpeed, dlFail, dlNote;
    private ListView<Integer> dlList;
    private Timeline dlTicker;
    private PauseTransition ballAutoHide;
    private Button dlStopBtn;
    private Button dlCancelBtn;
    /** 下载详情页当前渲染了多少行，用于判断是否需要重建列表 */
    private int dlListCount = -1;
    /** 上一次重画文件列表时的进度签名，避免空转重绘几千行 */
    private long dlSig = -1;
    /** 当前速度/ETA：按已下字节实时增量做 EMA 平滑（上次采样的字节数与时刻） */
    private long dlLastLive, dlLastTickNs;
    private double dlSmoothBps;

    private static final String ICON_DOWNLOAD = "M12 2 V14 M7 9 L12 14 L17 9 M4 19 H20";
    private static final String ICON_DONE = "M5 13 L10 18 L19 6";
    private static final String ICON_FAIL = "M7 7 L17 17 M17 7 L7 17";

    public static void main(String[] args) { launch(args); }

    /** 从 jar 资源加载 logo PNG（打包时由 MakeIcon 生成，与界面 .logo 同款）。
     *  用资源图片比 Canvas.snapshot 更可靠地显示在 Windows 任务栏。 */
    private static Image loadLogo(String res) {
        try (java.io.InputStream in = App.class.getResourceAsStream(res)) {
            if (in == null) return null;
            return new Image(in);
        } catch (Exception e) {
            return null;
        }
    }

    /** 检测本机物理内存总量（MB）。取不到时返回 0，由调用方回退。 */
    static int detectTotalMemoryMB() {
        try {
            java.lang.management.OperatingSystemMXBean os =
                    java.lang.management.ManagementFactory.getOperatingSystemMXBean();
            if (os instanceof com.sun.management.OperatingSystemMXBean sun) {
                long bytes = sun.getTotalMemorySize();
                if (bytes > 0) return (int) (bytes / (1024 * 1024));
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }

    /** 探测本机内存，确定内存滑块的 [最低, 上限] 区间；上限 = 本机物理内存。
     *  取不到物理内存时回退到 16384 MB。 */
    private void detectMemoryRange() {
        int total = detectTotalMemoryMB();
        if (total <= 0) {
            maxMemMB = 16384;
            return;
        }
        totalMemMB = total;
        // 上限向下取整到 256 的倍数，且不小于最低值
        int rounded = (total / 256) * 256;
        maxMemMB = Math.max(minMemMB, rounded);
    }

    /** 默认分配内存：取 4096 与本机上限的较小值，夹在 [最低, 上限] 内并取 256 整数倍。 */
    private int defaultMemory() {
        int base = Math.min(4096, maxMemMB);
        int v = Math.round(base / 256.0f) * 256;
        return Math.max(minMemMB, Math.min(v, maxMemMB));
    }

    /** PCL2 的内存自动分配算法（GetRam，PageSetupLaunch.xaml.vb）：
     *  目标档位随 Mod 数量上浮（T1 勉强带动 / T2 没啥问题 / T3 海量材质光影），
     *  再按「当前可用物理内存」四档扣减式分配——越高的档位，单位可用内存换到的分配额越少
     *  （0~T1 给 100%、T1~T2 给 70%、T2~T3 给 40%、T3~2T3 给 15%）。
     *  每次调用都按当下可用内存实时重算（与 PCL 一致）；breakdown 非空时写入估算明细。 */
    private int autoMemoryFor(String id, StringBuilder breakdown) {
        if (id == null || id.isBlank()) {
            int v = defaultMemory();
            if (breakdown != null) breakdown.append("未选择版本，按默认分配 ").append(v).append(" MB");
            return v;
        }
        int modCount = 0;
        try {
            for (MinecraftService.ModEntry e : mc.listMods(modsDirFor(id)))
                if (e.enabled()) modCount++;
        } catch (Exception ignored) {
        }
        // 目标档位（单位 GB）；modMin = 无论如何也要保证的最低限度
        double modMin, t1, t2, t3;
        if (!mc.getVersionLoader(id).isBlank()) {
            // 可装 Mod 的版本（Forge/NeoForge/Fabric/Quilt）
            modMin = 0.5 + modCount / 150.0;
            t1 = 1.5 + modCount / 90.0;
            t2 = 2.7 + modCount / 50.0;
            t3 = 4.5 + modCount / 25.0;
        } else {
            // 普通版本
            modMin = 0.5; t1 = 1.5; t2 = 2.5; t3 = 4.0;
        }
        double avail = 0;
        try {
            com.sun.management.OperatingSystemMXBean os =
                    (com.sun.management.OperatingSystemMXBean) java.lang.management.ManagementFactory.getOperatingSystemMXBean();
            avail = Math.round(os.getFreeMemorySize() / 1073741824.0 * 10) / 10.0;
        } catch (Exception ignored) {
        }
        double give = 0, a = avail, delta;
        delta = t1;                                        // 阶段一 0~T1，100%
        give += Math.min(a, delta); a -= delta;
        if (a >= 0.1) {
            delta = t2 - t1;                               // 阶段二 T1~T2，70%
            give += Math.min(a * 0.7, delta); a -= delta / 0.7;
        }
        if (a >= 0.1) {
            delta = t3 - t2;                               // 阶段三 T2~T3，40%
            give += Math.min(a * 0.4, delta); a -= delta / 0.4;
        }
        if (a >= 0.1) {
            delta = t3;                                    // 阶段四 T3~2T3，15%
            give += Math.min(a * 0.15, delta); a -= delta / 0.15;
        }
        give = Math.round(Math.max(give, modMin) * 10) / 10.0;
        int v = Math.max(minMemMB, Math.min((int) Math.floor(give * 1024), maxMemMB));
        if (breakdown != null) breakdown.append(String.format(
                "PCL GetRam 四档分配：Mod %d 个，可用内存 %.1f GB → %.1f GB（%d MB）",
                modCount, avail, give, v));
        return v;
    }

    /** 内存滑块下方的说明文字，包含检测到的本机内存。 */
    private String memRangeText() {
        String range = minMemMB + "–" + maxMemMB + " MB";
        if (totalMemMB > 0) {
            return "可调范围 " + range + "（本机内存 " + totalMemMB + " MB），改动自动保存";
        }
        return "可调范围 " + range + "，改动自动保存";
    }

    /* ==================== 启动 ==================== */
    @Override
    public void start(Stage stage) {
        detectMemoryRange();          // 先探测本机物理内存，再构建设置页
        homePage = buildHome();
        versionsPage = buildVersions();
        detailPage = buildDetail();
        modDlPage = buildModDownload();
        packModpack = new PackDl("modpack", "整合包", null);
        packModpack.page = buildPackDownload(packModpack);
        packShader = new PackDl("shader", "光影", "shaderpacks");
        packShader.page = buildPackDownload(packShader);
        packRes = new PackDl("resourcepack", "材质包", "resourcepacks");
        packRes.page = buildPackDownload(packRes);
        downloadHubPage = buildDownloadHub();
        verSetPage = buildVerSettings();
        verSelPage = buildVerSelPage();
        downloadPage = buildDownloadPage();
        frpPage = buildFrp();
        directPage = buildDirect();
        terraPage = buildTerra();
        voxPage = buildVox();
        frpHubPage = buildFrpHub();
        shotsPage = buildShots();
        settingsPage = buildSettings();
        logsPage = buildLogs();

        downloadBall = buildDownloadBall();
        dlTicker = new Timeline(new KeyFrame(Duration.millis(400), e -> refreshDownload()));
        dlTicker.setCycleCount(Animation.INDEFINITE);
        ballAutoHide = new PauseTransition(Duration.seconds(6));
        ballAutoHide.setOnFinished(e -> hideBall());

        HBox content = new HBox(18, buildRail(), buildCenter());
        content.setPadding(new Insets(6, 18, 18, 18));
        HBox.setHgrow(content.getChildren().get(1), Priority.ALWAYS);

        // 自绘标题栏（去掉系统白边）+ 整块玻璃外壳
        Pane titleBar = buildTitleBar(stage);
        VBox shell = new VBox(titleBar, content);
        shell.getStyleClass().add("shell");
        VBox.setVgrow(content, Priority.ALWAYS);

        // 自定义背景图层（最底）+ 暗色蒙版层；未设背景图时窗口保持纯色外壳底（不加装饰图层）
        bgLayer = new Pane();
        bgLayer.getStyleClass().add("bg-image");
        bgLayer.setVisible(false);
        bgLayer.setManaged(false);
        bgDim = new Pane();
        bgDim.getStyleClass().add("bg-dim");
        bgDim.setVisible(false);
        bgDim.setManaged(false);
        // 背景图裁剪到主页面（玻璃外壳）的圆角范围内，不溢出到窗口边距
                bgWrap = new StackPane(bgLayer, bgDim);
        bgWrap.getStyleClass().add("bg-base");   // 无壁纸时的默认底色，随 bgWrap 进玻璃快照
        bgClip = new Rectangle();
        bgClip.setArcWidth(48);   // Rectangle arc = 2×半径，与 .shell（radius 24）圆角一致
        bgClip.setArcHeight(48);
        bgClip.widthProperty().bind(shell.widthProperty());
        bgClip.heightProperty().bind(shell.heightProperty());
        bgClip.layoutXProperty().bind(shell.layoutXProperty());
        bgClip.layoutYProperty().bind(shell.layoutYProperty());
        bgWrap.setClip(bgClip);

        // 全局色调层：与玻璃卡片的色调填充同参数铺满卡缝（bgWrap 之上、shell 之下），

        // 卡缝与卡内同调，消除卡缝与卡内的色调断条；

        // 不进 bgWrap 快照（玻璃采样源），卡片不会双重叠加色调

        bgTint = new Pane();

        bgTint.setMouseTransparent(true);

        bgTint.setVisible(false);

        bgTint.setManaged(false);



        // 悬浮球叠在最上层，固定在右下角

        StackPane root = new StackPane(bgWrap, bgTint, shell, downloadBall);

        StackPane.setAlignment(downloadBall, Pos.BOTTOM_RIGHT);

        StackPane.setMargin(shell, new Insets(10));

        StackPane.setMargin(downloadBall, new Insets(0, 34, 34, 0));

        rootPane = root;
        hardenComboPopups(root);   // 全部下拉弹层文字锁固定深色（白底弹层防白字）



        // 色调层与壁纸同范围裁剪（同一圆角），缝隙色调与卡内严格同界

        Rectangle tintClip = new Rectangle();

        tintClip.setArcWidth(48);

        tintClip.setArcHeight(48);

        tintClip.widthProperty().bind(shell.widthProperty());

        tintClip.heightProperty().bind(shell.heightProperty());

        tintClip.layoutXProperty().bind(shell.layoutXProperty());

        tintClip.layoutYProperty().bind(shell.layoutYProperty());

        bgTint.setClip(tintClip);



                Scene scene = new Scene(root, 1180, 760);
        scene.setFill(Color.TRANSPARENT);
        installCss(scene);
        installModDrop(scene);   // 拖入 .jar / 整合包压缩包自动安装
        installWheelBoost(scene);   // 滚轮加速（所有可滚区域）
        // 诊断：事件是否进入场景（配合 yhcl.log 判定"点击/滚轮无反应"是事件链断了还是动作链断了）
        scene.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, e ->
                Launcher.diag("MOUSE_PRESSED target=" + e.getTarget()));
        scene.addEventFilter(ScrollEvent.SCROLL, e ->
                Launcher.diag("SCROLL target=" + e.getTarget()));
        // FX 线程未捕获异常（事件处理器抛出的异常最终落到这里）
        Thread.currentThread().setUncaughtExceptionHandler((t, ex) -> {
            Launcher.diag("FX UNCAUGHT: " + ex);
            for (StackTraceElement el : ex.getStackTrace()) Launcher.diag("    at " + el);
        });
        // 下拉弹窗/对话框等新窗口出现时同样加速滚轮
        javafx.stage.Window.getWindows().addListener(
                (javafx.collections.ListChangeListener<javafx.stage.Window>) ch -> {
            while (ch.next()) for (javafx.stage.Window w : ch.getAddedSubList()) {
                if (w.getScene() != null) installWheelBoost(w.getScene());
                else w.sceneProperty().addListener((o, a, b) -> installWheelBoost(b));
            }
        });

        // frp 日志回调 + 状态刷新定时器
        frp.setLogCallback(this::frpLog);
        frpTicker = new Timeline(new KeyFrame(Duration.millis(500), e -> refreshFrpUi()));
        frpTicker.setCycleCount(Animation.INDEFINITE);

        stage.initStyle(StageStyle.TRANSPARENT);   // 无系统边框，用自绘标题栏
        stage.setTitle("YHCL · Minecraft 启动器");
        stage.setScene(scene);
        stage.setMinWidth(980);
        stage.setMinHeight(660);
        for (Image logo : new Image[]{
                loadLogo("/logo-256.png"), loadLogo("/logo-64.png"), loadLogo("/logo-32.png")}) {
            if (logo != null) stage.getIcons().add(logo);   // 资源缺失时跳过，避免 getIcons() 收 null 直接崩溃
        }
        stage.setOnCloseRequest(e -> {
            shutdownLauncher();
        });
        // 窗口尺寸变化 → 防抖重建液态玻璃背景
        stage.widthProperty().addListener((o, a, b) -> scheduleGlassRefresh());
        stage.heightProperty().addListener((o, a, b) -> scheduleGlassRefresh());
        stage.show();
        mainStage = stage;
        Platform.runLater(this::scheduleGlassRefresh);   // 首轮布局完成后套上玻璃

        if (GLASS_DBG) {   // 调试导航钩子：启动时读 glass-nav.txt 直达指定页面（交付版 GLASS_DBG=false 不生效）
            try {
                String nav = Files.readString(Path.of("glass-nav.txt").toAbsolutePath()).trim();
                Platform.runLater(() -> {
                    if (nav.startsWith("settings")) showPage(settingsPage, "设置");
                    if (nav.startsWith("settings-v:")) {
                        final double v = Double.parseDouble(nav.substring("settings-v:".length()));
                        PauseTransition t1 = new PauseTransition(Duration.millis(4000));
                        t1.setOnFinished(ev -> {
                            for (Node nd : settingsPage.lookupAll(".scroll-pane")) {
                                if (nd instanceof ScrollPane sp) { sp.setVvalue(v); }
                            }
                        });
                        t1.play();
                    }
                    if (nav.startsWith("settings-dim:")) {
                        final double v = Double.parseDouble(nav.substring("settings-dim:".length()));
                        PauseTransition t1 = new PauseTransition(Duration.millis(4000));
                        t1.setOnFinished(ev -> {
                            if (dimSlider != null) dimSlider.setValue(v);   // 0~80，走监听器触发玻璃重合成
                        });
                        t1.play();
                    }
                    if ("settings-bottom".equals(nav)) {
                        PauseTransition t1 = new PauseTransition(Duration.millis(4000));
                        t1.setOnFinished(ev -> {
                            for (Node nd : settingsPage.lookupAll(".scroll-pane")) {
                                if (nd instanceof ScrollPane sp) { sp.setVvalue(1.0); }
                            }
                        });
                        t1.play();
                    }
                    if ("settings-drag".equals(nav)) {
                        PauseTransition t1 = new PauseTransition(Duration.millis(4000));
                        t1.setOnFinished(ev -> {
                            for (Node nd : settingsPage.lookupAll(".scroll-pane")) {
                                if (nd instanceof ScrollPane sp) {
                                    Timeline drag = new Timeline();
                                    for (int i = 1; i <= 24; i++) {
                                        final double v = i / 24.0;
                                        drag.getKeyFrames().add(new KeyFrame(Duration.millis(35L * i), e2 -> sp.setVvalue(v)));
                                    }
                                    drag.play();
                                }
                            }
                        });
                        t1.play();
                    }
                });
            } catch (Exception ignored) { }
        }
        // 隐藏启动器模式下窗口被 hide 不退出应用
        Platform.setImplicitExit(false);

        loadConfig();
        detectLocalGameDir();
        refreshInstalled();
        showPage(homePage, "首页");
        log("YHCL 已启动。游戏目录：" + mc.gameDir);
        silentRefresh();
        startFxWatchdog();
    }

    /** FX 线程心跳（看门狗线程写） */
    private volatile long fxBeatMs;

    /**
     * FX 线程卡死看门狗：每 2 秒投一次心跳，超过 6 秒没被消费就判定界面假死，
     * 把全线程栈写进 yhcl.log 留下现场（此类冻结事后无法从代码静态定位）。
     */
    private void startFxWatchdog() {
        AtomicBoolean reported = new AtomicBoolean();
        Thread t = new Thread(() -> {
            while (true) {
                try { Thread.sleep(2000); } catch (InterruptedException e) { return; }
                long at = System.currentTimeMillis();
                Platform.runLater(() -> fxBeatMs = at);
                if (at - fxBeatMs > 6000 && reported.compareAndSet(false, true)) {
                    StringBuilder sb = new StringBuilder("FX 线程疑似冻结，全线程栈：\n");
                    for (var e : Thread.getAllStackTraces().entrySet()) {
                        Thread th = e.getKey();
                        sb.append('"').append(th.getName()).append("\" ").append(th.getState()).append('\n');
                        for (StackTraceElement f : e.getValue()) sb.append("    at ").append(f).append('\n');
                    }
                    Launcher.diag(sb.toString());
                }
                if (at - fxBeatMs < 6000) reported.set(false);
            }
        }, "yhcl-fx-watchdog");
        t.setDaemon(true);
        t.start();
    }

    private void installCss(Scene scene) {
        var url = getClass().getResource(DARKSLATE_CSS);
        if (url != null) scene.getStylesheets().add(url.toExternalForm());
    }

        /* ==================== 自绘标题栏 + 液态玻璃背景 ==================== */

    /** 自绘标题栏：应用名 + 最小化/关闭按钮，整条空白区域可拖动窗口 */
    private Pane buildTitleBar(Stage stage) {
        appTitleLabel = new Label("YHCL · Minecraft 启动器");
        appTitleLabel.getStyleClass().addAll("muted-2", "small");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button minBtn = new Button("—");
        minBtn.getStyleClass().add("win-btn");
        minBtn.setOnAction(e -> stage.setIconified(true));
        Button closeBtn = new Button("✕");
        closeBtn.getStyleClass().addAll("win-btn", "win-close");
        closeBtn.setOnAction(e -> shutdownLauncher());
        HBox bar = new HBox(6, appTitleLabel, spacer, minBtn, closeBtn);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(12, 14, 0, 20));
        // 拖动窗口（按住标题栏空白处）
        bar.setOnMousePressed(e -> {
            dragDX = stage.getX() - e.getScreenX();
            dragDY = stage.getY() - e.getScreenY();
        });
        bar.setOnMouseDragged(e -> {
            stage.setX(e.getScreenX() + dragDX);
            stage.setY(e.getScreenY() + dragDY);
        });
        return bar;
    }

    /** 刷新启动器标题：标题栏文字与窗口标题都带上「自定义信息」 */
    private void refreshTitleText() {
        String base = "YHCL · Minecraft 启动器";
        String text = (customInfo == null || customInfo.isBlank()) ? base : base + " · " + customInfo.trim();
        if (appTitleLabel != null) appTitleLabel.setText(text);
        if (mainStage != null) mainStage.setTitle(text);
    }

    /* ==================== 离线皮肤：自定义皮肤导入 / 预览 ==================== */

    /** 导入自定义皮肤 PNG（64x64 或 64x32），复制到启动器数据目录并预览 */
    private void onImportSkin() {
        FileChooser fc = new FileChooser();
        fc.setTitle("选择皮肤 PNG");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("皮肤图片 (*.png)", "*.png"));
        File f = fc.showOpenDialog(mainStage);
        if (f == null) return;
        try {
            Image img = new Image(f.toURI().toString());
            if (img.isError()) throw new IOException("无法读取该图片");
            int w = (int) img.getWidth(), h = (int) img.getHeight();
            if (!((w == 64 && h == 64) || (w == 64 && h == 32))) {
                alert("皮肤尺寸不支持", "皮肤图片必须是 64x64 或 64x32 像素，当前 " + w + "x" + h + "。");
                return;
            }
            Path dir = Launcher.appDir().resolve("skins");
            Files.createDirectories(dir);
            Path dest = dir.resolve("custom-skin.png");
            Files.copy(f.toPath(), dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            customSkinFile = dest.toString();
            refreshSkinPreview();
            saveConfig();
            log("离线自定义皮肤已导入：" + dest);
        } catch (Exception ex) {
            alert("导入皮肤失败", ex.getMessage());
        }
    }

    /** 刷新自定义皮肤预览与文件状态说明；导入按钮仅「自定义」模式可点 */
    private void refreshSkinPreview() {
        if (skinImportBtn != null) skinImportBtn.setDisable(!"custom".equals(offlineSkin));
        if (skinPreview == null) return;
        boolean ok = false;
        try {
            ok = customSkinFile != null && !customSkinFile.isBlank()
                    && Files.exists(Path.of(customSkinFile));
        } catch (Exception ignored) { }
        if (ok) {
            skinPreview.setImage(new Image(Path.of(customSkinFile).toUri().toString()));
            skinPreview.setVisible(true);
            skinPreview.setManaged(true);
            if (skinFileNote != null) skinFileNote.setText("已导入：" + Path.of(customSkinFile).getFileName());
        } else {
            skinPreview.setImage(null);
            skinPreview.setVisible(false);
            skinPreview.setManaged(false);
            if (skinFileNote != null)
                skinFileNote.setText("未导入皮肤（自定义/随机模式将使用默认 Steve/Alex 皮肤）");
        }
    }

    /* ==================== 自定义背景图 ==================== */

    /** 选择自定义背景图：校验后复制到 YHCL/bg/ 并应用 */
    private void onChooseBg() {
        FileChooser fc = new FileChooser();
        fc.setTitle("选择背景图片");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("图片 (*.png *.jpg *.jpeg *.bmp)", "*.png", "*.jpg", "*.jpeg", "*.bmp"));
        File f = fc.showOpenDialog(mainStage);
        if (f == null) return;
        try {
            Image probe = new Image(f.toURI().toString(), false);
            if (probe.isError()) throw new IOException("无法读取该图片");
            String name = f.getName();
            String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "png";
            Path dir = Launcher.appDir().resolve("bg");
            Files.createDirectories(dir);
            Path dest = dir.resolve("custom-bg." + ext);
            Files.copy(f.toPath(), dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            customBgFile = dest.toString();
            applyCustomBg();
            saveConfig();
            log("自定义背景图已设置：" + dest);
        } catch (Exception ex) {
            alert("设置背景图失败", ex.getMessage());
        }
    }

    /** 恢复默认背景（液态玻璃光斑） */
    private void onResetBg() {
        customBgFile = "";
        customBgDim = 0.35;
        applyCustomBg();
        saveConfig();
        log("已恢复默认背景");
    }

    /** 应用自定义背景图：成功显示图+蒙版并隐藏光斑，失败回退默认 */
    private void applyCustomBg() {
        if (bgLayer == null) return;
        boolean ok = false;
        Image probe = null;
        try {
            ok = customBgFile != null && !customBgFile.isBlank()
                    && Files.exists(Path.of(customBgFile));
            if (ok) {
                probe = new Image(Path.of(customBgFile).toUri().toString(), false);
                ok = !probe.isError();

                if (ok) {

                    // CSS 背景图为异步载入：等真实像素就绪（progress=1）再重采样玻璃，

                    // 彻底消除“快照早于图片上屏”的空/旧窗快照

                    Image track = probe;

                    track.progressProperty().addListener((o, a, b) -> {

                        if (b.doubleValue() >= 1.0 && !track.isError())

                            Platform.runLater(() -> {

                                if (rootPane != null) rootPane.layout();

                                scheduleGlassRefresh();

                            });

                    });

                    if (track.getProgress() >= 1.0) scheduleGlassRefresh();

                }
            }
        } catch (Exception ignored) { ok = false; }
                bgDomColor = ok && probe != null ? dominantColor(probe) : null;
        if (ok) {
            // 用 BackgroundImage API 直接铺背景：旧方案把路径拼进 CSS url()，
            // 一旦 URI 含转义字符/中文等导致 CSS 解析失败，probe 能加载但背景显示为空（“选了图但背景没变”）
            bgLayer.setStyle(null);
            bgLayer.setBackground(new Background(new BackgroundImage(probe,
                    BackgroundRepeat.NO_REPEAT, BackgroundRepeat.NO_REPEAT,
                    BackgroundPosition.CENTER,
                    new BackgroundSize(1, 1, true, true, false, true))));   // cover 居中
        } else {
            bgLayer.setStyle(null);
            bgLayer.setBackground(null);
            if (customBgFile != null && !customBgFile.isBlank()) {
                customBgFile = "";
                if (bgNote != null) bgNote.setText("背景图无法加载，已恢复默认");
            }
        }
        bgLayer.setVisible(ok);
        bgLayer.setManaged(ok);
        bgDim.setVisible(ok);
        bgDim.setManaged(ok);
        bgDim.setOpacity(customBgDim);

        // 滤镜不改变背景颜色：卡缝直接显示纯背景，全局色调层永不启用



        if (bgTint != null) {

            bgTint.setVisible(false);

            bgTint.setManaged(false);



        }

                // 背景图激活时外壳/面板底色透明，背景图铺满主页面（方块卡片保持原样）
        if (rootPane != null) {
            rootPane.getStyleClass().remove("custom-bg");
            if (ok) rootPane.getStyleClass().add("custom-bg");
        }
        refreshBgPreview();
        applyAdaptiveTextColors();
        scheduleGlassRefresh();
    }

    /* ==================== iOS 26 液态玻璃（模块卡片/导航栏） ==================== */

    /** 窗口尺寸变化后防抖重建玻璃背景（Timeline 存字段防 GC） */

    private void scheduleGlassRefresh() {

        if (glassDebounce == null) {

            glassDebounce = new Timeline(new KeyFrame(Duration.millis(300), e -> {

                regenGlassBackdrop();

                applyGlassStyle();

            }));

            glassDebounce.setCycleCount(1);

        }

        glassDebounce.playFromStart();

    }



    /** 动态节点（如 mod 列表卡片）增删后防抖补套玻璃：共享背景不变，只给新节点合成面板图 */

    private void scheduleGlassApply() {

        if (glassApplyDebounce == null) {

            glassApplyDebounce = new Timeline(new KeyFrame(Duration.millis(120), e -> applyGlassStyle()));

            glassApplyDebounce.setCycleCount(1);

        }

        glassApplyDebounce.playFromStart();

    }



    /**

     * 滚动容器滚动时逐帧重绘玻璃。卡片后背景（bgWrap 壁纸+蒙版）在滚动中是静止的，

     * 采样源图（含磨砂）只需在换壁纸/改尺寸时重建一次；滚动中只把缓存磨砂图按卡片

     * 新位置重采样画进卡片画布——单帧只剩布局+少量 drawImage，追得上 60fps。

     * 重绘挂在 AnimationTimer（脉冲动画步，先于本脉冲的布局与渲染）：强制布局取到

     * 新坐标后立刻作画，玻璃与卡片同帧落位零滞后；同帧内多次滚轮事件自动合并一次重绘。

     */

    private void trackGlassScroll(ScrollPane sp) {

        sp.vvalueProperty().addListener((o, a, b) -> {
            long n = ++glassLiveFire;
            if (GLASS_DBG && (n <= 250 || n % 30 == 0)) {
                double ch = sp.getContent() != null ? sp.getContent().getBoundsInLocal().getHeight() : -1;
                double vp = sp.getViewportBounds().getHeight();
                glassDiag("vval #" + n + " " + a + "->" + b + " d=" + (b.doubleValue() - a.doubleValue()) + " contentH=" + ch + " vp=" + vp);
            }
            requestGlassLive();
        });

        sp.hvalueProperty().addListener((o, a, b) -> {
            long n = ++glassLiveFire;
            if (GLASS_DBG && (n <= 250 || n % 30 == 0)) {
                glassDiag("hval #" + n + " " + a + "->" + b + " d=" + (b.doubleValue() - a.doubleValue()));
            }
            requestGlassLive();
        });

    }



    /* ==================== 滚轮灵敏度加速 ==================== */

    /** 滚轮加速倍数（Windows 原生一格约 40px，长列表滚太慢） */
    private static final double WHEEL_BOOST = 3.5;
    /** 每格滚动即时响应的比例，剩余转为惯性余滑 */
    private static final double WHEEL_DIRECT = 0.8;
    /** 惯性余滑的轻微放量（总滚动量约为原来的 1.04 倍，不多滑） */
    private static final double WHEEL_GLIDE_EXTRA = 1.2;
    /** 惯性余滑存量上限（px），快速连滚也不会越滑越远 */
    private static final double WHEEL_GLIDE_MAX = 280;

    /* ---- 滚轮惯性：停止滚动后余滑一小段（指数衰减，总量受控） ---- */
    private double wheelGlidePx;         // 待余滑的剩余像素（向下为正）
    private long wheelGlideLastNs;       // 上一帧时间戳
    private Node wheelGlideTarget;       // 余滑目标容器（ScrollPane / VirtualFlow）
    private boolean wheelGlideRunning;
    private final AnimationTimer wheelGlideTimer = new AnimationTimer() {
        @Override public void handle(long now) {
            Node t = wheelGlideTarget;
            if (t == null || t.getScene() == null || Math.abs(wheelGlidePx) < 0.5) { stopGlide(); return; }
            double dt = Math.min(50, Math.max(1, (now - wheelGlideLastNs) / 1e6));   // ms
            wheelGlideLastNs = now;
            double step = wheelGlidePx * (1 - Math.exp(-dt / 90.0));   // 时间恒定衰减，与帧率无关
            wheelGlidePx -= step;
            scrollByPixels(t, step);
        }
    };

    /** 开始/累加余滑：同一目标继续，换目标则丢弃旧余滑 */
    private void startGlide(Node target, double px) {
        if (wheelGlideTarget != target) { wheelGlidePx = 0; wheelGlideTarget = target; }
        wheelGlidePx = Math.max(-WHEEL_GLIDE_MAX, Math.min(WHEEL_GLIDE_MAX, wheelGlidePx + px));
        if (!wheelGlideRunning) {
            wheelGlideRunning = true;
            wheelGlideLastNs = System.nanoTime();
            wheelGlideTimer.start();
        }
    }

    private void stopGlide() {
        wheelGlideTimer.stop();
        wheelGlideRunning = false;
        wheelGlidePx = 0;
        wheelGlideTarget = null;
    }

    /** 按像素滚动容器（向下为正）：ScrollPane 用 vvalue 换算，ListView 虚拟流用 scrollPixels */
    private void scrollByPixels(Node target, double px) {
        if (target instanceof ScrollPane sp && sp.getContent() != null) {
            double range = sp.getContent().getBoundsInLocal().getHeight() - sp.getViewportBounds().getHeight();
            if (range > 0) sp.setVvalue(sp.getVvalue() + px / range);
        } else if (target instanceof VirtualFlow<?> flow) {
            flow.scrollPixels(px);
        }
    }

    /** 给场景装滚轮加速过滤器（主窗口 + 下拉弹窗/对话框全覆盖，重复调用幂等） */
    private void installWheelBoost(Scene scene) {
        if (scene == null || scene.getProperties().put("yhclWheelBoost", Boolean.TRUE) != null) return;
        scene.addEventFilter(ScrollEvent.SCROLL, this::boostWheelScroll);
    }

    /** 滚轮事件：就近找可滚容器（ScrollPane / ListView 虚拟流），即时滚动 + 惯性余滑后吞掉原事件 */
    private void boostWheelScroll(ScrollEvent e) {
        // JavaFX 约定：正 delta = 往上滚（同 ScrollPaneSkin 原生取负语义），取负换算成「向下为正」
        double dy = -e.getDeltaY(), dx = -e.getDeltaX();
        if (dy == 0 && dx == 0) return;
        Node n = e.getTarget() instanceof Node nd ? nd : e.getPickResult().getIntersectedNode();
        for (Node cur = n; cur != null; cur = cur.getParent()) {
            if (cur instanceof ScrollPane sp && sp.getContent() != null) {
                boolean moved = false;
                if (dy != 0) {
                    double range = sp.getContent().getBoundsInLocal().getHeight() - sp.getViewportBounds().getHeight();
                    if (range > 0) {
                        double px = dy * WHEEL_BOOST;
                        scrollByPixels(cur, px * WHEEL_DIRECT);
                        startGlide(cur, px * (1 - WHEEL_DIRECT) * WHEEL_GLIDE_EXTRA);
                        moved = true;
                    }
                }
                if (dx != 0) {
                    double range = sp.getContent().getBoundsInLocal().getWidth() - sp.getViewportBounds().getWidth();
                    if (range > 0) { sp.setHvalue(sp.getHvalue() + dx * WHEEL_BOOST / range); moved = true; }
                }
                if (moved) { e.consume(); return; }   // 本层滚不动（无溢出）就继续找外层容器
            } else if (cur instanceof VirtualFlow<?> flow) {
                double px = dy * WHEEL_BOOST;
                flow.scrollPixels(px * WHEEL_DIRECT);
                startGlide(flow, px * (1 - WHEEL_DIRECT) * WHEEL_GLIDE_EXTRA);
                e.consume();
                return;
            } else if (cur instanceof Control ctl && dy != 0) {
                // 兜底：皮肤自带滚动条的控件（如 TextArea 直接点在控件上）
                for (Node bar : ctl.lookupAll(".scroll-bar")) {
                    if (bar instanceof ScrollBar vb && vb.getOrientation() == javafx.geometry.Orientation.VERTICAL
                            && vb.isVisible() && vb.getUnitIncrement() > 0) {
                        vb.adjustValue(vb.getValue() + (dy / 40.0) * vb.getUnitIncrement() * WHEEL_BOOST);
                        e.consume();
                        return;
                    }
                }
            }
        }
    }

    /** 同帧多次玻璃重绘请求的合并标记 */
    private boolean glassLivePending;

    private void requestGlassLive() {

        if (GLASS_DBG && ++glassLiveReq < 250) glassDiag("live #" + glassLiveReq);
        // 脉冲末尾重绘：布局已由场景自身完成，坐标是当帧最终值；
        // 绝不能强制 rootPane.layout()——那会扰动 ScrollPane 的 vvalue 钳位回路（爬行/风暴根因）
        if (glassLivePending) return;   // 同帧多次滚轮事件合并成一次重绘，防 runLater 堆积
        glassLivePending = true;
        Platform.runLater(() -> {
            glassLivePending = false;
            if (glassBackdrop == null) renderGlassBackdrop();   // 采样源缺失才重建（滚动中常态命中缓存）
            applyGlassStyle();
        });
        if (glassSettle == null) {

            glassSettle = new Timeline(
                    new KeyFrame(Duration.millis(180), e -> Platform.runLater(App.this::applyGlassStyle)),
                    new KeyFrame(Duration.millis(800), e -> Platform.runLater(() -> {
                        glassVer++;   // 收尾重生成：布局已静止，位置读数最终可信，全部玻璃无条件重画一次
                        applyGlassStyle();
                    })));
            glassSettle.setCycleCount(1);

        }

        glassSettle.playFromStart();   // 滚动停稳后补一次重绘收尾

    }





    /** 重建玻璃采样源（尺寸/背景变化后旧图作废，bust 版本缓存） */

    private void regenGlassBackdrop() {

        glassVer++;

        glassDiag("regen ver=" + glassVer);

        glassBackdrop = null;

        glassFrost = null;

        renderGlassBackdrop();

    }



    /**

     * 实时渲染「卡片正后方背景图」：对壁纸层（含暗色蒙版）整幅真实快照，全分辨率零模糊。

     * 无自定义壁纸时返回 null：默认背景是纯色外壳，玻璃走平色磨砂兜底。绝不能拿别的

     * 装饰图层冒充背景——采样源与卡缝后的真实画面对不上，就是"提前渲染好/割裂"的总根因。

     */

    private void renderGlassBackdrop() {

        boolean hasBg = bgWrap != null && bgLayer != null && bgLayer.isVisible();

        if (!hasBg) {

            glassBackdrop = null;

            glassFrost = null;

            glassDiag("backdrop: no wallpaper visible");

            return;

        }

        try {

            glassTw = bgWrap.getWidth();

            glassTh = bgWrap.getHeight();

            glassBackdrop = bgWrap.snapshot(transparentSnap(), null);   // 快照即卡片正后方真实画面（壁纸+蒙版）

            // bgWrap 自带 bgClip（shell 圆角裁剪）：快照只含裁剪框内画面（如 1160x740 vs 整窗 1180x760）。
            // 采样必须按快照内容区换算：原点减裁剪偏移、尺度按内容区尺寸，
            // 否则卡内画面会被系统性压缩+位移（“一张脸变三张脸”的根因）。
                        // glassSnapW/H 是快照内容区的【逻辑】尺寸（bgClip 裁剪范围），绝不能取 Image 像素宽：
            // kw = 磨砂图像素宽(含pad) - 2*pad 后再除以逻辑宽，商恰好等于 renderScale，
            // 采样窗才能在 HiDPI（125%/150%）下把逻辑坐标正确换算到图像像素。
            // 取像素宽会让 kw 恒等于 1，高分屏缩放下采样窗偏小、卡内背景被放大错位
            // （卡片看起来像"单独渲染"的一块贴片，与身后背景对不上）
            glassSnapW = bgClip != null ? bgClip.getWidth() : glassTw;

            glassSnapH = bgClip != null ? bgClip.getHeight() : glassTh;

            glassCropX = bgClip != null ? bgClip.getLayoutX() : 0;

            glassCropY = bgClip != null ? bgClip.getLayoutY() : 0;

                        glassFrost = glassBackdrop != null ? frostImage(glassBackdrop) : null;

            glassVer++;   // 快照就绪/更新后强制全部玻璃卡重画，避免停留在旧的白兜底缓存

            if (GLASS_DBG) {
                dumpImagePpm(glassBackdrop, "glass-backdrop.ppm");
                dumpImagePpm(glassFrost, "glass-frost.ppm");
                glassDiag("clip=" + (bgClip != null ? bgClip.getLayoutX() + "," + bgClip.getLayoutY()
                        + " " + bgClip.getWidth() + "x" + bgClip.getHeight() + " arc=" + bgClip.getArcWidth() : "none"));
            }

            glassDiag("backdrop bgWrap=" + glassTw + "x" + glassTh

                    + " snap=" + (glassBackdrop != null ? glassBackdrop.getWidth() + "x" + glassBackdrop.getHeight() : "null")

                    + " frost=" + (glassFrost != null ? glassFrost.getWidth() + "x" + glassFrost.getHeight() : "null"));

        } catch (Exception ex) {

            glassBackdrop = null;

            glassFrost = null;

        }

    }



    /**

     * 磨砂图：等价 liquid-glass-react 的 backdrop-filter: blur(6px) saturate(140%)。

     * GaussianBlur 纯磨砂一次成像；四周 GLASS_PAD 边距用拉伸图铺底（边缘延拓），

     * 既堵模糊出血透明边，也保证采样窗滑到窗口外时各带贴到一致内容、不错位成台阶。

     */

    private static Image frostImage(Image src) {

        try {

            int iw = (int) Math.ceil(src.getWidth()), ih = (int) Math.ceil(src.getHeight());

            if (iw < 8 || ih < 8) return src;

            int pw = iw + 2 * GLASS_PAD, ph = ih + 2 * GLASS_PAD;

            Canvas c = new Canvas(pw, ph);

            GraphicsContext g = c.getGraphicsContext2D();

            g.setImageSmoothing(true);

            // 边缘复制铺底（与正图边界像素级连续，采样窗探出窗口不会错位出条纹），同时堵住模糊出血的透明边

            g.drawImage(src, GLASS_PAD, GLASS_PAD, iw, ih);

            g.drawImage(src, 0, 0, 1, ih, 0, GLASS_PAD, GLASS_PAD, ih);

            g.drawImage(src, iw - 1, 0, 1, ih, GLASS_PAD + iw, GLASS_PAD, GLASS_PAD, ih);

            g.drawImage(src, 0, 0, iw, 1, 0, 0, iw, GLASS_PAD);

            g.drawImage(src, 0, ih - 1, iw, 1, 0, GLASS_PAD + ih, iw, GLASS_PAD);

            g.drawImage(src, 0, 0, 1, 1, 0, 0, GLASS_PAD, GLASS_PAD);

            g.drawImage(src, iw - 1, 0, 1, 1, GLASS_PAD + iw, 0, GLASS_PAD, GLASS_PAD);

            g.drawImage(src, 0, ih - 1, 1, 1, 0, GLASS_PAD + ih, GLASS_PAD, GLASS_PAD);

            g.drawImage(src, iw - 1, ih - 1, 1, 1, GLASS_PAD + iw, GLASS_PAD + ih, GLASS_PAD, GLASS_PAD);

            // backdrop-filter: blur(6px) —— 纯磨砂不调色（saturate 会改变背景颜色）



            GaussianBlur blur = new GaussianBlur(6);





            g.setEffect(blur);

            g.drawImage(src, GLASS_PAD, GLASS_PAD, iw, ih);

            g.setEffect(null);

            return c.snapshot(transparentSnap(), null);

        } catch (Exception e) {

            return src;

        }

    }



    /** 对所有卡片/导航栏一次性套上合成玻璃图（几何/背景版本不变时命中缓存零开销） */

    private void applyGlassStyle() {

        if (rootPane == null) return;

        if (GLASS_DBG && ++glassStyleN <= 250) {
            int pi = 0;
            for (Node nd : settingsPage.lookupAll(".scroll-pane")) {
                if (nd instanceof ScrollPane sp && sp.getContent() != null) {
                    glassDiag("style #" + glassStyleN + " pane[" + pi + "] v=" + sp.getVvalue() + " contentH=" + sp.getContent().getBoundsInLocal().getHeight() + " contentY=" + sp.getContent().localToScene(0, 0).getY());
                }
                pi++;
            }
        }

        double tw = bgWrap != null ? bgWrap.getWidth() : 0;

        double th = bgWrap != null ? bgWrap.getHeight() : 0;

        applyGlassWalk(rootPane, tw, th);

    }



    private static final String GLASS_CV = "yhclGlassCanvas";   // 常驻玻璃画布

    private static final String GLASS_IMG = "yhclGlassImg";     // 非 Pane 兜底快照图

    private static final String GLASS_GEOM = "yhclGlassGeom";   // {x, y, w, h, ver}

    private static final int GLASS_PAD = 32;                    // 磨砂图四周延拓边距（px）

    private static final Color GLASS_BASE = Color.rgb(255, 255, 255, 0.35);   // 合成失败兜底：中性半透明，保留背景本色



    private void applyGlassWalk(Parent p, double tw, double th) {

        for (Node n : p.getChildrenUnmodifiable()) {

            boolean isRail = n.getStyleClass().contains("rail");

            boolean isCard = n.getStyleClass().contains("card");

            boolean isMod = n.getStyleClass().contains("mod-cell-card");

            if ((isRail || isCard || isMod) && n instanceof Region r && n.isVisible()) {

                // 尺寸取整到像素：布局浮点抖动（如 673.33↔674.0）会让每帧都"变了尺寸"，

                // 画布缓冲随之逐帧重建，渲染层来不及上传就是整卡闪空

                double w = Math.ceil(r.getWidth()), h = Math.ceil(r.getHeight());

                if (w >= 4 && h >= 4) {

                    Point2D sc = r.localToScene(0, 0);

                    var props = n.getProperties();

                    double[] g = (double[]) props.get(GLASS_GEOM);

                    boolean hasGlass = props.containsKey(GLASS_CV) || props.containsKey(GLASS_IMG);

                    boolean cached = g != null && g[4] == glassVer && g[0] == sc.getX()

                            && g[1] == sc.getY() && g[2] == w && g[3] == h && hasGlass;

                    if (GLASS_DBG && ++glassWRec <= 1000) glassDiag("wrec y=" + sc.getY() + " on=" + (sc.getX() + w > 0 && sc.getY() + h > 0 && sc.getX() < tw && sc.getY() < th) + " cached=" + cached + " has=" + hasGlass + " cls=" + n.getStyleClass().toString().substring(0, Math.min(24, n.getStyleClass().toString().length())));

                    if (!cached) {

                        // 视口外且已有玻璃的节点跳过重绘（被裁剪不可见）；

                        // 尚无玻璃的节点一律绘制，滚动进视野前就有玻璃，不会闪白

                        boolean onScreen = sc.getX() + w > 0 && sc.getY() + h > 0

                                && sc.getX() < tw && sc.getY() < th;

                        if (onScreen || !hasGlass) {

                            double radius = isRail ? 26 : (isMod ? 16 : 18);

                            try {

                                if (r instanceof Pane pane) {

                                    // 常驻画布铺在内容下层：滚动时直接重画玻璃，实时跟随

                                    Canvas cv = (Canvas) props.get(GLASS_CV);

                                    if (cv == null) {

                                        cv = new Canvas();

                                        cv.setMouseTransparent(true);

                                        cv.setManaged(false);

                                        pane.getChildren().add(0, cv);

                                        props.put(GLASS_CV, cv);

                                        r.setBackground(Background.EMPTY);   // 兜底残留纯色须清掉（只设一次，逐帧设会无效化节点）

                                    }

                                    if (cv.getWidth() != w || cv.getHeight() != h) {

                                        cv.setWidth(w);

                                        cv.setHeight(h);

                                    }

                                    drawGlassPanel(cv.getGraphicsContext2D(), w, h,

                                            sc.getX(), sc.getY(), radius, tw, th);

                                } else {

                                    // 非 Pane 区域无法垫画布，退回快照贴背景——同一套 drawGlassPanel

                                    // 渲染、同一几何缓存节奏，与画布卡完全同款

                                    Image img = glassPanelImage(w, h, sc.getX(), sc.getY(), radius, tw, th);

                                    props.put(GLASS_IMG, img);

                                    r.setBackground(new Background(new BackgroundImage(img,

                                            BackgroundRepeat.NO_REPEAT, BackgroundRepeat.NO_REPEAT,

                                            BackgroundPosition.DEFAULT,

                                            new BackgroundSize(w, h, false, false, false, false))));

                                }

                                props.put(GLASS_GEOM, new double[]{sc.getX(), sc.getY(), w, h, glassVer});

                            } catch (Exception ex) {

                                // 单面板合成失败不中断整树，落到纯色兜底

                                props.remove(GLASS_CV);

                                props.remove(GLASS_IMG);

                                r.setBackground(Background.fill(GLASS_BASE));

                            }

                        }

                    }

                }

            }

            // 不可见子树整体跳过：隐藏页/折叠区里的卡 isVisible() 仍为 true，不拦会给它们全量
            // 建画布重绘（分组卡数百张时收尾重生成/滚动重绘会拖垮 UI）；显示时由 applyGlassStyle 补画
            if (n.isVisible() && n instanceof Parent cp) applyGlassWalk(cp, tw, th);

        }

    }



    private void drawGlassPanel(GraphicsContext g, double w, double h, double sceneX, double sceneY,

                                double radius, double tw, double th) {

        g.clearRect(0, 0, w, h);

        g.save();

        // 绝不能用 g.clip()：OpenJFX 已知 bug（JDK-8295062 / JDK-8352209）——Canvas 内 clip 走
        // blendAthruBintoC 内部合成路径会持续泄漏 VRAM，纹理池随后误回收仍被引用的纹理，
        // D3DTexture.update 抛 NPE 后渲染循环永久中断（画面冻结、点击/滚轮全部失效）。
        // 视觉等价替代：直接用圆角路径填充（GraphicsContext 路径是累积的，每条子路径前须 beginPath）。
        g.beginPath();

        g.appendSVGPath(roundRectPath(0, 0, w, h, radius));

        g.setFill(Color.rgb(255, 255, 255, 0.14));

        g.fill();

        if (GLASS_DBG && glassDrawCount++ < 30) {
            glassDiag("panel " + w + "x" + h + " at " + sceneX + "," + sceneY + " (filter-only noclip)");
        }

        // liquid-glass-react 双层边框高光：135° 渐变环（两端透明、中段最亮）+ inset 白描边 + 顶部柔光
        // Layer A（原 screen 层）淡底光；Layer B（原 overlay 层）主亮带。环占边缘 0~1.5px

        // 顶部柔光（原 box-shadow inset 0 1px 3px rgba(255,255,255,0.25)）
        // 纵向渐变超出 y=4 后即透明（NO_CYCLE 末端色），整板圆角路径填充 == 原 clip+fillRect(0,0,w,4)
        g.setFill(new LinearGradient(0, 0, 0, 4, false, CycleMethod.NO_CYCLE,

                new Stop(0, Color.rgb(255, 255, 255, 0.25)),

                new Stop(1, Color.rgb(255, 255, 255, 0))));

        g.beginPath();

        g.appendSVGPath(roundRectPath(0, 0, w, h, radius));

        g.fill();

        g.beginPath();

        g.appendSVGPath(roundRectPath(0.75, 0.75, w - 1.5, h - 1.5, Math.max(radius - 0.75, 0.75)));

        g.setLineWidth(1.5);

        g.setStroke(new LinearGradient(0, 0, w, h, false, CycleMethod.NO_CYCLE,

                new Stop(0, Color.rgb(255, 255, 255, 0)),

                new Stop(0.33, Color.rgb(255, 255, 255, 0.04)),

                new Stop(0.66, Color.rgb(255, 255, 255, 0.10)),

                new Stop(1, Color.rgb(255, 255, 255, 0))));

        g.stroke();

        g.beginPath();

        g.appendSVGPath(roundRectPath(0.75, 0.75, w - 1.5, h - 1.5, Math.max(radius - 0.75, 0.75)));

        g.setStroke(new LinearGradient(0, 0, w, h, false, CycleMethod.NO_CYCLE,

                new Stop(0, Color.rgb(255, 255, 255, 0)),

                new Stop(0.33, Color.rgb(255, 255, 255, 0.14)),

                new Stop(0.66, Color.rgb(255, 255, 255, 0.30)),

                new Stop(1, Color.rgb(255, 255, 255, 0))));

        g.stroke();

        // inset 锐描边（原 box-shadow 0 0 0 0.5px rgba(255,255,255,0.5) inset）
        g.beginPath();

        g.appendSVGPath(roundRectPath(1, 1, w - 2, h - 2, Math.max(radius - 1, 2)));

        g.setLineWidth(1);

        g.setStroke(Color.rgb(255, 255, 255, 0.45));

        g.stroke();

        g.restore();

    }






    /** 非 Pane 节点用：drawGlassPanel 内容的快照版，作为背景图贴上（无法垫画布时的退路，与画布卡同一套渲染） */

    private Image glassPanelImage(double w, double h, double sceneX, double sceneY,

                                  double radius, double tw, double th) {

        Canvas c = new Canvas(w, h);

        drawGlassPanel(c.getGraphicsContext2D(), w, h, sceneX, sceneY, radius, tw, th);

        return c.snapshot(transparentSnap(), null);

    }



    /**

     * 透明填充的快照参数。默认 snapshot(null, null) 会用不透明白色填充未绘制区域，

     * 圆角 clip 外侧的角落会残留白色楔形（即 r 角白边），必须用透明填充。

     */

    private static SnapshotParameters transparentSnap() {
        SnapshotParameters sp = new SnapshotParameters();
        sp.setFill(Color.TRANSPARENT);
        return sp;
    }

    /** 圆角矩形 SVG 路径（供 GraphicsContext.clip 用），x/y 为左上角偏移 */
    private static String roundRectPath(double x, double y, double w, double h, double r) {
        return "M " + (x + r) + " " + y + " H " + (x + w - r) + " A " + r + " " + r + " 0 0 1 " + (x + w) + " " + (y + r)
                + " V " + (y + h - r) + " A " + r + " " + r + " 0 0 1 " + (x + w - r) + " " + (y + h)
                + " H " + (x + r) + " A " + r + " " + r + " 0 0 1 " + x + " " + (y + h - r)
                + " V " + (y + r) + " A " + r + " " + r + " 0 0 1 " + (x + r) + " " + y + " Z";
    }

    /* ==================== 背景主打色系文字主题（全 UI 文字动态配色） ==================== */

        /** 主题变量名（顺序与 textPalette 返回值一一对应）：按文字所在背景分组，每组各自取对比色：
        ①②③ 裸壁纸底文字（tx/tx-muted/tx-faint，壁纸主色反转）
        ④⑤⑥ 玻璃卡片面文字（tx-card*，玻璃提亮基色反转，与裸底字不同色）
        ⑦⑧⑨ 浅色控件面文字（tx-solid*，恒深字随壁纸色相）
        ⑩⑪⑫ 按钮/药丸面（tx-solid-2/3/strong）
        ⑬⑭⑮ 强调/警告（tx-accent/accent-strong/amber）
        ⑯⑰⑱ 日志深底/提示/卡片强调（tx-solid-log/prompt/card-accent） */
    private static final String[] THEME_VARS_NAMES = {
            "tx", "tx-muted", "tx-faint",
            "tx-card", "tx-card-muted", "tx-card-faint",
            "tx-solid", "tx-solid-muted", "tx-solid-faint",
            "tx-solid-2", "tx-solid-3", "tx-solid-strong",
            "tx-accent", "tx-accent-strong", "tx-amber",
            "tx-solid-log", "tx-solid-prompt", "tx-card-accent"
    };

    /** 8×8×8 直方图主打色：比平均色更贴“主打色系”（平均色会被大面积渐变拉平） */
    private static Color dominantColor(Image img) {
        try {
            javafx.scene.image.PixelReader pr = img.getPixelReader();
            if (pr == null) return null;
            int w = (int) img.getWidth(), h = (int) img.getHeight();
            if (w <= 0 || h <= 0) return null;
            long[] hist = new long[512];
            int step = Math.max(1, Math.max(w, h) / 256);
            for (int y = 0; y < h; y += step) {
                for (int x = 0; x < w; x += step) {
                    int argb = pr.getArgb(x, y);
                    if (((argb >>> 24) & 0xFF) < 16) continue;   // 透明像素不参与
                    int ri = ((argb >> 16) & 0xFF) >> 5, gi = ((argb >> 8) & 0xFF) >> 5, bi = (argb & 0xFF) >> 5;
                    hist[(ri << 6) | (gi << 3) | bi]++;
                }
            }
            int best = 0;
            for (int i = 1; i < hist.length; i++) if (hist[i] > hist[best]) best = i;
            if (hist[best] == 0) return null;
            return Color.rgb(((best >> 6) & 7) * 32 + 16, ((best >> 3) & 7) * 32 + 16, (best & 7) * 32 + 16);
        } catch (Exception e) {
            return null;
        }
    }

                /**
     * 文字色板（18 个 hex，顺序同 THEME_VARS_NAMES）：按文字所在背景分组各取对比色，
     * 不同背景上的文字颜色各不相同，全部随背景图实时变化：
     * · 裸壁纸底（tx/tx-muted/tx-faint）：基色=壁纸主色（含蒙版），完全反转——黑底白字、白底黑字；
     * · 玻璃卡片面（tx-card*）：卡片玻璃把壁纸提亮后作基色再反转，字色带一丝色相，与裸底字天然分色；
     * · 浅色控件面（tx-solid*，输入框/列表/版本行/按钮等白底条）：底色≈白 → 恒深字，
     *   但色相/饱和度跟随壁纸主色，每个背景图下深字颜色各不相同；
     * · 强调/琥珀（tx-accent 与 tx-amber）：高饱和独立色系；
     * · 日志深底（tx-solid-log）：底色固定深 → 恒亮字。
     */
    private static String[] textPalette(Color dom, double dim) {
        double k = 1.0 - dim;
        Color eff = new Color(Math.min(1, dom.getRed() * k), Math.min(1, dom.getGreen() * k), Math.min(1, dom.getBlue() * k), 1);
        double lum = 0.299 * eff.getRed() + 0.587 * eff.getGreen() + 0.114 * eff.getBlue();
        double h = eff.getHue();
        boolean dark = lum < 0.5;
        double ah = (38 + ((((h - 38) % 360 + 540) % 360 - 180) * 0.35) + 360) % 360;
        String[] v = new String[18];
        // ① 裸壁纸底文字：与壁纸明暗完全相反（黑底白字、白底黑字），近无色保干净
        v[0] = hex(Color.hsb(h, 0.06, dark ? 0.98 : 0.07));
        v[1] = hex(Color.hsb(h, 0.09, dark ? 0.86 : 0.20));
        v[2] = hex(Color.hsb(h, 0.12, dark ? 0.72 : 0.34));
        // ② 玻璃卡片面文字：玻璃提亮后反转，带一丝壁纸色相，与裸底字分色
        v[3] = hex(Color.hsb(h, dark ? 0.18 : 0.28, dark ? 0.94 : 0.14));
        v[4] = hex(Color.hsb(h, dark ? 0.15 : 0.22, dark ? 0.82 : 0.30));
        v[5] = hex(Color.hsb(h, dark ? 0.12 : 0.18, dark ? 0.68 : 0.44));
        // ③ 浅色控件面（白底条）：恒深字，色相/饱和随壁纸主色 → 每张壁纸深字颜色不同
        v[6] = hex(Color.hsb(h, 0.42, 0.22));
        v[7] = hex(Color.hsb(h, 0.32, 0.34));
        v[8] = hex(Color.hsb(h, 0.24, 0.46));
        // ④ 按钮/药丸面白底字：恒深字，另一组色相/饱和与正文区分
        v[9] = hex(Color.hsb(h, 0.50, 0.26));
        v[10] = hex(Color.hsb(h, 0.38, 0.32));
        v[11] = hex(Color.hsb(h, 0.72, 0.30));
        // ⑤ 强调/警告：高饱和独立色系
        v[12] = hex(Color.hsb((h + 18) % 360, dark ? 0.85 : 0.92, dark ? 0.95 : 0.28));
        v[13] = hex(Color.hsb((h + 18) % 360, dark ? 0.92 : 1.00, dark ? 0.88 : 0.20));
        v[14] = hex(Color.hsb(ah, dark ? 0.95 : 1.00, dark ? 0.98 : 0.42));
        // ⑥ 日志深底（恒亮字）/ 提示灰 / 卡片强调
        v[15] = hex(Color.hsb(h, 0.15, dark ? 0.88 : 0.62));
        v[16] = hex(Color.hsb(h, 0.12, dark ? 0.72 : 0.52));
        v[17] = hex(Color.hsb((h + 18) % 360, dark ? 0.88 : 0.95, dark ? 0.92 : 0.32));
        return v;
    }

    private static String hex(Color c) {
        return String.format(java.util.Locale.ROOT, "#%02X%02X%02X",
                (int) Math.round(c.getRed() * 255), (int) Math.round(c.getGreen() * 255), (int) Math.round(c.getBlue() * 255));
    }

    /** 主题变量串：rootPane.setStyle 整套覆写，全场景文字（含动态 cell）随主打色系切换 */
    private static String textThemeVars(Color dom, double dim) {
        String[] v = textPalette(dom, dim);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < v.length; i++) sb.append(THEME_VARS_NAMES[i]).append(": ").append(v[i]).append("; ");
        return sb.toString();
    }

    /** 裸壁纸文字（walkAdaptiveText 内联）：主打色系文字 + 深底加投影保可读 */
    private static String bgTextStyle(Color dom, double dim) {
        double k = 1.0 - dim;
        Color eff = new Color(Math.min(1, dom.getRed() * k), Math.min(1, dom.getGreen() * k), Math.min(1, dom.getBlue() * k), 1);
        double lum = 0.299 * eff.getRed() + 0.587 * eff.getGreen() + 0.114 * eff.getBlue();
        String shadow = lum < 0.5 ? " -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.65), 8, 0.35, 0, 2);" : "";
        return "-fx-text-fill: " + textPalette(dom, dim)[0] + ";" + shadow;
    }

    /** 全场景文字主题变量：随背景主打色系/蒙版整套覆写（无壁纸回 CSS 默认=原配色） */
    private void updateTextTheme() {
        if (rootPane == null) return;
        String vars = (bgLayer != null && bgLayer.isVisible() && bgDomColor != null)
                ? textThemeVars(bgDomColor, bgDim == null ? 0 : bgDim.getOpacity()) : "";
        if (vars.equals(textThemeMemo)) return;   // 未变化不重设，避免整树样式失效
        textThemeMemo = vars;
        rootPane.setStyle(vars);
        glassDiag("theme " + (vars.isBlank() ? "(default)" : vars));
    }

    /** 自定义背景生效时给背景上的文字上色；恢复默认背景时还原 */
    private void applyAdaptiveTextColors() {
        if (rootPane == null || bgLayer == null) return;
        updateTextTheme();   // 卡片/导航/控件内文字：-tx* 主题变量统一跟随主打色系
        if (!bgLayer.isVisible()) {
            for (var it = adaptiveTextStyles.entrySet().iterator(); it.hasNext(); ) {
                var e = it.next();
                if (e.getKey().getStyle() == null || e.getKey().getStyle().equals(e.getValue())) {
                    e.getKey().setStyle(null);
                }
                it.remove();
            }
            return;
        }
        String style = bgTextStyle(bgDomColor == null ? Color.BLACK : bgDomColor, bgDim.getOpacity());
        walkAdaptiveText(rootPane, style, false);
    }

    private void walkAdaptiveText(javafx.scene.Parent parent, String style, boolean inModule) {
        for (Node n : parent.getChildrenUnmodifiable()) {
            if (n == bgLayer || n == bgDim) continue;
            if (!n.isVisible()) continue;   // 隐藏页面整棵跳过，避免切页时大面积 setStyle 卡顿
            boolean module = inModule || isTextModule(n);
            if (n instanceof Label l) {
                if (module) continue;
                String cur = l.getStyle();
                boolean ours = adaptiveTextStyles.containsKey(l);
                // 只接管无内联样式的文字；被其它逻辑（如状态色）改过样式的跳过
                if (cur == null || cur.isBlank() || (ours && adaptiveTextStyles.get(l).equals(cur))) {
                    l.setStyle(style);
                    adaptiveTextStyles.put(l, style);
                }
            } else if (!module && n instanceof javafx.scene.Parent p) {
                walkAdaptiveText(p, style, false);
            }
        }
    }

    /** 卡片/侧栏等模块 + 自带浅色底条的控件面：文字走 CSS 主题/固定色，不套内联样式（防投影/串色） */
    private static boolean isTextModule(Node n) {
        for (String c : n.getStyleClass()) {
            if (c.contains("card") || c.contains("rail") || c.contains("ver-row") || c.contains("ver-ico")
                    || c.contains("list-cell") || c.contains("dl-cell") || c.contains("chip") || c.contains("toast")
                    || c.contains("titled-pane") || c.contains("log-area") || c.contains("dark-combo")
                    || c.contains("dark-box") || c.contains("logo")) return true;
        }
        return false;
    }

    /** 刷新设置页背景缩略图与状态说明 */
    private void refreshBgPreview() {
        if (bgThumb == null) return;
        boolean ok = false;
        try {
            ok = customBgFile != null && !customBgFile.isBlank()
                    && Files.exists(Path.of(customBgFile));
        } catch (Exception ignored) { }
        if (ok) {
            bgThumb.setImage(new Image(Path.of(customBgFile).toUri().toString()));
            bgThumb.setVisible(true);
            bgThumb.setManaged(true);
            if (bgNote != null) bgNote.setText("已设置：" + Path.of(customBgFile).getFileName());
        } else {
            bgThumb.setImage(null);
            bgThumb.setVisible(false);
            bgThumb.setManaged(false);
            if (bgNote != null && (bgNote.getText() == null || !bgNote.getText().contains("无法加载")))
                bgNote.setText("未设置（使用默认纯色背景）");
        }
    }

    /* ==================== 版本选择（二级页面） ==================== */

        /** 版本选择页：按加载器分组的版本列表 + 刷新 / 导入整合包（删除入口在版本行悬停的垃圾桶上） */
    private VBox buildVerSelPage() {
        Button back = new Button("← 返回首页");
        back.getStyleClass().add("ghost-btn");
        back.setOnAction(e -> showPage(homePage, "首页"));
        Label title = new Label("版本选择");
        title.getStyleClass().add("h2");
        HBox head = new HBox(12, back, title);
        head.setAlignment(Pos.CENTER_LEFT);

        verSelGroups = new VBox(14);
                ScrollPane scroll = new ScrollPane(verSelGroups);
        wrapScroll(scroll);

        scroll.getStyleClass().add("card");
        VBox.setVgrow(scroll, Priority.ALWAYS);

                Button refreshB = new Button("刷新");
        refreshB.getStyleClass().add("ghost-btn");
        refreshB.setOnAction(e -> { refreshInstalled(); refreshVerSelPage(); showToast("已刷新版本列表", false); });
        Button importB = new Button("导入整合包…");
        importB.getStyleClass().add("ghost-btn");
        importB.setOnAction(e -> onImportModpack(importB));
        HBox btns = new HBox(10, refreshB, importB);
        btns.setAlignment(Pos.CENTER_LEFT);

        VBox panel = new VBox(16, head, scroll, btns);
        panel.getStyleClass().addAll("panel", "ring");
        VBox page = new VBox(panel);
        page.setPadding(new Insets(10));
        VBox.setVgrow(panel, Priority.ALWAYS);
        return page;
    }

    /** 重新填充版本分组：NeoForge / Forge / Fabric / 常规版本 */
    private void refreshVerSelPage() {
        verSelGroups.getChildren().clear();
        List<String> installed = mc.getInstalled();
        Map<String, List<String>> groups = new LinkedHashMap<>();
        for (String id : installed) groups.computeIfAbsent(groupOf(id), k -> new ArrayList<>()).add(id);
        String[] order = {"NeoForge", "Forge", "Fabric", "常规版本"};
        String[] letters = {"NF", "F", "FB", "MC"};
        String[] colors = {"#f97316", "#b45309", "#d4a843", "#5cb85c"};
        boolean any = false;
        for (int i = 0; i < order.length; i++) {
            List<String> ids = groups.get(order[i]);
            if (ids == null || ids.isEmpty()) continue;
            any = true;
            Label gt = new Label(order[i] + "（" + ids.size() + "）");
            gt.getStyleClass().add("h2");
            VBox card = new VBox(8, gt);
            card.getStyleClass().add("card");
            for (String id : ids) card.getChildren().add(verSelRow(id, letters[i], colors[i]));
            verSelGroups.getChildren().add(card);
        }
                if (!any) {
            Label empty = new Label("还没有安装任何版本，请到「下载」页获取");
            empty.getStyleClass().addAll("muted");
            verSelGroups.getChildren().add(empty);
        }
        // 残缺版本（安装中断/被外部清理）：列出来让用户看得见、删得掉，不再隐形
        List<String> broken = mc.getBrokenVersions();
        if (!broken.isEmpty()) {
            Label gt = new Label("安装不完整（" + broken.size() + "）");
            gt.getStyleClass().add("h2");
            VBox card = new VBox(8, gt);
            card.getStyleClass().add("card");
            for (String id : broken) card.getChildren().add(verSelRow(id, "!", "#8a939b", true));
            verSelGroups.getChildren().add(card);
        }
    }

    /** 按 id 判断版本所属分组：注意 neoforge 包含 forge，要先判 neoforge。 */
    private String groupOf(String id) {
        String s = id.toLowerCase();
        if (s.contains("neoforge")) return "NeoForge";
        if (s.contains("forge")) return "Forge";
        if (s.contains("fabric")) return "Fabric";
        // 整合包版本以整合包名命名，id 里没有加载器字样，读版本 json 的 yhclLoader 标记
        return switch (loaderTag(id)) {
            case "neoforge" -> "NeoForge";
            case "forge" -> "Forge";
            case "fabric" -> "Fabric";
            default -> "常规版本";
        };
    }

    /** 版本 id → 加载器标签（缓存，避免界面反复读版本 json；refreshInstalled 时重建） */
    private final java.util.Map<String, String> loaderTagCache = new HashMap<>();

    private String loaderTag(String id) {
        if (id == null) return "";
        String t = loaderTagCache.get(id);
        if (t == null) {
            t = mc.getVersionLoader(id);
            loaderTagCache.put(id, t);
        }
        return t;
    }

        /** 单个版本行：彩色图标 + 版本名 + 「正式版 x.y」副标题，点击即选为当前版本；悬停行尾出现垃圾桶可删除 */
    private HBox verSelRow(String id, String letter, String color) {
        return verSelRow(id, letter, color, false);
    }

    /** broken=true 表示残缺版本（缺 <id>.json）：不可选中启动，只提示并允许删除 */
    private HBox verSelRow(String id, String letter, String color, boolean broken) {
        StackPane icon = new StackPane(new Label(letter));
        icon.getStyleClass().add("ver-ico");
        icon.setStyle("-fx-background-color: " + color + ";");
        icon.setPrefSize(38, 38);

        Label name = new Label(verDisplay(id));
        name.getStyleClass().add("ver-row-name");
        String mcVer = broken ? null : mc.getMcVersion(id);
        Label sub = new Label(broken ? "安装不完整：缺少版本定义，无法启动"
                : "正式版 " + (mcVer == null || mcVer.isBlank() ? id : mcVer));
        sub.getStyleClass().addAll("muted", "small");
        VBox text = new VBox(2, name, sub);
        HBox.setHgrow(text, Priority.ALWAYS);   // 把垃圾桶挤到行最右侧

                        // 放大的悬停垃圾桶：实心深色 SVG 图标（不用线框 emoji），悬停灰色圆角底 + 缩放动画
        javafx.scene.shape.SVGPath trash = new javafx.scene.shape.SVGPath();
        trash.setContent("M6 19c0 1.1.9 2 2 2h8c1.1 0 2-.9 2-2V7H6v12zM19 4h-3.5l-1-1h-5l-1 1H5v2h14V4z");
        trash.setFill(Color.web("#22333c"));   // 实心深色，比 muted 淡灰醒目得多
                trash.setStroke(null);
        // 用 scaleX/scaleY 放大（绕图标自身中心缩放）；不能用绕原点的 Scale 变换，
        // 否则图标会被推离按钮中心，出现图标和可点击区域不对齐
        trash.setScaleX(1.3);
        trash.setScaleY(1.3);
        StackPane del = new StackPane(trash);
        final String delBase = "-fx-background-radius: 10px; -fx-padding: 7 12 7 12;";
        final String delHover = "-fx-background-color: rgba(128,128,128,0.45); "
                + "-fx-background-radius: 10px; -fx-padding: 7 12 7 12;";
        del.setStyle(delBase);
        del.setCursor(Cursor.HAND);
        del.setVisible(false);
        del.setManaged(false);
        ScaleTransition delZoom = new ScaleTransition(javafx.util.Duration.millis(150), del);
        del.setOnMouseEntered(e -> {
            del.setStyle(delHover);
            delZoom.stop();
            delZoom.setToX(1.15);
            delZoom.setToY(1.15);
            delZoom.playFromStart();
        });
        del.setOnMouseExited(e -> {
            del.setStyle(delBase);
            delZoom.stop();
            delZoom.setToX(1.0);
            delZoom.setToY(1.0);
            delZoom.playFromStart();
        });
        del.setOnMouseClicked(e -> {
            e.consume();   // 不冒泡成行的「选中版本」点击
            onDeleteVersion(id);
        });

        HBox row = new HBox(12, icon, text, del);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("ver-row");
        row.hoverProperty().addListener((o, was, hover) -> {
            del.setVisible(hover);
            del.setManaged(hover);
        });
                row.setOnMouseClicked(e -> {
            if (broken) {
                showToast("「" + id + "」缺少版本定义，无法启动；请删除后到「下载」页重新安装", true);
                return;
            }
            refreshInstalled();
            installedVerBox.getSelectionModel().select(id);
            showToast("已选择版本：" + id, false);
            showPage(homePage, "首页");
        });
        return row;
    }

    /** 导入整合包：文件选择器 → 复用拖入整合包的安装管线 */
    private void onImportModpack(javafx.scene.Node owner) {
        FileChooser fc = new FileChooser();
        fc.setTitle("选择整合包压缩包（Modrinth .mrpack 或 CurseForge .zip）");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("整合包", "*.mrpack", "*.zip"));
        File f = fc.showOpenDialog(owner.getScene() != null ? owner.getScene().getWindow() : null);
        if (f != null) handleDroppedModpack(f.toPath());
    }

    /* ==================== 左侧导航 ==================== */
    private VBox buildRail() {
        VBox rail = new VBox(10);
        rail.getStyleClass().add("rail");
        rail.setPadding(new Insets(18, 12, 18, 12));
        rail.setPrefWidth(96);
        rail.setMinWidth(96);   // 菜单栏绝不允许被右侧内容压缩
        rail.setAlignment(Pos.TOP_CENTER);

        Label logoText = new Label("YH");
        StackPane logo = new StackPane(logoText);
        logo.getStyleClass().add("logo");
        logo.setPrefSize(56, 56);

        ToggleGroup g = new ToggleGroup();
        g.selectedToggleProperty().addListener((o, ov, nv) -> {
            if (nv == null && ov != null) ov.setSelected(true);
        });
        ToggleButton home = railHome = railBtn("首页", g, true);
        ToggleButton dl = railDl = railBtn("下载", g, false);
        ToggleButton shots = railShots = railBtn("截图", g, false);
        ToggleButton frp = railFrp = railBtn("联机", g, false);
        ToggleButton sett = railSett = railBtn("设置", g, false);
        ToggleButton logs = railLogs = railBtn("日志", g, false);
        home.setOnAction(e -> {
            showPage(homePage, "首页");
            detectLocalGameDir();
            refreshInstalled();
        });
        dl.setOnAction(e -> {
            showPage(downloadHubPage, "下载 / 版本下载");
            refreshDownloadHubMcVersions();   // 进入下载模块时自动刷新游戏版本下拉
            ensureMcVersionChoices();   // 点击下载模块即自动获取游戏版本清单（完成前下拉显示「正在加载」）
            // 默认切到版本下载 Tab 首次自动获取版本列表
            switchDownloadHubTab("versions");
            if (manifestCache.isEmpty()) onLoadManifest();
        });
        shots.setOnAction(e -> {
            showPage(shotsPage, "截图");
            refreshShots();   // 点开截图自动识别截图文件夹并加载截图
        });
        frp.setOnAction(e -> {
            showPage(frpHubPage, "联机");
            refreshDownloadHubMcVersions();   // 进入联机模块时刷新游戏版本下拉（含 VoxLink 页目标版本）
        });
        sett.setOnAction(e -> showPage(settingsPage, "设置"));
        logs.setOnAction(e -> showPage(logsPage, "运行日志"));

        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);
        Label ver = new Label("v0.1.2beta");
        ver.getStyleClass().addAll("muted-2", "small");

        Region gap = new Region();
        gap.setPrefHeight(14);
        rail.getChildren().addAll(logo, gap, home, dl, shots, frp, sett, logs, spacer, ver);
        return rail;
    }

    private ToggleButton railBtn(String text, ToggleGroup g, boolean sel) {
        ToggleButton b = new ToggleButton(text);
        b.getStyleClass().add("rail-btn");
        b.setToggleGroup(g);
        b.setSelected(sel);
        b.setMaxWidth(Double.MAX_VALUE);
        return b;
    }

    /* ==================== 中部容器 ==================== */
    private VBox buildCenter() {
        crumb = new Label("首页");
        crumb.getStyleClass().addAll("muted", "small");
        HBox header = new HBox(crumb);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(2, 6, 0, 6));

        pageHolder = new StackPane();
        VBox.setVgrow(pageHolder, Priority.ALWAYS);

        VBox center = new VBox(10, header, pageHolder);
        return center;
    }

    private void showPage(Pane page, String name) {
        Launcher.diag("showPage -> " + name);
        crumb.setText(name);
        // 页面常驻场景、仅切可见性：节点被摘出场景再挂回时，CSS 计算状态会被清空且约一半概率
        // 不再重算（卡片 padding/背景/字体回落默认，表现为"模块完全透明"），故不再 setAll 摘挂
        for (Node c : pageHolder.getChildren()) {
            if (c != page) {
                c.setVisible(false);
                c.setManaged(false);
            }
        }
        if (!pageHolder.getChildren().contains(page)) pageHolder.getChildren().add(page);
        page.setVisible(true);
        page.setManaged(true);
        currentPage = page;
        animateIn(page);
        syncRail(page);
        applyAdaptiveTextColors();   // 新页面背景上的文字跟随背景配色
        if (page == settingsPage) updateGlMemLabels();
        if (page == downloadPage) { refreshDownload(); hideBall(); }
        else if (mc.monitor.running()) showBall();   // 离开详情页时若下载仍在进行，重新弹出悬浮球
        syncDlTicker();
        // frp 页面打开时启动状态刷新，离开就停
        if (frpTicker != null) {
            if (page == frpPage) {
                refreshFrpUi();
                if (frpTicker.getStatus() != Animation.Status.RUNNING) frpTicker.play();
            } else if (frpTicker.getStatus() == Animation.Status.RUNNING) {
                frpTicker.stop();
            }
        }
    }

    /** 左侧导航高亮实时跟随右侧页面：二级页面归到所属分区 */
    private void syncRail(Pane page) {
        ToggleButton b;
        if (page == downloadHubPage || page == modDlPage || page == downloadPage
                || page == versionsPage || page == detailPage) b = railDl;
        else if (page == shotsPage) b = railShots;
        else if (page == frpHubPage || page == frpPage || page == directPage
                || page == terraPage || page == voxPage) b = railFrp;
        else if (page == settingsPage) b = railSett;
        else if (page == logsPage) b = railLogs;
        else b = railHome;   // 首页、版本选择、版本设置等
        if (b != null && b.getToggleGroup() != null
                && b.getToggleGroup().getSelectedToggle() != b) {
            b.setSelected(true);
        }
    }

    /** 页面入场动画：淡入 + 轻微上滑（220ms）。只做入场，出场瞬切。 */
    private void animateIn(Node n) {
        if (pageAnim != null) {
            pageAnim.stop();
            resetNode(animNode);   // stop 不触发 onFinished，须手动复位防残影
        }
        n.setOpacity(0);
        n.setTranslateY(12);
        applyGlassStyle();   // 先按最终位置铺好玻璃，动画期间不再改动（画布随卡片一起动）
        // 整页含大量圆角/投影，动画期间逐帧重绘代价大：缓存成纹理只栅格化一次
        n.setCache(true);
        n.setCacheHint(CacheHint.SPEED);
        FadeTransition ft = new FadeTransition(Duration.millis(220), n);
        ft.setToValue(1);
        ft.setInterpolator(Interpolator.EASE_OUT);
        TranslateTransition tt = new TranslateTransition(Duration.millis(220), n);
        tt.setToY(0);
        tt.setInterpolator(Interpolator.EASE_OUT);
        ParallelTransition pt = new ParallelTransition(ft, tt);
        pt.setOnFinished(e -> {
            resetNode(n);
            if (pageAnim == pt) { pageAnim = null; animNode = null; }
            applyGlassStyle();   // 动画位移归零后校正玻璃背景（缓存命中时零开销）
        });
        pageAnim = pt;
        animNode = n;
        pt.play();
    }

    /** 复位节点动画属性，避免 opacity/translate 残值影响布局 */
    private void resetNode(Node n) {
        if (n != null) {
            n.setOpacity(1);
            n.setTranslateY(0);
            n.setCache(false);
            n.setCacheHint(CacheHint.DEFAULT);
        }
    }

    /* ==================== 下载监控（悬浮球 + 详情页） ==================== */

    /** 右下角悬浮球：进度环 + 下载图标，点击进入下载详情二级页 */
    private StackPane buildDownloadBall() {
        Arc track = new Arc(0, 0, 24, 24, 90, -360);
        track.setType(ArcType.OPEN);
        track.setFill(Color.TRANSPARENT);
        track.setStroke(Color.rgb(255, 255, 255, 0.4));
        track.setStrokeWidth(3.4);

        ballRing = new Arc(0, 0, 24, 24, 90, 0);
        ballRing.setType(ArcType.OPEN);
        ballRing.setFill(Color.TRANSPARENT);
        ballRing.setStroke(Color.WHITE);
        ballRing.setStrokeWidth(3.4);
        ballRing.setStrokeLineCap(StrokeLineCap.ROUND);

        ballIcon = new SVGPath();
        ballIcon.setContent(ICON_DOWNLOAD);
        ballIcon.setFill(null);
        ballIcon.setStroke(Color.WHITE);
        ballIcon.setStrokeWidth(1.9);
        ballIcon.setStrokeLineCap(StrokeLineCap.ROUND);
        ballIcon.setStrokeLineJoin(StrokeLineJoin.ROUND);
        // 图标坐标系是 24x24 网格，视觉中心约在 (12, 10.5)；平移后让它与圆环中心 (0,0) 重合，
        // 避免 StackPane 按各自 layoutBounds 居中时出现上下错位。
        ballIcon.setTranslateX(-12);
        ballIcon.setTranslateY(-10.5);

        // 用 Group 承载轨道/进度环/图标，三者共用原点，StackPane 只负责把 Group 居中。
        Group glyph = new Group(track, ballRing, ballIcon);
        StackPane ball = new StackPane(glyph);
        ball.getStyleClass().add("download-ball");
        ball.setMinSize(58, 58);
        ball.setPrefSize(58, 58);
        ball.setMaxSize(58, 58);
        ball.setCursor(Cursor.HAND);
        ball.setVisible(false);
        ball.setOnMouseClicked(e -> {
            showPage(downloadPage, "下载详情");
            refreshDownload();
        });
        Tooltip.install(ball, new Tooltip("查看下载进度"));
        return ball;
    }

    /** 下载详情页：全过程可视化（总进度、速度、耗时、失败数、逐文件状态、实时输出） */
    private VBox buildDownloadPage() {
        Button back = new Button("← 返回首页");
        back.getStyleClass().add("ghost-btn");
        back.setOnAction(e -> showPage(homePage, "首页"));
        Label title = new Label("下载详情");
        title.getStyleClass().add("h2");
        dlPhase = new Label("尚未开始");
        dlPhase.getStyleClass().addAll("muted", "small");
        HBox head = new HBox(12, back, title, dlPhase);
        head.setAlignment(Pos.CENTER_LEFT);

        dlProg = new ProgressBar(0);
        dlProg.setMaxWidth(Double.MAX_VALUE);
        dlStats = new Label("-");
        dlStats.getStyleClass().addAll("muted", "small");
        dlSpeed = new Label("");
        dlSpeed.getStyleClass().addAll("muted", "small");
        dlFail = new Label("");
        dlFail.getStyleClass().addAll("small");
        dlFail.setStyle("-fx-text-fill: #ff8a7a;");
        HBox stats = new HBox(16, dlStats, dlSpeed, dlFail);
        stats.setAlignment(Pos.CENTER_LEFT);
        VBox summary = new VBox(8, dlProg, stats);
        summary.getStyleClass().add("card");

        dlNote = new Label("");
        dlNote.getStyleClass().addAll("muted", "small");
        dlStopBtn = new Button("暂停下载");
        dlStopBtn.getStyleClass().add("ghost-btn");
        dlStopBtn.setDisable(true);
        dlStopBtn.setOnAction(e -> {
            boolean paused = mc.monitor.togglePause();
            dlStopBtn.setText(paused ? "继续下载" : "暂停下载");
            refreshDownload();
        });
        dlCancelBtn = new Button("取消下载");
        dlCancelBtn.getStyleClass().add("ghost-btn");
        dlCancelBtn.setDisable(true);
        dlCancelBtn.setOnAction(e -> {
            mc.monitor.cancelAndDelete();
            dlStopBtn.setDisable(true);
            dlCancelBtn.setDisable(true);
            refreshDownload();
        });
        HBox noteRow = new HBox(12, dlNote, dlStopBtn, dlCancelBtn);
        noteRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(dlNote, Priority.ALWAYS);

        dlList = new ListView<>();
        dlList.setPlaceholder(new Label("还没有下载任务"));
        dlList.setCellFactory(lv -> new ListCell<>() {
            { getStyleClass().add("dl-cell"); }

            @Override
            protected void updateItem(Integer idx, boolean empty) {
                super.updateItem(idx, empty);
                if (empty || idx == null) {
                    setText(null);
                    setStyle("");
                    return;
                }
                DownloadMonitor m = mc.monitor;
                String mark;
                String color;
                switch (m.state(idx)) {
                    case DOWNLOADING -> { mark = "▼"; color = "#3ddc97"; }
                    case DONE -> { mark = "✓"; color = "#7c95a1"; }
                    case FAILED -> { mark = "✕"; color = "#ff8a7a"; }
                    default -> { mark = "·"; color = "#526671"; }
                }
                String note = m.itemNote(idx);
                setText(mark + "  " + m.name(idx) + (note.isBlank() ? "" : "   —— " + note));
                setStyle("-fx-text-fill: " + color + ";");
            }
        });
        VBox.setVgrow(dlList, Priority.ALWAYS);

        VBox card = new VBox(8, noteRow, dlList);
        card.getStyleClass().add("card");
        VBox.setVgrow(card, Priority.ALWAYS);

        VBox panel = new VBox(14, head, summary, card);
        panel.getStyleClass().add("panel");
        VBox.setVgrow(panel, Priority.ALWAYS);
        return panel;
    }

    /** 下载开始：弹出悬浮球 + 启动刷新定时器 */
    private void onDownloadStart(String title) {
        mc.monitor.begin(title);
        dlListCount = -1;
        dlSig = -1;
        if (dlList != null) dlList.getItems().clear();
        dlStopBtn.setDisable(false);
        dlStopBtn.setText("暂停下载");
        dlCancelBtn.setDisable(false);
        showBall();
        refreshDownload();
        syncDlTicker();
    }

    /** 下载结束：悬浮球切到结果态并延迟淡出，详情页保留最后一次结果 */
    private void onDownloadEnd() {
        dlStopBtn.setDisable(true);
        dlCancelBtn.setDisable(true);
        dlStopBtn.setText("暂停下载");
        if (mc.monitor.consumeDeleteRequest()) deleteCancelledFiles();
        refreshDownload();
        syncDlTicker();
        ballAutoHide.playFromStart();   // 6 秒后收球，避免长期挡住画面
    }

    /** 取消下载后删除本次会话下载的文件（后台删除，避免卡界面） */
    private void deleteCancelledFiles() {
        List<java.nio.file.Path> files = mc.monitor.drainSessionFiles();
        if (files.isEmpty()) return;
        Thread t = new Thread(() -> {
            int n = 0;
            for (java.nio.file.Path p : files) {
                try {
                    if (java.nio.file.Files.deleteIfExists(p)) n++;
                } catch (Exception ignored) { }
            }
            final int deleted = n;
            Platform.runLater(() ->
                    mc.monitor.setNote("已删除本次下载的 " + deleted + " 个文件"));
        }, "yhcl-dl-cleanup");
        t.setDaemon(true);
        t.start();
    }

    private void showBall() {
        ballAutoHide.stop();
        if (downloadBall.isVisible() && downloadBall.getOpacity() >= 1) return;
        downloadBall.setVisible(true);
        downloadBall.setOpacity(0);
        downloadBall.setScaleX(0.4);
        downloadBall.setScaleY(0.4);
        ScaleTransition st = new ScaleTransition(Duration.millis(220), downloadBall);
        st.setToX(1);
        st.setToY(1);
        st.play();
        FadeTransition ft = new FadeTransition(Duration.millis(220), downloadBall);
        ft.setToValue(1);
        ft.play();
    }

    private void hideBall() {
        if (!downloadBall.isVisible()) return;
        FadeTransition ft = new FadeTransition(Duration.millis(260), downloadBall);
        ft.setToValue(0);
        ft.setOnFinished(e -> downloadBall.setVisible(false));
        ft.play();
    }

    /** 只有正在下载或正停在详情页时才需要刷新 */
    private void syncDlTicker() {
        if (dlTicker == null) return;
        boolean need = mc.monitor.running()
                || currentPage == downloadPage;
        if (need) {
            if (dlTicker.getStatus() != Animation.Status.RUNNING) dlTicker.play();
        } else if (dlTicker.getStatus() == Animation.Status.RUNNING) {
            dlTicker.stop();
        }
    }

    /** 把监控器当前状态刷到悬浮球和详情页上（每 400ms 一次） */
    private void refreshDownload() {
        DownloadMonitor m = mc.monitor;
        int total = m.total();
        int done = m.doneCount();
        int fail = m.failCount();
        int finished = done + fail;
        double p = m.progress();

        dlProg.setProgress(p);
        ballRing.setLength(-360 * p);

        long elapsed = m.elapsedMs();
        // 当前速度：已下字节的实时增量做 EMA 平滑；暂停/新会话/进度回退时清零重来
        long live = m.liveBytes();
        long nowNs = System.nanoTime();
        if (!m.running() || live < dlLastLive || dlLastTickNs == 0) {
            dlLastLive = live;
            dlLastTickNs = nowNs;
            dlSmoothBps = 0;
        } else {
            double dt = (nowNs - dlLastTickNs) / 1e9;
            if (dt >= 0.2) {
                double inst = (live - dlLastLive) / dt;
                dlSmoothBps = dlSmoothBps <= 0 ? inst : dlSmoothBps * 0.6 + inst * 0.4;
                dlLastLive = live;
                dlLastTickNs = nowNs;
            }
        }
        double mbps = dlSmoothBps / 1048576.0;
        // 预计剩余 = （预计总大小 - 已下字节）/ 当前速度
        String eta = "";
        if (m.running() && dlSmoothBps > 0) {
            long remain = Math.max(0, m.estimatedTotalBytes() - live);
            eta = " · 预计剩余 " + fmtDur((long) (remain / dlSmoothBps * 1000));
        }
        dlStats.setText("进度 " + finished + " / " + total
                + "（" + Math.round(p * 100) + "%） · 并发 " + m.activeCount()
                + " · 已下载 " + fmtSize(live));
        dlSpeed.setText(String.format("速度 %.2f MB/s · 用时 %s%s", mbps, fmtDur(elapsed), eta));
        dlFail.setText(fail > 0 ? "失败 " + fail : "");
        dlPhase.setText(m.paused() ? "已暂停" : m.phaseText());
        dlNote.setText(m.note());

        switch (m.phase()) {
            case DONE -> setBallState(ICON_DONE, "download-ball-done");
            case FAILED, ABORTED -> setBallState(ICON_FAIL, "download-ball-fail");
            default -> setBallState(ICON_DOWNLOAD, null);
        }

        if (dlListCount != total) {
            dlListCount = total;
            List<Integer> rows = new ArrayList<>(total);
            for (int i = 0; i < total; i++) rows.add(i);
            dlList.getItems().setAll(rows);
            dlSig = -1;
        } else if (!dlList.getItems().isEmpty()) {
            // 几千行时 refresh() 不便宜：只有状态真的变了才重画可见行
            long sig = ((long) finished << 32) ^ (m.activeCount() * 131L + total);
            if (sig != dlSig) {
                dlSig = sig;
                dlList.refresh();
            }
        }
    }

    /** 悬浮球当前状态样式（只在真变化时改样式，避免每 400ms 触发全场景 CSS 失效） */
    private String ballStateKey = "";

    private void setBallState(String icon, String extraClass) {
        String key = extraClass == null ? "" : extraClass;
        if (!key.equals(ballStateKey)) {
            ballStateKey = key;
            if (extraClass == null) downloadBall.getStyleClass().setAll("download-ball");
            else downloadBall.getStyleClass().setAll("download-ball", extraClass);
        }
        ballIcon.setContent(icon);
    }

    private static String fmtDur(long ms) {
        long s = Math.max(0, ms / 1000);
        if (s >= 3600) return (s / 3600) + " 小时 " + (s % 3600 / 60) + " 分";
        if (s >= 60) return (s / 60) + " 分 " + (s % 60) + " 秒";
        return s + " 秒";
    }

    /* ==================== 首页 ==================== */
    private VBox buildHome() {
        Label title = new Label("YHCL 启动器");
        title.getStyleClass().add("h1");
        Label subtitle = new Label("简洁的 Minecraft: Java Edition 启动器");
        subtitle.getStyleClass().add("muted");
        VBox head = new VBox(6, title, subtitle);

        /* ---- 账户卡片 ---- */
        Label accTitle = new Label("账户");
        accTitle.getStyleClass().add("h2");
        accountLabel = new Label("未登录（离线模式）");
        accountLabel.getStyleClass().add("muted");

        msLoginBtn = new Button("Microsoft 正版登录");
        msLoginBtn.getStyleClass().add("accent-btn");
        msLoginBtn.setOnAction(e -> onMicrosoftLogin());
        msLogoutBtn = new Button("注销");
        msLogoutBtn.getStyleClass().add("ghost-btn");
        msLogoutBtn.setVisible(false);
        msLogoutBtn.setManaged(false);
        msLogoutBtn.setOnAction(e -> onMicrosoftLogout());
        HBox accBtns = new HBox(10, msLoginBtn, msLogoutBtn);
        accBtns.setAlignment(Pos.CENTER_LEFT);

        Label offlineTip = new Label("离线玩家名（未登录时使用）");
        offlineTip.getStyleClass().addAll("muted", "small");
        userField = new TextField("Steve");
        userField.getStyleClass().add("dark-box");
        userField.setPromptText("例如 Steve");
        userField.textProperty().addListener((o, a, b) -> saveConfig());

        VBox accountCard = new VBox(10, accTitle, accountLabel, accBtns, offlineTip, userField);
        accountCard.getStyleClass().add("card");

        /* ---- 版本卡片 ---- */
        Label verTitle = new Label("游戏版本");
        verTitle.getStyleClass().add("h2");
        installedVerBox = new ComboBox<>();
        installedVerBox.getStyleClass().add("dark-combo");
        installedVerBox.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(installedVerBox, Priority.ALWAYS);
        installedVerBox.setCellFactory(lv -> new ListCell<>() {
            @Override protected void updateItem(String id, boolean empty) {
                super.updateItem(id, empty);
                if (empty || id == null) { setText(null); setGraphic(null); return; }
                setText(verDisplay(id));
            }
        });
        installedVerBox.setButtonCell(new ListCell<>() {
            @Override protected void updateItem(String id, boolean empty) {
                super.updateItem(id, empty);
                if (empty || id == null) { setText(null); setGraphic(null); return; }
                setText(verDisplay(id));
            }
        });
        installedVerBox.getSelectionModel().selectedItemProperty()
                .addListener((ob, o, n) -> updateSelLabel());
        // 版本选择 / 版本设置：两个静态按钮，内容点进二级菜单后才出现
        verSelBtn = new Button("版本选择");
        verSelBtn.getStyleClass().addAll("ghost-btn", "ring");
        verSelBtn.setPrefWidth(120);
        verSelBtn.setOnAction(e -> { showPage(verSelPage, "版本选择"); refreshVerSelPage(); });
        Button verSetBtn = new Button("版本设置");
        verSetBtn.getStyleClass().addAll("ghost-btn", "ring");
        verSetBtn.setPrefWidth(120);
        verSetBtn.setOnAction(e -> { showPage(verSetPage, "版本设置"); refreshVerSettings(); });
        HBox verRow = new HBox(10, verSelBtn, verSetBtn);
        verRow.setAlignment(Pos.CENTER_LEFT);
        selLabel = new Label("");
        selLabel.getStyleClass().addAll("muted", "small");
        VBox versionCard = new VBox(10, verTitle, verRow, selLabel);
        versionCard.getStyleClass().add("card");

        /* ---- 启动区 ---- */
        launchBtn = new Button("启 动 游 戏");
        launchBtn.getStyleClass().add("accent-btn");
        launchBtn.setMaxWidth(Double.MAX_VALUE);
        launchBtn.setPrefHeight(54);
        launchBtn.setStyle("-fx-font-size: 17px;");
        launchBtn.setOnAction(e -> onLaunch());
        launchProg = new ProgressBar(0);
        launchProg.setMaxWidth(Double.MAX_VALUE);
        statusLabel = new Label("就绪");
        statusLabel.getStyleClass().addAll("muted", "small");

        VBox panel = new VBox(18, head, accountCard, versionCard, launchBtn, launchProg, statusLabel);
        panel.getStyleClass().add("panel");

        ScrollPane scroll = new ScrollPane(panel);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);   // 首页彻底不显示滚动条（滚轮仍可滚动）

        trackGlassScroll(scroll);

        VBox page = new VBox(scroll);
        VBox.setVgrow(scroll, Priority.ALWAYS);
                return page;
    }

    /** 滚动条自动隐藏：滚动时隐约浮现（半透明），停滚 600ms 后淡出（macOS 风格） */
    private void makeScrollbarAutoHide(ScrollPane sp) {
        sp.skinProperty().addListener((o, ov, nv) -> {
            if (nv != null) attachAllBars(sp);
        });
        if (sp.getSkin() != null) attachAllBars(sp);
    }

    private void attachAllBars(ScrollPane sp) {
        for (Node n : sp.lookupAll(".scroll-bar")) {
            if (n instanceof ScrollBar bar && bar.getOpacity() > 0.01) attachAutoHide(sp, bar);
        }
    }

    private void attachAutoHide(ScrollPane sp, ScrollBar bar) {
        bar.setOpacity(0);
        FadeTransition fadeIn = new FadeTransition(Duration.millis(1), bar);
        fadeIn.setToValue(1.0);
        FadeTransition fadeOut = new FadeTransition(Duration.millis(300), bar);
        fadeOut.setToValue(0);
        PauseTransition delay = new PauseTransition(Duration.millis(600));
        delay.setOnFinished(e -> fadeOut.playFromStart());
        Runnable onScroll = () -> {
            fadeOut.stop();
            delay.stop();
            fadeIn.playFromStart();
            delay.playFromStart();
        };
                sp.vvalueProperty().addListener((o, ov, nv) -> onScroll.run());
        sp.hvalueProperty().addListener((o, ov, nv) -> onScroll.run());
    }

    /** 滚动容器统一处理：自适应宽度、隐藏横向滚动条、滚动条自动隐藏、玻璃滚动联动 */
    private void wrapScroll(ScrollPane scroll) {
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        makeScrollbarAutoHide(scroll);
        trackGlassScroll(scroll);
    }

    /* ==================== 截图（游戏内截图管理） ==================== */

    /** 自动识别截图目录：当前版本（含隔离目录）优先，再加全局与其它版本的 screenshots 目录 */
    private List<Path> shotsDirs() {
        List<Path> dirs = new ArrayList<>();
        String id = currentVersionId();
        if (id != null && isolationFor(id)) {
            Path d = mc.gameDir.resolve("versions").resolve(id).resolve("screenshots");
            if (Files.isDirectory(d)) dirs.add(d);
        }
        Path global = mc.gameDir.resolve("screenshots");
        if (Files.isDirectory(global) && !dirs.contains(global)) dirs.add(global);
        File[] vs = mc.gameDir.resolve("versions").toFile().listFiles();
        if (vs != null) {
            for (File v : vs) {
                if (!v.isDirectory()) continue;
                Path d = v.toPath().resolve("screenshots");
                if (Files.isDirectory(d) && !dirs.contains(d)) dirs.add(d);
            }
        }
        return dirs;
    }

    /** 点开截图自动识别截图文件夹并加载全部截图（新→旧排列） */
    private void refreshShots() {
        shotsStatus.setText("正在识别截图目录并加载截图…");
        pool.execute(() -> {
            List<Path> dirs = shotsDirs();
            List<Path> files = new ArrayList<>();
            for (Path d : dirs) {
                File[] fs = d.toFile().listFiles();
                if (fs == null) continue;
                for (File f : fs) {
                    String n = f.getName().toLowerCase(Locale.ROOT);
                    if (f.isFile() && (n.endsWith(".png") || n.endsWith(".jpg")
                            || n.endsWith(".jpeg") || n.endsWith(".webp") || n.endsWith(".bmp")))
                        files.add(f.toPath());
                }
            }
            files.sort((a, b) -> Long.compare(b.toFile().lastModified(), a.toFile().lastModified()));
            Platform.runLater(() -> renderShots(dirs, files));
        });
    }

    /** 渲染缩略图网格 */
    private void renderShots(List<Path> dirs, List<Path> files) {
        shotsList.clear();
        shotsList.addAll(files);
        shotsGrid.getChildren().clear();
        for (Path p : files) shotsGrid.getChildren().add(shotCell(p));
        if (files.isEmpty()) {
            Label empty = new Label(dirs.isEmpty()
                    ? "还没有识别到截图文件夹。启动一次游戏生成 screenshots 目录后，这里会自动显示游戏截图"
                    : "截图文件夹里还没有截图。进入游戏按 F2 截图后点「刷新」查看");
            empty.getStyleClass().addAll("muted", "small");
            empty.setWrapText(true);
            empty.setMaxWidth(560);
            StackPane box = new StackPane(empty);
            box.setPadding(new Insets(30, 10, 30, 10));
            shotsGrid.getChildren().add(box);
        }
        String where = dirs.isEmpty() ? "未找到截图目录（当前版本隔离目录 / 全局 / 各版本）"
                : dirs.size() == 1 ? "截图目录：" + dirs.get(0)
                : "截图目录：" + dirs.get(0) + " 等 " + dirs.size() + " 个";
        shotsStatus.setText(where + "  ·  共 " + files.size() + " 张截图  ·  点击缩略图查看大图");
    }

    /** 单张截图缩略图卡片 */
    private Node shotCell(Path p) {
        ImageView iv = new ImageView(new Image(p.toUri().toString(), 240, 135, true, true, true));
        iv.setFitWidth(240);
        iv.setFitHeight(135);
        iv.setPreserveRatio(true);
        iv.setSmooth(true);
        Rectangle clip = new Rectangle(240, 135);
        clip.setArcWidth(16);
        clip.setArcHeight(16);
        iv.setClip(clip);
        StackPane wrap = new StackPane(iv);
        wrap.setMinSize(240, 135);
        wrap.setMaxSize(240, 135);
        wrap.setStyle("-fx-background-color: rgba(255,255,255,0.06); -fx-background-radius: 8;");

        Label name = new Label(p.getFileName().toString());
        name.getStyleClass().addAll("muted", "small");
        name.setMaxWidth(240);
        Label meta = new Label(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(p.toFile().lastModified())
                + "  ·  " + fmtSize(p.toFile().length()));
        meta.getStyleClass().addAll("muted-2", "small");

        VBox cell = new VBox(6, wrap, name, meta);
        cell.setPadding(new Insets(8));
        cell.getStyleClass().add("mod-cell-card");
        cell.setCursor(Cursor.HAND);
        cell.setOnMouseClicked(e -> openShotViewer(p));
        return cell;
    }

    /** 大图查看器（root 上的全屏遮罩层） */
    private StackPane shotViewer;
    private ImageView shotViewerImg;
    private Label shotViewerName;
    private Path shotViewerPath;

    private void openShotViewer(Path p) {
        if (shotViewer == null) buildShotViewer();
        showShotAt(shotsList.indexOf(p));
    }

    private void buildShotViewer() {
        shotViewerImg = new ImageView();
        shotViewerImg.setPreserveRatio(true);
        shotViewerImg.setSmooth(true);
        shotViewerImg.fitWidthProperty().bind(rootPane.widthProperty().multiply(0.78));
        shotViewerImg.fitHeightProperty().bind(rootPane.heightProperty().multiply(0.70));

        shotViewerName = new Label();
        shotViewerName.getStyleClass().addAll("muted", "small");

        Button prev = new Button("‹ 上一张");
        prev.getStyleClass().add("ghost-btn");
        prev.setOnAction(e -> showShotAt(shotsList.indexOf(shotViewerPath) - 1));
        Button next = new Button("下一张 ›");
        next.getStyleClass().add("ghost-btn");
        next.setOnAction(e -> showShotAt(shotsList.indexOf(shotViewerPath) + 1));
        Button openDir = new Button("打开所在文件夹");
        openDir.getStyleClass().add("ghost-btn");
        openDir.setOnAction(e -> {
            Path dir = shotViewerPath == null ? null : shotViewerPath.getParent();
            if (dir == null) return;
            pool.submit(() -> {
                try {
                    Desktop.getDesktop().open(dir.toFile());
                } catch (Exception ex) {
                    Platform.runLater(() -> showToast("打开目录失败：" + ex.getMessage(), true));
                }
            });
        });
        Button del = new Button("删除");
        del.getStyleClass().add("ghost-btn");
        del.setOnAction(e -> deleteShot(shotViewerPath));
        Button close = new Button("关闭");
        close.getStyleClass().add("ghost-btn");
        close.setOnAction(e -> closeShotViewer());

        HBox bar = new HBox(10, shotViewerName, new Region(), prev, next, openDir, del, close);
        HBox.setHgrow(bar.getChildren().get(1), Priority.ALWAYS);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(10, 16, 10, 16));
        bar.setStyle("-fx-background-color: rgba(20,28,32,0.85); -fx-background-radius: 12;");

        VBox content = new VBox(10, shotViewerImg, bar);
        content.setAlignment(Pos.CENTER);

        Region backdrop = new Region();
        backdrop.setStyle("-fx-background-color: rgba(0,0,0,0.78);");
        backdrop.setOnMouseClicked(e -> closeShotViewer());

        shotViewer = new StackPane(backdrop, content);
        shotViewer.setVisible(false);
        shotViewer.setManaged(false);
        StackPane.setMargin(content, new Insets(20));
        rootPane.getChildren().add(shotViewer);
    }

    /** 显示 shotsList 第 idx 张大图（越界自动关闭） */
    private void showShotAt(int idx) {
        if (idx < 0 || idx >= shotsList.size()) {
            closeShotViewer();
            return;
        }
        Path p = shotsList.get(idx);
        shotViewerPath = p;
        shotViewerImg.setImage(new Image(p.toUri().toString(), true));
        shotViewerName.setText(p.getFileName() + "  ·  " + fmtSize(p.toFile().length()));
        shotViewer.setVisible(true);
        shotViewer.setManaged(true);
        shotViewer.toFront();
    }

    private void closeShotViewer() {
        if (shotViewer == null) return;
        shotViewer.setVisible(false);
        shotViewer.setManaged(false);
        shotViewerImg.setImage(null);
        shotViewerPath = null;
    }

    /** 删除截图：确认后删文件并刷新网格 */
    private void deleteShot(Path p) {
        if (p == null) return;
        Alert c = new Alert(Alert.AlertType.CONFIRMATION);
        c.setTitle("删除截图");
        c.setHeaderText(null);
        c.setContentText("确定删除截图 " + p.getFileName() + " 吗？删除后无法恢复。");
        c.showAndWait().filter(r -> r == ButtonType.OK).ifPresent(r -> {
            try {
                Files.deleteIfExists(p);
                showToast("已删除：" + p.getFileName(), false);
                log("截图已删除：" + p);
            } catch (Exception ex) {
                showToast("删除失败：" + ex.getMessage(), true);
            }
            closeShotViewer();
            refreshShots();
        });
    }

    /* ==================== Mod 管理（嵌入版本设置页） ==================== */

    private static String fmtSize(long bytes) {
        if (bytes >= 1 << 20) return String.format("%.1f MB", bytes / 1048576.0);
        if (bytes >= 1 << 10) return (bytes / 1024) + " KB";
        return bytes + " B";
    }

    /** 当前选中版本实际生效的 mods 目录 */
    private Path effectiveModsDir() {
        return modsDirFor(currentVersionId());
    }

    /** 指定版本实际生效的 mods 目录：开了版本隔离用 versions/<id>/mods，否则全局 mods */
    private Path modsDirFor(String id) {
        if (isolationFor(id))
            return mc.gameDir.resolve("versions").resolve(id).resolve("mods");
        return mc.gameDir.resolve("mods");
    }

    /** 该版本是否开启版本隔离：版本设置三态（on/off/global），global 跟随设置页「启动选项」的全局开关 */
    private boolean isolationFor(String id) {
        String v = id == null ? "global" : verSetting(id).isolation;
        if ("on".equals(v)) return true;
        if ("off".equals(v)) return false;
        return globalIsolation;
    }

    private void refreshMods() {
        Path dir = effectiveModsDir();
        List<MinecraftService.ModEntry> mods = mc.listMods(dir);
        modList.getItems().setAll(mods);
        long enabled = mods.stream().filter(MinecraftService.ModEntry::enabled).count();
        modDirLabel.setText("目录：" + dir
                + "  ·  共 " + mods.size() + " 个（启用 " + enabled + "）  ·  支持把 .jar 或 .mrpack/.zip 整合包拖进窗口直接安装");
    }

    private void onAddMod() {
        FileChooser fc = new FileChooser();
        fc.setTitle("选择 Mod 文件（.jar，可多选）");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Mod 文件", "*.jar"));
        List<File> files = fc.showOpenMultipleDialog(pageHolder.getScene().getWindow());
        if (files == null || files.isEmpty()) return;
        int ok = 0;
        StringBuilder errs = new StringBuilder();
        for (File f : files) {
            try {
                mc.addMod(f.toPath(), effectiveModsDir());
                ok++;
                log("已添加 mod：" + f.getName());
            } catch (Exception ex) {
                errs.append(f.getName()).append("（").append(ex.getMessage()).append("）");
            }
        }
        refreshMods();
        modStatus.setText(errs.isEmpty()
                ? "已添加 " + ok + " 个 mod"
                : "添加 " + ok + "/" + files.size() + " 个；失败：" + errs);
    }

    /* ==================== 拖拽 .jar / 整合包压缩包自动安装 ==================== */

    /** 把 .jar 或整合包压缩包（.mrpack/.zip）拖到窗口任意位置，自动安装。 */
    private void installModDrop(Scene scene) {
        scene.addEventFilter(DragEvent.DRAG_OVER, e -> {
            if (hasDroppable(e.getDragboard())) {
                e.acceptTransferModes(TransferMode.COPY);
                e.consume();
            }
        });
        scene.addEventFilter(DragEvent.DRAG_DROPPED, e -> {
            List<File> jars = jarFiles(e.getDragboard());
            List<File> packs = packFiles(e.getDragboard());
            if (jars.isEmpty() && packs.isEmpty()) return;
            boolean ok = jars.isEmpty() || handleDroppedMods(jars);
            for (File p : packs) handleDroppedModpack(p.toPath());
            e.setDropCompleted(ok);
            e.consume();
        });
    }

    /**
     * 把拖入的 .jar 复制到当前游戏目录的 mods/ 下。
     * 返回是否至少成功添加了一个。
     */
    boolean handleDroppedMods(List<File> jars) {
        int ok = 0;
        StringBuilder errs = new StringBuilder();
        for (File f : jars) {
            try {
                mc.addMod(f.toPath(), effectiveModsDir());
                ok++;
                log("拖入添加 mod：" + f.getName());
            } catch (Exception ex) {
                if (errs.length() > 0) errs.append("；");
                errs.append(f.getName()).append("（").append(ex.getMessage()).append("）");
            }
        }
        refreshMods();
        String where = currentVersionId() == null ? "" : "（版本 " + currentVersionId() + "）";
        String msg = errs.isEmpty()
                ? "已添加 " + ok + " 个 mod"
                : "添加 " + ok + "/" + jars.size() + " 个；失败：" + errs;
        if (currentPage == verSetPage) modStatus.setText(msg);
        if (ok > 0) showToast("已添加 " + ok + " 个 mod 到 mods 文件夹" + where, false);
        else showToast("添加失败：" + errs, true);
        return ok > 0;
    }

    /** 拖入整合包压缩包：解析并自动下载安装（接入下载监控悬浮球）。支持 Modrinth .mrpack 与 CurseForge manifest.json 的 zip */
    private void handleDroppedModpack(Path archive) {
        if (!MinecraftService.looksLikeModpack(archive)) {
            showToast("无法识别整合包格式：需要 .mrpack，或含 modrinth.index.json / manifest.json 的 zip", true);
            return;
        }
        if (mc.monitor.running()) {
            showToast("已有下载任务进行中，请先等待完成或停止", true);
            return;
        }
        String fileName = archive.getFileName().toString();
        onDownloadStart("整合包安装：" + fileName);
        log("拖入整合包：" + archive);
        mc.installModpack(archive, this::log).whenComplete((res, ex) -> Platform.runLater(() -> {
            onDownloadEnd();
            if (ex != null) {
                Throwable c = ex.getCause() != null ? ex.getCause() : ex;
                String msg = c.getMessage() == null ? c.toString() : c.getMessage();
                showToast("整合包安装失败：" + msg, true);
                log("整合包安装失败：" + msg);
                return;
            }
            String msg = "整合包安装完成：下载 " + res.installed() + " 个"
                    + (res.failed() > 0 ? "，失败 " + res.failed() + " 个" : "")
                    + "，overrides " + res.overrides() + " 个";
            if (res.versionId() != null && !res.versionId().isBlank()) {
                msg += "；已安装版本 " + res.versionId();
                enableIsolationFor(res.versionId());   // 整合包默认开版本隔离
                refreshInstalled();
                installedVerBox.getSelectionModel().select(res.versionId());
            }
            if (res.dependencies() != null && !res.dependencies().isBlank())
                msg += "；依赖：" + res.dependencies();
            showToast(msg, res.failed() > 0);
            refreshMods();
        }));
    }

    /** 当前选中的版本 id；没有已安装版本时返回 null。 */
    private String currentVersionId() {
        String id = installedVerBox == null ? null : installedVerBox.getValue();
        return (id == null || id.isBlank()) ? null : id;
    }

    private static boolean hasDroppable(Dragboard db) {
        if (!db.hasFiles()) return false;
        return db.getFiles().stream().anyMatch(f -> {
            String n = f.getName().toLowerCase(Locale.ROOT);
            return n.endsWith(".jar") || n.endsWith(".zip") || n.endsWith(".mrpack");
        });
    }

    private static List<File> jarFiles(Dragboard db) {
        if (!db.hasFiles()) return List.of();
        return db.getFiles().stream()
                .filter(File::isFile)
                .filter(f -> f.getName().toLowerCase(Locale.ROOT).endsWith(".jar"))
                .toList();
    }

    private static List<File> packFiles(Dragboard db) {
        if (!db.hasFiles()) return List.of();
        return db.getFiles().stream()
                .filter(File::isFile)
                .filter(f -> {
                    String n = f.getName().toLowerCase(Locale.ROOT);
                    return n.endsWith(".zip") || n.endsWith(".mrpack");
                })
                .toList();
    }

    /** 顶部短暂提示（拖入 mod 等操作的反馈）。 */
    private void showToast(String msg, boolean error) {
        if (toast == null) {
            toast = new Label();
            toast.getStyleClass().add("toast");
            toast.setMouseTransparent(true);
            toast.setOpacity(0);
            StackPane.setAlignment(toast, Pos.TOP_CENTER);
            StackPane.setMargin(toast, new Insets(26, 0, 0, 0));
            rootPane.getChildren().add(toast);
            toastHide = new PauseTransition(Duration.seconds(3));
            toastHide.setOnFinished(e -> {
                FadeTransition ft = new FadeTransition(Duration.millis(300), toast);
                ft.setToValue(0);
                ft.play();
            });
        }
        toast.setText(msg);
        toast.getStyleClass().remove("toast-error");
        if (error) toast.getStyleClass().add("toast-error");
        toast.setOpacity(1);
        toastHide.playFromStart();
    }

    private void onToggleMod() {
        MinecraftService.ModEntry m = modList.getSelectionModel().getSelectedItem();
        if (m == null) { modStatus.setText("请先在列表中选中一个 mod"); return; }
        try {
            mc.setModEnabled(m.fileName(), !m.enabled(), effectiveModsDir());
            log((m.enabled() ? "已禁用 mod：" : "已启用 mod：") + m.fileName());
            modStatus.setText("");
        } catch (Exception ex) {
            modStatus.setText("操作失败：" + ex.getMessage());
        }
        refreshMods();
    }

    private void onDeleteMod() {
        MinecraftService.ModEntry m = modList.getSelectionModel().getSelectedItem();
        if (m == null) { modStatus.setText("请先在列表中选中一个 mod"); return; }
        try {
            mc.deleteMod(m.fileName(), effectiveModsDir());
            log("已删除 mod：" + m.fileName());
            modStatus.setText("");
        } catch (Exception ex) {
            modStatus.setText("删除失败：" + ex.getMessage());
        }
        refreshMods();
    }

    /* ==================== 版本页 ==================== */
    private VBox buildVersions() {
        Label title = new Label("版本管理");
        title.getStyleClass().add("h2");

        searchField = new TextField();
        searchField.getStyleClass().add("dark-box");
        searchField.setPromptText("搜索版本号…");
        searchField.textProperty().addListener((o, a, b) -> filterManifest());
        HBox.setHgrow(searchField, Priority.ALWAYS);

        manifestBtn = new Button("获取版本列表");
        manifestBtn.getStyleClass().add("accent-btn");
        manifestBtn.setOnAction(e -> onLoadManifest());

        HBox top = new HBox(10, searchField, manifestBtn);
        top.setAlignment(Pos.CENTER_LEFT);

        ToggleGroup tg = new ToggleGroup();
        tg.selectedToggleProperty().addListener((o, ov, nv) -> {
            if (nv == null && ov != null) ov.setSelected(true);
        });
        typeAll = chipBtn("全部", tg, true);
        typeRelease = chipBtn("正式版", tg, false);
        typeSnapshot = chipBtn("快照", tg, false);
        typeLegacy = chipBtn("远古版", tg, false);
        typeAll.setOnAction(e -> applyTypeFilter("all"));
        typeRelease.setOnAction(e -> applyTypeFilter("release"));
        typeSnapshot.setOnAction(e -> applyTypeFilter("snapshot"));
        typeLegacy.setOnAction(e -> applyTypeFilter("legacy"));
        HBox chips = new HBox(10, typeAll, typeRelease, typeSnapshot, typeLegacy);
        chips.setAlignment(Pos.CENTER_LEFT);

        manifestInfo = new Label("点击「获取版本列表」拉取全部版本，单击版本进入详情页安装");
        manifestInfo.getStyleClass().addAll("muted", "small");

        manifestList = new ListView<>();
        manifestList.setPlaceholder(new Label("暂无数据"));
        VBox.setVgrow(manifestList, Priority.ALWAYS);
        manifestList.setOnMouseClicked(e -> {
            VersionEntry sel = manifestList.getSelectionModel().getSelectedItem();
            if (sel != null) openDetail(sel);
        });

        VBox panel = new VBox(14, title, top, chips, manifestInfo, manifestList);
        panel.getStyleClass().add("panel");
        VBox.setVgrow(panel, Priority.ALWAYS);
        VBox page = new VBox(panel);
        VBox.setVgrow(panel, Priority.ALWAYS);
        return page;
    }

    private ToggleButton chipBtn(String text, ToggleGroup g, boolean sel) {
        ToggleButton b = new ToggleButton(text);
        b.getStyleClass().add("chip");
        b.setToggleGroup(g);
        b.setSelected(sel);
        return b;
    }

    /** 内存分配卡的单选项（圆圈样式） */
    private RadioButton memRadioBtn(String text, ToggleGroup g, boolean sel) {
        RadioButton r = new RadioButton(text);
        r.getStyleClass().add("mem-radio");
        r.setToggleGroup(g);
        r.setSelected(sel);
        return r;
    }

    /* ==================== 下载模块（一级容器 + 二级子页） ==================== */

    /** 下载模块一级页：顶部 Tab 栏 + 下方子页容器。Tab 切换：版本下载 / Mod 下载 */
    private VBox buildDownloadHub() {
        Label title = new Label("下载模块");
        title.getStyleClass().add("h2");

        ToggleGroup tabG = new ToggleGroup();
        tabG.selectedToggleProperty().addListener((o, ov, nv) -> {
            if (nv == null && ov != null) ov.setSelected(true);
        });
        dlHubTabVers = chipBtn("版本下载", tabG, true);
        dlHubTabMods = chipBtn("Mod 下载", tabG, false);
        dlHubTabPack = chipBtn("整合包", tabG, false);
        dlHubTabShader = chipBtn("光影", tabG, false);
        dlHubTabRes = chipBtn("材质包", tabG, false);
        dlHubTabFav = chipBtn("收藏", tabG, false);
        dlHubTabVers.setOnAction(e -> switchDownloadHubTab("versions"));
        dlHubTabMods.setOnAction(e -> switchDownloadHubTab("mods"));
        dlHubTabPack.setOnAction(e -> switchDownloadHubTab("modpack"));
        dlHubTabShader.setOnAction(e -> switchDownloadHubTab("shader"));
        dlHubTabRes.setOnAction(e -> switchDownloadHubTab("resourcepack"));
        dlHubTabFav.setOnAction(e -> switchDownloadHubTab("favorites"));

        HBox tabs = new HBox(10, dlHubTabVers, dlHubTabMods, dlHubTabPack, dlHubTabShader, dlHubTabRes, dlHubTabFav);
        tabs.setAlignment(Pos.CENTER_LEFT);

        // 内容容器（StackPane：哪个子页 Tab 被选中就显示哪个）
        StackPane hubContent = new StackPane();
        VBox.setVgrow(hubContent, Priority.ALWAYS);
        hubContent.getChildren().setAll(versionsPage);   // 默认版本下载

        // 把 hubContent 存成一个可引用的字段，方便 Tab 切换时 setAll
        downloadHubContentHolder = hubContent;

        VBox hub = new VBox(14, title, tabs, hubContent);
        hub.getStyleClass().add("panel");
        VBox.setVgrow(hub, Priority.ALWAYS);
        return new VBox(hub);
    }

    /** Tab 切换：显示 versionsPage 或 modDlPage */
    private void switchDownloadHubTab(String which) {
        if (downloadHubContentHolder == null) return;
        if ("favorites".equals(which)) {
            if (favPage == null) favPage = buildFavPage();
            downloadHubContentHolder.getChildren().setAll(favPage);
            animateIn(favPage);
            crumb.setText("下载 / 收藏");
            refreshFavPage();
            return;
        }
        if (!"mods".equals(which) && !"versions".equals(which)) { switchDownloadHubPack(which); return; }
        if ("mods".equals(which)) {
            downloadHubContentHolder.getChildren().setAll(modDlPage);
            animateIn(modDlPage);
            crumb.setText("下载 / Mod 下载");
            ensureMcVersionChoices();   // 版本清单未获取完则继续获取（不打断），完成前下拉显示「正在加载」
            // 点击「Mod 下载」自动获取 Mod 信息并按热度（下载量）排序
            Platform.runLater(() -> {
                if (!"热度".equals(modDlSort.getValue())) {
                    modDlSort.setValue("热度");   // 值变化触发监听器自动搜索
                } else {
                    onModSearch();
                }
            });
        } else {
            downloadHubContentHolder.getChildren().setAll(versionsPage);
            animateIn(versionsPage);
            crumb.setText("下载 / 版本下载");
            Platform.runLater(() -> {
                if (manifestCache.isEmpty()) onLoadManifest();   // 点开版本下载自动拉取版本清单
            });
        }
    }

    /** 刷新下载模块的「目标版本」下拉：填真实版本 id（下载目录按 id 解析），显示成「MC 主版本 · 版本 id」 */
    private void refreshDownloadHubMcVersions() {
        if (modDlTargetVer == null) return;
        List<String> installed = mc.getInstalled();
        // Mod 只能装进带加载器的版本：原版（无加载器）不进 Mod「下载到」候选
        List<String> modded = new ArrayList<>();
        for (String id : installed) if (hasLoader(id)) modded.add(id);
        modDlTargetVer.getItems().setAll(modded);
        if (modDlTargetVer.getItems().isEmpty()) {
            // 没有带加载器的版本时给出「全部」选项：下载到默认 mods 目录
            modDlTargetVer.getItems().add("全部");
            modDlTargetVer.setValue("全部");
        } else if (modDlTargetVer.getValue() == null
                || !modDlTargetVer.getItems().contains(modDlTargetVer.getValue())) {
            modDlTargetVer.setValue(modDlTargetVer.getItems().get(0));
        }
        for (PackDl st : new PackDl[] { packShader, packRes }) {
            if (st == null || st.targetVer == null) continue;
            st.targetVer.getItems().setAll(installed);
            if (st.targetVer.getItems().isEmpty()) {
                st.targetVer.getItems().add("全部");
                st.targetVer.setValue("全部");
            } else if (st.targetVer.getValue() == null || !st.targetVer.getItems().contains(st.targetVer.getValue())) {
                st.targetVer.setValue(st.targetVer.getItems().get(0));
            }
        }
        // VoxLink 页的目标版本下拉同源填充
        if (voxVer != null) {
            voxVer.getItems().setAll(installed);
            if (!voxVer.getItems().isEmpty() && (voxVer.getValue() == null
                    || !voxVer.getItems().contains(voxVer.getValue()))) {
                voxVer.setValue(voxVer.getItems().get(0));
            }
            refreshVoxStatus();
        }
    }

    /** 下拉选项显示用 cell：版本 id 显示成「MC 主版本 · 版本 id」，「全部」原样显示 */
    private ListCell<String> verIdCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null
                        : "全部".equals(item) ? item : verDisplay(item));
            }
        };
    }

    /** 全场景下拉弹层加固：递归找所有 ComboBox，把弹层 cell 的文字锁成固定深色 */
    private static void hardenComboPopups(Parent root) {
        if (root instanceof ComboBox<?> cb) hardenComboPopup(cb);
        for (Node n : root.getChildrenUnmodifiable()) {
            if (n instanceof Parent p) hardenComboPopups(p);
        }
    }

    /** ComboBox 下拉弹层是独立 Scene，但 cell 的 CSS 父链会连回所属控件，
     *  .custom-bg 的浅色主题字（tx*）串进弹层后，白底固定色弹层上就是白底白字、完全看不清。
     *  内联样式优先级最高：弹层 cell 一律强制固定深色文字；
     *  收起时显示的按钮 cell（setButtonCell）不在此列，保持主题字色。 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void hardenComboPopup(ComboBox<?> cb) {
        ComboBox raw = (ComboBox) cb;
        javafx.util.Callback old = raw.getCellFactory();
        raw.setCellFactory(lv -> {
            ListCell c = old != null ? (ListCell) old.call(lv) : new ListCell() {
                @Override
                protected void updateItem(Object item, boolean empty) {
                    super.updateItem(item, empty);
                    setText(empty || item == null ? null : item.toString());
                }
            };
            c.setStyle("-fx-text-fill: #22333b;");
            return c;
        });
    }

    /* ==================== 版本设置（每个版本单独设置） ==================== */

    /** 标签 + 控件的一行 */
    private HBox vsRow(String name, Control c) {
        Label l = new Label(name);
        l.setMinWidth(140);
        HBox b = new HBox(12, l, c);
        b.setAlignment(Pos.CENTER_LEFT);
        if (c instanceof Region r) HBox.setHgrow(r, Priority.ALWAYS);
        return b;
    }

    private VBox buildVerSettings() {
        Button back = new Button("← 返回首页");
        back.getStyleClass().add("ghost-btn");
        back.setOnAction(e -> showPage(homePage, "首页"));
        Label title = new Label("版本设置");
        title.getStyleClass().add("h2");
        HBox head = new HBox(12, back, title);
        head.setAlignment(Pos.CENTER_LEFT);
        Label subtitle = new Label("为每个版本单独设置；留空或「跟随全局设置」表示使用全局配置");
        subtitle.getStyleClass().addAll("muted", "small");

        // 不再单独选版本：直接使用「版本选择」页选中的版本；没选到就只显示提示
        vsNoVer = new Label("未扫描到可用版本：请先在首页「版本选择」里选择一个版本，或到「下载」页安装");
        vsNoVer.getStyleClass().addAll("muted");
        vsNoVer.setWrapText(true);

        /* ---- 启动选项 ---- */
        vsIsolation = new ComboBox<>();
        vsIsolation.getItems().addAll("跟随全局设置", "开启", "关闭");
        vsIsolation.getStyleClass().add("dark-combo");
        vsIsolation.setMaxWidth(Double.MAX_VALUE);

        vsJava = new ComboBox<>();
        vsJava.getItems().add("自动选择");
        String foundJava = MinecraftService.findJava();
        if (foundJava != null && !foundJava.isBlank()) vsJava.getItems().add(foundJava);
        vsJava.setEditable(true);
        vsJava.getStyleClass().add("dark-combo");
        vsJava.setMaxWidth(Double.MAX_VALUE);
        vsJavaHint = new Label("");
        vsJavaHint.getStyleClass().addAll("muted", "small");
        vsJavaHint.setStyle("-fx-text-fill: #7ec2c0;");

        VBox launchCard = new VBox(10,
                new Label("启动选项"),
                vsRow("版本隔离", vsIsolation),
                vsRow("Java", vsJava),
                vsJavaHint);
        launchCard.getStyleClass().add("card");
        ((Label) launchCard.getChildren().getFirst()).getStyleClass().add("bold");

        /* ---- 内存分配（PCL2 风格：单选行 + 自定义行内嵌滑块 + 双数值 + 双色进度条） ---- */
        ToggleGroup memG = new ToggleGroup();
        vsMemGlobal = memRadioBtn("跟随全局设置", memG, true);
        vsMemAuto = memRadioBtn("自动配置", memG, false);
        vsMemCustom = memRadioBtn("自定义", memG, false);
        memG.selectedToggleProperty().addListener((o, ov, nv) -> {
            if (nv == null) { ov.setSelected(true); return; }
            vsMemSlider.setDisable(nv != vsMemCustom);
            applyVerSetting();
        });

        vsMemSlider = new Slider(minMemMB, maxMemMB, Math.min(maxMemMB, Math.max(minMemMB, 4096)));
        vsMemSlider.setBlockIncrement(256);
        vsMemSlider.setMaxWidth(Double.MAX_VALUE);
        vsMemSlider.setDisable(true);
        Rectangle vsClip = new Rectangle();
        vsClip.widthProperty().bind(vsMemSlider.widthProperty());
        vsClip.heightProperty().bind(vsMemSlider.heightProperty());
        vsMemSlider.setClip(vsClip);
        vsMemSlider.valueProperty().addListener((o, a, b) -> applyVerSetting());

        HBox memCustomRow = new HBox(14, vsMemCustom);
        HBox.setHgrow(vsMemSlider, Priority.ALWAYS);
        memCustomRow.getChildren().add(vsMemSlider);
        memCustomRow.setAlignment(Pos.CENTER_LEFT);

        vsMemOpt = new ComboBox<>();
        vsMemOpt.getItems().addAll("跟随全局设置", "开启", "关闭");
        vsMemOpt.getStyleClass().add("dark-combo");
        vsMemOpt.setMaxWidth(Double.MAX_VALUE);

        vsMemAutoNote = new Label("");
        vsMemAutoNote.getStyleClass().addAll("muted-2", "small");
        vsMemAutoNote.setWrapText(true);

        // 底部双数值：小标签在上、大字号值在下（已使用内存 | 游戏分配）
        Label vsMemUsedCap = new Label("已使用内存");
        vsMemUsedCap.getStyleClass().addAll("muted", "small");
        vsMemUsed = new Label("");
        vsMemUsed.getStyleClass().add("mem-value");
        VBox memUsedCol = new VBox(3, vsMemUsedCap, vsMemUsed);

        Label vsMemAllocCap = new Label("游戏分配");
        vsMemAllocCap.getStyleClass().addAll("muted", "small");
        vsMemAlloc = new Label("");
        vsMemAlloc.getStyleClass().addAll("mem-value", "accent-text");
        VBox memAllocCol = new VBox(3, vsMemAllocCap, vsMemAlloc);
        memAllocCol.setAlignment(Pos.CENTER_RIGHT);
        HBox.setHgrow(memUsedCol, Priority.ALWAYS);

        HBox memValues = new HBox(30, memUsedCol, memAllocCol);
        memValues.setAlignment(Pos.CENTER_LEFT);

        // 双色进度条：浅色段 = 已用 + 游戏分配（底层），深色段 = 已用（上层）
        vsMemAllocBar = new ProgressBar(0);
        vsMemAllocBar.getStyleClass().add("progress-alloc");
        vsMemAllocBar.setMaxWidth(Double.MAX_VALUE);
        vsMemBar = new ProgressBar(0);
        vsMemBar.getStyleClass().add("progress-used");
        vsMemBar.setMaxWidth(Double.MAX_VALUE);
        StackPane memBarStack = new StackPane(vsMemAllocBar, vsMemBar);
        memBarStack.setPrefHeight(10);

        VBox memCard = new VBox(10,
                new Label("内存分配"),
                vsMemGlobal,
                vsMemAuto,
                memCustomRow,
                vsRow("启动游戏前进行内存优化", vsMemOpt),
                vsMemAutoNote,
                memValues,
                memBarStack);
        memCard.getStyleClass().add("card");
        ((Label) memCard.getChildren().getFirst()).getStyleClass().add("bold");

        /* ---- 服务器 ---- */
        vsLogin = new ComboBox<>();
        vsLogin.getItems().addAll("正版登录或离线登录", "仅正版登录", "仅离线登录",
                "第三方登录（Authlib Injector / LittleSkin）");
        vsLogin.getStyleClass().add("dark-combo");
        vsLogin.setMaxWidth(Double.MAX_VALUE);

        vsServer = new TextField();
        vsServer.setPromptText("例如 mc.example.com:25565，留空不自动进入");
        vsServer.getStyleClass().add("dark-box");

        Button yggdrBtn = new Button("第三方登录…");
        yggdrBtn.getStyleClass().add("ghost-btn");
        yggdrBtn.setOnAction(e -> onYggdrLogin());
        yggdrNote = new Label("未登录第三方账户");
        yggdrNote.getStyleClass().addAll("muted", "small");
        yggdrNote.setWrapText(true);

        VBox serverCard = new VBox(10,
                new Label("服务器"),
                vsRow("登录方式", vsLogin),
                vsRow("第三方登录", yggdrBtn),
                yggdrNote,
                vsRow("自动进入服务器", vsServer));
        serverCard.getStyleClass().add("card");
        ((Label) serverCard.getChildren().getFirst()).getStyleClass().add("bold");

        /* ---- 高级选项（默认折叠） ---- */
        vsJvm = new TextField();
        vsJvm.setPromptText("留空使用全局 JVM 参数，如 -Dfoo=bar -XX:+UseG1GC");
        vsJvm.getStyleClass().add("dark-box");
        vsEnv = new TextField();
        vsEnv.setPromptText("留空不设置；格式：KEY=值 KEY2=值2");
        vsEnv.getStyleClass().add("dark-box");
        TitledPane adv = new TitledPane("高级选项",
                new VBox(10, vsRow("JVM 参数", vsJvm), vsRow("环境变量", vsEnv)));
        adv.setExpanded(false);

        /* ---- Mod 管理（管理当前选中版本实际生效的 mods 目录） ---- */
        Label modTitle = new Label("Mod 管理");
        modTitle.getStyleClass().add("bold");
        modDirLabel = new Label("");
        modDirLabel.getStyleClass().addAll("muted", "small");
        modDirLabel.setWrapText(true);
        modList = new ListView<>();
        modList.setPlaceholder(new Label("mods 文件夹是空的：点「添加 Mod」，或把 .jar / .mrpack 整合包直接拖进窗口"));
        modList.setPrefHeight(220);
        modList.setCellFactory(lv -> new ListCell<>() {
            @Override protected void updateItem(MinecraftService.ModEntry m, boolean empty) {
                super.updateItem(m, empty);
                if (empty || m == null) { setText(null); return; }
                setText((m.enabled() ? "● " : "○ ") + m.fileName()
                        + "（" + fmtSize(m.size()) + (m.enabled() ? "" : " · 已禁用") + "）");
            }
        });
        Button addBtn = new Button("添加 Mod…");
        addBtn.getStyleClass().add("accent-btn");
        addBtn.setOnAction(e -> onAddMod());
        Button toggleBtn = new Button("启用 / 禁用");
        toggleBtn.getStyleClass().add("ghost-btn");
        toggleBtn.setOnAction(e -> onToggleMod());
        Button delBtn = new Button("删除选中");
        delBtn.getStyleClass().add("ghost-btn");
        delBtn.setOnAction(e -> onDeleteMod());
        Button openBtn = new Button("打开 mods 文件夹");
        openBtn.getStyleClass().add("ghost-btn");
        openBtn.setOnAction(e -> {
            try {
                Files.createDirectories(effectiveModsDir());
                Desktop.getDesktop().open(effectiveModsDir().toFile());
            } catch (Exception ignored) { }
        });
        HBox modActions = new HBox(10, addBtn, toggleBtn, delBtn, openBtn);
        modActions.setAlignment(Pos.CENTER_LEFT);
        modStatus = new Label("");
        modStatus.getStyleClass().addAll("muted", "small");
        VBox modCard = new VBox(10, modTitle, modDirLabel, modList, modActions, modStatus);
        modCard.getStyleClass().add("card");

        /* ---- 跳转全局设置 ---- */
        Button globalBtn = new Button("→ 全局设置");
        globalBtn.getStyleClass().add("ghost-btn");
        globalBtn.setOnAction(e -> showPage(settingsPage, "设置"));

        /* ---- 监听改动立即保存 ---- */
        vsIsolation.valueProperty().addListener((o, a, b) -> applyVerSetting());
        vsMemOpt.valueProperty().addListener((o, a, b) -> applyVerSetting());
        vsLogin.valueProperty().addListener((o, a, b) -> applyVerSetting());
        vsServer.textProperty().addListener((o, a, b) -> applyVerSetting());
        vsJvm.textProperty().addListener((o, a, b) -> applyVerSetting());
        vsEnv.textProperty().addListener((o, a, b) -> applyVerSetting());
        vsJava.valueProperty().addListener((o, a, b) -> applyVerSetting());
        vsJava.getEditor().textProperty().addListener((o, a, b) -> applyVerSetting());

        vsContent = new VBox(14, launchCard, memCard, serverCard, adv, modCard, globalBtn);
        VBox page = new VBox(14, head, subtitle, vsNoVer, vsContent);
        page.getStyleClass().addAll("panel", "ring");
        page.setPadding(new Insets(6));

        // 包一层 ScrollPane：内容超高时可滚动，支持鼠标滚轮上下翻页
                ScrollPane scroll = new ScrollPane(page);
        wrapScroll(scroll);

        VBox out = new VBox(scroll);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        return out;
    }

    /** 刷新版本设置页：有选中版本才显示设置内容，否则只显示提示 */
    private void refreshVerSettings() {
        if (vsContent == null || vsNoVer == null) return;
        String id = currentVersionId();
        boolean has = id != null && !id.isBlank();
        vsNoVer.setVisible(!has);
        vsNoVer.setManaged(!has);
        vsContent.setVisible(has);
        vsContent.setManaged(has);
        if (has) {
            loadVerSetting();
            refreshMods();
        }
    }

    /** 把某版本的设置加载到控件 */
    private void loadVerSetting() {
        String id = currentVersionId();
        if (id == null || id.isBlank()) return;
        vsLoading = true;
        try {
            VerSetting s = verSetting(id);
            vsIsolation.setValue("on".equals(s.isolation) ? "开启" : "off".equals(s.isolation) ? "关闭" : "跟随全局设置");
            vsJava.setValue(s.javaPath.isBlank() ? "自动选择" : s.javaPath);
            vsMemGlobal.setSelected("global".equals(s.memoryMode));
            vsMemAuto.setSelected("auto".equals(s.memoryMode));
            vsMemCustom.setSelected("custom".equals(s.memoryMode));
            if (!vsMemGlobal.isSelected() && !vsMemAuto.isSelected() && !vsMemCustom.isSelected())
                vsMemGlobal.setSelected(true);
            vsMemSlider.setDisable(!vsMemCustom.isSelected());
            vsMemSlider.setValue(Math.max(minMemMB, Math.min(maxMemMB, s.memoryMB <= 0 ? 4096 : s.memoryMB)));
            vsMemOpt.setValue("on".equals(s.memOptimize) ? "开启" : "off".equals(s.memOptimize) ? "关闭" : "跟随全局设置");
            vsLogin.setValue("online".equals(s.loginMode) ? "仅正版登录"
                    : "offline".equals(s.loginMode) ? "仅离线登录"
                    : "yggdr".equals(s.loginMode) ? "第三方登录（Authlib Injector / LittleSkin）"
                    : "正版登录或离线登录");
            vsServer.setText(s.serverIp);
            vsJvm.setText(s.jvmArgs);
            vsEnv.setText(s.envVars);
            updateVsJavaHint();
            updateVsMemLabels();
        } finally {
            vsLoading = false;
        }
    }

    /** 控件当前值写回设置对象并保存 */
    private void applyVerSetting() {
        String curId = currentVersionId();
        if (vsLoading || curId == null || curId.isBlank()) return;
        VerSetting s = verSetting(curId);
        s.isolation = vsCombo3(vsIsolation, "开启", "关闭", "on", "off", "global");
        String jv = vsJava.getValue() == null ? "" : vsJava.getValue().trim();
        s.javaPath = jv.isBlank() || "自动选择".equals(jv) ? "" : jv;
        s.memoryMode = vsMemCustom.isSelected() ? "custom" : vsMemAuto.isSelected() ? "auto" : "global";
        s.memoryMB = (int) vsMemSlider.getValue();
        s.memOptimize = vsCombo3(vsMemOpt, "开启", "关闭", "on", "off", "global");
        String lv = vsLogin.getValue();
        s.loginMode = "仅正版登录".equals(lv) ? "online"
                : "仅离线登录".equals(lv) ? "offline"
                : "第三方登录（Authlib Injector / LittleSkin）".equals(lv) ? "yggdr" : "both";
        s.serverIp = vsText(vsServer);
        s.jvmArgs = vsText(vsJvm);
        s.envVars = vsText(vsEnv);
        updateVsJavaHint();
        updateVsMemLabels();
        saveConfig();
    }

    private static String vsText(TextField f) {
        return f.getText() == null ? "" : f.getText().trim();
    }

    /** 三态下拉 → 内部值 */
    private static String vsCombo3(ComboBox<String> box, String v1, String v2, String r1, String r2, String r0) {
        String v = box.getValue();
        if (v1.equals(v)) return r1;
        if (v2.equals(v)) return r2;
        return r0;
    }

    private void updateVsJavaHint() {
        String j = vsJava.getValue() == null ? "" : vsJava.getValue().trim();
        if (j.isBlank() || "自动选择".equals(j)) {
            String fj = MinecraftService.findJava();
            vsJavaHint.setText("将会使用：" + (fj == null || fj.isBlank() ? "自动检测的 Java" : fj));
        } else {
            vsJavaHint.setText("将会使用：" + j);
        }
    }

    /** 已使用内存 / 游戏分配 显示（双数值 + 双色进度条） */
    private void updateVsMemLabels() {
        try {
            com.sun.management.OperatingSystemMXBean os =
                    (com.sun.management.OperatingSystemMXBean) java.lang.management.ManagementFactory.getOperatingSystemMXBean();
            long total = os.getTotalMemorySize(), free = os.getFreeMemorySize();
            long used = Math.max(0, total - free);
            vsMemUsed.setText(String.format("%.1f GB / %.1f GB",
                    used / 1073741824.0, total / 1073741824.0));
            StringBuilder autoNote = new StringBuilder();
            int auto = autoMemoryFor(currentVersionId(), autoNote);
            int alloc = vsMemCustom.isSelected() ? (int) vsMemSlider.getValue()
                    : vsMemAuto.isSelected() ? auto
                    : "custom".equals(globalMemMode) ? mc.memoryMB : auto;
            vsMemAlloc.setText(String.format("%.1f GB", alloc / 1024.0));
            double usedP = total == 0 ? 0 : (double) used / total;
            double withAllocP = total == 0 ? 0 : Math.min(1.0, (used + alloc * 1048576.0) / total);
            vsMemBar.setProgress(usedP);
            if (vsMemAllocBar != null) vsMemAllocBar.setProgress(Math.max(usedP, withAllocP));
            if (vsMemAutoNote != null) vsMemAutoNote.setText("自动配置估算：" + autoNote);
        } catch (Exception e) {
            vsMemUsed.setText("内存信息不可用");
            vsMemAlloc.setText("");
        }
    }

    /** 设置页内存卡：已使用内存 / 游戏分配 + 双色进度条 */
    private void updateGlMemLabels() {
        if (glMemUsed == null) return;
        try {
            com.sun.management.OperatingSystemMXBean os =
                    (com.sun.management.OperatingSystemMXBean) java.lang.management.ManagementFactory.getOperatingSystemMXBean();
            long total = os.getTotalMemorySize(), free = os.getFreeMemorySize();
            long used = Math.max(0, total - free);
            glMemUsed.setText(String.format("%.1f GB / %.1f GB",
                    used / 1073741824.0, total / 1073741824.0));
            int alloc = "custom".equals(globalMemMode) ? mc.memoryMB : autoMemoryFor(currentVersionId(), null);
            glMemAlloc.setText(String.format("%.1f GB", alloc / 1024.0));
            double usedP = total == 0 ? 0 : (double) used / total;
            double withAllocP = total == 0 ? 0 : Math.min(1.0, (used + alloc * 1048576.0) / total);
            glMemBar.setProgress(usedP);
            glMemAllocBar.setProgress(Math.max(usedP, withAllocP));
        } catch (Exception e) {
            glMemUsed.setText("内存信息不可用");
            glMemAlloc.setText("");
        }
    }

    /** 取某版本的设置对象（没有则创建默认） */
    private VerSetting verSetting(String id) {
        return verSettings.computeIfAbsent(id, k -> new VerSetting());
    }

    /** 给指定版本强制开启版本隔离（整合包安装后默认隔离，Mod / 存档独立目录），并持久化 */
    private void enableIsolationFor(String id) {
        if (id == null || id.isBlank()) return;
        verSetting(id).isolation = "on";
        saveConfig();
    }

    /* ==================== Mod 下载子页（Modrinth API） ==================== */

    private VBox buildModDownload() {
        /* ---- 搜索行（PCL2 风格：大搜索框 + 搜索按钮） ---- */
        modDlSearch = new TextField();
        modDlSearch.getStyleClass().add("dark-box");
        modDlSearch.setPromptText("搜索 Mod，回车或点「搜索」；留空查看热门");
        modDlSearch.setOnAction(e -> onModSearch());

        Button searchBtn = new Button("搜索");
        searchBtn.getStyleClass().add("accent-btn");
        searchBtn.setPrefWidth(96);
        searchBtn.setOnAction(e -> onModSearch());

        HBox searchRow = new HBox(10, modDlSearch, searchBtn);
        searchRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(modDlSearch, Priority.ALWAYS);

        /* ---- 过滤 + 目标行（合并单行压缩头部：游戏版本 / 加载器 / 分类 / 排序 / 下载到） ---- */
        Label verCap = new Label("游戏版本");
        verCap.getStyleClass().addAll("muted", "small");
        modDlMcVersion = new ComboBox<>();
        modDlMcVersion.setPromptText("全部");
        modDlMcVersion.getItems().add("全部");
        modDlMcVersion.setValue("全部");
        modDlMcVersion.getStyleClass().addAll("dark-box", "filter-combo");
        modDlMcVersion.setEditable(false);   // 砍掉手动输入版本，只能从下拉选（点击也不会出现输入光标）
        modDlMcVersion.setPrefWidth(120);
        // 选中某个版本即按该版本过滤 Mod
        modDlMcVersion.valueProperty().addListener((o, a, b) -> {
            if (modDlVerQuiet) return;
            if (b != null && !"正在加载".equals(b) && modDlMcVersion.getItems().contains(b)) onModSearch();
        });
        // 版本清单在点击下载模块时自动获取（ensureMcVersionChoices），获取完成前下拉显示「正在加载」

        Label loaderCap = new Label("加载器");
        loaderCap.getStyleClass().addAll("muted", "small");
        modDlLoader = new ComboBox<>();
        modDlLoader.getItems().addAll("全部", "fabric", "forge", "neoforge");
        modDlLoader.setValue("全部");
        modDlLoader.getStyleClass().addAll("dark-box", "filter-combo");
        modDlLoader.setPrefWidth(120);
        modDlLoader.setMinWidth(Region.USE_PREF_SIZE);   // 不参与压缩，保证 "neoforge" 完整显示

        Label catCap = new Label("分类");
        catCap.getStyleClass().addAll("muted", "small");
        modDlCat = new ComboBox<>();
        modDlCat.getItems().add("全部");
        for (String[] row : MinecraftService.MOD_CATEGORIES) modDlCat.getItems().add(row[0]);
        modDlCat.setValue("全部");
        modDlCat.getStyleClass().addAll("dark-box", "filter-combo");
        modDlCat.setEditable(false);
        modDlCat.setPrefWidth(110);
        // 选中分类即回到第 1 页重新搜索（「全部」= 不过滤）
        modDlCat.valueProperty().addListener((o, a, b) -> {
            modDlCategory = categorySlug(b);
            modDlCurPage = 1;
            loadModPage();
        });

        Label sortCap = new Label("排序");
        sortCap.getStyleClass().addAll("muted", "small");
        modDlSort = new ComboBox<>();
        modDlSort.getItems().addAll("热度", "综合", "最近更新", "最多收藏");
        modDlSort.setValue("热度");
        modDlSort.getStyleClass().addAll("dark-box", "filter-combo");
        modDlSort.setPrefWidth(130);
        modDlSort.setMinWidth(Region.USE_PREF_SIZE);   // 不参与压缩，保证「最多收藏」等完整显示
        modDlSort.valueProperty().addListener((o, a, b) -> onModSearch());

        modDlTargetTip = new Label("下载到");
        modDlTargetTip.getStyleClass().addAll("muted", "small");
        modDlTargetVer = new ComboBox<>();
        modDlTargetVer.setPromptText("选择一个已安装版本");
        modDlTargetVer.getStyleClass().addAll("dark-box", "filter-combo");
        modDlTargetVer.setEditable(false);
        modDlTargetVer.setCellFactory(lv -> verIdCell());
        modDlTargetVer.setButtonCell(verIdCell());
        modDlTargetVer.setPrefWidth(185);

        modDlBrowseBtn = new Button("打开 mods 目录");
        modDlBrowseBtn.getStyleClass().add("ghost-btn");
        modDlBrowseBtn.setOnAction(e -> {
            try {
                String id = modDlTargetVer.getValue();
                Path dir = (id == null || "全部".equals(id))
                        ? mc.gameDir.resolve("mods")
                        : modsDirFor(id);
                Files.createDirectories(dir);
                Desktop.getDesktop().open(dir.toFile());
            } catch (Exception ex) { showToast("打开 mods 目录失败", true); }
        });

        modDlFilterRow = new HBox(8, verCap, modDlMcVersion, loaderCap, modDlLoader, catCap, modDlCat,
                sortCap, modDlSort, modDlTargetTip, modDlTargetVer, modDlBrowseBtn);
        modDlFilterRow.setAlignment(Pos.CENTER_LEFT);
        // 行内空间不足时优先压缩下拉框，标题文字和按钮不截断
        for (Label cap : new Label[] { verCap, loaderCap, catCap, sortCap, modDlTargetTip }) {
            cap.setMinWidth(Region.USE_PREF_SIZE);
        }
        modDlBrowseBtn.setMinWidth(Region.USE_PREF_SIZE);

        /* ---- 状态提示 ---- */
        modDlStatus = new Label("");
        modDlStatus.getStyleClass().addAll("muted", "small");

        /* ---- 结果卡片列表（与头部同在一个 ScrollPane 内滚动） ---- */
        modDlCards = new VBox(10);
        Label placeholder = new Label("按回车或点「搜索」开始查找 Mod（首次自动加载热门）");
        placeholder.getStyleClass().addAll("muted", "small");
        modDlCards.getChildren().add(placeholder);

        /* ---- 分页栏：上一页 + 页码 + 下一页，点页码即重新加载对应页 ---- */
        modDlPageBar = new HBox(6);
        modDlPageBar.setAlignment(Pos.CENTER);

        modDlListContent = new VBox(10, searchRow, modDlFilterRow, modDlStatus, modDlCards, modDlPageBar);
        modDlScroll = new ScrollPane(modDlListContent);
        modDlScroll.setFitToWidth(true);
                modDlScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        makeScrollbarAutoHide(modDlScroll);

        trackGlassScroll(modDlScroll);


        VBox panel = new VBox(modDlScroll);
        panel.getStyleClass().add("panel");
        VBox.setVgrow(modDlScroll, Priority.ALWAYS);
        VBox.setVgrow(panel, Priority.ALWAYS);
        return panel;
    }

    /* ==================== 下载子模块：整合包 / 光影 / 材质包 ==================== */

    /** 整合包/光影/材质包子模块的状态与控件（三页共用一套构建逻辑，kind 区分搜索类型与安装目录） */
    private static final class PackDl {
        final String kind;      // Modrinth project_type：modpack | shader | resourcepack
        final String label;     // 展示名：整合包 / 光影 / 材质包
        final String dirName;   // 安装目录名（shaderpacks / resourcepacks）；null = 整合包（安装成版本）
        VBox page;
        TextField search;
        ComboBox<String> mcVer, sort, targetVer;
        Label status;
        VBox cards, listContent;
        HBox pageBar, filterRow;
        ScrollPane scroll;
        List<Node> targetNodes = List.of();   // 「下载到」控件组（进二级页时临时搬走）
        int curPage = 1;
        long total, seq;
        boolean verQuiet;
        PackDl(String kind, String label, String dirName) {
            this.kind = kind;
            this.label = label;
            this.dirName = dirName;
        }
    }

    /** 按 kind 取子模块状态 */
    private PackDl packFor(String kind) {
        return switch (kind) {
            case "shader" -> packShader;
            case "resourcepack" -> packRes;
            default -> packModpack;
        };
    }

    /** 切到整合包/光影/材质包子模块：首次进入自动拉取热门列表 */
    private void switchDownloadHubPack(String which) {
        PackDl st = packFor(which);
        downloadHubContentHolder.getChildren().setAll(st.page);
        animateIn(st.page);
        crumb.setText("下载 / " + st.label + "下载");
        ensureMcVersionChoices();
        applyMcVerChoices(mcVerIds);
        Platform.runLater(() -> loadPackPage(st));   // 点开子模块就自动检索（与 Mod 下载页一致）
    }

    /** 把游戏版本清单填进整合包/光影/材质包的版本下拉（保留用户当前值） */
    private void applyMcVerChoices(List<String> ids) {
        if (ids == null || ids.isEmpty()) return;
        for (PackDl st : new PackDl[] { packModpack, packShader, packRes }) {
            if (st == null || st.mcVer == null) continue;
            st.verQuiet = true;
            String cur = st.mcVer.getValue();
            st.mcVer.getItems().setAll(ids);
            if (ids.size() > 1 && cur != null && !cur.isBlank() && !"正在加载".equals(cur)) st.mcVer.setValue(cur);
            else st.mcVer.setValue("全部");
            st.verQuiet = false;
        }
    }

    /** 整合包/光影/材质包子模块页：搜索 + 版本/排序过滤 + 结果卡片 + 分页 */
    private VBox buildPackDownload(PackDl st) {
        /* ---- 搜索行 ---- */
        st.search = new TextField();
        st.search.getStyleClass().add("dark-box");
        st.search.setPromptText("搜索" + st.label + "，回车或点「搜索」；留空查看热门");
        st.search.setOnAction(e -> onPackSearch(st));

        Button searchBtn = new Button("搜索");
        searchBtn.getStyleClass().add("accent-btn");
        searchBtn.setPrefWidth(96);
        searchBtn.setOnAction(e -> onPackSearch(st));

        HBox searchRow = new HBox(10, st.search, searchBtn);
        searchRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(st.search, Priority.ALWAYS);

        /* ---- 过滤 + 目标行 ---- */
        Label verCap = new Label("游戏版本");
        verCap.getStyleClass().addAll("muted", "small");
        verCap.setMinWidth(Region.USE_PREF_SIZE);
        st.mcVer = new ComboBox<>();
        st.mcVer.setPromptText("全部");
        st.mcVer.getItems().add("全部");
        st.mcVer.setValue("全部");
        st.mcVer.getStyleClass().addAll("dark-box", "filter-combo");
        st.mcVer.setEditable(false);
        st.mcVer.setPrefWidth(120);
        st.mcVer.valueProperty().addListener((o, a, b) -> {
            if (st.verQuiet) return;
            if (b != null && !"正在加载".equals(b) && st.mcVer.getItems().contains(b)) onPackSearch(st);
        });

        Label sortCap = new Label("排序");
        sortCap.getStyleClass().addAll("muted", "small");
        sortCap.setMinWidth(Region.USE_PREF_SIZE);
        st.sort = new ComboBox<>();
        st.sort.getItems().addAll("热度", "综合", "最近更新", "最多收藏");
        st.sort.setValue("热度");
        st.sort.getStyleClass().addAll("dark-box", "filter-combo");
        st.sort.setPrefWidth(130);
        st.sort.setMinWidth(Region.USE_PREF_SIZE);
        st.sort.valueProperty().addListener((o, a, b) -> onPackSearch(st));

        if (st.dirName != null) {
            Label targetTip = new Label("下载到");
            targetTip.getStyleClass().addAll("muted", "small");
            targetTip.setMinWidth(Region.USE_PREF_SIZE);
            st.targetVer = new ComboBox<>();
            st.targetVer.setPromptText("选择一个已安装版本");
            st.targetVer.getStyleClass().addAll("dark-box", "filter-combo");
            st.targetVer.setEditable(false);
            st.targetVer.setCellFactory(lv -> verIdCell());
            st.targetVer.setButtonCell(verIdCell());
            st.targetVer.setPrefWidth(185);
            Button browseBtn = new Button("打开" + st.label + "目录");
            browseBtn.getStyleClass().add("ghost-btn");
            browseBtn.setMinWidth(Region.USE_PREF_SIZE);
            browseBtn.setOnAction(e -> {
                try {
                    Path dir = packsDirFor(st.dirName, st.targetVer.getValue());
                    Files.createDirectories(dir);
                    Desktop.getDesktop().open(dir.toFile());
                } catch (Exception ex) {
                    showToast("打开目录失败", true);
                }
            });
            st.filterRow = new HBox(8, verCap, st.mcVer, sortCap, st.sort, targetTip, st.targetVer, browseBtn);
            st.targetNodes = List.of(targetTip, st.targetVer, browseBtn);
        } else {
            st.filterRow = new HBox(8, verCap, st.mcVer, sortCap, st.sort);
        }
        st.filterRow.setAlignment(Pos.CENTER_LEFT);

        /* ---- 状态 + 结果卡片 + 分页 ---- */
        st.status = new Label("点「搜索」开始查找" + st.label + "（首次自动加载热门）");
        st.status.getStyleClass().addAll("muted", "small");

        st.cards = new VBox(10);
        Label placeholder = new Label("按回车或点「搜索」开始查找" + st.label);
        placeholder.getStyleClass().addAll("muted", "small");
        st.cards.getChildren().add(placeholder);

        st.pageBar = new HBox(6);
        st.pageBar.setAlignment(Pos.CENTER);

        st.listContent = new VBox(10, searchRow, st.filterRow, st.status, st.cards, st.pageBar);
        st.scroll = new ScrollPane(st.listContent);
        st.scroll.setFitToWidth(true);
        st.scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        makeScrollbarAutoHide(st.scroll);
        trackGlassScroll(st.scroll);

        VBox panel = new VBox(st.scroll);
        panel.getStyleClass().add("panel");
        VBox.setVgrow(st.scroll, Priority.ALWAYS);
        VBox.setVgrow(panel, Priority.ALWAYS);
        return panel;
    }

    /** 子模块搜索按钮回调：回到第 1 页重新搜索 */
    private void onPackSearch(PackDl st) {
        st.curPage = 1;
        loadPackPage(st);
    }

    /** 加载第 st.curPage 页列表（每页 MOD_PAGE_SIZE 个，翻页时重新请求 API） */
    private void loadPackPage(PackDl st) {
        String q = st.search == null ? "" : st.search.getText();
        String mcVer = st.mcVer == null ? "" : st.mcVer.getValue();
        if (mcVer != null && (mcVer.isBlank() || "全部".equals(mcVer) || "正在加载".equals(mcVer))) mcVer = null;
        String sort = st.sort == null ? "" : st.sort.getValue();
        boolean searching = q != null && !q.isBlank();
        // 搜索时不用热度排序：按名称与搜索词的接近度排序（API 取回按 relevance，页内再按名称接近度重排）
        String index = searching ? "relevance"
                : "热度".equals(sort) ? "downloads"
                : "最近更新".equals(sort) ? "updated"
                : "最多收藏".equals(sort) ? "follows" : "relevance";

        long seq = ++st.seq;
        st.status.setText("正在加载第 " + st.curPage + " 页…");
        st.status.setStyle("-fx-text-fill: #9eb4be;");
        int offset = (st.curPage - 1) * MOD_PAGE_SIZE;
        mc.searchPacksPage(q, mcVer, st.kind, index, offset, MOD_PAGE_SIZE)
                .whenComplete((page, ex) -> Platform.runLater(() -> {
            if (seq != st.seq) return;   // 已发起新搜索/翻页，丢弃过期响应
            if (ex != null) {
                st.status.setText("搜索失败：" + (ex.getCause() == null ? ex.getMessage() : ex.getCause().getMessage()));
                st.status.setStyle("-fx-text-fill: #ff8a7a;");
                setPackPlaceholder(st, "搜索失败，请重试");
                st.pageBar.getChildren().clear();
                return;
            }
            st.total = page.totalHits();
            List<MinecraftService.ModSearchResult> list = page.items();
            if (searching) list = sortByTitleMatch(q, list);   // 搜索时按名称与搜索词的接近度排序（近的在前）
            if (list.isEmpty()) {
                setPackPlaceholder(st, st.curPage > 1 ? "这一页没有内容，点上一页返回" : "没有找到匹配的" + st.label);
            } else {
                st.cards.getChildren().clear();
                for (MinecraftService.ModSearchResult r : list) st.cards.getChildren().add(modCard(st.kind, r, () -> onPackOpenDetail(st, r)));
                scheduleGlassApply();   // 新卡片套液态玻璃
            }
            buildPackPageBar(st);
            st.status.setText("共 " + fmtCount(st.total) + " 个" + st.label + " · 第 "
                    + st.curPage + " / " + packPageCount(st) + " 页");
        }));
    }

    /** 总页数（搜索最多返回约 1 万个结果，钳一下避免请求越界的页） */
    private int packPageCount(PackDl st) {
        long n = (st.total + MOD_PAGE_SIZE - 1) / MOD_PAGE_SIZE;
        return (int) Math.max(1, Math.min(Math.max(n, st.curPage), 334));
    }

    /** 用一行灰字占位替换子模块卡片列表内容 */
    private void setPackPlaceholder(PackDl st, String text) {
        Label ph = new Label(text);
        ph.getStyleClass().addAll("muted", "small");
        st.cards.getChildren().setAll(ph);
    }

    /** 分页栏：上一页 + 页码（当前 ±2，首尾常驻，中间省略号）+ 下一页 */
    private void buildPackPageBar(PackDl st) {
        st.pageBar.getChildren().clear();
        if (st.total <= 0 && st.curPage <= 1) return;
        int pages = packPageCount(st);
        st.pageBar.getChildren().add(packPageBtn(st, "上一页", st.curPage - 1, st.curPage <= 1, false));

        java.util.TreeSet<Integer> shown = new java.util.TreeSet<>();
        shown.add(1);
        shown.add(pages);
        for (int p = st.curPage - 2; p <= st.curPage + 2; p++)
            if (p >= 1 && p <= pages) shown.add(p);
        int last = 0;
        for (int p : shown) {
            if (last != 0 && p > last + 1) {
                Label dots = new Label("…");
                dots.getStyleClass().addAll("muted", "small");
                st.pageBar.getChildren().add(dots);
            }
            st.pageBar.getChildren().add(packPageBtn(st, String.valueOf(p), p, false, p == st.curPage));
            last = p;
        }
        st.pageBar.getChildren().add(packPageBtn(st, "下一页", st.curPage + 1, st.curPage >= pages, false));
    }

    /** 子模块分页按钮：点击即重新加载对应页并回到列表顶部 */
    private Button packPageBtn(PackDl st, String text, int page, boolean disabled, boolean current) {
        Button b = new Button(text);
        b.getStyleClass().add(current ? "accent-btn" : "ghost-btn");
        b.setDisable(disabled);
        b.setOnAction(e -> {
            if (page == st.curPage) return;
            st.curPage = page;
            loadPackPage(st);
            st.scroll.setVvalue(0);
        });
        return b;
    }

    /** 单击卡片进入二级页：简介 + 版本列表，在版本行里挑版本下载 */
    private void onPackOpenDetail(PackDl st, MinecraftService.ModSearchResult item) {
        // 「下载到」控件搬到二级页（JavaFX 节点只有一个父容器，切换页面时挪动）
        if (!st.targetNodes.isEmpty()) st.filterRow.getChildren().removeAll(st.targetNodes);

        Button backBtn = new Button("← 返回列表");
        backBtn.getStyleClass().add("ghost-btn");
        backBtn.setOnAction(e -> {
            if (!st.targetNodes.isEmpty()) st.filterRow.getChildren().addAll(st.targetNodes);
            st.scroll.setContent(st.listContent);
            st.scroll.setVvalue(0);
            animateIn(st.listContent);
        });

        /* ---- 简介卡 ---- */
        ImageView icon = new ImageView();
        icon.setFitWidth(72);
        icon.setFitHeight(72);
        icon.setPreserveRatio(true);
        icon.setSmooth(true);
        Rectangle iconClip = new Rectangle(72, 72);
        iconClip.setArcWidth(20);
        iconClip.setArcHeight(20);
        icon.setClip(iconClip);
        icon.setOpacity(0.0);
        StackPane iconWrap = new StackPane(icon);
        iconWrap.setStyle("-fx-background-color: rgba(45,212,191,0.10); -fx-background-radius: 13;");
        iconWrap.setMinSize(72, 72);
        iconWrap.setMaxSize(72, 72);
        if (item.iconUrl() != null && !item.iconUrl().isBlank()) {
            loadModIcon(item.iconUrl(), icon);
        }
        Label nameLbl = new Label(item.title());
        nameLbl.getStyleClass().add("h2");
        Label authorLbl = new Label(item.author() == null || item.author().isBlank() ? "" : "作者 " + item.author());
        authorLbl.getStyleClass().addAll("muted", "small");
        Label summaryLbl = new Label(item.summary() == null ? "" : item.summary().replaceAll("\\s+", " ").trim());
        summaryLbl.getStyleClass().addAll("muted", "small");
        summaryLbl.setWrapText(true);
        summaryLbl.setMaxWidth(820);
        Label statLbl = new Label("下载 " + fmtCount(item.downloads()) + " · 收藏 " + fmtCount(item.follows())
                + " · " + fmtDate(item.dateModified()) + " 更新");
        statLbl.getStyleClass().add("mod-stat");
        VBox infoMid = new VBox(4, nameLbl, authorLbl, summaryLbl, statLbl);
        HBox.setHgrow(infoMid, Priority.ALWAYS);
        HBox infoCard = new HBox(16, iconWrap, infoMid);
        infoCard.setAlignment(Pos.CENTER_LEFT);
        infoCard.setPadding(new Insets(16, 18, 16, 18));
        infoCard.getStyleClass().add("mod-cell-card");

        Label verTitle = new Label("版本列表（点游戏版本标签筛选，点分组标题展开/收起）");
        verTitle.getStyleClass().add("h2");
        FlowPane verFilterRow = new FlowPane(8, 8);
        Label verStatus = new Label("正在加载版本列表…");
        verStatus.getStyleClass().addAll("muted", "small");
        VBox verGroupsBox = new VBox(10);

        VBox detail = new VBox(12, backBtn, infoCard);
        if (!st.targetNodes.isEmpty()) {
            HBox targetRow = new HBox(8);
            targetRow.setAlignment(Pos.CENTER_LEFT);
            targetRow.getChildren().addAll(st.targetNodes);
            detail.getChildren().add(targetRow);
        }
        detail.getChildren().addAll(verTitle, verFilterRow, verStatus, verGroupsBox);
        st.scroll.setContent(detail);
        st.scroll.setVvalue(0);
        animateIn(detail);

        // CF-only 条目的版本列表走 CurseForge，双源条目走 Modrinth（页面不展示来源）
        CompletableFuture<List<MinecraftService.ModVersion>> verReq =
                item.projectId().isBlank() && !item.cfProjectId().isBlank()
                        ? mc.listCfModVersions(item.cfProjectId())
                        : mc.listModVersions(item.projectId());
        verReq.whenComplete((versions, ex) -> Platform.runLater(() -> {
            if (ex != null) {
                verStatus.setText("版本列表加载失败：" + (ex.getCause() == null ? ex.getMessage() : ex.getCause().getMessage()));
                verStatus.setStyle("-fx-text-fill: #ff8a7a;");
                return;
            }
            if (versions.isEmpty()) {
                verStatus.setText("该" + st.label + "没有可下载的版本");
                return;
            }
            verStatus.setText("共 " + versions.size() + " 个版本");
            buildPackVersionGroups(st, item, versions, verFilterRow, verGroupsBox, verStatus);
        }));
    }

    /** 二级页版本列表（整合包/光影/材质包）：游戏版本筛选标签 + 按游戏版本分组的折叠卡片 */
    private void buildPackVersionGroups(PackDl st, MinecraftService.ModSearchResult mod,
                                        List<MinecraftService.ModVersion> versions,
                                        FlowPane filterRow, VBox groupsBox, Label verStatus) {
        // 收集游戏版本（新版本在前）
        List<String> mcVers = new ArrayList<>();
        for (MinecraftService.ModVersion v : versions)
            for (String gv : v.gameVersions()) if (!mcVers.contains(gv)) mcVers.add(gv);
        mcVers.sort((a, b) -> compareMcVersion(b, a));

        // 分组：每个游戏版本一组（同一版本文件可属于多个游戏版本组）
        Map<String, List<MinecraftService.ModVersion>> groups = new LinkedHashMap<>();
        for (String gv : mcVers)
            for (MinecraftService.ModVersion v : versions)
                if (v.gameVersions().contains(gv)) groups.computeIfAbsent(gv, k -> new ArrayList<>()).add(v);
        Map<String, ModVerGroup> groupCards = new LinkedHashMap<>();
        for (Map.Entry<String, List<MinecraftService.ModVersion>> en : groups.entrySet())
            groupCards.put(en.getKey(), packVerGroup(st, en.getKey(), en.getValue(), mod, verStatus));

        // 游戏版本筛选标签（「全部」+ 正式版 x.y 主线）
        List<String> chipTexts = new ArrayList<>();
        chipTexts.add("全部");
        for (String gv : mcVers) {
            String base = gv.split("-")[0];
            if (!base.matches("\\d+(\\.\\d+)*")) continue;
            String[] seg = base.split("\\.");
            String line = seg.length >= 2 ? seg[0] + "." + seg[1] : base;
            if (!chipTexts.contains(line)) chipTexts.add(line);
        }
        ToggleGroup tg = new ToggleGroup();
        List<ToggleButton> chips = new ArrayList<>();
        for (String text : chipTexts) {
            ToggleButton chip = new ToggleButton(text);
            chip.getStyleClass().add("chip");
            chip.setToggleGroup(tg);
            if ("全部".equals(text)) chip.setSelected(true);
            chips.add(chip);
        }
        filterRow.getChildren().setAll(chips);

        Runnable applyFilter = () -> {
            Toggle sel = tg.getSelectedToggle();
            String gv = sel == null ? "全部" : ((ToggleButton) sel).getText();
            groupsBox.getChildren().clear();
            for (Map.Entry<String, ModVerGroup> en : groupCards.entrySet()) {
                if ("全部".equals(gv) || mcTagMatches(en.getKey(), gv)) groupsBox.getChildren().add(en.getValue().box);
            }
            scheduleGlassApply();   // 分组卡增删后补套玻璃
        };
        tg.selectedToggleProperty().addListener((o, a, b) -> applyFilter.run());
        applyFilter.run();
    }

    /** 二级页分组卡片（整合包/光影/材质包）：点标题行折叠/展开版本行 */
    private ModVerGroup packVerGroup(PackDl st, String key, List<MinecraftService.ModVersion> items,
                                     MinecraftService.ModSearchResult mod, Label verStatus) {
        Label nameLbl = new Label(key);
        nameLbl.getStyleClass().add("mod-title");
        Label countLbl = new Label("共 " + items.size() + " 个版本");
        countLbl.getStyleClass().addAll("muted", "small");
        Label arrow = new Label("▲");
        arrow.getStyleClass().addAll("muted", "small");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox head = new HBox(10, nameLbl, countLbl, spacer, arrow);
        head.setAlignment(Pos.CENTER_LEFT);
        head.setPadding(new Insets(10, 12, 10, 14));
        head.setCursor(Cursor.HAND);

        VBox rows = new VBox(6);
        rows.setPadding(new Insets(2, 4, 6, 4));
        VBox box = new VBox(head, rows);
        box.getStyleClass().add("mod-cell-card");

        ModVerGroup group = new ModVerGroup(box, rows, arrow,
                () -> { for (MinecraftService.ModVersion v : items) rows.getChildren().add(packVerRow(st, mod, v, verStatus)); });
        group.setExpanded(false);   // 默认折叠，展开时才建行
        head.setOnMouseClicked(e -> {
            group.setExpanded(!rows.isVisible());
            scheduleGlassApply();
        });
        return group;
    }

    /** 二级页版本行（整合包/光影/材质包）：文件名 + 下载数/日期 + 下载按钮 */
    private HBox packVerRow(PackDl st, MinecraftService.ModSearchResult mod, MinecraftService.ModVersion v, Label verStatus) {
        String fn = v.fileName() == null ? "" : v.fileName();
        int dot = fn.lastIndexOf('.');
        Label nameLbl = new Label(fn.isBlank() ? v.versionNumber() : (dot > 0 ? fn.substring(0, dot) : fn));
        nameLbl.getStyleClass().add("mod-title");
        Label subLbl = new Label("版本 " + v.versionNumber()
                + " · 下载 " + fmtCount(v.downloads()) + " 次 · " + fmtDate(v.datePublished()) + " 更新");
        subLbl.getStyleClass().addAll("muted", "small");
        VBox mid = new VBox(3, nameLbl, subLbl);
        HBox.setHgrow(mid, Priority.ALWAYS);

        Button dlBtn = new Button("下载");
        dlBtn.getStyleClass().add("accent-btn");
        dlBtn.setPrefWidth(84);
        dlBtn.setOnAction(e -> onPackDownloadVersion(st, mod, v, verStatus));

        HBox box = new HBox(14, mid, dlBtn);
        box.setAlignment(Pos.CENTER_LEFT);
        box.setPadding(new Insets(8, 10, 8, 10));
        return box;
    }

    /** 下载二级页选中的版本文件：光影/材质包装进对应目录，整合包下载后走整合包安装链路 */
    private void onPackDownloadVersion(PackDl st, MinecraftService.ModSearchResult mod,
                                       MinecraftService.ModVersion ver, Label verStatus) {
        if (mc.monitor.running()) { showToast("已有下载任务正在进行，请稍后再试", true); return; }
        String targetVer = st.targetVer == null ? null : st.targetVer.getValue();
        if (st.dirName != null && (targetVer == null || targetVer.isBlank())) {
            showToast("请先选择目标版本", true);
            return;
        }
        // 双源下载 URL（Modrinth 优先，下载缓慢/失败自动切 CurseForge 备用源）
        List<String> primary = new ArrayList<>();
        if (mod.projectId().isBlank() && !mod.cfProjectId().isBlank()) {
            try {
                primary.addAll(MinecraftService.cfFileUrls(Long.parseLong(ver.id()), ver.fileName(), mod.cfProjectId()));
            } catch (NumberFormatException ignored) {
                primary.add(ver.fileUrl());
            }
        } else {
            primary.add(ver.fileUrl());
        }
        java.util.function.Supplier<List<String>> fallback =
                mod.projectId().isBlank() || mod.cfProjectId().isBlank() ? null
                        : () -> mc.cfModDownloadUrls(mod.cfProjectId(),
                                ver.gameVersions().isEmpty() ? "" : ver.gameVersions().get(0),
                                ver.loaders().isEmpty() ? "" : ver.loaders().get(0),
                                ver.versionNumber().isBlank() ? ver.name() : ver.versionNumber());

        onDownloadStart((st.dirName == null ? "整合包安装：" : "下载" + st.label + "：") + mod.title());
        final int idx = mc.monitor.addBatch(List.of(ver.fileName()));
        mc.monitor.markDownloading(idx);
        verStatus.setText("正在下载 " + mod.title() + " " + ver.versionNumber() + "…");
        verStatus.setStyle("-fx-text-fill: #ffcf6a;");
        mc.monitor.setPhase(MinecraftService.Phase.DOWNLOAD, "正在下载 " + mod.title() + "…");

        if (st.dirName == null) {
            // 整合包：压缩包先下到临时目录，再走整合包安装链路（下载 mods + 解压 overrides + 生成版本）
            final Path tmpDir;
            try {
                tmpDir = Files.createTempDirectory("yhcl-pack");
            } catch (IOException io) {
                mc.monitor.markFailed(idx, io.getMessage());
                mc.monitor.finishFail(io.getMessage());
                onDownloadEnd();
                verStatus.setText("创建临时目录失败：" + io.getMessage());
                verStatus.setStyle("-fx-text-fill: #ff8a7a;");
                return;
            }
            mc.downloadModSmart(ver.fileName(), tmpDir, primary, fallback)
                .whenComplete((path, dlEx) -> {
                    if (dlEx != null) {
                        Throwable c = dlEx.getCause() != null ? dlEx.getCause() : dlEx;
                        String msg = c.getMessage() == null ? c.toString() : c.getMessage();
                        mc.monitor.markFailed(idx, msg);
                        mc.monitor.finishFail(msg);
                        Platform.runLater(() -> {
                            onDownloadEnd();
                            verStatus.setText("下载失败：" + msg);
                            verStatus.setStyle("-fx-text-fill: #ff8a7a;");
                            showToast("下载失败：" + msg, true);
                        });
                        return;
                    }
                    mc.monitor.markDone(idx, path);
                    // installModpack 自己推进下载阶段并 finishOk/finishFail
                    mc.installModpack(path, this::log).whenComplete((res, ex) -> Platform.runLater(() -> {
                        onDownloadEnd();
                        try { Files.deleteIfExists(path); } catch (Exception ignored) { }
                        if (ex != null) {
                            Throwable c = ex.getCause() != null ? ex.getCause() : ex;
                            String msg = c.getMessage() == null ? c.toString() : c.getMessage();
                            verStatus.setText("安装失败：" + msg);
                            verStatus.setStyle("-fx-text-fill: #ff8a7a;");
                            showToast("整合包安装失败：" + msg, true);
                            log("整合包安装失败：" + msg);
                            return;
                        }
                        String msg = "整合包安装完成：下载 " + res.installed() + " 个"
                                + (res.failed() > 0 ? "，失败 " + res.failed() + " 个" : "")
                                + "，overrides " + res.overrides() + " 个";
                        if (res.versionId() != null && !res.versionId().isBlank()) {
                            msg += "；已安装版本 " + res.versionId();
                            enableIsolationFor(res.versionId());   // 整合包默认开版本隔离
                            refreshInstalled();
                            if (installedVerBox != null) installedVerBox.getSelectionModel().select(res.versionId());
                        }
                        if (res.dependencies() != null && !res.dependencies().isBlank())
                            msg += "；依赖：" + res.dependencies();
                        verStatus.setText(msg);
                        verStatus.setStyle("-fx-text-fill: #7ec2c0;");
                        showToast(msg, res.failed() > 0);
                        log(msg);
                    }));
                });
            return;
        }

        // 光影/材质包：下载到目标版本实际生效的 shaderpacks / resourcepacks 目录
        final Path dir = packsDirFor(st.dirName, targetVer);
        mc.downloadModSmart(ver.fileName(), dir, primary, fallback)
            .whenComplete((path, ex) -> Platform.runLater(() -> {
                String msg = ex != null ? (ex.getCause() == null ? ex.getMessage() : ex.getCause().getMessage()) : null;
                if (msg != null) {
                    mc.monitor.markFailed(idx, msg);
                    mc.monitor.finishFail(msg);
                    onDownloadEnd();
                    verStatus.setText("下载失败：" + msg);
                    verStatus.setStyle("-fx-text-fill: #ff8a7a;");
                    showToast("下载失败：" + msg, true);
                } else {
                    mc.monitor.markDone(idx, path);
                    mc.monitor.finishOk();
                    onDownloadEnd();
                    verStatus.setText("下载完成：" + path.getFileName());
                    verStatus.setStyle("-fx-text-fill: #7ec2c0;");
                    showToast("已下载到 " + path, false);
                }
            }));
    }

    /** 光影/材质包的安装目录：「全部」或未开版本隔离放全局目录，否则放版本独立目录 */
    private Path packsDirFor(String dirName, String targetVer) {
        if (targetVer != null && !"全部".equals(targetVer) && isolationFor(targetVer))
            return mc.gameDir.resolve("versions").resolve(targetVer).resolve(dirName);
        return mc.gameDir.resolve(dirName);
    }

    /** Mod 搜索按钮回调：回到第 1 页重新搜索 */
    private void onModSearch() {
        modDlCurPage = 1;
        loadModPage();
    }

    /**
     * 游戏版本下拉数据：点击下载模块时自动获取（切到 Mod 下载不打断、不重复发起）。
     * 获取完成前下拉显示「正在加载」，完成后自动填入版本清单（「全部」在首位，保留用户当前值）。
     * 获取失败不置位，下次点击下载模块可重试。
     */
    private void ensureMcVersionChoices() {
        if (mcVerLoading || mcVerLoaded) return;
        mcVerLoading = true;
        if (modDlMcVersion != null) {
            modDlVerQuiet = true;
            modDlMcVersion.getItems().setAll("正在加载");
            modDlMcVersion.setValue("正在加载");
            modDlVerQuiet = false;
        }
        CompletableFuture.supplyAsync(() -> {
            try {
                return mc.getManifest();
            } catch (Exception e) {
                return List.<VersionEntry>of();
            }
        }).whenComplete((list, ex) -> Platform.runLater(() -> {
            mcVerLoading = false;
            List<String> ids = new ArrayList<>();
            ids.add("全部");
            if (list != null) for (VersionEntry v : list) {
                // 只留正式版：快照 / 旧 Beta / 旧 Alpha 都不进下拉
                if (v.id != null && !v.id.isBlank() && "release".equals(v.type)) ids.add(v.id);
            }
            if (ids.size() > 1) mcVerLoaded = true;
            mcVerIds = ids;
            applyMcVerChoices(ids);
            if (modDlMcVersion == null) return;
            String cur = modDlMcVersion.getValue();
            modDlVerQuiet = true;
            modDlMcVersion.getItems().setAll(ids);
            if (ids.size() > 1 && cur != null && !cur.isBlank() && !"正在加载".equals(cur)) {
                modDlMcVersion.setValue(cur);   // 保留用户已选的版本
            } else {
                modDlMcVersion.setValue("全部");
            }
            modDlVerQuiet = false;
        }));
    }

    /** 分类显示名 → Modrinth slug（「全部」或未知返回 "" = 不过滤） */
    private static String categorySlug(String name) {
        if (name == null || "全部".equals(name)) return "";
        for (String[] row : MinecraftService.MOD_CATEGORIES)
            if (row[0].equals(name)) return row[1];
        return "";
    }

    /** 加载第 modDlCurPage 页 Mod 列表（每页 MOD_PAGE_SIZE 个，翻页时重新请求 API） */
    private void loadModPage() {
        String q = modDlSearch == null ? "" : modDlSearch.getText();
        String mcVer = modDlMcVersion == null ? "" : modDlMcVersion.getValue();
        String loader = modDlLoader == null ? "" : modDlLoader.getValue();
        if (mcVer != null && (mcVer.isBlank() || "全部".equals(mcVer) || "正在加载".equals(mcVer))) mcVer = null;
        if (loader != null && (loader.isBlank() || "全部".equals(loader))) loader = null;
        String sort = modDlSort == null ? "" : modDlSort.getValue();
        boolean searching = q != null && !q.isBlank();
        // 搜索时不用热度排序：按名称与搜索词的接近度排序（API 取回按 relevance，页内再按名称接近度重排）
        String index = searching ? "relevance"
                : "热度".equals(sort) ? "downloads"
                : "最近更新".equals(sort) ? "updated"
                : "最多收藏".equals(sort) ? "follows" : "relevance";

        long seq = ++modDlSeq;
        modDlStatus.setText("正在加载第 " + modDlCurPage + " 页…");
        modDlStatus.setStyle("-fx-text-fill: #9eb4be;");
        int offset = (modDlCurPage - 1) * MOD_PAGE_SIZE;
        mc.searchModsPage(q, mcVer, loader, modDlCategory == null ? "" : modDlCategory, index, offset, MOD_PAGE_SIZE)
                .whenComplete((page, ex) -> Platform.runLater(() -> {
            if (seq != modDlSeq) return;   // 已发起新搜索/翻页，丢弃过期响应
            if (ex != null) {
                modDlStatus.setText("搜索失败：" + ex.getCause().getMessage());
                modDlStatus.setStyle("-fx-text-fill: #ff8a7a;");
                setModDlPlaceholder("搜索失败，请重试");
                modDlPageBar.getChildren().clear();
                return;
            }
            modDlTotal = page.totalHits();
            List<MinecraftService.ModSearchResult> list = page.items();
            if (searching) list = sortByTitleMatch(q, list);   // 搜索时按名称与搜索词的接近度排序（近的在前）
            if (list.isEmpty()) {
                setModDlPlaceholder(modDlCurPage > 1 ? "这一页没有内容，点上一页返回" : "没有找到匹配的 Mod");
            } else {
                modDlCards.getChildren().clear();
                for (MinecraftService.ModSearchResult r : list) modDlCards.getChildren().add(modCard("mod", r, () -> onModOpenDetail(r)));
                scheduleGlassApply();   // 新卡片套液态玻璃
            }
            buildModPageBar();
            modDlStatus.setText("共 " + fmtCount(modDlTotal) + " 个 Mod · 第 "
                    + modDlCurPage + " / " + modPageCount() + " 页");
        }));
    }

    /** 总页数（Modrinth 搜索最多返回约 1 万个结果，钳一下避免请求越界的页） */
    private int modPageCount() {
        long n = (modDlTotal + MOD_PAGE_SIZE - 1) / MOD_PAGE_SIZE;
        return (int) Math.max(1, Math.min(Math.max(n, modDlCurPage), 334));
    }

    /** 分页栏：上一页 + 页码（当前 ±2，首尾常驻，中间省略号）+ 下一页 */
    private void buildModPageBar() {
        modDlPageBar.getChildren().clear();
        if (modDlTotal <= 0 && modDlCurPage <= 1) return;
        int pages = modPageCount();
        modDlPageBar.getChildren().add(pageBtn("上一页", modDlCurPage - 1, modDlCurPage <= 1, false));

        java.util.TreeSet<Integer> shown = new java.util.TreeSet<>();
        shown.add(1);
        shown.add(pages);
        for (int p = modDlCurPage - 2; p <= modDlCurPage + 2; p++)
            if (p >= 1 && p <= pages) shown.add(p);
        int last = 0;
        for (int p : shown) {
            if (last != 0 && p > last + 1) {
                Label dots = new Label("…");
                dots.getStyleClass().addAll("muted", "small");
                modDlPageBar.getChildren().add(dots);
            }
            modDlPageBar.getChildren().add(pageBtn(String.valueOf(p), p, false, p == modDlCurPage));
            last = p;
        }
        modDlPageBar.getChildren().add(pageBtn("下一页", modDlCurPage + 1, modDlCurPage >= pages, false));
    }

    /** 分页按钮：点击即重新加载对应页并回到列表顶部 */
    private Button pageBtn(String text, int page, boolean disabled, boolean current) {
        Button b = new Button(text);
        b.getStyleClass().add(current ? "accent-btn" : "ghost-btn");
        b.setDisable(disabled);
        b.setOnAction(e -> {
            if (page == modDlCurPage) return;
            modDlCurPage = page;
            loadModPage();
            modDlScroll.setVvalue(0);
        });
        return b;
    }

    /** 用一行灰字占位替换卡片列表内容 */
    private void setModDlPlaceholder(String text) {
        Label ph = new Label(text);
        ph.getStyleClass().addAll("muted", "small");
        modDlCards.getChildren().setAll(ph);
    }

    /** 单击卡片进入二级页：Mod 简介 + 版本列表（PCL2 风格），在版本行里挑版本下载 */
    private void onModOpenDetail(MinecraftService.ModSearchResult item) {
        // 「下载到」控件搬到二级页（JavaFX 节点只有一个父容器，切换页面时挪动）
        modDlFilterRow.getChildren().removeAll(modDlTargetTip, modDlTargetVer, modDlBrowseBtn);
        HBox detailTargetRow = new HBox(8, modDlTargetTip, modDlTargetVer, modDlBrowseBtn);
        detailTargetRow.setAlignment(Pos.CENTER_LEFT);

        Button backBtn = new Button("← 返回列表");
        backBtn.getStyleClass().add("ghost-btn");
        backBtn.setOnAction(e -> {
            modDlFilterRow.getChildren().addAll(modDlTargetTip, modDlTargetVer, modDlBrowseBtn);
            modDlScroll.setContent(modDlListContent);
            modDlScroll.setVvalue(0);
            animateIn(modDlListContent);
        });

        /* ---- Mod 简介卡 ---- */
        ImageView icon = new ImageView();
        icon.setFitWidth(72);
        icon.setFitHeight(72);
        icon.setPreserveRatio(true);
        icon.setSmooth(true);
        Rectangle iconClip = new Rectangle(72, 72);
        iconClip.setArcWidth(20);
        iconClip.setArcHeight(20);
        icon.setClip(iconClip);
        icon.setOpacity(0.0);
        StackPane iconWrap = new StackPane(icon);
        iconWrap.setStyle("-fx-background-color: rgba(45,212,191,0.10); -fx-background-radius: 13;");
        iconWrap.setMinSize(72, 72);
        iconWrap.setMaxSize(72, 72);
        if (item.iconUrl() != null && !item.iconUrl().isBlank()) {
            loadModIcon(item.iconUrl(), icon);
        }
        Label nameLbl = new Label(item.title());
        nameLbl.getStyleClass().add("h2");
        Label authorLbl = new Label(item.author() == null || item.author().isBlank() ? "" : "作者 " + item.author());
        authorLbl.getStyleClass().addAll("muted", "small");
        Label summaryLbl = new Label(item.summary() == null ? "" : item.summary().replaceAll("\\s+", " ").trim());
        summaryLbl.getStyleClass().addAll("muted", "small");
        summaryLbl.setWrapText(true);
        summaryLbl.setMaxWidth(820);
        Label statLbl = new Label("下载 " + fmtCount(item.downloads()) + " · 收藏 " + fmtCount(item.follows())
                + " · " + fmtDate(item.dateModified()) + " 更新");
        statLbl.getStyleClass().add("mod-stat");
        VBox infoMid = new VBox(4, nameLbl, authorLbl, summaryLbl, statLbl);
        HBox.setHgrow(infoMid, Priority.ALWAYS);
        HBox infoCard = new HBox(16, iconWrap, infoMid);
        infoCard.setAlignment(Pos.CENTER_LEFT);
        infoCard.setPadding(new Insets(16, 18, 16, 18));
        infoCard.getStyleClass().add("mod-cell-card");

        Label verTitle = new Label("版本列表（点游戏版本标签筛选，点分组标题展开/收起）");
        verTitle.getStyleClass().add("h2");
        FlowPane verFilterRow = new FlowPane(8, 8);
        Label verStatus = new Label("正在加载版本列表…");
        verStatus.getStyleClass().addAll("muted", "small");
        VBox verGroupsBox = new VBox(10);

        VBox detail = new VBox(12, backBtn, infoCard, detailTargetRow, verTitle, verFilterRow, verStatus, verGroupsBox);
        modDlScroll.setContent(detail);
        modDlScroll.setVvalue(0);
        animateIn(detail);

        // CF-only 的 Mod 版本列表走 CurseForge，双源 Mod 走 Modrinth（页面不展示来源）
        CompletableFuture<List<MinecraftService.ModVersion>> verReq =
                item.projectId().isBlank() && !item.cfProjectId().isBlank()
                        ? mc.listCfModVersions(item.cfProjectId())
                        : mc.listModVersions(item.projectId());
        verReq.whenComplete((versions, ex) -> {
            if (ex != null) {
                Platform.runLater(() -> {
                    verStatus.setText("版本列表加载失败：" + (ex.getCause() == null ? ex.getMessage() : ex.getCause().getMessage()));
                    verStatus.setStyle("-fx-text-fill: #ff8a7a;");
                });
                return;
            }
            if (versions.isEmpty()) {
                Platform.runLater(() -> verStatus.setText("该 Mod 没有可下载的版本"));
                return;
            }
            // 分组计算放后台线程：大 Mod（上百游戏版本 × 上千版本）的组合遍历放 FX 线程会卡死界面
            ModVerModel model = computeModVerGroups(versions);
            Platform.runLater(() -> {
                if (detail.getScene() == null) return;   // 已退出详情页，过期响应作废
                // 版本在 200ms 内就到时入场动画的整页缓存还开着：先关缓存再建卡，
                // 否则分组卡进缓存会反复重建超大纹理，直接卡死
                if (detail.isCache()) {
                    detail.setCache(false);
                    detail.setCacheHint(CacheHint.DEFAULT);
                }
                verStatus.setText("共 " + versions.size() + " 个版本");
                buildModVersionGroups(item, model, verFilterRow, verGroupsBox, verStatus);
            });
        });
    }

    /** 预计算的版本分组模型：分组计算在后台线程完成，FX 线程只负责组装 UI */
    private record ModVerModel(Map<String, List<MinecraftService.ModVersion>> groups, List<String> chipTexts) { }

    /** 分组卡分批追加的每批张数：大 Mod 可有数百个「加载器+游戏版本」分组，一次性建卡+布局+套玻璃会卡死 UI */
    private static final int GROUP_BATCH = 40;
    /** 分批追加序号：切换筛选/退出详情页后旧批次作废 */
    private long groupBatchSeq;

    /**
     * 版本分组纯计算（后台线程执行）：单遍分桶 O(Σ游戏版本×加载器)，
     * 再按「游戏版本新→旧 × 加载器常用在前」重排分组，观感与旧三重循环一致。
     */
    private static ModVerModel computeModVerGroups(List<MinecraftService.ModVersion> versions) {
        // 收集游戏版本（新版本在前）与加载器（常用在前）
        java.util.LinkedHashSet<String> gvSet = new java.util.LinkedHashSet<>();
        java.util.LinkedHashSet<String> ldSet = new java.util.LinkedHashSet<>();
        for (MinecraftService.ModVersion v : versions) {
            gvSet.addAll(v.gameVersions());
            ldSet.addAll(v.loaders());
        }
        List<String> mcVers = new ArrayList<>(gvSet);
        List<String> loaders = new ArrayList<>(ldSet);
        mcVers.sort((a, b) -> compareMcVersion(b, a));
        String[] loaderOrder = { "neoforge", "forge", "fabric", "quilt" };
        loaders.sort((a, b) -> Integer.compare(loaderOrderIndex(a, loaderOrder), loaderOrderIndex(b, loaderOrder)));

        // 单遍分桶：key = 「加载器 游戏版本」；单版本去重后遍历，同键只进一次
        Map<String, List<MinecraftService.ModVersion>> buckets = new LinkedHashMap<>();
        for (MinecraftService.ModVersion v : versions) {
            java.util.LinkedHashSet<String> gvs = new java.util.LinkedHashSet<>(v.gameVersions());
            java.util.LinkedHashSet<String> lds = new java.util.LinkedHashSet<>(v.loaders());
            for (String ld : lds) {
                for (String gv : gvs) {
                    buckets.computeIfAbsent(loaderDisplayName(ld) + " " + gv, k -> new ArrayList<>()).add(v);
                }
            }
        }
        // 按「游戏版本 × 加载器」循环序重排分组（与旧三重循环的分组顺序一致）
        Map<String, List<MinecraftService.ModVersion>> groups = new LinkedHashMap<>();
        for (String gv : mcVers) {
            for (String ld : loaders) {
                String key = loaderDisplayName(ld) + " " + gv;
                List<MinecraftService.ModVersion> list = buckets.get(key);
                if (list != null) groups.put(key, list);
            }
        }

        // 游戏版本筛选标签（「全部」+ 正式版 x.y 主线，PCL2 式）：快照/预发布版归到对应主线标签
        List<String> chipTexts = new ArrayList<>();
        chipTexts.add("全部");
        for (String gv : mcVers) {
            String base = gv.split("-")[0];
            if (!base.matches("\\d+(\\.\\d+)*")) continue;   // 26w14a 这类周快照不单独出标签
            String[] seg = base.split("\\.");
            String line = seg.length >= 2 ? seg[0] + "." + seg[1] : base;
            if (!chipTexts.contains(line)) chipTexts.add(line);
        }
        return new ModVerModel(groups, chipTexts);
    }

    /** 二级页版本列表（PCL2 式）：游戏版本筛选标签 + 「加载器 + 游戏版本」分组折叠卡片 */
    private void buildModVersionGroups(MinecraftService.ModSearchResult mod, ModVerModel model,
                                       FlowPane filterRow, VBox groupsBox, Label verStatus) {
        Map<String, ModVerGroup> groupCards = new LinkedHashMap<>();
        ToggleGroup tg = new ToggleGroup();
        List<ToggleButton> chips = new ArrayList<>();
        for (String text : model.chipTexts()) {
            ToggleButton chip = new ToggleButton(text);
            chip.getStyleClass().add("chip");
            chip.setToggleGroup(tg);
            if ("全部".equals(text)) chip.setSelected(true);
            chips.add(chip);
        }
        filterRow.getChildren().setAll(chips);

        Runnable applyFilter = () -> {
            Toggle sel = tg.getSelectedToggle();
            String gv = sel == null ? "全部" : ((ToggleButton) sel).getText();
            groupsBox.getChildren().clear();
            // 组 key = 「加载器 游戏版本」，取后缀匹配标签主线（"1.20" 命中 "1.20.4"）
            List<Map.Entry<String, List<MinecraftService.ModVersion>>> shown = new ArrayList<>();
            for (Map.Entry<String, List<MinecraftService.ModVersion>> en : model.groups().entrySet()) {
                String keyGv = en.getKey().substring(en.getKey().indexOf(' ') + 1);
                if ("全部".equals(gv) || mcTagMatches(keyGv, gv)) shown.add(en);
            }
            appendGroupBatch(++groupBatchSeq, shown, 0, groupCards, groupsBox, mod, verStatus);
        };
        tg.selectedToggleProperty().addListener((o, a, b) -> applyFilter.run());
        applyFilter.run();
    }

    /** 分批建分组卡并追加（每批 GROUP_BATCH 张，批间让出 FX 线程）；换筛选/退出页面后旧批次作废 */
    private void appendGroupBatch(long ticket, List<Map.Entry<String, List<MinecraftService.ModVersion>>> shown,
                                  int from, Map<String, ModVerGroup> groupCards, VBox groupsBox,
                                  MinecraftService.ModSearchResult mod, Label verStatus) {
        if (ticket != groupBatchSeq || groupsBox.getScene() == null) return;
        int to = Math.min(from + GROUP_BATCH, shown.size());
        for (int i = from; i < to; i++) {
            Map.Entry<String, List<MinecraftService.ModVersion>> en = shown.get(i);
            ModVerGroup g = groupCards.get(en.getKey());
            if (g == null) {
                g = modVerGroup(en.getKey(), en.getValue(), mod, verStatus);
                groupCards.put(en.getKey(), g);
            }
            groupsBox.getChildren().add(g.box);
        }
        scheduleGlassApply();   // 分组卡增删后补套玻璃
        if (to < shown.size()) {
            int next = to;
            Platform.runLater(() -> appendGroupBatch(ticket, shown, next, groupCards, groupsBox, mod, verStatus));
        }
    }

    /** 标签主线匹配："1.20" 命中 "1.20"/"1.20.4"/"1.20.4-rc-1"，但不命中 "1.201" */
    private static boolean mcTagMatches(String gameVersion, String tag) {
        String base = gameVersion.split("-")[0];
        return base.equals(tag) || base.startsWith(tag + ".");
    }

    /** 二级页分组卡片：点标题行折叠/展开下面的版本行 */
        private static final class ModVerGroup {
        final VBox box;
        private final VBox rows;
        private final Label arrow;
        private final Runnable buildRows;
        private boolean built;
        ModVerGroup(VBox box, VBox rows, Label arrow, Runnable buildRows) {
            this.box = box; this.rows = rows; this.arrow = arrow; this.buildRows = buildRows;
        }
        void setExpanded(boolean show) {
            // 版本行懒构建：首次展开才建，避免进详情页一次性创建几千个节点卡死 UI
            if (show && !built) { built = true; buildRows.run(); }
            rows.setVisible(show);
            rows.setManaged(show);
            arrow.setText(show ? "▲" : "▼");
        }
    }

    private ModVerGroup modVerGroup(String key, List<MinecraftService.ModVersion> items,
                                    MinecraftService.ModSearchResult mod, Label verStatus) {
        Label nameLbl = new Label(key);
        nameLbl.getStyleClass().add("mod-title");
        Label countLbl = new Label("共 " + items.size() + " 个版本");
        countLbl.getStyleClass().addAll("muted", "small");
        Label arrow = new Label("▲");
        arrow.getStyleClass().addAll("muted", "small");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox head = new HBox(10, nameLbl, countLbl, spacer, arrow);
        head.setAlignment(Pos.CENTER_LEFT);
        head.setPadding(new Insets(10, 12, 10, 14));
        head.setCursor(Cursor.HAND);

                VBox rows = new VBox(6);
        rows.setPadding(new Insets(2, 4, 6, 4));
        VBox box = new VBox(head, rows);
        box.getStyleClass().add("mod-cell-card");

        ModVerGroup group = new ModVerGroup(box, rows, arrow,
                () -> { for (MinecraftService.ModVersion v : items) rows.getChildren().add(modVerRow(mod, v, verStatus)); });
        group.setExpanded(false);   // 默认折叠（标题上显示数量），展开时才建行
        head.setOnMouseClicked(e -> {
            group.setExpanded(!rows.isVisible());
            scheduleGlassApply();   // 展开/收起改变卡片高度，重合成玻璃图
        });
        return group;
    }

    /** 二级页版本行：文件名 + 加载器/下载数/日期 + 下载按钮 */
    private HBox modVerRow(MinecraftService.ModSearchResult mod, MinecraftService.ModVersion v, Label verStatus) {
        String fn = v.fileName() == null ? "" : v.fileName();
        if (fn.toLowerCase(Locale.ROOT).endsWith(".jar")) fn = fn.substring(0, fn.length() - 4);
        Label nameLbl = new Label(fn.isBlank() ? v.versionNumber() : fn);
        nameLbl.getStyleClass().add("mod-title");
        Label subLbl = new Label(String.join("/", v.loaders())
                + " · 版本 " + v.versionNumber()
                + " · 下载 " + fmtCount(v.downloads()) + " 次 · " + fmtDate(v.datePublished()) + " 更新");
        subLbl.getStyleClass().addAll("muted", "small");
        VBox mid = new VBox(3, nameLbl, subLbl);
        HBox.setHgrow(mid, Priority.ALWAYS);

        Button dlBtn = new Button("下载");
        dlBtn.getStyleClass().add("accent-btn");
        dlBtn.setPrefWidth(84);
        dlBtn.setOnAction(e -> onModDownloadVersion(mod, v, verStatus));

        HBox box = new HBox(14, mid, dlBtn);
        box.setAlignment(Pos.CENTER_LEFT);
        box.setPadding(new Insets(8, 10, 8, 10));
        return box;
    }

    private static int loaderOrderIndex(String ld, String[] loaderOrder) {
        for (int i = 0; i < loaderOrder.length; i++) if (loaderOrder[i].equals(ld)) return i;
        return loaderOrder.length;
    }

    private static String loaderDisplayName(String ld) {
        if (ld == null || ld.isBlank()) return "";
        switch (ld.toLowerCase(Locale.ROOT)) {
            case "neoforge": return "NeoForge";
            case "forge": return "Forge";
            case "fabric": return "Fabric";
            case "quilt": return "Quilt";
            case "liteloader": return "LiteLoader";
            default: return Character.toUpperCase(ld.charAt(0)) + ld.substring(1);
        }
    }

    /** "1.20.4"/"26.3"/"26.4-snapshot-5" 解析成 3 个数字段（忽略快照后缀） */
    private static int[] mcVerParts(String v) {
        String[] seg = (v == null ? "" : v).split("[^0-9.]+")[0].split("\\.");
        int[] out = new int[3];
        for (int i = 0; i < 3 && i < seg.length; i++) {
            try { out[i] = Integer.parseInt(seg[i]); } catch (NumberFormatException ignored) { }
        }
        return out;
    }

    private static int compareMcVersion(String a, String b) {
        int[] pa = mcVerParts(a), pb = mcVerParts(b);
        for (int i = 0; i < 3; i++) if (pa[i] != pb[i]) return Integer.compare(pa[i], pb[i]);
        return 0;
    }

    /** 该版本是否带加载器（Fabric/Forge/NeoForge 等）：Mod 只能下进带加载器的版本，原版装不了 */
    private boolean hasLoader(String id) {
        return id != null && !mc.getMcVersion(id).equals(id);
    }

    /** 下载二级页选中的版本文件到目标版本的 mods/ */
    private void onModDownloadVersion(MinecraftService.ModSearchResult mod, MinecraftService.ModVersion ver, Label verStatus) {
        if (mc.monitor.running()) { showToast("已有下载任务正在进行，请稍后再试", true); return; }
        String targetVer = modDlTargetVer == null ? null : modDlTargetVer.getValue();
        if (targetVer == null || targetVer.isBlank()) {
            showToast("请先选择目标版本", true);
            return;
        }
        // 防呆：原版游戏没有加载器，装 Mod 不会生效，禁止下载进去
        if (!"全部".equals(targetVer) && !hasLoader(targetVer)) {
            showToast("原版游戏没有加载器，无法下载 Mod，请选择带加载器的版本", true);
            return;
        }
        // 「全部」= 不指定版本，下载到默认 mods 目录；指定版本则跟随版本隔离设置取实际生效的 mods 目录
        final Path modsDir = "全部".equals(targetVer)
                ? mc.gameDir.resolve("mods")
                : modsDirFor(targetVer);

        // 接入下载监控：悬浮球 + 下载详情页可视化
        onDownloadStart("下载 Mod：" + mod.title());
        final int idx = mc.monitor.addBatch(List.of(ver.fileName()));
        mc.monitor.markDownloading(idx);

        verStatus.setText("正在下载 " + mod.title() + " " + ver.versionNumber() + "…");
        verStatus.setStyle("-fx-text-fill: #ffcf6a;");
        mc.monitor.setPhase(MinecraftService.Phase.DOWNLOAD, "正在下载 " + mod.title() + "…");

        // 双源下载：Modrinth 优先，下载缓慢/失败时自动切换 CurseForge 备用源（页面不展示来源）
        List<String> primary = new ArrayList<>();
        if (mod.projectId().isBlank() && !mod.cfProjectId().isBlank()) {
            // CF-only Mod：版本行直接走 CF CDN 主机组
            try {
                primary.addAll(MinecraftService.cfFileUrls(Long.parseLong(ver.id()), ver.fileName(), mod.cfProjectId()));
            } catch (NumberFormatException ignored) {
                primary.add(ver.fileUrl());
            }
        } else {
            primary.add(ver.fileUrl());
        }
        java.util.function.Supplier<List<String>> fallback =
                mod.projectId().isBlank() || mod.cfProjectId().isBlank() ? null
                        : () -> mc.cfModDownloadUrls(mod.cfProjectId(),
                                ver.gameVersions().isEmpty() ? "" : ver.gameVersions().get(0),
                                ver.loaders().isEmpty() ? "" : ver.loaders().get(0),
                                ver.versionNumber().isBlank() ? ver.name() : ver.versionNumber());

        mc.downloadModSmart(ver.fileName(), modsDir, primary, fallback)
            .whenComplete((path, ex) -> Platform.runLater(() -> {
                String msg = ex != null ? (ex.getCause() == null ? ex.getMessage() : ex.getCause().getMessage()) : null;
                if (msg != null) {
                    mc.monitor.markFailed(idx, msg);
                    mc.monitor.finishFail(msg);
                    onDownloadEnd();
                    verStatus.setText("下载失败：" + msg);
                    verStatus.setStyle("-fx-text-fill: #ff8a7a;");
                    showToast("下载失败：" + msg, true);
                } else {
                    mc.monitor.markDone(idx, path);
                    mc.monitor.finishOk();
                    onDownloadEnd();
                    verStatus.setText("下载完成：" + path.getFileName());
                    verStatus.setStyle("-fx-text-fill: #7ec2c0;");
                    showToast("已下载到 " + path, false);
                    // 刷新本地 mods 列表（如果玩家当前在版本设置页）
                    refreshLocalModsIfVisible();
                }
            }));
    }

    /** 如果版本设置页当前在显示，刷新它的 mods 列表 */
    private void refreshLocalModsIfVisible() {
        if (currentPage == verSetPage) {
            if (modList != null) refreshMods();
        }
    }

    /** 大数字格式化：1234 → 1234；12345 → 1.2万；1.2亿 */
    private static String fmtCount(long n) {
        if (n >= 100000000L) return String.format("%.1f亿", n / 100000000.0);
        if (n >= 10000L) return String.format("%.1f万", n / 10000.0);
        return String.valueOf(n);
    }

    /** Modrinth 的 ISO 时间（2026-01-02T15:04:05.000Z）截成 yyyy-MM-dd */
    private static String fmtDate(String iso) {
        if (iso == null || iso.length() < 10) return "未知日期";
        return iso.substring(0, 10);
    }

    /** Mod 搜索结果卡片（PCL2 风格）：大图标 + 标题/作者 + 简介 + 统计行 + 右上角星形收藏键 + 右侧详情 */
    private StackPane modCard(String kind, MinecraftService.ModSearchResult item, Runnable onOpen) {
        ImageView icon = new ImageView();
        icon.setFitWidth(56);
        icon.setFitHeight(56);
        icon.setPreserveRatio(true);
        icon.setSmooth(true);
        Rectangle iconClip = new Rectangle(56, 56);
        iconClip.setArcWidth(18);
        iconClip.setArcHeight(18);
        icon.setClip(iconClip);
        icon.setOpacity(0.0);   // 没图标时隐藏占位
        StackPane iconWrap = new StackPane(icon);
        iconWrap.setStyle("-fx-background-color: rgba(45,212,191,0.10); -fx-background-radius: 11;");
        iconWrap.setMinSize(56, 56);
        iconWrap.setMaxSize(56, 56);

        Label titleLbl = new Label(item.title());
        titleLbl.getStyleClass().add("mod-title");
        Label authorLbl = new Label(item.author() == null || item.author().isBlank() ? "" : "作者 " + item.author());
        authorLbl.getStyleClass().addAll("muted", "small");
        Label summaryLbl = new Label();
        summaryLbl.getStyleClass().addAll("muted", "small");
        summaryLbl.setWrapText(true);
        summaryLbl.setMaxWidth(620);
        String s = item.summary() == null ? "" : item.summary().replaceAll("\\s+", " ").trim();
        summaryLbl.setText(s.length() > 110 ? s.substring(0, 110) + "…" : s);
        Label statLbl = new Label("下载 " + fmtCount(item.downloads()) + " · 收藏 " + fmtCount(item.follows())
                + " · " + fmtDate(item.dateModified()) + " 更新");
        statLbl.getStyleClass().add("mod-stat");

        // 标签：加载器 + 分类（去重后最多 4 个）
        HBox tagRow = new HBox(6);
        tagRow.setAlignment(Pos.CENTER_LEFT);
        List<String> tags = new ArrayList<>();
        if (item.loaders() != null) tags.addAll(item.loaders());
        if (item.categories() != null) tags.addAll(item.categories());
        for (String t : tags.stream().distinct().limit(4).toList()) {
            Label chip = new Label(t);
            chip.getStyleClass().add("mod-tag");
            tagRow.getChildren().add(chip);
        }

        HBox subRow = new HBox(10, authorLbl, tagRow);
        subRow.setAlignment(Pos.CENTER_LEFT);
        VBox mid = new VBox(3, titleLbl, subRow, summaryLbl, statLbl);
        HBox.setHgrow(mid, Priority.ALWAYS);

        Label hintLbl = new Label("详情 ›");
        hintLbl.getStyleClass().addAll("muted", "small");

        /* ---- 星形收藏键（悬浮在卡片右上角）：
           未收藏：默认隐藏，鼠标悬停该卡片时显示空心星；
           已收藏：常驻黄色实心星。 ---- */
        SVGPath star = new SVGPath();
        star.setContent("M12 2l2.9 6.6 7.1.6-5.4 4.7 1.6 7-6.2-3.7-6.2 3.7 1.6-7L2 9.2l7.1-.6z");
        star.setStrokeWidth(1.6);
        star.setStrokeLineJoin(StrokeLineJoin.ROUND);
        StackPane starWrap = new StackPane(star);
        starWrap.setMinSize(30, 30);
        starWrap.setPickOnBounds(true);   // 整块 30×30 都是点击热区
        starWrap.setCursor(Cursor.HAND);
        applyFavStar(star, kind, item);
        starWrap.setOnMouseClicked(e -> {
            e.consume();   // 别再触发卡片整行的打开详情
            toggleFav(kind, item);
            applyFavStar(star, kind, item);
            starWrap.setVisible(isFav(kind, item) || starWrap.isHover());
            // 收藏页里取消收藏后立即刷新列表
            if (favPage != null && downloadHubContentHolder != null
                    && downloadHubContentHolder.getChildren().contains(favPage)) refreshFavPage();
        });

        HBox box = new HBox(14, iconWrap, mid, hintLbl);
        box.setAlignment(Pos.CENTER_LEFT);
        box.setPadding(new Insets(14, 16, 14, 16));
        box.getStyleClass().add("mod-cell-card");
        box.setCursor(Cursor.HAND);
        // 单击卡片进入二级页（Mod 简介 + 版本列表），在二级页里选版本下载
        box.setOnMouseClicked(e -> onOpen.run());

        // 星形悬浮层钉在卡片右上角：已收藏常驻显示，未收藏仅悬停时出现
        StackPane card = new StackPane(box, starWrap);
        StackPane.setAlignment(starWrap, Pos.TOP_RIGHT);
        StackPane.setMargin(starWrap, new Insets(5, 12, 0, 0));
        starWrap.setVisible(isFav(kind, item));
        card.hoverProperty().addListener((o, was, now) ->
                starWrap.setVisible(now || isFav(kind, item)));

        // 异步加载图标
        if (item.iconUrl() != null && !item.iconUrl().isBlank()) {
            loadModIcon(item.iconUrl(), icon);
        }
        return card;
    }

    /* ==================== Mod 图标加载 ====================
     * Modrinth 的 icon_url 大多是 _96.webp 缩略图，JavaFX Image 不支持 WebP，
     * 直接 new Image(url) 会静默失败（只有 png 图标能显示）。
     * 这里后台下载字节后用 ImageIO（webp-imageio SPI）自解码成 BufferedImage 再转 JavaFX Image。 */
    private static final int ICON_CACHE_MAX = 300;
    private static final Map<String, Image> ICON_CACHE = Collections.synchronizedMap(
            new LinkedHashMap<String, Image>(64, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, Image> e) {
                    return size() > ICON_CACHE_MAX;
                }
            });
    private static final Set<String> ICON_LOADING = ConcurrentHashMap.newKeySet();

        /** 同一 URL 的所有等待 view：下载完成后统一回填（只更新发起 view 会让下载期间新建的 view 永远 opacity=0 不显示） */
    private static final Map<String, Set<ImageView>> ICON_WAITERS = new ConcurrentHashMap<>();

    static {
        // 强制扫描 classpath 插件，确保 webp-imageio SPI 注册（缺 jar 时解码会返回 null）
        try { ImageIO.scanForPlugins(); } catch (Exception ignored) { }
    }

    private void loadModIcon(String url, ImageView view) {
        Image cached = ICON_CACHE.get(url);
        if (cached != null) {
            view.setImage(cached);
            view.setOpacity(1.0);
            return;
        }
        ICON_WAITERS.computeIfAbsent(url, k -> ConcurrentHashMap.newKeySet()).add(view);
        if (!ICON_LOADING.add(url)) return;   // 已在下载：view 已注册，完成时随 waiters 一起回填
                pool.execute(() -> {
            Image loaded = null;
            try {
                Path disk = iconDiskCacheDir().resolve(iconCacheKey(url) + ".img");
                byte[] data = null;
                if (Files.isRegularFile(disk)) {
                    try { data = Files.readAllBytes(disk); } catch (IOException ignored) { }
                }
                if (data == null) {   // 磁盘 miss 才走网络，下载成功回写磁盘（重启后秒出）
                    data = mc.fetchIcon(url);
                    if (data != null) {
                        try {
                            Files.createDirectories(disk.getParent());
                            Files.write(disk, data);
                        } catch (IOException ignored) { }
                    }
                }
                if (data != null) loaded = decodeFxImage(data);
            } catch (Exception ignored) { }
            Launcher.diag("loadModIcon done url=" + url + " ok=" + (loaded != null)
                    + " waiters=" + (ICON_WAITERS.get(url) == null ? 0 : ICON_WAITERS.get(url).size()));
            if (loaded != null) ICON_CACHE.put(url, loaded);   // 先入缓存再撤标记，堵住 miss 窗口
            ICON_LOADING.remove(url);
            Set<ImageView> waiters = ICON_WAITERS.remove(url);
            if (loaded == null || waiters == null) return;
            Image img = loaded;
            Platform.runLater(() -> {
                for (ImageView v : waiters) {
                    v.setImage(img);
                    v.setOpacity(1.0);
                }
            });
        });
    }

    /** 图标磁盘缓存目录：PCL2 式秒出的关键——重启后直接读盘，免重复走海外 CDN */
    private Path iconDiskCacheDir() {
        return mc.gameDir.resolve("cache").resolve("icons");
    }

    private static String iconCacheKey(String url) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(url.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(h.length * 2);
            for (byte b : h) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            return "u" + Integer.toHexString(url.hashCode());
        }
    }

    /** 字节流解码为 JavaFX Image（png/jpg/gif/bmp/webp，webp 走 webp-imageio SPI） */
    private static Image decodeFxImage(byte[] data) throws IOException {
                BufferedImage bi = ImageIO.read(new ByteArrayInputStream(data));
        if (bi == null) {
            // 常见根因：运行 classpath 缺 lib/webp-imageio.jar → ImageIO 无 webp reader → 返回 null
            String magic = data.length >= 4 ? new String(data, 0, 4, StandardCharsets.ISO_8859_1) : "?";
            System.err.println("[icon] ImageIO decode failed: bytes=" + data.length + ", magic=" + magic);
            return null;
        }
        int w = bi.getWidth(), h = bi.getHeight();
        WritableImage img = new WritableImage(w, h);
        img.getPixelWriter().setPixels(0, 0, w, h, PixelFormat.getIntArgbInstance(),
                bi.getRGB(0, 0, w, h, null, 0, w), 0, w);
        return img;
    }

    private void applyTypeFilter(String f) {
        typeFilter = f;
        filterManifest();
    }

    /* ==================== 设置页 ==================== */
    private VBox buildSettings() {
        Label title = new Label("设置（改动自动保存）");
        title.getStyleClass().add("h2");

        /* 游戏目录 */
        Label dirTip = new Label("游戏目录");
        dirTip.getStyleClass().add("muted");
        dirField = new TextField(mc.gameDir.toString());
        dirField.getStyleClass().add("dark-box");
        HBox.setHgrow(dirField, Priority.ALWAYS);
        dirField.setOnAction(e -> applyGameDir());
        Button dirBtn = new Button("浏览…");
        dirBtn.getStyleClass().add("ghost-btn");
        dirBtn.setOnAction(e -> browseDir());
        VBox dirCard = new VBox(10, dirTip, new HBox(10, dirField, dirBtn));
        dirCard.getStyleClass().add("card");

        /* 内存分配（PCL2 风格：自动配置/自定义单选 + 自定义行内嵌滑块 + 双数值 + 双色进度条）
           滑块范围 = [MC 最低内存, 本机物理内存]，启动时自动检测 */
        ToggleGroup glMemG = new ToggleGroup();
        glMemAuto = memRadioBtn("自动配置", glMemG, !"custom".equals(globalMemMode));
        glMemCustom = memRadioBtn("自定义", glMemG, "custom".equals(globalMemMode));
        glMemG.selectedToggleProperty().addListener((o, ov, nv) -> {
            if (nv == null) { ov.setSelected(true); return; }
            globalMemMode = nv == glMemCustom ? "custom" : "auto";
            memSlider.setDisable(!"custom".equals(globalMemMode));
            updateGlMemLabels();
            saveConfig();
        });

        int defMem = defaultMemory();
        memSlider = new Slider(minMemMB, maxMemMB, defMem);
        memSlider.setBlockIncrement(256);
        memSlider.setMaxWidth(Double.MAX_VALUE);
        memSlider.setDisable(!"custom".equals(globalMemMode));
        memSlider.valueProperty().addListener((o, a, b) -> {
            int mb = (int) (Math.round(b.doubleValue() / 256.0) * 256);
            mb = Math.max(minMemMB, Math.min(mb, maxMemMB));
            mc.memoryMB = mb;
            updateGlMemLabels();
            saveConfig();
        });
        // 默认 Slider 皮肤会把轨道画得比控件更宽（左右各溢出约 89px），
        // 使可视轨道比 thumb 的可拖动范围长；把滑块裁剪到自身边界即可让两者对齐。
        Rectangle memClip = new Rectangle();
        memClip.widthProperty().bind(memSlider.widthProperty());
        memClip.heightProperty().bind(memSlider.heightProperty());
        memSlider.setClip(memClip);

        HBox memCustomRow = new HBox(14, glMemCustom);
        HBox.setHgrow(memSlider, Priority.ALWAYS);
        memCustomRow.getChildren().add(memSlider);
        memCustomRow.setAlignment(Pos.CENTER_LEFT);

        memLabel = new Label(memRangeText());
        memLabel.getStyleClass().addAll("muted", "small");

        // 底部双数值（已使用内存 | 游戏分配）+ 双色进度条，与版本设置页一致
        Label glUsedCap = new Label("已使用内存");
        glUsedCap.getStyleClass().addAll("muted", "small");
        glMemUsed = new Label("");
        glMemUsed.getStyleClass().add("mem-value");
        VBox glUsedCol = new VBox(3, glUsedCap, glMemUsed);

        Label glAllocCap = new Label("游戏分配");
        glAllocCap.getStyleClass().addAll("muted", "small");
        glMemAlloc = new Label("");
        glMemAlloc.getStyleClass().addAll("mem-value", "accent-text");
        VBox glAllocCol = new VBox(3, glAllocCap, glMemAlloc);
        glAllocCol.setAlignment(Pos.CENTER_RIGHT);
        HBox.setHgrow(glUsedCol, Priority.ALWAYS);
        HBox memValues = new HBox(30, glUsedCol, glAllocCol);
        memValues.setAlignment(Pos.CENTER_LEFT);

        glMemAllocBar = new ProgressBar(0);
        glMemAllocBar.getStyleClass().add("progress-alloc");
        glMemAllocBar.setMaxWidth(Double.MAX_VALUE);
        glMemBar = new ProgressBar(0);
        glMemBar.getStyleClass().add("progress-used");
        glMemBar.setMaxWidth(Double.MAX_VALUE);
        StackPane memBarStack = new StackPane(glMemAllocBar, glMemBar);

        VBox memCard = new VBox(10,
                new Label("内存分配"),
                glMemAuto,
                memCustomRow,
                memLabel,
                memValues,
                memBarStack);
        memCard.getStyleClass().add("card");
        ((Label) memCard.getChildren().getFirst()).getStyleClass().add("bold");
        updateGlMemLabels();

        /* 启动选项（Java 运行环境 + 版本隔离） */
        Label javaTip = new Label("启动选项");
        javaTip.getStyleClass().add("muted");
        autoJavaBox = new CheckBox("自动选择最合适的 Java（推荐）");
        autoJavaBox.getStyleClass().add("dark-check");
        autoJavaBox.setSelected(true);
        autoJavaBox.setOnAction(e -> {
            mc.autoJava = autoJavaBox.isSelected();
            javaField.setDisable(mc.autoJava);
            saveConfig();
        });
        javaField = new TextField();
        javaField.getStyleClass().add("dark-box");
        javaField.setPromptText("手动指定 javaw.exe 路径");
        javaField.setDisable(true);
        HBox.setHgrow(javaField, Priority.ALWAYS);
        javaField.setOnAction(e -> { mc.javaPath = javaField.getText().trim(); saveConfig(); });
        Button javaBtn = new Button("浏览…");
        javaBtn.getStyleClass().add("ghost-btn");
        javaBtn.setOnAction(e -> browseJava());
        Button scanBtn = new Button("重新扫描");
        scanBtn.getStyleClass().add("ghost-btn");
        scanBtn.setOnAction(e -> rescanJava());
        javaScanInfo = new Label("");
        javaScanInfo.getStyleClass().addAll("muted", "small");
        // 版本隔离（全局开关）：各版本以 versions/<id> 作为游戏目录，Mod / 存档互不干扰；
        // 单个版本可在「版本设置 → 版本隔离」里单独覆盖（开启 / 关闭 / 跟随全局设置）
        CheckBox isoBox = new CheckBox("版本隔离（每个版本使用独立 Mod / 存档目录）");
        isoBox.getStyleClass().add("dark-check");
        isoBox.setSelected(globalIsolation);
        isoBox.setOnAction(e -> {
            globalIsolation = isoBox.isSelected();
            saveConfig();
            if (modList != null) refreshMods();
        });
        Label isoTip = new Label("开启后各版本以 versions/<版本号> 作为游戏目录，Mod 互不干扰；单个版本可在「版本设置 → 版本隔离」里单独覆盖。");
        isoTip.getStyleClass().addAll("muted", "small");
        isoTip.setWrapText(true);
        VBox javaCard = new VBox(10, javaTip, autoJavaBox,
                new HBox(10, javaField, javaBtn, scanBtn), javaScanInfo, isoBox, isoTip);
        javaCard.getStyleClass().add("card");

        /* 下载（PCL2 风格「下载」区块：下载源 / 线程数 / 速度限制 / 目标文件夹说明 / SSL） */
        Label dlTip = new Label("下载");
        dlTip.getStyleClass().add("bold");
        String[] srcOpts = {
                "优先使用官方源，在加载缓慢时换用镜像源",
                "优先使用镜像源，在加载缓慢时换用官方源",
                "仅使用官方源",
                "仅使用镜像源"};

        Label fileSrcLb = new Label("文件下载源");
        fileSrcLb.getStyleClass().add("muted");
        fileSrcBox = new ComboBox<>();
        fileSrcBox.getStyleClass().add("dark-box");
        fileSrcBox.getItems().setAll(srcOpts);
        fileSrcBox.setMaxWidth(Double.MAX_VALUE);
        fileSrcBox.setValue(srcModeText(mc.fileSourceMode));
        fileSrcBox.valueProperty().addListener((o, a, b) -> {
            if (b == null) return;
            mc.fileSourceMode = srcModeOf(b);
            saveConfig();
        });

        Label metaSrcLb = new Label("版本列表源");
        metaSrcLb.getStyleClass().add("muted");
        metaSrcBox = new ComboBox<>();
        metaSrcBox.getStyleClass().add("dark-box");
        metaSrcBox.getItems().setAll(srcOpts);
        metaSrcBox.setMaxWidth(Double.MAX_VALUE);
        metaSrcBox.setValue(srcModeText(mc.metaSourceMode));
        metaSrcBox.valueProperty().addListener((o, a, b) -> {
            if (b == null) return;
            mc.metaSourceMode = srcModeOf(b);
            saveConfig();
        });

        Label threadLb = new Label("最大线程数");
        threadLb.getStyleClass().add("muted");
        threadSlider = new Slider(1, 256, mc.downloadThreads);
        threadSlider.setBlockIncrement(1);
        threadSlider.setMaxWidth(Double.MAX_VALUE);
        clipSlider(threadSlider);
        threadValLb = new Label(String.valueOf(mc.downloadThreads));
        threadValLb.getStyleClass().addAll("muted", "small");
        threadValLb.setMinWidth(36);
        threadValLb.setAlignment(Pos.CENTER_RIGHT);
        threadSlider.valueProperty().addListener((o, a, b) -> {
            int n = (int) Math.round(b.doubleValue());
            mc.setDownloadThreads(n);
            threadValLb.setText(String.valueOf(n));
            saveConfig();
        });
        HBox threadRow = new HBox(10, threadSlider, threadValLb);
        HBox.setHgrow(threadSlider, Priority.ALWAYS);

        Label speedLb = new Label("速度限制");
        speedLb.getStyleClass().add("muted");
        speedSlider = new Slider(0, 100, sliderFromSpeedKb(mc.speedLimitKb));
        speedSlider.setBlockIncrement(1);
        speedSlider.setMaxWidth(Double.MAX_VALUE);
        clipSlider(speedSlider);
        speedValLb = new Label(speedText(mc.speedLimitKb));
        speedValLb.getStyleClass().addAll("muted", "small");
        speedValLb.setMinWidth(76);
        speedValLb.setAlignment(Pos.CENTER_RIGHT);
        speedSlider.valueProperty().addListener((o, a, b) -> {
            int pos = (int) Math.round(b.doubleValue());
            mc.setSpeedLimitKb(pos >= 100 ? 0 : speedKbFromSlider(pos));
            speedValLb.setText(speedText(mc.speedLimitKb));
            saveConfig();
        });
        HBox speedRow = new HBox(10, speedSlider, speedValLb);
        HBox.setHgrow(speedSlider, Priority.ALWAYS);

        Label dlDirLb = new Label("目标文件夹");
        dlDirLb.getStyleClass().add("muted");
        Label dlDirNote = new Label("请在 启动 → 版本选择 → 文件夹列表 中更改下载目标文件夹。\n"
                + "在某个文件夹或游戏版本上右键，即可选择打开对应文件夹。");
        dlDirNote.getStyleClass().addAll("muted-2", "small");
        dlDirNote.setWrapText(true);

        GridPane dlGrid = new GridPane();
        dlGrid.setHgap(16);
        dlGrid.setVgap(12);
        ColumnConstraints dlCol1 = new ColumnConstraints();
        dlCol1.setMinWidth(Region.USE_PREF_SIZE);
        ColumnConstraints dlCol2 = new ColumnConstraints();
        dlCol2.setHgrow(Priority.ALWAYS);
        dlCol2.setFillWidth(true);
        dlGrid.getColumnConstraints().addAll(dlCol1, dlCol2);
        dlGrid.addRow(0, fileSrcLb, fileSrcBox);
        dlGrid.addRow(1, metaSrcLb, metaSrcBox);
        dlGrid.addRow(2, threadLb, threadRow);
        dlGrid.addRow(3, speedLb, speedRow);
        dlGrid.addRow(4, dlDirLb, dlDirNote);
        GridPane.setValignment(fileSrcLb, VPos.CENTER);
        GridPane.setValignment(metaSrcLb, VPos.CENTER);
        GridPane.setValignment(threadLb, VPos.CENTER);
        GridPane.setValignment(speedLb, VPos.CENTER);
        GridPane.setValignment(dlDirLb, VPos.TOP);

        sslBox = new CheckBox("在正版登录时验证 SSL 证书");
        sslBox.getStyleClass().add("dark-check");
        sslBox.setSelected(MicrosoftAuth.verifySsl);
        sslBox.setOnAction(e -> { MicrosoftAuth.verifySsl = sslBox.isSelected(); saveConfig(); });

        VBox dlCard = new VBox(12, dlTip, dlGrid, sslBox);
        dlCard.getStyleClass().add("card");

        /* JVM 参数 */
        Label jvmTip = new Label("Java 虚拟机参数");
        jvmTip.getStyleClass().add("muted");
        jvmField = new TextField(mc.jvmArgs);
        jvmField.getStyleClass().add("dark-box");
        jvmField.setOnAction(e -> { mc.jvmArgs = jvmField.getText().trim(); saveConfig(); });

        /* 游戏窗口与启动行为 */
        Label winTip = new Label("游戏窗口与启动行为");
        winTip.getStyleClass().add("muted");
        Label winTitleLb = new Label("游戏窗口标题");
        winTitleLb.getStyleClass().addAll("muted", "small");
        winTitleField = new TextField(windowTitle);
        winTitleField.getStyleClass().add("dark-box");
        winTitleField.setPromptText("默认");
        winTitleField.setOnAction(e -> { windowTitle = winTitleField.getText().trim(); saveConfig(); });
        winTitleField.focusedProperty().addListener((o, a, b) -> {
            if (!Boolean.TRUE.equals(b)) { windowTitle = winTitleField.getText().trim(); saveConfig(); }
        });
        Label customLb = new Label("自定义信息");
        customLb.getStyleClass().addAll("muted", "small");
        customInfoField = new TextField(customInfo);
        customInfoField.getStyleClass().add("dark-box");
        customInfoField.setPromptText("启动器/窗口上的自定义文案");
        customInfoField.setOnAction(e -> {
            customInfo = customInfoField.getText().trim();
            saveConfig();
            refreshTitleText();
        });
        customInfoField.focusedProperty().addListener((o, a, b) -> {
            if (!Boolean.TRUE.equals(b)) {
                customInfo = customInfoField.getText().trim();
                saveConfig();
                refreshTitleText();
            }
        });
        Label customNote = new Label("显示在启动器标题栏与游戏窗口标题上");
        customNote.getStyleClass().addAll("muted-2", "small");
        Label visLb = new Label("启动器可见性");
        visLb.getStyleClass().addAll("muted", "small");
        visBox = new ComboBox<>();
        visBox.getStyleClass().add("dark-box");
        visBox.getItems().setAll("游戏启动后仍保持不变", "隐藏启动器（游戏退出后恢复）", "退出启动器（游戏继续运行）");
        visBox.setValue("keep".equals(launcherVis) ? visBox.getItems().get(0)
                : "hide".equals(launcherVis) ? visBox.getItems().get(1) : visBox.getItems().get(2));
        visBox.valueProperty().addListener((o, a, b) -> {
            if (b == null) return;
            launcherVis = b == visBox.getItems().get(0) ? "keep"
                    : b == visBox.getItems().get(1) ? "hide" : "exit";
            saveConfig();
        });
        Label priLb = new Label("进程优先级");
        priLb.getStyleClass().addAll("muted", "small");
        priBox = new ComboBox<>();
        priBox.getStyleClass().add("dark-box");
        priBox.getItems().setAll("低", "中（平衡）", "高");
        priBox.setValue("low".equals(processPriority) ? "低"
                : "high".equals(processPriority) ? "高" : "中（平衡）");
        priBox.valueProperty().addListener((o, a, b) -> {
            if (b == null) return;
            processPriority = "低".equals(b) ? "low" : "高".equals(b) ? "high" : "normal";
            saveConfig();
        });
        Label sizeLb = new Label("窗口大小");
        sizeLb.getStyleClass().addAll("muted", "small");
        sizeBox = new ComboBox<>();
        sizeBox.getStyleClass().add("dark-box");
        sizeBox.getItems().setAll("默认", "1600x900", "1920x1080", "2560x1440");
        sizeBox.setValue("default".equals(windowSize) ? "默认"
                : sizeBox.getItems().contains(windowSize) ? windowSize : "默认");
        sizeBox.valueProperty().addListener((o, a, b) -> {
            if (b == null) return;
            windowSize = "默认".equals(b) ? "default" : b;
            saveConfig();
        });
        VBox winCard = new VBox(8, winTip, winTitleLb, winTitleField,
                customLb, customInfoField, customNote, visLb, visBox, priLb, priBox, sizeLb, sizeBox);
        winCard.getStyleClass().add("card");

        /* 离线皮肤（占位） */
        Label skinTip = new Label("离线皮肤");
        skinTip.getStyleClass().add("muted");
        Label skinWarn = new Label("通过覆盖原版默认皮肤贴图实现；资源包或 Mod 自带同名贴图时会被其覆盖。");
        skinWarn.getStyleClass().addAll("small");
        skinWarn.setStyle("-fx-text-fill:#d97706; -fx-border-color:#d97706; -fx-border-width:0 0 0 3; -fx-padding:4 8;");
        skinBox = new ComboBox<>();
        skinBox.getStyleClass().add("dark-box");
        skinBox.getItems().setAll("随机", "Steve", "Alex", "正版皮肤", "自定义");
        skinBox.setValue("random".equals(offlineSkin) ? "随机"
                : "steve".equals(offlineSkin) ? "Steve" : "alex".equals(offlineSkin) ? "Alex"
                : "official".equals(offlineSkin) ? "正版皮肤" : "自定义");
        skinBox.valueProperty().addListener((o, a, b) -> {
            if (b == null) return;
            offlineSkin = "随机".equals(b) ? "random" : "Steve".equals(b) ? "steve"
                    : "Alex".equals(b) ? "alex" : "正版皮肤".equals(b) ? "official" : "custom";
            saveConfig();
            refreshSkinPreview();
        });
        Label skinNote = new Label("仅对离线模式生效。随机＝每次启动在 Steve/Alex/自定义皮肤中随机；"
                + "正版皮肤＝使用同名正版玩家的皮肤；自定义＝使用下方导入的皮肤");
        skinNote.getStyleClass().addAll("muted-2", "small");
        skinNote.setWrapText(true);
        Label skinImportLb = new Label("自定义皮肤");
        skinImportLb.getStyleClass().addAll("muted", "small");
        skinPreview = new ImageView();
        skinPreview.setFitWidth(64);
        skinPreview.setFitHeight(64);
        skinPreview.setPreserveRatio(true);
        skinPreview.setSmooth(false);
        skinImportBtn = new Button("导入皮肤 PNG…");
        skinImportBtn.getStyleClass().add("ghost-btn");
        skinImportBtn.setOnAction(e -> onImportSkin());
        skinFileNote = new Label("");
        skinFileNote.getStyleClass().addAll("muted-2", "small");
        skinFileNote.setWrapText(true);
        VBox skinFileBox = new VBox(6, skinImportBtn, skinFileNote);
        HBox.setHgrow(skinFileBox, Priority.ALWAYS);
        HBox skinRow = new HBox(12, skinPreview, skinFileBox);
        skinRow.setAlignment(Pos.CENTER_LEFT);
        VBox skinCard = new VBox(10, skinTip, skinWarn, skinBox, skinNote, skinImportLb, skinRow);
        skinCard.getStyleClass().add("card");
        refreshSkinPreview();

        /* 自定义背景图 */
        Label bgTip = new Label("自定义背景图");
        bgTip.getStyleClass().add("muted");
        bgThumb = new ImageView();
        bgThumb.setFitWidth(120);
        bgThumb.setFitHeight(68);
        bgThumb.setPreserveRatio(true);
        bgThumb.setVisible(false);
        bgThumb.setManaged(false);
        Button bgChooseBtn = new Button("选择背景图…");
        bgChooseBtn.getStyleClass().add("ghost-btn");
        bgChooseBtn.setOnAction(e -> onChooseBg());
        Button bgResetBtn = new Button("恢复默认");
        bgResetBtn.getStyleClass().add("ghost-btn");
        bgResetBtn.setOnAction(e -> onResetBg());
        bgNote = new Label("");
        bgNote.getStyleClass().addAll("muted-2", "small");
        bgNote.setWrapText(true);
        HBox bgBtnRow = new HBox(10, bgChooseBtn, bgResetBtn);
        VBox bgBtnBox = new VBox(8, bgBtnRow, bgNote);
        HBox.setHgrow(bgBtnBox, Priority.ALWAYS);
        HBox bgRow = new HBox(12, bgThumb, bgBtnBox);
        bgRow.setAlignment(Pos.CENTER_LEFT);
        Label dimLb = new Label("背景蒙版");
        dimLb.getStyleClass().addAll("muted", "small");
        Label dimValLb = new Label(Math.round(customBgDim * 100) + "%");
        dimValLb.getStyleClass().addAll("muted", "small");
        dimValLb.setMinWidth(Region.USE_PREF_SIZE);
        dimSlider = new Slider(0, 80, Math.round(customBgDim * 100));
        dimSlider.getStyleClass().add("dark-box");
        // 默认 Slider 皮肤会把轨道画得比控件更宽，裁剪到自身边界让可见轨道与可拖动范围对齐
        Rectangle dimClip = new Rectangle();
        dimClip.widthProperty().bind(dimSlider.widthProperty());
        dimClip.heightProperty().bind(dimSlider.heightProperty());
        dimSlider.setClip(dimClip);
        dimSlider.valueProperty().addListener((o, a, b) -> {
            int pct = (int) Math.round(b.doubleValue());
            if (bgDim != null) bgDim.setOpacity(pct / 100.0);   // 拖动实时预览
            dimValLb.setText(pct + "%");
            if (!dimSlider.isValueChanging()) {
                customBgDim = pct / 100.0;
                saveConfig();
            }
            // 蒙版是整窗蒙版：卡片/导航栏玻璃采样自 bgWrap 快照（含蒙版层），
            // 蒙版一变必须作废采样源重合成，玻璃才随蒙版一起变灰/变亮；
            // 文字主题（rootPane.setStyle 全树失效）也随蒙版亮度跟随。
            // 快照+磨砂较贵，拖动时节流 150ms，松手（isValueChanging=false）必收尾一次
            long now = System.currentTimeMillis();
            if (bgLayer != null && bgLayer.isVisible()
                    && (!dimSlider.isValueChanging() || now - glassDimTick > 150)) {
                glassDimTick = now;
                regenGlassBackdrop();
                applyGlassStyle();
                applyAdaptiveTextColors();
            }
        });
        HBox dimRow = new HBox(10, dimLb, dimSlider, dimValLb);
        HBox.setHgrow(dimSlider, Priority.ALWAYS);
        VBox bgCard = new VBox(10, bgTip, bgRow, dimRow);
        bgCard.getStyleClass().add("card");
        refreshBgPreview();

        /* 高级选项 */
        Label advTip = new Label("高级选项");
        advTip.getStyleClass().add("muted");
        Label gameArgsLb = new Label("游戏参数");
        gameArgsLb.getStyleClass().addAll("muted", "small");
        gameArgsField = new TextField(gameArgs);
        gameArgsField.getStyleClass().add("dark-box");
        gameArgsField.setPromptText("拼接在启动参数末尾，空格分隔");
        gameArgsField.setOnAction(e -> { gameArgs = gameArgsField.getText().trim(); saveConfig(); });
        Label preCmdLb = new Label("启动前执行命令");
        preCmdLb.getStyleClass().addAll("muted", "small");
        preCmdField = new TextField(preLaunchCmd);
        preCmdField.getStyleClass().add("dark-box");
        preCmdField.setPromptText("例如 echo hello 或 start \"\" 某程序.exe");
        preCmdField.setOnAction(e -> { preLaunchCmd = preCmdField.getText().trim(); saveConfig(); });
        Label gcLb = new Label("内存管理");
        gcLb.getStyleClass().addAll("muted", "small");
        gcBox = new ComboBox<>();
        gcBox.getStyleClass().add("dark-box");
        gcBox.getItems().setAll("默认", "调优 G1GC（推荐）", "ZGC（低延迟）", "Shenandoah");
        gcBox.setValue("g1".equals(gcMode) ? gcBox.getItems().get(1)
                : "zgc".equals(gcMode) ? gcBox.getItems().get(2)
                : "shenandoah".equals(gcMode) ? gcBox.getItems().get(3) : gcBox.getItems().get(0));
        gcBox.valueProperty().addListener((o, a, b) -> {
            if (b == null) return;
            gcMode = b == gcBox.getItems().get(0) ? "default"
                    : b == gcBox.getItems().get(1) ? "g1"
                    : b == gcBox.getItems().get(2) ? "zgc" : "shenandoah";
            saveConfig();
        });
        chkLaunchWrap = new CheckBox("禁用 Java Launch Wrapper");
        chkLaunchWrap.getStyleClass().add("dark-check");
        chkLaunchWrap.setSelected(disableLaunchWrapper);
        chkLaunchWrap.setOnAction(e -> { disableLaunchWrapper = chkLaunchWrap.isSelected(); saveConfig(); });
        Label wrapNote = new Label("旧版 Forge 由 LaunchWrapper 启动；勾选后跳过 LaunchWrapper 直接以原版主类启动，"
                + "Mod 不会加载，仅用于排障");
        wrapNote.getStyleClass().addAll("muted-2", "small");
        wrapNote.setWrapText(true);
        chkLwjglAgent = new CheckBox("禁用 LWJGL Unsafe Agent");
        chkLwjglAgent.getStyleClass().add("dark-check");
        chkLwjglAgent.setSelected(disableLwjglAgent);
        chkLwjglAgent.setOnAction(e -> { disableLwjglAgent = chkLwjglAgent.isSelected(); saveConfig(); });
        Label lwjglNote = new Label("默认注入 LWJGL Unsafe Agent，放开 sun.misc.Unsafe 内存访问限制"
                + "（新版 Java 运行旧版 LWJGL 需要）；勾选后不再注入");
        lwjglNote.getStyleClass().addAll("muted-2", "small");
        lwjglNote.setWrapText(true);
        chkHighGpu = new CheckBox("使用高性能显卡");
        chkHighGpu.getStyleClass().add("dark-check");
        chkHighGpu.setSelected(highPerfGpu);
        chkHighGpu.setOnAction(e -> { highPerfGpu = chkHighGpu.isSelected(); saveConfig(); });
        Label gpuNote = new Label("启动游戏时为游戏 Java 设置 Windows 高性能显卡偏好");
        gpuNote.getStyleClass().addAll("muted-2", "small");
        Label advInfo = new Label("版本独立设置中还有更多高级选项可供调整。");
        advInfo.getStyleClass().addAll("muted", "small");
        Button vsShortcutBtn = new Button("→ 版本独立设置");
        vsShortcutBtn.getStyleClass().add("accent-btn");
        vsShortcutBtn.setOnAction(e -> { showPage(verSetPage, "版本设置"); refreshVerSettings(); });
        VBox advCard = new VBox(10, advTip, jvmTip, jvmField,
                gameArgsLb, gameArgsField, preCmdLb, preCmdField,
                gcLb, gcBox,
                new VBox(2, chkLaunchWrap, wrapNote),
                new VBox(2, chkLwjglAgent, lwjglNote),
                new VBox(2, chkHighGpu, gpuNote),
                advInfo, vsShortcutBtn);
        advCard.getStyleClass().add("card");

        /* 启动游戏前进行内存优化（挂到内存卡） */
        memOptBox = new CheckBox("启动游戏前进行内存优化");
        memOptBox.getStyleClass().add("dark-check");
        memOptBox.setSelected(memOptimize);
        memOptBox.setOnAction(e -> { memOptimize = memOptBox.isSelected(); saveConfig(); });
        memCard.getChildren().add(memOptBox);

        VBox panel = new VBox(16, title, dirCard, memCard, javaCard, dlCard, winCard, skinCard, bgCard, advCard);
        panel.getStyleClass().add("panel");

                ScrollPane scroll = new ScrollPane(panel);
        wrapScroll(scroll);

        VBox page = new VBox(scroll);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        return page;
    }

    private void applyGameDir() {
        String t = dirField.getText().trim();
        if (!t.isEmpty()) {
            mc.gameDir = Path.of(t);
            saveConfig();
            refreshInstalled();
        }
    }

    /* ==================== 设置页「下载」区块辅助 ==================== */

    /** 源选择模式 → 下拉框文案 */
    private static String srcModeText(String mode) {
        return switch (mode) {
            case "mirror_first" -> "优先使用镜像源，在加载缓慢时换用官方源";
            case "official_only" -> "仅使用官方源";
            case "mirror_only" -> "仅使用镜像源";
            default -> "优先使用官方源，在加载缓慢时换用镜像源";
        };
    }

    /** 下拉框文案 → 源选择模式 */
    private static String srcModeOf(String text) {
        return switch (text) {
            case "优先使用镜像源，在加载缓慢时换用官方源" -> "mirror_first";
            case "仅使用官方源" -> "official_only";
            case "仅使用镜像源" -> "mirror_only";
            default -> "official_first";
        };
    }

        /** 旧配置迁移：没有独立的源选择字段时，默认镜像优先（官方源国内直连太慢） */
    private static String normSrcMode(String saved, boolean legacyUseMirror) {
        if ("official_first".equals(saved) || "mirror_first".equals(saved)
                || "official_only".equals(saved) || "mirror_only".equals(saved)) return saved;
        return "mirror_first";
    }

    /* 速度限制滑块：位置 0~100 对数映射到 0.1KB/s ~ 100MB/s，位置 100 = 无限制 */
    private static final double SPEED_MIN_KB = 0.1, SPEED_MAX_KB = 100 * 1024;

    private static double speedKbFromSlider(double pos) {
        double kb = SPEED_MIN_KB * Math.pow(SPEED_MAX_KB / SPEED_MIN_KB, pos / 100.0);
        // 取 2 位有效数字，避免出现 17.3642KB/s 这类值
        double mag = Math.pow(10, Math.floor(Math.log10(kb)) - 1);
        return Math.round(kb / mag) * mag;
    }

    private static double sliderFromSpeedKb(double kb) {
        if (kb <= 0) return 100;
        double p = 100.0 * Math.log(kb / SPEED_MIN_KB) / Math.log(SPEED_MAX_KB / SPEED_MIN_KB);
        return Math.max(0, Math.min(100, p));
    }

    private static String speedText(double kbPerSec) {
        if (kbPerSec <= 0) return "无限制";
        return kbPerSec < 1024 ? trimZero(kbPerSec) + " KB/s" : trimZero(kbPerSec / 1024) + " MB/s";
    }

    private static String trimZero(double v) {
        String s = String.format(Locale.ROOT, "%.1f", v);
        return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
    }

    /** 默认 Slider 皮肤会把轨道画得比控件更宽（左右各溢出约 89px），
     *  使可视轨道比 thumb 的可拖动范围长；把滑块裁剪到自身边界即可让两者对齐。 */
    private static void clipSlider(Slider s) {
        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(s.widthProperty());
        clip.heightProperty().bind(s.heightProperty());
        s.setClip(clip);
    }

    /* ==================== 截图页面 ==================== */
    private VBox buildShots() {
        Label title = new Label("截图");
        title.getStyleClass().add("h2");

        Button refresh = new Button("刷新");
        refresh.getStyleClass().add("ghost-btn");
        refresh.setOnAction(e -> refreshShots());
        Button openDir = new Button("打开截图目录");
        openDir.getStyleClass().add("ghost-btn");
        openDir.setOnAction(e -> {
            List<Path> dirs = shotsDirs();
            Path target = dirs.isEmpty() ? mc.gameDir.resolve("screenshots") : dirs.get(0);
            Path t = target;
            pool.submit(() -> {
                try {
                    Files.createDirectories(t);
                    Desktop.getDesktop().open(t.toFile());
                } catch (Exception ex) {
                    Platform.runLater(() -> showToast("打开目录失败：" + ex.getMessage(), true));
                }
            });
        });
        HBox bar = new HBox(10, refresh, openDir);
        bar.setAlignment(Pos.CENTER_LEFT);

        shotsStatus = new Label("点击左侧「截图」自动识别游戏截图文件夹");
        shotsStatus.getStyleClass().addAll("muted", "small");

        shotsGrid = new FlowPane(12, 12);
        shotsGrid.setPrefWrapLength(960);

        VBox listContent = new VBox(14, title, bar, shotsStatus, shotsGrid);
        ScrollPane scroll = new ScrollPane(listContent);
        wrapScroll(scroll);

        VBox panel = new VBox(scroll);
        panel.getStyleClass().add("panel");
        VBox.setVgrow(scroll, Priority.ALWAYS);
        VBox.setVgrow(panel, Priority.ALWAYS);
        return panel;
    }

    /* ==================== 日志页 ==================== */
    private VBox buildLogs() {
        Label title = new Label("运行日志");
        title.getStyleClass().add("h2");

        Button clear = new Button("清空日志");
        clear.getStyleClass().add("ghost-btn");
        clear.setOnAction(e -> logArea.clear());
        Button openDir = new Button("打开游戏目录");
        openDir.getStyleClass().add("ghost-btn");
        openDir.setOnAction(e -> pool.submit(() -> {
            try {
                Files.createDirectories(mc.gameDir);
                Desktop.getDesktop().open(mc.gameDir.toFile());
            } catch (Exception ex) {
                log("打开目录失败：" + ex.getMessage());
            }
        }));
        HBox bar = new HBox(10, clear, openDir);
        bar.setAlignment(Pos.CENTER_LEFT);

        logArea = new TextArea();
        logArea.getStyleClass().add("log-area");
        logArea.setEditable(false);
        VBox.setVgrow(logArea, Priority.ALWAYS);

        VBox panel = new VBox(12, title, bar, logArea);
        panel.getStyleClass().add("panel");
        VBox page = new VBox(panel);
        VBox.setVgrow(panel, Priority.ALWAYS);
        return page;
    }

    /* ==================== Microsoft 正版登录 ==================== */
    private void onMicrosoftLogin() {
        msLoginBtn.setDisable(true);
        loginCancelled.set(false);
        pool.submit(() -> {
            try {
                log("正在向 Microsoft 申请登录代码…");
                MicrosoftAuth.DeviceCode dc = MicrosoftAuth.beginDeviceLogin();
                Platform.runLater(() -> showDeviceCodeDialog(dc));
                MicrosoftAuth.Result r =
                        MicrosoftAuth.completeDeviceLogin(dc, null, loginCancelled, this::log);
                Platform.runLater(() -> {
                    closeLoginDialog();
                    applyAccount(r);
                    saveConfig();
                    alert("登录成功", "欢迎，" + r.name + "！\n账户已保存，下次启动会自动续期。");
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    closeLoginDialog();
                    log("登录失败：" + ex.getMessage());
                    alert("登录失败", ex.getMessage());
                });
            } finally {
                Platform.runLater(() -> msLoginBtn.setDisable(false));
            }
        });
    }

    private void onMicrosoftLogout() {
        accountName = accountUuid = accountToken = accountRefresh = "";
        accountType = "";
        authApi = "";
        authClientToken = "";
        profile.isMicrosoft = false;
        profile.isYggdrasil = false;
        profile.authApi = "";
        profile.uuid = "";
        profile.accessToken = "";
        profile.name = userField.getText().trim().isEmpty() ? "Steve" : userField.getText().trim();
        updateAccountUi();
        saveConfig();
        log("已注销账户，切换到离线模式。");
    }

    /* ==================== 第三方登录（Authlib Injector / LittleSkin） ==================== */
    private void onYggdrLogin() {
        Stage st = new Stage();
        st.initModality(Modality.APPLICATION_MODAL);
        st.initOwner(rootPane.getScene().getWindow());
        st.setTitle("第三方登录（Authlib Injector / LittleSkin）");
        st.setResizable(false);

        Label tip = new Label("使用皮肤站（Yggdrasil 外置验证）账户登录，例如 LittleSkin。");
        tip.getStyleClass().add("muted");
        tip.setWrapText(true);

        TextField apiField = new TextField(authApi.isBlank() ? "https://littleskin.cn/api/yggdrasil" : authApi);
        apiField.getStyleClass().add("dark-box");
        apiField.setPromptText("https://littleskin.cn/api/yggdrasil");

        TextField nameField = new TextField("yggdrasil".equals(accountType) ? accountName : "");
        nameField.getStyleClass().add("dark-box");
        nameField.setPromptText("皮肤站用户名 / 邮箱");

        PasswordField passField = new PasswordField();
        passField.getStyleClass().add("dark-box");
        passField.setPromptText("皮肤站密码");

        Label err = new Label("");
        err.getStyleClass().addAll("accent-text", "small");
        err.setWrapText(true);

        Button loginBtn = new Button("登录");
        loginBtn.getStyleClass().add("accent-btn");
        Button cancelBtn = new Button("取消");
        cancelBtn.getStyleClass().add("ghost-btn");
        cancelBtn.setOnAction(e -> st.close());

        VBox box = new VBox(12, tip,
                vsRow("API 地址", apiField),
                vsRow("账户", nameField),
                vsRow("密码", passField),
                err, new HBox(10, loginBtn, cancelBtn));
        box.setPadding(new Insets(24));
        ((HBox) box.getChildren().getLast()).setAlignment(Pos.CENTER_RIGHT);

        loginBtn.setOnAction(e -> {
            String api = apiField.getText() == null ? "" : apiField.getText().trim();
            String un = nameField.getText() == null ? "" : nameField.getText().trim();
            String pw = passField.getText() == null ? "" : passField.getText();
            if (api.isBlank() || un.isBlank() || pw.isBlank()) {
                err.setText("请填写 API 地址、账户和密码。");
                return;
            }
            loginBtn.setDisable(true);
            err.setText("正在登录…");
            pool.submit(() -> {
                try {
                    YggdrasilAuth.Result r = YggdrasilAuth.authenticate(api, un, pw);
                    Platform.runLater(() -> {
                        accountName = r.name;
                        accountUuid = r.uuid;
                        accountToken = r.accessToken;
                        accountRefresh = "";
                        accountType = "yggdrasil";
                        authApi = api;
                        authClientToken = r.clientToken;
                        profile.name = r.name;
                        profile.uuid = r.uuid;
                        profile.accessToken = r.accessToken;
                        profile.isMicrosoft = false;
                        profile.isYggdrasil = true;
                        profile.authApi = api;
                        updateAccountUi();
                        saveConfig();
                        st.close();
                        log("第三方登录成功：" + r.name + "（" + api + "）");
                        alert("登录成功", "欢迎，" + r.name + "！\n启动游戏时将通过 Authlib Injector 使用该皮肤站账户。");
                    });
                } catch (Exception ex) {
                    Platform.runLater(() -> {
                        loginBtn.setDisable(false);
                        err.setText("登录失败：" + ex.getMessage());
                    });
                }
            });
        });

        Scene scene = new Scene(box);
        var css = getClass().getResource(DARKSLATE_CSS);
        if (css != null) scene.getStylesheets().add(css.toExternalForm());
        st.setScene(scene);
        st.show();
    }

    /** 设备码弹窗：显示 8 位代码，提供打开网页 / 复制代码 / 取消 */
    private void showDeviceCodeDialog(MicrosoftAuth.DeviceCode dc) {
        closeLoginDialog();
        Stage st = new Stage();
        st.initModality(Modality.APPLICATION_MODAL);
        st.initOwner(rootPane.getScene().getWindow());
        st.setTitle("Microsoft 正版登录");
        st.setResizable(false);

        Label tip = new Label("请在浏览器中打开下面的网址，输入此代码完成登录：");
        tip.getStyleClass().add("muted");
        tip.setWrapText(true);

        Label code = new Label(dc.userCode);
        code.getStyleClass().addAll("h1", "mono", "accent-text");

        Label url = new Label(dc.verifyUrl);
        url.getStyleClass().addAll("mono", "small", "muted");

        Button open = new Button("打开登录网页");
        open.getStyleClass().add("accent-btn");
        open.setOnAction(e -> pool.submit(() -> {
            try {
                Desktop.getDesktop().browse(URI.create(dc.verifyUrl));
            } catch (Exception ex) {
                log("无法自动打开浏览器，请手动访问 " + dc.verifyUrl);
            }
        }));
        Button copy = new Button("复制代码");
        copy.getStyleClass().add("ghost-btn");
        copy.setOnAction(e -> {
            ClipboardContent cc = new ClipboardContent();
            cc.putString(dc.userCode);
            Clipboard.getSystemClipboard().setContent(cc);
            copy.setText("已复制");
        });
        Button cancel = new Button("取消");
        cancel.getStyleClass().add("ghost-btn");
        cancel.setOnAction(e -> { loginCancelled.set(true); st.close(); });
        HBox btns = new HBox(10, open, copy, cancel);
        btns.setAlignment(Pos.CENTER);

        Label waiting = new Label("完成网页登录后，此窗口会自动关闭…");
        waiting.getStyleClass().addAll("muted", "small");

        VBox box = new VBox(14, tip, code, url, btns, waiting);
        box.setAlignment(Pos.CENTER);
        box.setPadding(new Insets(28));

        Scene scene = new Scene(box, 480, 280);
        var css = getClass().getResource(DARKSLATE_CSS);
        if (css != null) scene.getStylesheets().add(css.toExternalForm());
        st.setScene(scene);
        st.setOnCloseRequest(e -> loginCancelled.set(true));
        st.show();
        loginStage = st;
    }

    private void closeLoginDialog() {
        if (loginStage != null) {
            loginStage.close();
            loginStage = null;
        }
    }

    private void applyAccount(MicrosoftAuth.Result r) {
        accountName = r.name;
        accountUuid = r.uuid;
        accountToken = r.accessToken;
        accountRefresh = r.refreshToken;
        accountType = "microsoft";
        authApi = "";
        authClientToken = "";
        profile.name = r.name;
        profile.uuid = r.uuid;
        profile.accessToken = r.accessToken;
        profile.isMicrosoft = true;
        profile.isYggdrasil = false;
        profile.authApi = "";
        updateAccountUi();
    }

    private void updateAccountUi() {
        boolean logged = !accountToken.isBlank();
        boolean ygg = logged && "yggdrasil".equals(accountType);
        accountLabel.setText(logged ? (ygg ? "第三方 · " : "正版 · ") + accountName : "未登录（离线模式）");
        accountLabel.getStyleClass().removeAll(List.of("muted", "accent-text"));
        accountLabel.getStyleClass().add(logged ? "accent-text" : "muted");
        msLogoutBtn.setVisible(logged);
        msLogoutBtn.setManaged(logged);
        msLoginBtn.setText(logged ? "重新登录" : "Microsoft 正版登录");
        userField.setDisable(logged);
        if (yggdrNote != null) {
            yggdrNote.setText(ygg ? "已登录第三方账户：" + accountName + "（" + authApi + "）"
                    : "未登录第三方账户");
        }
    }

    /** 启动时用 refresh_token 静默续期 */
    private void silentRefresh() {
        if (accountRefresh.isBlank()) return;
        log("检测到已保存的账户，正在自动续期…");
        pool.submit(() -> {
            try {
                MicrosoftAuth.Result r = MicrosoftAuth.refresh(accountRefresh, this::log);
                Platform.runLater(() -> {
                    applyAccount(r);
                    saveConfig();
                });
            } catch (Exception ex) {
                Platform.runLater(() ->
                        log("自动续期失败（可能是网络问题）：" + ex.getMessage()
                                + "。可稍后重启启动器或重新登录。"));
            }
        });
    }

    /* ==================== 启动游戏 ==================== */

    /** 统一退出清理（关闭按钮 / 系统关闭 / 「退出启动器」模式共用）。清理全放后台线程做，FX 线程绝不阻塞 */
    private void shutdownLauncher() {
        saveConfig();
        Thread t = new Thread(() -> {
            try { directTunnel.removeMapping(); } catch (Exception ignored) { }
            try { stopTerracotta(); } catch (Exception ignored) { }
            try { frp.shutdown(); } catch (Exception ignored) { }
            try { pool.shutdownNow(); } catch (Exception ignored) { }
            Platform.exit();
            // 兜底：不管还有什么非 daemon 线程在收尾，保证进程退出，绝不留下假死窗口
            try { Thread.sleep(800); } catch (InterruptedException ignored) { }
            System.exit(0);
        }, "yhcl-shutdown");
        t.setDaemon(true);
        t.start();
    }

    /** 游戏进程启动后：设置 Windows 高性能显卡偏好（DirectX UserGpuPreferences 注册表） */
    private void applyHighPerfGpu(Process p) {
        try {
            String exe = p.info().command().orElse("");
            if (exe.isBlank()) return;
            int rc = new ProcessBuilder("reg", "add",
                    "HKCU\\Software\\Microsoft\\DirectX\\UserGpuPreferences",
                    "/v", exe, "/t", "REG_SZ", "/d", "GpuPreference=2;", "/f")
                    .redirectErrorStream(true).start().waitFor();
            if (rc == 0) log("已设置高性能显卡偏好：" + exe);
            else log("设置高性能显卡偏好失败（不影响游戏启动）");
        } catch (Exception ex) {
            log("设置高性能显卡偏好失败：" + ex.getMessage());
        }
    }

    /** 游戏进程启动后：设置进程优先级 */
    private void applyProcessPriority(Process p, String level) {
        String cls = switch (level) {
            case "high" -> "High";
            case "low" -> "BelowNormal";
            default -> "Normal";
        };
        try {
            int rc = new ProcessBuilder("powershell", "-NoProfile", "-Command",
                    "(Get-Process -Id " + p.pid() + ").PriorityClass='" + cls + "'")
                    .redirectErrorStream(true).start().waitFor();
            if (rc == 0) log("游戏进程优先级已设为：" + cls);
            else log("设置进程优先级失败（不影响游戏启动）");
        } catch (Exception ex) {
            log("设置进程优先级失败：" + ex.getMessage());
        }
    }

    /** 等待游戏窗口真正出现（最多 3 分钟），随后在 UI 线程执行动作 */
    private void awaitGameWindowThen(long pid, Runnable action) {
        Thread t = new Thread(() -> {
            boolean shown = false;
            try {
                String script = "for($i=0;$i -lt 360;$i++){ $p=Get-Process -Id " + pid
                        + " -ErrorAction SilentlyContinue; if($null -eq $p){exit 1}; "
                        + "if($p.MainWindowHandle -ne 0){exit 0}; Start-Sleep -Milliseconds 500 }; exit 1";
                Process w = new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive",
                        "-Command", script).redirectErrorStream(true).start();
                w.getInputStream().readAllBytes();
                shown = w.waitFor() == 0;
            } catch (Exception ignored) {}
            if (shown) Platform.runLater(action);
            else log("未检测到游戏窗口，跳过启动器可见性设置");
        }, "yhcl-game-window-watch");
        t.setDaemon(true);
        t.start();
    }

    /** 游戏窗口出现后：用 Win32 SetWindowText 改窗口标题（title 为空则保留游戏原标题；
     *  suffix 为自定义信息，追加在标题末尾；持续补写 20 秒，防游戏重设标题） */
    private void applyGameWindowTitle(long pid, String title, String suffix) {
        String fixed = title == null ? "" : title;
        String sfx = suffix == null ? "" : suffix;
        String script = "Add-Type -MemberDefinition '[DllImport(\"user32.dll\",CharSet=CharSet.Unicode)] "
                + "public static extern bool SetWindowText(IntPtr h,string t);' -Name W -Namespace U; "
                + "for($i=0;$i -lt 600;$i++){ $p=Get-Process -Id " + pid + " -ErrorAction SilentlyContinue; "
                + "if($null -eq $p){exit 1}; $p.Refresh(); if($p.MainWindowHandle -ne 0){break}; Start-Sleep -Milliseconds 500 }; "
                + "for($j=0;$j -lt 20;$j++){ $p.Refresh(); if($p.MainWindowHandle -eq 0){exit 0}; "
                + "$t='" + fixed.replace("'", "''") + "'; "
                + "if($t -eq ''){ $cur=[string]$p.MainWindowTitle; if($cur -eq ''){$cur='Minecraft'}; $t=$cur }; "
                + "$fx='" + sfx.replace("'", "''") + "'; "
                + "if($fx -ne '' -and -not $t.Contains($fx)){ $t=$t+' · '+$fx }; "
                + "[U.W]::SetWindowText([IntPtr]$p.MainWindowHandle,$t)|Out-Null; "
                + "if($j -eq 0){Write-Output ('APPLIED ' + $t)}; Start-Sleep -Milliseconds 1000 }; exit 0";
        // 用 EncodedCommand 传脚本，避免命令行引号被转义破坏
        String b64 = java.util.Base64.getEncoder()
                .encodeToString(script.getBytes(java.nio.charset.StandardCharsets.UTF_16LE));
        Thread t = new Thread(() -> {
            try {
                Process w = new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive",
                        "-EncodedCommand", b64).redirectErrorStream(true).start();
                try (var br = new java.io.BufferedReader(new java.io.InputStreamReader(w.getInputStream()))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        if (line.startsWith("APPLIED"))
                            log("游戏窗口标题已设为：" + line.substring("APPLIED".length()).trim());
                    }
                }
                w.waitFor();
            } catch (Exception ex) {
                log("设置游戏窗口标题失败（不影响游戏启动）：" + ex.getMessage());
            }
        }, "yhcl-game-title");
        t.setDaemon(true);
        t.start();
    }

    /** 启动游戏前：清空 Windows 缓存内存（备用列表），让物理内存可供游戏直接使用 */
    private void runMemoryOptimize() {
        log("正在清空 Windows 缓存内存…");
        try {
            Path tmp = Files.createTempFile("yhcl-memopt", ".ps1");
            try {
                byte[] data;
                try (var in = App.class.getResourceAsStream("/memopt.ps1")) {
                    if (in == null) throw new IllegalStateException("缺少 memopt.ps1 资源");
                    data = in.readAllBytes();
                }
                Files.write(tmp, data);
                Process p = new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive",
                        "-ExecutionPolicy", "Bypass", "-File", tmp.toString())
                        .redirectErrorStream(true).start();
                // 防止清理脚本挂起阻塞启动流程
                pool.submit(() -> {
                    try { Thread.sleep(120_000); if (p.isAlive()) p.destroyForcibly(); }
                    catch (InterruptedException ignored) {}
                });
                String result = null;
                try (var br = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        if (line.startsWith("RESULT")) result = line;
                        else if (line.startsWith("FLUSH")) log("[内存优化] " + line);
                    }
                }
                logMemOptResult(result);
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (Exception ex) {
            log("清空缓存内存失败：" + ex.getMessage());
        }
    }

    private void logMemOptResult(String result) {
        if (result == null) { log("清空缓存内存失败：清理脚本无输出"); return; }
        if (result.contains("ERROR=")) {
            log("清空缓存内存失败：" + result.substring(result.indexOf("ERROR=") + 6));
            return;
        }
        String head = result.contains("HOW=skip") ? "已检查 Windows 缓存内存（当前无缓存可清）"
                : "已清空 Windows 缓存内存";
        long before = parseLongAfter(result, "CACHE_BEFORE_KB=");
        long after = parseLongAfter(result, "CACHE_AFTER_KB=");
        if (before > 0 && after >= 0 && before > after) {
            log(head + "：已缓存 " + fmtGB(before) + " → " + fmtGB(after) + "，释放 " + fmtGB(before - after));
        } else {
            log(head);
        }
    }

    private static String fmtGB(long kb) {
        double gb = kb / 1048576.0;
        return gb >= 0.1 ? String.format(java.util.Locale.ROOT, "%.1fGB", gb) : (kb / 1024) + "MB";
    }

    private static long parseLongAfter(String s, String key) {
        int i = s.indexOf(key);
        if (i < 0) return -1;
        int j = i + key.length(), k = j;
        while (k < s.length() && Character.isDigit(s.charAt(k))) k++;
        try { return Long.parseLong(s.substring(j, k)); } catch (Exception e) { return -1; }
    }

    private void onLaunch() {
        String id = installedVerBox.getValue();
        if (id == null || id.isBlank()) {
            alert("没有可启动的版本", "请先在「下载」页安装一个游戏版本。");
            return;
        }
        // 该版本的独立设置
        VerSetting vs = verSettings.containsKey(id) ? verSettings.get(id) : new VerSetting();
        boolean msAccount = !accountToken.isBlank() && "microsoft".equals(accountType);
        boolean ygAccount = !accountToken.isBlank() && "yggdrasil".equals(accountType);
        if ("yggdr".equals(vs.loginMode) && !ygAccount) {
            alert("此版本仅允许第三方登录", "该版本的登录方式设置为「第三方登录」，"
                    + "请先在版本设置的「第三方登录」中登录（Authlib Injector / LittleSkin）。");
            return;
        }
        if ("online".equals(vs.loginMode) && !msAccount) {
            alert("此版本仅允许正版登录", "该版本的登录方式设置为「仅正版登录」，请先点击「Microsoft 正版登录」。");
            return;
        }
        if ("yggdr".equals(vs.loginMode)) {
            // 第三方登录（Authlib Injector / LittleSkin）
            profile.name = accountName;
            profile.uuid = accountUuid;
            profile.accessToken = accountToken;
            profile.isMicrosoft = false;
            profile.isYggdrasil = true;
            profile.authApi = authApi;
        } else if (msAccount && !"offline".equals(vs.loginMode)) {
            profile.name = accountName;
            profile.uuid = accountUuid;
            profile.accessToken = accountToken;
            profile.isMicrosoft = true;
            profile.isYggdrasil = false;
            profile.authApi = "";
        } else {
            String name = userField.getText() == null ? "" : userField.getText().trim();
            if (name.isBlank()) {
                alert("没有玩家名", "请输入离线玩家名，或点击「Microsoft 正版登录」。");
                return;
            }
            profile.name = name;
            profile.uuid = "";
            profile.accessToken = "";
            profile.isMicrosoft = false;
            profile.isYggdrasil = false;
            profile.authApi = "";
        }
        saveConfig();

        launchBtn.setDisable(true);
        launchProg.setProgress(0);
        statusLabel.setText("正在准备启动…");
        onDownloadStart("启动 " + id);
        // 组装该版本的启动覆盖项
        MinecraftService.LaunchOpts opts = new MinecraftService.LaunchOpts();
        if (isolationFor(id)) opts.isolation = true;
        if (!vs.javaPath.isBlank()) opts.javaPath = vs.javaPath.trim();
        if (!vs.jvmArgs.isBlank()) opts.jvmArgs = vs.jvmArgs;
        if (!vs.envVars.isBlank()) opts.envVars = vs.envVars;
        opts.serverIp = vs.serverIp == null ? "" : vs.serverIp.trim();
        if ("custom".equals(vs.memoryMode)) opts.memoryMB = Math.max(512, vs.memoryMB);
        else if ("auto".equals(vs.memoryMode)) {
            StringBuilder memNote = new StringBuilder();
            opts.memoryMB = autoMemoryFor(id, memNote);
            log("自动配置内存：" + memNote);
        } else if ("auto".equals(globalMemMode)) {
            // 版本跟随全局 + 全局=自动配置
            StringBuilder memNote = new StringBuilder();
            opts.memoryMB = autoMemoryFor(id, memNote);
            log("自动配置内存（全局）：" + memNote);
        }
        // 版本跟随全局 + 全局=自定义：用全局滑块值（LaunchOpts 为空时回退 mc.memoryMB）
        // 全局设置：窗口大小 / 游戏参数 / 内存管理（GC）
        opts.windowSize = windowSize;
        opts.gameArgs = gameArgs;
        // 离线皮肤（仅离线登录时生效，由 MinecraftService 注入皮肤贴图）
        opts.skinMode = offlineSkin;
        opts.skinFile = customSkinFile == null ? "" : customSkinFile;
        // 高级选项：禁用 Launch Wrapper / LWJGL Unsafe Agent
        opts.disableLaunchWrapper = disableLaunchWrapper;
        opts.disableLwjglAgent = disableLwjglAgent;
        String gcArgs = switch (gcMode) {
            case "g1" -> "-XX:+UseG1GC -XX:MaxGCPauseMillis=50 -XX:+UnlockExperimentalVMOptions -XX:G1NewSizePercent=20";
            case "zgc" -> "-XX:+UseZGC";
            case "shenandoah" -> "-XX:+UseShenandoahGC";
            default -> "";
        };
        if (!gcArgs.isBlank())
            opts.jvmArgs = (opts.jvmArgs == null || opts.jvmArgs.isBlank() ? gcArgs : opts.jvmArgs + " " + gcArgs);
        Path warnModsDir = modsDirFor(id);   // 启动前 Mod 兼容性检查用：与本次启动实际生效的 mods 目录一致
        boolean doMemOpt = memOptimize || "on".equals(vs.memOptimize);
        String preCmd = preLaunchCmd == null ? "" : preLaunchCmd;
        pool.submit(() -> {
            try {
                // 内存优化 / 启动前命令都同步等外部进程，必须在后台线程跑；
                // 放 FX 线程会在进程卡住时冻结整个界面（实测卡死到看门狗报活全家桶栈）
                if (doMemOpt) runMemoryOptimize();
                if (!preCmd.isBlank()) {
                    log("执行启动前命令：" + preCmd);
                    try {
                        Process pc = new ProcessBuilder("cmd", "/c", preCmd)
                                .redirectErrorStream(true).start();
                        if (!pc.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) {
                            pc.destroyForcibly();
                            log("启动前命令超时（30 秒），已终止");
                        } else {
                            try (var br = new java.io.BufferedReader(new java.io.InputStreamReader(pc.getInputStream()))) {
                                String line;
                                while ((line = br.readLine()) != null) log("[启动前] " + line);
                            }
                            log("启动前命令已结束，退出码 " + pc.exitValue());
                        }
                    } catch (Exception ex) {
                        log("启动前命令失败：" + ex.getMessage());
                    }
                }
                // 启动前 Mod 兼容性检查（只警告不阻止）：mods 里混入其他 MC 版本的 Mod 会让游戏直接崩溃退出
                String baseVer = mc.getMcVersion(id);
                List<String> badMods = mc.findIncompatibleMods(warnModsDir, baseVer);
                if (!badMods.isEmpty()) {
                    log("警告：" + warnModsDir + " 里有 " + badMods.size() + " 个 Mod 不适配 MC " + baseVer + "，可能导致启动失败：");
                    for (String b : badMods) log("  · " + b);
                    log("如游戏异常退出，请先把上述 Mod 移出 mods 目录（或开启版本隔离，让各版本用独立 mods 目录）。");
                    Platform.runLater(() -> showToast("检测到 " + badMods.size() + " 个不兼容 Mod，可能启动失败（详见日志）", true));
                }
                Process p = mc.launch(id, profile, this::log,
                        d -> Platform.runLater(() -> launchProg.setProgress(d / 100.0)), opts);
                mc.monitor.finishOk();
                // 启动后设置：进程优先级 / 高性能显卡 / 自定义窗口标题
                if (!"normal".equals(processPriority)) applyProcessPriority(p, processPriority);
                if (highPerfGpu) applyHighPerfGpu(p);
                if (!windowTitle.isBlank() || !customInfo.isBlank())
                    applyGameWindowTitle(p.pid(), windowTitle.trim(), customInfo.trim());
                Platform.runLater(() -> {
                    onDownloadEnd();
                    statusLabel.setText("游戏运行中（PID " + p.pid() + "）");
                    if ("hide".equals(launcherVis) || "exit".equals(launcherVis)) {
                        log("等待游戏窗口出现后" + ("hide".equals(launcherVis) ? "隐藏启动器" : "退出启动器") + "…");
                    }
                });
                // 启动器可见性：等游戏窗口真正出现后再触发
                if ("hide".equals(launcherVis) || "exit".equals(launcherVis)) {
                    awaitGameWindowThen(p.pid(), () -> {
                        if ("hide".equals(launcherVis) && mainStage != null && mainStage.isShowing()) {
                            mainStage.hide();
                            log("游戏窗口已出现，启动器已隐藏（游戏退出后自动恢复）");
                        } else if ("exit".equals(launcherVis)) {
                            log("游戏窗口已出现，按设置退出启动器（游戏继续运行）");
                            shutdownLauncher();
                        }
                    });
                }
                p.onExit().thenAccept(proc -> Platform.runLater(() -> {
                    // 隐藏模式下游戏退出后恢复窗口
                    if ("hide".equals(launcherVis) && mainStage != null && !mainStage.isShowing()) {
                        mainStage.show();
                        mainStage.toFront();
                        log("游戏已退出，启动器窗口已恢复");
                    }
                    statusLabel.setText("游戏已退出，代码 " + proc.exitValue());
                    if (proc.exitValue() != 0) {
                        String crash = mc.findLatestCrashReport(id);
                        log("游戏异常退出。" + (crash.isBlank() ? "" : "崩溃报告：" + crash));
                    }
                }));
            } catch (Exception ex) {
                mc.monitor.finishFail(ex.getMessage());
                Platform.runLater(() -> {
                    onDownloadEnd();
                    log("启动失败：" + ex.getMessage());
                    statusLabel.setText("启动失败");
                    alert("启动失败", ex.getMessage());
                });
            } finally {
                Platform.runLater(() -> launchBtn.setDisable(false));
            }
        });
    }

    /* ==================== 版本列表 / 安装 ==================== */
    private void onLoadManifest() {
        manifestBtn.setDisable(true);
        manifestInfo.setText("正在获取版本列表…");
        pool.submit(() -> {
            try {
                List<VersionEntry> list = mc.getManifest();
                long releases = list.stream().filter(v -> "release".equals(v.type)).count();
                long snapshots = list.stream().filter(v -> "snapshot".equals(v.type)).count();
                Platform.runLater(() -> {
                    manifestCache = list;
                    manifestInfo.setText("共 " + list.size() + " 个版本 · "
                            + releases + " 正式 · " + snapshots + " 快照");
                    filterManifest();
                    log("版本列表已更新，共 " + list.size() + " 个版本。");
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    manifestInfo.setText("获取失败");
                    log("获取版本列表失败：" + ex.getMessage());
                    alert("获取版本列表失败", ex.getMessage());
                });
            } finally {
                Platform.runLater(() -> manifestBtn.setDisable(false));
            }
        });
    }

    private void filterManifest() {
        if (manifestCache == null || manifestList == null) return;
        String q = searchField.getText() == null ? "" : searchField.getText().trim().toLowerCase();
        List<VersionEntry> filtered = manifestCache.stream().filter(v -> {
            boolean typeOk = switch (typeFilter) {
                case "release" -> "release".equals(v.type);
                case "snapshot" -> "snapshot".equals(v.type);
                case "legacy" -> !"release".equals(v.type) && !"snapshot".equals(v.type);
                default -> true;
            };
            return typeOk && (q.isEmpty() || v.id.toLowerCase().contains(q));
        }).toList();
        manifestList.getItems().setAll(filtered);
    }

    /* ==================== 版本详情页（二级界面） ==================== */
    private VBox buildDetail() {
        detailBack = new Button("← 返回列表");
        detailBack.getStyleClass().add("ghost-btn");
        detailBack.setOnAction(e -> showPage(versionsPage, "版本管理"));
        detailTitle = new Label("版本详情");
        detailTitle.getStyleClass().add("h2");
        HBox head = new HBox(12, detailBack, detailTitle);
        head.setAlignment(Pos.CENTER_LEFT);

        detailType = detailInfo("类型：-");
        detailTime = detailInfo("发布时间：-");
        detailJava = detailInfo("Java 要求：-");
        detailSize = detailInfo("客户端大小：-");
        detailInstalled = detailInfo("安装状态：-");
        VBox infoCard = new VBox(8, detailType, detailTime, detailJava, detailSize, detailInstalled);
        infoCard.getStyleClass().add("card");

        /* 安装方式：ToggleGroup 防呆，原版/Forge/NeoForge/Fabric 互斥单选 */
        Label kindTip = new Label("安装方式（只能选一种）");
        kindTip.getStyleClass().add("muted");
        ToggleGroup lg = new ToggleGroup();
        lg.selectedToggleProperty().addListener((o, ov, nv) -> {
            if (nv == null && ov != null) ov.setSelected(true);   // 不允许取消到空
        });
        loaderVanilla = chipBtn("原版", lg, true);
        loaderForge = chipBtn("Forge", lg, false);
        loaderNeo = chipBtn("NeoForge", lg, false);
        loaderFabric = chipBtn("Fabric", lg, false);
        for (ToggleButton t : List.of(loaderVanilla, loaderForge, loaderNeo, loaderFabric))
            t.setOnAction(e -> onLoaderKindChanged());
        HBox kinds = new HBox(10, loaderVanilla, loaderForge, loaderNeo, loaderFabric);
        kinds.setAlignment(Pos.CENTER_LEFT);
        VBox kindCard = new VBox(10, kindTip, kinds);
        kindCard.getStyleClass().add("card");

        /* 加载器版本（原版时禁用） + 统一安装按钮 */
        loaderVerCombo = new ComboBox<>();
        loaderVerCombo.getStyleClass().add("dark-combo");
        loaderVerCombo.setMaxWidth(Double.MAX_VALUE);
        loaderVerCombo.setPromptText("原版无需选择");
        loaderVerCombo.setDisable(true);

        detailInstallBtn = new Button("安 装");
        detailInstallBtn.getStyleClass().add("accent-btn");
        detailInstallBtn.setMaxWidth(Double.MAX_VALUE);
        detailInstallBtn.setOnAction(e -> onDetailInstall());

        detailProg = new ProgressBar(0);
        detailProg.setMaxWidth(Double.MAX_VALUE);
        detailStatus = new Label("");
        detailStatus.getStyleClass().addAll("muted", "small");

        VBox panel = new VBox(14, head, infoCard, kindCard, loaderVerCombo,
                detailInstallBtn, detailProg, detailStatus);
        panel.getStyleClass().add("panel");
        VBox.setVgrow(panel, Priority.ALWAYS);
        VBox page = new VBox(panel);
        VBox.setVgrow(panel, Priority.ALWAYS);
        return page;
    }

    private Label detailInfo(String text) {
        Label l = new Label(text);
        l.getStyleClass().addAll("muted", "small");
        return l;
    }

    private String selectedLoader() {
        if (loaderForge.isSelected()) return "forge";
        if (loaderNeo.isSelected()) return "neoforge";
        if (loaderFabric.isSelected()) return "fabric";
        return "vanilla";
    }

    private String kindLabel(String kind) {
        return switch (kind) {
            case "forge" -> "Forge";
            case "neoforge" -> "NeoForge";
            case "fabric" -> "Fabric";
            default -> "原版";
        };
    }

    /** 进入详情页：自动获取版本信息；加载器列表按需（选中时才拉取） */
    private void openDetail(VersionEntry v) {
        detailVersion = v;
        detailTitle.setText("版本详情 · " + v.id);
        detailType.setText("类型：" + switch (v.type) {
            case "release" -> "正式版";
            case "snapshot" -> "快照";
            case "old_alpha", "old_beta" -> "远古版本";
            default -> v.type;
        });
        detailTime.setText("发布时间：获取中…");
        detailJava.setText("Java 要求：获取中…");
        detailSize.setText("客户端大小：获取中…");
        detailInstalled.setText("安装状态：检查中…");
        detailProg.setProgress(0);
        detailStatus.setText("");
        detailInstallBtn.setDisable(false);
        // 预取各加载器版本列表（后台并行），点选时大多直接命中缓存
        for (String kind : List.of("fabric", "forge", "neoforge")) loaderListFuture(kind, v.id);
        loaderVanilla.setSelected(true);          // 重置为原版（互斥组自动取消其余）
        onLoaderKindChanged();
        showPage(detailPage, "版本管理 / " + v.id);

        pool.submit(() -> {
            try {
                MinecraftService.VersionMeta m = mc.fetchVersionMeta(v.url);
                Platform.runLater(() -> {
                    String t = m.releaseTime();
                    detailTime.setText("发布时间：" + (t.length() >= 10 ? t.substring(0, 10) : t));
                    detailJava.setText("Java 要求：" + (m.javaVersion().isEmpty() ? "默认" : "Java " + m.javaVersion()));
                    detailSize.setText("客户端大小：" +
                            (m.clientSize() > 0 ? String.format("%.1f MB", m.clientSize() / 1048576.0) : "-"));
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    detailTime.setText("版本信息获取失败：" + ex.getMessage());
                    detailJava.setText("Java 要求：-");
                    detailSize.setText("客户端大小：-");
                });
            }

            boolean inst = mc.getInstalled().contains(v.id);
            Platform.runLater(() -> {
                detailInstalled.setText("安装状态：" + (inst ? "已安装" : "未安装"));
                if ("vanilla".equals(selectedLoader()))
                    detailInstallBtn.setText(inst ? "重装原版" : "安 装");
            });
        });
    }

    /** 安装方式切换：原版禁用版本框；选加载器时取该加载器版本列表（缓存命中即秒显） */
    private void onLoaderKindChanged() {
        if (detailVersion == null) return;
        String kind = selectedLoader();
        if ("vanilla".equals(kind)) {
            loaderVerCombo.getItems().clear();
            loaderVerCombo.setPromptText("原版无需选择");
            loaderVerCombo.setDisable(true);
            boolean inst = mc.getInstalled().contains(detailVersion.id);
            detailInstallBtn.setText(inst ? "重装原版" : "安 装");
            return;
        }
        detailInstallBtn.setText("安装 " + kindLabel(kind));
        loaderVerCombo.setDisable(false);
        loaderVerCombo.getItems().clear();
        loaderVerCombo.setPromptText("加载中…");
        String mcVer = detailVersion.id;
        loaderListFuture(kind, mcVer).whenComplete((vs, err) -> Platform.runLater(() -> {
            if (!kind.equals(selectedLoader()) || !mcVer.equals(detailVersion.id)) return;   // 已切换则不覆盖
            if (err != null) {
                loaderListCache.remove(kind + "|" + mcVer);   // 失败不留缓存，重选可重试
                log(kindLabel(kind) + " 版本列表获取失败：" + (err.getCause() != null ? err.getCause() : err).getMessage());
                loaderVerCombo.setPromptText("加载失败，重选可重试");
                return;
            }
            fillLoaderCombo(vs);
        }));
    }

    /** 取（或发起）某加载器在某 MC 版本下的版本列表请求：同键并发去重，30 分钟内复用 */
    private CompletableFuture<List<String>> loaderListFuture(String kind, String mcVer) {
        String key = kind + "|" + mcVer;
        synchronized (loaderListCache) {
            LoaderList cached = loaderListCache.get(key);
            if (cached != null && System.currentTimeMillis() - cached.at() < LOADER_LIST_TTL_MS) return cached.future();
            CompletableFuture<List<String>> f = new CompletableFuture<>();
            loaderListCache.put(key, new LoaderList(f, System.currentTimeMillis()));
            pool.submit(() -> {
                try {
                    f.complete(mc.listLoaderVersions(kind, mcVer));
                } catch (Exception ex) {
                    f.completeExceptionally(ex);
                }
            });
            return f;
        }
    }

    private void fillLoaderCombo(List<String> vs) {
        loaderVerCombo.getItems().clear();
        if (vs.isEmpty()) {
            loaderVerCombo.setPromptText("无可用版本");
            return;
        }
        loaderVerCombo.getItems().add("自动（最新）");
        loaderVerCombo.getItems().addAll(vs);
        loaderVerCombo.getSelectionModel().selectFirst();
        loaderVerCombo.setPromptText(null);
    }

    /** 统一安装入口：按当前互斥选中的安装方式执行 */
    private void onDetailInstall() {
        if (detailVersion == null) return;
        String kind = selectedLoader();
        String mcVer = detailVersion.id;
        String loaderVer = null;
        if (!"vanilla".equals(kind)) {
            String v = loaderVerCombo.getValue();
            if (v == null || v.isBlank()) {
                alert("未选择版本", "请先选择要安装的 " + kindLabel(kind) + " 版本。");
                return;
            }
            if (!v.startsWith("自动")) loaderVer = v;
        }
        final String fLoaderVer = loaderVer;
        detailInstallBtn.setDisable(true);
        detailProg.setProgress(0);
        detailStatus.setText("准备安装…");
        onDownloadStart("安装 " + kindLabel(kind) + " · " + mcVer);
        pool.submit(() -> {
            try {
                String id;
                if ("vanilla".equals(kind)) {
                    mc.install(detailVersion, this::log,
                            d -> Platform.runLater(() -> detailProg.setProgress(d / 100.0)),
                            s -> Platform.runLater(() -> detailStatus.setText(s)));
                    id = mcVer;
                } else {
                    id = mc.installLoader(kind, mcVer, fLoaderVer, this::log,
                            d -> Platform.runLater(() -> detailProg.setProgress(d / 100.0)),
                            s -> Platform.runLater(() -> detailStatus.setText(s)));
                }
                mc.monitor.finishOk();
                String fId = id;
                Platform.runLater(() -> {
                    onDownloadEnd();
                    detailStatus.setText("安装完成：" + fId);
                    detailInstalled.setText("安装状态：已安装");
                    refreshInstalled();
                    installedVerBox.getSelectionModel().select(fId);
                });
            } catch (Exception ex) {
                mc.monitor.finishFail(ex.getMessage());
                Platform.runLater(() -> {
                    onDownloadEnd();
                    log(kindLabel(kind) + " 安装失败：" + ex.getMessage());
                    detailStatus.setText("安装失败：" + ex.getMessage());
                    showToast("安装失败：" + ex.getMessage(), true);
                });
            } finally {
                Platform.runLater(() -> detailInstallBtn.setDisable(false));
            }
        });
    }

    /* ==================== 文件浏览 ==================== */
    private void browseJava() {
        FileChooser fc = new FileChooser();
        fc.setTitle("选择 Java 可执行文件");
        fc.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("Java", "javaw.exe", "java.exe"));
        File f = fc.showOpenDialog(javaField.getScene().getWindow());
        if (f != null) {
            javaField.setText(f.getAbsolutePath());
            mc.javaPath = f.getAbsolutePath();
            mc.autoJava = false;
            autoJavaBox.setSelected(false);
            javaField.setDisable(false);
            saveConfig();
        }
    }

    private void browseDir() {
        DirectoryChooser dc = new DirectoryChooser();
        dc.setTitle("选择游戏目录");
        if (Files.isDirectory(mc.gameDir)) dc.setInitialDirectory(mc.gameDir.toFile());
        File f = dc.showDialog(dirField.getScene().getWindow());
        if (f != null) {
            mc.gameDir = f.toPath();
            dirField.setText(mc.gameDir.toString());
            saveConfig();
            refreshInstalled();
        }
    }

    private void rescanJava() {
        pool.submit(() -> {
            JavaLocator.clearCache();
            List<JavaLocator.JavaInfo> all = JavaLocator.scan();
            String text = all.isEmpty() ? "未找到任何 Java，请先安装。"
                    : "找到 " + all.size() + " 个 Java："
                    + String.join("、", all.stream().map(j -> "Java " + j.major).distinct().toList());
            Platform.runLater(() -> {
                javaScanInfo.setText(text);
                log(text);
            });
        });
    }

    /* ==================== 杂项 ==================== */
        private void onDeleteVersion(String id) {
        if (id == null || id.isBlank()) { statusLabel.setText("没有可删除的版本"); return; }
        Alert c = new Alert(Alert.AlertType.CONFIRMATION);
        c.setTitle("删除版本");
        c.setHeaderText(null);
        c.setContentText("确定删除版本 " + id + " 吗？\n只删除 versions\\" + id + " 目录，存档和 mods 不受影响。");
        c.showAndWait().filter(r -> r == ButtonType.OK).ifPresent(r -> {
            statusLabel.setText("正在删除 " + id + "…");
            pool.submit(() -> {
                try {
                    mc.deleteVersion(id);
                    log("版本已删除：" + id);
                                        Platform.runLater(() -> {
                        refreshInstalled();
                        refreshVerSelPage();   // 版本选择页同步移除该行
                        statusLabel.setText("已删除：" + id);
                    });
                } catch (Exception ex) {
                    log("删除版本失败：" + ex.getMessage());
                    Platform.runLater(() -> statusLabel.setText("删除失败：" + ex.getMessage()));
                }
            });
        });
    }

    /* ==================== 同目录 .minecraft 检测 ==================== */

    /**
     * 在启动器所在目录搜索 .minecraft 并显示已有的版本。
     * exe 模式下启动器本体在「exe 同级的 YHCL 解压目录」里运行，
     * 所以两处都找：解压目录本身 + exe 所在目录（解压目录的上一级）。
     * 找到装有版本最多的那个就切换过去 —— 用户把游戏和启动器放一起时无需手动配置。
     */
    private void detectLocalGameDir() {
        Path app = Launcher.appDir();
        List<Path> roots = new ArrayList<>(List.of(app));
        Path parent = app.getParent();
        if (parent != null) roots.add(parent);

        Path best = null;
        int bestCount = -1;
        for (Path root : roots) {
            Path g = root.resolve(".minecraft");
            int n = countVersions(g);
            if (n > bestCount) { bestCount = n; best = g; }
        }
        if (best == null || bestCount == 0 || best.equals(mc.gameDir)) return;

        mc.gameDir = best;
        dirField.setText(best.toString());
        saveConfig();
        log("检测到启动器同目录的 .minecraft（含 " + bestCount + " 个版本），已切换游戏目录：" + best);
    }

    /** 统计某个游戏目录下装了几个版本（有 <id>/<id>.json 才算） */
    private static int countVersions(Path gameDir) {
        Path dir = gameDir.resolve("versions");
        if (!Files.isDirectory(dir)) return 0;
        try (var s = Files.list(dir)) {
            return (int) s.filter(Files::isDirectory)
                    .filter(p -> Files.exists(p.resolve(p.getFileName().toString() + ".json")))
                    .count();
        } catch (IOException e) {
            return 0;
        }
    }

    private void refreshInstalled() {
        List<String> installed = mc.getInstalled();
        loaderTagCache.clear();   // 版本目录/版本 json 可能刚被整合包安装改写，重新识别加载器
        String prev = installedVerBox.getValue();
        installedVerBox.getItems().setAll(installed);
        if (prev != null && installed.contains(prev)) installedVerBox.getSelectionModel().select(prev);
        else if (!installed.isEmpty()) installedVerBox.getSelectionModel().selectFirst();
        updateSelLabel();
    }

    /** 把版本 id 显示成「MC 主版本 · 版本 id」，方便识别加载器版本对应的原版。 */
    private String verDisplay(String id) {
        if (id == null) return "";
        String mcVer = mc.getMcVersion(id);
        if (mcVer == null || mcVer.equals(id) || mcVer.isBlank()) return id;
        return mcVer + "  ·  " + id;
    }

    /** 下拉框下方的说明文字：选中版本的 MC 主版本 + 已安装总数。 */
    private void updateSelLabel() {
        List<String> installed = mc.getInstalled();
        if (installed.isEmpty()) {
            selLabel.setText("还没有安装任何版本，请到「下载」页下载");
            return;
        }
        String id = installedVerBox.getValue();
        if (id == null) {
            selLabel.setText("已安装 " + installed.size() + " 个版本");
            return;
        }
        String mcVer = mc.getMcVersion(id);
        String kind = id.equals(mcVer) ? "原版" : loaderName(id);
        selLabel.setText("Minecraft " + mcVer + "（" + kind + "） · 已安装 " + installed.size() + " 个版本");
    }

    /** 从版本 id 粗略判断加载器类型，仅用于显示。 */
    private String loaderName(String id) {
        if (id == null) return "原版";
        return switch (loaderTag(id)) {
            case "neoforge" -> "NeoForge";
            case "forge" -> "Forge";
            case "fabric" -> "Fabric";
            case "quilt" -> "Quilt";
            default -> "加载器";
        };
    }

    /** 从版本 id 推断 Modrinth 加载器 key（fabric/forge/neoforge），原版或未知返回 null */
    private String loaderKey(String id) {
        if (id == null) return null;
        String tag = loaderTag(id);
        return tag.isBlank() || "quilt".equals(tag) ? null : tag;
    }

        /* 日志批量合并：安装器/下载可能一秒吐几十行，逐行 runLater 会把 UI 队列刷爆导致卡死 */
    private final java.util.concurrent.ConcurrentLinkedQueue<String> logQueue = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private final java.util.concurrent.atomic.AtomicBoolean logFlushScheduled = new java.util.concurrent.atomic.AtomicBoolean();

    private void log(String msg) {
        logQueue.add("[" + LocalTime.now().format(TIME) + "] " + msg);
        if (logFlushScheduled.compareAndSet(false, true)) {
            Platform.runLater(() -> {
                logFlushScheduled.set(false);
                if (logArea == null) return;
                StringBuilder sb = new StringBuilder();
                String l;
                while ((l = logQueue.poll()) != null) sb.append(l).append('\n');
                if (sb.length() == 0) return;
                logArea.appendText(sb.toString());
                int len = logArea.getLength();
                if (len > 120_000) logArea.deleteText(0, len - 80_000);   // 防 TextArea 无限膨胀变卡
            });
        }
    }

    private void alert(String title, String msg) {
        Platform.runLater(() -> {
            Alert a = new Alert(Alert.AlertType.INFORMATION);
            a.setTitle(title);
            a.setHeaderText(null);
            a.setContentText(msg);
            a.showAndWait();
        });
    }

    /* ==================== 联机模块：一级入口（樱花 / UU 两个二级页） ==================== */

    private VBox buildFrpHub() {
        Label title = new Label("联机");
        title.getStyleClass().add("h2");
        HBox head = new HBox(12, title);
        head.setAlignment(Pos.CENTER_LEFT);

        /* ---- 樱花联机卡片 ---- */
        Label sakuraName = new Label("樱花联机（SakuraFrp）");
        sakuraName.getStyleClass().add("h2");
        Label sakuraDesc = new Label("通过 frpc 内网穿透实现 Minecraft 局域网联机："
                + "填写 SakuraFrp 访问密钥和隧道 ID，一键启动穿透，好友直连你的房间。");
        sakuraDesc.getStyleClass().addAll("muted", "small");
        sakuraDesc.setWrapText(true);
        Button sakuraBtn = new Button("打开樱花联机 →");
        sakuraBtn.getStyleClass().add("accent-btn");
        sakuraBtn.setPrefWidth(170);
        sakuraBtn.setPrefHeight(38);
        sakuraBtn.setOnAction(e -> showPage(frpPage, "联机 / 樱花联机"));
        VBox sakuraCard = new VBox(12, sakuraName, sakuraDesc, sakuraBtn);
        sakuraCard.getStyleClass().add("card");
        VBox.setVgrow(sakuraCard, Priority.ALWAYS);
        sakuraDesc.prefWidthProperty().bind(sakuraCard.widthProperty().subtract(32));

        /* ---- 直连联机卡片 ---- */
        Label directName = new Label("直连联机");
        directName.getStyleClass().add("h2");
        Label directDesc = new Label("不借助任何外部程序：同一局域网直接连、IPv6 直连；"
                + "路由器开启 UPnP 时还能自动映射端口，公网好友也能直连。");
        directDesc.getStyleClass().addAll("muted", "small");
        directDesc.setWrapText(true);
        Button directBtn = new Button("打开直连联机 →");
        directBtn.getStyleClass().add("accent-btn");
        directBtn.setPrefWidth(170);
        directBtn.setPrefHeight(38);
        directBtn.setOnAction(e -> showPage(directPage, "联机 / 直连联机"));
        VBox directCard = new VBox(12, directName, directDesc, directBtn);
        directCard.getStyleClass().add("card");
        VBox.setVgrow(directCard, Priority.ALWAYS);
        directDesc.prefWidthProperty().bind(directCard.widthProperty().subtract(32));

        /* ---- 陶瓦联机卡片 ---- */
        Label terraName = new Label("陶瓦联机（Terracotta）");
        terraName.getStyleClass().add("h2");
        Label terraDesc = new Label("开源 P2P 联机工具：一键创建房间生成邀请码，"
                + "好友输入邀请码加入即可联机；房间与 HMCL / PCL-CE / FMCL 等启动器互通。");
        terraDesc.getStyleClass().addAll("muted", "small");
        terraDesc.setWrapText(true);
        Button terraBtn = new Button("打开陶瓦联机 →");
        terraBtn.getStyleClass().add("accent-btn");
        terraBtn.setPrefWidth(170);
        terraBtn.setPrefHeight(38);
        terraBtn.setOnAction(e -> showPage(terraPage, "联机 / 陶瓦联机"));
        VBox terraCard = new VBox(12, terraName, terraDesc, terraBtn);
        terraCard.getStyleClass().add("card");
        VBox.setVgrow(terraCard, Priority.ALWAYS);
        terraDesc.prefWidthProperty().bind(terraCard.widthProperty().subtract(32));

        /* ---- VoxLink 卡片 ---- */
        Label voxName = new Label("VoxLink 联机");
        voxName.getStyleClass().add("h2");
        Label voxDesc = new Label("游戏内房间码联机 Mod：创建房间把 6 位房间码发给好友即可联机，"
                + "无需公网 IP；启动器一键把 Mod 装进游戏，进世界就能开房间。");
        voxDesc.getStyleClass().addAll("muted", "small");
        voxDesc.setWrapText(true);
        Button voxCBtn = new Button("打开 VoxLink 联机 →");
        voxCBtn.getStyleClass().add("accent-btn");
        voxCBtn.setPrefWidth(170);
        voxCBtn.setPrefHeight(38);
        voxCBtn.setOnAction(e -> showPage(voxPage, "联机 / VoxLink 联机"));
        VBox voxCard = new VBox(12, voxName, voxDesc, voxCBtn);
        voxCard.getStyleClass().add("card");
        VBox.setVgrow(voxCard, Priority.ALWAYS);
        voxDesc.prefWidthProperty().bind(voxCard.widthProperty().subtract(32));

        // 2×2 网格：两行各两张卡片
        HBox row1 = new HBox(14, sakuraCard, directCard);
        HBox row2 = new HBox(14, terraCard, voxCard);
        HBox.setHgrow(sakuraCard, Priority.ALWAYS);
        HBox.setHgrow(directCard, Priority.ALWAYS);
        HBox.setHgrow(terraCard, Priority.ALWAYS);
        HBox.setHgrow(voxCard, Priority.ALWAYS);
        VBox.setVgrow(row1, Priority.ALWAYS);
        VBox.setVgrow(row2, Priority.ALWAYS);
        VBox cards = new VBox(14, row1, row2);
        VBox.setVgrow(cards, Priority.ALWAYS);

        VBox panel = new VBox(14, head, cards);
        panel.getStyleClass().add("panel");
        VBox.setVgrow(panel, Priority.ALWAYS);
        return panel;
    }

    /* ==================== 联机（SakuraFrp）页 ==================== */

        /** 联机子页统一页头：返回按钮 + 标题 + 副标题 */
    private HBox frpSubHead(String titleText, String subtitleText) {
        Label title = new Label(titleText);
        title.getStyleClass().add("h2");
        Label subtitle = new Label(subtitleText);
        subtitle.getStyleClass().addAll("muted", "small");
        Button back = new Button("← 返回联机");
        back.getStyleClass().add("ghost-btn");
        back.setOnAction(e -> showPage(frpHubPage, "联机"));
        HBox head = new HBox(12, back, title, subtitle);
        head.setAlignment(Pos.CENTER_LEFT);
        return head;
    }

    /** 联机子页统一说明卡片（标题固定「使用步骤」） */
    private VBox helpCard(String text) {
        Label helpTip = new Label("使用步骤");
        helpTip.getStyleClass().add("muted");
        Label help = new Label(text);
        help.getStyleClass().addAll("muted", "small");
        help.setWrapText(true);
        VBox helpCard = new VBox(10, helpTip, help);
        helpCard.getStyleClass().add("card");
        help.prefWidthProperty().bind(helpCard.widthProperty().subtract(32));
        return helpCard;
    }

    private VBox buildFrp() {
        HBox head = frpSubHead("樱花联机（SakuraFrp）", "使用 SakuraFrp frpc 客户端实现 Minecraft 局域网联机穿透");

        frpStatusLabel = new Label("状态：未运行");
        frpStatusLabel.getStyleClass().addAll("muted", "small");

        // 内置 frpc（随安装包解压到 YHCL\tools\frpc）：自动关联；缺失时从安装包资源重新释放（杀软删除可自愈）
        Path builtinFrpc = builtinFrpcPath();
        if (!Files.isRegularFile(builtinFrpc)) releaseBuiltinFrpc();
        if (!frp.frpcReady() && Files.isRegularFile(builtinFrpc)) frp.setFrpcPath(builtinFrpc);

        /* ---- 访问密钥 ---- */
        Label keyTip = new Label("访问密钥（在 SakuraFrp 管理面板复制）");
        keyTip.getStyleClass().add("muted");
        frpAccessKeyPw = new PasswordField();
        frpAccessKeyPw.getStyleClass().add("dark-box");
        frpAccessKeyPw.setPromptText("粘贴您的访问密钥");
        frpAccessKeyPw.textProperty().addListener((o, a, b) -> saveConfig());
        HBox.setHgrow(frpAccessKeyPw, Priority.ALWAYS);
        VBox keyCard = new VBox(10, keyTip, frpAccessKeyPw);
        keyCard.getStyleClass().add("card");

        /* ---- 隧道 ID 列表 ---- */
        Label tunnelTip = new Label("隧道 ID（在管理面板创建隧道后复制 ID）");
        tunnelTip.getStyleClass().add("muted");

        frpTunnelInput = new TextField();
        frpTunnelInput.getStyleClass().add("dark-box");
        frpTunnelInput.setPromptText("输入隧道 ID，按 Enter 或点添加按钮");
        frpTunnelInput.setOnAction(e -> onFrpAddTunnel());

        Button fillMcBtn = new Button("常用 MC 端口 (25565)");
        fillMcBtn.getStyleClass().add("ghost-btn");
        fillMcBtn.setOnAction(e -> frpTunnelInput.setText("25565"));

        frpAddTunnelBtn = new Button("添加");
        frpAddTunnelBtn.getStyleClass().add("accent-btn");
        frpAddTunnelBtn.setOnAction(e -> onFrpAddTunnel());

        frpDelTunnelBtn = new Button("删除选中");
        frpDelTunnelBtn.getStyleClass().add("ghost-btn");
        frpDelTunnelBtn.setOnAction(e -> onFrpDelTunnel());

        HBox tunnelInputRow = new HBox(10, frpTunnelInput, fillMcBtn, frpAddTunnelBtn, frpDelTunnelBtn);
        tunnelInputRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(frpTunnelInput, Priority.ALWAYS);

        frpTunnelList = new ListView<>();
        frpTunnelList.setPlaceholder(new Label("还没有添加隧道 ID"));
        VBox.setVgrow(frpTunnelList, Priority.ALWAYS);

        VBox tunnelCard = new VBox(10, tunnelTip, tunnelInputRow, frpTunnelList);
        tunnelCard.getStyleClass().add("card");
        VBox.setVgrow(tunnelCard, Priority.ALWAYS);

        /* ---- 启动/停止按钮 ---- */
        frpToggleBtn = new Button("启动穿透");
        frpToggleBtn.getStyleClass().add("accent-btn");
        frpToggleBtn.setMaxWidth(Double.MAX_VALUE);
        frpToggleBtn.setPrefHeight(44);
        frpToggleBtn.setOnAction(e -> onFrpStartStop());

        /* ---- 日志区 ---- */
        Label logTip = new Label("运行日志");
        logTip.getStyleClass().add("muted");
        Button clearLogBtn = new Button("清空日志");
        clearLogBtn.getStyleClass().add("ghost-btn");
        clearLogBtn.setOnAction(e -> frpLogArea.clear());
        HBox logHead = new HBox(logTip);
        Region logSpacer = new Region();
        HBox.setHgrow(logSpacer, Priority.ALWAYS);
        logHead.getChildren().add(logSpacer);
        logHead.getChildren().add(clearLogBtn);

        frpLogArea = new TextArea();
        frpLogArea.getStyleClass().add("log-area");
        frpLogArea.setEditable(false);
        frpLogArea.setWrapText(true);
        frpLogArea.setPrefRowCount(8);
        VBox.setVgrow(frpLogArea, Priority.ALWAYS);

        VBox logCard = new VBox(8, logHead, frpLogArea);
        logCard.getStyleClass().add("card");
        VBox.setVgrow(logCard, Priority.ALWAYS);

        VBox panel = new VBox(14, head, keyCard, tunnelCard, frpToggleBtn, logCard);
        panel.getStyleClass().add("panel");
        VBox.setVgrow(panel, Priority.ALWAYS);
        return panel;
    }

    /** 内置 frpc 路径：exe 同级 YHCL\tools\frpc\frpc.exe（随安装包解压，免手动下载） */
    private static Path builtinFrpcPath() {
        return Launcher.appDir().resolve("tools").resolve("frpc").resolve("frpc.exe");
    }

    /** 从安装包资源（yhcl.jar 内 frpc.exe）重新释放内置 frpc：杀软删除后可自愈 */
    private static boolean releaseBuiltinFrpc() {
        Path dest = builtinFrpcPath();
        try {
            try (var in = App.class.getResourceAsStream("/frpc.exe")) {
                if (in == null) return false;
                Files.createDirectories(dest.getParent());
                Files.write(dest, in.readAllBytes());
            }
            return Files.isRegularFile(dest);
        } catch (Exception ex) {
            System.out.println("释放内置 frpc 失败：" + ex.getMessage());
            return false;
        }
    }

    /** 杀毒软件拦截 frpc 时的修复弹窗：提权加排除项 + 重新释放 frpc */
    private void showFrpcFixDialog(String reason) {
        Stage st = new Stage();
        st.initModality(Modality.APPLICATION_MODAL);
        st.initOwner(rootPane.getScene().getWindow());
        st.setTitle("樱花联机修复");
        st.setResizable(false);

        Label tip = new Label(reason + "\n\nWindows Defender 等杀毒软件常把樱花 frpc（内网穿透工具）"
                + "误判为风险程序，拦截运行或直接删除文件。\n\n一键修复会请求管理员授权：把 YHCL\\tools\\frpc "
                + "加入杀毒软件排除项，然后重新释放内置的 frpc 文件。请在弹出的 UAC 窗口点「是」。");
        tip.getStyleClass().add("muted");
        tip.setWrapText(true);

        Label err = new Label("");
        err.getStyleClass().addAll("accent-text", "small");
        err.setWrapText(true);

        Button fixBtn = new Button("一键修复（需管理员授权）");
        fixBtn.getStyleClass().add("accent-btn");
        Button cancelBtn = new Button("取消");
        cancelBtn.getStyleClass().add("ghost-btn");
        cancelBtn.setOnAction(e -> st.close());

        VBox box = new VBox(12, tip, err, new HBox(10, fixBtn, cancelBtn));
        box.setPadding(new Insets(24));
        box.setPrefWidth(440);
        ((HBox) box.getChildren().getLast()).setAlignment(Pos.CENTER_RIGHT);

        fixBtn.setOnAction(e -> {
            fixBtn.setDisable(true);
            err.setText("正在请求管理员授权…");
            pool.submit(() -> {
                String res;
                try {
                    res = runFrpcFix();
                } catch (Exception ex) {
                    res = "修复失败：" + ex.getMessage();
                }
                final String fRes = res;
                Platform.runLater(() -> {
                    if (fRes.startsWith("OK")) {
                        st.close();
                        showToast("修复完成，樱花联机已可正常使用", false);
                        frpLog("一键修复完成：杀毒软件排除项已添加，frpc 已就绪（" + fRes.substring(2).trim() + "）");
                        refreshFrpUi();
                    } else {
                        fixBtn.setDisable(false);
                        err.setText(fRes + "\n若反复失败，请手动在 Windows 安全中心「病毒和威胁防护 → "
                                + "排除项」里添加 YHCL\\tools\\frpc 目录后再试。");
                    }
                });
            });
        });

        Scene scene = new Scene(box);
        var css = getClass().getResource(DARKSLATE_CSS);
        if (css != null) scene.getStylesheets().add(css.toExternalForm());
        st.setScene(scene);
        st.show();
    }

    /** 一键修复：提权把 frpc 目录加入 Defender 排除项，重新释放内置 frpc 并验证可运行。返回 OK 开头=成功 */
    private String runFrpcFix() throws Exception {
        Path frpc = builtinFrpcPath();
        String dirQ = frpc.getParent().toString().replace("'", "''");
        Path script = Files.createTempFile("yhcl-frpcfix", ".ps1");
        Path result = Files.createTempFile("yhcl-frpcfix", ".txt");
        Files.deleteIfExists(result);
        try {
            String resQ = result.toString().replace("'", "''");
            String ps = "$ErrorActionPreference='Continue'\r\n"
                    + "try {\r\n"
                    + "  Add-MpPreference -ExclusionPath '" + dirQ + "'\r\n"
                    + "  $ok = (Get-MpPreference).ExclusionPath -contains '" + dirQ + "'\r\n"
                    + "  if ($ok) { Set-Content -LiteralPath '" + resQ + "' -Value 'YHCL-FIX-OK' -Encoding Ascii }\r\n"
                    + "  else { Set-Content -LiteralPath '" + resQ + "' -Value 'YHCL-FIX-FAIL' -Encoding Ascii }\r\n"
                    + "} catch {\r\n"
                    + "  Set-Content -LiteralPath '" + resQ + "' -Value ('YHCL-FIX-ERR ' + $_.Exception.Message) -Encoding Ascii\r\n"
                    + "}\r\n";
            // UTF-8 带 BOM：PowerShell 5 对无 BOM 文件按 ANSI 读，中文路径会乱码
            Files.write(script, ("\\uFEFF" + ps).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            String scriptQ = script.toString().replace("'", "''");
            Process p = new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive",
                    "-ExecutionPolicy", "Bypass", "-Command",
                    "Start-Process powershell -Verb RunAs -Wait -ArgumentList "
                            + "'-NoProfile','-ExecutionPolicy','Bypass','-File','" + scriptQ + "'")
                    .redirectErrorStream(true).start();
            try (var br = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()))) {
                while (br.readLine() != null) { /* 抽干输出防止阻塞 */ }
            }
            p.waitFor();
            String res = Files.isRegularFile(result) ? Files.readString(result).trim() : "";
            if (!res.startsWith("YHCL-FIX-OK")) {
                return "修复未完成" + (res.isEmpty() ? "（管理员授权被取消或杀毒软件不可配置）" : "：" + res);
            }
            if (!Files.isRegularFile(frpc) && !releaseBuiltinFrpc()) {
                return "排除项已添加，但 frpc 文件释放失败";
            }
            try {
                ProcessBuilder pb = new ProcessBuilder(frpc.toString(), "--version");
                pb.directory(frpc.getParent().toFile());
                pb.redirectErrorStream(true);
                Process v = pb.start();
                String out = new String(v.getInputStream().readAllBytes()).trim();
                if (!v.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)) {
                    v.destroyForcibly();
                    return "frpc 验证超时";
                }
                if (v.exitValue() != 0) return "frpc 仍无法运行（退出码 " + v.exitValue() + "）";
                frp.setFrpcPath(frpc);
                return "OK " + (out.isBlank() ? "frpc 就绪" : out);
            } catch (Exception ex) {
                return "frpc 仍无法运行：" + ex.getMessage();
            }
        } finally {
            Files.deleteIfExists(script);
            Files.deleteIfExists(result);
        }
    }

    /* ==================== 联机（直连）页 ==================== */

        private VBox buildDirect() {
        HBox head = frpSubHead("直连联机", "UPnP 自动映射 / IPv6 / 局域网直连，全程不下载任何外部程序");

        /* ---- 地址检测 ---- */
        Label detectTip = new Label("地址检测");
        detectTip.getStyleClass().add("muted");
        directStatusLb = new Label("检测中…");
        directStatusLb.getStyleClass().addAll("muted", "small");
        directStatusLb.setWrapText(true);
        Button detectBtn = new Button("重新检测");
        detectBtn.getStyleClass().add("ghost-btn");
        detectBtn.setOnAction(e -> onDirectDetect());
        HBox detectRow = new HBox(10, detectBtn);
        VBox detectCard = new VBox(10, detectTip, directStatusLb, detectRow);
        detectCard.getStyleClass().add("card");
        directStatusLb.prefWidthProperty().bind(detectCard.widthProperty().subtract(32));

        /* ---- UPnP 映射 ---- */
        Label mapTip = new Label("UPnP 自动映射（路由器开启 UPnP 时可用，公网好友直连）");
        mapTip.getStyleClass().add("muted");
        directPortField = new TextField("25565");
        directPortField.getStyleClass().add("dark-box");
        directPortField.setPrefWidth(110);
        directPortField.textProperty().addListener((o, a, b) -> updateDirectAddr());
        directMapBtn = new Button("开启映射");
        directMapBtn.getStyleClass().add("accent-btn");
        directMapBtn.setOnAction(e -> onDirectMapToggle());
        directMapStatusLb = new Label("未开启映射");
        directMapStatusLb.getStyleClass().addAll("muted", "small");
        directMapStatusLb.setWrapText(true);
        HBox mapRow = new HBox(10, directPortField, directMapBtn);
        mapRow.setAlignment(Pos.CENTER_LEFT);
        VBox mapCard = new VBox(10, mapTip, mapRow, directMapStatusLb);
        mapCard.getStyleClass().add("card");
        directMapStatusLb.prefWidthProperty().bind(mapCard.widthProperty().subtract(32));

        /* ---- 好友直连地址 ---- */
        Label addrTip = new Label("好友直连地址（复制发给朋友，让对方多人游戏 → 直接连接）");
        addrTip.getStyleClass().add("muted");
        directAddrField = new TextField();
        directAddrField.getStyleClass().add("dark-box");
        directAddrField.setEditable(false);
        HBox.setHgrow(directAddrField, Priority.ALWAYS);
        Button copyBtn = new Button("复制");
        copyBtn.getStyleClass().add("ghost-btn");
        copyBtn.setOnAction(e -> {
            javafx.scene.input.ClipboardContent cc = new javafx.scene.input.ClipboardContent();
            cc.putString(directAddrField.getText());
            javafx.scene.input.Clipboard.getSystemClipboard().setContent(cc);
            showToast("地址已复制", false);
        });
        HBox addrRow = new HBox(10, directAddrField, copyBtn);
        addrRow.setAlignment(Pos.CENTER_LEFT);
        VBox addrCard = new VBox(10, addrTip, addrRow);
        addrCard.getStyleClass().add("card");

        /* ---- 使用说明 ---- */
                VBox helpCard = helpCard("1. 主机进入单人世界 →「对局域网开放」（或运行服务端），端口填到上面（默认 25565）；\n"
                + "2. 同一 WiFi/路由器下的朋友：直接用「局域网地址」加入；\n"
                + "3. 有 IPv6 的朋友（国内宽带基本都有）：用「IPv6 地址」加入，不需要任何设置；\n"
                + "4. 路由器开了 UPnP：点「开启映射」成功后，公网好友用「公网地址」加入。\n"
                + "注意：Windows 防火墙弹窗时请选择「允许」，否则好友连不进来。");

        VBox panel = new VBox(14, head, detectCard, mapCard, addrCard, helpCard);
        panel.getStyleClass().add("panel");
        VBox.setVgrow(panel, Priority.ALWAYS);
        Platform.runLater(this::onDirectDetect);
        return panel;
    }

    /** 检测本机地址与 UPnP 网关（后台线程） */
    private void onDirectDetect() {
        if (directStatusLb == null) return;
        directStatusLb.setText("检测中…");
        pool.submit(() -> {
            String lan = LanTunnel.localIpv4();
            String v6 = LanTunnel.localIpv6();
            boolean igd = directTunnel.discover();
            String ext = igd ? directTunnel.getExternalIp() : "";
            Platform.runLater(() -> {
                directStatusLb.setText("局域网 IPv4：" + (lan.isEmpty() ? "未检测到" : lan) + "\n"
                        + "公网 IPv6：" + (v6.isEmpty() ? "未检测到（可尝试樱花联机）" : v6) + "\n"
                        + "UPnP 网关：" + (igd ? ("已发现" + (ext.isEmpty() ? "" : "，公网 IP：" + ext))
                                : "未发现（路由器可能未开启 UPnP）"));
                updateDirectAddr();
            });
        });
    }

    /** 开关 UPnP 映射 */
    private void onDirectMapToggle() {
        if (directMapped) {
            directTunnel.removeMapping();
            directMapped = false;
            directMapBtn.setText("开启映射");
            directMapStatusLb.setText("映射已关闭");
            updateDirectAddr();
            return;
        }
        int port;
        try {
            port = Integer.parseInt(directPortField.getText().trim());
            if (port < 1 || port > 65535) throw new NumberFormatException();
        } catch (NumberFormatException ex) {
            alert("端口无效", "请填 1-65535 之间的游戏端口（默认 25565）。");
            return;
        }
        directMapBtn.setDisable(true);
        directMapStatusLb.setText("正在映射…");
        final int p = port;
        pool.submit(() -> {
            try {
                if (!directTunnel.ready()) directTunnel.discover();
                String ip = directTunnel.addMapping(p);
                Platform.runLater(() -> {
                    directMapped = true;
                    directMapBtn.setText("停止映射");
                    directMapStatusLb.setText("映射成功：公网 " + ip + ":" + p + "（TCP）");
                    updateDirectAddr();
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    directMapStatusLb.setText("映射失败：" + ex.getMessage());
                    showToast("UPnP 映射失败：" + ex.getMessage(), true);
                });
            } finally {
                Platform.runLater(() -> directMapBtn.setDisable(false));
            }
        });
    }

    /** 按当前状态生成好友直连地址：公网映射 > IPv6 > 局域网 */
    private void updateDirectAddr() {
        if (directAddrField == null) return;
        String port = directPortField.getText() == null ? "25565" : directPortField.getText().trim();
        if (port.isEmpty()) port = "25565";
        String ext = directTunnel.externalIp();
        String addr;
        if (directMapped && !ext.isEmpty()) {
            addr = ext + ":" + port;
        } else {
            String v6 = LanTunnel.localIpv6();
            if (!v6.isEmpty()) addr = "[" + v6 + "]:" + port;
            else {
                String lan = LanTunnel.localIpv4();
                addr = (lan.isEmpty() ? "127.0.0.1" : lan) + ":" + port;
            }
        }
        directAddrField.setText(addr);
    }

    /* ==================== 联机（陶瓦 Terracotta）页 ==================== */

        private VBox buildTerra() {
        HBox head = frpSubHead("陶瓦联机（Terracotta）", "开源 P2P 联机工具，房间码机制，与 HMCL / PCL-CE / FMCL 等启动器互通");

        /* ---- 启动控制 ---- */
        Label ctrlTip = new Label("陶瓦工具");
        ctrlTip.getStyleClass().add("muted");
        terraStatusLb = new Label("未启动");
        terraStatusLb.getStyleClass().addAll("muted", "small");
        terraStatusLb.setWrapText(true);
        terraToggleBtn = new Button("启动陶瓦联机");
        terraToggleBtn.getStyleClass().add("accent-btn");
        terraToggleBtn.setOnAction(e -> onTerraToggle());
        terraOpenBtn = new Button("打开控制页");
        terraOpenBtn.getStyleClass().add("ghost-btn");
        terraOpenBtn.setDisable(true);
        terraOpenBtn.setOnAction(e -> openTerraUrl());
        HBox ctrlRow = new HBox(10, terraToggleBtn, terraOpenBtn);
        ctrlRow.setAlignment(Pos.CENTER_LEFT);
        VBox ctrlCard = new VBox(10, ctrlTip, terraStatusLb, ctrlRow);
        ctrlCard.getStyleClass().add("card");
        terraStatusLb.prefWidthProperty().bind(ctrlCard.widthProperty().subtract(32));

        /* ---- 使用说明 ---- */
                VBox helpCard = helpCard("1. 主机进入单人世界 →「对局域网开放」；\n"
                + "2. 点「启动陶瓦联机」，在打开的控制页里创建房间，把邀请码发给好友；\n"
                + "3. 好友在自己的启动器里打开陶瓦联机（YHCL / HMCL / PCL-CE / FMCL 均可），输入邀请码加入房间；\n"
                + "4. 按控制页的指引进入游戏直连即可。\n"
                + "注意：联机期间请保持陶瓦运行，关闭启动器时会自动停止。");

        /* ---- 版权声明（AGPL 例外要求标识） ---- */
        Label cr = new Label("陶瓦联机 Terracotta © Burning_TNT · AGPL-3.0 开源（基于 EasyTier）｜源码：github.com/BurningTNT/Terracotta");
        cr.getStyleClass().addAll("muted-2", "small");
        cr.setWrapText(true);
        VBox crCard = new VBox(8, cr);
        crCard.getStyleClass().add("card");
        cr.prefWidthProperty().bind(crCard.widthProperty().subtract(32));

        VBox panel = new VBox(14, head, ctrlCard, helpCard, crCard);
        panel.getStyleClass().add("panel");
        VBox.setVgrow(panel, Priority.ALWAYS);
        return panel;
    }

    /** 内置陶瓦工具路径（随安装包解压到 YHCL\tools\terracotta） */
    private static Path builtinTerraPath() {
        return Launcher.appDir().resolve("tools").resolve("terracotta").resolve("terracotta.exe");
    }

    /** 启动/停止陶瓦工具 */
    private void onTerraToggle() {
        if (terraProc != null && terraProc.isAlive()) {
            stopTerracotta();
            // 停止后必须复位按钮状态，否则再点会误入启动分支重新拉起工具
            terraToggleBtn.setText("启动陶瓦联机");
            terraOpenBtn.setDisable(true);
            terraStatusLb.setText("陶瓦已停止");
            return;
        }
        Path exe = builtinTerraPath();
        if (!Files.isRegularFile(exe)) {
            showToast("未找到内置陶瓦工具（YHCL\\tools\\terracotta\\terracotta.exe），请重新安装启动器", true);
            return;
        }
        terraToggleBtn.setDisable(true);
        terraStatusLb.setText("正在启动…");
        pool.submit(() -> {
            try {
                ProcessBuilder pb = new ProcessBuilder(exe.toString());
                pb.directory(exe.getParent().toFile());
                pb.redirectErrorStream(true);
                Process p = pb.start();
                terraProc = p;
                // 读取输出，抓取本地控制页地址（Rocket has launched from http://...）
                Thread t = new Thread(() -> {
                    try (var r = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()))) {
                        String line;
                        while ((line = r.readLine()) != null) {
                            int i = line.indexOf("http://127.0.0.1:");
                            if (i >= 0) {
                                String url = line.substring(i).trim();
                                terraUrl = url;
                                Platform.runLater(() -> {
                                    terraStatusLb.setText("陶瓦已启动，控制页：" + url
                                            + "\n请在控制页创建/加入房间（本工具会自动打开浏览器）。");
                                    terraOpenBtn.setDisable(false);
                                });
                            }
                        }
                    } catch (Exception ignore) { }
                    Platform.runLater(() -> {
                        if (terraProc == p && !p.isAlive()) {
                            terraToggleBtn.setText("启动陶瓦联机");
                            terraToggleBtn.setDisable(false);
                            terraOpenBtn.setDisable(true);
                            terraStatusLb.setText("陶瓦已退出");
                        }
                    });
                }, "terra-out");
                t.setDaemon(true);
                t.start();
                Platform.runLater(() -> {
                    terraToggleBtn.setText("停止陶瓦");
                    terraToggleBtn.setDisable(false);
                    terraStatusLb.setText("陶瓦正在启动…");
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    terraToggleBtn.setDisable(false);
                    terraStatusLb.setText("启动失败：" + ex.getMessage());
                });
            }
        });
    }

    /** 停止陶瓦工具 */
    private void stopTerracotta() {
        Process p = terraProc;
        terraProc = null;
        terraUrl = null;
        if (p != null && p.isAlive()) p.destroyForcibly();
    }

    /** 用系统浏览器打开陶瓦控制页 */
    private void openTerraUrl() {
        String url = terraUrl;
        if (url == null || url.isBlank()) return;
        try {
            java.awt.Desktop.getDesktop().browse(java.net.URI.create(url));
        } catch (Exception ex) {
            showToast("打开浏览器失败：" + ex.getMessage(), true);
        }
    }

    /* ==================== 联机（VoxLink Mod）页 ==================== */

        private VBox buildVox() {
        HBox head = frpSubHead("VoxLink 联机", "游戏内房间码联机 Mod：6 位房间码，免公网 IP，直连失败自动走 TURN 中继");

        /* ---- 一键安装 ---- */
        Label insTip = new Label("一键安装 VoxLink Mod（自动匹配该版本的 MC 版本与加载器）");
        insTip.getStyleClass().add("muted");
        voxVer = new ComboBox<>();
        voxVer.setPromptText("选择一个已安装版本");
        voxVer.getStyleClass().add("dark-box");
        voxVer.setEditable(false);
        voxVer.setCellFactory(lv -> verIdCell());
        voxVer.setButtonCell(verIdCell());
        voxVer.valueProperty().addListener((o, a, b) -> refreshVoxStatus());
        voxInstallBtn = new Button("一键安装 VoxLink");
        voxInstallBtn.getStyleClass().add("accent-btn");
        voxInstallBtn.setOnAction(e -> onVoxInstall());
        Button voxDirBtn = new Button("打开 mods 目录");
        voxDirBtn.getStyleClass().add("ghost-btn");
        voxDirBtn.setOnAction(e -> {
            try {
                String id = voxVer.getValue();
                if (id == null || id.isBlank()) { showToast("请先选择版本", true); return; }
                Path dir = modsDirFor(id);
                Files.createDirectories(dir);
                Desktop.getDesktop().open(dir.toFile());
            } catch (Exception ex) { showToast("打开 mods 目录失败", true); }
        });
        HBox insRow = new HBox(10, voxInstallBtn, voxDirBtn);
        insRow.setAlignment(Pos.CENTER_LEFT);
        voxStatusLb = new Label("未检测");
        voxStatusLb.getStyleClass().addAll("muted", "small");
        voxStatusLb.setWrapText(true);
        VBox insCard = new VBox(10, insTip, voxVer, insRow, voxStatusLb);
        insCard.getStyleClass().add("card");
        voxStatusLb.prefWidthProperty().bind(insCard.widthProperty().subtract(32));

        /* ---- 使用说明 ---- */
                VBox helpCard = helpCard("1. 给要联机的版本一键安装 VoxLink（好友也需要安装，VoxLink 是游戏内 Mod）；\n"
                + "2. 启动游戏，进入单人世界，用 VoxLink 的界面创建房间，得到 6 位房间码；\n"
                + "3. 把房间码发给好友，好友在游戏中输入房间码加入；\n"
                + "4. 先尝试 P2P 直连，20 秒未连上可由房主选择 TURN 中继；\n"
                + "5. 连接成功后按游戏内提示进入世界即可。\n"
                + "提示：1.0.4 起 VoxLink 还可选启用陶瓦 P2P（在 Mod 设置里开启）。");

        VBox panel = new VBox(14, head, insCard, helpCard);
        panel.getStyleClass().add("panel");
        VBox.setVgrow(panel, Priority.ALWAYS);
        return panel;
    }

    /** 刷新 VoxLink 安装状态 */
    private void refreshVoxStatus() {
        if (voxStatusLb == null || voxVer == null) return;
        String id = voxVer.getValue();
        if (id == null || id.isBlank()) {
            voxStatusLb.setText("请先选择要联机的游戏版本");
            return;
        }
        try {
            Path dir = modsDirFor(id);
            if (Files.isDirectory(dir)) {
                try (var s = Files.list(dir)) {
                    var hit = s.filter(f -> f.getFileName().toString().toLowerCase().contains("voxlink"))
                            .map(f -> f.getFileName().toString()).findFirst();
                    if (hit.isPresent()) {
                        voxStatusLb.setText("已安装：" + hit.get() + "（如需更新可再点一次安装）");
                        voxInstallBtn.setText("重新安装 VoxLink");
                        return;
                    }
                }
            }
        } catch (Exception ignore) { }
        voxStatusLb.setText("未安装 VoxLink，点「一键安装 VoxLink」下载到该版本的 mods 目录");
        voxInstallBtn.setText("一键安装 VoxLink");
    }

    /** 一键安装 VoxLink Mod：复用 Modrinth 解析/下载通道 */
    private void onVoxInstall() {
        String targetVer = voxVer == null ? null : voxVer.getValue();
        if (targetVer == null || targetVer.isBlank()) {
            showToast("请先选择要联机的游戏版本", true);
            return;
        }
        if (mc.monitor.running()) { showToast("已有下载任务正在进行，请稍后再试", true); return; }
        String mcVer = mc.getMcVersion(targetVer);
        String loader = loaderKey(targetVer);
        if (loader == null) {
            showToast("VoxLink 仅支持 Fabric / Forge / NeoForge 版本", true);
            return;
        }
        final Path modsDir = modsDirFor(targetVer);
        final String fMcVer = mcVer;

        onDownloadStart("下载 VoxLink Mod");
        final int idx = mc.monitor.addBatch(List.of("VoxLink.jar"));
        mc.monitor.markDownloading(idx);
        voxInstallBtn.setDisable(true);
        voxStatusLb.setText("正在解析 VoxLink 下载地址…");

        mc.resolveModDownloadUrl("voxlink", fMcVer, loader)
            .thenCompose(opt -> {
                if (opt.isEmpty()) return CompletableFuture.completedFuture(null);
                Platform.runLater(() -> {
                    mc.monitor.setPhase(MinecraftService.Phase.DOWNLOAD, "正在下载 VoxLink…");
                    voxStatusLb.setText("正在下载 VoxLink…");
                });
                return mc.downloadModByUrl(opt.get(), modsDir);
            })
            .whenComplete((path, ex) -> Platform.runLater(() -> {
                voxInstallBtn.setDisable(false);
                String msg = ex != null ? (ex.getCause() == null ? ex.getMessage() : ex.getCause().getMessage())
                        : (path == null ? "该版本/加载器暂无可用的 VoxLink" : null);
                if (msg != null) {
                    mc.monitor.markFailed(idx, msg);
                    mc.monitor.finishFail(msg);
                    onDownloadEnd();
                    voxStatusLb.setText("安装失败：" + msg);
                    showToast("安装失败：" + msg, true);
                } else {
                    mc.monitor.markDone(idx, path);
                    mc.monitor.finishOk();
                    onDownloadEnd();
                    showToast("VoxLink 已安装到 " + path, false);
                    refreshVoxStatus();
                    refreshLocalModsIfVisible();
                }
            }));
    }

    /** 添加一个隧道 ID 到列表 */
    private void onFrpAddTunnel() {
        String raw = frpTunnelInput.getText();
        if (raw == null) return;
        // 支持逗号/空格分隔多个 ID
        String[] ids = raw.split("[,\\s]+");
        boolean added = false;
        for (String id : ids) {
            String trimmed = id.trim();
            if (trimmed.isEmpty()) continue;
            // 简单验证：看起来像隧道ID（数字或短字符串）
            if (frpTunnelList.getItems().contains(trimmed)) continue;
            frpTunnelList.getItems().add(trimmed);
            added = true;
        }
        if (added) {
            frpTunnelInput.clear();
            saveConfig();
        }
    }

    /** 从列表删除选中的隧道 ID */
    private void onFrpDelTunnel() {
        String sel = frpTunnelList.getSelectionModel().getSelectedItem();
        if (sel != null) {
            frpTunnelList.getItems().remove(sel);
            saveConfig();
        }
    }

    /** 启动/停止 frpc */
    private void onFrpStartStop() {
        if (frp.isRunning() || frp.state() == FrpManager.State.LAUNCHING) {
            // 停止
            frp.stop();
            refreshFrpUi();
            showToast("已停止 frpc", false);
            return;
        }
        // 先检查 frpc 是否就绪：缺失时尝试从安装包资源重新释放，仍不可用则走一键修复
        if (!frp.frpcReady()) {
            Path builtinFrpc = builtinFrpcPath();
            if (!Files.isRegularFile(builtinFrpc)) releaseBuiltinFrpc();
            if (Files.isRegularFile(builtinFrpc)) frp.setFrpcPath(builtinFrpc);
        }
        if (!frp.frpcReady()) {
            showFrpcFixDialog("未找到内置 frpc 文件（YHCL\\tools\\frpc\\frpc.exe），可能已被杀毒软件删除。");
            refreshFrpUi();
            return;
        }
        // 验证输入
        String key = frpAccessKeyPw.getText() == null ? "" : frpAccessKeyPw.getText().trim();
        if (key.isEmpty()) {
            alert("缺少访问密钥", "请先在 SakuraFrp 管理面板复制您的访问密钥并粘贴到输入框。");
            return;
        }
        List<String> ids = new ArrayList<>(frpTunnelList.getItems());
        if (ids.isEmpty()) {
            alert("没有隧道", "请先在 SakuraFrp 管理面板创建至少一个隧道，把隧道 ID 添加到列表里。");
            return;
        }

        frpToggleBtn.setDisable(true);
        refreshFrpUi();

        final String fKey = key;
        final List<String> fIds = ids;
        pool.submit(() -> {
            try {
                frp.start(fKey, fIds);
                Platform.runLater(() -> {
                    frpToggleBtn.setText("停止穿透");
                    refreshFrpUi();
                    showToast("隧道已启动", false);
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    refreshFrpUi();
                    frpLog("启动失败：" + ex.getMessage());
                    String m = String.valueOf(ex.getMessage());
                    // 错误 225：杀毒软件拦截执行（文件被判定为风险程序）
                    if (m.contains("225") || m.toLowerCase(java.util.Locale.ROOT).contains("virus")) {
                        showFrpcFixDialog("frpc.exe 被杀毒软件拦截运行（Windows 错误 225）。");
                    } else {
                        showToast("启动失败：" + ex.getMessage(), true);
                    }
                });
            } finally {
                Platform.runLater(() -> {
                    frpToggleBtn.setDisable(false);
                });
            }
        });
    }

    /** 每 500ms 刷新 frp 状态显示 */
    private void refreshFrpUi() {
        if (frpStatusLabel == null) return;
        FrpManager.State s = frp.state();
        switch (s) {
            case RUNNING -> {
                frpStatusLabel.setText("状态：运行中");
                frpStatusLabel.setStyle("-fx-text-fill: #7ec2c0;");
                frpToggleBtn.setText("停止穿透");
            }
            case LAUNCHING -> {
                frpStatusLabel.setText("状态：启动中…");
                frpStatusLabel.setStyle("-fx-text-fill: #ffcf6a;");
            }
            case ERROR -> {
                frpStatusLabel.setText("状态：错误 - " + (frp.error().isBlank() ? "未知" : frp.error()));
                frpStatusLabel.setStyle("-fx-text-fill: #ff8a7a;");
                frpToggleBtn.setText("启动穿透");
            }
            default -> {
                frpStatusLabel.setText("状态：未运行");
                frpStatusLabel.setStyle("-fx-text-fill: #9eb4be;");
                frpToggleBtn.setText("启动穿透");
            }
        }
    }

    /** frp 日志回调：写入日志区 + 主日志 */
    private void frpLog(String line) {
        if (line == null || line.isBlank()) return;
        String prefix = "[Frp] ";
        // 先写主日志（加时间戳由它处理）
        log(line.startsWith(prefix) ? line : prefix + line);
        // 再写 frp 专用日志区（FX 线程）
        Platform.runLater(() -> {
            if (frpLogArea == null) return;
            String text = frpLogArea.getText();
            frpLogArea.appendText(line + "\n");
            // 限制日志行数，避免无限增长
            int lines = frpLogArea.getText().split("\n").length;
            if (lines > 500) {
                int idx = text.indexOf('\n', 0) + 1;
                frpLogArea.deleteText(0, idx);
            }
            // 自动滚动到底部
            frpLogArea.setScrollTop(Double.MAX_VALUE);
        });
    }

    /* ==================== 配置读写 ==================== */
    /** 单个版本的独立设置（空白/默认 = 跟随全局设置） */
    public static final class VerSetting {
        public String isolation = "global";    // global | on | off —— 版本隔离
        public String windowTitle = "";        // 游戏窗口标题（游戏窗口出现后 Win32 SetWindowText）
        public String customInfo = "";         // 自定义信息（保存为备注）
        public String javaPath = "";           // 空 = 自动选择
        public String memoryMode = "global";   // global | auto | custom
        public int memoryMB = 4096;
        public String memOptimize = "global";  // global | on | off
        public String loginMode = "both";      // both | online | offline
        public String serverIp = "";           // 启动后自动进入 host[:port]
        public String jvmArgs = "";
        public String envVars = "";
    }

    static final class Config {
        public String gameDir = "";
        public String javaPath = "";
        public boolean autoJava = true;
        public boolean useMirror = true;
        public int memoryMB = 4096;
        public String globalMemMode = "auto";    // 设置页内存分配：auto | custom
        public Boolean globalIsolation;          // 全局版本隔离：null(缺字段/旧配置)=默认开启；显式 true/false 才覆盖
        public String jvmArgs = "";
        public String playerName = "Steve";
        public String accountName = "";
        public String accountUuid = "";
        public String accountToken = "";
        public String accountRefresh = "";
        public String accountType = "";       // microsoft | yggdrasil（旧配置空 = microsoft）
        public String authApi = "";           // 第三方登录 Yggdrasil API
        public String authClientToken = "";   // 第三方登录 clientToken
        // frp 联机配置
        public String frpAccessKey = "";
        public List<String> frpTunnelIds = List.of();
        public String frpcPath = "";
        // 设置页：游戏窗口 / 启动行为 / 高级选项
        public String windowTitle = "";
        public String customInfo = "";
        public String launcherVis = "keep";      // keep | hide | exit
        public String processPriority = "normal"; // low | normal | high
        public String windowSize = "default";    // default | 宽x高
        public boolean memOptimize = false;
        public String gameArgs = "";
        public String preLaunchCmd = "";
        public String gcMode = "g1";             // default | g1 | zgc | shenandoah
        public boolean disableLaunchWrapper = false; // 禁用 Java Launch Wrapper（旧版 Forge 排障）
        public boolean disableLwjglAgent = false;    // 禁用 LWJGL Unsafe Agent 注入
        public boolean highPerfGpu = false;
        public String offlineSkin = "random";    // random | steve | alex | official | custom
        public String customSkinFile = "";       // 自定义皮肤 PNG 路径（导入后位于 YHCL/skins/）
        public String customBgFile = "";         // 自定义背景图路径（位于 YHCL/bg/）
        public double customBgDim = 0.35;        // 背景暗色蒙版不透明度 0~0.8
        // 设置页：下载
        public String fileSourceMode = "";       // official_first | mirror_first | official_only | mirror_only（空 = 由 useMirror 折算）
        public String metaSourceMode = "";       // 版本列表源，取值同上
        public int downloadThreads = 64;         // 最大下载线程数 1~256
        public double speedLimitKb = 0;          // 全局下载速度上限 KB/s，0 = 无限制
        public boolean verifySsl = true;         // 正版登录时校验 SSL 证书
        public List<FavEntry> favorites = List.of();  // 收藏（模组/整合包/材质包/光影）
        public Map<String, VerSetting> verSettings;
    }

    /** 收藏条目：ModSearchResult 快照 + 所属下载模块（kind = mod | modpack | shader | resourcepack） */
    public static final class FavEntry {
        public String kind = "mod";
        public String projectId = "", cfProjectId = "", title = "", summary = "",
                iconUrl = "", site = "", author = "", dateModified = "";
        public long downloads = 0, follows = 0;
        public List<String> categories = List.of(), mcVersions = List.of(), loaders = List.of();

        MinecraftService.ModSearchResult toResult() {
            return new MinecraftService.ModSearchResult(projectId, title, summary, iconUrl, site,
                    author, downloads, follows, dateModified, categories, mcVersions, loaders, cfProjectId);
        }

        static FavEntry of(String kind, MinecraftService.ModSearchResult r) {
            FavEntry e = new FavEntry();
            e.kind = kind;
            e.projectId = r.projectId();
            e.cfProjectId = r.cfProjectId();
            e.title = r.title();
            e.summary = r.summary();
            e.iconUrl = r.iconUrl();
            e.site = r.site();
            e.author = r.author();
            e.downloads = r.downloads();
            e.follows = r.follows();
            e.dateModified = r.dateModified();
            e.categories = r.categories();
            e.mcVersions = r.mcVersions();
            e.loaders = r.loaders();
            return e;
        }
    }

    /** 收藏键：kind + 项目 id（Modrinth 优先，CF 条目用 cfProjectId） */
    private static String favKey(String kind, MinecraftService.ModSearchResult r) {
        String id = r.projectId() == null || r.projectId().isBlank() ? r.cfProjectId() : r.projectId();
        return kind + "|" + id;
    }

    private boolean isFav(String kind, MinecraftService.ModSearchResult r) {
        String key = favKey(kind, r);
        synchronized (favList) {
            return favList.stream().anyMatch(f -> favKey(f.kind, f.toResult()).equals(key));
        }
    }

    private void toggleFav(String kind, MinecraftService.ModSearchResult r) {
        String key = favKey(kind, r);
        synchronized (favList) {
            boolean[] removed = { false };
            favList.removeIf(f -> {
                if (favKey(f.kind, f.toResult()).equals(key)) { removed[0] = true; return true; }
                return false;
            });
            if (!removed[0]) favList.add(FavEntry.of(kind, r));
        }
        saveConfig();
    }

    /** 应用星形收藏键外观：已收藏=黄色实心星，未收藏=空心星 */
    private void applyFavStar(SVGPath star, String kind, MinecraftService.ModSearchResult item) {
        if (isFav(kind, item)) {
            star.setFill(Color.web("#FFC93C"));   // 黄色实心
            star.setStroke(Color.web("#FFC93C"));
        } else {
            star.setFill(Color.TRANSPARENT);      // 空心
            star.setStroke(Color.web("#8fa3b0"));
        }
    }

    /* ==================== 收藏页（下载模块子页） ==================== */

    /** 收藏页：列出全部已收藏的模组/整合包/材质包/光影，点卡片进详情，点星形取消收藏 */
    private VBox buildFavPage() {
        Label title = new Label("我的收藏");
        title.getStyleClass().add("h2");
        Label tip = new Label("在 Mod / 整合包 / 材质包 / 光影卡片右上角点星形按钮收藏，再点一次取消");
        tip.getStyleClass().addAll("muted", "small");
        favStatus = new Label("");
        favStatus.getStyleClass().addAll("muted", "small");
        favCards = new VBox(10);

        VBox content = new VBox(10, title, tip, favStatus, favCards);
        content.setPadding(new Insets(2, 2, 12, 2));
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        makeScrollbarAutoHide(scroll);
        trackGlassScroll(scroll);

        VBox panel = new VBox(scroll);
        panel.getStyleClass().add("panel");
        VBox.setVgrow(scroll, Priority.ALWAYS);
        VBox.setVgrow(panel, Priority.ALWAYS);
        return panel;
    }

    /** 重建收藏列表卡片（进入收藏页或收藏变化后调用） */
    private void refreshFavPage() {
        if (favCards == null) return;
        List<FavEntry> items;
        synchronized (favList) { items = new ArrayList<>(favList); }
        favCards.getChildren().clear();
        if (items.isEmpty()) {
            favStatus.setText("");
            Label ph = new Label("暂无收藏：点下载卡片右上角的星形按钮即可收藏");
            ph.getStyleClass().addAll("muted", "small");
            favCards.getChildren().add(ph);
            return;
        }
        favStatus.setText("共 " + items.size() + " 个收藏");
        for (FavEntry f : items) {
            MinecraftService.ModSearchResult r = f.toResult();
            favCards.getChildren().add(modCard(f.kind, r, () -> onFavOpenDetail(f.kind, r)));
        }
        scheduleGlassApply();   // 新卡片套液态玻璃
    }

    /** 从收藏页打开详情：先切到对应下载子模块，再进二级详情页 */
    private void onFavOpenDetail(String kind, MinecraftService.ModSearchResult r) {
        if ("mod".equals(kind)) {
            switchDownloadHubTab("mods");
            Platform.runLater(() -> onModOpenDetail(r));
        } else {
            switchDownloadHubPack(kind);
            Platform.runLater(() -> onPackOpenDetail(packFor(kind), r));
        }
    }

    /** 搜索结果重排：按标题与搜索词的接近度降序（完全匹配 > 前缀 > 包含 > 编辑距离近），近的在前 */
    private static List<MinecraftService.ModSearchResult> sortByTitleMatch(String query,
            List<MinecraftService.ModSearchResult> list) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty() || list == null || list.size() < 2) return list;
        List<MinecraftService.ModSearchResult> out = new ArrayList<>(list);
        out.sort((a, b) -> Double.compare(titleCloseness(q, b.title()), titleCloseness(q, a.title())));
        return out;
    }

    /** 标题与搜索词的接近度得分（越大越近）：完全匹配 > 前缀 > 包含 > 公共前缀/编辑距离 */
    private static double titleCloseness(String qLower, String title) {
        String t = title == null ? "" : title.trim().toLowerCase(Locale.ROOT);
        if (t.isEmpty()) return -1;
        if (t.equals(qLower)) return 1000;
        if (t.startsWith(qLower)) return 800 - Math.min(300, t.length() - qLower.length());
        if (t.contains(qLower)) return 600 - Math.min(300, t.indexOf(qLower));
        // 否则按公共前缀长度 + 编辑距离倒数粗略评分
        int p = 0, n = Math.min(qLower.length(), t.length());
        while (p < n && qLower.charAt(p) == t.charAt(p)) p++;
        double dist = editDistance(qLower, t);
        return p * 2.0 + 200.0 / (1.0 + dist);
    }

    /** 简单编辑距离（Levenshtein），两词较短，直接 DP */
    private static int editDistance(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev; prev = cur; cur = tmp;
        }
        return prev[b.length()];
    }

    private void loadConfig() {
        if (!Files.exists(CONFIG)) {
            // 未保存过配置：采用按本机内存推算的默认值
            mc.memoryMB = defaultMemory();
            return;
        }
        try {
            Config c = mapper.readValue(CONFIG.toFile(), Config.class);
            loading = true;
            try {
                if (c.gameDir != null && !c.gameDir.isBlank()) mc.gameDir = Path.of(c.gameDir);
                mc.javaPath = c.javaPath == null ? "" : c.javaPath;
                mc.autoJava = c.autoJava;
                // 下载设置（旧配置没有源选择字段时按 useMirror 折算）
                                mc.fileSourceMode = normSrcMode(c.fileSourceMode, c.useMirror);
                mc.metaSourceMode = normSrcMode(c.metaSourceMode, c.useMirror);
                // 旧配置存过 official_first 的（含用旧默认值保存的）统一迁到镜像优先：
                // 官方源在国内是慢速涓流/黑洞，是“下载原版也慢”的直接原因
                if ("official_first".equals(mc.fileSourceMode)) mc.fileSourceMode = "mirror_first";
                if ("official_first".equals(mc.metaSourceMode)) mc.metaSourceMode = "mirror_first";
                mc.setDownloadThreads(c.downloadThreads);
                mc.setSpeedLimitKb(c.speedLimitKb);
                MicrosoftAuth.verifySsl = c.verifySsl;
                mc.memoryMB = c.memoryMB <= 0 ? defaultMemory() : c.memoryMB;
                // 夹到当前可调区间（内存可能变化，或旧配置超范围）
                mc.memoryMB = Math.max(minMemMB, Math.min(mc.memoryMB, maxMemMB));
                globalMemMode = "custom".equals(c.globalMemMode) ? "custom" : "auto";
                // 缺字段/旧配置（null）默认开启全局版本隔离，不用用户自己开；显式 false 才关闭
                globalIsolation = c.globalIsolation == null || c.globalIsolation;
                if (c.jvmArgs != null && !c.jvmArgs.isBlank()) mc.jvmArgs = c.jvmArgs;

                accountName = c.accountName == null ? "" : c.accountName;
                accountUuid = c.accountUuid == null ? "" : c.accountUuid;
                accountToken = c.accountToken == null ? "" : c.accountToken;
                accountRefresh = c.accountRefresh == null ? "" : c.accountRefresh;
                accountType = c.accountType == null ? "" : c.accountType;
                // 旧配置无 accountType：有 token 的账户都是 Microsoft
                if (accountType.isBlank() && !accountToken.isBlank()) accountType = "microsoft";
                authApi = c.authApi == null ? "" : c.authApi;
                authClientToken = c.authClientToken == null ? "" : c.authClientToken;

                // 收藏（模组/整合包/材质包/光影）
                synchronized (favList) {
                    favList.clear();
                    if (c.favorites != null) for (FavEntry f : c.favorites) if (f != null) favList.add(f);
                }

                // frp 配置
                String savedKey = c.frpAccessKey == null ? "" : c.frpAccessKey;
                List<String> savedIds = c.frpTunnelIds == null ? List.of() : c.frpTunnelIds;
                String savedFrpc = c.frpcPath == null ? "" : c.frpcPath;
                // UI 控件可能还未构建（首次加载），等 start() 结束后再填
                Platform.runLater(() -> {
                    if (frpAccessKeyPw != null && !savedKey.isBlank())
                        frpAccessKeyPw.setText(savedKey);
                    if (frpTunnelList != null) {
                        frpTunnelList.getItems().clear();
                        for (String id : savedIds)
                            if (id != null && !id.isBlank()) frpTunnelList.getItems().add(id);
                    }
                    if (!savedFrpc.isBlank()) {
                        Path p = Path.of(savedFrpc);
                        if (Files.exists(p)) frp.setFrpcPath(p);
                    }
                    // 手动选的路径无效时回退到内置 frpc
                    if (!frp.frpcReady()) {
                        Path builtin = builtinFrpcPath();
                        if (Files.isRegularFile(builtin)) frp.setFrpcPath(builtin);
                    }
                });

                // 版本独立设置
                verSettings.clear();
                if (c.verSettings != null) verSettings.putAll(c.verSettings);

                dirField.setText(mc.gameDir.toString());
                javaField.setText(mc.javaPath);
                autoJavaBox.setSelected(mc.autoJava);
                javaField.setDisable(mc.autoJava);
                if (fileSrcBox != null) fileSrcBox.setValue(srcModeText(mc.fileSourceMode));
                if (metaSrcBox != null) metaSrcBox.setValue(srcModeText(mc.metaSourceMode));
                if (threadSlider != null) threadSlider.setValue(mc.downloadThreads);
                if (threadValLb != null) threadValLb.setText(String.valueOf(mc.downloadThreads));
                if (speedSlider != null) speedSlider.setValue(sliderFromSpeedKb(mc.speedLimitKb));
                if (speedValLb != null) speedValLb.setText(speedText(mc.speedLimitKb));
                if (sslBox != null) sslBox.setSelected(MicrosoftAuth.verifySsl);
                memSlider.setValue(mc.memoryMB);
                if (glMemAuto != null) glMemAuto.setSelected(!"custom".equals(globalMemMode));
                if (glMemCustom != null) glMemCustom.setSelected("custom".equals(globalMemMode));
                if (memSlider != null) memSlider.setDisable(!"custom".equals(globalMemMode));
                updateGlMemLabels();
                jvmField.setText(mc.jvmArgs);
                userField.setText(c.playerName == null || c.playerName.isBlank() ? "Steve" : c.playerName);

                // 设置页：游戏窗口 / 启动行为 / 高级选项
                windowTitle = c.windowTitle == null ? "" : c.windowTitle;
                customInfo = c.customInfo == null ? "" : c.customInfo;
                launcherVis = c.launcherVis == null || c.launcherVis.isBlank() ? "keep" : c.launcherVis;
                processPriority = c.processPriority == null || c.processPriority.isBlank() ? "normal" : c.processPriority;
                windowSize = c.windowSize == null || c.windowSize.isBlank() ? "default" : c.windowSize;
                memOptimize = c.memOptimize;
                gameArgs = c.gameArgs == null ? "" : c.gameArgs;
                preLaunchCmd = c.preLaunchCmd == null ? "" : c.preLaunchCmd;
                gcMode = c.gcMode == null || c.gcMode.isBlank() ? "g1" : c.gcMode;
                disableLaunchWrapper = c.disableLaunchWrapper;
                disableLwjglAgent = c.disableLwjglAgent;
                highPerfGpu = c.highPerfGpu;
                offlineSkin = c.offlineSkin == null || c.offlineSkin.isBlank() ? "random" : c.offlineSkin;
                customSkinFile = c.customSkinFile == null ? "" : c.customSkinFile;
                customBgFile = c.customBgFile == null ? "" : c.customBgFile;
                customBgDim = c.customBgDim >= 0 && c.customBgDim <= 0.8 ? c.customBgDim : 0.35;
                if (winTitleField != null) winTitleField.setText(windowTitle);
                if (customInfoField != null) customInfoField.setText(customInfo);
                if (gameArgsField != null) gameArgsField.setText(gameArgs);
                if (preCmdField != null) preCmdField.setText(preLaunchCmd);
                if (memOptBox != null) memOptBox.setSelected(memOptimize);
                if (chkHighGpu != null) chkHighGpu.setSelected(highPerfGpu);
                if (chkLaunchWrap != null) chkLaunchWrap.setSelected(disableLaunchWrapper);
                if (chkLwjglAgent != null) chkLwjglAgent.setSelected(disableLwjglAgent);
                if (visBox != null) visBox.setValue("keep".equals(launcherVis) ? visBox.getItems().get(0)
                        : "hide".equals(launcherVis) ? visBox.getItems().get(1) : visBox.getItems().get(2));
                if (priBox != null) priBox.setValue("low".equals(processPriority) ? "低"
                        : "high".equals(processPriority) ? "高" : "中（平衡）");
                if (sizeBox != null) sizeBox.setValue("default".equals(windowSize) ? "默认"
                        : sizeBox.getItems().contains(windowSize) ? windowSize : "默认");
                if (gcBox != null) gcBox.setValue("g1".equals(gcMode) ? gcBox.getItems().get(1)
                        : "zgc".equals(gcMode) ? gcBox.getItems().get(2)
                        : "shenandoah".equals(gcMode) ? gcBox.getItems().get(3) : gcBox.getItems().get(0));
                if (skinBox != null) skinBox.setValue("random".equals(offlineSkin) ? "随机"
                        : "steve".equals(offlineSkin) ? "Steve" : "alex".equals(offlineSkin) ? "Alex"
                        : "official".equals(offlineSkin) ? "正版皮肤" : "自定义");
                refreshTitleText();
                refreshSkinPreview();
                Platform.runLater(this::applyCustomBg);

                if (!accountToken.isBlank()) {
                    profile.name = accountName;
                    profile.uuid = accountUuid;
                    profile.accessToken = accountToken;
                    profile.isMicrosoft = "microsoft".equals(accountType);
                    profile.isYggdrasil = "yggdrasil".equals(accountType);
                    profile.authApi = profile.isYggdrasil ? authApi : "";
                } else {
                    profile.name = userField.getText().trim();
                }
                updateAccountUi();
                log("配置已加载：" + CONFIG.toAbsolutePath());
            } finally {
                loading = false;
            }
        } catch (Exception ex) {
            log("读取配置失败：" + ex.getMessage());
        }
    }

    private void saveConfig() {
        if (loading) return;
        try {
            Config c = new Config();
            c.gameDir = mc.gameDir.toString();
            c.javaPath = mc.javaPath;
            c.autoJava = mc.autoJava;
            c.useMirror = mc.fileSourceMode.contains("mirror");   // 兼容旧版配置字段
            c.fileSourceMode = mc.fileSourceMode;
            c.metaSourceMode = mc.metaSourceMode;
            c.downloadThreads = mc.downloadThreads;
            c.speedLimitKb = mc.speedLimitKb;
            c.verifySsl = MicrosoftAuth.verifySsl;
            c.memoryMB = mc.memoryMB;
            c.globalMemMode = globalMemMode;
            c.globalIsolation = globalIsolation;
            c.jvmArgs = mc.jvmArgs;
            c.playerName = userField == null ? profile.name
                    : userField.getText().trim().isEmpty() ? "Steve" : userField.getText().trim();
            c.accountName = accountName;
            c.accountUuid = accountUuid;
            c.accountToken = accountToken;
            c.accountRefresh = accountRefresh;
            c.accountType = accountType;
            c.authApi = authApi;
            c.authClientToken = authClientToken;
            // frp
            c.frpAccessKey = frpAccessKeyPw == null ? ""
                    : frpAccessKeyPw.getText() == null ? "" : frpAccessKeyPw.getText().trim();
            c.frpTunnelIds = frpTunnelList == null ? List.of()
                    : List.copyOf(frpTunnelList.getItems());
            c.frpcPath = frp.frpcPath() == null ? "" : frp.frpcPath().toString();
            // 设置页：游戏窗口 / 启动行为 / 高级选项
            c.windowTitle = windowTitle;
            c.customInfo = customInfo;
            c.launcherVis = launcherVis;
            c.processPriority = processPriority;
            c.windowSize = windowSize;
            c.memOptimize = memOptimize;
            c.gameArgs = gameArgs;
            c.preLaunchCmd = preLaunchCmd;
            c.gcMode = gcMode;
            c.disableLaunchWrapper = disableLaunchWrapper;
            c.disableLwjglAgent = disableLwjglAgent;
            c.highPerfGpu = highPerfGpu;
            c.offlineSkin = offlineSkin;
            c.customSkinFile = customSkinFile;
            c.customBgFile = customBgFile;
            c.customBgDim = customBgDim;
            c.verSettings = verSettings;
            synchronized (favList) { c.favorites = new ArrayList<>(favList); }
            mapper.writerWithDefaultPrettyPrinter().writeValue(CONFIG.toFile(), c);
        } catch (Exception ex) {
            log("保存配置失败：" + ex.getMessage());
        }
    }
}
