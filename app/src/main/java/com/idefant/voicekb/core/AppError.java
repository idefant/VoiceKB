package com.idefant.voicekb.core;

import android.content.Context;

import com.idefant.voicekb.R;

import java.io.InterruptedIOException;
import java.util.Locale;

/**
 * Application error with a split between a short {@code message} (shown to the user) and raw
 * {@code details} (the provider's response body, kept separately and never mixed into the message).
 * Only {@code api} errors carry details.
 */
public final class AppError {

    public enum Kind { MESSAGE, API, TRANSPARENT }

    public final Kind kind;
    public final String message;
    public final String details;

    private AppError(Kind kind, String message, String details) {
        this.kind = kind;
        this.message = message;
        this.details = details;
    }

    /** A ready-made string. */
    public static AppError message(String message) {
        return new AppError(Kind.MESSAGE, message, null);
    }

    /** A provider error: own short {@code message}, raw {@code body} kept in details. */
    public static AppError api(String message, String body) {
        return new AppError(Kind.API, message, body);
    }

    /** A wrapper over a library error (network/parse): verbatim message, no details. */
    public static AppError transparent(String message) {
        return new AppError(Kind.TRANSPARENT, message, null);
    }

    /** The visible text — just the message for every kind. */
    public String intoMessage() {
        return message;
    }

    /**
     * Classifies a transcription failure the same way the keyboard does: network/timeout failures
     * become {@code transparent} (message only); provider responses become {@code api} (short
     * message plus the raw body in details).
     */
    public static AppError fromTranscriptionError(Context context, Throwable e) {
        if (e.getCause() instanceof InterruptedIOException) {
            return transparent(context.getString(R.string.voicekb_history_error_timeout));
        }
        String raw = e.getMessage();
        String low = raw != null ? raw.toLowerCase(Locale.ROOT) : "";
        if (low.contains("api key")) {
            return api(context.getString(R.string.voicekb_history_error_invalid_api_key), raw);
        }
        if (low.contains("quota")) {
            return api(context.getString(R.string.voicekb_history_error_quota), raw);
        }
        if (low.contains("audio duration") || low.contains("content size limit")) {
            return api(context.getString(R.string.voicekb_history_error_too_long), raw);
        }
        if (low.contains("format")) {
            return api(context.getString(R.string.voicekb_history_error_format), raw);
        }
        return api(context.getString(R.string.voicekb_history_error_network), raw);
    }
}
