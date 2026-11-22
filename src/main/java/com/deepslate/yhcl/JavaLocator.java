package com.deepslate.yhcl;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 在用户电脑上搜索已安装的 Java 并识别主版本号。
 * YHCL 不打包 JRE，运行时要求用户自行安装 Java。
 */
public final class JavaLocator {

    public static final class JavaInfo {
        public final Path home;
        public final int major;
        public final String vendor;
        /** 是否包含运行 Mod 必需的模块（jlink 裁剪版运行时缺 java.sql 会让 Fabric/Forge 崩溃） */
        public final boolean complete;

        public JavaInfo(Path home, int major, String vendor, boolean complete) {
            this.home = home;
            this.major = major;
            this.vendor = vendor;
            this.complete = complete;
        }

        /** 控制台版 java.exe（能回传游戏日志） */
        public Path consoleExe() {
            Path p = home.resolve("bin").resolve("java.exe");
            return Files.exists(p) ? p : home.resolve("bin").resolve("javaw.exe");
        }

        @Override public String toString() {
            return "Java " + major + " · " + vendor + " (" + home + ")";
        }
    }

    private static List<JavaInfo> cache;

    public static synchronized List<JavaInfo> scan() {
        if (cache != null) return cache;

        Set<Path> homes = new LinkedHashSet<>();

        // 1) 当前运行 YHCL 的 JVM 自带的 Java
        homes.add(Path.of(System.getProperty("java.home")));

        // 2) 环境变量 JAVA_HOME
        String jh = System.getenv("JAVA_HOME");
        if (jh != null && !jh.isBlank()) homes.add(Path.of(jh));

        // 3) PATH 上的 java（where java）
        for (String s : runCapture("where", "java")) {
            Path exe = Path.of(s.trim());
            if (!Files.exists(exe)) continue;
            Path bin = exe.toAbsolutePath().getParent();
            if (bin != null && bin.getFileName().toString().equalsIgnoreCase("bin"))
                homes.add(bin.getParent());
        }

        // 4) 常见安装目录（含 scoop / chocolatey）
        String pf = env("ProgramFiles", "C:\\Program Files");
        String pf86 = env("ProgramFiles(x86)", "C:\\Program Files (x86)");
        String local = env("LOCALAPPDATA", "");
        String userHome = System.getProperty("user.home");

        List<String> roots = new ArrayList<>(List.of(
                pf + "\\Java", pf + "\\Eclipse Adoptium", pf + "\\Eclipse Foundation",
                pf + "\\Microsoft", pf + "\\Zulu", pf + "\\Amazon Corretto",
                pf + "\\BellSoft", pf + "\\RedHat", pf + "\\IBM", pf + "\\Semeru",
                pf86 + "\\Java", pf86 + "\\Eclipse Adoptium",
                "D:\\software",
                userHome + "\\scoop\\apps",
                "C:\\ProgramData\\chocolatey\\lib",
                "C:\\ProgramData\\Oracle\\Java"));
        if (!local.isBlank()) {
            roots.add(local + "\\Programs");
            roots.add(local + "\\Programs\\Eclipse Adoptium");
        }

        for (String root : roots) {
            Path dir = Path.of(root);
            if (!Files.isDirectory(dir)) continue;
            try (var stream = Files.walk(dir, 3)) {
                stream.filter(p -> p.getFileName().toString().equalsIgnoreCase("javaw.exe"))
                        .filter(p -> p.getParent() != null
                                && p.getParent().getFileName().toString().equalsIgnoreCase("bin"))
                        .forEach(p -> homes.add(p.getParent().getParent()));
            } catch (IOException ignored) { }
        }

        // 5) 注册表
        for (String key : new String[]{
                "HKLM\\SOFTWARE\\JavaSoft\\JDK",
                "HKLM\\SOFTWARE\\JavaSoft\\Java Development Kit",
                "HKLM\\SOFTWARE\\JavaSoft\\JRE",
                "HKLM\\SOFTWARE\\Eclipse Adoptium\\JDK",
                "HKLM\\SOFTWARE\\Microsoft\\JDK",
                "HKLM\\SOFTWARE\\Zulu",
                "HKLM\\SOFTWARE\\Amazon Corretto",
                "HKLM\\SOFTWARE\\BellSoft",
                "HKLM\\SOFTWARE\\Wow6432Node\\JavaSoft\\Java Development Kit"}) {
            Pattern regPat = Pattern.compile("REG_SZ\\s+([A-Za-z]:\\\\\\S.*?)\\s*$");
            for (String line : runCapture("reg", "query", key, "/s")) {
                Matcher m = regPat.matcher(line);
                if (!m.find()) continue;
                Path cand = Path.of(m.group(1).trim());
                if (Files.exists(cand.resolve("bin").resolve("javaw.exe"))) homes.add(cand);
                else if (Files.exists(cand.resolve("javaw.exe"))) homes.add(cand.getParent());
            }
        }

        List<JavaInfo> out = new ArrayList<>();
        for (Path home : homes) {
            if (home == null || !Files.isDirectory(home)) continue;
            JavaInfo info = readRelease(home);
            if (info == null || info.major <= 0) continue;
            if (out.stream().noneMatch(j -> j.home.equals(info.home))) out.add(info);
        }
        out.sort(Comparator.comparingInt((JavaInfo j) -> j.major).thenComparing(j -> j.home.toString()));
        cache = out;
        return cache;
    }

    public static void clearCache() { cache = null; }

