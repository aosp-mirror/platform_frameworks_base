/*
 * Copyright (C) 2006 The Android Open Source Project
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

package android.os;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.TestApi;
import android.app.AppOpsManager;
import android.compat.annotation.UnsupportedAppUsage;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.ExceptionUtils;
import android.util.Log;
import android.util.MathUtils;
import android.util.Size;
import android.util.SizeF;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.util.SparseIntArray;

import com.android.internal.util.ArrayUtils;

import dalvik.annotation.optimization.CriticalNative;
import dalvik.annotation.optimization.FastNative;
import dalvik.system.VMRuntime;

import libcore.util.SneakyThrow;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.ObjectStreamClass;
import java.io.Serializable;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Container for a message (data and object references) that can
 * be sent through an IBinder.  A Parcel can contain both flattened data
 * that will be unflattened on the other side of the IPC (using the various
 * methods here for writing specific types, or the general
 * {@link Parcelable} interface), and references to live {@link IBinder}
 * objects that will result in the other side receiving a proxy IBinder
 * connected with the original IBinder in the Parcel.
 *
 * <p class="note">Parcel is <strong>not</strong> a general-purpose
 * serialization mechanism.  This class (and the corresponding
 * {@link Parcelable} API for placing arbitrary objects into a Parcel) is
 * designed as a high-performance IPC transport.  As such, it is not
 * appropriate to place any Parcel data in to persistent storage: changes
 * in the underlying implementation of any of the data in the Parcel can
 * render older data unreadable.</p>
 *
 * <p>The bulk of the Parcel API revolves around reading and writing data
 * of various types.  There are six major classes of such functions available.</p>
 *
 * <h3>Primitives</h3>
 *
 * <p>The most basic data functions are for writing and reading primitive
 * data types: {@link #writeByte}, {@link #readByte}, {@link #writeDouble},
 * {@link #readDouble}, {@link #writeFloat}, {@link #readFloat}, {@link #writeInt},
 * {@link #readInt}, {@link #writeLong}, {@link #readLong},
 * {@link #writeString}, {@link #readString}.  Most other
 * data operations are built on top of these.  The given data is written and
 * read using the endianess of the host CPU.</p>
 *
 * <h3>Primitive Arrays</h3>
 *
 * <p>There are a variety of methods for reading and writing raw arrays
 * of primitive objects, which generally result in writing a 4-byte length
 * followed by the primitive data items.  The methods for reading can either
 * read the data into an existing array, or create and return a new array.
 * These available types are:</p>
 *
 * <ul>
 * <li> {@link #writeBooleanArray(boolean[])},
 * {@link #readBooleanArray(boolean[])}, {@link #createBooleanArray()}
 * <li> {@link #writeByteArray(byte[])},
 * {@link #writeByteArray(byte[], int, int)}, {@link #readByteArray(byte[])},
 * {@link #createByteArray()}
 * <li> {@link #writeCharArray(char[])}, {@link #readCharArray(char[])},
 * {@link #createCharArray()}
 * <li> {@link #writeDoubleArray(double[])}, {@link #readDoubleArray(double[])},
 * {@link #createDoubleArray()}
 * <li> {@link #writeFloatArray(float[])}, {@link #readFloatArray(float[])},
 * {@link #createFloatArray()}
 * <li> {@link #writeIntArray(int[])}, {@link #readIntArray(int[])},
 * {@link #createIntArray()}
 * <li> {@link #writeLongArray(long[])}, {@link #readLongArray(long[])},
 * {@link #createLongArray()}
 * <li> {@link #writeStringArray(String[])}, {@link #readStringArray(String[])},
 * {@link #createStringArray()}.
 * <li> {@link #writeSparseBooleanArray(SparseBooleanArray)},
 * {@link #readSparseBooleanArray()}.
 * </ul>
 *
 * <h3>Parcelables</h3>
 *
 * <p>The {@link Parcelable} protocol provides an extremely efficient (but
 * low-level) protocol for objects to write and read themselves from Parcels.
 * You can use the direct methods {@link #writeParcelable(Parcelable, int)}
 * and {@link #readParcelable(ClassLoader)} or
 * {@link #writeParcelableArray} and
 * {@link #readParcelableArray(ClassLoader)} to write or read.  These
 * methods write both the class type and its data to the Parcel, allowing
 * that class to be reconstructed from the appropriate class loader when
 * later reading.</p>
 *
 * <p>There are also some methods that provide a more efficient way to work
 * with Parcelables: {@link #writeTypedObject}, {@link #writeTypedArray},
 * {@link #writeTypedList}, {@link #readTypedObject},
 * {@link #createTypedArray} and {@link #createTypedArrayList}.  These methods
 * do not write the class information of the original object: instead, the
 * caller of the read function must know what type to expect and pass in the
 * appropriate {@link Parcelable.Creator Parcelable.Creator} instead to
 * properly construct the new object and read its data.  (To more efficient
 * write and read a single Parcelable object that is not null, you can directly
 * call {@link Parcelable#writeToParcel Parcelable.writeToParcel} and
 * {@link Parcelable.Creator#createFromParcel Parcelable.Creator.createFromParcel}
 * yourself.)</p>
 *
 * <h3>Bundles</h3>
 *
 * <p>A special type-safe container, called {@link Bundle}, is available
 * for key/value maps of heterogeneous values.  This has many optimizations
 * for improved performance when reading and writing data, and its type-safe
 * API avoids difficult to debug type errors when finally marshalling the
 * data contents into a Parcel.  The methods to use are
 * {@link #writeBundle(Bundle)}, {@link #readBundle()}, and
 * {@link #readBundle(ClassLoader)}.
 *
 * <h3>Active Objects</h3>
 *
 * <p>An unusual feature of Parcel is the ability to read and write active
 * objects.  For these objects the actual contents of the object is not
 * written, rather a special token referencing the object is written.  When
 * reading the object back from the Parcel, you do not get a new instance of
 * the object, but rather a handle that operates on the exact same object that
 * was originally written.  There are two forms of active objects available.</p>
 *
 * <p>{@link Binder} objects are a core facility of Android's general cross-process
 * communication system.  The {@link IBinder} interface describes an abstract
 * protocol with a Binder object.  Any such interface can be written in to
 * a Parcel, and upon reading you will receive either the original object
 * implementing that interface or a special proxy implementation
 * that communicates calls back to the original object.  The methods to use are
 * {@link #writeStrongBinder(IBinder)},
 * {@link #writeStrongInterface(IInterface)}, {@link #readStrongBinder()},
 * {@link #writeBinderArray(IBinder[])}, {@link #readBinderArray(IBinder[])},
 * {@link #createBinderArray()},
 * {@link #writeBinderList(List)}, {@link #readBinderList(List)},
 * {@link #createBinderArrayList()}.</p>
 *
 * <p>FileDescriptor objects, representing raw Linux file descriptor identifiers,
 * can be written and {@link ParcelFileDescriptor} objects returned to operate
 * on the original file descriptor.  The returned file descriptor is a dup
 * of the original file descriptor: the object and fd is different, but
 * operating on the same underlying file stream, with the same position, etc.
 * The methods to use are {@link #writeFileDescriptor(FileDescriptor)},
 * {@link #readFileDescriptor()}.
 *
 * <h3>Untyped Containers</h3>
 *
 * <p>A final class of methods are for writing and reading standard Java
 * containers of arbitrary types.  These all revolve around the
 * {@link #writeValue(Object)} and {@link #readValue(ClassLoader)} methods
 * which define the types of objects allowed.  The container methods are
 * {@link #writeArray(Object[])}, {@link #readArray(ClassLoader)},
 * {@link #writeList(List)}, {@link #readList(List, ClassLoader)},
 * {@link #readArrayList(ClassLoader)},
 * {@link #writeMap(Map)}, {@link #readMap(Map, ClassLoader)},
 * {@link #writeSparseArray(SparseArray)},
 * {@link #readSparseArray(ClassLoader)}.
 */
public final class Parcel {

    private static final boolean DEBUG_RECYCLE = false;
    private static final boolean DEBUG_ARRAY_MAP = false;
    private static final String TAG = "Parcel";

    @UnsupportedAppUsage
    @SuppressWarnings({"UnusedDeclaration"})
    private long mNativePtr; // used by native code

    /**
     * Flag indicating if {@link #mNativePtr} was allocated by this object,
     * indicating that we're responsible for its lifecycle.
     */
    private boolean mOwnsNativeParcelObject;
    private long mNativeSize;

    private ArrayMap<Class, Object> mClassCookies;

    private RuntimeException mStack;

    /**
     * Whether or not to parcel the stack trace of an exception. This has a performance
     * impact, so should only be included in specific processes and only on debug builds.
     */
    private static boolean sParcelExceptionStackTrace;

    private static final int POOL_SIZE = 6;
    private static final Parcel[] sOwnedPool = new Parcel[POOL_SIZE];
    private static final Parcel[] sHolderPool = new Parcel[POOL_SIZE];

    // Keep in sync with frameworks/native/include/private/binder/ParcelValTypes.h.
    private static final int VAL_NULL = -1;
    private static final int VAL_STRING = 0;
    private static final int VAL_INTEGER = 1;
    private static final int VAL_MAP = 2;
    private static final int VAL_BUNDLE = 3;
    private static final int VAL_PARCELABLE = 4;
    private static final int VAL_SHORT = 5;
    private static final int VAL_LONG = 6;
    private static final int VAL_FLOAT = 7;
    private static final int VAL_DOUBLE = 8;
    private static final int VAL_BOOLEAN = 9;
    private static final int VAL_CHARSEQUENCE = 10;
    private static final int VAL_LIST  = 11;
    private static final int VAL_SPARSEARRAY = 12;
    private static final int VAL_BYTEARRAY = 13;
    private static final int VAL_STRINGARRAY = 14;
    private static final int VAL_IBINDER = 15;
    private static final int VAL_PARCELABLEARRAY = 16;
    private static final int VAL_OBJECTARRAY = 17;
    private static final int VAL_INTARRAY = 18;
    private static final int VAL_LONGARRAY = 19;
    private static final int VAL_BYTE = 20;
    private static final int VAL_SERIALIZABLE = 21;
    private static final int VAL_SPARSEBOOLEANARRAY = 22;
    private static final int VAL_BOOLEANARRAY = 23;
    private static final int VAL_CHARSEQUENCEARRAY = 24;
    private static final int VAL_PERSISTABLEBUNDLE = 25;
    private static final int VAL_SIZE = 26;
    private static final int VAL_SIZEF = 27;
    private static final int VAL_DOUBLEARRAY = 28;

    // The initial int32 in a Binder call's reply Parcel header:
    // Keep these in sync with libbinder's binder/Status.h.
    private static final int EX_SECURITY = -1;
    private static final int EX_BAD_PARCELABLE = -2;
    private static final int EX_ILLEGAL_ARGUMENT = -3;
    private static final int EX_NULL_POINTER = -4;
    private static final int EX_ILLEGAL_STATE = -5;
    private static final int EX_NETWORK_MAIN_THREAD = -6;
    private static final int EX_UNSUPPORTED_OPERATION = -7;
    private static final int EX_SERVICE_SPECIFIC = -8;
    private static final int EX_PARCELABLE = -9;
    /** @hide */
    public static final int EX_HAS_NOTED_APPOPS_REPLY_HEADER = -127; // special; see below
    private static final int EX_HAS_STRICTMODE_REPLY_HEADER = -128;  // special; see below
    // EX_TRANSACTION_FAILED is used exclusively in native code.
    // see libbinder's binder/Status.h
    private static final int EX_TRANSACTION_FAILED = -129;

    @CriticalNative
    private static native void nativeMarkSensitive(long nativePtr);
    @FastNative
    private static native void nativeMarkForBinder(long nativePtr, IBinder binder);
    @CriticalNative
    private static native int nativeDataSize(long nativePtr);
    @CriticalNative
    private static native int nativeDataAvail(long nativePtr);
    @CriticalNative
    private static native int nativeDataPosition(long nativePtr);
    @CriticalNative
    private static native int nativeDataCapacity(long nativePtr);
    @FastNative
    private static native long nativeSetDataSize(long nativePtr, int size);
    @CriticalNative
    private static native void nativeSetDataPosition(long nativePtr, int pos);
    @FastNative
    private static native void nativeSetDataCapacity(long nativePtr, int size);

    @CriticalNative
    private static native boolean nativePushAllowFds(long nativePtr, boolean allowFds);
    @CriticalNative
    private static native void nativeRestoreAllowFds(long nativePtr, boolean lastValue);

    private static native void nativeWriteByteArray(long nativePtr, byte[] b, int offset, int len);
    private static native void nativeWriteBlob(long nativePtr, byte[] b, int offset, int len);
    @FastNative
    private static native void nativeWriteInt(long nativePtr, int val);
    @FastNative
    private static native void nativeWriteLong(long nativePtr, long val);
    @FastNative
    private static native void nativeWriteFloat(long nativePtr, float val);
    @FastNative
    private static native void nativeWriteDouble(long nativePtr, double val);
    @FastNative
    private static native void nativeWriteString8(long nativePtr, String val);
    @FastNative
    private static native void nativeWriteString16(long nativePtr, String val);
    @FastNative
    private static native void nativeWriteStrongBinder(long nativePtr, IBinder val);
    @FastNative
    private static native long nativeWriteFileDescriptor(long nativePtr, FileDescriptor val);

    private static native byte[] nativeCreateByteArray(long nativePtr);
    private static native boolean nativeReadByteArray(long nativePtr, byte[] dest, int destLen);
    private static native byte[] nativeReadBlob(long nativePtr);
    @CriticalNative
    private static native int nativeReadInt(long nativePtr);
    @CriticalNative
    private static native long nativeReadLong(long nativePtr);
    @CriticalNative
    private static native float nativeReadFloat(long nativePtr);
    @CriticalNative
    private static native double nativeReadDouble(long nativePtr);
    @FastNative
    private static native String nativeReadString8(long nativePtr);
    @FastNative
    private static native String nativeReadString16(long nativePtr);
    @FastNative
    private static native IBinder nativeReadStrongBinder(long nativePtr);
    @FastNative
    private static native FileDescriptor nativeReadFileDescriptor(long nativePtr);

    private static native long nativeCreate();
    private static native long nativeFreeBuffer(long nativePtr);
    private static native void nativeDestroy(long nativePtr);

    private static native byte[] nativeMarshall(long nativePtr);
    private static native long nativeUnmarshall(
            long nativePtr, byte[] data, int offset, int length);
    private static native int nativeCompareData(long thisNativePtr, long otherNativePtr);
    private static native long nativeAppendFrom(
            long thisNativePtr, long otherNativePtr, int offset, int length);
    @CriticalNative
    private static native boolean nativeHasFileDescriptors(long nativePtr);
    private static native void nativeWriteInterfaceToken(long nativePtr, String interfaceName);
    private static native void nativeEnforceInterface(long nativePtr, String interfaceName);

    @CriticalNative
    private static native boolean nativeReplaceCallingWorkSourceUid(
            long nativePtr, int workSourceUid);
    @CriticalNative
    private static native int nativeReadCallingWorkSourceUid(long nativePtr);

    /** Last time exception with a stack trace was written */
    private static volatile long sLastWriteExceptionStackTrace;
    /** Used for throttling of writing stack trace, which is costly */
    private static final int WRITE_EXCEPTION_STACK_TRACE_THRESHOLD_MS = 1000;

    @CriticalNative
    private static native long nativeGetBlobAshmemSize(long nativePtr);

