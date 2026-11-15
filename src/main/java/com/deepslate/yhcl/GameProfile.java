package com.deepslate.yhcl;

public final class GameProfile {
    public String name = "Steve";
    public String uuid = "";
    public String accessToken = "";
    public boolean isMicrosoft = false;
    public boolean isYggdrasil = false;   // 第三方登录（Authlib Injector / LittleSkin）
    public String authApi = "";           // Yggdrasil API 地址，供 Authlib Injector 注入
}