    /** 运行 Mod 必需的模块：Mixin 内嵌 Gson 引用 java.sql.Time，裁掉 java.sql 启动即崩；
     *  jdk.dynalink 是整合包自带的 nashorn（org.openjdk.nashorn）requires 的模块，
     *  裁剪版运行时缺它会在 BootstrapLauncher 解析模块图时 FindException 直接崩 */
    private static final List<String> REQUIRED_MODULES =
            List.of("java.sql", "java.desktop", "java.naming", "java.management", "jdk.unsupported",
                    "jdk.dynalink");

    private static final Map<Path, Boolean> completeCache = new HashMap<>();

    /** 该 Java 是否包含运行 Mod 必需的模块；home 可传 JDK/JRE 根目录或 bin 下的 java.exe */
    public static synchronized boolean complete(Path javaOrHome) {
        Path home = javaOrHome.toAbsolutePath().normalize();
        if (Files.isRegularFile(home)) {
            Path bin = home.getParent();
            home = (bin != null && "bin".equalsIgnoreCase(
                    bin.getFileName().toString())) ? bin.getParent() : bin;
        }
        if (home == null) return false;
        final Path h = home;
        return completeCache.computeIfAbsent(h, k -> {
            Path exe = k.resolve("bin").resolve("java.exe");
            if (!Files.exists(exe)) return false;
            Set<String> mods = new HashSet<>();
            for (String line : runCapture(exe.toString(), "--list-modules")) {
                line = line.trim();
                // --list-modules 的报错行（如 Java 8 的 Unrecognized option）不是模块名
                if (line.isEmpty() || line.contains(" ")) continue;
                int at = line.indexOf('@');
                mods.add(at > 0 ? line.substring(0, at).trim() : line);
            }
            if (mods.isEmpty()) {
                // Java 8 及更早没有模块系统，也没有 jlink 裁剪一说：能正常回版本号就算完整
                return runCapture(exe.toString(), "-version").stream().anyMatch(l -> l.contains("\""));
            }
            return mods.containsAll(REQUIRED_MODULES);
        });
    }

    /**
     * 按游戏需要的主版本号挑 Java：
     * 只考虑版本号在 [requiredMajor, maxMajor] 范围内的（低版本 Java 无法运行高版本编译的类；
     * 上限来自启动方式的硬约束，例如 LaunchWrapper 时代的 MC ≤ 1.12 只能跑 Java 8），
     * 优先取满足要求的最小版本；没有则返回 null（不降级兼容）。
     * 裁剪版运行时（缺 java.sql 等模块）会被跳过——它们跑 Mod 必崩。
     */
    public static JavaInfo pick(int requiredMajor) {
        return pick(requiredMajor, Integer.MAX_VALUE);
    }

    public static JavaInfo pick(int requiredMajor, int maxMajor) {
        List<JavaInfo> all = scan();
        if (all.isEmpty()) return null;
        if (requiredMajor <= 0) requiredMajor = 8;

        for (JavaInfo j : all)
            if (j.major >= requiredMajor && j.major <= maxMajor && j.complete) return j;
        return null;   // 没有满足要求的完整 Java，绝不降级
    }

    /** 读取任意 Java 路径（家目录或 java.exe）的版本信息，不走扫描缓存 */
    public static JavaInfo info(Path javaOrHome) {
        Path home = javaOrHome.toAbsolutePath().normalize();
        if (Files.isRegularFile(home)) {
            Path bin = home.getParent();
            home = (bin != null && "bin".equalsIgnoreCase(
                    bin.getFileName().toString())) ? bin.getParent() : bin;
        }
        return home == null ? null : readRelease(home);
    }

    private static JavaInfo readRelease(Path home) {
        Path rel = home.resolve("release");
        String ver = "", impl = "";
        if (Files.exists(rel)) {
            try {
                for (String line : Files.readAllLines(rel, StandardCharsets.UTF_8)) {
                    if (line.startsWith("JAVA_VERSION="))
                        ver = line.substring(line.indexOf('"') + 1, line.lastIndexOf('"'));
                    if (line.startsWith("IMPLEMENTOR="))
                        impl = line.substring(line.indexOf('"') + 1, line.lastIndexOf('"'));
                }
            } catch (IOException ignored) { }
        }

        int major = parseMajor(ver);
        if (major <= 0) {
            List<String> out = runCapture(home.resolve("bin").resolve("java.exe").toString(), "-version");
            Matcher m = Pattern.compile("version \"(\\d+)(?:\\.(\\d+))?").matcher(String.join(" ", out));
            if (m.find())
                major = Integer.parseInt(m.group(1).equals("1") ? m.group(2) : m.group(1));
        }
        if (major <= 0) return null;

        String vendor = impl;
        if (vendor.isBlank())
            vendor = home.getParent() == null ? "未知" : home.getParent().getFileName().toString();
        return new JavaInfo(home, major, vendor, complete(home));
    }

    private static int parseMajor(String ver) {
        if (ver == null || ver.isBlank()) return -1;
        try {
            if (ver.startsWith("1.")) return Integer.parseInt(ver.split("\\.")[1]);
            return Integer.parseInt(ver.split("[._-]")[0]);
        } catch (Exception e) { return -1; }
    }

    private static String env(String key, String def) {
        String v = System.getenv(key);
        return (v == null || v.isBlank()) ? def : v;
    }

    private static List<String> runCapture(String... cmd) {
        List<String> out = new ArrayList<>();
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            try (var br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) out.add(line);
            }
            p.waitFor(5, TimeUnit.SECONDS);
        } catch (IOException | InterruptedException ignored) { }
        return out;
    }
}