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

import java.lang.ref.WeakReference;

import android.os.Handler;
import android.os.Looper;
import android.os.Message;

/**
 * Captures frames from an image stream as an OpenGL ES texture.
 *
 * <p>The image stream may come from either camera preview or video decode.  A SurfaceTexture
 * may be used in place of a SurfaceHolder when specifying the output destination of a
 * {@link android.hardware.Camera} or {@link android.media.MediaPlayer}
 * object.  Doing so will cause all the frames from the image stream to be sent to the
 * SurfaceTexture object rather than to the device's display.  When {@link #updateTexImage} is
 * called, the contents of the texture object specified when the SurfaceTexture was created are
 * updated to contain the most recent image from the image stream.  This may cause some frames of
 * the stream to be skipped.
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

    private EventHandler mEventHandler;
    private OnFrameAvailableListener mOnFrameAvailableListener;

    /**
     * These fields are used by native code, do not access or modify.
     */
    private int mSurfaceTexture;
    private int mFrameAvailableListener;

    /**
     * Callback interface for being notified that a new stream frame is available.
     */
    public interface OnFrameAvailableListener {
        void onFrameAvailable(SurfaceTexture surfaceTexture);
    }

    /**
     * Exception thrown when a surface couldn't be created or resized
     */
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
     */
    public SurfaceTexture(int texName) {
        this(texName, false);
    }

    /**
     * Construct a new SurfaceTexture to stream images to a given OpenGL texture.
     *
     * @param texName the OpenGL texture object name (e.g. generated via glGenTextures)
     * @param allowSynchronousMode whether the SurfaceTexture can run in the synchronous mode.
     *      When the image stream comes from OpenGL, SurfaceTexture may run in the synchronous
     *      mode where the producer side may be blocked to avoid skipping frames. To avoid the
     *      thread block, set allowSynchronousMode to false.
     *
     * @hide
     */
    public SurfaceTexture(int texName, boolean allowSynchronousMode) {
        Looper looper;
        if ((looper = Looper.myLooper()) != null) {
            mEventHandler = new EventHandler(looper);
        } else if ((looper = Looper.getMainLooper()) != null) {
            mEventHandler = new EventHandler(looper);
        } else {
            mEventHandler = null;
        }
        nativeInit(texName, new WeakReference<SurfaceTexture>(this), allowSynchronousMode);
    }

    /**
     * Register a callback to be invoked when a new image frame becomes available to the
     * SurfaceTexture.  Note that this callback may be called on an arbitrary thread, so it is not
     * safe to call {@link #updateTexImage} without first binding the OpenGL ES context to the
     * thread invoking the callback.
     */
    public void setOnFrameAvailableListener(OnFrameAvailableListener l) {
        mOnFrameAvailableListener = l;
    }

    /**
     * Set the default size of the image buffers.  The image producer may override the buffer size,
     * in which case the producer-set buffer size will be used, not the default size set by this
     * method.  Both video and camera based image producers do override the size.  This method may
     * be used to set the image size when producing images with {@link android.graphics.Canvas} (via
     * {@link android.view.Surface#lockCanvas}), or OpenGL ES (via an EGLSurface).
     *
     * The new default buffer size will take effect the next time the image producer requests a
     * buffer to fill.  For {@link android.graphics.Canvas} this will be the next time {@link
     * android.view.Surface#lockCanvas} is called.  For OpenGL ES, the EGLSurface should be
     * destroyed (via eglDestroySurface), made not-current (via eglMakeCurrent), and then recreated
     * (via eglCreateWindowSurface) to ensure that the new default size has taken effect.
     * 
     * The width and height parameters must be no greater than the minimum of
     * GL_MAX_VIEWPORT_DIMS and GL_MAX_TEXTURE_SIZE (see 
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
     * It will implicitly bind its texture to the GL_TEXTURE_EXTERNAL_OES texture target.
     */
    public void updateTexImage() {
        nativeUpdateTexImage();
    }

    /**
     * Detach the SurfaceTexture from the OpenGL ES context that owns the OpenGL ES texture object.
     * This call must be made with the OpenGL ES context current on the calling thread.  The OpenGL
     * ES texture object will be deleted as a result of this call.  After calling this method all
     * calls to {@link #updateTexImage} will throw an {@link java.lang.IllegalStateException} until
     * a successful call to {@link #attachToGLContext} is made.
     *
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
     * texture is bound to the GL_TEXTURE_EXTERNAL_OES texture target.
     *
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
     * the most recent call to updateTexImage.
     *
     * This transform matrix maps 2D homogeneous texture coordinates of the form (s, t, 0, 1) with s
     * and t in the inclusive range [0, 1] to the texture coordinate that should be used to sample
     * that location from the texture.  Sampling the texture outside of the range of this transform
     * is undefined.
     *
     * The matrix is stored in column-major order so that it may be passed directly to OpenGL ES via
     * the glLoadMatrixf or glUniformMatrix4fv functions.
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
     * updateTexImage.
     *
     * This timestamp is in nanoseconds, and is normally monotonically increasing. The timestamp
     * should be unaffected by time-of-day adjustments, and for a camera should be strictly
     * monotonic but for a MediaPlayer may be reset when the position is set.  The
     * specific meaning and zero point of the timestamp depends on the source providing images to
     * the SurfaceTexture. Unless otherwise specified by the image source, timestamps cannot
     * generally be compared across SurfaceTexture instances, or across multiple program
     * invocations. It is mostly useful for determining time offsets between subsequent frames.
     */

    public long getTimestamp() {
        return nativeGetTimestamp();
    }

    /**
     * release() frees all the buffers and puts the SurfaceTexture into the
     * 'abandoned' state. Once put in this state the SurfaceTexture can never
     * leave it. When in the 'abandoned' state, all methods of the
     * IGraphicBufferProducer interface will fail with the NO_INIT error.
     *
     * Note that while calling this method causes all the buffers to be freed
     * from the perspective of the the SurfaceTexture, if there are additional
     * references on the buffers (e.g. if a buffer is referenced by a client or
     * by OpenGL ES as a texture) then those buffer will remain allocated.
     *
     * Always call this method when you are done with SurfaceTexture. Failing
     * to do so may delay resource deallocation for a significant amount of
     * time.
     */
    public void release() {
        nativeRelease();
    }

    protected void finalize() throws Throwable {
        try {
            nativeFinalize();
        } finally {
            super.finalize();
        }
    }

    private class EventHandler extends Handler {
        public EventHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            if (mOnFrameAvailableListener != null) {
                mOnFrameAvailableListener.onFrameAvailable(SurfaceTexture.this);
            }
        }
    }

    /**
     * This method is invoked from native code only.
     */
    @SuppressWarnings({"UnusedDeclaration"})
    private static void postEventFromNative(Object selfRef) {
        WeakReference weakSelf = (WeakReference)selfRef;
        SurfaceTexture st = (SurfaceTexture)weakSelf.get();
        if (st == null) {
            return;
        }

        if (st.mEventHandler != null) {
            Message m = st.mEventHandler.obtainMessage();
            st.mEventHandler.sendMessage(m);
        }
    }

    private native void nativeInit(int texName, Object weakSelf, boolean allowSynchronousMode);
    private native void nativeFinalize();
    private native void nativeGetTransformMatrix(float[] mtx);
    private native long nativeGetTimestamp();
    private native void nativeSetDefaultBufferSize(int width, int height);
    private native void nativeUpdateTexImage();
    private native int nativeDetachFromGLContext();
    private native int nativeAttachToGLContext(int texName);
    private native int nativeGetQueuedCount();
    private native void nativeRelease();

    /*
     * We use a class initializer to allow the native code to cache some
     * field offsets.
     */
    private static native void nativeClassInit();
    static { nativeClassInit(); }
}
