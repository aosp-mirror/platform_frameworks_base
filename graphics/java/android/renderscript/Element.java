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
public class Element extends BaseObj {
    int mSize;
    Element[] mElements;
    String[] mElementNames;

    DataType mType;
    DataKind mKind;
    boolean mNormalized;
    int mVectorSize;

    int getSizeBytes() {return mSize;}

    public enum DataType {
        //FLOAT_16 (1, 2),
        FLOAT_32 (2, 4),
        //FLOAT_64 (3, 8),
        SIGNED_8 (4, 1),
        SIGNED_16 (5, 2),
        SIGNED_32 (6, 4),
        //SIGNED_64 (7, 8),
        UNSIGNED_8 (8, 1),
        UNSIGNED_16 (9, 2),
        UNSIGNED_32 (10, 4),
        //UNSIGNED_64 (11, 8),

        BOOLEAN(12, 1),

        UNSIGNED_5_6_5 (13, 2),
        UNSIGNED_5_5_5_1 (14, 2),
        UNSIGNED_4_4_4_4 (15, 2),

        RS_ELEMENT (16, 4),
        RS_TYPE (17, 4),
        RS_ALLOCATION (18, 4),
        RS_SAMPLER (19, 4),
        RS_SCRIPT (20, 4),
        RS_MESH (21, 4),
        RS_PROGRAM_FRAGMENT (22, 4),
        RS_PROGRAM_VERTEX (23, 4),
        RS_PROGRAM_RASTER (24, 4),
        RS_PROGRAM_STORE (25, 4);

        int mID;
        int mSize;
        DataType(int id, int size) {
            mID = id;
            mSize = size;
        }
    }

    public enum DataKind {
        USER (0),

        PIXEL_L (7),
        PIXEL_A (8),
        PIXEL_LA (9),
        PIXEL_RGB (10),
        PIXEL_RGBA (11);

        int mID;
        DataKind(int id) {
            mID = id;
        }
    }

    public static Element BOOLEAN(RenderScript rs) {
        if(rs.mElement_BOOLEAN == null) {
            rs.mElement_BOOLEAN = createUser(rs, DataType.BOOLEAN);
        }
        return rs.mElement_BOOLEAN;
    }

    public static Element U8(RenderScript rs) {
        if(rs.mElement_U8 == null) {
            rs.mElement_U8 = createUser(rs, DataType.UNSIGNED_8);
        }
        return rs.mElement_U8;
    }

    public static Element I8(RenderScript rs) {
        if(rs.mElement_I8 == null) {
            rs.mElement_I8 = createUser(rs, DataType.SIGNED_8);
        }
        return rs.mElement_I8;
    }

    public static Element U16(RenderScript rs) {
        if(rs.mElement_U16 == null) {
            rs.mElement_U16 = createUser(rs, DataType.UNSIGNED_16);
        }
        return rs.mElement_U16;
    }

    public static Element I16(RenderScript rs) {
        if(rs.mElement_I16 == null) {
            rs.mElement_I16 = createUser(rs, DataType.SIGNED_16);
        }
        return rs.mElement_I16;
    }

    public static Element U32(RenderScript rs) {
        if(rs.mElement_U32 == null) {
            rs.mElement_U32 = createUser(rs, DataType.UNSIGNED_32);
        }
        return rs.mElement_U32;
    }

    public static Element I32(RenderScript rs) {
        if(rs.mElement_I32 == null) {
            rs.mElement_I32 = createUser(rs, DataType.SIGNED_32);
        }
        return rs.mElement_I32;
    }

    public static Element F32(RenderScript rs) {
        if(rs.mElement_F32 == null) {
            rs.mElement_F32 = createUser(rs, DataType.FLOAT_32);
        }
        return rs.mElement_F32;
    }

    public static Element ELEMENT(RenderScript rs) {
        if(rs.mElement_ELEMENT == null) {
            rs.mElement_ELEMENT = createUser(rs, DataType.RS_ELEMENT);
        }
        return rs.mElement_ELEMENT;
    }

    public static Element TYPE(RenderScript rs) {
        if(rs.mElement_TYPE == null) {
            rs.mElement_TYPE = createUser(rs, DataType.RS_TYPE);
        }
        return rs.mElement_TYPE;
    }

    public static Element ALLOCATION(RenderScript rs) {
        if(rs.mElement_ALLOCATION == null) {
            rs.mElement_ALLOCATION = createUser(rs, DataType.RS_ALLOCATION);
        }
        return rs.mElement_ALLOCATION;
    }

    public static Element SAMPLER(RenderScript rs) {
        if(rs.mElement_SAMPLER == null) {
            rs.mElement_SAMPLER = createUser(rs, DataType.RS_SAMPLER);
        }
        return rs.mElement_SAMPLER;
    }

    public static Element SCRIPT(RenderScript rs) {
        if(rs.mElement_SCRIPT == null) {
            rs.mElement_SCRIPT = createUser(rs, DataType.RS_SCRIPT);
        }
        return rs.mElement_SCRIPT;
    }

