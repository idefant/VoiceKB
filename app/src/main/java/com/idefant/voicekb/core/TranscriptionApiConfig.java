package com.idefant.voicekb.core;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.text.TextUtils;

import com.openai.client.okhttp.OpenAIOkHttpClient;

import com.idefant.voicekb.R;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public class TranscriptionApiConfig {

    public static final int PROVIDER_OPENAI = 0;
    public static final int PROVIDER_GROQ = 1;
    public static final int PROVIDER_CUSTOM = 2;

    private static final String PREF = "com.idefant.voicekb.";
    private static final String[] CLOSING_PUNCTUATION = {".", "!", "?", "…"};

    public final int provider;
    public final String providerName;
    public final String baseUrl;
    public final String apiKey;
    public final String model;
    public final Map<String, String> headers;
    public final boolean vpnConfig;

    private TranscriptionApiConfig(int provider, String providerName, String baseUrl, String apiKey,
                                   String model, Map<String, String> headers, boolean vpnConfig) {
        this.provider = provider;
        this.providerName = providerName;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.headers = headers;
        this.vpnConfig = vpnConfig;
    }

    public static TranscriptionApiConfig getActive(Context context, SharedPreferences sp) {
        boolean useVpn = sp.getBoolean(PREF + "vpn_config_enabled", false) && isVpnActive(context);
        return fromPreferences(context, sp, useVpn);
    }

    public static TranscriptionApiConfig fromPreferences(Context context, SharedPreferences sp, boolean vpnConfig) {
        String prefix = vpnConfig ? "vpn_" : "";
        int provider = sp.getInt(PREF + prefix + "transcription_provider", PROVIDER_GROQ);
        if (provider < PROVIDER_OPENAI || provider > PROVIDER_CUSTOM) provider = PROVIDER_GROQ;

        String oldKey = sp.getString(PREF + prefix + "transcription_api_key",
                sp.getString(PREF + "api_key", ""));
        String apiKey;
        String model;
        String baseUrl;
        String providerName;
        String[] providerValues = context.getResources().getStringArray(R.array.voicekb_api_providers_values);
        switch (provider) {
            case PROVIDER_OPENAI:
                providerName = "OpenAI";
                apiKey = sp.getString(PREF + prefix + "transcription_api_key_openai", oldKey);
                model = sp.getString(PREF + prefix + "transcription_openai_model",
                        sp.getString(PREF + "transcription_model", "gpt-4o-mini-transcribe"));
                baseUrl = safeArrayValue(providerValues, provider, "https://api.openai.com/v1/");
                break;
            case PROVIDER_CUSTOM:
                providerName = context.getString(R.string.voicekb_custom_provider);
                apiKey = sp.getString(PREF + prefix + "transcription_api_key_custom", oldKey);
                model = sp.getString(PREF + prefix + "transcription_custom_model", "");
                baseUrl = sp.getString(PREF + prefix + "transcription_custom_host", "");
                break;
            case PROVIDER_GROQ:
            default:
                providerName = "Groq";
                apiKey = sp.getString(PREF + prefix + "transcription_api_key_groq", oldKey);
                model = sp.getString(PREF + prefix + "transcription_groq_model", "whisper-large-v3-turbo");
                baseUrl = safeArrayValue(providerValues, provider, "https://api.groq.com/openai/v1/");
                break;
        }

        boolean additionalEnabled = provider == PROVIDER_CUSTOM
                || sp.getBoolean(additionalEnabledKey(prefix, provider), false);
        if (additionalEnabled && provider != PROVIDER_CUSTOM) {
            baseUrl = sp.getString(additionalUrlKey(prefix, provider), "");
        }
        Map<String, String> headers = additionalEnabled
                ? parseHeaders(sp.getString(additionalHeadersKey(prefix, provider), ""))
                : Collections.emptyMap();

        return new TranscriptionApiConfig(provider, providerName, normalizeBaseUrl(baseUrl),
                sanitizeKey(apiKey), model == null ? "" : model.trim(), headers, vpnConfig);
    }

    public String validate(Context context, boolean tokenCheck) {
        if (TextUtils.isEmpty(apiKey)) {
            return context.getString(R.string.voicekb_api_config_missing_key, providerName);
        }
        if (provider == PROVIDER_CUSTOM && TextUtils.isEmpty(baseUrl)) {
            return context.getString(R.string.voicekb_api_config_missing_url);
        }
        if (provider == PROVIDER_CUSTOM && TextUtils.isEmpty(model)) {
            return context.getString(R.string.voicekb_api_config_missing_model);
        }
        if (!TextUtils.isEmpty(baseUrl) && !(baseUrl.startsWith("http://") || baseUrl.startsWith("https://"))) {
            return context.getString(R.string.voicekb_api_config_invalid_url);
        }
        if (tokenCheck && provider == PROVIDER_CUSTOM) {
            return context.getString(R.string.voicekb_api_check_unsupported_provider);
        }
        return null;
    }

    public void applyTo(OpenAIOkHttpClient.Builder builder) {
        builder.apiKey(apiKey).baseUrl(baseUrl);
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            builder.putHeader(entry.getKey(), entry.getValue());
        }
    }

    public String modelsUrl() {
        return appendPath(baseUrl, "models");
    }

    public boolean isOpenAI() {
        return provider == PROVIDER_OPENAI;
    }

    public static Map<String, String> parseHeaders(String rawHeaders) {
        if (TextUtils.isEmpty(rawHeaders)) return Collections.emptyMap();
        Map<String, String> result = new LinkedHashMap<>();
        String[] lines = rawHeaders.split("\\r?\\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            int colonIndex = trimmed.indexOf(':');
            if (colonIndex <= 0) {
                throw new IllegalArgumentException("Invalid header: " + trimmed);
            }
            String name = trimmed.substring(0, colonIndex).trim();
            String value = trimmed.substring(colonIndex + 1).trim();
            if (!name.matches("^[A-Za-z0-9-]+$") || value.isEmpty()) {
                throw new IllegalArgumentException("Invalid header: " + trimmed);
            }
            result.put(name, value);
        }
        return Collections.unmodifiableMap(result);
    }

    public static String validateHeaders(Context context, String rawHeaders) {
        try {
            parseHeaders(rawHeaders);
            return null;
        } catch (IllegalArgumentException e) {
            return context.getString(R.string.voicekb_api_config_invalid_headers, e.getMessage());
        }
    }

    public static boolean isVpnActive(Context context) {
        ConnectivityManager connectivityManager =
                (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connectivityManager == null) return false;
        Network activeNetwork = connectivityManager.getActiveNetwork();
        if (activeNetwork == null) return false;
        NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(activeNetwork);
        return capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
    }

    private static String safeArrayValue(String[] values, int index, String fallback) {
        return index >= 0 && index < values.length ? values[index] : fallback;
    }

    private static String additionalEnabledKey(String prefix, int provider) {
        return PREF + prefix + "transcription_" + providerKey(provider) + "_additional_parameters_enabled";
    }

    private static String additionalUrlKey(String prefix, int provider) {
        if (provider == PROVIDER_CUSTOM) {
            return PREF + prefix + "transcription_custom_host";
        }
        return PREF + prefix + "transcription_" + providerKey(provider) + "_additional_url";
    }

    private static String additionalHeadersKey(String prefix, int provider) {
        return PREF + prefix + "transcription_" + providerKey(provider) + "_additional_headers";
    }

    private static String providerKey(int provider) {
        if (provider == PROVIDER_OPENAI) return "openai";
        if (provider == PROVIDER_CUSTOM) return "custom";
        return "groq";
    }

    private static String sanitizeKey(String apiKey) {
        return apiKey == null ? "" : apiKey.replaceAll("[^ -~]", "").trim();
    }

    private static String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null) return "";
        String trimmed = baseUrl.trim();
        if (trimmed.isEmpty()) return "";
        return trimmed.endsWith("/") ? trimmed : trimmed + "/";
    }

    private static String appendPath(String baseUrl, String path) {
        if (baseUrl == null || baseUrl.isEmpty()) return "";
        return baseUrl.endsWith("/") ? baseUrl + path : baseUrl + "/" + path;
    }

    public static boolean startsWithSentenceEnding(String text) {
        if (TextUtils.isEmpty(text)) return false;
        String trimmed = text.trim();
        for (String punctuation : CLOSING_PUNCTUATION) {
            if (trimmed.endsWith(punctuation)) return true;
        }
        return false;
    }
}
