/*
 * Copyright 2013 The Android Open Source Project
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
package androidx.media.filterfw.decoder;

import android.annotation.TargetApi;
import android.graphics.SurfaceTexture;
import android.media.MediaCodec;
import android.media.MediaCodec.BufferInfo;
import android.media.MediaCodecInfo;
import android.media.MediaCodecInfo.CodecCapabilities;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.util.SparseIntArray;
import androidx.media.filterfw.ColorSpace;
import androidx.media.filterfw.Frame;
import androidx.media.filterfw.FrameImage2D;
import androidx.media.filterfw.PixelUtils;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeMap;

/**
 * {@link TrackDecoder} that decodes a video track and renders the frames onto a
 * {@link SurfaceTexture}.
 *
 * This implementation purely uses CPU based methods to decode and color-convert the frames.
 */
@TargetApi(16)
public class CpuVideoTrackDecoder extends VideoTrackDecoder {

    private static final int COLOR_FORMAT_UNSET = -1;

    private final int mWidth;
    private final int mHeight;

    private int mColorFormat = COLOR_FORMAT_UNSET;
    private long mCurrentPresentationTimeUs;
    private ByteBuffer mDecodedBuffer;
    private ByteBuffer mUnrotatedBytes;

    protected CpuVideoTrackDecoder(int trackIndex, MediaFormat format, Listener listener) {
        super(trackIndex, format, listener);

        mWidth = format.getInteger(MediaFormat.KEY_WIDTH);
        mHeight = format.getInteger(MediaFormat.KEY_HEIGHT);
    }

    @Override
    protected MediaCodec initMediaCodec(MediaFormat format) {
        // Find a codec for our video that can output to one of our supported color-spaces
        MediaCodec mediaCodec = findDecoderCodec(format, new int[] {
                CodecCapabilities.COLOR_Format32bitARGB8888,
                CodecCapabilities.COLOR_FormatYUV420Planar});
        if (mediaCodec == null) {
            throw new RuntimeException(
                    "Could not find a suitable decoder for format: " + format + "!");
        }
        mediaCodec.configure(format, null, null, 0);
        return mediaCodec;
    }

    @Override
    protected boolean onDataAvailable(
            MediaCodec codec, ByteBuffer[] buffers, int bufferIndex, BufferInfo info) {

        mCurrentPresentationTimeUs = info.presentationTimeUs;
        mDecodedBuffer = buffers[bufferIndex];

        if (mColorFormat == -1) {
            mColorFormat = codec.getOutputFormat().getInteger(MediaFormat.KEY_COLOR_FORMAT);
        }

        markFrameAvailable();
        notifyListener();

        // Wait for the grab before we release this buffer.
        waitForFrameGrab();

        codec.releaseOutputBuffer(bufferIndex, false);

        return false;
    }

    @Override
    protected void copyFrameDataTo(FrameImage2D outputVideoFrame, int rotation) {
        // Calculate output dimensions
        int outputWidth = mWidth;
        int outputHeight = mHeight;
        if (needSwapDimension(rotation)) {
            outputWidth = mHeight;
            outputHeight = mWidth;
        }

        // Create output frame
        outputVideoFrame.resize(new int[] {outputWidth, outputHeight});
        outputVideoFrame.setTimestamp(mCurrentPresentationTimeUs * 1000);
        ByteBuffer outBytes = outputVideoFrame.lockBytes(Frame.MODE_WRITE);

        // Set data
        if (rotation == MediaDecoder.ROTATE_NONE) {
            convertImage(mDecodedBuffer, outBytes, mColorFormat, mWidth, mHeight);
        } else {
            if (mUnrotatedBytes == null) {
                mUnrotatedBytes = ByteBuffer.allocateDirect(mWidth * mHeight * 4);
            }
            // TODO: This could be optimized by including the rotation in the color conversion.
            convertImage(mDecodedBuffer, mUnrotatedBytes, mColorFormat, mWidth, mHeight);
            copyRotate(mUnrotatedBytes, outBytes, rotation);
        }
        outputVideoFrame.unlock();
    }

