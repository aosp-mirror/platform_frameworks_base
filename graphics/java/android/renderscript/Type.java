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


import java.lang.reflect.Field;
import android.util.Log;

/**
 * @hide
 *
 * Type is an allocation template.  It consists of an Element and one or more
 * dimensions.  It describes only the layout of memory but does not allocate and
 * storage for the data thus described.
 *
 * A Type consists of several dimensions.  Those are X, Y, Z, LOD (level of
 * detail), Faces (faces of a cube map).  The X,Y,Z dimensions can be assigned
 * any positive integral value within the constraints of available memory.  A
 * single dimension allocation would have an X dimension of greater than zero
 * while the Y and Z dimensions would be zero to indicate not present.  In this
 * regard an allocation of x=10, y=1 would be considered 2 dimensionsal while
 * x=10, y=0 would be considered 1 dimensional.
 *
 * The LOD and Faces dimensions are booleans to indicate present or not present.
 *
 **/
public class Type extends BaseObj {
    int mDimX;
    int mDimY;
    int mDimZ;
    boolean mDimLOD;
    boolean mDimFaces;
    int mElementCount;
    Element mElement;

    /**
     * Return the element associated with this Type.
     *
     * @return Element
     */
    public Element getElement() {
        return mElement;
    }

    /**
     * Return the value of the X dimension.
     *
     * @return int
     */
    public int getX() {
        return mDimX;
    }

    /**
     * Return the value of the Y dimension or 0 for a 1D allocation.
     *
     * @return int
     */
    public int getY() {
        return mDimY;
    }

    /**
     * Return the value of the Z dimension or 0 for a 1D or 2D allocation.
     *
     * @return int
     */
    public int getZ() {
        return mDimZ;
    }

    /**
     * Return if the Type has a mipmap chain.
     *
     * @return boolean
     */
    public boolean getLOD() {
        return mDimLOD;
    }

    /**
     * Return if the Type is a cube map.
     *
     * @return boolean
     */
    public boolean getFaces() {
        return mDimFaces;
    }

    /**
     * Return the total number of accessable cells in the Type.
     *
     * @return int
     */
    public int getElementCount() {
        return mElementCount;
    }

    void calcElementCount() {
        boolean hasLod = getLOD();
        int x = getX();
        int y = getY();
        int z = getZ();
        int faces = 1;
        if(getFaces()) {
            faces = 6;
        }
        if(x == 0) {
            x = 1;
        }
        if(y == 0) {
            y = 1;
        }
        if(z == 0) {
            z = 1;
        }

        int count = x * y * z * faces;
        if(hasLod && (x > 1) && (y > 1) && (z > 1)) {
            if(x > 1) {
                x >>= 1;
            }
            if(y > 1) {
                y >>= 1;
            }
            if(z > 1) {
                z >>= 1;
            }

            count += x * y * z * faces;
        }
        mElementCount = count;
    }


    Type(int id, RenderScript rs) {
        super(id, rs);
    }

    protected void finalize() throws Throwable {
        super.finalize();
    }

    @Override
    void updateFromNative() {
        // We have 6 integer to obtain mDimX; mDimY; mDimZ;
        // mDimLOD; mDimFaces; mElement;
        int[] dataBuffer = new int[6];
        mRS.nTypeGetNativeData(getID(), dataBuffer);

        mDimX = dataBuffer[0];
        mDimY = dataBuffer[1];
        mDimZ = dataBuffer[2];
        mDimLOD = dataBuffer[3] == 1 ? true : false;
        mDimFaces = dataBuffer[4] == 1 ? true : false;

        int elementID = dataBuffer[5];
        if(elementID != 0) {
            mElement = new Element(elementID, mRS);
            mElement.updateFromNative();
        }
        calcElementCount();
    }

    /**
     * Builder class for Type.
     *
     */
    public static class Builder {
        RenderScript mRS;
        Dimension[] mDimensions;
        int[] mDimensionValues;
        int mEntryCount;
        Element mElement;

        class Entry {
            Dimension mDim;
            int mValue;
        }

        /**
         * Create a new builder object.
         *
         * @param rs
         * @param e The element for the type to be created.
         */
        public Builder(RenderScript rs, Element e) {
            if(e.getID() == 0) {
                throw new RSIllegalArgumentException("Invalid element.");
            }

            mRS = rs;
            mDimensions = new Dimension[4];
            mDimensionValues = new int[4];
            mElement = e;
        }

        /**
         * Add a dimension to the Type.
         *
         *
         * @param d
         * @param value
         */
        public void add(Dimension d, int value) {
            if(value < 1) {
                throw new RSIllegalArgumentException("Values of less than 1 for Dimensions are not valid.");
            }
            if(mDimensions.length >= mEntryCount) {
                Dimension[] dn = new Dimension[mEntryCount + 8];
                System.arraycopy(mDimensions, 0, dn, 0, mEntryCount);
                mDimensions = dn;

                int[] in = new int[mEntryCount + 8];
                System.arraycopy(mDimensionValues, 0, in, 0, mEntryCount);
                mDimensionValues = in;
            }
            mDimensions[mEntryCount] = d;
            mDimensionValues[mEntryCount] = value;
            mEntryCount++;
        }

        /**
         * Validate structure and create a new type.
         *
         * @return Type
         */
        public Type create() {
            int dims[] = new int[mEntryCount];
            for (int ct=0; ct < mEntryCount; ct++) {
                dims[ct] = mDimensions[ct].mID;
            }

            int id = mRS.nTypeCreate(mElement.getID(), dims, mDimensionValues);
            Type t = new Type(id, mRS);
            t.mElement = mElement;

            for(int ct=0; ct < mEntryCount; ct++) {
                if(mDimensions[ct] == Dimension.X) {
                    t.mDimX = mDimensionValues[ct];
                }
                if(mDimensions[ct] == Dimension.Y) {
                    t.mDimY = mDimensionValues[ct];
                }
                if(mDimensions[ct] == Dimension.Z) {
                    t.mDimZ = mDimensionValues[ct];
                }
                if(mDimensions[ct] == Dimension.LOD) {
                    t.mDimLOD = mDimensionValues[ct] != 0;
                }
                if(mDimensions[ct] == Dimension.FACE) {
                    t.mDimFaces = mDimensionValues[ct] != 0;
                }
            }

            if (t.mDimZ > 0) {
                if ((t.mDimX < 1) || (t.mDimY < 1)) {
                    throw new RSInvalidStateException("Both X and Y dimension required when Z is present.");
                }
                if (t.mDimFaces) {
                    throw new RSInvalidStateException("Cube maps not supported with 3D types.");
                }
            }
            if (t.mDimY > 0) {
                if (t.mDimX < 1) {
                    throw new RSInvalidStateException("X dimension required when Y is present.");
                }
            }
            if (t.mDimFaces) {
                if (t.mDimY < 1) {
                    throw new RSInvalidStateException("Cube maps require 2D Types.");
                }
            }

            t.calcElementCount();
            return t;
        }
    }

}
