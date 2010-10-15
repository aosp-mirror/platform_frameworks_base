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
 **/
public class Type extends BaseObj {
    int mDimX;
    int mDimY;
    int mDimZ;
    boolean mDimLOD;
    boolean mDimFaces;
    int mElementCount;
    Element mElement;

    Class mJavaClass;

    public Element getElement() {
        return mElement;
    }

    public int getX() {
        return mDimX;
    }
    public int getY() {
        return mDimY;
    }
    public int getZ() {
        return mDimZ;
    }
    public boolean getLOD() {
        return mDimLOD;
    }
    public boolean getFaces() {
        return mDimFaces;
    }
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
        mRS.nTypeGetNativeData(mID, dataBuffer);

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

    public static Type createFromClass(RenderScript rs, Class c, int size, String scriptName) {
        android.util.Log.e("RenderScript", "Calling depricated createFromClass");
        return null;
    }


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

        public Builder(RenderScript rs, Element e) {
            if(e.mID == 0) {
                throw new IllegalArgumentException("Invalid element.");
            }

            mRS = rs;
            mDimensions = new Dimension[4];
            mDimensionValues = new int[4];
            mElement = e;
        }

        public void add(Dimension d, int value) {
            if(value < 1) {
                throw new IllegalArgumentException("Values of less than 1 for Dimensions are not valid.");
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
            t.calcElementCount();
            return t;
        }
    }

}
