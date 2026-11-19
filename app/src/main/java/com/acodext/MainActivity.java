package com.acodext;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.IBinder;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import com.termux.terminal.TerminalSession;
import com.termux.terminal.KeyHandler;
import com.termux.view.TerminalView;
import com.termux.view.TerminalViewClient;
import java.io.File;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;

public final class MainActivity extends Activity implements ServiceConnection, TerminalViewClient, RuntimeInstaller.Listener {
    private TerminalService service;
    private TerminalView terminal;
    private LinearLayout tabs;
    private final Map<String, Button> modifierButtons = new HashMap<>();
    private boolean bound, control, alt, shift, hardwareControl;
    private TextView status;
    private float fontSp = 13;
    private boolean startingUp;
    private File pendingProject;
    private boolean pendingResume;
    private boolean pendingCodex = true;
    private static final String SHOW_PROJECTS_ACTION = "com.acodext.SHOW_PROJECTS";

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        fontSp = getPreferences(0).getFloat("font", 13);
        beginStartup();
    }

    private void beginStartup() {
        startingUp = true;
        android.content.SharedPreferences preferences = getSharedPreferences("startup-v3", 0);
        boolean hasAccess = hasSharedStorageAccess();
        if (!preferences.getBoolean("filePromptSeen", false)) {
            new AlertDialog.Builder(this).setTitle("File access for your projects")
                .setMessage("Allow ACodexT to access shared files so Codex can read and edit your projects? Your API token stays in ACodexT’s private storage.")
                .setPositiveButton("Continue", (d,w) -> {
                    preferences.edit().putBoolean("filePromptSeen", true).apply();
                    if (hasAccess) { linkSharedStorage(false); requestBatteryStartup(); }
                    else requestFileAccess();
                })
                .setNegativeButton("Later", (d,w) -> {
                    preferences.edit().putBoolean("filePromptSeen", true).apply();
                    requestBatteryStartup();
                }).show();
        } else if (hasAccess) { linkSharedStorage(false); requestBatteryStartup(); }
        else requestFileAccess();
    }

    private boolean hasSharedStorageAccess() {
        return checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED
            && checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    private void requestFileAccess() {
        requestPermissions(new String[]{android.Manifest.permission.READ_EXTERNAL_STORAGE, android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, 22);
    }

    private void requestBatteryStartup() {
        android.content.SharedPreferences preferences = getSharedPreferences("startup-v3", 0);
        android.os.PowerManager manager = (android.os.PowerManager)getSystemService(POWER_SERVICE);
        if (!manager.isIgnoringBatteryOptimizations(getPackageName()) && !preferences.getBoolean("batteryAsked", false)) {
            preferences.edit().putBoolean("batteryAsked", true).apply();
            try {
                startActivityForResult(new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, android.net.Uri.parse("package:" + getPackageName())), 23);
                return;
            } catch (android.content.ActivityNotFoundException e) { toast("Battery settings are available in Android app settings."); }
        }
        startingUp = false;
        openWorkspace();
    }

    private void openWorkspace() {
        if (getIntent().getBooleanExtra("providers", false)) { openProviders(); return; }
        try {
            if (Profiles.load(this).items.isEmpty()) {
                openProviders(); return;
            }
        } catch (Exception e) { toast(e.getMessage()); openProviders(); return; }
        initialize();
    }

    private void initialize() {
        if (!RuntimeInstaller.ready(this)) {
            LinearLayout content = new LinearLayout(this); content.setOrientation(1); content.setGravity(Gravity.CENTER); content.setPadding(Ui.dp(this, 28), 0, Ui.dp(this, 28), 0);
            content.addView(Ui.text(this, ">_", 48, Ui.GREEN));
            content.addView(Ui.text(this, "Preparing your terminal", 24, Ui.TEXT));
            TextView description = Ui.text(this, "Unpacking Codex, Bash, Git, Python, and tools into this app’s private storage. This takes a moment on first launch.", 15, Ui.MUTED);
            description.setPadding(0, Ui.dp(this, 18), 0, Ui.dp(this, 24)); content.addView(description);
            content.addView(new ProgressBar(this)); status = Ui.text(this, "Starting…", 13, Ui.MUTED); content.addView(status);
            setContentView(content); RuntimeInstaller.start(this, this); return;
        }
        Intent intent = new Intent(this, TerminalService.class);
        startForegroundService(intent);
        if (!bound) bound = bindService(intent, this, BIND_AUTO_CREATE);
        showProjects();
    }

    private File projectsDirectory() {
        File directory = new File(RuntimePaths.root(this), "home/projects");
        if (!directory.isDirectory()) directory.mkdirs();
        return directory;
    }

    private void showProjects() {
        if (terminal != null) {
            ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(terminal.getWindowToken(), 0);
        }
        clearOneShotModifiers();
        hardwareControl = false;
        terminal = null;
        tabs = null;
        modifierButtons.clear();
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(Ui.dp(this, 22), Ui.dp(this, 20), Ui.dp(this, 22), Ui.dp(this, 18));
        page.setBackgroundColor(Ui.BG);

        TextView title = Ui.text(this, ">_  ACodexT", 27, Ui.GREEN);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        page.addView(title);
        TextView intro = Ui.text(this, "Continue a project or create a new project.", 15, Ui.MUTED);
        intro.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 12));
        page.addView(intro);
        page.addView(Ui.button(this, "+  New project", true, v -> createProject()));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button shell = smallButton("Open shell", v -> openShell());
        shell.setTextColor(Ui.GREEN);
        Button providers = smallButton("Providers & models", v -> openProviders());
        providers.setTextColor(Ui.GREEN);
        actions.addView(shell, new LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1));
        actions.addView(providers, new LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1));
        page.addView(actions);

        TextView heading = Ui.text(this, "PROJECTS", 12, Ui.MUTED);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        heading.setPadding(0, Ui.dp(this, 22), 0, Ui.dp(this, 9));
        page.addView(heading);
        ScrollView scroll = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        File[] projects = projectsDirectory().listFiles(File::isDirectory);
        if (projects == null) projects = new File[0];
        Arrays.sort(projects, Comparator.comparingLong(File::lastModified).reversed());
        if (projects.length == 0) {
            TextView empty = Ui.text(this, "No projects yet. Create a project folder, or open a shell and use git clone.", 15, Ui.MUTED);
            empty.setPadding(Ui.dp(this, 14), Ui.dp(this, 18), Ui.dp(this, 14), Ui.dp(this, 18));
            empty.setBackground(Ui.background(Ui.PANEL, Ui.dp(this, 12)));
            list.addView(empty);
        }
        for (File project : projects) addProjectCard(list, project);
        scroll.addView(list);
        page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout rootRow = new LinearLayout(this);
        rootRow.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        CheckBox rootBox = new CheckBox(this);
        rootBox.setText("Root");
        rootBox.setTextColor(Ui.TEXT);
        rootBox.setTextSize(14);
        rootBox.setChecked(getSharedPreferences("runtime", 0).getBoolean("root_enabled", false));
        rootBox.setOnCheckedChangeListener((button, checked) -> requestRootMode(rootBox, checked));
        rootRow.addView(rootBox, new LinearLayout.LayoutParams(-2, Ui.dp(this, 48)));
        page.addView(rootRow, new LinearLayout.LayoutParams(-1, Ui.dp(this, 52)));
        setContentView(page);
    }

    private void addProjectCard(LinearLayout list, File project) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(Ui.dp(this, 14), Ui.dp(this, 11), Ui.dp(this, 14), Ui.dp(this, 12));
        card.setBackground(Ui.background(Ui.PANEL, Ui.dp(this, 12)));
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(-1, -2);
        cardParams.bottomMargin = Ui.dp(this, 10);
        card.setLayoutParams(cardParams);
        TextView name = Ui.text(this, project.getName(), 17, Ui.TEXT);
        name.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        card.addView(name);
        TextView path = Ui.text(this, "~/projects/" + project.getName(), 12, Ui.MUTED);
        path.setSingleLine(true);
        path.setPadding(0, Ui.dp(this, 3), 0, Ui.dp(this, 6));
        card.addView(path);
        LinearLayout buttons = new LinearLayout(this);
        Button resume = smallButton("Continue", v -> openProject(project, true));
        resume.setTextColor(Ui.GREEN);
        buttons.addView(resume, new LinearLayout.LayoutParams(0, Ui.dp(this, 44), 1));
        card.addView(buttons);
        list.addView(card);
    }

    private void requestRootMode(CheckBox box, boolean checked) {
        if (!checked) {
            getSharedPreferences("runtime", 0).edit().putBoolean("root_enabled", false).apply();
            if (service != null) service.setRootMode(false);
            return;
        }
        box.setEnabled(false);
        new Thread(() -> {
            boolean root = TerminalService.hasRootAccess();
            runOnUiThread(() -> {
                box.setEnabled(true);
                if (!root) {
                    box.setChecked(false);
                    new AlertDialog.Builder(this).setTitle("Root access unavailable")
                        .setMessage("ACodexT could not obtain UID 0. Install a superuser manager, grant ACodexT root access, then try again.")
                        .setPositiveButton("Close", null).show();
                    return;
                }
                getSharedPreferences("runtime", 0).edit().putBoolean("root_enabled", true).apply();
                if (service != null) service.setRootMode(true);
                toast("Root mode enabled for new sessions.");
            });
        }, "RootAccessCheck").start();
    }

    private void createProject() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("Project name");
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setPadding(Ui.dp(this, 14), Ui.dp(this, 10), Ui.dp(this, 14), Ui.dp(this, 10));
        new AlertDialog.Builder(this).setTitle("Create a project")
            .setMessage("ACodexT creates a folder under ~/projects.")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Create", (d,w) -> {
                String projectName = input.getText().toString().trim();
                if (!projectName.matches("[A-Za-z0-9][A-Za-z0-9._ -]{0,63}") || projectName.equals("..")) {
                    toast("Use 1–64 letters, numbers, spaces, dots, dashes, or underscores.");
                    return;
                }
                File project = new File(projectsDirectory(), projectName);
                if (!project.isDirectory() && !project.mkdirs()) { toast("Could not create this project folder."); return; }
                openProject(project, false);
            }).show();
    }

    private void openProject(File project, boolean resume) {
        try {
            if (service == null) { pendingProject = project; pendingResume = resume; pendingCodex = true; return; }
            Provider provider = Profiles.load(this).selected();
            if (provider == null) { openProviders(); return; }
            TerminalSession session = service.openSession(true, provider.id, project, resume);
            showTerminal(session);
            if (service.isRootSession(session) != service.isRootMode()) {
                toast("This session is already open. The Root setting applies when you start a session.");
            }
        } catch (Exception e) { toast(e.getMessage()); }
    }

    private void openShell() {
        try {
            if (service == null) { pendingProject = projectsDirectory(); pendingResume = false; pendingCodex = false; return; }
            showTerminal(service.openSession(false, null, projectsDirectory(), false));
        } catch (Exception e) { toast(e.getMessage()); }
    }

    private void showTerminal(TerminalSession session) {
        if (terminal == null) buildTerminal();
        attach(service.sessions.indexOf(session));
    }

    private void buildTerminal() {
        LinearLayout root = new LinearLayout(this); root.setOrientation(1); root.setBackgroundColor(Ui.BG);
        LinearLayout header = new LinearLayout(this); header.setGravity(Gravity.CENTER_VERTICAL); header.setPadding(Ui.dp(this, 14), 0, Ui.dp(this, 8), 0);
        TextView title = Ui.text(this, ">_  ACodexT", 18, Ui.GREEN); title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, -1, 1)); title.setGravity(Gravity.CENTER_VERTICAL);
        Button home = smallButton("⌂", v -> showProjects()); home.setContentDescription("Projects home"); header.addView(home, new LinearLayout.LayoutParams(Ui.dp(this, 48), -1));
        Button menu = smallButton("⋮", v -> showMenu(v)); menu.setContentDescription("Terminal menu"); header.addView(menu, new LinearLayout.LayoutParams(Ui.dp(this, 44), -1));
        root.addView(header, new LinearLayout.LayoutParams(-1, Ui.dp(this, 52)));
        HorizontalScrollView scroll = new HorizontalScrollView(this); scroll.setHorizontalScrollBarEnabled(false);
        tabs = new LinearLayout(this); tabs.setPadding(Ui.dp(this, 8), 0, Ui.dp(this, 8), 0); scroll.addView(tabs);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, Ui.dp(this, 38)));
        terminal = new TerminalView(this, null); terminal.setTerminalViewClient(this);
        terminal.setFocusable(true); terminal.setFocusableInTouchMode(true);
        terminal.setTextSize(Math.round(fontSp * getResources().getDisplayMetrics().scaledDensity));
        terminal.setPadding(Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 6), 0);
        root.addView(terminal, new LinearLayout.LayoutParams(-1, 0, 1));
        HorizontalScrollView keyScroll = new HorizontalScrollView(this);
        keyScroll.setHorizontalScrollBarEnabled(false); keyScroll.setBackgroundColor(Ui.PANEL);
        LinearLayout keys = new LinearLayout(this); keys.setPadding(Ui.dp(this, 4), Ui.dp(this, 3), Ui.dp(this, 4), Ui.dp(this, 3));
        for (String key : new String[]{"ESC", "TAB", "CTRL", "ALT", "SHIFT", "←", "↑", "↓", "→", "COPY", "PASTE"}) {
            Button b = smallButton(key, v -> extraKey(key, (Button)v));
            b.setTextSize(11);
            if (key.equals("CTRL") || key.equals("ALT") || key.equals("SHIFT")) modifierButtons.put(key, b);
            int width = key.length() > 3 ? 62 : 48;
            keys.addView(b, new LinearLayout.LayoutParams(Ui.dp(this, width), Ui.dp(this, 42)));
        }
        keyScroll.addView(keys); root.addView(keyScroll, new LinearLayout.LayoutParams(-1, Ui.dp(this, 48)));
        setContentView(root);
    }

    private Button smallButton(String name, View.OnClickListener click) {
        Button b = new Button(this); b.setText(name); b.setTextColor(Ui.TEXT); b.setTextSize(14); b.setAllCaps(false); b.setMinWidth(0); b.setMinimumWidth(0);
        b.setPadding(Ui.dp(this, 8), 0, Ui.dp(this, 8), 0); b.setBackgroundColor(android.graphics.Color.TRANSPARENT); b.setOnClickListener(click); return b;
    }

    private void extraKey(String key, Button button) {
        if (key.equals("CTRL")) { control = !control; updateModifierButtons(); return; }
        if (key.equals("ALT")) { alt = !alt; updateModifierButtons(); return; }
        if (key.equals("SHIFT")) { shift = !shift; updateModifierButtons(); return; }
        if (key.equals("COPY")) { copySelection(); clearOneShotModifiers(); return; }
        if (key.equals("PASTE")) { clearOneShotModifiers(); pasteSelection(); return; }
        if (terminal == null || terminal.getCurrentSession() == null) return;
        int code = key.equals("ESC") ? KeyEvent.KEYCODE_ESCAPE : key.equals("TAB") ? KeyEvent.KEYCODE_TAB : key.equals("←") ? KeyEvent.KEYCODE_DPAD_LEFT : key.equals("→") ? KeyEvent.KEYCODE_DPAD_RIGHT : key.equals("↑") ? KeyEvent.KEYCODE_DPAD_UP : KeyEvent.KEYCODE_DPAD_DOWN;
        int modifiers = (control ? KeyHandler.KEYMOD_CTRL : 0) | (alt ? KeyHandler.KEYMOD_ALT : 0) | (shift ? KeyHandler.KEYMOD_SHIFT : 0);
        terminal.handleKeyCode(code, modifiers);
        clearOneShotModifiers();
        terminal.requestFocus();
    }

    private void updateModifierButtons() {
        Button ctrl = modifierButtons.get("CTRL"); if (ctrl != null) ctrl.setTextColor(control ? Ui.GREEN : Ui.TEXT);
        Button altButton = modifierButtons.get("ALT"); if (altButton != null) altButton.setTextColor(alt ? Ui.GREEN : Ui.TEXT);
        Button shiftButton = modifierButtons.get("SHIFT"); if (shiftButton != null) shiftButton.setTextColor(shift ? Ui.GREEN : Ui.TEXT);
    }

    private void clearOneShotModifiers() { control = false; alt = false; shift = false; updateModifierButtons(); }

    private void refreshTabs() {
        if (service == null || tabs == null) return;
        tabs.removeAllViews();
        for (int i = 0; i < service.sessions.size(); i++) {
            final int index = i; TerminalSession session = service.sessions.get(i);
            Button b = smallButton((i + 1) + "  " + session.mSessionName + (session.isRunning() || session.getEmulator() == null ? "" : " · ended"), v -> attach(index));
            b.setTextSize(12); b.setTextColor(i == service.selected ? Ui.GREEN : Ui.MUTED);
            b.setOnLongClickListener(v -> { new AlertDialog.Builder(this).setMessage("Close this terminal session?").setNegativeButton("Cancel", null).setPositiveButton("Close", (d, w) -> {
                if (service == null || terminal == null) return;
                boolean current = session == terminal.getCurrentSession();
                service.closeSession(session);
                if (service.sessions.isEmpty()) showProjects();
                else if (current) attach(service.selected);
                else refreshTabs();
            }).show(); return true; });
            tabs.addView(b, new LinearLayout.LayoutParams(-2, -1));
        }
        tabs.addView(smallButton("+", v -> newSession(false)), new LinearLayout.LayoutParams(Ui.dp(this, 44), -1));
    }

    private void attach(int index) {
        if (service == null || service.sessions.isEmpty()) return;
        service.selected = Math.max(0, Math.min(index, service.sessions.size() - 1));
        terminal.attachSession(service.sessions.get(service.selected));
        terminal.setTypeface(Typeface.MONOSPACE);
        terminal.setTextSize(Math.round(fontSp * getResources().getDisplayMetrics().scaledDensity));
        terminal.requestFocus(); refreshTabs();
    }

    private void newSession(boolean codex) {
        try {
            if (service == null || terminal == null) return;
            Provider p = Profiles.load(this).selected();
            if (codex && p == null) { openProviders(); return; }
            File directory = service.sessionDirectory(terminal.getCurrentSession());
            showTerminal(service.openSession(codex, p == null ? null : p.id, directory, false));
        } catch (Exception e) { toast(e.getMessage()); }
    }

    private void showMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        for (String item : new String[]{"New Codex session", "New shell", "Providers", "Shared storage access", "Paste", "Font larger", "Font smaller", service != null && service.wakeEnabled ? "Release wake lock" : "Keep CPU awake", "Help & licenses", "Stop all sessions"}) menu.getMenu().add(item);
        menu.setOnMenuItemClickListener(item -> {
            String name = item.getTitle().toString();
            if (name.equals("Providers")) openProviders();
            else if (name.equals("Shared storage access")) requestSharedStorage();
            else if (name.equals("New Codex session")) newSession(true);
            else if (name.equals("New shell")) newSession(false);
            else if (name.equals("Paste")) { if (service != null && terminal.getCurrentSession() != null) service.onPasteTextFromClipboard(terminal.getCurrentSession()); }
            else if (name.startsWith("Font")) resize(fontSp + (name.endsWith("larger") ? 1 : -1));
            else if (name.contains("wake") || name.equals("Keep CPU awake")) { if (service != null) service.toggleWakeLock(); }
            else if (name.equals("Stop all sessions")) new AlertDialog.Builder(this).setMessage("Stop all terminal processes?").setNegativeButton("Cancel", null).setPositiveButton("Stop", (d, w) -> { startService(new Intent(this, TerminalService.class).setAction("STOP")); finish(); }).show();
            else help();
            return true;
        }); menu.show();
    }

    private void help() {
        new AlertDialog.Builder(this).setTitle("ACodexT 1.4")
            .setMessage("An independent, unofficial ARM64 Android terminal.\n\nCommands\ncodex — selected provider\ncodex -m MODEL — change model\ncodex --sites — list providers\ncodex --site NAME — switch provider\ncodex resume — previous conversations\npkg --help — package installer\n\nProjects: ~/projects\nLong-press a tab to close it. Pinch to resize text. Long-press terminal text to copy.\n\nCodex runs inside this app’s Android permissions and can edit this app’s files and use the network.\n\nUpstream Codex: Apache-2.0. Termux terminal components and runtime: GPL-3.0 and individual package licenses. Notices: $PREFIX/share/codex-terminal/NOTICE.txt. Source and build instructions accompany this APK.")
            .setPositiveButton("Close", null).show();
    }

    private void resize(float size) { fontSp = Math.max(8, Math.min(28, size)); terminal.setTextSize(Math.round(fontSp * getResources().getDisplayMetrics().scaledDensity)); getPreferences(0).edit().putFloat("font", fontSp).apply(); }
    private void keyboard() {
        if (terminal == null) return;
        terminal.requestFocusFromTouch();
        terminal.post(() -> ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).showSoftInput(terminal, InputMethodManager.SHOW_IMPLICIT));
    }
    private void copySelection() {
        if (terminal == null || service == null || terminal.getCurrentSession() == null) return;
        String selected = terminal.getSelectedText();
        if (selected == null || selected.isEmpty()) { toast("Long-press terminal text, select it, then tap COPY."); return; }
        service.onCopyTextToClipboard(terminal.getCurrentSession(), selected);
        terminal.stopTextSelectionMode();
        toast("Copied to clipboard");
    }

    private void pasteSelection() {
        if (terminal == null || service == null || terminal.getCurrentSession() == null) return;
        service.onPasteTextFromClipboard(terminal.getCurrentSession());
        terminal.requestFocus();
    }

    private void interruptAndShowProjects() {
        TerminalSession session = terminal == null ? null : terminal.getCurrentSession();
        if (session != null) session.writeCodePoint(false, 3);
        showProjects();
    }

    @Override public void onBackPressed() {
        if (terminal != null) { showProjects(); return; }
        super.onBackPressed();
    }
    private void openProviders() { startActivityForResult(new Intent(this, ProvidersActivity.class), 10); }
    private void requestSharedStorage() {
        String[] permissions = {android.Manifest.permission.READ_EXTERNAL_STORAGE, android.Manifest.permission.WRITE_EXTERNAL_STORAGE};
        boolean allowed = true;
        for (String permission : permissions) allowed &= checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED;
        if (!allowed) requestPermissions(permissions, 22); else linkSharedStorage(true);
    }
    private void linkSharedStorage(boolean notify) {
        try {
            java.io.File storage = new java.io.File(RuntimePaths.root(this), "home/storage");
            storage.mkdirs();
            java.io.File shared = new java.io.File(storage, "shared");
            if (!shared.exists() && !java.nio.file.Files.isSymbolicLink(shared.toPath())) android.system.Os.symlink(android.os.Environment.getExternalStorageDirectory().getAbsolutePath(), shared.getAbsolutePath());
            if (notify) toast("Shared storage is available at ~/storage/shared");
        } catch (Exception e) { toast("Could not link shared storage: " + e.getMessage()); }
    }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(request, permissions, grants);
        if (request == 22) {
            if (grants.length == 2 && grants[0] == android.content.pm.PackageManager.PERMISSION_GRANTED && grants[1] == android.content.pm.PackageManager.PERMISSION_GRANTED) linkSharedStorage(false);
            else toast("Shared storage permission was not granted.");
            if (startingUp) requestBatteryStartup();
        }
    }
    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_LONG).show(); }
    @Override protected void onActivityResult(int r, int result, Intent data) {
        super.onActivityResult(r, result, data);
        if (r == 23 && startingUp) { startingUp = false; openWorkspace(); }
        if (r == 10 && terminal == null) initialize();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (SHOW_PROJECTS_ACTION.equals(intent.getAction())) showProjects();
    }

    @Override public void onServiceConnected(ComponentName name, IBinder binder) {
        service = ((TerminalService.LocalBinder)binder).getService();
        service.listener = new TerminalService.Listener() {
            @Override public void changed(TerminalSession session) {
                if (terminal != null && terminal.getCurrentSession() == session) terminal.onScreenUpdated();
            }

            @Override public void finished(TerminalSession session, boolean codex) {
                if (terminal == null) return;
                if (codex && terminal.getCurrentSession() == session) {
                    if (session.getExitStatus() != 0) toast("Codex exited (code " + session.getExitStatus() + ").");
                    showProjects();
                } else refreshTabs();
            }
        };
        if (pendingProject != null) {
            File project = pendingProject;
            boolean resume = pendingResume;
            boolean codex = pendingCodex;
            pendingProject = null;
            if (codex) openProject(project, resume);
            else openShell();
        } else if (terminal != null && !service.sessions.isEmpty()) attach(service.selected);
    }
    @Override public void onServiceDisconnected(ComponentName name) { service = null; }
    @Override public void progress(int files) { if (status != null) status.setText(files + " files unpacked…"); }
    @Override public void complete(Exception error) {
        if (isFinishing() || isDestroyed()) return;
        if (error == null) initialize();
        else new AlertDialog.Builder(this).setTitle("Runtime setup failed").setMessage(error.getMessage() + "\nCheck that at least 1 GB of storage is available.").setPositiveButton("Retry", (d,w) -> initialize()).setNegativeButton("Close", (d,w) -> finish()).show();
    }
    @Override protected void onDestroy() { RuntimeInstaller.detach(this); if (service != null) service.listener = null; if (bound) unbindService(this); super.onDestroy(); }
    @Override public float onScale(float scale) { if (scale > 1.08f) resize(fontSp + 1); else if (scale < 0.92f) resize(fontSp - 1); else return scale; return 1; }
    @Override public void onSingleTapUp(MotionEvent e) { keyboard(); }
    @Override public boolean shouldBackButtonBeMappedToEscape() { return false; }
    @Override public boolean shouldEnforceCharBasedInput() { return true; }
    @Override public boolean shouldUseCtrlSpaceWorkaround() { return false; }
    @Override public boolean isTerminalViewSelected() { return true; }
    @Override public void copyModeChanged(boolean copyMode) { }
    @Override public boolean onKeyDown(int code, KeyEvent event, TerminalSession session) {
        boolean ctrlPressed = event.isCtrlPressed() || control || hardwareControl;
        if (ctrlPressed && code == KeyEvent.KEYCODE_C) {
            interruptAndShowProjects();
            return true;
        }
        if (code == KeyEvent.KEYCODE_VOLUME_DOWN) { hardwareControl = true; return true; }
        if (control || alt || shift) terminal.post(this::clearOneShotModifiers);
        return false;
    }
    @Override public boolean onKeyUp(int code, KeyEvent event) { if (code == KeyEvent.KEYCODE_VOLUME_DOWN) { hardwareControl = false; return true; } return false; }
    @Override public boolean onLongPress(MotionEvent e) { return false; }
    @Override public boolean readControlKey() { return control || hardwareControl; }
    @Override public boolean readAltKey() { return alt; }
    @Override public boolean readShiftKey() { return shift; }
    @Override public boolean readFnKey() { return false; }
    @Override public boolean onCodePoint(int code, boolean ctrl, TerminalSession session) {
        if (ctrl && (code == 3 || code == 'c' || code == 'C')) {
            interruptAndShowProjects();
            return true;
        }
        clearOneShotModifiers();
        return false;
    }
    @Override public void onEmulatorSet() { }
    @Override public void logError(String tag, String text) { android.util.Log.e(tag, text); }
    @Override public void logWarn(String tag, String text) { android.util.Log.w(tag, text); }
    @Override public void logInfo(String tag, String text) { }
    @Override public void logDebug(String tag, String text) { }
    @Override public void logVerbose(String tag, String text) { }
    @Override public void logStackTraceWithMessage(String tag, String text, Exception e) { android.util.Log.e(tag, text, e); }
    @Override public void logStackTrace(String tag, Exception e) { android.util.Log.e(tag, "Terminal error", e); }
}
