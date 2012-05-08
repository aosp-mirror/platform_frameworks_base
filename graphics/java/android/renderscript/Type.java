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
 * <p>Type is an allocation template. It consists of an Element and one or more
 * dimensions. It describes only the layout of memory but does not allocate any
 * storage for the data that is described.</p>
 *
 * <p>A Type consists of several dimensions. Those are X, Y, Z, LOD (level of
 * detail), Faces (faces of a cube map).  The X,Y,Z dimensions can be assigned
 * any positive integral value within the constraints of available memory.  A
 * single dimension allocation would have an X dimension of greater than zero
 * while the Y and Z dimensions would be zero to indicate not present.  In this
 * regard an allocation of x=10, y=1 would be considered 2 dimensionsal while
 * x=10, y=0 would be considered 1 dimensional.</p>
 *
 * <p>The LOD and Faces dimensions are booleans to indicate present or not present.</p>
 *
 * <div class="special reference">
 * <h3>Developer Guides</h3>
 * <p>For more information about creating an application that uses Renderscript, read the
 * <a href="{@docRoot}guide/topics/graphics/renderscript.html">Renderscript</a> developer guide.</p>
 * </div>
 **/
public class Type extends BaseObj {
    int mDimX;
    int mDimY;
    int mDimZ;
    boolean mDimMipmaps;
    boolean mDimFaces;
    int mElementCount;
    Element mElement;

    public enum CubemapFace {
        POSITIVE_X (0),
        NEGATIVE_X (1),
        POSITIVE_Y (2),
        NEGATIVE_Y (3),
        POSITIVE_Z (4),
        NEGATIVE_Z (5),
        @Deprecated
        POSITVE_X (0),
        @Deprecated
        POSITVE_Y (2),
        @Deprecated
        POSITVE_Z (4);

        int mID;
        CubemapFace(int id) {
            mID = id;
        }
    }

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
    public boolean hasMipmaps() {
        return mDimMipmaps;
    }

    /**
     * Return if the Type is a cube map.
     *
     * @return boolean
     */
    public boolean hasFaces() {
        return mDimFaces;
    }

    /**
     * Return the total number of accessable cells in the Type.
     *
     * @return int
     */
    public int getCount() {
        return mElementCount;
    }

    void calcElementCount() {
        boolean hasLod = hasMipmaps();
        int x = getX();
        int y = getY();
        int z = getZ();
        int faces = 1;
        if (hasFaces()) {
            faces = 6;
        }
        if (x == 0) {
            x = 1;
        }
        if (y == 0) {
            y = 1;
        }
        if (z == 0) {
            z = 1;
        }

        int count = x * y * z * faces;

        while (hasLod && ((x > 1) || (y > 1) || (z > 1))) {
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

    @Override
    void updateFromNative() {
        // We have 6 integer to obtain mDimX; mDimY; mDimZ;
        // mDimLOD; mDimFaces; mElement;
        int[] dataBuffer = new int[6];
        mRS.nTypeGetNativeData(getID(mRS), dataBuffer);

        mDimX = dataBuffer[0];
        mDimY = dataBuffer[1];
        mDimZ = dataBuffer[2];
        mDimMipmaps = dataBuffer[3] == 1 ? true : false;
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
        int mDimX = 1;
        int mDimY;
        int mDimZ;
        boolean mDimMipmaps;
        boolean mDimFaces;

        Element mElement;

        /**
         * Create a new builder object.
         *
         * @param rs
         * @param e The element for the type to be created.
         */
        public Builder(RenderScript rs, Element e) {
            e.checkValid();
            mRS = rs;
            mElement = e;
        }

        /**
         * Add a dimension to the Type.
         *
         *
         * @param value
         */
        public Builder setX(int value) {
            if(value < 1) {
                throw new RSIllegalArgumentException("Values of less than 1 for Dimension X are not valid.");
            }
            mDimX = value;
            return this;
        }

        public Builder setY(int value) {
            if(value < 1) {
                throw new RSIllegalArgumentException("Values of less than 1 for Dimension Y are not valid.");
            }
            mDimY = value;
            return this;
        }

        public Builder setMipmaps(boolean value) {
            mDimMipmaps = value;
            return this;
        }

        public Builder setFaces(boolean value) {
            mDimFaces = value;
            return this;
        }


        /**
         * Validate structure and create a new type.
         *
         * @return Type
         */
        public Type create() {
            if (mDimZ > 0) {
                if ((mDimX < 1) || (mDimY < 1)) {
                    throw new RSInvalidStateException("Both X and Y dimension required when Z is present.");
                }
                if (mDimFaces) {
                    throw new RSInvalidStateException("Cube maps not supported with 3D types.");
                }
            }
            if (mDimY > 0) {
                if (mDimX < 1) {
                    throw new RSInvalidStateException("X dimension required when Y is present.");
                }
            }
            if (mDimFaces) {
                if (mDimY < 1) {
                    throw new RSInvalidStateException("Cube maps require 2D Types.");
                }
            }

            int id = mRS.nTypeCreate(mElement.getID(mRS),
                                     mDimX, mDimY, mDimZ, mDimMipmaps, mDimFaces);
            Type t = new Type(id, mRS);
            t.mElement = mElement;
            t.mDimX = mDimX;
            t.mDimY = mDimY;
            t.mDimZ = mDimZ;
            t.mDimMipmaps = mDimMipmaps;
            t.mDimFaces = mDimFaces;

            t.calcElementCount();
            return t;
        }
    }

}
