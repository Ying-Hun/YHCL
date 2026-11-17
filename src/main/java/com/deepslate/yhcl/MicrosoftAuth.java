package com.deepslate.yhcl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Microsoft 正版登录（设备码流程：8 位代码，PCL 同款体验）。
 *
 * 采用 AAD v2 设备码通道（login.microsoftonline.com/consumers）：
 *  - 端点：…/oauth2/v2.0/devicecode 与 …/oauth2/v2.0/token；
 *  - client_id：自注册 Azure 应用「YHCL」（登录网页显示 YHCL）；
 *  - scope：XboxLive.signin offline_access；
 *  - XBL RpsTicket 前缀 "d="（AAD v2 令牌）。
 */
public final class MicrosoftAuth {

    /** 自注册 Azure 应用「YHCL」客户端 ID（登录网页显示 YHCL） */
    public static final String CLIENT_ID = "6ab0774b-a229-4504-8b30-b9b96df453a9";

    /** AAD v2 scope：Xbox Live 登录 + 刷新令牌 */
    private static final String SCOPE = "XboxLive.signin offline_access";
    private static final String DEVICE_CODE_URL = "https://login.microsoftonline.com/consumers/oauth2/v2.0/devicecode";
    private static final String TOKEN_URL = "https://login.microsoftonline.com/consumers/oauth2/v2.0/token";

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** 正版登录时是否校验 SSL 证书（关闭后跳过证书链与主机名校验，仅供特殊网络环境排障） */
    public static volatile boolean verifySsl = true;
    /** 不校验证书的客户端，仅在关闭 SSL 校验时懒初始化使用 */
    private static volatile HttpClient looseHttp;

    private static HttpClient http() {
        if (verifySsl) return HTTP;
        HttpClient c = looseHttp;
        if (c != null) return c;
        synchronized (MicrosoftAuth.class) {
            if (looseHttp == null) looseHttp = buildLoose();
            return looseHttp;
        }
    }

