package com.renzmc.rencam.livephoto;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.renzmc.rencam.MyDebug;
import com.renzmc.rencam.PreferenceKeys;
import com.renzmc.rencam.R;
import com.renzmc.rencam.cameracontroller.CameraController;
import com.renzmc.rencam.preview.Preview;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Orchestrates the RenCam "Live Photo" (Google Motion Photo) feature.
 *
 * <p><b>How it works (as requested by the user):</b> when the shutter is pressed in photo mode
 * while Live Photo is enabled, instead of taking a plain still photo the app <b>records a short
 * video</b>. Once the clip has finished recording, a single cover frame is extracted from the video
 * and saved as a normal photo through the usual pipeline (MediaStore / SAF / file). Finally the
 * recorded MP4 is packaged together with the saved cover frame into a single Motion Photo
 * (JPEG with an embedded MP4 + XMP metadata) using {@link LivePhotoHelper}.</p>
 *
 * <p>This "record a video, then convert it" approach is deliberately chosen because it works on
 * <b>every</b> device that can record video - it does not depend on the device supporting the
 * simultaneous photo+video capture that a "buffer the preview" implementation would need. It also
 * makes the feature work identically on the front camera.</p>
 *
 * <p>The class is intentionally decoupled from the camera plumbing: the actual recording primitive
 * lives in {@link Preview} ({@code startLivePhotoBuffer()} / {@code stopLivePhotoBuffer()}), the
 * cover image is saved through the {@link LivePhotoHost} callbacks, and the saved still is signalled
 * back via {@code MyApplicationInterface.addLastImage(...)}.</p>
 *
 * @author RenzMc
 */
public class LivePhotoManager {
    private static final String TAG = "LivePhotoManager";

    /** Minimum Android version that reliably supports MediaMuxer/MediaExtractor based processing. */
    private static final int MIN_SDK = Build.VERSION_CODES.JELLY_BEAN_MR2; // 18

    private final Context context;
    private final SharedPreferences sharedPreferences;
    private Preview preview;
    private LivePhotoHost host;
    private final Handler handler = new Handler(Looper.getMainLooper());

    /** True while we are recording the Live Photo video (between the shutter press and the end of the clip). */
    private volatile boolean capturing;
    /** True once the video has been recorded and we are waiting for the cover still to be saved. */
    private volatile boolean waiting_for_cover;
    /** The recorded video file that is waiting to be packaged into the saved cover still. */
    private File pending_video_file;
    /** The presentation timestamp (microseconds) of the cover frame within the recorded video. */
    private long pending_presentation_us;

    private Runnable stop_runnable;

    /** The flash value that was active before we forced a torch, so we can restore it afterwards. */
    private String flash_value_before_capture = null;
    /** True if we turned on the front-screen flash (bright white screen) for the current capture. */
    private boolean used_screen_flash = false;

    public LivePhotoManager(Context context, SharedPreferences sharedPreferences, Preview preview) {
        this.context = context;
        this.sharedPreferences = sharedPreferences;
        this.preview = preview;
    }

    /** Updates the Preview reference (the Preview may not exist when this manager is first created). */
    public void setPreview(Preview preview) {
        this.preview = preview;
    }

    /** Sets the host used to save the cover frame and control the front-screen flash. */
    public void setHost(LivePhotoHost host) {
        this.host = host;
    }

    // ---------------------------------------------------------------------------------------------
    // Configuration
    // ---------------------------------------------------------------------------------------------

    /** Whether the user has enabled the Live Photo feature. */
    public boolean isEnabled() {
        return sharedPreferences.getBoolean(PreferenceKeys.LivePhotoEnablePreferenceKey, false);
    }

    /** Whether the device/OS supports the Motion Photo pipeline. */
    public boolean isSupported() {
        return Build.VERSION.SDK_INT >= MIN_SDK;
    }

    /** Whether Live Photo capture should currently be used (enabled and supported). */
    public boolean isActive() {
        return isEnabled() && isSupported();
    }

    /** Total Live Photo length in milliseconds (video duration setting, default 3s). */
    public int getDurationMs() {
        return parseSecondsToMs(sharedPreferences.getString(PreferenceKeys.LivePhotoDurationPreferenceKey, "3"), 3000);
    }

