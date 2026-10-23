package com.github.stjiege.ftpshare.activities;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import com.github.stjiege.ftpshare.R;
import com.github.stjiege.ftpshare.utils.LogManager;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;

/** Reliable in-app browser for the guaranteed app-private log directory. */
public class LogDirectoryActivity extends BaseActivity {
    private final ArrayList<File> files = new ArrayList<>();

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_log_directory);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        File directory = LogManager.getWritableLogDirectory(this);
        ((TextView) findViewById(R.id.log_directory_path)).setText(directory == null ? "" : directory.getAbsolutePath());
        if (directory != null) {
            File[] listed = directory.listFiles(File::isFile);
            if (listed != null) {
                Arrays.sort(listed, new Comparator<File>() {
                    @Override public int compare(File left, File right) {
                        return Long.compare(right.lastModified(), left.lastModified());
                    }
                });
                Collections.addAll(files, listed);
            }
        }
        findViewById(R.id.log_directory_empty).setVisibility(files.isEmpty() ? View.VISIBLE : View.GONE);
        ArrayList<String> names = new ArrayList<>();
        for (File file : files) names.add(file.getName());
        ListView list = findViewById(R.id.log_directory_list);
        list.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, names));
        list.setOnItemClickListener((parent, view, position, id) -> openFile(files.get(position)));
    }

    private void openFile(File file) {
        try {
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);
            Intent intent = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "text/plain");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, getString(R.string.log_directory_title)));
        } catch (Exception e) {
            Toast.makeText(this, R.string.log_file_open_failed, Toast.LENGTH_SHORT).show();
        }
    }

    @Override public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}

