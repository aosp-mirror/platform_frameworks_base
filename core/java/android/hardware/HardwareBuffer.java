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
import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;

import dalvik.annotation.optimization.FastNative;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import libcore.util.NativeAllocationRegistry;

/**
 * HardwareBuffer wraps a native <code>AHardwareBuffer</code> object, which is a low-level object
 * representing a memory buffer accessible by various hardware units. HardwareBuffer allows sharing
 * buffers across different application processes. In particular, HardwareBuffers may be mappable
 * to memory accessibly to various hardware systems, such as the GPU, a sensor or context hub, or
 * other auxiliary processing units.
 *
 * For more information, see the NDK documentation for <code>AHardwareBuffer</code>.
 */
public final class HardwareBuffer implements Parcelable {
    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({RGBA_8888, RGBA_FP16, RGBX_8888, RGB_888, RGB_565, BLOB})
    public @interface Format {};

    /** Format: 8 bits each red, green, blue, alpha */
    public static final int RGBA_8888   = 1;
    /** Format: 8 bits each red, green, blue, alpha, alpha is always 0xFF */
    public static final int RGBX_8888   = 2;
    /** Format: 8 bits each red, green, blue, no alpha */
    public static final int RGB_888     = 3;
    /** Format: 5 bits each red and blue, 6 bits green, no alpha */
    public static final int RGB_565     = 4;
    /** Format: 16 bits each red, green, blue, alpha */
    public static final int RGBA_FP16   = 0x16;
    /** Format: opaque format used for raw data transfer; must have a height of 1 */
    public static final int BLOB        = 0x21;

    // Note: do not rename, this field is used by native code
    private long mNativeObject;

    // Invoked on destruction
    private Runnable mCleaner;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(flag = true, value = {USAGE0_CPU_READ, USAGE0_CPU_READ_OFTEN, USAGE0_CPU_WRITE,
            USAGE0_CPU_WRITE_OFTEN, USAGE0_GPU_SAMPLED_IMAGE, USAGE0_GPU_COLOR_OUTPUT,
            USAGE0_GPU_STORAGE_IMAGE, USAGE0_GPU_CUBEMAP, USAGE0_GPU_DATA_BUFFER,
            USAGE0_PROTECTED_CONTENT, USAGE0_SENSOR_DIRECT_DATA, USAGE0_VIDEO_ENCODE})
    public @interface Usage0 {};