    /**
     * The offset (in milliseconds) into the recorded clip at which the cover frame is taken.
     *
     * <p>Because we start recording at the moment the shutter is pressed, the cover frame is taken
     * slightly into the clip (so the camera's exposure/focus has settled and there is some motion
     * before and after the still). This reuses the "duration before shutter" preference, clamped so
     * that it always stays inside the clip.</p>
     */
    public int getCoverOffsetMs() {
        int duration = getDurationMs();
        int offset;
        try {
            offset = Integer.parseInt(sharedPreferences.getString(PreferenceKeys.LivePhotoPrerollPreferenceKey, "1500"));
        }
        catch(NumberFormatException e) {
            offset = 1500;
        }
        if( offset < 0 ) {
            offset = 0;
        }
        if( offset > duration - 200 ) {
            offset = Math.max(0, duration - 200);
        }
        return offset;
    }

    /** Flash behaviour: "on" (use the light) or "off". */
    public String getFlashBehavior() {
        return sharedPreferences.getString(PreferenceKeys.LivePhotoFlashBehaviorPreferenceKey, "on");
    }

    /** Whether the recorded video should record audio. */
    public boolean getRecordAudio() {
        return sharedPreferences.getBoolean(PreferenceKeys.LivePhotoAudioPreferenceKey, false);
    }

