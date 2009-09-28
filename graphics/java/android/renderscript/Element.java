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
public class Element extends BaseObj {
    int mSize;
    Entry[] mEntries;

    int getSizeBytes() {
        return mSize;
    }
    int getComponentCount() {
        return mEntries.length;
    }
    Element.DataType getComponentDataType(int num) {
        return mEntries[num].mType;
    }
    Element.DataKind getComponentDataKind(int num) {
        return mEntries[num].mKind;
    }
    boolean getComponentIsNormalized(int num) {
        return mEntries[num].mIsNormalized;
    }
    int getComponentBits(int num) {
        return mEntries[num].mBits;
    }
    String getComponentName(int num) {
        return mEntries[num].mName;
    }

    static class Entry {
        //Element mElement;
        Element.DataType mType;
        Element.DataKind mKind;
        boolean mIsNormalized;
        int mBits;
        String mName;

        //Entry(Element e, int bits) {
            //mElement = e;
            //int mBits = bits;
        //}

        Entry(DataType dt, DataKind dk, boolean isNorm, int bits, String name) {
            mType = dt;
            mKind = dk;
            mIsNormalized = isNorm;
            mBits = bits;
            mName = name;
        }
    }

    public static Element USER_U8(RenderScript rs) {
        if(rs.mElement_USER_U8 == null) {
            rs.mElement_USER_U8 = new Element(rs, 1);
            rs.mElement_USER_U8.mEntries[0] = new Entry(DataType.UNSIGNED, DataKind.USER, false, 8, null);
            rs.mElement_USER_U8.init();
        }
        return rs.mElement_USER_U8;
    }

    public static Element USER_I8(RenderScript rs) {
        if(rs.mElement_USER_I8 == null) {
            rs.mElement_USER_I8 = new Element(rs, 1);
            rs.mElement_USER_I8.mEntries[0] = new Entry(DataType.SIGNED, DataKind.USER, false, 8, null);
            rs.mElement_USER_I8.init();
        }
        return rs.mElement_USER_I8;
    }

    public static Element USER_U16(RenderScript rs) {
        if(rs.mElement_USER_U16 == null) {
            rs.mElement_USER_U16 = new Element(rs, 1);
            rs.mElement_USER_U16.mEntries[0] = new Entry(DataType.UNSIGNED, DataKind.USER, false, 16, null);
            rs.mElement_USER_U16.init();
        }
        return rs.mElement_USER_U16;
    }

    public static Element USER_I16(RenderScript rs) {
        if(rs.mElement_USER_I16 == null) {
            rs.mElement_USER_I16 = new Element(rs, 1);
            rs.mElement_USER_I16.mEntries[0] = new Entry(DataType.SIGNED, DataKind.USER, false, 16, null);
            rs.mElement_USER_I16.init();
        }
        return rs.mElement_USER_I16;
    }

    public static Element USER_U32(RenderScript rs) {
        if(rs.mElement_USER_U32 == null) {
            rs.mElement_USER_U32 = new Element(rs, 1);
            rs.mElement_USER_U32.mEntries[0] = new Entry(DataType.UNSIGNED, DataKind.USER, false, 32, null);
            rs.mElement_USER_U32.init();
        }
        return rs.mElement_USER_U32;
    }

    public static Element USER_I32(RenderScript rs) {
        if(rs.mElement_USER_I32 == null) {
            rs.mElement_USER_I32 = new Element(rs, 1);
            rs.mElement_USER_I32.mEntries[0] = new Entry(DataType.SIGNED, DataKind.USER, false, 32, null);
            rs.mElement_USER_I32.init();
        }
        return rs.mElement_USER_I32;
    }

    public static Element USER_F32(RenderScript rs) {
        if(rs.mElement_USER_FLOAT == null) {
            rs.mElement_USER_FLOAT = new Element(rs, 1);
            rs.mElement_USER_FLOAT.mEntries[0] = new Entry(DataType.FLOAT, DataKind.USER, false, 32, null);
            rs.mElement_USER_FLOAT.init();
        }
        return rs.mElement_USER_FLOAT;
    }

    public static Element A_8(RenderScript rs) {
        if(rs.mElement_A_8 == null) {
            rs.mElement_A_8 = new Element(rs, 1);
            rs.mElement_A_8.mEntries[0] = new Entry(DataType.UNSIGNED, DataKind.ALPHA, true, 8, "a");
            rs.mElement_A_8.init();
        }
        return rs.mElement_A_8;
    }

