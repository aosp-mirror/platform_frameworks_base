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

/**
 * Only intended for use by generated reflected code.
 *
 **/
public class AllocationAdapter extends Allocation {
    Type mWindow;

    AllocationAdapter(long id, RenderScript rs, Allocation alloc, Type t) {
        super(id, rs, alloc.mType, alloc.mUsage);
        mAdaptedAllocation = alloc;
        mWindow = t;
    }

    /*
    long getID(RenderScript rs) {
        throw new RSInvalidStateException(
            "This operation is not supported with adapters at this time.");
    }
    */

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

    private void updateOffsets() {
        int a1 = 0, a2 = 0, a3 = 0, a4 = 0;

        if (mSelectedArray != null) {
            if (mSelectedArray.length > 0) {
                a1 = mSelectedArray[0];
            }
            if (mSelectedArray.length > 1) {
                a2 = mSelectedArray[2];
            }
            if (mSelectedArray.length > 2) {
                a3 = mSelectedArray[2];
            }
            if (mSelectedArray.length > 3) {
                a4 = mSelectedArray[3];
            }
        }
        mRS.nAllocationAdapterOffset(getID(mRS), mSelectedX, mSelectedY, mSelectedZ,
                                     mSelectedLOD, mSelectedFace.mID, a1, a2, a3, a4);

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
        if (mWindow.hasMipmaps()) {
            throw new RSInvalidStateException("Cannot set LOD when the adapter includes mipmaps.");
        }

        initLOD(lod);
        mSelectedLOD = lod;
        updateOffsets();
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
        if (mWindow.hasFaces()) {
            throw new RSInvalidStateException("Cannot set face when the adapter includes faces.");
        }
        if (cf == null) {
            throw new RSIllegalArgumentException("Cannot set null face.");
        }

        mSelectedFace = cf;
        updateOffsets();
    }


    /**
     *
     * Set the active X.  The x value must be within the range for
     * the allocation being adapted.
     *
     * @param x The x to make active.
     */
    public void setX(int x) {
        if (mAdaptedAllocation.getType().getX() <= x) {
            throw new RSInvalidStateException("Cannot set X greater than dimension of allocation.");
        }
        if (mWindow.getX() == mAdaptedAllocation.getType().getX()) {
            throw new RSInvalidStateException("Cannot set X when the adapter includes X.");
        }
        if ((mWindow.getX() + x) >= mAdaptedAllocation.getType().getX()) {
            throw new RSInvalidStateException("Cannot set (X + window) which would be larger than dimension of allocation.");
        }

        mSelectedX = x;
        updateOffsets();
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
        if (mWindow.getY() == mAdaptedAllocation.getType().getY()) {
            throw new RSInvalidStateException("Cannot set Y when the adapter includes Y.");
        }
        if ((mWindow.getY() + y) >= mAdaptedAllocation.getType().getY()) {
            throw new RSInvalidStateException("Cannot set (Y + window) which would be larger than dimension of allocation.");
        }

        mSelectedY = y;
        updateOffsets();
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
        if (mWindow.getZ() == mAdaptedAllocation.getType().getZ()) {
            throw new RSInvalidStateException("Cannot set Z when the adapter includes Z.");
        }
        if ((mWindow.getZ() + z) >= mAdaptedAllocation.getType().getZ()) {
            throw new RSInvalidStateException("Cannot set (Z + window) which would be larger than dimension of allocation.");
        }

        mSelectedZ = z;
        updateOffsets();
    }

    /**
     * @hide
     */
    public void setArray(int arrayNum, int arrayVal) {
        if (mAdaptedAllocation.getType().getArray(arrayNum) == 0) {
            throw new RSInvalidStateException("Cannot set arrayNum when the allocation type does not include arrayNum dim.");
        }
        if (mAdaptedAllocation.getType().getArray(arrayNum) <= arrayVal) {
            throw new RSInvalidStateException("Cannot set arrayNum greater than dimension of allocation.");
        }
        if (mWindow.getArray(arrayNum) == mAdaptedAllocation.getType().getArray(arrayNum)) {
            throw new RSInvalidStateException("Cannot set arrayNum when the adapter includes arrayNum.");
        }
        if ((mWindow.getArray(arrayNum) + arrayVal) >= mAdaptedAllocation.getType().getArray(arrayNum)) {
            throw new RSInvalidStateException("Cannot set (arrayNum + window) which would be larger than dimension of allocation.");
        }

        mSelectedArray[arrayNum] = arrayVal;
        updateOffsets();
    }

    static public AllocationAdapter create1D(RenderScript rs, Allocation a) {
        rs.validate();
        Type t = Type.createX(rs, a.getElement(), a.getType().getX());
        return createTyped(rs, a, t);
    }


    static public AllocationAdapter create2D(RenderScript rs, Allocation a) {
        rs.validate();
        Type t = Type.createXY(rs, a.getElement(), a.getType().getX(), a.getType().getY());
        return createTyped(rs, a, t);
    }

    /**
     *
     *
     * Create an arbitrary window into the base allocation
     * The type describes the shape of the window.
     *
     * Any dimensions present in the type must be equal or smaller
     * to the dimensions in the source allocation.  A dimension
     * present in the allocation that is not present in the type
     * will be constrained away with the selectors
     *
     * If a dimension is present in the type and allcation one of
     * two things will happen
     *
     * If the type is smaller than the allocation a window will be
     * created, the selected value in the adapter for that dimension
     * will act as the base address and the type will describe the
     * size of the view starting at that point.
     *
     * If the type and allocation dimension are of the same size
     * then setting the selector for the dimension will be an error.
     */
    static public AllocationAdapter createTyped(RenderScript rs, Allocation a, Type t) {
        rs.validate();

        if (a.mAdaptedAllocation != null) {
            throw new RSInvalidStateException("Adapters cannot be nested.");
        }

        if (!a.getType().getElement().equals(t.getElement())) {
            throw new RSInvalidStateException("Element must match Allocation type.");
        }

        if (t.hasFaces() || t.hasMipmaps()) {
            throw new RSInvalidStateException("Adapters do not support window types with Mipmaps or Faces.");
        }

        Type at = a.getType();
        if ((t.getX() > at.getX()) ||
            (t.getY() > at.getY()) ||
            (t.getZ() > at.getZ()) ||
            (t.getArrayCount() > at.getArrayCount())) {

            throw new RSInvalidStateException("Type cannot have dimension larger than the source allocation.");
        }

        if (t.getArrayCount() > 0) {
            for (int i = 0; i < t.getArray(i); i++) {
                if (t.getArray(i) > at.getArray(i)) {
                    throw new RSInvalidStateException("Type cannot have dimension larger than the source allocation.");
                }
            }
        }

        // Create the object
        long id = rs.nAllocationAdapterCreate(a.getID(rs), t.getID(rs));
        if (id == 0) {
            throw new RSRuntimeException("AllocationAdapter creation failed.");
        }
        return new AllocationAdapter(id, rs, a, t);
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


