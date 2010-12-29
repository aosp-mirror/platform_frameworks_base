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
 * @hide
 *
 **/
public class AllocationAdapter extends Allocation {
    private int mSelectedDimX;
    private int mSelectedDimY;
    private int mSelectedCount;
    private Allocation mAlloc;

    private int mSelectedLOD = 0;
    private Type.CubemapFace mSelectedFace = Type.CubemapFace.POSITVE_X;;

    AllocationAdapter(int id, RenderScript rs, Allocation alloc) {
        super(id, rs, null, alloc.mUsage);
        Type t = alloc.getType();
        mSelectedDimX = t.getX();
        mSelectedDimY = t.getY();
        mSelectedCount = t.getCount();
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
        subData1D(0, mType.getCount(), i);
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
        int eSize = mType.mElement.getSizeBytes();
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
        if (component_number >= mType.mElement.mElements.length) {
            throw new RSIllegalArgumentException("Component_number " + component_number + " out of range.");
        }
        if(xoff < 0) {
            throw new RSIllegalArgumentException("Offset must be >= 0.");
        }

        final byte[] data = fp.getData();
        int eSize = mType.mElement.mElements[component_number].getSizeBytes();

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
        if((off + count) > mSelectedDimX * mSelectedDimY) {
            throw new RSIllegalArgumentException("Overflow, Available count " + mType.getCount() +
                                               ", got " + count + " at offset " + off + ".");
        }
        if((len) < dataSize) {
            throw new RSIllegalArgumentException("Array too small for allocation type.");
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


    public void subData2D(int xoff, int yoff, int w, int h, int[] d) {
        mRS.validate();
        mRS.nAllocationData2D(getID(), xoff, yoff, mSelectedLOD, mSelectedFace.mID, w, h, d, d.length * 4);
    }

    public void subData2D(int xoff, int yoff, int w, int h, float[] d) {
        mRS.validate();
        mRS.nAllocationData2D(getID(), xoff, yoff, mSelectedLOD, mSelectedFace.mID, w, h, d, d.length * 4);
    }

    public void readData(int[] d) {
        mRS.validate();
        mRS.nAllocationRead(getID(), d);
    }

    public void readData(float[] d) {
        mRS.validate();
        mRS.nAllocationRead(getID(), d);
    }

    public void setLOD(int lod) {
    }

    public void setFace(Type.CubemapFace cf) {
    }

    public void setY(int y) {
    }

    public void setZ(int z) {
    }

    // creation
    //static public AllocationAdapter create1D(RenderScript rs, Allocation a) {
    //}

    static public AllocationAdapter create2D(RenderScript rs, Allocation a) {
        rs.validate();
        AllocationAdapter aa = new AllocationAdapter(0, rs, a);
        return aa;
    }


}


