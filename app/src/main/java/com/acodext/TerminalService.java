package com.acodext;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;
import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

public final class TerminalService extends Service implements TerminalSessionClient {
    public final class LocalBinder extends Binder { TerminalService getService() { return TerminalService.this; } }
    interface Listener { void changed(TerminalSession session); }
    final List<TerminalSession> sessions = new ArrayList<>();
    Listener listener;
    int selected;
    private final LocalBinder binder = new LocalBinder();
    private PowerManager.WakeLock wakeLock;
    boolean wakeEnabled;
    private boolean rootMode;

    @Override public void onCreate() {
        super.onCreate();
        rootMode = getSharedPreferences("runtime", 0).getBoolean("root_enabled", false);
        ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(new NotificationChannel("terminal", "Terminal sessions", NotificationManager.IMPORTANCE_LOW));
        updateNotification();
    }

    void setRootMode(boolean enabled) { rootMode = enabled; }

    static boolean hasRootAccess() {
        Process process = null;
        try {
            process = new ProcessBuilder("su", "-c", "id -u").redirectErrorStream(true).start();
            if (!process.waitFor(60, TimeUnit.SECONDS)) return false;
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) output.append(line).append('\n');
            }
            return process.exitValue() == 0 && output.toString().trim().equals("0");
        } catch (Exception ignored) {
            return false;
        } finally {
            if (process != null) process.destroy();
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "STOP".equals(intent.getAction())) {
            for (TerminalSession s : new ArrayList<>(sessions)) s.finishIfRunning();
            sessions.clear();
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_NOT_STICKY;
    }

    private void updateNotification() {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, TerminalService.class).setAction("STOP"), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, "terminal").setSmallIcon(R.drawable.ic_launcher).setContentTitle("ACodexT")
            .setContentText(sessions.size() + " session(s)" + (wakeEnabled ? " · Wake lock on" : ""))
            .setOngoing(true).setContentIntent(open).setShowWhen(false).addAction(new Notification.Action.Builder(null, "Stop sessions", stop).build()).build();
        startForeground(41, n);
    }

    TerminalSession createSession(boolean codex, String profileId, File project, boolean resume) {
        if (!RuntimeInstaller.ready(this)) throw new IllegalStateException("The terminal runtime is still installing.");
        String files = RuntimePaths.root(this).getAbsolutePath();
        String prefix = files + "/usr";
        String home = files + "/home";
        File cwd = project == null ? new File(home, "projects") : project;
        cwd.mkdirs();
        List<String> environment = new ArrayList<>();
        environment.add("HOME=" + home); environment.add("PREFIX=" + prefix);
        environment.add("PATH=" + prefix + "/bin:/system/bin:/system/xbin");
        environment.add("LD_LIBRARY_PATH=" + prefix + "/lib");
        environment.add("TMPDIR=" + prefix + "/tmp"); environment.add("SHELL=" + prefix + "/bin/bash");
        environment.add("TERM=xterm-256color"); environment.add("COLORTERM=truecolor"); environment.add("LANG=C.UTF-8");
        environment.add("ANDROID_ROOT=/system"); environment.add("ANDROID_DATA=/data");
        environment.add("SSL_CERT_FILE=" + prefix + "/etc/tls/cert.pem");
        environment.add("SSL_CERT_DIR=" + prefix + "/etc/tls/certs");
        environment.add("OPENSSL_CONF=" + prefix + "/etc/tls/openssl.cnf");
        environment.add("GIT_EXEC_PATH=" + prefix + "/libexec/git-core");
        environment.add("PYTHONHOME=" + prefix);
        environment.add("ACODEXT_APP_UID=" + android.os.Process.myUid());
        environment.add("ACODEXT_APP_GID=" + android.os.Process.myUid());
        if (profileId != null && !profileId.isEmpty()) environment.add("CODEX_PROFILE=" + profileId);
        if (codex) environment.add("CODEX_AUTOSTART=" + (resume ? "resume" : "new"));
        String shellPath = prefix + "/bin/bash";
        String[] shellArgs = new String[]{"bash", "--rcfile", prefix + "/share/codex-terminal/bashrc", "-i"};
        if (rootMode) {
            if (!hasRootAccess()) throw new IllegalStateException("Root access was revoked. Disable Root mode or grant ACodexT UID 0.");
            StringBuilder rootCommand = new StringBuilder();
            rootCommand.append("cd ").append(shellQuote(cwd.getAbsolutePath())).append("; umask 000;");
            for (String variable : environment) {
                int separator = variable.indexOf('=');
                if (separator > 0) rootCommand.append("export ").append(variable, 0, separator).append('=')
                    .append(shellQuote(variable.substring(separator + 1))).append(';');
            }
            rootCommand.append("exec ").append(shellQuote(prefix + "/bin/bash"))
                .append(" --rcfile ").append(shellQuote(prefix + "/share/codex-terminal/bashrc")).append(" -i");
            shellPath = "su";
            shellArgs = new String[]{"su", "-c", rootCommand.toString()};
        }
        TerminalSession s = new TerminalSession(shellPath, cwd.getAbsolutePath(), shellArgs, environment.toArray(new String[0]), 12000, this);
        s.mSessionName = (rootMode ? "Root · " : "") + (codex ? "Codex · " : "Shell · ") + cwd.getName();
        sessions.add(s); selected = sessions.size() - 1;
        updateNotification();
        return s;
    }

    private static String shellQuote(String value) { return "'" + value.replace("'", "'\\''") + "'"; }

    void closeSession(TerminalSession s) {
        s.finishIfRunning(); sessions.remove(s); selected = Math.min(selected, Math.max(0, sessions.size() - 1)); updateNotification();
    }

    void toggleWakeLock() {
        if (wakeLock == null) wakeLock = ((PowerManager)getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "codext:terminal");
        wakeEnabled = !wakeEnabled;
        if (wakeEnabled) wakeLock.acquire(); else if (wakeLock.isHeld()) wakeLock.release();
        updateNotification();
    }

    @Override public IBinder onBind(Intent intent) { return binder; }
    @Override public void onDestroy() {
        listener = null;
        for (TerminalSession s : sessions) s.finishIfRunning();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        super.onDestroy();
    }
    private void changed(TerminalSession s) { if (listener != null) listener.changed(s); }
    @Override public void onTextChanged(TerminalSession s) { changed(s); }
    @Override public void onTitleChanged(TerminalSession s) { changed(s); }
    @Override public void onSessionFinished(TerminalSession s) { changed(s); }
    @Override public void onCopyTextToClipboard(TerminalSession s, String text) {
        ((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Terminal", text));
    }
    @Override public void onPasteTextFromClipboard(TerminalSession s) {
        ClipboardManager clipboard = (ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
        if (clipboard.hasPrimaryClip() && clipboard.getPrimaryClip().getItemCount() > 0 && s.getEmulator() != null)
            s.getEmulator().paste(clipboard.getPrimaryClip().getItemAt(0).coerceToText(this).toString());
    }
    @Override public void onBell(TerminalSession s) { }
    @Override public void onColorsChanged(TerminalSession s) { changed(s); }
    @Override public void onTerminalCursorStateChange(boolean state) { }
    @Override public Integer getTerminalCursorStyle() { return null; }
    @Override public void logError(String tag, String text) { Log.e(tag, text); }
    @Override public void logWarn(String tag, String text) { Log.w(tag, text); }
    @Override public void logInfo(String tag, String text) { }
    @Override public void logDebug(String tag, String text) { }
    @Override public void logVerbose(String tag, String text) { }
    @Override public void logStackTraceWithMessage(String tag, String text, Exception e) { Log.e(tag, text, e); }
    @Override public void logStackTrace(String tag, Exception e) { Log.e(tag, "Terminal error", e); }
}
