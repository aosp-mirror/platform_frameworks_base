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
    final int mPredefinedID;
    final boolean mIsPredefined;

    public static final Element USER_U8 = new Element(0);
    public static final Element USER_I8 = new Element(1);
    public static final Element USER_U16 = new Element(2);
    public static final Element USER_I16 = new Element(3);
    public static final Element USER_U32 = new Element(4);
    public static final Element USER_I32 = new Element(5);
    public static final Element USER_FLOAT = new Element(6);

    public static final Element A_8 = new Element(7);
    public static final Element RGB_565 = new Element(8);
    public static final Element RGB_888 = new Element(11);
    public static final Element RGBA_5551 = new Element(9);
    public static final Element RGBA_4444 = new Element(10);
    public static final Element RGBA_8888 = new Element(12);

    public static final Element INDEX_16 = new Element(13);
    public static final Element INDEX_32 = new Element(14);
    public static final Element XY_F32 = new Element(15);
    public static final Element XYZ_F32 = new Element(16);
    public static final Element ST_XY_F32 = new Element(17);
    public static final Element ST_XYZ_F32 = new Element(18);
    public static final Element NORM_XYZ_F32 = new Element(19);
    public static final Element NORM_ST_XYZ_F32 = new Element(20);

    void initPredef(RenderScript rs) {
        mID = rs.nElementGetPredefined(mPredefinedID);
    }

    static void init(RenderScript rs) {
        USER_U8.initPredef(rs);
        USER_I8.initPredef(rs);
        USER_U16.initPredef(rs);
        USER_I16.initPredef(rs);
        USER_U32.initPredef(rs);
        USER_I32.initPredef(rs);
        USER_FLOAT.initPredef(rs);

        A_8.initPredef(rs);
        RGB_565.initPredef(rs);
        RGB_888.initPredef(rs);
        RGBA_5551.initPredef(rs);
        RGBA_4444.initPredef(rs);
        RGBA_8888.initPredef(rs);

        INDEX_16.initPredef(rs);
        INDEX_32.initPredef(rs);
        XY_F32.initPredef(rs);
        XYZ_F32.initPredef(rs);
        ST_XY_F32.initPredef(rs);
        ST_XYZ_F32.initPredef(rs);
        NORM_XYZ_F32.initPredef(rs);
        NORM_ST_XYZ_F32.initPredef(rs);
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
        INDEX (18);

        int mID;
        DataKind(int id) {
            mID = id;
        }
    }


    Element(int predef) {
        super(null);
        mID = 0;
        mPredefinedID = predef;
        mIsPredefined = true;
    }

    Element(int id, RenderScript rs) {
        super(rs);
        mID = id;
        mPredefinedID = 0;
        mIsPredefined = false;
    }

    public void destroy() throws IllegalStateException {
        if(mIsPredefined) {
            throw new IllegalStateException("Attempting to destroy a predefined Element.");
        }
        if(mDestroyed) {
            throw new IllegalStateException("Object already destroyed.");
        }
        mDestroyed = true;
        mRS.nElementDestroy(mID);
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


    public static class Builder {
        RenderScript mRS;
        Entry[] mEntries;
        int mEntryCount;

        private class Entry {
            Element mElement;
            Element.DataType mType;
            Element.DataKind mKind;
            boolean mIsNormalized;
            int mBits;
            String mName;
        }

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

        public Builder add(Element e) throws IllegalArgumentException {
            if(!e.mIsPredefined) {
                throw new IllegalArgumentException("add requires a predefined Element.");
            }
            Entry en = new Entry();
            en.mElement = e;
            addEntry(en);
            return this;
        }

        public Builder add(Element.DataType dt, Element.DataKind dk, boolean isNormalized, int bits, String name) {
            Entry en = new Entry();
            en.mType = dt;
            en.mKind = dk;
            en.mIsNormalized = isNormalized;
            en.mBits = bits;
            en.mName = name;
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

        public Builder addFloatXYZ() {
            add(DataType.FLOAT, DataKind.X, false, 32, null);
            add(DataType.FLOAT, DataKind.Y, false, 32, null);
            add(DataType.FLOAT, DataKind.Z, false, 32, null);
            return this;
        }

        public Builder addFloatRGB() {
            add(DataType.FLOAT, DataKind.RED, false, 32, null);
            add(DataType.FLOAT, DataKind.GREEN, false, 32, null);
            add(DataType.FLOAT, DataKind.BLUE, false, 32, null);
            return this;
        }

        public Builder addFloatRGBA() {
            add(DataType.FLOAT, DataKind.RED, false, 32, null);
            add(DataType.FLOAT, DataKind.GREEN, false, 32, null);
            add(DataType.FLOAT, DataKind.BLUE, false, 32, null);
            add(DataType.FLOAT, DataKind.ALPHA, false, 32, null);
            return this;
        }

        public Builder addUNorm8RGBA() {
            add(DataType.UNSIGNED, DataKind.RED, true, 8, null);
            add(DataType.UNSIGNED, DataKind.GREEN, true, 8, null);
            add(DataType.UNSIGNED, DataKind.BLUE, true, 8, null);
            add(DataType.UNSIGNED, DataKind.ALPHA, true, 8, null);
            return this;
        }

        static synchronized Element internalCreate(RenderScript rs, Builder b) {
            rs.nElementBegin();
            for (int ct=0; ct < b.mEntryCount; ct++) {
                Entry en = b.mEntries[ct];
                if(en.mElement !=  null) {
                    rs.nElementAddPredefined(en.mElement.mPredefinedID);
                } else {
                    int norm = 0;
                    if (en.mIsNormalized) {
                        norm = 1;
                    }
                    rs.nElementAdd(en.mKind.mID, en.mType.mID, norm, en.mBits, en.mName);
                }
            }
            int id = rs.nElementCreate();
            return new Element(id, rs);
        }

        public Element create() {
            return internalCreate(mRS, this);
        }
    }

}

