package com.pocketlinux.probe.ui;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/** Steam 快捷方式存储：files/steam-games.json（写法参照 GameStore）。 */
public class SteamStore {
    private static final String TAG = "PLPROBE";
    private static final String FILE = "steam-games.json";

    private final Context ctx;
    private final List<SteamGame> games = new ArrayList<>();

    public SteamStore(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        load();
    }

    public List<SteamGame> all() {
        return new ArrayList<>(games);
    }

    public int size() {
        return games.size();
    }

    public SteamGame get(String id) {
        for (SteamGame g : games) {
            if (g.id != null && g.id.equals(id)) return g;
        }
        return null;
    }

    public void add(SteamGame g) {
        games.add(0, g);
        save();
    }

    public void update(SteamGame g) {
        for (int i = 0; i < games.size(); i++) {
            if (games.get(i).id != null && games.get(i).id.equals(g.id)) {
                games.set(i, g);
                save();
                return;
            }
        }
        add(g);
    }

    public void remove(String id) {
        games.removeIf(g -> g.id != null && g.id.equals(id));
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
                games.add(SteamGame.fromJson(arr.getJSONObject(i)));
            }
        } catch (Throwable t) {
            Log.e(TAG, "load steam games fail", t);
        }
    }

    private void save() {
        try {
            JSONArray arr = new JSONArray();
            for (SteamGame g : games) arr.put(g.toJson());
            Files.write(file().toPath(), arr.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Throwable t) {
            Log.e(TAG, "save steam games fail", t);
        }
    }
}
