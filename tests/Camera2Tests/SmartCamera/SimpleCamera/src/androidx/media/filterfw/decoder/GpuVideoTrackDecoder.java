/*
 * Copyright (C) 2012 The Android Open Source Project
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
import android.graphics.SurfaceTexture.OnFrameAvailableListener;
import android.media.MediaCodec;
import android.media.MediaCodec.BufferInfo;
import android.media.MediaFormat;
import android.view.Surface;

import androidx.media.filterfw.FrameImage2D;
import androidx.media.filterfw.ImageShader;
import androidx.media.filterfw.TextureSource;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * {@link TrackDecoder} that decodes a video track and renders the frames onto a
 * {@link SurfaceTexture}.
 *
 * This implementation uses the GPU for image operations such as copying
 * and color-space conversion.
 */
@TargetApi(16)
public class GpuVideoTrackDecoder extends VideoTrackDecoder {

    /**
     * Identity fragment shader for external textures.
     */
    private static final String COPY_FRAGMENT_SHADER =
            "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float;\n" +
            "uniform samplerExternalOES tex_sampler_0;\n" +
            "varying vec2 v_texcoord;\n" +
            "void main() {\n" +
            "  gl_FragColor = texture2D(tex_sampler_0, v_texcoord);\n" +
            "}\n";

    private final TextureSource mTextureSource;
    private final SurfaceTexture mSurfaceTexture; // Access guarded by mFrameMonitor.
    private final float[] mTransformMatrix;

    private final int mOutputWidth;
    private final int mOutputHeight;

    private ImageShader mImageShader;

    private long mCurrentPresentationTimeUs;

    public GpuVideoTrackDecoder(
            int trackIndex, MediaFormat format, Listener listener) {
        super(trackIndex, format, listener);

        // Create a surface texture to be used by the video track decoder.
        mTextureSource = TextureSource.newExternalTexture();
        mSurfaceTexture = new SurfaceTexture(mTextureSource.getTextureId());
        mSurfaceTexture.detachFromGLContext();
        mSurfaceTexture.setOnFrameAvailableListener(new OnFrameAvailableListener() {
            @Override
            public void onFrameAvailable(SurfaceTexture surfaceTexture) {
                markFrameAvailable();
            }
        });

        mOutputWidth = format.getInteger(MediaFormat.KEY_WIDTH);
        mOutputHeight = format.getInteger(MediaFormat.KEY_HEIGHT);

        mTransformMatrix = new float[16];
    }

    @Override
    protected MediaCodec initMediaCodec(MediaFormat format) {
        MediaCodec mediaCodec;
        try {
            mediaCodec = MediaCodec.createDecoderByType(
                    format.getString(MediaFormat.KEY_MIME));
        } catch (IOException e) {
            throw new RuntimeException(
                    "failed to create decoder for "
                    + format.getString(MediaFormat.KEY_MIME), e);
        }
        Surface surface = new Surface(mSurfaceTexture);
        mediaCodec.configure(format, surface, null, 0);
        surface.release();
        return mediaCodec;
    }

    @Override
    protected boolean onDataAvailable(
            MediaCodec codec, ByteBuffer[] buffers, int bufferIndex, BufferInfo info) {
        boolean textureAvailable = waitForFrameGrab();

        mCurrentPresentationTimeUs = info.presentationTimeUs;

        // Only render the next frame if we weren't interrupted.
        codec.releaseOutputBuffer(bufferIndex, textureAvailable);

        if (textureAvailable) {
            if (updateTexture()) {
                notifyListener();
            }
        }

        return false;
    }

    /**
     * Waits for the texture's {@link OnFrameAvailableListener} to be notified and then updates
     * the internal {@link SurfaceTexture}.
     */
    private boolean updateTexture() {
        // Wait for the frame we just released to appear in the texture.
        synchronized (mFrameMonitor) {
            try {
                while (!mFrameAvailable) {
                    mFrameMonitor.wait();
                }
                mSurfaceTexture.attachToGLContext(mTextureSource.getTextureId());
                mSurfaceTexture.updateTexImage();
                mSurfaceTexture.detachFromGLContext();
                return true;
            } catch (InterruptedException e) {
                return false;
            }
        }
    }

    @Override
    protected void copyFrameDataTo(FrameImage2D outputVideoFrame, int rotation) {
        TextureSource targetTexture = TextureSource.newExternalTexture();
        mSurfaceTexture.attachToGLContext(targetTexture.getTextureId());
        mSurfaceTexture.getTransformMatrix(mTransformMatrix);

        ImageShader imageShader = getImageShader();
        imageShader.setSourceTransform(mTransformMatrix);

        int outputWidth = mOutputWidth;
        int outputHeight = mOutputHeight;
        if (rotation != 0) {
            float[] targetCoords = getRotationCoords(rotation);
            imageShader.setTargetCoords(targetCoords);
            if (needSwapDimension(rotation)) {
                outputWidth = mOutputHeight;
                outputHeight = mOutputWidth;
            }
        }
        outputVideoFrame.resize(new int[] { outputWidth, outputHeight });
        imageShader.process(
                targetTexture,
                outputVideoFrame.lockRenderTarget(),
                outputWidth,
                outputHeight);
        outputVideoFrame.setTimestamp(mCurrentPresentationTimeUs * 1000);
        outputVideoFrame.unlock();
        targetTexture.release();

        mSurfaceTexture.detachFromGLContext();
    }

    @Override
    public void release() {
        super.release();
        synchronized (mFrameMonitor) {
            mTextureSource.release();
            mSurfaceTexture.release();
        }
    }

    /*
     * This method has to be called on the MFF processing thread.
     */
    private ImageShader getImageShader() {
        if (mImageShader == null) {
            mImageShader = new ImageShader(COPY_FRAGMENT_SHADER);
            mImageShader.setTargetRect(0f, 1f, 1f, -1f);
        }
        return mImageShader;
    }

    /**
     * Get the quad coords for rotation.
     * @param rotation applied to the frame, value is one of
     *   {ROTATE_NONE, ROTATE_90_RIGHT, ROTATE_180, ROTATE_90_LEFT}
     * @return coords the calculated quad coords for the given rotation
     */
    private static float[] getRotationCoords(int rotation) {
         switch(rotation) {
             case MediaDecoder.ROTATE_90_RIGHT:
                 return new float[] { 0f, 0f, 0f, 1f, 1f, 0f, 1f, 1f };
             case MediaDecoder.ROTATE_180:
                 return new float[] { 1f, 0f, 0f, 0f, 1f, 1f, 0f, 1f };
             case MediaDecoder.ROTATE_90_LEFT:
                 return new float[] { 1f, 1f, 1f, 0f, 0f, 1f, 0f, 0f };
             case MediaDecoder.ROTATE_NONE:
                 return new float[] { 0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f };
             default:
                 throw new IllegalArgumentException("Unsupported rotation angle.");
         }
     }

}
