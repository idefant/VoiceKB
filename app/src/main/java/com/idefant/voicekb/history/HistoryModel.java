package com.idefant.voicekb.history;

public class HistoryModel {
    private final long id;
    private final long createdAt;      // epoch milliseconds
    private final String transcript;
    private final String audioFileName; // file name inside the history audio directory, may be empty
    private final long durationSec;     // audio duration in seconds, -1 if unknown
    private final String provider;      // display name, e.g. "Groq"
    private final String model;         // model id, e.g. "whisper-large-v3-turbo"
    private final String errorMessage;  // short error summary if the recognition failed, else null
    private final String errorDetails;  // raw provider body for the error, may be null

    public HistoryModel(long id, long createdAt, String transcript, String audioFileName,
                        long durationSec, String provider, String model,
                        String errorMessage, String errorDetails) {
        this.id = id;
        this.createdAt = createdAt;
        this.transcript = transcript;
        this.audioFileName = audioFileName;
        this.durationSec = durationSec;
        this.provider = provider;
        this.model = model;
        this.errorMessage = errorMessage;
        this.errorDetails = errorDetails;
    }

    public long getId() {
        return id;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public String getTranscript() {
        return transcript;
    }

    public String getAudioFileName() {
        return audioFileName;
    }

    public long getDurationSec() {
        return durationSec;
    }

    public String getProvider() {
        return provider;
    }

    public String getModel() {
        return model;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public String getErrorDetails() {
        return errorDetails;
    }

    public boolean isError() {
        return errorMessage != null && !errorMessage.isEmpty();
    }
}
