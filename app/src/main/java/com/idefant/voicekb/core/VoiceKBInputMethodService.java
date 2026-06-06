package com.idefant.voicekb.core;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.inputmethodservice.InputMethodService;
import android.icu.text.BreakIterator;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.audio.AudioResponseFormat;
import com.openai.models.audio.transcriptions.Transcription;
import com.openai.models.audio.transcriptions.TranscriptionCreateParams;

import com.idefant.voicekb.VoiceKBUtils;
import com.idefant.voicekb.R;
import com.idefant.voicekb.settings.VoiceKBSettingsActivity;
import com.idefant.voicekb.usage.UsageDatabaseHelper;

import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// MAIN CLASS
public class VoiceKBInputMethodService extends InputMethodService {

    // define handlers and runnables for background tasks
    private static final int DELETE_LOOKBACK_CHARACTERS = 64;
    private static final String PREF_RESEND_FILE_NAME = "com.idefant.voicekb.resend_file_name";
    private static final String PREF_RETURN_TO_PREVIOUS_KEYBOARD = "com.idefant.voicekb.return_to_previous_keyboard";
    private static final String PREF_QUICK_SETTINGS_BUTTON = "com.idefant.voicekb.quick_settings_button";
    private static final String PREF_SMART_PRESERVE_ABBREVIATIONS = "com.idefant.voicekb.smart_insertion_preserve_abbreviations";
    private static final String PREF_SMART_CAPITALIZE_AFTER_SENTENCE = "com.idefant.voicekb.smart_insertion_capitalize_after_sentence";
    private static final String PREF_SMART_LOWERCASE_UNFINISHED = "com.idefant.voicekb.smart_insertion_lowercase_unfinished_sentence";
    private static final String PREF_SMART_CAPITALIZE_MARKDOWN_LIST = "com.idefant.voicekb.smart_insertion_capitalize_markdown_list";
    private static final String PREF_SMART_CAPITALIZE_QUOTE = "com.idefant.voicekb.smart_insertion_capitalize_quote";
    private static final String PREF_SMART_BLACKLIST = "com.idefant.voicekb.smart_insertion_blacklist";
    private static final String SENTENCE_END_CHARS = ".!?\u2026";
    private static final String CLOSING_AFTER_SENTENCE_CHARS = "\")]}'\u00BB\u201D\u2019\u203A\u300D\u300F\uFF09]}";
    private Handler mainHandler;
    private Handler deleteHandler;
    private Handler recordTimeHandler;
    private Runnable deleteRunnable;
    private Runnable recordTimeRunnable;

    // define variables and objects
    private long elapsedTime;
    private boolean isDeleting = false;
    private long startDeleteTime = 0;
    private int currentDeleteDelay = 50;
    private boolean isRecording = false;
    private boolean isPaused = false;
    private boolean vibrationEnabled = true;
    private boolean audioFocusEnabled = true;
    private TextView selectedCharacter = null;
    private boolean spaceButtonUserHasSwiped = false;
    private int currentInputLanguagePos;
    private String currentInputLanguageValue;
    private boolean transientVoiceSession = false;
    private boolean switchingToPreviousKeyboard = false;
    private boolean suppressTransientKeyboardReturnOnce = false;
    private boolean instantRecordingConsumedForCurrentImeSelection = false;
    private boolean keepKeyboardAfterCurrentTranscription = false;
    private KeyboardUiState keyboardUiState = KeyboardUiState.IDLE;
    private boolean keyboardThemeDark = false;
    private AlertDialog activeDialog;
    private final Set<AlertDialog> dialogsDismissedWithoutImeRestore =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private boolean preserveInputViewAfterDialogDismiss = false;
    private boolean restoreInputViewStateAfterDialogDismiss = false;
    private int dialogDismissRestoreGeneration = 0;
    private boolean editorUndoAvailable = false;
    private boolean editorRedoAvailable = false;

    private enum KeyboardUiState {
        IDLE,
        PREPARING,
        RECORDING,
        PAUSED,
        SENDING
    }

    // Swipe-to-select-words state
    private boolean isSwipeSelectingWords = false;
    private float backspaceStartX = 0f;
    private int swipeBaseCursor = -1;
    private List<Integer> swipeWordBoundaries = null;
    private int swipeSelectedSteps = 0;

    private MediaRecorder recorder;
    private ExecutorService speechApiThread;
    private File audioFile;
    private Vibrator vibrator;
    private SharedPreferences sp;
    private AudioManager am;
    private AudioFocusRequest audioFocusRequest;
    private BroadcastReceiver bluetoothScoReceiver;

    // Bluetooth/SCO state
    private boolean isBluetoothScoStarted = false; // true only when SCO is CONNECTED
    private boolean isPreparingRecording = false; // true while we wait for SCO before starting recorder
    private boolean recordingPending = false;     // flag to start recording after SCO connected
    private boolean waitingForSco = false;        // we're actively waiting for SCO
    private boolean recordingUsesBluetooth = false; // current recording actually uses BT mic
    private Handler bluetoothHandler;             // handler for timeouts
    private Runnable scoTimeoutRunnable;

    // define views
    private ConstraintLayout voicekbKeyboardView;
    private MaterialButton settingsButton;
    private MaterialButton returnPreviousButton;
    private MaterialButton recordButton;
    private MaterialButton fileTranscriptionButton;
    private MaterialButton resendButton;
    private MaterialButton backspaceButton;
    private MaterialButton switchButton;
    private MaterialButton trashButton;
    private MaterialButton spaceButton;
    private MaterialButton pauseButton;
    private MaterialButton enterButton;
    private MaterialButton languageButton;
    private ImageView recordIcon;
    private ImageView recordBluetoothBadge;
    private ProgressBar recordProgress;
    private TextView recordTimer;
    private View keyboardHeader;
    private View specialCharactersScrim;
    private ConstraintLayout infoCl;
    private TextView infoTv;
    private Button infoYesButton;
    private Button infoNoButton;
    private MaterialButton editUndoButton;
    private MaterialButton editRedoButton;
    private LinearLayout overlayCharactersLl;

    // Keep screen awake while recording
    private boolean keepScreenAwakeApplied = false;

    UsageDatabaseHelper usageDb;

    // start method that is called when user opens the keyboard
    @SuppressLint("ClickableViewAccessibility")
    @Override
    public View onCreateInputView() {
        Context context = new ContextThemeWrapper(this, R.style.Theme_VoiceKB);

        // initialize some stuff
        mainHandler = new Handler(Looper.getMainLooper());
        deleteHandler = new Handler();
        recordTimeHandler = new Handler(Looper.getMainLooper());
        bluetoothHandler = new Handler(Looper.getMainLooper());

        vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        sp = getSharedPreferences("com.idefant.voicekb", MODE_PRIVATE);
        usageDb = new UsageDatabaseHelper(this);
        vibrationEnabled = sp.getBoolean("com.idefant.voicekb.vibration", true);
        currentInputLanguagePos = sp.getInt("com.idefant.voicekb.input_language_pos", 0);

        voicekbKeyboardView = (ConstraintLayout) LayoutInflater.from(context).inflate(R.layout.activity_voicekb_keyboard_view, null);
        voicekbKeyboardView.setKeepScreenOn(false);
        keepScreenAwakeApplied = false;
        ViewCompat.setOnApplyWindowInsetsListener(voicekbKeyboardView, (v, insets) -> {
            v.setPadding(0, 0, 0, insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom);
            return insets;  // fix for overlapping with navigation bar on Android 15+
        });

        settingsButton = voicekbKeyboardView.findViewById(R.id.settings_btn);
        returnPreviousButton = voicekbKeyboardView.findViewById(R.id.return_previous_btn);
        recordButton = voicekbKeyboardView.findViewById(R.id.record_btn);
        fileTranscriptionButton = voicekbKeyboardView.findViewById(R.id.file_transcription_btn);
        resendButton = voicekbKeyboardView.findViewById(R.id.resend_btn);
        backspaceButton = voicekbKeyboardView.findViewById(R.id.backspace_btn);
        switchButton = voicekbKeyboardView.findViewById(R.id.switch_btn);
        trashButton = voicekbKeyboardView.findViewById(R.id.trash_btn);
        spaceButton = voicekbKeyboardView.findViewById(R.id.space_btn);
        pauseButton = voicekbKeyboardView.findViewById(R.id.pause_btn);
        enterButton = voicekbKeyboardView.findViewById(R.id.enter_btn);
        languageButton = voicekbKeyboardView.findViewById(R.id.language_btn);
        recordIcon = voicekbKeyboardView.findViewById(R.id.record_icon);
        recordBluetoothBadge = voicekbKeyboardView.findViewById(R.id.record_bluetooth_badge);
        recordProgress = voicekbKeyboardView.findViewById(R.id.record_progress);
        recordTimer = voicekbKeyboardView.findViewById(R.id.record_timer);
        keyboardHeader = voicekbKeyboardView.findViewById(R.id.keyboard_header);
        specialCharactersScrim = voicekbKeyboardView.findViewById(R.id.special_characters_scrim);

        infoCl = voicekbKeyboardView.findViewById(R.id.info_cl);
        infoTv = voicekbKeyboardView.findViewById(R.id.info_tv);
        infoYesButton = voicekbKeyboardView.findViewById(R.id.info_yes_btn);
        infoNoButton = voicekbKeyboardView.findViewById(R.id.info_no_btn);

        editUndoButton = voicekbKeyboardView.findViewById(R.id.edit_undo_btn);
        editRedoButton = voicekbKeyboardView.findViewById(R.id.edit_redo_btn);
        initializeKeyPressAnimations();

        overlayCharactersLl = voicekbKeyboardView.findViewById(R.id.overlay_characters_ll);

        // initialize audio manager to stop and start background audio
        am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build())
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener(focusChange -> {
                    if (focusChange == AudioManager.AUDIOFOCUS_LOSS) {
                        if (isRecording) pauseButton.performClick();
                    }
                })
                .build();
        initAndRegisterBluetoothReceiver();

        settingsButton.setOnClickListener(v -> {
            infoCl.setVisibility(View.GONE);
            showQuickSettingsPopup();
        });
        returnPreviousButton.setOnClickListener(v -> {
            vibrate();
            boolean enabled = !sp.getBoolean(PREF_RETURN_TO_PREVIOUS_KEYBOARD, false);
            sp.edit().putBoolean(PREF_RETURN_TO_PREVIOUS_KEYBOARD, enabled).apply();
            updateReturnPreviousButtonStyle();
        });
        languageButton.setOnClickListener(v -> showLanguageSelectionPopup());

        recordTimeRunnable = new Runnable() {
            @Override
            public void run() {
                elapsedTime += 100;
                updateRecordingButtonText();
                recordTimeHandler.postDelayed(this, 100);
            }
        };

