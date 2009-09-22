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

    public static final Element USER_U8 = new Element();
    public static final Element USER_I8 = new Element();
    public static final Element USER_U16 = new Element();
    public static final Element USER_I16 = new Element();
    public static final Element USER_U32 = new Element();
    public static final Element USER_I32 = new Element();
    public static final Element USER_FLOAT = new Element();

    public static final Element A_8 = new Element();
    public static final Element RGB_565 = new Element();
    public static final Element RGB_888 = new Element();
    public static final Element RGBA_5551 = new Element();
    public static final Element RGBA_4444 = new Element();
    public static final Element RGBA_8888 = new Element();

    public static final Element INDEX_16 = new Element();
    public static final Element XY_F32 = new Element();
    public static final Element XYZ_F32 = new Element();
    public static final Element ST_XY_F32 = new Element();
    public static final Element ST_XYZ_F32 = new Element();
    public static final Element NORM_XYZ_F32 = new Element();
    public static final Element NORM_ST_XYZ_F32 = new Element();

    static void initPredefined(RenderScript rs) {
        USER_U8.mEntries = new Entry[1];
        USER_U8.mEntries[0] = new Entry(DataType.UNSIGNED, DataKind.USER, false, 8, null);
        USER_U8.init(rs);

        USER_I8.mEntries = new Entry[1];
        USER_I8.mEntries[0] = new Entry(DataType.SIGNED, DataKind.USER, false, 8, null);
        USER_I8.init(rs);

        USER_U16.mEntries = new Entry[1];
        USER_U16.mEntries[0] = new Entry(DataType.UNSIGNED, DataKind.USER, false, 16, null);
        USER_U16.init(rs);

        USER_I16.mEntries = new Entry[1];
        USER_I16.mEntries[0] = new Entry(DataType.SIGNED, DataKind.USER, false, 16, null);
        USER_I16.init(rs);

        USER_U32.mEntries = new Entry[1];
        USER_U32.mEntries[0] = new Entry(DataType.UNSIGNED, DataKind.USER, false, 32, null);
        USER_U32.init(rs);

        USER_I32.mEntries = new Entry[1];
        USER_I32.mEntries[0] = new Entry(DataType.SIGNED, DataKind.USER, false, 32, null);
        USER_I32.init(rs);

        USER_FLOAT.mEntries = new Entry[1];
        USER_FLOAT.mEntries[0] = new Entry(DataType.FLOAT, DataKind.USER, false, 32, null);
        USER_FLOAT.init(rs);

        A_8.mEntries = new Entry[1];
        A_8.mEntries[0] = new Entry(DataType.UNSIGNED, DataKind.ALPHA, true, 8, "a");
        A_8.init(rs);

        RGB_565.mEntries = new Entry[3];
        RGB_565.mEntries[0] = new Entry(DataType.UNSIGNED, DataKind.RED, true, 5, "r");
        RGB_565.mEntries[1] = new Entry(DataType.UNSIGNED, DataKind.GREEN, true, 6, "g");
        RGB_565.mEntries[2] = new Entry(DataType.UNSIGNED, DataKind.BLUE, true, 5, "b");
        RGB_565.init(rs);

        RGB_888.mEntries = new Entry[3];
        RGB_888.mEntries[0] = new Entry(DataType.UNSIGNED, DataKind.RED, true, 8, "r");
        RGB_888.mEntries[1] = new Entry(DataType.UNSIGNED, DataKind.GREEN, true, 8, "g");
        RGB_888.mEntries[2] = new Entry(DataType.UNSIGNED, DataKind.BLUE, true, 8, "b");
        RGB_888.init(rs);

        RGBA_5551.mEntries = new Entry[4];
        RGBA_5551.mEntries[0] = new Entry(DataType.UNSIGNED, DataKind.RED, true, 5, "r");
        RGBA_5551.mEntries[1] = new Entry(DataType.UNSIGNED, DataKind.GREEN, true, 5, "g");
        RGBA_5551.mEntries[2] = new Entry(DataType.UNSIGNED, DataKind.BLUE, true, 5, "b");
        RGBA_5551.mEntries[3] = new Entry(DataType.UNSIGNED, DataKind.ALPHA, true, 1, "a");
        RGBA_5551.init(rs);

        RGBA_4444.mEntries = new Entry[4];
        RGBA_4444.mEntries[0] = new Entry(DataType.UNSIGNED, DataKind.RED, true, 4, "r");
        RGBA_4444.mEntries[1] = new Entry(DataType.UNSIGNED, DataKind.GREEN, true, 4, "g");
        RGBA_4444.mEntries[2] = new Entry(DataType.UNSIGNED, DataKind.BLUE, true, 4, "b");
        RGBA_4444.mEntries[3] = new Entry(DataType.UNSIGNED, DataKind.ALPHA, true, 4, "a");
        RGBA_4444.init(rs);

        RGBA_8888.mEntries = new Entry[4];
        RGBA_8888.mEntries[0] = new Entry(DataType.UNSIGNED, DataKind.RED, true, 8, "r");
        RGBA_8888.mEntries[1] = new Entry(DataType.UNSIGNED, DataKind.GREEN, true, 8, "g");
        RGBA_8888.mEntries[2] = new Entry(DataType.UNSIGNED, DataKind.BLUE, true, 8, "b");
        RGBA_8888.mEntries[3] = new Entry(DataType.UNSIGNED, DataKind.ALPHA, true, 8, "a");
        RGBA_8888.init(rs);

        INDEX_16.mEntries = new Entry[1];
        INDEX_16.mEntries[0] = new Entry(DataType.UNSIGNED, DataKind.INDEX, false, 16, "index");
        INDEX_16.init(rs);

        XY_F32.mEntries = new Entry[2];
        XY_F32.mEntries[0] = new Entry(DataType.FLOAT, DataKind.X, false, 32, "x");
        XY_F32.mEntries[1] = new Entry(DataType.FLOAT, DataKind.Y, false, 32, "y");
        XY_F32.init(rs);

        XYZ_F32.mEntries = new Entry[3];
        XYZ_F32.mEntries[0] = new Entry(DataType.FLOAT, DataKind.X, false, 32, "x");
        XYZ_F32.mEntries[1] = new Entry(DataType.FLOAT, DataKind.Y, false, 32, "y");
        XYZ_F32.mEntries[2] = new Entry(DataType.FLOAT, DataKind.Z, false, 32, "z");
        XYZ_F32.init(rs);

        ST_XY_F32.mEntries = new Entry[4];
        ST_XY_F32.mEntries[0] = new Entry(DataType.FLOAT, DataKind.S, false, 32, "s");
        ST_XY_F32.mEntries[1] = new Entry(DataType.FLOAT, DataKind.T, false, 32, "t");
        ST_XY_F32.mEntries[2] = new Entry(DataType.FLOAT, DataKind.X, false, 32, "x");
        ST_XY_F32.mEntries[3] = new Entry(DataType.FLOAT, DataKind.Y, false, 32, "y");
        ST_XY_F32.init(rs);

        ST_XYZ_F32.mEntries = new Entry[5];
        ST_XYZ_F32.mEntries[0] = new Entry(DataType.FLOAT, DataKind.S, false, 32, "s");
        ST_XYZ_F32.mEntries[1] = new Entry(DataType.FLOAT, DataKind.T, false, 32, "t");
        ST_XYZ_F32.mEntries[2] = new Entry(DataType.FLOAT, DataKind.X, false, 32, "x");
        ST_XYZ_F32.mEntries[3] = new Entry(DataType.FLOAT, DataKind.Y, false, 32, "y");
        ST_XYZ_F32.mEntries[4] = new Entry(DataType.FLOAT, DataKind.Z, false, 32, "z");
        ST_XYZ_F32.init(rs);

        NORM_XYZ_F32.mEntries = new Entry[6];
        NORM_XYZ_F32.mEntries[0] = new Entry(DataType.FLOAT, DataKind.NX, false, 32, "nx");
        NORM_XYZ_F32.mEntries[1] = new Entry(DataType.FLOAT, DataKind.NY, false, 32, "ny");
        NORM_XYZ_F32.mEntries[2] = new Entry(DataType.FLOAT, DataKind.NZ, false, 32, "nz");
        NORM_XYZ_F32.mEntries[3] = new Entry(DataType.FLOAT, DataKind.X, false, 32, "x");
        NORM_XYZ_F32.mEntries[4] = new Entry(DataType.FLOAT, DataKind.Y, false, 32, "y");
        NORM_XYZ_F32.mEntries[5] = new Entry(DataType.FLOAT, DataKind.Z, false, 32, "z");
        NORM_XYZ_F32.init(rs);

        NORM_ST_XYZ_F32.mEntries = new Entry[8];
        NORM_ST_XYZ_F32.mEntries[0] = new Entry(DataType.FLOAT, DataKind.NX, false, 32, "nx");
        NORM_ST_XYZ_F32.mEntries[1] = new Entry(DataType.FLOAT, DataKind.NY, false, 32, "ny");
        NORM_ST_XYZ_F32.mEntries[2] = new Entry(DataType.FLOAT, DataKind.NZ, false, 32, "nz");
        NORM_ST_XYZ_F32.mEntries[3] = new Entry(DataType.FLOAT, DataKind.S, false, 32, "s");
        NORM_ST_XYZ_F32.mEntries[4] = new Entry(DataType.FLOAT, DataKind.T, false, 32, "t");
        NORM_ST_XYZ_F32.mEntries[5] = new Entry(DataType.FLOAT, DataKind.X, false, 32, "x");
        NORM_ST_XYZ_F32.mEntries[6] = new Entry(DataType.FLOAT, DataKind.Y, false, 32, "y");
        NORM_ST_XYZ_F32.mEntries[7] = new Entry(DataType.FLOAT, DataKind.Z, false, 32, "z");
        NORM_ST_XYZ_F32.init(rs);

        rs.nInitElements(A_8.mID, RGBA_4444.mID, RGBA_8888.mID, RGB_565.mID);
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

    Element() {
        super(null);
        mID = 0;
        mSize = 0;
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

    void init(RenderScript rs) {
        mRS = rs;
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
            Element e = new Element();
            e.mEntries = new Entry[mEntryCount];
            java.lang.System.arraycopy(mEntries, 0, e.mEntries, 0, mEntryCount);
            e.init(mRS);
            return e;
        }
    }

}

