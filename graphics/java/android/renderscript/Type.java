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

    private int mNativeCache;
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
        super(rs);
        mID = id;
        mNativeCache = 0;
    }

    protected void finalize() throws Throwable {
        if(mNativeCache != 0) {
            mRS.nTypeFinalDestroy(this);
            mNativeCache = 0;
        }
        super.finalize();
    }

    public static Type createFromClass(RenderScript rs, Class c, int size) {
        Element e = Element.createFromClass(rs, c);
        Builder b = new Builder(rs, e);
        b.add(Dimension.X, size);
        Type t = b.create();
        e.destroy();

        // native fields
        {
            Field[] fields = c.getFields();
            int[] arTypes = new int[fields.length];
            int[] arBits = new int[fields.length];

            for(int ct=0; ct < fields.length; ct++) {
                Field f = fields[ct];
                Class fc = f.getType();
                if(fc == int.class) {
                    arTypes[ct] = Element.DataType.SIGNED_32.mID;
                    arBits[ct] = 32;
                } else if(fc == short.class) {
                    arTypes[ct] = Element.DataType.SIGNED_16.mID;
                    arBits[ct] = 16;
                } else if(fc == byte.class) {
                    arTypes[ct] = Element.DataType.SIGNED_8.mID;
                    arBits[ct] = 8;
                } else if(fc == float.class) {
                    arTypes[ct] = Element.DataType.FLOAT_32.mID;
                    arBits[ct] = 32;
                } else {
                    throw new IllegalArgumentException("Unkown field type");
                }
            }
            rs.nTypeSetupFields(t, arTypes, arBits, fields);
        }
        t.mJavaClass = c;
        return t;
    }

    public static Type createFromClass(RenderScript rs, Class c, int size, String scriptName) {
        Type t = createFromClass(rs, c, size);
        t.setName(scriptName);
        return t;
    }


    public static class Builder {
        RenderScript mRS;
        Entry[] mEntries;
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
            mEntries = new Entry[4];
            mElement = e;
        }

        public void add(Dimension d, int value) {
            if(value < 1) {
                throw new IllegalArgumentException("Values of less than 1 for Dimensions are not valid.");
            }
            if(mEntries.length >= mEntryCount) {
                Entry[] en = new Entry[mEntryCount + 8];
                System.arraycopy(mEntries, 0, en, 0, mEntries.length);
                mEntries = en;
            }
            mEntries[mEntryCount] = new Entry();
            mEntries[mEntryCount].mDim = d;
            mEntries[mEntryCount].mValue = value;
            mEntryCount++;
        }

        static synchronized Type internalCreate(RenderScript rs, Builder b) {
            rs.nTypeBegin(b.mElement.mID);
            for (int ct=0; ct < b.mEntryCount; ct++) {
                Entry en = b.mEntries[ct];
                rs.nTypeAdd(en.mDim.mID, en.mValue);
            }
            int id = rs.nTypeCreate();
            return new Type(id, rs);
        }

        public Type create() {
            Type t = internalCreate(mRS, this);
            t.mElement = mElement;

            for(int ct=0; ct < mEntryCount; ct++) {
                if(mEntries[ct].mDim == Dimension.X) {
                    t.mDimX = mEntries[ct].mValue;
                }
                if(mEntries[ct].mDim == Dimension.Y) {
                    t.mDimY = mEntries[ct].mValue;
                }
                if(mEntries[ct].mDim == Dimension.Z) {
                    t.mDimZ = mEntries[ct].mValue;
                }
                if(mEntries[ct].mDim == Dimension.LOD) {
                    t.mDimLOD = mEntries[ct].mValue != 0;
                }
                if(mEntries[ct].mDim == Dimension.FACE) {
                    t.mDimFaces = mEntries[ct].mValue != 0;
                }
            }
            t.calcElementCount();
            return t;
        }
    }

}
