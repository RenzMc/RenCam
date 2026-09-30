package com.renzmc.rencam.livephoto;

import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;
import android.util.Log;

import java.io.File;
import java.nio.ByteBuffer;

/**
 * RenCam: centre-crops a video to a fixed aspect ratio (e.g. 9:16) by re-encoding the video track
 * with {@link MediaCodec}. The audio track is copied through untouched (no re-encode), so the result
 * keeps its original sound. The display rotation of the source is preserved via the muxer's
 * orientation hint, so a portrait clip stays portrait after cropping.
 *
 * <p>Cropping is a best-effort enhancement: every failure path returns {@code false} and leaves the
 * caller free to keep the original, uncropped video. It therefore can never make a capture worse.
 */
public class VideoCropper {

    private static final String TAG = "RenCamVideoCropper";
    private static final long TIMEOUT_US = 10000L;

    private VideoCropper() {
    }

    /**
     * Centre-crops {@code input} to {@code target_ratio} (width/height in <b>display</b> terms, i.e.
     * 9:16 is expressed as 9.0/16.0) and writes the result to {@code output}.
     *
     * @return true if a cropped file was produced, false otherwise (caller should keep the original).
     */
    public static boolean cropToAspectRatio(File input, File output, double target_ratio) {
        if( input == null || output == null || !input.exists() || target_ratio <= 0.0 ) {
            return false;
        }
        MediaExtractor video_extractor = null;
        MediaExtractor audio_extractor = null;
        MediaCodec decoder = null;
        MediaCodec encoder = null;
        MediaMuxer muxer = null;
        boolean success = false;
        try {
            video_extractor = new MediaExtractor();
            video_extractor.setDataSource(input.getAbsolutePath());

            int video_track = -1;
            MediaFormat video_format = null;
            MediaFormat audio_format = null;
            for( int i = 0; i < video_extractor.getTrackCount(); i++ ) {
                MediaFormat format = video_extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if( mime == null ) {
                    continue;
                }
                if( mime.startsWith("video/") && video_track < 0 ) {
                    video_track = i;
                    video_format = format;
                }
                else if( mime.startsWith("audio/") && audio_format == null ) {
                    audio_format = format;
                }
            }
            if( video_track < 0 || video_format == null ) {
                Log.e(TAG, "no video track in " + input);
                return false;
            }

            int src_w = video_format.getInteger(MediaFormat.KEY_WIDTH);
            int src_h = video_format.getInteger(MediaFormat.KEY_HEIGHT);
            int rotation = 0;
            if( video_format.containsKey(MediaFormat.KEY_ROTATION) ) {
                rotation = video_format.getInteger(MediaFormat.KEY_ROTATION);
            }
            rotation = ((rotation % 360) + 360) % 360;

            boolean swap = (rotation == 90 || rotation == 270);
            int disp_w = swap ? src_h : src_w;
            int disp_h = swap ? src_w : src_h;
            double current_ratio = (double) disp_w / (double) disp_h;
            if( Math.abs(current_ratio - target_ratio) < 0.01 ) {
                Log.d(TAG, "video already matches target ratio " + target_ratio);
                return false;
            }

            // Work out the visible crop rectangle (in display terms) that produces target_ratio.
            int crop_disp_w, crop_disp_h;
            if( current_ratio > target_ratio ) {
                crop_disp_h = disp_h;
                crop_disp_w = (int) Math.round(disp_h * target_ratio);
            }
            else {
                crop_disp_w = disp_w;
                crop_disp_h = (int) Math.round(disp_w / target_ratio);
            }
            // Map back to encoded (pre-rotation) coordinates.
            int crop_src_w = swap ? crop_disp_h : crop_disp_w;
            int crop_src_h = swap ? crop_disp_w : crop_disp_h;
            crop_src_w &= ~1;
            crop_src_h &= ~1;
            if( crop_src_w <= 0 || crop_src_h <= 0 || crop_src_w > src_w || crop_src_h > src_h ) {
                Log.e(TAG, "invalid crop size " + crop_src_w + "x" + crop_src_h);
                return false;
            }
            int crop_x = ((src_w - crop_src_w) / 2) & ~1;
            int crop_y = ((src_h - crop_src_h) / 2) & ~1;

            // --- decoder (ByteBuffer mode so we can read the YUV planes and crop them) ---
            decoder = MediaCodec.createDecoderByType(video_format.getString(MediaFormat.KEY_MIME));
            decoder.configure(video_format, null, null, 0);
            decoder.start();

            // --- encoder ---
            String out_mime = "video/avc";
            MediaCodecInfo codec_info = selectCodec(out_mime);
            if( codec_info == null ) {
                Log.e(TAG, "no encoder for " + out_mime);
                return false;
            }
            int color_format = selectColorFormat(codec_info, out_mime);
            if( color_format == 0 ) {
                Log.e(TAG, "no supported color format for " + out_mime);
                return false;
            }
            int src_bitrate = video_format.containsKey(MediaFormat.KEY_BIT_RATE)
                    ? video_format.getInteger(MediaFormat.KEY_BIT_RATE)
                    : (int) (src_w * src_h * 4);
            // scale the bitrate down for the smaller frame so the file stays a sensible size
            int bitrate = (int) (src_bitrate * ((double) (crop_src_w * crop_src_h) / (double) (src_w * src_h)));
            int frame_rate = video_format.containsKey(MediaFormat.KEY_FRAME_RATE)
                    ? video_format.getInteger(MediaFormat.KEY_FRAME_RATE) : 30;
            if( frame_rate <= 0 ) {
                frame_rate = 30;
            }

            MediaFormat enc_format = MediaFormat.createVideoFormat(out_mime, crop_src_w, crop_src_h);
            enc_format.setInteger(MediaFormat.KEY_COLOR_FORMAT, color_format);
            enc_format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
            enc_format.setInteger(MediaFormat.KEY_FRAME_RATE, frame_rate);
            enc_format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
            encoder = MediaCodec.createByCodecName(codec_info.getName());
            encoder.configure(enc_format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoder.start();

            // --- muxer ---
            muxer = new MediaMuxer(output.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            if( rotation != 0 ) {
                muxer.setOrientationHint(rotation);
            }
            int mux_video_track = -1;
            int mux_audio_track = -1;
            if( audio_format != null ) {
                mux_audio_track = muxer.addTrack(audio_format);
                audio_extractor = new MediaExtractor();
                audio_extractor.setDataSource(input.getAbsolutePath());
                for( int i = 0; i < audio_extractor.getTrackCount(); i++ ) {
                    String mime = audio_extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
                    if( mime != null && mime.startsWith("audio/") ) {
                        audio_extractor.selectTrack(i);
                        break;
                    }
                }
            }

            video_extractor.selectTrack(video_track);

            ByteBuffer audio_buffer = ByteBuffer.allocate(256 * 1024);
            MediaCodec.BufferInfo audio_info = new MediaCodec.BufferInfo();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

            boolean muxer_started = false;
            boolean input_done = false;
            boolean output_done = false;
            boolean encoder_eos_queued = false;
            long last_video_us = 0L;

            while( !output_done ) {
                if( !input_done ) {
                    int in_index = decoder.dequeueInputBuffer(TIMEOUT_US);
                    if( in_index >= 0 ) {
                        ByteBuffer in_buf = decoder.getInputBuffer(in_index);
                        int sample_size = video_extractor.readSampleData(in_buf, 0);
                        if( sample_size < 0 ) {
                            decoder.queueInputBuffer(in_index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            input_done = true;
                        }
                        else {
                            long pts = video_extractor.getSampleTime();
                            decoder.queueInputBuffer(in_index, 0, sample_size, pts, 0);
                            video_extractor.advance();
                        }
                    }
                }

                int dec_index = decoder.dequeueOutputBuffer(info, TIMEOUT_US);
                if( dec_index >= 0 ) {
                    boolean dec_eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    if( !dec_eos ) {
                        Image src_img = decoder.getOutputImage(dec_index);
                        if( src_img != null ) {
                            int enc_index = dequeueInputBuffer(encoder);
                            if( enc_index < 0 ) {
                                Log.e(TAG, "encoder gave no input buffer");
                                src_img.close();
                                decoder.releaseOutputBuffer(dec_index, false);
                                return false;
                            }
                            Image dst_img = encoder.getInputImage(enc_index);
                            if( dst_img != null ) {
                                cropIntoImage(src_img, dst_img, crop_x, crop_y, crop_src_w, crop_src_h);
                                ByteBuffer ib = encoder.getInputBuffer(enc_index);
                                int size = ib != null ? ib.capacity() : 0;
                                encoder.queueInputBuffer(enc_index, 0, size, info.presentationTimeUs, 0);
                            }
                            else {
                                // can't get an input image - bail out cleanly
                                Log.e(TAG, "encoder.getInputImage returned null");
                                decoder.releaseOutputBuffer(dec_index, false);
                                return false;
                            }
                            src_img.close();
                        }
                    }
                    else if( !encoder_eos_queued ) {
                        int enc_index;
                        do {
                            enc_index = encoder.dequeueInputBuffer(TIMEOUT_US);
                        } while( enc_index < 0 );
                        encoder.queueInputBuffer(enc_index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        encoder_eos_queued = true;
                    }
                    decoder.releaseOutputBuffer(dec_index, false);
                }

                // drain the encoder into the muxer
                int enc_index = encoder.dequeueOutputBuffer(info, TIMEOUT_US);
                if( enc_index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED ) {
                    MediaFormat out_format = encoder.getOutputFormat();
                    mux_video_track = muxer.addTrack(out_format);
                    muxer.start();
                    muxer_started = true;
                }
                else if( enc_index >= 0 ) {
                    if( (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0 ) {
                        info.size = 0;
                    }
                    if( info.size > 0 && muxer_started ) {
                        ByteBuffer out_buf = encoder.getOutputBuffer(enc_index);
                        if( out_buf != null ) {
                            out_buf.position(info.offset);
                            out_buf.limit(info.offset + info.size);
                            muxer.writeSampleData(mux_video_track, out_buf, info);
                            last_video_us = info.presentationTimeUs;
                        }
                    }
                    if( (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0 ) {
                        output_done = true;
                    }
                    encoder.releaseOutputBuffer(enc_index, false);

                    // interleave any audio samples that are now due, keeping the muxer happy
                    if( muxer_started && mux_audio_track >= 0 && audio_extractor != null ) {
                        writeAudioUpTo(audio_extractor, muxer, mux_audio_track, last_video_us,
                                audio_buffer, audio_info);
                    }
                }
            }

            // flush the remaining audio
            if( muxer_started && mux_audio_track >= 0 && audio_extractor != null ) {
                writeAudioUpTo(audio_extractor, muxer, mux_audio_track, Long.MAX_VALUE,
                        audio_buffer, audio_info);
            }

            success = output.exists() && output.length() > 100;
            Log.d(TAG, "cropped video: success=" + success + " " + crop_src_w + "x" + crop_src_h
                    + " rotation=" + rotation);
        }
        catch( Exception e ) {
            Log.e(TAG, "failed to crop video", e);
            success = false;
        }
        finally {
            try {
                if( decoder != null ) {
                    decoder.stop();
                    decoder.release();
                }
            }
            catch( Exception ignored ) {
            }
            try {
                if( encoder != null ) {
                    encoder.stop();
                    encoder.release();
                }
            }
            catch( Exception ignored ) {
            }
            try {
                if( muxer != null ) {
                    muxer.stop();
                    muxer.release();
                }
            }
            catch( Exception ignored ) {
            }
            try {
                if( video_extractor != null ) {
                    video_extractor.release();
                }
            }
            catch( Exception ignored ) {
            }
            try {
                if( audio_extractor != null ) {
                    audio_extractor.release();
                }
            }
            catch( Exception ignored ) {
            }
        }
        if( !success && output.exists() ) {
            //noinspection ResultOfMethodCallIgnored
            output.delete();
        }
        return success;
    }

    /** Waits (bounded) for an input buffer so a stalled encoder can never spin forever. */
    private static int dequeueInputBuffer(MediaCodec codec) {
        for( int i = 0; i < 200; i++ ) { // ~2s at 10ms per attempt
            int index = codec.dequeueInputBuffer(TIMEOUT_US);
            if( index >= 0 ) {
                return index;
            }
        }
        return -1;
    }

    /** Copies the cropped region of every plane of {@code src} into {@code dst}, honouring each
     *  plane's own row/pixel stride so it works regardless of the device's YUV layout. */
    private static void cropIntoImage(Image src, Image dst, int crop_x, int crop_y, int crop_w, int crop_h) {
        Image.Plane[] src_planes = src.getPlanes();
        Image.Plane[] dst_planes = dst.getPlanes();
        int planes = Math.min(src_planes.length, dst_planes.length);
        for( int p = 0; p < planes; p++ ) {
            // chroma planes are subsampled by 2 in both directions
            int shift = p == 0 ? 0 : 1;
            int ox = crop_x >> shift;
            int oy = crop_y >> shift;
            int w = crop_w >> shift;
            int h = crop_h >> shift;
            copyPlane(src_planes[p], dst_planes[p], ox, oy, w, h);
        }
    }

    private static void copyPlane(Image.Plane src_plane, Image.Plane dst_plane,
                                  int offset_x, int offset_y, int width, int height) {
        ByteBuffer src = src_plane.getBuffer();
        ByteBuffer dst = dst_plane.getBuffer();
        int src_row_stride = src_plane.getRowStride();
        int src_pixel_stride = src_plane.getPixelStride();
        int dst_row_stride = dst_plane.getRowStride();
        int dst_pixel_stride = dst_plane.getPixelStride();
        for( int y = 0; y < height; y++ ) {
            int src_row = (offset_y + y) * src_row_stride;
            int dst_row = y * dst_row_stride;
            for( int x = 0; x < width; x++ ) {
                byte value = src.get(src_row + (offset_x + x) * src_pixel_stride);
                dst.put(dst_row + x * dst_pixel_stride, value);
            }
        }
    }

    /** Copies audio samples whose timestamp is at or before {@code up_to_us} from the extractor to
     *  the muxer. Pass {@code Long.MAX_VALUE} to flush everything that remains. */
    private static void writeAudioUpTo(MediaExtractor extractor, MediaMuxer muxer, int track,
                                       long up_to_us, ByteBuffer buffer, MediaCodec.BufferInfo info) {
        while( true ) {
            long sample_time = extractor.getSampleTime();
            if( sample_time < 0 || sample_time > up_to_us ) {
                return;
            }
            int size = extractor.readSampleData(buffer, 0);
            if( size < 0 ) {
                return;
            }
            info.offset = 0;
            info.size = size;
            info.presentationTimeUs = sample_time;
            info.flags = extractor.getSampleFlags();
            muxer.writeSampleData(track, buffer, info);
            extractor.advance();
        }
    }

    private static MediaCodecInfo selectCodec(String mime) {
        MediaCodecList list = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
        for( MediaCodecInfo info : list.getCodecInfos() ) {
            if( !info.isEncoder() ) {
                continue;
            }
            for( String type : info.getSupportedTypes() ) {
                if( type.equalsIgnoreCase(mime) ) {
                    return info;
                }
            }
        }
        return null;
    }

    private static int selectColorFormat(MediaCodecInfo codec_info, String mime) {
        try {
            MediaCodecInfo.CodecCapabilities caps = codec_info.getCapabilitiesForType(mime);
            int[] preferred = new int[] {
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar,
            };
            for( int want : preferred ) {
                for( int have : caps.colorFormats ) {
                    if( have == want ) {
                        return want;
                    }
                }
            }
        }
        catch( Exception e ) {
            Log.e(TAG, "failed to query color formats", e);
        }
        return 0;
    }
}