    public static Element MESH(RenderScript rs) {
        if(rs.mElement_MESH == null) {
            rs.mElement_MESH = createUser(rs, DataType.RS_MESH);
        }
        return rs.mElement_MESH;
    }

    public static Element PROGRAM_FRAGMENT(RenderScript rs) {
        if(rs.mElement_PROGRAM_FRAGMENT == null) {
            rs.mElement_PROGRAM_FRAGMENT = createUser(rs, DataType.RS_PROGRAM_FRAGMENT);
        }
        return rs.mElement_PROGRAM_FRAGMENT;
    }

    public static Element PROGRAM_VERTEX(RenderScript rs) {
        if(rs.mElement_PROGRAM_VERTEX == null) {
            rs.mElement_PROGRAM_VERTEX = createUser(rs, DataType.RS_PROGRAM_VERTEX);
        }
        return rs.mElement_PROGRAM_VERTEX;
    }

    public static Element PROGRAM_RASTER(RenderScript rs) {
        if(rs.mElement_PROGRAM_RASTER == null) {
            rs.mElement_PROGRAM_RASTER = createUser(rs, DataType.RS_PROGRAM_RASTER);
        }
        return rs.mElement_PROGRAM_RASTER;
    }

    public static Element PROGRAM_STORE(RenderScript rs) {
        if(rs.mElement_PROGRAM_STORE == null) {
            rs.mElement_PROGRAM_STORE = createUser(rs, DataType.RS_PROGRAM_STORE);
        }
        return rs.mElement_PROGRAM_STORE;
    }


    public static Element A_8(RenderScript rs) {
        if(rs.mElement_A_8 == null) {
            rs.mElement_A_8 = createPixel(rs, DataType.UNSIGNED_8, DataKind.PIXEL_A);
        }
        return rs.mElement_A_8;
    }

    public static Element RGB_565(RenderScript rs) {
        if(rs.mElement_RGB_565 == null) {
            rs.mElement_RGB_565 = createPixel(rs, DataType.UNSIGNED_5_6_5, DataKind.PIXEL_RGB);
        }
        return rs.mElement_RGB_565;
    }

    public static Element RGB_888(RenderScript rs) {
        if(rs.mElement_RGB_888 == null) {
            rs.mElement_RGB_888 = createPixel(rs, DataType.UNSIGNED_8, DataKind.PIXEL_RGB);
        }
        return rs.mElement_RGB_888;
    }

    public static Element RGBA_5551(RenderScript rs) {
        if(rs.mElement_RGBA_5551 == null) {
            rs.mElement_RGBA_5551 = createPixel(rs, DataType.UNSIGNED_5_5_5_1, DataKind.PIXEL_RGBA);
        }
        return rs.mElement_RGBA_5551;
    }

    public static Element RGBA_4444(RenderScript rs) {
        if(rs.mElement_RGBA_4444 == null) {
            rs.mElement_RGBA_4444 = createPixel(rs, DataType.UNSIGNED_4_4_4_4, DataKind.PIXEL_RGBA);
        }
        return rs.mElement_RGBA_4444;
    }

    public static Element RGBA_8888(RenderScript rs) {
        if(rs.mElement_RGBA_8888 == null) {
            rs.mElement_RGBA_8888 = createPixel(rs, DataType.UNSIGNED_8, DataKind.PIXEL_RGBA);
        }
        return rs.mElement_RGBA_8888;
    }

    public static Element F32_2(RenderScript rs) {
        if(rs.mElement_FLOAT_2 == null) {
            rs.mElement_FLOAT_2 = createVector(rs, DataType.FLOAT_32, 2);
        }
        return rs.mElement_FLOAT_2;
    }

    public static Element F32_3(RenderScript rs) {
        if(rs.mElement_FLOAT_3 == null) {
            rs.mElement_FLOAT_3 = createVector(rs, DataType.FLOAT_32, 3);
        }
        return rs.mElement_FLOAT_3;
    }

    public static Element F32_4(RenderScript rs) {
        if(rs.mElement_FLOAT_4 == null) {
            rs.mElement_FLOAT_4 = createVector(rs, DataType.FLOAT_32, 4);
        }
        return rs.mElement_FLOAT_4;
    }

    public static Element U8_4(RenderScript rs) {
        if(rs.mElement_UCHAR_4 == null) {
            rs.mElement_UCHAR_4 = createVector(rs, DataType.UNSIGNED_8, 4);
        }
        return rs.mElement_UCHAR_4;
    }


    Element(RenderScript rs, Element[] e, String[] n) {
        super(rs);
        mSize = 0;
        mElements = e;
        mElementNames = n;
        int[] ids = new int[mElements.length];
        for (int ct = 0; ct < mElements.length; ct++ ) {
            mSize += mElements[ct].mSize;
            ids[ct] = mElements[ct].mID;
        }
        mID = rs.nElementCreate2(ids, mElementNames);
    }

    Element(RenderScript rs, DataType dt, DataKind dk, boolean norm, int size) {
        super(rs);
        mSize = dt.mSize * size;
        mType = dt;
        mKind = dk;
        mNormalized = norm;
        mVectorSize = size;
        mID = rs.nElementCreate(dt.mID, dk.mID, norm, size);
    }

