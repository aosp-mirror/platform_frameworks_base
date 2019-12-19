/*
 * Copyright (C) 2011 The Android Open Source Project
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


package android.filterfw.core;

import android.annotation.UnsupportedAppUsage;
import android.filterfw.core.Frame;
import android.filterfw.core.FrameFormat;
import android.filterfw.core.FrameManager;
import android.filterfw.core.NativeFrame;
import android.filterfw.core.StopWatchMap;
import android.graphics.Bitmap;
import android.opengl.GLES20;
import android.graphics.Rect;

import java.nio.ByteBuffer;

class GLFrameTimer {

    private static StopWatchMap mTimer = null;

    public static StopWatchMap get() {
        if (mTimer == null) {
            mTimer = new StopWatchMap();
        }
        return mTimer;
    }

}

/**
 * @hide
 */
public class GLFrame extends Frame {

    // GL-related binding types
    public final static int EXISTING_TEXTURE_BINDING = 100;
    public final static int EXISTING_FBO_BINDING     = 101;
    public final static int NEW_TEXTURE_BINDING      = 102; // TODO: REMOVE THIS
    public final static int NEW_FBO_BINDING          = 103; // TODO: REMOVE THIS
    public final static int EXTERNAL_TEXTURE         = 104;

    private int glFrameId = -1;

    /**
     * Flag whether we own the texture or not. If we do not, we must be careful when caching or
     * storing the frame, as the user may delete, and regenerate it.
     */
    private boolean mOwnsTexture = true;

    /**
     * Keep a reference to the GL environment, so that it does not get deallocated while there
     * are still frames living in it.
     */
    private GLEnvironment mGLEnvironment;

    GLFrame(FrameFormat format, FrameManager frameManager) {
        super(format, frameManager);
    }

    GLFrame(FrameFormat format, FrameManager frameManager, int bindingType, long bindingId) {
        super(format, frameManager, bindingType, bindingId);
    }

    void init(GLEnvironment glEnv) {
        FrameFormat format = getFormat();
        mGLEnvironment = glEnv;

        // Check that we have a valid format
        if (format.getBytesPerSample() != 4) {
            throw new IllegalArgumentException("GL frames must have 4 bytes per sample!");
        } else if (format.getDimensionCount() != 2) {
            throw new IllegalArgumentException("GL frames must be 2-dimensional!");
        } else if (getFormat().getSize() < 0) {
            throw new IllegalArgumentException("Initializing GL frame with zero size!");
        }

        // Create correct frame
        int bindingType = getBindingType();
        boolean reusable = true;
        if (bindingType == Frame.NO_BINDING) {
            initNew(false);
        } else if (bindingType == EXTERNAL_TEXTURE) {
            initNew(true);
            reusable = false;
        } else if (bindingType == EXISTING_TEXTURE_BINDING) {
            initWithTexture((int)getBindingId());
        } else if (bindingType == EXISTING_FBO_BINDING) {
            initWithFbo((int)getBindingId());
        } else if (bindingType == NEW_TEXTURE_BINDING) {
            initWithTexture((int)getBindingId());
        } else if (bindingType == NEW_FBO_BINDING) {
            initWithFbo((int)getBindingId());
        } else {
            throw new RuntimeException("Attempting to create GL frame with unknown binding type "
                + bindingType + "!");
        }
        setReusable(reusable);
    }

    private void initNew(boolean isExternal) {
        if (isExternal) {
            if (!nativeAllocateExternal(mGLEnvironment)) {
                throw new RuntimeException("Could not allocate external GL frame!");
            }
        } else {
            if (!nativeAllocate(mGLEnvironment, getFormat().getWidth(), getFormat().getHeight())) {
                throw new RuntimeException("Could not allocate GL frame!");
            }
        }
    }

    private void initWithTexture(int texId) {
        int width = getFormat().getWidth();
        int height = getFormat().getHeight();
        if (!nativeAllocateWithTexture(mGLEnvironment, texId, width, height)) {
            throw new RuntimeException("Could not allocate texture backed GL frame!");
        }
        mOwnsTexture = false;
        markReadOnly();
    }

