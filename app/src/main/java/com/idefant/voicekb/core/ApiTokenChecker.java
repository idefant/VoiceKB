package com.idefant.voicekb.core;

import android.text.TextUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public final class ApiTokenChecker {

    private static final int BODY_LIMIT = 8 * 1024;

    private ApiTokenChecker() {}

    public static Result check(TranscriptionApiConfig config) throws IOException {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(config.modelsUrl());
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(15000);
            connection.setRequestProperty("Authorization", "Bearer " + config.apiKey);
            for (Map.Entry<String, String> header : config.headers.entrySet()) {
                connection.setRequestProperty(header.getKey(), header.getValue());
            }
            int statusCode = connection.getResponseCode();
            String body = readBody(statusCode >= 200 && statusCode < 300
                    ? connection.getInputStream()
                    : connection.getErrorStream());
            return new Result(statusCode >= 200 && statusCode < 300, statusCode, body);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static String readBody(InputStream inputStream) throws IOException {
        if (inputStream == null) return "";
        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null && builder.length() < BODY_LIMIT) {
                if (builder.length() > 0) builder.append('\n');
                builder.append(line);
            }
        }
        if (builder.length() > BODY_LIMIT) {
            return builder.substring(0, BODY_LIMIT) + "\n...";
        }
        return builder.toString();
    }

    public static final class Result {
        public final boolean success;
        public final int statusCode;
        public final String body;

        private Result(boolean success, int statusCode, String body) {
            this.success = success;
            this.statusCode = statusCode;
            this.body = TextUtils.isEmpty(body) ? "" : body;
        }
    }
}
