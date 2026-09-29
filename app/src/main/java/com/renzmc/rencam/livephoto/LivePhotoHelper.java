package com.renzmc.rencam.livephoto;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;
import android.net.Uri;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;

/**
 * LivePhotoHelper - utilities for creating and reading "Live Photos" / "Motion Photos".
 *
 * <p>A Live Photo (as used by Google Photos, Samsung, Xiaomi, etc.) is a single JPEG file that
 * contains:
 * <ul>
 *     <li>the primary still image (JPEG), and</li>
 *     <li>an embedded MP4 video appended after the JPEG data,</li>
 * </ul>
 * linked together by XMP metadata (the {@code GCamera:MicroVideo} / {@code Container:Directory}
 * schema) stored inside the JPEG APP1 segment. This is the same format MotionCraft
 * (github.com/WeiErLiTeo/MotionCraft) produces, ported here to Java and adapted for RenCam.</p>
 *
 * <p>The class is intentionally dependency-free (only framework APIs) so it can be used both for
 * capture (packaging) and for reading existing Live Photos.</p>
 *
 * @author RenzMc
 */
public final class LivePhotoHelper {

    private static final String TAG = "LivePhotoHelper";

    /** Google namespace used for Motion Photo metadata. */
    private static final String NS_GCAMERA = "http://ns.google.com/photos/1.0/camera/";
    private static final String NS_CONTAINER = "http://ns.google.com/photos/1.0/container/";
    private static final String NS_ITEM = "http://ns.google.com/photos/1.0/container/item/";

    /** 'ftyp' box signature used to locate the start of the embedded MP4. */
    private static final byte[] FTYP = new byte[]{0x66, 0x74, 0x79, 0x70};

    private LivePhotoHelper() {
        // no instances
    }

    // ---------------------------------------------------------------------------------------------
    // Packaging (JPEG + MP4 -> Motion Photo JPEG)
    // ---------------------------------------------------------------------------------------------

    /**
     * Packages a cover JPEG and a video into a single Motion Photo file.
     *
     * @param coverFile  the primary still image (JPEG)
     * @param videoFile  the motion video (MP4)
     * @param outputFile the destination Motion Photo (JPEG)
     * @param presentationTimestampUs the timestamp (in microseconds) inside the video that
     *                                corresponds to the still image (the moment the shutter fired).
     * @return true on success
     */
    public static boolean packageMotionPhoto(File coverFile, File videoFile, File outputFile,
                                             long presentationTimestampUs) {
        try {
            long videoLength = videoFile.length();
            byte[] coverBytes = readFile(coverFile);
            byte[] jpegWithXmp = injectXMPIntoJPEG(coverBytes, videoLength, presentationTimestampUs);

            try (FileOutputStream out = new FileOutputStream(outputFile)) {
                out.write(jpegWithXmp);
                try (InputStream videoIn = new java.io.FileInputStream(videoFile)) {
                    byte[] buffer = new byte[128 * 1024];
                    int read;
                    while ((read = videoIn.read(buffer)) > 0) {
                        out.write(buffer, 0, read);
                    }
                }
            }
            Log.d(TAG, "Packaged motion photo into " + outputFile.getAbsolutePath()
                    + ", total size: " + outputFile.length() + " bytes");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Error packaging motion photo from files", e);
            return false;
        }
    }

    /**
     * Packages a cover JPEG (as bytes) and a video (as bytes) into a Motion Photo file.
     */
    public static boolean packageMotionPhoto(byte[] coverBytes, byte[] videoBytes, File outputFile,
                                             long presentationTimestampUs) {
        try {
            byte[] jpegWithXmp = injectXMPIntoJPEG(coverBytes, videoBytes.length, presentationTimestampUs);
            try (FileOutputStream out = new FileOutputStream(outputFile)) {
                out.write(jpegWithXmp);
                out.write(videoBytes);
            }
            Log.d(TAG, "Packaged motion photo into " + outputFile.getAbsolutePath()
                    + ", total size: " + outputFile.length() + " bytes");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Error packaging motion photo from bytes", e);
            return false;
        }
    }

