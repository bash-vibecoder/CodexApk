package com.acodext;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.system.Os;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

final class RuntimeInstaller {
    interface Listener { void progress(int files); void complete(Exception error); }
    private static boolean running;
    private static Listener listener;
    private static final Handler handler = new Handler(Looper.getMainLooper());
    private static final String VERSION = "2";

    static boolean ready(Context c) { return new File(RuntimePaths.root(c), "usr/.acodext-runtime-" + VERSION).isFile(); }

    static synchronized void start(Context c, Listener callback) {
        listener = callback;
        if (running) return;
        running = true;
        Context context = c.getApplicationContext();
        new Thread(() -> {
            Exception failure = null;
            File stage = new File(RuntimePaths.root(context), "runtime-staging");
            try {
                if (!ready(context)) install(context, stage);
            } catch (Exception e) { failure = e; }
            final Exception result = failure;
            handler.post(() -> {
                Listener target;
                synchronized (RuntimeInstaller.class) { running = false; target = listener; listener = null; }
                if (target != null) target.complete(result);
            });
        }, "RuntimeInstaller").start();
    }

    static synchronized void detach(Listener l) { if (listener == l) listener = null; }

    private static void install(Context context, File stage) throws Exception {
        if (!java.util.Arrays.asList(android.os.Build.SUPPORTED_ABIS).contains("arm64-v8a")) throw new IllegalStateException("This build requires an ARM64 Android device.");
        File rootDirectory = RuntimePaths.root(context);
        long free = rootDirectory.getParentFile().getUsableSpace();
        if (free < 1024L * 1024L * 1024L) {
            throw new IOException("ACodexT needs at least 1 GiB of free device storage for its Codex and terminal runtime. Free up space and tap Retry.");
        }
        delete(stage);
        if (!stage.mkdirs()) throw new IllegalStateException("Could not create runtime staging folder.");
        String root = stage.getCanonicalPath() + "/";
        JSONObject links = null;
        byte[] buffer = new byte[64 * 1024];
        int count = 0;
        try (ZipInputStream zip = new ZipInputStream(context.getAssets().open("runtime.zip"))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.getName().equals("SYMLINKS.json")) {
                    ByteArrayOutputStream data = new ByteArrayOutputStream();
                    int n; while ((n = zip.read(buffer)) != -1) data.write(buffer, 0, n);
                    links = new JSONObject(data.toString("UTF-8"));
                    continue;
                }
                File target = new File(stage, entry.getName());
                if (!entry.getName().startsWith("usr/") || !target.getCanonicalPath().startsWith(root)) throw new IllegalStateException("Invalid runtime archive path.");
                if (entry.isDirectory()) { target.mkdirs(); continue; }
                if (!target.getParentFile().isDirectory() && !target.getParentFile().mkdirs()) throw new IllegalStateException("Could not create runtime directory.");
                try (FileOutputStream out = new FileOutputStream(target)) {
                    int n; while ((n = zip.read(buffer)) != -1) out.write(buffer, 0, n);
                }
                // Private executable files are permitted by the Android terminal target (API 28).
                boolean executable = entry.getName().startsWith("usr/bin/") || entry.getName().startsWith("usr/libexec/") || entry.getName().startsWith("usr/lib/apt/") || entry.getName().endsWith(".sh");
                Os.chmod(target.getAbsolutePath(), executable ? 0700 : 0600);
                count++;
                if (count % 120 == 0) {
                    final int done = count;
                    handler.post(() -> { Listener l; synchronized (RuntimeInstaller.class) { l = listener; } if (l != null) l.progress(done); });
                }
            }
        }
        if (links == null) throw new IllegalStateException("Runtime symlink index is missing.");
        for (Iterator<String> it = links.keys(); it.hasNext();) {
            String key = it.next();
            File path = new File(stage, key);
            if (!key.startsWith("usr/") || key.contains("..") || !path.getParentFile().getCanonicalPath().startsWith(stage.getCanonicalPath() + "/")) throw new IllegalStateException("Invalid runtime symlink.");
            path.getParentFile().mkdirs();
            if (path.exists() || java.nio.file.Files.isSymbolicLink(path.toPath())) {
                if (!path.delete() && java.nio.file.Files.isSymbolicLink(path.toPath())) {
                    throw new IOException("Could not replace an existing terminal link: " + key);
                }
            }
            Os.symlink(links.getString(key), path.getAbsolutePath());
        }
        File installed = new File(RuntimePaths.root(context), "usr");
        if (installed.exists()) delete(installed);
        if (!new File(stage, "usr").renameTo(installed)) throw new IllegalStateException("Could not activate runtime.");
        new File(installed, ".acodext-runtime-" + VERSION).createNewFile();
        new File(installed, "tmp").mkdirs();
        new File(RuntimePaths.root(context), "home/projects").mkdirs();
        delete(stage);
    }

    private static void delete(File file) throws Exception {
        if (!file.exists() && !java.nio.file.Files.isSymbolicLink(file.toPath())) return;
        // Never traverse symlinks during cleanup.
        if (file.isDirectory() && !java.nio.file.Files.isSymbolicLink(file.toPath())) {
            File[] children = file.listFiles();
            if (children == null) throw new IllegalStateException("Could not read staging folder.");
            for (File child : children) delete(child);
        }
        if (!file.delete()) throw new IllegalStateException("Could not clean runtime staging file.");
    }
}
