package com.renzmc.rencam.livephoto;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
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
 * <p><b>How it works (Apple Live Photo model):</b> while the camera is open in photo mode with Live
 * Photo enabled, a short <b>video buffer is recorded continuously in the background</b> - it does
 * <i>not</i> wait for the shutter. When the shutter is pressed the app:</p>
 * <ol>
 *     <li>notes how far into the buffer the shutter happened,</li>
 *     <li>fires a short <b>flash burst</b> (LED torch, or a bright screen for the front camera) - the
 *         flash is only on for a fraction of a second around the still, it is <b>not</b> a continuous
 *         torch for the whole clip,</li>
 *     <li>keeps buffering for {@link #POST_MS} (1.5s) after the shutter,</li>
 *     <li>stops the buffer and cuts the video down to the {@code [shutter-1.5s, shutter+1.5s]} window
 *         (3 seconds total),</li>
 *     <li>extracts the cover frame at the shutter timestamp and saves it through the normal image
 *         pipeline (MediaStore / SAF / file),</li>
 *     <li>packages the saved cover + the trimmed MP4 into a single Motion Photo (JPEG with embedded
 *         MP4 + XMP) using {@link LivePhotoHelper}, setting
 *         {@code GCamera:MotionPhotoPresentationTimestampUs} to the cover frame's position inside the
 *         clip.</li>
 * </ol>
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

    /** Live Photo timing, fixed to the classic Apple layout: 1.5s before + 1.5s after the shutter. */
    public static final int PRE_MS = 1500;
    public static final int POST_MS = 1500;
    public static final int TOTAL_MS = PRE_MS + POST_MS; // 3000

    /** How long the flash burst stays on (a fraction of a second - never the whole clip). */
    private static final int FLASH_BURST_MS = 700;
    /**
     * How long after the shutter the cover frame is taken. The LED needs a moment to reach full
     * brightness, so sampling slightly after the shutter gives a properly lit key photo.
     */
    private static final int COVER_DELAY_MS = 250;
    /**
     * Upper bound on the continuously running buffer. When it is reached the buffer is stopped and
     * restarted, so it can't grow without limit while the camera sits idle.
     */
    private static final int BUFFER_MAX_MS = 30000;

    private final Context context;
    private final SharedPreferences sharedPreferences;
    private Preview preview;
    private LivePhotoHost host;
    private final Handler handler = new Handler(Looper.getMainLooper());

    // ---- Continuous buffer state -----------------------------------------------------------------
    /** True while the background video buffer is recording. */
    private volatile boolean buffer_running;
    /** {@link SystemClock#elapsedRealtime()} at which the current buffer started recording. */
    private volatile long buffer_start_ms;
    /** The temp file the current buffer is being recorded to. */
    private volatile File buffer_file;
    /** Restarts the buffer when {@link #BUFFER_MAX_MS} is reached. */
    private Runnable buffer_cap_runnable;

    // ---- Capture state ---------------------------------------------------------------------------
    /** True between the shutter press and the end of the post-roll. */
    private volatile boolean capturing;
    /** True once the video has been recorded and we are waiting for the cover still to be saved. */
    private volatile boolean waiting_for_cover;
    /** The recorded (already trimmed) video file waiting to be packaged into the saved cover still. */
    private File pending_video_file;
    /** The presentation timestamp (microseconds) of the cover frame within the recorded video. */
    private long pending_presentation_us;
    /** How far into the buffer the shutter was pressed (ms). */
    private long shutter_offset_ms;

    private Runnable post_roll_runnable;
    private Runnable flash_off_runnable;

    // ---- Flash state -----------------------------------------------------------------------------
    /** The flash value that was active before we forced a torch, so we can restore it afterwards. */
    private String flash_value_before_capture = null;
    /** True if we turned on the front-screen flash (bright white screen) for the current burst. */
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

    /** Flash behaviour: "on" (use the light) or "off". */
    public String getFlashBehavior() {
        return sharedPreferences.getString(PreferenceKeys.LivePhotoFlashBehaviorPreferenceKey, "on");
    }

    /** Whether the recorded video should record audio. */
    public boolean getRecordAudio() {
        return sharedPreferences.getBoolean(PreferenceKeys.LivePhotoAudioPreferenceKey, false);
    }

    // ---------------------------------------------------------------------------------------------
    // Continuous buffer lifecycle
    // ---------------------------------------------------------------------------------------------

    /**
     * Starts the continuous background video buffer. Called when the camera preview starts (and after
     * the camera is reconnected following a capture). Does nothing unless Live Photo is active, the
     * camera is open in photo mode and no capture is in progress.
     */
    public synchronized void onPreviewStarted() {
        if( MyDebug.LOG )
            Log.d(TAG, "onPreviewStarted");
        startBuffer();
    }

    /** Stops the continuous background video buffer (e.g. the preview is stopping). */
    public synchronized void onPreviewStopped() {
        if( MyDebug.LOG )
            Log.d(TAG, "onPreviewStopped");
        cancelBufferCap();
        if( buffer_running ) {
            // The preview is stopping, so don't reconnect the camera here.
            File file = preview != null ? preview.stopLivePhotoBuffer(false) : null;
            deleteQuietly(file);
            buffer_running = false;
            buffer_file = null;
        }
    }

    /** Starts the background buffer if it isn't already running. */
    private synchronized boolean startBuffer() {
        if( MyDebug.LOG )
            Log.d(TAG, "startBuffer");
        if( !isActive() )
            return false;
        if( buffer_running || capturing )
            return false;
        if( preview == null || preview.getCameraController() == null )
            return false;
        if( preview.isVideo() || !preview.isPreviewStarted() )
            return false;
        File file = createBufferFile();
        if( file == null )
            return false;
        boolean started = preview.startLivePhotoBuffer(file, getRecordAudio());
        if( !started ) {
            if( MyDebug.LOG )
                Log.d(TAG, "failed to start live photo buffer");
            deleteQuietly(file);
            return false;
        }
        buffer_file = file;
        buffer_start_ms = SystemClock.elapsedRealtime();
        buffer_running = true;
        scheduleBufferCap();
        if( MyDebug.LOG )
            Log.d(TAG, "live photo buffer started");
        return true;
    }

    /**
     * Restarts the buffer shortly after a capture. The camera is reconnected when the buffer is
     * stopped, which can take a moment, so we retry a few times if the camera isn't ready yet.
     */
    private void scheduleBufferRestart(final int attempts_left) {
        if( !isActive() || capturing ) {
            return;
        }
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if( !isActive() || capturing ) {
                    return;
                }
                if( startBuffer() ) {
                    return;
                }
                if( attempts_left > 1 ) {
                    if( MyDebug.LOG )
                        Log.d(TAG, "buffer not ready yet - retrying (" + attempts_left + ")");
                    scheduleBufferRestart(attempts_left - 1);
                }
            }
        }, 400L);
    }

    /**
     * The buffer is capped at {@link #BUFFER_MAX_MS}; when the cap is reached (and we're not in the
     * middle of a capture) the buffer is restarted so it can't grow without limit.
     */
    private void scheduleBufferCap() {
        cancelBufferCap();
        buffer_cap_runnable = new Runnable() {
            @Override
            public void run() {
                buffer_cap_runnable = null;
                synchronized( LivePhotoManager.this ) {
                    if( !buffer_running || capturing ) {
                        return;
                    }
                    if( MyDebug.LOG )
                        Log.d(TAG, "live photo buffer reached cap - restarting");
                    File file = preview != null ? preview.stopLivePhotoBuffer() : null;
                    deleteQuietly(file);
                    buffer_running = false;
                    buffer_file = null;
                }
                // stopLivePhotoBuffer() reconnects the camera, which triggers onPreviewStarted() and
                // therefore restarts the buffer automatically.
            }
        };
        handler.postDelayed(buffer_cap_runnable, BUFFER_MAX_MS);
    }

    private void cancelBufferCap() {
        if( buffer_cap_runnable != null ) {
            handler.removeCallbacks(buffer_cap_runnable);
            buffer_cap_runnable = null;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Capture lifecycle
    // ---------------------------------------------------------------------------------------------

    /**
     * Called when the shutter is pressed in photo mode. Notes how far into the continuous buffer the
     * shutter happened, fires the flash burst and schedules the end of the post-roll. Must be called
     * on the UI thread.
     *
     * @return true if the caller should SKIP the normal still capture - either because a Live Photo
     *         capture was just started, or because a Live Photo is already in progress (in which case
     *         the shutter press is swallowed so it can't be mistaken for a normal still).
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

        // The buffer should already be running (started when the preview started). If for some reason
        // it isn't, start one now - the clip will simply have less (or no) motion before the shutter.
        if( !buffer_running ) {
            if( MyDebug.LOG )
                Log.d(TAG, "buffer not running - starting one now");
            startBuffer();
        }
        shutter_offset_ms = buffer_running ? (SystemClock.elapsedRealtime() - buffer_start_ms) : 0L;
        if( MyDebug.LOG )
            Log.d(TAG, "shutter_offset_ms: " + shutter_offset_ms);

        // Flash: a short burst only around the still (never a continuous torch for the whole clip).
        boolean front = host != null && host.isFrontFacing();
        startFlashBurst(front);

        capturing = true;
        waiting_for_cover = false;

        // Update the preview state: hide the GUI and show the "taking photo" indicator during the
        // post-roll (the shutter was already put into PHASE_TAKING_PHOTO by takePicture()).
        if( preview != null ) {
            preview.onLivePhotoCaptureStarted();
        }
        if( host != null ) {
            host.showToast(R.string.live_photo_hold_steady);
        }

        // Schedule the end of the flash burst and the end of the post-roll.
        flash_off_runnable = new Runnable() {
            @Override
            public void run() {
                flash_off_runnable = null;
                stopFlashBurst();
            }
        };
        handler.postDelayed(flash_off_runnable, FLASH_BURST_MS);

        post_roll_runnable = new Runnable() {
            @Override
            public void run() {
                onPostRollElapsed();
            }
        };
        handler.postDelayed(post_roll_runnable, POST_MS);
        return true;
    }

    /** Called once the post-roll has elapsed: stop the buffer, trim it and extract the cover frame. */
    private synchronized void onPostRollElapsed() {
        if( MyDebug.LOG )
            Log.d(TAG, "onPostRollElapsed");
        post_roll_runnable = null;
        if( !capturing ) {
            return;
        }
        capturing = false;

        // Make sure the flash is off before we stop.
        if( flash_off_runnable != null ) {
            handler.removeCallbacks(flash_off_runnable);
            flash_off_runnable = null;
        }
        stopFlashBurst();

        final long offset_ms = shutter_offset_ms;
        // Stop the buffer (this reconnects the camera and restarts the normal preview).
        final File video_file = preview != null ? preview.stopLivePhotoBuffer(true) : null;
        buffer_running = false;
        buffer_file = null;

        if( preview != null ) {
            preview.onLivePhotoCaptureFinished();
        }

        // Restart the buffer for the next shot (once the camera has settled).
        scheduleBufferRestart(5);

        if( video_file == null || !video_file.exists() || video_file.length() < 100 ) {
            Log.e(TAG, "live photo recording produced no usable video");
            deleteQuietly(video_file);
            return;
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
                processCapturedVideo(video_file, offset_ms);
            }
        }, "LivePhotoProcessor").start();
    }

    /**
     * Trims the recorded buffer down to the {@code [shutter-1.5s, shutter+1.5s]} window, extracts the
     * cover frame at the shutter timestamp, and hands it to the host to be saved.
     */
    private void processCapturedVideo(File video_file, long offset_ms) {
        long duration = LivePhotoHelper.getVideoDuration(context, Uri.fromFile(video_file));
        if( duration <= 0 ) {
            // Fall back to the elapsed buffer time if the container didn't report a duration.
            duration = offset_ms + POST_MS;
        }

        long window_start = Math.max(0L, offset_ms - PRE_MS);
        long window_end = Math.min(duration, offset_ms + POST_MS);
        if( window_end <= window_start ) {
            window_end = Math.min(duration, window_start + TOTAL_MS);
        }

        File trimmed_file = new File(context.getCacheDir(),
                "rencam_live_trim_" + System.currentTimeMillis() + ".mp4");
        boolean trimmed = LivePhotoHelper.trimVideo(context, video_file, trimmed_file, window_start, window_end);
        File source = (trimmed && trimmed_file.exists() && trimmed_file.length() > 100) ? trimmed_file : video_file;
        if( source == video_file ) {
            // Trimming failed - use the whole clip, so the cover offset must be relative to its start.
            window_start = 0L;
        }

        // The cover frame is taken at the shutter moment (plus a small delay so the flash has lit it).
        long cover_ms = (offset_ms - window_start) + COVER_DELAY_MS;
        long cover_duration = LivePhotoHelper.getVideoDuration(context, Uri.fromFile(source));
        if( cover_duration > 0 && cover_ms >= cover_duration ) {
            cover_ms = Math.max(0L, cover_duration - 50L);
        }
        if( cover_ms < 0 ) {
            cover_ms = 0L;
        }

        final byte[] cover = extractCoverJpeg(source, cover_ms);
        deleteQuietly(trimmed_file);
        if( cover == null ) {
            Log.e(TAG, "failed to extract cover frame from live photo video");
            deleteQuietly(video_file);
            return;
        }

        // Register the pending video *before* saving, so that when the still is saved (possibly
        // synchronously) the onStillSaved() callback finds it.
        synchronized( LivePhotoManager.this ) {
            pending_video_file = source;
            pending_presentation_us = cover_ms * 1000L;
            waiting_for_cover = true;
        }
        if( source != video_file ) {
            // The trimmed file is the one we keep; the raw buffer can go.
            deleteQuietly(video_file);
        }

        // Save the cover on the main thread, as saveImage() touches UI-related state.
        handler.post(new Runnable() {
            @Override
            public void run() {
                boolean ok = host != null && host.saveLivePhotoCover(cover, new Date());
                if( !ok ) {
                    Log.e(TAG, "failed to save live photo cover frame");
                    File to_delete;
                    synchronized( LivePhotoManager.this ) {
                        waiting_for_cover = false;
                        to_delete = pending_video_file;
                        pending_video_file = null;
                    }
                    deleteQuietly(to_delete);
                }
            }
        });
    }

    /** Extracts a single frame from the recorded video and encodes it as JPEG bytes. */
    private byte[] extractCoverJpeg(File video_file, long offset_ms) {
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
        if( post_roll_runnable != null ) {
            handler.removeCallbacks(post_roll_runnable);
            post_roll_runnable = null;
        }
        if( flash_off_runnable != null ) {
            handler.removeCallbacks(flash_off_runnable);
            flash_off_runnable = null;
        }
        cancelBufferCap();
        capturing = false;
        waiting_for_cover = false;
        pending_video_file = null;
        stopFlashBurst();
        if( preview != null && preview.isLivePhotoBuffering() ) {
            File file = preview.stopLivePhotoBuffer();
            deleteQuietly(file);
        }
        buffer_running = false;
        buffer_file = null;
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
     * Fires a short flash burst for the still. For the front camera without an LED this lights the
     * screen bright white; otherwise the LED is turned on as a torch. In both cases the flash is only
     * on for {@link #FLASH_BURST_MS} - it is <b>not</b> left on for the whole clip.
     */
    private void startFlashBurst(boolean front) {
        String behavior = getFlashBehavior();
        if( "off".equals(behavior) ) {
            return;
        }
        CameraController controller = preview != null ? preview.getCameraController() : null;
        String current = controller != null ? controller.getFlashValue() : null; // "" if unsupported
        boolean has_led = current != null && current.length() > 0;

        if( front && !has_led ) {
            // The front camera has no LED flash, so we use the "front screen flash": the screen is
            // lit up bright white (with a glow around the edges) for the burst.
            if( host != null ) {
                if( MyDebug.LOG )
                    Log.d(TAG, "front screen flash burst on");
                host.turnFrontScreenFlashOn();
                used_screen_flash = true;
            }
            return;
        }

        if( controller != null && has_led ) {
            if( flash_value_before_capture == null ) {
                flash_value_before_capture = current;
            }
            if( MyDebug.LOG )
                Log.d(TAG, "LED flash burst on");
            try {
                controller.setFlashValue("flash_torch");
            }
            catch(Exception e) {
                Log.e(TAG, "failed to turn on flash burst", e);
            }
        }
    }

    /**
     * Turns the flash burst off and restores the flash mode the user had before. Called once the
     * burst duration has elapsed (or when aborting).
     */
    private void stopFlashBurst() {
        if( used_screen_flash && host != null ) {
            host.turnFrontScreenFlashOff();
            used_screen_flash = false;
        }
        CameraController controller = preview != null ? preview.getCameraController() : null;
        if( controller != null && flash_value_before_capture != null ) {
            if( MyDebug.LOG )
                Log.d(TAG, "stopFlashBurst, restoring: " + flash_value_before_capture);
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