    Element(RenderScript rs, int id) {
        super(rs);
        mID = id;
    }

    @Override
    void updateFromNative() {

        // we will pack mType; mKind; mNormalized; mVectorSize; NumSubElements
        int[] dataBuffer = new int[5];
        mRS.nElementGetNativeData(mID, dataBuffer);
        for (DataType dt: DataType.values()) {
            if(dt.mID == dataBuffer[0]){
                mType = dt;
            }
        }
        for (DataKind dk: DataKind.values()) {
            if(dk.mID == dataBuffer[1]){
                mKind = dk;
            }
        }

        mNormalized = dataBuffer[2] == 1 ? true : false;
        mVectorSize = dataBuffer[3];
        int numSubElements = dataBuffer[4];
        if(numSubElements > 0) {
            mElements = new Element[numSubElements];
            mElementNames = new String[numSubElements];

            int[] subElementIds = new int[numSubElements];
            mRS.nElementGetSubElements(mID, subElementIds, mElementNames);
            for(int i = 0; i < numSubElements; i ++) {
                mElements[i] = new Element(mRS, subElementIds[i]);
                mElements[i].updateFromNative();
            }
        }

    }

    public void destroy() throws IllegalStateException {
        super.destroy();
    }

    /////////////////////////////////////////
    public static Element createUser(RenderScript rs, DataType dt) {
        return new Element(rs, dt, DataKind.USER, false, 1);
    }

    public static Element createVector(RenderScript rs, DataType dt, int size) {
        if (size < 2 || size > 4) {
            throw new IllegalArgumentException("Bad size");
        }
        return new Element(rs, dt, DataKind.USER, false, size);
    }

    public static Element createPixel(RenderScript rs, DataType dt, DataKind dk) {
        if (!(dk == DataKind.PIXEL_L ||
              dk == DataKind.PIXEL_A ||
              dk == DataKind.PIXEL_LA ||
              dk == DataKind.PIXEL_RGB ||
              dk == DataKind.PIXEL_RGBA)) {
            throw new IllegalArgumentException("Unsupported DataKind");
        }
        if (!(dt == DataType.UNSIGNED_8 ||
              dt == DataType.UNSIGNED_5_6_5 ||
              dt == DataType.UNSIGNED_4_4_4_4 ||
              dt == DataType.UNSIGNED_5_5_5_1)) {
            throw new IllegalArgumentException("Unsupported DataType");
        }
        if (dt == DataType.UNSIGNED_5_6_5 && dk != DataKind.PIXEL_RGB) {
            throw new IllegalArgumentException("Bad kind and type combo");
        }
        if (dt == DataType.UNSIGNED_5_5_5_1 && dk != DataKind.PIXEL_RGBA) {
            throw new IllegalArgumentException("Bad kind and type combo");
        }
        if (dt == DataType.UNSIGNED_4_4_4_4 && dk != DataKind.PIXEL_RGBA) {
            throw new IllegalArgumentException("Bad kind and type combo");
        }

        int size = 1;
        if (dk == DataKind.PIXEL_LA) {
            size = 2;
        }
        if (dk == DataKind.PIXEL_RGB) {
            size = 3;
        }
        if (dk == DataKind.PIXEL_RGBA) {
            size = 4;
        }

        return new Element(rs, dt, dk, true, size);
    }

    public static class Builder {
        RenderScript mRS;
        Element[] mElements;
        String[] mElementNames;
        int mCount;

        public Builder(RenderScript rs) {
            mRS = rs;
            mCount = 0;
            mElements = new Element[8];
            mElementNames = new String[8];
        }

        public void add(Element element, String name) {
            if(mCount == mElements.length) {
                Element[] e = new Element[mCount + 8];
                String[] s = new String[mCount + 8];
                System.arraycopy(mElements, 0, e, 0, mCount);
                System.arraycopy(mElementNames, 0, s, 0, mCount);
                mElements = e;
                mElementNames = s;
            }
            mElements[mCount] = element;
            mElementNames[mCount] = name;
            mCount++;
        }

        public Element create() {
            mRS.validate();
            Element[] ein = new Element[mCount];
            String[] sin = new String[mCount];
            java.lang.System.arraycopy(mElements, 0, ein, 0, mCount);
            java.lang.System.arraycopy(mElementNames, 0, sin, 0, mCount);
            return new Element(mRS, ein, sin);
        }
    }

    static void initPredefined(RenderScript rs) {
        int a8 = rs.nElementCreate(DataType.UNSIGNED_8.mID,
                                   DataKind.PIXEL_A.mID, true, 1);
        int rgba4444 = rs.nElementCreate(DataType.UNSIGNED_4_4_4_4.mID,
                                         DataKind.PIXEL_RGBA.mID, true, 4);
        int rgba8888 = rs.nElementCreate(DataType.UNSIGNED_8.mID,
                                         DataKind.PIXEL_RGBA.mID, true, 4);
        int rgb565 = rs.nElementCreate(DataType.UNSIGNED_5_6_5.mID,
                                       DataKind.PIXEL_RGB.mID, true, 3);
        rs.nInitElements(a8, rgba4444, rgba8888, rgb565);
    }
}

