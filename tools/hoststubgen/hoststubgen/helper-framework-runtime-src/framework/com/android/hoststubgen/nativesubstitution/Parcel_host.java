/*
 * Copyright (C) 2023 The Android Open Source Project
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
package com.android.hoststubgen.nativesubstitution;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tentative, partial implementation of the Parcel native methods, using Java's
 * {@code byte[]}.
 * (We don't use a {@link ByteBuffer} because there's enough semantics differences between Parcel
 * and {@link ByteBuffer}, and it didn't work out.
 * e.g. Parcel seems to allow moving the data position to be beyond its size? Which
 * {@link ByteBuffer} wouldn't allow...)
 */
public class Parcel_host {
    private Parcel_host() {
    }

    private static final AtomicLong sNextId = new AtomicLong(1);

    private static final Map<Long, Parcel_host> sInstances = new ConcurrentHashMap<>();

    private boolean mDeleted = false;

    private byte[] mBuffer;
    private int mSize;
    private int mPos;

    private boolean mSensitive;
    private boolean mAllowFds;

    // TODO Use the actual value from Parcel.java.
    private static final int OK = 0;

    private void validate() {
        if (mDeleted) {
            // TODO: Put more info
            throw new RuntimeException("Parcel already destroyed");
        }
    }

    private static Parcel_host getInstance(long id) {
        Parcel_host p = sInstances.get(id);
        if (p == null) {
            // TODO: Put more info
            throw new RuntimeException("Parcel doesn't exist with id=" + id);
        }
        p.validate();
        return p;
    }

    public static long nativeCreate() {
        final long id = sNextId.getAndIncrement();
        final Parcel_host p = new Parcel_host();
        sInstances.put(id, p);
        p.init();
        return id;
    }

    private void init() {
        mBuffer = new byte[0];
        mSize = 0;
        mPos = 0;
        mSensitive = false;
        mAllowFds = false;
    }

    private void updateSize() {
        if (mSize < mPos) {
            mSize = mPos;
        }
    }

    public static void nativeDestroy(long nativePtr) {
        getInstance(nativePtr).mDeleted = true;
        sInstances.remove(nativePtr);
    }

    public static void nativeFreeBuffer(long nativePtr) {
        getInstance(nativePtr).freeBuffer();
    }

    public void freeBuffer() {
        init();
    }

    private int getCapacity() {
        return mBuffer.length;
    }

    private void ensureMoreCapacity(int size) {
        ensureCapacity(mPos + size);
    }

    private void ensureCapacity(int targetSize) {
        if (targetSize <= getCapacity()) {
            return;
        }
        var newSize = getCapacity() * 2;
        if (newSize < targetSize) {
            newSize = targetSize;
        }
        forceSetCapacity(newSize);
    }

    private void forceSetCapacity(int newSize) {
        var newBuf = new byte[newSize];

        // Copy
        System.arraycopy(mBuffer, 0, newBuf, 0, Math.min(newSize, getCapacity()));

        this.mBuffer = newBuf;
    }

    private void ensureDataAvailable(int requestSize) {
        if (mSize - mPos < requestSize) {
            throw new RuntimeException(String.format(
                    "Pacel data underflow. size=%d, pos=%d, request=%d", mSize, mPos, requestSize));
        }
    }

    public static void nativeMarkSensitive(long nativePtr) {
        getInstance(nativePtr).mSensitive = true;
    }
    public static int nativeDataSize(long nativePtr) {
        return getInstance(nativePtr).mSize;
    }
    public static int nativeDataAvail(long nativePtr) {
        var p = getInstance(nativePtr);
        return p.mSize - p.mPos;
    }
    public static int nativeDataPosition(long nativePtr) {
        return getInstance(nativePtr).mPos;
    }
    public static int nativeDataCapacity(long nativePtr) {
        return getInstance(nativePtr).mBuffer.length;
    }
    public static void nativeSetDataSize(long nativePtr, int size) {
        var p = getInstance(nativePtr);
        p.ensureCapacity(size);
        getInstance(nativePtr).mSize = size;
    }
    public static void nativeSetDataPosition(long nativePtr, int pos) {
        var p = getInstance(nativePtr);
        // TODO: Should this change the size or the capacity??
        p.mPos = pos;
    }
    public static void nativeSetDataCapacity(long nativePtr, int size) {
        var p = getInstance(nativePtr);
        if (p.getCapacity() < size) {
            p.forceSetCapacity(size);
        }
    }

