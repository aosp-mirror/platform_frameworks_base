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
import android.graphics.Bitmap;

import java.nio.ByteBuffer;

/**
 * @hide
 */
public class VertexFrame extends Frame {

    private int vertexFrameId = -1;

    VertexFrame(FrameFormat format, FrameManager frameManager) {
        super(format, frameManager);
        if (getFormat().getSize() <= 0) {
            throw new IllegalArgumentException("Initializing vertex frame with zero size!");
        } else {
            if (!nativeAllocate(getFormat().getSize())) {
                throw new RuntimeException("Could not allocate vertex frame!");
            }
        }
    }

    @Override
    protected synchronized boolean hasNativeAllocation() {
        return vertexFrameId != -1;
    }

    @Override
    protected synchronized void releaseNativeAllocation() {
        nativeDeallocate();
        vertexFrameId = -1;
    }

    @Override
    public Object getObjectValue() {
        throw new RuntimeException("Vertex frames do not support reading data!");
    }

    @Override
    public void setInts(int[] ints) {
        assertFrameMutable();
        if (!setNativeInts(ints)) {
            throw new RuntimeException("Could not set int values for vertex frame!");
        }
    }

    @Override
    public int[] getInts() {
        throw new RuntimeException("Vertex frames do not support reading data!");
    }

    @Override
    public void setFloats(float[] floats) {
        assertFrameMutable();
        if (!setNativeFloats(floats)) {
            throw new RuntimeException("Could not set int values for vertex frame!");
        }
    }

    @Override
    public float[] getFloats() {
        throw new RuntimeException("Vertex frames do not support reading data!");
    }

    @Override
    public void setData(ByteBuffer buffer, int offset, int length) {
        assertFrameMutable();
        byte[] bytes = buffer.array();
        if (getFormat().getSize() != bytes.length) {
            throw new RuntimeException("Data size in setData does not match vertex frame size!");
        } else if (!setNativeData(bytes, offset, length)) {
            throw new RuntimeException("Could not set vertex frame data!");
        }
    }

    @Override
    public ByteBuffer getData() {
        throw new RuntimeException("Vertex frames do not support reading data!");
    }

    @Override
    public void setBitmap(Bitmap bitmap) {
        throw new RuntimeException("Unsupported: Cannot set vertex frame bitmap value!");
    }

    @Override
    public Bitmap getBitmap() {
        throw new RuntimeException("Vertex frames do not support reading data!");
    }

    @Override
    public void setDataFromFrame(Frame frame) {
        // TODO: Optimize
        super.setDataFromFrame(frame);
    }

    public int getVboId() {
        return getNativeVboId();
    }

    @Override
    public String toString() {
        return "VertexFrame (" + getFormat() + ") with VBO ID " + getVboId();
    }

    static {
        System.loadLibrary("filterfw");
    }

    private native boolean nativeAllocate(int size);

    private native boolean nativeDeallocate();

    private native boolean setNativeData(byte[] data, int offset, int length);

    private native boolean setNativeInts(int[] ints);

    private native boolean setNativeFloats(float[] floats);

    private native int getNativeVboId();
}