    /** Usage0: the buffer will sometimes be read by the CPU */
    public static final long USAGE0_CPU_READ               = (1 << 1);
    /** Usage0: the buffer will often be read by the CPU*/
    public static final long USAGE0_CPU_READ_OFTEN         = (1 << 2 | USAGE0_CPU_READ);
    /** Usage0: the buffer will sometimes be written to by the CPU */
    public static final long USAGE0_CPU_WRITE              = (1 << 5);
    /** Usage0: the buffer will often be written to by the CPU */
    public static final long USAGE0_CPU_WRITE_OFTEN        = (1 << 6 | USAGE0_CPU_WRITE);
    /** Usage0: the buffer will be read from by the GPU */
    public static final long USAGE0_GPU_SAMPLED_IMAGE      = (1 << 10);
    /** Usage0: the buffer will be written to by the GPU */
    public static final long USAGE0_GPU_COLOR_OUTPUT       = (1 << 11);
    /** Usage0: the buffer will be read from and written to by the GPU */
    public static final long USAGE0_GPU_STORAGE_IMAGE      = (USAGE0_GPU_SAMPLED_IMAGE |
            USAGE0_GPU_COLOR_OUTPUT);
    /** Usage0: the buffer will be used as a cubemap texture */
    public static final long USAGE0_GPU_CUBEMAP            = (1 << 13);
    /** Usage0: the buffer will be used as a shader storage or uniform buffer object*/
    public static final long USAGE0_GPU_DATA_BUFFER        = (1 << 14);
    /** Usage0: the buffer must not be used outside of a protected hardware path */
    public static final long USAGE0_PROTECTED_CONTENT      = (1 << 18);
    /** Usage0: the buffer will be used for sensor direct data */
    public static final long USAGE0_SENSOR_DIRECT_DATA     = (1 << 29);
    /** Usage0: the buffer will be read by a hardware video encoder */
    public static final long USAGE0_VIDEO_ENCODE           = (1 << 21);

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
     * @param format The format of each pixel, one of {@link #RGBA_8888}, {@link #RGBA_FP16},
     * {@link #RGBX_8888}, {@link #RGB_565}, {@link #RGB_888}
     * @param layers The number of layers in the buffer
     * @param usage Flags describing how the buffer will be used, one of
     *     {@link #USAGE0_CPU_READ}, {@link #USAGE0_CPU_READ_OFTEN}, {@link #USAGE0_CPU_WRITE},
     *     {@link #USAGE0_CPU_WRITE_OFTEN}, {@link #USAGE0_GPU_SAMPLED_IMAGE},
     *     {@link #USAGE0_GPU_COLOR_OUTPUT},{@link #USAGE0_GPU_STORAGE_IMAGE},
     *     {@link #USAGE0_GPU_CUBEMAP}, {@link #USAGE0_GPU_DATA_BUFFER},
     *     {@link #USAGE0_PROTECTED_CONTENT}, {@link #USAGE0_SENSOR_DIRECT_DATA},
     *     {@link #USAGE0_VIDEO_ENCODE}
     *
     * @return A <code>HardwareBuffer</code> instance if successful, or throws an
     *     IllegalArgumentException if the dimensions passed are invalid (either zero, negative, or
     *     too large to allocate), if the format is not supported, if the requested number of layers
     *     is less than one or not supported, or if the passed usage flags are not a supported set.
     */
    @NonNull
    public static HardwareBuffer create(int width, int height, @Format int format, int layers,
            @Usage0 long usage) {
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
                    "or an invalid set of usage flags was passed");
        }
        return new HardwareBuffer(nativeObject);
    }

    /**
     * Private use only. See {@link #create(int, int, int, int, int, long, long)}. May also be
     * called from JNI using an already allocated native <code>HardwareBuffer</code>.
     */
    private HardwareBuffer(long nativeObject) {
        mNativeObject = nativeObject;

        long nativeSize = NATIVE_HARDWARE_BUFFER_SIZE;
        NativeAllocationRegistry registry = new NativeAllocationRegistry(
            HardwareBuffer.class.getClassLoader(), nGetNativeFinalizer(), nativeSize);
        mCleaner = registry.registerNativeAllocation(this, mNativeObject);
    }

    /**
     * Returns the width of this buffer in pixels.
     */
    public int getWidth() {
        if (mNativeObject == 0) {
            throw new IllegalStateException("This HardwareBuffer has been destroyed and its width "
                    + "cannot be obtained.");
        }
        return nGetWidth(mNativeObject);
    }

    /**
     * Returns the height of this buffer in pixels.
     */
    public int getHeight() {
        if (mNativeObject == 0) {
            throw new IllegalStateException("This HardwareBuffer has been destroyed and its height "
                    + "cannot be obtained.");
        }
        return nGetHeight(mNativeObject);
    }

    /**
     * Returns the format of this buffer, one of {@link #RGBA_8888}, {@link #RGBA_FP16},
     * {@link #RGBX_8888}, {@link #RGB_565}, or {@link #RGB_888}.
     */
    public int getFormat() {
        if (mNativeObject == 0) {
            throw new IllegalStateException("This HardwareBuffer has been destroyed and its format "
                    + "cannot be obtained.");
        }
        return nGetFormat(mNativeObject);
    }

    /**
     * Returns the number of layers in this buffer.
     */
    public int getLayers() {
        if (mNativeObject == 0) {
            throw new IllegalStateException("This HardwareBuffer has been destroyed and its layer "
                    + "count cannot be obtained.");
        }
        return nGetLayers(mNativeObject);
    }

    /**
     * Returns the usage flags of the usage hints set on this buffer.
     */
    public long getUsage() {
        if (mNativeObject == 0) {
            throw new IllegalStateException("This HardwareBuffer has been destroyed and its usage "
                    + "cannot be obtained.");
        }
        return nGetUsage(mNativeObject);
    }

    /**
     * Destroys this buffer immediately. Calling this method frees up any
     * underlying native resources. After calling this method, this buffer
     * must not be used in any way.
     *
     * @see #isDestroyed()
     */
    public void destroy() {
        if (mNativeObject != 0) {
            mNativeObject = 0;
            mCleaner.run();
            mCleaner = null;
        }
    }

    /**
     * Indicates whether this buffer has been destroyed. A destroyed buffer
     * cannot be used in any way: the buffer cannot be written to a parcel, etc.
     *
     * @return True if this <code>HardwareBuffer</code> is in a destroyed state,
     *         false otherwise.
     *
     * @see #destroy()
     */
    public boolean isDestroyed() {
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
     * {@link #destroy()} has been previously called.</p>
     *
     * @param dest The Parcel in which the object should be written.
     * @param flags Additional flags about how the object should be written.
     *              May be 0 or {@link #PARCELABLE_WRITE_RETURN_VALUE}.
     */
    @Override
    public void writeToParcel(Parcel dest, int flags) {
        if (mNativeObject == 0) {
            throw new IllegalStateException("This HardwareBuffer has been destroyed and cannot be "
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
     * See {@link #create(int, int, int, int, int, long, long)}.a
     */
    private static boolean isSupportedFormat(@Format int format) {
        switch(format) {
            case RGBA_8888:
            case RGBA_FP16:
            case RGBX_8888:
            case RGB_565:
            case RGB_888:
            case BLOB:
                return true;
        }
        return false;
    }

    private static native long nCreateHardwareBuffer(int width, int height, int format, int layers,
            long usage);
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
}