    public final static Parcelable.Creator<String> STRING_CREATOR
             = new Parcelable.Creator<String>() {
        public String createFromParcel(Parcel source) {
            return source.readString();
        }
        public String[] newArray(int size) {
            return new String[size];
        }
    };

    /**
     * @hide
     */
    public static class ReadWriteHelper {

        @UnsupportedAppUsage
        public ReadWriteHelper() {
        }

        public static final ReadWriteHelper DEFAULT = new ReadWriteHelper();

        /**
         * Called when writing a string to a parcel. Subclasses wanting to write a string
         * must use {@link #writeStringNoHelper(String)} to avoid
         * infinity recursive calls.
         */
        public void writeString8(Parcel p, String s) {
            p.writeString8NoHelper(s);
        }

        public void writeString16(Parcel p, String s) {
            p.writeString16NoHelper(s);
        }

        /**
         * Called when reading a string to a parcel. Subclasses wanting to read a string
         * must use {@link #readStringNoHelper()} to avoid
         * infinity recursive calls.
         */
        public String readString8(Parcel p) {
            return p.readString8NoHelper();
        }

        public String readString16(Parcel p) {
            return p.readString16NoHelper();
        }
    }

    private ReadWriteHelper mReadWriteHelper = ReadWriteHelper.DEFAULT;

    /**
     * Retrieve a new Parcel object from the pool.
     */
    @NonNull
    public static Parcel obtain() {
        final Parcel[] pool = sOwnedPool;
        synchronized (pool) {
            Parcel p;
            for (int i=0; i<POOL_SIZE; i++) {
                p = pool[i];
                if (p != null) {
                    pool[i] = null;
                    if (DEBUG_RECYCLE) {
                        p.mStack = new RuntimeException();
                    }
                    p.mReadWriteHelper = ReadWriteHelper.DEFAULT;
                    return p;
                }
            }
        }
        return new Parcel(0);
    }

    /**
     * Put a Parcel object back into the pool.  You must not touch
     * the object after this call.
     */
    public final void recycle() {
        if (DEBUG_RECYCLE) mStack = null;
        freeBuffer();

        final Parcel[] pool;
        if (mOwnsNativeParcelObject) {
            pool = sOwnedPool;
        } else {
            mNativePtr = 0;
            pool = sHolderPool;
        }

        synchronized (pool) {
            for (int i=0; i<POOL_SIZE; i++) {
                if (pool[i] == null) {
                    pool[i] = this;
                    return;
                }
            }
        }
    }

    /**
     * Set a {@link ReadWriteHelper}, which can be used to avoid having duplicate strings, for
     * example.
     *
     * @hide
     */
    public void setReadWriteHelper(@Nullable ReadWriteHelper helper) {
        mReadWriteHelper = helper != null ? helper : ReadWriteHelper.DEFAULT;
    }

    /**
     * @return whether this parcel has a {@link ReadWriteHelper}.
     *
     * @hide
     */
    public boolean hasReadWriteHelper() {
        return (mReadWriteHelper != null) && (mReadWriteHelper != ReadWriteHelper.DEFAULT);
    }

    /** @hide */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public static native long getGlobalAllocSize();

    /** @hide */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public static native long getGlobalAllocCount();

    /**
     * Parcel data should be zero'd before realloc'd or deleted.
     *
     * Note: currently this feature requires multiple things to work in concert:
     * - markSensitive must be called on every relative Parcel
     * - FLAG_CLEAR_BUF must be passed into the kernel
     * This requires having code which does the right thing in every method and in every backend
     * of AIDL. Rather than exposing this API, it should be replaced with a single API on
     * IBinder objects which can be called once, and the information should be fed into the
     * Parcel using markForBinder APIs. In terms of code size and number of API calls, this is
     * much more extensible.
     *
     * @hide
     */
    public final void markSensitive() {
        nativeMarkSensitive(mNativePtr);
    }

    /**
     * Associate this parcel with a binder object. This marks the parcel as being prepared for a
     * transaction on this specific binder object. Based on this, the format of the wire binder
     * protocol may change. This should be called before any data is written to the parcel. If this
     * is called multiple times, this will only be marked for the last binder. For future
     * compatibility, it is recommended to call this on all parcels which are being sent over
     * binder.
     *
     * @hide
     */
    public void markForBinder(@NonNull IBinder binder) {
        nativeMarkForBinder(mNativePtr, binder);
    }

    /**
     * Returns the total amount of data contained in the parcel.
     */
    public int dataSize() {
        return nativeDataSize(mNativePtr);
    }

    /**
     * Returns the amount of data remaining to be read from the
     * parcel.  That is, {@link #dataSize}-{@link #dataPosition}.
     */
    public final int dataAvail() {
        return nativeDataAvail(mNativePtr);
    }

    /**
     * Returns the current position in the parcel data.  Never
     * more than {@link #dataSize}.
     */
    public final int dataPosition() {
        return nativeDataPosition(mNativePtr);
    }

    /**
     * Returns the total amount of space in the parcel.  This is always
     * >= {@link #dataSize}.  The difference between it and dataSize() is the
     * amount of room left until the parcel needs to re-allocate its
     * data buffer.
     */
    public final int dataCapacity() {
        return nativeDataCapacity(mNativePtr);
    }

    /**
     * Change the amount of data in the parcel.  Can be either smaller or
     * larger than the current size.  If larger than the current capacity,
     * more memory will be allocated.
     *
     * @param size The new number of bytes in the Parcel.
     */
    public final void setDataSize(int size) {
        updateNativeSize(nativeSetDataSize(mNativePtr, size));
    }

    /**
     * Move the current read/write position in the parcel.
     * @param pos New offset in the parcel; must be between 0 and
     * {@link #dataSize}.
     */
    public final void setDataPosition(int pos) {
        nativeSetDataPosition(mNativePtr, pos);
    }

    /**
     * Change the capacity (current available space) of the parcel.
     *
     * @param size The new capacity of the parcel, in bytes.  Can not be
     * less than {@link #dataSize} -- that is, you can not drop existing data
     * with this method.
     */
    public final void setDataCapacity(int size) {
        nativeSetDataCapacity(mNativePtr, size);
    }

    /** @hide */
    public final boolean pushAllowFds(boolean allowFds) {
        return nativePushAllowFds(mNativePtr, allowFds);
    }

    /** @hide */
    public final void restoreAllowFds(boolean lastValue) {
        nativeRestoreAllowFds(mNativePtr, lastValue);
    }

    /**
     * Returns the raw bytes of the parcel.
     *
     * <p class="note">The data you retrieve here <strong>must not</strong>
     * be placed in any kind of persistent storage (on local disk, across
     * a network, etc).  For that, you should use standard serialization
     * or another kind of general serialization mechanism.  The Parcel
     * marshalled representation is highly optimized for local IPC, and as
     * such does not attempt to maintain compatibility with data created
     * in different versions of the platform.
     */
    public final byte[] marshall() {
        return nativeMarshall(mNativePtr);
    }

    /**
     * Set the bytes in data to be the raw bytes of this Parcel.
     */
    public final void unmarshall(@NonNull byte[] data, int offset, int length) {
        updateNativeSize(nativeUnmarshall(mNativePtr, data, offset, length));
    }

    public final void appendFrom(Parcel parcel, int offset, int length) {
        updateNativeSize(nativeAppendFrom(mNativePtr, parcel.mNativePtr, offset, length));
    }

    /** @hide */
    public final int compareData(Parcel other) {
        return nativeCompareData(mNativePtr, other.mNativePtr);
    }

    /** @hide */
    public final void setClassCookie(Class clz, Object cookie) {
        if (mClassCookies == null) {
            mClassCookies = new ArrayMap<>();
        }
        mClassCookies.put(clz, cookie);
    }

    /** @hide */
    @Nullable
    public final Object getClassCookie(Class clz) {
        return mClassCookies != null ? mClassCookies.get(clz) : null;
    }

    /** @hide */
    public final void adoptClassCookies(Parcel from) {
        mClassCookies = from.mClassCookies;
    }

    /** @hide */
    public Map<Class, Object> copyClassCookies() {
        return new ArrayMap<>(mClassCookies);
    }

    /** @hide */
    public void putClassCookies(Map<Class, Object> cookies) {
        if (cookies == null) {
            return;
        }
        if (mClassCookies == null) {
            mClassCookies = new ArrayMap<>();
        }
        mClassCookies.putAll(cookies);
    }

    /**
     * Report whether the parcel contains any marshalled file descriptors.
     */
    public final boolean hasFileDescriptors() {
        return nativeHasFileDescriptors(mNativePtr);
    }

    /**
     * Check if the object used in {@link #readValue(ClassLoader)} / {@link #writeValue(Object)}
     * has file descriptors.
     *
     * <p>For most cases, it will use the self-reported {@link Parcelable#describeContents()} method
     * for that.
     *
     * @throws IllegalArgumentException if you provide any object not supported by above methods.
     *         Most notably, if you pass {@link Parcel}, this method will throw, for that check
     *         {@link Parcel#hasFileDescriptors()}
     *
     * @hide
     */
    public static boolean hasFileDescriptors(Object value) {
        if (value instanceof LazyValue) {
            return ((LazyValue) value).hasFileDescriptors();
        } else if (value instanceof Parcelable) {
            if ((((Parcelable) value).describeContents()
                    & Parcelable.CONTENTS_FILE_DESCRIPTOR) != 0) {
                return true;
            }
        } else if (value instanceof Parcelable[]) {
            Parcelable[] array = (Parcelable[]) value;
            for (int n = array.length - 1; n >= 0; n--) {
                Parcelable p = array[n];
                if (p != null && ((p.describeContents()
                        & Parcelable.CONTENTS_FILE_DESCRIPTOR) != 0)) {
                    return true;
                }
            }
        } else if (value instanceof SparseArray<?>) {
            SparseArray<?> array = (SparseArray<?>) value;
            for (int n = array.size() - 1; n >= 0; n--) {
                Object object = array.valueAt(n);
                if (object instanceof Parcelable) {
                    Parcelable p = (Parcelable) object;
                    if (p != null && (p.describeContents()
                            & Parcelable.CONTENTS_FILE_DESCRIPTOR) != 0) {
                        return true;
                    }
                }
            }
        } else if (value instanceof ArrayList<?>) {
            ArrayList<?> array = (ArrayList<?>) value;
            for (int n = array.size() - 1; n >= 0; n--) {
                Object object = array.get(n);
                if (object instanceof Parcelable) {
                    Parcelable p = (Parcelable) object;
                    if (p != null && ((p.describeContents()
                            & Parcelable.CONTENTS_FILE_DESCRIPTOR) != 0)) {
                        return true;
                    }
                }
            }
        } else {
            getValueType(value); // Will throw if value is not supported
        }
        return false;
    }

    /**
     * Store or read an IBinder interface token in the parcel at the current
     * {@link #dataPosition}.  This is used to validate that the marshalled
     * transaction is intended for the target interface.
     */
    public final void writeInterfaceToken(@NonNull String interfaceName) {
        nativeWriteInterfaceToken(mNativePtr, interfaceName);
    }

    public final void enforceInterface(@NonNull String interfaceName) {
        nativeEnforceInterface(mNativePtr, interfaceName);
    }

    /**
     * Writes the work source uid to the request headers.
     *
     * <p>It requires the headers to have been written/read already to replace the work source.
     *
     * @return true if the request headers have been updated.
     *
     * @hide
     */
    public boolean replaceCallingWorkSourceUid(int workSourceUid) {
        return nativeReplaceCallingWorkSourceUid(mNativePtr, workSourceUid);
    }

    /**
     * Reads the work source uid from the request headers.
     *
     * <p>Unlike other read methods, this method does not read the parcel at the current
     * {@link #dataPosition}. It will set the {@link #dataPosition} before the read and restore the
     * position after reading the request header.
     *
     * @return the work source uid or {@link Binder#UNSET_WORKSOURCE} if headers have not been
     * written/parsed yet.
     *
     * @hide
     */
    public int readCallingWorkSourceUid() {
        return nativeReadCallingWorkSourceUid(mNativePtr);
    }

    /**
     * Write a byte array into the parcel at the current {@link #dataPosition},
     * growing {@link #dataCapacity} if needed.
     * @param b Bytes to place into the parcel.
     */
    public final void writeByteArray(@Nullable byte[] b) {
        writeByteArray(b, 0, (b != null) ? b.length : 0);
    }

    /**
     * Write a byte array into the parcel at the current {@link #dataPosition},
     * growing {@link #dataCapacity} if needed.
     * @param b Bytes to place into the parcel.
     * @param offset Index of first byte to be written.
     * @param len Number of bytes to write.
     */
    public final void writeByteArray(@Nullable byte[] b, int offset, int len) {
        if (b == null) {
            writeInt(-1);
            return;
        }
        ArrayUtils.throwsIfOutOfBounds(b.length, offset, len);
        nativeWriteByteArray(mNativePtr, b, offset, len);
    }

    /**
     * Write a blob of data into the parcel at the current {@link #dataPosition},
     * growing {@link #dataCapacity} if needed.
     * @param b Bytes to place into the parcel.
     * {@hide}
     * {@SystemApi}
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public final void writeBlob(@Nullable byte[] b) {
        writeBlob(b, 0, (b != null) ? b.length : 0);
    }

    /**
     * Write a blob of data into the parcel at the current {@link #dataPosition},
     * growing {@link #dataCapacity} if needed.
     * @param b Bytes to place into the parcel.
     * @param offset Index of first byte to be written.
     * @param len Number of bytes to write.
     * {@hide}
     * {@SystemApi}
     */
    public final void writeBlob(@Nullable byte[] b, int offset, int len) {
        if (b == null) {
            writeInt(-1);
            return;
        }
        ArrayUtils.throwsIfOutOfBounds(b.length, offset, len);
        nativeWriteBlob(mNativePtr, b, offset, len);
    }

    /**
     * Write an integer value into the parcel at the current dataPosition(),
     * growing dataCapacity() if needed.
     */
    public final void writeInt(int val) {
        nativeWriteInt(mNativePtr, val);
    }

    /**
     * Write a long integer value into the parcel at the current dataPosition(),
     * growing dataCapacity() if needed.
     */
    public final void writeLong(long val) {
        nativeWriteLong(mNativePtr, val);
    }

    /**
     * Write a floating point value into the parcel at the current
     * dataPosition(), growing dataCapacity() if needed.
     */
    public final void writeFloat(float val) {
        nativeWriteFloat(mNativePtr, val);
    }

    /**
     * Write a double precision floating point value into the parcel at the
     * current dataPosition(), growing dataCapacity() if needed.
     */
    public final void writeDouble(double val) {
        nativeWriteDouble(mNativePtr, val);
    }

    /**
     * Write a string value into the parcel at the current dataPosition(),
     * growing dataCapacity() if needed.
     */
    public final void writeString(@Nullable String val) {
        writeString16(val);
    }

    /** {@hide} */
    public final void writeString8(@Nullable String val) {
        mReadWriteHelper.writeString8(this, val);
    }

    /** {@hide} */
    public final void writeString16(@Nullable String val) {
        mReadWriteHelper.writeString16(this, val);
    }

    /**
     * Write a string without going though a {@link ReadWriteHelper}.  Subclasses of
     * {@link ReadWriteHelper} must use this method instead of {@link #writeString} to avoid
     * infinity recursive calls.
     *
     * @hide
     */
    public void writeStringNoHelper(@Nullable String val) {
        writeString16NoHelper(val);
    }

