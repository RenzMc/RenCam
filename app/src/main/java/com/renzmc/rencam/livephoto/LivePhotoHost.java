package com.renzmc.rencam.livephoto;

import java.util.Date;

/**
 * Host callbacks used by {@link LivePhotoManager} to talk back to the camera application
 * without creating a hard dependency on {@code MyApplicationInterface}.
 *
 * <p>RenCam's Live Photo feature works by <b>recording a short video</b> and then converting it
 * into a Google Motion Photo (JPEG cover frame + embedded MP4 + XMP metadata). Because the cover
 * image is taken from the video itself, the feature works on <b>every</b> device that can record
 * video - it does not depend on the device supporting simultaneous photo+video capture.</p>
 *
 * @author RenzMc
 */
public interface LivePhotoHost {

    /**
     * Saves the cover frame (extracted from the recorded video) using the normal photo saving
     * pipeline (MediaStore / SAF / file), so that it appears in the gallery and respects the
     * user's storage preferences. Once saved, {@link LivePhotoManager#onStillSaved} is invoked
     * and the video is packaged into the cover image.
     *
     * @param jpeg the cover frame as JPEG bytes
     * @param date the capture date
     * @return true if the save was started
     */
    boolean saveLivePhotoCover(byte[] jpeg, Date date);

    /** Turns on the front-screen "flash" (a bright white screen used to light the subject). */
    void turnFrontScreenFlashOn();

    /**
     * Turns on the front-screen "flash" at a given brightness, used by the Live Photo flash burst
     * so the main flash can be dimmer and the capture flash can be maxed out.
     *
     * @param alpha brightness of the white overlay, 0 (off) .. 255 (full white)
     */
    void turnFrontScreenFlashOn(int alpha);

    /** Turns off the front-screen flash. */
    void turnFrontScreenFlashOff();

    /** Whether the currently open camera is front-facing. */
    boolean isFrontFacing();

    /** Shows a short toast (by string resource id) on the UI thread. */
    void showToast(int string_id);
}
