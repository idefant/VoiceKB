package com.idefant.voicekb.onboarding;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.text.Editable;
import android.text.Html;
import android.text.TextWatcher;
import android.text.method.LinkMovementMethod;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import com.idefant.voicekb.VoiceKBUtils;
import com.idefant.voicekb.R;
import com.idefant.voicekb.settings.VoiceKBSettingsActivity;

import java.util.List;


public class OnboardingAdapter extends RecyclerView.Adapter<OnboardingAdapter.ViewHolder> {

    Activity activity;
    int[] layoutIds;

    public OnboardingAdapter(Activity activity, int[] layoutIds) {
        this.activity = activity;
        this.layoutIds = layoutIds;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(layoutIds[viewType], parent, false);

        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        int adapterPosition = holder.getBindingAdapterPosition();
        if (adapterPosition == RecyclerView.NO_POSITION) {
            return;
        }

        if (adapterPosition == 1) {
            Spinner languageSpinner = holder.itemView.findViewById(R.id.onboarding_language_spn);
            ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(
                    activity, R.array.voicekb_app_languages, android.R.layout.simple_spinner_item);
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            languageSpinner.setAdapter(adapter);
            SharedPreferences sp = activity.getSharedPreferences("com.idefant.voicekb", Context.MODE_PRIVATE);
            String currentLanguage = sp.getString("com.idefant.voicekb.app_language", "system");
            String[] values = activity.getResources().getStringArray(R.array.voicekb_app_languages_values);
            int selected = 0;
            for (int i = 0; i < values.length; i++) {
                if (values[i].equals(currentLanguage)) {
                    selected = i;
                    break;
                }
            }
            languageSpinner.setSelection(selected);
            languageSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                boolean initialized;

                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    if (!initialized) {
                        initialized = true;
                        return;
                    }
                    String language = values[position];
                    sp.edit().putString("com.idefant.voicekb.app_language", language).apply();
                    VoiceKBUtils.applyApplicationLocale(language);
                    activity.recreate();
                }

                @Override
                public void onNothingSelected(AdapterView<?> parent) {}
            });
        } else if (adapterPosition == 2) {
            TextView microphoneStatusTv = holder.itemView.findViewById(R.id.onboarding_permissions_microphone_status_tv);
            TextView keyboardStatusTv = holder.itemView.findViewById(R.id.onboarding_permissions_keyboard_status_tv);
            Button microphoneBtn = holder.itemView.findViewById(R.id.onboarding_permissions_microphone_btn);
            Button keyboardBtn = holder.itemView.findViewById(R.id.onboarding_permissions_keyboard_btn);

            if (activity.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                microphoneStatusTv.setText(activity.getString(R.string.voicekb_microphone_permission_granted));
                microphoneBtn.setEnabled(false);
            }

            microphoneBtn.setOnClickListener(v -> activity.requestPermissions(new String[]{ android.Manifest.permission.RECORD_AUDIO }, 1337));

            List<InputMethodInfo> inputMethodsList = ((InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE)).getEnabledInputMethodList();
            for (InputMethodInfo inputMethod : inputMethodsList) {
                if (inputMethod.getPackageName().equals(activity.getPackageName())) {
                    keyboardStatusTv.setText(activity.getString(R.string.voicekb_keyboard_enabled));
                    keyboardBtn.setEnabled(false);
                }
            }

            keyboardBtn.setOnClickListener(v -> activity.startActivity(new Intent(android.provider.Settings.ACTION_INPUT_METHOD_SETTINGS)));
        } else if (adapterPosition == 3) {
            Spinner providerSpinner = holder.itemView.findViewById(R.id.onboarding_api_provider_spn);
            TextView requestApiKeyTv = holder.itemView.findViewById(R.id.onboarding_api_key_request_tv);
            EditText apiKeyEt = holder.itemView.findViewById(R.id.onboarding_api_key_et);
            Button finishBtn = holder.itemView.findViewById(R.id.onboarding_api_key_finish_btn);
            Button skipBtn = holder.itemView.findViewById(R.id.onboarding_api_key_skip_btn);

            requestApiKeyTv.setMovementMethod(LinkMovementMethod.getInstance());

            ArrayAdapter<String> providerAdapter = new ArrayAdapter<>(activity,
                    android.R.layout.simple_spinner_item, new String[]{"Groq", "OpenAI"});
            providerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            providerSpinner.setAdapter(providerAdapter);
            providerSpinner.setSelection(0);
            updateProviderInstruction(requestApiKeyTv, apiKeyEt, finishBtn, 1);
            providerSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    updateProviderInstruction(requestApiKeyTv, apiKeyEt, finishBtn, position == 0 ? 1 : 0);
                }

                @Override
                public void onNothingSelected(AdapterView<?> parent) {}
            });

            apiKeyEt.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) { }

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                    int provider = providerSpinner.getSelectedItemPosition() == 0 ? 1 : 0;
                    finishBtn.setEnabled(isTokenValidForProvider(s.toString(), provider));
                }

                @Override
                public void afterTextChanged(Editable s) { }
            });

            finishBtn.setOnClickListener(v -> new MaterialAlertDialogBuilder(activity)
                    .setTitle(R.string.voicekb_onboarding_complete_dialog_title)
                    .setMessage(R.string.voicekb_onboarding_complete_dialog_message)
                    .setPositiveButton(R.string.voicekb_okay, (dialog, which) -> {
                        SharedPreferences sp = activity.getSharedPreferences("com.idefant.voicekb", Context.MODE_PRIVATE);
                        int provider = providerSpinner.getSelectedItemPosition() == 0 ? 1 : 0;
                        String key = apiKeyEt.getText().toString();
                        SharedPreferences.Editor editor = sp.edit()
                                .putInt("com.idefant.voicekb.transcription_provider", provider)
                                .putString("com.idefant.voicekb.transcription_api_key", key)
                                .putBoolean("com.idefant.voicekb.onboarding_complete", true);
                        if (provider == 1) {
                            editor.putString("com.idefant.voicekb.transcription_api_key_groq", key);
                        } else {
                            editor.putString("com.idefant.voicekb.transcription_api_key_openai", key);
                        }
                        editor.apply();
                        activity.startActivity(new Intent(activity, VoiceKBSettingsActivity.class));
                        activity.finish();
                    })
                    .show());

            skipBtn.setOnClickListener(v -> new MaterialAlertDialogBuilder(activity)
                    .setTitle(R.string.voicekb_onboarding_complete_dialog_title)
                    .setMessage(R.string.voicekb_onboarding_skip_api_message)
                    .setPositiveButton(R.string.voicekb_okay, (dialog, which) -> {
                        SharedPreferences sp = activity.getSharedPreferences("com.idefant.voicekb", Context.MODE_PRIVATE);
                        sp.edit()
                                .putInt("com.idefant.voicekb.transcription_provider",
                                        providerSpinner.getSelectedItemPosition() == 0 ? 1 : 0)
                                .putBoolean("com.idefant.voicekb.onboarding_complete", true)
                                .apply();
                        activity.startActivity(new Intent(activity, VoiceKBSettingsActivity.class));
                        activity.finish();
                    })
                    .show());
        }
    }

    private void updateProviderInstruction(TextView instructionTv, EditText apiKeyEt, Button finishBtn, int provider) {
        instructionTv.setText(Html.fromHtml(activity.getString(provider == 1
                ? R.string.voicekb_onboarding_groq_instruction_html
                : R.string.voicekb_onboarding_openai_instruction_html), Html.FROM_HTML_MODE_LEGACY));
        apiKeyEt.setHint(provider == 1
                ? R.string.voicekb_groq_api_key_hint
                : R.string.voicekb_openai_api_key_hint);
        apiKeyEt.setText("");
        finishBtn.setEnabled(false);
    }

    private boolean isTokenValidForProvider(String token, int provider) {
        return provider == 1 ? token.startsWith("gsk_") : token.startsWith("sk-");
    }

    @Override
    public int getItemCount() {
        return layoutIds.length;
    }

    @Override
    public int getItemViewType(int position) {
        return position;
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        public ViewHolder(@NonNull View itemView) {
            super(itemView);
        }
    }

}
