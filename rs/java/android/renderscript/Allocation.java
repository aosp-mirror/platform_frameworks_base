/*
 * Copyright (C) 2008-2012 The Android Open Source Project
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

package android.renderscript;

import java.util.HashMap;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.view.Surface;
import android.util.Log;
import android.graphics.Canvas;
import android.os.Trace;

/**
 * <p> This class provides the primary method through which data is passed to
 * and from RenderScript kernels.  An Allocation provides the backing store for
 * a given {@link android.renderscript.Type}.  </p>
 *
 * <p>An Allocation also contains a set of usage flags that denote how the
 * Allocation could be used. For example, an Allocation may have usage flags
 * specifying that it can be used from a script as well as input to a {@link
 * android.renderscript.Sampler}. A developer must synchronize across these
 * different usages using {@link android.renderscript.Allocation#syncAll} in
 * order to ensure that different users of the Allocation have a consistent view
 * of memory. For example, in the case where an Allocation is used as the output
 * of one kernel and as Sampler input in a later kernel, a developer must call
 * {@link #syncAll syncAll(Allocation.USAGE_SCRIPT)} prior to launching the
 * second kernel to ensure correctness.
 *
 * <p>An Allocation can be populated with the {@link #copyFrom} routines. For
 * more complex Element types, the {@link #copyFromUnchecked} methods can be
 * used to copy from byte arrays or similar constructs.</p>
 *
 * <div class="special reference">
 * <h3>Developer Guides</h3>
 * <p>For more information about creating an application that uses RenderScript, read the
 * <a href="{@docRoot}guide/topics/renderscript/index.html">RenderScript</a> developer guide.</p>
 * </div>
 **/
public class Allocation extends BaseObj {
    Type mType;
    Bitmap mBitmap;
    int mUsage;
    Allocation mAdaptedAllocation;
    int mSize;

    boolean mConstrainedLOD;
    boolean mConstrainedFace;
    boolean mConstrainedY;
    boolean mConstrainedZ;
    boolean mReadAllowed = true;
    boolean mWriteAllowed = true;
    int mSelectedY;
    int mSelectedZ;
    int mSelectedLOD;
    Type.CubemapFace mSelectedFace = Type.CubemapFace.POSITIVE_X;

    int mCurrentDimX;
    int mCurrentDimY;
    int mCurrentDimZ;
    int mCurrentCount;
    static HashMap<Long, Allocation> mAllocationMap =
            new HashMap<Long, Allocation>();
    OnBufferAvailableListener mBufferNotifier;

    private Element.DataType validateObjectIsPrimitiveArray(Object d, boolean checkType) {
        final Class c = d.getClass();
        if (!c.isArray()) {
            throw new RSIllegalArgumentException("Object passed is not an array of primitives.");
        }
        final Class cmp = c.getComponentType();
        if (!cmp.isPrimitive()) {
            throw new RSIllegalArgumentException("Object passed is not an Array of primitives.");
        }

        if (cmp == Long.TYPE) {
            if (checkType) {
                validateIsInt64();
                return mType.mElement.mType;
            }
            return Element.DataType.SIGNED_64;
        }

        if (cmp == Integer.TYPE) {
            if (checkType) {
                validateIsInt32();
                return mType.mElement.mType;
            }
            return Element.DataType.SIGNED_32;
        }

        if (cmp == Short.TYPE) {
            if (checkType) {
                validateIsInt16();
                return mType.mElement.mType;
            }
            return Element.DataType.SIGNED_16;
        }

        if (cmp == Byte.TYPE) {
            if (checkType) {
                validateIsInt8();
                return mType.mElement.mType;
            }
            return Element.DataType.SIGNED_8;
        }

        if (cmp == Float.TYPE) {
            if (checkType) {
                validateIsFloat32();
            }
            return Element.DataType.FLOAT_32;
        }

        if (cmp == Double.TYPE) {
            if (checkType) {
                validateIsFloat64();
            }
            return Element.DataType.FLOAT_64;
        }
        return null;
    }


    /**
     * The usage of the Allocation.  These signal to RenderScript where to place
     * the Allocation in memory.
     *
     */

    /**
     * The Allocation will be bound to and accessed by scripts.
     */
    public static final int USAGE_SCRIPT = 0x0001;

    /**
     * The Allocation will be used as a texture source by one or more graphics
     * programs.
     *
     */
    public static final int USAGE_GRAPHICS_TEXTURE = 0x0002;

    /**
     * The Allocation will be used as a graphics mesh.
     *
     * This was deprecated in API level 16.
     *
     */
    public static final int USAGE_GRAPHICS_VERTEX = 0x0004;


    /**
     * The Allocation will be used as the source of shader constants by one or
     * more programs.
     *
     * This was deprecated in API level 16.
     *
     */
    public static final int USAGE_GRAPHICS_CONSTANTS = 0x0008;

    /**
     * The Allocation will be used as a target for offscreen rendering
     *
     * This was deprecated in API level 16.
     *
     */
    public static final int USAGE_GRAPHICS_RENDER_TARGET = 0x0010;

    /**
     * The Allocation will be used as a {@link android.view.Surface}
     * consumer.  This usage will cause the Allocation to be created
     * as read-only.
     *
     */
    public static final int USAGE_IO_INPUT = 0x0020;

    /**
     * The Allocation will be used as a {@link android.view.Surface}
     * producer.  The dimensions and format of the {@link
     * android.view.Surface} will be forced to those of the
     * Allocation.
     *
     */
    public static final int USAGE_IO_OUTPUT = 0x0040;

    /**
     * The Allocation's backing store will be inherited from another object
     * (usually a {@link android.graphics.Bitmap}); copying to or from the
     * original source Bitmap will cause a synchronization rather than a full
     * copy.  {@link #syncAll} may also be used to synchronize the Allocation
     * and the source Bitmap.
     *
     * <p>This is set by default for allocations created with {@link
     * #createFromBitmap} in API version 18 and higher.</p>
     *
     */
    public static final int USAGE_SHARED = 0x0080;

    /**
     * Controls mipmap behavior when using the bitmap creation and update
     * functions.
     */
    public enum MipmapControl {
        /**
         * No mipmaps will be generated and the type generated from the incoming
         * bitmap will not contain additional LODs.
         */
        MIPMAP_NONE(0),

        /**
         * A full mipmap chain will be created in script memory.  The Type of
         * the Allocation will contain a full mipmap chain.  On upload, the full
         * chain will be transferred.
         */
        MIPMAP_FULL(1),

        /**
         * The Type of the Allocation will be the same as MIPMAP_NONE.  It will
         * not contain mipmaps.  On upload, the allocation data will contain a
         * full mipmap chain generated from the top level in script memory.
         */
        MIPMAP_ON_SYNC_TO_TEXTURE(2);

        int mID;
        MipmapControl(int id) {
            mID = id;
        }
    }


    private long getIDSafe() {
        if (mAdaptedAllocation != null) {
            return mAdaptedAllocation.getID(mRS);
        }
        return getID(mRS);
    }


   /**
     * Get the {@link android.renderscript.Element} of the {@link
     * android.renderscript.Type} of the Allocation.
     *
     * @return Element
     *
     */
    public Element getElement() {
        return mType.getElement();
    }

    /**
     * Get the usage flags of the Allocation.
     *
     * @return usage this Allocation's set of the USAGE_* flags OR'd together
     *
     */
    public int getUsage() {
        return mUsage;
    }

    /**
     * Get the size of the Allocation in bytes.
     *
     * @return size of the Allocation in bytes.
     *
     */
    public int getBytesSize() {
        if (mType.mDimYuv != 0) {
            return (int)Math.ceil(mType.getCount() * mType.getElement().getBytesSize() * 1.5);
        }
        return mType.getCount() * mType.getElement().getBytesSize();
    }

    private void updateCacheInfo(Type t) {
        mCurrentDimX = t.getX();
        mCurrentDimY = t.getY();
        mCurrentDimZ = t.getZ();
        mCurrentCount = mCurrentDimX;
        if (mCurrentDimY > 1) {
            mCurrentCount *= mCurrentDimY;
        }
        if (mCurrentDimZ > 1) {
            mCurrentCount *= mCurrentDimZ;
        }
    }

    private void setBitmap(Bitmap b) {
        mBitmap = b;
    }