    private void initWithFbo(int fboId) {
        int width = getFormat().getWidth();
        int height = getFormat().getHeight();
        if (!nativeAllocateWithFbo(mGLEnvironment, fboId, width, height)) {
            throw new RuntimeException("Could not allocate FBO backed GL frame!");
        }
    }

    void flushGPU(String message) {
        StopWatchMap timer = GLFrameTimer.get();
        if (timer.LOG_MFF_RUNNING_TIMES) {
          timer.start("glFinish " + message);
          GLES20.glFinish();
          timer.stop("glFinish " + message);
        }
    }

    @Override
    protected synchronized boolean hasNativeAllocation() {
        return glFrameId != -1;
    }

    @Override
    protected synchronized void releaseNativeAllocation() {
        nativeDeallocate();
        glFrameId = -1;
    }

    public GLEnvironment getGLEnvironment() {
        return mGLEnvironment;
    }

    @Override
    public Object getObjectValue() {
        assertGLEnvValid();
        return ByteBuffer.wrap(getNativeData());
    }

    @Override
    public void setInts(int[] ints) {
        assertFrameMutable();
        assertGLEnvValid();
        if (!setNativeInts(ints)) {
            throw new RuntimeException("Could not set int values for GL frame!");
        }
    }

    @Override
    public int[] getInts() {
        assertGLEnvValid();
        flushGPU("getInts");
        return getNativeInts();
    }

    @Override
    public void setFloats(float[] floats) {
        assertFrameMutable();
        assertGLEnvValid();
        if (!setNativeFloats(floats)) {
            throw new RuntimeException("Could not set int values for GL frame!");
        }
    }

    @Override
    public float[] getFloats() {
        assertGLEnvValid();
        flushGPU("getFloats");
        return getNativeFloats();
    }

    @Override
    public void setData(ByteBuffer buffer, int offset, int length) {
        assertFrameMutable();
        assertGLEnvValid();
        byte[] bytes = buffer.array();
        if (getFormat().getSize() != bytes.length) {
            throw new RuntimeException("Data size in setData does not match GL frame size!");
        } else if (!setNativeData(bytes, offset, length)) {
            throw new RuntimeException("Could not set GL frame data!");
        }
    }

    @Override
    public ByteBuffer getData() {
        assertGLEnvValid();
        flushGPU("getData");
        return ByteBuffer.wrap(getNativeData());
    }

    @Override
    @UnsupportedAppUsage
    public void setBitmap(Bitmap bitmap) {
        assertFrameMutable();
        assertGLEnvValid();
        if (getFormat().getWidth()  != bitmap.getWidth() ||
            getFormat().getHeight() != bitmap.getHeight()) {
            throw new RuntimeException("Bitmap dimensions do not match GL frame dimensions!");
        } else {
            Bitmap rgbaBitmap = convertBitmapToRGBA(bitmap);
            if (!setNativeBitmap(rgbaBitmap, rgbaBitmap.getByteCount())) {
                throw new RuntimeException("Could not set GL frame bitmap data!");
            }
        }
    }

    @Override
    public Bitmap getBitmap() {
        assertGLEnvValid();
        flushGPU("getBitmap");
        Bitmap result = Bitmap.createBitmap(getFormat().getWidth(),
                                            getFormat().getHeight(),
                                            Bitmap.Config.ARGB_8888);
        if (!getNativeBitmap(result)) {
            throw new RuntimeException("Could not get bitmap data from GL frame!");
        }
        return result;
    }

    @Override
    public void setDataFromFrame(Frame frame) {
        assertGLEnvValid();

        // Make sure frame fits
        if (getFormat().getSize() < frame.getFormat().getSize()) {
            throw new RuntimeException(
                "Attempting to assign frame of size " + frame.getFormat().getSize() + " to " +
                "smaller GL frame of size " + getFormat().getSize() + "!");
        }

        // Invoke optimized implementations if possible
        if (frame instanceof NativeFrame) {
            nativeCopyFromNative((NativeFrame)frame);
        } else if (frame instanceof GLFrame) {
            nativeCopyFromGL((GLFrame)frame);
        } else if (frame instanceof SimpleFrame) {
            setObjectValue(frame.getObjectValue());
        } else {
            super.setDataFromFrame(frame);
        }
    }

