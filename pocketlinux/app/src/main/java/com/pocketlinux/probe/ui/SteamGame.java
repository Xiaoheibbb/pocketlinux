package com.pocketlinux.probe.ui;

import org.json.JSONObject;

/** 一条 Steam 游戏快捷方式（本地 JSON 存储）。 */
public class SteamGame {
    public String id;
    public String name;
    /** 容器内可执行路径或 App 私有目录里的 exe 绝对路径。 */
    public String exePath;
    /** 工作目录（可为空，空则用 /home）。 */
    public String workDir;
    /** Steam AppID（可选，留给后端 steam -applaunch 用）。 */
    public String appId;
    /** 备注，比如版本号 / 启动器说明。 */
    public String note;
    public long lastPlayed;

    public SteamGame() {}

    public SteamGame(String id, String name, String exePath) {
        this.id = id;
        this.name = name;
        this.exePath = exePath;
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("id", id);
            o.put("name", name);
            o.put("exePath", exePath);
            o.put("workDir", workDir == null ? "" : workDir);
            o.put("appId", appId == null ? "" : appId);
            o.put("note", note == null ? "" : note);
            o.put("lastPlayed", lastPlayed);
        } catch (Throwable ignored) {
        }
        return o;
    }

    public static SteamGame fromJson(JSONObject o) {
        SteamGame g = new SteamGame();
        g.id = o.optString("id");
        g.name = o.optString("name");
        g.exePath = o.optString("exePath");
        g.workDir = o.optString("workDir", "");
        g.appId = o.optString("appId", "");
        g.note = o.optString("note", "");
        g.lastPlayed = o.optLong("lastPlayed");
        return g;
    }
}
