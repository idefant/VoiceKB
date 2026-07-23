package com.idefant.voicekb.history;

import android.annotation.SuppressLint;
import android.content.SharedPreferences;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.idefant.voicekb.R;
import com.idefant.voicekb.core.AppError;
import com.idefant.voicekb.data.VoiceKBDatabaseHelper;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class HistoryAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    public interface Listener {
        void onEntryDeleted(HistoryModel entry);

        void onEntryReprocessed(HistoryModel updated);
    }

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_ITEM = 1;

    private final AppCompatActivity activity;
    private final VoiceKBDatabaseHelper db;
    private final SharedPreferences sp;
    private final Listener listener;

    // Rows are either a String (day header) or a HistoryModel (entry).
    private final List<Object> rows = new ArrayList<>();

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService reprocessExecutor = Executors.newSingleThreadExecutor();

    private MediaPlayer player;
    private long playingId = -1;
    private boolean isPlaying = false;
    private long playerDurationMs = 0;
    private ItemViewHolder playingHolder;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (player != null && isPlaying) {
                updateProgressViews();
                mainHandler.postDelayed(this, 250);
            }
        }
    };

    public HistoryAdapter(AppCompatActivity activity, VoiceKBDatabaseHelper db,
                          SharedPreferences sp, Listener listener) {
        this.activity = activity;
        this.db = db;
        this.sp = sp;
        this.listener = listener;
    }

    @SuppressLint("NotifyDataSetChanged")
    public void submit(List<Object> newRows) {
        rows.clear();
        rows.addAll(newRows);
        notifyDataSetChanged();
    }

    @Override
    public int getItemViewType(int position) {
        return rows.get(position) instanceof String ? TYPE_HEADER : TYPE_ITEM;
    }

    @Override
    public int getItemCount() {
        return rows.size();
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_HEADER) {
            return new HeaderViewHolder(inflater.inflate(R.layout.item_history_header, parent, false));
        }
        return new ItemViewHolder(inflater.inflate(R.layout.item_history, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Object row = rows.get(position);
        if (holder instanceof HeaderViewHolder) {
            ((HeaderViewHolder) holder).title.setText((String) row);
            return;
        }
        ItemViewHolder h = (ItemViewHolder) holder;
        HistoryModel m = (HistoryModel) row;
        h.model = m;
        h.meta.setText(HistoryFormat.meta(activity, m));

        if (m.isError()) {
            h.transcript.setText(m.getErrorMessage());
            h.transcript.setTextColor(ContextCompat.getColor(activity, R.color.voicekb_history_danger));
            boolean hasDetails = m.getErrorDetails() != null && !m.getErrorDetails().isEmpty();
            h.detailsToggle.setVisibility(hasDetails ? View.VISIBLE : View.GONE);
            h.detailsToggle.setText(R.string.voicekb_history_details_show);
            h.details.setText(hasDetails ? m.getErrorDetails() : "");
            h.details.setVisibility(View.GONE);
        } else {
            h.transcript.setText(m.getTranscript());
            h.transcript.setTextColor(ContextCompat.getColor(activity, R.color.voicekb_history_text_primary));
            h.detailsToggle.setVisibility(View.GONE);
            h.details.setVisibility(View.GONE);
        }

        boolean thisPlaying = m.getId() == playingId;
        if (thisPlaying) playingHolder = h;
        h.playIcon.setImageResource(thisPlaying && isPlaying
                ? R.drawable.ic_lucide_pause_24 : R.drawable.ic_lucide_play_24);
        bindProgress(h);
    }

    @Override
    public void onViewRecycled(@NonNull RecyclerView.ViewHolder holder) {
        if (holder == playingHolder) playingHolder = null;
    }

    private void bindProgress(ItemViewHolder h) {
        boolean thisPlaying = h.model != null && h.model.getId() == playingId;
        long posSec = 0;
        int progress = 0;
        if (thisPlaying && player != null && playerDurationMs > 0) {
            long posMs = safeCurrentPosition();
            posSec = posMs / 1000;
            progress = (int) (1000L * posMs / playerDurationMs);
        }
        h.seek.setProgress(thisPlaying ? progress : 0);
        h.time.setText(HistoryFormat.time(posSec, h.model != null ? h.model.getDurationSec() : -1));
    }

    private void updateProgressViews() {
        if (playingHolder != null && playingHolder.model != null
                && playingHolder.model.getId() == playingId) {
            bindProgress(playingHolder);
        }
    }

    private long safeCurrentPosition() {
        try {
            return player != null ? player.getCurrentPosition() : 0;
        } catch (IllegalStateException e) {
            return 0;
        }
    }

    // --- Playback ---

    private void togglePlayback(ItemViewHolder h) {
        HistoryModel m = h.model;
        if (m == null) return;

        if (m.getId() == playingId && player != null) {
            if (isPlaying) {
                player.pause();
                isPlaying = false;
            } else {
                player.start();
                isPlaying = true;
                mainHandler.post(ticker);
            }
            h.playIcon.setImageResource(isPlaying
                    ? R.drawable.ic_lucide_pause_24 : R.drawable.ic_lucide_play_24);
            return;
        }

        File audio = VoiceKBDatabaseHelper.getAudioFile(activity, m.getAudioFileName());
        if (audio == null || !audio.exists()) {
            Toast.makeText(activity, R.string.voicekb_history_audio_missing, Toast.LENGTH_SHORT).show();
            return;
        }

        long previousPlayingId = playingId;
        stopPlayback();
        if (previousPlayingId != -1) refreshRowFor(previousPlayingId);

        try {
            player = new MediaPlayer();
            player.setDataSource(audio.getAbsolutePath());
            player.prepare();
            playerDurationMs = player.getDuration();
            player.setOnCompletionListener(mp -> {
                isPlaying = false;
                try {
                    player.seekTo(0);
                } catch (IllegalStateException ignored) {
                }
                if (playingHolder != null) {
                    playingHolder.playIcon.setImageResource(R.drawable.ic_lucide_play_24);
                    bindProgress(playingHolder);
                }
            });
            player.start();
            playingId = m.getId();
            isPlaying = true;
            playingHolder = h;
            h.playIcon.setImageResource(R.drawable.ic_lucide_pause_24);
            mainHandler.post(ticker);
        } catch (Exception e) {
            releasePlayer();
            Toast.makeText(activity, R.string.voicekb_history_audio_missing, Toast.LENGTH_SHORT).show();
        }
    }

    private void refreshRowFor(long id) {
        for (int i = 0; i < rows.size(); i++) {
            Object row = rows.get(i);
            if (row instanceof HistoryModel && ((HistoryModel) row).getId() == id) {
                notifyItemChanged(i);
                return;
            }
        }
    }

    /** Stops and releases the player, keeping the current row's UI state consistent. */
    public void stopPlayback() {
        releasePlayer();
        playingId = -1;
        isPlaying = false;
        playerDurationMs = 0;
        playingHolder = null;
    }

    private void releasePlayer() {
        mainHandler.removeCallbacks(ticker);
        if (player != null) {
            try {
                player.release();
            } catch (Exception ignored) {
            }
            player = null;
        }
        isPlaying = false;
    }

    public void release() {
        releasePlayer();
        reprocessExecutor.shutdownNow();
    }

    // --- Actions ---

    private void copy(HistoryModel m) {
        android.content.ClipboardManager cm =
                (android.content.ClipboardManager) activity.getSystemService(AppCompatActivity.CLIPBOARD_SERVICE);
        if (cm != null) {
            String text = m.isError() ? m.getErrorMessage() : m.getTranscript();
            cm.setPrimaryClip(android.content.ClipData.newPlainText("VoiceKB", text));
            Toast.makeText(activity, R.string.voicekb_history_copied, Toast.LENGTH_SHORT).show();
        }
    }

    private void confirmDelete(HistoryModel m) {
        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.voicekb_history_delete_title)
                .setMessage(R.string.voicekb_history_delete_message)
                .setPositiveButton(R.string.voicekb_history_delete_confirm, (dialog, which) -> {
                    if (m.getId() == playingId) stopPlayback();
                    db.deleteHistory(m.getId());
                    listener.onEntryDeleted(m);
                })
                .setNegativeButton(R.string.voicekb_cancel, null)
                .show();
    }

    private void reprocess(ItemViewHolder h) {
        HistoryModel m = h.model;
        if (m == null) return;
        File audio = VoiceKBDatabaseHelper.getAudioFile(activity, m.getAudioFileName());
        if (audio == null || !audio.exists()) {
            Toast.makeText(activity, R.string.voicekb_history_audio_missing, Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(activity, R.string.voicekb_history_reprocessing, Toast.LENGTH_SHORT).show();
        reprocessExecutor.execute(() -> {
            try {
                HistoryReprocessor.Result result = HistoryReprocessor.transcribe(activity, sp, audio);
                db.updateHistorySuccess(m.getId(), result.text, result.providerName, result.model);
                db.edit(result.model, m.getDurationSec(), 0, 0, result.provider);
                HistoryModel updated = new HistoryModel(m.getId(), m.getCreatedAt(), result.text,
                        m.getAudioFileName(), m.getDurationSec(), result.providerName, result.model, null, null);
                mainHandler.post(() -> listener.onEntryReprocessed(updated));
            } catch (Exception e) {
                AppError error = AppError.fromTranscriptionError(activity, e);
                db.updateHistoryError(m.getId(), error.message, error.details, m.getProvider(), m.getModel());
                HistoryModel updated = new HistoryModel(m.getId(), m.getCreatedAt(), null,
                        m.getAudioFileName(), m.getDurationSec(), m.getProvider(), m.getModel(),
                        error.message, error.details);
                mainHandler.post(() -> {
                    listener.onEntryReprocessed(updated);
                    Toast.makeText(activity, R.string.voicekb_history_reprocess_failed, Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    // --- View holders ---

    static class HeaderViewHolder extends RecyclerView.ViewHolder {
        final TextView title;

        HeaderViewHolder(@NonNull View itemView) {
            super(itemView);
            title = itemView.findViewById(R.id.history_header);
        }
    }

    class ItemViewHolder extends RecyclerView.ViewHolder {
        final TextView transcript;
        final TextView meta;
        final TextView detailsToggle;
        final TextView details;
        final ImageView playIcon;
        final SeekBar seek;
        final TextView time;
        HistoryModel model;

        ItemViewHolder(@NonNull View itemView) {
            super(itemView);
            transcript = itemView.findViewById(R.id.history_transcript);
            meta = itemView.findViewById(R.id.history_meta);
            detailsToggle = itemView.findViewById(R.id.history_details_toggle);
            details = itemView.findViewById(R.id.history_details);
            playIcon = itemView.findViewById(R.id.history_play_icon);
            seek = itemView.findViewById(R.id.history_seek);
            time = itemView.findViewById(R.id.history_time);

            detailsToggle.setOnClickListener(v -> {
                boolean show = details.getVisibility() != View.VISIBLE;
                details.setVisibility(show ? View.VISIBLE : View.GONE);
                detailsToggle.setText(show
                        ? R.string.voicekb_history_details_hide : R.string.voicekb_history_details_show);
            });
            itemView.findViewById(R.id.history_play_button).setOnClickListener(v -> togglePlayback(this));
            itemView.findViewById(R.id.history_action_copy).setOnClickListener(v -> {
                if (model != null) copy(model);
            });
            itemView.findViewById(R.id.history_action_reprocess).setOnClickListener(v -> reprocess(this));
            itemView.findViewById(R.id.history_action_delete).setOnClickListener(v -> {
                if (model != null) confirmDelete(model);
            });

            seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                    if (fromUser && model != null && model.getId() == playingId
                            && player != null && playerDurationMs > 0) {
                        try {
                            player.seekTo((int) (progress / 1000f * playerDurationMs));
                        } catch (IllegalStateException ignored) {
                        }
                        time.setText(HistoryFormat.time(safeCurrentPosition() / 1000, model.getDurationSec()));
                    }
                }

                @Override
                public void onStartTrackingTouch(SeekBar sb) {
                }

                @Override
                public void onStopTrackingTouch(SeekBar sb) {
                }
            });
        }
    }
}