    Allocation(long id, RenderScript rs, Type t, int usage) {
        super(id, rs);
        if ((usage & ~(USAGE_SCRIPT |
                       USAGE_GRAPHICS_TEXTURE |
                       USAGE_GRAPHICS_VERTEX |
                       USAGE_GRAPHICS_CONSTANTS |
                       USAGE_GRAPHICS_RENDER_TARGET |
                       USAGE_IO_INPUT |
                       USAGE_IO_OUTPUT |
                       USAGE_SHARED)) != 0) {
            throw new RSIllegalArgumentException("Unknown usage specified.");
        }

        if ((usage & USAGE_IO_INPUT) != 0) {
            mWriteAllowed = false;

            if ((usage & ~(USAGE_IO_INPUT |
                           USAGE_GRAPHICS_TEXTURE |
                           USAGE_SCRIPT)) != 0) {
                throw new RSIllegalArgumentException("Invalid usage combination.");
            }
        }

        mType = t;
        mUsage = usage;

        if (t != null) {
            // TODO: A3D doesn't have Type info during creation, so we can't
            // calculate the size ahead of time. We can possibly add a method
            // to update the size in the future if it seems reasonable.
            mSize = mType.getCount() * mType.getElement().getBytesSize();
            updateCacheInfo(t);
        }
        try {
            RenderScript.registerNativeAllocation.invoke(RenderScript.sRuntime, mSize);
        } catch (Exception e) {
            Log.e(RenderScript.LOG_TAG, "Couldn't invoke registerNativeAllocation:" + e);
            throw new RSRuntimeException("Couldn't invoke registerNativeAllocation:" + e);
        }
    }

    protected void finalize() throws Throwable {
        RenderScript.registerNativeFree.invoke(RenderScript.sRuntime, mSize);
        super.finalize();
    }

    private void validateIsInt64() {
        if ((mType.mElement.mType == Element.DataType.SIGNED_64) ||
            (mType.mElement.mType == Element.DataType.UNSIGNED_64)) {
            return;
        }
        throw new RSIllegalArgumentException(
            "64 bit integer source does not match allocation type " + mType.mElement.mType);
    }

    private void validateIsInt32() {
        if ((mType.mElement.mType == Element.DataType.SIGNED_32) ||
            (mType.mElement.mType == Element.DataType.UNSIGNED_32)) {
            return;
        }
        throw new RSIllegalArgumentException(
            "32 bit integer source does not match allocation type " + mType.mElement.mType);
    }

    private void validateIsInt16() {
        if ((mType.mElement.mType == Element.DataType.SIGNED_16) ||
            (mType.mElement.mType == Element.DataType.UNSIGNED_16)) {
            return;
        }
        throw new RSIllegalArgumentException(
            "16 bit integer source does not match allocation type " + mType.mElement.mType);
    }

    private void validateIsInt8() {
        if ((mType.mElement.mType == Element.DataType.SIGNED_8) ||
            (mType.mElement.mType == Element.DataType.UNSIGNED_8)) {
            return;
        }
        throw new RSIllegalArgumentException(
            "8 bit integer source does not match allocation type " + mType.mElement.mType);
    }

    private void validateIsFloat32() {
        if (mType.mElement.mType == Element.DataType.FLOAT_32) {
            return;
        }
        throw new RSIllegalArgumentException(
            "32 bit float source does not match allocation type " + mType.mElement.mType);
    }

    private void validateIsFloat64() {
        if (mType.mElement.mType == Element.DataType.FLOAT_64) {
            return;
        }
        throw new RSIllegalArgumentException(
            "64 bit float source does not match allocation type " + mType.mElement.mType);
    }

    private void validateIsObject() {
        if ((mType.mElement.mType == Element.DataType.RS_ELEMENT) ||
            (mType.mElement.mType == Element.DataType.RS_TYPE) ||
            (mType.mElement.mType == Element.DataType.RS_ALLOCATION) ||
            (mType.mElement.mType == Element.DataType.RS_SAMPLER) ||
            (mType.mElement.mType == Element.DataType.RS_SCRIPT) ||
            (mType.mElement.mType == Element.DataType.RS_MESH) ||
            (mType.mElement.mType == Element.DataType.RS_PROGRAM_FRAGMENT) ||
            (mType.mElement.mType == Element.DataType.RS_PROGRAM_VERTEX) ||
            (mType.mElement.mType == Element.DataType.RS_PROGRAM_RASTER) ||
            (mType.mElement.mType == Element.DataType.RS_PROGRAM_STORE)) {
            return;
        }
        throw new RSIllegalArgumentException(
            "Object source does not match allocation type " + mType.mElement.mType);
    }

    @Override
    void updateFromNative() {
        super.updateFromNative();
        long typeID = mRS.nAllocationGetType(getID(mRS));
        if(typeID != 0) {
            mType = new Type(typeID, mRS);
            mType.updateFromNative();
            updateCacheInfo(mType);
        }
    }

    /**
     * Get the {@link android.renderscript.Type} of the Allocation.
     *
     * @return Type
     *
     */
    public Type getType() {
        return mType;
    }

    /**
     * Propagate changes from one usage of the Allocation to the
     * other usages of the Allocation.
     *
     */
    public void syncAll(int srcLocation) {
        Trace.traceBegin(RenderScript.TRACE_TAG, "syncAll");
        switch (srcLocation) {
        case USAGE_GRAPHICS_TEXTURE:
        case USAGE_SCRIPT:
            if ((mUsage & USAGE_SHARED) != 0) {
                copyFrom(mBitmap);
            }
            break;
        case USAGE_GRAPHICS_CONSTANTS:
        case USAGE_GRAPHICS_VERTEX:
            break;
        case USAGE_SHARED:
            if ((mUsage & USAGE_SHARED) != 0) {
                copyTo(mBitmap);
            }
            break;
        default:
            throw new RSIllegalArgumentException("Source must be exactly one usage type.");
        }
        mRS.validate();
        mRS.nAllocationSyncAll(getIDSafe(), srcLocation);
        Trace.traceEnd(RenderScript.TRACE_TAG);
    }

    /**
     * Send a buffer to the output stream.  The contents of the Allocation will
     * be undefined after this operation. This operation is only valid if {@link
     * #USAGE_IO_OUTPUT} is set on the Allocation.
     *
     *
     */
    public void ioSend() {
        Trace.traceBegin(RenderScript.TRACE_TAG, "ioSend");
        if ((mUsage & USAGE_IO_OUTPUT) == 0) {
            throw new RSIllegalArgumentException(
                "Can only send buffer if IO_OUTPUT usage specified.");
        }
        mRS.validate();
        mRS.nAllocationIoSend(getID(mRS));
        Trace.traceEnd(RenderScript.TRACE_TAG);
    }

    /**
     * Receive the latest input into the Allocation. This operation
     * is only valid if {@link #USAGE_IO_INPUT} is set on the Allocation.
     *
     */
    public void ioReceive() {
        Trace.traceBegin(RenderScript.TRACE_TAG, "ioReceive");
        if ((mUsage & USAGE_IO_INPUT) == 0) {
            throw new RSIllegalArgumentException(
                "Can only receive if IO_INPUT usage specified.");
        }
        mRS.validate();
        mRS.nAllocationIoReceive(getID(mRS));
        Trace.traceEnd(RenderScript.TRACE_TAG);
    }

    /**
     * Copy an array of RS objects to the Allocation.
     *
     * @param d Source array.
     */
    public void copyFrom(BaseObj[] d) {
        Trace.traceBegin(RenderScript.TRACE_TAG, "copyFrom");
        mRS.validate();
        validateIsObject();
        if (d.length != mCurrentCount) {
            throw new RSIllegalArgumentException("Array size mismatch, allocation sizeX = " +
                                                 mCurrentCount + ", array length = " + d.length);
        }

        if (RenderScript.sPointerSize == 8) {
            long i[] = new long[d.length * 4];
            for (int ct=0; ct < d.length; ct++) {
                i[ct * 4] = d[ct].getID(mRS);
            }
            copy1DRangeFromUnchecked(0, mCurrentCount, i);
        } else {
            int i[] = new int[d.length];
            for (int ct=0; ct < d.length; ct++) {
                i[ct] = (int)d[ct].getID(mRS);
            }
            copy1DRangeFromUnchecked(0, mCurrentCount, i);
        }
        Trace.traceEnd(RenderScript.TRACE_TAG);
    }

