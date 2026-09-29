package com.renzmc.rencam.gallery;

import android.Manifest;
import android.content.ContentUris;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.GridView;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.renzmc.rencam.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * RenCam in-app gallery. Shows a grid of recent photos and videos (loaded from MediaStore) with a
 * "LIVE" badge for Live Photos and a play overlay for Live Photos and videos. Tapping a cell opens
 * {@link LivePhotoViewerActivity}.
 *
 * @author RenzMc
 */
public class GalleryActivity extends AppCompatActivity {
    private static final String TAG = "GalleryActivity";
    private static final int REQUEST_PERMISSION = 2001;
    private static final int MAX_ITEMS = 300;

    /** Shared with {@link LivePhotoViewerActivity} (same process). */
    static List<GalleryItem> currentItems = new ArrayList<>();

    private GridView grid;
    private TextView emptyView;
    private GalleryAdapter adapter;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_gallery);

        grid = findViewById(R.id.gallery_grid);
        emptyView = findViewById(R.id.gallery_empty);

        ImageButton close = findViewById(R.id.gallery_close_button);
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        ImageButton systemButton = findViewById(R.id.gallery_system_button);
        systemButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openSystemGallery();
            }
        });

        adapter = new GalleryAdapter(this, currentItems);
        grid.setAdapter(adapter);
        grid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                Intent intent = new Intent(GalleryActivity.this, LivePhotoViewerActivity.class);
                intent.putExtra(LivePhotoViewerActivity.EXTRA_POSITION, position);
                startActivity(intent);
            }
        });

        if (hasPermission()) {
            loadMedia();
        } else {
            requestPermission();
        }
    }

    private boolean hasPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            return ContextCompat.checkSelfPermission(this, "android.permission.READ_MEDIA_IMAGES") == PackageManager.PERMISSION_GRANTED;
        }
        return ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            ActivityCompat.requestPermissions(this,
                    new String[]{"android.permission.READ_MEDIA_IMAGES", "android.permission.READ_MEDIA_VIDEO"},
                    REQUEST_PERMISSION);
        } else {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.READ_EXTERNAL_STORAGE},
                    REQUEST_PERMISSION);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSION) {
            loadMedia();
        }
    }

    private void loadMedia() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<GalleryItem> loaded = queryMedia();
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        currentItems.clear();
                        currentItems.addAll(loaded);
                        adapter.notifyDataSetChanged();
                        if (loaded.isEmpty()) {
                            emptyView.setVisibility(View.VISIBLE);
                            grid.setVisibility(View.GONE);
                        } else {
                            emptyView.setVisibility(View.GONE);
                            grid.setVisibility(View.VISIBLE);
                        }
                    }
                });
            }
        }, "GalleryLoader").start();
    }

    private List<GalleryItem> queryMedia() {
        List<GalleryItem> list = new ArrayList<>();
        queryCollection(list, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, false);
        queryCollection(list, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true);
        Collections.sort(list, new Comparator<GalleryItem>() {
            @Override
            public int compare(GalleryItem a, GalleryItem b) {
                return Long.compare(b.date, a.date);
            }
        });
        if (list.size() > MAX_ITEMS) {
            list = new ArrayList<>(list.subList(0, MAX_ITEMS));
        }
        return list;
    }

    private void queryCollection(List<GalleryItem> out, Uri collection, boolean isVideo) {
        String[] projection = {
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DATE_ADDED,
                MediaStore.MediaColumns.DISPLAY_NAME
        };
        Cursor cursor = null;
        try {
            cursor = getContentResolver().query(collection, projection, null, null,
                    MediaStore.MediaColumns.DATE_ADDED + " DESC");
            if (cursor != null) {
                int idCol = cursor.getColumnIndex(MediaStore.MediaColumns._ID);
                int dateCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_ADDED);
                int nameCol = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME);
                while (cursor.moveToNext()) {
                    long id = idCol >= 0 ? cursor.getLong(idCol) : -1;
                    long date = dateCol >= 0 ? cursor.getLong(dateCol) * 1000L : 0L;
                    String name = nameCol >= 0 ? cursor.getString(nameCol) : null;
                    Uri uri = ContentUris.withAppendedId(collection, id);
                    out.add(new GalleryItem(uri, date, isVideo, name));
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "query failed for " + collection, e);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    private void openSystemGallery() {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
            intent.setType("image/*");
            startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "failed to open system gallery", e);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (adapter != null) {
            adapter.shutdown();
        }
    }
}