    public static Element RGB_565(RenderScript rs) {
        if(rs.mElement_RGB_565 == null) {
            rs.mElement_RGB_565 = new Element(rs, 3);
            rs.mElement_RGB_565.mEntries[0] = new Entry(DataType.UNSIGNED, DataKind.RED, true, 5, "r");
            rs.mElement_RGB_565.mEntries[1] = new Entry(DataType.UNSIGNED, DataKind.GREEN, true, 6, "g");
            rs.mElement_RGB_565.mEntries[2] = new Entry(DataType.UNSIGNED, DataKind.BLUE, true, 5, "b");
            rs.mElement_RGB_565.init();
        }
        return rs.mElement_RGB_565;
    }

    public static Element RGB_888(RenderScript rs) {
        if(rs.mElement_RGB_888 == null) {
            rs.mElement_RGB_888 = new Element(rs, 3);
            rs.mElement_RGB_888.mEntries[0] = new Entry(DataType.UNSIGNED, DataKind.RED, true, 8, "r");
            rs.mElement_RGB_888.mEntries[1] = new Entry(DataType.UNSIGNED, DataKind.GREEN, true, 8, "g");
            rs.mElement_RGB_888.mEntries[2] = new Entry(DataType.UNSIGNED, DataKind.BLUE, true, 8, "b");
            rs.mElement_RGB_888.init();
        }
        return rs.mElement_RGB_888;
    }

    public static Element RGBA_5551(RenderScript rs) {
        if(rs.mElement_RGBA_5551 == null) {
            rs.mElement_RGBA_5551 = new Element(rs, 4);
            rs.mElement_RGBA_5551.mEntries[0] = new Entry(DataType.UNSIGNED, DataKind.RED, true, 5, "r");
            rs.mElement_RGBA_5551.mEntries[1] = new Entry(DataType.UNSIGNED, DataKind.GREEN, true, 5, "g");
            rs.mElement_RGBA_5551.mEntries[2] = new Entry(DataType.UNSIGNED, DataKind.BLUE, true, 5, "b");
            rs.mElement_RGBA_5551.mEntries[3] = new Entry(DataType.UNSIGNED, DataKind.ALPHA, true, 1, "a");
            rs.mElement_RGBA_5551.init();
        }
        return rs.mElement_RGBA_5551;
    }

    public static Element RGBA_4444(RenderScript rs) {
        if(rs.mElement_RGBA_4444 == null) {
            rs.mElement_RGBA_4444 = new Element(rs, 4);
            rs.mElement_RGBA_4444.mEntries[0] = new Entry(DataType.UNSIGNED, DataKind.RED, true, 4, "r");
            rs.mElement_RGBA_4444.mEntries[1] = new Entry(DataType.UNSIGNED, DataKind.GREEN, true, 4, "g");
            rs.mElement_RGBA_4444.mEntries[2] = new Entry(DataType.UNSIGNED, DataKind.BLUE, true, 4, "b");
            rs.mElement_RGBA_4444.mEntries[3] = new Entry(DataType.UNSIGNED, DataKind.ALPHA, true, 4, "a");
            rs.mElement_RGBA_4444.init();
        }
        return rs.mElement_RGBA_4444;
    }

    public static Element RGBA_8888(RenderScript rs) {
        if(rs.mElement_RGBA_8888 == null) {
            rs.mElement_RGBA_8888 = new Element(rs, 4);
            rs.mElement_RGBA_8888.mEntries[0] = new Entry(DataType.UNSIGNED, DataKind.RED, true, 8, "r");
            rs.mElement_RGBA_8888.mEntries[1] = new Entry(DataType.UNSIGNED, DataKind.GREEN, true, 8, "g");
            rs.mElement_RGBA_8888.mEntries[2] = new Entry(DataType.UNSIGNED, DataKind.BLUE, true, 8, "b");
            rs.mElement_RGBA_8888.mEntries[3] = new Entry(DataType.UNSIGNED, DataKind.ALPHA, true, 8, "a");
            rs.mElement_RGBA_8888.init();
        }
        return rs.mElement_RGBA_8888;
    }

    public static Element INDEX_16(RenderScript rs) {
        if(rs.mElement_INDEX_16 == null) {
            rs.mElement_INDEX_16 = new Element(rs, 1);
            rs.mElement_INDEX_16.mEntries[0] = new Entry(DataType.UNSIGNED, DataKind.INDEX, false, 16, "index");
            rs.mElement_INDEX_16.init();
        }
        return rs.mElement_INDEX_16;
    }

    public static Element XY_F32(RenderScript rs) {
        if(rs.mElement_XY_F32 == null) {
            rs.mElement_XY_F32 = new Element(rs, 2);
            rs.mElement_XY_F32.mEntries[0] = new Entry(DataType.UNSIGNED, DataKind.X, false, 32, "x");
            rs.mElement_XY_F32.mEntries[1] = new Entry(DataType.UNSIGNED, DataKind.Y, false, 32, "y");
            rs.mElement_XY_F32.init();
        }
        return rs.mElement_XY_F32;
    }

