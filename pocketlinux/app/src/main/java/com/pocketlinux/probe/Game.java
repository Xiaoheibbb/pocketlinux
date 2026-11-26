package com.pocketlinux.probe;

import org.json.JSONException;
import org.json.JSONObject;

/** 一个可启动的游戏/程序条目。 */
public class Game {
    public String id;
    public String name;
    public String exePath;     // proot 内可执行的路径（copy 后位于 App 私有目录）
    public String workDir;     // 启动时的工作目录
    public String iconPath;    // 可选，图标文件路径
    public long size;
    public long lastPlayed;
    // 每游戏配置
    public int width = 1280;
    public int height = 800;
    public boolean dxvk = false;

    public Game() {}

    public Game(String id, String name, String exePath, String workDir) {
        this.id = id;
        this.name = name;
        this.exePath = exePath;
        this.workDir = workDir;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("name", name);
        o.put("exePath", exePath);
        o.put("workDir", workDir);
        o.put("iconPath", iconPath == null ? "" : iconPath);
        o.put("size", size);
        o.put("lastPlayed", lastPlayed);
        o.put("width", width);
        o.put("height", height);
        o.put("dxvk", dxvk);
        return o;
    }

    public static Game fromJson(JSONObject o) {
        Game g = new Game();
        g.id = o.optString("id");
        g.name = o.optString("name");
        g.exePath = o.optString("exePath");
        g.workDir = o.optString("workDir");
        g.iconPath = o.optString("iconPath", "");
        g.size = o.optLong("size");
        g.lastPlayed = o.optLong("lastPlayed");
        g.width = o.optInt("width", 1280);
        g.height = o.optInt("height", 800);
        g.dxvk = o.optBoolean("dxvk", false);
        return g;
    }
}
