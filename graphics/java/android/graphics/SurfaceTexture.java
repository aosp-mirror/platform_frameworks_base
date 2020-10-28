/*
 * Copyright (C) 2010 The Android Open Source Project
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

package android.graphics;

import android.annotation.Nullable;
import android.compat.annotation.UnsupportedAppUsage;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.view.Surface;

import java.lang.ref.WeakReference;

/**
 * Captures frames from an image stream as an OpenGL ES texture.
 *
 * <p>The image stream may come from either camera preview or video decode. A
 * {@link android.view.Surface} created from a SurfaceTexture can be used as an output
 * destination for the {@link android.hardware.camera2}, {@link android.media.MediaCodec},
 * {@link android.media.MediaPlayer}, and {@link android.renderscript.Allocation} APIs.
 * When {@link #updateTexImage} is called, the contents of the texture object specified
 * when the SurfaceTexture was created are updated to contain the most recent image from the image
 * stream.  This may cause some frames of the stream to be skipped.
 *
 * <p>A SurfaceTexture may also be used in place of a SurfaceHolder when specifying the output
 * destination of the older {@link android.hardware.Camera} API. Doing so will cause all the
 * frames from the image stream to be sent to the SurfaceTexture object rather than to the device's
 * display.
 *
 * <p>When sampling from the texture one should first transform the texture coordinates using the
 * matrix queried via {@link #getTransformMatrix(float[])}.  The transform matrix may change each
 * time {@link #updateTexImage} is called, so it should be re-queried each time the texture image
 * is updated.
 * This matrix transforms traditional 2D OpenGL ES texture coordinate column vectors of the form (s,
 * t, 0, 1) where s and t are on the inclusive interval [0, 1] to the proper sampling location in
 * the streamed texture.  This transform compensates for any properties of the image stream source
 * that cause it to appear different from a traditional OpenGL ES texture.  For example, sampling
 * from the bottom left corner of the image can be accomplished by transforming the column vector
 * (0, 0, 0, 1) using the queried matrix, while sampling from the top right corner of the image can
 * be done by transforming (1, 1, 0, 1).
 *
 * <p>The texture object uses the GL_TEXTURE_EXTERNAL_OES texture target, which is defined by the
 * <a href="http://www.khronos.org/registry/gles/extensions/OES/OES_EGL_image_external.txt">
 * GL_OES_EGL_image_external</a> OpenGL ES extension.  This limits how the texture may be used.
 * Each time the texture is bound it must be bound to the GL_TEXTURE_EXTERNAL_OES target rather than
 * the GL_TEXTURE_2D target.  Additionally, any OpenGL ES 2.0 shader that samples from the texture
 * must declare its use of this extension using, for example, an "#extension
 * GL_OES_EGL_image_external : require" directive.  Such shaders must also access the texture using
 * the samplerExternalOES GLSL sampler type.
 *
 * <p>SurfaceTexture objects may be created on any thread.  {@link #updateTexImage} may only be
 * called on the thread with the OpenGL ES context that contains the texture object.  The
 * frame-available callback is called on an arbitrary thread, so unless special care is taken {@link
 * #updateTexImage} should not be called directly from the callback.
 */
public class SurfaceTexture {
    private final Looper mCreatorLooper;
    @UnsupportedAppUsage
    private Handler mOnFrameAvailableHandler;

    /**
     * These fields are used by native code, do not access or modify.
     */
    @UnsupportedAppUsage
    private long mSurfaceTexture;
    @UnsupportedAppUsage
    private long mProducer;
    @UnsupportedAppUsage
    private long mFrameAvailableListener;

    private boolean mIsSingleBuffered;

    /**
     * Callback interface for being notified that a new stream frame is available.
     */
    public interface OnFrameAvailableListener {
        void onFrameAvailable(SurfaceTexture surfaceTexture);
    }

    /**
     * Exception thrown when a SurfaceTexture couldn't be created or resized.
     *
     * @deprecated No longer thrown. {@link android.view.Surface.OutOfResourcesException}
     * is used instead.
     */
    @SuppressWarnings("serial")
    @Deprecated
    public static class OutOfResourcesException extends Exception {
        public OutOfResourcesException() {
        }
        public OutOfResourcesException(String name) {
            super(name);
        }
    }

