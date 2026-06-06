package com.idefant.voicekb.settings;

import android.os.Bundle;
import android.text.InputType;

import androidx.preference.EditTextPreference;
import androidx.preference.PreferenceFragmentCompat;

import com.idefant.voicekb.R;

public class SmartInsertionSettingsFragment extends PreferenceFragmentCompat {

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        getPreferenceManager().setSharedPreferencesName("com.idefant.voicekb");
        setPreferencesFromResource(R.xml.fragment_smart_insertion_settings, rootKey);

        EditTextPreference blacklistPreference = findPreference("com.idefant.voicekb.smart_insertion_blacklist");
        if (blacklistPreference != null) {
            blacklistPreference.setOnBindEditTextListener(editText -> {
                editText.setInputType(InputType.TYPE_CLASS_TEXT
                        | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                        | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
                editText.setSingleLine(false);
                editText.setMinLines(5);
                editText.setGravity(android.view.Gravity.TOP);
            });
        }
    }
}