    private void validateBitmapFormat(Bitmap b) {
        Bitmap.Config bc = b.getConfig();
        if (bc == null) {
            throw new RSIllegalArgumentException("Bitmap has an unsupported format for this operation");
        }
        switch (bc) {
        case ALPHA_8:
            if (mType.getElement().mKind != Element.DataKind.PIXEL_A) {
                throw new RSIllegalArgumentException("Allocation kind is " +
                                                     mType.getElement().mKind + ", type " +
                                                     mType.getElement().mType +
                                                     " of " + mType.getElement().getBytesSize() +
                                                     " bytes, passed bitmap was " + bc);
            }
            break;
        case ARGB_8888:
            if ((mType.getElement().mKind != Element.DataKind.PIXEL_RGBA) ||
                (mType.getElement().getBytesSize() != 4)) {
                throw new RSIllegalArgumentException("Allocation kind is " +
                                                     mType.getElement().mKind + ", type " +
                                                     mType.getElement().mType +
                                                     " of " + mType.getElement().getBytesSize() +
                                                     " bytes, passed bitmap was " + bc);
            }
            break;
        case RGB_565:
            if ((mType.getElement().mKind != Element.DataKind.PIXEL_RGB) ||
                (mType.getElement().getBytesSize() != 2)) {
                throw new RSIllegalArgumentException("Allocation kind is " +
                                                     mType.getElement().mKind + ", type " +
                                                     mType.getElement().mType +
                                                     " of " + mType.getElement().getBytesSize() +
                                                     " bytes, passed bitmap was " + bc);
            }
            break;
        case ARGB_4444:
            if ((mType.getElement().mKind != Element.DataKind.PIXEL_RGBA) ||
                (mType.getElement().getBytesSize() != 2)) {
                throw new RSIllegalArgumentException("Allocation kind is " +
                                                     mType.getElement().mKind + ", type " +
                                                     mType.getElement().mType +
                                                     " of " + mType.getElement().getBytesSize() +
                                                     " bytes, passed bitmap was " + bc);
            }
            break;

        }
    }

    private void validateBitmapSize(Bitmap b) {
        if((mCurrentDimX != b.getWidth()) || (mCurrentDimY != b.getHeight())) {
            throw new RSIllegalArgumentException("Cannot update allocation from bitmap, sizes mismatch");
        }
    }

    private void copyFromUnchecked(Object array, Element.DataType dt, int arrayLen) {
        Trace.traceBegin(RenderScript.TRACE_TAG, "copyFromUnchecked");
        mRS.validate();
        if (mCurrentDimZ > 0) {
            copy3DRangeFromUnchecked(0, 0, 0, mCurrentDimX, mCurrentDimY, mCurrentDimZ, array, dt, arrayLen);
        } else if (mCurrentDimY > 0) {
            copy2DRangeFromUnchecked(0, 0, mCurrentDimX, mCurrentDimY, array, dt, arrayLen);
        } else {
            copy1DRangeFromUnchecked(0, mCurrentCount, array, dt, arrayLen);
        }
        Trace.traceEnd(RenderScript.TRACE_TAG);
    }

    /**
     * Copy into this Allocation from an array. This method does not guarantee
     * that the Allocation is compatible with the input buffer; it copies memory
     * without reinterpretation.
     *
     * @param array The source data array
     */
    public void copyFromUnchecked(Object array) {
        Trace.traceBegin(RenderScript.TRACE_TAG, "copyFromUnchecked");
        copyFromUnchecked(array, validateObjectIsPrimitiveArray(array, false),
                          java.lang.reflect.Array.getLength(array));
        Trace.traceEnd(RenderScript.TRACE_TAG);
    }

    /**
     * Copy into this Allocation from an array. This method does not guarantee
     * that the Allocation is compatible with the input buffer; it copies memory
     * without reinterpretation.
     *
     * @param d the source data array
     */
    public void copyFromUnchecked(int[] d) {
        copyFromUnchecked(d, Element.DataType.SIGNED_32, d.length);
    }

    /**
     * Copy into this Allocation from an array. This method does not guarantee
     * that the Allocation is compatible with the input buffer; it copies memory
     * without reinterpretation.
     *
     * @param d the source data array
     */
    public void copyFromUnchecked(short[] d) {
        copyFromUnchecked(d, Element.DataType.SIGNED_16, d.length);
    }

    /**
     * Copy into this Allocation from an array. This method does not guarantee
     * that the Allocation is compatible with the input buffer; it copies memory
     * without reinterpretation.
     *
     * @param d the source data array
     */
    public void copyFromUnchecked(byte[] d) {
        copyFromUnchecked(d, Element.DataType.SIGNED_8, d.length);
    }

    /**
     * Copy into this Allocation from an array. This method does not guarantee
     * that the Allocation is compatible with the input buffer; it copies memory
     * without reinterpretation.
     *
     * @param d the source data array
     */
    public void copyFromUnchecked(float[] d) {
        copyFromUnchecked(d, Element.DataType.FLOAT_32, d.length);
    }


    /**
     * Copy into this Allocation from an array.  This variant is type checked
     * and will generate exceptions if the Allocation's {@link
     * android.renderscript.Element} does not match the array's
     * primitive type.
     *
     * @param array The source data array
     */
    public void copyFrom(Object array) {
        Trace.traceBegin(RenderScript.TRACE_TAG, "copyFrom");
        copyFromUnchecked(array, validateObjectIsPrimitiveArray(array, true),
                          java.lang.reflect.Array.getLength(array));
        Trace.traceEnd(RenderScript.TRACE_TAG);
    }

    /**
     * Copy into this Allocation from an array.  This variant is type checked
     * and will generate exceptions if the Allocation's {@link
     * android.renderscript.Element} is not a 32 bit integer type.
     *
     * @param d the source data array
     */
    public void copyFrom(int[] d) {
        validateIsInt32();
        copyFromUnchecked(d, Element.DataType.SIGNED_32, d.length);
    }

    /**
     * Copy into this Allocation from an array.  This variant is type checked
     * and will generate exceptions if the Allocation's {@link
     * android.renderscript.Element} is not a 16 bit integer type.
     *
     * @param d the source data array
     */
    public void copyFrom(short[] d) {
        validateIsInt16();
        copyFromUnchecked(d, Element.DataType.SIGNED_16, d.length);
    }

    /**
     * Copy into this Allocation from an array.  This variant is type checked
     * and will generate exceptions if the Allocation's {@link
     * android.renderscript.Element} is not an 8 bit integer type.
     *
     * @param d the source data array
     */
    public void copyFrom(byte[] d) {
        validateIsInt8();
        copyFromUnchecked(d, Element.DataType.SIGNED_8, d.length);
    }

    /**
     * Copy into this Allocation from an array.  This variant is type checked
     * and will generate exceptions if the Allocation's {@link
     * android.renderscript.Element} is not a 32 bit float type.
     *
     * @param d the source data array
     */
    public void copyFrom(float[] d) {
        validateIsFloat32();
        copyFromUnchecked(d, Element.DataType.FLOAT_32, d.length);
    }

