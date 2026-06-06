package com.idefant.voicekb.settings;

import android.content.SharedPreferences;
import android.graphics.Rect;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import com.idefant.voicekb.R;
import com.idefant.voicekb.SimpleTextWatcher;
import com.idefant.voicekb.core.ApiTokenChecker;
import com.idefant.voicekb.core.TranscriptionApiConfig;

import java.io.IOException;
import java.util.stream.IntStream;

public class APISettingsActivity extends AppCompatActivity {

    private static final String PREF = "com.idefant.voicekb.";

    private SharedPreferences sp;
    private ArrayAdapter<CharSequence> providerAdapter;
    private ArrayAdapter<CharSequence> openAIModelAdapter;
    private ArrayAdapter<CharSequence> groqModelAdapter;
    private boolean ignoreChanges;

    private ApiForm primaryForm;
    private ApiForm vpnForm;
    private Switch vpnEnabledSwitch;
    private LinearLayout vpnWrapper;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_api_settings);
        ScrollView scrollView = findViewById(R.id.activity_api_settings);
        ViewCompat.setOnApplyWindowInsetsListener(scrollView, (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right,
                    Math.max(systemBars.bottom, ime.bottom));
            if (insets.isVisible(WindowInsetsCompat.Type.ime())) {
                scrollFocusedEditTextIntoView(scrollView);
            }
            return insets;
        });
        installEditTextAutoScroll(scrollView);

        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
            actionBar.setTitle(R.string.voicekb_api_settings);
        }

        sp = getSharedPreferences("com.idefant.voicekb", MODE_PRIVATE);
        providerAdapter = ArrayAdapter.createFromResource(this,
                R.array.voicekb_api_providers, android.R.layout.simple_spinner_item);
        providerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        openAIModelAdapter = ArrayAdapter.createFromResource(this,
                R.array.voicekb_transcription_models_openai, android.R.layout.simple_spinner_item);
        openAIModelAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        groqModelAdapter = ArrayAdapter.createFromResource(this,
                R.array.voicekb_transcription_models_groq, android.R.layout.simple_spinner_item);
        groqModelAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);

        primaryForm = new ApiForm("", false,
                findViewById(R.id.api_settings_transcription_provider_spn),
                findViewById(R.id.api_settings_provider_summary_tv),
                findViewById(R.id.api_settings_transcription_model_label),
                findViewById(R.id.api_settings_transcription_model_spn),
                findViewById(R.id.api_settings_transcription_custom_model_et),
                findViewById(R.id.api_settings_transcription_api_key_et),
                findViewById(R.id.api_settings_check_token_btn),
                findViewById(R.id.api_settings_additional_parameters_sw),
                findViewById(R.id.api_settings_additional_parameters_wrapper),
                findViewById(R.id.api_settings_additional_url_et),
                findViewById(R.id.api_settings_additional_headers_et));

        vpnForm = new ApiForm("vpn_", true,
                findViewById(R.id.api_settings_vpn_provider_spn),
                findViewById(R.id.api_settings_vpn_provider_summary_tv),
                findViewById(R.id.api_settings_vpn_model_label),
                findViewById(R.id.api_settings_vpn_model_spn),
                findViewById(R.id.api_settings_vpn_custom_model_et),
                findViewById(R.id.api_settings_vpn_api_key_et),
                findViewById(R.id.api_settings_vpn_check_token_btn),
                findViewById(R.id.api_settings_vpn_additional_parameters_sw),
                findViewById(R.id.api_settings_vpn_additional_parameters_wrapper),
                findViewById(R.id.api_settings_vpn_additional_url_et),
                findViewById(R.id.api_settings_vpn_additional_headers_et));

        vpnEnabledSwitch = findViewById(R.id.api_settings_vpn_enabled_sw);
        vpnWrapper = findViewById(R.id.api_settings_vpn_wrapper);
        vpnEnabledSwitch.setChecked(sp.getBoolean(PREF + "vpn_config_enabled", false));
        vpnWrapper.setVisibility(vpnEnabledSwitch.isChecked() ? View.VISIBLE : View.GONE);
        vpnEnabledSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            sp.edit().putBoolean(PREF + "vpn_config_enabled", isChecked).apply();
            vpnWrapper.setVisibility(isChecked ? View.VISIBLE : View.GONE);
        });

        bindForm(primaryForm);
        bindForm(vpnForm);
    }

    private void bindForm(ApiForm form) {
        form.providerSpinner.setAdapter(providerAdapter);
        int provider = sp.getInt(pref(form, "transcription_provider"), TranscriptionApiConfig.PROVIDER_GROQ);
        form.providerSpinner.setSelection(safeProvider(provider));
        form.providerSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (ignoreChanges) return;
                int newProvider = safeProvider(position);
                if (form.loadedProvider != -1) {
                    saveFormValuesForProvider(form, form.loadedProvider);
                }
                sp.edit().putInt(pref(form, "transcription_provider"), newProvider).apply();
                updateFormForProvider(form);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        form.modelSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (ignoreChanges) return;
                int provider = currentProvider(form);
                if (provider == TranscriptionApiConfig.PROVIDER_OPENAI) {
                    String model = getResources().getStringArray(R.array.voicekb_transcription_models_openai_values)[position];
                    sp.edit().putString(pref(form, "transcription_openai_model"), model).apply();
                } else if (provider == TranscriptionApiConfig.PROVIDER_GROQ) {
                    String model = getResources().getStringArray(R.array.voicekb_transcription_models_groq_values)[position];
                    sp.edit().putString(pref(form, "transcription_groq_model"), model).apply();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        form.apiKeyEdit.addTextChangedListener(new SimpleTextWatcher() {
            @Override
            public void afterTextChanged(Editable editable) {
                if (ignoreChanges) return;
                int provider = currentProvider(form);
                String value = editable.toString();
                SharedPreferences.Editor editor = sp.edit()
                        .putString(pref(form, "transcription_api_key"), value);
                if (provider == TranscriptionApiConfig.PROVIDER_OPENAI) {
                    editor.putString(pref(form, "transcription_api_key_openai"), value);
                } else if (provider == TranscriptionApiConfig.PROVIDER_GROQ) {
                    editor.putString(pref(form, "transcription_api_key_groq"), value);
                } else {
                    editor.putString(pref(form, "transcription_api_key_custom"), value);
                }
                editor.apply();
            }
        });

        form.customModelEdit.addTextChangedListener(new SimpleTextWatcher() {
            @Override
            public void afterTextChanged(Editable editable) {
                if (!ignoreChanges) {
                    sp.edit().putString(pref(form, "transcription_custom_model"), editable.toString()).apply();
                }
            }
        });

        form.additionalSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (ignoreChanges) return;
            sp.edit().putBoolean(additionalEnabledKey(form, currentProvider(form)), isChecked).apply();
            updateAdditionalVisibility(form);
        });

        form.additionalUrlEdit.addTextChangedListener(new SimpleTextWatcher() {
            @Override
            public void afterTextChanged(Editable editable) {
                if (ignoreChanges) return;
                sp.edit().putString(additionalUrlKey(form, currentProvider(form)), editable.toString()).apply();
            }
        });

        form.headersEdit.addTextChangedListener(new SimpleTextWatcher() {
            @Override
            public void afterTextChanged(Editable editable) {
                if (!ignoreChanges) {
                    sp.edit().putString(additionalHeadersKey(form, currentProvider(form)), editable.toString()).apply();
                }
            }
        });

        form.checkTokenButton.setOnClickListener(v -> checkToken(form));
        updateFormForProvider(form);
    }

    private void updateFormForProvider(ApiForm form) {
        ignoreChanges = true;
        int provider = currentProvider(form);
        boolean customProvider = provider == TranscriptionApiConfig.PROVIDER_CUSTOM;

        form.modelSpinner.setVisibility(customProvider ? View.GONE : View.VISIBLE);
        form.customModelEdit.setVisibility(customProvider ? View.VISIBLE : View.GONE);
        form.checkTokenButton.setVisibility(customProvider ? View.GONE : View.VISIBLE);
        form.additionalSwitch.setVisibility(customProvider ? View.GONE : View.VISIBLE);
        form.providerSummary.setText(providerSummary(provider));
        form.apiKeyEdit.setHint(apiKeyHint(provider));

        if (provider == TranscriptionApiConfig.PROVIDER_OPENAI) {
            String model = sp.getString(pref(form, "transcription_openai_model"),
                    sp.getString(PREF + "transcription_model", "gpt-4o-mini-transcribe"));
            form.modelSpinner.setAdapter(openAIModelAdapter);
            form.modelSpinner.setSelection(findModelPosition(
                    R.array.voicekb_transcription_models_openai_values, model, openAIModelAdapter.getCount()));
            form.apiKeyEdit.setText(readProviderKey(form, provider));
        } else if (provider == TranscriptionApiConfig.PROVIDER_GROQ) {
            String model = sp.getString(pref(form, "transcription_groq_model"), "whisper-large-v3-turbo");
            form.modelSpinner.setAdapter(groqModelAdapter);
            form.modelSpinner.setSelection(findModelPosition(
                    R.array.voicekb_transcription_models_groq_values, model, groqModelAdapter.getCount()));
            form.apiKeyEdit.setText(readProviderKey(form, provider));
        } else {
            form.customModelEdit.setText(sp.getString(pref(form, "transcription_custom_model"), ""));
            form.apiKeyEdit.setText(readProviderKey(form, provider));
        }

        form.additionalSwitch.setChecked(sp.getBoolean(additionalEnabledKey(form, provider), false));
        form.headersEdit.setText(sp.getString(additionalHeadersKey(form, provider), ""));
        form.additionalUrlEdit.setText(sp.getString(additionalUrlKey(form, provider), ""));
        form.loadedProvider = provider;
        ignoreChanges = false;
        updateAdditionalVisibility(form);
    }

    private void updateAdditionalVisibility(ApiForm form) {
        boolean customProvider = currentProvider(form) == TranscriptionApiConfig.PROVIDER_CUSTOM;
        form.additionalWrapper.setVisibility(customProvider || form.additionalSwitch.isChecked()
                ? View.VISIBLE : View.GONE);
    }

    private void checkToken(ApiForm form) {
        saveFormValuesForProvider(form, currentProvider(form));
        String headerError = TranscriptionApiConfig.validateHeaders(this,
                sp.getString(additionalHeadersKey(form, currentProvider(form)), ""));
        if (headerError != null) {
            showMessage(R.string.voicekb_api_check_failed_title, headerError);
            return;
        }

        TranscriptionApiConfig config;
        try {
            config = TranscriptionApiConfig.fromPreferences(this, sp, form.vpnConfig);
        } catch (IllegalArgumentException e) {
            showMessage(R.string.voicekb_api_check_failed_title,
                    getString(R.string.voicekb_api_config_invalid_headers, e.getMessage()));
            return;
        }
        String validationError = config.validate(this, true);
        if (validationError != null) {
            showMessage(R.string.voicekb_api_check_failed_title, validationError);
            return;
        }

        Button button = form.checkTokenButton;
        button.setEnabled(false);
        button.setText(R.string.voicekb_checking_api_token);
        new Thread(() -> {
            try {
                ApiTokenChecker.Result result = ApiTokenChecker.check(config);
                runOnUiThread(() -> {
                    button.setEnabled(true);
                    button.setText(R.string.voicekb_check_api_token);
                    if (result.success) {
                        showMessage(R.string.voicekb_api_check_success_title,
                                getString(R.string.voicekb_api_check_success_message, config.providerName));
                    } else {
                        showErrorWithDetails(config.providerName, result.statusCode, result.body);
                    }
                });
            } catch (IOException e) {
                runOnUiThread(() -> {
                    button.setEnabled(true);
                    button.setText(R.string.voicekb_check_api_token);
                    showErrorWithDetails(config.providerName, -1, e.getMessage());
                });
            }
        }).start();
    }

    private void showMessage(int titleRes, String message) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(titleRes)
                .setMessage(message)
                .setPositiveButton(R.string.voicekb_okay, null)
                .show();
    }

    private void showErrorWithDetails(String providerName, int statusCode, String details) {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (16 * getResources().getDisplayMetrics().density + 0.5f);
        content.setPadding(padding, 0, padding, 0);

        TextView message = new TextView(this);
        message.setText(statusCode > 0
                ? getString(R.string.voicekb_api_check_failed_message_with_code, providerName, statusCode)
                : getString(R.string.voicekb_api_check_failed_message, providerName));
        content.addView(message);

        TextView detailsView = new TextView(this);
        detailsView.setText(TextUtils.isEmpty(details) ? getString(R.string.voicekb_api_check_no_details) : details);
        detailsView.setVisibility(View.GONE);
        detailsView.setTextIsSelectable(true);
        content.addView(detailsView);

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.voicekb_api_check_failed_title)
                .setView(content)
                .setPositiveButton(R.string.voicekb_okay, null)
                .setNeutralButton(R.string.voicekb_show_details, null)
                .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
            detailsView.setVisibility(View.VISIBLE);
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setVisibility(View.GONE);
        }));
        dialog.show();
    }

    private void installEditTextAutoScroll(View view) {
        if (view instanceof EditText) {
            view.setOnFocusChangeListener((focusedView, hasFocus) -> {
                if (hasFocus) {
                    focusedView.postDelayed(() -> scrollFocusedEditTextIntoView(
                            findViewById(R.id.activity_api_settings)), 220);
                }
            });
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                installEditTextAutoScroll(group.getChildAt(i));
            }
        }
    }

    private void scrollFocusedEditTextIntoView(ScrollView scrollView) {
        if (scrollView == null) return;
        View focused = scrollView.findFocus();
        if (!(focused instanceof EditText)) return;

        Rect bounds = new Rect();
        focused.getDrawingRect(bounds);
        scrollView.offsetDescendantRectToMyCoords(focused, bounds);

        int margin = dp(24);
        int visibleBottom = scrollView.getScrollY() + scrollView.getHeight() - scrollView.getPaddingBottom() - margin;
        int visibleTop = scrollView.getScrollY() + scrollView.getPaddingTop() + margin;
        if (bounds.bottom > visibleBottom) {
            scrollView.smoothScrollTo(0, scrollView.getScrollY() + bounds.bottom - visibleBottom);
        } else if (bounds.top < visibleTop) {
            scrollView.smoothScrollTo(0, Math.max(0, scrollView.getScrollY() - (visibleTop - bounds.top)));
        }
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private String readProviderKey(ApiForm form, int provider) {
        String oldKey = sp.getString(pref(form, "transcription_api_key"),
                sp.getString(PREF + "api_key", ""));
        if (provider == TranscriptionApiConfig.PROVIDER_OPENAI) {
            return sp.getString(pref(form, "transcription_api_key_openai"), oldKey);
        } else if (provider == TranscriptionApiConfig.PROVIDER_GROQ) {
            return sp.getString(pref(form, "transcription_api_key_groq"), oldKey);
        }
        return sp.getString(pref(form, "transcription_api_key_custom"), oldKey);
    }

    private void saveFormValuesForProvider(ApiForm form, int provider) {
        SharedPreferences.Editor editor = sp.edit();
        String apiKey = form.apiKeyEdit.getText().toString();
        editor.putString(pref(form, "transcription_api_key"), apiKey);
        if (provider == TranscriptionApiConfig.PROVIDER_OPENAI) {
            editor.putString(pref(form, "transcription_api_key_openai"), apiKey);
            String model = selectedModelValue(R.array.voicekb_transcription_models_openai_values,
                    form.modelSpinner.getSelectedItemPosition());
            if (!TextUtils.isEmpty(model)) editor.putString(pref(form, "transcription_openai_model"), model);
        } else if (provider == TranscriptionApiConfig.PROVIDER_GROQ) {
            editor.putString(pref(form, "transcription_api_key_groq"), apiKey);
            String model = selectedModelValue(R.array.voicekb_transcription_models_groq_values,
                    form.modelSpinner.getSelectedItemPosition());
            if (!TextUtils.isEmpty(model)) editor.putString(pref(form, "transcription_groq_model"), model);
        } else {
            editor.putString(pref(form, "transcription_api_key_custom"), apiKey);
            editor.putString(pref(form, "transcription_custom_model"), form.customModelEdit.getText().toString());
        }
        editor.putBoolean(additionalEnabledKey(form, provider), form.additionalSwitch.isChecked());
        editor.putString(additionalUrlKey(form, provider), form.additionalUrlEdit.getText().toString());
        editor.putString(additionalHeadersKey(form, provider), form.headersEdit.getText().toString());
        editor.apply();
    }

    private String selectedModelValue(int valuesResource, int position) {
        String[] values = getResources().getStringArray(valuesResource);
        if (position < 0 || position >= values.length) return "";
        return values[position];
    }

    private int currentProvider(ApiForm form) {
        return safeProvider(form.providerSpinner.getSelectedItemPosition());
    }

    private int safeProvider(int provider) {
        return provider >= 0 && provider <= TranscriptionApiConfig.PROVIDER_CUSTOM
                ? provider : TranscriptionApiConfig.PROVIDER_GROQ;
    }

    private String pref(ApiForm form, String name) {
        return PREF + form.prefix + name;
    }

    private String additionalEnabledKey(ApiForm form, int provider) {
        return pref(form, "transcription_" + providerKey(provider) + "_additional_parameters_enabled");
    }

    private String additionalUrlKey(ApiForm form, int provider) {
        if (provider == TranscriptionApiConfig.PROVIDER_CUSTOM) {
            return pref(form, "transcription_custom_host");
        }
        return pref(form, "transcription_" + providerKey(provider) + "_additional_url");
    }

    private String additionalHeadersKey(ApiForm form, int provider) {
        return pref(form, "transcription_" + providerKey(provider) + "_additional_headers");
    }

    private String providerKey(int provider) {
        if (provider == TranscriptionApiConfig.PROVIDER_OPENAI) return "openai";
        if (provider == TranscriptionApiConfig.PROVIDER_CUSTOM) return "custom";
        return "groq";
    }

    private int providerSummary(int provider) {
        if (provider == TranscriptionApiConfig.PROVIDER_OPENAI) {
            return R.string.voicekb_openai_provider_summary;
        }
        if (provider == TranscriptionApiConfig.PROVIDER_CUSTOM) {
            return R.string.voicekb_custom_provider_summary;
        }
        return R.string.voicekb_groq_provider_summary;
    }

    private int apiKeyHint(int provider) {
        if (provider == TranscriptionApiConfig.PROVIDER_OPENAI) {
            return R.string.voicekb_openai_api_key_hint;
        }
        if (provider == TranscriptionApiConfig.PROVIDER_GROQ) {
            return R.string.voicekb_groq_api_key_hint;
        }
        return R.string.voicekb_custom_api_key_hint;
    }

    private int findModelPosition(int valuesResource, String model, int count) {
        String[] values = getResources().getStringArray(valuesResource);
        return IntStream.range(0, count)
                .filter(i -> values[i].equals(model))
                .findFirst()
                .orElse(0);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private static class ApiForm {
        final String prefix;
        final boolean vpnConfig;
        final Spinner providerSpinner;
        final TextView providerSummary;
        final TextView modelLabel;
        final Spinner modelSpinner;
        final EditText customModelEdit;
        final EditText apiKeyEdit;
        final Button checkTokenButton;
        final Switch additionalSwitch;
        final LinearLayout additionalWrapper;
        final EditText additionalUrlEdit;
        final EditText headersEdit;
        int loadedProvider = -1;

        ApiForm(String prefix, boolean vpnConfig, Spinner providerSpinner, TextView providerSummary, TextView modelLabel,
                Spinner modelSpinner, EditText customModelEdit, EditText apiKeyEdit,
                Button checkTokenButton, Switch additionalSwitch, LinearLayout additionalWrapper,
                EditText additionalUrlEdit, EditText headersEdit) {
            this.prefix = prefix;
            this.vpnConfig = vpnConfig;
            this.providerSpinner = providerSpinner;
            this.providerSummary = providerSummary;
            this.modelLabel = modelLabel;
            this.modelSpinner = modelSpinner;
            this.customModelEdit = customModelEdit;
            this.apiKeyEdit = apiKeyEdit;
            this.checkTokenButton = checkTokenButton;
            this.additionalSwitch = additionalSwitch;
            this.additionalWrapper = additionalWrapper;
            this.additionalUrlEdit = additionalUrlEdit;
            this.headersEdit = headersEdit;
        }
    }
}
