package com.idefant.voicekb.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.text.TextUtils;

import androidx.annotation.Nullable;

import com.idefant.voicekb.VoiceKBUtils;
import com.idefant.voicekb.history.HistoryModel;
import com.idefant.voicekb.usage.UsageModel;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * Single app database. Owns every table (usage statistics and recognition history).
 * The schema version bumps additively as tables are introduced.
 */
public class VoiceKBDatabaseHelper extends SQLiteOpenHelper {

    // Recognized recordings are kept for this long, then pruned together with their audio files.
    public static final long RETENTION_MS = 90L * 24 * 60 * 60 * 1000;

    private static final String DB_NAME = "voicekb.db";
    private static final String LEGACY_DB_NAME = "usage.db";

    private static final String USAGE = "USAGE";
    private static final String HISTORY = "HISTORY";
    private static final String AUDIO_DIR_NAME = "history";

    private static boolean legacyMigrationChecked = false;

    private final Context context;

    public VoiceKBDatabaseHelper(@Nullable Context context) {
        super(context, DB_NAME, null, 4);
        this.context = context;
        migrateLegacyDatabaseIfNeeded(context);
    }

    /**
     * The database was originally named {@code usage.db} (statistics only). Rename it in place the
     * first time the neutral name is used, so existing statistics are preserved. Idempotent: once
     * the new file exists, or the old one is gone, this does nothing.
     */
    private static synchronized void migrateLegacyDatabaseIfNeeded(Context context) {
        if (legacyMigrationChecked || context == null) return;
        legacyMigrationChecked = true;

        File newDb = context.getDatabasePath(DB_NAME);
        if (newDb.exists()) return;
        File oldDb = context.getDatabasePath(LEGACY_DB_NAME);
        if (!oldDb.exists()) return;

        for (String suffix : new String[]{"", "-journal", "-wal", "-shm"}) {
            File from = new File(oldDb.getPath() + suffix);
            if (from.exists()) {
                //noinspection ResultOfMethodCallIgnored
                from.renameTo(new File(newDb.getPath() + suffix));
            }
        }
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + USAGE + " ("
                + "MODEL_NAME TEXT PRIMARY KEY, AUDIO_TIME LONG, INPUT_TOKENS LONG, "
                + "OUTPUT_TOKENS LONG, MODEL_PROVIDER LONG)");
        createHistoryTable(db);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion <= 1) {
            db.execSQL("ALTER TABLE " + USAGE + " ADD COLUMN MODEL_PROVIDER LONG DEFAULT 0");
        }
        if (oldVersion <= 2) {
            // Original history schema (v3); the error columns are added by the v3 -> v4 step below.
            db.execSQL("CREATE TABLE " + HISTORY + " ("
                    + "ID INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "CREATED_AT LONG, TRANSCRIPT TEXT, AUDIO_FILE TEXT, "
                    + "DURATION LONG, PROVIDER TEXT, MODEL TEXT)");
        }
        if (oldVersion <= 3) {
            db.execSQL("ALTER TABLE " + HISTORY + " ADD COLUMN ERROR_MESSAGE TEXT");
            db.execSQL("ALTER TABLE " + HISTORY + " ADD COLUMN ERROR_DETAILS TEXT");
        }
    }

    private void createHistoryTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + HISTORY + " ("
                + "ID INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "CREATED_AT LONG, "
                + "TRANSCRIPT TEXT, "
                + "AUDIO_FILE TEXT, "
                + "DURATION LONG, "
                + "PROVIDER TEXT, "
                + "MODEL TEXT, "
                + "ERROR_MESSAGE TEXT, "
                + "ERROR_DETAILS TEXT)");
    }

    // --- Usage ---

    public void edit(String model, long timeToAdd, long inputTokensToAdd, long outputTokensToAdd, long provider) {
        SQLiteDatabase db = this.getWritableDatabase();
        Cursor cursor = db.rawQuery("SELECT * FROM " + USAGE + " WHERE MODEL_NAME='" + model + "'", null);

        boolean entryExists = cursor.moveToFirst();
        cursor.close();

        if (!entryExists) {
            ContentValues cv = new ContentValues();
            cv.put("MODEL_NAME", model);
            cv.put("AUDIO_TIME", timeToAdd);
            cv.put("INPUT_TOKENS", inputTokensToAdd);
            cv.put("OUTPUT_TOKENS", outputTokensToAdd);
            cv.put("MODEL_PROVIDER", provider);
            db.insert(USAGE, null, cv);
        } else {
            cursor = db.rawQuery("SELECT * FROM " + USAGE + " WHERE MODEL_NAME='" + model + "'", null);
            if (cursor.moveToFirst()) {
                ContentValues cv = new ContentValues();
                cv.put("AUDIO_TIME", cursor.getLong(1) + timeToAdd);
                cv.put("INPUT_TOKENS", cursor.getLong(2) + inputTokensToAdd);
                cv.put("OUTPUT_TOKENS", cursor.getLong(3) + outputTokensToAdd);
                db.update(USAGE, cv, "MODEL_NAME='" + model + "'", null);
            }
            cursor.close();
        }

        db.close();
    }

    public void reset() {
        SQLiteDatabase db = this.getWritableDatabase();
        db.execSQL("DELETE FROM " + USAGE);
        db.close();
    }

    public List<UsageModel> getAllUsage() {
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor cursor = db.rawQuery("SELECT * FROM " + USAGE, null);

        List<UsageModel> models = new ArrayList<>();
        if (cursor.moveToFirst()) {
            do {
                models.add(new UsageModel(cursor.getString(0), cursor.getLong(1), cursor.getLong(2), cursor.getLong(3), cursor.getLong(4)));
            } while (cursor.moveToNext());
        }
        cursor.close();
        db.close();
        return models;
    }

    public double getCost(String modelName) {
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor cursor = db.rawQuery("SELECT * FROM " + USAGE + " WHERE MODEL_NAME='" + modelName + "'", null);

        double cost = 0;
        if (cursor.moveToFirst()) {
            cost = VoiceKBUtils.calcModelCost(cursor.getString(0), cursor.getLong(1), cursor.getLong(2), cursor.getLong(3));
        }
        cursor.close();
        return cost;
    }

    public double getTotalCost() {
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor cursor = db.rawQuery("SELECT * FROM " + USAGE, null);

        double totalCost = 0;
        if (cursor.moveToFirst()) {
            do {
                totalCost += VoiceKBUtils.calcModelCost(cursor.getString(0), cursor.getLong(1), cursor.getLong(2), cursor.getLong(3));
            } while (cursor.moveToNext());
        }
        cursor.close();
        return totalCost;
    }

    public long getTotalAudioTime() {
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor cursor = db.rawQuery("SELECT * FROM " + USAGE, null);

        long totalAudioTime = 0;
        if (cursor.moveToFirst()) {
            do {
                totalAudioTime += cursor.getLong(1);
            } while (cursor.moveToNext());
        }
        cursor.close();
        return totalAudioTime;
    }

    // --- History ---

    /** Durable directory for history audio files (survives cache eviction). */
    public static File getAudioDir(Context context) {
        File dir = new File(context.getFilesDir(), AUDIO_DIR_NAME);
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    public static File getAudioFile(Context context, String fileName) {
        if (TextUtils.isEmpty(fileName)) return null;
        return new File(getAudioDir(context), fileName);
    }

    /**
     * Inserts an entry. For a successful recognition pass {@code transcript} and leave the error
     * fields null; for a failure leave {@code transcript} null and pass {@code errorMessage}
     * (short, shown) and {@code errorDetails} (raw provider body, may be null). The audio file is
     * referenced by name and never copied — several entries may share one file (e.g. after resend).
     */
    public long insertHistory(long createdAt, String transcript, String errorMessage, String errorDetails,
                              String audioFileName, long durationSec, String provider, String model) {
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("CREATED_AT", createdAt);
        cv.put("TRANSCRIPT", transcript);
        cv.put("ERROR_MESSAGE", errorMessage);
        cv.put("ERROR_DETAILS", errorDetails);
        cv.put("AUDIO_FILE", audioFileName);
        cv.put("DURATION", durationSec);
        cv.put("PROVIDER", provider);
        cv.put("MODEL", model);
        long id = db.insert(HISTORY, null, cv);
        db.close();
        return id;
    }

    public List<HistoryModel> getAllHistory() {
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor cursor = db.rawQuery(
                "SELECT ID, CREATED_AT, TRANSCRIPT, AUDIO_FILE, DURATION, PROVIDER, MODEL, ERROR_MESSAGE, ERROR_DETAILS "
                        + "FROM " + HISTORY + " ORDER BY CREATED_AT DESC, ID DESC", null);

        List<HistoryModel> models = new ArrayList<>();
        if (cursor.moveToFirst()) {
            do {
                models.add(new HistoryModel(
                        cursor.getLong(0),
                        cursor.getLong(1),
                        cursor.getString(2),
                        cursor.getString(3),
                        cursor.getLong(4),
                        cursor.getString(5),
                        cursor.getString(6),
                        cursor.getString(7),
                        cursor.getString(8)));
            } while (cursor.moveToNext());
        }
        cursor.close();
        db.close();
        return models;
    }

    /** Reprocess succeeded: store the recognized text and clear any previous error. */
    public void updateHistorySuccess(long id, String transcript, String provider, String model) {
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("TRANSCRIPT", transcript);
        cv.put("PROVIDER", provider);
        cv.put("MODEL", model);
        cv.putNull("ERROR_MESSAGE");
        cv.putNull("ERROR_DETAILS");
        db.update(HISTORY, cv, "ID=?", new String[]{String.valueOf(id)});
        db.close();
    }

    /** Reprocess failed: store the error and clear any previous transcript. */
    public void updateHistoryError(long id, String errorMessage, String errorDetails, String provider, String model) {
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.putNull("TRANSCRIPT");
        cv.put("ERROR_MESSAGE", errorMessage);
        cv.put("ERROR_DETAILS", errorDetails);
        cv.put("PROVIDER", provider);
        cv.put("MODEL", model);
        db.update(HISTORY, cv, "ID=?", new String[]{String.valueOf(id)});
        db.close();
    }

    /** Audio file name of the most recent entry, or null when history is empty. Used by resend. */
    public String getLatestHistoryAudioFileName() {
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor cursor = db.rawQuery(
                "SELECT AUDIO_FILE FROM " + HISTORY + " ORDER BY CREATED_AT DESC, ID DESC LIMIT 1", null);
        String fileName = null;
        if (cursor.moveToFirst()) {
            fileName = cursor.getString(0);
        }
        cursor.close();
        db.close();
        return TextUtils.isEmpty(fileName) ? null : fileName;
    }

    /** Deletes a single entry; removes its audio file only if no other entry still references it. */
    public void deleteHistory(long id) {
        SQLiteDatabase db = this.getWritableDatabase();
        Cursor cursor = db.rawQuery("SELECT AUDIO_FILE FROM " + HISTORY + " WHERE ID=?",
                new String[]{String.valueOf(id)});
        String fileName = cursor.moveToFirst() ? cursor.getString(0) : null;
        cursor.close();
        db.delete(HISTORY, "ID=?", new String[]{String.valueOf(id)});
        if (!TextUtils.isEmpty(fileName)) {
            Cursor ref = db.rawQuery("SELECT 1 FROM " + HISTORY + " WHERE AUDIO_FILE=? LIMIT 1",
                    new String[]{fileName});
            boolean stillReferenced = ref.moveToFirst();
            ref.close();
            if (!stillReferenced) deleteAudioFile(fileName);
        }
        db.close();
    }

    /** Removes entries older than {@code cutoff} (epoch ms), then sweeps orphaned audio files. */
    public void pruneHistoryOlderThan(long cutoff) {
        SQLiteDatabase db = this.getWritableDatabase();
        db.delete(HISTORY, "CREATED_AT < ?", new String[]{String.valueOf(cutoff)});
        db.close();
        deleteOrphanAudioFiles();
    }

    /**
     * Deletes audio files in the history directory not referenced by any entry (garbage collection
     * for files shared via resend once all their entries are gone). In-progress recordings live in
     * the cache, not here, so there is nothing unregistered to protect.
     */
    public void deleteOrphanAudioFiles() {
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor cursor = db.rawQuery("SELECT DISTINCT AUDIO_FILE FROM " + HISTORY, null);
        HashSet<String> referenced = new HashSet<>();
        if (cursor.moveToFirst()) {
            do {
                String name = cursor.getString(0);
                if (name != null) referenced.add(name);
            } while (cursor.moveToNext());
        }
        cursor.close();
        db.close();

        File[] files = getAudioDir(context).listFiles();
        if (files == null) return;
        for (File file : files) {
            if (referenced.contains(file.getName())) continue;
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    private void deleteAudioFile(String fileName) {
        File file = getAudioFile(context, fileName);
        if (file != null && file.exists()) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }
}
