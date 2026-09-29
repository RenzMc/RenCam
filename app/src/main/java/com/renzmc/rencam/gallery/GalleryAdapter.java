package com.renzmc.rencam.gallery;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.LruCache;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import com.renzmc.rencam.R;
import com.renzmc.rencam.livephoto.LivePhotoHelper;

import java.io.InputStream;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Adapter for the RenCam in-app gallery grid. Loads thumbnails asynchronously and shows a "LIVE"
 * badge for Live Photos plus a play overlay for Live Photos and videos.
 *
 * @author RenzMc
 */
public class GalleryAdapter extends BaseAdapter {
    private static final String TAG = "GalleryAdapter";

    private final Context context;
    private final List<GalleryItem> items;
    private final LayoutInflater inflater;
    private final ExecutorService executor;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final LruCache<String, Bitmap> cache;

    public GalleryAdapter(Context context, List<GalleryItem> items) {
        this.context = context;
        this.items = items;
        this.inflater = LayoutInflater.from(context);
        this.executor = Executors.newFixedThreadPool(4);
        int maxKb = (int) (Runtime.getRuntime().maxMemory() / 1024);
        this.cache = new LruCache<String, Bitmap>(maxKb / 8) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value.getByteCount() / 1024;
            }
        };
    }

    @Override
    public int getCount() {
        return items.size();
    }

    @Override
    public Object getItem(int position) {
        return items.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        View view = convertView;
        if (view == null) {
            view = inflater.inflate(R.layout.gallery_item, parent, false);
        }
        final GalleryItem item = items.get(position);
        final ImageView image = view.findViewById(R.id.gallery_item_image);
        final TextView liveBadge = view.findViewById(R.id.gallery_item_live_badge);
        final ImageView play = view.findViewById(R.id.gallery_item_play);
        final ImageView videoBadge = view.findViewById(R.id.gallery_item_video_badge);

        final String key = item.uri.toString();
        Bitmap bmp = cache.get(key);
        if (bmp != null) {
            image.setImageBitmap(bmp);
        } else {
            image.setImageBitmap(null);
            loadThumbnail(item, image);
        }

        // Badges
        if (item.isVideo) {
            liveBadge.setVisibility(View.GONE);
            play.setVisibility(View.VISIBLE);
            videoBadge.setVisibility(View.VISIBLE);
        } else if (item.isMotionPhoto) {
            liveBadge.setVisibility(View.VISIBLE);
            play.setVisibility(View.VISIBLE);
            videoBadge.setVisibility(View.GONE);
        } else {
            liveBadge.setVisibility(View.GONE);
            play.setVisibility(View.GONE);
            videoBadge.setVisibility(View.GONE);
        }
        return view;
    }

    private void loadThumbnail(final GalleryItem item, final ImageView image) {
        final String key = item.uri.toString();
        executor.execute(new Runnable() {
            @Override
            public void run() {
                // For stills, first work out whether it's a Live Photo (cached on the item).
                if (!item.isVideo && !item.motionPhotoChecked) {
                    try {
                        item.isMotionPhoto = LivePhotoHelper.isMotionPhoto(context, item.uri);
                    } catch (Exception e) {
                        item.isMotionPhoto = false;
                    }
                    item.motionPhotoChecked = true;
                }

                Bitmap bmp = null;
                try {
                    if (item.isVideo) {
                        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
                        try {
                            retriever.setDataSource(context, item.uri);
                            bmp = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                        } finally {
                            try {
                                retriever.release();
                            } catch (Exception ignored) {
                            }
                        }
                    } else {
                        bmp = decodeStill(item);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "failed to load thumbnail for " + item.uri, e);
                }

                final Bitmap result = bmp;
                if (result != null) {
                    cache.put(key, result);
                }
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (result != null) {
                            image.setImageBitmap(result);
                        }
                        // Refresh badges now that we may have discovered a Live Photo.
                        notifyDataSetChanged();
                    }
                });
            }
        });
    }

    private Bitmap decodeStill(GalleryItem item) {
        try {
            // First pass: read bounds.
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream is = context.getContentResolver().openInputStream(item.uri)) {
                BitmapFactory.decodeStream(is, null, bounds);
            }
            int target = 320;
            int sample = 1;
            int w = bounds.outWidth;
            int h = bounds.outHeight;
            while (w / (sample * 2) >= target && h / (sample * 2) >= target) {
                sample *= 2;
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            try (InputStream is = context.getContentResolver().openInputStream(item.uri)) {
                return BitmapFactory.decodeStream(is, null, opts);
            }
        } catch (Exception e) {
            Log.e(TAG, "decodeStill failed", e);
            return null;
        }
    }

    public void shutdown() {
        executor.shutdownNow();
    }
}