    /**
     * Construct a new SurfaceTexture to stream images to a given OpenGL texture.
     *
     * @param texName the OpenGL texture object name (e.g. generated via glGenTextures)
     *
     * @throws android.view.Surface.OutOfResourcesException If the SurfaceTexture cannot be created.
     */
    public SurfaceTexture(int texName) {
        this(texName, false);
    }

    /**
     * Construct a new SurfaceTexture to stream images to a given OpenGL texture.
     * <p>
     * In single buffered mode the application is responsible for serializing access to the image
     * content buffer. Each time the image content is to be updated, the
     * {@link #releaseTexImage()} method must be called before the image content producer takes
     * ownership of the buffer. For example, when producing image content with the NDK
     * ANativeWindow_lock and ANativeWindow_unlockAndPost functions, {@link #releaseTexImage()}
     * must be called before each ANativeWindow_lock, or that call will fail. When producing
     * image content with OpenGL ES, {@link #releaseTexImage()} must be called before the first
     * OpenGL ES function call each frame.
     *
     * @param texName the OpenGL texture object name (e.g. generated via glGenTextures)
     * @param singleBufferMode whether the SurfaceTexture will be in single buffered mode.
     *
     * @throws android.view.Surface.OutOfResourcesException If the SurfaceTexture cannot be created.
     */
    public SurfaceTexture(int texName, boolean singleBufferMode) {
        mCreatorLooper = Looper.myLooper();
        mIsSingleBuffered = singleBufferMode;
        nativeInit(false, texName, singleBufferMode, new WeakReference<SurfaceTexture>(this));
    }

    /**
     * Construct a new SurfaceTexture to stream images to a given OpenGL texture.
     * <p>
     * In single buffered mode the application is responsible for serializing access to the image
     * content buffer. Each time the image content is to be updated, the
     * {@link #releaseTexImage()} method must be called before the image content producer takes
     * ownership of the buffer. For example, when producing image content with the NDK
     * ANativeWindow_lock and ANativeWindow_unlockAndPost functions, {@link #releaseTexImage()}
     * must be called before each ANativeWindow_lock, or that call will fail. When producing
     * image content with OpenGL ES, {@link #releaseTexImage()} must be called before the first
     * OpenGL ES function call each frame.
     * <p>
     * Unlike {@link #SurfaceTexture(int, boolean)}, which takes an OpenGL texture object name,
     * this constructor creates the SurfaceTexture in detached mode. A texture name must be passed
     * in using {@link #attachToGLContext} before calling {@link #releaseTexImage()} and producing
     * image content using OpenGL ES.
     *
     * @param singleBufferMode whether the SurfaceTexture will be in single buffered mode.
     *
     * @throws android.view.Surface.OutOfResourcesException If the SurfaceTexture cannot be created.
     */
    public SurfaceTexture(boolean singleBufferMode) {
        mCreatorLooper = Looper.myLooper();
        mIsSingleBuffered = singleBufferMode;
        nativeInit(true, 0, singleBufferMode, new WeakReference<SurfaceTexture>(this));
    }

    /**
     * Register a callback to be invoked when a new image frame becomes available to the
     * SurfaceTexture.
     * <p>
     * The callback may be called on an arbitrary thread, so it is not
     * safe to call {@link #updateTexImage} without first binding the OpenGL ES context to the
     * thread invoking the callback.
     * </p>
     *
     * @param listener The listener to use, or null to remove the listener.
     */
    public void setOnFrameAvailableListener(@Nullable OnFrameAvailableListener listener) {
        setOnFrameAvailableListener(listener, null);
    }

    /**
     * Register a callback to be invoked when a new image frame becomes available to the
     * SurfaceTexture.
     * <p>
     * If a handler is specified, the callback will be invoked on that handler's thread.
     * If no handler is specified, then the callback may be called on an arbitrary thread,
     * so it is not safe to call {@link #updateTexImage} without first binding the OpenGL ES
     * context to the thread invoking the callback.
     * </p>
     *
     * @param listener The listener to use, or null to remove the listener.
     * @param handler The handler on which the listener should be invoked, or null
     * to use an arbitrary thread.
     */
    public void setOnFrameAvailableListener(@Nullable final OnFrameAvailableListener listener,
            @Nullable Handler handler) {
        if (listener != null) {
            // Although we claim the thread is arbitrary, earlier implementation would
            // prefer to send the callback on the creating looper or the main looper
            // so we preserve this behavior here.
            Looper looper = handler != null ? handler.getLooper() :
                    mCreatorLooper != null ? mCreatorLooper : Looper.getMainLooper();
            mOnFrameAvailableHandler = new Handler(looper, null, true /*async*/) {
                @Override
                public void handleMessage(Message msg) {
                    listener.onFrameAvailable(SurfaceTexture.this);
                }
            };
        } else {
            mOnFrameAvailableHandler = null;
        }
    }

