package com.idefant.voicekb.history;

import android.content.Context;
import android.text.TextUtils;
import android.text.format.DateFormat;

import com.idefant.voicekb.R;

import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/** Formatting helpers for the history screen (locale- and 24h-aware). */
final class HistoryFormat {

    private HistoryFormat() {}

    /** "14:32 · 0:12 · Groq · whisper-large-v3-turbo" */
    static String meta(Context context, HistoryModel m) {
        StringBuilder sb = new StringBuilder();
        sb.append(DateFormat.getTimeFormat(context).format(new Date(m.getCreatedAt())));
        sb.append(" · ").append(duration(m.getDurationSec()));
        if (!TextUtils.isEmpty(m.getProvider())) sb.append(" · ").append(m.getProvider());
        if (!TextUtils.isEmpty(m.getModel())) sb.append(" · ").append(m.getModel());
        return sb.toString();
    }

    /** "0:03 / 0:12" */
    static String time(long positionSec, long totalSec) {
        return duration(positionSec) + " / " + duration(totalSec);
    }

    static String duration(long seconds) {
        if (seconds < 0) seconds = 0;
        return String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60);
    }

    /** Group header for an entry: Today, Yesterday, or a locale-formatted date. */
    static String dayHeader(Context context, long createdAt) {
        Calendar now = Calendar.getInstance();
        Calendar entry = Calendar.getInstance();
        entry.setTimeInMillis(createdAt);

        if (isSameDay(now, entry)) {
            return context.getString(R.string.voicekb_history_group_today);
        }
        now.add(Calendar.DAY_OF_YEAR, -1);
        if (isSameDay(now, entry)) {
            return context.getString(R.string.voicekb_history_group_yesterday);
        }
        return DateFormat.getMediumDateFormat(context).format(new Date(createdAt));
    }

    private static boolean isSameDay(Calendar a, Calendar b) {
        return a.get(Calendar.YEAR) == b.get(Calendar.YEAR)
                && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR);
    }
}
