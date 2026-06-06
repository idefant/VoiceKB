package com.idefant.voicekb.core;

import android.content.Context;
import android.content.SharedPreferences;

import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URI;
import java.net.URL;
import java.io.InputStream;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class NetworkWarmup {

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Set<String> WARMED_HOSTS = ConcurrentHashMap.newKeySet();

    private NetworkWarmup() {}

    public static void warmUpTranscriptionProvider(Context context, SharedPreferences sp) {
        if (sp.getBoolean("com.idefant.voicekb.proxy_enabled", false)) return;
        warmUp(TranscriptionApiConfig.getActive(context, sp).baseUrl);
    }

    private static void warmUp(String baseUrl) {
        if (baseUrl == null || baseUrl.trim().isEmpty()) return;
        EXECUTOR.execute(() -> {
            HttpURLConnection connection = null;
            boolean connected = false;
            try {
                URI uri = URI.create(baseUrl);
                String host = uri.getHost();
                if (host == null || !WARMED_HOSTS.add(host)) return;

                InetAddress.getByName(host);
                URL url = new URL(uri.getScheme(), host, uri.getPort(), "/");
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("HEAD");
                connection.setConnectTimeout(3000);
                connection.setReadTimeout(3000);
                connection.setInstanceFollowRedirects(false);
                connection.connect();
                connection.getResponseCode();
                InputStream response = connection.getErrorStream();
                if (response != null) response.close();
                connected = true;
            } catch (Exception ignored) {
                try {
                    URI uri = URI.create(baseUrl);
                    if (uri.getHost() != null) WARMED_HOSTS.remove(uri.getHost());
                } catch (Exception ignoredUri) {}
                // Warm-up is best effort. The real transcription request still handles errors.
            } finally {
                if (!connected && connection != null) connection.disconnect();
            }
        });
    }
}
