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
 * <p>The image stream may come from either video playback or camera preview.  A SurfaceTexture may
 * be used in place of a SurfaceHolder when specifying the output destination of a MediaPlayer or
 * Camera object.  This will cause all the frames from that image stream to be sent to the
 * SurfaceTexture object rather than to the device's display.  When {@link #updateTexImage} is
 * called, the contents of the texture object specified when the SurfaceTexture was created is
 * updated to contain the most recent image from the image stream.  This may cause some frames of
 * the stream to be skipped.
 *
 * <p>The texture object uses the GL_TEXTURE_EXTERNAL_OES texture target, which is defined by the
 * OES_EGL_image_external OpenGL ES extension.  This limits how the texture may be used.
 */
public class SurfaceTexture {

    private EventHandler mEventHandler;
    private OnFrameAvailableListener mOnFrameAvailableListener;

    @SuppressWarnings("unused")
    private int mSurfaceTexture;

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
        Looper looper;
        if ((looper = Looper.myLooper()) != null) {
            mEventHandler = new EventHandler(looper);
        } else if ((looper = Looper.getMainLooper()) != null) {
            mEventHandler = new EventHandler(looper);
        } else {
            mEventHandler = null;
        }
        nativeInit(texName, new WeakReference<SurfaceTexture>(this));
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
     * Update the texture image to the most recent frame from the image stream.  This may only be
     * called while the OpenGL ES context that owns the texture is bound to the thread.  It will
     * implicitly bind its texture to the GL_TEXTURE_EXTERNAL_OES texture target.
     */
    public void updateTexImage() {
        nativeUpdateTexImage();
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
        if (mtx.length != 16) {
            throw new IllegalArgumentException();
        }
        nativeGetTransformMatrix(mtx);
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
            return;
        }
    }

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

    private native void nativeInit(int texName, Object weakSelf);
    private native void nativeFinalize();
    private native void nativeGetTransformMatrix(float[] mtx);
    private native void nativeUpdateTexImage();

    /*
     * We use a class initializer to allow the native code to cache some
     * field offsets.
     */
    private static native void nativeClassInit();
    static { nativeClassInit(); }
}