    /**
     * Packages a cover JPEG (as bytes) together with a video <b>file</b> into a Motion Photo.
     *
     * <p>Unlike {@link #packageMotionPhoto(byte[], byte[], File, long)} this streams the video
     * straight from disk instead of loading it all into memory, which is important when the cover
     * image came from a content {@code Uri} (e.g. MediaStore / SAF) that we could only read as
     * bytes.</p>
     */
    public static boolean packageMotionPhoto(byte[] coverBytes, File videoFile, File outputFile,
                                             long presentationTimestampUs) {
        try {
            long videoLength = videoFile.length();
            byte[] jpegWithXmp = injectXMPIntoJPEG(coverBytes, videoLength, presentationTimestampUs);
            try (FileOutputStream out = new FileOutputStream(outputFile)) {
                out.write(jpegWithXmp);
                try (InputStream videoIn = new java.io.FileInputStream(videoFile)) {
                    byte[] buffer = new byte[128 * 1024];
                    int read;
                    while ((read = videoIn.read(buffer)) > 0) {
                        out.write(buffer, 0, read);
                    }
                }
            }
            Log.d(TAG, "Packaged motion photo into " + outputFile.getAbsolutePath()
                    + ", total size: " + outputFile.length() + " bytes");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Error packaging motion photo from cover bytes + video file", e);
            return false;
        }
    }

