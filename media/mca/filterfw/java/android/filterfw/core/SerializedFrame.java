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
import android.filterfw.format.ObjectFormat;
import android.graphics.Bitmap;

import java.io.InputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;

/**
 * A frame that serializes any assigned values. Such a frame is used when passing data objects
 * between threads.
 *
 * @hide
 */
public class SerializedFrame extends Frame {

    /**
     * The initial capacity of the serialized data stream.
     */
    private final static int INITIAL_CAPACITY = 64;

    /**
     * The internal data streams.
     */
    private DirectByteOutputStream mByteOutputStream;
    private ObjectOutputStream mObjectOut;

    /**
     * An unsynchronized output stream that writes data to an accessible byte array. Callers are
     * responsible for synchronization. This is more efficient than a ByteArrayOutputStream, as
     * there are no array copies or synchronization involved to read back written data.
     */
    private class DirectByteOutputStream extends OutputStream {
        private byte[] mBuffer = null;
        private int mOffset = 0;
        private int mDataOffset = 0;

        public DirectByteOutputStream(int size) {
            mBuffer = new byte[size];
        }

        private final void ensureFit(int bytesToWrite) {
            if (mOffset + bytesToWrite > mBuffer.length) {
                byte[] oldBuffer = mBuffer;
                mBuffer = new byte[Math.max(mOffset + bytesToWrite, mBuffer.length * 2)];
                System.arraycopy(oldBuffer, 0, mBuffer, 0, mOffset);
                oldBuffer = null;
            }
        }

        public final void markHeaderEnd() {
            mDataOffset = mOffset;
        }

        public final int getSize() {
            return mOffset;
        }

        public byte[] getByteArray() {
            return mBuffer;
        }

        @Override
        public final void write(byte b[]) {
            write(b, 0, b.length);
        }

        @Override
        public final void write(byte b[], int off, int len) {
            ensureFit(len);
            System.arraycopy(b, off, mBuffer, mOffset, len);
            mOffset += len;
        }

        @Override
        public final void write(int b) {
            ensureFit(1);
            mBuffer[mOffset++] = (byte)b;
        }

        public final void reset() {
            mOffset = mDataOffset;
        }

        public final DirectByteInputStream getInputStream() {
            return new DirectByteInputStream(mBuffer, mOffset);
        }
    }

    /**
     * An unsynchronized input stream that reads data directly from a provided byte array. Callers
     * are responsible for synchronization and ensuring that the byte buffer is valid.
     */
    private class DirectByteInputStream extends InputStream {

        private byte[] mBuffer;
        private int mPos = 0;
        private int mSize;

        public DirectByteInputStream(byte[] buffer, int size) {
            mBuffer = buffer;
            mSize = size;
        }

        @Override
        public final int available() {
            return mSize - mPos;
        }

        @Override
        public final int read() {
            return (mPos < mSize) ? (mBuffer[mPos++] & 0xFF) : -1;
        }

        @Override
        public final int read(byte[] b, int off, int len) {
            if (mPos >= mSize) {
                return -1;
            }
            if ((mPos + len) > mSize) {
                len = mSize - mPos;
            }
            System.arraycopy(mBuffer, mPos, b, off, len);
            mPos += len;
            return len;
        }

        @Override
        public final long skip(long n) {
            if ((mPos + n) > mSize) {
                n = mSize - mPos;
            }
            if (n < 0) {
                return 0;
            }
            mPos += n;
            return n;
        }
    }

    SerializedFrame(FrameFormat format, FrameManager frameManager) {
        super(format, frameManager);
        setReusable(false);

        // Setup streams
        try {
            mByteOutputStream = new DirectByteOutputStream(INITIAL_CAPACITY);
            mObjectOut = new ObjectOutputStream(mByteOutputStream);
            mByteOutputStream.markHeaderEnd();
        } catch (IOException e) {
            throw new RuntimeException("Could not create serialization streams for "
                + "SerializedFrame!", e);
        }
    }

    static SerializedFrame wrapObject(Object object, FrameManager frameManager) {
        FrameFormat format = ObjectFormat.fromObject(object, FrameFormat.TARGET_SIMPLE);
        SerializedFrame result = new SerializedFrame(format, frameManager);
        result.setObjectValue(object);
        return result;
    }

    @Override
    protected boolean hasNativeAllocation() {
        return false;
    }

    @Override
    protected void releaseNativeAllocation() {
    }

    @Override
    public Object getObjectValue() {
        return deserializeObjectValue();
    }

    @Override
    public void setInts(int[] ints) {
        assertFrameMutable();
        setGenericObjectValue(ints);
    }

    @Override
    public int[] getInts() {
        Object result = deserializeObjectValue();
        return (result instanceof int[]) ? (int[])result : null;
    }

    @Override
    public void setFloats(float[] floats) {
        assertFrameMutable();
        setGenericObjectValue(floats);
    }

    @Override
    public float[] getFloats() {
        Object result = deserializeObjectValue();
        return (result instanceof float[]) ? (float[])result : null;
    }

    @Override
    public void setData(ByteBuffer buffer, int offset, int length) {
        assertFrameMutable();
        setGenericObjectValue(ByteBuffer.wrap(buffer.array(), offset, length));
    }

    @Override
    public ByteBuffer getData() {
        Object result = deserializeObjectValue();
        return (result instanceof ByteBuffer) ? (ByteBuffer)result : null;
    }

    @Override
    public void setBitmap(Bitmap bitmap) {
        assertFrameMutable();
        setGenericObjectValue(bitmap);
    }

    @Override
    public Bitmap getBitmap() {
        Object result = deserializeObjectValue();
        return (result instanceof Bitmap) ? (Bitmap)result : null;
    }

    @Override
    protected void setGenericObjectValue(Object object) {
        serializeObjectValue(object);
    }

    private final void serializeObjectValue(Object object) {
        try {
            mByteOutputStream.reset();
            mObjectOut.writeObject(object);
            mObjectOut.flush();
            mObjectOut.close();
        } catch (IOException e) {
            throw new RuntimeException("Could not serialize object " + object + " in "
                + this + "!", e);
        }
    }

    private final Object deserializeObjectValue() {
        try {
            InputStream inputStream = mByteOutputStream.getInputStream();
            ObjectInputStream objectStream = new ObjectInputStream(inputStream);
            return objectStream.readObject();
        } catch (IOException e) {
            throw new RuntimeException("Could not deserialize object in " + this + "!", e);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("Unable to deserialize object of unknown class in "
                + this + "!", e);
        }
    }

    @Override
    public String toString() {
        return "SerializedFrame (" + getFormat() + ")";
    }
}