    /**
     * Copy into an Allocation from a {@link android.graphics.Bitmap}.  The
     * height, width, and format of the bitmap must match the existing
     * allocation.
     *
     * <p>If the {@link android.graphics.Bitmap} is the same as the {@link
     * android.graphics.Bitmap} used to create the Allocation with {@link
     * #createFromBitmap} and {@link #USAGE_SHARED} is set on the Allocation,
     * this will synchronize the Allocation with the latest data from the {@link
     * android.graphics.Bitmap}, potentially avoiding the actual copy.</p>
     *
     * @param b the source bitmap
     */
    public void copyFrom(Bitmap b) {
        Trace.traceBegin(RenderScript.TRACE_TAG, "copyFrom");
        mRS.validate();
        if (b.getConfig() == null) {
            Bitmap newBitmap = Bitmap.createBitmap(b.getWidth(), b.getHeight(), Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(newBitmap);
            c.drawBitmap(b, 0, 0, null);
            copyFrom(newBitmap);
            return;
        }
        validateBitmapSize(b);
        validateBitmapFormat(b);
        mRS.nAllocationCopyFromBitmap(getID(mRS), b);
        Trace.traceEnd(RenderScript.TRACE_TAG);
    }

    /**
     * Copy an Allocation from an Allocation.  The types of both allocations
     * must be identical.
     *
     * @param a the source allocation
     */
    public void copyFrom(Allocation a) {
        Trace.traceBegin(RenderScript.TRACE_TAG, "copyFrom");
        mRS.validate();
        if (!mType.equals(a.getType())) {
            throw new RSIllegalArgumentException("Types of allocations must match.");
        }
        copy2DRangeFrom(0, 0, mCurrentDimX, mCurrentDimY, a, 0, 0);
        Trace.traceEnd(RenderScript.TRACE_TAG);
    }

    /**
     * This is only intended to be used by auto-generated code reflected from
     * the RenderScript script files and should not be used by developers.
     *
     * @param xoff
     * @param fp
     */
    public void setFromFieldPacker(int xoff, FieldPacker fp) {
        mRS.validate();
        int eSize = mType.mElement.getBytesSize();
        final byte[] data = fp.getData();
        int data_length = fp.getPos();

        int count = data_length / eSize;
        if ((eSize * count) != data_length) {
            throw new RSIllegalArgumentException("Field packer length " + data_length +
                                               " not divisible by element size " + eSize + ".");
        }
        copy1DRangeFromUnchecked(xoff, count, data);
    }

    /**
     * This is only intended to be used by auto-generated code reflected from
     * the RenderScript script files.
     *
     * @param xoff
     * @param component_number
     * @param fp
     */
    public void setFromFieldPacker(int xoff, int component_number, FieldPacker fp) {
        mRS.validate();
        if (component_number >= mType.mElement.mElements.length) {
            throw new RSIllegalArgumentException("Component_number " + component_number + " out of range.");
        }
        if(xoff < 0) {
            throw new RSIllegalArgumentException("Offset must be >= 0.");
        }

        final byte[] data = fp.getData();
        int data_length = fp.getPos();
        int eSize = mType.mElement.mElements[component_number].getBytesSize();
        eSize *= mType.mElement.mArraySizes[component_number];

        if (data_length != eSize) {
            throw new RSIllegalArgumentException("Field packer sizelength " + data_length +
                                               " does not match component size " + eSize + ".");
        }

        mRS.nAllocationElementData1D(getIDSafe(), xoff, mSelectedLOD,
                                     component_number, data, data_length);
    }

    private void data1DChecks(int off, int count, int len, int dataSize) {
        mRS.validate();
        if(off < 0) {
            throw new RSIllegalArgumentException("Offset must be >= 0.");
        }
        if(count < 1) {
            throw new RSIllegalArgumentException("Count must be >= 1.");
        }
        if((off + count) > mCurrentCount) {
            throw new RSIllegalArgumentException("Overflow, Available count " + mCurrentCount +
                                               ", got " + count + " at offset " + off + ".");
        }
        if(len < dataSize) {
            throw new RSIllegalArgumentException("Array too small for allocation type.");
        }
    }

    /**
     * Generate a mipmap chain. This is only valid if the Type of the Allocation
     * includes mipmaps.
     *
     * <p>This function will generate a complete set of mipmaps from the top
     * level LOD and place them into the script memory space.</p>
     *
     * <p>If the Allocation is also using other memory spaces, a call to {@link
     * #syncAll syncAll(Allocation.USAGE_SCRIPT)} is required.</p>
     */
    public void generateMipmaps() {
        mRS.nAllocationGenerateMipmaps(getID(mRS));
    }

    private void copy1DRangeFromUnchecked(int off, int count, Object array,
                                          Element.DataType dt, int arrayLen) {
        Trace.traceBegin(RenderScript.TRACE_TAG, "copy1DRangeFromUnchecked");
        final int dataSize = mType.mElement.getBytesSize() * count;
        data1DChecks(off, count, arrayLen * dt.mSize, dataSize);
        mRS.nAllocationData1D(getIDSafe(), off, mSelectedLOD, count, array, dataSize, dt);
        Trace.traceEnd(RenderScript.TRACE_TAG);
    }

    /**
     * Copy an array into part of this Allocation.  This method does not
     * guarantee that the Allocation is compatible with the input buffer.
     *
     * @param off The offset of the first element to be copied.
     * @param count The number of elements to be copied.
     * @param array The source data array
     */
    public void copy1DRangeFromUnchecked(int off, int count, Object array) {
        copy1DRangeFromUnchecked(off, count, array,
                                 validateObjectIsPrimitiveArray(array, false),
                                 java.lang.reflect.Array.getLength(array));
    }

    /**
     * Copy an array into part of this Allocation.  This method does not
     * guarantee that the Allocation is compatible with the input buffer.
     *
     * @param off The offset of the first element to be copied.
     * @param count The number of elements to be copied.
     * @param d the source data array
     */
    public void copy1DRangeFromUnchecked(int off, int count, int[] d) {
        copy1DRangeFromUnchecked(off, count, (Object)d, Element.DataType.SIGNED_32, d.length);
    }

    /**
     * Copy an array into part of this Allocation.  This method does not
     * guarantee that the Allocation is compatible with the input buffer.
     *
     * @param off The offset of the first element to be copied.
     * @param count The number of elements to be copied.
     * @param d the source data array
     */
    public void copy1DRangeFromUnchecked(int off, int count, short[] d) {
        copy1DRangeFromUnchecked(off, count, (Object)d, Element.DataType.SIGNED_16, d.length);
    }

    /**
     * Copy an array into part of this Allocation.  This method does not
     * guarantee that the Allocation is compatible with the input buffer.
     *
     * @param off The offset of the first element to be copied.
     * @param count The number of elements to be copied.
     * @param d the source data array
     */
    public void copy1DRangeFromUnchecked(int off, int count, byte[] d) {
        copy1DRangeFromUnchecked(off, count, (Object)d, Element.DataType.SIGNED_8, d.length);
    }

    /**
     * Copy an array into part of this Allocation.  This method does not
     * guarantee that the Allocation is compatible with the input buffer.
     *
     * @param off The offset of the first element to be copied.
     * @param count The number of elements to be copied.
     * @param d the source data array
     */
    public void copy1DRangeFromUnchecked(int off, int count, float[] d) {
        copy1DRangeFromUnchecked(off, count, (Object)d, Element.DataType.FLOAT_32, d.length);
    }


    /**
     * Copy an array into part of this Allocation.  This variant is type checked
     * and will generate exceptions if the Allocation type does not
     * match the component type of the array passed in.
     *
     * @param off The offset of the first element to be copied.
     * @param count The number of elements to be copied.
     * @param array The source data array.
     */
    public void copy1DRangeFrom(int off, int count, Object array) {
        copy1DRangeFromUnchecked(off, count, array,
                                 validateObjectIsPrimitiveArray(array, true),
                                 java.lang.reflect.Array.getLength(array));
    }

    /**
     * Copy an array into part of this Allocation.  This variant is type checked
     * and will generate exceptions if the Allocation type is not a 32 bit
     * integer type.
     *
     * @param off The offset of the first element to be copied.
     * @param count The number of elements to be copied.
     * @param d the source data array
     */
    public void copy1DRangeFrom(int off, int count, int[] d) {
        validateIsInt32();
        copy1DRangeFromUnchecked(off, count, d, Element.DataType.SIGNED_32, d.length);
    }

    /**
     * Copy an array into part of this Allocation.  This variant is type checked
     * and will generate exceptions if the Allocation type is not a 16 bit
     * integer type.
     *
     * @param off The offset of the first element to be copied.
     * @param count The number of elements to be copied.
     * @param d the source data array
     */
    public void copy1DRangeFrom(int off, int count, short[] d) {
        validateIsInt16();
        copy1DRangeFromUnchecked(off, count, d, Element.DataType.SIGNED_16, d.length);
    }

    /**
     * Copy an array into part of this Allocation.  This variant is type checked
     * and will generate exceptions if the Allocation type is not an 8 bit
     * integer type.
     *
     * @param off The offset of the first element to be copied.
     * @param count The number of elements to be copied.
     * @param d the source data array
     */
    public void copy1DRangeFrom(int off, int count, byte[] d) {
        validateIsInt8();
        copy1DRangeFromUnchecked(off, count, d, Element.DataType.SIGNED_8, d.length);
    }

    /**
     * Copy an array into part of this Allocation.  This variant is type checked
     * and will generate exceptions if the Allocation type is not a 32 bit float
     * type.
     *
     * @param off The offset of the first element to be copied.
     * @param count The number of elements to be copied.
     * @param d the source data array.
     */
    public void copy1DRangeFrom(int off, int count, float[] d) {
        validateIsFloat32();
        copy1DRangeFromUnchecked(off, count, d, Element.DataType.FLOAT_32, d.length);
    }

     /**
     * Copy part of an Allocation into this Allocation.
     *
     * @param off The offset of the first element to be copied.
     * @param count The number of elements to be copied.
     * @param data the source data allocation.
     * @param dataOff off The offset of the first element in data to
     *          be copied.
     */
    public void copy1DRangeFrom(int off, int count, Allocation data, int dataOff) {
        Trace.traceBegin(RenderScript.TRACE_TAG, "copy1DRangeFrom");
        mRS.nAllocationData2D(getIDSafe(), off, 0,
                              mSelectedLOD, mSelectedFace.mID,
                              count, 1, data.getID(mRS), dataOff, 0,
                              data.mSelectedLOD, data.mSelectedFace.mID);
    }

    private void validate2DRange(int xoff, int yoff, int w, int h) {
        if (mAdaptedAllocation != null) {

        } else {

            if (xoff < 0 || yoff < 0) {
                throw new RSIllegalArgumentException("Offset cannot be negative.");
            }
            if (h < 0 || w < 0) {
                throw new RSIllegalArgumentException("Height or width cannot be negative.");
            }
            if (((xoff + w) > mCurrentDimX) || ((yoff + h) > mCurrentDimY)) {
                throw new RSIllegalArgumentException("Updated region larger than allocation.");
            }
        }
    }

    void copy2DRangeFromUnchecked(int xoff, int yoff, int w, int h, Object array,
                                  Element.DataType dt, int arrayLen) {
        Trace.traceBegin(RenderScript.TRACE_TAG, "copy2DRangeFromUnchecked");
        mRS.validate();
        validate2DRange(xoff, yoff, w, h);
        mRS.nAllocationData2D(getIDSafe(), xoff, yoff, mSelectedLOD, mSelectedFace.mID, w, h,
                              array, arrayLen * dt.mSize, dt);
        Trace.traceEnd(RenderScript.TRACE_TAG);
    }

    /**
     * Copy from an array into a rectangular region in this Allocation.  The
     * array is assumed to be tightly packed.
     *
     * @param xoff X offset of the region to update in this Allocation
     * @param yoff Y offset of the region to update in this Allocation
     * @param w Width of the region to update
     * @param h Height of the region to update
     * @param array Data to be placed into the Allocation
     */
    public void copy2DRangeFrom(int xoff, int yoff, int w, int h, Object array) {
        Trace.traceBegin(RenderScript.TRACE_TAG, "copy2DRangeFrom");
        copy2DRangeFromUnchecked(xoff, yoff, w, h, array,
                                 validateObjectIsPrimitiveArray(array, true),
                                 java.lang.reflect.Array.getLength(array));
        Trace.traceEnd(RenderScript.TRACE_TAG);
    }

    /**
     * Copy from an array into a rectangular region in this Allocation.  The
     * array is assumed to be tightly packed.
     *
     * @param xoff X offset of the region to update in this Allocation
     * @param yoff Y offset of the region to update in this Allocation
     * @param w Width of the region to update
     * @param h Height of the region to update
     * @param data to be placed into the Allocation
     */
    public void copy2DRangeFrom(int xoff, int yoff, int w, int h, byte[] data) {
        validateIsInt8();
        copy2DRangeFromUnchecked(xoff, yoff, w, h, data,
                                 Element.DataType.SIGNED_8, data.length);
    }

    /**
     * Copy from an array into a rectangular region in this Allocation.  The
     * array is assumed to be tightly packed.
     *
     * @param xoff X offset of the region to update in this Allocation
     * @param yoff Y offset of the region to update in this Allocation
     * @param w Width of the region to update
     * @param h Height of the region to update
     * @param data to be placed into the Allocation
     */
    public void copy2DRangeFrom(int xoff, int yoff, int w, int h, short[] data) {
        validateIsInt16();
        copy2DRangeFromUnchecked(xoff, yoff, w, h, data,
                                 Element.DataType.SIGNED_16, data.length);
    }

    /**
     * Copy from an array into a rectangular region in this Allocation.  The
     * array is assumed to be tightly packed.
     *
     * @param xoff X offset of the region to update in this Allocation
     * @param yoff Y offset of the region to update in this Allocation
     * @param w Width of the region to update
     * @param h Height of the region to update
     * @param data to be placed into the Allocation
     */
    public void copy2DRangeFrom(int xoff, int yoff, int w, int h, int[] data) {
        validateIsInt32();
        copy2DRangeFromUnchecked(xoff, yoff, w, h, data,
                                 Element.DataType.SIGNED_32, data.length);
    }

    /**
     * Copy from an array into a rectangular region in this Allocation.  The
     * array is assumed to be tightly packed.
     *
     * @param xoff X offset of the region to update in this Allocation
     * @param yoff Y offset of the region to update in this Allocation
     * @param w Width of the region to update
     * @param h Height of the region to update
     * @param data to be placed into the Allocation
     */
    public void copy2DRangeFrom(int xoff, int yoff, int w, int h, float[] data) {
        validateIsFloat32();
        copy2DRangeFromUnchecked(xoff, yoff, w, h, data,
                                 Element.DataType.FLOAT_32, data.length);
    }

    /**
     * Copy a rectangular region from an Allocation into a rectangular region in
     * this Allocation.
     *
     * @param xoff X offset of the region in this Allocation
     * @param yoff Y offset of the region in this Allocation
     * @param w Width of the region to update.
     * @param h Height of the region to update.
     * @param data source Allocation.
     * @param dataXoff X offset in source Allocation
     * @param dataYoff Y offset in source Allocation
     */
    public void copy2DRangeFrom(int xoff, int yoff, int w, int h,
                                Allocation data, int dataXoff, int dataYoff) {
        Trace.traceBegin(RenderScript.TRACE_TAG, "copy2DRangeFrom");
        mRS.validate();
        validate2DRange(xoff, yoff, w, h);
        mRS.nAllocationData2D(getIDSafe(), xoff, yoff,
                              mSelectedLOD, mSelectedFace.mID,
                              w, h, data.getID(mRS), dataXoff, dataYoff,
                              data.mSelectedLOD, data.mSelectedFace.mID);
        Trace.traceEnd(RenderScript.TRACE_TAG);
    }

    /**
     * Copy a {@link android.graphics.Bitmap} into an Allocation.  The height
     * and width of the update will use the height and width of the {@link
     * android.graphics.Bitmap}.
     *
     * @param xoff X offset of the region to update in this Allocation
     * @param yoff Y offset of the region to update in this Allocation
     * @param data the Bitmap to be copied
     */
    public void copy2DRangeFrom(int xoff, int yoff, Bitmap data) {
        Trace.traceBegin(RenderScript.TRACE_TAG, "copy2DRangeFrom");
        mRS.validate();
        if (data.getConfig() == null) {
            Bitmap newBitmap = Bitmap.createBitmap(data.getWidth(), data.getHeight(), Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(newBitmap);
            c.drawBitmap(data, 0, 0, null);
            copy2DRangeFrom(xoff, yoff, newBitmap);
            return;
        }
        validateBitmapFormat(data);
        validate2DRange(xoff, yoff, data.getWidth(), data.getHeight());
        mRS.nAllocationData2D(getIDSafe(), xoff, yoff, mSelectedLOD, mSelectedFace.mID, data);
        Trace.traceEnd(RenderScript.TRACE_TAG);
    }

    private void validate3DRange(int xoff, int yoff, int zoff, int w, int h, int d) {
        if (mAdaptedAllocation != null) {

        } else {

            if (xoff < 0 || yoff < 0 || zoff < 0) {
                throw new RSIllegalArgumentException("Offset cannot be negative.");
            }
            if (h < 0 || w < 0 || d < 0) {
                throw new RSIllegalArgumentException("Height or width cannot be negative.");
            }
            if (((xoff + w) > mCurrentDimX) || ((yoff + h) > mCurrentDimY) || ((zoff + d) > mCurrentDimZ)) {
                throw new RSIllegalArgumentException("Updated region larger than allocation.");
            }
        }
    }

    /**
     * @hide
     *
     */
    private void copy3DRangeFromUnchecked(int xoff, int yoff, int zoff, int w, int h, int d,
                                          Object array, Element.DataType dt, int arrayLen) {
        Trace.traceBegin(RenderScript.TRACE_TAG, "copy3DRangeFromUnchecked");
        mRS.validate();
        validate3DRange(xoff, yoff, zoff, w, h, d);
        mRS.nAllocationData3D(getIDSafe(), xoff, yoff, zoff, mSelectedLOD, w, h, d,
                              array, arrayLen * dt.mSize, dt);
        Trace.traceEnd(RenderScript.TRACE_TAG);
    }

    /**
     * @hide
     * Copy a rectangular region from the array into the allocation.
     * The array is assumed to be tightly packed.
     *
     * @param xoff X offset of the region to update in this Allocation
     * @param yoff Y offset of the region to update in this Allocation
     * @param zoff Z offset of the region to update in this Allocation
     * @param w Width of the region to update
     * @param h Height of the region to update
     * @param d Depth of the region to update
     * @param data to be placed into the allocation
     */
    public void copy3DRangeFrom(int xoff, int yoff, int zoff, int w, int h, int d, Object array) {
        Trace.traceBegin(RenderScript.TRACE_TAG, "copy3DRangeFrom");
        copy3DRangeFromUnchecked(xoff, yoff, zoff, w, h, d, array,
                                 validateObjectIsPrimitiveArray(array, true),
                                 java.lang.reflect.Array.getLength(array));
        Trace.traceEnd(RenderScript.TRACE_TAG);
    }

    /**
     * @hide
     * Copy a rectangular region into the allocation from another
     * allocation.
     *
     * @param xoff X offset of the region to update in this Allocation
     * @param yoff Y offset of the region to update in this Allocation
     * @param zoff Z offset of the region to update in this Allocation
     * @param w Width of the region to update.
     * @param h Height of the region to update.
     * @param d Depth of the region to update.
     * @param data source allocation.
     * @param dataXoff X offset of the region in the source Allocation
     * @param dataYoff Y offset of the region in the source Allocation
     * @param dataZoff Z offset of the region in the source Allocation
     */
    public void copy3DRangeFrom(int xoff, int yoff, int zoff, int w, int h, int d,
                                Allocation data, int dataXoff, int dataYoff, int dataZoff) {
        mRS.validate();
        validate3DRange(xoff, yoff, zoff, w, h, d);
        mRS.nAllocationData3D(getIDSafe(), xoff, yoff, zoff, mSelectedLOD,
                              w, h, d, data.getID(mRS), dataXoff, dataYoff, dataZoff,
                              data.mSelectedLOD);
    }


    /**
     * Copy from the Allocation into a {@link android.graphics.Bitmap}.  The
     * bitmap must match the dimensions of the Allocation.
     *
     * @param b The bitmap to be set from the Allocation.
     */
    public void copyTo(Bitmap b) {
        Trace.traceBegin(RenderScript.TRACE_TAG, "copyTo");
        mRS.validate();
        validateBitmapFormat(b);
        validateBitmapSize(b);
        mRS.nAllocationCopyToBitmap(getID(mRS), b);
        Trace.traceEnd(RenderScript.TRACE_TAG);
    }

    private void copyTo(Object array, Element.DataType dt, int arrayLen) {
        Trace.traceBegin(RenderScript.TRACE_TAG, "copyTo");
        if (dt.mSize * arrayLen < mSize) {
            throw new RSIllegalArgumentException(
                "Size of output array cannot be smaller than size of allocation.");
        }
        mRS.validate();
        mRS.nAllocationRead(getID(mRS), array, dt);
        Trace.traceEnd(RenderScript.TRACE_TAG);
    }

    /**
     * Copy from the Allocation into an array.  The array must be at
     * least as large as the Allocation.  The
     * {@link android.renderscript.Element} must match the component
     * type of the array passed in.
     *
     * @param array The array to be set from the Allocation.
     */
    public void copyTo(Object array) {
        copyTo(array, validateObjectIsPrimitiveArray(array, true),
               java.lang.reflect.Array.getLength(array));
    }

    /**
     * Copy from the Allocation into a byte array.  The array must be at least
     * as large as the Allocation.  The allocation must be of an 8 bit integer
     * {@link android.renderscript.Element} type.
     *
     * @param d The array to be set from the Allocation.
     */
    public void copyTo(byte[] d) {
        validateIsInt8();
        copyTo(d, Element.DataType.SIGNED_8, d.length);
    }

    /**
     * Copy from the Allocation into a short array.  The array must be at least
     * as large as the Allocation.  The allocation must be of an 16 bit integer
     * {@link android.renderscript.Element} type.
     *
     * @param d The array to be set from the Allocation.
     */
    public void copyTo(short[] d) {
        validateIsInt16();
        copyTo(d, Element.DataType.SIGNED_16, d.length);
    }

    /**
     * Copy from the Allocation into a int array.  The array must be at least as
     * large as the Allocation.  The allocation must be of an 32 bit integer
     * {@link android.renderscript.Element} type.
     *
     * @param d The array to be set from the Allocation.
     */
    public void copyTo(int[] d) {
        validateIsInt32();
        copyTo(d, Element.DataType.SIGNED_32, d.length);
    }

    /**
     * Copy from the Allocation into a float array.  The array must be at least
     * as large as the Allocation.  The allocation must be of an 32 bit float
     * {@link android.renderscript.Element} type.
     *
     * @param d The array to be set from the Allocation.
     */
    public void copyTo(float[] d) {
        validateIsFloat32();
        copyTo(d, Element.DataType.FLOAT_32, d.length);
    }

    /**
     * Resize a 1D allocation.  The contents of the allocation are preserved.
     * If new elements are allocated objects are created with null contents and
     * the new region is otherwise undefined.
     *
     * <p>If the new region is smaller the references of any objects outside the
     * new region will be released.</p>
     *
     * <p>A new type will be created with the new dimension.</p>
     *
     * @param dimX The new size of the allocation.
     *
     * @deprecated RenderScript objects should be immutable once created.  The
     * replacement is to create a new allocation and copy the contents. This
     * function will throw an exception if API 21 or higher is used.
     */
    public synchronized void resize(int dimX) {
        if (mRS.getApplicationContext().getApplicationInfo().targetSdkVersion >= 21) {
            throw new RSRuntimeException("Resize is not allowed in API 21+.");
        }
        if ((mType.getY() > 0)|| (mType.getZ() > 0) || mType.hasFaces() || mType.hasMipmaps()) {
            throw new RSInvalidStateException("Resize only support for 1D allocations at this time.");
        }
        mRS.nAllocationResize1D(getID(mRS), dimX);
        mRS.finish();  // Necessary because resize is fifoed and update is async.

        long typeID = mRS.nAllocationGetType(getID(mRS));
        mType = new Type(typeID, mRS);
        mType.updateFromNative();
        updateCacheInfo(mType);
    }


    // creation

    static BitmapFactory.Options mBitmapOptions = new BitmapFactory.Options();
    static {
        mBitmapOptions.inScaled = false;
    }

    /**
     * Creates a new Allocation with the given {@link
     * android.renderscript.Type}, mipmap flag, and usage flags.
     *
     * @param type RenderScript type describing data layout
     * @param mips specifies desired mipmap behaviour for the
     *             allocation
     * @param usage bit field specifying how the Allocation is
     *              utilized
     */
    static public Allocation createTyped(RenderScript rs, Type type, MipmapControl mips, int usage) {
        Trace.traceBegin(RenderScript.TRACE_TAG, "createTyped");
        rs.validate();
        if (type.getID(rs) == 0) {
            throw new RSInvalidStateException("Bad Type");
        }
        long id = rs.nAllocationCreateTyped(type.getID(rs), mips.mID, usage, 0);
        if (id == 0) {
            throw new RSRuntimeException("Allocation creation failed.");
        }
        Trace.traceEnd(RenderScript.TRACE_TAG);
        return new Allocation(id, rs, type, usage);
    }

    /**
     * Creates an Allocation with the size specified by the type and no mipmaps
     * generated by default
     *
     * @param rs Context to which the allocation will belong.
     * @param type renderscript type describing data layout
     * @param usage bit field specifying how the allocation is
     *              utilized
     *
     * @return allocation
     */
    static public Allocation createTyped(RenderScript rs, Type type, int usage) {
        return createTyped(rs, type, MipmapControl.MIPMAP_NONE, usage);
    }

    /**
     * Creates an Allocation for use by scripts with a given {@link
     * android.renderscript.Type} and no mipmaps
     *
     * @param rs Context to which the Allocation will belong.
     * @param type RenderScript Type describing data layout
     *
     * @return allocation
     */
    static public Allocation createTyped(RenderScript rs, Type type) {
        return createTyped(rs, type, MipmapControl.MIPMAP_NONE, USAGE_SCRIPT);
    }

    /**
     * Creates an Allocation with a specified number of given elements
     *
     * @param rs Context to which the Allocation will belong.
     * @param e Element to use in the Allocation
     * @param count the number of Elements in the Allocation
     * @param usage bit field specifying how the Allocation is
     *              utilized
     *
     * @return allocation
     */
    static public Allocation createSized(RenderScript rs, Element e,
                                         int count, int usage) {
        Trace.traceBegin(RenderScript.TRACE_TAG, "createSized");
        rs.validate();
        Type.Builder b = new Type.Builder(rs, e);
        b.setX(count);
        Type t = b.create();

        long id = rs.nAllocationCreateTyped(t.getID(rs), MipmapControl.MIPMAP_NONE.mID, usage, 0);
        if (id == 0) {
            throw new RSRuntimeException("Allocation creation failed.");
        }
        Trace.traceEnd(RenderScript.TRACE_TAG);
        return new Allocation(id, rs, t, usage);
    }

    /**
     * Creates an Allocation with a specified number of given elements
     *
     * @param rs Context to which the Allocation will belong.
     * @param e Element to use in the Allocation
     * @param count the number of Elements in the Allocation
     *
     * @return allocation
     */
    static public Allocation createSized(RenderScript rs, Element e, int count) {
        return createSized(rs, e, count, USAGE_SCRIPT);
    }

    static Element elementFromBitmap(RenderScript rs, Bitmap b) {
        final Bitmap.Config bc = b.getConfig();
        if (bc == Bitmap.Config.ALPHA_8) {
            return Element.A_8(rs);
        }
        if (bc == Bitmap.Config.ARGB_4444) {
            return Element.RGBA_4444(rs);
        }
        if (bc == Bitmap.Config.ARGB_8888) {
            return Element.RGBA_8888(rs);
        }
        if (bc == Bitmap.Config.RGB_565) {
            return Element.RGB_565(rs);
        }
        throw new RSInvalidStateException("Bad bitmap type: " + bc);
    }

    static Type typeFromBitmap(RenderScript rs, Bitmap b,
                                       MipmapControl mip) {
        Element e = elementFromBitmap(rs, b);
        Type.Builder tb = new Type.Builder(rs, e);
        tb.setX(b.getWidth());
        tb.setY(b.getHeight());
        tb.setMipmaps(mip == MipmapControl.MIPMAP_FULL);
        return tb.create();
    }

    /**
     * Creates an Allocation from a {@link android.graphics.Bitmap}.
     *
     * @param rs Context to which the allocation will belong.
     * @param b Bitmap source for the allocation data
     * @param mips specifies desired mipmap behaviour for the
     *             allocation
     * @param usage bit field specifying how the allocation is
     *              utilized
     *
     * @return Allocation containing bitmap data
     *
     */
    static public Allocation createFromBitmap(RenderScript rs, Bitmap b,
                                              MipmapControl mips,
                                              int usage) {
        Trace.traceBegin(RenderScript.TRACE_TAG, "createFromBitmap");
        rs.validate();

        // WAR undocumented color formats
        if (b.getConfig() == null) {
            if ((usage & USAGE_SHARED) != 0) {
                throw new RSIllegalArgumentException("USAGE_SHARED cannot be used with a Bitmap that has a null config.");
            }
            Bitmap newBitmap = Bitmap.createBitmap(b.getWidth(), b.getHeight(), Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(newBitmap);
            c.drawBitmap(b, 0, 0, null);
            return createFromBitmap(rs, newBitmap, mips, usage);
        }

        Type t = typeFromBitmap(rs, b, mips);

        // enable optimized bitmap path only with no mipmap and script-only usage
        if (mips == MipmapControl.MIPMAP_NONE &&
            t.getElement().isCompatible(Element.RGBA_8888(rs)) &&
            usage == (USAGE_SHARED | USAGE_SCRIPT | USAGE_GRAPHICS_TEXTURE)) {
            long id = rs.nAllocationCreateBitmapBackedAllocation(t.getID(rs), mips.mID, b, usage);
            if (id == 0) {
                throw new RSRuntimeException("Load failed.");
            }

            // keep a reference to the Bitmap around to prevent GC
            Allocation alloc = new Allocation(id, rs, t, usage);
            alloc.setBitmap(b);
            return alloc;
        }


        long id = rs.nAllocationCreateFromBitmap(t.getID(rs), mips.mID, b, usage);
        if (id == 0) {
            throw new RSRuntimeException("Load failed.");
        }
        Trace.traceEnd(RenderScript.TRACE_TAG);
        return new Allocation(id, rs, t, usage);
    }

    /**
     * Returns the handle to a raw buffer that is being managed by the screen
     * compositor. This operation is only valid for Allocations with {@link
     * #USAGE_IO_INPUT}.
     *
     * @return Surface object associated with allocation
     *
     */
    public Surface getSurface() {
        if ((mUsage & USAGE_IO_INPUT) == 0) {
            throw new RSInvalidStateException("Allocation is not a surface texture.");
        }
        return mRS.nAllocationGetSurface(getID(mRS));
    }

    /**
     * Associate a {@link android.view.Surface} with this Allocation. This
     * operation is only valid for Allocations with {@link #USAGE_IO_OUTPUT}.
     *
     * @param sur Surface to associate with allocation
     */
    public void setSurface(Surface sur) {
        mRS.validate();
        if ((mUsage & USAGE_IO_OUTPUT) == 0) {
            throw new RSInvalidStateException("Allocation is not USAGE_IO_OUTPUT.");
        }

        mRS.nAllocationSetSurface(getID(mRS), sur);
    }

    /**
     * Creates an Allocation from a {@link android.graphics.Bitmap}.
     *
     * <p>With target API version 18 or greater, this Allocation will be created
     * with {@link #USAGE_SHARED}, {@link #USAGE_SCRIPT}, and {@link
     * #USAGE_GRAPHICS_TEXTURE}. With target API version 17 or lower, this
     * Allocation will be created with {@link #USAGE_GRAPHICS_TEXTURE}.</p>
     *
     * @param rs Context to which the allocation will belong.
     * @param b bitmap source for the allocation data
     *
     * @return Allocation containing bitmap data
     *
     */
    static public Allocation createFromBitmap(RenderScript rs, Bitmap b) {
        if (rs.getApplicationContext().getApplicationInfo().targetSdkVersion >= 18) {
            return createFromBitmap(rs, b, MipmapControl.MIPMAP_NONE,
                                    USAGE_SHARED | USAGE_SCRIPT | USAGE_GRAPHICS_TEXTURE);
        }
        return createFromBitmap(rs, b, MipmapControl.MIPMAP_NONE,
                                USAGE_GRAPHICS_TEXTURE);
    }

    /**
     * Creates a cubemap Allocation from a {@link android.graphics.Bitmap}
     * containing the horizontal list of cube faces. Each face must be a square,
     * have the same size as all other faces, and have a width that is a power
     * of 2.
     *
     * @param rs Context to which the allocation will belong.
     * @param b Bitmap with cubemap faces layed out in the following
     *          format: right, left, top, bottom, front, back
     * @param mips specifies desired mipmap behaviour for the cubemap
     * @param usage bit field specifying how the cubemap is utilized
     *
     * @return allocation containing cubemap data
     *
     */
    static public Allocation createCubemapFromBitmap(RenderScript rs, Bitmap b,
                                                     MipmapControl mips,
                                                     int usage) {
        rs.validate();

        int height = b.getHeight();
        int width = b.getWidth();

        if (width % 6 != 0) {
            throw new RSIllegalArgumentException("Cubemap height must be multiple of 6");
        }
        if (width / 6 != height) {
            throw new RSIllegalArgumentException("Only square cube map faces supported");
        }
        boolean isPow2 = (height & (height - 1)) == 0;
        if (!isPow2) {
            throw new RSIllegalArgumentException("Only power of 2 cube faces supported");
        }

        Element e = elementFromBitmap(rs, b);
        Type.Builder tb = new Type.Builder(rs, e);
        tb.setX(height);
        tb.setY(height);
        tb.setFaces(true);
        tb.setMipmaps(mips == MipmapControl.MIPMAP_FULL);
        Type t = tb.create();

        long id = rs.nAllocationCubeCreateFromBitmap(t.getID(rs), mips.mID, b, usage);
        if(id == 0) {
            throw new RSRuntimeException("Load failed for bitmap " + b + " element " + e);
        }
        return new Allocation(id, rs, t, usage);
    }

    /**
     * Creates a non-mipmapped cubemap Allocation for use as a graphics texture
     * from a {@link android.graphics.Bitmap} containing the horizontal list of
     * cube faces. Each face must be a square, have the same size as all other
     * faces, and have a width that is a power of 2.
     *
     * @param rs Context to which the allocation will belong.
     * @param b bitmap with cubemap faces layed out in the following
     *          format: right, left, top, bottom, front, back
     *
     * @return allocation containing cubemap data
     *
     */
    static public Allocation createCubemapFromBitmap(RenderScript rs,
                                                     Bitmap b) {
        return createCubemapFromBitmap(rs, b, MipmapControl.MIPMAP_NONE,
                                       USAGE_GRAPHICS_TEXTURE);
    }

    /**
     * Creates a cubemap Allocation from 6 {@link android.graphics.Bitmap}
     * objects containing the cube faces. Each face must be a square, have the
     * same size as all other faces, and have a width that is a power of 2.
     *
     * @param rs Context to which the allocation will belong.
     * @param xpos cubemap face in the positive x direction
     * @param xneg cubemap face in the negative x direction
     * @param ypos cubemap face in the positive y direction
     * @param yneg cubemap face in the negative y direction
     * @param zpos cubemap face in the positive z direction
     * @param zneg cubemap face in the negative z direction
     * @param mips specifies desired mipmap behaviour for the cubemap
     * @param usage bit field specifying how the cubemap is utilized
     *
     * @return allocation containing cubemap data
     *
     */
    static public Allocation createCubemapFromCubeFaces(RenderScript rs,
                                                        Bitmap xpos,
                                                        Bitmap xneg,
                                                        Bitmap ypos,
                                                        Bitmap yneg,
                                                        Bitmap zpos,
                                                        Bitmap zneg,
                                                        MipmapControl mips,
                                                        int usage) {
        int height = xpos.getHeight();
        if (xpos.getWidth() != height ||
            xneg.getWidth() != height || xneg.getHeight() != height ||
            ypos.getWidth() != height || ypos.getHeight() != height ||
            yneg.getWidth() != height || yneg.getHeight() != height ||
            zpos.getWidth() != height || zpos.getHeight() != height ||
            zneg.getWidth() != height || zneg.getHeight() != height) {
            throw new RSIllegalArgumentException("Only square cube map faces supported");
        }
        boolean isPow2 = (height & (height - 1)) == 0;
        if (!isPow2) {
            throw new RSIllegalArgumentException("Only power of 2 cube faces supported");
        }

        Element e = elementFromBitmap(rs, xpos);
        Type.Builder tb = new Type.Builder(rs, e);
        tb.setX(height);
        tb.setY(height);
        tb.setFaces(true);
        tb.setMipmaps(mips == MipmapControl.MIPMAP_FULL);
        Type t = tb.create();
        Allocation cubemap = Allocation.createTyped(rs, t, mips, usage);

        AllocationAdapter adapter = AllocationAdapter.create2D(rs, cubemap);
        adapter.setFace(Type.CubemapFace.POSITIVE_X);
        adapter.copyFrom(xpos);
        adapter.setFace(Type.CubemapFace.NEGATIVE_X);
        adapter.copyFrom(xneg);
        adapter.setFace(Type.CubemapFace.POSITIVE_Y);
        adapter.copyFrom(ypos);
        adapter.setFace(Type.CubemapFace.NEGATIVE_Y);
        adapter.copyFrom(yneg);
        adapter.setFace(Type.CubemapFace.POSITIVE_Z);
        adapter.copyFrom(zpos);
        adapter.setFace(Type.CubemapFace.NEGATIVE_Z);
        adapter.copyFrom(zneg);

        return cubemap;
    }

    /**
     * Creates a non-mipmapped cubemap Allocation for use as a sampler input
     * from 6 {@link android.graphics.Bitmap} objects containing the cube
     * faces. Each face must be a square, have the same size as all other faces,
     * and have a width that is a power of 2.
     *
     * @param rs Context to which the allocation will belong.
     * @param xpos cubemap face in the positive x direction
     * @param xneg cubemap face in the negative x direction
     * @param ypos cubemap face in the positive y direction
     * @param yneg cubemap face in the negative y direction
     * @param zpos cubemap face in the positive z direction
     * @param zneg cubemap face in the negative z direction
     *
     * @return allocation containing cubemap data
     *
     */
    static public Allocation createCubemapFromCubeFaces(RenderScript rs,
                                                        Bitmap xpos,
                                                        Bitmap xneg,
                                                        Bitmap ypos,
                                                        Bitmap yneg,
                                                        Bitmap zpos,
                                                        Bitmap zneg) {
        return createCubemapFromCubeFaces(rs, xpos, xneg, ypos, yneg,
                                          zpos, zneg, MipmapControl.MIPMAP_NONE,
                                          USAGE_GRAPHICS_TEXTURE);
    }

    /**
     * Creates an Allocation from the Bitmap referenced
     * by resource ID.
     *
     * @param rs Context to which the allocation will belong.
     * @param res application resources
     * @param id resource id to load the data from
     * @param mips specifies desired mipmap behaviour for the
     *             allocation
     * @param usage bit field specifying how the allocation is
     *              utilized
     *
     * @return Allocation containing resource data
     *
     */
    static public Allocation createFromBitmapResource(RenderScript rs,
                                                      Resources res,
                                                      int id,
                                                      MipmapControl mips,
                                                      int usage) {

        rs.validate();
        if ((usage & (USAGE_SHARED | USAGE_IO_INPUT | USAGE_IO_OUTPUT)) != 0) {
            throw new RSIllegalArgumentException("Unsupported usage specified.");
        }
        Bitmap b = BitmapFactory.decodeResource(res, id);
        Allocation alloc = createFromBitmap(rs, b, mips, usage);
        b.recycle();
        return alloc;
    }

    /**
     * Creates a non-mipmapped Allocation to use as a graphics texture from the
     * {@link android.graphics.Bitmap} referenced by resource ID.
     *
     * <p>With target API version 18 or greater, this allocation will be created
     * with {@link #USAGE_SCRIPT} and {@link #USAGE_GRAPHICS_TEXTURE}. With
     * target API version 17 or lower, this allocation will be created with
     * {@link #USAGE_GRAPHICS_TEXTURE}.</p>
     *
     * @param rs Context to which the allocation will belong.
     * @param res application resources
     * @param id resource id to load the data from
     *
     * @return Allocation containing resource data
     *
     */
    static public Allocation createFromBitmapResource(RenderScript rs,
                                                      Resources res,
                                                      int id) {
        if (rs.getApplicationContext().getApplicationInfo().targetSdkVersion >= 18) {
            return createFromBitmapResource(rs, res, id,
                                            MipmapControl.MIPMAP_NONE,
                                            USAGE_SCRIPT | USAGE_GRAPHICS_TEXTURE);
        }
        return createFromBitmapResource(rs, res, id,
                                        MipmapControl.MIPMAP_NONE,
                                        USAGE_GRAPHICS_TEXTURE);
    }

    /**
     * Creates an Allocation containing string data encoded in UTF-8 format.
     *
     * @param rs Context to which the allocation will belong.
     * @param str string to create the allocation from
     * @param usage bit field specifying how the allocaiton is
     *              utilized
     *
     */
    static public Allocation createFromString(RenderScript rs,
                                              String str,
                                              int usage) {
        rs.validate();
        byte[] allocArray = null;
        try {
            allocArray = str.getBytes("UTF-8");
            Allocation alloc = Allocation.createSized(rs, Element.U8(rs), allocArray.length, usage);
            alloc.copyFrom(allocArray);
            return alloc;
        }
        catch (Exception e) {
            throw new RSRuntimeException("Could not convert string to utf-8.");
        }
    }

    /**
     * Interface to handle notification when new buffers are available via
     * {@link #USAGE_IO_INPUT}. An application will receive one notification
     * when a buffer is available. Additional buffers will not trigger new
     * notifications until a buffer is processed.
     */
    public interface OnBufferAvailableListener {
        public void onBufferAvailable(Allocation a);
    }

    /**
     * Set a notification handler for {@link #USAGE_IO_INPUT}.
     *
     * @param callback instance of the OnBufferAvailableListener
     *                 class to be called when buffer arrive.
     */
    public void setOnBufferAvailableListener(OnBufferAvailableListener callback) {
        synchronized(mAllocationMap) {
            mAllocationMap.put(new Long(getID(mRS)), this);
            mBufferNotifier = callback;
        }
    }

    static void sendBufferNotification(long id) {
        synchronized(mAllocationMap) {
            Allocation a = mAllocationMap.get(new Long(id));

            if ((a != null) && (a.mBufferNotifier != null)) {
                a.mBufferNotifier.onBufferAvailable(a);
            }
        }
    }

}