    public void setViewport(int x, int y, int width, int height) {
        assertFrameMutable();
        setNativeViewport(x, y, width, height);
    }

    public void setViewport(Rect rect) {
        assertFrameMutable();
        setNativeViewport(rect.left, rect.top, rect.right - rect.left, rect.bottom - rect.top);
    }

    @UnsupportedAppUsage
    public void generateMipMap() {
        assertFrameMutable();
        assertGLEnvValid();
        if (!generateNativeMipMap()) {
            throw new RuntimeException("Could not generate mip-map for GL frame!");
        }
    }

    @UnsupportedAppUsage
    public void setTextureParameter(int param, int value) {
        assertFrameMutable();
        assertGLEnvValid();
        if (!setNativeTextureParam(param, value)) {
            throw new RuntimeException("Could not set texture value " + param + " = " + value + " " +
                                       "for GLFrame!");
        }
    }

    @UnsupportedAppUsage
    public int getTextureId() {
        return getNativeTextureId();
    }

    public int getFboId() {
        return getNativeFboId();
    }

    public void focus() {
        if (!nativeFocus()) {
            throw new RuntimeException("Could not focus on GLFrame for drawing!");
        }
    }

    @Override
    public String toString() {
        return "GLFrame id: " + glFrameId + " (" + getFormat() + ") with texture ID "
            + getTextureId() + ", FBO ID " + getFboId();
    }

    @Override
    protected void reset(FrameFormat newFormat) {
        if (!nativeResetParams()) {
            throw new RuntimeException("Could not reset GLFrame texture parameters!");
        }
        super.reset(newFormat);
    }

    @Override
    protected void onFrameStore() {
        if (!mOwnsTexture) {
            // Detach texture from FBO in case user manipulates it.
            nativeDetachTexFromFbo();
        }
    }

    @Override
    protected void onFrameFetch() {
        if (!mOwnsTexture) {
            // Reattach texture to FBO when using frame again. This may reallocate the texture
            // in case it has become invalid.
            nativeReattachTexToFbo();
        }
    }

    private void assertGLEnvValid() {
        if (!mGLEnvironment.isContextActive()) {
            if (GLEnvironment.isAnyContextActive()) {
                throw new RuntimeException("Attempting to access " + this + " with foreign GL " +
                    "context active!");
            } else {
                throw new RuntimeException("Attempting to access " + this + " with no GL context " +
                    " active!");
            }
        }
    }

    static {
        System.loadLibrary("filterfw");
    }

    private native boolean nativeAllocate(GLEnvironment env, int width, int height);

    private native boolean nativeAllocateWithTexture(GLEnvironment env,
                                               int textureId,
                                               int width,
                                               int height);

    private native boolean nativeAllocateWithFbo(GLEnvironment env,
                                           int fboId,
                                           int width,
                                           int height);

    private native boolean nativeAllocateExternal(GLEnvironment env);

    private native boolean nativeDeallocate();

    private native boolean setNativeData(byte[] data, int offset, int length);

    private native byte[] getNativeData();

    private native boolean setNativeInts(int[] ints);

    private native boolean setNativeFloats(float[] floats);

    private native int[] getNativeInts();

    private native float[] getNativeFloats();

    private native boolean setNativeBitmap(Bitmap bitmap, int size);

    private native boolean getNativeBitmap(Bitmap bitmap);

    private native boolean setNativeViewport(int x, int y, int width, int height);

    private native int getNativeTextureId();

    private native int getNativeFboId();

    private native boolean generateNativeMipMap();

    private native boolean setNativeTextureParam(int param, int value);

    private native boolean nativeResetParams();

    private native boolean nativeCopyFromNative(NativeFrame frame);

    private native boolean nativeCopyFromGL(GLFrame frame);

    private native boolean nativeFocus();

    private native boolean nativeReattachTexToFbo();

    private native boolean nativeDetachTexFromFbo();
}
