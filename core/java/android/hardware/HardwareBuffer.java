/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.hardware;

import android.annotation.IntDef;
import android.annotation.LongDef;
import android.annotation.NonNull;
import android.annotation.UnsupportedAppUsage;
import android.graphics.GraphicBuffer;
import android.os.Build;
import android.os.Parcel;
import android.os.Parcelable;

import dalvik.annotation.optimization.FastNative;
import dalvik.system.CloseGuard;

import libcore.util.NativeAllocationRegistry;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * HardwareBuffer wraps a native <code>AHardwareBuffer</code> object, which is a low-level object
 * representing a memory buffer accessible by various hardware units. HardwareBuffer allows sharing
 * buffers across different application processes. In particular, HardwareBuffers may be mappable
 * to memory accessibly to various hardware systems, such as the GPU, a sensor or context hub, or
 * other auxiliary processing units.
 *
 * For more information, see the NDK documentation for <code>AHardwareBuffer</code>.
 */
public final class HardwareBuffer implements Parcelable, AutoCloseable {
    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = { "RGB", "BLOB", "D_", "DS_", "S_" }, value = {
            RGBA_8888,
            RGBA_FP16,
            RGBA_1010102,
            RGBX_8888,
            RGB_888,
            RGB_565,
            BLOB,
            D_16,
            D_24,
            DS_24UI8,
            D_FP32,
            DS_FP32UI8,
            S_UI8,
    })
    public @interface Format {
    }

    @Format
    /** Format: 8 bits each red, green, blue, alpha */
    public static final int RGBA_8888    = 1;
    /** Format: 8 bits each red, green, blue, alpha, alpha is always 0xFF */
    public static final int RGBX_8888    = 2;
    /** Format: 8 bits each red, green, blue, no alpha */
    public static final int RGB_888      = 3;
    /** Format: 5 bits each red and blue, 6 bits green, no alpha */
    public static final int RGB_565      = 4;
    /** Format: 16 bits each red, green, blue, alpha */
    public static final int RGBA_FP16    = 0x16;
    /** Format: 10 bits each red, green, blue, 2 bits alpha */
    public static final int RGBA_1010102 = 0x2b;
    /** Format: opaque format used for raw data transfer; must have a height of 1 */
    public static final int BLOB         = 0x21;
    /** Format: 16 bits depth */
    public static final int D_16         = 0x30;
    /** Format: 24 bits depth */
    public static final int D_24         = 0x31;
    /** Format: 24 bits depth, 8 bits stencil */
    public static final int DS_24UI8     = 0x32;
    /** Format: 32 bits depth */
    public static final int D_FP32       = 0x33;
    /** Format: 32 bits depth, 8 bits stencil */
    public static final int DS_FP32UI8   = 0x34;
    /** Format: 8 bits stencil */
    public static final int S_UI8        = 0x35;

    // Note: do not rename, this field is used by native code
    @UnsupportedAppUsage
    private long mNativeObject;

    // Invoked on destruction
    private Runnable mCleaner;

    private final CloseGuard mCloseGuard = CloseGuard.get();

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @LongDef(flag = true, value = {USAGE_CPU_READ_RARELY, USAGE_CPU_READ_OFTEN,
            USAGE_CPU_WRITE_RARELY, USAGE_CPU_WRITE_OFTEN, USAGE_GPU_SAMPLED_IMAGE,
            USAGE_GPU_COLOR_OUTPUT, USAGE_PROTECTED_CONTENT, USAGE_VIDEO_ENCODE,
            USAGE_GPU_DATA_BUFFER, USAGE_SENSOR_DIRECT_DATA, USAGE_GPU_CUBE_MAP,
            USAGE_GPU_MIPMAP_COMPLETE})
    public @interface Usage {};

    @Usage
    /** Usage: The buffer will sometimes be read by the CPU */
    public static final long USAGE_CPU_READ_RARELY       = 2;
    /** Usage: The buffer will often be read by the CPU */
    public static final long USAGE_CPU_READ_OFTEN        = 3;

    /** Usage: The buffer will sometimes be written to by the CPU */
    public static final long USAGE_CPU_WRITE_RARELY      = 2 << 4;
    /** Usage: The buffer will often be written to by the CPU */
    public static final long USAGE_CPU_WRITE_OFTEN       = 3 << 4;

    /** Usage: The buffer will be read from by the GPU */
    public static final long USAGE_GPU_SAMPLED_IMAGE      = 1 << 8;
    /** Usage: The buffer will be written to by the GPU */
    public static final long USAGE_GPU_COLOR_OUTPUT       = 1 << 9;
    /** Usage: The buffer must not be used outside of a protected hardware path */
    public static final long USAGE_PROTECTED_CONTENT      = 1 << 14;
    /** Usage: The buffer will be read by a hardware video encoder */
    public static final long USAGE_VIDEO_ENCODE           = 1 << 16;
    /** Usage: The buffer will be used for sensor direct data */
    public static final long USAGE_SENSOR_DIRECT_DATA     = 1 << 23;
    /** Usage: The buffer will be used as a shader storage or uniform buffer object */
    public static final long USAGE_GPU_DATA_BUFFER        = 1 << 24;
    /** Usage: The buffer will be used as a cube map texture */
    public static final long USAGE_GPU_CUBE_MAP           = 1 << 25;
    /** Usage: The buffer contains a complete mipmap hierarchy */
    public static final long USAGE_GPU_MIPMAP_COMPLETE    = 1 << 26;

    // The approximate size of a native AHardwareBuffer object.
    private static final long NATIVE_HARDWARE_BUFFER_SIZE = 232;
    /**
     * Creates a new <code>HardwareBuffer</code> instance.
     *
     * <p>Calling this method will throw an <code>IllegalStateException</code> if
     * format is not a supported Format type.</p>
     *
     * @param width The width in pixels of the buffer
     * @param height The height in pixels of the buffer
     * @param format The @Format of each pixel
     * @param layers The number of layers in the buffer
     * @param usage The @Usage flags describing how the buffer will be used
     * @return A <code>HardwareBuffer</code> instance if successful, or throws an
     *     IllegalArgumentException if the dimensions passed are invalid (either zero, negative, or
     *     too large to allocate), if the format is not supported, if the requested number of layers
     *     is less than one or not supported, or if the passed usage flags are not a supported set.
     */
    @NonNull
    public static HardwareBuffer create(int width, int height, @Format int format, int layers,
            @Usage long usage) {
        if (!HardwareBuffer.isSupportedFormat(format)) {
            throw new IllegalArgumentException("Invalid pixel format " + format);
        }
        if (width <= 0) {
            throw new IllegalArgumentException("Invalid width " + width);
        }
        if (height <= 0) {
            throw new IllegalArgumentException("Invalid height " + height);
        }
        if (layers <= 0) {
            throw new IllegalArgumentException("Invalid layer count " + layers);
        }
        if (format == BLOB && height != 1) {
            throw new IllegalArgumentException("Height must be 1 when using the BLOB format");
        }
        long nativeObject = nCreateHardwareBuffer(width, height, format, layers, usage);
        if (nativeObject == 0) {
            throw new IllegalArgumentException("Unable to create a HardwareBuffer, either the " +
                    "dimensions passed were too large, too many image layers were requested, " +
                    "or an invalid set of usage flags or invalid format was passed");
        }
        return new HardwareBuffer(nativeObject);
    }

    /**
     * Queries whether the given buffer description is supported by the system. If this returns
     * true, then the allocation may succeed until resource exhaustion occurs. If this returns
     * false then this combination will never succeed.
     *
     * @param width The width in pixels of the buffer
     * @param height The height in pixels of the buffer
     * @param format The @Format of each pixel
     * @param layers The number of layers in the buffer
     * @param usage The @Usage flags describing how the buffer will be used
     * @return True if the combination is supported, false otherwise.
     */
    public static boolean isSupported(int width, int height, @Format int format, int layers,
            @Usage long usage) {
        if (!HardwareBuffer.isSupportedFormat(format)) {
            throw new IllegalArgumentException("Invalid pixel format " + format);
        }
        if (width <= 0) {
            throw new IllegalArgumentException("Invalid width " + width);
        }
        if (height <= 0) {
            throw new IllegalArgumentException("Invalid height " + height);
        }
        if (layers <= 0) {
            throw new IllegalArgumentException("Invalid layer count " + layers);
        }
        if (format == BLOB && height != 1) {
            throw new IllegalArgumentException("Height must be 1 when using the BLOB format");
        }
        return nIsSupported(width, height, format, layers, usage);
    }

    /**
     * @hide
     * Returns a <code>HardwareBuffer</code> instance from <code>GraphicBuffer</code>
     *
     * @param graphicBuffer A GraphicBuffer to be wrapped as HardwareBuffer
     * @return A <code>HardwareBuffer</code> instance.
     */
    @NonNull
    public static HardwareBuffer createFromGraphicBuffer(@NonNull GraphicBuffer graphicBuffer) {
        long nativeObject = nCreateFromGraphicBuffer(graphicBuffer);
        return new HardwareBuffer(nativeObject);
    }

    /**
     * Private use only. See {@link #create(int, int, int, int, long)}. May also be
     * called from JNI using an already allocated native <code>HardwareBuffer</code>.
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    private HardwareBuffer(long nativeObject) {
        mNativeObject = nativeObject;

        ClassLoader loader = HardwareBuffer.class.getClassLoader();
        NativeAllocationRegistry registry = new NativeAllocationRegistry(
                loader, nGetNativeFinalizer(), NATIVE_HARDWARE_BUFFER_SIZE);
        mCleaner = registry.registerNativeAllocation(this, mNativeObject);
        mCloseGuard.open("close");
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            mCloseGuard.warnIfOpen();
            close();
        } finally {
            super.finalize();
        }
    }

    /**
     * Returns the width of this buffer in pixels.
     */
    public int getWidth() {
        if (isClosed()) {
            throw new IllegalStateException("This HardwareBuffer has been closed and its width "
                    + "cannot be obtained.");
        }
        return nGetWidth(mNativeObject);
    }

    /**
     * Returns the height of this buffer in pixels.
     */
    public int getHeight() {
        if (isClosed()) {
            throw new IllegalStateException("This HardwareBuffer has been closed and its height "
                    + "cannot be obtained.");
        }
        return nGetHeight(mNativeObject);
    }

    /**
     * Returns the @Format of this buffer.
     */
    @Format
    public int getFormat() {
        if (isClosed()) {
            throw new IllegalStateException("This HardwareBuffer has been closed and its format "
                    + "cannot be obtained.");
        }
        return nGetFormat(mNativeObject);
    }

    /**
     * Returns the number of layers in this buffer.
     */
    public int getLayers() {
        if (isClosed()) {
            throw new IllegalStateException("This HardwareBuffer has been closed and its layer "
                    + "count cannot be obtained.");
        }
        return nGetLayers(mNativeObject);
    }

    /**
     * Returns the usage flags of the usage hints set on this buffer.
     */
    public long getUsage() {
        if (isClosed()) {
            throw new IllegalStateException("This HardwareBuffer has been closed and its usage "
                    + "cannot be obtained.");
        }
        return nGetUsage(mNativeObject);
    }

    /** @removed replaced by {@link #close()} */
    @Deprecated
    public void destroy() {
        close();
    }

    /** @removed replaced by {@link #isClosed()} */
    @Deprecated
    public boolean isDestroyed() {
        return isClosed();
    }

    /**
     * Destroys this buffer immediately. Calling this method frees up any
     * underlying native resources. After calling this method, this buffer
     * must not be used in any way.
     *
     * @see #isClosed()
     */
    @Override
    public void close() {
        if (!isClosed()) {
            mCloseGuard.close();
            mNativeObject = 0;
            mCleaner.run();
            mCleaner = null;
        }
    }

    /**
     * Indicates whether this buffer has been closed. A closed buffer cannot
     * be used in any way: the buffer cannot be written to a parcel, etc.
     *
     * @return True if this <code>HardwareBuffer</code> is in a closed state,
     *         false otherwise.
     *
     * @see #close()
     */
    public boolean isClosed() {
        return mNativeObject == 0;
    }

    @Override
    public int describeContents() {
        return Parcelable.CONTENTS_FILE_DESCRIPTOR;
    }

    /**
     * Flatten this object in to a Parcel.
     *
     * <p>Calling this method will throw an <code>IllegalStateException</code> if
     * {@link #close()} has been previously called.</p>
     *
     * @param dest The Parcel in which the object should be written.
     * @param flags Additional flags about how the object should be written.
     *              May be 0 or {@link #PARCELABLE_WRITE_RETURN_VALUE}.
     */
    @Override
    public void writeToParcel(Parcel dest, int flags) {
        if (isClosed()) {
            throw new IllegalStateException("This HardwareBuffer has been closed and cannot be "
                    + "written to a parcel.");
        }
        nWriteHardwareBufferToParcel(mNativeObject, dest);
    }

    public static final Parcelable.Creator<HardwareBuffer> CREATOR =
            new Parcelable.Creator<HardwareBuffer>() {
        public HardwareBuffer createFromParcel(Parcel in) {
            long nativeObject = nReadHardwareBufferFromParcel(in);
            if (nativeObject != 0) {
                return new HardwareBuffer(nativeObject);
            }
            return null;
        }

        public HardwareBuffer[] newArray(int size) {
            return new HardwareBuffer[size];
        }
    };

    /**
     * Validates whether a particular format is supported by HardwareBuffer.
     *
     * @param format The format to validate.
     *
     * @return True if <code>format</code> is a supported format. false otherwise.
     * See {@link #create(int, int, int, int, long)}.
     */
    private static boolean isSupportedFormat(@Format int format) {
        switch(format) {
            case RGBA_8888:
            case RGBA_FP16:
            case RGBA_1010102:
            case RGBX_8888:
            case RGB_565:
            case RGB_888:
            case BLOB:
            case D_16:
            case D_24:
            case DS_24UI8:
            case D_FP32:
            case DS_FP32UI8:
            case S_UI8:
                return true;
        }
        return false;
    }

    private static native long nCreateHardwareBuffer(int width, int height, int format, int layers,
            long usage);
    private static native long nCreateFromGraphicBuffer(GraphicBuffer graphicBuffer);
    private static native long nGetNativeFinalizer();
    private static native void nWriteHardwareBufferToParcel(long nativeObject, Parcel dest);
    private static native long nReadHardwareBufferFromParcel(Parcel in);
    @FastNative
    private static native int nGetWidth(long nativeObject);
    @FastNative
    private static native int nGetHeight(long nativeObject);
    @FastNative
    private static native int nGetFormat(long nativeObject);
    @FastNative
    private static native int nGetLayers(long nativeObject);
    @FastNative
    private static native long nGetUsage(long nativeObject);
    private static native boolean nIsSupported(int width, int height, int format, int layers,
            long usage);
}
