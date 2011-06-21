/*
 * Copyright (C) 2008 The Android Open Source Project
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

import android.content.res.Resources;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;
import android.util.TypedValue;

/**
 *
 **/
public class AllocationAdapter extends Allocation {
    private boolean mConstrainedLOD;
    private boolean mConstrainedFace;
    private boolean mConstrainedY;
    private boolean mConstrainedZ;

    private int mSelectedDimX;
    private int mSelectedDimY;
    private int mSelectedDimZ;
    private int mSelectedCount;
    private Allocation mAlloc;

    private int mSelectedLOD = 0;
    private Type.CubemapFace mSelectedFace = Type.CubemapFace.POSITIVE_X;

    AllocationAdapter(int id, RenderScript rs, Allocation alloc) {
        super(id, rs, null, alloc.mUsage);
        mAlloc = alloc;
    }


    int getID() {
        return mAlloc.getID();
    }

    public void copyFrom(BaseObj[] d) {
        mRS.validate();
        if (d.length != mSelectedCount) {
            throw new RSIllegalArgumentException("Array size mismatch, allocation size = " +
                                                 mSelectedCount + ", array length = " + d.length);
        }
        int i[] = new int[d.length];
        for (int ct=0; ct < d.length; ct++) {
            i[ct] = d[ct].getID();
        }
        subData1D(0, mAlloc.mType.getCount(), i);
    }

    void validateBitmap(Bitmap b) {
        mRS.validate();
        if(mSelectedDimX != b.getWidth() ||
           mSelectedDimY != b.getHeight()) {
            throw new RSIllegalArgumentException("Cannot update allocation from bitmap, sizes mismatch");
        }
    }

    public void copyFrom(int[] d) {
        mRS.validate();
        subData1D(0, mSelectedCount, d);
    }
    public void copyFrom(short[] d) {
        mRS.validate();
        subData1D(0, mSelectedCount, d);
    }
    public void copyFrom(byte[] d) {
        mRS.validate();
        subData1D(0, mSelectedCount, d);
    }
    public void copyFrom(float[] d) {
        mRS.validate();
        subData1D(0, mSelectedCount, d);
    }
    public void copyFrom(Bitmap b) {
        validateBitmap(b);
        mRS.nAllocationCopyFromBitmap(getID(), b);
    }

    public void copyTo(Bitmap b) {
        validateBitmap(b);
        mRS.nAllocationCopyToBitmap(getID(), b);
    }


    public void subData(int xoff, FieldPacker fp) {
        int eSize = mAlloc.mType.mElement.getSizeBytes();
        final byte[] data = fp.getData();

        int count = data.length / eSize;
        if ((eSize * count) != data.length) {
            throw new RSIllegalArgumentException("Field packer length " + data.length +
                                               " not divisible by element size " + eSize + ".");
        }
        data1DChecks(xoff, count, data.length, data.length);
        mRS.nAllocationData1D(getID(), xoff, mSelectedLOD, count, data, data.length);
    }


    public void subElementData(int xoff, int component_number, FieldPacker fp) {
        if (component_number >= mAlloc.mType.mElement.mElements.length) {
            throw new RSIllegalArgumentException("Component_number " + component_number + " out of range.");
        }
        if(xoff < 0) {
            throw new RSIllegalArgumentException("Offset must be >= 0.");
        }

        final byte[] data = fp.getData();
        int eSize = mAlloc.mType.mElement.mElements[component_number].getSizeBytes();

        if (data.length != eSize) {
            throw new RSIllegalArgumentException("Field packer sizelength " + data.length +
                                               " does not match component size " + eSize + ".");
        }

        mRS.nAllocationElementData1D(getID(), xoff, mSelectedLOD, component_number, data, data.length);
    }

    void data1DChecks(int off, int count, int len, int dataSize) {
        mRS.validate();
        if(off < 0) {
            throw new RSIllegalArgumentException("Offset must be >= 0.");
        }
        if(count < 1) {
            throw new RSIllegalArgumentException("Count must be >= 1.");
        }
        if((off + count) > mSelectedCount) {
            throw new RSIllegalArgumentException("Overflow, Available count " + mAlloc.mType.getCount() +
                                               ", got " + count + " at offset " + off + ".");
        }
        if((len) < dataSize) {
            throw new RSIllegalArgumentException("Array too small for allocation type.  len = " +
                                                 len + ", dataSize = " + dataSize);
        }
    }

