package com.deepslate.yhcl;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * SakuraFrp frpc 客户端管理器：只负责启动/停止进程、回传运行日志。
 * frpc.exe 由用户手动下载/选择，启动器不做任何自动下载。
 */
public final class FrpManager {

    public enum State { IDLE, LAUNCHING, RUNNING, ERROR }

    private final ExecutorService pool = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "yhcl-frp");
        t.setDaemon(true);
        return t;
    });

    private Path frpcPath;
    private Process proc;
    private volatile State state = State.IDLE;
    private volatile String errorMsg = "";
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Consumer<String> logCallback;

    public FrpManager() {}

    public void setLogCallback(Consumer<String> cb) { this.logCallback = cb; }

    /** 设置 frpc.exe 路径（用户手动选择或从 config 恢复） */
    public void setFrpcPath(Path p) { this.frpcPath = p; }
    public Path frpcPath() { return frpcPath; }

    public State state() { return state; }
    public boolean isRunning() { return running.get(); }
    public String error() { return errorMsg; }

    /** frpc 是否就绪：路径非空且文件存在 */
    public boolean frpcReady() {
        return frpcPath != null && Files.exists(frpcPath) && Files.isRegularFile(frpcPath);
    }

    /** 启动 frpc。-f 参数格式：访问密钥:隧道ID1,隧道ID2,... */
    public synchronized void start(String accessKey, List<String> tunnelIds) throws Exception {
        if (running.get()) throw new IOException("frpc 已在运行");
        if (!frpcReady()) throw new IOException("未找到内置 frpc（YHCL\\tools\\frpc\\frpc.exe），请重新安装启动器");
        if (accessKey == null || accessKey.isBlank()) throw new IOException("访问密钥不能为空");
        if (tunnelIds == null || tunnelIds.isEmpty()) throw new IOException("请至少选择一个隧道");

        List<String> cleanIds = tunnelIds.stream()
                .filter(id -> id != null && !id.isBlank())
                .map(String::trim).toList();
        if (cleanIds.isEmpty()) throw new IOException("隧道 ID 不能为空");

        String fArg = accessKey.trim() + ":" + String.join(",", cleanIds);

        state = State.LAUNCHING;
        errorMsg = "";

        List<String> cmd = List.of(frpcPath.toString(), "-f", fArg);
        log("启动 frpc（隧道 " + cleanIds.size() + " 条）：" + frpcPath);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(frpcPath.getParent().toFile());
        pb.redirectErrorStream(true);
        proc = pb.start();
        running.set(true);
        state = State.RUNNING;

        Thread t = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    log(line);
                    String lc = line.toLowerCase();
                    if (lc.contains("login to server failed")
                            || lc.contains("fatal")
                            || lc.contains("access token invalid")
                            || lc.contains("token is invalid")
                            || lc.contains("proxy conflict")) {
                        state = State.ERROR;
                        if (errorMsg.isBlank()) errorMsg = line;
                    }
                }
            } catch (IOException ignored) { }

            if (running.compareAndSet(true, false)) {
                try {
                    int code = proc.exitValue();
                    log("frpc 进程退出，代码 " + code);
                    if (code != 0 && state != State.ERROR) {
                        state = State.ERROR;
                        errorMsg = "frpc 异常退出（代码 " + code + "）";
                    } else if (state != State.ERROR) {
                        state = State.IDLE;
                    }
                } catch (IllegalThreadStateException ignored) {
                    state = State.IDLE;
                }
            }
        }, "yhcl-frpc-pump");
        t.setDaemon(true);
        t.start();
    }

    /** 停止 frpc 进程（强制杀死）。 */
    public synchronized void stop() {
        running.set(false);
        if (proc != null) {
            try {
                log("正在停止 frpc…");
                proc.destroyForcibly();
            } catch (Exception ignored) { }
            proc = null;
        }
        state = State.IDLE;
    }

    /** 启动器关闭时的清理 */
    public void shutdown() {
        stop();
        pool.shutdownNow();
    }

    private void log(String msg) {
        if (logCallback != null) logCallback.accept(msg);
    }
}
