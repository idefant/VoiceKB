package com.idefant.voicekb.core;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognizerIntent;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.audio.AudioResponseFormat;
import com.openai.models.audio.transcriptions.Transcription;
import com.openai.models.audio.transcriptions.TranscriptionCreateParams;

import com.idefant.voicekb.VoiceKBUtils;
import com.idefant.voicekb.R;
import com.idefant.voicekb.usage.UsageDatabaseHelper;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class VoiceRecognitionActivity extends AppCompatActivity {

    private static final int MICROPHONE_PERMISSION_REQUEST = 1701;

    private enum VoiceUiState {
        IDLE,
        RECORDING,
        PAUSED,
        SENDING
    }

    private final Handler timerHandler = new Handler(Looper.getMainLooper());
    private final Runnable timerRunnable = new Runnable() {
        @Override
        public void run() {
            if (uiState != VoiceUiState.RECORDING) return;
            elapsedTime += 100;
            updateTimer();
            timerHandler.postDelayed(this, 100);
        }
    };

    private MaterialButton recordButton;
    private MaterialButton pauseButton;
    private MaterialButton resetButton;
    private MaterialButton languageButton;
    private ImageView recordIcon;
    private ProgressBar recordProgress;
    private TextView timer;
    private TextView title;
    private View dialogRoot;
    private MediaRecorder recorder;
    private ExecutorService apiThread;
    private AlertDialog languageDialog;
    private SharedPreferences sp;
    private UsageDatabaseHelper usageDb;
    private File audioFile;
    private VoiceUiState uiState = VoiceUiState.IDLE;
    private String selectedLanguage;
    private long elapsedTime;
    private int requestGeneration;
    private boolean waitingForMicrophonePermission;
    private boolean darkTheme;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_voice_recognition);

        sp = getSharedPreferences("com.idefant.voicekb", MODE_PRIVATE);
        usageDb = new UsageDatabaseHelper(this);
        dialogRoot = findViewById(R.id.voice_recognition_dialog);
        recordButton = findViewById(R.id.voice_recognition_record_btn);
        pauseButton = findViewById(R.id.voice_recognition_pause_btn);
        resetButton = findViewById(R.id.voice_recognition_reset_btn);
        languageButton = findViewById(R.id.voice_recognition_language_btn);
        recordIcon = findViewById(R.id.voice_recognition_record_icon);
        recordProgress = findViewById(R.id.voice_recognition_progress);
        timer = findViewById(R.id.voice_recognition_timer);
        title = findViewById(R.id.voice_recognition_title);

        String intentLanguage = getIntent().getStringExtra(RecognizerIntent.EXTRA_LANGUAGE);
        selectedLanguage = TextUtils.isEmpty(intentLanguage)
                ? InputLanguageManager.ensureSelectedLanguage(
                        this, sp, sp.getInt(InputLanguageManager.PREF_INPUT_LANGUAGE_POS, 0))
                : InputLanguageManager.normalizeRecognizerLanguage(intentLanguage);

        recordButton.setOnClickListener(v -> {
            if (uiState == VoiceUiState.SENDING) return;
            if (uiState == VoiceUiState.RECORDING || uiState == VoiceUiState.PAUSED) {
                stopAndTranscribe();
            } else {
                startRecordingWithPermission();
            }
        });
        pauseButton.setOnClickListener(v -> togglePause());
        resetButton.setOnClickListener(v -> resetRecording());
        languageButton.setOnClickListener(v -> showLanguageSelectionPopup());

        applyTheme();
        renderState();
        startRecordingWithPermission();
    }

    private void startRecordingWithPermission() {
        String validationError = getTranscriptionConfigValidationError();
        if (validationError != null) {
            Toast.makeText(this, validationError, Toast.LENGTH_LONG).show();
            uiState = VoiceUiState.IDLE;
            renderState();
            return;
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            waitingForMicrophonePermission = true;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, MICROPHONE_PERMISSION_REQUEST);
            return;
        }
        startRecording();
    }

    private void startRecording() {
        if (uiState == VoiceUiState.RECORDING || uiState == VoiceUiState.PAUSED || uiState == VoiceUiState.SENDING) return;

        NetworkWarmup.warmUpTranscriptionProvider(this, sp);
        audioFile = new File(getCacheDir(), "voice_input_" + System.currentTimeMillis() + ".m4a");
        recorder = new MediaRecorder();
        recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
        recorder.setAudioEncodingBitRate(64000);
        recorder.setAudioSamplingRate(44100);
        recorder.setOutputFile(audioFile);

        try {
            recorder.prepare();
            recorder.start();
        } catch (IOException | RuntimeException e) {
            releaseRecorder();
            Toast.makeText(this, R.string.voicekb_voice_recording_error, Toast.LENGTH_SHORT).show();
            return;
        }

        elapsedTime = 0;
        uiState = VoiceUiState.RECORDING;
        timerHandler.removeCallbacks(timerRunnable);
        timerHandler.post(timerRunnable);
        renderState();
    }

    private void togglePause() {
        if (recorder == null || uiState == VoiceUiState.SENDING || uiState == VoiceUiState.IDLE) return;
        try {
            if (uiState == VoiceUiState.PAUSED) {
                recorder.resume();
                uiState = VoiceUiState.RECORDING;
                timerHandler.post(timerRunnable);
            } else {
                recorder.pause();
                uiState = VoiceUiState.PAUSED;
                timerHandler.removeCallbacks(timerRunnable);
            }
            renderState();
        } catch (RuntimeException e) {
            resetRecording();
        }
    }

    private void resetRecording() {
        requestGeneration++;
        timerHandler.removeCallbacks(timerRunnable);
        releaseRecorder();
        if (apiThread != null) apiThread.shutdownNow();
        if (audioFile != null) audioFile.delete();
        audioFile = null;
        elapsedTime = 0;
        uiState = VoiceUiState.IDLE;
        renderState();
    }

    private void cancelRecognitionAndFinish() {
        if (isFinishing()) return;
        if (languageDialog != null) {
            AlertDialog dialog = languageDialog;
            languageDialog = null;
            dialog.dismiss();
        }
        resetRecording();
        setResult(Activity.RESULT_CANCELED);
        finish();
    }

    private void stopAndTranscribe() {
        if (recorder == null || (uiState != VoiceUiState.RECORDING && uiState != VoiceUiState.PAUSED)) return;
        timerHandler.removeCallbacks(timerRunnable);
        try {
            recorder.stop();
        } catch (RuntimeException e) {
            resetRecording();
            return;
        }
        releaseRecorder();
        uiState = VoiceUiState.SENDING;
        renderState();

        int generation = ++requestGeneration;
        apiThread = Executors.newSingleThreadExecutor();
        apiThread.execute(() -> {
            try {
                String text = transcribeAudio();
                runOnUiThread(() -> {
                    if (generation == requestGeneration) returnRecognitionResult(text);
                });
            } catch (RuntimeException e) {
                runOnUiThread(() -> {
                    if (generation != requestGeneration) return;
                    Toast.makeText(this,
                            getString(R.string.voicekb_voice_transcription_error,
                                    VoiceKBUtils.getTranscriptionProviderName(this, sp)),
                            Toast.LENGTH_LONG).show();
                    uiState = VoiceUiState.IDLE;
                    renderState();
                });
            }
        });
    }

    private String transcribeAudio() {
        TranscriptionApiConfig apiConfig = TranscriptionApiConfig.getActive(this, sp);

        OpenAIOkHttpClient.Builder clientBuilder = OpenAIOkHttpClient.builder()
                .timeout(Duration.ofSeconds(120));
        apiConfig.applyTo(clientBuilder);
        if (sp.getBoolean("com.idefant.voicekb.proxy_enabled", false)) VoiceKBUtils.applyProxy(clientBuilder, sp);

        String stylePrompt = getStylePrompt(selectedLanguage);
        TranscriptionCreateParams.Builder transcriptionBuilder = TranscriptionCreateParams.builder()
                .file(audioFile.toPath())
                .model(apiConfig.model)
                .responseFormat(AudioResponseFormat.JSON);
        if (!"detect".equals(selectedLanguage)) transcriptionBuilder.language(selectedLanguage);
        if (!stylePrompt.isEmpty()) transcriptionBuilder.prompt(stylePrompt);

        Transcription transcription = clientBuilder.build()
                .audio()
                .transcriptions()
                .create(transcriptionBuilder.build())
                .asTranscription();
        usageDb.edit(apiConfig.model, VoiceKBUtils.getAudioDuration(audioFile), 0, 0, apiConfig.provider);
        return transcription.text().strip();
    }

    private String getTranscriptionConfigValidationError() {
        try {
            return TranscriptionApiConfig.getActive(this, sp).validate(this, false);
        } catch (IllegalArgumentException e) {
            return getString(R.string.voicekb_api_config_invalid_headers, e.getMessage());
        }
    }

    private String getStylePrompt(String language) {
        switch (sp.getInt("com.idefant.voicekb.style_prompt_selection", 1)) {
            case 1:
                return VoiceKBUtils.getPunctuationPromptForLanguage(language);
            case 2:
                return sp.getString("com.idefant.voicekb.style_prompt_custom_text", "");
            default:
                return "";
        }
    }

    private void returnRecognitionResult(String text) {
        Intent result = new Intent();
        result.putStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS,
                new ArrayList<>(Collections.singletonList(text)));
        setResult(Activity.RESULT_OK, result);
        finish();
    }

    private void updateTimer() {
        timer.setText(String.format(Locale.getDefault(), "%02d:%02d",
                (int) (elapsedTime / 60000),
                (int) (elapsedTime / 1000) % 60));
    }

    private void renderState() {
        updateTimer();
        int primary = getColor(R.color.voicekb_keyboard_primary);
        int recording = getColor(R.color.voicekb_keyboard_recording);
        applyButtonColor(recordButton, uiState == VoiceUiState.RECORDING ? recording : primary);
        recordButton.setEnabled(uiState != VoiceUiState.SENDING);
        recordProgress.setVisibility(uiState == VoiceUiState.SENDING ? View.VISIBLE : View.GONE);
        timer.setVisibility(uiState == VoiceUiState.RECORDING || uiState == VoiceUiState.PAUSED ? View.VISIBLE : View.GONE);
        resetButton.setVisibility(uiState == VoiceUiState.IDLE ? View.INVISIBLE : View.VISIBLE);
        pauseButton.setVisibility(uiState == VoiceUiState.RECORDING || uiState == VoiceUiState.PAUSED
                ? View.VISIBLE : View.INVISIBLE);
        pauseButton.setIconResource(uiState == VoiceUiState.PAUSED
                ? R.drawable.ic_lucide_play_24 : R.drawable.ic_lucide_pause_24);
        recordIcon.setVisibility(uiState == VoiceUiState.SENDING ? View.GONE : View.VISIBLE);
        if (uiState == VoiceUiState.IDLE) {
            setRecordIcon(R.drawable.ic_lucide_mic_24, 38, 29);
        } else if (uiState != VoiceUiState.SENDING) {
            setRecordIcon(R.drawable.ic_lucide_send_24, 32, 21);
        }
        languageButton.setText(InputLanguageManager.getShortLabel(selectedLanguage));
        languageButton.setEnabled(uiState != VoiceUiState.SENDING);
    }

    private void setRecordIcon(int icon, int sizeDp, int topMarginDp) {
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) recordIcon.getLayoutParams();
        params.width = dp(sizeDp);
        params.height = dp(sizeDp);
        params.topMargin = dp(topMarginDp);
        recordIcon.setLayoutParams(params);
        recordIcon.setImageResource(icon);
    }

    private void applyTheme() {
        String theme = sp.getString("com.idefant.voicekb.theme", "system");
        darkTheme = "dark".equals(theme)
                || ("system".equals(theme)
                && (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES);
        int panel = getPanelColor(R.color.voicekb_keyboard_panel_light, R.color.voicekb_keyboard_panel_dark);
        int surface = getPanelColor(R.color.voicekb_keyboard_button_light, R.color.voicekb_keyboard_button_dark);
        int danger = getPanelColor(R.color.voicekb_keyboard_danger_container_light, R.color.voicekb_keyboard_danger_container_dark);
        int dangerIcon = getPanelColor(R.color.voicekb_keyboard_danger_icon_light, R.color.voicekb_keyboard_danger_icon_dark);
        int icon = getPanelColor(R.color.voicekb_keyboard_icon_light, R.color.voicekb_keyboard_icon_dark);
        int text = getPanelColor(R.color.voicekb_keyboard_text_light, R.color.voicekb_keyboard_text_dark);
        int language = getPanelColor(R.color.voicekb_keyboard_language_light, R.color.voicekb_keyboard_language_dark);
        int accent = getPanelColor(R.color.voicekb_keyboard_primary_pressed, R.color.voicekb_keyboard_language_icon_dark);

        dialogRoot.setBackground(createRoundedBackground(panel, 20));
        applyButtonColor(resetButton, danger);
        resetButton.setIconTint(ColorStateList.valueOf(dangerIcon));
        applyButtonColor(pauseButton, surface);
        pauseButton.setIconTint(ColorStateList.valueOf(icon));
        title.setTextColor(text);
        applyButtonColor(languageButton, language);
        languageButton.setTextColor(accent);
        languageButton.setIconTint(ColorStateList.valueOf(accent));
    }

    private void showLanguageSelectionPopup() {
        if (languageDialog != null || uiState == VoiceUiState.SENDING) return;
        if (uiState == VoiceUiState.RECORDING) togglePause();

        View content = LayoutInflater.from(new ContextThemeWrapper(this, R.style.Theme_VoiceKB))
                .inflate(R.layout.popup_keyboard_language_selection, null);
        tintPopupForTheme(content);
        LinearLayout options = content.findViewById(R.id.language_selection_options);
        List<String> languages = InputLanguageManager.getAvailableLanguages(this, sp);
        for (int i = 0; i < languages.size(); i++) {
            String language = languages.get(i);
            options.addView(createLanguageOptionRow(language));
            if (i < languages.size() - 1) options.addView(createPanelDivider());
        }

        languageDialog = new MaterialAlertDialogBuilder(new ContextThemeWrapper(this, R.style.Theme_VoiceKB))
                .setView(content)
                .create();
        content.findViewById(R.id.language_selection_cancel).setOnClickListener(v -> languageDialog.dismiss());
        languageDialog.setOnDismissListener(ignored -> languageDialog = null);
        languageDialog.show();
        Window window = languageDialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setLayout(dp(356), WindowManager.LayoutParams.WRAP_CONTENT);
        }
    }

    private View createLanguageOptionRow(String language) {
        boolean selected = language.equals(selectedLanguage);
        int accent = getPanelColor(R.color.voicekb_keyboard_primary_pressed, R.color.voicekb_keyboard_language_icon_dark);
        int text = getPanelColor(R.color.voicekb_keyboard_panel_text_light, R.color.voicekb_keyboard_panel_text_dark);
        int muted = getPanelColor(R.color.voicekb_keyboard_muted_text_light, R.color.voicekb_keyboard_muted_text_dark);
        int radio = getPanelColor(R.color.voicekb_keyboard_panel_radio_light, R.color.voicekb_keyboard_panel_radio_dark);
        int surface = selected
                ? getPanelColor(R.color.voicekb_keyboard_panel_selected_light, R.color.voicekb_keyboard_panel_selected_dark)
                : getPanelColor(R.color.voicekb_keyboard_panel_light, R.color.voicekb_keyboard_panel_dark);

        LinearLayout row = new LinearLayout(this);
        row.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(18), 0, dp(18), 0);
        row.setBackgroundColor(surface);
        TypedValue selectable = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, selectable, true);
        row.setForeground(ContextCompat.getDrawable(this, selectable.resourceId));

        FrameLayout radioFrame = new FrameLayout(this);
        radioFrame.setLayoutParams(new LinearLayout.LayoutParams(dp(20), dp(20)));
        GradientDrawable radioBackground = createRoundedBackground(Color.TRANSPARENT, 10);
        radioBackground.setStroke(dp(2), selected ? accent : radio);
        radioFrame.setBackground(radioBackground);
        if (selected) {
            View dot = new View(this);
            dot.setLayoutParams(new FrameLayout.LayoutParams(dp(10), dp(10), Gravity.CENTER));
            dot.setBackground(createRoundedBackground(accent, 5));
            radioFrame.addView(dot);
        }
        row.addView(radioFrame);

        TextView label = new TextView(this);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        labelParams.setMarginStart(dp(12));
        label.setLayoutParams(labelParams);
        label.setText(InputLanguageManager.getDisplayName(this, language));
        label.setTextColor(text);
        label.setTextSize(13);
        row.addView(label);

        TextView code = new TextView(this);
        code.setText(InputLanguageManager.getShortLabel(language));
        code.setTextColor(selected ? accent : muted);
        code.setTextSize(10);
        code.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        row.addView(code);

        row.setOnClickListener(v -> {
            selectedLanguage = InputLanguageManager.selectLanguage(this, sp, language);
            renderState();
            languageDialog.dismiss();
        });
        return row;
    }

    private void tintPopupForTheme(View root) {
        View panel = root.findViewById(R.id.language_selection_panel);
        if (panel != null) panel.setBackground(createRoundedBackground(
                getPanelColor(R.color.voicekb_keyboard_panel_light, R.color.voicekb_keyboard_panel_dark), 20));
        tintPopupView(root);
    }

    private void tintPopupView(View view) {
        String tag = view.getTag() instanceof String ? (String) view.getTag() : "";
        switch (tag) {
            case "panel_surface":
                view.setBackgroundColor(Color.TRANSPARENT);
                break;
            case "panel_divider":
                view.setBackgroundColor(getPanelColor(R.color.voicekb_keyboard_divider_light, R.color.voicekb_keyboard_divider_dark));
                break;
            case "panel_icon_container":
                view.setBackground(createRoundedBackground(
                        getPanelColor(R.color.voicekb_keyboard_enter_light, R.color.voicekb_keyboard_enter_dark), 12));
                break;
            case "panel_title":
                ((TextView) view).setTextColor(getPanelColor(R.color.voicekb_keyboard_panel_title_light, R.color.voicekb_keyboard_panel_title_dark));
                break;
            case "panel_muted":
                ((TextView) view).setTextColor(getPanelColor(R.color.voicekb_keyboard_muted_text_light, R.color.voicekb_keyboard_muted_text_dark));
                break;
            case "panel_accent":
                ((TextView) view).setTextColor(getPanelColor(R.color.voicekb_keyboard_primary_pressed, R.color.voicekb_keyboard_language_icon_dark));
                break;
            case "panel_accent_icon":
                ((ImageView) view).setImageTintList(ColorStateList.valueOf(
                        getPanelColor(R.color.voicekb_keyboard_primary_pressed, R.color.voicekb_keyboard_language_icon_dark)));
                break;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) tintPopupView(group.getChildAt(i));
        }
    }

    private View createPanelDivider() {
        View divider = new View(this);
        divider.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
        divider.setBackgroundColor(getPanelColor(R.color.voicekb_keyboard_divider_light, R.color.voicekb_keyboard_divider_dark));
        return divider;
    }

    private int getPanelColor(int lightColor, int darkColor) {
        return getResources().getColor(darkTheme ? darkColor : lightColor, getTheme());
    }

    private GradientDrawable createRoundedBackground(int color, int radiusDp) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(radiusDp));
        return background;
    }

    private void applyButtonColor(MaterialButton button, int color) {
        button.setBackgroundTintList(ColorStateList.valueOf(color));
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void releaseRecorder() {
        if (recorder != null) {
            recorder.release();
            recorder = null;
        }
    }

    @Override
    protected void onDestroy() {
        timerHandler.removeCallbacks(timerRunnable);
        releaseRecorder();
        if (languageDialog != null) languageDialog.dismiss();
        if (apiThread != null) apiThread.shutdownNow();
        super.onDestroy();
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        cancelRecognitionAndFinish();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (!waitingForMicrophonePermission && languageDialog == null
                && !isChangingConfigurations() && !isFinishing()) {
            cancelRecognitionAndFinish();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != MICROPHONE_PERMISSION_REQUEST) return;
        waitingForMicrophonePermission = false;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startRecording();
        } else {
            setResult(Activity.RESULT_CANCELED);
            finish();
        }
    }
}