    /**
     * Set the default size of the image buffers.  The image producer may override the buffer size,
     * in which case the producer-set buffer size will be used, not the default size set by this
     * method.  Both video and camera based image producers do override the size.  This method may
     * be used to set the image size when producing images with {@link android.graphics.Canvas} (via
     * {@link android.view.Surface#lockCanvas}), or OpenGL ES (via an EGLSurface).
     * <p>
     * The new default buffer size will take effect the next time the image producer requests a
     * buffer to fill.  For {@link android.graphics.Canvas} this will be the next time {@link
     * android.view.Surface#lockCanvas} is called.  For OpenGL ES, the EGLSurface should be
     * destroyed (via eglDestroySurface), made not-current (via eglMakeCurrent), and then recreated
     * (via {@code eglCreateWindowSurface}) to ensure that the new default size has taken effect.
     * <p>
     * The width and height parameters must be no greater than the minimum of
     * {@code GL_MAX_VIEWPORT_DIMS} and {@code GL_MAX_TEXTURE_SIZE} (see
     * {@link javax.microedition.khronos.opengles.GL10#glGetIntegerv glGetIntegerv}).
     * An error due to invalid dimensions might not be reported until
     * updateTexImage() is called.
     */
    public void setDefaultBufferSize(int width, int height) {
        nativeSetDefaultBufferSize(width, height);
    }

    /**
     * Update the texture image to the most recent frame from the image stream.  This may only be
     * called while the OpenGL ES context that owns the texture is current on the calling thread.
     * It will implicitly bind its texture to the {@code GL_TEXTURE_EXTERNAL_OES} texture target.
     */
    public void updateTexImage() {
        nativeUpdateTexImage();
    }

    /**
     * Releases the the texture content. This is needed in single buffered mode to allow the image
     * content producer to take ownership of the image buffer.
     * <p>
     * For more information see {@link #SurfaceTexture(int, boolean)}.
     */
    public void releaseTexImage() {
        nativeReleaseTexImage();
    }

    /**
     * Detach the SurfaceTexture from the OpenGL ES context that owns the OpenGL ES texture object.
     * This call must be made with the OpenGL ES context current on the calling thread.  The OpenGL
     * ES texture object will be deleted as a result of this call.  After calling this method all
     * calls to {@link #updateTexImage} will throw an {@link java.lang.IllegalStateException} until
     * a successful call to {@link #attachToGLContext} is made.
     * <p>
     * This can be used to access the SurfaceTexture image contents from multiple OpenGL ES
     * contexts.  Note, however, that the image contents are only accessible from one OpenGL ES
     * context at a time.
     */
    public void detachFromGLContext() {
        int err = nativeDetachFromGLContext();
        if (err != 0) {
            throw new RuntimeException("Error during detachFromGLContext (see logcat for details)");
        }
    }

    /**
     * Attach the SurfaceTexture to the OpenGL ES context that is current on the calling thread.  A
     * new OpenGL ES texture object is created and populated with the SurfaceTexture image frame
     * that was current at the time of the last call to {@link #detachFromGLContext}.  This new
     * texture is bound to the {@code GL_TEXTURE_EXTERNAL_OES} texture target.
     * <p>
     * This can be used to access the SurfaceTexture image contents from multiple OpenGL ES
     * contexts.  Note, however, that the image contents are only accessible from one OpenGL ES
     * context at a time.
     *
     * @param texName The name of the OpenGL ES texture that will be created.  This texture name
     * must be unusued in the OpenGL ES context that is current on the calling thread.
     */
    public void attachToGLContext(int texName) {
        int err = nativeAttachToGLContext(texName);
        if (err != 0) {
            throw new RuntimeException("Error during attachToGLContext (see logcat for details)");
        }
    }

