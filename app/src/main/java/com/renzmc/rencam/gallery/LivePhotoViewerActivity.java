package com.renzmc.rencam.gallery;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.MediaController;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.VideoView;

import androidx.appcompat.app.AppCompatActivity;

import com.renzmc.rencam.R;
import com.renzmc.rencam.livephoto.LivePhotoHelper;

import java.io.File;
import java.io.InputStream;

/**
 * Full-screen viewer for the RenCam in-app gallery. For a Live Photo it shows the still and a play
 * button; pressing play extracts the embedded MP4 and plays it (the "Live" motion). For videos it
 * plays directly.
 *
 * @author RenzMc
 */
public class LivePhotoViewerActivity extends AppCompatActivity {
    private static final String TAG = "LivePhotoViewer";

    public static final String EXTRA_POSITION = "position";

    private ImageView imageView;
    private VideoView videoView;
    private ProgressBar progress;
    private ImageView playButton;
    private TextView liveBadge;
    private ImageButton prevButton;
    private ImageButton nextButton;

    private int position;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_live_photo_viewer);

        imageView = findViewById(R.id.viewer_image);
        videoView = findViewById(R.id.viewer_video);
        progress = findViewById(R.id.viewer_progress);
        playButton = findViewById(R.id.viewer_play);
        liveBadge = findViewById(R.id.viewer_live_badge);
        prevButton = findViewById(R.id.viewer_prev);
        nextButton = findViewById(R.id.viewer_next);

        position = getIntent().getIntExtra(EXTRA_POSITION, 0);

        findViewById(R.id.viewer_close).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        playButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                playLivePhoto();
            }
        });
        prevButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                move(-1);
            }
        });
        nextButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                move(1);
            }
        });

        show();
    }

    private GalleryItem currentItem() {
        if (position < 0 || position >= GalleryActivity.currentItems.size()) {
            return null;
        }
        return GalleryActivity.currentItems.get(position);
    }

    private void move(int delta) {
        int np = position + delta;
        if (np < 0 || np >= GalleryActivity.currentItems.size()) {
            return;
        }
        position = np;
        show();
    }

    private void show() {
        final GalleryItem item = currentItem();
        if (item == null) {
            finish();
            return;
        }
        stopPlayback();
        imageView.setVisibility(View.VISIBLE);
        imageView.setImageBitmap(null);
        progress.setVisibility(View.VISIBLE);
        playButton.setVisibility(View.GONE);
        liveBadge.setVisibility(View.GONE);

        prevButton.setEnabled(position > 0);
        nextButton.setEnabled(position < GalleryActivity.currentItems.size() - 1);

        if (item.isVideo) {
            // Play videos straight away.
            progress.setVisibility(View.GONE);
            playVideo(item.uri);
            return;
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
                final Bitmap bmp = decodeStill(item.uri);
                final boolean isMotion = item.motionPhotoChecked ? item.isMotionPhoto
                        : LivePhotoHelper.isMotionPhoto(LivePhotoViewerActivity.this, item.uri);
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        progress.setVisibility(View.GONE);
                        if (bmp != null) {
                            imageView.setImageBitmap(bmp);
                        }
                        if (isMotion) {
                            liveBadge.setVisibility(View.VISIBLE);
                            playButton.setVisibility(View.VISIBLE);
                        }
                    }
                });
            }
        }, "ViewerLoad").start();
    }

    private Bitmap decodeStill(Uri uri) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream is = getContentResolver().openInputStream(uri)) {
                BitmapFactory.decodeStream(is, null, bounds);
            }
            int sample = 1;
            int w = bounds.outWidth;
            int h = bounds.outHeight;
            while (w / (sample * 2) >= 1080 && h / (sample * 2) >= 1080) {
                sample *= 2;
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            try (InputStream is = getContentResolver().openInputStream(uri)) {
                return BitmapFactory.decodeStream(is, null, opts);
            }
        } catch (Exception e) {
            Log.e(TAG, "decodeStill failed", e);
            return null;
        }
    }

    private void playLivePhoto() {
        final GalleryItem item = currentItem();
        if (item == null) {
            return;
        }
        progress.setVisibility(View.VISIBLE);
        playButton.setVisibility(View.GONE);
        new Thread(new Runnable() {
            @Override
            public void run() {
                File out = new File(getCacheDir(), "viewer_live_" + System.currentTimeMillis() + ".mp4");
                final boolean ok = LivePhotoHelper.extractVideoFromMotionPhoto(
                        LivePhotoViewerActivity.this, item.uri, out);
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        progress.setVisibility(View.GONE);
                        if (ok) {
                            playVideo(Uri.fromFile(out));
                        } else {
                            playButton.setVisibility(View.VISIBLE);
                        }
                    }
                });
            }
        }, "ViewerExtract").start();
    }

    private void playVideo(Uri uri) {
        imageView.setVisibility(View.GONE);
        videoView.setVisibility(View.VISIBLE);
        videoView.setVideoURI(uri);
        MediaController controller = new MediaController(this);
        controller.setAnchorView(videoView);
        videoView.setMediaController(controller);
        videoView.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            @Override
            public void onPrepared(MediaPlayer mp) {
                mp.setLooping(false);
                videoView.start();
            }
        });
        videoView.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
            @Override
            public void onCompletion(MediaPlayer mp) {
                GalleryItem item = currentItem();
                if (item != null && !item.isVideo) {
                    // Live Photo: return to the still after the motion plays.
                    stopPlayback();
                    imageView.setVisibility(View.VISIBLE);
                    playButton.setVisibility(View.VISIBLE);
                }
            }
        });
        videoView.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            @Override
            public boolean onError(MediaPlayer mp, int what, int extra) {
                stopPlayback();
                imageView.setVisibility(View.VISIBLE);
                GalleryItem item = currentItem();
                if (item != null && !item.isVideo) {
                    playButton.setVisibility(View.VISIBLE);
                }
                return true;
            }
        });
    }

    private void stopPlayback() {
        try {
            if (videoView.isPlaying()) {
                videoView.stopPlayback();
            }
        } catch (Exception ignored) {
        }
        videoView.setVisibility(View.GONE);
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopPlayback();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopPlayback();
    }
}
