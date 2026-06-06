package com.idefant.voicekb.settings;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.widget.Toast;

import androidx.preference.EditTextPreference;
import androidx.preference.ListPreference;
import androidx.preference.MultiSelectListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.SwitchPreference;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import com.idefant.voicekb.BuildConfig;
import com.idefant.voicekb.VoiceKBUtils;
import com.idefant.voicekb.R;
import com.idefant.voicekb.usage.UsageActivity;
import com.idefant.voicekb.usage.UsageDatabaseHelper;

import java.io.File;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

public class PreferencesFragment extends PreferenceFragmentCompat {

    SharedPreferences sp;
    UsageDatabaseHelper usageDatabaseHelper;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        getPreferenceManager().setSharedPreferencesName("com.idefant.voicekb");
        setPreferencesFromResource(R.xml.fragment_preferences, null);
        sp = getPreferenceManager().getSharedPreferences();
        usageDatabaseHelper = new UsageDatabaseHelper(requireContext());

        MultiSelectListPreference inputLanguagesPreference = findPreference("com.idefant.voicekb.input_languages");
        if (inputLanguagesPreference != null) {
            inputLanguagesPreference.setSummaryProvider((Preference.SummaryProvider<MultiSelectListPreference>) preference -> {
                String[] selectedLanguagesValues = preference.getValues().toArray(new String[0]);
                return Arrays.stream(selectedLanguagesValues).map(VoiceKBUtils::translateLanguageToEmoji).collect(Collectors.joining(" "));
            });

            inputLanguagesPreference.setOnPreferenceChangeListener((preference, newValue) -> {
                Set<String> selectedLanguages = (Set<String>) newValue;
                if (selectedLanguages.isEmpty()) {
                    Toast.makeText(requireContext(), R.string.voicekb_input_languages_empty, Toast.LENGTH_SHORT).show();
                    return false;
                }
                sp.edit().putInt("com.idefant.voicekb.input_language_pos", 0).apply();
                return true;
            });
        }

        ListPreference appLanguagePreference = findPreference("com.idefant.voicekb.app_language");
        if (appLanguagePreference != null) {
            appLanguagePreference.setOnPreferenceChangeListener((preference, newValue) -> {
                VoiceKBUtils.applyApplicationLocale((String) newValue);
                requireActivity().recreate();
                return true;
            });
        }

