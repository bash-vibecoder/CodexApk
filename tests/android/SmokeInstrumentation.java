package com.acodext;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.system.Os;
import com.termux.terminal.TerminalSession;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class SmokeInstrumentation extends Instrumentation {
    private Bundle args;
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); args = arguments; start(); }

    private String run(Context c, String... command) throws Exception {
        String base = RuntimePaths.root(c).getAbsolutePath();
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true).directory(new File(base + "/home/projects"));
        Map<String,String> env = builder.environment();
        env.put("HOME", base + "/home"); env.put("PREFIX", base + "/usr");
        env.put("PATH", base + "/usr/bin:/system/bin"); env.put("LD_LIBRARY_PATH", base + "/usr/lib");
        env.put("TMPDIR", base + "/usr/tmp"); env.put("SHELL", base + "/usr/bin/bash");
        env.put("PYTHONHOME", base + "/usr"); env.put("LANG", "C.UTF-8"); env.put("TERM", "xterm-256color");
        env.put("SSL_CERT_FILE", base + "/usr/etc/tls/cert.pem"); env.put("GIT_EXEC_PATH", base + "/usr/libexec/git-core");
        File output = new File(c.getCacheDir(), "validation-process.txt"); builder.redirectOutput(output);
        Process process = builder.start();
        if (!process.waitFor(75, TimeUnit.SECONDS)) { process.destroyForcibly(); throw new AssertionError("Command timed out: " + command[0]); }
        String text = new String(Files.readAllBytes(output.toPath()), StandardCharsets.UTF_8);
        if (process.exitValue() != 0) throw new AssertionError("Exit " + process.exitValue() + " from " + command[0] + ":\n" + text);
        return text;
    }

    @Override public void onStart() {
        Bundle result = new Bundle(); Context context = getTargetContext();
        File profileFile = Profiles.location(context);
        byte[] saved = null;
        try {
            if (profileFile.exists()) saved = Files.readAllBytes(profileFile.toPath());
            Provider profile = new Provider(); profile.id = "validation-response"; profile.baseUrl = args.getString("endpoint", "http://127.0.0.1:18890/v1"); profile.token = "validation-token"; profile.model = "gpt-5.4";
            profile.validate(); Profiles data = new Profiles(); data.items.add(profile); data.active = profile.id; data.save(context);
            if (!Profiles.load(context).selected().token.equals("validation-token")) throw new AssertionError("Profile roundtrip failed");
            if ((Os.stat(profileFile.getAbsolutePath()).st_mode & 0777) != 0600) throw new AssertionError("Token file permissions are not 0600");
            if (RuntimeInstaller.ready(context)) {
                File installed = new File(RuntimePaths.root(context), "usr");
                deleteTree(installed);
            }
            CountDownLatch ready = new CountDownLatch(1); Exception[] error = {null};
            runOnMainSync(() -> RuntimeInstaller.start(context, new RuntimeInstaller.Listener() {
                public void progress(int n) { }
                public void complete(Exception e) { error[0] = e; ready.countDown(); }
            }));
            if (!ready.await(180, TimeUnit.SECONDS)) throw new AssertionError("Runtime install timed out");
            if (error[0] != null) throw error[0];
            String base = RuntimePaths.root(context).getAbsolutePath();
            String tools = run(context, base + "/usr/bin/bash", "-c", "codex --version && git --version && python3 --version && rg --version | head -1 && pkg list | head -3");
            result.putString("tools", tools);
            File proof = new File(base + "/home/projects/acodext-validation.txt");
            proof.delete();
            String nativeResult = run(context, base + "/usr/bin/codex", "exec", "--skip-git-repo-check", "--json", "Create acodext-validation.txt containing ACodexT-working using a shell command, then say DONE.");
            if (!proof.exists() || !new String(Files.readAllBytes(proof.toPath()), StandardCharsets.UTF_8).contains("ACodexT-working")) throw new AssertionError("Codex Responses tool did not write proof:\n" + nativeResult);
            result.putString("responses", "codex exec received a response and executed a shell tool successfully"); proof.delete();
            profile.id = "validation-chat"; profile.protocol = "chat"; data.items.clear(); data.items.add(profile); data.active = profile.id; data.save(context);
            String chatResult = run(context, base + "/usr/bin/codex", "exec", "--skip-git-repo-check", "--json", "Create acodext-validation.txt containing ACodexT-working using a shell command, then say DONE.");
            if (!proof.exists() || !new String(Files.readAllBytes(proof.toPath()), StandardCharsets.UTF_8).contains("ACodexT-working")) throw new AssertionError("Codex Chat tool did not write proof:\n" + chatResult);
            result.putString("chat", "codex exec completed a tool roundtrip through Chat Completions"); proof.delete();
            result.putString("stream", "PASS: profile save, 0600 credential permissions, full runtime install, native tools, Responses and Chat Codex tool execution");
            finish(-1, result);
        } catch (Throwable e) {
            result.putString("stream", "FAIL: " + android.util.Log.getStackTraceString(e)); finish(0, result);
        } finally {
            try { if (saved == null) profileFile.delete(); else Files.write(profileFile.toPath(), saved); } catch (Exception ignored) { }
        }
    }

    private void deleteTree(File file) throws Exception {
        if (!file.exists() && !java.nio.file.Files.isSymbolicLink(file.toPath())) return;
        if (file.isDirectory() && !java.nio.file.Files.isSymbolicLink(file.toPath())) {
            File[] children = file.listFiles(); if (children == null) throw new AssertionError("Cannot list " + file);
            for (File child : children) deleteTree(child);
        }
        if (!file.delete()) throw new AssertionError("Cannot delete " + file);
    }
}
