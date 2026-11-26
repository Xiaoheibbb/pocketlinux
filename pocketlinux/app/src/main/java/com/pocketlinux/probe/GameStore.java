package com.pocketlinux.probe;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/** 游戏库存储：files/games.json */
public class GameStore {
    private static final String TAG = "PLPROBE";
    private static final String FILE = "games.json";

    private final Context ctx;
    private final List<Game> games = new ArrayList<>();

    public GameStore(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        load();
    }

    public List<Game> all() {
        return new ArrayList<>(games);
    }

    public int size() {
        return games.size();
    }

    public void add(Game g) {
        games.add(0, g);
        save();
    }

    public void remove(String id) {
        games.removeIf(g -> g.id.equals(id));
        save();
    }

    public void update(Game g) {
        for (int i = 0; i < games.size(); i++) {
            if (games.get(i).id.equals(g.id)) {
                games.set(i, g);
                break;
            }
        }
        save();
    }

    private File file() {
        return new File(ctx.getFilesDir(), FILE);
    }

    private void load() {
        try {
            File f = file();
            if (!f.exists()) return;
            String s = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            JSONArray arr = new JSONArray(s);
            games.clear();
            for (int i = 0; i < arr.length(); i++) {
                games.add(Game.fromJson(arr.getJSONObject(i)));
            }
        } catch (Throwable t) {
            Log.e(TAG, "load games fail", t);
        }
    }

    private void save() {
        try {
            JSONArray arr = new JSONArray();
            for (Game g : games) arr.put(g.toJson());
            Files.write(file().toPath(), arr.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Throwable t) {
            Log.e(TAG, "save games fail", t);
        }
    }
}