    public static boolean nativePushAllowFds(long nativePtr, boolean allowFds) {
        var p = getInstance(nativePtr);
        var prev = p.mAllowFds;
        p.mAllowFds = allowFds;
        return prev;
    }
    public static void nativeRestoreAllowFds(long nativePtr, boolean lastValue) {
        getInstance(nativePtr).mAllowFds = lastValue;
    }

    public static void nativeWriteByteArray(long nativePtr, byte[] b, int offset, int len) {
        nativeWriteBlob(nativePtr, b, offset, len);
    }

    public static void nativeWriteBlob(long nativePtr, byte[] b, int offset, int len) {
        var p = getInstance(nativePtr);

        if (b == null) {
            nativeWriteInt(nativePtr, -1);
        } else {
            final var alignedSize = align4(len);

            nativeWriteInt(nativePtr, len);

            p.ensureMoreCapacity(alignedSize);

            System.arraycopy(b, offset, p.mBuffer,  p.mPos, len);
            p.mPos += alignedSize;
            p.updateSize();
        }
    }

    public static int nativeWriteInt(long nativePtr, int value) {
        var p = getInstance(nativePtr);
        p.ensureMoreCapacity(Integer.BYTES);

        p.mBuffer[p.mPos++] = (byte) ((value >> 24) & 0xff);
        p.mBuffer[p.mPos++] = (byte) ((value >> 16) & 0xff);
        p.mBuffer[p.mPos++] = (byte) ((value >>  8) & 0xff);
        p.mBuffer[p.mPos++] = (byte) ((value >>  0) & 0xff);

        p.updateSize();

        return OK;
    }

    public static int nativeWriteLong(long nativePtr, long value) {
        nativeWriteInt(nativePtr, (int) (value >>> 32));
        nativeWriteInt(nativePtr, (int) (value));
        return OK;
    }
    public static int nativeWriteFloat(long nativePtr, float val) {
        return nativeWriteInt(nativePtr, Float.floatToIntBits(val));
    }
    public static int nativeWriteDouble(long nativePtr, double val) {
        return nativeWriteLong(nativePtr, Double.doubleToLongBits(val));
    }

    private static int align4(int val) {
        return ((val + 3) / 4) * 4;
    }

    public static void nativeWriteString8(long nativePtr, String val) {
        if (val == null) {
            nativeWriteBlob(nativePtr, null, 0, 0);
        } else {
            var bytes = val.getBytes(StandardCharsets.UTF_8);
            nativeWriteBlob(nativePtr, bytes, 0, bytes.length);
        }
    }
    public static void nativeWriteString16(long nativePtr, String val) {
        // Just reuse String8
        nativeWriteString8(nativePtr, val);
    }

    public static byte[] nativeCreateByteArray(long nativePtr) {
        return nativeReadBlob(nativePtr);
    }

    public static boolean nativeReadByteArray(long nativePtr, byte[] dest, int destLen) {
        if (dest == null) {
            return false;
        }
        var data = nativeReadBlob(nativePtr);
        if (data == null) {
            System.err.println("Percel has NULL, which is unexpected."); // TODO: Is this correct?
            return false;
        }
        // TODO: Make sure the check logic is correct.
        if (data.length != destLen) {
            System.err.println("Byte array size mismatch: expected="
                    + data.length + " given=" + destLen);
            return false;
        }
        System.arraycopy(data, 0, dest, 0, data.length);
        return true;
    }

