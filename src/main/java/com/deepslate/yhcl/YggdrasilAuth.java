package com.deepslate.yhcl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

/** 第三方登录（Authlib Injector / LittleSkin）：Yggdrasil 外置验证 */
public final class YggdrasilAuth {

    public static final class Result {
        public String name = "";
        public String uuid = "";
        public String accessToken = "";
        public String clientToken = "";
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    /** 登录：POST {api}/authserver/authenticate */
    public static Result authenticate(String apiBase, String username, String password) throws Exception {
        String base = apiBase == null ? "" : apiBase.trim();
        if (base.isBlank()) throw new Exception("请填写 API 地址");
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        ObjectNode req = MAPPER.createObjectNode();
        ObjectNode agent = req.putObject("agent");
        agent.put("name", "Minecraft");
        agent.put("version", 1);
        req.put("username", username);
        req.put("password", password);
        req.put("clientToken", UUID.randomUUID().toString().replace("-", ""));
        req.put("requestUser", false);
        JsonNode resp = postJson(base + "/authserver/authenticate", req);
        JsonNode prof = resp.path("selectedProfile");
        if (!prof.has("id"))
            throw new Exception("该账户没有可用的游戏档案（请先在皮肤站创建角色）");
        Result r = new Result();
        r.name = prof.path("name").asText();
        r.uuid = prof.path("id").asText().replace("-", "");
        r.accessToken = resp.path("access_token").asText();
        r.clientToken = resp.path("clientToken").asText();
        if (r.accessToken.isBlank()) throw new Exception("登录响应缺少令牌");
        return r;
    }

    private static JsonNode postJson(String url, ObjectNode payload) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", "YHCL/0.1.2beta (Java launcher)")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(payload), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        String body = resp.body();
        if (resp.statusCode() / 100 != 2) {
            String msg = body;
            try {
                JsonNode e = MAPPER.readTree(body);
                msg = e.path("errorMessage").asText(e.path("error").asText(body));
            } catch (Exception ignored) { }
            throw new Exception("登录失败（HTTP " + resp.statusCode() + "）：" + msg);
        }
        return MAPPER.readTree(body);
    }
}
