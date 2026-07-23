package com.idefant.voicekb.history;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.idefant.voicekb.R;
import com.idefant.voicekb.data.VoiceKBDatabaseHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class HistoryActivity extends AppCompatActivity implements HistoryAdapter.Listener {

    private VoiceKBDatabaseHelper db;
    private HistoryAdapter adapter;
    private View emptyView;

    private final List<HistoryModel> allEntries = new ArrayList<>();
    private String query = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_history);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.activity_history), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
            actionBar.setTitle(R.string.voicekb_history_title);
        }

        SharedPreferences sp = getSharedPreferences("com.idefant.voicekb", MODE_PRIVATE);

        db = new VoiceKBDatabaseHelper(this);
        db.pruneHistoryOlderThan(System.currentTimeMillis() - VoiceKBDatabaseHelper.RETENTION_MS);
        allEntries.addAll(db.getAllHistory());

        emptyView = findViewById(R.id.history_empty);

        RecyclerView recyclerView = findViewById(R.id.history_rv);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new HistoryAdapter(this, db, sp, this);
        recyclerView.setAdapter(adapter);

        EditText search = findViewById(R.id.history_search);
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                query = s.toString().trim();
                applyFilter();
            }
        });

        applyFilter();
    }

    private void applyFilter() {
        List<HistoryModel> filtered = new ArrayList<>();
        String needle = query.toLowerCase(Locale.getDefault());
        for (HistoryModel m : allEntries) {
            String transcript = m.getTranscript() == null ? "" : m.getTranscript();
            if (needle.isEmpty() || transcript.toLowerCase(Locale.getDefault()).contains(needle)) {
                filtered.add(m);
            }
        }
        adapter.submit(group(filtered));
        emptyView.setVisibility(filtered.isEmpty() ? View.VISIBLE : View.GONE);
    }

    /** Flattens entries into [header, item, item, header, item, ...] grouped by day. */
    private List<Object> group(List<HistoryModel> entries) {
        List<Object> rows = new ArrayList<>();
        String currentHeader = null;
        for (HistoryModel m : entries) {
            String header = HistoryFormat.dayHeader(this, m.getCreatedAt());
            if (!header.equals(currentHeader)) {
                rows.add(header);
                currentHeader = header;
            }
            rows.add(m);
        }
        return rows;
    }

    @Override
    public void onEntryDeleted(HistoryModel entry) {
        removeById(entry.getId());
        applyFilter();
    }

    @Override
    public void onEntryReprocessed(HistoryModel updated) {
        for (int i = 0; i < allEntries.size(); i++) {
            if (allEntries.get(i).getId() == updated.getId()) {
                allEntries.set(i, updated);
                break;
            }
        }
        applyFilter();
    }

    private void removeById(long id) {
        for (int i = 0; i < allEntries.size(); i++) {
            if (allEntries.get(i).getId() == id) {
                allEntries.remove(i);
                return;
            }
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (adapter != null) adapter.stopPlayback();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (adapter != null) adapter.release();
        if (db != null) db.close();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
