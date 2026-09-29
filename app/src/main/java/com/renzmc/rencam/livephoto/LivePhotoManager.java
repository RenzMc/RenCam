package com.renzmc.rencam.livephoto;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.renzmc.rencam.MyDebug;
import com.renzmc.rencam.PreferenceKeys;
import com.renzmc.rencam.cameracontroller.CameraController;
import com.renzmc.rencam.preview.Preview;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Orchestrates the RenCam "Live Photo" (Google Motion Photo) feature.
 *
 * <p>Design (as requested by the user, matching Apple/Google Live Photo semantics):
 * <ol>
 *   <li>While the camera is open in photo mode and Live Photo is enabled, a low-overhead
 *       video is <b>continuously buffered</b> to a temporary file (the "pre-roll").</li>
 *   <li>When the user presses the shutter we remember the exact timestamp, capture the
 *       high resolution still, and (per the flash behaviour setting) fire a <b>momentary
 *       flash burst</b> for the still. The flash is <b>never</b> left on as a continuous
 *       torch while the buffer is recording.</li>
 *   <li>Recording continues for the configured post-roll so we also capture the moment
 *       <i>after</i> the shutter.</li>
 *   <li>The buffered video is trimmed to <code>[shutter - preroll, shutter + postroll]</code>
 *       and packaged together with the still JPEG into a single Motion Photo file
 *       (JPEG with an embedded MP4 + XMP metadata), using {@link LivePhotoHelper}.</li>
 * </ol>
 *
 * <p>This class is intentionally decoupled from the camera plumbing: the actual recording
 * primitive lives in {@link Preview} ({@code startLivePhotoBuffer()} /
 * {@code stopLivePhotoBuffer()}), and the still-image save is signalled by
 * {@code MyApplicationInterface.addLastImage(File, boolean)}.
 *
 * @author RenzMc
 */
public class LivePhotoManager {
    private static final String TAG = "LivePhotoManager";

    /** Minimum Android version that reliably supports MediaMuxer/MediaExtractor based trimming. */
    private static final int MIN_SDK = Build.VERSION_CODES.JELLY_BEAN_MR2; // 18

    private final Context context;
    private final SharedPreferences sharedPreferences;
    private Preview preview;
    private final Handler handler = new Handler(Looper.getMainLooper());

    /** True while a background video buffer is being recorded. */
    private volatile boolean buffering;
    /** Wall-clock time (ms) at which the current buffer started recording. */
    private long buffer_start_time;
    /** Wall-clock time (ms) at which the shutter fired, relative to the running buffer. */
    private long shutter_time;
    /** Set once the shutter has fired and we are waiting for the post-roll to elapse. */
    private volatile boolean waiting_for_postroll;
    /** The still photo file, once it has been saved by {@code ImageSaver}. */
    private File pending_still;
    /** The still photo content uri (MediaStore / SAF), once it has been saved by {@code ImageSaver}. */
    private Uri pending_still_uri;

    // State used when the post-roll elapses before the still has finished saving.
    private volatile boolean waiting_for_still;
    private File stopped_video_file;
    private Uri stopped_still_uri;
    private long stopped_shutter_offset;
    private int stopped_preroll;
    private int stopped_postroll;

    private Runnable postroll_runnable;

    public LivePhotoManager(Context context, SharedPreferences sharedPreferences, Preview preview) {
        this.context = context;
        this.sharedPreferences = sharedPreferences;
        this.preview = preview;
    }

