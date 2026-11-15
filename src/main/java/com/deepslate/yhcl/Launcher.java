package com.deepslate.yhcl;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

public final class Launcher {
    private static final DateTimeFormatter DIAG_TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static PrintStream diagStream;

    public static void main(String[] args) {
        installDiagnostics();
        // 动画帧率 120：必须在 JavaFX 工具包初始化前设置（用户可用 -Djavafx.animation.pulse 覆盖）
        System.getProperties().putIfAbsent("javafx.animation.pulse", "120");
        App.main(args);
    }

    /** 诊断日志：写一行带时间戳的记录到 appDir()\yhcl.log */
    public static void diag(String msg) {
        PrintStream s = diagStream;
        if (s == null) return;
        synchronized (Launcher.class) {
            s.println(LocalTime.now().format(DIAG_TIME) + " [" + Thread.currentThread().getName() + "] " + msg);
        }
    }

    /**
     * 诊断设施：stdout/stderr 重定向到 yhcl.log + 未捕获异常落日志。
     * javaw 会吞掉 stderr，一旦场景/事件链内部抛异常就完全无线索；重定向后所有线索都留在 appDir()\yhcl.log。
     */
    private static void installDiagnostics() {
        try {
            Path log = appDir().resolve("yhcl.log");
            diagStream = new PrintStream(
                    Files.newOutputStream(log, StandardOpenOption.CREATE, StandardOpenOption.APPEND),
                    true, StandardCharsets.UTF_8);
            System.setOut(diagStream);
            System.setErr(diagStream);
        } catch (Exception ignored) {
            diagStream = null;
        }
        Thread.setDefaultUncaughtExceptionHandler((t, ex) -> {
            diag("UNCAUGHT in " + t.getName() + ": " + ex);
            for (StackTraceElement el : ex.getStackTrace()) diag("    at " + el);
        });
        diag("==== launcher start ====");
    }

    /** 启动器 exe 所在目录：SFX 布局为 exe-dir\YHCL\app\yhcl.jar，appDir() 会停在 YHCL，这里再向上一级。 */
    public static Path exeDir() {
        Path d = appDir();
        if ("YHCL".equals(d.getFileName().toString())) {
            Path p = d.getParent();
            if (p != null) return p.toAbsolutePath();
        }
        return d;
    }

    /**
     * 程序所在目录（便携模式基准目录）：
     * - 打包为 jar/exe（jpackage 布局为 exe 同级 app/ 子目录）→ exe 所在目录
     * - 开发期从 classes 目录运行 → 当前工作目录
     */
    public static Path appDir() {
        try {
            Path loc = Path.of(Launcher.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            if (Files.isRegularFile(loc)) { // 从 jar 运行
                Path dir = loc.getParent();
                if (dir != null && "app".equals(dir.getFileName().toString())
                        && dir.getParent() != null) {
                    dir = dir.getParent(); // jpackage：app/ 的上一级才是 exe 所在目录
                }
                return dir.toAbsolutePath();
            }
        } catch (Exception ignored) {}
        return Path.of(System.getProperty("user.dir")).toAbsolutePath();
    }
}