    /** {@hide} */
    public void writeString8NoHelper(@Nullable String val) {
        nativeWriteString8(mNativePtr, val);
    }

    /** {@hide} */
    public void writeString16NoHelper(@Nullable String val) {
        nativeWriteString16(mNativePtr, val);
    }

    /**
     * Write a boolean value into the parcel at the current dataPosition(),
     * growing dataCapacity() if needed.
     *
     * <p>Note: This method currently delegates to writeInt with a value of 1 or 0
     * for true or false, respectively, but may change in the future.
     */
    public final void writeBoolean(boolean val) {
        writeInt(val ? 1 : 0);
    }

    /**
     * Write a CharSequence value into the parcel at the current dataPosition(),
     * growing dataCapacity() if needed.
     * @hide
     */
    @UnsupportedAppUsage
    public final void writeCharSequence(@Nullable CharSequence val) {
        TextUtils.writeToParcel(val, this, 0);
    }

    /**
     * Write an object into the parcel at the current dataPosition(),
     * growing dataCapacity() if needed.
     */
    public final void writeStrongBinder(IBinder val) {
        nativeWriteStrongBinder(mNativePtr, val);
    }

    /**
     * Write an object into the parcel at the current dataPosition(),
     * growing dataCapacity() if needed.
     */
    public final void writeStrongInterface(IInterface val) {
        writeStrongBinder(val == null ? null : val.asBinder());
    }

    /**
     * Write a FileDescriptor into the parcel at the current dataPosition(),
     * growing dataCapacity() if needed.
     *
     * <p class="caution">The file descriptor will not be closed, which may
     * result in file descriptor leaks when objects are returned from Binder
     * calls.  Use {@link ParcelFileDescriptor#writeToParcel} instead, which
     * accepts contextual flags and will close the original file descriptor
     * if {@link Parcelable#PARCELABLE_WRITE_RETURN_VALUE} is set.</p>
     */
    public final void writeFileDescriptor(@NonNull FileDescriptor val) {
        updateNativeSize(nativeWriteFileDescriptor(mNativePtr, val));
    }

    private void updateNativeSize(long newNativeSize) {
        if (mOwnsNativeParcelObject) {
            if (newNativeSize > Integer.MAX_VALUE) {
                newNativeSize = Integer.MAX_VALUE;
            }
            if (newNativeSize != mNativeSize) {
                int delta = (int) (newNativeSize - mNativeSize);
                if (delta > 0) {
                    VMRuntime.getRuntime().registerNativeAllocation(delta);
                } else {
                    VMRuntime.getRuntime().registerNativeFree(-delta);
                }
                mNativeSize = newNativeSize;
            }
        }
    }

    /**
     * {@hide}
     * This will be the new name for writeFileDescriptor, for consistency.
     **/
    public final void writeRawFileDescriptor(@NonNull FileDescriptor val) {
        nativeWriteFileDescriptor(mNativePtr, val);
    }

    /**
     * {@hide}
     * Write an array of FileDescriptor objects into the Parcel.
     *
     * @param value The array of objects to be written.
     */
    public final void writeRawFileDescriptorArray(@Nullable FileDescriptor[] value) {
        if (value != null) {
            int N = value.length;
            writeInt(N);
            for (int i=0; i<N; i++) {
                writeRawFileDescriptor(value[i]);
            }
        } else {
            writeInt(-1);
        }
    }

    /**
     * Write a byte value into the parcel at the current dataPosition(),
     * growing dataCapacity() if needed.
     *
     * <p>Note: This method currently delegates to writeInt but may change in
     * the future.
     */
    public final void writeByte(byte val) {
        writeInt(val);
    }

    /**
     * Please use {@link #writeBundle} instead.  Flattens a Map into the parcel
     * at the current dataPosition(),
     * growing dataCapacity() if needed.  The Map keys must be String objects.
     * The Map values are written using {@link #writeValue} and must follow
     * the specification there.
     *
     * <p>It is strongly recommended to use {@link #writeBundle} instead of
     * this method, since the Bundle class provides a type-safe API that
     * allows you to avoid mysterious type errors at the point of marshalling.
     */
    public final void writeMap(@Nullable Map val) {
        writeMapInternal((Map<String, Object>) val);
    }

    /**
     * Flatten a Map into the parcel at the current dataPosition(),
     * growing dataCapacity() if needed.  The Map keys must be String objects.
     */
    /* package */ void writeMapInternal(@Nullable Map<String,Object> val) {
        if (val == null) {
            writeInt(-1);
            return;
        }
        Set<Map.Entry<String,Object>> entries = val.entrySet();
        int size = entries.size();
        writeInt(size);

        for (Map.Entry<String,Object> e : entries) {
            writeValue(e.getKey());
            writeValue(e.getValue());
            size--;
        }

        if (size != 0) {
            throw new BadParcelableException("Map size does not match number of entries!");
        }

    }

    /**
     * Flatten an ArrayMap into the parcel at the current dataPosition(),
     * growing dataCapacity() if needed.  The Map keys must be String objects.
     */
    /* package */ void writeArrayMapInternal(@Nullable ArrayMap<String, Object> val) {
        if (val == null) {
            writeInt(-1);
            return;
        }
        // Keep the format of this Parcel in sync with writeToParcelInner() in
        // frameworks/native/libs/binder/PersistableBundle.cpp.
        final int N = val.size();
        writeInt(N);
        if (DEBUG_ARRAY_MAP) {
            RuntimeException here =  new RuntimeException("here");
            here.fillInStackTrace();
            Log.d(TAG, "Writing " + N + " ArrayMap entries", here);
        }
        int startPos;
        for (int i=0; i<N; i++) {
            if (DEBUG_ARRAY_MAP) startPos = dataPosition();
            writeString(val.keyAt(i));
            writeValue(val.valueAt(i));
            if (DEBUG_ARRAY_MAP) Log.d(TAG, "  Write #" + i + " "
                    + (dataPosition()-startPos) + " bytes: key=0x"
                    + Integer.toHexString(val.keyAt(i) != null ? val.keyAt(i).hashCode() : 0)
                    + " " + val.keyAt(i));
        }
    }

    /**
     * @hide For testing only.
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public void writeArrayMap(@Nullable ArrayMap<String, Object> val) {
        writeArrayMapInternal(val);
    }

    /**
     * Flatten an {@link ArrayMap} with string keys containing a particular object
     * type into the parcel at the current dataPosition() and growing dataCapacity()
     * if needed. The type of the objects in the array must be one that implements
     * Parcelable. Only the raw data of the objects is written and not their type,
     * so you must use the corresponding {@link #createTypedArrayMap(Parcelable.Creator)}
     *
     * @param val The map of objects to be written.
     * @param parcelableFlags The parcelable flags to use.
     *
     * @see #createTypedArrayMap(Parcelable.Creator)
     * @see Parcelable
     */
    public <T extends Parcelable> void writeTypedArrayMap(@Nullable ArrayMap<String, T> val,
            int parcelableFlags) {
        if (val == null) {
            writeInt(-1);
            return;
        }
        final int count = val.size();
        writeInt(count);
        for (int i = 0; i < count; i++) {
            writeString(val.keyAt(i));
            writeTypedObject(val.valueAt(i), parcelableFlags);
        }
    }

    /**
     * Write an array set to the parcel.
     *
     * @param val The array set to write.
     *
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public void writeArraySet(@Nullable ArraySet<? extends Object> val) {
        final int size = (val != null) ? val.size() : -1;
        writeInt(size);
        for (int i = 0; i < size; i++) {
            writeValue(val.valueAt(i));
        }
    }

    /**
     * Flatten a Bundle into the parcel at the current dataPosition(),
     * growing dataCapacity() if needed.
     */
    public final void writeBundle(@Nullable Bundle val) {
        if (val == null) {
            writeInt(-1);
            return;
        }

        val.writeToParcel(this, 0);
    }

    /**
     * Flatten a PersistableBundle into the parcel at the current dataPosition(),
     * growing dataCapacity() if needed.
     */
    public final void writePersistableBundle(@Nullable PersistableBundle val) {
        if (val == null) {
            writeInt(-1);
            return;
        }

        val.writeToParcel(this, 0);
    }

    /**
     * Flatten a Size into the parcel at the current dataPosition(),
     * growing dataCapacity() if needed.
     */
    public final void writeSize(@NonNull Size val) {
        writeInt(val.getWidth());
        writeInt(val.getHeight());
    }

    /**
     * Flatten a SizeF into the parcel at the current dataPosition(),
     * growing dataCapacity() if needed.
     */
    public final void writeSizeF(@NonNull SizeF val) {
        writeFloat(val.getWidth());
        writeFloat(val.getHeight());
    }

    /**
     * Flatten a List into the parcel at the current dataPosition(), growing
     * dataCapacity() if needed.  The List values are written using
     * {@link #writeValue} and must follow the specification there.
     */
    public final void writeList(@Nullable List val) {
        if (val == null) {
            writeInt(-1);
            return;
        }
        int N = val.size();
        int i=0;
        writeInt(N);
        while (i < N) {
            writeValue(val.get(i));
            i++;
        }
    }

    /**
     * Flatten an Object array into the parcel at the current dataPosition(),
     * growing dataCapacity() if needed.  The array values are written using
     * {@link #writeValue} and must follow the specification there.
     */
    public final void writeArray(@Nullable Object[] val) {
        if (val == null) {
            writeInt(-1);
            return;
        }
        int N = val.length;
        int i=0;
        writeInt(N);
        while (i < N) {
            writeValue(val[i]);
            i++;
        }
    }

    /**
     * Flatten a generic SparseArray into the parcel at the current
     * dataPosition(), growing dataCapacity() if needed.  The SparseArray
     * values are written using {@link #writeValue} and must follow the
     * specification there.
     */
    public final <T> void writeSparseArray(@Nullable SparseArray<T> val) {
        if (val == null) {
            writeInt(-1);
            return;
        }
        int N = val.size();
        writeInt(N);
        int i=0;
        while (i < N) {
            writeInt(val.keyAt(i));
            writeValue(val.valueAt(i));
            i++;
        }
    }

    public final void writeSparseBooleanArray(@Nullable SparseBooleanArray val) {
        if (val == null) {
            writeInt(-1);
            return;
        }
        int N = val.size();
        writeInt(N);
        int i=0;
        while (i < N) {
            writeInt(val.keyAt(i));
            writeByte((byte)(val.valueAt(i) ? 1 : 0));
            i++;
        }
    }

    /**
     * @hide
     */
    public final void writeSparseIntArray(@Nullable SparseIntArray val) {
        if (val == null) {
            writeInt(-1);
            return;
        }
        int N = val.size();
        writeInt(N);
        int i=0;
        while (i < N) {
            writeInt(val.keyAt(i));
            writeInt(val.valueAt(i));
            i++;
        }
    }

    public final void writeBooleanArray(@Nullable boolean[] val) {
        if (val != null) {
            int N = val.length;
            writeInt(N);
            for (int i=0; i<N; i++) {
                writeInt(val[i] ? 1 : 0);
            }
        } else {
            writeInt(-1);
        }
    }

    @Nullable
    public final boolean[] createBooleanArray() {
        int N = readInt();
        // >>2 as a fast divide-by-4 works in the create*Array() functions
        // because dataAvail() will never return a negative number.  4 is
        // the size of a stored boolean in the stream.
        if (N >= 0 && N <= (dataAvail() >> 2)) {
            boolean[] val = new boolean[N];
            for (int i=0; i<N; i++) {
                val[i] = readInt() != 0;
            }
            return val;
        } else {
            return null;
        }
    }

    public final void readBooleanArray(@NonNull boolean[] val) {
        int N = readInt();
        if (N == val.length) {
            for (int i=0; i<N; i++) {
                val[i] = readInt() != 0;
            }
        } else {
            throw new RuntimeException("bad array lengths");
        }
    }

    public final void writeCharArray(@Nullable char[] val) {
        if (val != null) {
            int N = val.length;
            writeInt(N);
            for (int i=0; i<N; i++) {
                writeInt((int)val[i]);
            }
        } else {
            writeInt(-1);
        }
    }

    @Nullable
    public final char[] createCharArray() {
        int N = readInt();
        if (N >= 0 && N <= (dataAvail() >> 2)) {
            char[] val = new char[N];
            for (int i=0; i<N; i++) {
                val[i] = (char)readInt();
            }
            return val;
        } else {
            return null;
        }
    }

    public final void readCharArray(@NonNull char[] val) {
        int N = readInt();
        if (N == val.length) {
            for (int i=0; i<N; i++) {
                val[i] = (char)readInt();
            }
        } else {
            throw new RuntimeException("bad array lengths");
        }
    }

    public final void writeIntArray(@Nullable int[] val) {
        if (val != null) {
            int N = val.length;
            writeInt(N);
            for (int i=0; i<N; i++) {
                writeInt(val[i]);
            }
        } else {
            writeInt(-1);
        }
    }

    @Nullable
    public final int[] createIntArray() {
        int N = readInt();
        if (N >= 0 && N <= (dataAvail() >> 2)) {
            int[] val = new int[N];
            for (int i=0; i<N; i++) {
                val[i] = readInt();
            }
            return val;
        } else {
            return null;
        }
    }

    public final void readIntArray(@NonNull int[] val) {
        int N = readInt();
        if (N == val.length) {
            for (int i=0; i<N; i++) {
                val[i] = readInt();
            }
        } else {
            throw new RuntimeException("bad array lengths");
        }
    }

    public final void writeLongArray(@Nullable long[] val) {
        if (val != null) {
            int N = val.length;
            writeInt(N);
            for (int i=0; i<N; i++) {
                writeLong(val[i]);
            }
        } else {
            writeInt(-1);
        }
    }

    @Nullable
    public final long[] createLongArray() {
        int N = readInt();
        // >>3 because stored longs are 64 bits
        if (N >= 0 && N <= (dataAvail() >> 3)) {
            long[] val = new long[N];
            for (int i=0; i<N; i++) {
                val[i] = readLong();
            }
            return val;
        } else {
            return null;
        }
    }

    public final void readLongArray(@NonNull long[] val) {
        int N = readInt();
        if (N == val.length) {
            for (int i=0; i<N; i++) {
                val[i] = readLong();
            }
        } else {
            throw new RuntimeException("bad array lengths");
        }
    }

    public final void writeFloatArray(@Nullable float[] val) {
        if (val != null) {
            int N = val.length;
            writeInt(N);
            for (int i=0; i<N; i++) {
                writeFloat(val[i]);
            }
        } else {
            writeInt(-1);
        }
    }

    @Nullable
    public final float[] createFloatArray() {
        int N = readInt();
        // >>2 because stored floats are 4 bytes
        if (N >= 0 && N <= (dataAvail() >> 2)) {
            float[] val = new float[N];
            for (int i=0; i<N; i++) {
                val[i] = readFloat();
            }
            return val;
        } else {
            return null;
        }
    }

    public final void readFloatArray(@NonNull float[] val) {
        int N = readInt();
        if (N == val.length) {
            for (int i=0; i<N; i++) {
                val[i] = readFloat();
            }
        } else {
            throw new RuntimeException("bad array lengths");
        }
    }

    public final void writeDoubleArray(@Nullable double[] val) {
        if (val != null) {
            int N = val.length;
            writeInt(N);
            for (int i=0; i<N; i++) {
                writeDouble(val[i]);
            }
        } else {
            writeInt(-1);
        }
    }

