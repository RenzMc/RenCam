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
import java.util.ArrayList;
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
 * lives in {@link Preview} ({@code startLivePhotoBuffer()} / {@code stopLivePhotoBuffer()}), the cover
 * image is saved through the {@link LivePhotoHost} callbacks, and the saved still is signalled
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

    /**
     * Three-pulse iPhone-style flash approximation, measured in milliseconds after the shutter.
     *
     * <p>Apple publicly documents a pre-flash sequence followed by a strobe-synchronised capture,
     * but does not publish the exact pulse timings used by the Camera app. This sequence therefore
     * reproduces the visible three-beat rhythm while keeping the final pulse as the capture flash.</p>
     *
     * <p>The first two pulses act as a short pre-flash sequence. The third pulse is the longer,
     * brightest capture flash and the cover frame is sampled inside it.</p>
     */
    private static final int FLASH_PRE_1_START_MS = 90;
    private static final int FLASH_PRE_1_END_MS = 140;

    private static final int FLASH_PRE_2_START_MS = 185;
    private static final int FLASH_PRE_2_END_MS = 225;

    private static final int FLASH_CAPTURE_START_MS = 280;
    private static final int FLASH_CAPTURE_END_MS = 410;

    /**
     * How long after the shutter the cover frame is taken. This is inside the final capture-flash
     * window so the still is sampled during the brightest pulse.
     */
    private static final int COVER_DELAY_MS = 340;

    /**
     * Screen-flash brightness (alpha 0..255) for the front camera.
     * The LED on the rear camera is hardware-controlled and therefore ignores these alpha values.
     */
    private static final int FLASH_PRE_1_ALPHA = 90;
    private static final int FLASH_PRE_2_ALPHA = 165;
    private static final int FLASH_CAPTURE_ALPHA = 255;

    /**
     * Extra recording time kept after the post-roll before the buffer is stopped. MediaRecorder can
     * drop the last few frames when it is stopped, so we record a little longer than the 3s window
     * and then trim back to exactly 3s - this guarantees the packaged clip is never shorter than 3s.
     */
    private static final int RECORD_MARGIN_MS = 400;

    /**
     * Upper bound on the continuously running buffer. When it is reached the buffer is stopped and
     * restarted, so it can't grow without limit while the camera sits idle.
     */
    private static final int BUFFER_MAX_MS = 30000;

    /**
     * Maximum number of shutter presses that can be queued while a Live Photo is still finishing.
     * Each queued press is captured as soon as the buffer is ready again, so no photo is ever lost.
     */
    private static final int MAX_PENDING_CAPTURES = 20;

    private final Context context;
    private final SharedPreferences sharedPreferences;
    private Preview preview;
    private LivePhotoHost host;
    private final Handler handler = new Handler(Looper.getMainLooper());

    /**
     * RenCam: a dedicated single-thread executor that runs <b>all</b> Live Photo finalization
     * (trimming the buffer, extracting the cover frame, saving the still and packaging the Motion
     * Photo) in the background. Running the whole pipeline here - instead of on the main thread or on
     * ad-hoc threads - means the processing never blocks the UI, is serialized (no two captures
     * trample each other) and keeps working even while the activity is paused or the preview is
     * reconnecting. It is a daemon thread so it can never keep the process alive.
     */
    private final java.util.concurrent.ExecutorService finalize_executor =
            java.util.concurrent.Executors.newSingleThreadExecutor(new java.util.concurrent.ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "LivePhotoFinalize");
                    t.setDaemon(true);
                    return t;
                }
            });

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
    /** How far into the buffer the shutter was pressed (ms). */
    private long shutter_offset_ms;

    /**
     * Shutter presses received while a Live Photo was still being recorded/processed. They are
     * captured as soon as the buffer is ready again, so rapid shots are never silently dropped.
     */
    private int pending_captures = 0;

    /**
     * Number of captures that have been started but not yet fully finished (recorded, processed,
     * saved and packaged). This is now used <b>only</b> to keep the foreground service alive while
     * there is work to do - it deliberately does <b>not</b> gate new captures any more. The user can
     * shoot as fast as they like; every capture is finalised one-by-one on {@link #finalize_executor}.
     */
    private int captures_in_flight = 0;

    /**
     * Holds the file/uri of the cover still that was just saved, for the finalisation thread.
     *
     * <p>The cover save is performed <b>synchronously on the finalisation thread</b> (see
     * {@code MyApplicationInterface.saveLivePhotoCover()}), so {@link #onStillSaved} can hand the
     * result straight back to the {@code finalizeCapture} call that triggered it - without any
     * shared "pending" slot that a second capture could clobber. That shared slot was the reason
     * the old design had to process captures strictly one at a time (and why rapid shots had to wait).
     * Using a {@link ThreadLocal} means a normal (non-Live-Photo) save happening on the image-saver
     * thread can never fill this by mistake.</p>
     */
    private static final class CoverResult {
        File file;
        Uri uri;
        boolean done;
    }

    private final ThreadLocal<CoverResult> cover_result = new ThreadLocal<>();

    private Runnable post_roll_runnable;
    /** Adaptive post-roll used for the current capture (ms after the shutter). */
    private long post_roll_ms = POST_MS;
    /** Pending flash-burst steps, so they can all be cancelled if the capture is aborted. */
    private final java.util.List<Runnable> flash_runnables = new java.util.ArrayList<>();

    // ---- Flash state -----------------------------------------------------------------------------
    /** The flash value that was active before we forced a torch, so we can restore it afterwards. */
    private String flash_value_before_capture = null;
    /** True if we turned on the front-screen flash (bright white screen) for the current burst. */
    private boolean used_screen_flash = false;
    /** True if the current burst uses the front screen instead of an LED. */
    private boolean flash_use_screen = false;
    /**
     * True if a flash burst actually fired for the current capture. When it did, the flash is used
     * as a visual sync mark so the still is always sampled from the lit frame (see
     * {@link #extractCoverJpegSynced}).
     */
    private boolean flash_fired = false;

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
        if( !startBuffer() ) {
            // The camera is sometimes not quite ready the instant the preview reports as started (and
            // the buffer can fail for other transient reasons too). Retry a few times so Live Photo is
            // ready by the time the user presses the shutter - this is what makes capture reliable.
            scheduleBufferRestart(8);
        }
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

    /**
     * Stops the buffer <b>and reconnects the camera</b> so the normal preview continues. Used when
     * Live Photo is switched off while the preview is still running (e.g. via the LIVE badge).
     */
    public synchronized void stopBufferAndReconnect() {
        if( MyDebug.LOG )
            Log.d(TAG, "stopBufferAndReconnect");
        cancelBufferCap();
        if( buffer_running ) {
            File file = preview != null ? preview.stopLivePhotoBuffer(true) : null;
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
        // If the user pressed the shutter while the previous Live Photo was still finishing, the
        // press is queued (see captureLivePhoto()); now that the buffer is running again, capture it.
        maybeStartPendingCapture();
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

        // If a capture is already being recorded, queue the press. The user can keep shooting as fast
        // as they like - queued presses are captured one-by-one as soon as the buffer is ready again
        // (see maybeStartPendingCapture()), and each is finalised in the background.
        if( capturing ) {
            queuePress();
            return true;
        }

        // The buffer is stopped briefly after every capture while the camera reconnects. If it isn't
        // running right now, try to (re)start it. If it can't start at all, fall back to a normal
        // still so the user never loses the shot.
        if( !buffer_running ) {
            if( MyDebug.LOG )
                Log.d(TAG, "buffer not running - starting one now");
            startBuffer();
            if( !buffer_running ) {
                // If a capture is still being finalised in the background, or a restart is pending,
                // queue the press rather than falling back - the buffer will come back shortly.
                if( captures_in_flight > 0 || pending_captures > 0 ) {
                    queuePress();
                    return true;
                }
                if( MyDebug.LOG )
                    Log.d(TAG, "buffer unavailable - falling back to a normal photo");
                if( host != null ) {
                    host.showToast(R.string.live_photo_buffer_unavailable);
                }
                return false;
            }
        }
        startCaptureInternal();
        return true;
    }

    /**
     * Queues a shutter press so it is captured once the buffer is ready again. This is what lets the
     * user shoot many Live Photos in a row without waiting: the press is remembered, and the actual
     * recording starts as soon as the previous capture has finished recording (the finalisation of
     * earlier photos keeps running in the background meanwhile).
     */
    private void queuePress() {
        if( pending_captures < MAX_PENDING_CAPTURES ) {
            pending_captures++;
            if( MyDebug.LOG )
                Log.d(TAG, "live photo in progress - queued shutter press, pending=" + pending_captures);
            if( host != null ) {
                host.showToast(R.string.live_photo_queued);
            }
        }
        // If the buffer is running and nothing is being recorded, start the queued capture right away.
        maybeStartPendingCapture();
    }

    /**
     * Starts a Live Photo capture using the already-running buffer. Separated from
     * {@link #captureLivePhoto()} so a queued press (or the pending-capture drain) can start a
     * capture without re-running the "is a capture already in progress" checks.
     */
    private synchronized void startCaptureInternal() {
        if( capturing || !buffer_running ) {
            return;
        }
        shutter_offset_ms = SystemClock.elapsedRealtime() - buffer_start_ms;
        if( MyDebug.LOG )
            Log.d(TAG, "shutter_offset_ms: " + shutter_offset_ms);

        // This capture is now in flight; it is released once the still has been saved and the Motion
        // Photo packaged (or on failure). captures_in_flight only keeps the foreground service alive
        // while there is work to do - it does NOT block new captures, so the user can keep shooting
        // while this one is finalised in the background.
        captures_in_flight++;
        // Ask Android to keep the process alive while we record + finalise (foreground service).
        updateForegroundService();

        // Reset any leftover flash state from a previous capture (e.g. after switching cameras), so
        // the flash can never be left "stuck" and always fires for this shot.
        flash_value_before_capture = null;
        used_screen_flash = false;
        flash_use_screen = false;
        flash_fired = false;

        // Flash: a short three-pulse approximation of the visible Apple-style pre-flash/capture rhythm.
        boolean front = host != null && host.isFrontFacing();
        startFlashBurst(front);

        capturing = true;

        // Update the preview state: hide the GUI and show the "taking photo" indicator during the
        // post-roll (the shutter was already put into PHASE_TAKING_PHOTO by takePicture()).
        if( preview != null ) {
            preview.onLivePhotoCaptureStarted();
        }
        if( host != null ) {
            host.showToast(R.string.live_photo_hold_steady);
        }

        // Adaptive post-roll: keep the total clip at exactly 3s even when the shutter is pressed
        // very soon after the buffer (re)starts, so the Live Photo is never too short.
        post_roll_ms = Math.min(TOTAL_MS, POST_MS + Math.max(0L, PRE_MS - shutter_offset_ms));
        if( MyDebug.LOG )
            Log.d(TAG, "post_roll_ms: " + post_roll_ms);

        post_roll_runnable = new Runnable() {
            @Override
            public void run() {
                onPostRollElapsed();
            }
        };
        // Record a little past the trim window (see RECORD_MARGIN_MS) so the last frames are flushed
        // and the trimmed clip still reaches the full 3 seconds.
        handler.postDelayed(post_roll_runnable, post_roll_ms + RECORD_MARGIN_MS);
    }

    /**
     * If a shutter press was queued while a Live Photo was finishing, and the buffer is now running
     * again, capture it. Runs on the UI thread and re-checks the state so it is safe to call from any
     * point where the buffer may have just (re)started.
     */
    private void maybeStartPendingCapture() {
        handler.post(new Runnable() {
            @Override
            public void run() {
                boolean start = false;
                synchronized( LivePhotoManager.this ) {
                    if( pending_captures > 0 && !capturing
                            && buffer_running && isActive() && preview != null && !preview.isVideo()
                            && preview.isPreviewStarted() ) {
                        pending_captures--;
                        start = true;
                    }
                }
                if( start ) {
                    if( MyDebug.LOG )
                        Log.d(TAG, "starting queued Live Photo capture");
                    startCaptureInternal();
                }
            }
        });
    }

    /**
     * Marks the current capture as finished (success <i>or</i> failure) and releases the in-flight
     * slot, so the next queued shutter press can start. Also refreshes the foreground service: it is
     * stopped as soon as no capture is in flight, so the notification never lingers.
     *
     * <p>This is the counterpart to the {@code captures_in_flight++} in
     * {@link #startCaptureInternal()}. Every code path that finishes a capture <b>must</b> call it
     * exactly once - if a path forgot to, the queue would stall forever and no new Live Photo could
     * ever start. The calls are therefore placed on every early-return and in {@code finally} blocks.</p>
     */
    private synchronized void releaseCapture() {
        if( captures_in_flight > 0 ) {
            captures_in_flight--;
        }
        if( MyDebug.LOG )
            Log.d(TAG, "releaseCapture, captures_in_flight=" + captures_in_flight);
        updateForegroundService();
        // RenCam round 6: if the user queued more shutter presses while this capture was finishing,
        // make sure the buffer is running again so they actually get captured - the buffer may still
        // be restarting after the last shot (and without this the queue could sit idle until the next
        // manual shutter press). scheduleBufferRestart() is safe to call from any thread: it just
        // posts a retrying start to the main handler and re-checks the state.
        if( pending_captures > 0 && captures_in_flight == 0 && !capturing && !buffer_running ) {
            scheduleBufferRestart(8);
        }
        // The buffer is usually running again by now; start any press the user queued meanwhile.
        maybeStartPendingCapture();
    }

    /**
     * Starts the foreground service while a Live Photo is being finalised and stops it once there is
     * nothing left to do. This is what lets the (background) finalisation keep running when the user
     * leaves the app - without it Android could kill the process mid-packaging and leave the still as
     * a plain JPEG (the "sometimes it isn't a Live Photo" bug).
     */
    private void updateForegroundService() {
        boolean active;
        synchronized( this ) {
            active = captures_in_flight > 0;
        }
        if( active ) {
            LivePhotoService.start(context);
        }
        else {
            LivePhotoService.stop(context);
        }
    }

    /** Called once the post-roll has elapsed: stop the buffer, trim it and extract the cover frame. */
    private synchronized void onPostRollElapsed() {
        onPostRollElapsed(true);
    }

    /**
     * Called once the post-roll has elapsed (or when the camera is closing mid-capture): stop the
     * buffer, trim it and extract the cover frame.
     *
     * @param reconnect if true the camera is reconnected and the buffer restarted for the next shot
     *                  (the normal end-of-capture path); if false the buffer is stopped without
     *                  reconnecting, because the camera is being released anyway (the camera-closing
     *                  path - see {@link #onCameraClosing()}).
     */
    private synchronized void onPostRollElapsed(boolean reconnect) {
        if( MyDebug.LOG )
            Log.d(TAG, "onPostRollElapsed: reconnect=" + reconnect);
        post_roll_runnable = null;
        if( !capturing ) {
            return;
        }
        capturing = false;

        // Make sure the flash is off before we stop.
        cancelFlashSteps();
        stopFlashBurst();

        final long offset_ms = shutter_offset_ms;
        final long post_roll = post_roll_ms;
        final boolean flash_used = flash_fired;
        // Stop the buffer. On the normal path this reconnects the camera and restarts the preview; on
        // the camera-closing path it does not (the camera is going away), but the recorded file is
        // still complete on disk and can be finalised in the background.
        final File video_file = preview != null ? preview.stopLivePhotoBuffer(reconnect) : null;
        buffer_running = false;
        buffer_file = null;

        if( preview != null ) {
            preview.onLivePhotoCaptureFinished();
        }

        // Restart the buffer for the next shot (once the camera has settled) - but only on the normal
        // path; while the camera is closing there is nothing to restart.
        if( reconnect ) {
            scheduleBufferRestart(5);
        }

        if( video_file == null || !video_file.exists() || video_file.length() < 100 ) {
            Log.e(TAG, "live photo recording produced no usable video");
            deleteQuietly(video_file);
            // The capture can't be finalised - release the in-flight slot so the queue can continue.
            releaseCapture();
            return;
        }

        // Run the whole finalization pipeline on the dedicated background executor (see
        // finalize_executor) so it never blocks the UI and keeps working while the activity is paused.
        // Because the executor is single-threaded, captures are finalised strictly one at a time -
        // even though the user may have queued several shots in the meantime.
        finalize_executor.execute(new Runnable() {
            @Override
            public void run() {
                finalizeCapture(video_file, offset_ms, post_roll, flash_used);
            }
        });
    }

    /**
     * Trims the recorded buffer down to the {@code [shutter-1.5s, shutter+1.5s]} window, extracts the
     * cover frame at the shutter timestamp, saves it through the normal image pipeline and packages
     * the video into it - all as one <b>self-contained</b> unit of work.
     *
     * <p>This runs on the single-threaded {@link #finalize_executor}, so captures are finalised
     * strictly one at a time. Because the whole pipeline (including the cover save, which is made
     * synchronous for Live Photos - see {@code MyApplicationInterface.saveLivePhotoCover()}) happens
     * inside this one method, there is no shared "pending" state that a second capture could clobber.
     * That is exactly what lets the user shoot many Live Photos in a row while they are processed
     * one-by-one in the background.</p>
     */
    private void finalizeCapture(File video_file, long offset_ms, long post_roll_ms, boolean flash_used) {
        File source = null;
        try {
            long duration = LivePhotoHelper.getVideoDuration(context, Uri.fromFile(video_file));
            if( duration <= 0 ) {
                // Fall back to the elapsed buffer time if the container didn't report a duration.
                duration = offset_ms + post_roll_ms;
            }

            long window_start = Math.max(0L, offset_ms - PRE_MS);
            long window_end = Math.min(duration, offset_ms + post_roll_ms);
            if( window_end <= window_start ) {
                window_end = Math.min(duration, window_start + TOTAL_MS);
            }

            File trimmed_file = new File(context.getCacheDir(),
                    "rencam_live_trim_" + System.currentTimeMillis() + ".mp4");
            LivePhotoHelper.TrimResult trim = LivePhotoHelper.trimVideo(
                    context,
                    video_file,
                    trimmed_file,
                    window_start,
                    window_end
            );

            boolean trimmed = trim.success
                    && trimmed_file.exists()
                    && trimmed_file.length() > 100;

            source = trimmed ? trimmed_file : video_file;

            // The source timestamp that corresponds to time 0 in `source`. Trimming seeks to the
            // nearest keyframe (usually a little before window_start), so the trimmed clip's timeline
            // is offset by this amount - without it the still would land at a random point instead of
            // on the flash.
            long source_start_ms = trimmed ? trim.startMs : 0L;

            if( trimmed ) {
                // Trimming succeeded - the trimmed file is the one we keep, so the raw buffer can go.
                deleteQuietly(video_file);
            }
            else {
                // Trimming failed - use the whole clip, and discard the (empty/partial) trim output.
                deleteQuietly(trimmed_file);
            }

            // The cover frame is taken at the shutter moment plus the capture-flash delay, mapped
            // onto the trimmed clip's timeline using the actual trim start.
            long cover_ms = (offset_ms + COVER_DELAY_MS) - source_start_ms;

            long cover_duration =
                    LivePhotoHelper.getVideoDuration(context, Uri.fromFile(source));

            if( cover_duration > 0 && cover_ms >= cover_duration ) {
                cover_ms = Math.max(0L, cover_duration - 50L);
            }

            if( cover_ms < 0 ) {
                cover_ms = 0L;
            }

            final byte[] cover =
                    extractCoverJpegSynced(
                            source,
                            cover_ms,
                            getTargetStillAspectRatio(),
                            flash_used
                    );

            if( cover == null ) {
                Log.e(TAG, "failed to extract cover frame from live photo video");
                return;
            }

            // Save the cover synchronously on this (background) thread and capture the resulting
            // file/uri straight back, so the packaging below is fully self-contained.
            // The save is made synchronous by MyApplicationInterface.saveLivePhotoCover() (it forces
            // do_in_background=false), which allows onStillSaved() to fill `result` before
            // saveLivePhotoCover() returns.
            CoverResult result = new CoverResult();
            cover_result.set(result);

            boolean saved;
            try {
                saved = host != null && host.saveLivePhotoCover(cover, new Date());
            }
            finally {
                cover_result.remove();
            }

            if( !saved || !result.done ) {
                Log.e(TAG, "failed to save live photo cover frame");
                return;
            }

            // Package the (trimmed) video into the saved still, turning it into a Motion Photo. The
            // embedded video is cropped to the target aspect ratio (9:16) so it matches the cover.
            File video_to_package = source;
            File cropped_file = null;

            try {
                double target_ratio = getTargetVideoAspectRatio();

                if( target_ratio > 0.0 && source.exists() ) {
                    cropped_file = new File(
                            context.getCacheDir(),
                            "rencam_live_crop_" + System.currentTimeMillis() + ".mp4"
                    );

                    if( VideoCropper.cropToAspectRatio(
                            source,
                            cropped_file,
                            target_ratio
                    ) ) {
                        video_to_package = cropped_file;
                    }
                    else {
                        deleteQuietly(cropped_file);
                        cropped_file = null;
                    }
                }

                long presentation_us = cover_ms * 1000L;

                boolean ok = packageStill(
                        result.file,
                        result.uri,
                        video_to_package,
                        presentation_us
                );

                // If the (cropped) video failed to package, retry with the original video: a crop
                // problem must never cost the user their Live Photo (it would stay a plain JPEG).
                if( !ok && cropped_file != null && source.exists() ) {
                    if( MyDebug.LOG )
                        Log.d(TAG,
                                "packaging with cropped video failed - retrying with original");
                    ok = packageStill(
                            result.file,
                            result.uri,
                            source,
                            presentation_us
                    );
                }

                if( !ok ) {
                    Log.e(TAG,
                            "failed to package live photo after retry - still left as plain JPEG");
                }
            }
            finally {
                if( cropped_file != null ) {
                    deleteQuietly(cropped_file);
                }
            }
        }
        catch(Exception e) {
            Log.e(TAG, "failed to finalise live photo", e);
        }
        finally {
            // The (trimmed) source video is no longer needed once packaging has finished.
            deleteQuietly(source);

            // This capture is now completely finished (success or failure), so let the next queued
            // shutter press start.
            releaseCapture();
        }
    }

    /** Extracts a single frame from the recorded video and encodes it as JPEG bytes. */
    private byte[] extractCoverJpeg(
            File video_file,
            long offset_ms,
            double target_aspect_ratio
    ) {
        Bitmap bitmap =
                LivePhotoHelper.extractVideoFrame(
                        context,
                        Uri.fromFile(video_file),
                        offset_ms
                );

        if( bitmap == null ) {
            return null;
        }

        try {
            // Match the still to the photo aspect ratio (e.g. 4:3 / 16:9) so the saved cover has the
            // same framing as a normal photo - the video buffer may be a wider 16:9 crop.
            bitmap = cropToAspect(bitmap, target_aspect_ratio);

            ByteArrayOutputStream bos = new ByteArrayOutputStream();

            // RenCam Live Photo "super HD": encode the extracted cover at the maximum JPEG quality so
            // there is no visible generation loss before the still goes through the image pipeline
            // (which re-encodes it once more when it crops to 9:16). The cover is taken from a video
            // frame recorded at the highest resolution the device supports (see
            // Preview.getLivePhotoVideoProfile()), so this keeps the still as sharp as possible.
            bitmap.compress(Bitmap.CompressFormat.JPEG, 100, bos);

            return bos.toByteArray();
        }
        finally {
            bitmap.recycle();
        }
    }

    /**
     * Extracts the cover frame for a Live Photo, <b>synced to the final capture flash</b> when one fired.
     *
     * <p>The three visible pulses are arranged as two short pre-flash pulses followed by a longer
     * final capture pulse. The expected cover time is inside the final pulse, so the sync search is
     * restricted to that region and cannot normally select one of the earlier pre-flashes.</p>
     *
     * <p>When no flash fired (flash set to off, or a camera with no light) we simply use the expected
     * time, since there is no visual sync mark.</p>
     */
    private byte[] extractCoverJpegSynced(
            File video_file,
            long expected_ms,
            double target_aspect_ratio,
            boolean flash_used
    ) {
        long chosen_ms = expected_ms;

        if( flash_used ) {
            long flash_ms = findFlashFrameMs(video_file, expected_ms);

            if( flash_ms >= 0 ) {
                chosen_ms = flash_ms;

                if( MyDebug.LOG ) {
                    Log.d(
                            TAG,
                            "flash-synced cover: expected "
                                    + expected_ms
                                    + "ms, chose "
                                    + chosen_ms
                                    + "ms"
                    );
                }
            }
        }

        byte[] cover =
                extractCoverJpeg(
                        video_file,
                        chosen_ms,
                        target_aspect_ratio
                );

        if( cover == null ) {
            long[] fallbacks = {
                    chosen_ms - 50L,
                    chosen_ms + 50L,
                    chosen_ms - 100L,
                    chosen_ms + 100L,
                    chosen_ms - 200L,
                    chosen_ms + 200L,
                    0L
            };

            for( long t : fallbacks ) {
                if( t < 0L || t == chosen_ms ) {
                    continue;
                }

                cover =
                        extractCoverJpeg(
                                video_file,
                                t,
                                target_aspect_ratio
                        );

                if( cover != null ) {
                    if( MyDebug.LOG ) {
                        Log.d(
                                TAG,
                                "cover frame fallback succeeded at "
                                        + t
                                        + "ms (expected "
                                        + chosen_ms
                                        + "ms)"
                        );
                    }
                    break;
                }
            }
        }

        return cover;
    }

    /**
     * Finds the timestamp (ms) of the frame lit by the <b>final capture flash</b>.
     *
     * <p>The two short pre-flashes end before the sync-search region. The final pulse is the brightest
     * and longest pulse, so the brightest frame in this restricted region is used as the cover frame.
     * This keeps the cover synchronized with the third visible flash rather than one of the earlier
     * pre-flashes.</p>
     */
    private long findFlashFrameMs(File video_file, long expected_ms) {
        final long window_before = 80L;
        final long window_after = 100L;
        final long step = 25L;

        Uri uri = Uri.fromFile(video_file);

        long duration =
                LivePhotoHelper.getVideoDuration(
                        context,
                        uri
                );

        long start =
                Math.max(
                        0L,
                        expected_ms - window_before
                );

        long end =
                expected_ms + window_after;

        if( duration > 0 && end > duration - 1 ) {
            end = duration - 1;
        }

        if( end <= start ) {
            return -1L;
        }

        ArrayList<Long> times = new ArrayList<>();
        ArrayList<Double> brightnesses = new ArrayList<>();

        double max_brightness = -1.0;

        for( long t = start; t <= end; t += step ) {
            Bitmap frame =
                    LivePhotoHelper.extractVideoFrame(
                            context,
                            uri,
                            t
                    );

            if( frame == null ) {
                continue;
            }

            double brightness;

            try {
                brightness = averageBrightness(frame);
            }
            finally {
                frame.recycle();
            }

            times.add(t);
            brightnesses.add(brightness);

            if( brightness > max_brightness ) {
                max_brightness = brightness;
            }
        }

        if( times.isEmpty() ) {
            return -1L;
        }

        final double threshold = max_brightness * 0.92;

        long best_ms = -1L;
        long best_dist = Long.MAX_VALUE;

        for( int i = 0; i < times.size(); i++ ) {
            if( brightnesses.get(i) < threshold ) {
                continue;
            }

            long dist =
                    Math.abs(
                            times.get(i) - expected_ms
                    );

            if( dist < best_dist ) {
                best_dist = dist;
                best_ms = times.get(i);
            }
        }

        return best_ms;
    }

    /** Average luminance (0..255) of a bitmap, sampled on a coarse grid so it is cheap. */
    private double averageBrightness(Bitmap bitmap) {
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();

        if( w <= 0 || h <= 0 ) {
            return 0.0;
        }

        int step_x = Math.max(1, w / 32);
        int step_y = Math.max(1, h / 32);

        long sum = 0L;
        int count = 0;

        for( int y = 0; y < h; y += step_y ) {
            for( int x = 0; x < w; x += step_x ) {
                int c = bitmap.getPixel(x, y);

                int r = (c >> 16) & 0xFF;
                int g = (c >> 8) & 0xFF;
                int b = c & 0xFF;

                sum += (r + g + b) / 3;
                count++;
            }
        }

        return count > 0
                ? (double) sum / (double) count
                : 0.0;
    }

    /**
     * Centre-crops a bitmap to the given <b>landscape</b> width/height aspect ratio. If the bitmap is
     * portrait the ratio is inverted so the still keeps the same shape as the photo in the current
     * orientation. Returns the original bitmap if the ratio is unknown or already close enough.
     */
    private Bitmap cropToAspect(
            Bitmap bitmap,
            double landscape_ratio
    ) {
        if( bitmap == null || landscape_ratio <= 0.0 ) {
            return bitmap;
        }

        int w = bitmap.getWidth();
        int h = bitmap.getHeight();

        if( w <= 0 || h <= 0 ) {
            return bitmap;
        }

        double target_ratio =
                (h > w)
                        ? (1.0 / landscape_ratio)
                        : landscape_ratio;

        double current =
                (double) w / (double) h;

        if( Math.abs(current - target_ratio) < 0.02 ) {
            return bitmap;
        }

        int new_w = w;
        int new_h = h;

        if( current > target_ratio ) {
            // too wide - crop the width
            new_w =
                    (int) Math.round(
                            h * target_ratio
                    );
        }
        else {
            // too tall - crop the height
            new_h =
                    (int) Math.round(
                            w / target_ratio
                    );
        }

        new_w =
                Math.max(
                        1,
                        Math.min(
                                w,
                                new_w
                        )
                );

        new_h =
                Math.max(
                        1,
                        Math.min(
                                h,
                                new_h
                        )
                );

        int x = (w - new_w) / 2;
        int y = (h - new_h) / 2;

        try {
            Bitmap cropped =
                    Bitmap.createBitmap(
                            bitmap,
                            x,
                            y,
                            new_w,
                            new_h
                    );

            if( cropped != bitmap ) {
                bitmap.recycle();
            }

            return cropped;
        }
        catch(Exception e) {
            Log.e(TAG, "failed to crop cover frame", e);
            return bitmap;
        }
    }

    /**
     * The width/height aspect ratio the still photo should have (in landscape terms), taken from the
     * camera's current picture size. Returns 0 if it can't be determined (in which case the cover is
     * saved with the video's own aspect ratio).
     */
    private double getTargetStillAspectRatio() {
        // RenCam Live Photo: the cover still is ALWAYS 9:16 (see ImageSaver, which forces the crop to
        // 9:16 for a Live Photo cover). We therefore never crop the cover here - cropping it to the
        // camera's picture size ratio (e.g. 4:3) would make the still a different shape from the
        // 9:16 motion video (the "video 9:16 but photo 4:3" bug). Leaving it uncropped also means the
        // cover is only cropped once (in ImageSaver), so there is no quality loss from a double crop.
        return 0.0;
    }

    /**
     * The aspect ratio the embedded video should be cropped to. The cover image is cropped to the
     * photo aspect ratio, so the video is made to match it; if no photo ratio is set we fall back to
     * the dedicated video aspect ratio. Returns 0 when neither is set (no crop).
     */
    private double getTargetVideoAspectRatio() {
        // RenCam Live Photo: the embedded motion video is ALWAYS cropped to 9:16, so it matches the
        // 9:16 cover still. This guarantees a Live Photo is a consistent 9:16 no matter what photo /
        // video aspect ratio (or resolution) the user has selected - previously the video could be
        // 9:16 while the cover stayed 4:3, or the whole thing came out 16:9.
        return 9.0 / 16.0;
    }

    /** The camera id currently in use, or 0 if the preview isn't available. */
    private int getCameraId() {
        try {
            if( preview != null ) {
                return preview.getCameraId();
            }
        }
        catch(Exception e) {
            // ignore
        }

        return 0;
    }

    /**
     * Called once the cover still has been saved to disk. Because the Live Photo cover save is
     * synchronous (it runs on the finalisation thread inside {@link #finalizeCapture}), this hands
     * the saved file straight back to the {@code finalizeCapture} call that triggered it - via the
     * thread-local {@link #cover_result} - rather than through a shared "pending" slot. Normal
     * (non-Live-Photo) saves run on the image-saver thread, so their thread-local is null and they
     * are ignored here.
     */
    public void onStillSaved(File stillFile) {
        if( MyDebug.LOG )
            Log.d(
                    TAG,
                    "onStillSaved(file): "
                            + (stillFile != null
                            ? stillFile.getAbsolutePath()
                            : "null")
            );

        CoverResult result = cover_result.get();

        if( result == null ) {
            return;
        }

        result.file = stillFile;
        result.uri = null;
        result.done = true;
    }

    /**
     * Called once the cover still has been saved to a content {@link Uri} (MediaStore or SAF).
     */
    public void onStillSaved(Uri stillUri) {
        if( MyDebug.LOG )
            Log.d(TAG, "onStillSaved(uri): " + stillUri);

        CoverResult result = cover_result.get();

        if( result == null ) {
            return;
        }

        result.uri = stillUri;
        result.file = null;
        result.done = true;
    }

    /**
     * Called when the camera is being closed (the user left the app, the preview is being released,
     * the camera is being switched, etc.).
     *
     * <p>Unlike {@link #abort()}, this does <b>not</b> throw away an in-flight capture. This is what
     * makes Live Photo processing genuinely run in the background: if a Live Photo is still being
     * recorded its buffer is stopped and the capture is finalised on the background executor, and if
     * one is already being finalised it is simply left to finish. The foreground service is kept
     * alive until every capture has been packaged, so closing RenCam never loses a Live Photo - the
     * trimming, cover extraction and Motion Photo packaging all continue after the app is gone.</p>
     */
    public synchronized void onCameraClosing() {
        if( MyDebug.LOG )
            Log.d(TAG, "onCameraClosing");

        // No more captures can be started once the camera is closing, so drop any queued presses.
        pending_captures = 0;
        cancelBufferCap();

        if( capturing ) {
            // A capture is still being recorded: stop the buffer (without reconnecting - the camera is
            // going away) and finalise it.
            if( post_roll_runnable != null ) {
                handler.removeCallbacks(post_roll_runnable);
                post_roll_runnable = null;
            }

            onPostRollElapsed(false);
        }
        else {
            // No capture is being recorded: stop the idle buffer (if any) and delete it.
            if( preview != null && preview.isLivePhotoBuffering() ) {
                File file =
                        preview.stopLivePhotoBuffer(false);

                deleteQuietly(file);
            }

            buffer_running = false;
            buffer_file = null;

            // If a capture is still being finalised in the background, keep the foreground service
            // alive so the process (and the finalisation thread) survives; otherwise stop it.
            updateForegroundService();
        }
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

        cancelFlashSteps();
        cancelBufferCap();

        capturing = false;
        pending_captures = 0;

        // No capture is in flight any more (the in-progress one, if any, will fail its own checks and
        // release itself); reset the counter so the queue is clean and the foreground service stops.
        captures_in_flight = 0;

        stopFlashBurst();

        if( preview != null && preview.isLivePhotoBuffering() ) {
            File file =
                    preview.stopLivePhotoBuffer();

            deleteQuietly(file);
        }

        buffer_running = false;
        buffer_file = null;

        updateForegroundService();

        // Restore the normal preview state if we had been recording (the shutter was put into
        // PHASE_TAKING_PHOTO by takePicture()).
        if( was_capturing && preview != null ) {
            preview.onLivePhotoCaptureFinished();
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Finalize: package the video into the saved still
    // ---------------------------------------------------------------------------------------------

    /** Packages the still (file or uri) with the given video. Returns true on success. */
    private boolean packageStill(
            File still_file,
            Uri still_uri,
            File video_file,
            long presentation_us
    ) {
        if( still_file != null ) {
            return packageIntoFile(
                    still_file,
                    video_file,
                    presentation_us
            );
        }

        if( still_uri != null ) {
            return packageIntoUri(
                    still_uri,
                    video_file,
                    presentation_us
            );
        }

        return false;
    }

    /**
     * Packages the still (a plain file) in place, replacing it with the Motion Photo. Returns true
     * only if the file on disk really is a valid Motion Photo afterwards.
     *
     * <p>Safety: the Motion Photo is built in a temporary file and only swapped in once it has been
     * verified to contain the embedded MP4. The swap writes over the original in place (rather than
     * delete-then-rename), so if anything goes wrong the original still is left untouched - the user
     * can never lose the photo, at worst it stays a plain JPEG.</p>
     */
    private boolean packageIntoFile(
            File still_file,
            File video_file,
            long presentation_us
    ) {
        if( still_file == null || video_file == null ) {
            return false;
        }

        File output =
                new File(
                        still_file.getParentFile(),
                        still_file.getName() + ".live.tmp"
                );

        boolean ok =
                LivePhotoHelper.packageMotionPhoto(
                        still_file,
                        video_file,
                        output,
                        presentation_us
                );

        // Verify the output really contains the embedded MP4 before it replaces the plain still.
        ok = ok
                && output.exists()
                && output.length() > 0
                && LivePhotoHelper.containsEmbeddedVideo(output);

        if( ok ) {
            // Replace the plain JPEG with the Motion Photo.
            ok = copyFile(
                    output,
                    still_file
            );

            if( ok ) {
                if( MyDebug.LOG )
                    Log.d(
                            TAG,
                            "live photo saved: "
                                    + still_file.getAbsolutePath()
                    );

                notifySaved(still_file);
            }
            else {
                Log.e(
                        TAG,
                        "failed to write live photo over original still"
                );
            }
        }
        else {
            Log.e(
                    TAG,
                    "failed to package motion photo"
            );
        }

        deleteQuietly(output);

        return ok;
    }

    /** Copies {@code src} over {@code dst}, replacing its contents. Returns true on success. */
    private boolean copyFile(
            File src,
            File dst
    ) {
        java.io.FileInputStream in = null;
        java.io.FileOutputStream out = null;

        try {
            in = new java.io.FileInputStream(src);
            out = new java.io.FileOutputStream(dst, false);

            byte[] buffer = new byte[64 * 1024];
            int read;

            while( (read = in.read(buffer)) > 0 ) {
                out.write(
                        buffer,
                        0,
                        read
                );
            }

            out.flush();

            try {
                out.getFD().sync();
            }
            catch(Exception ignored) {
            }

            return true;
        }
        catch(Exception e) {
            Log.e(
                    TAG,
                    "failed to copy "
                            + src
                            + " to "
                            + dst,
                    e
            );

            return false;
        }
        finally {
            try {
                if( in != null )
                    in.close();
            }
            catch(Exception ignored) {
            }

            try {
                if( out != null )
                    out.close();
            }
            catch(Exception ignored) {
            }
        }
    }

    /**
     * Packages the still (a content {@link Uri} from MediaStore/SAF) and writes the Motion Photo
     * back over the same Uri, replacing the plain JPEG that was saved.
     */
    private boolean packageIntoUri(
            Uri still_uri,
            File video_file,
            long presentation_us
    ) {
        if( still_uri == null || video_file == null ) {
            return false;
        }

        File output = null;
        boolean ok = false;

        try {
            byte[] coverBytes =
                    LivePhotoHelper.readUri(
                            context,
                            still_uri
                    );

            output =
                    new File(
                            context.getCacheDir(),
                            "rencam_live_pkg_"
                                    + System.currentTimeMillis()
                                    + ".jpg"
                    );

            ok =
                    LivePhotoHelper.packageMotionPhoto(
                            coverBytes,
                            video_file,
                            output,
                            presentation_us
                    );

            // Verify the packaged file really contains the embedded MP4 before writing it back.
            ok = ok
                    && output.exists()
                    && output.length() > 0
                    && LivePhotoHelper.containsEmbeddedVideo(output);

            if( ok ) {
                long new_size = output.length();

                ok =
                        LivePhotoHelper.writeFileToUri(
                                context,
                                output,
                                still_uri
                        );

                if( ok ) {
                    if( MyDebug.LOG )
                        Log.d(
                                TAG,
                                "live photo saved to uri: "
                                        + still_uri
                        );

                    notifySaved(
                            still_uri,
                            new_size
                    );
                }
                else {
                    Log.e(
                            TAG,
                            "failed to write live photo back to uri"
                    );
                }
            }
            else {
                Log.e(
                        TAG,
                        "failed to package motion photo for uri"
                );
            }
        }
        catch(Exception e) {
            Log.e(
                    TAG,
                    "failed to package motion photo into uri",
                    e
            );

            ok = false;
        }
        finally {
            if( output != null && output.exists() ) {
                deleteQuietly(output);
            }
        }

        return ok;
    }

    private void notifySaved(final File file) {
        // Ask the media scanner to re-index the (now larger) Motion Photo.
        try {
            android.media.MediaScannerConnection.scanFile(
                    context,
                    new String[]{ file.getAbsolutePath() },
                    new String[]{ "image/jpeg" },
                    null
            );
        }
        catch(Exception e) {
            Log.e(
                    TAG,
                    "failed to scan live photo file",
                    e
            );
        }
    }

    private void notifySaved(
            final Uri uri,
            final long new_size
    ) {
        // The file content changed in place; refresh the MediaStore metadata.
        try {
            if( "content".equals(uri.getScheme()) ) {
                android.content.ContentValues values =
                        new android.content.ContentValues();

                values.put(
                        android.provider.MediaStore.MediaColumns.SIZE,
                        new_size
                );

                values.put(
                        android.provider.MediaStore.MediaColumns.DATE_MODIFIED,
                        System.currentTimeMillis() / 1000L
                );

                context.getContentResolver().update(
                        uri,
                        values,
                        null,
                        null
                );
            }

            // Best-effort: also request a scan so external viewers see the updated file promptly.
            String path = uri.getPath();

            if( path != null && path.startsWith("/") ) {
                android.media.MediaScannerConnection.scanFile(
                        context,
                        new String[]{ path },
                        new String[]{ "image/jpeg" },
                        null
                );
            }
        }
        catch(Exception e) {
            Log.e(
                    TAG,
                    "failed to refresh live photo uri metadata",
                    e
            );
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Flash handling
    // ---------------------------------------------------------------------------------------------

    /**
     * Fires a three-pulse Apple-style approximation for the still:
     * two short pre-flash pulses followed by a longer, brighter capture flash.
     *
     * <p>For the front camera without an LED this lights the screen. For the rear camera the LED is
     * driven as a torch because that is the flash control exposed by the current RenCam camera
     * controller.</p>
     */
    private void startFlashBurst(boolean front) {
        String behavior = getFlashBehavior();

        if( "off".equals(behavior) ) {
            if( MyDebug.LOG )
                Log.d(
                        TAG,
                        "flash burst disabled by preference"
                );

            return;
        }

        CameraController controller =
                preview != null
                        ? preview.getCameraController()
                        : null;

        String current =
                controller != null
                        ? controller.getFlashValue()
                        : null;

        boolean has_led =
                current != null
                        && current.length() > 0
                        && !current.startsWith("flash_frontscreen");

        if( front && !has_led ) {
            flash_use_screen = true;
            flash_value_before_capture = null;
        }
        else if( controller != null && has_led ) {
            flash_use_screen = false;
            flash_value_before_capture = current;
        }
        else {
            if( MyDebug.LOG )
                Log.d(
                        TAG,
                        "no flash available for this camera"
                );

            return;
        }

        if( MyDebug.LOG ) {
            Log.d(
                    TAG,
                    "flash burst: screen="
                            + flash_use_screen
                            + " has_led="
                            + has_led
            );
        }

        // Remember that a flash actually fired, so the cover frame can be synced to it.
        flash_fired = true;

        // Three visible pulses:
        // pulse 1 = short pre-flash
        // pulse 2 = second pre-flash
        // pulse 3 = brightest capture flash
        scheduleFlashStep(
                FLASH_PRE_1_START_MS,
                true,
                FLASH_PRE_1_ALPHA
        );

        scheduleFlashStep(
                FLASH_PRE_1_END_MS,
                false,
                0
        );

        scheduleFlashStep(
                FLASH_PRE_2_START_MS,
                true,
                FLASH_PRE_2_ALPHA
        );

        scheduleFlashStep(
                FLASH_PRE_2_END_MS,
                false,
                0
        );

        scheduleFlashStep(
                FLASH_CAPTURE_START_MS,
                true,
                FLASH_CAPTURE_ALPHA
        );

        scheduleFlashStep(
                FLASH_CAPTURE_END_MS,
                false,
                0
        );
    }

    /** Schedules one on/off step of the flash burst. {@code alpha} is the screen-flash brightness. */
    private void scheduleFlashStep(
            long delay_ms,
            final boolean on,
            final int alpha
    ) {
        Runnable r =
                new Runnable() {
                    @Override
                    public void run() {
                        setFlashHardware(
                                on,
                                alpha
                        );
                    }
                };

        flash_runnables.add(r);

        handler.postDelayed(
                r,
                delay_ms
        );
    }

    /** Actually turns the LED torch / front screen on or off. */
    private void setFlashHardware(
            boolean on,
            int alpha
    ) {
        if( flash_use_screen ) {
            if( host != null ) {
                if( on ) {
                    host.turnFrontScreenFlashOn(alpha);
                    used_screen_flash = true;
                }
                else {
                    host.turnFrontScreenFlashOff();
                    used_screen_flash = false;
                }
            }

            return;
        }

        CameraController controller =
                preview != null
                        ? preview.getCameraController()
                        : null;

        if( controller != null ) {
            try {
                controller.setFlashValue(
                        on
                                ? "flash_torch"
                                : "flash_off"
                );
            }
            catch(Exception e) {
                Log.e(
                        TAG,
                        "failed to set flash burst state",
                        e
                );
            }
        }
    }

    /** Cancels any pending flash-burst steps. */
    private void cancelFlashSteps() {
        for( Runnable r : flash_runnables ) {
            handler.removeCallbacks(r);
        }

        flash_runnables.clear();
    }

    /**
     * Turns the flash burst off and restores the user's flash mode. Called once the burst duration
     * has elapsed (or when aborting).
     *
     * <p>We restore the mode from the <b>UI</b> ({@link Preview#getCurrentFlashValue()}), not from the
     * controller's current value. That is deliberate: the burst drives the LED through
     * {@code setFlashValue("flash_torch")}, so if a burst were ever interrupted the controller could
     * be left on {@code flash_torch} - and restoring <i>that</i> would keep the LED on and light the
     * whole post-roll. Reading the UI value means a previous interrupted burst can never leak a torch
     * into the next shot. Any torch mode is forced back to {@code flash_off} for the same reason.</p>
     */
    private void stopFlashBurst() {
        cancelFlashSteps();

        if( used_screen_flash && host != null ) {
            host.turnFrontScreenFlashOff();
            used_screen_flash = false;
        }

        boolean burst_fired =
                flash_fired
                        || flash_value_before_capture != null;

        if( burst_fired ) {
            CameraController controller =
                    preview != null
                            ? preview.getCameraController()
                            : null;

            if( controller != null ) {
                String restore =
                        preview != null
                                ? preview.getCurrentFlashValue()
                                : null;

                if( restore == null
                        || restore.length() == 0
                        || restore.contains("torch") ) {

                    restore = "flash_off";
                }

                if( MyDebug.LOG ) {
                    Log.d(
                            TAG,
                            "stopFlashBurst, restoring flash: "
                                    + restore
                    );
                }

                try {
                    controller.setFlashValue(restore);
                }
                catch(Exception e) {
                    Log.e(
                            TAG,
                            "failed to restore flash value",
                            e
                    );
                }
            }
        }
        else if( MyDebug.LOG ) {
            Log.d(
                    TAG,
                    "stopFlashBurst: no burst fired, leaving camera flash untouched"
            );
        }

        flash_value_before_capture = null;
        flash_use_screen = false;
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    private File createBufferFile() {
        try {
            File dir = context.getCacheDir();

            String timestamp =
                    new SimpleDateFormat(
                            "yyyyMMdd_HHmmss_SSS",
                            Locale.US
                    ).format(new Date());

            return new File(
                    dir,
                    "rencam_live_"
                            + timestamp
                            + ".mp4"
            );
        }
        catch(Exception e) {
            Log.e(
                    TAG,
                    "failed to create live photo file",
                    e
            );

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
