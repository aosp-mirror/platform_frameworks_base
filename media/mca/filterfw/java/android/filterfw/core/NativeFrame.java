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

import android.filterfw.core.Frame;
import android.filterfw.core.FrameFormat;
import android.filterfw.core.FrameManager;
import android.filterfw.core.GLFrame;
import android.filterfw.core.NativeBuffer;
import android.graphics.Bitmap;

import android.util.Log;

import java.nio.ByteBuffer;

/**
 * @hide
 */
public class NativeFrame extends Frame {

    private int nativeFrameId = -1;

    NativeFrame(FrameFormat format, FrameManager frameManager) {
        super(format, frameManager);
        int capacity = format.getSize();
        nativeAllocate(capacity);
        setReusable(capacity != 0);
    }

    @Override
    protected synchronized void releaseNativeAllocation() {
        nativeDeallocate();
        nativeFrameId = -1;
    }

    @Override
    protected synchronized boolean hasNativeAllocation() {
        return nativeFrameId != -1;
    }

    @Override
    public int getCapacity() {
        return getNativeCapacity();
    }

    /**
     * Returns the native frame's Object value.
     *
     * If the frame's base-type is not TYPE_OBJECT, this returns a data buffer containing the native
     * data (this is equivalent to calling getData().
     * If the frame is based on an object type, this type is expected to be a subclass of
     * NativeBuffer. The NativeBuffer returned is only valid for as long as the frame is alive. If
     * you need to hold on to the returned value, you must retain it.
     */
    @Override
    public Object getObjectValue() {
        // If this is not a structured frame, return our data
        if (getFormat().getBaseType() != FrameFormat.TYPE_OBJECT) {
            return getData();
        }

        // Get the structure class
        Class structClass = getFormat().getObjectClass();
        if (structClass == null) {
            throw new RuntimeException("Attempting to get object data from frame that does " +
                                       "not specify a structure object class!");
        }

        // Make sure it is a NativeBuffer subclass
        if (!NativeBuffer.class.isAssignableFrom(structClass)) {
            throw new RuntimeException("NativeFrame object class must be a subclass of " +
                                       "NativeBuffer!");
        }

        // Instantiate a new empty instance of this class
        NativeBuffer structData = null;
        try {
          structData = (NativeBuffer)structClass.newInstance();
        } catch (Exception e) {
          throw new RuntimeException("Could not instantiate new structure instance of type '" +
                                     structClass + "'!");
        }

        // Wrap it around our data
        if (!getNativeBuffer(structData)) {
            throw new RuntimeException("Could not get the native structured data for frame!");
        }

        // Attach this frame to it
        structData.attachToFrame(this);

        return structData;
    }

    @Override
    public void setInts(int[] ints) {
        assertFrameMutable();
        if (ints.length * nativeIntSize() > getFormat().getSize()) {
            throw new RuntimeException(
                "NativeFrame cannot hold " + ints.length + " integers. (Can only hold " +
                (getFormat().getSize() / nativeIntSize()) + " integers).");
        } else if (!setNativeInts(ints)) {
            throw new RuntimeException("Could not set int values for native frame!");
        }
    }

    @Override
    public int[] getInts() {
        return getNativeInts(getFormat().getSize());
    }

    @Override
    public void setFloats(float[] floats) {
        assertFrameMutable();
        if (floats.length * nativeFloatSize() > getFormat().getSize()) {
            throw new RuntimeException(
                "NativeFrame cannot hold " + floats.length + " floats. (Can only hold " +
                (getFormat().getSize() / nativeFloatSize()) + " floats).");
        } else if (!setNativeFloats(floats)) {
            throw new RuntimeException("Could not set int values for native frame!");
        }
    }

    @Override
    public float[] getFloats() {
        return getNativeFloats(getFormat().getSize());
    }

