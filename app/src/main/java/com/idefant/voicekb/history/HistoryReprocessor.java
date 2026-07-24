package com.idefant.voicekb.history;

import android.content.Context;
import android.content.SharedPreferences;

import com.idefant.voicekb.R;
import com.idefant.voicekb.VoiceKBUtils;
import com.idefant.voicekb.core.InputLanguageManager;
import com.idefant.voicekb.core.TranscriptionApiConfig;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.audio.AudioResponseFormat;
import com.openai.models.audio.transcriptions.Transcription;
import com.openai.models.audio.transcriptions.TranscriptionCreateParams;

import java.io.File;
import java.io.InterruptedIOException;
import java.time.Duration;

/**
 * Re-runs transcription on a stored history recording. Mirrors the request the keyboard sends,
 * so a history entry can be refined (e.g. after changing model or provider) without recording again.
 * The blocking {@link #transcribe} call must run off the main thread.
 */
public final class HistoryReprocessor {

    private HistoryReprocessor() {}

    public static final class Result {
        public final String text;
        public final int provider;
        public final String providerName;
        public final String model;

        Result(String text, int provider, String providerName, String model) {
            this.text = text;
            this.provider = provider;
            this.providerName = providerName;
            this.model = model;
        }
    }

    public static Result transcribe(Context context, SharedPreferences sp, File audioFile) throws Exception {
        String language = InputLanguageManager.ensureSelectedLanguage(context, sp,
                sp.getInt("com.idefant.voicekb.input_language_pos", 0));

        String stylePrompt;
        switch (sp.getInt("com.idefant.voicekb.style_prompt_selection", 1)) {
            case 1:
                stylePrompt = VoiceKBUtils.getPunctuationPromptForLanguage(language);
                break;
            case 2:
                stylePrompt = sp.getString("com.idefant.voicekb.style_prompt_custom_text", "");
                break;
            default:
                stylePrompt = "";
        }

        TranscriptionApiConfig apiConfig = TranscriptionApiConfig.getActive(context, sp);
        String proxyHost = sp.getString("com.idefant.voicekb.proxy_host",
                context.getString(R.string.voicekb_settings_proxy_hint));

        OpenAIOkHttpClient.Builder clientBuilder = OpenAIOkHttpClient.builder()
                .timeout(Duration.ofSeconds(120));
        apiConfig.applyTo(clientBuilder);

        TranscriptionCreateParams.Builder transcriptionBuilder = TranscriptionCreateParams.builder()
                .file(audioFile.toPath())
                .model(apiConfig.model)
                .responseFormat(AudioResponseFormat.JSON);

        if (!language.equals("detect")) transcriptionBuilder.language(language);
        if (!stylePrompt.isEmpty()) transcriptionBuilder.prompt(stylePrompt);
        if (sp.getBoolean("com.idefant.voicekb.proxy_enabled", false)
                && VoiceKBUtils.isValidProxy(proxyHost)) {
            VoiceKBUtils.applyProxy(clientBuilder, sp);
        }

        int retryCount = 0;
        while (true) {
            try {
                Transcription transcription = clientBuilder.build().audio().transcriptions()
                        .create(transcriptionBuilder.build()).asTranscription();
                String text = transcription.text().strip();
                return new Result(text, apiConfig.provider, apiConfig.providerName, apiConfig.model);
            } catch (RuntimeException e) {
                // Отмена (shutdownNow прерывает поток) — не повторяем.
                if (Thread.currentThread().isInterrupted() || e.getCause() instanceof InterruptedIOException) {
                    throw e;
                }
                if (VoiceKBUtils.isTransientTranscriptionError(e) && retryCount < 3) {
                    retryCount++;
                    try {
                        Thread.sleep(3000);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw e;
                    }
                } else {
                    throw e;
                }
            }
        }
    }
}