    public static Element XYZ_F32(RenderScript rs) {
        if(rs.mElement_XYZ_F32 == null) {
            rs.mElement_XYZ_F32 = new Element(rs, 3);
            rs.mElement_XYZ_F32.mEntries[0] = new Entry(DataType.UNSIGNED, DataKind.X, false, 32, "x");
            rs.mElement_XYZ_F32.mEntries[1] = new Entry(DataType.UNSIGNED, DataKind.Y, false, 32, "y");
            rs.mElement_XYZ_F32.mEntries[2] = new Entry(DataType.UNSIGNED, DataKind.Z, false, 32, "z");
            rs.mElement_XYZ_F32.init();
        }
        return rs.mElement_XYZ_F32;
    }

    static void initPredefined(RenderScript rs) {
        rs.nInitElements(A_8(rs).mID, RGBA_4444(rs).mID, RGBA_8888(rs).mID, RGB_565(rs).mID);
    }

    public enum DataType {
        FLOAT (0),
        UNSIGNED (1),
        SIGNED (2);

        int mID;
        DataType(int id) {
            mID = id;
        }
    }

    public enum DataKind {
        USER (0),
        RED (1),
        GREEN (2),
        BLUE (3),
        ALPHA (4),
        LUMINANCE (5),
        INTENSITY (6),
        X (7),
        Y (8),
        Z (9),
        W (10),
        S (11),
        T (12),
        Q (13),
        R (14),
        NX (15),
        NY (16),
        NZ (17),
        INDEX (18),
        POINT_SIZE(19);

        int mID;
        DataKind(int id) {
            mID = id;
        }
    }

    Element(RenderScript rs, int count) {
        super(rs);
        mSize = 0;
        mEntries = new Entry[count];
    }

    public void destroy() throws IllegalStateException {
        super.destroy();
    }

    public static Element createFromClass(RenderScript rs, Class c) {
        Field[] fields = c.getFields();
        Builder b = new Builder(rs);

        for(Field f: fields) {
            Class fc = f.getType();
            if(fc == int.class) {
                b.add(Element.DataType.SIGNED, Element.DataKind.USER, false, 32, f.getName());
            } else if(fc == short.class) {
                b.add(Element.DataType.SIGNED, Element.DataKind.USER, false, 16, f.getName());
            } else if(fc == byte.class) {
                b.add(Element.DataType.SIGNED, Element.DataKind.USER, false, 8, f.getName());
            } else if(fc == float.class) {
                b.add(Element.DataType.FLOAT, Element.DataKind.USER, false, 32, f.getName());
            } else {
                throw new IllegalArgumentException("Unkown field type");
            }
        }
        return b.create();
    }

    static synchronized void internalCreate(RenderScript rs, Element e) {
        rs.nElementBegin();
        int bits = 0;
        for (int ct=0; ct < e.mEntries.length; ct++) {
            Entry en = e.mEntries[ct];
            //if(en.mElement !=  null) {
                //rs.nElementAdd(en.mElement.mID);
            //} else
            {
                rs.nElementAdd(en.mKind.mID, en.mType.mID, en.mIsNormalized, en.mBits, en.mName);
                bits += en.mBits;
            }
        }
        e.mID = rs.nElementCreate();
        e.mSize = (bits + 7) >> 3;
    }

    void init() {
        internalCreate(mRS, this);
    }


    public static class Builder {
        RenderScript mRS;
        Entry[] mEntries;
        int mEntryCount;

        public Builder(RenderScript rs) {
            mRS = rs;
            mEntryCount = 0;
            mEntries = new Entry[8];
        }

        void addEntry(Entry e) {
            if(mEntries.length >= mEntryCount) {
                Entry[] en = new Entry[mEntryCount + 8];
                System.arraycopy(mEntries, 0, en, 0, mEntries.length);
                mEntries = en;
            }
            mEntries[mEntryCount] = e;
            mEntryCount++;
        }

        //public Builder add(Element e) throws IllegalArgumentException {
            //Entry en = new Entry(e, e.mSize * 8);
            //addEntry(en);
            //return this;
        //}

        public Builder add(Element.DataType dt, Element.DataKind dk, boolean isNormalized, int bits, String name) {
            Entry en = new Entry(dt, dk, isNormalized, bits, name);
            addEntry(en);
            return this;
        }

        public Builder add(Element.DataType dt, Element.DataKind dk, boolean isNormalized, int bits) {
            add(dt, dk, isNormalized, bits, null);
            return this;
        }

        public Builder addFloat(Element.DataKind dk) {
            add(DataType.FLOAT, dk, false, 32, null);
            return this;
        }

        public Builder addFloat(Element.DataKind dk, String name) {
            add(DataType.FLOAT, dk, false, 32, name);
            return this;
        }

        public Builder addFloatXY() {
            add(DataType.FLOAT, DataKind.X, false, 32, null);
            add(DataType.FLOAT, DataKind.Y, false, 32, null);
            return this;
        }