        EditTextPreference overlayCharactersPreference = findPreference("com.idefant.voicekb.overlay_characters");
        if (overlayCharactersPreference != null) {
            overlayCharactersPreference.setSummaryProvider((Preference.SummaryProvider<EditTextPreference>) preference -> {
                String text = preference.getText();
                if (TextUtils.isEmpty(text)) {
                    return getString(R.string.voicekb_default_overlay_characters);
                }
                return text.chars().mapToObj(c -> String.valueOf((char) c)).collect(Collectors.joining(" "));
            });

            overlayCharactersPreference.setOnBindEditTextListener(editText -> {
                editText.setInputType(InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
                editText.setSingleLine(true);
                editText.setHint(R.string.voicekb_default_overlay_characters);
                editText.setFilters(new InputFilter[] {new InputFilter.LengthFilter(8)});
                editText.setSelection(editText.getText().length());
            });

            overlayCharactersPreference.setOnPreferenceChangeListener((preference, newValue) -> {
                String text = (String) newValue;
                if (text.isEmpty()) {
                    Toast.makeText(requireContext(), R.string.voicekb_overlay_characters_empty, Toast.LENGTH_SHORT).show();
                    return false;
                }
                return true;
            });
        }

        Preference usagePreference = findPreference("com.idefant.voicekb.usage");
        if (usagePreference != null) {
            usagePreference.setSummary(getString(R.string.voicekb_usage_total_cost, usageDatabaseHelper.getTotalCost()));

            usagePreference.setOnPreferenceClickListener(preference -> {
                Intent intent = new Intent(requireContext(), UsageActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
                return true;
            });
        }

        Preference apiSettingsPreference = findPreference("com.idefant.voicekb.api_settings");
        if (apiSettingsPreference != null) {
            apiSettingsPreference.setOnPreferenceClickListener(preference -> {
                Intent intent = new Intent(requireContext(), APISettingsActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
                return true;
            });
        }

        Preference smartInsertionPreference = findPreference("com.idefant.voicekb.smart_insertion");
        if (smartInsertionPreference != null) {
            smartInsertionPreference.setOnPreferenceClickListener(preference -> {
                Intent intent = new Intent(requireContext(), SmartInsertionSettingsActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
                return true;
            });
        }

        Preference promptPreference = findPreference("com.idefant.voicekb.prompts");
        if (promptPreference != null) {
            promptPreference.setOnPreferenceClickListener(preference -> {
                Intent intent = new Intent(requireContext(), SystemPromptsActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
                return true;
            });
        }

        EditTextPreference proxyHostPreference = findPreference("com.idefant.voicekb.proxy_host");
        if (proxyHostPreference != null) {
            proxyHostPreference.setSummaryProvider((Preference.SummaryProvider<EditTextPreference>) preference -> {
                String host = preference.getText();
                if (TextUtils.isEmpty(host)) return getString(R.string.voicekb_settings_proxy_hint);
                return host;
            });

            proxyHostPreference.setOnBindEditTextListener(editText -> {
                editText.setInputType(InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | InputType.TYPE_TEXT_VARIATION_URI);
                editText.setSingleLine(true);
                editText.setHint(R.string.voicekb_settings_proxy_hint);
            });

            proxyHostPreference.setOnPreferenceChangeListener((preference, newValue) -> {
                String host = (String) newValue;
                if (VoiceKBUtils.isValidProxy(host)) return true;
                else {
                    new MaterialAlertDialogBuilder(requireContext())
                            .setTitle(R.string.voicekb_proxy_invalid_title)
                            .setMessage(R.string.voicekb_proxy_invalid_message)
                            .setPositiveButton(R.string.voicekb_okay, null)
                            .show();
                    return false;
                }
            });
        }

        Preference cachePreference = findPreference("com.idefant.voicekb.cache");
        File[] cacheFiles = requireContext().getCacheDir().listFiles();
        if (cachePreference != null) {
            if (cacheFiles != null) {
                long cacheSize = Arrays.stream(cacheFiles).mapToLong(File::length).sum();
                cachePreference.setTitle(getString(R.string.voicekb_settings_cache, cacheFiles.length, cacheSize / 1024f / 1024f));
            }

            cachePreference.setOnPreferenceClickListener(preference -> {
                new MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.voicekb_cache_clear_title)
                        .setMessage(R.string.voicekb_cache_clear_message)
                        .setPositiveButton(R.string.voicekb_yes, (dialog, which) -> {
                            if (cacheFiles != null) {
                                for (File file : cacheFiles) {
                                    file.delete();
                                }
                            }
                            cachePreference.setTitle(getString(R.string.voicekb_settings_cache, 0, 0f));
                            Toast.makeText(requireContext(), R.string.voicekb_cache_cleared, Toast.LENGTH_SHORT).show();
                        })
                        .setNegativeButton(R.string.voicekb_no, null)
                        .show();
                return true;
            });
        }

        Preference githubPreference = findPreference("com.idefant.voicekb.github");
        if (githubPreference != null) {
            githubPreference.setOnPreferenceClickListener(preference -> {
                Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/idefant/VoiceKB"));
                startActivity(browserIntent);
                return true;
            });
        }

        Preference aboutPreference = findPreference("com.idefant.voicekb.about");
        if (aboutPreference != null) {
            aboutPreference.setTitle(getString(R.string.voicekb_about, BuildConfig.VERSION_NAME));
        }
    }
}