    /** 信任任意证书 + 关闭主机名校验的客户端（设置页「在正版登录时验证 SSL 证书」关闭时用） */
    private static HttpClient buildLoose() {
        try {
            TrustManager[] trustAll = { new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] chain, String authType) { }
                public void checkServerTrusted(X509Certificate[] chain, String authType) { }
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            } };
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, trustAll, new SecureRandom());
            SSLParameters params = ctx.getDefaultSSLParameters();
            params.setEndpointIdentificationAlgorithm(null);   // 主机名校验一并关掉
            return HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .sslContext(ctx)
                    .sslParameters(params)
                    .build();
        } catch (Exception e) {
            return HTTP;
        }
    }
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public interface DevicePrompt {
        void prompt(String userCode, String verifyUrl, String message);
    }

    public static final class DeviceCode {
        public String deviceCode = "", userCode = "", verifyUrl = "", message = "";
        public int interval = 5;
    }

    public static final class Result {
        public String accessToken = "";
        public String uuid = "";
        public String name = "";
        public String refreshToken = "";
    }

    /* ============ 1) 申请设备码（8 位登录代码） ============ */
    public static DeviceCode beginDeviceLogin() throws Exception {
        String body = postForm(DEVICE_CODE_URL, Map.of("client_id", CLIENT_ID, "scope", SCOPE));
        JsonNode n = MAPPER.readTree(body);

        if (n.has("error")) {
            throw new Exception("申请登录代码失败：" + n.path("error").asText("")
                    + "\n" + n.path("error_description").asText(""));
        }

        DeviceCode dc = new DeviceCode();
        dc.deviceCode = n.path("device_code").asText();
        dc.userCode = n.path("user_code").asText();
        dc.verifyUrl = n.path("verification_uri").asText();
        dc.message = n.path("message").asText("");
        dc.interval = n.path("interval").asInt(5);

        if (dc.deviceCode.isBlank() || dc.userCode.isBlank() || dc.verifyUrl.isBlank())
            throw new Exception("服务器没有返回登录代码，请重试。\n原始响应：" + body);
        return dc;
    }

    /* ============ 2) 轮询直到用户在浏览器完成登录 ============ */
    public static Result completeDeviceLogin(DeviceCode dc, DevicePrompt prompt,
                                             AtomicBoolean cancelled, Consumer<String> log) throws Exception {
        if (prompt != null) prompt.prompt(dc.userCode, dc.verifyUrl, dc.message);
        log.accept("请在浏览器打开 " + dc.verifyUrl + " 并输入代码 " + dc.userCode);

        int interval = dc.interval;
        String msToken = "", msRefresh = "";
        while (true) {
            if (cancelled != null && cancelled.get())
                throw new Exception("已取消登录。");

            Thread.sleep(interval * 1000L);
            String body = postForm(TOKEN_URL,
                    Map.of("grant_type", "urn:ietf:params:oauth:grant-type:device_code",
                            "client_id", CLIENT_ID,
                            "device_code", dc.deviceCode));
            JsonNode t = MAPPER.readTree(body);
            if (t.has("access_token")) {
                msToken = t.path("access_token").asText();
                msRefresh = t.path("refresh_token").asText("");
                break;
            }
            String err = t.path("error").asText();
            switch (err) {
                case "authorization_pending" -> { }
                case "slow_down" -> interval += 5;
                case "expired_token" -> throw new Exception("登录代码已过期，请重新点登录。");
                case "authorization_declined" -> throw new Exception("你在浏览器拒绝了登录。");
                default -> throw new Exception("登录失败：" + t.path("error_description").asText(err));
            }
        }
        log.accept("Microsoft 账户登录成功，正在验证 Xbox / Minecraft 资格…");

        Result r = xboxChain(msToken, log);
        r.refreshToken = msRefresh;
        return r;
    }

    /* ============ 3) 静默续期 ============ */
    public static Result refresh(String refreshToken, Consumer<String> log) throws Exception {
        String body = postForm(TOKEN_URL,
                Map.of("grant_type", "refresh_token",
                        "client_id", CLIENT_ID,
                        "refresh_token", refreshToken,
                        "scope", SCOPE));
        JsonNode t = MAPPER.readTree(body);
        if (!t.has("access_token")) throw new Exception("refresh_token 失效：" + body);
        Result r = xboxChain(t.path("access_token").asText(), log);
        r.refreshToken = t.path("refresh_token").asText(refreshToken);
        log.accept("登录已自动续期：" + r.name);
        return r;
    }

    /* ============ Xbox Live → XSTS → Minecraft Services ============ */
    private static Result xboxChain(String msToken, Consumer<String> log) throws Exception {

        // ---- 1) Xbox Live ----
        ObjectNode xblReq = MAPPER.createObjectNode();
        ObjectNode props = xblReq.putObject("Properties");
        props.put("AuthMethod", "RPS");
        props.put("SiteName", "user.auth.xboxlive.com");
        props.put("RpsTicket", "d=" + msToken);   // AAD v2 令牌用 d= 前缀
        xblReq.put("RelyingParty", "http://auth.xboxlive.com");
        xblReq.put("TokenType", "JWT");

        JsonNode xbl;
        try {
            xbl = postJson("https://user.auth.xboxlive.com/user/authenticate", xblReq);
        } catch (Exception ex) {
            throw new Exception("Xbox Live 认证失败：" + ex.getMessage());
        }
        String xblToken = xbl.path("Token").asText();
        log.accept("Xbox Live 认证成功");

        // ---- 2) XSTS ----
        ObjectNode xstsReq = MAPPER.createObjectNode();
        ObjectNode p2 = xstsReq.putObject("Properties");
        p2.put("SandboxId", "RETAIL");
        p2.putArray("UserTokens").add(xblToken);
        xstsReq.put("RelyingParty", "rp://api.minecraftservices.com/");
        xstsReq.put("TokenType", "JWT");

        JsonNode xsts;
        try {
            xsts = postJson("https://xsts.auth.xboxlive.com/xsts/authorize", xstsReq);
        } catch (Exception ex) {
            throw new Exception(explainXsts(ex.getMessage()));
        }

        String uhs = xsts.path("DisplayClaims").path("xui").get(0).path("uhs").asText();
        String xstsToken = xsts.path("Token").asText();
        if (uhs.isBlank() || xstsToken.isBlank())
            throw new Exception("XSTS 令牌不完整。");

        // 诊断：解码 XSTS JWT，确认 appid 是被 Mojang 接受的 Xbox App ID
        try {
            String payload = decodeJwtPayload(xstsToken);
            JsonNode claims = MAPPER.readTree(payload);
            log.accept("诊断 → XSTS appid = " + claims.path("appid").asText("?")
                    + " | rp = " + claims.path("rp").asText("?"));
        } catch (Exception ex) {
            log.accept("诊断解码失败：" + ex.getMessage());
        }

        // ---- 3) Minecraft Services ----
        String identityToken = "XBL3.0 x=" + uhs + ";" + xstsToken;
        ObjectNode mcReq = MAPPER.createObjectNode();
        mcReq.put("identityToken", identityToken);
        JsonNode mc = postJson("https://api.minecraftservices.com/authentication/login_with_xbox", mcReq);
        String mcToken = mc.path("access_token").asText();
        if (mcToken.isBlank())
            throw new Exception("Minecraft 令牌为空，响应：" + mc);
        log.accept("Minecraft 服务认证成功");

        // ---- 4) 玩家档案 ----
        HttpRequest req = withUa(HttpRequest.newBuilder(
                URI.create("https://api.minecraftservices.com/minecraft/profile")))
                .header("Authorization", "Bearer " + mcToken)
                .GET().build();
        HttpResponse<String> resp =
                http().send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        JsonNode prof = MAPPER.readTree(resp.body());

        if (!prof.has("id")) {
            String err = prof.path("error").asText("?");
            String text = resp.body();
            if ("NOT_FOUND".equals(err) || text.contains("NOT_FOUND"))
                throw new Exception("该 Microsoft 账户尚未拥有 Minecraft: Java Edition。\n"
                        + "（可改用离线模式：在玩家名框输入名字即可）");
            throw new Exception("获取玩家档案失败（HTTP " + resp.statusCode() + "）：" + text);
        }

        Result r = new Result();
        r.accessToken = mcToken;
        r.uuid = prof.path("id").asText();
        r.name = prof.path("name").asText();
        log.accept("欢迎，" + r.name + "（正版验证通过）");
        return r;
    }

    /* ============ 辅助 ============ */

    /** 解码 JWT payload（自动补 Base64 URL padding） */
    private static String decodeJwtPayload(String jwt) {
        String[] parts = jwt.split("\\.");
        if (parts.length < 2) return "";
        String p = parts[1];
        p = p.replace('-', '+').replace('_', '/');
        while (p.length() % 4 != 0) p += "=";
        return new String(java.util.Base64.getDecoder().decode(p), StandardCharsets.UTF_8);
    }

    private static String explainXsts(String raw) {
        if (raw == null) return "XSTS 认证失败。";
        java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("\"XErr\"\\s*:\\s*(\\d+)").matcher(raw);
        if (!m.find()) return "XSTS 认证失败：" + raw;
        return switch (m.group(1)) {
            case "2148916233" -> "该账户还没有 Xbox 档案。\n请先打开 https://www.xbox.com/ 登录一次，再重试登录。";
            case "2148916235" -> "该账户所在地区不支持 Xbox Live。\n请到 account.microsoft.com 把「国家/地区」改成香港或美国后重试。";
            case "2148916236", "2148916237", "2148916238" ->
                    "这是儿童/受限账户，需要家长授权后才能登录 Xbox Live。";
            case "2148916239" -> "需要先在 xbox.com 接受 Xbox 服务条款。";
            case "2148916240" -> "需要先为该账户添加成人（家长）信息才能使用 Xbox Live。";
            default -> "XSTS 认证失败（XErr=" + m.group(1) + "）：" + raw;
        };
    }

    private static HttpRequest.Builder withUa(HttpRequest.Builder b) {
        return b.header("User-Agent", "YHCL/0.1.2beta (Java launcher)");
    }

    private static String postForm(String url, Map<String, String> form) throws Exception {
        String body = form.entrySet().stream()
                .map(e -> e.getKey() + "=" + java.net.URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .reduce((a, b) -> a + "&" + b).orElse("");
        HttpRequest req = withUa(HttpRequest.newBuilder(URI.create(url)))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return http().send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).body();
    }

    private static JsonNode postJson(String url, ObjectNode payload) throws Exception {
        HttpRequest req = withUa(HttpRequest.newBuilder(URI.create(url)))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(payload)))
                .build();
        HttpResponse<String> resp = http().send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        String body = resp.body();
        if (resp.statusCode() / 100 != 2)
            throw new Exception("请求失败 " + resp.statusCode() + "：" + body);
        return MAPPER.readTree(body);
    }
}