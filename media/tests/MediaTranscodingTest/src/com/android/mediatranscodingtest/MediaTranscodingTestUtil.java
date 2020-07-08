/*
 * Copyright (C) 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.mediatranscodingtest;

import static org.junit.Assert.assertTrue;

import android.content.ContentResolver;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.ImageFormat;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.util.Log;
import android.util.Size;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Locale;


/* package */ class MediaTranscodingTestUtil {
    private static final String TAG = "MediaTranscodingTestUtil";

    // Helper class to extract the information from source file and transcoded file.
    static class VideoFileInfo {
        String mUri;
        int mNumVideoFrames = 0;
        int mWidth = 0;
        int mHeight = 0;
        float mVideoFrameRate = 0.0f;
        boolean mHasAudio = false;

        public String toString() {
            String str = mUri;
            str += " Width:" + mWidth;
            str += " Height:" + mHeight;
            str += " FrameRate:" + mWidth;
            str += " FrameCount:" + mNumVideoFrames;
            str +=  " HasAudio:" + (mHasAudio ? "Yes" : "No");
            return str;
        }
    }

    static VideoFileInfo extractVideoFileInfo(Context ctx, Uri videoUri) throws IOException {
        VideoFileInfo info = new VideoFileInfo();
        AssetFileDescriptor afd =  null;
        MediaMetadataRetriever retriever = null;

        try {
            afd = ctx.getContentResolver().openAssetFileDescriptor(videoUri, "r");
            retriever = new MediaMetadataRetriever();
            retriever.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());

            info.mUri = videoUri.getLastPathSegment();
            Log.i(TAG, "Trying to transcode to " + info.mUri);
            String width = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
            String height = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
            if (width != null && height != null) {
                info.mWidth = Integer.parseInt(width);
                info.mHeight = Integer.parseInt(height);
            }

            String frameRate = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE);
            if (frameRate != null) {
                info.mVideoFrameRate = Float.parseFloat(frameRate);
            }

            String frameCount = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT);
            if (frameCount != null) {
                info.mNumVideoFrames = Integer.parseInt(frameCount);
            }

            String hasAudio = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO);
            if (hasAudio != null) {
                info.mHasAudio = hasAudio.equals("yes");
            }
        } finally {
            if (retriever != null) {
                retriever.close();
            }
            if (afd != null) {
                afd.close();
            }
        }
        return info;
    }

    static VideoTranscodingStatistics computeStats(final Context ctx, final Uri sourceMp4,
            final Uri transcodedMp4)
            throws Exception {
        // First decode the sourceMp4 to a temp yuv in yuv420p format.
        Uri sourceYUV420PUri = Uri.parse(ContentResolver.SCHEME_FILE + "://"
                + ctx.getCacheDir().getAbsolutePath() + "/sourceYUV420P.yuv");
        Size sourceSize = decodeMp4ToYuv(ctx, sourceMp4, sourceYUV420PUri);

        // Second decode the transcodedMp4 to a temp yuv in yuv420p format.
        Uri transcodedYUV420PUri = Uri.parse(ContentResolver.SCHEME_FILE + "://"
                + ctx.getCacheDir().getAbsolutePath() + "/transcodedYUV420P.yuv");
        Size transcodedSize = decodeMp4ToYuv(ctx, transcodedMp4, transcodedYUV420PUri);

        assertTrue(sourceSize.equals(transcodedSize));

        // Then Compute the psnr of transcodedYUV420PUri against sourceYUV420PUri.
        return computePsnr(ctx, sourceYUV420PUri, transcodedYUV420PUri, sourceSize.getWidth(),
                transcodedSize.getHeight());
    }

    private static Size decodeMp4ToYuv(final Context ctx, final Uri fileUri, final Uri yuvUri)
            throws Exception {
        AssetFileDescriptor fileFd = null;
        MediaExtractor extractor = null;
        MediaCodec codec = null;
        AssetFileDescriptor yuvFd = null;
        FileOutputStream out = null;
        int width = 0;
        int height = 0;

        try {
            fileFd = ctx.getContentResolver().openAssetFileDescriptor(fileUri,"r");
            extractor = new MediaExtractor();
            extractor.setDataSource(fileFd.getFileDescriptor(), fileFd.getStartOffset(),
                    fileFd.getLength());

            // Selects the video track.
            int trackCount = extractor.getTrackCount();
            if (trackCount <= 0) {
                throw new IllegalArgumentException("Invalid mp4 file");
            }
            int videoTrackIndex = -1;
            for (int i = 0; i < trackCount; i++) {
                extractor.selectTrack(i);
                MediaFormat format = extractor.getTrackFormat(i);
                if (format.getString(MediaFormat.KEY_MIME).startsWith("video/")) {
                    videoTrackIndex = i;
                    break;
                }
            }
            if (videoTrackIndex == -1) {
                throw new IllegalArgumentException("Can not find video track");
            }

            extractor.selectTrack(videoTrackIndex);
            MediaFormat format = extractor.getTrackFormat(videoTrackIndex);
            String mime = format.getString(MediaFormat.KEY_MIME);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar);

            // Opens the yuv file uri.
            yuvFd = ctx.getContentResolver().openAssetFileDescriptor(yuvUri,
                    "w");
            out = new FileOutputStream(yuvFd.getFileDescriptor());

            codec = MediaCodec.createDecoderByType(mime);
            codec.configure(format,
                    null,  // surface
                    null,  // crypto
                    0);    // flags
            codec.start();

            ByteBuffer[] inputBuffers = codec.getInputBuffers();
            ByteBuffer[] outputBuffers = codec.getOutputBuffers();
            MediaFormat decoderOutputFormat = codec.getInputFormat();

            // start decode loop
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

            final long kTimeOutUs = 1000; // 1ms timeout
            long lastOutputTimeUs = 0;
            boolean sawInputEOS = false;
            boolean sawOutputEOS = false;
            int inputNum = 0;
            int outputNum = 0;
            boolean advanceDone = true;

            long start = System.currentTimeMillis();
            while (!sawOutputEOS) {
                // handle input
                if (!sawInputEOS) {
                    int inputBufIndex = codec.dequeueInputBuffer(kTimeOutUs);

                    if (inputBufIndex >= 0) {
                        ByteBuffer dstBuf = inputBuffers[inputBufIndex];
                        // sample contains the buffer and the PTS offset normalized to frame index
                        int sampleSize =
                                extractor.readSampleData(dstBuf, 0 /* offset */);
                        long presentationTimeUs = extractor.getSampleTime();
                        advanceDone = extractor.advance();

                        if (sampleSize < 0) {
                            Log.d(TAG, "saw input EOS.");
                            sawInputEOS = true;
                            sampleSize = 0;
                        }
                        codec.queueInputBuffer(
                                inputBufIndex,
                                0 /* offset */,
                                sampleSize,
                                presentationTimeUs,
                                sawInputEOS ? MediaCodec.BUFFER_FLAG_END_OF_STREAM : 0);
                    } else {
                        Log.d(TAG, "codec.dequeueInputBuffer() unrecognized return value:");
                    }
                }

                // handle output
                int outputBufIndex = codec.dequeueOutputBuffer(info, kTimeOutUs);

                if (outputBufIndex >= 0) {
                    if (info.size > 0) { // Disregard 0-sized buffers at the end.
                        outputNum++;
                        Log.i(TAG, "Output frame numer " + outputNum);
                        Image image = codec.getOutputImage(outputBufIndex);
                        dumpYUV420PToFile(image, out);
                    }

                    codec.releaseOutputBuffer(outputBufIndex, false /* render */);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        Log.d(TAG, "saw output EOS.");
                        sawOutputEOS = true;
                    }
                } else if (outputBufIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                    outputBuffers = codec.getOutputBuffers();
                    Log.d(TAG, "output buffers have changed.");
                } else if (outputBufIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    decoderOutputFormat = codec.getOutputFormat();
                    width = decoderOutputFormat.getInteger(MediaFormat.KEY_WIDTH);
                    height = decoderOutputFormat.getInteger(MediaFormat.KEY_HEIGHT);
                    Log.d(TAG, "output resolution " + width + "x" + height);
                } else {
                    Log.w(TAG, "codec.dequeueOutputBuffer() unrecognized return index");
                }
            }
        } finally {
            codec.stop();
            codec.release();
            extractor.release();
            out.close();
            fileFd.close();
            yuvFd.close();
        }
        return new Size(width, height);
    }

    private static void dumpYUV420PToFile(Image image, FileOutputStream out) throws IOException {
        int format = image.getFormat();

        if (ImageFormat.YUV_420_888 != format) {
            throw new UnsupportedOperationException("Only supports YUV420P");
        }

        int imageWidth = image.getWidth();
        int imageHeight = image.getHeight();
        byte[] bb = new byte[imageWidth * imageHeight];
        byte[] lb = null;
        Image.Plane[] planes = image.getPlanes();
        for (int i = 0; i < planes.length; ++i) {
            ByteBuffer buf = planes[i].getBuffer();

            int width, height, rowStride, pixelStride, x, y;
            rowStride = planes[i].getRowStride();
            pixelStride = planes[i].getPixelStride();
            if (i == 0) {
                width = imageWidth;
                height = imageHeight;
            } else {
                width = imageWidth / 2;
                height = imageHeight / 2;
            }

            if (buf.hasArray()) {
                byte b[] = buf.array();
                int offs = buf.arrayOffset();
                if (pixelStride == 1) {
                    for (y = 0; y < height; ++y) {
                        System.arraycopy(bb, y * width, b, y * rowStride + offs, width);
                    }
                } else {
                    // do it pixel-by-pixel
                    for (y = 0; y < height; ++y) {
                        int lineOffset = offs + y * rowStride;
                        for (x = 0; x < width; ++x) {
                            bb[y * width + x] = b[lineOffset + x * pixelStride];
                        }
                    }
                }
            } else { // almost always ends up here due to direct buffers
                int pos = buf.position();
                if (pixelStride == 1) {
                    for (y = 0; y < height; ++y) {
                        buf.position(pos + y * rowStride);
                        buf.get(bb, y * width, width);
                    }
                } else {
                    // Reallocate linebuffer if necessary.
                    if (lb == null || lb.length < rowStride) {
                        lb = new byte[rowStride];
                    }
                    // do it pixel-by-pixel
                    for (y = 0; y < height; ++y) {
                        buf.position(pos + y * rowStride);
                        // we're only guaranteed to have pixelStride * (width - 1) + 1 bytes
                        buf.get(lb, 0, pixelStride * (width - 1) + 1);
                        for (x = 0; x < width; ++x) {
                            bb[y * width + x] = lb[x * pixelStride];
                        }
                    }
                }
                buf.position(pos);
            }
            // Write out the buffer to the output.
            out.write(bb, 0, width * height);
        }
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////
    // The following psnr code is leveraged from the following file with minor modification:
    // cts/tests/tests/media/src/android/media/cts/VideoCodecTestBase.java
    ////////////////////////////////////////////////////////////////////////////////////////////////

    /**
     * Calculates PSNR value between two video frames.
     */
    private static double computePSNR(byte[] data0, byte[] data1) {
        long squareError = 0;
        assertTrue(data0.length == data1.length);
        int length = data0.length;
        for (int i = 0; i < length; i++) {
            int diff = ((int) data0[i] & 0xff) - ((int) data1[i] & 0xff);
            squareError += diff * diff;
        }
        double meanSquareError = (double) squareError / length;
        double psnr = 10 * Math.log10((double) 255 * 255 / meanSquareError);
        return psnr;
    }

    /**
     * Calculates average and minimum PSNR values between
     * set of reference and decoded video frames.
     * Runs PSNR calculation for the full duration of the decoded data.
     */
    private static VideoTranscodingStatistics computePsnr(
            Context ctx,
            Uri referenceYuvFileUri,
            Uri decodedYuvFileUri,
            int width,
            int height) throws Exception {
        VideoTranscodingStatistics statistics = new VideoTranscodingStatistics();
        AssetFileDescriptor referenceFd = ctx.getContentResolver().openAssetFileDescriptor(
                referenceYuvFileUri, "r");
        InputStream referenceStream = new FileInputStream(referenceFd.getFileDescriptor());

        AssetFileDescriptor decodedFd = ctx.getContentResolver().openAssetFileDescriptor(
                decodedYuvFileUri, "r");
        InputStream decodedStream = new FileInputStream(decodedFd.getFileDescriptor());

        int ySize = width * height;
        int uvSize = width * height / 4;
        byte[] yRef = new byte[ySize];
        byte[] yDec = new byte[ySize];
        byte[] uvRef = new byte[uvSize];
        byte[] uvDec = new byte[uvSize];

        int frames = 0;
        double averageYPSNR = 0;
        double averageUPSNR = 0;
        double averageVPSNR = 0;
        double minimumYPSNR = Integer.MAX_VALUE;
        double minimumUPSNR = Integer.MAX_VALUE;
        double minimumVPSNR = Integer.MAX_VALUE;
        int minimumPSNRFrameIndex = 0;

        while (true) {
            // Calculate Y PSNR.
            int bytesReadRef = referenceStream.read(yRef);
            int bytesReadDec = decodedStream.read(yDec);
            if (bytesReadDec == -1) {
                break;
            }
            if (bytesReadRef == -1) {
                // Reference file wrapping up
                referenceStream.close();
                break;
            }
            double curYPSNR = computePSNR(yRef, yDec);
            averageYPSNR += curYPSNR;
            minimumYPSNR = Math.min(minimumYPSNR, curYPSNR);
            double curMinimumPSNR = curYPSNR;

            // Calculate U PSNR.
            bytesReadRef = referenceStream.read(uvRef);
            bytesReadDec = decodedStream.read(uvDec);
            double curUPSNR = computePSNR(uvRef, uvDec);
            averageUPSNR += curUPSNR;
            minimumUPSNR = Math.min(minimumUPSNR, curUPSNR);
            curMinimumPSNR = Math.min(curMinimumPSNR, curUPSNR);

            // Calculate V PSNR.
            bytesReadRef = referenceStream.read(uvRef);
            bytesReadDec = decodedStream.read(uvDec);
            double curVPSNR = computePSNR(uvRef, uvDec);
            averageVPSNR += curVPSNR;
            minimumVPSNR = Math.min(minimumVPSNR, curVPSNR);
            curMinimumPSNR = Math.min(curMinimumPSNR, curVPSNR);

            // Frame index for minimum PSNR value - help to detect possible distortions
            if (curMinimumPSNR < statistics.mMinimumPSNR) {
                statistics.mMinimumPSNR = curMinimumPSNR;
                minimumPSNRFrameIndex = frames;
            }

            String logStr = String.format(Locale.US, "PSNR #%d: Y: %.2f. U: %.2f. V: %.2f",
                    frames, curYPSNR, curUPSNR, curVPSNR);
            Log.v(TAG, logStr);

            frames++;
        }

        averageYPSNR /= frames;
        averageUPSNR /= frames;
        averageVPSNR /= frames;
        statistics.mAveragePSNR = (4 * averageYPSNR + averageUPSNR + averageVPSNR) / 6;

        Log.d(TAG, "PSNR statistics for " + frames + " frames.");
        String logStr = String.format(Locale.US,
                "Average PSNR: Y: %.1f. U: %.1f. V: %.1f. Average: %.1f",
                averageYPSNR, averageUPSNR, averageVPSNR, statistics.mAveragePSNR);
        Log.d(TAG, logStr);
        logStr = String.format(Locale.US,
                "Minimum PSNR: Y: %.1f. U: %.1f. V: %.1f. Overall: %.1f at frame %d",
                minimumYPSNR, minimumUPSNR, minimumVPSNR,
                statistics.mMinimumPSNR, minimumPSNRFrameIndex);
        Log.d(TAG, logStr);

        referenceStream.close();
        decodedStream.close();
        referenceFd.close();
        decodedFd.close();
        return statistics;
    }

    /**
     * Transcoding PSNR statistics.
     */
    protected static class VideoTranscodingStatistics {
        public double mAveragePSNR;
        public double mMinimumPSNR;

        VideoTranscodingStatistics() {
            mMinimumPSNR = Integer.MAX_VALUE;
        }
    }
}