        updateIdleRecordButtonIcons();
        recordButton.setOnClickListener(v -> {
            vibrate();

            infoCl.setVisibility(View.GONE);
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                openSettingsActivity();
            } else if (!isRecording && !isPreparingRecording) {
                if (ensureTranscriptionConfigReady()) startRecording();
            } else if (isRecording) {
                stopRecording();
            }
        });

        fileTranscriptionButton.setOnClickListener(v -> {
            vibrate();
            if (keyboardUiState != KeyboardUiState.PREPARING
                    && keyboardUiState != KeyboardUiState.SENDING
                    && sp.getBoolean("com.idefant.voicekb.file_upload_enabled", false)
                    && ensureTranscriptionConfigReady()) {
                if (keyboardUiState == KeyboardUiState.RECORDING || keyboardUiState == KeyboardUiState.PAUSED) {
                    resetRecordingState(false, true);
                }
                openFilePicker();
            }
        });

        resendButton.setOnClickListener(v -> {
            vibrate();
            File resendFile = getResendAudioFile();
            if (keyboardUiState != KeyboardUiState.PREPARING
                    && keyboardUiState != KeyboardUiState.SENDING
                    && resendFile != null
                    && ensureTranscriptionConfigReady()) {
                if (keyboardUiState == KeyboardUiState.RECORDING || keyboardUiState == KeyboardUiState.PAUSED) {
                    resetRecordingState(false, true);
                }
                audioFile = resendFile;
                startWhisperApiRequest(false);
            }
        });

        backspaceButton.setOnClickListener(v -> {
            vibrate();
            deleteOneCharacter();
        });

        backspaceButton.setOnLongClickListener(v -> {
            isDeleting = true;
            startDeleteTime = System.currentTimeMillis();
            currentDeleteDelay = 50;
            deleteRunnable = new Runnable() {
                @Override
                public void run() {
                    if (isDeleting) {
                        deleteOneCharacter();
                        long diff = System.currentTimeMillis() - startDeleteTime;
                        if (diff > 1500 && currentDeleteDelay == 50) {
                            vibrate();
                            currentDeleteDelay = 25;
                        } else if (diff > 3000 && currentDeleteDelay == 25) {
                            vibrate();
                            currentDeleteDelay = 10;
                        } else if (diff > 5000 && currentDeleteDelay == 10) {
                            vibrate();
                            currentDeleteDelay = 5;
                        }
                        deleteHandler.postDelayed(this, currentDeleteDelay);
                    }
                }
            };
            deleteHandler.post(deleteRunnable);
            return true;
        });

        // Enhanced touch handling: swipe left while holding to select words progressively
        backspaceButton.setOnTouchListener((v, event) -> {
            handlePressAnimationEvent(v, event);
            InputConnection ic = getCurrentInputConnection();
            final float density = getResources().getDisplayMetrics().density;
            final int stepPx = (int) (24f * density + 0.5f);
            final int activationPx = Math.max(ViewConfiguration.get(getApplicationContext()).getScaledTouchSlop(),
                    (int) (8f * density + 0.5f)); // small threshold to enter swipe-select and cancel long-press early

            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    // reset states; allow click/long-press detection
                    isDeleting = false;
                    if (deleteRunnable != null) deleteHandler.removeCallbacks(deleteRunnable);

                    isSwipeSelectingWords = false;
                    swipeSelectedSteps = 0;
                    swipeWordBoundaries = null;
                    swipeBaseCursor = -1;
                    backspaceStartX = event.getX();
                    return false;

                case MotionEvent.ACTION_MOVE: {
                    float dx = event.getX() - backspaceStartX;

                    // if the user moves left beyond activation threshold, start swipe-select and cancel long-press
                    if (dx < -activationPx) {
                        if (!isSwipeSelectingWords) {
                            isSwipeSelectingWords = true;

                            // cancel system long-press to avoid auto-delete kick-in
                            v.cancelLongPress();
                            if (v.getParent() != null) v.getParent().requestDisallowInterceptTouchEvent(true);

                            // stop auto-delete if it was started via long-press (safety)
                            isDeleting = false;
                            if (deleteRunnable != null) deleteHandler.removeCallbacks(deleteRunnable);

                            if (ic != null) {
                                ExtractedText et = ic.getExtractedText(new ExtractedTextRequest(), 0);
                                if (et != null && et.text != null) {
                                    swipeBaseCursor = Math.max(et.selectionStart, et.selectionEnd);
                                    String before = et.text.subSequence(0, swipeBaseCursor).toString();
                                    swipeWordBoundaries = computeWordBoundaries(before);
                                }
                            }
                            if (swipeWordBoundaries == null) {
                                swipeWordBoundaries = Collections.singletonList(0);
                                swipeBaseCursor = 0;
                            }
                        }

                        // step size defines when next word gets added to selection
                        if (ic != null && swipeWordBoundaries != null && !swipeWordBoundaries.isEmpty()) {
                            int maxSteps = swipeWordBoundaries.size() - 1;
                            int steps = Math.min((int) ((-dx) / stepPx), maxSteps);
                            steps = Math.max(0, steps);

                            if (steps != swipeSelectedSteps) {
                                swipeSelectedSteps = steps;
                                int newStart = swipeWordBoundaries.get(steps);
                                ic.setSelection(newStart, swipeBaseCursor);
                                vibrate();
                            }
                        }
                        return true; // consume while swipe-selecting
                    } else if (isSwipeSelectingWords) {
                        // moving back right reduces selection
                        if (ic != null && swipeWordBoundaries != null && !swipeWordBoundaries.isEmpty()) {
                            int steps = Math.max(0, (int) ((-dx) / stepPx));
                            steps = Math.min(steps, swipeWordBoundaries.size() - 1);

                            if (steps != swipeSelectedSteps) {
                                swipeSelectedSteps = steps;
                                int newStart = swipeWordBoundaries.get(steps);
                                ic.setSelection(newStart, swipeBaseCursor);
                                vibrate();
                            }
                            if (steps == 0) {
                                ic.setSelection(swipeBaseCursor, swipeBaseCursor);
                            }
                        }
                        return true;
                    }

                    return false; // not yet swiping -> keep default handling for click/long press
                }

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    // always stop auto-delete
                    isDeleting = false;
                    if (deleteRunnable != null) deleteHandler.removeCallbacks(deleteRunnable);

                    if (isSwipeSelectingWords) {
                        if (ic != null) {
                            if (swipeSelectedSteps > 0) {
                                ic.commitText("", 1);
                                markEditorHistoryChanged();
                                vibrate();
                            } else {
                                ic.setSelection(swipeBaseCursor, swipeBaseCursor);
                            }
                        }
                        isSwipeSelectingWords = false;
                        return true; // consume
                    }
                    return false; // no swipe-select -> allow click/long-press outcomes

                default:
                    return false;
            }
        });

        switchButton.setOnClickListener(v -> {
            vibrate();
            switchToPreviousKeyboard(true);
        });

        switchButton.setOnLongClickListener(v -> {
            vibrate();
            showQuickSettingsPopup();
            return true;
        });

        // trash button to abort the recording and reset all variables and views
        trashButton.setOnClickListener(v -> {
            vibrate();
            resetRecordingState(true, true);
        });

        // space button that changes cursor position if user swipes over it
        spaceButton.setOnTouchListener((v, event) -> {
            handlePressAnimationEvent(v, event);
            InputConnection inputConnection = getCurrentInputConnection();
            int action = event.getActionMasked();
            if (inputConnection != null) {
                setSpaceButtonArrows();
                switch (action) {
                    case MotionEvent.ACTION_DOWN:
                        spaceButtonUserHasSwiped = false;
                        spaceButton.setTag(event.getX());
                        break;

                    case MotionEvent.ACTION_MOVE:
                        float x = (float) spaceButton.getTag();
                        if (event.getX() - x > 30) {
                            vibrate();
                            inputConnection.commitText("", 2);
                            spaceButton.setTag(event.getX());
                            spaceButtonUserHasSwiped = true;
                        } else if (x - event.getX() > 30) {
                            vibrate();
                            inputConnection.commitText("", -1);
                            spaceButton.setTag(event.getX());
                            spaceButtonUserHasSwiped = true;
                        }
                        break;

                    case MotionEvent.ACTION_UP:
                        if (!spaceButtonUserHasSwiped) {
                            vibrate();
                            inputConnection.commitText(" ", 1);
                            markEditorHistoryChanged();
                        }
                        setSpaceButtonArrows();
                        break;
                    case MotionEvent.ACTION_CANCEL:
                        setSpaceButtonArrows();
                        break;
                }
            } else {
                setSpaceButtonArrows();
            }
            return false;
        });

        pauseButton.setOnClickListener(v -> {
            vibrate();
            if (recorder != null) {
                if (isPaused) {
                    if (audioFocusEnabled) am.requestAudioFocus(audioFocusRequest);
                    recorder.resume();
                    recordTimeHandler.post(recordTimeRunnable);
                    isPaused = false;
                    keyboardUiState = KeyboardUiState.RECORDING;
                } else {
                    if (audioFocusEnabled) am.abandonAudioFocusRequest(audioFocusRequest);
                    recorder.pause();
                    recordTimeHandler.removeCallbacks(recordTimeRunnable);
                    isPaused = true;
                    keyboardUiState = KeyboardUiState.PAUSED;
                }
                renderKeyboardState();
            }
        });

        enterButton.setOnClickListener(v -> {
            vibrate();
            performEnterAction();
        });

        enterButton.setOnLongClickListener(v -> {
            vibrate();
            selectedCharacter = null;
            resetSpecialCharacterStyles();
            specialCharactersScrim.setVisibility(View.VISIBLE);
            overlayCharactersLl.setVisibility(View.VISIBLE);
            return true;
        });

        enterButton.setOnTouchListener((v, event) -> {
            handlePressAnimationEvent(v, event);
            if (overlayCharactersLl.getVisibility() == View.VISIBLE) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_MOVE:
                        if (!isPointInsideSpecialCharacterSelectionArea(event.getRawX(), event.getRawY())) {
                            selectSpecialCharacter(null);
                            break;
                        }
                        selectSpecialCharacter(findSpecialCharacterByRawX(event.getRawX()));
                        break;
                    case MotionEvent.ACTION_UP:
                        if (selectedCharacter != null) {
                            InputConnection inputConnection = getCurrentInputConnection();
                            if (inputConnection != null) {
                                inputConnection.commitText(selectedCharacter.getText(), 1);
                                markEditorHistoryChanged();
                            }
                            selectedCharacter = null;
                        }
                        specialCharactersScrim.setVisibility(View.GONE);
                        overlayCharactersLl.setVisibility(View.GONE);
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        specialCharactersScrim.setVisibility(View.GONE);
                        overlayCharactersLl.setVisibility(View.GONE);
                        return true;
                }
            }
            return false;
        });

        // initialize all edit buttons
        editUndoButton.setOnClickListener(v -> {
            vibrate();
            performEditorHistoryAction(false);
        });
        editRedoButton.setOnClickListener(v -> {
            vibrate();
            performEditorHistoryAction(true);
        });

        // initialize overlay characters
        for (int i = 0; i < 8; i++) {
            TextView charView = (TextView) LayoutInflater.from(context).inflate(R.layout.item_overlay_characters, overlayCharactersLl, false);
            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.RECTANGLE);
            bg.setCornerRadius(dp(10));
            bg.setColor(Color.TRANSPARENT);
            charView.setBackground(bg);
            overlayCharactersLl.addView(charView);
        }

        return voicekbKeyboardView;
    }

    private void initAndRegisterBluetoothReceiver() {
        if (bluetoothScoReceiver != null) return;

        bluetoothScoReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (!AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED.equals(intent.getAction())) return;

                int state = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, AudioManager.SCO_AUDIO_STATE_ERROR);
                if (state == AudioManager.SCO_AUDIO_STATE_CONNECTED) {
                    isBluetoothScoStarted = true;

                    // If we were waiting to start the recording until SCO connects, start now
                    if (recordingPending && waitingForSco) {
                        waitingForSco = false;
                        if (bluetoothHandler != null && scoTimeoutRunnable != null) {
                            bluetoothHandler.removeCallbacks(scoTimeoutRunnable);
                        }
                        proceedStartRecording(MediaRecorder.AudioSource.VOICE_COMMUNICATION, true);
                    }

                    // Update icon if we are recording and currently using BT
                    if (isRecording) {
                        updateRecordButtonIconWhileRecording();
                    }
                } else if (state == AudioManager.SCO_AUDIO_STATE_DISCONNECTED) {
                    isBluetoothScoStarted = false;

                    // If we were recording using BT and it got disconnected, keep recording and switch icon
                    if (isRecording && recordingUsesBluetooth) {
                        recordingUsesBluetooth = false;
                        updateRecordButtonIconWhileRecording();
                    }
                }
            }
        };
        registerReceiver(bluetoothScoReceiver, new IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED));
    }

    // method is called if the user closed the keyboard
    @Override
    public void onFinishInputView(boolean finishingInput) {
        super.onFinishInputView(finishingInput);

        if (preserveInputViewAfterDialogDismiss) {
            preserveInputViewAfterDialogDismiss = false;
            restoreKeyboardVisibility();
            return;
        }

        dismissActiveDialog(false);
        boolean suppressReturnForInternalActivity = suppressTransientKeyboardReturnOnce;
        boolean returnToPreviousKeyboard = sp.getBoolean(PREF_RETURN_TO_PREVIOUS_KEYBOARD, false)
                && transientVoiceSession
                && !switchingToPreviousKeyboard
                && !suppressReturnForInternalActivity;
        transientVoiceSession = false;

        resetRecordingState(false, true);
        mainHandler.postDelayed(this::resetInstantRecordingLatchIfVoiceKBIsNotSelected, 300);

        if (bluetoothScoReceiver != null) {
            unregisterReceiver(bluetoothScoReceiver);
            bluetoothScoReceiver = null;
        }

        infoCl.setVisibility(View.GONE);
        if (returnToPreviousKeyboard) {
            instantRecordingConsumedForCurrentImeSelection = false;
            mainHandler.post(() -> switchToPreviousKeyboard(false));
        } else {
            switchingToPreviousKeyboard = false;
        }
    }

    // method is called if the keyboard appears again
    @Override
    public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        boolean restoringDialogState = restoreInputViewStateAfterDialogDismiss;
        restoreInputViewStateAfterDialogDismiss = false;
        boolean returningFromInternalActivity = suppressTransientKeyboardReturnOnce;
        suppressTransientKeyboardReturnOnce = false;
        switchingToPreviousKeyboard = false;
        hideSpecialCharactersPanel();
        updateEnterButtonIcon(info);
        initAndRegisterBluetoothReceiver();

        String pendingAudioFileName = sp.getString("com.idefant.voicekb.transcription_audio_file", "");
        // get the currently selected input language
        ensureCurrentInputLanguage();

        // check if user enabled audio focus
        audioFocusEnabled = sp.getBoolean("com.idefant.voicekb.audio_focus", true);

        // fill all overlay characters
        String charactersString = sp.getString("com.idefant.voicekb.overlay_characters", "()-:!?,.");
        for (int i = 0; i < overlayCharactersLl.getChildCount(); i++) {
            TextView charView = (TextView) overlayCharactersLl.getChildAt(i);
            if (i >= charactersString.length()) {
                charView.setVisibility(View.GONE);
            } else {
                charView.setVisibility(View.VISIBLE);
                charView.setText(charactersString.substring(i, i + 1));
            }
        }

        // update theme
        applyKeyboardTheme();
        if (restoringDialogState) {
            renderKeyboardState();
            settingsButton.setVisibility(sp.getBoolean(PREF_QUICK_SETTINGS_BUTTON, true) ? View.VISIBLE : View.GONE);
            setSpaceButtonArrows();
            return;
        }

        keyboardUiState = KeyboardUiState.IDLE;
        renderKeyboardState();
        initializeEditorHistoryButtons();
        settingsButton.setVisibility(sp.getBoolean(PREF_QUICK_SETTINGS_BUTTON, true) ? View.VISIBLE : View.GONE);
        setSpaceButtonArrows();
        // start audio file transcription if user selected an audio file
        if (!pendingAudioFileName.isEmpty()) {
            audioFile = new File(getCacheDir(), pendingAudioFileName);

            sp.edit().remove("com.idefant.voicekb.transcription_audio_file").apply();
            if (ensureTranscriptionConfigReady()) startWhisperApiRequest(true);

        } else if (shouldStartInstantRecording(restarting, returningFromInternalActivity)) {
            instantRecordingConsumedForCurrentImeSelection = true;
            transientVoiceSession = true;
            recordButton.performClick();
        }
    }

    private boolean shouldStartInstantRecording(boolean restarting, boolean returningFromInternalActivity) {
        return !restarting
                && !returningFromInternalActivity
                && !instantRecordingConsumedForCurrentImeSelection
                && sp.getBoolean("com.idefant.voicekb.instant_recording", false);
    }

    private void resetInstantRecordingLatchIfVoiceKBIsNotSelected() {
        if (!isVoiceKBSelectedInputMethod()) {
            instantRecordingConsumedForCurrentImeSelection = false;
        }
    }

    private boolean isVoiceKBSelectedInputMethod() {
        String currentInputMethod = Settings.Secure.getString(getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
        return TextUtils.equals(currentInputMethod, getPackageName() + "/.core.VoiceKBInputMethodService");
    }

    private void vibrate() {
        if (vibrationEnabled) if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK));
        } else {
            vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE));
        }
    }

    private void applyButtonColor(MaterialButton button, int backgroundColor) {
        if (button == null) return;
        button.setBackgroundTintList(ColorStateList.valueOf(backgroundColor));
    }

    private void updateIdleRecordButtonIcons() {
        if (recordIcon == null || sp == null) return;
        setRecordIcon(R.drawable.ic_lucide_mic_24, 40, 26);
        recordBluetoothBadge.setVisibility(View.GONE);
    }

    private void setRecordIcon(int iconResource, int sizeDp, int topMarginDp) {
        if (recordIcon == null) return;
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) recordIcon.getLayoutParams();
        params.width = dp(sizeDp);
        params.height = dp(sizeDp);
        params.topMargin = dp(topMarginDp);
        recordIcon.setLayoutParams(params);
        recordIcon.setImageResource(iconResource);
        recordIcon.setVisibility(View.VISIBLE);
    }

    private void updateRecordingButtonText() {
        if (recordButton == null) return;
        recordTimer.setText(String.format(Locale.getDefault(), "%02d:%02d",
                (int) (elapsedTime / 60000),
                (int) (elapsedTime / 1000) % 60));
    }

    private void renderKeyboardState() {
        if (recordButton == null) return;

        int primaryColor = getResources().getColor(R.color.voicekb_keyboard_primary, getTheme());
        int recordColor = getResources().getColor(
                keyboardUiState == KeyboardUiState.RECORDING ? R.color.voicekb_keyboard_recording : R.color.voicekb_keyboard_primary,
                getTheme());

        applyButtonColor(recordButton, recordColor);
        recordProgress.setVisibility(
                keyboardUiState == KeyboardUiState.PREPARING || keyboardUiState == KeyboardUiState.SENDING
                        ? View.VISIBLE : View.GONE);
        recordButton.setEnabled(keyboardUiState != KeyboardUiState.PREPARING && keyboardUiState != KeyboardUiState.SENDING);
        recordButton.setIconTint(ColorStateList.valueOf(getResources().getColor(R.color.voicekb_white, getTheme())));
        recordButton.setIcon(null);
        recordButton.setText("");
        recordIcon.setImageTintList(ColorStateList.valueOf(getResources().getColor(R.color.voicekb_white, getTheme())));
        recordTimer.setVisibility(
                keyboardUiState == KeyboardUiState.RECORDING || keyboardUiState == KeyboardUiState.PAUSED
                        ? View.VISIBLE : View.GONE);

        boolean showTrash = keyboardUiState != KeyboardUiState.IDLE;
        boolean showPause = keyboardUiState == KeyboardUiState.RECORDING || keyboardUiState == KeyboardUiState.PAUSED;
        boolean sending = keyboardUiState == KeyboardUiState.SENDING;
        trashButton.setVisibility(showTrash ? View.VISIBLE : View.INVISIBLE);
        pauseButton.setVisibility(showPause ? View.VISIBLE : View.INVISIBLE);
        boolean resendEnabled = sp.getBoolean("com.idefant.voicekb.resend_button", false);
        boolean hasResendAudioFile = getResendAudioFile() != null;
        resendButton.setVisibility(resendEnabled ? View.VISIBLE : View.INVISIBLE);
        resendButton.setEnabled(!sending && keyboardUiState != KeyboardUiState.PREPARING && hasResendAudioFile);
        resendButton.setAlpha((sending || !hasResendAudioFile) ? 0.38f : 1f);
        boolean fileUploadEnabled = sp.getBoolean("com.idefant.voicekb.file_upload_enabled", false);
        fileTranscriptionButton.setVisibility(fileUploadEnabled ? View.VISIBLE : View.INVISIBLE);
        fileTranscriptionButton.setEnabled(!sending && keyboardUiState != KeyboardUiState.PREPARING);
        fileTranscriptionButton.setAlpha((sending || keyboardUiState == KeyboardUiState.PREPARING) ? 0.38f : 1f);
        updateFileResendLayout(fileUploadEnabled && resendEnabled);

        switch (keyboardUiState) {
            case RECORDING:
                updateRecordingButtonText();
                updateRecordButtonIconWhileRecording();
                pauseButton.setIconResource(R.drawable.ic_lucide_pause_24);
                break;
            case PAUSED:
                updateRecordingButtonText();
                setRecordIcon(R.drawable.ic_lucide_send_24, 33, 21);
                recordBluetoothBadge.setVisibility(View.GONE);
                pauseButton.setIconResource(R.drawable.ic_lucide_play_24);
                break;
            case PREPARING:
            case SENDING:
                recordIcon.setVisibility(View.GONE);
                recordBluetoothBadge.setVisibility(View.GONE);
                break;
            case IDLE:
            default:
                updateIdleRecordButtonIcons();
                break;
        }

        languageButton.setText(getCurrentLanguageLabel());
        languageButton.setEnabled(keyboardUiState != KeyboardUiState.PREPARING && keyboardUiState != KeyboardUiState.SENDING);
        updateReturnPreviousButtonStyle();
        applyButtonColor(recordButton, recordColor);
        if (keyboardUiState == KeyboardUiState.IDLE) applyButtonColor(recordButton, primaryColor);
    }

    private void updateFileResendLayout(boolean stackAboveResend) {
        ConstraintLayout.LayoutParams params = (ConstraintLayout.LayoutParams) fileTranscriptionButton.getLayoutParams();
        params.topToTop = ConstraintLayout.LayoutParams.UNSET;
        params.bottomToBottom = ConstraintLayout.LayoutParams.UNSET;
        params.bottomToTop = ConstraintLayout.LayoutParams.UNSET;
        params.topMargin = 0;
        params.bottomMargin = 0;
        if (stackAboveResend) {
            params.bottomToTop = R.id.resend_btn;
            params.bottomMargin = dp(10);
        } else {
            params.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID;
        }
        fileTranscriptionButton.setLayoutParams(params);
    }

    private void applyKeyboardTheme() {
        String theme = sp.getString("com.idefant.voicekb.theme", "system");
        keyboardThemeDark = "dark".equals(theme)
                || ("system".equals(theme)
                && (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES);

        int surface = getResources().getColor(
                keyboardThemeDark ? R.color.voicekb_keyboard_surface_dark : R.color.voicekb_keyboard_surface_light,
                getTheme());
        int button = getResources().getColor(
                keyboardThemeDark ? R.color.voicekb_keyboard_button_dark : R.color.voicekb_keyboard_button_light,
                getTheme());
        int enter = getResources().getColor(
                keyboardThemeDark ? R.color.voicekb_keyboard_enter_dark : R.color.voicekb_keyboard_enter_light,
                getTheme());
        int danger = getResources().getColor(
                keyboardThemeDark ? R.color.voicekb_keyboard_danger_container_dark : R.color.voicekb_keyboard_danger_container_light,
                getTheme());
        int mutedText = getResources().getColor(
                keyboardThemeDark ? R.color.voicekb_keyboard_muted_text_dark : R.color.voicekb_keyboard_muted_text_light,
                getTheme());
        int text = getResources().getColor(
                keyboardThemeDark ? R.color.voicekb_keyboard_text_dark : R.color.voicekb_keyboard_text_light,
                getTheme());
        int languageBackground = getResources().getColor(
                keyboardThemeDark ? R.color.voicekb_keyboard_language_dark : R.color.voicekb_keyboard_language_light,
                getTheme());
        int languageAccent = getResources().getColor(
                keyboardThemeDark ? R.color.voicekb_keyboard_language_icon_dark : R.color.voicekb_keyboard_primary_pressed,
                getTheme());
        int languageIcon = getResources().getColor(
                keyboardThemeDark ? R.color.voicekb_keyboard_language_icon_dark : R.color.voicekb_keyboard_language_icon,
                getTheme());
        int headerIcon = getResources().getColor(
                keyboardThemeDark ? R.color.voicekb_keyboard_header_icon_dark : R.color.voicekb_keyboard_icon_light,
                getTheme());
        int dangerIcon = getResources().getColor(
                keyboardThemeDark ? R.color.voicekb_keyboard_danger_icon_dark : R.color.voicekb_keyboard_danger_icon_light,
                getTheme());
        int tray = getResources().getColor(
                keyboardThemeDark ? R.color.voicekb_keyboard_tray_dark : R.color.voicekb_keyboard_panel_light,
                getTheme());
        int trayDivider = getResources().getColor(
                keyboardThemeDark ? R.color.voicekb_keyboard_tray_divider_dark : R.color.voicekb_keyboard_divider_light,
                getTheme());
        int trayText = getResources().getColor(
                keyboardThemeDark ? R.color.voicekb_keyboard_tray_text_dark : R.color.voicekb_keyboard_tray_text_light,
                getTheme());

        voicekbKeyboardView.setBackgroundColor(surface);
        keyboardHeader.setBackgroundColor(getResources().getColor(
                keyboardThemeDark ? R.color.voicekb_keyboard_header_dark : R.color.voicekb_keyboard_header_light,
                getTheme()));
        TextView title = voicekbKeyboardView.findViewById(R.id.voicekb_title);
        title.setTextColor(text);
        spaceButton.setTextColor(mutedText);
        applyButtonColor(languageButton, languageBackground);
        languageButton.setTextColor(languageAccent);
        languageButton.setIconTint(ColorStateList.valueOf(languageIcon));
        GradientDrawable trayBackground = (GradientDrawable) overlayCharactersLl.getBackground();
        trayBackground.setColor(tray);
        trayBackground.setStroke(dp(1), trayDivider);
        for (int i = 0; i < overlayCharactersLl.getChildCount(); i++) {
            styleSpecialCharacter((TextView) overlayCharactersLl.getChildAt(i), false, trayText);
        }
        MaterialButton[] surfaceButtons = {
                fileTranscriptionButton, resendButton, backspaceButton, switchButton, pauseButton
        };
        for (MaterialButton buttonView : surfaceButtons) applyButtonColor(buttonView, button);
        MaterialButton[] headerButtons = {
                settingsButton, returnPreviousButton, editUndoButton, editRedoButton
        };
        for (MaterialButton buttonView : headerButtons) applyButtonColor(buttonView, Color.TRANSPARENT);
        applyButtonColor(trashButton, danger);
        trashButton.setIconTint(ColorStateList.valueOf(dangerIcon));
        applyButtonColor(spaceButton, button);
        applyButtonColor(enterButton, enter);
        enterButton.setIconTint(ColorStateList.valueOf(languageAccent));
        int icon = getResources().getColor(
                keyboardThemeDark ? R.color.voicekb_keyboard_icon_dark : R.color.voicekb_keyboard_icon_light,
                getTheme());
        for (MaterialButton buttonView : surfaceButtons) {
            if (buttonView != null) buttonView.setIconTint(ColorStateList.valueOf(icon));
        }
        for (MaterialButton buttonView : headerButtons) {
            if (buttonView != null) buttonView.setIconTint(ColorStateList.valueOf(headerIcon));
        }
        updateEditorHistoryButtons();
        int rippleColor = sp.getBoolean("com.idefant.voicekb.animations", true)
                ? (keyboardThemeDark ? Color.argb(54, 255, 255, 255) : Color.argb(30, 0, 0, 0))
                : Color.TRANSPARENT;
        MaterialButton[] rippleButtons = {
                settingsButton, returnPreviousButton, languageButton, recordButton, fileTranscriptionButton, resendButton, backspaceButton,
                switchButton, trashButton, spaceButton, pauseButton, enterButton, editUndoButton, editRedoButton
        };
        for (MaterialButton buttonView : rippleButtons) {
            if (buttonView != null) buttonView.setRippleColor(ColorStateList.valueOf(rippleColor));
        }
        updateReturnPreviousButtonStyle();
    }

    private void updateReturnPreviousButtonStyle() {
        if (returnPreviousButton == null || sp == null) return;
        boolean enabled = sp.getBoolean(PREF_RETURN_TO_PREVIOUS_KEYBOARD, false);
        int background = enabled
                ? getResources().getColor(
                        keyboardThemeDark ? R.color.voicekb_keyboard_button_dark : R.color.voicekb_keyboard_language_light,
                        getTheme())
                : Color.TRANSPARENT;
        int icon = getResources().getColor(
                enabled
                        ? (keyboardThemeDark ? R.color.voicekb_keyboard_language_icon_dark : R.color.voicekb_keyboard_primary_pressed)
                        : (keyboardThemeDark ? R.color.voicekb_keyboard_header_icon_dark : R.color.voicekb_keyboard_icon_light),
                getTheme());
        applyButtonColor(returnPreviousButton, background);
        returnPreviousButton.setIconTint(ColorStateList.valueOf(icon));
    }

    private void setSpaceButtonArrows() {
        if (spaceButton == null) return;
        spaceButton.setCompoundDrawablesRelativeWithIntrinsicBounds(
                R.drawable.ic_lucide_chevron_left_24,
                0,
                R.drawable.ic_lucide_chevron_right_24,
                0);
    }

    private void hideSpecialCharactersPanel() {
        if (specialCharactersScrim != null) specialCharactersScrim.setVisibility(View.GONE);
        if (overlayCharactersLl != null) overlayCharactersLl.setVisibility(View.GONE);
        selectedCharacter = null;
        resetSpecialCharacterStyles();
    }

    private void resetSpecialCharacterStyles() {
        if (overlayCharactersLl == null) return;
        int textColor = getResources().getColor(
                keyboardThemeDark ? R.color.voicekb_keyboard_tray_text_dark : R.color.voicekb_keyboard_tray_text_light,
                getTheme());
        for (int i = 0; i < overlayCharactersLl.getChildCount(); i++) {
            styleSpecialCharacter((TextView) overlayCharactersLl.getChildAt(i), false, textColor);
        }
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void performEditorHistoryAction(boolean redo) {
        InputConnection inputConnection = getCurrentInputConnection();
        if (inputConnection == null || (redo ? !editorRedoAvailable : !editorUndoAvailable)) return;

        String before = getEditorText(inputConnection);
        inputConnection.performContextMenuAction(redo ? android.R.id.redo : android.R.id.undo);
        if (mainHandler == null) return;

        mainHandler.postDelayed(() -> continueEditorHistoryAction(before, redo, false), 80);
    }

    private void continueEditorHistoryAction(String before, boolean redo, boolean shortcutSent) {
        InputConnection inputConnection = getCurrentInputConnection();
        if (inputConnection == null) return;
        if (!Objects.equals(before, getEditorText(inputConnection))) {
            if (redo) {
                editorUndoAvailable = true;
            } else {
                editorRedoAvailable = true;
            }
            updateEditorHistoryButtons();
            return;
        }

        if (!shortcutSent) {
            sendEditorHistoryShortcut(inputConnection, redo, false);
            mainHandler.postDelayed(() -> continueEditorHistoryAction(before, redo, true), 80);
            return;
        }

        if (redo) {
            sendEditorHistoryShortcut(inputConnection, true, true);
            mainHandler.postDelayed(() -> finishEditorHistoryAction(before, true), 80);
        } else {
            finishEditorHistoryAction(before, false);
        }
    }

    private void finishEditorHistoryAction(String before, boolean redo) {
        InputConnection inputConnection = getCurrentInputConnection();
        if (inputConnection == null) return;
        if (!Objects.equals(before, getEditorText(inputConnection))) {
            if (redo) {
                editorUndoAvailable = true;
            } else {
                editorRedoAvailable = true;
            }
        } else if (redo) {
            editorRedoAvailable = false;
        } else {
            editorUndoAvailable = false;
        }
        updateEditorHistoryButtons();
    }

    private void initializeEditorHistoryButtons() {
        InputConnection inputConnection = getCurrentInputConnection();
        String text = inputConnection == null ? null : getEditorText(inputConnection);
        editorUndoAvailable = !TextUtils.isEmpty(text);
        editorRedoAvailable = false;
        updateEditorHistoryButtons();
    }

    private void markEditorHistoryChanged() {
        editorUndoAvailable = true;
        editorRedoAvailable = false;
        updateEditorHistoryButtons();
    }

    private void updateEditorHistoryButtons() {
        updateEditorHistoryButton(editUndoButton, editorUndoAvailable);
        updateEditorHistoryButton(editRedoButton, editorRedoAvailable);
    }

    private void updateEditorHistoryButton(MaterialButton button, boolean enabled) {
        if (button == null) return;
        button.setEnabled(enabled);
        button.setAlpha(enabled ? 1f : 0.38f);
    }

    private String getEditorText(InputConnection inputConnection) {
        ExtractedText extractedText = inputConnection.getExtractedText(new ExtractedTextRequest(), 0);
        return extractedText == null || extractedText.text == null ? null : extractedText.text.toString();
    }

    private void sendEditorHistoryShortcut(InputConnection inputConnection, boolean redo, boolean useCtrlY) {
        long now = System.currentTimeMillis();
        int metaState = KeyEvent.META_CTRL_ON | KeyEvent.META_CTRL_LEFT_ON;
        if (redo && !useCtrlY) metaState |= KeyEvent.META_SHIFT_ON | KeyEvent.META_SHIFT_LEFT_ON;
        int keyCode = useCtrlY ? KeyEvent.KEYCODE_Y : KeyEvent.KEYCODE_Z;
        inputConnection.sendKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, metaState));
        inputConnection.sendKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0, metaState));
    }

    private void initializeKeyPressAnimations() {
        View[] animatedViews = {
                settingsButton, returnPreviousButton, recordButton, fileTranscriptionButton,
                resendButton, switchButton, trashButton,
                pauseButton, editUndoButton, editRedoButton,
                infoYesButton, infoNoButton
        };
        for (View view : animatedViews) {
            applyPressAnimation(view);
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private void applyPressAnimation(View view) {
        if (view == null) return;
        view.setOnTouchListener((v, event) -> {
            handlePressAnimationEvent(v, event);
            return false;
        });
    }

    private void handlePressAnimationEvent(View view, MotionEvent event) {
        // MaterialButton ripple provides touch feedback without changing key geometry.
    }

    private void performEnterAction() {
        InputConnection inputConnection = getCurrentInputConnection();
        if (inputConnection == null) return;
        EditorInfo editorInfo = getCurrentInputEditorInfo();

        if (editorInfo == null) {
            inputConnection.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER));
            inputConnection.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER));
            return;
        }

        int imeAction = editorInfo.imeOptions & EditorInfo.IME_MASK_ACTION;
        boolean noEnterAction = (editorInfo.imeOptions & EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0;

        if (noEnterAction) {
            inputConnection.commitText("\n", 1);
            markEditorHistoryChanged();
        } else {
            switch (imeAction) {
                case EditorInfo.IME_ACTION_GO:
                case EditorInfo.IME_ACTION_SEARCH:
                case EditorInfo.IME_ACTION_SEND:
                case EditorInfo.IME_ACTION_NEXT:
                case EditorInfo.IME_ACTION_DONE:
                    inputConnection.performEditorAction(imeAction);
                    break;
                default:
                    inputConnection.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER));
                    inputConnection.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER));
                    markEditorHistoryChanged();
                    break;
            }
        }
    }

    private void updateEnterButtonIcon(EditorInfo info) {
        if (info == null || enterButton == null) return;

        int imeAction = info.imeOptions & EditorInfo.IME_MASK_ACTION;
        boolean noEnterAction = (info.imeOptions & EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0;

        if (noEnterAction) {
            enterButton.setIconResource(R.drawable.ic_lucide_corner_down_left_24);
        } else {
            switch (imeAction) {
                case EditorInfo.IME_ACTION_GO:
                case EditorInfo.IME_ACTION_SEARCH:
                case EditorInfo.IME_ACTION_SEND:
                case EditorInfo.IME_ACTION_NEXT:
                    enterButton.setIconResource(R.drawable.ic_lucide_send_24);
                    break;
                case EditorInfo.IME_ACTION_DONE:
                    enterButton.setIconResource(R.drawable.ic_lucide_check_24);
                    break;
                default:
                    enterButton.setIconResource(R.drawable.ic_lucide_corner_down_left_24);
                    break;
            }
        }
    }

    private void openSettingsActivity() {
        suppressTransientKeyboardReturnOnce = true;
        Intent intent = new Intent(this, VoiceKBSettingsActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }

    private void openFilePicker() {
        suppressTransientKeyboardReturnOnce = true;
        Intent intent = new Intent(this, VoiceKBSettingsActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.putExtra("com.idefant.voicekb.open_file_picker", true);
        startActivity(intent);
    }

    private boolean ensureTranscriptionConfigReady() {
        String validationError;
        try {
            TranscriptionApiConfig config = TranscriptionApiConfig.getActive(this, sp);
            validationError = config.validate(this, false);
        } catch (IllegalArgumentException e) {
            validationError = getString(R.string.voicekb_api_config_invalid_headers, e.getMessage());
        }
        if (validationError == null) return true;
        showInfoMessage(validationError);
        return false;
    }

    private void startRecording() {
        if (isRecording || isPreparingRecording) return;  // prevent re-entrance

        NetworkWarmup.warmUpTranscriptionProvider(this, sp);
        audioFile = new File(getCacheDir(), "audio_" + System.currentTimeMillis() + ".m4a");

        boolean useBluetoothMic = sp.getBoolean("com.idefant.voicekb.use_bluetooth_mic", false);  // read preference: only use BT mic if enabled
        boolean btAvailable = useBluetoothMic && am.isBluetoothScoAvailableOffCall() && hasBluetoothInputDevice();  // Check if BT SCO is available and (likely) an input device is present

        if (btAvailable) {
            if (am.isBluetoothScoOn()) {
                proceedStartRecording(MediaRecorder.AudioSource.VOICE_COMMUNICATION, true);
            } else {
                // Prepare to wait for SCO connection before starting the recorder
                isPreparingRecording = true;
                recordingPending = true;
                waitingForSco = true;
                mainHandler.post(() -> recordButton.setEnabled(false));
                keyboardUiState = KeyboardUiState.PREPARING;
                mainHandler.post(this::renderKeyboardState);

                am.startBluetoothSco();  // initiate SCO connection

                scoTimeoutRunnable = () -> {  // Timeout: if SCO not connected in time, fall back to MIC to avoid gaps
                    if (recordingPending && waitingForSco) {
                        waitingForSco = false;
                        try { am.stopBluetoothSco(); } catch (Exception ignored) {}
                        proceedStartRecording(MediaRecorder.AudioSource.MIC, false);
                    }
                };
                bluetoothHandler.postDelayed(scoTimeoutRunnable, 2500); // 2.5s timeout
            }
        } else {
            proceedStartRecording(MediaRecorder.AudioSource.MIC, false);  // Start immediately with local MIC
        }
    }

    private void proceedStartRecording(int audioSource, boolean useBtForThisRecording) {
        // Build and start MediaRecorder with the decided audio source
        recorder = new MediaRecorder();
        recorder.setAudioSource(audioSource);
        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
        recorder.setAudioEncodingBitRate(64000);
        recorder.setAudioSamplingRate(44100);
        recorder.setOutputFile(audioFile);

        if (audioFocusEnabled) am.requestAudioFocus(audioFocusRequest);

        try {
            recorder.prepare();
            recorder.start();
        } catch (IOException e) {
            logExceptionLocally(e);
            // reset UI/state on failure
            isRecording = false;
            isPreparingRecording = false;
            recordingPending = false;
            waitingForSco = false;
            recordingUsesBluetooth = false;
            if (audioFocusEnabled) am.abandonAudioFocusRequest(audioFocusRequest);
            mainHandler.post(() -> {
                keyboardUiState = KeyboardUiState.IDLE;
                renderKeyboardState();
            });
            return;
        }

        // success -> update state and UI
        isRecording = true;
        isPreparingRecording = false;
        recordingPending = false;
        waitingForSco = false;
        recordingUsesBluetooth = useBtForThisRecording;
        mainHandler.post(() -> {
            keyboardUiState = KeyboardUiState.RECORDING;
            updateKeepScreenAwake(true);
            elapsedTime = 0;
            renderKeyboardState();
            recordTimeHandler.post(recordTimeRunnable);
        });
    }

    private void stopRecording() {
        cancelScoWaitIfAny();  // cancel any pending SCO wait

        if (recorder != null) {
            try {
                recorder.stop();
            } catch (RuntimeException ignored) { }
            recorder.release();
            recorder = null;

            if (recordTimeRunnable != null) {
                recordTimeHandler.removeCallbacks(recordTimeRunnable);
            }

        }

        updateKeepScreenAwake(false);

        if (isBluetoothScoStarted) am.stopBluetoothSco();

        startWhisperApiRequest(false);
    }

    private void resetRecordingState(boolean showResendIfAvailable, boolean cancelApiRequests) {
        cancelScoWaitIfAny();  // cancel any pending SCO wait

        if (recorder != null) {
            try {
                recorder.stop();
            } catch (RuntimeException ignored) { }
            recorder.release();
            recorder = null;

            if (recordTimeRunnable != null) {
                recordTimeHandler.removeCallbacks(recordTimeRunnable);
            }

        }

        if (cancelApiRequests) {
            if (speechApiThread != null) speechApiThread.shutdownNow();
        }

        if (audioFocusEnabled && am != null) am.abandonAudioFocusRequest(audioFocusRequest);
        if (isBluetoothScoStarted && am != null) am.stopBluetoothSco();

        isRecording = false;
        isPaused = false;
        recordingUsesBluetooth = false;

        keyboardUiState = KeyboardUiState.IDLE;
        renderKeyboardState();

        updateKeepScreenAwake(false);
    }

    private void markAudioFileForResend() {
        if (audioFile != null && audioFile.exists()) {
            sp.edit()
                    .putString(PREF_RESEND_FILE_NAME, audioFile.getName())
                    .putString("com.idefant.voicekb.last_file_name", audioFile.getName())
                    .apply();
        }
    }

    private File getResendAudioFile() {
        String fileName = sp.getString(PREF_RESEND_FILE_NAME, "");
        if (TextUtils.isEmpty(fileName)) {
            fileName = sp.getString("com.idefant.voicekb.last_file_name", "");
        }
        if (TextUtils.isEmpty(fileName)) return null;
        File file = new File(getCacheDir(), fileName);
        return file.exists() ? file : null;
    }

    private boolean hasResendAudioFile() {
        return sp.getBoolean("com.idefant.voicekb.resend_button", false) && getResendAudioFile() != null;
    }

    private void restoreRecordButtonColor() {
        if (recordButton != null && sp != null) {
            applyButtonColor(recordButton, getResources().getColor(R.color.voicekb_keyboard_primary, getTheme()));
        }
    }

    private void updateKeepScreenAwake(boolean keepAwake) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            if (mainHandler != null) {
                mainHandler.post(() -> updateKeepScreenAwake(keepAwake));
            }
            return;
        }

        if (voicekbKeyboardView != null) {
            voicekbKeyboardView.setKeepScreenOn(keepAwake);
        }

        if (keepScreenAwakeApplied == keepAwake) return;

        Dialog windowDialog = getWindow();
        if (windowDialog == null) {
            if (!keepAwake) keepScreenAwakeApplied = false;
            return;
        }

        Window window = windowDialog.getWindow();
        if (window == null) {
            if (!keepAwake) keepScreenAwakeApplied = false;
            return;
        }

        if (keepAwake) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        keepScreenAwakeApplied = keepAwake;
    }

    private void startWhisperApiRequest() {
        startWhisperApiRequest(false);
    }

    private void startWhisperApiRequest(boolean keepKeyboardAfterTranscription) {
        keepKeyboardAfterCurrentTranscription = keepKeyboardAfterTranscription;
        markAudioFileForResend();
        applyRecordingIconState(false);  // recording finished -> stop pulsing

        keyboardUiState = KeyboardUiState.SENDING;
        renderKeyboardState();
        infoCl.setVisibility(View.GONE);
        isRecording = false;
        isPaused = false;
        recordingUsesBluetooth = false;
        if (audioFocusEnabled) am.abandonAudioFocusRequest(audioFocusRequest);

        String stylePrompt;
        switch (sp.getInt("com.idefant.voicekb.style_prompt_selection", 1)) {
            case 1:
                stylePrompt = VoiceKBUtils.getPunctuationPromptForLanguage(currentInputLanguageValue);
                break;
            case 2:
                stylePrompt = sp.getString("com.idefant.voicekb.style_prompt_custom_text", "");
                break;
            default:
                stylePrompt = "";
        }

        speechApiThread = Executors.newSingleThreadExecutor();
        speechApiThread.execute(() -> {
            try {
                TranscriptionApiConfig apiConfig = TranscriptionApiConfig.getActive(this, sp);
                String proxyHost = sp.getString("com.idefant.voicekb.proxy_host", getString(R.string.voicekb_settings_proxy_hint));

                OpenAIOkHttpClient.Builder clientBuilder = OpenAIOkHttpClient.builder()
                        .timeout(Duration.ofSeconds(120));
                apiConfig.applyTo(clientBuilder);

                TranscriptionCreateParams.Builder transcriptionBuilder = TranscriptionCreateParams.builder()
                        .file(audioFile.toPath())
                        .model(apiConfig.model)
                        .responseFormat(AudioResponseFormat.JSON);  // gpt-4o-transcribe only supports json

                if (!currentInputLanguageValue.equals("detect")) transcriptionBuilder.language(currentInputLanguageValue);
                if (!stylePrompt.isEmpty()) transcriptionBuilder.prompt(stylePrompt);
                if (sp.getBoolean("com.idefant.voicekb.proxy_enabled", false)) {
                    if (VoiceKBUtils.isValidProxy(proxyHost)) VoiceKBUtils.applyProxy(clientBuilder, sp);
                }
                Log.d("VoiceKBKeyboardSerice", "Style-Prompt: " + stylePrompt);

                Transcription transcription;
                int retryCount = 0;
                while (true) {
                    try {
                        transcription = clientBuilder.build().audio().transcriptions().create(transcriptionBuilder.build()).asTranscription();
                        break;
                    } catch (RuntimeException e) {
                        String msg = e.getMessage() != null ? e.getMessage().toLowerCase(Locale.ROOT) : "";
                        boolean isRetryable = !msg.contains("api key") && !msg.contains("quota") && !msg.contains("audio duration")
                                && !msg.contains("content size limit") && !msg.contains("format");

                        if (isRetryable && retryCount < 3) {
                            retryCount++;
                            try { Thread.sleep(3000); } catch (InterruptedException ignored) {}
                        } else {
                            throw e;
                        }
                    }
                }
                String resultText = transcription.text().strip();  // Groq sometimes adds leading whitespace

                usageDb.edit(apiConfig.model, VoiceKBUtils.getAudioDuration(audioFile), 0, 0, apiConfig.provider);

                mainHandler.post(() -> commitTextToInputConnection(resultText, () -> {
                    keyboardUiState = KeyboardUiState.IDLE;
                    renderKeyboardState();
                    boolean keepKeyboard = keepKeyboardAfterCurrentTranscription;
                    keepKeyboardAfterCurrentTranscription = false;
                    transientVoiceSession = false;
                    if (!keepKeyboard && sp.getBoolean(PREF_RETURN_TO_PREVIOUS_KEYBOARD, false)) {
                        switchToPreviousKeyboard(true);
                    } else {
                        restoreKeyboardVisibility();
                    }
                }));
                return;

            } catch (RuntimeException e) {
                if (!(e.getCause() instanceof InterruptedIOException)) {
                    logExceptionLocally(e);
                    if (vibrationEnabled) vibrator.vibrate(VibrationEffect.createOneShot(300, VibrationEffect.DEFAULT_AMPLITUDE));
                    mainHandler.post(() -> {
                        keyboardUiState = KeyboardUiState.IDLE;
                        renderKeyboardState();
                        String message = e.getMessage() != null ? e.getMessage().toLowerCase(Locale.ROOT) : "";
                        String providerName = VoiceKBUtils.getTranscriptionProviderName(this, sp);
                        if (message.contains("api key")) {
                            showInfo("invalid_api_key");
                        } else if (message.contains("quota")) {
                            showInfo("quota_exceeded", providerName);
                        } else if (message.contains("audio duration") || message.contains("content size limit")) {  // gpt-o-transcribe and whisper have different limits
                            showInfo("content_size_limit");
                        } else if (message.contains("format")) {
                            showInfo("format_not_supported");
                        } else {
                            showInfo("internet_error", providerName);
                        }
                    });
                } else if (e.getCause().getMessage() != null && (e.getCause().getMessage().contains("timeout") || e.getCause().getMessage().contains("failed to connect"))) {
                    logExceptionLocally(e);
                    if (vibrationEnabled) vibrator.vibrate(VibrationEffect.createOneShot(300, VibrationEffect.DEFAULT_AMPLITUDE));
                    mainHandler.post(() -> {
                        keyboardUiState = KeyboardUiState.IDLE;
                        renderKeyboardState();
                        showInfo("timeout", VoiceKBUtils.getTranscriptionProviderName(this, sp));
                    });
                }
            }


            mainHandler.post(() -> {
                keyboardUiState = KeyboardUiState.IDLE;
                renderKeyboardState();
            });
        });
    }

    private void commitTextToInputConnection(String text, Runnable onComplete) {
        InputConnection inputConnection = getCurrentInputConnection();
        if (inputConnection == null) {
            mainHandler.post(this::finishWithoutTextInsertion);
            return;
        }

        String output = prepareSmartInsertionText(inputConnection, text == null ? "" : text);
        if (output.isEmpty()) {
            mainHandler.post(this::finishWithoutTextInsertion);
            return;
        }
        inputConnection.commitText(output, 1);
        markEditorHistoryChanged();
        if (sp.getBoolean("com.idefant.voicekb.auto_enter", false)) {
            performEnterAction();
        }
        if (mainHandler != null) {
            mainHandler.post(onComplete);
        } else {
            onComplete.run();
        }
    }

    private String prepareSmartInsertionText(InputConnection inputConnection, String text) {
        if (TextUtils.isEmpty(text)) return "";
        CharSequence beforeCursor;
        try {
            beforeCursor = inputConnection.getTextBeforeCursor(256, 0);
        } catch (RuntimeException e) {
            logExceptionLocally(e);
            return text.strip();
        }
        if (beforeCursor == null) return text;

        String before = beforeCursor.toString();
        String result = text.strip();
        if (result.isEmpty()) return "";

        int firstLetterIndex = firstLetterIndex(result);
        if (firstLetterIndex >= 0 && shouldApplySmartCase(result, firstLetterIndex)) {
            Character replacement = smartCaseReplacement(before, result.charAt(firstLetterIndex));
            if (replacement != null) {
                result = replaceCharAt(result, firstLetterIndex, replacement);
            }
        }

        if (needsLeadingSpace(before, result)) {
            result = " " + result;
        }
        return result;
    }

    private boolean shouldApplySmartCase(String text, int firstLetterIndex) {
        String blacklistToken = firstWordTokenBeforeApostrophe(text, firstLetterIndex);
        if (isSmartInsertionBlacklisted(blacklistToken)) return false;
        return !sp.getBoolean(PREF_SMART_PRESERVE_ABBREVIATIONS, true)
                || !firstWordIsAbbreviation(text, firstLetterIndex);
    }

    private Character smartCaseReplacement(String before, char firstLetter) {
        boolean previousSentenceFinished = before.trim().isEmpty() || previousTextEndsSentence(before);
        if (sp.getBoolean(PREF_SMART_CAPITALIZE_MARKDOWN_LIST, false)
                && previousTextLooksLikeMarkdownListMarker(before)) {
            return Character.toTitleCase(firstLetter);
        }
        if (sp.getBoolean(PREF_SMART_CAPITALIZE_QUOTE, false)
                && previousTextLooksLikeMarkdownQuoteMarker(before)) {
            return Character.toTitleCase(firstLetter);
        }
        if (previousSentenceFinished && sp.getBoolean(PREF_SMART_CAPITALIZE_AFTER_SENTENCE, true)) {
            return Character.toTitleCase(firstLetter);
        }
        if (!previousSentenceFinished && sp.getBoolean(PREF_SMART_LOWERCASE_UNFINISHED, false)) {
            return Character.toLowerCase(firstLetter);
        }
        return null;
    }

    private boolean needsLeadingSpace(String before, String text) {
        if (TextUtils.isEmpty(before) || TextUtils.isEmpty(text)) return false;
        char previous = before.charAt(before.length() - 1);
        char next = text.charAt(0);
        if (Character.isWhitespace(previous) || Character.isWhitespace(next)) return false;
        if ("([{\u00AB\u201C\u2018".indexOf(previous) >= 0) return false;
        return ".,!?;:\u2026)]}\u00BB\u201D\u2019".indexOf(next) < 0;
    }

    private boolean previousTextEndsSentence(String before) {
        if (TextUtils.isEmpty(before)) return true;
        int index = before.length() - 1;
        while (index >= 0 && Character.isWhitespace(before.charAt(index))) index--;
        while (index >= 0 && CLOSING_AFTER_SENTENCE_CHARS.indexOf(before.charAt(index)) >= 0) index--;
        if (index < 0) return true;
        return SENTENCE_END_CHARS.indexOf(before.charAt(index)) >= 0;
    }

    private int firstLetterIndex(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (Character.isLetter(text.charAt(i))) return i;
        }
        return -1;
    }

    private boolean firstWordIsAbbreviation(String text, int firstLetterIndex) {
        int uppercaseCount = 0;
        for (int i = firstLetterIndex; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!Character.isLetter(c)) break;
            if (Character.isUpperCase(c)) uppercaseCount++;
            if (uppercaseCount >= 2) return true;
        }
        return false;
    }

    private String firstWordTokenBeforeApostrophe(String text, int firstLetterIndex) {
        StringBuilder token = new StringBuilder();
        for (int i = firstLetterIndex; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\'' || c == '\u2019') break;
            if (!Character.isLetter(c)) break;
            token.append(c);
        }
        return token.toString();
    }

    private boolean isSmartInsertionBlacklisted(String token) {
        if (TextUtils.isEmpty(token)) return false;
        String blacklist = sp.getString(PREF_SMART_BLACKLIST, "I");
        if (blacklist == null) return false;
        for (String line : blacklist.split("\\r?\\n|\\r")) {
            if (token.equals(line.trim())) return true;
        }
        return false;
    }

    private boolean previousTextLooksLikeMarkdownListMarker(String before) {
        String line = currentLineBeforeCursor(before).stripLeading();
        return line.matches("[-+*]\\s+")
                || line.matches("[-+*]\\s+\\[[ xX]\\]\\s+");
    }

    private boolean previousTextLooksLikeMarkdownQuoteMarker(String before) {
        return currentLineBeforeCursor(before).stripLeading().matches(">\\s+");
    }

    private String currentLineBeforeCursor(String before) {
        int lineBreakIndex = Math.max(before.lastIndexOf('\n'), before.lastIndexOf('\r'));
        return lineBreakIndex >= 0 ? before.substring(lineBreakIndex + 1) : before;
    }

    private String replaceCharAt(String text, int index, char character) {
        if (text.charAt(index) == character) return text;
        return text.substring(0, index) + character + text.substring(index + 1);
    }

    private void finishWithoutTextInsertion() {
        keyboardUiState = KeyboardUiState.IDLE;
        renderKeyboardState();
    }

    private void switchToPreviousKeyboard(boolean showPickerOnFailure) {
        boolean success = false;
        switchingToPreviousKeyboard = true;
        instantRecordingConsumedForCurrentImeSelection = false;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                success = switchToPreviousInputMethod();
            } else {
                InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                success = imm.switchToLastInputMethod(getWindow().getWindow().getAttributes().token);
            }
        } catch (Exception ignored) {}

        if (!success) {
            switchingToPreviousKeyboard = false;
            if (showPickerOnFailure) {
                InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                imm.showInputMethodPicker();
            }
        }
    }

    private void logExceptionLocally(Exception e) {
        // Firebase Crashlytics reporting is intentionally paused. Keep this as
        // local logging until privacy/error-reporting behavior is redesigned.
        StringWriter sw = new StringWriter();
        e.printStackTrace(new PrintWriter(sw));
        Log.e("VoiceKBInputMethodService", sw.toString());
    }

    private void showInfo(String type) {
        showInfo(type, VoiceKBUtils.getTranscriptionProviderName(this, sp));
    }

    private void showInfoMessage(String message) {
        infoCl.setVisibility(View.VISIBLE);
        infoNoButton.setVisibility(View.VISIBLE);
        infoYesButton.setVisibility(View.VISIBLE);
        infoTv.setTextColor(getResources().getColor(R.color.voicekb_red, getTheme()));
        infoTv.setText(message);
        infoYesButton.setOnClickListener(v -> {
            openSettingsActivity();
            infoCl.setVisibility(View.GONE);
        });
        infoNoButton.setOnClickListener(v -> infoCl.setVisibility(View.GONE));
    }

    private void showInfo(String type, String providerName) {
        infoCl.setVisibility(View.VISIBLE);
        infoNoButton.setVisibility(View.VISIBLE);
        infoTv.setTextColor(getResources().getColor(R.color.voicekb_red, getTheme()));
        switch (type) {
            case "timeout":
                infoTv.setText(getString(R.string.voicekb_timeout_msg, providerName));
                infoYesButton.setVisibility(View.GONE);
                infoNoButton.setOnClickListener(v -> infoCl.setVisibility(View.GONE));
                break;
            case "invalid_api_key":
                infoTv.setText(R.string.voicekb_invalid_api_key_msg);
                infoYesButton.setVisibility(View.VISIBLE);
                infoYesButton.setOnClickListener(v -> {
                    openSettingsActivity();
                    infoCl.setVisibility(View.GONE);
                });
                infoNoButton.setOnClickListener(v -> infoCl.setVisibility(View.GONE));
                break;
            case "quota_exceeded":
                infoTv.setText(getString(R.string.voicekb_quota_exceeded_msg, providerName));
                if (VoiceKBUtils.isOpenAITranscriptionProvider(sp)) {
                    infoYesButton.setVisibility(View.VISIBLE);
                    infoYesButton.setOnClickListener(v -> {
                        Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://platform.openai.com/settings/organization/billing/overview"));
                        browserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(browserIntent);
                        infoCl.setVisibility(View.GONE);
                    });
                } else {
                    infoYesButton.setVisibility(View.GONE);
                }
                infoNoButton.setOnClickListener(v -> infoCl.setVisibility(View.GONE));
                break;
            case "content_size_limit":
                infoTv.setText(R.string.voicekb_content_size_limit_msg);
                infoYesButton.setVisibility(View.GONE);
                infoNoButton.setOnClickListener(v -> infoCl.setVisibility(View.GONE));
                break;
            case "format_not_supported":
                infoTv.setText(R.string.voicekb_format_not_supported_msg);
                infoYesButton.setVisibility(View.GONE);
                infoNoButton.setOnClickListener(v -> infoCl.setVisibility(View.GONE));
                break;
            case "internet_error":
                infoTv.setText(getString(R.string.voicekb_internet_error_msg, providerName));
                infoYesButton.setVisibility(View.GONE);
                infoNoButton.setOnClickListener(v -> infoCl.setVisibility(View.GONE));
                break;
        }
    }

    private List<String> getAvailableInputLanguages() {
        return InputLanguageManager.getAvailableLanguages(this, sp);
    }

    private void ensureCurrentInputLanguage() {
        currentInputLanguageValue = InputLanguageManager.ensureSelectedLanguage(this, sp, currentInputLanguagePos);
        currentInputLanguagePos = sp.getInt(InputLanguageManager.PREF_INPUT_LANGUAGE_POS, 0);
    }

    private void selectInputLanguage(String language, boolean rerender) {
        currentInputLanguageValue = InputLanguageManager.selectLanguage(this, sp, language);
        currentInputLanguagePos = sp.getInt(InputLanguageManager.PREF_INPUT_LANGUAGE_POS, 0);
        if (rerender) renderKeyboardState();
    }

    private String getCurrentLanguageLabel() {
        return InputLanguageManager.getShortLabel(currentInputLanguageValue);
    }

    private String getInputLanguageDisplayName(String language) {
        return InputLanguageManager.getDisplayName(this, language);
    }

    private void showQuickSettingsPopup() {
        dismissActiveDialog();
        pauseRecordingForPopup();

        View content = LayoutInflater.from(new ContextThemeWrapper(this, R.style.Theme_VoiceKB))
                .inflate(R.layout.popup_keyboard_quick_settings, null);
        AlertDialog dialog = createKeyboardDialog(content);

        TextView languageValue = content.findViewById(R.id.quick_settings_language_value);
        languageValue.setText(getCurrentLanguageLabel());
        content.findViewById(R.id.quick_settings_cancel).setOnClickListener(v -> dismissActiveDialog());
        content.findViewById(R.id.quick_settings_keyboard_settings).setOnClickListener(v -> {
            suppressTransientKeyboardReturnOnce = true;
            dismissActiveDialog(false);
            openSettingsActivity();
        });
        content.findViewById(R.id.quick_settings_language).setOnClickListener(v -> {
            preserveInputViewForDialogDismiss();
            dismissActiveDialog(false);
            mainHandler.postDelayed(this::showLanguageSelectionPopup, 120);
        });
        content.findViewById(R.id.quick_settings_change_input_method).setOnClickListener(v -> {
            suppressTransientKeyboardReturnOnce = true;
            preserveInputViewForDialogDismiss();
            dismissActiveDialog(false);
            mainHandler.postDelayed(() -> {
                InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                imm.showInputMethodPicker();
            }, 180);
        });

        showKeyboardDialog(dialog);
    }

    private void showLanguageSelectionPopup() {
        dismissActiveDialog();
        pauseRecordingForPopup();

        View content = LayoutInflater.from(new ContextThemeWrapper(this, R.style.Theme_VoiceKB))
                .inflate(R.layout.popup_keyboard_language_selection, null);
        AlertDialog dialog = createKeyboardDialog(content);
        LinearLayout options = content.findViewById(R.id.language_selection_options);
        List<String> languages = getAvailableInputLanguages();
        for (int i = 0; i < languages.size(); i++) {
            String language = languages.get(i);
            options.addView(createLanguageOptionRow(language));
            if (i < languages.size() - 1) options.addView(createPanelDivider());
        }

        content.findViewById(R.id.language_selection_cancel).setOnClickListener(v -> dismissActiveDialog());
        showKeyboardDialog(dialog);
    }

    private AlertDialog createKeyboardDialog(View content) {
        tintPopupForKeyboardTheme(content);
        AlertDialog dialog = new MaterialAlertDialogBuilder(new ContextThemeWrapper(this, R.style.Theme_VoiceKB))
                .setView(content)
                .create();
        dialog.setCanceledOnTouchOutside(true);
        dialog.setOnDismissListener(ignored -> {
            if (activeDialog == dialog) activeDialog = null;
            if (!dialogsDismissedWithoutImeRestore.remove(dialog)) {
                preserveInputViewForDialogDismiss();
                restoreKeyboardVisibility();
            }
        });
        return dialog;
    }

    private void showKeyboardDialog(AlertDialog dialog) {
        Window window = dialog.getWindow();
        if (window == null || voicekbKeyboardView.getWindowToken() == null) return;

        WindowManager.LayoutParams params = window.getAttributes();
        params.token = voicekbKeyboardView.getWindowToken();
        params.type = WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG;
        window.setAttributes(params);
        window.addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
                | WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.setDimAmount(0.72f);
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));

        activeDialog = dialog;
        dialog.show();
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.setLayout(dp(356), WindowManager.LayoutParams.WRAP_CONTENT);
    }

    private void dismissActiveDialog() {
        dismissActiveDialog(true);
    }

    private void dismissActiveDialog(boolean restoreIme) {
        if (activeDialog != null) {
            AlertDialog dialog = activeDialog;
            activeDialog = null;
            if (restoreIme) {
                preserveInputViewForDialogDismiss();
            } else {
                dialogsDismissedWithoutImeRestore.add(dialog);
            }
            dialog.dismiss();
        }
    }

    private void preserveInputViewForDialogDismiss() {
        preserveInputViewAfterDialogDismiss = true;
        restoreInputViewStateAfterDialogDismiss = true;
        int generation = ++dialogDismissRestoreGeneration;
        mainHandler.postDelayed(() -> {
            if (generation == dialogDismissRestoreGeneration) {
                preserveInputViewAfterDialogDismiss = false;
            }
        }, 600);
        mainHandler.postDelayed(() -> {
            if (generation == dialogDismissRestoreGeneration) {
                restoreInputViewStateAfterDialogDismiss = false;
            }
        }, 1500);
    }

    private void restoreKeyboardVisibility() {
        if (mainHandler == null) return;
        mainHandler.postDelayed(() -> {
            if (voicekbKeyboardView != null && voicekbKeyboardView.getWindowToken() != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    requestShowSelf(InputMethodManager.SHOW_IMPLICIT);
                } else {
                    InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                    imm.showSoftInput(voicekbKeyboardView, InputMethodManager.SHOW_IMPLICIT);
                }
            }
        }, 80);
    }

    private void pauseRecordingForPopup() {
        if (isRecording && !isPaused && pauseButton != null) pauseButton.performClick();
    }

    private void tintPopupForKeyboardTheme(View root) {
        View panel = root.findViewById(R.id.quick_settings_panel);
        if (panel == null) panel = root.findViewById(R.id.language_selection_panel);
        if (panel != null) {
            panel.setBackground(createRoundedBackground(getPanelColor(
                    R.color.voicekb_keyboard_panel_light,
                    R.color.voicekb_keyboard_panel_dark), 20));
        }
        tintPopupView(root);
    }

    private void tintPopupView(View view) {
        String tag = view.getTag() instanceof String ? (String) view.getTag() : "";
        switch (tag) {
            case "panel_surface":
                view.setBackgroundColor(Color.TRANSPARENT);
                break;
            case "panel_divider":
                view.setBackgroundColor(getPanelColor(
                        R.color.voicekb_keyboard_divider_light,
                        R.color.voicekb_keyboard_divider_dark));
                break;
            case "panel_icon_container":
                view.setBackground(createRoundedBackground(getPanelColor(
                        R.color.voicekb_keyboard_enter_light,
                        R.color.voicekb_keyboard_enter_dark), 12));
                break;
            case "panel_title":
                ((TextView) view).setTextColor(getPanelColor(
                        R.color.voicekb_keyboard_panel_title_light,
                        R.color.voicekb_keyboard_panel_title_dark));
                break;
            case "panel_text":
                ((TextView) view).setTextColor(getPanelColor(
                        R.color.voicekb_keyboard_panel_text_light,
                        R.color.voicekb_keyboard_panel_text_dark));
                break;
            case "panel_muted":
                ((TextView) view).setTextColor(getPanelColor(
                        R.color.voicekb_keyboard_muted_text_light,
                        R.color.voicekb_keyboard_muted_text_dark));
                break;
            case "panel_accent":
                ((TextView) view).setTextColor(getPanelColor(
                        R.color.voicekb_keyboard_primary_pressed,
                        R.color.voicekb_keyboard_language_icon_dark));
                break;
            case "panel_icon":
                ((ImageView) view).setImageTintList(ColorStateList.valueOf(getPanelColor(
                        R.color.voicekb_keyboard_panel_icon_light,
                        R.color.voicekb_keyboard_panel_icon_dark)));
                break;
            case "panel_chevron":
                ((ImageView) view).setImageTintList(ColorStateList.valueOf(getPanelColor(
                        R.color.voicekb_keyboard_panel_chevron_light,
                        R.color.voicekb_keyboard_panel_chevron_dark)));
                break;
            case "panel_accent_icon":
                ((ImageView) view).setImageTintList(ColorStateList.valueOf(getPanelColor(
                        R.color.voicekb_keyboard_primary_pressed,
                        R.color.voicekb_keyboard_language_icon_dark)));
                break;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) tintPopupView(group.getChildAt(i));
        }
    }

    private View createLanguageOptionRow(String language) {
        boolean selected = language.equals(currentInputLanguageValue);
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
        TypedValue selectableBackground = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, selectableBackground, true);
        row.setForeground(ContextCompat.getDrawable(this, selectableBackground.resourceId));

        FrameLayout radioFrame = new FrameLayout(this);
        radioFrame.setLayoutParams(new LinearLayout.LayoutParams(dp(20), dp(20)));
        GradientDrawable radioBackground = createRoundedBackground(Color.TRANSPARENT, 10);
        radioBackground.setStroke(dp(2), selected ? accent : radio);
        radioFrame.setBackground(radioBackground);
        if (selected) {
            View dot = new View(this);
            FrameLayout.LayoutParams dotParams = new FrameLayout.LayoutParams(dp(10), dp(10), Gravity.CENTER);
            dot.setLayoutParams(dotParams);
            dot.setBackground(createRoundedBackground(accent, 5));
            radioFrame.addView(dot);
        }
        row.addView(radioFrame);

        TextView label = new TextView(this);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        labelParams.setMarginStart(dp(12));
        label.setLayoutParams(labelParams);
        label.setText(getInputLanguageDisplayName(language));
        label.setTextColor(text);
        label.setTextSize(13);
        row.addView(label);

        TextView code = new TextView(this);
        code.setText(getShortLanguageLabel(language));
        code.setTextColor(selected ? accent : muted);
        code.setTextSize(10);
        code.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        row.addView(code);

        row.setOnClickListener(v -> {
            selectInputLanguage(language, true);
            dismissActiveDialog();
        });
        return row;
    }

    private View createPanelDivider() {
        View divider = new View(this);
        divider.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
        divider.setBackgroundColor(getPanelColor(
                R.color.voicekb_keyboard_divider_light,
                R.color.voicekb_keyboard_divider_dark));
        return divider;
    }

    private int getPanelColor(int lightColor, int darkColor) {
        return getResources().getColor(keyboardThemeDark ? darkColor : lightColor, getTheme());
    }

    private GradientDrawable createRoundedBackground(int color, int radiusDp) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(radiusDp));
        return background;
    }

    private String getShortLanguageLabel(String language) {
        return InputLanguageManager.getShortLabel(language);
    }

    private void deleteOneCharacter() {
        InputConnection inputConnection = getCurrentInputConnection();
        if (inputConnection == null) return;

        CharSequence selectedText = inputConnection.getSelectedText(0);
        if (selectedText != null && selectedText.length() > 0) {
            inputConnection.commitText("", 1);
            markEditorHistoryChanged();
            return;
        }

        CharSequence textBeforeCursor = inputConnection.getTextBeforeCursor(DELETE_LOOKBACK_CHARACTERS, 0);
        if (textBeforeCursor == null || textBeforeCursor.length() == 0) {
            inputConnection.deleteSurroundingText(1, 0);
            markEditorHistoryChanged();
            return;
        }

        String before = textBeforeCursor.toString();
        BreakIterator breakIterator = BreakIterator.getCharacterInstance(Locale.getDefault());
        breakIterator.setText(before);

        int end = before.length();
        int start = breakIterator.preceding(end);
        if (start == BreakIterator.DONE) {
            try {
                start = before.offsetByCodePoints(end, -1);
            } catch (IndexOutOfBoundsException ignored) {
                start = Math.max(0, end - 1);
            }
        }

        int charsToDelete = Math.max(1, end - start);
        inputConnection.deleteSurroundingText(charsToDelete, 0);
        markEditorHistoryChanged();
    }

    private boolean isPointInsideSpecialCharacterSelectionArea(float x, float y) {
        int[] trayLocation = new int[2];
        int[] keyboardLocation = new int[2];
        overlayCharactersLl.getLocationOnScreen(trayLocation);
        voicekbKeyboardView.getLocationOnScreen(keyboardLocation);
        int keyboardRight = keyboardLocation[0] + voicekbKeyboardView.getWidth();
        int keyboardBottom = keyboardLocation[1] + voicekbKeyboardView.getHeight();

        return x >= trayLocation[0]
                && x <= keyboardRight
                && y >= trayLocation[1]
                && y <= keyboardBottom;
    }

    private TextView findSpecialCharacterByRawX(float rawX) {
        TextView closestCharacter = null;
        float closestDistance = Float.MAX_VALUE;

        for (int i = 0; i < overlayCharactersLl.getChildCount(); i++) {
            TextView charView = (TextView) overlayCharactersLl.getChildAt(i);
            if (charView.getVisibility() != View.VISIBLE) continue;

            int[] location = new int[2];
            charView.getLocationOnScreen(location);
            int left = location[0];
            int right = left + charView.getWidth();
            if (rawX >= left && rawX <= right) {
                return charView;
            }

            float center = left + charView.getWidth() / 2f;
            float distance = Math.abs(rawX - center);
            if (distance < closestDistance) {
                closestDistance = distance;
                closestCharacter = charView;
            }
        }

        return closestCharacter;
    }

    private void selectSpecialCharacter(TextView character) {
        if (selectedCharacter == character) return;
        selectedCharacter = character;
        highlightSelectedCharacter(selectedCharacter);
    }

    private void highlightSelectedCharacter(TextView selectedView) {
        int textColor = getResources().getColor(
                keyboardThemeDark ? R.color.voicekb_keyboard_tray_text_dark : R.color.voicekb_keyboard_tray_text_light,
                getTheme());
        for (int i = 0; i < overlayCharactersLl.getChildCount(); i++) {
            TextView charView = (TextView) overlayCharactersLl.getChildAt(i);
            styleSpecialCharacter(charView, charView == selectedView, textColor);
        }
    }

    private void styleSpecialCharacter(TextView charView, boolean selected, int normalTextColor) {
        GradientDrawable background = (GradientDrawable) charView.getBackground();
        background.setColor(selected
                ? getResources().getColor(R.color.voicekb_keyboard_primary, getTheme())
                : Color.TRANSPARENT);
        charView.setTextColor(selected
                ? getResources().getColor(R.color.voicekb_white, getTheme())
                : normalTextColor);
        charView.setTypeface(Typeface.DEFAULT, selected ? Typeface.BOLD : Typeface.NORMAL);
    }

    // Compute progressive word boundaries to the left of the cursor for swipe selection
    private List<Integer> computeWordBoundaries(String before) {
        // returns absolute start indices (0..cursor) for selection:
        // boundaries[0] = cursor, boundaries[1] = start of previous "word incl. preceding spaces", etc.
        java.util.ArrayList<Integer> res = new java.util.ArrayList<>();
        int pos = before.length();
        res.add(pos);

        while (pos > 0) {
            int i = pos;

            while (i > 0 && Character.isWhitespace(before.charAt(i - 1))) i--;  // 1) skip whitespace to the left

            while (i > 0) {  // 2) skip non-alnum punctuation to the left
                char c = before.charAt(i - 1);
                if (Character.isLetterOrDigit(c) || Character.isWhitespace(c)) break;
                i--;
            }

            while (i > 0 && Character.isLetterOrDigit(before.charAt(i - 1))) i--;  // 3) skip letters/digits (the word)

            while (i > 0 && Character.isWhitespace(before.charAt(i - 1))) i--;  // 4) also include preceding spaces so each step removes "space + word"

            if (i == pos) i--;
            pos = i;
            res.add(pos);
        }

        return res;
    }

    private void applyRecordingIconState(boolean active) {
        // Kept as a transition hook: the new design deliberately avoids geometry changes.
    }

    // Helpers for Bluetooth/SCO availability
    private boolean hasBluetoothInputDevice() {
        try {
            AudioDeviceInfo[] inputs = am.getDevices(AudioManager.GET_DEVICES_INPUTS);
            for (AudioDeviceInfo info : inputs) {
                if (info.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                    return true;
                }
            }
            return false;
        } catch (Exception ignored) {}
        return am.isBluetoothScoOn();  // fallback heuristic
    }

    private void updateRecordButtonIconWhileRecording() {
        if (!isRecording) return;
        setRecordIcon(R.drawable.ic_lucide_send_24, 33, 21);
        recordBluetoothBadge.setVisibility(recordingUsesBluetooth ? View.VISIBLE : View.GONE);
    }

    private void cancelScoWaitIfAny() {
        recordingPending = false;
        waitingForSco = false;
        isPreparingRecording = false;
        if (bluetoothHandler != null && scoTimeoutRunnable != null) {
            bluetoothHandler.removeCallbacks(scoTimeoutRunnable);
        }
    }
}
