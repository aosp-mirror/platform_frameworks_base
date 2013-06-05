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
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.TypedValue;

/**
 * Only intended for use by generated reflected code.
 *
 **/
public class AllocationAdapter extends Allocation {
    AllocationAdapter(int id, RenderScript rs, Allocation alloc) {
        super(id, rs, alloc.mType, alloc.mUsage);
        mAdaptedAllocation = alloc;
    }

    int getID(RenderScript rs) {
        throw new RSInvalidStateException(
            "This operation is not supported with adapters at this time.");
    }

    /**
     * @hide
     */
    public void subData(int xoff, FieldPacker fp) {
        super.setFromFieldPacker(xoff, fp);
    }
    /**
     * @hide
     */
    public void subElementData(int xoff, int component_number, FieldPacker fp) {
        super.setFromFieldPacker(xoff, component_number, fp);
    }
    /**
     * @hide
     */
    public void subData1D(int off, int count, int[] d) {
        super.copy1DRangeFrom(off, count, d);
    }
    /**
     * @hide
     */
    public void subData1D(int off, int count, short[] d) {
        super.copy1DRangeFrom(off, count, d);
    }
    /**
     * @hide
     */
    public void subData1D(int off, int count, byte[] d) {
        super.copy1DRangeFrom(off, count, d);
    }
    /**
     * @hide
     */
    public void subData1D(int off, int count, float[] d) {
        super.copy1DRangeFrom(off, count, d);
    }
    /**
     * @hide
     */
    public void subData2D(int xoff, int yoff, int w, int h, int[] d) {
        super.copy2DRangeFrom(xoff, yoff, w, h, d);
    }
    /**
     * @hide
     */
    public void subData2D(int xoff, int yoff, int w, int h, float[] d) {
        super.copy2DRangeFrom(xoff, yoff, w, h, d);
    }
    /**
     * @hide
     */
    public void readData(int[] d) {
        super.copyTo(d);
    }
    /**
     * @hide
     */
    public void readData(float[] d) {
        super.copyTo(d);
    }

    void initLOD(int lod) {
        if (lod < 0) {
            throw new RSIllegalArgumentException("Attempting to set negative lod (" + lod + ").");
        }

        int tx = mAdaptedAllocation.mType.getX();
        int ty = mAdaptedAllocation.mType.getY();
        int tz = mAdaptedAllocation.mType.getZ();

        for (int ct=0; ct < lod; ct++) {
            if ((tx==1) && (ty == 1) && (tz == 1)) {
                throw new RSIllegalArgumentException("Attempting to set lod (" + lod + ") out of range.");
            }

            if (tx > 1) tx >>= 1;
            if (ty > 1) ty >>= 1;
            if (tz > 1) tz >>= 1;
        }

        mCurrentDimX = tx;
        mCurrentDimY = ty;
        mCurrentDimZ = tz;
        mCurrentCount = mCurrentDimX;
        if (mCurrentDimY > 1) {
            mCurrentCount *= mCurrentDimY;
        }
        if (mCurrentDimZ > 1) {
            mCurrentCount *= mCurrentDimZ;
        }
        mSelectedY = 0;
        mSelectedZ = 0;
    }

    /**
     * Set the active LOD.  The LOD must be within the range for the
     * type being adapted.  The base allocation must have mipmaps.
     *
     * Because this changes the dimensions of the adapter the
     * current Y and Z will be reset.
     *
     * @param lod The LOD to make active.
     */
    public void setLOD(int lod) {
        if (!mAdaptedAllocation.getType().hasMipmaps()) {
            throw new RSInvalidStateException("Cannot set LOD when the allocation type does not include mipmaps.");
        }
        if (!mConstrainedLOD) {
            throw new RSInvalidStateException("Cannot set LOD when the adapter includes mipmaps.");
        }

        initLOD(lod);
    }

    /**
     * Set the active Face.  The base allocation must be of a type
     * that includes faces.
     *
     * @param cf The face to make active.
     */
    public void setFace(Type.CubemapFace cf) {
        if (!mAdaptedAllocation.getType().hasFaces()) {
            throw new RSInvalidStateException("Cannot set Face when the allocation type does not include faces.");
        }
        if (!mConstrainedFace) {
            throw new RSInvalidStateException("Cannot set LOD when the adapter includes mipmaps.");
        }
        if (cf == null) {
            throw new RSIllegalArgumentException("Cannot set null face.");
        }

        mSelectedFace = cf;
    }

    /**
     * Set the active Y.  The y value must be within the range for
     * the allocation being adapted.  The base allocation must
     * contain the Y dimension.
     *
     * @param y The y to make active.
     */
    public void setY(int y) {
        if (mAdaptedAllocation.getType().getY() == 0) {
            throw new RSInvalidStateException("Cannot set Y when the allocation type does not include Y dim.");
        }
        if (mAdaptedAllocation.getType().getY() <= y) {
            throw new RSInvalidStateException("Cannot set Y greater than dimension of allocation.");
        }
        if (!mConstrainedY) {
            throw new RSInvalidStateException("Cannot set Y when the adapter includes Y.");
        }

        mSelectedY = y;
    }

    /**
     * Set the active Z.  The z value must be within the range for
     * the allocation being adapted.  The base allocation must
     * contain the Z dimension.
     *
     * @param z The z to make active.
     */
    public void setZ(int z) {
        if (mAdaptedAllocation.getType().getZ() == 0) {
            throw new RSInvalidStateException("Cannot set Z when the allocation type does not include Z dim.");
        }
        if (mAdaptedAllocation.getType().getZ() <= z) {
            throw new RSInvalidStateException("Cannot set Z greater than dimension of allocation.");
        }
        if (!mConstrainedZ) {
            throw new RSInvalidStateException("Cannot set Z when the adapter includes Z.");
        }

        mSelectedZ = z;
    }

    static public AllocationAdapter create1D(RenderScript rs, Allocation a) {
        rs.validate();
        AllocationAdapter aa = new AllocationAdapter(0, rs, a);
        aa.mConstrainedLOD = true;
        aa.mConstrainedFace = true;
        aa.mConstrainedY = true;
        aa.mConstrainedZ = true;
        aa.initLOD(0);
        return aa;
    }

    static public AllocationAdapter create2D(RenderScript rs, Allocation a) {
        android.util.Log.e("rs", "create2d " + a);
        rs.validate();
        AllocationAdapter aa = new AllocationAdapter(0, rs, a);
        aa.mConstrainedLOD = true;
        aa.mConstrainedFace = true;
        aa.mConstrainedY = false;
        aa.mConstrainedZ = true;
        aa.initLOD(0);
        return aa;
    }


    /**
     * Override the Allocation resize.  Resizing adapters is not
     * allowed and will throw a RSInvalidStateException.
     *
     * @param dimX ignored.
     */
    public synchronized void resize(int dimX) {
        throw new RSInvalidStateException("Resize not allowed for Adapters.");
    }

}