    @Nullable
    public final double[] createDoubleArray() {
        int N = readInt();
        // >>3 because stored doubles are 8 bytes
        if (N >= 0 && N <= (dataAvail() >> 3)) {
            double[] val = new double[N];
            for (int i=0; i<N; i++) {
                val[i] = readDouble();
            }
            return val;
        } else {
            return null;
        }
    }

    public final void readDoubleArray(@NonNull double[] val) {
        int N = readInt();
        if (N == val.length) {
            for (int i=0; i<N; i++) {
                val[i] = readDouble();
            }
        } else {
            throw new RuntimeException("bad array lengths");
        }
    }

    public final void writeStringArray(@Nullable String[] val) {
        writeString16Array(val);
    }

    @Nullable
    public final String[] createStringArray() {
        return createString16Array();
    }

    public final void readStringArray(@NonNull String[] val) {
        readString16Array(val);
    }

    /** {@hide} */
    public final void writeString8Array(@Nullable String[] val) {
        if (val != null) {
            int N = val.length;
            writeInt(N);
            for (int i=0; i<N; i++) {
                writeString8(val[i]);
            }
        } else {
            writeInt(-1);
        }
    }

    /** {@hide} */
    @Nullable
    public final String[] createString8Array() {
        int N = readInt();
        if (N >= 0) {
            String[] val = new String[N];
            for (int i=0; i<N; i++) {
                val[i] = readString8();
            }
            return val;
        } else {
            return null;
        }
    }

    /** {@hide} */
    public final void readString8Array(@NonNull String[] val) {
        int N = readInt();
        if (N == val.length) {
            for (int i=0; i<N; i++) {
                val[i] = readString8();
            }
        } else {
            throw new RuntimeException("bad array lengths");
        }
    }

    /** {@hide} */
    public final void writeString16Array(@Nullable String[] val) {
        if (val != null) {
            int N = val.length;
            writeInt(N);
            for (int i=0; i<N; i++) {
                writeString16(val[i]);
            }
        } else {
            writeInt(-1);
        }
    }

    /** {@hide} */
    @Nullable
    public final String[] createString16Array() {
        int N = readInt();
        if (N >= 0) {
            String[] val = new String[N];
            for (int i=0; i<N; i++) {
                val[i] = readString16();
            }
            return val;
        } else {
            return null;
        }
    }

    /** {@hide} */
    public final void readString16Array(@NonNull String[] val) {
        int N = readInt();
        if (N == val.length) {
            for (int i=0; i<N; i++) {
                val[i] = readString16();
            }
        } else {
            throw new RuntimeException("bad array lengths");
        }
    }

    public final void writeBinderArray(@Nullable IBinder[] val) {
        if (val != null) {
            int N = val.length;
            writeInt(N);
            for (int i=0; i<N; i++) {
                writeStrongBinder(val[i]);
            }
        } else {
            writeInt(-1);
        }
    }

    /**
     * @hide
     */
    public final void writeCharSequenceArray(@Nullable CharSequence[] val) {
        if (val != null) {
            int N = val.length;
            writeInt(N);
            for (int i=0; i<N; i++) {
                writeCharSequence(val[i]);
            }
        } else {
            writeInt(-1);
        }
    }

    /**
     * @hide
     */
    public final void writeCharSequenceList(@Nullable ArrayList<CharSequence> val) {
        if (val != null) {
            int N = val.size();
            writeInt(N);
            for (int i=0; i<N; i++) {
                writeCharSequence(val.get(i));
            }
        } else {
            writeInt(-1);
        }
    }

    @Nullable
    public final IBinder[] createBinderArray() {
        int N = readInt();
        if (N >= 0) {
            IBinder[] val = new IBinder[N];
            for (int i=0; i<N; i++) {
                val[i] = readStrongBinder();
            }
            return val;
        } else {
            return null;
        }
    }

    public final void readBinderArray(@NonNull IBinder[] val) {
        int N = readInt();
        if (N == val.length) {
            for (int i=0; i<N; i++) {
                val[i] = readStrongBinder();
            }
        } else {
            throw new RuntimeException("bad array lengths");
        }
    }

    /**
     * Flatten a List containing a particular object type into the parcel, at
     * the current dataPosition() and growing dataCapacity() if needed.  The
     * type of the objects in the list must be one that implements Parcelable.
     * Unlike the generic writeList() method, however, only the raw data of the
     * objects is written and not their type, so you must use the corresponding
     * readTypedList() to unmarshall them.
     *
     * @param val The list of objects to be written.
     *
     * @see #createTypedArrayList
     * @see #readTypedList
     * @see Parcelable
     */
    public final <T extends Parcelable> void writeTypedList(@Nullable List<T> val) {
        writeTypedList(val, 0);
    }

    /**
     * Flatten a {@link SparseArray} containing a particular object type into the parcel
     * at the current dataPosition() and growing dataCapacity() if needed. The
     * type of the objects in the array must be one that implements Parcelable.
     * Unlike the generic {@link #writeSparseArray(SparseArray)} method, however, only
     * the raw data of the objects is written and not their type, so you must use the
     * corresponding {@link #createTypedSparseArray(Parcelable.Creator)}.
     *
     * @param val The list of objects to be written.
     * @param parcelableFlags The parcelable flags to use.
     *
     * @see #createTypedSparseArray(Parcelable.Creator)
     * @see Parcelable
     */
    public final <T extends Parcelable> void writeTypedSparseArray(@Nullable SparseArray<T> val,
            int parcelableFlags) {
        if (val == null) {
            writeInt(-1);
            return;
        }
        final int count = val.size();
        writeInt(count);
        for (int i = 0; i < count; i++) {
            writeInt(val.keyAt(i));
            writeTypedObject(val.valueAt(i), parcelableFlags);
        }
    }

    /**
     * @hide
     */
    public <T extends Parcelable> void writeTypedList(@Nullable List<T> val, int parcelableFlags) {
        if (val == null) {
            writeInt(-1);
            return;
        }
        int N = val.size();
        int i=0;
        writeInt(N);
        while (i < N) {
            writeTypedObject(val.get(i), parcelableFlags);
            i++;
        }
    }

    /**
     * Flatten a List containing String objects into the parcel, at
     * the current dataPosition() and growing dataCapacity() if needed.  They
     * can later be retrieved with {@link #createStringArrayList} or
     * {@link #readStringList}.
     *
     * @param val The list of strings to be written.
     *
     * @see #createStringArrayList
     * @see #readStringList
     */
    public final void writeStringList(@Nullable List<String> val) {
        if (val == null) {
            writeInt(-1);
            return;
        }
        int N = val.size();
        int i=0;
        writeInt(N);
        while (i < N) {
            writeString(val.get(i));
            i++;
        }
    }

    /**
     * Flatten a List containing IBinder objects into the parcel, at
     * the current dataPosition() and growing dataCapacity() if needed.  They
     * can later be retrieved with {@link #createBinderArrayList} or
     * {@link #readBinderList}.
     *
     * @param val The list of strings to be written.
     *
     * @see #createBinderArrayList
     * @see #readBinderList
     */
    public final void writeBinderList(@Nullable List<IBinder> val) {
        if (val == null) {
            writeInt(-1);
            return;
        }
        int N = val.size();
        int i=0;
        writeInt(N);
        while (i < N) {
            writeStrongBinder(val.get(i));
            i++;
        }
    }

    /**
     * Flatten a {@code List} containing arbitrary {@code Parcelable} objects into this parcel
     * at the current position. They can later be retrieved using
     * {@link #readParcelableList(List, ClassLoader)} if required.
     *
     * @see #readParcelableList(List, ClassLoader)
     */
    public final <T extends Parcelable> void writeParcelableList(@Nullable List<T> val, int flags) {
        if (val == null) {
            writeInt(-1);
            return;
        }

        int N = val.size();
        int i=0;
        writeInt(N);
        while (i < N) {
            writeParcelable(val.get(i), flags);
            i++;
        }
    }

    /**
     * Flatten a homogeneous array containing a particular object type into
     * the parcel, at
     * the current dataPosition() and growing dataCapacity() if needed.  The
     * type of the objects in the array must be one that implements Parcelable.
     * Unlike the {@link #writeParcelableArray} method, however, only the
     * raw data of the objects is written and not their type, so you must use
     * {@link #readTypedArray} with the correct corresponding
     * {@link Parcelable.Creator} implementation to unmarshall them.
     *
     * @param val The array of objects to be written.
     * @param parcelableFlags Contextual flags as per
     * {@link Parcelable#writeToParcel(Parcel, int) Parcelable.writeToParcel()}.
     *
     * @see #readTypedArray
     * @see #writeParcelableArray
     * @see Parcelable.Creator
     */
    public final <T extends Parcelable> void writeTypedArray(@Nullable T[] val,
            int parcelableFlags) {
        if (val != null) {
            int N = val.length;
            writeInt(N);
            for (int i = 0; i < N; i++) {
                writeTypedObject(val[i], parcelableFlags);
            }
        } else {
            writeInt(-1);
        }
    }

    /**
     * Flatten the Parcelable object into the parcel.
     *
     * @param val The Parcelable object to be written.
     * @param parcelableFlags Contextual flags as per
     * {@link Parcelable#writeToParcel(Parcel, int) Parcelable.writeToParcel()}.
     *
     * @see #readTypedObject
     */
    public final <T extends Parcelable> void writeTypedObject(@Nullable T val,
            int parcelableFlags) {
        if (val != null) {
            writeInt(1);
            val.writeToParcel(this, parcelableFlags);
        } else {
            writeInt(0);
        }
    }

    /**
     * Flatten a generic object in to a parcel.  The given Object value may
     * currently be one of the following types:
     *
     * <ul>
     * <li> null
     * <li> String
     * <li> Byte
     * <li> Short
     * <li> Integer
     * <li> Long
     * <li> Float
     * <li> Double
     * <li> Boolean
     * <li> String[]
     * <li> boolean[]
     * <li> byte[]
     * <li> int[]
     * <li> long[]
     * <li> Object[] (supporting objects of the same type defined here).
     * <li> {@link Bundle}
     * <li> Map (as supported by {@link #writeMap}).
     * <li> Any object that implements the {@link Parcelable} protocol.
     * <li> Parcelable[]
     * <li> CharSequence (as supported by {@link TextUtils#writeToParcel}).
     * <li> List (as supported by {@link #writeList}).
     * <li> {@link SparseArray} (as supported by {@link #writeSparseArray(SparseArray)}).
     * <li> {@link IBinder}
     * <li> Any object that implements Serializable (but see
     *      {@link #writeSerializable} for caveats).  Note that all of the
     *      previous types have relatively efficient implementations for
     *      writing to a Parcel; having to rely on the generic serialization
     *      approach is much less efficient and should be avoided whenever
     *      possible.
     * </ul>
     *
     * <p class="caution">{@link Parcelable} objects are written with
     * {@link Parcelable#writeToParcel} using contextual flags of 0.  When
     * serializing objects containing {@link ParcelFileDescriptor}s,
     * this may result in file descriptor leaks when they are returned from
     * Binder calls (where {@link Parcelable#PARCELABLE_WRITE_RETURN_VALUE}
     * should be used).</p>
     */
    public final void writeValue(@Nullable Object v) {
        if (v instanceof LazyValue) {
            LazyValue value = (LazyValue) v;
            value.writeToParcel(this);
            return;
        }
        int type = getValueType(v);
        writeInt(type);
        if (isLengthPrefixed(type)) {
            // Length
            int length = dataPosition();
            writeInt(-1); // Placeholder
            // Object
            int start = dataPosition();
            writeValue(type, v);
            int end = dataPosition();
            // Backpatch length
            setDataPosition(length);
            writeInt(end - start);
            setDataPosition(end);
        } else {
            writeValue(type, v);
        }
    }

    /** @hide */
    public static int getValueType(@Nullable Object v) {
        if (v == null) {
            return VAL_NULL;
        } else if (v instanceof String) {
            return VAL_STRING;
        } else if (v instanceof Integer) {
            return VAL_INTEGER;
        } else if (v instanceof Map) {
            return VAL_MAP;
        } else if (v instanceof Bundle) {
            // Must be before Parcelable
            return VAL_BUNDLE;
        } else if (v instanceof PersistableBundle) {
            // Must be before Parcelable
            return VAL_PERSISTABLEBUNDLE;
        } else if (v instanceof SizeF) {
            // Must be before Parcelable
            return VAL_SIZEF;
        } else if (v instanceof Parcelable) {
            // IMPOTANT: cases for classes that implement Parcelable must
            // come before the Parcelable case, so that their speci fic VAL_*
            // types will be written.
            return VAL_PARCELABLE;
        } else if (v instanceof Short) {
            return VAL_SHORT;
        } else if (v instanceof Long) {
            return VAL_LONG;
        } else if (v instanceof Float) {
            return VAL_FLOAT;
        } else if (v instanceof Double) {
            return VAL_DOUBLE;
        } else if (v instanceof Boolean) {
            return VAL_BOOLEAN;
        } else if (v instanceof CharSequence) {
            // Must be after String
            return VAL_CHARSEQUENCE;
        } else if (v instanceof List) {
            return VAL_LIST;
        } else if (v instanceof SparseArray) {
            return VAL_SPARSEARRAY;
        } else if (v instanceof boolean[]) {
            return VAL_BOOLEANARRAY;
        } else if (v instanceof byte[]) {
            return VAL_BYTEARRAY;
        } else if (v instanceof String[]) {
            return VAL_STRINGARRAY;
        } else if (v instanceof CharSequence[]) {
            // Must be after String[] and before Object[]
            return VAL_CHARSEQUENCEARRAY;
        } else if (v instanceof IBinder) {
            return VAL_IBINDER;
        } else if (v instanceof Parcelable[]) {
            return VAL_PARCELABLEARRAY;
        } else if (v instanceof int[]) {
            return VAL_INTARRAY;
        } else if (v instanceof long[]) {
            return VAL_LONGARRAY;
        } else if (v instanceof Byte) {
            return VAL_BYTE;
        } else if (v instanceof Size) {
            return VAL_SIZE;
        } else if (v instanceof double[]) {
            return VAL_DOUBLEARRAY;
        } else {
            Class<?> clazz = v.getClass();
            if (clazz.isArray() && clazz.getComponentType() == Object.class) {
                // Only pure Object[] are written here, Other arrays of non-primitive types are
                // handled by serialization as this does not record the component type.
                return VAL_OBJECTARRAY;
            } else if (v instanceof Serializable) {
                // Must be last
                return VAL_SERIALIZABLE;
            } else {
                throw new IllegalArgumentException("Parcel: unknown type for value " + v);
            }
        }
    }
    /**
     * Writes value {@code v} in the parcel. This does NOT write the int representing the type
     * first.
     *
     * @hide
     */
    public void writeValue(int type, @Nullable Object v) {
        switch (type) {
            case VAL_NULL:
                break;
            case VAL_STRING:
                writeString((String) v);
                break;
            case VAL_INTEGER:
                writeInt((Integer) v);
                break;
            case VAL_MAP:
                writeMap((Map) v);
                break;
            case VAL_BUNDLE:
                writeBundle((Bundle) v);
                break;
            case VAL_PERSISTABLEBUNDLE:
                writePersistableBundle((PersistableBundle) v);
                break;
            case VAL_PARCELABLE:
                writeParcelable((Parcelable) v, 0);
                break;
            case VAL_SHORT:
                writeInt(((Short) v).intValue());
                break;
            case VAL_LONG:
                writeLong((Long) v);
                break;
            case VAL_FLOAT:
                writeFloat((Float) v);
                break;
            case VAL_DOUBLE:
                writeDouble((Double) v);
                break;
            case VAL_BOOLEAN:
                writeInt((Boolean) v ? 1 : 0);
                break;
            case VAL_CHARSEQUENCE:
                writeCharSequence((CharSequence) v);
                break;
            case VAL_LIST:
                writeList((List) v);
                break;
            case VAL_SPARSEARRAY:
                writeSparseArray((SparseArray) v);
                break;
            case VAL_BOOLEANARRAY:
                writeBooleanArray((boolean[]) v);
                break;
            case VAL_BYTEARRAY:
                writeByteArray((byte[]) v);
                break;
            case VAL_STRINGARRAY:
                writeStringArray((String[]) v);
                break;
            case VAL_CHARSEQUENCEARRAY:
                writeCharSequenceArray((CharSequence[]) v);
                break;
            case VAL_IBINDER:
                writeStrongBinder((IBinder) v);
                break;
            case VAL_PARCELABLEARRAY:
                writeParcelableArray((Parcelable[]) v, 0);
                break;
            case VAL_INTARRAY:
                writeIntArray((int[]) v);
                break;
            case VAL_LONGARRAY:
                writeLongArray((long[]) v);
                break;
            case VAL_BYTE:
                writeInt((Byte) v);
                break;
            case VAL_SIZE:
                writeSize((Size) v);
                break;
            case VAL_SIZEF:
                writeSizeF((SizeF) v);
                break;
            case VAL_DOUBLEARRAY:
                writeDoubleArray((double[]) v);
                break;
            case VAL_OBJECTARRAY:
                writeArray((Object[]) v);
                break;
            case VAL_SERIALIZABLE:
                writeSerializable((Serializable) v);
                break;
            default:
                throw new RuntimeException("Parcel: unable to marshal value " + v);
        }
    }