    /**
     * Retrieve the 4x4 texture coordinate transform matrix associated with the texture image set by
     * the most recent call to {@link #updateTexImage}.
     * <p>
     * This transform matrix maps 2D homogeneous texture coordinates of the form (s, t, 0, 1) with s
     * and t in the inclusive range [0, 1] to the texture coordinate that should be used to sample
     * that location from the texture.  Sampling the texture outside of the range of this transform
     * is undefined.
     * <p>
     * The matrix is stored in column-major order so that it may be passed directly to OpenGL ES via
     * the {@code glLoadMatrixf} or {@code glUniformMatrix4fv} functions.
     * <p>
     * If the underlying buffer has a crop associated with it, the transformation will also include
     * a slight scale to cut off a 1-texel border around the edge of the crop. This ensures that
     * when the texture is bilinear sampled that no texels outside of the buffer's valid region
     * are accessed by the GPU, avoiding any sampling artifacts when scaling.
     *
     * @param mtx the array into which the 4x4 matrix will be stored.  The array must have exactly
     *     16 elements.
     */
    public void getTransformMatrix(float[] mtx) {
        // Note we intentionally don't check mtx for null, so this will result in a
        // NullPointerException. But it's safe because it happens before the call to native.
        if (mtx.length != 16) {
            throw new IllegalArgumentException();
        }
        nativeGetTransformMatrix(mtx);
    }

    /**
     * Retrieve the timestamp associated with the texture image set by the most recent call to
     * {@link #updateTexImage}.
     *
     * <p>This timestamp is in nanoseconds, and is normally monotonically increasing. The timestamp
     * should be unaffected by time-of-day adjustments. The specific meaning and zero point of the
     * timestamp depends on the source providing images to the SurfaceTexture. Unless otherwise
     * specified by the image source, timestamps cannot generally be compared across SurfaceTexture
     * instances, or across multiple program invocations. It is mostly useful for determining time
     * offsets between subsequent frames.</p>
     *
     * <p>For camera sources, timestamps should be strictly monotonic. Timestamps from MediaPlayer
     * sources may be reset when the playback position is set. For EGL and Vulkan producers, the
     * timestamp is the desired present time set with the {@code EGL_ANDROID_presentation_time} or
     * {@code VK_GOOGLE_display_timing} extensions.</p>
     */

    public long getTimestamp() {
        return nativeGetTimestamp();
    }

    /**
     * {@code release()} frees all the buffers and puts the SurfaceTexture into the
     * 'abandoned' state. Once put in this state the SurfaceTexture can never
     * leave it. When in the 'abandoned' state, all methods of the
     * {@code IGraphicBufferProducer} interface will fail with the {@code NO_INIT}
     * error.
     * <p>
     * Note that while calling this method causes all the buffers to be freed
     * from the perspective of the the SurfaceTexture, if there are additional
     * references on the buffers (e.g. if a buffer is referenced by a client or
     * by OpenGL ES as a texture) then those buffer will remain allocated.
     * <p>
     * Always call this method when you are done with SurfaceTexture. Failing
     * to do so may delay resource deallocation for a significant amount of
     * time.
     *
     * @see #isReleased()
     */
    public void release() {
        nativeRelease();
    }

    /**
     * Returns {@code true} if the SurfaceTexture was released.
     *
     * @see #release()
     */
    public boolean isReleased() {
        return nativeIsReleased();
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            nativeFinalize();
        } finally {
            super.finalize();
        }
    }

    /**
     * This method is invoked from native code only.
     */
    @SuppressWarnings({"UnusedDeclaration"})
    @UnsupportedAppUsage
    private static void postEventFromNative(WeakReference<SurfaceTexture> weakSelf) {
        SurfaceTexture st = weakSelf.get();
        if (st != null) {
            Handler handler = st.mOnFrameAvailableHandler;
            if (handler != null) {
                handler.sendEmptyMessage(0);
            }
        }
    }

    /**
     * Returns {@code true} if the SurfaceTexture is single-buffered.
     * @hide
     */
    public boolean isSingleBuffered() {
        return mIsSingleBuffered;
    }

    private native void nativeInit(boolean isDetached, int texName,
            boolean singleBufferMode, WeakReference<SurfaceTexture> weakSelf)
            throws Surface.OutOfResourcesException;
    private native void nativeFinalize();
    private native void nativeGetTransformMatrix(float[] mtx);
    private native long nativeGetTimestamp();
    private native void nativeSetDefaultBufferSize(int width, int height);
    private native void nativeUpdateTexImage();
    private native void nativeReleaseTexImage();
    @UnsupportedAppUsage
    private native int nativeDetachFromGLContext();
    private native int nativeAttachToGLContext(int texName);
    private native void nativeRelease();
    private native boolean nativeIsReleased();
}
