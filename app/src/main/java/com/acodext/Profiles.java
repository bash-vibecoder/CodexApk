package com.acodext;

import android.content.Context;
import android.util.AtomicFile;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class Profiles {
    public final List<Provider> items = new ArrayList<>();
    public String active = "";

    static File location(Context context) {
        return new File(RuntimePaths.root(context), "home/.config/codex-terminal/providers.json");
    }

    public static Profiles load(Context context) {
        Profiles result = new Profiles();
        AtomicFile file = new AtomicFile(location(context));
        if (!file.getBaseFile().exists() && !new File(file.getBaseFile() + ".bak").exists()) return result;
        try {
            JSONObject root = new JSONObject(new String(file.readFully(), StandardCharsets.UTF_8));
            result.active = root.optString("active");
            JSONArray list = root.getJSONArray("profiles");
            for (int i = 0; i < list.length(); i++) {
                JSONObject j = list.getJSONObject(i);
                Provider p = new Provider();
                p.id = j.getString("id"); p.name = j.getString("name"); p.baseUrl = j.getString("base_url");
                p.model = j.getString("model"); p.token = j.getString("token"); p.protocol = j.optString("protocol", "responses");
                p.validate();
                result.items.add(p);
            }
            return result;
        } catch (Exception e) {
            throw new IllegalStateException("Could not read saved providers. Your profile file was preserved.", e);
        }
    }

    public Provider selected() {
        for (Provider p : items) if (p.id.equals(active)) return p;
        return items.isEmpty() ? null : items.get(0);
    }

    public void save(Context context) throws Exception {
        JSONObject root = new JSONObject();
        root.put("active", active);
        JSONArray list = new JSONArray();
        for (Provider p : items) {
            p.validate();
            JSONObject j = new JSONObject();
            j.put("id", p.id); j.put("name", p.name); j.put("base_url", p.baseUrl);
            j.put("model", p.model); j.put("token", p.token); j.put("protocol", p.protocol);
            list.put(j);
        }
        root.put("profiles", list);
        File path = location(context);
        if (!path.getParentFile().isDirectory() && !path.getParentFile().mkdirs()) throw new IllegalStateException("Could not create private profile directory.");
        AtomicFile file = new AtomicFile(path);
        FileOutputStream stream = null;
        try {
            stream = file.startWrite();
            // AtomicFile writes to a temporary file; the final filename doesn't exist yet.
            android.system.Os.fchmod(stream.getFD(), 0600);
            stream.write(root.toString(2).getBytes(StandardCharsets.UTF_8));
            file.finishWrite(stream);
        } catch (Exception e) {
            if (stream != null) file.failWrite(stream);
            throw e;
        }
    }
}
