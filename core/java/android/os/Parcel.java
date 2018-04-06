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

import android.annotation.Nullable;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.ExceptionUtils;
import android.util.Log;
import android.util.Size;
import android.util.SizeF;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.util.SparseIntArray;

import dalvik.annotation.optimization.CriticalNative;
import dalvik.annotation.optimization.FastNative;
import dalvik.system.VMRuntime;

import libcore.util.SneakyThrow;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileDescriptor;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.ObjectStreamClass;
import java.io.Serializable;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    private static final int EX_HAS_REPLY_HEADER = -128;  // special; see below
    // EX_TRANSACTION_FAILED is used exclusively in native code.
    // see libbinder's binder/Status.h
    private static final int EX_TRANSACTION_FAILED = -129;

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
    static native void nativeWriteString(long nativePtr, String val);
    private static native void nativeWriteStrongBinder(long nativePtr, IBinder val);
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
    static native String nativeReadString(long nativePtr);
    private static native IBinder nativeReadStrongBinder(long nativePtr);
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
        public static final ReadWriteHelper DEFAULT = new ReadWriteHelper();

        /**
         * Called when writing a string to a parcel. Subclasses wanting to write a string
         * must use {@link #writeStringNoHelper(String)} to avoid
         * infinity recursive calls.
         */
        public void writeString(Parcel p, String s) {
            nativeWriteString(p.mNativePtr, s);
        }

        /**
         * Called when reading a string to a parcel. Subclasses wanting to read a string
         * must use {@link #readStringNoHelper()} to avoid
         * infinity recursive calls.
         */
        public String readString(Parcel p) {
            return nativeReadString(p.mNativePtr);
        }
    }

    private ReadWriteHelper mReadWriteHelper = ReadWriteHelper.DEFAULT;

    /**
     * Retrieve a new Parcel object from the pool.
     */
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
    public void setReadWriteHelper(ReadWriteHelper helper) {
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
    public static native long getGlobalAllocSize();

    /** @hide */
    public static native long getGlobalAllocCount();

    /**
     * Returns the total amount of data contained in the parcel.
     */
    public final int dataSize() {
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
    public final void unmarshall(byte[] data, int offset, int length) {
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
     * Store or read an IBinder interface token in the parcel at the current
     * {@link #dataPosition}.  This is used to validate that the marshalled
     * transaction is intended for the target interface.
     */
    public final void writeInterfaceToken(String interfaceName) {
        nativeWriteInterfaceToken(mNativePtr, interfaceName);
    }

    public final void enforceInterface(String interfaceName) {
        nativeEnforceInterface(mNativePtr, interfaceName);
    }

    /**
     * Write a byte array into the parcel at the current {@link #dataPosition},
     * growing {@link #dataCapacity} if needed.
     * @param b Bytes to place into the parcel.
     */
    public final void writeByteArray(byte[] b) {
        writeByteArray(b, 0, (b != null) ? b.length : 0);
    }

    /**
     * Write a byte array into the parcel at the current {@link #dataPosition},
     * growing {@link #dataCapacity} if needed.
     * @param b Bytes to place into the parcel.
     * @param offset Index of first byte to be written.
     * @param len Number of bytes to write.
     */
    public final void writeByteArray(byte[] b, int offset, int len) {
        if (b == null) {
            writeInt(-1);
            return;
        }
        Arrays.checkOffsetAndCount(b.length, offset, len);
        nativeWriteByteArray(mNativePtr, b, offset, len);
    }

    /**
     * Write a blob of data into the parcel at the current {@link #dataPosition},
     * growing {@link #dataCapacity} if needed.
     * @param b Bytes to place into the parcel.
     * {@hide}
     * {@SystemApi}
     */
    public final void writeBlob(byte[] b) {
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
    public final void writeBlob(byte[] b, int offset, int len) {
        if (b == null) {
            writeInt(-1);
            return;
        }
        Arrays.checkOffsetAndCount(b.length, offset, len);
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
    public final void writeString(String val) {
        mReadWriteHelper.writeString(this, val);
    }

    /**
     * Write a string without going though a {@link ReadWriteHelper}.  Subclasses of
     * {@link ReadWriteHelper} must use this method instead of {@link #writeString} to avoid
     * infinity recursive calls.
     *
     * @hide
     */
    public void writeStringNoHelper(String val) {
        nativeWriteString(mNativePtr, val);
    }

    /** @hide */
    public final void writeBoolean(boolean val) {
        writeInt(val ? 1 : 0);
    }

    /**
     * Write a CharSequence value into the parcel at the current dataPosition(),
     * growing dataCapacity() if needed.
     * @hide
     */
    public final void writeCharSequence(CharSequence val) {
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
    public final void writeFileDescriptor(FileDescriptor val) {
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
    public final void writeRawFileDescriptor(FileDescriptor val) {
        nativeWriteFileDescriptor(mNativePtr, val);
    }

    /**
     * {@hide}
     * Write an array of FileDescriptor objects into the Parcel.
     *
     * @param value The array of objects to be written.
     */
    public final void writeRawFileDescriptorArray(FileDescriptor[] value) {
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
    public final void writeMap(Map val) {
        writeMapInternal((Map<String, Object>) val);
    }

    /**
     * Flatten a Map into the parcel at the current dataPosition(),
     * growing dataCapacity() if needed.  The Map keys must be String objects.
     */
    /* package */ void writeMapInternal(Map<String,Object> val) {
        if (val == null) {
            writeInt(-1);
            return;
        }
        Set<Map.Entry<String,Object>> entries = val.entrySet();
        writeInt(entries.size());
        for (Map.Entry<String,Object> e : entries) {
            writeValue(e.getKey());
            writeValue(e.getValue());
        }
    }

    /**
     * Flatten an ArrayMap into the parcel at the current dataPosition(),
     * growing dataCapacity() if needed.  The Map keys must be String objects.
     */
    /* package */ void writeArrayMapInternal(ArrayMap<String, Object> val) {
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
    public void writeArrayMap(ArrayMap<String, Object> val) {
        writeArrayMapInternal(val);
    }

    /**
     * Write an array set to the parcel.
     *
     * @param val The array set to write.
     *
     * @hide
     */
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
    public final void writeBundle(Bundle val) {
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
    public final void writePersistableBundle(PersistableBundle val) {
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
    public final void writeSize(Size val) {
        writeInt(val.getWidth());
        writeInt(val.getHeight());
    }

    /**
     * Flatten a SizeF into the parcel at the current dataPosition(),
     * growing dataCapacity() if needed.
     */
    public final void writeSizeF(SizeF val) {
        writeFloat(val.getWidth());
        writeFloat(val.getHeight());
    }

    /**
     * Flatten a List into the parcel at the current dataPosition(), growing
     * dataCapacity() if needed.  The List values are written using
     * {@link #writeValue} and must follow the specification there.
     */
    public final void writeList(List val) {
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
    public final void writeArray(Object[] val) {
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
    public final void writeSparseArray(SparseArray<Object> val) {
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

    public final void writeSparseBooleanArray(SparseBooleanArray val) {
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
    public final void writeSparseIntArray(SparseIntArray val) {
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

    public final void writeBooleanArray(boolean[] val) {
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

    public final void readBooleanArray(boolean[] val) {
        int N = readInt();
        if (N == val.length) {
            for (int i=0; i<N; i++) {
                val[i] = readInt() != 0;
            }
        } else {
            throw new RuntimeException("bad array lengths");
        }
    }

    public final void writeCharArray(char[] val) {
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

    public final void readCharArray(char[] val) {
        int N = readInt();
        if (N == val.length) {
            for (int i=0; i<N; i++) {
                val[i] = (char)readInt();
            }
        } else {
            throw new RuntimeException("bad array lengths");
        }
    }

    public final void writeIntArray(int[] val) {
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

    public final void readIntArray(int[] val) {
        int N = readInt();
        if (N == val.length) {
            for (int i=0; i<N; i++) {
                val[i] = readInt();
            }
        } else {
            throw new RuntimeException("bad array lengths");
        }
    }

    public final void writeLongArray(long[] val) {
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

    public final void readLongArray(long[] val) {
        int N = readInt();
        if (N == val.length) {
            for (int i=0; i<N; i++) {
                val[i] = readLong();
            }
        } else {
            throw new RuntimeException("bad array lengths");
        }
    }

    public final void writeFloatArray(float[] val) {
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

    public final void readFloatArray(float[] val) {
        int N = readInt();
        if (N == val.length) {
            for (int i=0; i<N; i++) {
                val[i] = readFloat();
            }
        } else {
            throw new RuntimeException("bad array lengths");
        }
    }

    public final void writeDoubleArray(double[] val) {
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

    public final void readDoubleArray(double[] val) {
        int N = readInt();
        if (N == val.length) {
            for (int i=0; i<N; i++) {
                val[i] = readDouble();
            }
        } else {
            throw new RuntimeException("bad array lengths");
        }
    }

    public final void writeStringArray(String[] val) {
        if (val != null) {
            int N = val.length;
            writeInt(N);
            for (int i=0; i<N; i++) {
                writeString(val[i]);
            }
        } else {
            writeInt(-1);
        }
    }

    public final String[] createStringArray() {
        int N = readInt();
        if (N >= 0) {
            String[] val = new String[N];
            for (int i=0; i<N; i++) {
                val[i] = readString();
            }
            return val;
        } else {
            return null;
        }
    }

    public final void readStringArray(String[] val) {
        int N = readInt();
        if (N == val.length) {
            for (int i=0; i<N; i++) {
                val[i] = readString();
            }
        } else {
            throw new RuntimeException("bad array lengths");
        }
    }

    public final void writeBinderArray(IBinder[] val) {
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
    public final void writeCharSequenceArray(CharSequence[] val) {
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
    public final void writeCharSequenceList(ArrayList<CharSequence> val) {
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

    public final void readBinderArray(IBinder[] val) {
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
    public final <T extends Parcelable> void writeTypedList(List<T> val) {
        writeTypedList(val, 0);
    }

    /**
     * @hide
     */
    public <T extends Parcelable> void writeTypedList(List<T> val, int parcelableFlags) {
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
    public final void writeStringList(List<String> val) {
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
    public final void writeBinderList(List<IBinder> val) {
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
     * @hide
     */
    public final <T extends Parcelable> void writeParcelableList(List<T> val, int flags) {
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
    public final <T extends Parcelable> void writeTypedArray(T[] val,
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
    public final <T extends Parcelable> void writeTypedObject(T val, int parcelableFlags) {
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
    public final void writeValue(Object v) {
        if (v == null) {
            writeInt(VAL_NULL);
        } else if (v instanceof String) {
            writeInt(VAL_STRING);
            writeString((String) v);
        } else if (v instanceof Integer) {
            writeInt(VAL_INTEGER);
            writeInt((Integer) v);
        } else if (v instanceof Map) {
            writeInt(VAL_MAP);
            writeMap((Map) v);
        } else if (v instanceof Bundle) {
            // Must be before Parcelable
            writeInt(VAL_BUNDLE);
            writeBundle((Bundle) v);
        } else if (v instanceof PersistableBundle) {
            writeInt(VAL_PERSISTABLEBUNDLE);
            writePersistableBundle((PersistableBundle) v);
        } else if (v instanceof Parcelable) {
            // IMPOTANT: cases for classes that implement Parcelable must
            // come before the Parcelable case, so that their specific VAL_*
            // types will be written.
            writeInt(VAL_PARCELABLE);
            writeParcelable((Parcelable) v, 0);
        } else if (v instanceof Short) {
            writeInt(VAL_SHORT);
            writeInt(((Short) v).intValue());
        } else if (v instanceof Long) {
            writeInt(VAL_LONG);
            writeLong((Long) v);
        } else if (v instanceof Float) {
            writeInt(VAL_FLOAT);
            writeFloat((Float) v);
        } else if (v instanceof Double) {
            writeInt(VAL_DOUBLE);
            writeDouble((Double) v);
        } else if (v instanceof Boolean) {
            writeInt(VAL_BOOLEAN);
            writeInt((Boolean) v ? 1 : 0);
        } else if (v instanceof CharSequence) {
            // Must be after String
            writeInt(VAL_CHARSEQUENCE);
            writeCharSequence((CharSequence) v);
        } else if (v instanceof List) {
            writeInt(VAL_LIST);
            writeList((List) v);
        } else if (v instanceof SparseArray) {
            writeInt(VAL_SPARSEARRAY);
            writeSparseArray((SparseArray) v);
        } else if (v instanceof boolean[]) {
            writeInt(VAL_BOOLEANARRAY);
            writeBooleanArray((boolean[]) v);
        } else if (v instanceof byte[]) {
            writeInt(VAL_BYTEARRAY);
            writeByteArray((byte[]) v);
        } else if (v instanceof String[]) {
            writeInt(VAL_STRINGARRAY);
            writeStringArray((String[]) v);
        } else if (v instanceof CharSequence[]) {
            // Must be after String[] and before Object[]
            writeInt(VAL_CHARSEQUENCEARRAY);
            writeCharSequenceArray((CharSequence[]) v);
        } else if (v instanceof IBinder) {
            writeInt(VAL_IBINDER);
            writeStrongBinder((IBinder) v);
        } else if (v instanceof Parcelable[]) {
            writeInt(VAL_PARCELABLEARRAY);
            writeParcelableArray((Parcelable[]) v, 0);
        } else if (v instanceof int[]) {
            writeInt(VAL_INTARRAY);
            writeIntArray((int[]) v);
        } else if (v instanceof long[]) {
            writeInt(VAL_LONGARRAY);
            writeLongArray((long[]) v);
        } else if (v instanceof Byte) {
            writeInt(VAL_BYTE);
            writeInt((Byte) v);
        } else if (v instanceof Size) {
            writeInt(VAL_SIZE);
            writeSize((Size) v);
        } else if (v instanceof SizeF) {
            writeInt(VAL_SIZEF);
            writeSizeF((SizeF) v);
        } else if (v instanceof double[]) {
            writeInt(VAL_DOUBLEARRAY);
            writeDoubleArray((double[]) v);
        } else {
            Class<?> clazz = v.getClass();
            if (clazz.isArray() && clazz.getComponentType() == Object.class) {
                // Only pure Object[] are written here, Other arrays of non-primitive types are
                // handled by serialization as this does not record the component type.
                writeInt(VAL_OBJECTARRAY);
                writeArray((Object[]) v);
            } else if (v instanceof Serializable) {
                // Must be last
                writeInt(VAL_SERIALIZABLE);
                writeSerializable((Serializable) v);
            } else {
                throw new RuntimeException("Parcel: unable to marshal value " + v);
            }
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
    public final void writeParcelable(Parcelable p, int parcelableFlags) {
        if (p == null) {
            writeString(null);
            return;
        }
        writeParcelableCreator(p);
        p.writeToParcel(this, parcelableFlags);
    }

    /** @hide */
    public final void writeParcelableCreator(Parcelable p) {
        String name = p.getClass().getName();
        writeString(name);
    }

    /**
     * Write a generic serializable object in to a Parcel.  It is strongly
     * recommended that this method be avoided, since the serialization
     * overhead is extremely large, and this approach will be much slower than
     * using the other approaches to writing data in to a Parcel.
     */
    public final void writeSerializable(Serializable s) {
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
    public final void writeException(Exception e) {
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

    /**
     * Special function for writing information at the front of the Parcel
     * indicating that no exception occurred.
     *
     * @see #writeException
     * @see #readException
     */
    public final void writeNoException() {
        // Despite the name of this function ("write no exception"),
        // it should instead be thought of as "write the RPC response
        // header", but because this function name is written out by
        // the AIDL compiler, we're not going to rename it.
        //
        // The response header, in the non-exception case (see also
        // writeException above, also called by the AIDL compiler), is
        // either a 0 (the default case), or EX_HAS_REPLY_HEADER if
        // StrictMode has gathered up violations that have occurred
        // during a Binder call, in which case we write out the number
        // of violations and their details, serialized, before the
        // actual RPC respons data.  The receiving end of this is
        // readException(), below.
        if (StrictMode.hasGatheredViolations()) {
            writeInt(EX_HAS_REPLY_HEADER);
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
    public final int readExceptionCode() {
        int code = readInt();
        if (code == EX_HAS_REPLY_HEADER) {
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
            try {
                Throwable rootCause = ExceptionUtils.getRootCause(e);
                if (rootCause != null) {
                    rootCause.initCause(cause);
                }
            } catch (RuntimeException ex) {
                Log.e(TAG, "Cannot set cause " + cause + " for " + e, ex);
            }
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
        }
        return new RuntimeException("Unknown exception code: " + code
                + " msg " + msg);
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
    public final String readString() {
        return mReadWriteHelper.readString(this);
    }

    /**
     * Read a string without going though a {@link ReadWriteHelper}.  Subclasses of
     * {@link ReadWriteHelper} must use this method instead of {@link #readString} to avoid
     * infinity recursive calls.
     *
     * @hide
     */
    public String readStringNoHelper() {
        return nativeReadString(mNativePtr);
    }

    /** @hide */
    public final boolean readBoolean() {
        return readInt() != 0;
    }

    /**
     * Read a CharSequence value from the parcel at the current dataPosition().
     * @hide
     */
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
    public final FileDescriptor readRawFileDescriptor() {
        return nativeReadFileDescriptor(mNativePtr);
    }

    /**
     * {@hide}
     * Read and return a new array of FileDescriptors from the parcel.
     * @return the FileDescriptor array, or null if the array is null.
     **/
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

    /** @deprecated use {@link android.system.Os#open(String, int, int)} */
    @Deprecated
    static native FileDescriptor openFileDescriptor(String file, int mode)
            throws FileNotFoundException;

    /** @deprecated use {@link android.system.Os#dup(FileDescriptor)} */
    @Deprecated
    static native FileDescriptor dupFileDescriptor(FileDescriptor orig) throws IOException;

    /** @deprecated use {@link android.system.Os#close(FileDescriptor)} */
    @Deprecated
    static native void closeFileDescriptor(FileDescriptor desc) throws IOException;

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
    public final void readMap(Map outVal, ClassLoader loader) {
        int N = readInt();
        readMapInternal(outVal, N, loader);
    }

    /**
     * Read into an existing List object from the parcel at the current
     * dataPosition(), using the given class loader to load any enclosed
     * Parcelables.  If it is null, the default class loader is used.
     */
    public final void readList(List outVal, ClassLoader loader) {
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
    public final HashMap readHashMap(ClassLoader loader)
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
    public final Bundle readBundle() {
        return readBundle(null);
    }

    /**
     * Read and return a new Bundle object from the parcel at the current
     * dataPosition(), using the given class loader to initialize the class
     * loader of the Bundle for later retrieval of Parcelable objects.
     * Returns null if the previously written Bundle object was null.
     */
    public final Bundle readBundle(ClassLoader loader) {
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
    public final PersistableBundle readPersistableBundle() {
        return readPersistableBundle(null);
    }

    /**
     * Read and return a new Bundle object from the parcel at the current
     * dataPosition(), using the given class loader to initialize the class
     * loader of the Bundle for later retrieval of Parcelable objects.
     * Returns null if the previously written Bundle object was null.
     */
    public final PersistableBundle readPersistableBundle(ClassLoader loader) {
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
    public final Size readSize() {
        final int width = readInt();
        final int height = readInt();
        return new Size(width, height);
    }

    /**
     * Read a SizeF from the parcel at the current dataPosition().
     */
    public final SizeF readSizeF() {
        final float width = readFloat();
        final float height = readFloat();
        return new SizeF(width, height);
    }

    /**
     * Read and return a byte[] object from the parcel.
     */
    public final byte[] createByteArray() {
        return nativeCreateByteArray(mNativePtr);
    }

    /**
     * Read a byte[] object from the parcel and copy it into the
     * given byte array.
     */
    public final void readByteArray(byte[] val) {
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
    public final byte[] readBlob() {
        return nativeReadBlob(mNativePtr);
    }

    /**
     * Read and return a String[] object from the parcel.
     * {@hide}
     */
    public final String[] readStringArray() {
        String[] array = null;

        int length = readInt();
        if (length >= 0)
        {
            array = new String[length];

            for (int i = 0 ; i < length ; i++)
            {
                array[i] = readString();
            }
        }

        return array;
    }

    /**
     * Read and return a CharSequence[] object from the parcel.
     * {@hide}
     */
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
    public final ArrayList readArrayList(ClassLoader loader) {
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
    public final Object[] readArray(ClassLoader loader) {
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
    public final SparseArray readSparseArray(ClassLoader loader) {
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
    public final <T> ArrayList<T> createTypedArrayList(Parcelable.Creator<T> c) {
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
    public final <T> void readTypedList(List<T> list, Parcelable.Creator<T> c) {
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
    public final void readStringList(List<String> list) {
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
    public final void readBinderList(List<IBinder> list) {
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
     * @hide
     */
    public final <T extends Parcelable> List<T> readParcelableList(List<T> list, ClassLoader cl) {
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
    public final <T> T[] createTypedArray(Parcelable.Creator<T> c) {
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

    public final <T> void readTypedArray(T[] val, Parcelable.Creator<T> c) {
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
    public final <T> T readTypedObject(Parcelable.Creator<T> c) {
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
    public final <T extends Parcelable> void writeParcelableArray(T[] value,
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
    public final Object readValue(ClassLoader loader) {
        int type = readInt();

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
    public final <T extends Parcelable> T readParcelable(ClassLoader loader) {
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
    @SuppressWarnings("unchecked")
    public final <T extends Parcelable> T readCreator(Parcelable.Creator<?> creator,
            ClassLoader loader) {
        if (creator instanceof Parcelable.ClassLoaderCreator<?>) {
          Parcelable.ClassLoaderCreator<?> classLoaderCreator =
              (Parcelable.ClassLoaderCreator<?>) creator;
          return (T) classLoaderCreator.createFromParcel(this, loader);
        }
        return (T) creator.createFromParcel(this);
    }

    /** @hide */
    public final Parcelable.Creator<?> readParcelableCreator(ClassLoader loader) {
        String name = readString();
        if (name == null) {
            return null;
        }
        Parcelable.Creator<?> creator;
        synchronized (mCreators) {
            HashMap<String,Parcelable.Creator<?>> map = mCreators.get(loader);
            if (map == null) {
                map = new HashMap<>();
                mCreators.put(loader, map);
            }
            creator = map.get(name);
            if (creator == null) {
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
                }
                catch (IllegalAccessException e) {
                    Log.e(TAG, "Illegal access when unmarshalling: " + name, e);
                    throw new BadParcelableException(
                            "IllegalAccessException when unmarshalling: " + name);
                }
                catch (ClassNotFoundException e) {
                    Log.e(TAG, "Class not found when unmarshalling: " + name, e);
                    throw new BadParcelableException(
                            "ClassNotFoundException when unmarshalling: " + name);
                }
                catch (NoSuchFieldException e) {
                    throw new BadParcelableException("Parcelable protocol requires a "
                            + "Parcelable.Creator object called "
                            + "CREATOR on class " + name);
                }
                if (creator == null) {
                    throw new BadParcelableException("Parcelable protocol requires a "
                            + "non-null Parcelable.Creator object called "
                            + "CREATOR on class " + name);
                }

                map.put(name, creator);
            }
        }

        return creator;
    }

    /**
     * Read and return a new Parcelable array from the parcel.
     * The given class loader will be used to load any enclosed
     * Parcelables.
     * @return the Parcelable array, or null if the array is null
     */
    public final Parcelable[] readParcelableArray(ClassLoader loader) {
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
    public final <T extends Parcelable> T[] readParcelableArray(ClassLoader loader,
            Class<T> clazz) {
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
    public final Serializable readSerializable() {
        return readSerializable(null);
    }

    private final Serializable readSerializable(final ClassLoader loader) {
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
        if (mOwnsNativeParcelObject) {
            updateNativeSize(nativeFreeBuffer(mNativePtr));
        }
        mReadWriteHelper = ReadWriteHelper.DEFAULT;
    }

    private void destroy() {
        if (mNativePtr != 0) {
            if (mOwnsNativeParcelObject) {
                nativeDestroy(mNativePtr);
                updateNativeSize(0);
            }
            mNativePtr = 0;
        }
        mReadWriteHelper = null;
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

    /* package */ void readMapInternal(Map outVal, int N,
        ClassLoader loader) {
        while (N > 0) {
            Object key = readValue(loader);
            Object value = readValue(loader);
            outVal.put(key, value);
            N--;
        }
    }

    /* package */ void readArrayMapInternal(ArrayMap outVal, int N,
        ClassLoader loader) {
        if (DEBUG_ARRAY_MAP) {
            RuntimeException here =  new RuntimeException("here");
            here.fillInStackTrace();
            Log.d(TAG, "Reading " + N + " ArrayMap entries", here);
        }
        int startPos;
        while (N > 0) {
            if (DEBUG_ARRAY_MAP) startPos = dataPosition();
            String key = readString();
            Object value = readValue(loader);
            if (DEBUG_ARRAY_MAP) Log.d(TAG, "  Read #" + (N-1) + " "
                    + (dataPosition()-startPos) + " bytes: key=0x"
                    + Integer.toHexString((key != null ? key.hashCode() : 0)) + " " + key);
            outVal.append(key, value);
            N--;
        }
        outVal.validate();
    }

    /* package */ void readArrayMapSafelyInternal(ArrayMap outVal, int N,
        ClassLoader loader) {
        if (DEBUG_ARRAY_MAP) {
            RuntimeException here =  new RuntimeException("here");
            here.fillInStackTrace();
            Log.d(TAG, "Reading safely " + N + " ArrayMap entries", here);
        }
        while (N > 0) {
            String key = readString();
            if (DEBUG_ARRAY_MAP) Log.d(TAG, "  Read safe #" + (N-1) + ": key=0x"
                    + (key != null ? key.hashCode() : 0) + " " + key);
            Object value = readValue(loader);
            outVal.put(key, value);
            N--;
        }
    }

    /**
     * @hide For testing only.
     */
    public void readArrayMap(ArrayMap outVal, ClassLoader loader) {
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
    public @Nullable ArraySet<? extends Object> readArraySet(ClassLoader loader) {
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

    private void readListInternal(List outVal, int N,
        ClassLoader loader) {
        while (N > 0) {
            Object value = readValue(loader);
            //Log.d(TAG, "Unmarshalling value=" + value);
            outVal.add(value);
            N--;
        }
    }

    private void readArrayInternal(Object[] outVal, int N,
        ClassLoader loader) {
        for (int i = 0; i < N; i++) {
            Object value = readValue(loader);
            //Log.d(TAG, "Unmarshalling value=" + value);
            outVal[i] = value;
        }
    }

    private void readSparseArrayInternal(SparseArray outVal, int N,
        ClassLoader loader) {
        while (N > 0) {
            int key = readInt();
            Object value = readValue(loader);
            //Log.i(TAG, "Unmarshalling key=" + key + " value=" + value);
            outVal.append(key, value);
            N--;
        }
    }


    private void readSparseBooleanArrayInternal(SparseBooleanArray outVal, int N) {
        while (N > 0) {
            int key = readInt();
            boolean value = this.readByte() == 1;
            //Log.i(TAG, "Unmarshalling key=" + key + " value=" + value);
            outVal.append(key, value);
            N--;
        }
    }

    private void readSparseIntArrayInternal(SparseIntArray outVal, int N) {
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
}