    /** Updates the Preview reference (the Preview may not exist when this manager is first created). */
    public void setPreview(Preview preview) {
        this.preview = preview;
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

    /** Total Live Photo length in milliseconds (video duration setting, default 3s). */
    public int getDurationMs() {
        return parseSecondsToMs(sharedPreferences.getString(PreferenceKeys.LivePhotoDurationPreferenceKey, "3"), 3000);
    }

    /** Pre-roll (before shutter) in milliseconds, default 1500ms. */
    public int getPrerollMs() {
        String value = sharedPreferences.getString(PreferenceKeys.LivePhotoPrerollPreferenceKey, "1500");
        try {
            return Integer.parseInt(value);
        }
        catch(NumberFormatException e) {
            return 1500;
        }
    }

    /** Post-roll (after shutter) in milliseconds = duration - preroll (clamped to >= 0). */
    public int getPostrollMs() {
        return Math.max(0, getDurationMs() - getPrerollMs());
    }

    /** Flash behaviour: "burst" (recommended), "torch" or "off". */
    public String getFlashBehavior() {
        return sharedPreferences.getString(PreferenceKeys.LivePhotoFlashBehaviorPreferenceKey, "burst");
    }

    /** Whether the buffered video should record audio. */
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
    // Buffer lifecycle
    // ---------------------------------------------------------------------------------------------

    /**
     * Starts continuously buffering video. Safe to call repeatedly; does nothing if already
     * buffering or if the feature is disabled/unsupported.
     */
    public synchronized void startBuffering() {
        if( MyDebug.LOG )
            Log.d(TAG, "startBuffering");
        if( !isEnabled() || !isSupported() ) {
            if( MyDebug.LOG )
                Log.d(TAG, "not enabled or not supported");
            return;
        }
        if( buffering ) {
            if( MyDebug.LOG )
                Log.d(TAG, "already buffering");
            return;
        }
        if( preview == null || preview.getCameraController() == null ) {
            if( MyDebug.LOG )
                Log.d(TAG, "camera not open");
            return;
        }
        if( preview.isVideo() || !preview.isPreviewStarted() ) {
            if( MyDebug.LOG )
                Log.d(TAG, "not in photo preview mode");
            return;
        }
        if( !preview.supportsPhotoVideoRecording() ) {
            // Without the ability to capture a still while the buffer is recording, a Live Photo
            // cannot be produced - fall back to normal photo capture (no buffering).
            if( MyDebug.LOG )
                Log.d(TAG, "device does not support photo-video recording");
            return;
        }
        File buffer_file = createBufferFile();
        if( buffer_file == null ) {
            Log.e(TAG, "failed to create live photo buffer file");
            return;
        }
        boolean started = preview.startLivePhotoBuffer(buffer_file, getRecordAudio());
        if( started ) {
            buffering = true;
            buffer_start_time = System.currentTimeMillis();
            if( MyDebug.LOG )
                Log.d(TAG, "live photo buffer started: " + buffer_file.getAbsolutePath());
        }
        else {
            Log.e(TAG, "failed to start live photo buffer");
        }
    }

    /** Stops the buffer (if running) and returns the recorded file, or null. */
    public synchronized File stopBuffering() {
        if( MyDebug.LOG )
            Log.d(TAG, "stopBuffering");
        cancelPostroll();
        if( !buffering ) {
            return null;
        }
        buffering = false;
        File file = preview.stopLivePhotoBuffer();
        if( MyDebug.LOG )
            Log.d(TAG, "buffer stopped: " + (file != null ? file.getAbsolutePath() : "null"));
        return file;
    }

    /** Aborts any in-flight capture and discards the buffer. */
    public synchronized void abort() {
        if( MyDebug.LOG )
            Log.d(TAG, "abort");
        cancelPostroll();
        waiting_for_postroll = false;
        waiting_for_still = false;
        pending_still = null;
        pending_still_uri = null;
        stopped_still_uri = null;
        if( stopped_video_file != null ) {
            if( stopped_video_file.exists() ) {
                //noinspection ResultOfMethodCallIgnored
                stopped_video_file.delete();
            }
            stopped_video_file = null;
        }
        if( buffering ) {
            buffering = false;
            File file = preview.stopLivePhotoBuffer();
            if( file != null && file.exists() ) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Shutter handling
    // ---------------------------------------------------------------------------------------------

    /**
     * Called the moment the shutter fires (before/at still capture). Records the shutter
     * timestamp relative to the running buffer and schedules the post-roll stop.
     */
    public synchronized void onShutter() {
        if( MyDebug.LOG )
            Log.d(TAG, "onShutter");
        if( !isEnabled() || !buffering ) {
            return;
        }
        shutter_time = System.currentTimeMillis() - buffer_start_time;
        if( MyDebug.LOG )
            Log.d(TAG, "shutter offset within buffer: " + shutter_time + "ms");
        waiting_for_postroll = true;
        pending_still = null;
        pending_still_uri = null;

        // Schedule the stop after the post-roll has elapsed.
        int postroll = getPostrollMs();
        if( MyDebug.LOG )
            Log.d(TAG, "scheduling post-roll stop in " + postroll + "ms");
        postroll_runnable = new Runnable() {
            @Override
            public void run() {
                synchronized( LivePhotoManager.this ) {
                    if( !waiting_for_postroll ) {
                        return;
                    }
                    waiting_for_postroll = false;
                }
                onPostrollElapsed();
            }
        };
        handler.postDelayed(postroll_runnable, postroll);
    }

    /**
     * Called once the still photo has been saved to disk, so we know which file to use as the
     * Motion Photo cover image.
     */
    public synchronized void onStillSaved(File stillFile) {
        if( MyDebug.LOG )
            Log.d(TAG, "onStillSaved: " + (stillFile != null ? stillFile.getAbsolutePath() : "null"));
        if( !isEnabled() ) {
            return;
        }
        if( waiting_for_still ) {
            // The post-roll already elapsed and we were waiting for the still to finish saving.
            waiting_for_still = false;
            File video_file = stopped_video_file;
            long shutter_offset = stopped_shutter_offset;
            int preroll = stopped_preroll;
            int postroll = stopped_postroll;
            stopped_video_file = null;
            stopped_still_uri = null;
            startPackaging(video_file, stillFile, null, shutter_offset, preroll, postroll);
        }
        else {
            pending_still = stillFile;
            pending_still_uri = null;
        }
    }

    /**
     * Called once the still photo has been saved to a content {@link Uri} (MediaStore or SAF),
     * so we know which file to use as the Motion Photo cover image.
     *
     * <p>This is the path used on Android 10+ where photos are saved via MediaStore by default,
     * and whenever the user has enabled the Storage Access Framework. Without this the Live Photo
     * manager would never be notified of the saved still and the photo would remain a plain JPEG.</p>
     */
    public synchronized void onStillSaved(Uri stillUri) {
        if( MyDebug.LOG )
            Log.d(TAG, "onStillSaved(uri): " + stillUri);
        if( !isEnabled() ) {
            return;
        }
        if( waiting_for_still ) {
            waiting_for_still = false;
            File video_file = stopped_video_file;
            long shutter_offset = stopped_shutter_offset;
            int preroll = stopped_preroll;
            int postroll = stopped_postroll;
            stopped_video_file = null;
            stopped_still_uri = null;
            startPackaging(video_file, null, stillUri, shutter_offset, preroll, postroll);
        }
        else {
            pending_still = null;
            pending_still_uri = stillUri;
        }
    }

    private void cancelPostroll() {
        if( postroll_runnable != null ) {
            handler.removeCallbacks(postroll_runnable);
            postroll_runnable = null;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Finalize: clip + package
    // ---------------------------------------------------------------------------------------------

    /**
     * Called when the post-roll has elapsed: stops the buffer and either packages immediately
     * (if the still is already saved) or waits for the still to arrive.
     */
    private synchronized void onPostrollElapsed() {
        final File video_file = stopBuffering();
        final File still_file = pending_still;
        final Uri still_uri = pending_still_uri;
        final long shutter_offset = shutter_time;
        final int preroll = getPrerollMs();
        final int postroll = getPostrollMs();
        pending_still = null;
        pending_still_uri = null;

        if( video_file == null ) {
            if( MyDebug.LOG )
                Log.d(TAG, "cannot finalize live photo: no video");
            return;
        }

        if( still_file != null && still_file.exists() ) {
            startPackaging(video_file, still_file, null, shutter_offset, preroll, postroll);
        }
        else if( still_uri != null ) {
            startPackaging(video_file, null, still_uri, shutter_offset, preroll, postroll);
        }
        else {
            // The still hasn't been saved yet - keep the video and wait for onStillSaved().
            if( MyDebug.LOG )
                Log.d(TAG, "still not saved yet, waiting for it before packaging");
            stopped_video_file = video_file;
            stopped_still_uri = null;
            stopped_shutter_offset = shutter_offset;
            stopped_preroll = preroll;
            stopped_postroll = postroll;
            waiting_for_still = true;
        }
    }

    /** Runs the (potentially slow) trimming/packaging on a background thread. */
    private void startPackaging(final File video_file, final File still_file, final Uri still_uri,
                                final long shutter_offset, final int preroll, final int postroll) {
        boolean have_still = (still_file != null && still_file.exists()) || still_uri != null;
        if( video_file == null || !have_still ) {
            if( MyDebug.LOG )
                Log.d(TAG, "cannot package live photo: video=" + video_file + " still=" + still_file + " uri=" + still_uri);
            if( video_file != null && video_file.exists() ) {
                //noinspection ResultOfMethodCallIgnored
                video_file.delete();
            }
            return;
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    packageLivePhoto(video_file, still_file, still_uri, shutter_offset, preroll, postroll);
                }
                catch(Exception e) {
                    Log.e(TAG, "failed to package live photo", e);
                }
                finally {
                    if( video_file.exists() ) {
                        //noinspection ResultOfMethodCallIgnored
                        video_file.delete();
                    }
                }
            }
        }, "LivePhotoPackager").start();
    }

    private void packageLivePhoto(File video_file, File still_file, Uri still_uri, long shutter_offset,
                                  int preroll, int postroll) {
        if( MyDebug.LOG )
            Log.d(TAG, "packageLivePhoto: shutter_offset=" + shutter_offset + " preroll=" + preroll + " postroll=" + postroll);

        long clip_start = Math.max(0, shutter_offset - preroll);
        long clip_end = shutter_offset + postroll;

        File trimmed = new File(video_file.getParentFile(), video_file.getName().replace(".mp4", "_trim.mp4"));
        boolean trimmed_ok = LivePhotoHelper.trimVideo(context, Uri.fromFile(video_file), trimmed, clip_start, clip_end);
        File motion_source = trimmed_ok && trimmed.exists() ? trimmed : video_file;

        // The still corresponds to (shutter_offset - clip_start) ms into the trimmed clip.
        long presentation_us = Math.max(0, shutter_offset - clip_start) * 1000L;

        if( still_file != null && still_file.exists() ) {
            packageIntoFile(still_file, motion_source, presentation_us);
        }
        else if( still_uri != null ) {
            packageIntoUri(still_uri, motion_source, presentation_us);
        }
        else {
            Log.e(TAG, "no still image to package");
        }

        if( trimmed.exists() ) {
            //noinspection ResultOfMethodCallIgnored
            trimmed.delete();
        }

        // Restart buffering so the next shot is also a Live Photo.
        handler.post(new Runnable() {
            @Override
            public void run() {
                startBuffering();
            }
        });
    }

    /** Packages the still (a plain file) in place, replacing it with the Motion Photo. */
    private void packageIntoFile(File still_file, File motion_source, long presentation_us) {
        File output = new File(still_file.getParentFile(), still_file.getName() + ".live.tmp");
        boolean ok = LivePhotoHelper.packageMotionPhoto(still_file, motion_source, output, presentation_us);
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
            //noinspection ResultOfMethodCallIgnored
            output.delete();
        }
    }

    /**
     * Packages the still (a content {@link Uri} from MediaStore/SAF) and writes the Motion Photo
     * back over the same Uri, replacing the plain JPEG that was saved.
     */
    private void packageIntoUri(Uri still_uri, File motion_source, long presentation_us) {
        File output = null;
        try {
            byte[] coverBytes = LivePhotoHelper.readUri(context, still_uri);
            output = new File(context.getCacheDir(),
                    "rencam_live_pkg_" + System.currentTimeMillis() + ".jpg");
            boolean ok = LivePhotoHelper.packageMotionPhoto(coverBytes, motion_source, output, presentation_us);
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
                //noinspection ResultOfMethodCallIgnored
                output.delete();
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
                        new String[]{ path }, new String[]{ "image/jpeg" }, null);
            }
        }
        catch(Exception e) {
            Log.e(TAG, "failed to refresh live photo uri metadata", e);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Flash handling
    // ---------------------------------------------------------------------------------------------

    /** The flash value that was active before we forced a burst, so we can restore it afterwards. */
    private String flash_value_before_burst = null;

    /**
     * Ensures the flash is configured correctly for the upcoming still capture.
     *
     * <p>Per the requested behaviour, the flash must be a <b>momentary burst</b> fired only at the
     * instant the still is captured - it must <b>not</b> be a continuous torch running for the whole
     * duration of the buffered video.
     *
     * <p>To make the embedded key photo look like an iPhone Live Photo taken with flash, we want the
     * still that ends up inside the Motion Photo to be the <b>flash-illuminated</b> frame. We therefore
     * force the flash into a definite still-flash mode ({@code flash_on}) for the burst behaviour,
     * remembering the user's previous mode so it can be restored after the shot. If the flash was
     * already {@code on}/{@code auto}/{@code red-eye} we keep the same LED behaviour but guarantee it
     * fires (rather than possibly not firing in a bright scene under {@code auto}).
     */
    public void applyFlashForStillCapture(CameraController controller) {
        if( controller == null || !isEnabled() ) {
            return;
        }
        String behavior = getFlashBehavior();
        if( MyDebug.LOG )
            Log.d(TAG, "applyFlashForStillCapture, behavior: " + behavior);
        String current = controller.getFlashValue(); // "" if flash not supported
        if( "off".equals(behavior) ) {
            controller.setFlashValue("flash_off");
        }
        else if( "torch".equals(behavior) ) {
            // Non-recommended: continuous torch for the whole clip.
            controller.setFlashValue("flash_torch");
        }
        else {
            // Recommended "burst": the still must be the flash-illuminated frame.
            if( current != null && current.length() > 0 ) {
                // Flash is available on this camera. Remember the user's mode and force a definite
                // momentary burst for the still (never a continuous torch).
                if( flash_value_before_burst == null ) {
                    flash_value_before_burst = current;
                }
                if( !"flash_on".equals(current) ) {
                    controller.setFlashValue("flash_on");
                }
                // Make sure the startup-autofocus flash restore can't override the burst.
                if( preview != null ) {
                    preview.pinFlashForLivePhotoStill();
                }
            }
        }
    }

    /**
     * Restores the flash mode the user had before we forced the still-capture burst. Called once the
     * still has been captured/saved so the on-screen flash setting and the next preview are unaffected.
     */
    public void restoreFlashAfterStillCapture(CameraController controller) {
        if( controller == null ) {
            return;
        }
        if( flash_value_before_burst != null ) {
            if( MyDebug.LOG )
                Log.d(TAG, "restoreFlashAfterStillCapture, restoring: " + flash_value_before_burst);
            try {
                controller.setFlashValue(flash_value_before_burst);
            }
            catch(Exception e) {
                Log.e(TAG, "failed to restore flash value", e);
            }
            flash_value_before_burst = null;
        }
        if( preview != null ) {
            preview.unpinFlashForLivePhotoStill();
        }
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
            Log.e(TAG, "failed to create buffer file", e);
            return null;
        }
    }
}