    /**
     * Flatten the name of the class of the Parcelable and its contents
     * into the parcel.
     *
     * @param p The Parcelable object to be written.
     * @param parcelableFlags Contextual flags as per
     * {@link Parcelable#writeToParcel(Parcel, int) Parcelable.writeToParcel()}.
     */
    public final void writeParcelable(@Nullable Parcelable p, int parcelableFlags) {
        if (p == null) {
            writeString(null);
            return;
        }
        writeParcelableCreator(p);
        p.writeToParcel(this, parcelableFlags);
    }

    /**
     * Flatten the name of the class of the Parcelable into this Parcel.
     *
     * @param p The Parcelable object to be written.
     * @see #readParcelableCreator
     */
    public final void writeParcelableCreator(@NonNull Parcelable p) {
        String name = p.getClass().getName();
        writeString(name);
    }

    /**
     * A map used by {@link #maybeWriteSquashed} to keep track of what parcelables have
     * been seen, and what positions they were written. The value is the absolute position of
     * each parcelable.
     */
    private ArrayMap<Parcelable, Integer> mWrittenSquashableParcelables;

    private void ensureWrittenSquashableParcelables() {
        if (mWrittenSquashableParcelables != null) {
            return;
        }
        mWrittenSquashableParcelables = new ArrayMap<>();
    }

    private boolean mAllowSquashing = false;

    /**
     * Allow "squashing" writes in {@link #maybeWriteSquashed}. This allows subsequent calls to
     * {@link #maybeWriteSquashed(Parcelable)} to "squash" the same instances into one in a Parcel.
     *
     * Typically, this method is called at the beginning of {@link Parcelable#writeToParcel}. The
     * caller must retain the return value from this method and call {@link #restoreAllowSquashing}
     * with it.
     *
     * See {@link #maybeWriteSquashed(Parcelable)} for the details.
     *
     * @see #restoreAllowSquashing(boolean)
     * @see #maybeWriteSquashed(Parcelable)
     * @see #readSquashed(SquashReadHelper)
     *
     * @hide
     */
    @TestApi
    public boolean allowSquashing() {
        boolean previous = mAllowSquashing;
        mAllowSquashing = true;
        return previous;
    }

    /**
     * @see #allowSquashing()
     * @hide
     */
    @TestApi
    public void restoreAllowSquashing(boolean previous) {
        mAllowSquashing = previous;
        if (!mAllowSquashing) {
            mWrittenSquashableParcelables = null;
        }
    }

    private void resetSqaushingState() {
        if (mAllowSquashing) {
            Slog.wtf(TAG, "allowSquashing wasn't restored.");
        }
        mWrittenSquashableParcelables = null;
        mReadSquashableParcelables = null;
        mAllowSquashing = false;
    }

    /**
     * A map used by {@link #readSquashed} to cache parcelables. It's a map from
     * an absolute position in a Parcel to the parcelable stored at the position.
     */
    private ArrayMap<Integer, Parcelable> mReadSquashableParcelables;

    private void ensureReadSquashableParcelables() {
        if (mReadSquashableParcelables != null) {
            return;
        }
        mReadSquashableParcelables = new ArrayMap<>();
    }

    /**
     * Write a parcelable with "squash" -- that is, when the same instance is written to the
     * same Parcelable multiple times, instead of writing the entire instance multiple times,
     * only write it once, and in subsequent writes we'll only write the offset to the original
     * object.
     *
     * This approach does not work of the resulting Parcel is copied with {@link #appendFrom} with
     * a non-zero offset, so we do not enable this behavior by default. Instead, we only enable
     * it between {@link #allowSquashing} and {@link #restoreAllowSquashing}, in order to make sure
     * we only do so within each "top level" Parcelable.
     *
     * Usage: Use this method in {@link Parcelable#writeToParcel}.
     * If this method returns TRUE, it's a subsequent call, and the offset is already written,
     * so the caller doesn't have to do anything. If this method returns FALSE, it's the first
     * time for the instance to be written to this parcel. The caller has to proceed with its
     * {@link Parcelable#writeToParcel}.
     *
     * (See {@code ApplicationInfo} for the example.)
     *
     * @param p the target Parcelable to write.
     *
     * @see #allowSquashing()
     * @see #restoreAllowSquashing(boolean)
     * @see #readSquashed(SquashReadHelper)
     *
     * @hide
     */
    public boolean maybeWriteSquashed(@NonNull Parcelable p) {
        if (!mAllowSquashing) {
            // Don't squash, and don't put it in the map either.
            writeInt(0);
            return false;
        }
        ensureWrittenSquashableParcelables();
        final Integer firstPos = mWrittenSquashableParcelables.get(p);
        if (firstPos != null) {
            // Already written.
            // Write the relative offset from the current position to the first position.
            final int pos = dataPosition();

            // We want the offset from the next byte of this integer, so we need to +4.
            writeInt(pos - firstPos + 4);
            return true;
        }
        // First time seen, write a marker.
        writeInt(0);

        // Remember the position.
        final int pos = dataPosition();
        mWrittenSquashableParcelables.put(p, pos);

        // Return false and let the caller actually write the content.
        return false;
    }

    /**
     * Helper function that's used by {@link #readSquashed(SquashReadHelper)}
     * @hide
     */
    public interface SquashReadHelper<T> {
        /** Read and instantiate {@code T} from a Parcel. */
        @NonNull
        T readRawParceled(@NonNull Parcel p);
    }

    /**
     * Read a {@link Parcelable} that's written with {@link #maybeWriteSquashed}.
     *
     * @param reader a callback function that instantiates an instance from a parcel.
     * Typicallly, a lambda to the instructor that takes a {@link Parcel} is passed.
     *
     * @see #maybeWriteSquashed(Parcelable)
     *
     * @hide
     */
    @SuppressWarnings("unchecked")
    @Nullable
    public <T extends Parcelable> T readSquashed(SquashReadHelper<T> reader) {
        final int offset = readInt();
        final int pos = dataPosition();

        if (offset == 0) {
            // First time read. Unparcel, and remember it.
            final T p = reader.readRawParceled(this);
            ensureReadSquashableParcelables();
            mReadSquashableParcelables.put(pos, p);
            return p;
        }
        // Subsequent read.
        final int firstAbsolutePos = pos - offset;

        final Parcelable p = mReadSquashableParcelables.get(firstAbsolutePos);
        if (p == null) {
            Slog.wtfStack(TAG, "Map doesn't contain offset "
                    + firstAbsolutePos
                    + " : contains=" + new ArrayList<>(mReadSquashableParcelables.keySet()));
        }
        return (T) p;
    }

