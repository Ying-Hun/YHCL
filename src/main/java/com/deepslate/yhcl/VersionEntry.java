package com.deepslate.yhcl;

public final class VersionEntry {
    public String id = "";
    public String type = "";
    public String url = "";
    public String releaseTime = "";

    @Override public String toString() { return id + "   [" + type + "]"; }
}