    public static byte[] nativeReadBlob(long nativePtr) {
        var p = getInstance(nativePtr);
        if (p.mSize - p.mPos < 4) {
            // Match native impl that returns "null" when not enough data
            return null;
        }
        final var size = nativeReadInt(nativePtr);
        if (size == -1) {
            return null;
        }
        try {
            p.ensureDataAvailable(align4(size));
        } catch (Exception e) {
            System.err.println(e.toString());
            return null;
        }

        var bytes = new byte[size];
        System.arraycopy(p.mBuffer, p.mPos, bytes, 0, size);

        p.mPos += align4(size);

        return bytes;
    }
    public static int nativeReadInt(long nativePtr) {
        var p = getInstance(nativePtr);

        if (p.mSize - p.mPos < 4) {
            // Match native impl that returns "0" when not enough data
            return 0;
        }

        var ret = (((p.mBuffer[p.mPos++] & 0xff) << 24)
                | ((p.mBuffer[p.mPos++] & 0xff) << 16)
                | ((p.mBuffer[p.mPos++] & 0xff) <<  8)
                | ((p.mBuffer[p.mPos++] & 0xff) <<  0));

        return ret;
    }
    public static long nativeReadLong(long nativePtr) {
        return (((long) nativeReadInt(nativePtr)) << 32)
                | (((long) nativeReadInt(nativePtr)) & 0xffff_ffffL);
    }

    public static float nativeReadFloat(long nativePtr) {
        return Float.intBitsToFloat(nativeReadInt(nativePtr));
    }

    public static double nativeReadDouble(long nativePtr) {
        return Double.longBitsToDouble(nativeReadLong(nativePtr));
    }

    public static String nativeReadString8(long nativePtr) {
        final var bytes = nativeReadBlob(nativePtr);
        if (bytes == null) {
            return null;
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }
    public static String nativeReadString16(long nativePtr) {
        return nativeReadString8(nativePtr);
    }

    public static byte[] nativeMarshall(long nativePtr) {
        var p = getInstance(nativePtr);
        return Arrays.copyOf(p.mBuffer, p.mSize);
    }
    public static void nativeUnmarshall(
            long nativePtr, byte[] data, int offset, int length) {
        var p = getInstance(nativePtr);
        p.ensureMoreCapacity(length);
        System.arraycopy(data, offset, p.mBuffer, p.mPos, length);
        p.mPos += length;
        p.updateSize();
    }
    public static int nativeCompareData(long thisNativePtr, long otherNativePtr) {
        var a = getInstance(thisNativePtr);
        var b = getInstance(otherNativePtr);
        if ((a.mSize == b.mSize) && Arrays.equals(a.mBuffer, b.mBuffer)) {
            return 0;
        } else {
            return -1;
        }
    }
    public static boolean nativeCompareDataInRange(
            long ptrA, int offsetA, long ptrB, int offsetB, int length) {
        var a = getInstance(ptrA);
        var b = getInstance(ptrB);
        if (offsetA < 0 || offsetA + length > a.mSize) {
            throw new IllegalArgumentException();
        }
        if (offsetB < 0 || offsetB + length > b.mSize) {
            throw new IllegalArgumentException();
        }
        return Arrays.equals(Arrays.copyOfRange(a.mBuffer, offsetA, offsetA + length),
                Arrays.copyOfRange(b.mBuffer, offsetB, offsetB + length));
    }
    public static void nativeAppendFrom(
            long thisNativePtr, long otherNativePtr, int srcOffset, int length) {
        var dst = getInstance(thisNativePtr);
        var src = getInstance(otherNativePtr);

        dst.ensureMoreCapacity(length);

        System.arraycopy(src.mBuffer, srcOffset, dst.mBuffer, dst.mPos, length);
        dst.mPos += length; // TODO: 4 byte align?
        dst.updateSize();

        // TODO: Update the other's position?
    }

    public static boolean nativeHasFileDescriptors(long nativePtr) {
        // Assume false for now, because we don't support writing FDs yet.
        return false;
    }
    public static boolean nativeHasFileDescriptorsInRange(
            long nativePtr, int offset, int length) {
        // Assume false for now, because we don't support writing FDs yet.
        return false;
    }
}