    private int parseSecondsToMs(String value, int fallback) {
        try {
            return Integer.parseInt(value) * 1000;
        }
        catch(NumberFormatException e) {
            return fallback;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Capture lifecycle
    // ---------------------------------------------------------------------------------------------

    /**
     * Starts a Live Photo capture: begins recording a short video. Must be called on the UI thread.
     *
     * @return true if the caller should SKIP the normal still capture - either because a Live Photo
     *         recording was just started, or because a Live Photo is already in progress (in which
     *         case the shutter press is swallowed so it can't be mistaken for a normal still that
     *         would then get the wrong video packaged into it).
     */
    public synchronized boolean captureLivePhoto() {
        if( MyDebug.LOG )
            Log.d(TAG, "captureLivePhoto");
        if( !isActive() ) {
            if( MyDebug.LOG )
                Log.d(TAG, "live photo not active");
            return false;
        }
        if( capturing || waiting_for_cover ) {
            if( MyDebug.LOG )
                Log.d(TAG, "live photo already in progress - swallowing shutter press");
            return true;
        }
        if( preview == null || preview.getCameraController() == null ) {
            if( MyDebug.LOG )
                Log.d(TAG, "camera not open");
            return false;
        }
        if( preview.isVideo() || !preview.isPreviewStarted() ) {
            if( MyDebug.LOG )
                Log.d(TAG, "not in photo preview mode");
            return false;
        }

        File video_file = createBufferFile();
        if( video_file == null ) {
            Log.e(TAG, "failed to create live photo file");
            return false;
        }

        // Flash: the front camera has no LED, so we light the scene with a bright white screen;
        // the back camera uses its LED as a continuous torch for the duration of the clip.
        boolean front = host != null && host.isFrontFacing();
        applyFlashForCapture(front);

        boolean started = preview.startLivePhotoBuffer(video_file, getRecordAudio());
        if( !started ) {
            Log.e(TAG, "failed to start live photo recording");
            restoreFlashAfterCapture();
            deleteQuietly(video_file);
            return false;
        }

        capturing = true;
        pending_video_file = video_file;
        pending_presentation_us = 0L;
        waiting_for_cover = false;

        // Update the preview state: hide the GUI and show the "taking photo" indicator for the
        // duration of the clip (the shutter was already put into PHASE_TAKING_PHOTO by takePicture()).
        if( preview != null ) {
            preview.onLivePhotoCaptureStarted();
        }

        if( host != null ) {
            host.showToast(R.string.live_photo_hold_steady);
        }

        int duration = getDurationMs();
        if( MyDebug.LOG )
            Log.d(TAG, "recording live photo for " + duration + "ms");
        stop_runnable = new Runnable() {
            @Override
            public void run() {
                onCaptureDurationElapsed();
            }
        };
        handler.postDelayed(stop_runnable, duration);
        return true;
    }

    /** Called once the configured clip duration has elapsed: stop recording and extract the cover frame. */
    private synchronized void onCaptureDurationElapsed() {
        if( MyDebug.LOG )
            Log.d(TAG, "onCaptureDurationElapsed");
        stop_runnable = null;
        if( !capturing ) {
            return;
        }
        capturing = false;

        final File video_file = preview != null ? preview.stopLivePhotoBuffer() : null;

        // Recording has finished - turn the flash off again and restore the normal preview state
        // (so the shutter button becomes usable again while the cover frame is extracted/saved).
        restoreFlashAfterCapture();
        if( preview != null ) {
            preview.onLivePhotoCaptureFinished();
        }

        if( video_file == null || !video_file.exists() || video_file.length() < 100 ) {
            Log.e(TAG, "live photo recording produced no usable video");
            deleteQuietly(video_file);
            return;
        }

        final int offset_ms = getCoverOffsetMs();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final byte[] cover = extractCoverJpeg(video_file, offset_ms);
                if( cover == null ) {
                    Log.e(TAG, "failed to extract cover frame from live photo video");
                    deleteQuietly(video_file);
                    return;
                }
                // Register the pending video *before* saving, so that when the still is saved
                // (possibly synchronously) the onStillSaved() callback finds it.
                synchronized( LivePhotoManager.this ) {
                    pending_video_file = video_file;
                    pending_presentation_us = offset_ms * 1000L;
                    waiting_for_cover = true;
                }
                // Save the cover on the main thread, as saveImage() touches UI-related state.
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        boolean ok = host != null && host.saveLivePhotoCover(cover, new Date());
                        if( !ok ) {
                            Log.e(TAG, "failed to save live photo cover frame");
                            synchronized( LivePhotoManager.this ) {
                                waiting_for_cover = false;
                                pending_video_file = null;
                            }
                            deleteQuietly(video_file);
                        }
                    }
                });
            }
        }, "LivePhotoCover").start();
    }

    /** Extracts a single frame from the recorded video and encodes it as JPEG bytes. */
    private byte[] extractCoverJpeg(File video_file, int offset_ms) {
        Bitmap bitmap = LivePhotoHelper.extractVideoFrame(context, Uri.fromFile(video_file), offset_ms);
        if( bitmap == null ) {
            return null;
        }
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, bos);
            return bos.toByteArray();
        }
        finally {
            bitmap.recycle();
        }
    }

    /**
     * Called once the cover still has been saved to disk, so we know which file to package the video
     * into (turning the plain JPEG into a Motion Photo).
     */
    public synchronized void onStillSaved(File stillFile) {
        if( MyDebug.LOG )
            Log.d(TAG, "onStillSaved(file): " + (stillFile != null ? stillFile.getAbsolutePath() : "null"));
        if( !waiting_for_cover ) {
            return;
        }
        waiting_for_cover = false;
        final File video_file = pending_video_file;
        final long presentation_us = pending_presentation_us;
        pending_video_file = null;
        if( video_file == null || stillFile == null || !stillFile.exists() ) {
            deleteQuietly(video_file);
            return;
        }
        startPackaging(stillFile, null, video_file, presentation_us);
    }

    /**
     * Called once the cover still has been saved to a content {@link Uri} (MediaStore or SAF), so we
     * know which file to package the video into.
     */
    public synchronized void onStillSaved(Uri stillUri) {
        if( MyDebug.LOG )
            Log.d(TAG, "onStillSaved(uri): " + stillUri);
        if( !waiting_for_cover ) {
            return;
        }
        waiting_for_cover = false;
        final File video_file = pending_video_file;
        final long presentation_us = pending_presentation_us;
        pending_video_file = null;
        if( video_file == null || stillUri == null ) {
            deleteQuietly(video_file);
            return;
        }
        startPackaging(null, stillUri, video_file, presentation_us);
    }

    /** Aborts any in-flight capture and discards the recorded video. */
    public synchronized void abort() {
        if( MyDebug.LOG )
            Log.d(TAG, "abort");
        boolean was_capturing = capturing;
        if( stop_runnable != null ) {
            handler.removeCallbacks(stop_runnable);
            stop_runnable = null;
        }
        capturing = false;
        waiting_for_cover = false;
        pending_video_file = null;
        restoreFlashAfterCapture();
        if( preview != null && preview.isLivePhotoBuffering() ) {
            File file = preview.stopLivePhotoBuffer();
            deleteQuietly(file);
        }
        // Restore the normal preview state if we had been recording (the shutter was put into
        // PHASE_TAKING_PHOTO by takePicture()).
        if( was_capturing && preview != null ) {
            preview.onLivePhotoCaptureFinished();
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Finalize: package the video into the saved still
    // ---------------------------------------------------------------------------------------------

    /** Runs the (potentially slow) packaging on a background thread. */
    private void startPackaging(final File still_file, final Uri still_uri, final File video_file,
                                final long presentation_us) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    if( still_file != null ) {
                        packageIntoFile(still_file, video_file, presentation_us);
                    }
                    else if( still_uri != null ) {
                        packageIntoUri(still_uri, video_file, presentation_us);
                    }
                }
                catch(Exception e) {
                    Log.e(TAG, "failed to package live photo", e);
                }
                finally {
                    deleteQuietly(video_file);
                }
            }
        }, "LivePhotoPackager").start();
    }

    /** Packages the still (a plain file) in place, replacing it with the Motion Photo. */
    private void packageIntoFile(File still_file, File video_file, long presentation_us) {
        File output = new File(still_file.getParentFile(), still_file.getName() + ".live.tmp");
        boolean ok = LivePhotoHelper.packageMotionPhoto(still_file, video_file, output, presentation_us);
        if( ok && output.exists() ) {
            // Replace the plain JPEG with the Motion Photo (JPEG + embedded MP4 + XMP).
            if( still_file.delete() ) {
                if( output.renameTo(still_file) ) {
                    if( MyDebug.LOG )
                        Log.d(TAG, "live photo saved: " + still_file.getAbsolutePath());
                    notifySaved(still_file);
                }
                else {
                    Log.e(TAG, "failed to move live photo into place");
                }
            }
            else {
                Log.e(TAG, "failed to delete plain still before replacing with live photo");
            }
        }
        else {
            Log.e(TAG, "failed to package motion photo");
        }
        if( !ok && output.exists() ) {
            deleteQuietly(output);
        }
    }

    /**
     * Packages the still (a content {@link Uri} from MediaStore/SAF) and writes the Motion Photo
     * back over the same Uri, replacing the plain JPEG that was saved.
     */
    private void packageIntoUri(Uri still_uri, File video_file, long presentation_us) {
        File output = null;
        try {
            byte[] coverBytes = LivePhotoHelper.readUri(context, still_uri);
            output = new File(context.getCacheDir(),
                    "rencam_live_pkg_" + System.currentTimeMillis() + ".jpg");
            boolean ok = LivePhotoHelper.packageMotionPhoto(coverBytes, video_file, output, presentation_us);
            if( ok && output.exists() ) {
                long new_size = output.length();
                if( LivePhotoHelper.writeFileToUri(context, output, still_uri) ) {
                    if( MyDebug.LOG )
                        Log.d(TAG, "live photo saved to uri: " + still_uri);
                    notifySaved(still_uri, new_size);
                }
                else {
                    Log.e(TAG, "failed to write live photo back to uri");
                }
            }
            else {
                Log.e(TAG, "failed to package motion photo for uri");
            }
        }
        catch(Exception e) {
            Log.e(TAG, "failed to package motion photo into uri", e);
        }
        finally {
            if( output != null && output.exists() ) {
                deleteQuietly(output);
            }
        }
    }

    private void notifySaved(final File file) {
        // Ask the media scanner to re-index the (now larger) Motion Photo.
        try {
            android.media.MediaScannerConnection.scanFile(context,
                    new String[]{ file.getAbsolutePath() }, new String[]{ "image/jpeg" }, null);
        }
        catch(Exception e) {
            Log.e(TAG, "failed to scan live photo file", e);
        }
    }

    private void notifySaved(final Uri uri, final long new_size) {
        // The file content changed in place; refresh the MediaStore metadata (size/mtime) so that
        // gallery apps (and our own thumbnail) see the updated Motion Photo.
        try {
            if( "content".equals(uri.getScheme()) ) {
                android.content.ContentValues values = new android.content.ContentValues();
                values.put(android.provider.MediaStore.MediaColumns.SIZE, new_size);
                values.put(android.provider.MediaStore.MediaColumns.DATE_MODIFIED,
                        System.currentTimeMillis() / 1000L);
                context.getContentResolver().update(uri, values, null, null);
            }
            // Best-effort: also request a scan so external viewers see the updated file promptly.
            String path = uri.getPath();
            if( path != null && path.startsWith("/") ) {
                android.media.MediaScannerConnection.scanFile(context,
                        new String[]{ path }, new String[] { "image/jpeg" }, null);
            }
        }
        catch(Exception e) {
            Log.e(TAG, "failed to refresh live photo uri metadata", e);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Flash handling
    // ---------------------------------------------------------------------------------------------

    /**
     * Configures the flash for the upcoming Live Photo recording.
     *
     * <p>For the front camera we light the scene with a bright white screen (there is no LED on
     * front cameras). For the back camera we keep the LED on as a continuous torch for the whole
     * clip (so the recorded video - and therefore the extracted cover frame - is illuminated),
     * remembering the user's previous flash mode so it can be restored afterwards.</p>
     */
    private void applyFlashForCapture(boolean front) {
        String behavior = getFlashBehavior();
        CameraController controller = preview != null ? preview.getCameraController() : null;
        String current = controller != null ? controller.getFlashValue() : null; // "" if flash not supported
        boolean has_led = current != null && current.length() > 0;

        if( "off".equals(behavior) ) {
            // User asked not to use any flash for Live Photo.
            if( !front && controller != null && has_led ) {
                controller.setFlashValue("flash_off");
            }
            return;
        }

        if( front && !has_led ) {
            // The front camera has no LED flash, so we use the "front screen flash": the screen is
            // lit up bright white (with a glow around the edges) to illuminate the subject while the
            // video is recorded. This is the standard technique for front-camera flash.
            if( host != null ) {
                if( MyDebug.LOG )
                    Log.d(TAG, "turning on front screen flash for live photo");
                host.turnFrontScreenFlashOn();
                used_screen_flash = true;
            }
            return;
        }

        // Use the LED as a continuous torch for the whole clip (front LED if the device has one,
        // otherwise the back LED), so the recorded video - and the extracted cover frame - is lit.
        if( controller != null && has_led ) {
            if( flash_value_before_capture == null ) {
                flash_value_before_capture = current;
            }
            if( MyDebug.LOG )
                Log.d(TAG, "turning on LED torch for live photo");
            controller.setFlashValue("flash_torch");
        }
    }

    /**
     * Restores the flash mode the user had before we forced a torch, and turns off the front-screen
     * flash. Called once the recording has finished.
     */
    private void restoreFlashAfterCapture() {
        if( used_screen_flash && host != null ) {
            host.turnFrontScreenFlashOff();
            used_screen_flash = false;
        }
        CameraController controller = preview != null ? preview.getCameraController() : null;
        if( controller != null && flash_value_before_capture != null ) {
            if( MyDebug.LOG )
                Log.d(TAG, "restoreFlashAfterCapture, restoring: " + flash_value_before_capture);
            try {
                controller.setFlashValue(flash_value_before_capture);
            }
            catch(Exception e) {
                Log.e(TAG, "failed to restore flash value", e);
            }
        }
        flash_value_before_capture = null;
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    private File createBufferFile() {
        try {
            File dir = context.getCacheDir();
            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date());
            return new File(dir, "rencam_live_" + timestamp + ".mp4");
        }
        catch(Exception e) {
            Log.e(TAG, "failed to create live photo file", e);
            return null;
        }
    }

    private void deleteQuietly(File file) {
        if( file != null && file.exists() ) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }
}
