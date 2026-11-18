package com.acodext;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.text.method.PasswordTransformationMethod;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ProvidersActivity extends Activity {
    private Profiles profiles;
    private String editingId;
    private EditText url, token;
    private AutoCompleteTextView model;
    private Spinner saved, protocol;
    private TextView status;
    private Button fetch;
    private boolean loadingSpinner;
    private final java.util.concurrent.ExecutorService network = java.util.concurrent.Executors.newSingleThreadExecutor();

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        try { profiles = Profiles.load(this); }
        catch (Exception e) {
            new AlertDialog.Builder(this).setTitle("Saved providers unavailable").setMessage(e.getMessage()).setPositiveButton("Close", (d,w) -> finish()).show();
            return;
        }
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true);
        LinearLayout body = new LinearLayout(this); body.setOrientation(1); body.setPadding(Ui.dp(this, 22), Ui.dp(this, 20), Ui.dp(this, 22), Ui.dp(this, 28)); scroll.addView(body);
        TextView eyebrow = Ui.text(this, ">_  ACODEXT", 12, Ui.GREEN); eyebrow.setLetterSpacing(0.12f); body.addView(eyebrow);
        TextView title = Ui.text(this, "Your agent, your provider", 27, Ui.TEXT); title.setTypeface(Typeface.DEFAULT, Typeface.BOLD); title.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 8)); body.addView(title);
        TextView description = Ui.text(this, "Connect an API provider, choose a model, then work in your own terminal.", 15, Ui.MUTED); body.addView(description);

        label(body, "Saved providers"); saved = new Spinner(this); body.addView(saved, new LinearLayout.LayoutParams(-1, Ui.dp(this, 48)));
        url = field(body, "API base URL", "https://api.xingchiapi.com/v1", false); url.setId(R.id.provider_url); url.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        token = field(body, "API token", "Paste your API token", true); token.setId(R.id.provider_token);
        CheckBox reveal = new CheckBox(this); reveal.setText("Show token"); reveal.setTextColor(Ui.MUTED); reveal.setTextSize(13);
        reveal.setOnCheckedChangeListener((v, checked) -> { token.setTransformationMethod(checked ? null : PasswordTransformationMethod.getInstance()); token.setSelection(token.length()); }); body.addView(reveal);
        label(body, "API protocol"); protocol = new Spinner(this);
        protocol.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, new String[]{"Responses API · native Codex", "Chat Completions · compatibility"}));
        body.addView(protocol, new LinearLayout.LayoutParams(-1, Ui.dp(this, 48)));
        TextView protocolHelp = Ui.text(this, "Choose Responses if the provider supports /v1/responses. Use compatibility for models served through /v1/chat/completions; text, images, and function tools are supported.", 12, Ui.MUTED); body.addView(protocolHelp);

        label(body, "Model ID"); model = new AutoCompleteTextView(this); model.setId(R.id.provider_model); styleField(model, "The exact model name from your provider"); model.setThreshold(0); body.addView(model, new LinearLayout.LayoutParams(-1, Ui.dp(this, 52)));
        fetch = Ui.button(this, "Fetch models", false, v -> fetchModels()); body.addView(fetch);
        status = Ui.text(this, "Tokens stay in this app’s private storage. Profiles and conversations are separate from your installed Termux.", 13, Ui.MUTED); status.setId(R.id.provider_status); status.setPadding(0, Ui.dp(this, 18), 0, Ui.dp(this, 8)); body.addView(status);
        Button launch = Ui.button(this, "Save & open terminal", true, v -> { if (saveProfile()) { setResult(RESULT_OK); finish(); } }); launch.setId(R.id.save_provider); body.addView(launch);
        body.addView(Ui.button(this, "Save profile", false, v -> { if (saveProfile()) { refreshChoices(editingId); status.setText("Provider saved. Start a new Codex session to use it."); } }));
        body.addView(Ui.button(this, "Delete profile", false, v -> deleteProfile()));
        body.addView(Ui.button(this, "Open shell without setup", false, v -> { setResult(RESULT_CANCELED); finish(); }));
        TextView cli = Ui.text(this, "In the shell: codex -m MODEL\nUse codex exec to try a prompt with your provider.\nUse /help inside Codex for CLI commands.", 13, Ui.MUTED); cli.setTypeface(Typeface.MONOSPACE); cli.setPadding(0, Ui.dp(this, 18), 0, 0); body.addView(cli);
        setContentView(scroll);
        saved.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View view, int index, long id) {
                if (!loadingSpinner) load(index < profiles.items.size() ? profiles.items.get(index) : null);
            }
            @Override public void onNothingSelected(AdapterView<?> p) { }
        });
        Provider selected = profiles.selected(); refreshChoices(selected == null ? null : selected.id);
    }

    private void label(LinearLayout parent, String title) {
        TextView v = Ui.text(this, title, 13, Ui.TEXT); v.setTypeface(Typeface.DEFAULT, Typeface.BOLD); v.setPadding(0, Ui.dp(this, 18), 0, Ui.dp(this, 7)); parent.addView(v);
    }

    private void styleField(EditText input, String hint) {
        input.setTextColor(Ui.TEXT); input.setHintTextColor(Ui.MUTED); input.setTextSize(15); input.setHint(hint); input.setSingleLine(true);
        input.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), 0); input.setBackground(Ui.background(Ui.PANEL, Ui.dp(this, 9)));
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setSaveEnabled(false); input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
    }

    private EditText field(LinearLayout parent, String title, String hint, boolean password) {
        label(parent, title); EditText field = new EditText(this); styleField(field, hint);
        if (password) { field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD); field.setTransformationMethod(PasswordTransformationMethod.getInstance()); }
        parent.addView(field, new LinearLayout.LayoutParams(-1, Ui.dp(this, 52))); return field;
    }

    private void refreshChoices(String selectedId) {
        loadingSpinner = true;
        List<String> entries = new ArrayList<>(); int selectedIndex = profiles.items.size();
        for (int i = 0; i < profiles.items.size(); i++) {
            Provider p = profiles.items.get(i); entries.add(p.name + " · " + p.model + (p.id.equals(profiles.active) ? " · active" : ""));
            if (p.id.equals(selectedId)) selectedIndex = i;
        }
        entries.add("+ Add provider");
        saved.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, entries)); saved.setSelection(selectedIndex);
        load(selectedIndex < profiles.items.size() ? profiles.items.get(selectedIndex) : null);
        saved.post(() -> loadingSpinner = false);
    }

    private void load(Provider p) {
        editingId = p == null ? null : p.id;
        url.setText(p == null ? "" : p.baseUrl);
        model.setText(p == null ? "" : p.model, false); token.setText(p == null ? "" : p.token);
        protocol.setSelection(p != null && p.protocol.equals("chat") ? 1 : 0);
    }

    private Provider read(boolean requireModel) {
        Provider p = new Provider(); if (editingId != null) p.id = editingId;
        p.baseUrl = url.getText().toString().trim(); p.model = model.getText().toString().trim(); p.token = token.getText().toString().trim();
        p.protocol = protocol.getSelectedItemPosition() == 1 ? "chat" : "responses";
        if (requireModel) p.validate();
        else {
            p.baseUrl = Provider.normalizeUrl(p.baseUrl);
            if (p.token.isEmpty()) throw new IllegalArgumentException("Enter an API token first.");
            for (int i = 0; i < p.token.length(); i++) if (Character.isISOControl(p.token.charAt(i))) throw new IllegalArgumentException("The token contains an invalid character.");
        }
        return p;
    }

    private boolean saveProfile() {
        try {
            Provider p = read(true);
            Profiles next = new Profiles(); next.items.addAll(profiles.items); next.items.removeIf(old -> old.id.equals(p.id)); next.items.add(p); next.active = p.id;
            next.save(this); profiles = next; editingId = p.id; return true;
        } catch (Exception e) { error(e.getMessage()); return false; }
    }

    private void deleteProfile() {
        if (editingId == null) { load(null); return; }
        new AlertDialog.Builder(this).setMessage("Delete this provider and its saved token? Existing terminal sessions keep running. Conversations are kept in ~/.codex-sites.")
            .setNegativeButton("Cancel", null).setPositiveButton("Delete", (d,w) -> {
                try {
                    Profiles next = new Profiles(); next.items.addAll(profiles.items); next.items.removeIf(p -> p.id.equals(editingId));
                    next.active = next.items.isEmpty() ? "" : next.items.get(0).id; next.save(this); profiles = next; refreshChoices(next.active);
                } catch (Exception e) { error(e.getMessage()); }
            }).show();
    }

    private void fetchModels() {
        final Provider p;
        try { p = read(false); } catch (Exception e) { error(e.getMessage()); return; }
        busy(true, "Fetching models…");
        network.submit(() -> {
            try {
                JSONObject response = request(p, "/models", null);
                JSONArray data = response.getJSONArray("data"); List<String> ids = new ArrayList<>();
                for (int i = 0; i < data.length(); i++) { String id = data.getJSONObject(i).optString("id"); if (!id.isEmpty()) ids.add(id); }
                Collections.sort(ids);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    busy(false, ids.isEmpty() ? "The provider returned no models. Enter a model ID manually." : ids.size() + " models available. Type to filter, or choose below.");
                    model.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_dropdown_item_1line, ids));
                    if (!ids.isEmpty()) new AlertDialog.Builder(this).setTitle("Choose a model").setItems(ids.toArray(new String[0]), (d, which) -> model.setText(ids.get(which), false)).setNegativeButton("Cancel", null).show();
                });
            } catch (Exception e) { runOnUiThread(() -> busy(false, e.getMessage() + " You can still enter a model ID manually.")); }
        });
    }

    static JSONObject request(Provider p, String path, JSONObject body) throws Exception {
        HttpURLConnection connection = (HttpURLConnection)new URL(p.baseUrl + path).openConnection();
        connection.setInstanceFollowRedirects(false); connection.setConnectTimeout(15000); connection.setReadTimeout(90000);
        connection.setRequestProperty("Authorization", "Bearer " + p.token); connection.setRequestProperty("Accept", "application/json");
        try {
            if (body != null) {
                connection.setRequestMethod("POST"); connection.setDoOutput(true); connection.setRequestProperty("Content-Type", "application/json");
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8); connection.setFixedLengthStreamingMode(bytes.length);
                try (java.io.OutputStream out = connection.getOutputStream()) { out.write(bytes); }
            }
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) throw new IllegalStateException("Provider returned HTTP " + code + (code == 401 || code == 403 ? ". Check the token and account permissions." : code == 404 ? ". Check the API base URL and protocol." : ". Check the model, balance, and provider status."));
            try (InputStream in = connection.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192]; int n;
                while ((n = in.read(buffer)) != -1) { out.write(buffer, 0, n); if (out.size() > 8 * 1024 * 1024) throw new IllegalStateException("The provider response is too large."); }
                return new JSONObject(out.toString("UTF-8"));
            }
        } catch (java.net.SocketTimeoutException e) { throw new IllegalStateException("The provider request timed out. Check the connection or try another model."); }
        catch (java.io.IOException e) { throw new IllegalStateException("Could not connect to the provider. Check the URL and network."); }
        finally { connection.disconnect(); }
    }

    private void error(String message) { status.setText(message); status.setTextColor(0xffffb4ab); }
    private void busy(boolean busy, String message) {
        if (isFinishing() || isDestroyed()) return;
        fetch.setEnabled(!busy); status.setTextColor(Ui.MUTED); status.setText(message);
    }
    @Override protected void onDestroy() { network.shutdownNow(); super.onDestroy(); }
}