        public Builder addFloatXY(String prefix) {
            add(DataType.FLOAT, DataKind.X, false, 32, prefix + "x");
            add(DataType.FLOAT, DataKind.Y, false, 32, prefix + "y");
            return this;
        }

        public Builder addFloatXYZ() {
            add(DataType.FLOAT, DataKind.X, false, 32, null);
            add(DataType.FLOAT, DataKind.Y, false, 32, null);
            add(DataType.FLOAT, DataKind.Z, false, 32, null);
            return this;
        }

        public Builder addFloatXYZ(String prefix) {
            add(DataType.FLOAT, DataKind.X, false, 32, prefix + "x");
            add(DataType.FLOAT, DataKind.Y, false, 32, prefix + "y");
            add(DataType.FLOAT, DataKind.Z, false, 32, prefix + "z");
            return this;
        }

        public Builder addFloatST() {
            add(DataType.FLOAT, DataKind.S, false, 32, null);
            add(DataType.FLOAT, DataKind.T, false, 32, null);
            return this;
        }

        public Builder addFloatST(String prefix) {
            add(DataType.FLOAT, DataKind.S, false, 32, prefix + "s");
            add(DataType.FLOAT, DataKind.T, false, 32, prefix + "t");
            return this;
        }

        public Builder addFloatNorm() {
            add(DataType.FLOAT, DataKind.NX, false, 32, null);
            add(DataType.FLOAT, DataKind.NY, false, 32, null);
            add(DataType.FLOAT, DataKind.NZ, false, 32, null);
            return this;
        }

        public Builder addFloatNorm(String prefix) {
            add(DataType.FLOAT, DataKind.NX, false, 32, prefix + "nx");
            add(DataType.FLOAT, DataKind.NY, false, 32, prefix + "ny");
            add(DataType.FLOAT, DataKind.NZ, false, 32, prefix + "nz");
            return this;
        }

        public Builder addFloatPointSize() {
            add(DataType.FLOAT, DataKind.POINT_SIZE, false, 32, null);
            return this;
        }

        public Builder addFloatPointSize(String prefix) {
            add(DataType.FLOAT, DataKind.POINT_SIZE, false, 32, prefix + "pointSize");
            return this;
        }

        public Builder addFloatRGB() {
            add(DataType.FLOAT, DataKind.RED, false, 32, null);
            add(DataType.FLOAT, DataKind.GREEN, false, 32, null);
            add(DataType.FLOAT, DataKind.BLUE, false, 32, null);
            return this;
        }

        public Builder addFloatRGB(String prefix) {
            add(DataType.FLOAT, DataKind.RED, false, 32, prefix + "r");
            add(DataType.FLOAT, DataKind.GREEN, false, 32, prefix + "g");
            add(DataType.FLOAT, DataKind.BLUE, false, 32, prefix + "b");
            return this;
        }

        public Builder addFloatRGBA() {
            add(DataType.FLOAT, DataKind.RED, false, 32, null);
            add(DataType.FLOAT, DataKind.GREEN, false, 32, null);
            add(DataType.FLOAT, DataKind.BLUE, false, 32, null);
            add(DataType.FLOAT, DataKind.ALPHA, false, 32, null);
            return this;
        }

        public Builder addFloatRGBA(String prefix) {
            add(DataType.FLOAT, DataKind.RED, false, 32, prefix + "r");
            add(DataType.FLOAT, DataKind.GREEN, false, 32, prefix + "g");
            add(DataType.FLOAT, DataKind.BLUE, false, 32, prefix + "b");
            add(DataType.FLOAT, DataKind.ALPHA, false, 32, prefix + "a");
            return this;
        }

        public Builder addUNorm8RGBA() {
            add(DataType.UNSIGNED, DataKind.RED, true, 8, null);
            add(DataType.UNSIGNED, DataKind.GREEN, true, 8, null);
            add(DataType.UNSIGNED, DataKind.BLUE, true, 8, null);
            add(DataType.UNSIGNED, DataKind.ALPHA, true, 8, null);
            return this;
        }

        public Builder addUNorm8RGBA(String prefix) {
            add(DataType.UNSIGNED, DataKind.RED, true, 8, prefix + "r");
            add(DataType.UNSIGNED, DataKind.GREEN, true, 8, prefix + "g");
            add(DataType.UNSIGNED, DataKind.BLUE, true, 8, prefix + "b");
            add(DataType.UNSIGNED, DataKind.ALPHA, true, 8, prefix + "a");
            return this;
        }

        public Element create() {
            Element e = new Element(mRS, mEntryCount);
            java.lang.System.arraycopy(mEntries, 0, e.mEntries, 0, mEntryCount);
            e.init();
            return e;
        }
    }

}

