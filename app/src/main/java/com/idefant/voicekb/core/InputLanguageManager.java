package com.idefant.voicekb.core;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import com.idefant.voicekb.R;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class InputLanguageManager {

    public static final String PREF_INPUT_LANGUAGE_VALUE = "com.idefant.voicekb.input_language_value";
    public static final String PREF_INPUT_LANGUAGE_POS = "com.idefant.voicekb.input_language_pos";
    private static final String PREF_INPUT_LANGUAGES = "com.idefant.voicekb.input_languages";

    private InputLanguageManager() {}

    public static List<String> getAvailableLanguages(Context context, SharedPreferences sp) {
        List<String> supported = Arrays.asList(
                context.getResources().getStringArray(R.array.voicekb_input_languages_values));
        LinkedHashSet<String> defaults = new LinkedHashSet<>(Arrays.asList(
                context.getResources().getStringArray(R.array.voicekb_default_input_languages)));
        Set<String> stored = sp.getStringSet(PREF_INPUT_LANGUAGES, defaults);
        LinkedHashSet<String> sanitized = new LinkedHashSet<>();
        sanitized.add("detect");
        for (String language : stored) {
            if (supported.contains(language)) sanitized.add(language);
        }
        if (sanitized.size() == 1 && !stored.contains("detect")) sanitized.addAll(defaults);
        return new ArrayList<>(sanitized);
    }

    public static String ensureSelectedLanguage(Context context, SharedPreferences sp, int migratedPosition) {
        List<String> languages = getAvailableLanguages(context, sp);
        String stored = sp.getString(PREF_INPUT_LANGUAGE_VALUE, "");
        if (!languages.contains(stored)) {
            int position = Math.max(0, Math.min(migratedPosition, languages.size() - 1));
            stored = languages.get(position);
        }
        return selectLanguage(context, sp, stored);
    }

    public static String selectLanguage(Context context, SharedPreferences sp, String language) {
        List<String> languages = getAvailableLanguages(context, sp);
        int position = languages.indexOf(language);
        if (position < 0) position = 0;
        String selected = languages.get(position);
        sp.edit()
                .putInt(PREF_INPUT_LANGUAGE_POS, position)
                .putString(PREF_INPUT_LANGUAGE_VALUE, selected)
                .apply();
        return selected;
    }

    public static String getShortLabel(String language) {
        if (TextUtils.isEmpty(language) || "detect".equalsIgnoreCase(language)) return "DETECT";
        String normalized = language.toUpperCase(Locale.ROOT);
        return normalized.substring(0, Math.min(2, normalized.length()));
    }

    public static String getDisplayName(Context context, String language) {
        List<String> values = Arrays.asList(
                context.getResources().getStringArray(R.array.voicekb_input_languages_values));
        String[] names = context.getResources().getStringArray(R.array.voicekb_input_languages);
        int index = values.indexOf(language);
        return index >= 0 && index < names.length ? names[index] : getShortLabel(language);
    }

    public static String normalizeRecognizerLanguage(String language) {
        if (TextUtils.isEmpty(language)) return "detect";
        return language.toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
