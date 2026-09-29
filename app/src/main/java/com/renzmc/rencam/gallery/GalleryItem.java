package com.renzmc.rencam.gallery;

import android.net.Uri;

/**
 * A single media entry shown in the RenCam in-app gallery.
 *
 * @author RenzMc
 */
public class GalleryItem {
    /** The MediaStore content uri. */
    public final Uri uri;
    /** Date taken / added, in milliseconds since epoch (used for sorting). */
    public final long date;
    /** True if this entry is a video rather than a still image. */
    public final boolean isVideo;
    /** Human readable display name (may be null). */
    public final String displayName;

    /**
     * True if this still image is a Live Photo (a JPEG with an embedded MP4 + XMP). This is
     * determined lazily on a background thread and cached here.
     */
    public volatile boolean isMotionPhoto;

    /** True once {@link #isMotionPhoto} has been resolved (so we don't re-scan). */
    public volatile boolean motionPhotoChecked;

    public GalleryItem(Uri uri, long date, boolean isVideo, String displayName) {
        this.uri = uri;
        this.date = date;
        this.isVideo = isVideo;
        this.displayName = displayName;
    }
}
