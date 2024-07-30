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
package com.android.platform.test.ravenwood.nativesubstitution;

import android.system.ErrnoException;
import android.system.Os;
import android.util.Log;

import java.io.FileDescriptor;
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
    private static final String TAG = "Parcel";

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

    private final Map<Integer, FileDescriptor> mFdMap = new ConcurrentHashMap<>();

    private static final int FD_PLACEHOLDER = 0xDEADBEEF;
    private static final int FD_PAYLOAD_SIZE = 8;

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

    /** Native method substitution */
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
        mAllowFds = true;
        mFdMap.clear();
    }

    private void updateSize() {
        if (mSize < mPos) {
            mSize = mPos;
        }
    }

    /** Native method substitution */
    public static void nativeDestroy(long nativePtr) {
        getInstance(nativePtr).mDeleted = true;
        sInstances.remove(nativePtr);
    }

    /** Native method substitution */
    public static void nativeFreeBuffer(long nativePtr) {
        getInstance(nativePtr).freeBuffer();
    }

    /** Native method substitution */
    private void freeBuffer() {
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

    /** Native method substitution */
    public static void nativeMarkSensitive(long nativePtr) {
        getInstance(nativePtr).mSensitive = true;
    }

    /** Native method substitution */
    public static int nativeDataSize(long nativePtr) {
        return getInstance(nativePtr).mSize;
    }

    /** Native method substitution */
    public static int nativeDataAvail(long nativePtr) {
        var p = getInstance(nativePtr);
        return p.mSize - p.mPos;
    }

    /** Native method substitution */
    public static int nativeDataPosition(long nativePtr) {
        return getInstance(nativePtr).mPos;
    }

    /** Native method substitution */
    public static int nativeDataCapacity(long nativePtr) {
        return getInstance(nativePtr).mBuffer.length;
    }

    /** Native method substitution */
    public static void nativeSetDataSize(long nativePtr, int size) {
        var p = getInstance(nativePtr);
        p.ensureCapacity(size);
        getInstance(nativePtr).mSize = size;
    }

    /** Native method substitution */
    public static void nativeSetDataPosition(long nativePtr, int pos) {
        var p = getInstance(nativePtr);
        // TODO: Should this change the size or the capacity??
        p.mPos = pos;
    }

    /** Native method substitution */
    public static void nativeSetDataCapacity(long nativePtr, int size) {
        if (size < 0) {
            throw new IllegalArgumentException("size < 0: size=" + size);
        }
        var p = getInstance(nativePtr);
        if (p.getCapacity() < size) {
            p.forceSetCapacity(size);
        }
    }

    /** Native method substitution */
    public static boolean nativePushAllowFds(long nativePtr, boolean allowFds) {
        var p = getInstance(nativePtr);
        var prev = p.mAllowFds;
        p.mAllowFds = allowFds;
        return prev;
    }

    /** Native method substitution */
    public static void nativeRestoreAllowFds(long nativePtr, boolean lastValue) {
        getInstance(nativePtr).mAllowFds = lastValue;
    }

    /** Native method substitution */
    public static void nativeWriteByteArray(long nativePtr, byte[] b, int offset, int len) {
        nativeWriteBlob(nativePtr, b, offset, len);
    }

    /** Native method substitution */
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

    /** Native method substitution */
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

    /** Native method substitution */
    public static int nativeWriteLong(long nativePtr, long value) {
        nativeWriteInt(nativePtr, (int) (value >>> 32));
        nativeWriteInt(nativePtr, (int) (value));
        return OK;
    }

    /** Native method substitution */
    public static int nativeWriteFloat(long nativePtr, float val) {
        return nativeWriteInt(nativePtr, Float.floatToIntBits(val));
    }

    /** Native method substitution */
    public static int nativeWriteDouble(long nativePtr, double val) {
        return nativeWriteLong(nativePtr, Double.doubleToLongBits(val));
    }

    private static int align4(int val) {
        return ((val + 3) / 4) * 4;
    }

    /** Native method substitution */
    public static void nativeWriteString8(long nativePtr, String val) {
        if (val == null) {
            nativeWriteBlob(nativePtr, null, 0, 0);
        } else {
            var bytes = val.getBytes(StandardCharsets.UTF_8);
            nativeWriteBlob(nativePtr, bytes, 0, bytes.length);
        }
    }

    /** Native method substitution */
    public static void nativeWriteString16(long nativePtr, String val) {
        // Just reuse String8
        nativeWriteString8(nativePtr, val);
    }

    /** Native method substitution */
    public static byte[] nativeCreateByteArray(long nativePtr) {
        return nativeReadBlob(nativePtr);
    }

    /** Native method substitution */
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

    /** Native method substitution */
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

    /** Native method substitution */
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

    /** Native method substitution */
    public static long nativeReadLong(long nativePtr) {
        return (((long) nativeReadInt(nativePtr)) << 32)
                | (((long) nativeReadInt(nativePtr)) & 0xffff_ffffL);
    }

    /** Native method substitution */
    public static float nativeReadFloat(long nativePtr) {
        return Float.intBitsToFloat(nativeReadInt(nativePtr));
    }

    /** Native method substitution */
    public static double nativeReadDouble(long nativePtr) {
        return Double.longBitsToDouble(nativeReadLong(nativePtr));
    }

    /** Native method substitution */
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

    /** Native method substitution */
    public static byte[] nativeMarshall(long nativePtr) {
        var p = getInstance(nativePtr);
        return Arrays.copyOf(p.mBuffer, p.mSize);
    }

    /** Native method substitution */
    public static void nativeUnmarshall(
            long nativePtr, byte[] data, int offset, int length) {
        var p = getInstance(nativePtr);
        p.ensureMoreCapacity(length);
        System.arraycopy(data, offset, p.mBuffer, p.mPos, length);
        p.mPos += length;
        p.updateSize();
    }

    /** Native method substitution */
    public static int nativeCompareData(long thisNativePtr, long otherNativePtr) {
        var a = getInstance(thisNativePtr);
        var b = getInstance(otherNativePtr);
        if ((a.mSize == b.mSize) && Arrays.equals(a.mBuffer, b.mBuffer)) {
            return 0;
        } else {
            return -1;
        }
    }

    /** Native method substitution */
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

    /** Native method substitution */
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

    /** Native method substitution */
    public static boolean nativeHasBinders(long nativePtr) {
        // Assume false for now, because we don't support adding binders.
        return false;
    }

    /** Native method substitution */
    public static boolean nativeHasBindersInRange(
            long nativePtr, int offset, int length) {
        // Assume false for now, because we don't support writing FDs yet.
        return false;
    }

    /** Native method substitution */
    public static void nativeWriteFileDescriptor(long nativePtr, java.io.FileDescriptor val) {
        var p = getInstance(nativePtr);

        if (!p.mAllowFds) {
            // Simulate the FDS_NOT_ALLOWED case in frameworks/base/core/jni/android_util_Binder.cpp
            throw new RuntimeException("Not allowed to write file descriptors here");
        }

        FileDescriptor dup = null;
        try {
            dup = Os.dup(val);
        } catch (ErrnoException e) {
            throw new RuntimeException(e);
        }
        p.mFdMap.put(p.mPos, dup);

        // Parcel.cpp writes two int32s for a FD.
        // Make sure FD_PAYLOAD_SIZE is in sync with this code.
        nativeWriteInt(nativePtr, FD_PLACEHOLDER);
        nativeWriteInt(nativePtr, FD_PLACEHOLDER);
    }

    /** Native method substitution */
    public static java.io.FileDescriptor nativeReadFileDescriptor(long nativePtr) {
        var p = getInstance(nativePtr);

        var pos = p.mPos;
        var fd = p.mFdMap.get(pos);

        if (fd == null) {
            Log.w(TAG, "nativeReadFileDescriptor: Not a FD at pos #" + pos);
            return null;
        }
        nativeReadInt(nativePtr);
        return fd;
    }

    /** Native method substitution */
    public static boolean nativeHasFileDescriptors(long nativePtr) {
        var p = getInstance(nativePtr);
        return p.mFdMap.size() > 0;
    }

    /** Native method substitution */
    public static boolean nativeHasFileDescriptorsInRange(long nativePtr, int offset, int length) {
        var p = getInstance(nativePtr);

        // Original code: hasFileDescriptorsInRange() in frameworks/native/libs/binder/Parcel.cpp
        if (offset < 0 || length < 0) {
            throw new IllegalArgumentException("Negative value not allowed: offset=" + offset
                    + " length=" + length);
        }
        long limit = (long) offset + (long) length;
        if (limit > p.mSize) {
            throw new IllegalArgumentException("Out of range: offset=" + offset
                    + " length=" + length + " dataSize=" + p.mSize);
        }

        for (var pos : p.mFdMap.keySet()) {
            if (offset <= pos && (pos + FD_PAYLOAD_SIZE - 1) < (offset + length)) {
                return true;
            }
        }
        return false;
    }
}