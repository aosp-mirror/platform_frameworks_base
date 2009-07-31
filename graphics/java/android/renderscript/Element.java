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
        mRS.nElementDestroy(mID);
        mID = 0;
    }




    public static class Builder {
        RenderScript mRS;
        boolean mActive = true;

        Builder(RenderScript rs) {
            mRS = rs;
        }

        void begin() throws IllegalStateException {
            if (mActive) {
                throw new IllegalStateException("Element builder already active.");
            }
            mRS.nElementBegin();
            mActive = true;
        }

        public Builder add(Element e) throws IllegalArgumentException, IllegalStateException {
            if(!mActive) {
                throw new IllegalStateException("Element builder not active.");
            }
            if(!e.mIsPredefined) {
                throw new IllegalArgumentException("add requires a predefined Element.");
            }
            mRS.nElementAddPredefined(e.mID);
            return this;
        }

        public Builder add(Element.DataType dt, Element.DataKind dk, boolean isNormalized, int bits)
            throws IllegalStateException {
            if(!mActive) {
                throw new IllegalStateException("Element builder not active.");
            }
            int norm = 0;
            if (isNormalized) {
                norm = 1;
            }
            mRS.nElementAdd(dt.mID, dk.mID, norm, bits);
            return this;
        }

        public void abort() throws IllegalStateException {
            if(!mActive) {
                throw new IllegalStateException("Element builder not active.");
            }
            mActive = false;
        }

        public Element create() throws IllegalStateException {
            if(!mActive) {
                throw new IllegalStateException("Element builder not active.");
            }
            int id = mRS.nElementCreate();
            mActive = false;
            return new Element(id, mRS);
        }
    }

}