    /**
     * Write a generic serializable object in to a Parcel.  It is strongly
     * recommended that this method be avoided, since the serialization
     * overhead is extremely large, and this approach will be much slower than
     * using the other approaches to writing data in to a Parcel.
     */
    public final void writeSerializable(@Nullable Serializable s) {
        if (s == null) {
            writeString(null);
            return;
        }
        String name = s.getClass().getName();
        writeString(name);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            ObjectOutputStream oos = new ObjectOutputStream(baos);
            oos.writeObject(s);
            oos.close();

            writeByteArray(baos.toByteArray());
        } catch (IOException ioe) {
            throw new RuntimeException("Parcelable encountered " +
                "IOException writing serializable object (name = " + name +
                ")", ioe);
        }
    }

    /** @hide For debugging purposes */
    public static void setStackTraceParceling(boolean enabled) {
        sParcelExceptionStackTrace = enabled;
    }

    /**
     * Special function for writing an exception result at the header of
     * a parcel, to be used when returning an exception from a transaction.
     * Note that this currently only supports a few exception types; any other
     * exception will be re-thrown by this function as a RuntimeException
     * (to be caught by the system's last-resort exception handling when
     * dispatching a transaction).
     *
     * <p>The supported exception types are:
     * <ul>
     * <li>{@link BadParcelableException}
     * <li>{@link IllegalArgumentException}
     * <li>{@link IllegalStateException}
     * <li>{@link NullPointerException}
     * <li>{@link SecurityException}
     * <li>{@link UnsupportedOperationException}
     * <li>{@link NetworkOnMainThreadException}
     * </ul>
     *
     * @param e The Exception to be written.
     *
     * @see #writeNoException
     * @see #readException
     */
    public final void writeException(@NonNull Exception e) {
        AppOpsManager.prefixParcelWithAppOpsIfNeeded(this);

        int code = getExceptionCode(e);
        writeInt(code);
        StrictMode.clearGatheredViolations();
        if (code == 0) {
            if (e instanceof RuntimeException) {
                throw (RuntimeException) e;
            }
            throw new RuntimeException(e);
        }
        writeString(e.getMessage());
        final long timeNow = sParcelExceptionStackTrace ? SystemClock.elapsedRealtime() : 0;
        if (sParcelExceptionStackTrace && (timeNow - sLastWriteExceptionStackTrace
                > WRITE_EXCEPTION_STACK_TRACE_THRESHOLD_MS)) {
            sLastWriteExceptionStackTrace = timeNow;
            writeStackTrace(e);
        } else {
            writeInt(0);
        }
        switch (code) {
            case EX_SERVICE_SPECIFIC:
                writeInt(((ServiceSpecificException) e).errorCode);
                break;
            case EX_PARCELABLE:
                // Write parceled exception prefixed by length
                final int sizePosition = dataPosition();
                writeInt(0);
                writeParcelable((Parcelable) e, Parcelable.PARCELABLE_WRITE_RETURN_VALUE);
                final int payloadPosition = dataPosition();
                setDataPosition(sizePosition);
                writeInt(payloadPosition - sizePosition);
                setDataPosition(payloadPosition);
                break;
        }
    }

    /** @hide */
    public static int getExceptionCode(@NonNull Throwable e) {
        int code = 0;
        if (e instanceof Parcelable
                && (e.getClass().getClassLoader() == Parcelable.class.getClassLoader())) {
            // We only send Parcelable exceptions that are in the
            // BootClassLoader to ensure that the receiver can unpack them
            code = EX_PARCELABLE;
        } else if (e instanceof SecurityException) {
            code = EX_SECURITY;
        } else if (e instanceof BadParcelableException) {
            code = EX_BAD_PARCELABLE;
        } else if (e instanceof IllegalArgumentException) {
            code = EX_ILLEGAL_ARGUMENT;
        } else if (e instanceof NullPointerException) {
            code = EX_NULL_POINTER;
        } else if (e instanceof IllegalStateException) {
            code = EX_ILLEGAL_STATE;
        } else if (e instanceof NetworkOnMainThreadException) {
            code = EX_NETWORK_MAIN_THREAD;
        } else if (e instanceof UnsupportedOperationException) {
            code = EX_UNSUPPORTED_OPERATION;
        } else if (e instanceof ServiceSpecificException) {
            code = EX_SERVICE_SPECIFIC;
        }
        return code;
    }

    /** @hide */
    public void writeStackTrace(@NonNull Throwable e) {
        final int sizePosition = dataPosition();
        writeInt(0); // Header size will be filled in later
        StackTraceElement[] stackTrace = e.getStackTrace();
        final int truncatedSize = Math.min(stackTrace.length, 5);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < truncatedSize; i++) {
            sb.append("\tat ").append(stackTrace[i]).append('\n');
        }
        writeString(sb.toString());
        final int payloadPosition = dataPosition();
        setDataPosition(sizePosition);
        // Write stack trace header size. Used in native side to skip the header
        writeInt(payloadPosition - sizePosition);
        setDataPosition(payloadPosition);
    }

    /**
     * Special function for writing information at the front of the Parcel
     * indicating that no exception occurred.
     *
     * @see #writeException
     * @see #readException
     */
    public final void writeNoException() {
        AppOpsManager.prefixParcelWithAppOpsIfNeeded(this);

        // Despite the name of this function ("write no exception"),
        // it should instead be thought of as "write the RPC response
        // header", but because this function name is written out by
        // the AIDL compiler, we're not going to rename it.
        //
        // The response header, in the non-exception case (see also
        // writeException above, also called by the AIDL compiler), is
        // either a 0 (the default case), or EX_HAS_STRICTMODE_REPLY_HEADER if
        // StrictMode has gathered up violations that have occurred
        // during a Binder call, in which case we write out the number
        // of violations and their details, serialized, before the
        // actual RPC respons data.  The receiving end of this is
        // readException(), below.
        if (StrictMode.hasGatheredViolations()) {
            writeInt(EX_HAS_STRICTMODE_REPLY_HEADER);
            final int sizePosition = dataPosition();
            writeInt(0);  // total size of fat header, to be filled in later
            StrictMode.writeGatheredViolationsToParcel(this);
            final int payloadPosition = dataPosition();
            setDataPosition(sizePosition);
            writeInt(payloadPosition - sizePosition);  // header size
            setDataPosition(payloadPosition);
        } else {
            writeInt(0);
        }
    }

    /**
     * Special function for reading an exception result from the header of
     * a parcel, to be used after receiving the result of a transaction.  This
     * will throw the exception for you if it had been written to the Parcel,
     * otherwise return and let you read the normal result data from the Parcel.
     *
     * @see #writeException
     * @see #writeNoException
     */
    public final void readException() {
        int code = readExceptionCode();
        if (code != 0) {
            String msg = readString();
            readException(code, msg);
        }
    }

    /**
     * Parses the header of a Binder call's response Parcel and
     * returns the exception code.  Deals with lite or fat headers.
     * In the common successful case, this header is generally zero.
     * In less common cases, it's a small negative number and will be
     * followed by an error string.
     *
     * This exists purely for android.database.DatabaseUtils and
     * insulating it from having to handle fat headers as returned by
     * e.g. StrictMode-induced RPC responses.
     *
     * @hide
     */
    @UnsupportedAppUsage
    @TestApi
    public final int readExceptionCode() {
        int code = readInt();
        if (code == EX_HAS_NOTED_APPOPS_REPLY_HEADER) {
            AppOpsManager.readAndLogNotedAppops(this);
            // Read next header or real exception if there is no more header
            code = readInt();
        }

        if (code == EX_HAS_STRICTMODE_REPLY_HEADER) {
            int headerSize = readInt();
            if (headerSize == 0) {
                Log.e(TAG, "Unexpected zero-sized Parcel reply header.");
            } else {
                // Currently the only thing in the header is StrictMode stacks,
                // but discussions around event/RPC tracing suggest we might
                // put that here too.  If so, switch on sub-header tags here.
                // But for now, just parse out the StrictMode stuff.
                StrictMode.readAndHandleBinderCallViolations(this);
            }
            // And fat response headers are currently only used when
            // there are no exceptions, so return no error:
            return 0;
        }
        return code;
    }

    /**
     * Throw an exception with the given message. Not intended for use
     * outside the Parcel class.
     *
     * @param code Used to determine which exception class to throw.
     * @param msg The exception message.
     */
    public final void readException(int code, String msg) {
        String remoteStackTrace = null;
        final int remoteStackPayloadSize = readInt();
        if (remoteStackPayloadSize > 0) {
            remoteStackTrace = readString();
        }
        Exception e = createException(code, msg);
        // Attach remote stack trace if availalble
        if (remoteStackTrace != null) {
            RemoteException cause = new RemoteException(
                    "Remote stack trace:\n" + remoteStackTrace, null, false, false);
            ExceptionUtils.appendCause(e, cause);
        }
        SneakyThrow.sneakyThrow(e);
    }

    /**
     * Creates an exception with the given message.
     *
     * @param code Used to determine which exception class to throw.
     * @param msg The exception message.
     */
    private Exception createException(int code, String msg) {
        Exception exception = createExceptionOrNull(code, msg);
        return exception != null
                ? exception
                : new RuntimeException("Unknown exception code: " + code + " msg " + msg);
    }

    /** @hide */
    public Exception createExceptionOrNull(int code, String msg) {
        switch (code) {
            case EX_PARCELABLE:
                if (readInt() > 0) {
                    return (Exception) readParcelable(Parcelable.class.getClassLoader());
                } else {
                    return new RuntimeException(msg + " [missing Parcelable]");
                }
            case EX_SECURITY:
                return new SecurityException(msg);
            case EX_BAD_PARCELABLE:
                return new BadParcelableException(msg);
            case EX_ILLEGAL_ARGUMENT:
                return new IllegalArgumentException(msg);
            case EX_NULL_POINTER:
                return new NullPointerException(msg);
            case EX_ILLEGAL_STATE:
                return new IllegalStateException(msg);
            case EX_NETWORK_MAIN_THREAD:
                return new NetworkOnMainThreadException();
            case EX_UNSUPPORTED_OPERATION:
                return new UnsupportedOperationException(msg);
            case EX_SERVICE_SPECIFIC:
                return new ServiceSpecificException(readInt(), msg);
            default:
                return null;
        }
    }

    /**
     * Read an integer value from the parcel at the current dataPosition().
     */
    public final int readInt() {
        return nativeReadInt(mNativePtr);
    }

    /**
     * Read a long integer value from the parcel at the current dataPosition().
     */
    public final long readLong() {
        return nativeReadLong(mNativePtr);
    }

    /**
     * Read a floating point value from the parcel at the current
     * dataPosition().
     */
    public final float readFloat() {
        return nativeReadFloat(mNativePtr);
    }

    /**
     * Read a double precision floating point value from the parcel at the
     * current dataPosition().
     */
    public final double readDouble() {
        return nativeReadDouble(mNativePtr);
    }

    /**
     * Read a string value from the parcel at the current dataPosition().
     */
    @Nullable
    public final String readString() {
        return readString16();
    }

    /** {@hide} */
    public final @Nullable String readString8() {
        return mReadWriteHelper.readString8(this);
    }

    /** {@hide} */
    public final @Nullable String readString16() {
        return mReadWriteHelper.readString16(this);
    }

    /**
     * Read a string without going though a {@link ReadWriteHelper}.  Subclasses of
     * {@link ReadWriteHelper} must use this method instead of {@link #readString} to avoid
     * infinity recursive calls.
     *
     * @hide
     */
    public @Nullable String readStringNoHelper() {
        return readString16NoHelper();
    }

    /** {@hide} */
    public @Nullable String readString8NoHelper() {
        return nativeReadString8(mNativePtr);
    }

    /** {@hide} */
    public @Nullable String readString16NoHelper() {
        return nativeReadString16(mNativePtr);
    }

    /**
     * Read a boolean value from the parcel at the current dataPosition().
     */
    public final boolean readBoolean() {
        return readInt() != 0;
    }

    /**
     * Read a CharSequence value from the parcel at the current dataPosition().
     * @hide
     */
    @UnsupportedAppUsage
    @Nullable
    public final CharSequence readCharSequence() {
        return TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(this);
    }

    /**
     * Read an object from the parcel at the current dataPosition().
     */
    public final IBinder readStrongBinder() {
        return nativeReadStrongBinder(mNativePtr);
    }

    /**
     * Read a FileDescriptor from the parcel at the current dataPosition().
     */
    public final ParcelFileDescriptor readFileDescriptor() {
        FileDescriptor fd = nativeReadFileDescriptor(mNativePtr);
        return fd != null ? new ParcelFileDescriptor(fd) : null;
    }

    /** {@hide} */
    @UnsupportedAppUsage
    public final FileDescriptor readRawFileDescriptor() {
        return nativeReadFileDescriptor(mNativePtr);
    }

    /**
     * {@hide}
     * Read and return a new array of FileDescriptors from the parcel.
     * @return the FileDescriptor array, or null if the array is null.
     **/
    @Nullable
    public final FileDescriptor[] createRawFileDescriptorArray() {
        int N = readInt();
        if (N < 0) {
            return null;
        }
        FileDescriptor[] f = new FileDescriptor[N];
        for (int i = 0; i < N; i++) {
            f[i] = readRawFileDescriptor();
        }
        return f;
    }

    /**
     * {@hide}
     * Read an array of FileDescriptors from a parcel.
     * The passed array must be exactly the length of the array in the parcel.
     * @return the FileDescriptor array, or null if the array is null.
     **/
    public final void readRawFileDescriptorArray(FileDescriptor[] val) {
        int N = readInt();
        if (N == val.length) {
            for (int i=0; i<N; i++) {
                val[i] = readRawFileDescriptor();
            }
        } else {
            throw new RuntimeException("bad array lengths");
        }
    }

    /**
     * Read a byte value from the parcel at the current dataPosition().
     */
    public final byte readByte() {
        return (byte)(readInt() & 0xff);
    }

    /**
     * Please use {@link #readBundle(ClassLoader)} instead (whose data must have
     * been written with {@link #writeBundle}.  Read into an existing Map object
     * from the parcel at the current dataPosition().
     */
    public final void readMap(@NonNull Map outVal, @Nullable ClassLoader loader) {
        int N = readInt();
        readMapInternal(outVal, N, loader);
    }

    /**
     * Read into an existing List object from the parcel at the current
     * dataPosition(), using the given class loader to load any enclosed
     * Parcelables.  If it is null, the default class loader is used.
     */
    public final void readList(@NonNull List outVal, @Nullable ClassLoader loader) {
        int N = readInt();
        readListInternal(outVal, N, loader);
    }

    /**
     * Please use {@link #readBundle(ClassLoader)} instead (whose data must have
     * been written with {@link #writeBundle}.  Read and return a new HashMap
     * object from the parcel at the current dataPosition(), using the given
     * class loader to load any enclosed Parcelables.  Returns null if
     * the previously written map object was null.
     */
    @Nullable
    public final HashMap readHashMap(@Nullable ClassLoader loader)
    {
        int N = readInt();
        if (N < 0) {
            return null;
        }
        HashMap m = new HashMap(N);
        readMapInternal(m, N, loader);
        return m;
    }

    /**
     * Read and return a new Bundle object from the parcel at the current
     * dataPosition().  Returns null if the previously written Bundle object was
     * null.
     */
    @Nullable
    public final Bundle readBundle() {
        return readBundle(null);
    }

    /**
     * Read and return a new Bundle object from the parcel at the current
     * dataPosition(), using the given class loader to initialize the class
     * loader of the Bundle for later retrieval of Parcelable objects.
     * Returns null if the previously written Bundle object was null.
     */
    @Nullable
    public final Bundle readBundle(@Nullable ClassLoader loader) {
        int length = readInt();
        if (length < 0) {
            if (Bundle.DEBUG) Log.d(TAG, "null bundle: length=" + length);
            return null;
        }

        final Bundle bundle = new Bundle(this, length);
        if (loader != null) {
            bundle.setClassLoader(loader);
        }
        return bundle;
    }

    /**
     * Read and return a new Bundle object from the parcel at the current
     * dataPosition().  Returns null if the previously written Bundle object was
     * null.
     */
    @Nullable
    public final PersistableBundle readPersistableBundle() {
        return readPersistableBundle(null);
    }

    /**
     * Read and return a new Bundle object from the parcel at the current
     * dataPosition(), using the given class loader to initialize the class
     * loader of the Bundle for later retrieval of Parcelable objects.
     * Returns null if the previously written Bundle object was null.
     */
    @Nullable
    public final PersistableBundle readPersistableBundle(@Nullable ClassLoader loader) {
        int length = readInt();
        if (length < 0) {
            if (Bundle.DEBUG) Log.d(TAG, "null bundle: length=" + length);
            return null;
        }

        final PersistableBundle bundle = new PersistableBundle(this, length);
        if (loader != null) {
            bundle.setClassLoader(loader);
        }
        return bundle;
    }

    /**
     * Read a Size from the parcel at the current dataPosition().
     */
    @NonNull
    public final Size readSize() {
        final int width = readInt();
        final int height = readInt();
        return new Size(width, height);
    }

    /**
     * Read a SizeF from the parcel at the current dataPosition().
     */
    @NonNull
    public final SizeF readSizeF() {
        final float width = readFloat();
        final float height = readFloat();
        return new SizeF(width, height);
    }

    /**
     * Read and return a byte[] object from the parcel.
     */
    @Nullable
    public final byte[] createByteArray() {
        return nativeCreateByteArray(mNativePtr);
    }

    /**
     * Read a byte[] object from the parcel and copy it into the
     * given byte array.
     */
    public final void readByteArray(@NonNull byte[] val) {
        boolean valid = nativeReadByteArray(mNativePtr, val, (val != null) ? val.length : 0);
        if (!valid) {
            throw new RuntimeException("bad array lengths");
        }
    }

    /**
     * Read a blob of data from the parcel and return it as a byte array.
     * {@hide}
     * {@SystemApi}
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    @Nullable
    public final byte[] readBlob() {
        return nativeReadBlob(mNativePtr);
    }

    /**
     * Read and return a String[] object from the parcel.
     * {@hide}
     */
    @UnsupportedAppUsage
    @Nullable
    public final String[] readStringArray() {
        return createString16Array();
    }

    /**
     * Read and return a CharSequence[] object from the parcel.
     * {@hide}
     */
    @Nullable
    public final CharSequence[] readCharSequenceArray() {
        CharSequence[] array = null;

        int length = readInt();
        if (length >= 0)
        {
            array = new CharSequence[length];

            for (int i = 0 ; i < length ; i++)
            {
                array[i] = readCharSequence();
            }
        }

        return array;
    }

    /**
     * Read and return an ArrayList&lt;CharSequence&gt; object from the parcel.
     * {@hide}
     */
    @Nullable
    public final ArrayList<CharSequence> readCharSequenceList() {
        ArrayList<CharSequence> array = null;

        int length = readInt();
        if (length >= 0) {
            array = new ArrayList<CharSequence>(length);

            for (int i = 0 ; i < length ; i++) {
                array.add(readCharSequence());
            }
        }

        return array;
    }

    /**
     * Read and return a new ArrayList object from the parcel at the current
     * dataPosition().  Returns null if the previously written list object was
     * null.  The given class loader will be used to load any enclosed
     * Parcelables.
     */
    @Nullable
    public final ArrayList readArrayList(@Nullable ClassLoader loader) {
        int N = readInt();
        if (N < 0) {
            return null;
        }
        ArrayList l = new ArrayList(N);
        readListInternal(l, N, loader);
        return l;
    }

    /**
     * Read and return a new Object array from the parcel at the current
     * dataPosition().  Returns null if the previously written array was
     * null.  The given class loader will be used to load any enclosed
     * Parcelables.
     */
    @Nullable
    public final Object[] readArray(@Nullable ClassLoader loader) {
        int N = readInt();
        if (N < 0) {
            return null;
        }
        Object[] l = new Object[N];
        readArrayInternal(l, N, loader);
        return l;
    }

    /**
     * Read and return a new SparseArray object from the parcel at the current
     * dataPosition().  Returns null if the previously written list object was
     * null.  The given class loader will be used to load any enclosed
     * Parcelables.
     */
    @Nullable
    public final <T> SparseArray<T> readSparseArray(@Nullable ClassLoader loader) {
        int N = readInt();
        if (N < 0) {
            return null;
        }
        SparseArray sa = new SparseArray(N);
        readSparseArrayInternal(sa, N, loader);
        return sa;
    }

    /**
     * Read and return a new SparseBooleanArray object from the parcel at the current
     * dataPosition().  Returns null if the previously written list object was
     * null.
     */
    @Nullable
    public final SparseBooleanArray readSparseBooleanArray() {
        int N = readInt();
        if (N < 0) {
            return null;
        }
        SparseBooleanArray sa = new SparseBooleanArray(N);
        readSparseBooleanArrayInternal(sa, N);
        return sa;
    }

    /**
     * Read and return a new SparseIntArray object from the parcel at the current
     * dataPosition(). Returns null if the previously written array object was null.
     * @hide
     */
    @Nullable
    public final SparseIntArray readSparseIntArray() {
        int N = readInt();
        if (N < 0) {
            return null;
        }
        SparseIntArray sa = new SparseIntArray(N);
        readSparseIntArrayInternal(sa, N);
        return sa;
    }

    /**
     * Read and return a new ArrayList containing a particular object type from
     * the parcel that was written with {@link #writeTypedList} at the
     * current dataPosition().  Returns null if the
     * previously written list object was null.  The list <em>must</em> have
     * previously been written via {@link #writeTypedList} with the same object
     * type.
     *
     * @return A newly created ArrayList containing objects with the same data
     *         as those that were previously written.
     *
     * @see #writeTypedList
     */
    @Nullable
    public final <T> ArrayList<T> createTypedArrayList(@NonNull Parcelable.Creator<T> c) {
        int N = readInt();
        if (N < 0) {
            return null;
        }
        ArrayList<T> l = new ArrayList<T>(N);
        while (N > 0) {
            l.add(readTypedObject(c));
            N--;
        }
        return l;
    }

    /**
     * Read into the given List items containing a particular object type
     * that were written with {@link #writeTypedList} at the
     * current dataPosition().  The list <em>must</em> have
     * previously been written via {@link #writeTypedList} with the same object
     * type.
     *
     * @return A newly created ArrayList containing objects with the same data
     *         as those that were previously written.
     *
     * @see #writeTypedList
     */
    public final <T> void readTypedList(@NonNull List<T> list, @NonNull Parcelable.Creator<T> c) {
        int M = list.size();
        int N = readInt();
        int i = 0;
        for (; i < M && i < N; i++) {
            list.set(i, readTypedObject(c));
        }
        for (; i<N; i++) {
            list.add(readTypedObject(c));
        }
        for (; i<M; i++) {
            list.remove(N);
        }
    }

    /**
     * Read into a new {@link SparseArray} items containing a particular object type
     * that were written with {@link #writeTypedSparseArray(SparseArray, int)} at the
     * current dataPosition().  The list <em>must</em> have previously been written
     * via {@link #writeTypedSparseArray(SparseArray, int)} with the same object type.
     *
     * @param creator The creator to use when for instantiation.
     *
     * @return A newly created {@link SparseArray} containing objects with the same data
     *         as those that were previously written.
     *
     * @see #writeTypedSparseArray(SparseArray, int)
     */
    public final @Nullable <T extends Parcelable> SparseArray<T> createTypedSparseArray(
            @NonNull Parcelable.Creator<T> creator) {
        final int count = readInt();
        if (count < 0) {
            return null;
        }
        final SparseArray<T> array = new SparseArray<>(count);
        for (int i = 0; i < count; i++) {
            final int index = readInt();
            final T value = readTypedObject(creator);
            array.append(index, value);
        }
        return array;
    }

    /**
     * Read into a new {@link ArrayMap} with string keys items containing a particular
     * object type that were written with {@link #writeTypedArrayMap(ArrayMap, int)} at the
     * current dataPosition().  The list <em>must</em> have previously been written
     * via {@link #writeTypedArrayMap(ArrayMap, int)} with the same object type.
     *
     * @param creator The creator to use when for instantiation.
     *
     * @return A newly created {@link ArrayMap} containing objects with the same data
     *         as those that were previously written.
     *
     * @see #writeTypedArrayMap(ArrayMap, int)
     */
    public final @Nullable <T extends Parcelable> ArrayMap<String, T> createTypedArrayMap(
            @NonNull Parcelable.Creator<T> creator) {
        final int count = readInt();
        if (count < 0) {
            return null;
        }
        final ArrayMap<String, T> map = new ArrayMap<>(count);
        for (int i = 0; i < count; i++) {
            final String key = readString();
            final T value = readTypedObject(creator);
            map.append(key, value);
        }
        return map;
    }

    /**
     * Read and return a new ArrayList containing String objects from
     * the parcel that was written with {@link #writeStringList} at the
     * current dataPosition().  Returns null if the
     * previously written list object was null.
     *
     * @return A newly created ArrayList containing strings with the same data
     *         as those that were previously written.
     *
     * @see #writeStringList
     */
    @Nullable
    public final ArrayList<String> createStringArrayList() {
        int N = readInt();
        if (N < 0) {
            return null;
        }
        ArrayList<String> l = new ArrayList<String>(N);
        while (N > 0) {
            l.add(readString());
            N--;
        }
        return l;
    }

    /**
     * Read and return a new ArrayList containing IBinder objects from
     * the parcel that was written with {@link #writeBinderList} at the
     * current dataPosition().  Returns null if the
     * previously written list object was null.
     *
     * @return A newly created ArrayList containing strings with the same data
     *         as those that were previously written.
     *
     * @see #writeBinderList
     */
    @Nullable
    public final ArrayList<IBinder> createBinderArrayList() {
        int N = readInt();
        if (N < 0) {
            return null;
        }
        ArrayList<IBinder> l = new ArrayList<IBinder>(N);
        while (N > 0) {
            l.add(readStrongBinder());
            N--;
        }
        return l;
    }

    /**
     * Read into the given List items String objects that were written with
     * {@link #writeStringList} at the current dataPosition().
     *
     * @see #writeStringList
     */
    public final void readStringList(@NonNull List<String> list) {
        int M = list.size();
        int N = readInt();
        int i = 0;
        for (; i < M && i < N; i++) {
            list.set(i, readString());
        }
        for (; i<N; i++) {
            list.add(readString());
        }
        for (; i<M; i++) {
            list.remove(N);
        }
    }

    /**
     * Read into the given List items IBinder objects that were written with
     * {@link #writeBinderList} at the current dataPosition().
     *
     * @see #writeBinderList
     */
    public final void readBinderList(@NonNull List<IBinder> list) {
        int M = list.size();
        int N = readInt();
        int i = 0;
        for (; i < M && i < N; i++) {
            list.set(i, readStrongBinder());
        }
        for (; i<N; i++) {
            list.add(readStrongBinder());
        }
        for (; i<M; i++) {
            list.remove(N);
        }
    }

    /**
     * Read the list of {@code Parcelable} objects at the current data position into the
     * given {@code list}. The contents of the {@code list} are replaced. If the serialized
     * list was {@code null}, {@code list} is cleared.
     *
     * @see #writeParcelableList(List, int)
     */
    @NonNull
    public final <T extends Parcelable> List<T> readParcelableList(@NonNull List<T> list,
            @Nullable ClassLoader cl) {
        final int N = readInt();
        if (N == -1) {
            list.clear();
            return list;
        }

        final int M = list.size();
        int i = 0;
        for (; i < M && i < N; i++) {
            list.set(i, (T) readParcelable(cl));
        }
        for (; i<N; i++) {
            list.add((T) readParcelable(cl));
        }
        for (; i<M; i++) {
            list.remove(N);
        }
        return list;
    }

    /**
     * Read and return a new array containing a particular object type from
     * the parcel at the current dataPosition().  Returns null if the
     * previously written array was null.  The array <em>must</em> have
     * previously been written via {@link #writeTypedArray} with the same
     * object type.
     *
     * @return A newly created array containing objects with the same data
     *         as those that were previously written.
     *
     * @see #writeTypedArray
     */
    @Nullable
    public final <T> T[] createTypedArray(@NonNull Parcelable.Creator<T> c) {
        int N = readInt();
        if (N < 0) {
            return null;
        }
        T[] l = c.newArray(N);
        for (int i=0; i<N; i++) {
            l[i] = readTypedObject(c);
        }
        return l;
    }

    public final <T> void readTypedArray(@NonNull T[] val, @NonNull Parcelable.Creator<T> c) {
        int N = readInt();
        if (N == val.length) {
            for (int i=0; i<N; i++) {
                val[i] = readTypedObject(c);
            }
        } else {
            throw new RuntimeException("bad array lengths");
        }
    }

    /**
     * @deprecated
     * @hide
     */
    @Deprecated
    public final <T> T[] readTypedArray(Parcelable.Creator<T> c) {
        return createTypedArray(c);
    }

    /**
     * Read and return a typed Parcelable object from a parcel.
     * Returns null if the previous written object was null.
     * The object <em>must</em> have previous been written via
     * {@link #writeTypedObject} with the same object type.
     *
     * @return A newly created object of the type that was previously
     *         written.
     *
     * @see #writeTypedObject
     */
    @Nullable
    public final <T> T readTypedObject(@NonNull Parcelable.Creator<T> c) {
        if (readInt() != 0) {
            return c.createFromParcel(this);
        } else {
            return null;
        }
    }

    /**
     * Write a heterogeneous array of Parcelable objects into the Parcel.
     * Each object in the array is written along with its class name, so
     * that the correct class can later be instantiated.  As a result, this
     * has significantly more overhead than {@link #writeTypedArray}, but will
     * correctly handle an array containing more than one type of object.
     *
     * @param value The array of objects to be written.
     * @param parcelableFlags Contextual flags as per
     * {@link Parcelable#writeToParcel(Parcel, int) Parcelable.writeToParcel()}.
     *
     * @see #writeTypedArray
     */
    public final <T extends Parcelable> void writeParcelableArray(@Nullable T[] value,
            int parcelableFlags) {
        if (value != null) {
            int N = value.length;
            writeInt(N);
            for (int i=0; i<N; i++) {
                writeParcelable(value[i], parcelableFlags);
            }
        } else {
            writeInt(-1);
        }
    }

    /**
     * Read a typed object from a parcel.  The given class loader will be
     * used to load any enclosed Parcelables.  If it is null, the default class
     * loader will be used.
     */
    @Nullable
    public final Object readValue(@Nullable ClassLoader loader) {
        int type = readInt();
        final Object object;
        if (isLengthPrefixed(type)) {
            int length = readInt();
            int start = dataPosition();
            object = readValue(type, loader);
            int actual = dataPosition() - start;
            if (actual != length) {
                Log.w(TAG,
                        "Unparcelling of " + object + " of type " + Parcel.valueTypeToString(type)
                                + "  consumed " + actual + " bytes, but " + length + " expected.");
            }
        } else {
            object = readValue(type, loader);
        }
        return object;
    }

    /**
     * This will return a {@link Supplier} for length-prefixed types that deserializes the object
     * when {@link Supplier#get()} is called, for other types it will return the object itself.
     *
     * <p>After calling {@link Supplier#get()} the parcel cursor will not change. Note that you
     * shouldn't recycle the parcel, not at least until all objects have been retrieved. No
     * synchronization attempts are made.
     *
     * </p>The supplier returned implements {@link #equals(Object)} and {@link #hashCode()}. Two
     * suppliers are equal if either of the following is true:
     * <ul>
     *   <li>{@link Supplier#get()} has been called on both and both objects returned are equal.
     *   <li>{@link Supplier#get()} hasn't been called on either one and everything below is true:
     *   <ul>
     *       <li>The {@code loader} parameters used to retrieve each are equal.
     *       <li>They both have the same type.
     *       <li>They have the same payload length.
     *       <li>Their binary content is the same.
     *   </ul>
     * </ul>
     *
     * @hide
     */
    @Nullable
    public Object readLazyValue(@Nullable ClassLoader loader) {
        int start = dataPosition();
        int type = readInt();
        if (isLengthPrefixed(type)) {
            int length = readInt();
            setDataPosition(MathUtils.addOrThrow(dataPosition(), length));
            return new LazyValue(this, start, length, type, loader);
        } else {
            return readValue(type, loader);
        }
    }

    private static final class LazyValue implements Supplier<Object> {
        private final int mPosition;
        private final int mLength;
        private final int mType;
        @Nullable private final ClassLoader mLoader;
        @Nullable private Parcel mSource;
        @Nullable private Object mObject;
        @Nullable private Parcel mValueParcel;

        LazyValue(Parcel source, int position, int length, int type, @Nullable ClassLoader loader) {
            mSource = source;
            mPosition = position;
            mLength = length;
            mType = type;
            mLoader = loader;
        }

        @Override
        public Object get() {
            if (mObject == null) {
                int restore = mSource.dataPosition();
                try {
                    mSource.setDataPosition(mPosition);
                    mObject = mSource.readValue(mLoader);
                } finally {
                    mSource.setDataPosition(restore);
                }
                mSource = null;
                if (mValueParcel != null) {
                    mValueParcel.recycle();
                    mValueParcel = null;
                }
            }
            return mObject;
        }

        public void writeToParcel(Parcel out) {
            if (mObject == null) {
                out.appendFrom(mSource, mPosition, mLength + 8);
            } else {
                out.writeValue(mObject);
            }
        }

        public boolean hasFileDescriptors() {
            return getValueParcel().hasFileDescriptors();
        }

        @Override
        public String toString() {
            return mObject == null
                    ? "Supplier{" + valueTypeToString(mType) + "@" + mPosition + "+" + mLength + '}'
                    : "Supplier{" + mObject + "}";
        }

        /**
         * We're checking if the *lazy value* is equal to another one, not if the *object*
         * represented by the lazy value is equal to the other one. So, if there are two lazy values
         * and one of them has been deserialized but the other hasn't this will always return false.
         */
        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof LazyValue)) {
                return false;
            }
            LazyValue value = (LazyValue) other;
            // Check if they are either both serialized or both deserialized
            if ((mObject == null) != (value.mObject == null)) {
                return false;
            }
            // If both are deserialized, compare the live objects
            if (mObject != null) {
                return mObject.equals(value.mObject);
            }
            // Better safely fail here since this could mean we get different objects
            if (!Objects.equals(mLoader, value.mLoader)) {
                return false;
            }
            // Otherwise compare metadata prior to comparing payload
            if (mType != value.mType || mLength != value.mLength) {
                return false;
            }
            // Finally we compare the payload
            return getValueParcel().compareData(value.getValueParcel()) == 0;
        }

        @Override
        public int hashCode() {
            return Objects.hash(mObject, mLoader, mType, mLength);
        }

        /** This extracts the parcel section responsible for the object and returns it. */
        private Parcel getValueParcel() {
            if (mValueParcel == null) {
                mValueParcel = Parcel.obtain();
                // mLength is the length of object representation, excluding the type and length.
                // mPosition is the position of the entire value container, right before the type.
                // So, we add 4 bytes for the type + 4 bytes for the length written.
                mValueParcel.appendFrom(mSource, mPosition, mLength + 8);
            }
            return mValueParcel;
        }
    }

    /**
     * Reads a value from the parcel of type {@code type}. Does NOT read the int representing the
     * type first.
     */
    @Nullable
    private Object readValue(int type, @Nullable ClassLoader loader) {
        switch (type) {
        case VAL_NULL:
            return null;

        case VAL_STRING:
            return readString();

        case VAL_INTEGER:
            return readInt();

        case VAL_MAP:
            return readHashMap(loader);

        case VAL_PARCELABLE:
            return readParcelable(loader);

        case VAL_SHORT:
            return (short) readInt();

        case VAL_LONG:
            return readLong();

        case VAL_FLOAT:
            return readFloat();

        case VAL_DOUBLE:
            return readDouble();

        case VAL_BOOLEAN:
            return readInt() == 1;

        case VAL_CHARSEQUENCE:
            return readCharSequence();

        case VAL_LIST:
            return readArrayList(loader);

        case VAL_BOOLEANARRAY:
            return createBooleanArray();

        case VAL_BYTEARRAY:
            return createByteArray();

        case VAL_STRINGARRAY:
            return readStringArray();

        case VAL_CHARSEQUENCEARRAY:
            return readCharSequenceArray();

        case VAL_IBINDER:
            return readStrongBinder();

        case VAL_OBJECTARRAY:
            return readArray(loader);

        case VAL_INTARRAY:
            return createIntArray();

        case VAL_LONGARRAY:
            return createLongArray();

        case VAL_BYTE:
            return readByte();

        case VAL_SERIALIZABLE:
            return readSerializable(loader);

        case VAL_PARCELABLEARRAY:
            return readParcelableArray(loader);

        case VAL_SPARSEARRAY:
            return readSparseArray(loader);

        case VAL_SPARSEBOOLEANARRAY:
            return readSparseBooleanArray();

        case VAL_BUNDLE:
            return readBundle(loader); // loading will be deferred

        case VAL_PERSISTABLEBUNDLE:
            return readPersistableBundle(loader);

        case VAL_SIZE:
            return readSize();

        case VAL_SIZEF:
            return readSizeF();

        case VAL_DOUBLEARRAY:
            return createDoubleArray();

        default:
            int off = dataPosition() - 4;
            throw new RuntimeException(
                "Parcel " + this + ": Unmarshalling unknown type code " + type + " at offset " + off);
        }
    }

    private boolean isLengthPrefixed(int type) {
        switch (type) {
            case VAL_PARCELABLE:
            case VAL_PARCELABLEARRAY:
            case VAL_LIST:
            case VAL_SPARSEARRAY:
            case VAL_BUNDLE:
            case VAL_SERIALIZABLE:
                return true;
            default:
                return false;
        }
    }

    /**
     * Read and return a new Parcelable from the parcel.  The given class loader
     * will be used to load any enclosed Parcelables.  If it is null, the default
     * class loader will be used.
     * @param loader A ClassLoader from which to instantiate the Parcelable
     * object, or null for the default class loader.
     * @return Returns the newly created Parcelable, or null if a null
     * object has been written.
     * @throws BadParcelableException Throws BadParcelableException if there
     * was an error trying to instantiate the Parcelable.
     */
    @SuppressWarnings("unchecked")
    @Nullable
    public final <T extends Parcelable> T readParcelable(@Nullable ClassLoader loader) {
        Parcelable.Creator<?> creator = readParcelableCreator(loader);
        if (creator == null) {
            return null;
        }
        if (creator instanceof Parcelable.ClassLoaderCreator<?>) {
          Parcelable.ClassLoaderCreator<?> classLoaderCreator =
              (Parcelable.ClassLoaderCreator<?>) creator;
          return (T) classLoaderCreator.createFromParcel(this, loader);
        }
        return (T) creator.createFromParcel(this);
    }

    /** @hide */
    @UnsupportedAppUsage
    @SuppressWarnings("unchecked")
    @Nullable
    public final <T extends Parcelable> T readCreator(@NonNull Parcelable.Creator<?> creator,
            @Nullable ClassLoader loader) {
        if (creator instanceof Parcelable.ClassLoaderCreator<?>) {
          Parcelable.ClassLoaderCreator<?> classLoaderCreator =
              (Parcelable.ClassLoaderCreator<?>) creator;
          return (T) classLoaderCreator.createFromParcel(this, loader);
        }
        return (T) creator.createFromParcel(this);
    }

    /**
     * Read and return a Parcelable.Creator from the parcel. The given class loader will be used to
     * load the {@link Parcelable.Creator}. If it is null, the default class loader will be used.
     *
     * @param loader A ClassLoader from which to instantiate the {@link Parcelable.Creator}
     * object, or null for the default class loader.
     * @return the previously written {@link Parcelable.Creator}, or null if a null Creator was
     * written.
     * @throws BadParcelableException Throws BadParcelableException if there was an error trying to
     * read the {@link Parcelable.Creator}.
     *
     * @see #writeParcelableCreator
     */
    @Nullable
    public final Parcelable.Creator<?> readParcelableCreator(@Nullable ClassLoader loader) {
        String name = readString();
        if (name == null) {
            return null;
        }
        Parcelable.Creator<?> creator;
        HashMap<String, Parcelable.Creator<?>> map;
        synchronized (mCreators) {
            map = mCreators.get(loader);
            if (map == null) {
                map = new HashMap<>();
                mCreators.put(loader, map);
            }
            creator = map.get(name);
        }
        if (creator != null) {
            return creator;
        }

        try {
            // If loader == null, explicitly emulate Class.forName(String) "caller
            // classloader" behavior.
            ClassLoader parcelableClassLoader =
                    (loader == null ? getClass().getClassLoader() : loader);
            // Avoid initializing the Parcelable class until we know it implements
            // Parcelable and has the necessary CREATOR field. http://b/1171613.
            Class<?> parcelableClass = Class.forName(name, false /* initialize */,
                    parcelableClassLoader);
            if (!Parcelable.class.isAssignableFrom(parcelableClass)) {
                throw new BadParcelableException("Parcelable protocol requires subclassing "
                        + "from Parcelable on class " + name);
            }
            Field f = parcelableClass.getField("CREATOR");
            if ((f.getModifiers() & Modifier.STATIC) == 0) {
                throw new BadParcelableException("Parcelable protocol requires "
                        + "the CREATOR object to be static on class " + name);
            }
            Class<?> creatorType = f.getType();
            if (!Parcelable.Creator.class.isAssignableFrom(creatorType)) {
                // Fail before calling Field.get(), not after, to avoid initializing
                // parcelableClass unnecessarily.
                throw new BadParcelableException("Parcelable protocol requires a "
                        + "Parcelable.Creator object called "
                        + "CREATOR on class " + name);
            }
            creator = (Parcelable.Creator<?>) f.get(null);
        } catch (IllegalAccessException e) {
            Log.e(TAG, "Illegal access when unmarshalling: " + name, e);
            throw new BadParcelableException(
                    "IllegalAccessException when unmarshalling: " + name, e);
        } catch (ClassNotFoundException e) {
            Log.e(TAG, "Class not found when unmarshalling: " + name, e);
            throw new BadParcelableException(
                    "ClassNotFoundException when unmarshalling: " + name, e);
        } catch (NoSuchFieldException e) {
            throw new BadParcelableException("Parcelable protocol requires a "
                    + "Parcelable.Creator object called "
                    + "CREATOR on class " + name, e);
        }
        if (creator == null) {
            throw new BadParcelableException("Parcelable protocol requires a "
                    + "non-null Parcelable.Creator object called "
                    + "CREATOR on class " + name);
        }

        synchronized (mCreators) {
            map.put(name, creator);
        }

        return creator;
    }

    /**
     * Read and return a new Parcelable array from the parcel.
     * The given class loader will be used to load any enclosed
     * Parcelables.
     * @return the Parcelable array, or null if the array is null
     */
    @Nullable
    public final Parcelable[] readParcelableArray(@Nullable ClassLoader loader) {
        int N = readInt();
        if (N < 0) {
            return null;
        }
        Parcelable[] p = new Parcelable[N];
        for (int i = 0; i < N; i++) {
            p[i] = readParcelable(loader);
        }
        return p;
    }

    /** @hide */
    @Nullable
    public final <T extends Parcelable> T[] readParcelableArray(@Nullable ClassLoader loader,
            @NonNull Class<T> clazz) {
        int N = readInt();
        if (N < 0) {
            return null;
        }
        T[] p = (T[]) Array.newInstance(clazz, N);
        for (int i = 0; i < N; i++) {
            p[i] = readParcelable(loader);
        }
        return p;
    }

    /**
     * Read and return a new Serializable object from the parcel.
     * @return the Serializable object, or null if the Serializable name
     * wasn't found in the parcel.
     */
    @Nullable
    public final Serializable readSerializable() {
        return readSerializable(null);
    }

    @Nullable
    private final Serializable readSerializable(@Nullable final ClassLoader loader) {
        String name = readString();
        if (name == null) {
            // For some reason we were unable to read the name of the Serializable (either there
            // is nothing left in the Parcel to read, or the next value wasn't a String), so
            // return null, which indicates that the name wasn't found in the parcel.
            return null;
        }

        byte[] serializedData = createByteArray();
        ByteArrayInputStream bais = new ByteArrayInputStream(serializedData);
        try {
            ObjectInputStream ois = new ObjectInputStream(bais) {
                @Override
                protected Class<?> resolveClass(ObjectStreamClass osClass)
                        throws IOException, ClassNotFoundException {
                    // try the custom classloader if provided
                    if (loader != null) {
                        Class<?> c = Class.forName(osClass.getName(), false, loader);
                        if (c != null) {
                            return c;
                        }
                    }
                    return super.resolveClass(osClass);
                }
            };
            return (Serializable) ois.readObject();
        } catch (IOException ioe) {
            throw new RuntimeException("Parcelable encountered " +
                "IOException reading a Serializable object (name = " + name +
                ")", ioe);
        } catch (ClassNotFoundException cnfe) {
            throw new RuntimeException("Parcelable encountered " +
                "ClassNotFoundException reading a Serializable object (name = "
                + name + ")", cnfe);
        }
    }

    // Cache of previously looked up CREATOR.createFromParcel() methods for
    // particular classes.  Keys are the names of the classes, values are
    // Method objects.
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P)
    private static final HashMap<ClassLoader,HashMap<String,Parcelable.Creator<?>>>
        mCreators = new HashMap<>();

    /** @hide for internal use only. */
    static protected final Parcel obtain(int obj) {
        throw new UnsupportedOperationException();
    }

    /** @hide */
    static protected final Parcel obtain(long obj) {
        final Parcel[] pool = sHolderPool;
        synchronized (pool) {
            Parcel p;
            for (int i=0; i<POOL_SIZE; i++) {
                p = pool[i];
                if (p != null) {
                    pool[i] = null;
                    if (DEBUG_RECYCLE) {
                        p.mStack = new RuntimeException();
                    }
                    p.init(obj);
                    return p;
                }
            }
        }
        return new Parcel(obj);
    }

    private Parcel(long nativePtr) {
        if (DEBUG_RECYCLE) {
            mStack = new RuntimeException();
        }
        //Log.i(TAG, "Initializing obj=0x" + Integer.toHexString(obj), mStack);
        init(nativePtr);
    }

    private void init(long nativePtr) {
        if (nativePtr != 0) {
            mNativePtr = nativePtr;
            mOwnsNativeParcelObject = false;
        } else {
            mNativePtr = nativeCreate();
            mOwnsNativeParcelObject = true;
        }
    }

    private void freeBuffer() {
        resetSqaushingState();
        if (mOwnsNativeParcelObject) {
            updateNativeSize(nativeFreeBuffer(mNativePtr));
        }
        mReadWriteHelper = ReadWriteHelper.DEFAULT;
    }

    private void destroy() {
        resetSqaushingState();
        if (mNativePtr != 0) {
            if (mOwnsNativeParcelObject) {
                nativeDestroy(mNativePtr);
                updateNativeSize(0);
            }
            mNativePtr = 0;
        }
    }

    @Override
    protected void finalize() throws Throwable {
        if (DEBUG_RECYCLE) {
            if (mStack != null) {
                Log.w(TAG, "Client did not call Parcel.recycle()", mStack);
            }
        }
        destroy();
    }

    /* package */ void readMapInternal(@NonNull Map outVal, int N,
            @Nullable ClassLoader loader) {
        while (N > 0) {
            Object key = readValue(loader);
            Object value = readValue(loader);
            outVal.put(key, value);
            N--;
        }
    }

    /* package */ void readArrayMapInternal(@NonNull ArrayMap<? super String, Object> outVal,
            int size, @Nullable ClassLoader loader) {
        readArrayMap(outVal, size, /* sorted */ true, /* lazy */ false, loader);
    }

    /**
     * Reads a map into {@code map}.
     *
     * @param sorted Whether the keys are sorted by their hashes, if so we use an optimized path.
     * @param lazy   Whether to populate the map with lazy {@link Supplier} objects for
     *               length-prefixed values. See {@link Parcel#readLazyValue(ClassLoader)} for more
     *               details.
     * @return whether the parcel can be recycled or not.
     * @hide
     */
    boolean readArrayMap(ArrayMap<? super String, Object> map, int size, boolean sorted,
            boolean lazy, @Nullable ClassLoader loader) {
        boolean recycle = true;
        while (size > 0) {
            String key = readString();
            Object value = (lazy) ? readLazyValue(loader) : readValue(loader);
            if (value instanceof LazyValue) {
                recycle = false;
            }
            if (sorted) {
                map.append(key, value);
            } else {
                map.put(key, value);
            }
            size--;
        }
        if (sorted) {
            map.validate();
        }
        return recycle;
    }

    /**
     * @hide For testing only.
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public void readArrayMap(@NonNull ArrayMap<? super String, Object> outVal,
            @Nullable ClassLoader loader) {
        final int N = readInt();
        if (N < 0) {
            return;
        }
        readArrayMapInternal(outVal, N, loader);
    }

    /**
     * Reads an array set.
     *
     * @param loader The class loader to use.
     *
     * @hide
     */
    @UnsupportedAppUsage
    public @Nullable ArraySet<? extends Object> readArraySet(@Nullable ClassLoader loader) {
        final int size = readInt();
        if (size < 0) {
            return null;
        }
        ArraySet<Object> result = new ArraySet<>(size);
        for (int i = 0; i < size; i++) {
            Object value = readValue(loader);
            result.append(value);
        }
        return result;
    }

    private void readListInternal(@NonNull List outVal, int N,
            @Nullable ClassLoader loader) {
        while (N > 0) {
            Object value = readValue(loader);
            //Log.d(TAG, "Unmarshalling value=" + value);
            outVal.add(value);
            N--;
        }
    }

    private void readArrayInternal(@NonNull Object[] outVal, int N,
            @Nullable ClassLoader loader) {
        for (int i = 0; i < N; i++) {
            Object value = readValue(loader);
            //Log.d(TAG, "Unmarshalling value=" + value);
            outVal[i] = value;
        }
    }

    private void readSparseArrayInternal(@NonNull SparseArray outVal, int N,
            @Nullable ClassLoader loader) {
        while (N > 0) {
            int key = readInt();
            Object value = readValue(loader);
            //Log.i(TAG, "Unmarshalling key=" + key + " value=" + value);
            outVal.append(key, value);
            N--;
        }
    }


    private void readSparseBooleanArrayInternal(@NonNull SparseBooleanArray outVal, int N) {
        while (N > 0) {
            int key = readInt();
            boolean value = this.readByte() == 1;
            //Log.i(TAG, "Unmarshalling key=" + key + " value=" + value);
            outVal.append(key, value);
            N--;
        }
    }

    private void readSparseIntArrayInternal(@NonNull SparseIntArray outVal, int N) {
        while (N > 0) {
            int key = readInt();
            int value = readInt();
            outVal.append(key, value);
            N--;
        }
    }

    /**
     * @hide For testing
     */
    public long getBlobAshmemSize() {
        return nativeGetBlobAshmemSize(mNativePtr);
    }

    private static String valueTypeToString(int type) {
        switch (type) {
            case VAL_NULL: return "VAL_NULL";
            case VAL_INTEGER: return "VAL_INTEGER";
            case VAL_MAP: return "VAL_MAP";
            case VAL_BUNDLE: return "VAL_BUNDLE";
            case VAL_PERSISTABLEBUNDLE: return "VAL_PERSISTABLEBUNDLE";
            case VAL_PARCELABLE: return "VAL_PARCELABLE";
            case VAL_SHORT: return "VAL_SHORT";
            case VAL_LONG: return "VAL_LONG";
            case VAL_FLOAT: return "VAL_FLOAT";
            case VAL_DOUBLE: return "VAL_DOUBLE";
            case VAL_BOOLEAN: return "VAL_BOOLEAN";
            case VAL_CHARSEQUENCE: return "VAL_CHARSEQUENCE";
            case VAL_LIST: return "VAL_LIST";
            case VAL_SPARSEARRAY: return "VAL_SPARSEARRAY";
            case VAL_BOOLEANARRAY: return "VAL_BOOLEANARRAY";
            case VAL_BYTEARRAY: return "VAL_BYTEARRAY";
            case VAL_STRINGARRAY: return "VAL_STRINGARRAY";
            case VAL_CHARSEQUENCEARRAY: return "VAL_CHARSEQUENCEARRAY";
            case VAL_IBINDER: return "VAL_IBINDER";
            case VAL_PARCELABLEARRAY: return "VAL_PARCELABLEARRAY";
            case VAL_INTARRAY: return "VAL_INTARRAY";
            case VAL_LONGARRAY: return "VAL_LONGARRAY";
            case VAL_BYTE: return "VAL_BYTE";
            case VAL_SIZE: return "VAL_SIZE";
            case VAL_SIZEF: return "VAL_SIZEF";
            case VAL_DOUBLEARRAY: return "VAL_DOUBLEARRAY";
            case VAL_OBJECTARRAY: return "VAL_OBJECTARRAY";
            case VAL_SERIALIZABLE: return "VAL_SERIALIZABLE";
            default: return "UNKNOWN(" + type + ")";
        }
    }
}