    public void subData1D(int off, int count, int[] d) {
        int dataSize = mAlloc.mType.mElement.getSizeBytes() * count;
        data1DChecks(off, count, d.length * 4, dataSize);
        mRS.nAllocationData1D(getID(), off, mSelectedLOD, count, d, dataSize);
    }
    public void subData1D(int off, int count, short[] d) {
        int dataSize = mAlloc.mType.mElement.getSizeBytes() * count;
        data1DChecks(off, count, d.length * 2, dataSize);
        mRS.nAllocationData1D(getID(), off, mSelectedLOD, count, d, dataSize);
    }
    public void subData1D(int off, int count, byte[] d) {
        int dataSize = mAlloc.mType.mElement.getSizeBytes() * count;
        data1DChecks(off, count, d.length, dataSize);
        mRS.nAllocationData1D(getID(), off, mSelectedLOD, count, d, dataSize);
    }
    public void subData1D(int off, int count, float[] d) {
        int dataSize = mAlloc.mType.mElement.getSizeBytes() * count;
        data1DChecks(off, count, d.length * 4, dataSize);
        mRS.nAllocationData1D(getID(), off, mSelectedLOD, count, d, dataSize);
    }

    /**
     * Copy part of an allocation from another allocation.
     *
     * @param off The offset of the first element to be copied.
     * @param count The number of elements to be copied.
     * @param data the source data allocation.
     * @param dataOff off The offset of the first element in data to
     *          be copied.
     */
    public void subData1D(int off, int count, AllocationAdapter data, int dataOff) {
        mRS.nAllocationData2D(getID(), off, 0,
                              mSelectedLOD, mSelectedFace.mID,
                              count, 1, data.getID(), dataOff, 0,
                              data.mSelectedLOD, data.mSelectedFace.mID);
    }


    public void subData2D(int xoff, int yoff, int w, int h, int[] d) {
        mRS.validate();
        mRS.nAllocationData2D(getID(), xoff, yoff, mSelectedLOD, mSelectedFace.mID,
                              w, h, d, d.length * 4);
    }

    public void subData2D(int xoff, int yoff, int w, int h, float[] d) {
        mRS.validate();
        mRS.nAllocationData2D(getID(), xoff, yoff, mSelectedLOD, mSelectedFace.mID,
                              w, h, d, d.length * 4);
    }

    /**
     * Copy a rectangular region into the allocation from another
     * allocation.
     *
     * @param xoff X offset of the region to update.
     * @param yoff Y offset of the region to update.
     * @param w Width of the incoming region to update.
     * @param h Height of the incoming region to update.
     * @param data source allocation.
     * @param dataXoff X offset in data of the region to update.
     * @param dataYoff Y offset in data of the region to update.
     */
    public void subData2D(int xoff, int yoff, int w, int h,
                          AllocationAdapter data, int dataXoff, int dataYoff) {
        mRS.validate();
        mRS.nAllocationData2D(getID(), xoff, yoff,
                              mSelectedLOD, mSelectedFace.mID,
                              w, h, data.getID(), dataXoff, dataYoff,
                              data.mSelectedLOD, data.mSelectedFace.mID);
    }

    public void readData(int[] d) {
        mRS.validate();
        mRS.nAllocationRead(getID(), d);
    }

    public void readData(float[] d) {
        mRS.validate();
        mRS.nAllocationRead(getID(), d);
    }

    private void initLOD(int lod) {
        if (lod < 0) {
            throw new RSIllegalArgumentException("Attempting to set negative lod (" + lod + ").");
        }

        int tx = mAlloc.mType.getX();
        int ty = mAlloc.mType.getY();
        int tz = mAlloc.mType.getZ();

        for (int ct=0; ct < lod; ct++) {
            if ((tx==1) && (ty == 1) && (tz == 1)) {
                throw new RSIllegalArgumentException("Attempting to set lod (" + lod + ") out of range.");
            }

            if (tx > 1) tx >>= 1;
            if (ty > 1) ty >>= 1;
            if (tz > 1) tz >>= 1;
        }

        mSelectedDimX = tx;
        mSelectedDimY = ty;
        mSelectedCount = tx;
        if (ty > 1) {
            mSelectedCount *= ty;
        }
        if (tz > 1) {
            mSelectedCount *= tz;
        }
    }

    /**
     * Set the active LOD.  The LOD must be within the range for the
     * type being adapted.
     *
     * @param lod The LOD to make active.
     */
    public void setLOD(int lod) {
        if (!mAlloc.getType().hasMipmaps()) {
            throw new RSInvalidStateException("Cannot set LOD when the allocation type does not include mipmaps.");
        }
        if (!mConstrainedLOD) {
            throw new RSInvalidStateException("Cannot set LOD when the adapter includes mipmaps.");
        }

        initLOD(lod);
    }

    public void setFace(Type.CubemapFace cf) {
        mSelectedFace = cf;
    }

    public void setY(int y) {
        mSelectedDimY = y;
    }

    public void setZ(int z) {
    }

    // creation
    //static public AllocationAdapter create1D(RenderScript rs, Allocation a) {
    //}

    static public AllocationAdapter create2D(RenderScript rs, Allocation a) {
        rs.validate();
        AllocationAdapter aa = new AllocationAdapter(0, rs, a);
        aa.mConstrainedLOD = true;
        aa.mConstrainedFace = true;
        aa.mConstrainedY = false;
        aa.mConstrainedZ = true;
        aa.initLOD(0);
        return aa;
    }


}


