package com.acodext;

import java.net.URI;
import java.util.UUID;

public final class Provider {
    public String id = UUID.randomUUID().toString();
    public String name = "";
    public String baseUrl = "";
    public String model = "";
    public String token = "";
    public String protocol = "responses";

    public void validate() {
        name = name.trim();
        model = model.trim();
        token = token.trim();
        if (model.isEmpty()) throw new IllegalArgumentException("Enter a model ID, or tap Fetch models.");
        if (token.isEmpty()) throw new IllegalArgumentException("Enter an API token.");
        if (!id.matches("[a-zA-Z0-9-]{1,80}")) throw new IllegalArgumentException("Invalid profile ID.");
        for (String value : new String[]{name, model, token}) {
            for (int i = 0; i < value.length(); i++) {
                if (Character.isISOControl(value.charAt(i))) throw new IllegalArgumentException("Fields cannot contain control characters.");
            }
        }
        if (!protocol.equals("responses") && !protocol.equals("chat")) throw new IllegalArgumentException("Choose an API protocol.");
        baseUrl = normalizeUrl(baseUrl);
        name = URI.create(baseUrl).getAuthority();
    }

    public static String normalizeUrl(String input) {
        try {
            URI uri = new URI(input.trim());
            String scheme = uri.getScheme();
            if (!("https".equals(scheme) || "http".equals(scheme)) || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null)
                throw new IllegalArgumentException();
            if ("http".equals(scheme) && !uri.getHost().equals("localhost") && !uri.getHost().equals("127.0.0.1") && !uri.getHost().equals("[::1]"))
                throw new IllegalArgumentException("Use HTTPS for a remote provider.");
            String url = input.trim().replaceAll("/+$", "");
            for (String suffix : new String[]{"/chat/completions", "/responses", "/models"}) {
                if (url.endsWith(suffix)) url = url.substring(0, url.length() - suffix.length());
            }
            URI base = new URI(url);
            if (base.getPath() == null || base.getPath().isEmpty()) url += "/v1";
            return url;
        } catch (IllegalArgumentException e) {
            if (e.getMessage() != null) throw e;
            throw new IllegalArgumentException("Enter a valid API base URL, such as https://api.example.com/v1");
        } catch (Exception e) {
            throw new IllegalArgumentException("Enter a valid API base URL.");
        }
    }
}