    /** Reads the whole content of a content {@link Uri} into a byte array. */
    public static byte[] readUri(Context context, Uri uri) throws java.io.IOException {
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) {
                throw new java.io.IOException("openInputStream returned null for " + uri);
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) {
                bos.write(buffer, 0, read);
            }
            return bos.toByteArray();
        }
    }

    /**
     * Overwrites the content of a content {@link Uri} with the bytes of {@code sourceFile}.
     * Used to replace the plain still JPEG that the camera saved with the packaged Motion Photo.
     */
    public static boolean writeFileToUri(Context context, File sourceFile, Uri uri) {
        try (InputStream in = new java.io.FileInputStream(sourceFile);
             java.io.OutputStream out = context.getContentResolver().openOutputStream(uri, "wt")) {
            if (out == null) {
                return false;
            }
            byte[] buffer = new byte[128 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            out.flush();
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Error writing packaged motion photo back to uri " + uri, e);
            return false;
        }
    }

    /**
     * Injects the Google Motion Photo XMP metadata into a JPEG, inserting a new APP1 (0xFFE1)
     * segment immediately after the SOI marker. The original JPEG data is preserved.
     *
     * @param jpegBytes the original JPEG bytes
     * @param videoLength the length in bytes of the MP4 that will be appended
     * @param presentationTimestampUs timestamp (us) of the still within the video
     */
    public static byte[] injectXMPIntoJPEG(byte[] jpegBytes, long videoLength,
                                           long presentationTimestampUs) {
        String xmpContent =
                "<?xpacket begin=\"\uFEFF\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>\n" +
                "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\" x:xmptk=\"Adobe XMP Core 5.1.0-jc003\">\n" +
                "  <rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">\n" +
                "    <rdf:Description rdf:about=\"\"\n" +
                "        xmlns:GCamera=\"" + NS_GCAMERA + "\"\n" +
                "        xmlns:Container=\"" + NS_CONTAINER + "\"\n" +
                "        xmlns:Item=\"" + NS_ITEM + "\"\n" +
                "        GCamera:MotionPhoto=\"1\"\n" +
                "        GCamera:MotionPhotoVersion=\"1\"\n" +
                "        GCamera:MotionPhotoPresentationTimestampUs=\"" + presentationTimestampUs + "\">\n" +
                "      <Container:Directory>\n" +
                "        <rdf:Seq>\n" +
                "          <rdf:li rdf:parseType=\"Resource\">\n" +
                "            <Container:Item Item:Mime=\"image/jpeg\" Item:Semantic=\"Primary\" Item:Length=\"0\" Item:Padding=\"0\"/>\n" +
                "          </rdf:li>\n" +
                "          <rdf:li rdf:parseType=\"Resource\">\n" +
                "            <Container:Item Item:Mime=\"video/mp4\" Item:Semantic=\"MotionPhoto\" Item:Length=\"" + videoLength + "\" Item:Padding=\"0\"/>\n" +
                "          </rdf:li>\n" +
                "        </rdf:Seq>\n" +
                "      </Container:Directory>\n" +
                "    </rdf:Description>\n" +
                "  </rdf:RDF>\n" +
                "</x:xmpmeta>\n" +
                "<?xpacket end=\"w\"?>";

        // The XMP namespace header used by the JPEG APP1 "XMP" segment.
        byte[] namespace = "http://ns.adobe.com/xap/1.0/\u0000".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] xmpBytes = xmpContent.getBytes(java.nio.charset.StandardCharsets.UTF_8);

        int payloadSize = namespace.length + xmpBytes.length;
        int markerSize = payloadSize + 2; // length field includes itself (2 bytes)

        ByteArrayOutputStream out = new ByteArrayOutputStream(jpegBytes.length + markerSize + 2);
        out.write(0xFF);
        out.write(0xD8); // SOI
        out.write(0xFF);
        out.write(0xE1); // APP1
        out.write((markerSize >> 8) & 0xFF);
        out.write(markerSize & 0xFF);
        out.write(namespace, 0, namespace.length);
        out.write(xmpBytes, 0, xmpBytes.length);

        // append the original JPEG (skipping its own SOI marker if present)
        if (jpegBytes.length > 2 && (jpegBytes[0] & 0xFF) == 0xFF && (jpegBytes[1] & 0xFF) == 0xD8) {
            out.write(jpegBytes, 2, jpegBytes.length - 2);
        } else {
            out.write(jpegBytes, 0, jpegBytes.length);
        }
        return out.toByteArray();
    }

    // ---------------------------------------------------------------------------------------------
    // Extraction / reading
    // ---------------------------------------------------------------------------------------------

    /**
     * Scans a JPEG/Motion Photo file to find the embedded MP4 and copies it to {@code cacheFile}.
     * Streaming is used so large videos do not need to be held in memory.
     */
    public static boolean extractVideoFromMotionPhoto(File file, File cacheFile) {
        try {
            if (!file.exists() || file.length() < 100) {
                return false;
            }
            java.io.RandomAccessFile raf = new java.io.RandomAccessFile(file, "r");
            long fileLength = raf.length();

            byte[] buffer = new byte[64 * 1024];
            long ftypOffset = -1L;

            long pos = 0L;
            while (pos < fileLength) {
                raf.seek(pos);
                int bytesRead = raf.read(buffer);
                if (bytesRead < 4) {
                    break;
                }
                for (int i = 0; i < bytesRead - 3; i++) {
                    if (buffer[i] == FTYP[0] && buffer[i + 1] == FTYP[1]
                            && buffer[i + 2] == FTYP[2] && buffer[i + 3] == FTYP[3]) {
                        ftypOffset = pos + i;
                        break;
                    }
                }
                if (ftypOffset != -1L) {
                    break;
                }
                pos += bytesRead - 3;
            }

            if (ftypOffset == -1L) {
                raf.close();
                return false;
            }

            long startOffset = Math.max(0L, ftypOffset - 4);
            raf.seek(startOffset);

            try (FileOutputStream out = new FileOutputStream(cacheFile)) {
                byte[] copyBuffer = new byte[128 * 1024];
                long bytesToRead = fileLength - startOffset;
                while (bytesToRead > 0) {
                    int readSize = raf.read(copyBuffer, 0,
                            (int) Math.min(copyBuffer.length, bytesToRead));
                    if (readSize <= 0) {
                        break;
                    }
                    out.write(copyBuffer, 0, readSize);
                    bytesToRead -= readSize;
                }
            }
            raf.close();
            Log.d(TAG, "Extracted embedded MP4 to " + cacheFile.getAbsolutePath()
                    + ", size: " + cacheFile.length() + " bytes");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Error extracting video from motion photo file", e);
            return false;
        }
    }

    /**
     * Extracts the embedded MP4 from a Motion Photo {@link Uri} (e.g. a MediaStore uri) into
     * {@code cacheFile}. Used by the in-app gallery viewer to play the Live Photo video.
     *
     * @return true on success
     */
    public static boolean extractVideoFromMotionPhoto(Context context, Uri uri, File cacheFile) {
        try (InputStream inputStream = context.getContentResolver().openInputStream(uri)) {
            if (inputStream == null) {
                return false;
            }
            byte[] buffer = new byte[64 * 1024];
            long ftypOffset = -1L;
            long pos = 0L;
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                for (int i = 0; i < bytesRead - 3; i++) {
                    if (buffer[i] == FTYP[0] && buffer[i + 1] == FTYP[1]
                            && buffer[i + 2] == FTYP[2] && buffer[i + 3] == FTYP[3]) {
                        ftypOffset = pos + i;
                        break;
                    }
                }
                if (ftypOffset != -1L) {
                    break;
                }
                pos += bytesRead - 3;
            }
            if (ftypOffset == -1L) {
                return false;
            }
            // Re-open and skip to the MP4 start, then copy the remainder.
            try (InputStream skipStream = context.getContentResolver().openInputStream(uri)) {
                if (skipStream == null) {
                    return false;
                }
                long toSkip = Math.max(0L, ftypOffset - 4);
                long skipped = 0L;
                while (skipped < toSkip) {
                    long s = skipStream.skip(toSkip - skipped);
                    if (s <= 0) {
                        break;
                    }
                    skipped += s;
                }
                try (FileOutputStream out = new FileOutputStream(cacheFile)) {
                    byte[] copyBuffer = new byte[128 * 1024];
                    int read;
                    while ((read = skipStream.read(copyBuffer)) != -1) {
                        out.write(copyBuffer, 0, read);
                    }
                }
            }
            Log.d(TAG, "Extracted embedded MP4 to " + cacheFile.getAbsolutePath()
                    + ", size: " + cacheFile.length() + " bytes");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Error extracting video from motion photo uri", e);
            return false;
        }
    }

    /**
     * Determines whether a Uri represents a Motion Photo (contains an embedded MP4).
     */
    public static boolean isMotionPhoto(Context context, Uri uri) {
        try (InputStream inputStream = context.getContentResolver().openInputStream(uri)) {
            if (inputStream == null) {
                return false;
            }
            byte[] buffer = new byte[64 * 1024];
            int bytesRead;
            long totalRead = 0L;
            long maxSearch = 8L * 1024 * 1024;
            while ((bytesRead = inputStream.read(buffer)) != -1 && totalRead < maxSearch) {
                if (findSubarray(buffer, FTYP) != -1) {
                    return true;
                }
                totalRead += bytesRead;
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Extracts the XMP metadata block from image bytes, if present.
     */
    public static String extractXmpXml(byte[] bytes) {
        try {
            int ftypIdx = findSubarray(bytes, FTYP);
            int searchLimit = ftypIdx != -1 ? ftypIdx : Math.min(bytes.length, 2 * 1024 * 1024);

            byte[] headerBytes = "<?xpacket begin".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            byte[] head = new byte[searchLimit];
            System.arraycopy(bytes, 0, head, 0, searchLimit);
            int headerIdx = findSubarray(head, headerBytes);
            if (headerIdx != -1) {
                byte[] footerBytes = "<?xpacket end".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                int remain = searchLimit - headerIdx;
                byte[] tail = new byte[remain];
                System.arraycopy(bytes, headerIdx, tail, 0, remain);
                int footerIdx = findSubarray(tail, footerBytes);
                int endIdx = footerIdx != -1
                        ? headerIdx + footerIdx + 19
                        : Math.min(headerIdx + 4096, bytes.length);
                endIdx = Math.min(endIdx, bytes.length);
                return new String(bytes, headerIdx, endIdx - headerIdx,
                        java.nio.charset.StandardCharsets.UTF_8);
            }
            return "XMP metadata packet not found in image header (<?xpacket begin...)";
        } catch (Exception e) {
            return "Error parsing XMP: " + e.getMessage();
        }
    }

    /**
     * Extracts a single frame from a video at the given timestamp.
     */
    public static Bitmap extractVideoFrame(Context context, Uri videoUri, long timeMs) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(context, videoUri);
            return retriever.getFrameAtTime(timeMs * 1000L,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
        } catch (Exception e) {
            Log.e(TAG, "Error extracting frame at " + timeMs + " ms", e);
            return null;
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * Gets the duration of a video in milliseconds.
     */
    public static long getVideoDuration(Context context, Uri videoUri) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(context, videoUri);
            String durationStr = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_DURATION);
            return durationStr != null ? Long.parseLong(durationStr) : 0L;
        } catch (Exception e) {
            Log.e(TAG, "Error getting video duration", e);
            return 0L;
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Trimming
    // ---------------------------------------------------------------------------------------------

    /**
     * Trims a video to the [startMs, endMs] window without re-encoding, using MediaExtractor +
     * MediaMuxer. This is used to cut the exact "before + after shutter" window out of the
     * continuously running video buffer.
     *
     * @param inputUri   source video
     * @param outputFile destination MP4
     * @param startMs    start time in milliseconds
     * @param endMs      end time in milliseconds
     * @return true on success (falls back to copying the whole file on failure)
     */
    public static boolean trimVideo(Context context, Uri inputUri, File outputFile,
                                    long startMs, long endMs) {
        MediaExtractor extractor = new MediaExtractor();
        MediaMuxer muxer = null;
        FileDescriptor pfd = null;
        try {
            android.os.ParcelFileDescriptor parcelFd =
                    context.getContentResolver().openFileDescriptor(inputUri, "r");
            if (parcelFd == null) {
                return false;
            }
            pfd = parcelFd.getFileDescriptor();
            extractor.setDataSource(pfd);

            int trackCount = extractor.getTrackCount();
            java.util.HashMap<Integer, Integer> trackIndices = new java.util.HashMap<>();
            int videoTrackIdx = -1;

            muxer = new MediaMuxer(outputFile.getAbsolutePath(),
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

            for (int i = 0; i < trackCount; i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime == null) {
                    continue;
                }
                if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                    extractor.selectTrack(i);
                    int dstIndex = muxer.addTrack(format);
                    trackIndices.put(i, dstIndex);
                    if (mime.startsWith("video/")) {
                        videoTrackIdx = i;
                    }
                }
            }

            muxer.start();

            long startUs = startMs * 1000L;
            long endUs = endMs * 1000L;
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC);

            ByteBuffer buffer = ByteBuffer.allocate(2 * 1024 * 1024);
            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();

            boolean firstVideoKeyFrameSeen = false;
            long baseUs = -1L;

            while (true) {
                int sampleTrackIndex = extractor.getSampleTrackIndex();
                if (sampleTrackIndex == -1) {
                    break;
                }
                Integer dstTrackIndex = trackIndices.get(sampleTrackIndex);
                if (dstTrackIndex == null) {
                    extractor.advance();
                    continue;
                }

                long sampleTime = extractor.getSampleTime();
                if (sampleTime > endUs) {
                    break;
                }
                int flags = extractor.getSampleFlags();

                if (sampleTrackIndex == videoTrackIdx && !firstVideoKeyFrameSeen) {
                    if ((flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                            || (flags & MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                        firstVideoKeyFrameSeen = true;
                        baseUs = sampleTime;
                    } else {
                        extractor.advance();
                        continue;
                    }
                }

                if (baseUs == -1L) {
                    baseUs = sampleTime;
                }

                long presentationTimeUs = Math.max(0L, sampleTime - baseUs);

                bufferInfo.offset = 0;
                bufferInfo.size = extractor.readSampleData(buffer, 0);
                if (bufferInfo.size < 0) {
                    break;
                }
                bufferInfo.presentationTimeUs = presentationTimeUs;
                bufferInfo.flags = flags;

                muxer.writeSampleData(dstTrackIndex, buffer, bufferInfo);
                extractor.advance();
            }

            parcelFd.close();
            Log.d(TAG, "Trimming video succeeded! Start: " + startMs + " ms, End: " + endMs + " ms");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Error trimming video", e);
            // fallback: copy the whole file
            try (InputStream in = context.getContentResolver().openInputStream(inputUri);
                 FileOutputStream out = new FileOutputStream(outputFile)) {
                if (in != null) {
                    byte[] buf = new byte[128 * 1024];
                    int r;
                    while ((r = in.read(buf)) > 0) {
                        out.write(buf, 0, r);
                    }
                }
                Log.d(TAG, "Trim failed but fallback copied full file");
                return true;
            } catch (Exception e2) {
                return false;
            }
        } finally {
            try {
                extractor.release();
            } catch (Exception ignored) {
            }
            if (muxer != null) {
                try {
                    muxer.stop();
                } catch (Exception ignored) {
                }
                try {
                    muxer.release();
                } catch (Exception ignored) {
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    private static byte[] readFile(File file) throws java.io.IOException {
        try (InputStream in = new java.io.FileInputStream(file)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream((int) file.length());
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) {
                bos.write(buffer, 0, read);
            }
            return bos.toByteArray();
        }
    }

    private static int findSubarray(byte[] array, byte[] pattern) {
        if (pattern.length > array.length) {
            return -1;
        }
        outer:
        for (int i = 0; i <= array.length - pattern.length; i++) {
            for (int j = 0; j < pattern.length; j++) {
                if (array[i + j] != pattern[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
