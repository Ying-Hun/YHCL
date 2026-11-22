package com.deepslate.yhcl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class MinecraftService {

    // 必须强制 HTTP/1.1：默认协商 HTTP/2 时 JDK 会把同一主机的全部并发请求多路复用到
    // 一条 TCP 连接上，64 线程/分片并发实际只有一条连接的带宽，下载必慢；且一条 h2 连接的
    // TCP 队头阻塞会拖住所有流，几千个小文件挤在同一连接上还会因响应头排队超 30s 超时反复失败。
    // h1 下每个请求各自一条连接，多线程/分片并发才能真正聚合带宽。
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))   // 10 秒连不上就判死换源，别让黑洞主机拖满长超时
            .build();
    /** 版本列表（maven-metadata）专用客户端：连接超时压到 5 秒，官方源是黑洞时能快速放弃 */
    private static final HttpClient META_HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_RETRY = 5;

    /* ---- 大文件分片并发下载 ---- */
    /** 低于此大小不分片（小文件分片反而更慢） */
    private static final long CHUNK_MIN = 4L * 1024 * 1024;
    /** 每片约 4MB */
    private static final long CHUNK_SIZE = 4L * 1024 * 1024;
    /** 分片并发线程池（并发额度由 CHUNK_SEM 按「最大线程数」设置控制） */
    private static final ExecutorService CHUNK_POOL = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "yhcl-chunk-dl");
        t.setDaemon(true);
        return t;
    });
    /** 分片下载的全局并发额度（许可数随「最大线程数」设置动态调整） */
    private static final DynamicSemaphore CHUNK_SEM = new DynamicSemaphore(64);

    public Path gameDir = defaultGameDir();
    public int memoryMB = 4096;
    public String javaPath = "";
    public boolean autoJava = true;
    /** 文件下载源：official_first | mirror_first | official_only | mirror_only */
    // 默认镜像优先：国内直连 Mojang 官方源常是慢速涓流/黑洞，先镜像才能让默认配置就快
    public String fileSourceMode = "mirror_first";
    /** 版本列表源：取值同上 */
    public String metaSourceMode = "mirror_first";
    /** 最大下载线程数 1~256（经 setDownloadThreads 调整） */
    public int downloadThreads = 64;
    /** 全局下载速度上限（KB/s），0 = 无限制（经 setSpeedLimitKb 调整） */
    public double speedLimitKb = 0;
    /** 全局限速器（令牌桶），所有文件下载共用一个额度 */
    private final RateLimiter limiter = new RateLimiter();

    public String jvmArgs =
            "-XX:+UseG1GC -XX:MaxGCPauseMillis=50 -XX:+UnlockExperimentalVMOptions -XX:G1NewSizePercent=20";

    /** 设置最大下载线程数（1~256），同时调整分片下载的全局并发额度 */
    public void setDownloadThreads(int n) {
        downloadThreads = Math.max(1, Math.min(256, n));
        CHUNK_SEM.setPermits(downloadThreads);
    }

    /** 当前生效的下载并发数（1~256） */
    private int dlThreads() {
        return Math.max(1, Math.min(256, downloadThreads));
    }

    /** 设置全局下载速度上限（KB/s，0 = 无限制） */
    public void setSpeedLimitKb(double kbPerSec) {
        speedLimitKb = Math.max(0, kbPerSec);
        limiter.setBytesPerSec(speedLimitKb <= 0 ? 0 : speedLimitKb * 1024);
    }

    /** 下载源设置的展示文案 */
    public static String sourceLabel(String mode) {
        return switch (mode) {
            case "mirror_first" -> "BMCLAPI 镜像优先";
            case "official_only" -> "仅官方源";
            case "mirror_only" -> "仅 BMCLAPI 镜像";
            default -> "官方源优先";
        };
    }

    /** 动态许可数的信号量（Java 自带 Semaphore 的许可数不能调） */
    private static final class DynamicSemaphore extends Semaphore {
        private int permits;
        DynamicSemaphore(int p) { super(p); permits = p; }
        synchronized void setPermits(int p) {
            if (p > permits) release(p - permits);
            else if (p < permits) reducePermits(permits - p);
            permits = p;
        }
    }

    /** 全局下载限速器：令牌桶，容量 = 1 秒的额度。bytesPerSec <= 0 时不限速 */
    private static final class RateLimiter {
        private volatile double bytesPerSec;
        private double tokens;
        private long lastNs = System.nanoTime();

        void setBytesPerSec(double bps) {
            synchronized (this) {
                refill();
                bytesPerSec = bps;
            }
        }

        /** 取 n 字节的额度，不足则阻塞等待（分段取，保证桶容量恒够） */
        void acquire(int n) throws InterruptedException {
            while (n > 0) {
                double bps = bytesPerSec;
                if (bps <= 0) return;
                int take = (int) Math.min(n, Math.max(1, bps));
                acquireOne(take);
                n -= take;
            }
        }

        private void acquireOne(int n) throws InterruptedException {
            synchronized (this) {
                while (bytesPerSec > 0) {
                    refill();
                    if (tokens >= n) { tokens -= n; return; }
                    double need = n - tokens;
                    long waitNs = (long) (need / bytesPerSec * 1_000_000_000L);
                    waitNs = Math.max(1_000_000L, Math.min(waitNs, 500_000_000L));
                    TimeUnit.NANOSECONDS.timedWait(this, waitNs);
                }
            }
        }

        private void refill() {
            long now = System.nanoTime();
            double bps = bytesPerSec;
            if (bps > 0) tokens = Math.min(bps, tokens + (now - lastNs) / 1e9 * bps);
            lastNs = now;
        }
    }

    /** 下载全过程监控：安装/启动流程往里写，界面悬浮球与下载详情页读它做可视化 */
    public final DownloadMonitor monitor = new DownloadMonitor();

    /** 一个下载任务：按顺序尝试 urls，成功为止。timeoutSec 为单次尝试的总超时 */
    private record Job(List<String> urls, Path dest, String sha1, Runnable after, long timeoutSec) {
        Job(List<String> urls, Path dest, String sha1, Runnable after) { this(urls, dest, sha1, after, 300); }
    }

    /** 解析后的依赖库 */
    private record Lib(Path dest, List<String> urls, String sha1, JsonNode raw) {}

    /* ============ 下载全过程监控 ============ */

    /** 单个下载项的状态 */
    public enum ItemState { WAITING, DOWNLOADING, DONE, FAILED }

    /** 下载流程所处阶段 */
    public enum Phase { IDLE, PREPARE, DOWNLOAD, VERIFY, INSTALL, DONE, FAILED, ABORTED }

    /**
     * 全过程下载监控：下载线程写、UI 线程读，写侧只用原子量/不可变快照，
     * 所以 UI 可以随时无锁读取当前状态。同一时刻只会有一个安装/启动任务，共用一个实例即可。
     */
    public static final class DownloadMonitor {
        /** 连续这么久没有任何文件完成，即判定网络挂死，自动停止下载并报错 */
        public static final long STALL_TIMEOUT_SEC = 120;

        private final AtomicInteger total = new AtomicInteger();
        private final AtomicInteger doneCount = new AtomicInteger();
        private final AtomicInteger failCount = new AtomicInteger();
        private final AtomicInteger activeCount = new AtomicInteger();
        private final AtomicLong bytes = new AtomicLong();
        private final AtomicBoolean cancel = new AtomicBoolean();
        private final AtomicBoolean running = new AtomicBoolean();
        private final AtomicBoolean paused = new AtomicBoolean();
        /** 下载未完成（取消/失败/挂死）：界面在下载结束时把本次会话下载的文件删掉 */
        private final AtomicBoolean deleteOnCancel = new AtomicBoolean();
        private final Object pauseLock = new Object();
        /** 本次会话实际下载（写入）过的文件清单，下载未完成时统一删除 */
        private final Set<Path> sessionFiles = ConcurrentHashMap.newKeySet();
        private final ConcurrentHashMap<Integer, String> itemNotes = new ConcurrentHashMap<>();
        private volatile AtomicIntegerArray states = new AtomicIntegerArray(0);
        private volatile List<String> names = List.of();
        private volatile long startAt, endAt, lastTouch;
        private volatile Phase phase = Phase.IDLE;
        private volatile String note = "";
        private volatile String error = "";
        /** 各写入流已计入 liveBytes 的峰值（key = 写入文件绝对路径，值 = 已计入的峰值字节） */
        private final ConcurrentHashMap<String, long[]> itemLive = new ConcurrentHashMap<>();
        /** 本次会话已写入的实时字节数（按写入流峰值累计，重试/续传不重复计） */
        private final AtomicLong liveBytes = new AtomicLong();
        /** 已知目标大小的下载项（.tmp 与最终文件同 key 去重） */
        private final Set<String> sizedItems = ConcurrentHashMap.newKeySet();
        /** 已知目标大小之和（ETA 估算总量用） */
        private final AtomicLong expectedBytes = new AtomicLong();

        /** 开始一次新的下载流程（清空上一次的全部状态） */
        public synchronized void begin(String title) {
            total.set(0); doneCount.set(0); failCount.set(0); activeCount.set(0); bytes.set(0);
            cancel.set(false); running.set(true);
            paused.set(false); deleteOnCancel.set(false);
            sessionFiles.clear();
            itemNotes.clear();
            itemLive.clear();
            liveBytes.set(0);
            sizedItems.clear();
            expectedBytes.set(0);
            states = new AtomicIntegerArray(0);
            names = List.of();
            startAt = System.currentTimeMillis();
            lastTouch = startAt;
            endAt = 0;
            phase = Phase.PREPARE;
            note = title == null ? "" : title;
            error = "";
        }

        /** 追加一批文件到清单（阶段推进时调用），返回这批文件在清单里的起始下标 */
        public synchronized int addBatch(List<String> itemNames) {
            List<String> merged = new ArrayList<>(names);
            merged.addAll(itemNames);
            AtomicIntegerArray old = states;
            AtomicIntegerArray st = new AtomicIntegerArray(merged.size());
            for (int i = 0; i < old.length(); i++) st.set(i, old.get(i));
            int base = names.size();
            names = merged;
            states = st;
            total.set(merged.size());
            touch();
            return base;
        }

        /** 离开旧状态时把对应的计数器收回来，保证 done/fail/active 三者始终自洽 */
        private void leave(int idx, int prev) {
            ItemState s = ItemState.values()[prev];
            if (s == ItemState.DOWNLOADING) activeCount.decrementAndGet();
            else if (s == ItemState.FAILED) failCount.decrementAndGet();
            else if (s == ItemState.DONE) doneCount.decrementAndGet();   // 校验修复重下时退回计数
        }

        public void markDownloading(int idx) {
            if (idx < 0 || idx >= states.length()) return;
            leave(idx, states.get(idx));
            states.set(idx, ItemState.DOWNLOADING.ordinal());
            activeCount.incrementAndGet();
            touch();
        }

        public void markDone(int idx, Path file) {
            if (idx < 0 || idx >= states.length()) return;
            leave(idx, states.get(idx));
            states.set(idx, ItemState.DONE.ordinal());
            doneCount.incrementAndGet();
            itemNotes.remove(idx);
            if (file != null) {
                try {
                    if (Files.exists(file)) {
                        long size = Files.size(file);
                        bytes.addAndGet(size);
                        noteItemSize(file, size);
                        setItemBytes(file, size);
                    }
                } catch (IOException ignored) { }
            }
            touch();
        }

        public void markFailed(int idx, String msg) {
            if (idx < 0 || idx >= states.length()) return;
            int prev = states.get(idx);
            if (prev == ItemState.FAILED.ordinal()) return;   // 重复失败不重复计数
            leave(idx, prev);
            states.set(idx, ItemState.FAILED.ordinal());
            failCount.incrementAndGet();
            if (msg != null && !msg.isBlank()) itemNotes.put(idx, msg);
            touch();
        }

        /** 被中止时把没跑完的项退回「等待」，避免把取消当成失败刷屏 */
        public void markWaiting(int idx) {
            if (idx < 0 || idx >= states.length()) return;
            leave(idx, states.get(idx));
            states.set(idx, ItemState.WAITING.ordinal());
        }

        public int total() { return total.get(); }
        public int doneCount() { return doneCount.get(); }
        public int failCount() { return failCount.get(); }
        public int activeCount() { return activeCount.get(); }
        public long bytes() { return bytes.get(); }
        public List<String> names() { return names; }
        public String name(int idx) {
            List<String> n = names;
            return idx >= 0 && idx < n.size() ? n.get(idx) : "";
        }
        public ItemState state(int idx) {
            AtomicIntegerArray s = states;
            if (idx < 0 || idx >= s.length()) return ItemState.WAITING;
            return ItemState.values()[s.get(idx)];
        }
        public String itemNote(int idx) { return itemNotes.getOrDefault(idx, ""); }
        public Phase phase() { return phase; }
        public String note() { return note; }
        public String error() { return error; }
        public boolean running() { return running.get(); }
        public boolean cancelRequested() { return cancel.get(); }

        /** 0~1 的完成度（含重试成功项） */
        public double progress() {
            int t = total.get();
            return t == 0 ? 0 : (double) (doneCount.get() + failCount.get()) / t;
        }
        public long elapsedMs() {
            long end = endAt == 0 ? System.currentTimeMillis() : endAt;
            return Math.max(0, end - startAt);
        }
        /** 距离上一次有文件完成过去了多久（看门狗用） */
        public long idleMs() { return System.currentTimeMillis() - lastTouch; }
        public void touch() { lastTouch = System.currentTimeMillis(); }

        public void setPhase(Phase p, String text) {
            phase = p;
            if (text != null && !text.isBlank()) note = text;
        }
        public void setNote(String text) { if (text != null) note = text; }

        /** 请求停止下载（用户点「停止下载」）：状态置为已中止，界面据此显示中止态 */
        public void cancel() {
            cancel.set(true);
            paused.set(false);
            synchronized (pauseLock) { pauseLock.notifyAll(); }
            note = "正在停止下载…";
            if (phase != Phase.ABORTED) {
                phase = Phase.ABORTED;
                if (error.isBlank()) error = "已停止下载";
            }
        }

        /** 暂停下载：所有下载线程停在闸门上，恢复后原地继续 */
        public void pause() {
            if (!running.get() || cancel.get()) return;
            paused.set(true);
            note = "下载已暂停";
            touch();
        }

        /** 继续下载 */
        public void resume() {
            paused.set(false);
            touch();
            synchronized (pauseLock) { pauseLock.notifyAll(); }
            if (running.get() && !cancel.get()) note = "已恢复下载";
        }

        /** 暂停/继续切换，返回切换后是否处于暂停 */
        public boolean togglePause() {
            if (paused.get()) { resume(); return false; }
            pause(); return true;
        }

        public boolean paused() { return paused.get(); }

        /**
         * 暂停闸门：暂停期间阻塞在此，恢复后返回本次因暂停等待的纳秒数
         * （调用方用它延后超时截止）；被取消（含暂停中被取消）则抛出 IOException。
         */
        public long gate() throws IOException {
            if (cancel.get()) throw new IOException("下载已取消");
            if (!paused.get()) return 0;
            long t0 = System.nanoTime();
            synchronized (pauseLock) {
                while (paused.get() && !cancel.get()) {
                    try {
                        pauseLock.wait(500);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException("下载被中断");
                    }
                    touch();   // 暂停期间也喂看门狗
                }
            }
            if (cancel.get()) throw new IOException("下载已取消");
            return System.nanoTime() - t0;
        }

        /** 取消下载，并要求界面在下载停止后删除本次会话下载的文件 */
        public void cancelAndDelete() {
            deleteOnCancel.set(true);
            cancel();
        }

        /** 是否有未处理的「取消并删除」请求（界面取走后自动复位） */
        public boolean consumeDeleteRequest() { return deleteOnCancel.getAndSet(false); }

        /** 登记本次会话实际下载的文件（取消下载时删除用） */
        public void registerDownloaded(Path p) { if (p != null) sessionFiles.add(p); }

        /** 取出并清空本次会话下载的文件清单 */
        public List<Path> drainSessionFiles() {
            List<Path> out = new ArrayList<>(sessionFiles);
            sessionFiles.clear();
            return out;
        }

        /* ---- 字节级实时进度（ETA：预计总量 - 已下字节，除以当前速度） ---- */

        /** 登记文件目标大小（只计一次）：.tmp 与最终文件同 key，中间态与结果不重复计 */
        public void noteItemSize(Path item, long size) {
            if (item == null || size <= 0) return;
            String s = item.toAbsolutePath().normalize().toString();
            String k = s.endsWith(".tmp") ? s.substring(0, s.length() - 4) : s;
            if (sizedItems.add(k)) expectedBytes.addAndGet(size);
        }

        /**
         * 峰值记账：partial 为该写入流的累计字节，只把新增部分计入已下字节。
         * 断点续传接着旧峰值累计、从头重下不回退不重计，是 ETA「已下字节」的实时来源。
         */
        public void setItemBytes(Path p, long partial) {
            if (p == null || partial <= 0) return;
            String k = p.toAbsolutePath().normalize().toString();
            synchronized (itemLive) {
                long[] e = itemLive.get(k);
                long old = e == null ? 0 : e[0];
                if (partial <= old) return;
                if (e == null) itemLive.put(k, new long[]{partial});
                else e[0] = partial;
                liveBytes.addAndGet(partial - old);
            }
        }

        /** 流收尾归账：丢弃写入流（.tmp）的峰值，改记最终文件大小（峰值幂等，markDone 重复调不叠加） */
        public void settleBytes(Path streamed, Path item, long size) {
            if (streamed != null) dropItemKey(streamed.toAbsolutePath().normalize().toString());
            if (item != null && size > 0) setItemBytes(item, size);
        }

        /** 分片合并归账：丢弃各分片峰值，整体按合并总量记到目标（失败清理传 0 只丢不记） */
        public void foldBytes(List<Path> parts, Path target, long total) {
            if (parts != null) {
                for (Path p : parts) dropItemKey(p.toAbsolutePath().normalize().toString());
            }
            if (target != null && total > 0) setItemBytes(target, total);
        }

        private void dropItemKey(String k) {
            synchronized (itemLive) {
                long[] e = itemLive.remove(k);
                if (e != null) liveBytes.addAndGet(-e[0]);
            }
        }

        /** 本次会话已写入的实时字节数 */
        public long liveBytes() { return Math.max(0, liveBytes.get()); }

        /**
         * 预计总字节：已知大小之和 + 未知名按已知均值外推
         * （完全没有已知大小时按每文件 2MB 兜底），供 ETA 计算用。
         */
        public long estimatedTotalBytes() {
            long exp = expectedBytes.get();
            int sized = sizedItems.size();
            int t = total.get();
            if (sized == 0) return Math.max(exp, t * 2L * 1024 * 1024);
            double avg = (double) exp / sized;
            return (long) (exp + Math.max(0, t - sized) * avg);
        }

        /** 看门狗判定超时：中止下载并记录报错原因 */
        public void abort(String reason) {
            cancel.set(true);
            deleteOnCancel.set(true);   // 失败同样清掉本次下载的文件
            phase = Phase.ABORTED;
            error = reason == null ? "" : reason;
            note = error;
        }

        public void finishOk() {
            if (phase != Phase.ABORTED) phase = Phase.DONE;
            running.set(false);
            endAt = System.currentTimeMillis();
        }

        public void finishFail(String msg) {
            if (phase != Phase.ABORTED) phase = Phase.FAILED;
            if (msg != null && !msg.isBlank()) error = msg;
            if (error.isBlank()) error = "下载失败";
            note = error;
            deleteOnCancel.set(true);   // 失败同样清掉本次下载的文件
            running.set(false);
            endAt = System.currentTimeMillis();
        }

        public String phaseText() {
            return switch (phase) {
                case IDLE -> "空闲";
                case PREPARE -> "准备中";
                case DOWNLOAD -> "下载中";
                case VERIFY -> "校验中";
                case INSTALL -> "安装中";
                case DONE -> "已完成";
                case FAILED -> "已失败";
                case ABORTED -> "已中止";
            };
        }
    }

    private static Path defaultGameDir() {
        // 游戏目录默认放在启动器 exe 的同级目录下（SFX 布局为 exe-dir\YHCL\app\yhcl.jar，需向上越过 YHCL）
        return Launcher.exeDir().resolve(".minecraft");
    }

    /** 把 Mojang / maven 官方地址改写成 BMCLAPI 镜像 */
    private String mirror(String url) {
        return url
                .replace("https://piston-meta.mojang.com", "https://bmclapi2.bangbang93.com")
                .replace("https://launchermeta.mojang.com", "https://bmclapi2.bangbang93.com")
                .replace("https://launcher.mojang.com", "https://bmclapi2.bangbang93.com")
                .replace("https://piston-data.mojang.com", "https://bmclapi2.bangbang93.com")
                .replace("https://resources.download.minecraft.net", "https://bmclapi2.bangbang93.com/assets")
                .replace("https://libraries.minecraft.net", "https://bmclapi2.bangbang93.com/maven")
                .replace("https://maven.fabricmc.net", "https://bmclapi2.bangbang93.com/maven")
                .replace("https://maven.minecraftforge.net", "https://bmclapi2.bangbang93.com/maven")
                .replace("https://maven.neoforged.net/releases", "https://bmclapi2.bangbang93.com/maven")
                .replace("https://repo1.maven.org/maven2", "https://bmclapi2.bangbang93.com/maven")
                // Fabric 官方 meta API（loader 列表/profile）在国内直连不稳定，走 BMCLAPI 的 fabric-meta
                .replace("https://meta.fabricmc.net/v2", "https://bmclapi2.bangbang93.com/fabric-meta/v2")
                // 账户/皮肤接口（离线皮肤的正版皮肤拉取）也走 BMCLAPI
                .replace("https://api.mojang.com", "https://bmclapi2.bangbang93.com")
                .replace("https://sessionserver.mojang.com", "https://bmclapi2.bangbang93.com");
    }

    /* ============ 版本清单 ============ */
    public List<VersionEntry> getManifest() throws IOException, InterruptedException {
        String json = getText("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json", metaSourceMode);
        JsonNode root = MAPPER.readTree(json);
        List<VersionEntry> out = new ArrayList<>();
        for (JsonNode v : root.path("versions")) {
            VersionEntry e = new VersionEntry();
            e.id = v.path("id").asText();
            e.type = v.path("type").asText();
            e.url = v.path("url").asText();
            e.releaseTime = v.path("releaseTime").asText("");
            out.add(e);
        }
        return out;
    }

    /** 版本详情（详情页展示用） */
    public record VersionMeta(String id, String type, String releaseTime, String javaVersion, long clientSize) { }

    /** 拉取单个版本的元数据 JSON */
    public VersionMeta fetchVersionMeta(String versionUrl) throws IOException, InterruptedException {
        JsonNode n = MAPPER.readTree(getText(versionUrl, metaSourceMode));
        return new VersionMeta(
                n.path("id").asText(""),
                n.path("type").asText(""),
                n.path("releaseTime").asText(""),
                n.path("javaVersion").path("majorVersion").asText(""),
                n.path("downloads").path("client").path("size").asLong(0));
    }

    public List<String> getInstalled() {
        List<String> out = new ArrayList<>();
        Path dir = gameDir.resolve("versions");
        if (!Files.isDirectory(dir)) return out;
        try (var stream = Files.list(dir)) {
            stream.filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString())
                    .filter(id -> Files.exists(dir.resolve(id).resolve(id + ".json")))
                    .sorted(Comparator.reverseOrder())
                    .forEach(out::add);
        } catch (IOException ignored) { }
        return out;
    }

    /** 读取某个已安装版本对应的 Minecraft 主版本。
     *  加载器版本（Fabric/Forge/NeoForge）的 json 里有 inheritsFrom 指向原版 MC；
     *  整合包版本以整合包名命名且 json 被合并成自包含（去掉 inheritsFrom），
     *  MC 版本记在 yhclMc 标记里，需要一并读取，否则会把整合包名当 MC 版本号用。
     *  原版版本则直接返回自身 id。读取失败时退回 id。 */
    public String getMcVersion(String id) {
        Path p = gameDir.resolve("versions").resolve(id).resolve(id + ".json");
        if (!Files.exists(p)) return id;
        try {
            JsonNode n = MAPPER.readTree(p.toFile());
            String inh = n.path("yhclMc").asText(n.path("inheritsFrom").asText(""));
            if (!inh.isBlank()) return inh;
        } catch (Exception ignored) { }
        // 无 inheritsFrom：原版版本返回自身 id；清理重复原版后合并成自包含 json 的
        // 加载器版本（如 1.20.1-fabric-0.15.0）从 id 里的加载器标记解析出 MC 版本
        for (String tag : new String[]{"-fabric-", "-neoforge-", "-forge-"}) {
            int i = id.indexOf(tag);
            if (i > 0) return id.substring(0, i);
        }
        Matcher m = Pattern.compile("^(\\d+\\.\\d+(?:\\.\\d+)?)-forge\\d").matcher(id);   // 旧版 Forge：1.12.2-forge14.23.5.2854
        return m.find() ? m.group(1) : id;
    }

    /**
     * 版本所用的加载器类型（forge / neoforge / fabric / quilt），原版返回空串。
     * 整合包版本目录以整合包名命名（不带加载器字样），所以先读版本 json 里的 yhclLoader 标记。
     */
    public String getVersionLoader(String id) {
        if (id == null || id.isBlank()) return "";
        Path p = gameDir.resolve("versions").resolve(id).resolve(id + ".json");
        if (Files.isRegularFile(p)) {
            try {
                String tag = MAPPER.readTree(p.toFile()).path("yhclLoader").asText("").toLowerCase(Locale.ROOT);
                if (!tag.isBlank()) return tag;
            } catch (Exception ignored) { }
        }
        String lower = id.toLowerCase(Locale.ROOT);
        if (lower.contains("neoforge")) return "neoforge";
        if (lower.contains("forge")) return "forge";
        if (lower.contains("fabric")) return "fabric";
        if (lower.contains("quilt")) return "quilt";
        return "";
    }

    /* ============ 残缺版本（有目录但缺 <id>.json，多为安装中断残留） ============ */
    /** 列出残缺版本 id：这些目录启动器不能用，但必须让用户看得见、删得掉 */
    public List<String> getBrokenVersions() {
        List<String> out = new ArrayList<>();
        Path dir = gameDir.resolve("versions");
        if (!Files.isDirectory(dir)) return out;
        try (var stream = Files.list(dir)) {
            stream.filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString())
                    .filter(id -> !Files.exists(dir.resolve(id).resolve(id + ".json")))
                    .sorted(Comparator.reverseOrder())
                    .forEach(out::add);
        } catch (IOException ignored) { }
        return out;
    }

    /* ============ 删除版本（只删 versions/<id>，存档/mods 不动） ============ */
    public void deleteVersion(String id) throws IOException {
        Path dir = gameDir.resolve("versions").resolve(id);
        if (!Files.isDirectory(dir)) throw new IOException("版本不存在：" + id);
        try (var s = Files.walk(dir)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.delete(p); } catch (IOException ignored) { }
            });
        }
    }

    /** 尽力删除目录树，失败静默；仅用于安装失败后清理空壳目录 */
    private static void deleteQuietly(Path dir) {
        try (var s = Files.walk(dir)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.delete(p); } catch (IOException ignored) { }
            });
        } catch (IOException ignored) { }
    }

    /* ============ Mods 管理（mods/ 目录，禁用 = 文件名加 .disabled 后缀） ============ */

    /** mod 条目：fileName 为当前磁盘文件名，enabled=false 时以 .disabled 结尾 */
    public record ModEntry(String fileName, boolean enabled, long size) {}

    public List<ModEntry> listMods() {
        return listMods(gameDir.resolve("mods"));
    }

    /** 列出指定 mods 目录的 mod（含 .jar.disabled） */
    public List<ModEntry> listMods(Path dir) {
        List<ModEntry> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) return out;
        try (var s = Files.list(dir)) {
            s.filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .filter(n -> {
                        String l = n.toLowerCase(Locale.ROOT);
                        return l.endsWith(".jar") || l.endsWith(".jar.disabled");
                    })
                    .map(n -> {
                        boolean en = !n.toLowerCase(Locale.ROOT).endsWith(".disabled");
                        long sz = 0;
                        try { sz = Files.size(dir.resolve(n)); } catch (IOException ignored) { }
                        return new ModEntry(n, en, sz);
                    })
                    .sorted(Comparator.comparing(ModEntry::fileName, String.CASE_INSENSITIVE_ORDER))
                    .forEach(out::add);
        } catch (IOException ignored) { }
        return out;
    }

    /**
     * 扫描 mods 目录里与指定 MC 版本不兼容的 mod（启动前警告用，只报不拦，尽力检测）：
     * 读 jar 内 mcmod.info 的 mcversion，缺失/占位符时退化到文件名里的 mc1.12.2 标记，
     * 与目标版本的大版本号（1.8 / 1.12 这一级）比对，不一致即报出。
     * 返回「文件名（需要 MC x.y.z）」清单；判定不了的（无任何版本信息）不报，避免误伤。
     */
    public List<String> findIncompatibleMods(Path dir, String mcVersion) {
        List<String> out = new ArrayList<>();
        String family = mcFamily(mcVersion);
        if (family == null || !Files.isDirectory(dir)) return out;
        try (var s = Files.list(dir)) {
            s.filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .filter(n -> n.toLowerCase(Locale.ROOT).endsWith(".jar"))   // .disabled 的不会被加载，不查
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .forEach(n -> {
                        String declared = modMcVersion(dir.resolve(n), n);
                        String df = declared == null ? null : mcFamily(declared);
                        // 区间声明（[1.7,1.12] 之类）含目标大版本即视为兼容，不误报
                        if (df != null && !df.equals(family) && !declared.contains(family))
                            out.add(n + "（需要 MC " + declared + "）");
                    });
        } catch (IOException ignored) { }
        return out;
    }

    /** 取 mod jar 声明的 MC 版本：优先 mcmod.info 的 mcversion，缺失/占位符时退化到文件名 mc1.12.2 标记；无法判定返回 null */
    private static String modMcVersion(Path jar, String fileName) {
        try (ZipFile z = new ZipFile(jar.toFile())) {
            ZipEntry e = z.getEntry("mcmod.info");
            if (e != null) {
                try (InputStream in = z.getInputStream(e)) {
                    String txt = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    Matcher m = Pattern.compile("\"mcversion\"\\s*:\\s*\"([^\"]*)\"").matcher(txt);
                    if (m.find()) {
                        String v = m.group(1).trim();
                        if (!v.isBlank() && !v.contains("${") && !v.contains("[") && !v.contains(","))
                            return v;
                    }
                }
            }
        } catch (Exception ignored) { }
        // 文件名标记：AppleCore-mc1.12.2-3.4.0.jar / BiblioCraft[v2.4.6][MC1.12.2].jar 等
        Matcher m = Pattern.compile("(?i)mc[ _v.\\-]?(\\d+\\.\\d+(?:\\.\\d+)?)").matcher(fileName);
        return m.find() ? m.group(1) : null;
    }

    /** 版本号的「大版本」段：1.8.9 → 1.8，1.12 → 1.12；无法解析返回 null */
    private static String mcFamily(String ver) {
        if (ver == null) return null;
        Matcher m = Pattern.compile("\\d+\\.\\d+").matcher(ver);
        return m.find() ? m.group(0) : null;
    }

    /** 把 .jar 复制进 mods 目录（内容相同则跳过） */
    public void addMod(Path source) throws IOException {
        addMod(source, gameDir.resolve("mods"));
    }

    /** 把 mod 复制进指定 mods 目录（SHA1 相同则跳过） */
    public void addMod(Path source, Path dir) throws IOException {
        String n = source.getFileName().toString();
        if (!n.toLowerCase(Locale.ROOT).endsWith(".jar"))
            throw new IOException("只支持 .jar 格式的 mod：" + n);
        Files.createDirectories(dir);
        Path dest = dir.resolve(n);
        if (Files.exists(dest) && sha1Of(dest).equalsIgnoreCase(sha1Of(source))) return;
        Files.copy(source, dest, StandardCopyOption.REPLACE_EXISTING);
    }

    public void deleteMod(String fileName) throws IOException {
        deleteMod(fileName, gameDir.resolve("mods"));
    }

    public void deleteMod(String fileName, Path dir) throws IOException {
        Path p = dir.resolve(fileName).normalize();
        if (!p.startsWith(dir.normalize())) throw new IOException("非法文件名：" + fileName);
        Files.deleteIfExists(p);
    }

    /** 启用/禁用：通过加/去 .disabled 后缀实现，加载器只认 .jar */
    public void setModEnabled(String fileName, boolean enable) throws IOException {
        setModEnabled(fileName, enable, gameDir.resolve("mods"));
    }

    public void setModEnabled(String fileName, boolean enable, Path dir) throws IOException {
        Path from = dir.resolve(fileName);
        if (!Files.exists(from)) throw new IOException("mod 不存在：" + fileName);
        String target = enable
                ? fileName.replaceAll("(?i)\\.disabled$", "")
                : fileName + ".disabled";
        if (target.equals(fileName)) return;
        Files.move(from, dir.resolve(target), StandardCopyOption.REPLACE_EXISTING);
    }

    /* ============ 加载器：版本列表 ============ */

    /** loader: fabric / forge / neoforge，返回新版本在前 */
    public List<String> listLoaderVersions(String loader, String mcVer) throws Exception {
        return switch (loader) {
            case "fabric"   -> listFabricLoaders(mcVer);
            case "forge"    -> listForge(mcVer);
            case "neoforge" -> listNeoForge(mcVer);
            default -> new ArrayList<>();
        };
    }

    private List<String> listFabricLoaders(String mcVer) throws Exception {
        String json = getText("https://meta.fabricmc.net/v2/versions/loader/" + mcVer, metaSourceMode);
        JsonNode arr = MAPPER.readTree(json);
        List<String> out = new ArrayList<>();
        for (JsonNode v : arr) {
            String ver = v.path("loader").path("version").asText("");   // 版本在 loader.version 嵌套里
            if (!ver.isEmpty()) out.add(ver);
        }
        return out;   // 官方接口本身就是新版本在前
    }

    private List<String> listForge(String mcVer) throws Exception {
        // bmclapi 的 forge maven-metadata 是过期快照（缺新版本号），
        // 官方和镜像并行抓取后合并去重——官方偶发拖慢/超时不再拖住整个列表
        Set<String> raw = fetchVersionsParallel(List.of(
                "https://maven.minecraftforge.net",
                "https://bmclapi2.bangbang93.com/maven"), "net/minecraftforge/forge");
        Set<String> out = new LinkedHashSet<>();
        for (String v : raw)
            if (v.startsWith(mcVer + "-")) out.add(v.substring(mcVer.length() + 1));
        if (out.isEmpty()) throw new IOException("未能从任何源获取 " + mcVer + " 的 Forge 版本列表");
        List<String> list = new ArrayList<>(out);
        list.sort((a, b) -> compareVer(b, a));
        return list;
    }

    private List<String> listNeoForge(String mcVer) throws Exception {
        // 新坐标 net/neoforged/neoforge：1.20.2+，版本号去掉 "1." 前缀（如 1.21.4 -> 21.4.x）
        // 旧坐标 net/neoforged/forge：仅 1.20.1，版本 1.20.1-47.1.x
        String prefix = mcVer.replaceFirst("^1\\.", "") + ".";
        List<String> artifacts = new ArrayList<>(List.of("net/neoforged/neoforge"));
        if (mcVer.equals("1.20.1")) artifacts.add("net/neoforged/forge");
        Set<String> raw = fetchVersionsParallel(List.of(
                "https://maven.neoforged.net/releases",
                "https://bmclapi2.bangbang93.com/maven"), artifacts.toArray(String[]::new));
        Set<String> out = new LinkedHashSet<>();
        for (String v : raw) {
            if (v.startsWith(mcVer + "-")) {          // 旧坐标 1.20.1-47.1.x
                out.add(v.substring(mcVer.length() + 1));
            } else if (v.startsWith(prefix)) {        // 新坐标 21.4.x 等
                out.add(v);
            }
        }
        if (out.isEmpty()) throw new IOException("未能从任何源获取 " + mcVer + " 的 NeoForge 版本列表");
        List<String> list = new ArrayList<>(out);
        list.sort((a, b) -> compareVer(b, a));
        return list;
    }

    /**
     * 并行从多个 maven 源抓取 maven-metadata 的版本号并合并（每个源把所有 artifact 抓完才算成功）。
     * maven.neoforged.net 等官方源在国内常是连接黑洞，等它超时会把列表卡上几十秒；
     * 这里改成「竞速 + 短宽限」：第一个源成功后只再等 2 秒合并其它源（防镜像快照缺新版本），
     * 单源失败不影响其它源，整体硬上限 15 秒。
     */
    private Set<String> fetchVersionsParallel(List<String> bases, String... artifacts) {
        Set<String> out = ConcurrentHashMap.newKeySet();
        // 按「版本列表源」设置筛源：「仅」模式只留单源，其余模式两源并行竞速合并
        List<String> src = new ArrayList<>();
        for (String b : bases) {
            boolean isMirror = b.contains("bangbang93");
            if ("official_only".equals(metaSourceMode) && isMirror) continue;
            if ("mirror_only".equals(metaSourceMode) && !isMirror) continue;
            src.add(b);
        }
        if (src.isEmpty()) src = new ArrayList<>(bases);
        if ("mirror_first".equals(metaSourceMode))
            src.sort((a, b) -> Boolean.compare(!a.contains("bangbang93"), !b.contains("bangbang93")));
        int n = src.size();
        ExecutorService pool = Executors.newFixedThreadPool(n);
        ExecutorCompletionService<Boolean> ecs = new ExecutorCompletionService<>(pool);
        for (String base : src) {
            ecs.submit(() -> {
                Set<String> mine = new LinkedHashSet<>();
                for (String artifact : artifacts) fetchVersionsFrom(base, artifact, mine);
                out.addAll(mine);
                return Boolean.TRUE;
            });
        }
        try {
            long hard = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            long grace = Long.MAX_VALUE;
            for (int got = 0; got < n; got++) {
                long until = Math.min(hard, grace) - System.nanoTime();
                if (until <= 0) break;
                Future<Boolean> f = ecs.poll(until, TimeUnit.NANOSECONDS);
                if (f == null) break;
                try {
                    if (Boolean.TRUE.equals(f.get()) && grace == Long.MAX_VALUE)
                        grace = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                } catch (Exception ignored) { }   // 单源失败/取消，继续等其它源
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } finally {
            pool.shutdownNow();
        }
        return out;
    }

    /** 单源抓取 maven-metadata 里的版本号（短超时、不重试，失败交给竞速逻辑兜底） */
    private void fetchVersionsFrom(String base, String artifact, Set<String> out) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/" + artifact + "/maven-metadata.xml"))
                .timeout(Duration.ofSeconds(12)).GET().build();
        HttpResponse<String> resp =
                META_HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() / 100 != 2) throw new IOException("请求失败 HTTP " + resp.statusCode() + "：" + base);
        Matcher m = Pattern.compile("<version>([^<]+)</version>").matcher(resp.body());
        while (m.find()) out.add(m.group(1));
    }

    /* ============ 加载器：安装 ============ */

    /** 加载器标识 → 展示名 */
    private static String kindLabel(String loader) {
        return switch (loader) {
            case "forge" -> "Forge";
            case "neoforge" -> "NeoForge";
            case "fabric" -> "Fabric";
            default -> "原版";
        };
    }

    /** 在 mcVer 上安装加载器，返回生成的版本 id */
    public String installLoader(String loader, String mcVer, String loaderVer,
                                Consumer<String> log, Consumer<Double> prog, Consumer<String> status) throws Exception {
        if ("vanilla".equals(loader)) return mcVer;
        long installStart = System.currentTimeMillis();   // 本次下载起始（清理重复原版的时间窗锚点）
        monitor.setPhase(Phase.PREPARE, "准备安装 " + kindLabel(loader) + "（" + mcVer + "）");

        // 加载器依赖原版的版本定义（继承解析要用），但不单独跑一遍「原版」安装——那样会先下一堆
        // 原版文件、看起来像「下的是原版」。这里只把原版 json 取下来（几百字节），原版的 jar/库/资源
        // 交给下面的 prepare(加载器id) 跟加载器一起一次下完，整个下载显示为同一个「安装 <加载器>」批次，
        // 即「对应版本 + 加载器」一起下，不再单独显示一遍原版。
        if (!getInstalled().contains(mcVer)) {
            status.accept("准备 " + mcVer + " 原版定义（" + kindLabel(loader) + " 需要）…");
            VersionEntry vanilla = getManifest().stream()
                    .filter(v -> v.id.equals(mcVer)).findFirst()
                    .orElseThrow(() -> new IOException("版本清单里没有 " + mcVer));
            Path vdir = gameDir.resolve("versions").resolve(mcVer);
            Files.createDirectories(vdir);
            downloadFile(vanilla.url, vdir.resolve(mcVer + ".json"), null);
        }

        String ver = loaderVer == null ? "" : loaderVer.trim();
        if (ver.isEmpty()) {
            List<String> all = listLoaderVersions(loader, mcVer);
            if (all.isEmpty()) throw new IOException(mcVer + " 没有可用的 " + loader + " 版本");
            ver = all.getFirst();   // 自动 = 最新
        }

        String id = switch (loader) {
            case "fabric"   -> installFabric(mcVer, ver, log, status);
            case "forge"    -> installForgeLike(mcVer, ver, "forge", log, status);
            case "neoforge" -> installForgeLike(mcVer, ver, "neoforge", log, status);
            default -> mcVer;
        };

        prepare(id, log, prog, status);
        // 勾选加载器会把原版游戏顺带下一份（client jar 归属继承根版本）：
        // 若那份原版是本次下载期间落盘的重复产物，并入加载器版本后删掉，只留可启动的加载器版本
        cleanupRedundantVanilla(mcVer, id, installStart, log);
        status.accept("安装完成：" + id);
        return id;
    }

    /**
     * 清理「勾选加载器」时被顺带重复下载出来的原版版本目录：
     * versions/&lt;mcVer&gt; 的 json/jar 若在本次下载总时长窗口内落盘（即当次下载的重复产物），
     * 把加载器 json 合并继承链改写为自包含（去掉 inheritsFrom）、client jar 挪进加载器目录
     * （buildClasspath 优先自身目录的 jar），然后删除原版目录——加载器版本依旧可正常启动。
     * 时间窗之外落盘的 = 用户原有的原版安装，绝不动。
     */
    private void cleanupRedundantVanilla(String mcVer, String loaderId, long installStart, Consumer<String> log) {
        if (loaderId == null || loaderId.equals(mcVer)) return;
        try {
            Path vdir = gameDir.resolve("versions").resolve(mcVer);
            Path ldir = gameDir.resolve("versions").resolve(loaderId);
            Path ljson = ldir.resolve(loaderId + ".json");
            if (!Files.isDirectory(vdir) || !Files.exists(ljson)) return;
            long touched = 0;
            for (String n : new String[]{mcVer + ".json", mcVer + ".jar"}) {
                Path p = vdir.resolve(n);
                if (Files.exists(p)) touched = Math.max(touched, Files.getLastModifiedTime(p).toMillis());
            }
            if (touched < installStart - 60_000) return;   // 窗口外 = 用户原有安装，不动

            JsonNode merged = loadRoot(loaderId);
            ((ObjectNode) merged).remove("inheritsFrom");
            Files.writeString(ljson, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(merged),
                    StandardCharsets.UTF_8);
            // 2) 原版 client jar 并入加载器目录（加载器版本自身持有 jar 即可启动）
            Path srcJar = vdir.resolve(mcVer + ".jar");
            Path dstJar = ldir.resolve(loaderId + ".jar");
            if (Files.exists(srcJar) && !Files.exists(dstJar))
                Files.move(srcJar, dstJar, StandardCopyOption.REPLACE_EXISTING);
            deleteQuietly(vdir);
            log.accept("已清理重复下载的原版 " + mcVer + "（已并入 " + loaderId + "，可正常启动）");
        } catch (Exception e) {
            log.accept("清理重复的原版 " + mcVer + " 失败：" + e.getMessage() + "（不影响使用）");
        }
    }

    private String installFabric(String mcVer, String loaderVer,
                                 Consumer<String> log, Consumer<String> status) throws Exception {
        status.accept("下载 Fabric Loader 配置…");
        monitor.setPhase(Phase.PREPARE, "获取 Fabric Loader 配置");
        String url = "https://meta.fabricmc.net/v2/versions/loader/" + mcVer + "/" + loaderVer + "/profile/json";
        JsonNode node = MAPPER.readTree(getText(url, metaSourceMode));
        String id = mcVer + "-fabric-" + loaderVer;
        ((ObjectNode) node).put("id", id);
        Path dir = gameDir.resolve("versions").resolve(id);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(id + ".json"),
                MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(node), StandardCharsets.UTF_8);
        log.accept("Fabric Loader " + loaderVer + " 已写入版本 " + id);
        return id;
    }

    /**
     * Forge / NeoForge 安装器在 maven 上的相对路径。
     * NeoForge 1.20.1 发布在旧坐标 net/neoforged/forge 下，版本串形如
     * 1.20.1-47.1.106（版本列表层会剥掉前缀，这里拼回），安装器为
     * forge-&lt;完整版本&gt;-installer.jar；1.20.2+ 在新坐标 net/neoforged/neoforge 下，
     * 安装器为 neoforge-&lt;版本&gt;-installer.jar。此前按「loaderVer 是否带前缀」分支，
     * 但列表层剥过前缀导致条件永不命中，拼出新坐标 404 地址。
     */
    static String installerArtifact(String kind, String mcVer, String loaderVer) {
        if ("forge".equals(kind)) {
            return "net/minecraftforge/forge/" + mcVer + "-" + loaderVer
                    + "/forge-" + mcVer + "-" + loaderVer + "-installer.jar";
        }
        String full = loaderVer.startsWith(mcVer + "-") ? loaderVer : mcVer + "-" + loaderVer;
        if (mcVer.equals("1.20.1")) {
            return "net/neoforged/forge/" + full + "/forge-" + full + "-installer.jar";
        }
        return "net/neoforged/neoforge/" + loaderVer
                + "/neoforge-" + loaderVer + "-installer.jar";
    }

    /**
     * Forge / NeoForge：下载官方 installer 并真正执行 --installClient。
     * 版本 json 里 forge-*-client.jar（几十 MB）是安装器 processor 现场生成的产物，
     * maven 上并不存在（url 为空、官方和镜像都 404），只解压 version.json 必然安装失败。
     */
    private String installForgeLike(String mcVer, String loaderVer, String kind,
                                    Consumer<String> log, Consumer<String> status) throws Exception {
        status.accept("下载 " + kind + " 安装包…");
        monitor.setPhase(Phase.PREPARE, "下载 " + kindLabel(kind) + " 安装包");
        String base = "forge".equals(kind)
                ? "https://maven.minecraftforge.net/"
                : "https://maven.neoforged.net/releases/";
        String artifact = installerArtifact(kind, mcVer, loaderVer);

        Path cacheDir = gameDir.resolve("cache");
        Files.createDirectories(cacheDir);
        Path installer = cacheDir.resolve(kind + "-installer.jar");
        // 安装包也注册进下载清单：详情页能看到任务进度，而不是停留在「准备中 0/0」
        String instName = artifact.substring(artifact.lastIndexOf('/') + 1);
        int instIdx = monitor.addBatch(List.of(instName));
        monitor.setPhase(Phase.DOWNLOAD, "下载 " + kindLabel(kind) + " 安装包");
        monitor.markDownloading(instIdx);
        try {
            // maven 仓库自带 .sha1 校验文件，用它做完整性校验；取不到则靠 zip 结构兜底。
            // 旧坐标等仓库没有 .sha1（镜像 404），走 getText 的重试循环会白耗几十秒，
            // 这里每个候选只快速试一次
            String installerSha1 = null;
            for (String u : candidates(base + artifact + ".sha1")) {
                if (hostDead(u)) continue;
                try {
                    String s = fetchText(u).trim();
                    if (s.matches("[0-9a-fA-F]{40}")) { installerSha1 = s; break; }
                } catch (Exception ignored) { }
            }
            downloadFile(base + artifact, installer, installerSha1);
            monitor.markDone(instIdx, installer);
        } catch (Exception e) {
            if (monitor.cancelRequested()) monitor.markWaiting(instIdx);
            else monitor.markFailed(instIdx, e.getMessage());
            throw e;
        }

        // install_profile.json 的 "version" 就是安装器最终生成的版本 id。
        // Forge 是 <mc>-forge-<loader>，NeoForge 却是 neoforge-<ver>（不带 mc 前缀），
        // 规则不同，必须读安装器自己的取值，不能凭公式拼。
        JsonNode profile = readInstallProfile(installer);
        // spec 1 老安装器（1.20.1 系）把运行时库放在 version.json（loader、modlauncher 等），
        // install_profile.json 里只有安装器自己要用的库；两个清单都要预取，
        // 漏掉哪个，哪个就会由安装器直连官方黑洞去下。
        JsonNode versionJson = readInstallerEntry(installer, "version.json");

        // 官方安装器要求游戏目录存在 launcher_profiles.json，否则直接拒绝安装
        Path profiles = gameDir.resolve("launcher_profiles.json");
        if (!Files.exists(profiles))
            Files.writeString(profiles, "{\"profiles\":{},\"settings\":{},\"version\":3}",
                    StandardCharsets.UTF_8);

        prefetchInstallerLibraries(profile, versionJson, kind, log, status);
        // 预取阶段若被看门狗判超时中止，直接报错，不再白跑安装器
        if (monitor.cancelRequested())
            throw new IOException(monitor.error().isBlank() ? "下载已取消" : monitor.error());

        // 不要改写安装器内嵌的下载地址：Forge/NeoForge 安装器启动时会校验内嵌清单里的
        // 主机与镜像源，改成镜像地址后它直接判定「环境异常」秒退
        // （A problem installing was detected, install cannot continue），什么版本都生成不出来。
        // 镜像加速由上面的 prefetchInstallerLibraries 完成，安装器发现文件已存在且校验通过会跳过下载。

        // spec 0 老安装器（1.12.2 及以前，如 1.8.9）是 SimpleInstaller，
        // 只认 --installServer，传 --installClient 会直接
        // joptsimple.UnrecognizedOptionException 崩掉、什么也装不上。
        // 它没有 processor 环节：install_profile.json 的 versionInfo 就是最终版本定义，
        // universal jar 走库清单由 prepare() 下载——直接落盘即可，不运行安装器。
        JsonNode processors = profile == null ? null : profile.path("processors");
        boolean legacy = processors == null || !processors.isArray() || processors.isEmpty();
        if (legacy) {
            JsonNode vinfo = profile != null && profile.has("versionInfo")
                    ? profile.path("versionInfo") : versionJson;
            if (vinfo == null || !vinfo.isObject() || !vinfo.has("id"))
                throw new IOException("老版 " + kind + " 安装器缺少 versionInfo，无法安装 " + mcVer);
            String vid = vinfo.path("id").asText();
            Path vdir = gameDir.resolve("versions").resolve(vid);
            Files.createDirectories(vdir);
            Files.writeString(vdir.resolve(vid + ".json"),
                    MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(vinfo), StandardCharsets.UTF_8);
            try { Files.deleteIfExists(installer); } catch (IOException ignored) { }
            log.accept(kind + " " + loaderVer + " 安装完成（老安装器，直接写入版本定义）：" + vid);
            return vid;
        }

        String profileVer = profile == null ? "" : profile.path("version").asText("");
        String id = profileVer.isBlank() ? mcVer + "-" + kind + "-" + loaderVer : profileVer;
        // 安装器最后一步用 Files.copy 写版本 json，父目录必须先存在
        Files.createDirectories(gameDir.resolve("versions").resolve(id));
        String java = resolveJavaForInstaller(vanillaJavaMajor(mcVer), log);
        status.accept("正在运行 " + kind + " 安装器（生成补丁客户端，可能需要几分钟）…");
        monitor.setPhase(Phase.INSTALL, "运行 " + kindLabel(kind) + " 安装器（生成补丁客户端）");

        List<String> cmd = new ArrayList<>(List.of(java, "-jar", installer.toString(),
                "--installClient", gameDir.toString()));
        log.accept("执行安装器：" + String.join(" ", cmd.subList(2, cmd.size())));

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(gameDir.toFile());
        pb.redirectErrorStream(true);

        // 安装器运行时会读本地 versions/<mc>/<mc>.json 里原版客户端 / mappings 的下载地址自己拉文件，
        // 官方 piston-data.mojang.com 在部分网络下单线程能卡几十分钟（实测 30 分钟 0 字节）。
        // 安装期间临时把这些地址改成镜像，安装结束立即还原，不改变版本定义本身。
        String jsonBackup = patchVanillaMirrorUrls(mcVer, log);
        Deque<String> tail = new ArrayDeque<>();
        Process proc;
        int code;
        try {
            proc = pb.start();
            // 安装器自己会打印下载/生成进度，顺手喂给监控器，详情页能看到实时输出；
            // 同时留一份末尾输出，失败时拼进报错信息并落 yhcl.log，避免只看到「退出码 1」无从排查
            pump(proc.getInputStream(), line -> {
                log.accept(line);
                monitor.setNote(line);
                synchronized (tail) {
                    tail.addLast(line);
                    while (tail.size() > 40) tail.removeFirst();
                }
            });
            // 安装器自己会下载/生成补丁客户端：网络或处理器卡死时不能无限等，20 分钟兜底
            if (!proc.waitFor(20, TimeUnit.MINUTES)) {
                proc.destroyForcibly();
                throw new IOException(kind + " 安装器运行超时（20 分钟），已终止");
            }
            code = proc.exitValue();
        } finally {
            restoreVanillaJson(mcVer, jsonBackup);
        }
        // 安装器进程刚退出时 Windows 可能仍占着 jar，删除失败不该盖掉真正的报错
        try { Files.deleteIfExists(installer); } catch (IOException ignored) { }
        Path vjson = gameDir.resolve("versions").resolve(id).resolve(id + ".json");
        if (code != 0) {
            if (!Files.exists(vjson)) deleteQuietly(gameDir.resolve("versions").resolve(id));
            String why;
            synchronized (tail) { why = String.join(" | ", tail); }
            if (why.length() > 600) why = why.substring(why.length() - 600);
            throw new IOException(kind + " 安装器执行失败（退出码 " + code + "）：" + why);
        }
        if (!Files.exists(vjson)) {
            deleteQuietly(gameDir.resolve("versions").resolve(id));
            throw new IOException(kind + " 安装器没有生成版本文件：" + vjson);
        }
        log.accept(kind + " " + loaderVer + " 安装完成：" + id);
        return id;
    }

    /**
     * 安装器运行期间，把本地原版版本 json 里的下载地址临时改成镜像。
     * Forge/NeoForge 安装器会用 {@code versions/<mc>/<mc>.json} 里的 url 拉原版客户端 jar
     * 和 mappings，官方 piston-data 在部分网络下是黑洞（单线程实测 30 分钟 0 字节），
     * 而镜像同一文件 3MB/s。返回原文用于安装结束后还原；无需改动时返回 null。
     */
    private String patchVanillaMirrorUrls(String mcVer, Consumer<String> log) {
        if ("official_only".equals(fileSourceMode)) return null;
        Path json = gameDir.resolve("versions").resolve(mcVer).resolve(mcVer + ".json");
        if (!Files.isRegularFile(json)) return null;
        try {
            String original = Files.readString(json, StandardCharsets.UTF_8);
            JsonNode root = MAPPER.readTree(original);
            if (!(root instanceof ObjectNode on)) return null;
            int[] hits = { 0 };
            rewriteUrls(on, hits);
            if (hits[0] == 0) return null;
            Files.writeString(json, MAPPER.writeValueAsString(on), StandardCharsets.UTF_8);
            log.accept("安装器下载地址已切换到镜像（" + hits[0] + " 处），避免官方源单线程卡死");
            return original;
        } catch (Exception ex) {
            return null;
        }
    }

    /** 递归把 json 里所有 http(s) 字符串按镜像规则改写 */
    private void rewriteUrls(JsonNode node, int[] hits) {
        if (node instanceof ObjectNode obj) {
            List<String> keys = new ArrayList<>();
            obj.fieldNames().forEachRemaining(keys::add);
            for (String k : keys) {
                JsonNode v = obj.get(k);
                if (v.isTextual()) {
                    String s = v.asText();
                    if (s.startsWith("http")) {
                        String m = mirror(s);
                        if (!m.equals(s)) { obj.put(k, m); hits[0]++; }
                    }
                } else if (v.isContainerNode()) {
                    rewriteUrls(v, hits);
                }
            }
        } else if (node.isArray()) {
            for (JsonNode v : node) if (v.isContainerNode()) rewriteUrls(v, hits);
        }
    }

    /** 安装结束还原原版版本 json（安装器运行期临时改过下载地址） */
    private void restoreVanillaJson(String mcVer, String original) {
        if (original == null) return;
        Path json = gameDir.resolve("versions").resolve(mcVer).resolve(mcVer + ".json");
        try {
            Files.writeString(json, original, StandardCharsets.UTF_8);
        } catch (IOException ignored) { }
    }

    /** 读取安装器 jar 内的 install_profile.json（含库清单，以及安装器生成的版本 id） */
    private JsonNode readInstallProfile(Path installer) {
        return readInstallerEntry(installer, "install_profile.json");
    }

    /** 读取安装器 jar 内的指定 JSON 条目（如 install_profile.json / version.json） */
    private JsonNode readInstallerEntry(Path installer, String name) {
        try (ZipFile zf = new ZipFile(installer.toFile())) {
            ZipEntry e = zf.getEntry(name);
            if (e == null) return null;
            try (InputStream in = zf.getInputStream(e)) {
                return MAPPER.readTree(in);
            }
        } catch (Exception ex) {
            return null;
        }
    }

    /**
     * 预取安装器要用的库。
     * NeoForge 的库托管在 maven.neoforged.net，国内常连不通，而安装器内部把该地址硬编码了
     * （命令行 --mirror 只是仓库 JSON 模型，不做 URL 重写，实测无效）。
     * 好在安装器对「已存在且 SHA1 校验通过」的文件会直接跳过下载（"exists. Checksum valid."），
     * 所以先用镜像把 install_profile.json / version.json 两份清单里的库预取到 libraries/，
     * 安装器就不会再连不可达的源。
     * 预取失败不算致命：交给安装器自行重试，失败原因会体现在安装器日志里。
     */
    private void prefetchInstallerLibraries(JsonNode profile, JsonNode versionJson, String kind,
                                            Consumer<String> log, Consumer<String> status) {
        Path libRoot = gameDir.resolve("libraries");
        List<Job> jobs = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        // spec 1 老安装器的库分两份清单：install_profile.json 是安装器自用库，
        // version.json 是运行时库（loader、modlauncher 等），必须都预取；两份重复的按 path 去重
        for (JsonNode src : new JsonNode[] { profile, versionJson }) {
            if (src == null) continue;
            for (JsonNode lib : src.path("libraries")) {
                JsonNode art = lib.path("downloads").path("artifact");
                String url = art.path("url").asText("");
                String path = art.path("path").asText("");
                // url 为空的库由处理程序现场生成（如 forge-*-client.jar），无法预取
                if (url.isBlank() || path.isBlank() || !seen.add(path)) continue;
                jobs.add(new Job(List.of(url), libRoot.resolve(path.replace('/', File.separatorChar)),
                        art.path("sha1").asText(""), null));
            }
        }
        if (jobs.isEmpty()) return;

        int total = jobs.size(), done = 0, failed = 0;
        if (status != null) status.accept("预取 " + kind + " 依赖库（" + total + " 个）…");
        int base = monitor.addBatch(fileNames(jobs));
        monitor.setPhase(Phase.DOWNLOAD, "预取 " + kindLabel(kind) + " 依赖库");
        ExecutorService pool = Executors.newFixedThreadPool(dlThreads());
        AtomicBoolean finished = new AtomicBoolean();
        Thread watchdog = startWatchdog(finished, pool);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < jobs.size(); i++) {
                final Job j = jobs.get(i);
                final int idx = base + i;
                futures.add(pool.submit(() -> { runJob(j, idx); return null; }));
            }
            for (Future<?> f : futures) {
                try {
                    f.get();
                } catch (ExecutionException ex) {
                    if (monitor.cancelRequested()) return;
                    failed++;
                    log.accept("依赖库预取失败：" + ex.getCause().getMessage());
                } catch (CancellationException ex) {
                    return;
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return;
                }
                done++;
                if (status != null && (done % 5 == 0 || done == total))
                    status.accept("预取 " + kind + " 依赖库 " + done + "/" + total + "…");
            }
        } finally {
            finished.set(true);
            watchdog.interrupt();
            pool.shutdownNow();
        }
        log.accept("依赖库预取完成 " + (total - failed) + "/" + total + (failed > 0 ? "（失败部分交由安装器处理）" : ""));
    }

    /** 原版版本 json 要求的 Java 主版本，供安装器挑选合适的 Java */
    private int vanillaJavaMajor(String mcVer) {
        Path p = gameDir.resolve("versions").resolve(mcVer).resolve(mcVer + ".json");
        try {
            if (Files.exists(p))
                return MAPPER.readTree(p.toFile()).path("javaVersion").path("majorVersion").asInt(8);
        } catch (IOException ignored) { }
        return 21;
    }

    /* ============ 安装原版 ============ */
    public void install(VersionEntry entry, Consumer<String> log,
                        Consumer<Double> prog, Consumer<String> status) throws Exception {
        status.accept("下载版本定义…");
        log.accept("开始安装 " + entry.id + "（" + entry.type + "）");
        monitor.setPhase(Phase.PREPARE, "获取 " + entry.id + " 版本定义");
        Path dir = gameDir.resolve("versions").resolve(entry.id);
        Files.createDirectories(dir);
        try {
            downloadFile(entry.url, dir.resolve(entry.id + ".json"), null);
            prepare(entry.id, log, prog, status);
        } catch (Exception ex) {
            // 失败不留空壳目录（有目录无 json 的残缺版本在列表里看不见）
            if (!Files.exists(dir.resolve(entry.id + ".json"))) deleteQuietly(dir);
            throw ex;
        }
        status.accept("安装完成");
        log.accept(entry.id + " 安装完成。");
    }

    /* ============ 准备文件（校验 + 下载） ============ */
    public JsonNode prepare(String id, Consumer<String> log,
                            Consumer<Double> prog, Consumer<String> status) throws Exception {
        JsonNode root = loadRoot(id);
        Path nativesDir = gameDir.resolve("versions").resolve(id).resolve("natives");
        List<Job> jobs = new ArrayList<>();

        JsonNode client = root.path("downloads").path("client");
        if (client.has("url")) {
            // client jar 归属继承根版本：加载器版本（Forge/Fabric/NeoForge）靠 inheritsFrom
            // 复用原版 jar，不能下到加载器目录，否则装加载器时原版游戏会被重复下载一份。
            // 自身 json 显式声明 downloads.client（自带客户端）时才下到自身目录。
            String owner = inheritedRoot(id);
            Path selfJson = gameDir.resolve("versions").resolve(id).resolve(id + ".json");
            try {
                if (MAPPER.readTree(selfJson.toFile()).path("downloads").path("client").has("url")) owner = id;
            } catch (IOException ignored) { }
            jobs.add(new Job(List.of(client.path("url").asText()),
                    gameDir.resolve("versions").resolve(owner).resolve(owner + ".jar"),
                    sha1Of(client), null));
        }

        for (Lib l : resolveLibraries(root)) {
            jobs.add(new Job(l.urls(), l.dest(), l.sha1(), null));
        }

        // 1.12 及以前：natives 是带 classifier 的 jar（纯 natives 库没有主件），要下载分类器 jar 并解压。
        // 这里直接遍历原始清单：纯 natives 库不在 resolveLibraries 结果里
        for (JsonNode lib : root.path("libraries")) {
            if (!rulesAllow(lib)) continue;
            String classifier = lib.path("natives").path("windows").asText("");
            JsonNode nar = lib.path("downloads").path("classifiers").path(classifier);
            if (!classifier.isEmpty() && nar.has("path")) {
                Path dest = gameDir.resolve("libraries")
                        .resolve(nar.path("path").asText().replace('/', File.separatorChar));
                List<String> urls = List.of(nar.has("url") && !nar.path("url").asText().isBlank()
                        ? nar.path("url").asText()
                        : "https://libraries.minecraft.net/" + nar.path("path").asText());
                Path natDir = nativesDir;
                jobs.add(new Job(urls, dest, nar.path("sha1").asText(""),
                        () -> extractNatives(dest, natDir)));
            }
        }

        if (status != null) status.accept("正在收集下载任务…");
        jobs.addAll(collectAssets(root, status));

        // 已经完整存在（sha1/zip 校验通过）的文件不再列进下载清单：安装加载器时原版
        // 刚在上一步装好，加载器的 prepare 又把原版客户端/库/资源整套收进清单，会让
        // 监控器显示成「原版被重复下载一遍」。这里只保留缺漏或损坏的文件（完好的下载
        // 本就会被跳过，损坏的仍会重下修复），让清单只列真正要下的，装加载器就只下加载器。
        // 但带 after 回调的任务（natives 解压、虚拟资源映射）文件完整也要执行回调：
        // 回调产物目录可能整个缺失（如 PCL 全量游戏包只带全量 libraries 不带 natives），
        // 漏掉解压启动会报 UnsatisfiedLinkError: no lwjgl64 in java.library.path。
        jobs.removeIf(j -> {
            boolean ok = intact(j);
            if (ok && j.after != null) j.after.run();
            return ok;
        });

        int total = jobs.size();
        int done = 0;
        log.accept("共 " + total + " 个文件需要下载 / 校验"
                + "，" + sourceLabel(fileSourceMode)
                + "，最大 " + downloadThreads + " 线程");
        if (status != null) status.accept("开始下载 " + total + " 个文件…");

        // 把本阶段文件清单交给监控器（追加在已有阶段之后，拼出全过程视图）
        int base = monitor.addBatch(fileNames(jobs));
        monitor.setPhase(Phase.DOWNLOAD, "下载 " + id + " 的 " + total + " 个文件");

        IOException firstError = null;
        List<Job> failed = new ArrayList<>();
        List<Integer> failedIdx = new ArrayList<>();
        // 并发数由「最大线程数」设置控制（旧版固定 32；实测 BMCLAPI 资源源随并发数扩展）
        ExecutorService pool = Executors.newFixedThreadPool(dlThreads());
        AtomicBoolean finished = new AtomicBoolean();
        Thread watchdog = startWatchdog(finished, pool);
        boolean cancelled = false;
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < jobs.size(); i++) {
                final Job j = jobs.get(i);
                final int idx = base + i;
                futures.add(pool.submit(() -> { runJob(j, idx); return null; }));
            }

            for (int i = 0; i < futures.size(); i++) {
                if (monitor.cancelRequested()) { cancelled = true; pool.shutdownNow(); break; }
                try {
                    futures.get(i).get();
                } catch (ExecutionException ex) {
                    // 看门狗/用户中止时，工作线程抛的是「已取消」，不能算作文件失败
                    if (monitor.cancelRequested()) { cancelled = true; break; }
                    failed.add(jobs.get(i));
                    failedIdx.add(base + i);
                    if (firstError == null)
                        firstError = ex.getCause() instanceof IOException ioe
                                ? ioe : new IOException(String.valueOf(ex.getCause()));
                } catch (CancellationException ex) {
                    cancelled = true;
                    break;
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    cancelled = true;
                    break;
                }
                done++;
                if (prog != null) prog.accept(Math.min(100.0, done * 100.0 / Math.max(1, total)));
                if (status != null) {
                    // 这里必须用监控器的实时计数：本循环按提交顺序 get()，前面有个慢文件没跑完，
                    // done 就会卡住不动，而监控器按实际完成数走，两者会显示成完全不同的进度。
                    int f = monitor.total() > 0 ? monitor.doneCount() + monitor.failCount() : done;
                    int t = monitor.total() > 0 ? monitor.total() : total;
                    int pct = (int) Math.round(f * 100.0 / Math.max(1, t));
                    status.accept("下载中 " + f + "/" + t + "（" + pct + "%）");
                }
            }

            // 被中止（超时看门狗或用户点停止）：直接报错，不再继续重试
            if (cancelled) throw new IOException(cancelReason());

            // 几千个文件里难免有几个偶发失败：集中重试一轮，别让整个安装前功尽弃
            if (!failed.isEmpty()) {
                if (status != null) status.accept("重试 " + failed.size() + " 个失败的文件…");
                log.accept("第一轮有 " + failed.size() + " 个文件失败，重试中");
                List<Future<?>> retry = new ArrayList<>();
                for (int i = 0; i < failed.size(); i++) {
                    final Job j = failed.get(i);
                    final int idx = failedIdx.get(i);
                    retry.add(pool.submit(() -> { runJob(j, idx); return null; }));
                }
                int stillFailed = 0;
                for (Future<?> f : retry) {
                    try {
                        f.get();
                    } catch (ExecutionException ex) {
                        if (monitor.cancelRequested()) { cancelled = true; break; }
                        stillFailed++;
                        if (firstError == null)
                            firstError = ex.getCause() instanceof IOException ioe
                                    ? ioe : new IOException(String.valueOf(ex.getCause()));
                    } catch (CancellationException ex) {
                        cancelled = true;
                        break;
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        cancelled = true;
                        break;
                    }
                }
                if (cancelled) throw new IOException(cancelReason());
                if (stillFailed > 0)
                    throw new IOException(stillFailed + " 个文件重试后仍下载失败："
                            + (firstError == null ? "未知错误" : firstError.getMessage()));
            }
        } finally {
            finished.set(true);
            watchdog.interrupt();
            pool.shutdownNow();
        }
        // 下载完成后完整性校验：把清单里每个文件按 sha1 / zip 结构复核一遍，
        // 发现损坏或缺失的自动重新下载修复，不让坏文件混进游戏目录
        verifyDownloaded(jobs, base, log, status);
        if (prog != null) prog.accept(100.0);
        return root;
    }

    /**
     * 下载完成后的完整性校验：逐个复核清单文件（有 sha1 按 sha1；jar 无 sha1 按 zip 结构；
     * 其余按存在且非空），发现损坏/缺失的自动重新下载修复；修复后仍不完整的直接报错。
     */
    private void verifyDownloaded(List<Job> jobs, int base, Consumer<String> log,
                                  Consumer<String> status) throws Exception {
        if (jobs.isEmpty()) return;
        int total = jobs.size();
        monitor.setPhase(Phase.VERIFY, "校验 " + total + " 个文件的完整性…");
        if (status != null) status.accept("下载完成，正在校验 " + total + " 个文件…");
        log.accept("下载完成，开始完整性校验（" + total + " 个文件）");

        // 第一轮：并发复核全部文件（读盘算 sha1，几十 MB 资源几秒内跑完）
        List<Job> broken = new ArrayList<>();
        List<Integer> brokenIdx = new ArrayList<>();
        ExecutorService vpool = Executors.newFixedThreadPool(dlThreads());
        try {
            List<Future<Boolean>> futures = new ArrayList<>(total);
            for (Job j : jobs) futures.add(vpool.submit(() -> intact(j)));
            for (int i = 0; i < futures.size(); i++) {
                boolean ok;
                try {
                    ok = futures.get(i).get();
                } catch (ExecutionException ex) {
                    ok = false;
                }
                if (!ok) {
                    broken.add(jobs.get(i));
                    brokenIdx.add(base + i);
                }
                monitor.touch();   // 校验也喂看门狗，别被判成网络挂死
            }
        } finally {
            vpool.shutdownNow();
        }
        if (monitor.cancelRequested()) throw new IOException(cancelReason());

        if (broken.isEmpty()) {
            log.accept("完整性校验通过：" + total + " 个文件全部完整");
            if (status != null) status.accept("完整性校验通过");
            return;
        }

        log.accept("完整性校验发现 " + broken.size() + " 个文件不完整，正在重新下载修复…");
        if (status != null) status.accept("校验发现 " + broken.size() + " 个不完整文件，正在修复…");
        ExecutorService rpool = Executors.newFixedThreadPool(dlThreads());
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < broken.size(); i++) {
                final Job j = broken.get(i);
                final int idx = brokenIdx.get(i);
                futures.add(rpool.submit(() -> { runJob(j, idx); return null; }));
            }
            for (Future<?> f : futures) {
                try {
                    f.get();
                } catch (ExecutionException ignored) {
                    // 单个修复失败不打断整批，最终以复核结果为准
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IOException("完整性校验修复被中断");
                }
            }
        } finally {
            rpool.shutdownNow();
        }
        if (monitor.cancelRequested()) throw new IOException(cancelReason());

        List<String> stillBad = new ArrayList<>();
        for (Job j : broken) {
            if (!intact(j)) stillBad.add(j.dest().getFileName().toString());
        }
        if (!stillBad.isEmpty()) {
            throw new IOException("完整性校验失败：" + stillBad.size() + " 个文件修复后仍不完整（"
                    + String.join("、", stillBad.subList(0, Math.min(5, stillBad.size())))
                    + (stillBad.size() > 5 ? " 等" : "") + "），请检查磁盘空间或网络后重试");
        }
        log.accept("完整性校验通过：已修复 " + broken.size() + " 个不完整文件");
        if (status != null) status.accept("完整性校验通过");
    }

    /** 单个文件完整性判定：有 sha1 按 sha1；jar 无 sha1 按 zip 结构；其余按存在且非空 */
    private static boolean intact(Job j) {
        Path dest = j.dest();
        try {
            if (!Files.isRegularFile(dest)) return false;
            String sha1 = j.sha1();
            if (sha1 != null && !sha1.isEmpty()) return sha1Of(dest).equalsIgnoreCase(sha1);
            if (dest.getFileName().toString().endsWith(".jar")) return validZip(dest);
            return Files.size(dest) > 0;
        } catch (IOException e) {
            return false;
        }
    }

    /** 监控清单用的文件名列表（便于详情页逐条展示） */
    private static List<String> fileNames(List<Job> jobs) {
        List<String> out = new ArrayList<>(jobs.size());
        for (Job j : jobs) out.add(j.dest().getFileName().toString());
        return out;
    }

    /** 中止时的报错文案：看门狗写的超时原因优先，否则是用户主动取消 */
    private String cancelReason() {
        String e = monitor.error();
        return e.isBlank() ? "下载已停止" : e;
    }

    /**
     * 下载看门狗：连续 STALL_TIMEOUT_SEC 秒没有任何文件完成，说明网络已经挂死
     * （请求既不返回也不超时）。此时中止整个下载并记录报错原因，由调用方抛出，
     * 实现「下载超时自动停止并报错」，而不是让界面无限期卡在 99%。
     */
    private Thread startWatchdog(AtomicBoolean finished, ExecutorService pool) {
        Thread t = new Thread(() -> {
            while (!finished.get()) {
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    return;
                }
                if (finished.get()) return;
                if (monitor.paused()) continue;   // 用户暂停时不计挂死
                if (monitor.idleMs() >= DownloadMonitor.STALL_TIMEOUT_SEC * 1000L) {
                    monitor.abort("下载超时：连续 " + DownloadMonitor.STALL_TIMEOUT_SEC
                            + " 秒没有任何文件完成，已自动停止下载。请检查网络后重试。");
                    pool.shutdownNow();
                    return;
                }
            }
        }, "yhlc-download-watchdog");
        t.setDaemon(true);
        t.start();
        return t;
    }

    private void runJob(Job j, int idx) {
        if (monitor.cancelRequested()) {
            monitor.markWaiting(idx);
            throw new CompletionException(new IOException("下载已取消"));
        }
        try {
            monitor.gate();   // 暂停闸门：暂停期间线程停在这里
        } catch (IOException e) {
            monitor.markWaiting(idx);
            throw new CompletionException(e);
        }
        monitor.markDownloading(idx);
        try {
            downloadFirstOf(j.urls(), j.dest(), j.sha1(), j.timeoutSec());
            if (j.after != null) j.after.run();
            monitor.markDone(idx, j.dest());
        } catch (Exception e) {
            // 取消导致的中断不该记成文件失败，退回「等待」状态
            if (monitor.cancelRequested()) monitor.markWaiting(idx);
            else monitor.markFailed(idx, e.getMessage());
            throw new CompletionException(e);
        }
    }

    /** 按顺序尝试多个地址，全部失败才报错 */
    private void downloadFirstOf(List<String> urls, Path dest, String sha1, long timeoutSec)
            throws IOException, InterruptedException {
        IOException last = null;
        for (String u : urls) {
            try {
                downloadFile(u, dest, sha1, timeoutSec);
                return;
            } catch (IOException ex) {
                if (monitor.cancelRequested()) throw ex;   // 取消/暂停中断不再换源重试
                last = ex;
            }
        }
        throw last != null ? last : new IOException("没有可用下载地址");
    }

    /* ============ 启动 ============ */

    /** 单版本启动覆盖项（null / 空白 = 跟随全局设置） */
    public static final class LaunchOpts {
        public Integer memoryMB;   // 覆盖 -Xmx/-Xms
        public String javaPath;    // 指定 Java 可执行文件或其所在目录
        public String jvmArgs;     // 追加 JVM 参数（在全局参数之后）
        public String envVars;     // 附加环境变量，格式：KEY=值 KEY2=值2
        public String serverIp;    // 启动后自动进入的服务器 host[:port]
        public boolean isolation;  // 版本隔离：以 versions/<id> 作为该次启动的游戏目录
        public String gameArgs;    // 追加游戏参数（拼接在启动参数末尾）
        public String windowSize;  // 窗口大小："default" 或 "宽x高"（如 1920x1080）
        public String skinMode = "";   // 离线皮肤：random | steve | alex | official | custom（空=不处理）
        public String skinFile = "";   // 自定义皮肤 PNG 路径（custom / random 模式用）
        public boolean disableLaunchWrapper; // 禁用 Java Launch Wrapper：跳过 LaunchWrapper 直接启动
        public boolean disableLwjglAgent;    // 禁用 LWJGL Unsafe Agent：不注入 Unsafe 兼容 Agent
    }

    public Process launch(String id, GameProfile profile,
                          Consumer<String> log, Consumer<Double> prog) throws Exception {
        return launch(id, profile, log, prog, new LaunchOpts());
    }

    public Process launch(String id, GameProfile profile,
                          Consumer<String> log, Consumer<Double> prog, LaunchOpts o) throws Exception {
        log.accept("准备启动 " + id + " …");
        JsonNode root = prepare(id, log, prog, null);

        Path nativesDir = gameDir.resolve("versions").resolve(id).resolve("natives");
        String cp = String.join(";", buildClasspath(root));
        // 离线皮肤：把皮肤贴图 jar 前置到 classpath，覆盖默认 steve/alex 贴图
        Path skinJar = prepareSkinJar(getMcVersion(id), profile, o, log);
        if (skinJar != null) cp = skinJar + ";" + cp;
        String assetIndex = root.path("assetIndex").path("id").asText("legacy");

        Map<String, String> vars = new HashMap<>();
        vars.put("auth_player_name", profile.name);
        vars.put("version_name", id);
        Path procDir = o != null && o.isolation ? gameDir.resolve("versions").resolve(id) : gameDir;
        try { Files.createDirectories(procDir); } catch (IOException ignored) { }
        vars.put("game_directory", procDir.toString());
        vars.put("assets_root", gameDir.resolve("assets").toString());
        vars.put("assets_index_name", assetIndex);
        vars.put("auth_uuid", profile.uuid.isBlank() ? offlineUuid(profile.name) : profile.uuid);
        vars.put("auth_access_token",
                profile.accessToken.isBlank() ? UUID.randomUUID().toString().replace("-", "") : profile.accessToken);
        vars.put("clientid", "0");
        vars.put("auth_xuid", "0");
        vars.put("user_type", profile.isMicrosoft ? "msa" : profile.isYggdrasil ? "mojang" : "legacy");
        vars.put("version_type", root.path("type").asText("release"));
        vars.put("natives_directory", nativesDir.toString());
        vars.put("launcher_name", "YHCL");
        vars.put("launcher_version", "0.1.2beta");
        vars.put("classpath", cp);
        vars.put("classpath_separator", ";");
        vars.put("user_properties", "{}");
        // 窗口大小：默认 1280x720，可用设置指定（同时作为 --width/--height 传给游戏）
        String winSize = o != null && o.windowSize != null ? o.windowSize.trim() : "";
        String ww = "1280", wh = "720";
        if (!winSize.isBlank() && !"default".equalsIgnoreCase(winSize)) {
            int xi = winSize.toLowerCase().indexOf('x');
            if (xi > 0 && xi < winSize.length() - 1) {
                ww = winSize.substring(0, xi).trim();
                wh = winSize.substring(xi + 1).trim();
            }
        }
        vars.put("resolution_width", ww);
        vars.put("resolution_height", wh);
        vars.put("library_directory", gameDir.resolve("libraries").toString());

        int mem = o != null && o.memoryMB != null && o.memoryMB > 0 ? o.memoryMB : memoryMB;
        List<String> jvm = new ArrayList<>();
        jvm.add("-Xmx" + mem + "M");
        jvm.add("-Xms" + Math.max(512, mem / 2) + "M");
        for (String a : jvmArgs.split("\\s+")) if (!a.isBlank()) jvm.add(a);
        if (o != null && o.jvmArgs != null && !o.jvmArgs.isBlank())
            for (String a : o.jvmArgs.split("\\s+")) if (!a.isBlank()) jvm.add(a);

        // LWJGL Unsafe Agent：放开新版 JDK 对 sun.misc.Unsafe 内存访问的限制（默认注入，可在设置中禁用）
        if (o == null || !o.disableLwjglAgent) {
            try {
                Path agentJar = buildLwjglUnsafeAgent();
                if (agentJar != null) {
                    jvm.add("-javaagent:" + agentJar);
                    // 该属性必须由启动命令行提供：JDK 在 sun.misc.Unsafe 类初始化时经
                    // VM.getSavedProperty 读取，premain 里再设置已太晚
                    jvm.add("-Dsun.misc.unsafe.memory.access=allow");
                    log.accept("已注入 LWJGL Unsafe Agent（放开 sun.misc.Unsafe 内存访问）：" + agentJar);
                }
            } catch (Exception ex) {
                log.accept("LWJGL Unsafe Agent 注入失败（不影响启动）：" + ex.getMessage());
            }
        }

        // Authlib Injector：第三方登录（Yggdrasil）时替换游戏内验证/皮肤端点
        if (profile.isYggdrasil && profile.authApi != null && !profile.authApi.isBlank()) {
            Path injector = ensureAuthlibInjector(log);
            jvm.add("-javaagent:" + injector + "=" + profile.authApi.trim());
            log.accept("已注入 Authlib Injector（第三方登录）：" + profile.authApi.trim());
        }

        List<String> game;
        JsonNode args = root.path("arguments");
        if (args.isObject()) {
            jvm.addAll(expand(args.path("jvm"), vars));
            game = expand(args.path("game"), vars);
        } else {
            game = new ArrayList<>();
            for (String a : root.path("minecraftArguments").asText("").split("\\s+"))
                if (!a.isBlank()) game.add(sub(a, vars));
        }

        if (o != null && o.serverIp != null && !o.serverIp.isBlank()) {
            String sip = o.serverIp.trim();
            String host, port;
            if (sip.startsWith("[")) {                     // IPv6：[::1]:25565
                int cb = sip.indexOf(']');
                host = sip.substring(1, Math.max(1, cb));
                port = cb >= 0 && cb + 1 < sip.length() && sip.charAt(cb + 1) == ':'
                        ? sip.substring(cb + 2) : "25565";
            } else {
                int ci = sip.lastIndexOf(':');
                if (ci > 0 && sip.indexOf(':') == ci) { host = sip.substring(0, ci); port = sip.substring(ci + 1); }
                else { host = sip; port = "25565"; }
            }
            game.add("--server");
            game.add(host);
            game.add("--port");
            game.add(port);
            log.accept("启动后自动进入服务器：" + host + ":" + port);
        }

        // 窗口大小（自定义时传 --width/--height 给游戏主类）
        if (!"1280".equals(ww) || !"720".equals(wh)) {
            game.add("--width");
            game.add(ww);
            game.add("--height");
            game.add(wh);
            log.accept("窗口大小：" + ww + "x" + wh);
        }
        // 追加游戏参数
        if (o != null && o.gameArgs != null && !o.gameArgs.isBlank())
            for (String a : o.gameArgs.split("\\s+")) if (!a.isBlank()) game.add(a);

        if (jvm.stream().noneMatch(a -> a.startsWith("-Djava.library.path")))
            jvm.add("-Djava.library.path=" + nativesDir);
        if (!jvm.contains("-cp")) { jvm.add("-cp"); jvm.add(cp); }

        String mainClass = root.path("mainClass").asText();
        // LaunchWrapper 时代（MC ≤ 1.12）：Launch 构造器把 AppClassLoader 强转 URLClassLoader，
        // Java 9+ 必然 ClassCastException，只能跑 Java 8
        boolean launchWrapperEra = "net.minecraft.launchwrapper.Launch".equals(mainClass);

        // 禁用 Java Launch Wrapper：跳过 LaunchWrapper，直接以客户端主类启动（Mod 不加载，仅用于排障）
        if (o != null && o.disableLaunchWrapper && launchWrapperEra) {
            mainClass = "net.minecraft.client.main.Main";
            for (Iterator<String> it = game.iterator(); it.hasNext(); ) {
                if ("--tweakClass".equals(it.next())) {
                    it.remove();
                    if (it.hasNext()) { it.next(); it.remove(); }
                }
            }
            log.accept("已禁用 Java Launch Wrapper：剔除 --tweakClass，以 " + mainClass + " 启动（Mod 不会加载）");
        }

        int needJava = root.path("javaVersion").path("majorVersion").asInt(0);
        int maxJava = launchWrapperEra ? 8 : Integer.MAX_VALUE;
        String java;
        if (o != null && o.javaPath != null && !o.javaPath.isBlank()) {
            Path jp = Path.of(o.javaPath);
            if (!Files.exists(jp)) throw new IOException("找不到版本设置指定的 Java 路径：" + o.javaPath);
            java = jp.toString().toLowerCase().endsWith(".exe") ? jp.toString()
                    : Files.exists(jp.resolve("javaw.exe")) ? jp.resolve("javaw.exe").toString()
                    : jp.resolve("java.exe").toString();
            if (!Files.exists(Path.of(java))) throw new IOException("该路径不是有效的 Java：" + o.javaPath);
            JavaLocator.JavaInfo mi = JavaLocator.info(Path.of(java));
            if (mi != null && ((needJava > 0 && mi.major < needJava) || mi.major > maxJava))
                throw new IOException(launchWrapperEra
                        ? "本版本只能用 Java 8 运行（LaunchWrapper 在 Java 9+ 上启动即崩），版本设置指定的是 Java " + mi.major + "。请改选 Java 8。"
                        : "本版本需要 Java " + needJava + "，版本设置指定的是 Java " + mi.major + "，不满足要求。");
            log.accept("该版本要求 Java " + (needJava > 0 ? needJava : 8) + "，使用版本设置指定的 Java：" + java);
        } else {
            log.accept("该版本要求 Java " + (needJava > 0 ? needJava : 8)
                    + (maxJava == 8 ? "（LaunchWrapper 版本，仅支持 Java 8）" : ""));
            java = resolveJava(needJava, maxJava, log);
        }

        applySystemLang(procDir, log);   // 游戏默认语言跟随系统语言

        List<String> all = new ArrayList<>();
        all.add(java);
        all.addAll(jvm);
        all.add(mainClass);
        all.addAll(game);

        log.accept("主类 " + mainClass + " · 共 " + all.size() + " 个启动参数");
        log.accept("Java：" + all.getFirst() + " · 内存 " + mem + " MB"
                + (o != null && o.isolation ? " · 版本隔离：开启" : ""));

        ProcessBuilder pb = new ProcessBuilder(all)
                .directory(procDir.toFile())
                .redirectErrorStream(false);
        if (o != null && o.envVars != null && !o.envVars.isBlank()) {
            for (String kv : o.envVars.split("\\s+")) {
                int eq = kv.indexOf('=');
                if (eq > 1) pb.environment().put(kv.substring(0, eq), kv.substring(eq + 1));
            }
        }
        Process p = pb.start();

        pump(p.getInputStream(), line -> log.accept("[游戏] " + line));
        pump(p.getErrorStream(), line -> log.accept("[游戏][错误] " + line));
        log.accept("游戏已启动（PID " + p.pid() + "）");
        return p;
    }

    /* ============ 依赖库解析（支持 maven 坐标：Fabric/Forge/NeoForge） ============ */
    private List<Lib> resolveLibraries(JsonNode root) {
        List<Lib> out = new ArrayList<>();
        Path libRoot = gameDir.resolve("libraries");
        for (JsonNode lib : root.path("libraries")) {
            if (!rulesAllow(lib)) continue;

            JsonNode art = lib.path("downloads").path("artifact");
            if (art.has("path")) {
                Path dest = libRoot.resolve(art.path("path").asText().replace('/', File.separatorChar));
                String path = art.path("path").asText();
                String url = art.has("url") && !art.path("url").asText().isBlank()
                        ? art.path("url").asText()
                        : "https://libraries.minecraft.net/" + path;
                out.add(new Lib(dest, List.of(url), art.path("sha1").asText(""), lib));

            } else if (lib.has("name")) {
                // 纯 natives 库（只有 classifiers 没有主件，如 jinput-platform）：主 jar 在任何源都不存在，
                // 拼出来的地址必 404 还会拖垮整个安装；真正要下的是 natives 分类器 jar，交给 prepare 的分类器分支
                if (lib.path("natives").size() > 0
                        && lib.path("downloads").path("classifiers").size() > 0) continue;
                // maven 坐标：group:artifact:version[:classifier]
                String name = lib.path("name").asText();
                try {
                    String path = mavenPath(name);
                    Path dest = libRoot.resolve(path.replace('/', File.separatorChar));
                    List<String> urls = new ArrayList<>();
                    String explicit = lib.path("url").asText("");
                    if (!explicit.isBlank()) {
                        String base = explicit.endsWith("/") ? explicit : explicit + "/";
                        urls.add(base + path);
                    }
                    urls.add("https://libraries.minecraft.net/" + path);
                    urls.add("https://maven.fabricmc.net/" + path);
                    urls.add("https://maven.minecraftforge.net/" + path);
                    urls.add("https://maven.neoforged.net/releases/" + path);
                    urls.add("https://repo1.maven.org/maven2/" + path);
                    out.add(new Lib(dest, urls, "", lib));
                } catch (Exception ignored) { }
            }
        }
        return out;
    }

    private static String mavenPath(String name) {
        String[] p = name.split(":");
        if (p.length < 3) throw new IllegalArgumentException("非法 maven 坐标: " + name);
        String group = p[0].replace('.', '/');
        String art = p[1], ver = p[2];
        String classifier = p.length > 3 ? p[3] : "";
        String file = art + "-" + ver + (classifier.isEmpty() ? "" : "-" + classifier) + ".jar";
        return group + "/" + art + "/" + ver + "/" + file;
    }

    /** 系统语言 → Minecraft 语言代码（如 zh_CN→zh_cn、zh_TW→zh_tw、pt_BR→pt_br）；其余按小写 language_country 组合，MC 找不到时自会回退 en_us */
    private static String systemLangCode() {
        Locale loc = Locale.getDefault();
        String lang = loc.getLanguage();
        if (lang == null || lang.isBlank()) return null;
        String country = loc.getCountry() == null ? "" : loc.getCountry().toLowerCase(Locale.ROOT);
        switch (lang) {
            case "zh":
                return ("tw".equals(country) || "hk".equals(country) || "mo".equals(country))
                        ? "zh_tw" : "zh_cn";
            case "pt":
                return "br".equals(country) ? "pt_br" : "pt_pt";
            default:
                return country.isEmpty()
                        ? lang.toLowerCase(Locale.ROOT) + "_" + lang.toLowerCase(Locale.ROOT)
                        : lang.toLowerCase(Locale.ROOT) + "_" + country;
        }
    }

    /**
     * 默认游戏语言跟随系统语言：
     * 某游戏目录首次由 YHCL 启动（无 .yhcl-lang 标记）时，若游戏还没设置语言
     * （options.txt 无 lang 或仍是游戏首次运行自写的 en_us 默认值），写入系统语言；
     * 之后只补空缺，不覆盖玩家在游戏内选的语言。
     */
    private static void applySystemLang(Path procDir, Consumer<String> log) {
        try {
            String sys = systemLangCode();
            if (sys == null) return;
            Path opt = procDir.resolve("options.txt");
            Path marker = procDir.resolve(".yhcl-lang");
            boolean seeded = Files.exists(marker);
            List<String> lines = Files.exists(opt) ? Files.readAllLines(opt) : new ArrayList<>();
            String cur = null;
            for (String l : lines) {
                if (l.startsWith("lang:")) {
                    cur = l.substring(5).trim();
                    break;
                }
            }
            // 空缺必补；en_us 只在首次（未种子）时折算——那是 MC 自写的默认值，不是玩家的选择
            boolean fill = cur == null || cur.isEmpty()
                    || (!seeded && "en_us".equalsIgnoreCase(cur));
            if (fill && !sys.equalsIgnoreCase(cur)) {
                lines.removeIf(l -> l.startsWith("lang:"));
                lines.add("lang:" + sys);
                Files.createDirectories(procDir);
                Files.write(opt, lines);
                if (log != null) log.accept("游戏语言默认使用系统语言：" + sys);
            }
            if (!seeded) Files.write(marker, List.of(sys));
        } catch (Exception ex) {
            // 默认语言写入失败不影响启动
        }
    }

    /* ============ 资源文件 ============ */
    private List<Job> collectAssets(JsonNode root, Consumer<String> status) throws Exception {
        List<Job> jobs = new ArrayList<>();
        JsonNode ai = root.path("assetIndex");
        if (!ai.has("url")) return jobs;

        String indexId = ai.path("id").asText();
        Path idxPath = gameDir.resolve("assets").resolve("indexes").resolve(indexId + ".json");
        if (status != null) status.accept("下载资源索引 " + indexId + "…");
        downloadFile(ai.path("url").asText(), idxPath, sha1Of(ai));
        if (status != null) status.accept("资源索引已下载，正在解析…");

        JsonNode idx = MAPPER.readTree(idxPath.toFile());
        boolean virtual = idx.path("virtual").asBoolean(false);
        Path objRoot = gameDir.resolve("assets").resolve("objects");

        int count = 0;
        Iterator<Map.Entry<String, JsonNode>> it = idx.path("objects").fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            String name = e.getKey();
            String hash = e.getValue().path("hash").asText();
            Path dest = objRoot.resolve(hash.substring(0, 2)).resolve(hash);
            String url = "https://resources.download.minecraft.net/" + hash.substring(0, 2) + "/" + hash;
            // 资源文件都很小（几百 KB）：单次尝试超时放宽到 30 秒即可，
            // 镜像节点卡住时快速换下一个源，别把并发线程挂住几分钟
            jobs.add(new Job(List.of(url), dest, hash, virtual ? () -> {
                try {
                    Path dst = gameDir.resolve("assets").resolve("virtual").resolve("legacy")
                            .resolve(name.replace('/', File.separatorChar));
                    Files.createDirectories(dst.getParent());
                    if (!Files.exists(dst)) Files.copy(dest, dst);
                } catch (IOException ignored) { }
            } : null, 30));
            count++;
        }
        if (status != null) status.accept("资源索引解析完成，共 " + count + " 个资源文件待下载");
        return jobs;
    }

    /* ============ 下载 + SHA1 校验 + 双源候选轮换 ============ */

    /* ---- 源熔断：连接级失败的主机一段时间内不再尝试 ---- */

    /** 已判定连不通的主机 → 解禁时间戳。maven.neoforged.net 这类连接黑洞每次空耗一个
     *  连接超时（20 秒），预取几十个库时会被反复拖死；熔断后候选自动绕开死主机，
     *  冷却结束再放回来探测。 */
    private static final ConcurrentHashMap<String, Long> DEAD_HOSTS = new ConcurrentHashMap<>();
    /** 熔断冷却时长 */
    private static final long DEAD_COOLDOWN_MS = 10 * 60 * 1000L;

    private static String hostOf(String url) {
        try {
            String h = URI.create(url).getHost();
            return h == null ? "" : h;
        } catch (Exception e) {
            return "";
        }
    }

    /** 该 URL 的主机是否处于熔断期 */
    private static boolean hostDead(String url) {
        String h = hostOf(url);
        if (h.isEmpty()) return false;
        Long until = DEAD_HOSTS.get(h);
        return until != null && until > System.currentTimeMillis();
    }

    /** 连接级失败（连不上/超时）才算主机死亡：HTTP 错误码、哈希错等不算 */
    private static boolean isConnectFail(Exception ex) {
        return ex instanceof java.net.http.HttpTimeoutException
                || ex instanceof java.net.ConnectException;
    }

    /** 记录主机死亡（仅连接级失败），该主机的后续候选自动绕开 */
    private static void noteHostDead(String url, Exception ex) {
        if (!isConnectFail(ex)) return;
        String h = hostOf(url);
        if (!h.isEmpty()) DEAD_HOSTS.put(h, System.currentTimeMillis() + DEAD_COOLDOWN_MS);
    }

    /** 按重试序号轮换取候选；轮到熔断中的主机就换成没死的候选 */
    private static String pickCandidate(List<String> candidates, int attempt) {
        String real = candidates.get((attempt - 1) % candidates.size());
        if (candidates.size() > 1 && hostDead(real)) {
            for (String c : candidates) if (!hostDead(c)) return c;
        }
        return real;
    }

    /* ---- 源测速择优：按 host 缓存实测下行速度，双源候选择快优先 ---- */

    /** 测速结果缓存：host -> {速度 B/s（-1 失败），测量时间戳} */
    private static final ConcurrentHashMap<String, long[]> SPEED_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Object> SPEED_LOCKS = new ConcurrentHashMap<>();
    private static final long SPEED_TTL_MS = 5 * 60 * 1000;

    /** 该 URL 所在主机的实测下行速度（B/s），-1 = 测速失败；每 host 每 5 分钟只测一次 */
    private long speedOf(String url) {
        String host = hostOf(url);
        long[] c = SPEED_CACHE.get(host);
        long now = System.currentTimeMillis();
        if (c != null && now - c[1] < SPEED_TTL_MS) return c[0];
        synchronized (SPEED_LOCKS.computeIfAbsent(host, k -> new Object())) {
            c = SPEED_CACHE.get(host);
            if (c != null && System.currentTimeMillis() - c[1] < SPEED_TTL_MS) return c[0];
            long bps = measureSpeed(url);
            SPEED_CACHE.put(host, new long[] { bps, System.currentTimeMillis() });
            return bps;
        }
    }

    /** 拉目标文件的前 256KB 测下行速度（读够即停，服务端不支持 Range 也不会多下） */
    private long measureSpeed(String url) {
        long got = 0;
        long t0 = System.nanoTime();
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(8))
                    .header("Range", "bytes=0-262143").GET().build();
            HttpResponse<InputStream> resp =
                    HTTP.send(req, HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() / 100 != 2) return -1;
            byte[] buf = new byte[64 * 1024];
            try (InputStream in = resp.body()) {
                int n;
                while (got < 262143 && (n = in.read(buf)) > 0) got += n;
            }
        } catch (Exception e) {
            return -1;
        }
        long elapsed = System.nanoTime() - t0;
        return got <= 0 || elapsed <= 0 ? -1 : got * 1_000_000_000L / elapsed;
    }

    /**
     * 生成下载候选列表：按源选择模式排列官方/镜像源。
     * BMCLAPI 偶尔会把请求 302 到不稳定节点导致整条链路超时，
     * 「优先」模式下两个源都要兜底，「仅」模式只用单源。
     * 双源候选再按实测速度择快优先：BMCLAPI 对单 IP 有总带宽限速（实测开到
     * 16 并发也只有 ~0.8MB/s），官方源反而能跑 6MB/s+，固定偏好会把全部流量
     * 压到慢源上；测速数据 5 分钟内复用，差距不足 25% 时保留用户偏好。
     */
    private List<String> candidates(String url, String mode) {
        String m = mirror(url);
        List<String> out = new ArrayList<>();
        switch (mode) {
            case "mirror_only" -> out.add(m);
            case "official_only" -> out.add(url);
            case "mirror_first" -> {
                out.add(m);
                if (!m.equals(url)) out.add(url);
            }
            default -> {
                out.add(url);
                if (!m.equals(url)) out.add(m);
            }
        }
        if (out.size() == 2) {
            long s0 = speedOf(out.get(0)), s1 = speedOf(out.get(1));
            // 快 25% 以上才翻转偏好；测速失败（-1）的一方自动让位给可用源
            if (s1 > 0 && (s0 <= 0 || s1 > s0 * 5 / 4)) Collections.reverse(out);
        }
        return out;
    }

    /** 文件下载默认按「文件下载源」设置排候选 */
    private List<String> candidates(String url) {
        return candidates(url, fileSourceMode);
    }

    /**
     * 只对值得分片的大文件启用（整包、安装器、mod、版本 jar），
     * 避免给几千个小资源文件发 Range 探测请求。
     */
    private static boolean isChunkCandidate(Path dest) {
        String name = dest.getFileName().toString().toLowerCase(Locale.ROOT);
        String path = dest.toString().replace('/', '\\').toLowerCase(Locale.ROOT);
        return name.endsWith(".mrpack") || name.contains("installer")
                || path.contains("\\mods\\")
                // 大依赖库（如 neoforge-<ver>-universal.jar 几十 MB）也分片：
                // 单连接常被 CDN 节点限速到几 KB/s，整包下载必死于总超时；分片并发能聚合并发带宽
                || (path.contains("\\libraries\\") && name.endsWith(".jar"))
                || (path.contains("\\versions\\") && name.endsWith(".jar"));
    }

    /**
     * 大文件分片并发下载：先用 Range 探测总大小，把文件切成 4MB 的片并发下载到 .partN，
     * 再合并写入 target。任何一步不支持/失败都返回 false，由调用方退回普通单流下载。
     */
    private boolean downloadChunked(String url, Path target, long timeoutSec) {
        List<Path> parts = new ArrayList<>();
        try {
            long total;
            HttpRequest probe = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .header("Range", "bytes=0-0").GET().build();
            HttpResponse<Void> pr = HTTP.send(probe, HttpResponse.BodyHandlers.discarding());
            if (pr.statusCode() != 206) return false;
            String cr = pr.headers().firstValue("Content-Range").orElse("");
            int slash = cr.lastIndexOf('/');
            if (slash < 0) return false;
            total = Long.parseLong(cr.substring(slash + 1).trim());
            if (total < CHUNK_MIN) return false;
            monitor.noteItemSize(target, total);   // 分片探测即知总大小，尽早纳入 ETA 总量

            // 2) 切片：按文件大小自适应粒度。并行连接数 = 分片数，固定 4MB 一片会让几十 MB 的
            // 文件只有十几条连接；CDN 常按单连接限速（约 30KB/s），连接数不够聚合带宽上不去。
            // 以约 96 片为目标缩小粒度（下限 256KB），小文件同样能开满并发
            long chunkSize = Math.max(256 * 1024, Math.min(CHUNK_SIZE, total / 96));
            if (total / chunkSize > 256) chunkSize = (total + 255) / 256;
            int n = (int) ((total + chunkSize - 1) / chunkSize);
            List<Future<?>> fs = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                long from = i * chunkSize;
                long to = Math.min(total, from + chunkSize) - 1;
                Path part = target.resolveSibling(target.getFileName() + ".part" + i);
                parts.add(part);
                fs.add(CHUNK_POOL.submit((Callable<Void>) () -> {
                    monitor.gate();   // 暂停闸门
                    CHUNK_SEM.acquire();
                    try {
                        // 分片内断点续传：停顿/断流就换连接从已下字节继续，不整片归零。
                        // 镜像源常见「突发几百 KB 后静默几十秒」的限速形态，归零重试必死。
                        // 但「偶尔来几字节」的涓流会让下面 idle 一直归零、整片永不收尾而卡死末尾，
                        // 这里给每片加墙钟上限：磨过上限仍未完成 = 源对该片过慢，抛错放弃，
                        // 让 downloadChunked 回退单流 / downloadPackFile 换下一个下载源，不再永久卡住
                        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(180);
                        int idle = 0;
                        while (true) {
                            long need = to - from + 1;
                            long done = Files.exists(part) ? Files.size(part) : 0;
                            if (done >= need) return null;
                            if (System.nanoTime() > deadline)
                                throw new IOException("分片下载超时（源过慢）");
                            try {
                                HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                                        .timeout(Duration.ofSeconds(timeoutSec))
                                        .header("Range", "bytes=" + (from + done) + "-" + to).GET().build();
                                HttpResponse<InputStream> resp =
                                        HTTP.send(req, HttpResponse.BodyHandlers.ofInputStream());
                                if (resp.statusCode() != 206)
                                    throw new IOException("分片下载失败 HTTP " + resp.statusCode());
                                copyLimited(resp.body(), part, done > 0);
                                if (Files.size(part) != need)
                                    throw new IOException("分片长度不符");
                                return null;
                            } catch (IOException ex) {
                                if (monitor.cancelRequested()) throw ex;
                                long now = Files.exists(part) ? Files.size(part) : 0;
                                if (now > done) idle = 0;          // 有进展：重置失败计数
                                else if (++idle >= MAX_RETRY) throw ex;
                                Thread.sleep(500L * idle);
                            }
                        }
                    } finally {
                        CHUNK_SEM.release();
                    }
                }));
            }
            // 等全部分片收尾再判定：任一失败 → 清理残片退回单流。
            // 先等完所有线程是为了避免「清理删文件」和「其它分片还在写」竞写留脏分片
            Exception first = null;
            for (Future<?> f : fs) {
                try { f.get(); } catch (Exception ex) { if (first == null) first = ex; }
            }
            if (first != null) throw first;

            try (FileChannel out = FileChannel.open(target,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                for (Path part : parts) {
                    try (FileChannel in = FileChannel.open(part, StandardOpenOption.READ)) {
                        long size = in.size(), pos = 0;
                        while (pos < size) pos += in.transferTo(pos, size - pos, out);
                    }
                }
            }
            for (Path part : parts) { try { Files.deleteIfExists(part); } catch (IOException ignored) { } }
            monitor.foldBytes(parts, target, total);   // 分片峰值归账到合并目标
            return true;
        } catch (Exception ex) {
            monitor.foldBytes(parts, target, 0);   // 失败：丢弃分片峰值记账
            // 清理残留分片，调用方会退回普通单流下载
            try (var stream = Files.list(target.getParent())) {
                stream.filter(p -> p.getFileName().toString()
                                .startsWith(target.getFileName() + ".part"))
                        .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) { } });
            } catch (IOException ignored) { }
            return false;
        }
    }

    private void downloadFile(String url, Path dest, String sha1)
            throws IOException, InterruptedException {
        downloadFile(url, dest, sha1, 300);
    }

    /**
     * 响应体停滞看门狗：连续 COPY_STALL_SEC 秒读不到任何数据就强行关流，让阻塞中的 read 抛错。
     * java.net.http 的请求超时只覆盖到响应头，响应体读取可能无限阻塞；而慢速涓流的连接
     * 不该被「总超时」杀掉——用「多久没有进度」判死：慢但在动的下载让它继续，静默的尽快换源。
     */
    private static final long COPY_STALL_SEC = 60;
    private static final long COPY_TRICKLE_SEC = 25;               // 近死涓流宽限期
    private static final double COPY_MIN_KBS = 3.0;                // 均速低于此视为死连接
    private static final long COPY_TRICKLE_MAX = 8L * 1024 * 1024; // 只对小进度判死（大文件慢源可能正常）

    private Thread startStallGuard(InputStream in, AtomicLong lastData, AtomicBoolean stalled,
                                   AtomicLong got, long start) {
        Thread t = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    return;
                }
                if (monitor.cancelRequested()) {   // 取消：立刻关流，解除卡在 read() 里的下载线程
                    try { in.close(); } catch (IOException ignored) { }
                    return;
                }
                if (monitor.paused()) {   // 用户暂停不算停滞
                    lastData.set(System.nanoTime());
                    continue;
                }
                long now = System.nanoTime();
                boolean stall = now - lastData.get() > TimeUnit.SECONDS.toNanos(COPY_STALL_SEC);
                // 近死涓流：偶尔来几字节会让「多久没数据」永远判不死，这里补一道平均速率下限——
                // 过了宽限期仍均速极低（且总量很小）就当死连接强断，交回上层换源/重试，不再无限卡在末尾
                double avgKbs = got.get() / 1024.0 / Math.max(0.001, (now - start) / 1e9);
                boolean trickle = now - start > TimeUnit.SECONDS.toNanos(COPY_TRICKLE_SEC)
                        && got.get() < COPY_TRICKLE_MAX && avgKbs < COPY_MIN_KBS;
                if (stall || trickle) {
                    stalled.set(true);
                    try { in.close(); } catch (IOException ignored) { }
                    return;
                }
            }
        }, "yhcl-copy-stall-guard");
        t.setDaemon(true);
        t.start();
        return t;
    }

    private void copyLimited(InputStream in, Path dest)
            throws IOException, InterruptedException {
        copyLimited(in, dest, false);
    }

    /** append = 断点续传模式：保留文件里已有的字节只往后追加（配合 Range 请求使用） */
    private void copyLimited(InputStream in, Path dest, boolean append)
            throws IOException, InterruptedException {
        boolean limited = speedLimitKb > 0;
        long lastTouch = System.nanoTime();
        AtomicLong lastData = new AtomicLong(lastTouch);
        AtomicBoolean stalled = new AtomicBoolean();
        AtomicLong got = new AtomicLong();
        // 限速时读间隔由用户设定的速度决定，不做停滞检测（限速慢是用户意图）
        Thread guard = limited ? null : startStallGuard(in, lastData, stalled, got, lastTouch);
        long written = append && Files.exists(dest) ? Files.size(dest) : 0;
        try (InputStream src = in;
             OutputStream out = Files.newOutputStream(dest,
                     StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                     append ? StandardOpenOption.APPEND : StandardOpenOption.TRUNCATE_EXISTING)) {
            byte[] buf = new byte[64 * 1024];
            while (true) {
                monitor.gate();   // 暂停期间挂起，取消时抛出
                int n = src.read(buf);
                if (n <= 0) break;
                lastData.set(System.nanoTime());
                limiter.acquire(n);
                out.write(buf, 0, n);
                written += n;
                got.addAndGet(n);
                monitor.setItemBytes(dest, written);   // 实时字节进度（ETA 用）
                long now = System.nanoTime();
                if (now - lastTouch > TimeUnit.SECONDS.toNanos(2)) {
                    monitor.touch();
                    lastTouch = now;
                }
            }
            if (stalled.get())
                throw new IOException("下载数据流中断（停滞/过慢）：" + dest.getFileName());
        } finally {
            if (guard != null) guard.interrupt();
        }
    }

    private void downloadFile(String url, Path dest, String sha1, long timeoutSec)
            throws IOException, InterruptedException {
        List<String> candidates = candidates(url);
        boolean isJar = dest.getFileName().toString().endsWith(".jar");
        boolean zipCheck = isJar && (sha1 == null || sha1.isEmpty()); // 无校验和时用 zip 结构兜底
        if (Files.exists(dest)) {
            if (sha1 != null && !sha1.isEmpty()) {
                if (sha1Of(dest).equalsIgnoreCase(sha1)) return;
            } else if (!zipCheck || validZip(dest)) {
                return;
            }
            // 校验不通过：删掉重下
            Files.deleteIfExists(dest);
        }
        Files.createDirectories(dest.getParent());
        Path tmp = dest.resolveSibling(dest.getFileName() + ".tmp");
        // 断点续传：失败不删 .tmp，下次尝试发 Range 从已下字节接着下。
        // 判定彻底失败的标准是「连续 MAX_RETRY 次尝试一个字节都没多下」，
        // 而不是固定次数——慢速涓流只要还在动就一直磨，突发-停顿式限速也磨得完
        IOException last = null;
        int idle = 0;
        for (int attempt = 1; attempt <= 60 && idle < MAX_RETRY; attempt++) {
            if (monitor.cancelRequested()) {
                try { Files.deleteIfExists(tmp); } catch (IOException ignored) { }
                throw new IOException("下载已取消");
            }
            monitor.gate();   // 暂停闸门：暂停期间挂起，取消时抛出
            String real = pickCandidate(candidates, attempt);
            long before = Files.exists(tmp) ? Files.size(tmp) : 0;
            try {
                // 大文件走分片并发下载（4MB/片），失败自动退回下面的单流下载
                boolean chunked = isChunkCandidate(dest)
                        && downloadChunked(real, tmp, timeoutSec);
                if (!chunked) {
                    long offset = Files.exists(tmp) ? Files.size(tmp) : 0;
                    HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(real))
                            .timeout(Duration.ofSeconds(timeoutSec)).GET();
                    if (offset > 0) rb.header("Range", "bytes=" + offset + "-");
                    HttpResponse<InputStream> resp =
                            HTTP.send(rb.build(), HttpResponse.BodyHandlers.ofInputStream());
                    int sc = resp.statusCode();
                    if (sc == 416) {   // 续传点越界（残留 tmp 已完整或文件变小）：丢弃残留从头来
                        try { Files.deleteIfExists(tmp); } catch (IOException ignored) { }
                        idle++;
                        continue;
                    }
                    if (sc / 100 != 2)
                        throw new IOException("下载失败 HTTP " + sc + "：" + real);
                    long cl = resp.headers().firstValueAsLong("Content-Length").orElse(-1);
                    if (cl > 0) monitor.noteItemSize(dest, (sc == 206 ? offset : 0) + cl);
                    // 206 = 服务端认 Range，接着已有字节追加；200 = 只能从头覆盖
                    copyLimited(resp.body(), tmp, sc == 206 && offset > 0);
                }
                if (sha1 != null && !sha1.isEmpty() && !sha1Of(tmp).equalsIgnoreCase(sha1)) {
                    try { Files.deleteIfExists(tmp); } catch (IOException ignored) { }
                    throw new IOException("哈希校验失败：" + real);
                }
                if (zipCheck && !validZip(tmp)) {
                    try { Files.deleteIfExists(tmp); } catch (IOException ignored) { }
                    throw new IOException("文件不完整（zip 校验失败）：" + real);
                }
                Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING);
                monitor.settleBytes(tmp, dest, Files.size(dest));   // .tmp 峰值归账到最终文件
                monitor.registerDownloaded(dest);   // 取消下载时按清单删除本次下载的文件
                return;
            } catch (IOException ex) {
                if (monitor.cancelRequested()) {
                    try { Files.deleteIfExists(tmp); } catch (IOException ignored) { }
                    throw ex;
                }
                last = ex;
                noteHostDead(real, ex);
                long now = Files.exists(tmp) ? Files.size(tmp) : 0;
                if (now > before) idle = 0;   // 有进展就清零失败计数
                else idle++;
                if (idle < MAX_RETRY) Thread.sleep(500L * Math.max(1, idle));
            }
        }
        // 保留 .tmp：下次重试同一文件可直接续传
        throw new IOException("下载失败（连续 " + MAX_RETRY + " 次无进展，镜像/官方都试过）：" + url
                + " —— " + (last == null ? "未知错误" : last.getMessage()));
    }

    /** 校验文件是完整的 zip/jar（能读到中央目录才算） */
    private static boolean validZip(Path p) {
        try (ZipFile zf = new ZipFile(p.toFile())) {
            return zf.size() > 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static String sha1Of(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return sha1Of(in);
        }
    }

    private static String sha1Of(InputStream in) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    private static String sha1Of(JsonNode node) { return node.path("sha1").asText(""); }

    private static void extractNatives(Path jarPath, Path dest) {
        try (ZipFile zip = new ZipFile(jarPath.toFile())) {
            Files.createDirectories(dest);
            zip.stream().forEach(e -> {
                if (e.isDirectory()) return;
                String name = e.getName();
                if (name.toUpperCase(Locale.ROOT).startsWith("META-INF")) return;
                Path out = dest.resolve(new File(name).getName());
                try (InputStream in = zip.getInputStream(e)) {
                    Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException ignored) { }
            });
        } catch (IOException ignored) { }
    }

    /* ============ 启动参数解析 ============ */
    private static List<String> expand(JsonNode arr, Map<String, String> vars) {
        List<String> out = new ArrayList<>();
        if (arr == null || !arr.isArray()) return out;
        for (JsonNode el : arr) {
            if (el.isTextual()) {
                out.add(sub(el.asText(), vars));
            } else if (el.isObject()) {
                if (!rulesAllow(el)) continue;
                JsonNode v = el.path("value");
                if (v.isTextual()) out.add(sub(v.asText(), vars));
                else if (v.isArray()) for (JsonNode s : v) out.add(sub(s.asText(), vars));
            }
        }
        return out;
    }

    private static String sub(String s, Map<String, String> vars) {
        for (Map.Entry<String, String> kv : vars.entrySet())
            s = s.replace("${" + kv.getKey() + "}", kv.getValue());
        return s;
    }

    private static boolean feature(String name) {
        return switch (name) {
            case "is_demo_user" -> false;
            case "has_custom_resolution" -> true;
            default -> false;
        };
    }

    private static boolean rulesAllow(JsonNode el) {
        JsonNode rules = el.path("rules");
        if (!rules.isArray() || rules.isEmpty()) return true;

        boolean allow = false;
        for (JsonNode r : rules) {
            boolean match = true;

            JsonNode os = r.path("os");
            if (os.has("name") && !"windows".equals(os.path("name").asText())) match = false;
            if ("x86".equals(os.path("arch").asText(""))
                    && System.getProperty("os.arch").contains("64")) match = false;

            JsonNode feats = r.path("features");
            if (feats.isObject()) {
                var it = feats.fieldNames();
                while (it.hasNext()) {
                    String name = it.next();
                    if (feature(name) != feats.path(name).asBoolean()) match = false;
                }
            }

            if (match) allow = "allow".equals(r.path("action").asText());
        }
        return allow;
    }

    private List<String> buildClasspath(JsonNode root) {
        List<String> cp = new ArrayList<>();
        for (Lib l : resolveLibraries(root)) cp.add(l.dest().toString());
        String id = root.path("id").asText();
        // client jar 优先自身目录（兼容旧安装），否则用继承根版本的 jar（加载器版本不重复存原版）
        Path own = gameDir.resolve("versions").resolve(id).resolve(id + ".jar");
        if (Files.exists(own)) {
            cp.add(own.toString());
        } else {
            String rootId = inheritedRoot(id);
            cp.add(gameDir.resolve("versions").resolve(rootId).resolve(rootId + ".jar").toString());
        }
        return cp;
    }

    /** 沿 inheritsFrom 链上溯到最顶层的原版版本 id（无继承则为自身）；防环上限 16 层 */
    private String inheritedRoot(String id) {
        String cur = id;
        for (int i = 0; i < 16; i++) {
            Path p = gameDir.resolve("versions").resolve(cur).resolve(cur + ".json");
            if (!Files.exists(p)) break;
            try {
                String parent = MAPPER.readTree(p.toFile()).path("inheritsFrom").asText("");
                if (parent.isBlank()) break;
                cur = parent;
            } catch (IOException e) { break; }
        }
        return cur;
    }

    /* ============ 版本 json：加载 + 继承合并（Fabric/Forge 靠它） ============ */
    private JsonNode loadRoot(String id) throws IOException {
        Path p = gameDir.resolve("versions").resolve(id).resolve(id + ".json");
        if (!Files.exists(p))
            throw new IOException("缺少版本定义 " + id + ".json，请先在「版本」页安装。");
        ObjectNode node = (ObjectNode) MAPPER.readTree(p.toFile());
        String parent = node.path("inheritsFrom").asText("");
        if (!parent.isBlank()) return merge(loadRoot(parent), node);
        return node;
    }

    private static JsonNode merge(JsonNode baseNode, JsonNode childNode) {
        ObjectNode out = (ObjectNode) baseNode.deepCopy();
        ObjectNode child = (ObjectNode) childNode;
        Iterator<Map.Entry<String, JsonNode>> it = child.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            String k = e.getKey();
            JsonNode v = e.getValue();
            switch (k) {
                case "libraries" -> {
                    List<JsonNode> merged = new ArrayList<>();
                    if (out.path("libraries").isArray()) out.path("libraries").forEach(merged::add);
                    if (v.isArray()) v.forEach(merged::add);
                    out.set("libraries", MAPPER.valueToTree(merged));
                }
                case "arguments" -> {
                    ObjectNode args = (out.has("arguments") && out.get("arguments").isObject())
                            ? (ObjectNode) out.get("arguments")
                            : out.putObject("arguments");
                    if (v.isObject()) {
                        for (String key : new String[]{"game", "jvm"}) {
                            List<JsonNode> merged = new ArrayList<>();
                            if (args.path(key).isArray()) args.path(key).forEach(merged::add);
                            if (v.path(key).isArray()) v.path(key).forEach(merged::add);
                            args.set(key, MAPPER.valueToTree(merged));
                        }
                    }
                }
                default -> out.set(k, v);
            }
        }
        return out;
    }

    /* ============ Java 解析 ============ */
    public static String findJava() {
        JavaLocator.JavaInfo best = JavaLocator.pick(21);
        return best == null ? "javaw.exe" : best.consoleExe().toString();
    }

    /**
     * 安装器对 Java 的要求比游戏本体宽松：例如需要 Java 25 才能运行的版本，
     * 其 Forge 安装器在 Java 21 下也能正常完成安装（实测 26.1.2-64.1.5）。
     * 因此找不到游戏要求的 Java 时，退回本机较高版本的 Java 来跑安装器。
     */
    private String resolveJavaForInstaller(int gameNeed, Consumer<String> log) {
        try {
            return resolveJava(gameNeed, log);
        } catch (IllegalStateException first) {
            if (gameNeed >= 17) {
                JavaLocator.JavaInfo j = JavaLocator.pick(17);
                if (j != null) {
                    log.accept("本机没有 Java " + gameNeed + "，安装器改用 Java " + j.major + " 运行");
                    return j.consoleExe().toString();
                }
            }
            throw first;
        }
    }

    private String resolveJava(int requiredMajor, Consumer<String> log) {
        return resolveJava(requiredMajor, Integer.MAX_VALUE, log);
    }

    /**
     * 游戏对 Java 有上下限：下限来自版本 json 的 javaVersion（低版本 Java 跑不了新类文件）；
     * 上限来自启动方式的硬约束——LaunchWrapper 时代（MC ≤ 1.12）会把 AppClassLoader 强转
     * URLClassLoader，Java 9+ 必然 ClassCastException，只接受 Java 8，绝不向上兜底。
     * 本机没有合适版本时，自动从国内镜像下载托管运行时。
     */
    private String resolveJava(int requiredMajor, int maxMajor, Consumer<String> log) {
        if (requiredMajor <= 0) requiredMajor = 8;

        List<JavaLocator.JavaInfo> all = JavaLocator.scan();
        log.accept("在电脑上搜索到 " + all.size() + " 个 Java：" + String.join("、",
                all.stream().map(j -> String.valueOf(j.major)).distinct().toList()));

        if (!autoJava && javaPath != null && !javaPath.isBlank() && Files.exists(Path.of(javaPath))) {
            String console = javaPath.replace("javaw.exe", "java.exe");
            Path use = Files.exists(Path.of(console)) ? Path.of(console) : Path.of(javaPath);
            JavaLocator.JavaInfo mi = JavaLocator.info(use);
            if (mi != null && mi.major >= requiredMajor && mi.major <= maxMajor && JavaLocator.complete(use)) {
                log.accept("使用手动指定的 Java：" + javaPath);
                return use.toString();
            }
            if (mi != null && (mi.major < requiredMajor || mi.major > maxMajor))
                log.accept("手动指定的 Java " + mi.major + " 不满足本版本要求（Java "
                        + requiredMajor + (maxMajor == 8 ? "～" + maxMajor : " 或更高") + "），已忽略，改用自动选择。");
            else
                log.accept("手动指定的 Java 缺少运行 Mod 必需的模块（java.sql 等），已忽略，改用自动选择。");
        }

        JavaLocator.JavaInfo pick = JavaLocator.pick(requiredMajor, maxMajor);
        if (pick == null) {
            // 本机没有合适版本：先用启动器托管的运行时（此前自动下载的），没有就自动下载
            String managed = useManagedJava(requiredMajor, maxMajor, log);
            if (managed != null) return managed;

            final int need = requiredMajor;
            // 区分「版本不够」和「版本够但都是裁剪版运行时」两种情况
            boolean trimmedOnly = all.stream()
                    .anyMatch(j -> j.major >= need && j.major <= maxMajor && !j.complete);
            String range = maxMajor == 8 ? "Java 8" : "Java " + need + " 或更高";
            if (trimmedOnly) {
                throw new IllegalStateException(
                        "找到的 " + range + " 都是裁剪版运行时（缺少 java.sql 等运行 Mod 必需的模块），" +
                                "游戏启动会报 ClassNotFoundException: java.sql.Time。\n" +
                                "自动下载完整版 Java 也失败了。解决方法（任选其一）：\n" +
                                "1. 到 https://mirrors.tuna.tsinghua.edu.cn/Adoptium/8/jdk/x64/windows/\n" +
                                "   （国内镜像）下载 OpenJDK8U-jdk_x64_windows_hotspot_*.zip，解压到任意目录，\n" +
                                "   然后在「版本设置 → Java 路径」里选中其中的 java.exe；\n" +
                                "2. 到 https://adoptium.net/temurin/releases/?version=" + need +
                                " 下载安装完整版 Java（Windows x64 .msi，国外站点），安装后重新点「启动游戏」。");
            }
            String found = all.isEmpty() ? "没有找到任何 Java"
                    : "只找到 " + String.join("、", all.stream()
                    .map(j -> "Java " + j.major).distinct().toList());
            throw new IllegalStateException(
                    "这个游戏需要 " + range + "，但" + found + "。\n" +
                            "自动下载 Java 失败，请手动安装后重新点「启动游戏」：\n" +
                            "国内镜像：https://mirrors.tuna.tsinghua.edu.cn/Adoptium/8/jdk/x64/windows/\n" +
                            "（下载 OpenJDK8U-jdk_x64_windows_hotspot_*.zip 解压，在「版本设置 → Java 路径」里选其中的 java.exe）\n" +
                            "国外站点：https://adoptium.net/temurin/releases/?version=" + need +
                            "（Windows x64 .msi）");
        }

        if (pick.major > requiredMajor)
            log.accept("未找到 Java " + requiredMajor + "，使用 Java " + pick.major + "（向下兼容）。");
        log.accept("选用 " + pick);
        return pick.consoleExe().toString();
    }

    /**
     * 启动器托管的 Java 运行时（gameDir/cache/runtime/jdkN）：
     * 已下载过就直接用；没有则从国内镜像（清华 Tuna Adoptium）自动下载 Temurin，
     * 失败回退官方 Adoptium API。失败返回 null（原因写日志）。
     */
    private String useManagedJava(int requiredMajor, int maxMajor, Consumer<String> log) {
        Path dir = gameDir.resolve("cache").resolve("runtime");
        if (Files.isDirectory(dir)) {
            try (var s = Files.list(dir)) {
                for (Path sub : s.sorted().toList()) {
                    if (!Files.isDirectory(sub)) continue;
                    JavaLocator.JavaInfo mi = JavaLocator.info(sub);
                    if (mi != null && mi.major >= requiredMajor && mi.major <= maxMajor
                            && JavaLocator.complete(sub)) {
                        log.accept("使用启动器托管的 Java " + mi.major + "：" + sub);
                        return mi.consoleExe().toString();
                    }
                }
            } catch (IOException ignored) { }
        }

        // 镜像只收录常用大版本：挑 [requiredMajor, maxMajor] 范围内镜像上有的最小版本
        int want = -1;
        for (int m : new int[]{8, 11, 17, 21})
            if (m >= requiredMajor && m <= maxMajor) { want = m; break; }
        if (want < 0) return null;

        try {
            log.accept("本机没有合适的 Java，正在自动下载 Temurin JDK " + want + "（国内镜像）…");
            Path home = downloadManagedJava(want, dir, log);
            JavaLocator.JavaInfo mi = JavaLocator.info(home);
            if (mi != null && JavaLocator.complete(home)) {
                log.accept("Java " + mi.major + " 下载完成：" + home);
                return mi.consoleExe().toString();
            }
            log.accept("下载的 Java 校验失败（缺少运行 Mod 必需模块），已丢弃。");
        } catch (Exception e) {
            log.accept("自动下载 Java " + want + " 失败：" + e.getMessage());
        }
        return null;
    }

    /** 下载并解压 Temurin JDK 到 runtime/jdk{major}，返回家目录 */
    private Path downloadManagedJava(int major, Path runtimeDir, Consumer<String> log)
            throws IOException, InterruptedException {
        Files.createDirectories(runtimeDir);
        List<String> urls = new ArrayList<>();
        // 国内镜像优先：清华 Tuna 的 Adoptium 镜像（目录页解析出最新 zip 文件名）
        String listing = "https://mirrors.tuna.tsinghua.edu.cn/Adoptium/" + major + "/jdk/x64/windows/";
        try {
            String html = fetchText(listing);
            Matcher m = Pattern.compile("href=\"(OpenJDK\\d+U-jdk_x64_windows_hotspot_[\\w.\\-]+\\.zip)\"")
                    .matcher(html);
            String best = null;
            while (m.find()) {
                String name = m.group(1);
                if (best == null || name.compareTo(best) > 0) best = name;
            }
            if (best != null) {
                urls.add(listing + best);
                log.accept("镜像文件：" + best);
            }
        } catch (IOException e) {
            log.accept("清华镜像暂不可用：" + e.getMessage());
        }
        // 回退官方 Adoptium API（国外，可能慢或不可达）
        urls.add("https://api.adoptium.net/v3/binary/latest/" + major + "/ga/windows/x64/jdk/hotspot/normal/eclipse");
        if (urls.isEmpty()) throw new IOException("没有可用的下载源");

        Path zip = runtimeDir.resolve("jdk" + major + ".zip");
        Files.deleteIfExists(zip);
        // 下载期间持续摸一下看门狗，避免安装流程里误判网络挂死
        AtomicBoolean dlDone = new AtomicBoolean(false);
        Thread ticker = new Thread(() -> {
            while (!dlDone.get()) {
                monitor.touch();
                try { Thread.sleep(3000); } catch (InterruptedException e) { return; }
            }
        }, "yhcl-java-dl-ticker");
        ticker.setDaemon(true);
        ticker.start();
        try {
            downloadFirstOf(urls, zip, null, 600);
        } finally {
            dlDone.set(true);
            ticker.interrupt();
        }
        if (!validZip(zip)) {
            Files.deleteIfExists(zip);
            throw new IOException("下载的 Java 压缩包不完整");
        }

        // 解压到暂存目录再挪到位（zip 内是一层版本目录，如 jdk8u504-b01/）
        Path staging = runtimeDir.resolve("jdk" + major + ".tmp");
        deleteRecursive(staging);
        Files.createDirectories(staging);
        try {
            unzip(zip, staging);
            Path home = staging;
            try (var s = Files.list(staging)) {
                List<Path> kids = s.toList();
                if (kids.size() == 1 && Files.isDirectory(kids.get(0))) home = kids.get(0);
            }
            Path target = runtimeDir.resolve("jdk" + major);
            deleteRecursive(target);
            Files.move(home, target);
            return target;
        } finally {
            deleteRecursive(staging);
            Files.deleteIfExists(zip);
        }
    }

    /** 解压 zip 到目标目录（条目名按 / 归一，防目录穿越） */
    private static void unzip(Path zipFile, Path destDir) throws IOException {
        Path base = destDir.toAbsolutePath().normalize();
        try (ZipFile zf = new ZipFile(zipFile.toFile())) {
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                String name = e.getName().replace('\\', '/');
                Path out = base.resolve(name).normalize();
                if (!out.startsWith(base)) throw new IOException("非法的 zip 条目：" + e.getName());
                if (e.isDirectory()) { Files.createDirectories(out); continue; }
                Files.createDirectories(out.getParent());
                try (InputStream in = zf.getInputStream(e)) {
                    Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static void deleteRecursive(Path dir) {
        if (dir == null || !Files.exists(dir)) return;
        try (var s = Files.walk(dir)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.delete(p); } catch (IOException ignored) { }
            });
        } catch (IOException ignored) { }
    }

    /* ============ 崩溃辅助 ============ */
    /**
     * 找最近的崩溃报告：版本隔离时崩溃报告落在 versions/&lt;id&gt;/crash-reports（游戏以该目录为工作目录），
     * 而非全局 crash-reports。这里按「指定版本隔离目录 → 全局目录 → 其它版本隔离目录」全部扫一遍，
     * 返回最新的一份，避免隔离版本崩溃后报告查不到。id 为空时只看全局。
     */
    public String findLatestCrashReport(String id) {
        List<Path> dirs = new ArrayList<>();
        if (id != null && !id.isBlank()) {
            Path d = gameDir.resolve("versions").resolve(id).resolve("crash-reports");
            if (Files.isDirectory(d)) dirs.add(d);
        }
        Path global = gameDir.resolve("crash-reports");
        if (Files.isDirectory(global) && !dirs.contains(global)) dirs.add(global);
        Path vers = gameDir.resolve("versions");
        if (Files.isDirectory(vers)) {
            try (var s = Files.list(vers)) {
                s.filter(Files::isDirectory).forEach(v -> {
                    Path d = v.resolve("crash-reports");
                    if (Files.isDirectory(d) && !dirs.contains(d)) dirs.add(d);
                });
            } catch (IOException ignored) { }
        }
        String best = "";
        long bestTime = 0;
        for (Path dir : dirs) {
            try (var s = Files.list(dir)) {
                var opt = s.filter(p -> p.getFileName().toString().startsWith("crash-"))
                        .max(Comparator.comparingLong(p -> p.toFile().lastModified()));
                if (opt.isPresent()) {
                    long t = opt.get().toFile().lastModified();
                    if (t > bestTime) { bestTime = t; best = opt.get().toString(); }
                }
            } catch (IOException ignored) { }
        }
        return best;
    }


    /* ============ 皮肤（官方 sessionserver） ============ */
    /** 拉取皮肤文件到本地缓存，返回缓存路径；force=true 时强制重新下载 */
    public Path fetchSkin(String uuid, boolean force) throws Exception {
        if (uuid == null || uuid.isBlank()) throw new IOException("没有账户 UUID");
        Path dir = gameDir.resolve("cache").resolve("skins");
        Files.createDirectories(dir);
        Path file = dir.resolve(uuid + ".png");
        if (force) Files.deleteIfExists(file);
        if (Files.exists(file) && Files.size(file) > 100) return file;

        String json = getText("https://sessionserver.mojang.com/session/minecraft/profile/" + uuid);
        JsonNode root = MAPPER.readTree(json);
        String b64 = "";
        for (JsonNode p : root.path("properties")) {
            if ("textures".equals(p.path("name").asText())) b64 = p.path("value").asText("");
        }
        if (b64.isBlank()) throw new IOException("无法获取皮肤数据");
        String decoded = new String(java.util.Base64.getDecoder().decode(b64), StandardCharsets.UTF_8);
        JsonNode tex = MAPPER.readTree(decoded);
        String skinUrl = tex.path("textures").path("SKIN").path("url").asText("");
        if (skinUrl.isBlank()) throw new IOException("该账户没有设置皮肤");
        downloadFile(skinUrl, file, null);
        return file;
    }

    /* ============ 离线皮肤（classpath 皮肤贴图 jar，覆盖默认 steve/alex） ============ */

    /**
     * 启动前按设置生成皮肤注入 jar：把皮肤贴图打成 jar 前置到 classpath，
     * 覆盖默认 steve/alex 贴图（离线玩家走的就是这两个默认贴图）。
     * 未启用或失败返回 null，不影响启动。
     */
    public Path prepareSkinJar(String mcVersion, GameProfile profile, LaunchOpts o, Consumer<String> log) {
        if (o == null || o.skinMode == null || o.skinMode.isBlank()) return null;
        if (profile.isMicrosoft || profile.isYggdrasil) return null;   // 正版/第三方账号游戏自己会取皮肤
        try {
            String mode = o.skinMode;
            if ("random".equals(mode)) {
                List<String> pool = new ArrayList<>(List.of("steve", "alex"));
                if (o.skinFile != null && !o.skinFile.isBlank() && Files.exists(Path.of(o.skinFile)))
                    pool.add("custom");
                mode = pool.get(java.util.concurrent.ThreadLocalRandom.current().nextInt(pool.size()));
            }
            byte[] png;
            String desc;
            switch (mode) {
                case "steve" -> {
                    png = requireSkin(defaultSkinFromJar(mcVersion, false), "无法从版本 jar 提取 Steve 皮肤");
                    desc = "Steve";
                }
                case "alex" -> {
                    png = requireSkin(defaultSkinFromJar(mcVersion, true), "无法从版本 jar 提取 Alex 皮肤");
                    desc = "Alex";
                }
                case "official" -> {
                    try {
                        String uuid = profile.uuid != null && !profile.uuid.isBlank()
                                ? profile.uuid : premiumUuidByName(profile.name);
                        png = Files.readAllBytes(fetchSkin(uuid, false));
                        desc = "正版皮肤 · " + profile.name;
                    } catch (Exception ex) {
                        png = requireSkin(defaultSkinFromJar(mcVersion, false), "无法从版本 jar 提取 Steve 皮肤");
                        desc = "正版皮肤获取失败，回退 Steve（" + ex.getMessage() + "）";
                    }
                }
                case "custom" -> {
                    Path cf = o.skinFile == null || o.skinFile.isBlank() ? null : Path.of(o.skinFile);
                    if (cf == null || !Files.isRegularFile(cf))
                        throw new IOException("未导入自定义皮肤 PNG（设置页 → 离线皮肤 → 导入皮肤）");
                    png = Files.readAllBytes(cf);
                    desc = "自定义皮肤 · " + cf.getFileName();
                }
                default -> {
                    return null;
                }
            }
            Path jar = gameDir.resolve("cache").resolve("skins").resolve("yhcl-skin.jar");
            Files.createDirectories(jar.getParent());
            writeSkinJar(jar, png);
            log.accept("离线皮肤已生效（" + desc + "）");
            return jar;
        } catch (Exception ex) {
            log.accept("离线皮肤应用失败（不影响启动）：" + ex.getMessage());
            return null;
        }
    }

    private static byte[] requireSkin(byte[] png, String err) throws IOException {
        if (png == null) throw new IOException(err);
        return png;
    }

    /** 从原版客户端 jar 提取默认 steve/alex 皮肤贴图（1.20.2+ 贴图迁移到 entity/player/wide|slim/，两套路径都试） */
    private byte[] defaultSkinFromJar(String mcVersion, boolean slim) throws IOException {
        String[] entries = slim
                ? new String[] {
                    "assets/minecraft/textures/entity/alex.png",
                    "assets/minecraft/textures/entity/player/slim/alex.png" }
                : new String[] {
                    "assets/minecraft/textures/entity/steve.png",
                    "assets/minecraft/textures/entity/player/wide/steve.png" };
        if (mcVersion != null && !mcVersion.isBlank()) {
            Path j = gameDir.resolve("versions").resolve(mcVersion).resolve(mcVersion + ".jar");
            if (Files.isRegularFile(j)) {
                try (java.util.zip.ZipFile z = new java.util.zip.ZipFile(j.toFile())) {
                    for (String entry : entries) {
                        java.util.zip.ZipEntry e = z.getEntry(entry);
                        if (e != null) return z.getInputStream(e).readAllBytes();
                    }
                }
            }
        }
        return null;
    }

    /** 把皮肤贴图写成资源 jar：覆盖所有默认皮肤贴图位置（含新版本的 player/wide|slim 路径） */
    private void writeSkinJar(Path jar, byte[] png) throws IOException {
        String[] entries = {
                "assets/minecraft/textures/entity/steve.png",
                "assets/minecraft/textures/entity/alex.png",
                "assets/minecraft/textures/entity/player/wide/steve.png",
                "assets/minecraft/textures/entity/player/wide/alex.png",
                "assets/minecraft/textures/entity/player/slim/steve.png",
                "assets/minecraft/textures/entity/player/slim/alex.png",
        };
        try (java.util.zip.ZipOutputStream z = new java.util.zip.ZipOutputStream(
                Files.newOutputStream(jar, java.nio.file.StandardOpenOption.WRITE,
                        java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.TRUNCATE_EXISTING))) {
            for (String e : entries) {
                z.putNextEntry(new java.util.zip.ZipEntry(e));
                z.write(png);
                z.closeEntry();
            }
        }
    }

    /** 生成 LWJGL Unsafe Agent jar（premain 在游戏启动最早期放开 sun.misc.Unsafe 内存访问限制）。
     *  Agent 类内置于启动器资源（Java 8 字节码），每次启动重建 jar 保证与启动器版本一致；未内置时返回 null。 */
    private Path buildLwjglUnsafeAgent() throws IOException {
        byte[] cls;
        try (InputStream in = MinecraftService.class.getResourceAsStream("/agent/lwjgl-unsafe-agent.class")) {
            if (in == null) return null;
            cls = in.readAllBytes();
        }
        Path dir = gameDir.resolve("cache").resolve("agents");
        Files.createDirectories(dir);
        Path jar = dir.resolve("lwjgl-unsafe-agent.jar");
        String manifest = "Manifest-Version: 1.0\r\n"
                + "Premain-Class: com.deepslate.yhcl.LwjglUnsafeAgent\r\n\r\n";
        try (java.util.zip.ZipOutputStream z = new java.util.zip.ZipOutputStream(
                Files.newOutputStream(jar, StandardOpenOption.WRITE,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING))) {
            z.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
            z.write(manifest.getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
            z.putNextEntry(new ZipEntry("com/deepslate/yhcl/LwjglUnsafeAgent.class"));
            z.write(cls);
            z.closeEntry();
        }
        return jar;
    }

    /* ============ Authlib Injector（第三方登录） ============ */
    /** 获取 Authlib Injector jar（YHCL\tools\authlib-injector\authlib-injector.jar），
     *  缺失或过小则镜像优先下载，镜像失败回退官方源；全部失败抛异常（否则游戏会 invalid session）。 */
    private Path ensureAuthlibInjector(Consumer<String> log) throws Exception {
        Path jar = Launcher.appDir().resolve("tools").resolve("authlib-injector")
                .resolve("authlib-injector.jar");
        if (Files.isRegularFile(jar) && fileSize(jar) > 200_000) return jar;

        Files.createDirectories(jar.getParent());
        String[] metaUrls = {
                "https://bmclapi2.bangbang93.com/mirrors/authlib-injector/artifact/latest.json",
                "https://authlib-injector.yushi.moe/artifact/latest.json"
        };
        Exception last = null;
        for (String metaUrl : metaUrls) {
            try {
                log.accept("下载 Authlib Injector（" + metaUrl + "）…");
                String body = getText(metaUrl);
                String downloadUrl = MAPPER.readTree(body).path("download_url").asText("");
                if (downloadUrl.isBlank()) throw new IOException("latest.json 无 download_url");
                if (metaUrl.contains("bmclapi")) {
                    // 镜像源的 download_url 可能指向官方，换到镜像直链
                    downloadUrl = downloadUrl.replace("https://authlib-injector.yushi.moe",
                            "https://bmclapi2.bangbang93.com/mirrors/authlib-injector");
                }
                byte[] data = getBytes(downloadUrl);
                if (data.length <= 200_000) throw new IOException("下载文件过小（" + data.length + " 字节），疑似错误页");
                Path tmp = jar.resolveSibling(jar.getFileName() + ".tmp");
                Files.write(tmp, data, StandardOpenOption.WRITE,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                Files.move(tmp, jar, StandardCopyOption.REPLACE_EXISTING);
                log.accept("Authlib Injector 已就绪（" + (data.length / 1024) + " KB）");
                return jar;
            } catch (Exception ex) {
                last = ex;
                log.accept("该下载源失败：" + ex.getMessage());
            }
        }
        throw new Exception("Authlib Injector 下载失败（镜像/官方都试过），无法第三方登录："
                + (last == null ? "未知错误" : last.getMessage()));
    }

    private static long fileSize(Path p) {
        try { return Files.size(p); } catch (IOException e) { return 0L; }
    }

    /** 下载为字节数组（不限于文本） */
    private byte[] getBytes(String url) throws IOException, InterruptedException {
        IOException last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(30)).GET().build();
                HttpResponse<byte[]> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofByteArray());
                if (resp.statusCode() / 100 != 2)
                    throw new IOException("HTTP " + resp.statusCode());
                return resp.body();
            } catch (IOException ex) {
                last = ex;
                if (attempt < 3) Thread.sleep(800L * attempt);
            }
        }
        throw last != null ? last : new IOException("下载失败：" + url);
    }

    /** 按玩家名查正版 UUID（无连字符），查不到抛异常 */
    private String premiumUuidByName(String name) throws Exception {
        if (name == null || name.isBlank()) throw new IOException("没有玩家名");
        String json = getText("https://api.mojang.com/users/profiles/minecraft/" + name.trim());
        String id = MAPPER.readTree(json).path("id").asText("");
        if (id.isBlank()) throw new IOException("找不到同名正版玩家");
        return id;
    }

    /* ============ 离线 UUID ============ */
    private static String offlineUuid(String name) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] h = md.digest(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
            h[6] = (byte) ((h[6] & 0x0F) | 0x30);
            h[8] = (byte) ((h[8] & 0x3F) | 0x80);
            StringBuilder sb = new StringBuilder();
            for (byte b : h) sb.append(String.format("%02x", b));
            String hex = sb.toString();
            return hex.substring(0, 8) + "-" + hex.substring(8, 12) + "-" + hex.substring(12, 16)
                    + "-" + hex.substring(16, 20) + "-" + hex.substring(20);
        } catch (Exception e) {
            return UUID.randomUUID().toString().replace("-", "");
        }
    }

    /* ============ 杂项 ============ */
    private String getText(String url) throws IOException, InterruptedException {
        return getText(url, fileSourceMode);
    }

    /** 取文本；mode 指定按哪个源选择设置排候选（版本列表相关走 metaSourceMode） */
    private String getText(String url, String mode) throws IOException, InterruptedException {
        List<String> candidates = candidates(url, mode);
        IOException last = null;
        for (int attempt = 1; attempt <= MAX_RETRY; attempt++) {
            String real = pickCandidate(candidates, attempt);
            try {
                return fetchText(real);
            } catch (IOException ex) {
                last = ex;
                if (attempt < MAX_RETRY) Thread.sleep(800L * attempt);
            }
        }
        throw new IOException("请求失败（已重试 " + MAX_RETRY + " 次，镜像/官方都试过）：" + url
                + " —— " + (last == null ? "未知错误" : last.getMessage()));
    }

    /** 对单个真实 URL 取文本（不做镜像改写，供需要精确访问指定源的调用使用） */
    private String fetchText(String realUrl) throws IOException, InterruptedException {
        IOException last = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(realUrl))
                        .timeout(Duration.ofSeconds(20)).GET().build();
                HttpResponse<String> resp =
                        HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (resp.statusCode() / 100 != 2)
                    throw new IOException("请求失败 HTTP " + resp.statusCode() + "：" + realUrl);
                return resp.body();
            } catch (IOException ex) {
                last = ex;
                noteHostDead(realUrl, ex);
                // 连接级失败（超时/拒连）同源重试只会再空耗一个连接超时，立即抛给上层换源
                if (isConnectFail(ex)) break;
                if (attempt < 2) Thread.sleep(800L);
            }
        }
        throw last != null ? last : new IOException("请求失败：" + realUrl);
    }

    private static void pump(InputStream in, Consumer<String> line) {
        Thread t = new Thread(() -> {
            try (var br = new java.io.BufferedReader(new java.io.InputStreamReader(in, StandardCharsets.UTF_8))) {
                String l;
                while ((l = br.readLine()) != null) line.accept(l);
            } catch (IOException ignored) { }
        }, "yhcl-log-pump");
        t.setDaemon(true);
        t.start();
    }

    /* ============ 版本号比较 ============ */
    private static int compareVer(String a, String b) {
        String[] pa = a.split("[.-]"), pb = b.split("[.-]");
        for (int i = 0; i < Math.max(pa.length, pb.length); i++) {
            int na = i < pa.length ? parseSafe(pa[i]) : 0;
            int nb = i < pb.length ? parseSafe(pb[i]) : 0;
            if (na != nb) return Integer.compare(na, nb);
        }
        return 0;
    }

    private static int parseSafe(String s) {
        try { return Integer.parseInt(s.replaceAll("\\D.*", "")); }
        catch (Exception e) { return 0; }
    }

    /* ============ Modrinth Mod 下载 ============ */

    /** Modrinth/CurseForge 搜索结果（简化版，UI 列表展示用）。cfProjectId 为 CurseForge 备用下载源（空 = 无） */
    public record ModSearchResult(
            String projectId, String title, String summary, String iconUrl, String site,
            String author, long downloads, long follows, String dateModified,
            List<String> categories, List<String> mcVersions, List<String> loaders,
            String cfProjectId) {}

    /** 搜索结果页：items = 本页条目，totalHits = 命中总数（分页用） */
    public record ModSearchPage(List<ModSearchResult> items, long totalHits) {}

    /** 搜索 Modrinth，query 为空或 "*" 时返回热门；mcVersion 为空时不过滤游戏版本；loader 为空时不过滤加载器。 */
    public CompletableFuture<List<ModSearchResult>> searchMods(String query, String mcVersion, String loader) {
        return searchMods(query, mcVersion, loader, null);
    }

    /** 搜索 Modrinth（带排序）：index = relevance | downloads | follows | newest | updated，空值按 relevance。 */
    public CompletableFuture<List<ModSearchResult>> searchMods(String query, String mcVersion, String loader, String index) {
        return searchModsPage(query, mcVersion, loader, "", index, 0, 30).thenApply(ModSearchPage::items);
    }

    /** 搜索 Modrinth + CurseForge（带排序 + 分页）：category = Modrinth 分类 slug（空 = 不过滤）；结果合并去重，Modrinth 优先。 */
    public CompletableFuture<ModSearchPage> searchModsPage(String query, String mcVersion, String loader, String category,
                                                           String index, int offset, int limit) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                List<String> facetList = new ArrayList<>();
                facetList.add("[\"project_type:mod\"]");   // 只搜 Mod，避免分类过滤把整合包/光影带进来
                if (mcVersion != null && !mcVersion.isBlank())
                    facetList.add("[\"versions:" + mcVersion + "\"]");
                if (loader != null && !loader.isBlank())
                    facetList.add("[\"loaders:" + loader + "\"]");
                if (category != null && !category.isBlank())
                    facetList.add("[\"categories:" + category + "\"]");
                String q = (query == null || query.isBlank() || "*".equals(query)) ? "" : query;
                ModSearchPage mr = modrinthSearch(q, facetList, index, offset, limit);
                // 合并 CurseForge 结果：双站都有的条目优先保留 Modrinth（挂上 CF 备用下载源），
                // 仅 CF 有的追加在后。页面不展示来源。CF 源失败不影响 Modrinth 结果。
                try {
                    return mergeCfResults(mr, searchCfMods(6, "mc-mods", q, mcVersion, loader, category, index, offset, limit));
                } catch (Exception ignored) {
                    return mr;
                }
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        });
    }

    /** 搜索整合包/光影/材质包（Modrinth + CurseForge 双源）：kind = modpack | shader | resourcepack */
    public CompletableFuture<ModSearchPage> searchPacksPage(String query, String mcVersion, String kind,
                                                            String index, int offset, int limit) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                List<String> facetList = new ArrayList<>();
                facetList.add("[\"project_type:" + kind + "\"]");
                if (mcVersion != null && !mcVersion.isBlank())
                    facetList.add("[\"versions:" + mcVersion + "\"]");
                String q = (query == null || query.isBlank() || "*".equals(query)) ? "" : query;
                ModSearchPage mr = modrinthSearch(q, facetList, index, offset, limit);
                // CurseForge classId：整合包 4471 / 光影 6552 / 材质包 12；站点路径用于结果链接
                long classId = switch (kind) {
                    case "modpack" -> 4471; case "shader" -> 6552; case "resourcepack" -> 12; default -> 0;
                };
                String sitePath = switch (kind) {
                    case "modpack" -> "modpacks"; case "shader" -> "shaders";
                    case "resourcepack" -> "texture-packs"; default -> "mc-mods";
                };
                try {
                    return mergeCfResults(mr, searchCfMods(classId, sitePath, q, mcVersion, null, "", index, offset, limit));
                } catch (Exception ignored) {
                    return mr;
                }
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        });
    }

    /** Modrinth 单源搜索（同步）：facetList 每项形如 ["project_type:mod"]，由调用方拼好 */
    private ModSearchPage modrinthSearch(String q, List<String> facetList, String index, int offset, int limit) throws Exception {
        // Modrinth facets 是 JSON 数组的数组：外层还要包一层 []
        String facets = facetList.isEmpty() ? ""
                : "&facets=" + java.net.URLEncoder.encode(
                        "[" + String.join(",", facetList) + "]", StandardCharsets.UTF_8);
        String idx = (index == null || index.isBlank()) ? "" : "&index=" + index;
                String url = "https://api.modrinth.com/v2/search?query="
                        + java.net.URLEncoder.encode(q, StandardCharsets.UTF_8)
                        + facets + idx + "&limit=" + limit + "&offset=" + offset;
                HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url)).timeout(Duration.ofSeconds(15)).GET().build();
                HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (resp.statusCode() != 200) throw new IOException("Modrinth HTTP " + resp.statusCode() + " body=" + resp.body());
                JsonNode root = MAPPER.readTree(resp.body());
                JsonNode hits = root.get("hits");
                long total = root.path("total_hits").asLong(0);
                List<ModSearchResult> out = new ArrayList<>();
                if (hits != null && hits.isArray()) {
                    for (JsonNode h : hits) {
                        JsonNode mv = h.get("versions"), ld = h.get("loaders");
                        List<String> vlist = new ArrayList<>(), llist = new ArrayList<>();
                        if (mv != null && mv.isArray()) mv.forEach(n -> vlist.add(n.asText()));
                        if (ld != null && ld.isArray()) ld.forEach(n -> llist.add(n.asText()));
                        JsonNode cats = h.get("categories");
                        List<String> catList = new ArrayList<>();
                        if (cats != null && cats.isArray()) cats.forEach(n -> catList.add(n.asText()));
                        out.add(new ModSearchResult(
                                h.path("project_id").asText(""),
                                h.path("title").asText(""),
                                h.path("description").asText(""),
                                h.path("icon_url").asText(""),
                                "https://modrinth.com/mod/" + h.path("slug").asText(h.path("project_id").asText("")),
                                h.path("author").asText(""),
                                h.path("downloads").asLong(0),
                                h.path("follows").asLong(0),
                                h.path("date_modified").asText(""),
                                catList, vlist, llist, ""));
                    }
                }
        return new ModSearchPage(out, total);
    }

    /** 合并 CurseForge 结果：双站都有的条目优先保留 Modrinth（挂上 CF 备用下载源），仅 CF 有的追加在后 */
    private static ModSearchPage mergeCfResults(ModSearchPage mr, ModSearchPage cf) {
        List<ModSearchResult> cfItems = cf.items();
        if (cfItems.isEmpty()) return mr;
        List<ModSearchResult> out = mr.items();
        Map<String, String> cfByKey = new HashMap<>();
        for (ModSearchResult c : cfItems)
            cfByKey.putIfAbsent(modKey(c.site(), c.title()), c.cfProjectId());
        List<ModSearchResult> merged = new ArrayList<>(out.size() + cfItems.size());
        Set<String> keys = new HashSet<>();
        for (ModSearchResult r : out) {
            String k = modKey(r.site(), r.title());
            keys.add(k);
            String cfId = cfByKey.get(k);
            merged.add(cfId == null || cfId.equals(r.cfProjectId()) ? r
                    : new ModSearchResult(r.projectId(), r.title(), r.summary(), r.iconUrl(),
                            r.site(), r.author(), r.downloads(), r.follows(), r.dateModified(),
                            r.categories(), r.mcVersions(), r.loaders(), cfId));
        }
        for (ModSearchResult c : cfItems)
            if (!keys.contains(modKey(c.site(), c.title()))) merged.add(c);
        return new ModSearchPage(merged, mr.totalHits() + cf.totalHits());
    }
    /** 跨站去重键：优先取站点 URL 尾段（slug），退化为归一化标题 */
    private static String modKey(String site, String title) {
        String slug = site == null ? "" : site;
        int slash = slug.lastIndexOf('/');
        if (slash >= 0 && slash < slug.length() - 1) slug = slug.substring(slash + 1).toLowerCase(Locale.ROOT);
        if (!slug.isBlank()) return "s:" + slug;
        String t = (title == null ? "" : title).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9\\u4e00-\\u9fff]", "");
        return "t:" + t;
    }

    /** 搜索 CurseForge（经 MCIM 的 CurseForge API 代理，免 API Key）。失败抛异常，由调用方降级回 Modrinth 单源。 */
    private ModSearchPage searchCfMods(long classId, String sitePath, String query, String mcVer, String loader, String category, String index,
                                       int offset, int limit) throws Exception {
        StringBuilder sb = new StringBuilder("https://mod.mcimirror.top/curseforge/v1/mods/search?gameId=432&classId=" + classId);
        if (category != null && !category.isBlank()) {
            Long cid = cfCategoryIdFor(category);
            if (cid == null) return new ModSearchPage(List.of(), 0);   // CF 无对应分类：该分类只走 Modrinth
            sb.append("&categoryId=").append(cid);
        }
        String q = (query == null || query.isBlank() || "*".equals(query)) ? "" : query;
        if (!q.isEmpty())
            sb.append("&searchFilter=").append(java.net.URLEncoder.encode(q, StandardCharsets.UTF_8));
        if (mcVer != null && !mcVer.isBlank())
            sb.append("&gameVersion=").append(java.net.URLEncoder.encode(mcVer, StandardCharsets.UTF_8));
        if (loader != null && !loader.isBlank()) {
            int lt = switch (loader.toLowerCase(Locale.ROOT)) {
                case "forge" -> 1; case "fabric" -> 4; case "quilt" -> 5; case "neoforge" -> 6; default -> 0;
            };
            if (lt > 0) sb.append("&modLoaderType=").append(lt);
        }
        int sort = switch (index == null ? "" : index) {
            case "downloads" -> 6;   // CF sortField：2=热度 3=最近更新 6=下载量
            case "updated", "newest" -> 3;
            default -> 2;
        };
        sb.append("&sortField=").append(sort)
          .append("&index=").append(Math.max(0, offset))
          .append("&pageSize=").append(Math.max(1, limit));
        HttpRequest req = HttpRequest.newBuilder().uri(URI.create(sb.toString()))
                .timeout(Duration.ofSeconds(15)).GET().build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() != 200) throw new IOException("CurseForge HTTP " + resp.statusCode());
        JsonNode root = MAPPER.readTree(resp.body());
        List<ModSearchResult> out = new ArrayList<>();
        for (JsonNode d : root.path("data")) {
            List<String> vlist = new ArrayList<>(), llist = new ArrayList<>();
            JsonNode lf = d.path("latestFiles");
            if (lf.isArray() && lf.size() > 0) {
                // 按选中版本展示：优先取该版本的文件信息，没有再退回最新文件
                JsonNode use = lf.get(0);
                if (mcVer != null && !mcVer.isBlank()) {
                    for (JsonNode f : lf) {
                        boolean hit = false;
                        for (JsonNode gv : f.path("gameVersions"))
                            if (mcVer.equals(gv.asText(""))) { hit = true; break; }
                        if (hit) { use = f; break; }
                    }
                }
                for (JsonNode gv : use.path("gameVersions")) {
                    String s = gv.asText("");
                    if (s.matches("\\d+\\.\\d+(\\.\\d+)?")) vlist.add(s);
                    else if (!s.isBlank()) llist.add(s.toLowerCase(Locale.ROOT));
                }
            }
            String slug = d.path("slug").asText(d.path("id").asText(""));
            String author = d.path("authors").isArray() && d.path("authors").size() > 0
                    ? d.path("authors").get(0).path("name").asText("") : "";
            out.add(new ModSearchResult(
                    "",   // CF 条目无 Modrinth projectId，下载走 cfProjectId
                    d.path("name").asText(""),
                    d.path("summary").asText(""),
                    d.path("logo").path("url").asText(""),
                    "https://www.curseforge.com/minecraft/" + sitePath + "/" + slug,
                    author,
                    d.path("downloadCount").asLong(0),
                    0,
                    d.path("dateModified").asText(""),
                    List.of(), vlist, llist,
                    String.valueOf(d.path("id").asLong(0))));
        }
        long total = root.path("pagination").path("totalCount").asLong(out.size());
        return new ModSearchPage(out, total);
    }

    /** Mod 分类表：{中文标签, Modrinth 分类 slug, CF 分类 slug 候选…}（两站共通分类，UI 分类 chips 与搜索过滤共用） */
    public static final String[][] MOD_CATEGORIES = {
            { "冒险", "adventure", "adventure-rpg" },
            { "装饰", "decoration", "decoration" },
            { "装备", "equipment", "equipment" },
            { "食物", "food", "food" },
            { "农业", "farming", "farming" },
            { "游戏机制", "game-mechanics", "game-mechanics" },
            { "库", "library", "api-and-library" },
            { "魔法", "magic", "magic" },
            { "管理", "management", "management" },
            { "小游戏", "minigame", "minigame" },
            { "生物", "mobs", "mobs" },
            { "优化", "optimization", "optimization" },
            { "存储", "storage", "storage" },
            { "科技", "technology", "technology" },
            { "交通", "transportation", "transportation" },
            { "世界生成", "worldgen", "worldgen", "world-gen" },
            { "杂项", "utility", "misc" },
            { "诅咒", "cursed" },
    };

    private static volatile Map<String, Long> cfCatIds;   // CF 分类 slug → id（懒加载缓存）

    /** Modrinth 分类 slug → CF 分类 id：按 MOD_CATEGORIES 的 CF slug 候选匹配 CF 分类表；无映射返回 null */
    private Long cfCategoryIdFor(String modrinthSlug) {
        Map<String, Long> ids = cfCatIds;
        if (ids == null) {
            ids = new HashMap<>();
            try {
                HttpRequest req = HttpRequest.newBuilder().uri(URI.create(
                        "https://mod.mcimirror.top/curseforge/v1/categories?gameId=432&classId=6"))
                        .timeout(Duration.ofSeconds(15)).GET().build();
                HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (resp.statusCode() == 200)
                    for (JsonNode c : MAPPER.readTree(resp.body()).path("data"))
                        ids.put(c.path("slug").asText("").toLowerCase(Locale.ROOT), c.path("id").asLong(0));
            } catch (Exception ignored) { }
            cfCatIds = ids;
        }
        String[] cands = null;
        for (String[] row : MOD_CATEGORIES)
            if (row[1].equals(modrinthSlug)) { cands = Arrays.copyOfRange(row, 2, row.length); break; }
        if (cands == null) return null;
        for (String cand : cands) {
            Long id = ids.get(cand.toLowerCase(Locale.ROOT));
            if (id != null && id > 0) return id;
        }
        return null;
    }

    /** 下载 Mod 图标原始字节。Modrinth 图标多为 webp（JavaFX 不解码），由调用方用 ImageIO 自解码。失败返回 null。 */
    public byte[] fetchIcon(String url) {
        if (url == null || url.isBlank()) return null;
        try {
            HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url))
                    .timeout(Duration.ofSeconds(5)).GET().build();   // 图标小文件 5s 够用，失败下次渲染重试
            HttpResponse<byte[]> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() == 200 && resp.body() != null && resp.body().length > 0) return resp.body();
        } catch (Exception ignored) { }
        return null;
    }

    /** 根据 Modrinth projectId + 游戏版本 + 加载器，找最新可用版本文件的下载 URL。找不到返回 Optional.empty()。 */
    public CompletableFuture<Optional<String>> resolveModDownloadUrl(String projectId, String mcVersion, String loader) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                StringBuilder sb = new StringBuilder("https://api.modrinth.com/v2/project/")
                        .append(projectId).append("/version?");
                List<String> parts = new ArrayList<>();
                // 过滤参数必须是 URL 编码后的 JSON 数组（["1.21.1"] 里 [] 和 " 都是 URI 非法字符）
                if (mcVersion != null && !mcVersion.isBlank())
                    parts.add("game_versions=" + java.net.URLEncoder.encode("[\"" + mcVersion + "\"]", StandardCharsets.UTF_8));
                if (loader != null && !loader.isBlank())
                    parts.add("loaders=" + java.net.URLEncoder.encode("[\"" + loader + "\"]", StandardCharsets.UTF_8));
                sb.append(String.join("&", parts));
                HttpRequest req = HttpRequest.newBuilder().uri(URI.create(sb.toString())).timeout(Duration.ofSeconds(15)).GET().build();
                HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (resp.statusCode() != 200)
                    throw new IOException("Modrinth HTTP " + resp.statusCode());
                JsonNode arr = MAPPER.readTree(resp.body());
                if (!arr.isArray() || arr.size() == 0) return Optional.empty();
                // 选最新一个 (index 0)
                JsonNode files = arr.get(0).get("files");
                if (files == null || !files.isArray() || files.size() == 0) return Optional.empty();
                for (JsonNode f : files) if (f.path("primary").asBoolean(false))
                    return Optional.of(f.path("url").asText());
                return Optional.of(files.get(0).path("url").asText());
            } catch (Exception e) {
                // 把真实错误抛给 UI 层显示，而不是静默变成「无可用版本」
                throw new CompletionException(e);
            }
        });
    }

    /** Modrinth 版本列表项（二级菜单里选版本下载用） */
    public record ModVersion(
            String id, String name, String versionNumber, String datePublished,
            long downloads, List<String> gameVersions, List<String> loaders,
            String fileUrl, String fileName) {}

    /** 拉取项目全部版本列表（/project/{id}/version 不带过滤参数，由用户在二级页自己挑版本）。 */
    public CompletableFuture<List<ModVersion>> listModVersions(String projectId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String url = "https://api.modrinth.com/v2/project/" + projectId + "/version";
                HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url)).timeout(Duration.ofSeconds(15)).GET().build();
                HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (resp.statusCode() != 200) throw new IOException("Modrinth HTTP " + resp.statusCode());
                JsonNode arr = MAPPER.readTree(resp.body());
                List<ModVersion> out = new ArrayList<>();
                if (arr.isArray()) {
                    for (JsonNode vn : arr) {
                        JsonNode files = vn.get("files");
                        if (files == null || !files.isArray() || files.size() == 0) continue;
                        JsonNode file = files.get(0);
                        for (JsonNode f : files) if (f.path("primary").asBoolean(false)) { file = f; break; }
                        JsonNode gvs = vn.get("game_versions"), lds = vn.get("loaders");
                        List<String> gvList = new ArrayList<>(), ldList = new ArrayList<>();
                        if (gvs != null && gvs.isArray()) gvs.forEach(n -> gvList.add(n.asText()));
                        if (lds != null && lds.isArray()) lds.forEach(n -> ldList.add(n.asText()));
                        out.add(new ModVersion(
                                vn.path("id").asText(""),
                                vn.path("name").asText(""),
                                vn.path("version_number").asText(""),
                                vn.path("date_published").asText(""),
                                vn.path("downloads").asLong(0),
                                gvList, ldList,
                                file.path("url").asText(),
                                file.path("filename").asText("")));
                    }
                }
                return out;
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        });
    }

    /** 直接下载一个 URL 到 mods 目录。fileName 从 URL 自动提取（最后的文件名，URL 解码还原 + 等字符）。 */
    public CompletableFuture<Path> downloadModByUrl(String downloadUrl, Path modsDir) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Files.createDirectories(modsDir);
                String fileName = downloadUrl.substring(downloadUrl.lastIndexOf('/') + 1);
                // 去掉 URL query 部分（Modrinth URL 常有 ?token=...）
                int q = fileName.indexOf('?');
                if (q > 0) fileName = fileName.substring(0, q);
                // URL 解码：Modrinth 文件名里的 + 会被编码成 %2B
                fileName = java.net.URLDecoder.decode(fileName, StandardCharsets.UTF_8);
                if (fileName.isBlank()) fileName = "mod-" + System.currentTimeMillis() + ".jar";
                Path target = modsDir.resolve(fileName);
                HttpRequest req = HttpRequest.newBuilder().uri(URI.create(downloadUrl))
                        .timeout(Duration.ofMinutes(3)).GET().build();
                HttpResponse<InputStream> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofInputStream());
                if (resp.statusCode() != 200) throw new IOException("HTTP " + resp.statusCode());
                copyLimited(resp.body(), target);
                monitor.registerDownloaded(target);   // 取消下载时按清单删除
                return target;
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        });
    }

    /* ============ Mod 双源下载（Modrinth 优先，缓慢自动切 CurseForge） ============ */

    /** CF 文件下载 URL 组：CDN 三主机 + 按 ID 寻址的 CurseMaven 兜底 */
    public static List<String> cfFileUrls(long fileId, String fileName, String cfProjectId) {
        String tail = (fileId / 1000) + "/" + (fileId % 1000) + "/" + encodePath(fileName);
        List<String> urls = new ArrayList<>(List.of(
                "https://mediafilez.forgecdn.net/files/" + tail,
                "https://edge.forgecdn.net/files/" + tail,
                "https://media.forgecdn.net/files/" + tail));
        if (cfProjectId != null && !cfProjectId.isBlank())
            urls.add("https://cursemaven.com/curse/maven/x-" + cfProjectId + "/" + fileId
                    + "/x-" + cfProjectId + "-" + fileId + ".jar");
        return urls;
    }

    /** 拉取 CF 项目版本列表（经 MCIM 代理）。CF-only Mod 的二级版本页用；fileUrl 为 CDN 主地址。 */
    public CompletableFuture<List<ModVersion>> listCfModVersions(String cfProjectId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String url = "https://mod.mcimirror.top/curseforge/v1/mods/" + cfProjectId
                        + "/files?index=0&pageSize=50";
                HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url))
                        .timeout(Duration.ofSeconds(15)).GET().build();
                HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (resp.statusCode() != 200) throw new IOException("CurseForge HTTP " + resp.statusCode());
                List<ModVersion> out = new ArrayList<>();
                for (JsonNode d : MAPPER.readTree(resp.body()).path("data")) {
                    String fileName = d.path("fileName").asText("");
                    long fid = d.path("id").asLong(0);
                    if (fileName.isBlank() || fid == 0) continue;
                    List<String> vlist = new ArrayList<>(), llist = new ArrayList<>();
                    for (JsonNode gv : d.path("gameVersions")) {
                        String s = gv.asText("");
                        if (s.matches("\\d+\\.\\d+(\\.\\d+)?")) vlist.add(s);
                        else if (!s.isBlank()) llist.add(s.toLowerCase(Locale.ROOT));
                    }
                    out.add(new ModVersion(
                            String.valueOf(fid),
                            d.path("displayName").asText(fileName),
                            fileName,
                            d.path("fileDate").asText(""),
                            0, vlist, llist,
                            cfFileUrls(fid, fileName, cfProjectId).get(0),
                            fileName));
                }
                return out;
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        });
    }

    /** 解析 CF 项目的备用下载 URL 组：优先文件名含 versionHint 的文件，退化取最新；无结果返回空表 */
    public List<String> cfModDownloadUrls(String cfProjectId, String mcVer, String loader, String versionHint) {
        try {
            StringBuilder sb = new StringBuilder("https://mod.mcimirror.top/curseforge/v1/mods/")
                    .append(cfProjectId).append("/files?index=0&pageSize=50");
            if (mcVer != null && !mcVer.isBlank())
                sb.append("&gameVersion=").append(java.net.URLEncoder.encode(mcVer, StandardCharsets.UTF_8));
            if (loader != null && !loader.isBlank()) {
                int lt = switch (loader.toLowerCase(Locale.ROOT)) {
                    case "forge" -> 1; case "fabric" -> 4; case "quilt" -> 5; case "neoforge" -> 6; default -> 0;
                };
                if (lt > 0) sb.append("&modLoaderType=").append(lt);
            }
            HttpRequest req = HttpRequest.newBuilder().uri(URI.create(sb.toString()))
                    .timeout(Duration.ofSeconds(15)).GET().build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() != 200) return List.of();
            String hint = versionHint == null ? "" : versionHint.toLowerCase(Locale.ROOT);
            JsonNode pick = null;
            for (JsonNode d : MAPPER.readTree(resp.body()).path("data")) {
                if (d.path("fileName").asText("").isBlank() || d.path("id").asLong(0) == 0) continue;
                if (pick == null) pick = d;
                if (!hint.isBlank() && (d.path("fileName").asText("").toLowerCase(Locale.ROOT).contains(hint)
                        || d.path("displayName").asText("").toLowerCase(Locale.ROOT).contains(hint))) {
                    pick = d;
                    break;
                }
            }
            if (pick == null) return List.of();
            return cfFileUrls(pick.path("id").asLong(), pick.path("fileName").asText(""),
                    cfProjectId);
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * 双源智能下载 Mod：按序尝试 primaryUrls（Modrinth 优先），全部走不通再拉 fallbackUrls（CurseForge 备用）。
     * 当前源下载缓慢/停滞且还有后续源时自动切断换下一个源（限速时不判慢，限速慢是用户意图）。
     */
    public CompletableFuture<Path> downloadModSmart(String fileName, Path modsDir,
            List<String> primaryUrls, Supplier<List<String>> fallbackUrls) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Files.createDirectories(modsDir);
                List<String> urls = new ArrayList<>();
                for (String u : primaryUrls) if (u != null && !u.isBlank()) urls.add(u.trim());
                Supplier<List<String>> fbSup = fallbackUrls;
                IOException last = null;
                List<String> slowKilled = new ArrayList<>();   // 曾因“慢/停滞”被强断换源的 URL
                while (!urls.isEmpty()) {
                    String u = urls.remove(0);
                    if (monitor.cancelRequested()) throw new IOException("已取消");
                    String name = fileName;
                    if (name == null || name.isBlank()) {
                        name = u.substring(u.lastIndexOf('/') + 1);
                        int q = name.indexOf('?');
                        if (q > 0) name = name.substring(0, q);
                        name = java.net.URLDecoder.decode(name, StandardCharsets.UTF_8);
                        if (name.isBlank()) name = "mod-" + System.currentTimeMillis() + ".jar";
                    }
                    Path target = modsDir.resolve(name);
                    boolean moreLeft = !urls.isEmpty() || fbSup != null;
                    try {
                        downloadModStream(u, target, moreLeft);
                        monitor.registerDownloaded(target);   // 取消下载时按清单删除
                        return target;
                    } catch (IOException io) {
                        last = io;
                        if (io.getMessage() != null && io.getMessage().contains("下载缓慢")) slowKilled.add(u);
                    }
                    if (urls.isEmpty() && fbSup != null) {
                        List<String> fb = fbSup.get();
                        fbSup = null;   // 备用源只解析一次
                        if (fb != null) for (String f : fb) if (f != null && !f.isBlank()) urls.add(f.trim());
                    }
                }
                // 全部源都走完仍没下成：曾因“慢”被强断的源再给一次机会——这次不判慢
                // （慢速 CDN 慢也把它下完，只保留停滞保护），否则唯一可用的慢源会被永远切死
                for (String u : slowKilled) {
                    if (monitor.cancelRequested()) throw new IOException("已取消");
                    String name = fileName;
                    if (name == null || name.isBlank()) {
                        name = u.substring(u.lastIndexOf('/') + 1);
                        int q = name.indexOf('?');
                        if (q > 0) name = name.substring(0, q);
                        name = java.net.URLDecoder.decode(name, StandardCharsets.UTF_8);
                        if (name.isBlank()) name = "mod-" + System.currentTimeMillis() + ".jar";
                    }
                    Path target = modsDir.resolve(name);
                    try {
                        downloadModStream(u, target, false);
                        monitor.registerDownloaded(target);
                        return target;
                    } catch (IOException io) {
                        last = new IOException("慢速下载重试仍失败：" + io.getMessage(), io);
                    }
                }
                throw last != null ? last : new IOException("无可用下载地址");
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        });
    }

    /** 单 URL 流式下载 Mod 到目标（先写 .part 再改名）。watchSpeed：还有后续源时，缓慢/停滞即强断换源。 */
    private void downloadModStream(String url, Path target, boolean watchSpeed) throws IOException {
        // 大文件优先分片并发：多连接聚合并行带宽（单连接常被 CDN 限成 10KB/s 级涓流），
        // 分片自带断点续传，慢速也不会整文件归零；不支持 Range 的源自动回落单流
        if (downloadChunked(url, target, 300)) return;
        monitor.gate();   // 分片失败若是取消导致的，直接抛出，不再发起单流
        Path part = target.resolveSibling(target.getFileName() + ".part");
        HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url))
                .timeout(Duration.ofMinutes(5)).GET().build();
        HttpResponse<InputStream> resp;
        try {
            resp = HTTP.send(req, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IOException("下载被中断");
        }
        if (resp.statusCode() != 200) throw new IOException("HTTP " + resp.statusCode());
        AtomicLong lastData = new AtomicLong(System.nanoTime());
        AtomicLong got = new AtomicLong();
        AtomicBoolean killed = new AtomicBoolean();
        long start = System.nanoTime();
        boolean checkSpeed = watchSpeed && speedLimitKb <= 0;
        Thread guard = new Thread(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    TimeUnit.SECONDS.sleep(2);
                    long now = System.nanoTime();
                    boolean stall = now - lastData.get() > TimeUnit.SECONDS.toNanos(20);
                    double avgKbs = got.get() / 1024.0 / Math.max(0.001, (now - start) / 1e9);
                    boolean slow = checkSpeed && now - start > TimeUnit.SECONDS.toNanos(8)
                            && got.get() < 8L * 1024 * 1024 && avgKbs < 40;
                    if (monitor.cancelRequested() || stall || slow) {
                        killed.set(true);
                        try { resp.body().close(); } catch (Exception ignored) { }
                        return;
                    }
                }
            } catch (InterruptedException ignored) { }
        }, "yhcl-mod-watch");
        guard.setDaemon(true);
        guard.start();
        try (InputStream in = resp.body();
             OutputStream out = Files.newOutputStream(part,
                     StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            byte[] buf = new byte[64 * 1024];
            while (true) {
                monitor.gate();   // 暂停期间挂起，取消时抛出
                int n = in.read(buf);
                if (n <= 0) break;
                lastData.set(System.nanoTime());
                got.addAndGet(n);
                limiter.acquire(n);
                out.write(buf, 0, n);
                monitor.setItemBytes(part, got.get());   // 实时字节进度（速度/ETA 显示）
            }
        } catch (IOException io) {
            Files.deleteIfExists(part);
            if (monitor.cancelRequested()) throw new IOException("下载已取消");
            if (killed.get()) throw new IOException("下载缓慢/停滞，自动切换下载源：" + target.getFileName());
            throw io;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            Files.deleteIfExists(part);
            throw new IOException("下载被中断");
        } finally {
            guard.interrupt();
        }
        if (killed.get()) {
            Files.deleteIfExists(part);
            if (monitor.cancelRequested()) throw new IOException("下载已取消");
            throw new IOException("下载缓慢/停滞，自动切换下载源：" + target.getFileName());
        }
        Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
        monitor.settleBytes(part, target, Files.size(target));   // 字节进度归账到最终文件
    }

    /* ============ 整合包（.mrpack / zip）拖入自动安装 ============ */

    /** 整合包安装结果：下载成功/失败数、overrides 解压数、依赖描述、安装好的版本 id（没有则空） */
    public record ModpackResult(int installed, int failed, int overrides, String dependencies, String versionId) {}

    /**
     * 打开 zip。国内整合包常有 GBK 编码的条目名且未设置 UTF-8 标志，
     * JDK 21 的 ZipFile 严格校验会直接抛 "invalid CEN header"（整包被判无法识别），
     * 这里失败后改用 GBK 解码重开一次；带 UTF-8 标志的条目仍按 UTF-8 解析。
     */
    private static ZipFile openZip(File f) throws IOException {
        try {
            return new ZipFile(f);
        } catch (java.util.zip.ZipException ex) {
            try {
                return new ZipFile(f, java.nio.charset.Charset.forName("GBK"));
            } catch (Exception retry) {
                throw ex;
            }
        }
    }

    /** 是否为整合包压缩包：.mrpack，或含 modrinth.index.json / modpack.mrpack / manifest.json 的 zip，
     *  或 PCL 全量游戏包（.minecraft/versions/&lt;版本名&gt;/ 整包结构的懒人包） */
    public static boolean looksLikeModpack(Path p) {
        String n = p.getFileName().toString().toLowerCase();
        if (n.endsWith(".mrpack")) return true;
        if (!n.endsWith(".zip")) return false;
        try (ZipFile zf = openZip(p.toFile())) {
            return zf.getEntry("modrinth.index.json") != null
                    || zf.getEntry("modpack.mrpack") != null
                    || zf.getEntry("manifest.json") != null
                    || findFullPackVersion(zf) != null;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 安装整合包压缩包到当前游戏目录（接入下载监控，悬浮球/详情页可见进度）：
     *  - Modrinth 格式（.mrpack 或含 modrinth.index.json 的 zip）：按索引并行下载文件（SHA1 校验）+ 解压 overrides
     *  - CurseForge 格式（manifest.json）：解析文件清单下载 mods + 解压 overrides + 安装加载器生成版本
     *  - PCL 全量游戏包（versions/&lt;版本名&gt;/ 整包结构）：版本 json+jar+游戏目录内容整体解压，合并 assets/libraries
     *  - 其他 zip：解压其中的 mods/config/resourcepacks/shaderpacks 目录
     */
    public CompletableFuture<ModpackResult> installModpack(Path archive, Consumer<String> log) {
        return CompletableFuture.supplyAsync(() -> {
            ModpackResult res;
            try (ZipFile zf = openZip(archive.toFile())) {
                ZipEntry index = zf.getEntry("modrinth.index.json");
                if (index != null) {
                    res = installModrinthPack(zf, index, log);
                } else if (zf.getEntry("modpack.mrpack") != null) {
                    // PCL2 导出的整合包：zip 内嵌 Modrinth 格式的 modpack.mrpack，解出来后按 Modrinth 管线安装
                    Path tmp = Files.createTempFile("yhcl-pcl-", ".mrpack");
                    try (InputStream in = zf.getInputStream(zf.getEntry("modpack.mrpack"))) {
                        Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
                    }
                    try (ZipFile inner = openZip(tmp.toFile())) {
                        ZipEntry idx = inner.getEntry("modrinth.index.json");
                        if (idx == null) throw new IOException("内置 modpack.mrpack 缺少 modrinth.index.json");
                        res = installModrinthPack(inner, idx, log);
                    } finally {
                        try { Files.deleteIfExists(tmp); } catch (IOException ignored) { }
                    }
                } else if (zf.getEntry("manifest.json") != null) {
                    res = installCurseForgePack(zf, zf.getEntry("manifest.json"), log);
                } else {
                    String fullVer = findFullPackVersion(zf);
                    res = fullVer != null ? installFullGamePack(zf, fullVer, log) : installPlainZip(zf, log);
                }
            } catch (Exception e) {
                monitor.finishFail(e.getMessage() == null ? e.toString() : e.getMessage());
                throw new CompletionException(e instanceof IOException io ? io : new IOException(e.getMessage(), e));
            }
            if (res.failed() > 0) monitor.finishFail(res.failed() + " 个文件下载失败");
            else monitor.finishOk();
            return res;
        });
    }

    /**
     * 整合包先安装加载器生成可启动版本，拿到真实 versionId——整合包默认开版本隔离，
     * mods/overrides 必须落到 versions/&lt;id&gt;/ 独立目录，故在下载文件前先装好加载器。
     * 返回 versionId；不支持的加载器（如 quilt）或安装失败返回空串，并把提示并入 depText。
     */
    private String packInstallLoader(String mcVer, String loaderKind, String loaderVer, String loaderId,
                                     StringBuilder depText, Consumer<String> log) {
        boolean supported = "forge".equals(loaderKind) || "neoforge".equals(loaderKind)
                || "fabric".equals(loaderKind);
        if (supported && !mcVer.isBlank()) {
            // 已装过的加载器版本直接复用，不重复下载
            String id1 = mcVer + "-" + loaderKind + "-" + loaderVer;
            String id2 = loaderKind + "-" + loaderVer;
            String id3 = mcVer + "-" + loaderKind + loaderVer;   // 旧版 Forge 无连字符：1.12.2-forge14.23.5.2854
            List<String> installed = getInstalled();
            if (installed.contains(id1)) return id1;
            if (installed.contains(id2)) return id2;
            if (installed.contains(id3)) return id3;
            monitor.setPhase(Phase.INSTALL, "安装加载器 " + loaderId);
            try {
                return installLoader(loaderKind, mcVer, loaderVer, log, d -> { }, monitor::setNote);
            } catch (Exception ex) {
                log.accept("加载器安装失败：" + ex.getMessage() + "（可稍后在版本详情页手动安装 " + loaderId + "）");
                depText.append("（加载器自动安装失败，请手动安装 ").append(loaderId).append("）");
                return "";
            }
        }
        if (!loaderId.isBlank())
            depText.append("（请手动安装加载器 ").append(loaderId).append("）");
        return "";
    }

    /** 整合包版本目录名：取整合包名，替换 Windows 非法字符、压缩空白并截断 */
    private static String packDirName(String name) {
        if (name == null) return "";
        String s = name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", " ")
                .replaceAll("\\s+", " ").trim();
        while (s.endsWith(".")) s = s.substring(0, s.length() - 1).trim();
        if (s.length() > 48) s = s.substring(0, 48).trim();
        return s;
    }

    /** 已按整合包名装好的版本（加载器与 MC 版本都对得上）直接复用，避免重装时再跑一遍安装器 */
    private String packReusableId(String packId, String mcVer, String loaderKind) {
        if (packId.isBlank()) return "";
        Path json = gameDir.resolve("versions").resolve(packId).resolve(packId + ".json");
        if (!Files.isRegularFile(json)) return "";
        try {
            JsonNode n = MAPPER.readTree(json.toFile());
            String kind = n.path("yhclLoader").asText("");
            // 加载器版本被 cleanupRedundantVanilla 合并成自包含 json 后会去掉 inheritsFrom，
            // 所以额外记一份原版版本号
            String parent = n.path("yhclMc").asText(n.path("inheritsFrom").asText(""));
            if (kind.equalsIgnoreCase(loaderKind) && (mcVer.isBlank() || parent.equals(mcVer))) return packId;
        } catch (Exception ignored) { }
        return "";
    }

    /**
     * 把安装器产出的加载器版本目录改名为整合包名：目录里以版本 id 开头的文件（json/jar）
     * 一并改名，json 的 id 同步改掉（启动器按 id 找 json 和 jar），并记下 yhclLoader 供界面分组。
     * 失败时保持原目录名，不影响安装结果。
     */
    private String renameVersionToPack(String versionId, String packId, String loaderKind, String mcVer,
                                       Consumer<String> log) {
        if (packId.isBlank() || packId.equals(versionId)) return versionId;
        Path versions = gameDir.resolve("versions");
        Path src = versions.resolve(versionId);
        Path dst = versions.resolve(packId);
        try {
            if (!Files.isRegularFile(src.resolve(versionId + ".json"))) return versionId;
            deleteQuietly(dst);
            Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING);
            // 目录内以旧 id 命名的文件（<id>.json / Forge 的 <id>.jar 等）统一改成新名字
            try (var list = Files.list(dst)) {
                for (Path p : list.toList()) {
                    String fn = p.getFileName().toString();
                    if (fn.startsWith(versionId) && fn.length() > versionId.length()) {
                        Path np = dst.resolve(packId + fn.substring(versionId.length()));
                        try { Files.move(p, np, StandardCopyOption.REPLACE_EXISTING); } catch (IOException ignored) { }
                    }
                }
            }
            Path newJson = dst.resolve(packId + ".json");
            JsonNode node = MAPPER.readTree(newJson.toFile());
            if (!(node instanceof ObjectNode on)) return versionId;
            on.put("id", packId);
            if (!loaderKind.isBlank()) on.put("yhclLoader", loaderKind);
            if (!mcVer.isBlank()) on.put("yhclMc", mcVer);
            MAPPER.writeValue(newJson.toFile(), on);
            log.accept("版本目录已命名为整合包名：" + packId);
            return packId;
        } catch (Exception ex) {
            log.accept("整合包目录改名失败（沿用加载器版本名）：" + ex.getMessage());
            return versionId;
        }
    }

    /** Modrinth 整合包：解析 modrinth.index.json → 并行下载 → 校验 → 解压 overrides */
    private ModpackResult installModrinthPack(ZipFile zf, ZipEntry index, Consumer<String> log) throws Exception {
        JsonNode root = MAPPER.readTree(zf.getInputStream(index));
        log.accept("整合包：" + root.path("name").asText("(未命名)") + "  " + root.path("summary").asText(""));

        JsonNode deps = root.get("dependencies");
        List<String> depList = new ArrayList<>();
        if (deps != null && deps.isObject()) {
            if (!deps.path("minecraft").asText("").isBlank()) depList.add("Minecraft " + deps.path("minecraft").asText());
            if (!deps.path("fabric-loader").asText("").isBlank()) depList.add("Fabric " + deps.path("fabric-loader").asText());
            if (!deps.path("forge").asText("").isBlank()) depList.add("Forge " + deps.path("forge").asText());
            if (!deps.path("neoforge").asText("").isBlank()) depList.add("NeoForge " + deps.path("neoforge").asText());
            if (!deps.path("quilt-loader").asText("").isBlank()) depList.add("Quilt " + deps.path("quilt-loader").asText());
        }
        StringBuilder depText = new StringBuilder(String.join(" · ", depList));
        if (!depText.isEmpty()) log.accept("整合包依赖：" + depText + "（请确认已安装对应版本）");

        // 从依赖里取 MC 版本 + 加载器（一个整合包只带一个加载器）
        String mcVer = deps == null ? "" : deps.path("minecraft").asText("");
        String loaderKind = "", loaderVer = "", loaderId = "";
        if (deps != null && deps.isObject()) {
            if (!deps.path("neoforge").asText("").isBlank())         { loaderKind = "neoforge"; loaderVer = deps.path("neoforge").asText(); }
            else if (!deps.path("forge").asText("").isBlank())        { loaderKind = "forge";    loaderVer = deps.path("forge").asText(); }
            else if (!deps.path("fabric-loader").asText("").isBlank()){ loaderKind = "fabric";   loaderVer = deps.path("fabric-loader").asText(); }
            else if (!deps.path("quilt-loader").asText("").isBlank()) { loaderKind = "quilt";    loaderVer = deps.path("quilt-loader").asText(); }
            if (!loaderKind.isBlank()) loaderId = loaderKind + "-" + loaderVer;
        }

        // 先装加载器拿到真实 versionId，后续 mods/overrides 都落到 versions/<id>/（版本隔离目录）；
        // 版本目录最终以整合包名命名，重装同一个整合包时直接复用，不再跑一遍安装器
        String packId = packDirName(root.path("name").asText(""));
        String versionId = packReusableId(packId, mcVer, loaderKind);
        if (!versionId.isBlank()) {
            log.accept("复用已安装的整合包版本：" + versionId);
        } else {
            versionId = packInstallLoader(mcVer, loaderKind, loaderVer, loaderId, depText, log);
            if (!versionId.isBlank()) versionId = renameVersionToPack(versionId, packId, loaderKind, mcVer, log);
        }
        Path vbase = versionId.isBlank() ? gameDir : gameDir.resolve("versions").resolve(versionId);

        // 解析待下载文件（跳过 client=unsupported 的项）
        List<String[]> pending = new ArrayList<>();   // {显示名, 相对路径, 多URL(换行分隔), sha1}
        JsonNode files = root.get("files");
        if (files != null && files.isArray()) {
            for (JsonNode f : files) {
                JsonNode env = f.get("env");
                if (env != null && "unsupported".equalsIgnoreCase(env.path("client").asText("required"))) continue;
                String rel = f.path("path").asText("");
                List<String> urls = new ArrayList<>();
                JsonNode dl = f.get("downloads");
                if (dl != null && dl.isArray()) dl.forEach(n -> urls.add(n.asText()));
                if (rel.isBlank() || urls.isEmpty()) continue;
                pending.add(new String[]{
                        rel.substring(rel.lastIndexOf('/') + 1), rel,
                        String.join("\n", urls),
                        f.path("hashes").path("sha1").asText(""),
                        Long.toString(f.path("fileSize").asLong(-1))});
            }
        }
        log.accept("待下载文件：" + pending.size() + " 个");

        monitor.setPhase(Phase.DOWNLOAD, "下载整合包文件");
        int base = monitor.addBatch(pending.stream().map(p -> p[0]).toList());
        AtomicInteger ok = new AtomicInteger(), bad = new AtomicInteger();
        if (!pending.isEmpty()) {
            ExecutorService pool = Executors.newFixedThreadPool(Math.min(dlThreads(), pending.size()));
            CountDownLatch latch = new CountDownLatch(pending.size());
            for (int i = 0; i < pending.size(); i++) {
                final int idx = base + i;
                final String[] p = pending.get(i);
                pool.submit(() -> {
                    try {
                        if (monitor.cancelRequested()) { monitor.markWaiting(idx); return; }
                        monitor.markDownloading(idx);
                        Path target = vbase.resolve(p[1]).normalize();
                        if (!target.startsWith(vbase)) throw new IOException("非法路径：" + p[1]);
                        downloadPackFile(p[2].split("\n"), p[3], Long.parseLong(p[4]), target);
                        monitor.markDone(idx, target);
                        ok.incrementAndGet();
                    } catch (Exception ex) {
                        monitor.markFailed(idx, ex.getMessage() == null ? ex.toString() : ex.getMessage());
                        bad.incrementAndGet();
                    } finally {
                        latch.countDown();
                    }
                });
            }
            latch.await();
            pool.shutdown();
        }

        // 解压 overrides/ 到游戏目录
        monitor.setPhase(Phase.INSTALL, "解压 overrides");
        int overrides = 0;
        Enumeration<? extends ZipEntry> en = zf.entries();
        while (en.hasMoreElements()) {
            ZipEntry e = en.nextElement();
            String n = e.getName();
            if (e.isDirectory() || !n.startsWith("overrides/")) continue;
            String sub = n.substring("overrides/".length());
            if (sub.isBlank()) continue;
            Path target = vbase.resolve(sub).normalize();
            if (!target.startsWith(vbase)) continue;
            Files.createDirectories(target.getParent());
            try (InputStream in = zf.getInputStream(e)) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
            overrides++;
        }
        log.accept("overrides 解压：" + overrides + " 个文件");

        if (monitor.cancelRequested()) throw new IOException("已取消");
        return new ModpackResult(ok.get(), bad.get(), overrides, depText.toString(), versionId);
    }

    /** CurseForge 文件解析结果：文件名 + 字节数（未知为 -1）+ SHA1（未知为空） */
    private record CfFile(String name, long size, String sha1) {}

    /** CurseForge 待下载文件：显示名 + 候选 URL + 预期大小 + SHA1 */
    private record CfJob(String name, String[] urls, long size, String sha1) {}

    /**
     * CurseForge 整合包（manifest.json 格式，CurseForge App 导出）：
     *  1. 读清单拿 Minecraft 版本、加载器（forge-xx / fabric-xx / neoforge-xx）和文件列表（projectID + fileID）
     *  2. 解析文件名后经 CurseForge CDN 下载 mods/：CFWidget 批量解析 → 老文件缺失走 MCIM 的
     *     CurseForge API 代理补解析（带 SHA1/大小）→ 仍无名字则 CurseMaven 按 ID 兜底下载
     *  3. 解压 overrides 覆盖游戏目录（防 zip 路径穿越）
     *  4. 复用加载器安装管线生成可启动版本；加载器失败不影响已下载的 mods/overrides，只记入依赖提示
     */
    private ModpackResult installCurseForgePack(ZipFile zf, ZipEntry manifest, Consumer<String> log) throws Exception {
        JsonNode root = MAPPER.readTree(zf.getInputStream(manifest));
        log.accept("CurseForge 整合包：" + root.path("name").asText("(未命名)")
                + "  " + root.path("version").asText(""));

        JsonNode mcNode = root.path("minecraft");
        String mcVer = mcNode.path("version").asText("");
        // modLoaders 的 id 形如 forge-47.2.0 / fabric-0.15.11 / neoforge-20.4.x，优先取 primary
        String loaderId = "";
        for (JsonNode l : mcNode.path("modLoaders")) {
            if (loaderId.isBlank() || l.path("primary").asBoolean(false)) loaderId = l.path("id").asText("");
            if (l.path("primary").asBoolean(false)) break;
        }
        String loaderKind = "", loaderVer = "";
        int dash = loaderId.indexOf('-');
        if (dash > 0) {
            loaderKind = loaderId.substring(0, dash).toLowerCase(Locale.ROOT);
            loaderVer = loaderId.substring(dash + 1);
        }
        List<String> depList = new ArrayList<>();
        if (!mcVer.isBlank()) depList.add("Minecraft " + mcVer);
        if (!loaderId.isBlank()) depList.add(loaderId);
        StringBuilder depText = new StringBuilder(String.join(" · ", depList));
        if (!depText.isEmpty()) log.accept("整合包依赖：" + depText);

        // 先装加载器拿到真实 versionId，后续 mods/overrides 都落到 versions/<id>/（版本隔离目录）；
        // 版本目录最终以整合包名命名，重装同一个整合包时直接复用
        String packId = packDirName(root.path("name").asText(""));
        String versionId = packReusableId(packId, mcVer, loaderKind);
        if (!versionId.isBlank()) {
            log.accept("复用已安装的整合包版本：" + versionId);
        } else {
            versionId = packInstallLoader(mcVer, loaderKind, loaderVer, loaderId, depText, log);
            if (!versionId.isBlank()) versionId = renameVersionToPack(versionId, packId, loaderKind, mcVer, log);
        }
        Path vbase = versionId.isBlank() ? gameDir : gameDir.resolve("versions").resolve(versionId);

        List<JsonNode> wanted = new ArrayList<>();
        for (JsonNode f : root.path("files")) wanted.add(f);
        log.accept("待下载文件：" + wanted.size() + " 个");

        // CFWidget 免 API Key 解析 fileID → 文件名/大小：按 projectID 只请求一次
        monitor.setPhase(Phase.PREPARE, "解析整合包文件");
        Map<Long, Map<Long, CfFile>> byProject = new ConcurrentHashMap<>();
        Set<Long> projectIds = new LinkedHashSet<>();
        for (JsonNode f : wanted) projectIds.add(f.path("projectID").asLong());
        if (!projectIds.isEmpty()) {
            ExecutorService resolver = Executors.newFixedThreadPool(Math.min(4, projectIds.size()));
            CountDownLatch rl = new CountDownLatch(projectIds.size());
            for (Long pid : projectIds) {
                resolver.submit(() -> {
                    try {
                        byProject.put(pid, fetchCfFiles(pid));
                    } finally {
                        rl.countDown();
                    }
                });
            }
            rl.await();
            resolver.shutdown();
        }

        List<CfJob> pending = new ArrayList<>();
        for (JsonNode f : wanted) {
            long pid = f.path("projectID").asLong();
            long fid = f.path("fileID").asLong();
            CfFile cf = byProject.getOrDefault(pid, Map.of()).get(fid);
            if (cf == null || cf.name().isBlank()) {
                // CFWidget 对老文件收录不全（只缓存部分文件），用 MCIM 的 CurseForge API 代理按 fileID 补解析
                cf = fetchCfFileViaApi(pid, fid);
            }
            String name;
            long size = -1;
            String sha1 = "";
            if (cf != null && !cf.name().isBlank()) {
                // 只取文件名部分，防止清单里塞路径写到游戏目录外面
                name = new File(cf.name()).getName();
                size = cf.size();
                sha1 = cf.sha1();
            } else {
                // 两个解析源都没名字：CurseMaven 按 ID 兜底下载，游戏不关心 mod 文件名
                name = "cf-" + fid + ".jar";
                log.accept("未解析到文件名，按 ID 兜底下载：" + name + "（projectID=" + pid + "）");
            }
            String tail = (fid / 1000) + "/" + (fid % 1000) + "/" + encodePath(name);
            pending.add(new CfJob(name, new String[]{
                    "https://mediafilez.forgecdn.net/files/" + tail,
                    "https://edge.forgecdn.net/files/" + tail,
                    "https://media.forgecdn.net/files/" + tail,
                    // CurseMaven 按 projectID/fileID 寻址，文件名只是装饰，可作最终兜底
                    "https://cursemaven.com/curse/maven/x-" + pid + "/" + fid + "/x-" + pid + "-" + fid + ".jar"},
                    size, sha1));
        }

        monitor.setPhase(Phase.DOWNLOAD, "下载整合包 mods");
        int base = monitor.addBatch(pending.stream().map(CfJob::name).toList());
        AtomicInteger ok = new AtomicInteger(), bad = new AtomicInteger();
        if (!pending.isEmpty()) {
            ExecutorService pool = Executors.newFixedThreadPool(Math.min(dlThreads(), pending.size()));
            CountDownLatch latch = new CountDownLatch(pending.size());
            for (int i = 0; i < pending.size(); i++) {
                final int idx = base + i;
                final CfJob p = pending.get(i);
                pool.submit(() -> {
                    try {
                        if (monitor.cancelRequested()) { monitor.markWaiting(idx); return; }
                        monitor.markDownloading(idx);
                        Path target = vbase.resolve("mods").resolve(p.name()).normalize();
                        if (!target.startsWith(vbase)) throw new IOException("非法路径：" + p.name());
                        // 重装/补装：已下载过且大小一致的直接跳过，不用全部重下
                        if (Files.exists(target) && (p.size() < 0 || Files.size(target) == p.size())) {
                            monitor.markDone(idx, target);
                            ok.incrementAndGet();
                            return;
                        }
                        downloadPackFile(p.urls(), p.sha1(), p.size(), target);
                        monitor.markDone(idx, target);
                        ok.incrementAndGet();
                    } catch (Exception ex) {
                        monitor.markFailed(idx, ex.getMessage() == null ? ex.toString() : ex.getMessage());
                        bad.incrementAndGet();
                    } finally {
                        latch.countDown();
                    }
                });
            }
            latch.await();
            pool.shutdown();
        }
        log.accept("mods 下载：" + ok.get() + "/" + pending.size() + " 个");

        // 解压 overrides/（目录名以清单为准）到游戏目录
        String ovPrefix = root.path("overrides").asText("overrides");
        while (ovPrefix.endsWith("/")) ovPrefix = ovPrefix.substring(0, ovPrefix.length() - 1);
        if (ovPrefix.isBlank()) ovPrefix = "overrides";
        String want = ovPrefix + "/";
        monitor.setPhase(Phase.INSTALL, "解压 overrides");
        int overrides = 0;
        Enumeration<? extends ZipEntry> en = zf.entries();
        while (en.hasMoreElements()) {
            ZipEntry e = en.nextElement();
            // Windows 工具打的 zip 条目名可能用反斜杠，统一归一成 / 再匹配
            String n = e.getName().replace('\\', '/');
            if (e.isDirectory() || !n.startsWith(want)) continue;
            String sub = n.substring(want.length());
            if (sub.isBlank()) continue;
            Path target = vbase.resolve(sub).normalize();
            if (!target.startsWith(vbase)) continue;
            Files.createDirectories(target.getParent());
            try (InputStream in = zf.getInputStream(e)) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
            overrides++;
        }
        log.accept("overrides 解压：" + overrides + " 个文件");

        if (monitor.cancelRequested()) throw new IOException("已取消");
        return new ModpackResult(ok.get(), bad.get(), overrides, depText.toString(), versionId);
    }

    /** 用 CFWidget（免 API Key）拿项目全部文件的 id → 文件名/大小；解析失败返回空表，由调用方走补解析 */
    private Map<Long, CfFile> fetchCfFiles(long projectId) {
        Map<Long, CfFile> out = new HashMap<>();
        try {
            JsonNode root = MAPPER.readTree(getText("https://api.cfwidget.com/" + projectId));
            for (JsonNode f : root.path("files")) {
                out.put(f.path("id").asLong(),
                        new CfFile(f.path("name").asText(""), f.path("filesize").asLong(-1), ""));
            }
        } catch (Exception e) {
            // 解析失败留空即可：具体文件走 fetchCfFileViaApi 补解析
        }
        return out;
    }

    /** CFWidget 拿不到（老文件收录不全）时，用 MCIM 的 CurseForge API 代理按 fileID 补解析；失败返回 null */
    private CfFile fetchCfFileViaApi(long projectId, long fileId) {
        try {
            JsonNode d = MAPPER.readTree(getText(
                    "https://mod.mcimirror.top/curseforge/v1/mods/" + projectId + "/files/" + fileId))
                    .path("data");
            String name = d.path("fileName").asText("");
            if (name.isBlank()) return null;
            String sha1 = "";
            for (JsonNode h : d.path("hashes")) {
                if (h.path("algo").asInt() == 1) sha1 = h.path("value").asText("");
            }
            return new CfFile(name, d.path("fileLength").asLong(-1), sha1);
        } catch (Exception e) {
            return null;
        }
    }

    /** 文件名拼进 URL path：空格等按 %XX 转义（URLEncoder 会把空格编成 +，path 里要换回 %20） */
    private static String encodePath(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /**
     * 整合包单个文件下载：按候选源逐个尝试（含断源重试一轮），校验 SHA1/大小后落位。
     * 已知大小且不需要分片的小文件不再额外发一次 Range 探测请求（每个文件省一次往返）。
     */
    private void downloadPackFile(String[] urls, String sha1, long expectSize, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        List<String> cands = new ArrayList<>();
        for (String u : urls) {
            String t = u == null ? "" : u.trim();
            if (!t.isEmpty() && !cands.contains(t)) cands.add(t);
        }
        if (cands.isEmpty()) throw new IOException("无可用下载地址");
        boolean smallKnown = expectSize > 0 && expectSize < CHUNK_MIN;
        IOException last = null;
        for (int round = 0; round < 2; round++) {
            for (String u : cands) {
                if (monitor.cancelRequested()) throw new IOException("已取消");
                Path part = target.resolveSibling(target.getFileName() + ".part");
                try {
                    monitor.gate();   // 暂停闸门
                    // 大文件优先分片并发下载（多连接聚合并行带宽 + 断点续传），
                    // 不支持 Range 的源自动回落单流；已知是小文件就直接走单流
                    if (smallKnown || !downloadChunked(u, part, 30)) {
                        HttpRequest req = HttpRequest.newBuilder().uri(URI.create(u))
                                .timeout(Duration.ofSeconds(30)).GET().build();
                        // 流式落盘（.part 再改名）：大文件不受「整包读进内存 + 30 秒总超时」限制，
                        // 走 copyLimited 还带实时字节进度（速度/ETA）、限速与停滞保护
                        HttpResponse<InputStream> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofInputStream());
                        if (resp.statusCode() != 200) {
                            try { resp.body().close(); } catch (Exception ignored) { }
                            last = new IOException("HTTP " + resp.statusCode());
                            continue;
                        }
                        copyLimited(resp.body(), part, false);
                    }
                    if (sha1 != null && !sha1.isBlank() && !sha1HexFile(part).equals(sha1.toLowerCase())) {
                        Files.deleteIfExists(part);
                        last = new IOException("SHA1 校验失败");
                        continue;
                    }
                    long size = Files.size(part);
                    if (expectSize > 0 && size != expectSize) {
                        Files.deleteIfExists(part);
                        last = new IOException("文件大小不符（" + size + " ≠ " + expectSize + "）");
                        continue;
                    }
                    Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
                    monitor.settleBytes(part, target, size);
                    monitor.registerDownloaded(target);   // 取消下载时按清单删除
                    return;
                } catch (IOException io) {
                    last = io;
                    try { Files.deleteIfExists(part); } catch (IOException ignored) { }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IOException("下载被中断");
                }
            }
        }
        throw last != null ? last : new IOException("无可用下载地址");
    }

    /** 流式算文件 SHA1（大文件不整口读进内存） */
    private static String sha1HexFile(Path p) {
        try (InputStream in = Files.newInputStream(p)) {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            StringBuilder sb = new StringBuilder(40);
            for (byte b : md.digest()) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /** 普通压缩包：解压其中 mods/config/resourcepacks/shaderpacks 目录到游戏目录 */
    private ModpackResult installPlainZip(ZipFile zf, Consumer<String> log) throws IOException {
        String[] prefixes = {"mods/", "config/", "resourcepacks/", "shaderpacks/"};
        int count = 0;
        Enumeration<? extends ZipEntry> en = zf.entries();
        while (en.hasMoreElements()) {
            ZipEntry e = en.nextElement();
            if (e.isDirectory()) continue;
            String n = e.getName();
            if (n.startsWith(".minecraft/")) n = n.substring(".minecraft/".length());
            boolean hit = false;
            for (String p : prefixes) if (n.startsWith(p)) { hit = true; break; }
            if (!hit) continue;
            Path target = gameDir.resolve(n).normalize();
            if (!target.startsWith(gameDir)) continue;
            Files.createDirectories(target.getParent());
            try (InputStream in = zf.getInputStream(e)) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
            count++;
        }
        log.accept("普通压缩包：解压 " + count + " 个文件（仅 mods/config/资源包/光影包 目录）");
        return new ModpackResult(count, 0, count, "", "");
    }

    /** 归一化 zip 条目名：反斜杠转正斜杠 */
    private static String zipName(ZipEntry e) {
        return e.getName().replace('\\', '/');
    }

    /**
     * PCL 全量游戏包（懒人包）检测：zip 内 .minecraft/versions/&lt;版本名&gt;/&lt;版本名&gt;.json 结构
     * （版本 json+jar 与游戏目录内容混放在版本目录里，配套全量 assets/、libraries/）。
     * 返回主版本目录名，优先取「json 与目录同名且非纯数字原版号」的目录（整合包版本）；不是该格式返回 null。
     */
    private static String findFullPackVersion(ZipFile zf) {
        Pattern p = Pattern.compile("^(?:\\.?minecraft/)?versions/([^/]+)/([^/]+)\\.json$");
        String named = null, first = null;
        Enumeration<? extends ZipEntry> en = zf.entries();
        while (en.hasMoreElements()) {
            ZipEntry e = en.nextElement();
            if (e.isDirectory()) continue;
            Matcher m = p.matcher(zipName(e));
            if (!m.matches()) continue;
            String dir = m.group(1), base = m.group(2);
            if (first == null) first = dir;
            if (dir.equals(base)) {
                if (!dir.matches("\\d+(\\.\\d+)*")) return dir;
                if (named == null) named = dir;
            }
        }
        return named != null ? named : first;
    }

    /** 全量游戏包版本目录里的运行垃圾：PCL 缓存、作者日志/崩溃报告/JVM 崩溃转储/用户缓存 */
    private static boolean fullPackJunk(String rel) {
        int slash = rel.indexOf('/');
        String first = slash < 0 ? rel : rel.substring(0, slash);
        return first.equals("logs") || first.equals("crash-reports") || first.equals("PCL")
                || first.endsWith("-natives") || first.startsWith("hs_err")
                || rel.equals("usercache.json") || rel.equals("usernamecache.json");
    }

    /**
     * PCL 全量游戏包（懒人包）安装：
     *  - versions/&lt;包名&gt;/（版本 json+jar + mods/config/saves 等游戏目录内容）整体解到
     *    gameDir/versions/&lt;包名&gt;/——与整合包版本隔离目录布局一致，装好后开版本隔离即可直接玩；
     *    顺带解出 inheritsFrom 指向的原版版本目录
     *  - assets/、libraries/ 合并到游戏目录（内容寻址，已存在且大小一致的跳过）
     *  - 根目录游戏内容（mods/config 等旧式懒人包布局）解到该版本的隔离目录
     *  - 跳过 PCL 启动器自身文件、launcher_profiles.json（含他人登录凭证）与运行垃圾
     * 版本 json/jar 统一改名为 &lt;包名&gt;.json/.jar（启动器按 &lt;id&gt;/&lt;id&gt;.json 定位），并同步 json 的 id 字段。
     */
    private ModpackResult installFullGamePack(ZipFile zf, String verName, Consumer<String> log) throws IOException {
        String safeName = packDirName(verName);
        if (safeName.isBlank()) safeName = verName;
        Path isoDir = gameDir.resolve("versions").resolve(safeName);   // 根目录内容的落点（版本隔离目录）
        String[] rootDirs = {"mods/", "config/", "resourcepacks/", "shaderpacks/", "saves/",
                "datapacks/", "kubejs/", "scripts/", "defaultconfigs/", "journeymap/", "schematics/"};
        String[] rootFiles = {"options.txt", "optionsof.txt", "optionsshaders.txt", "servers.dat"};
        monitor.setPhase(Phase.INSTALL, "解压全量游戏包");
        int count = 0;
        Enumeration<? extends ZipEntry> en = zf.entries();
        while (en.hasMoreElements()) {
            ZipEntry e = en.nextElement();
            if (e.isDirectory()) continue;
            String n = zipName(e);
            if (n.startsWith(".minecraft/")) n = n.substring(".minecraft/".length());
            else if (n.startsWith("minecraft/")) n = n.substring("minecraft/".length());
            Path target;
            if (n.startsWith("assets/") || n.startsWith("libraries/")) {
                target = gameDir.resolve(n).normalize();
                if (!target.startsWith(gameDir)) continue;
                if (Files.exists(target) && Files.size(target) == e.getSize()) continue;
            } else if (n.startsWith("versions/")) {
                String rel = n.substring("versions/".length());
                int slash = rel.indexOf('/');
                if (slash < 0) continue;
                String vdir = rel.substring(0, slash), rest = rel.substring(slash + 1);
                if (fullPackJunk(rest)) continue;
                if (vdir.equals(verName) && rest.indexOf('/') < 0) {
                    // 版本 json/jar 文件名统一为版本目录名，否则启动器找不到 <id>/<id>.json
                    if (rest.endsWith(".json")) rest = safeName + ".json";
                    else if (rest.endsWith(".jar")) rest = safeName + ".jar";
                }
                String outDir = vdir.equals(verName) ? safeName : vdir;
                target = gameDir.resolve("versions").resolve(outDir).resolve(rest).normalize();
                if (!target.startsWith(gameDir)) continue;
            } else {
                boolean hit = false;
                for (String p : rootDirs) if (n.startsWith(p)) { hit = true; break; }
                if (!hit) for (String p : rootFiles) if (n.equals(p)) { hit = true; break; }
                if (!hit) continue;   // PCL 启动器本体/配置、地图备份等杂项不解压
                target = isoDir.resolve(n).normalize();
                if (!target.startsWith(gameDir)) continue;
            }
            Files.createDirectories(target.getParent());
            try (InputStream in = zf.getInputStream(e)) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
            count++;
        }
        // 版本 json 的 id 字段与目录名一致化，避免启动参数里的版本名与实际版本对不上
        Path json = gameDir.resolve("versions").resolve(safeName).resolve(safeName + ".json");
        if (Files.exists(json)) {
            try {
                JsonNode node = MAPPER.readTree(json.toFile());
                if (node instanceof ObjectNode obj && !safeName.equals(obj.path("id").asText(""))) {
                    obj.put("id", safeName);
                    MAPPER.writeValue(json.toFile(), obj);
                }
            } catch (Exception ignored) { }
        }
        log.accept("全量游戏包：已安装版本 " + safeName + "，解压 " + count + " 个文件");
        return new ModpackResult(count, 0, count, "", safeName);
    }
}