    // TODO: This function may be a bit confusing: Is the offset the target or source offset? Maybe
    // we should allow specifying both? (May be difficult for other frame types).
    @Override
    public void setData(ByteBuffer buffer, int offset, int length) {
        assertFrameMutable();
        byte[] bytes = buffer.array();
        if ((length + offset) > buffer.limit()) {
            throw new RuntimeException("Offset and length exceed buffer size in native setData: " +
                                       (length + offset) + " bytes given, but only " + buffer.limit() +
                                       " bytes available!");
        } else if (getFormat().getSize() != length) {
            throw new RuntimeException("Data size in setData does not match native frame size: " +
                                       "Frame size is " + getFormat().getSize() + " bytes, but " +
                                       length + " bytes given!");
        } else if (!setNativeData(bytes, offset, length)) {
            throw new RuntimeException("Could not set native frame data!");
        }
    }

    @Override
    public ByteBuffer getData() {
        byte[] data = getNativeData(getFormat().getSize());
        return data == null ? null : ByteBuffer.wrap(data);
    }

    @Override
    public void setBitmap(Bitmap bitmap) {
        assertFrameMutable();
        if (getFormat().getNumberOfDimensions() != 2) {
            throw new RuntimeException("Attempting to set Bitmap for non 2-dimensional native frame!");
        } else if (getFormat().getWidth()  != bitmap.getWidth() ||
                   getFormat().getHeight() != bitmap.getHeight()) {
            throw new RuntimeException("Bitmap dimensions do not match native frame dimensions!");
        } else {
            Bitmap rgbaBitmap = convertBitmapToRGBA(bitmap);
            int byteCount = rgbaBitmap.getByteCount();
            int bps = getFormat().getBytesPerSample();
            if (!setNativeBitmap(rgbaBitmap, byteCount, bps)) {
                throw new RuntimeException("Could not set native frame bitmap data!");
            }
        }
    }

    @Override
    public Bitmap getBitmap() {
        if (getFormat().getNumberOfDimensions() != 2) {
            throw new RuntimeException("Attempting to get Bitmap for non 2-dimensional native frame!");
        }
        Bitmap result = Bitmap.createBitmap(getFormat().getWidth(),
                                            getFormat().getHeight(),
                                            Bitmap.Config.ARGB_8888);
        int byteCount = result.getByteCount();
        int bps = getFormat().getBytesPerSample();
        if (!getNativeBitmap(result, byteCount, bps)) {
            throw new RuntimeException("Could not get bitmap data from native frame!");
        }
        return result;
    }

    @Override
    public void setDataFromFrame(Frame frame) {
        // Make sure frame fits
        if (getFormat().getSize() < frame.getFormat().getSize()) {
            throw new RuntimeException(
                "Attempting to assign frame of size " + frame.getFormat().getSize() + " to " +
                "smaller native frame of size " + getFormat().getSize() + "!");
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

    @Override
    public String toString() {
        return "NativeFrame id: " + nativeFrameId + " (" + getFormat() + ") of size "
            + getCapacity();
    }

    static {
        System.loadLibrary("filterfw");
    }

    private native boolean nativeAllocate(int capacity);

    private native boolean nativeDeallocate();

    private native int getNativeCapacity();

    private static native int nativeIntSize();

    private static native int nativeFloatSize();

    private native boolean setNativeData(byte[] data, int offset, int length);

    private native byte[] getNativeData(int byteCount);

    private native boolean getNativeBuffer(NativeBuffer buffer);

    private native boolean setNativeInts(int[] ints);

    private native boolean setNativeFloats(float[] floats);

    private native int[] getNativeInts(int byteCount);

    private native float[] getNativeFloats(int byteCount);

    private native boolean setNativeBitmap(Bitmap bitmap, int size, int bytesPerSample);

    private native boolean getNativeBitmap(Bitmap bitmap, int size, int bytesPerSample);

    private native boolean nativeCopyFromNative(NativeFrame frame);

    private native boolean nativeCopyFromGL(GLFrame frame);
}