    /**
     * Copy the input data to the output data applying the specified rotation.
     *
     * @param input The input image data
     * @param output Buffer for the output image data
     * @param rotation The rotation to apply
     */
    private void copyRotate(ByteBuffer input, ByteBuffer output, int rotation) {
        int offset;
        int pixStride;
        int rowStride;
        switch (rotation) {
            case MediaDecoder.ROTATE_NONE:
                offset = 0;
                pixStride = 1;
                rowStride = mWidth;
                break;
            case MediaDecoder.ROTATE_90_LEFT:
                offset = (mWidth - 1) * mHeight;
                pixStride = -mHeight;
                rowStride = 1;
                break;
            case MediaDecoder.ROTATE_90_RIGHT:
                offset = mHeight - 1;
                pixStride = mHeight;
                rowStride = -1;
                break;
            case MediaDecoder.ROTATE_180:
                offset = mWidth * mHeight - 1;
                pixStride = -1;
                rowStride = -mWidth;
                break;
            default:
                throw new IllegalArgumentException("Unsupported rotation " + rotation + "!");
        }
        PixelUtils.copyPixels(input, output, mWidth, mHeight, offset, pixStride, rowStride);
    }

    /**
     * Looks for a codec with the specified requirements.
     *
     * The set of codecs will be filtered down to those that meet the following requirements:
     * <ol>
     *   <li>The codec is a decoder.</li>
     *   <li>The codec can decode a video of the specified format.</li>
     *   <li>The codec can decode to one of the specified color formats.</li>
     * </ol>
     * If multiple codecs are found, the one with the preferred color-format is taken. Color format
     * preference is determined by the order of their appearance in the color format array.
     *
     * @param format The format the codec must decode.
     * @param requiredColorFormats Array of target color spaces ordered by preference.
     * @return A codec that meets the requirements, or null if no such codec was found.
     */
    private static MediaCodec findDecoderCodec(MediaFormat format, int[] requiredColorFormats) {
        TreeMap<Integer, String> candidateCodecs = new TreeMap<Integer, String>();
        SparseIntArray colorPriorities = intArrayToPriorityMap(requiredColorFormats);
        for (int i = 0; i < MediaCodecList.getCodecCount(); ++i) {
            // Get next codec
            MediaCodecInfo info = MediaCodecList.getCodecInfoAt(i);

            // Check that this is a decoder
            if (info.isEncoder()) {
                continue;
            }

            // Check if this codec can decode the video in question
            String requiredType = format.getString(MediaFormat.KEY_MIME);
            String[] supportedTypes = info.getSupportedTypes();
            Set<String> typeSet = new HashSet<String>(Arrays.asList(supportedTypes));

            // Check if it can decode to one of the required color formats
            if (typeSet.contains(requiredType)) {
                CodecCapabilities capabilities = info.getCapabilitiesForType(requiredType);
                for (int supportedColorFormat : capabilities.colorFormats) {
                    if (colorPriorities.indexOfKey(supportedColorFormat) >= 0) {
                        int priority = colorPriorities.get(supportedColorFormat);
                        candidateCodecs.put(priority, info.getName());
                    }
                }
            }
        }

        // Pick the best codec (with the highest color priority)
        if (candidateCodecs.isEmpty()) {
            return null;
        } else {
            String bestCodec = candidateCodecs.firstEntry().getValue();
            return MediaCodec.createByCodecName(bestCodec);
        }
    }

    private static SparseIntArray intArrayToPriorityMap(int[] values) {
        SparseIntArray result = new SparseIntArray();
        for (int priority = 0; priority < values.length; ++priority) {
            result.append(values[priority], priority);
        }
        return result;
    }

    private static void convertImage(
            ByteBuffer input, ByteBuffer output, int colorFormat, int width, int height) {
        switch (colorFormat) {
            case CodecCapabilities.COLOR_Format32bitARGB8888:
                ColorSpace.convertArgb8888ToRgba8888(input, output, width, height);
                break;
            case CodecCapabilities.COLOR_FormatYUV420Planar:
                ColorSpace.convertYuv420pToRgba8888(input, output, width, height);
                break;
            default:
                throw new RuntimeException("Unsupported color format: " + colorFormat + "!");
        }
    }

}
