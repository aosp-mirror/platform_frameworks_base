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
 * <p>The most basic data type. An element represents one cell of a memory allocation.
 * Element is the basic data type of Renderscript. An element can be of two forms: Basic elements or Complex forms.
 * Examples of basic elements are:</p>
 * <ul>
 *  <li>Single float value</li>
 *  <li>4 element float vector</li>
 *  <li>single RGB-565 color</li>
 *  <li>single unsigned int 16</li>
 * </ul>
 * <p>Complex elements contain a list of sub-elements and names that
 * represents a structure of data. The fields can be accessed by name
 * from a script or shader. The memory layout is defined and ordered. Data
 * alignment is determined by the most basic primitive type. i.e. a float4
 * vector will be aligned to sizeof(float) and not sizeof(float4). The
 * ordering of elements in memory will be the order in which they were added
 * with each component aligned as necessary. No re-ordering will be done.</p>
 *
 * <p>The primary source of elements are from scripts. A script that exports a
 * bind point for a data structure generates a Renderscript element to represent the
 * data exported by the script. The other common source of elements is from bitmap formats.</p>
 **/
public class Element extends BaseObj {
    int mSize;
    Element[] mElements;
    String[] mElementNames;
    int[] mArraySizes;
    int[] mOffsetInBytes;

    int[] mVisibleElementMap;

    DataType mType;
    DataKind mKind;
    boolean mNormalized;
    int mVectorSize;

    private void updateVisibleSubElements() {
        if (mElements == null) {
            return;
        }

        int noPaddingFieldCount = 0;
        int fieldCount = mElementNames.length;
        // Find out how many elements are not padding
        for (int ct = 0; ct < fieldCount; ct ++) {
            if (mElementNames[ct].charAt(0) != '#') {
                noPaddingFieldCount ++;
            }
        }
        mVisibleElementMap = new int[noPaddingFieldCount];

        // Make a map that points us at non-padding elements
        for (int ct = 0, ctNoPadding = 0; ct < fieldCount; ct ++) {
            if (mElementNames[ct].charAt(0) != '#') {
                mVisibleElementMap[ctNoPadding ++] = ct;
            }
        }
    }

    /**
    * @hide
    * @return element size in bytes
    */
    public int getSizeBytes() {return mSize;}

    /**
    * @hide
    * @return element vector size
    */
    public int getVectorSize() {return mVectorSize;}


    /**
     * DataType represents the basic type information for a basic element.  The
     * naming convention follows.  For numeric types it is FLOAT,
     * SIGNED, or UNSIGNED followed by the _BITS where BITS is the
     * size of the data.  BOOLEAN is a true / false (1,0)
     * represented in an 8 bit container.  The UNSIGNED variants
     * with multiple bit definitions are for packed graphical data
     * formats and represent vectors with per vector member sizes
     * which are treated as a single unit for packing and alignment
     * purposes.
     *
     * MATRIX the three matrix types contain FLOAT_32 elements and are treated
     * as 32 bits for alignment purposes.
     *
     * RS_* objects.  32 bit opaque handles.
     */
    public enum DataType {
        /**
        * @hide
        * new enum
        */
        NONE (0, 0),
        //FLOAT_16 (1, 2),
        FLOAT_32 (2, 4),
        FLOAT_64 (3, 8),
        SIGNED_8 (4, 1),
        SIGNED_16 (5, 2),
        SIGNED_32 (6, 4),
        SIGNED_64 (7, 8),
        UNSIGNED_8 (8, 1),
        UNSIGNED_16 (9, 2),
        UNSIGNED_32 (10, 4),
        UNSIGNED_64 (11, 8),

        BOOLEAN(12, 1),

        UNSIGNED_5_6_5 (13, 2),
        UNSIGNED_5_5_5_1 (14, 2),
        UNSIGNED_4_4_4_4 (15, 2),

        MATRIX_4X4 (16, 64),
        MATRIX_3X3 (17, 36),
        MATRIX_2X2 (18, 16),

        RS_ELEMENT (1000, 4),
        RS_TYPE (1001, 4),
        RS_ALLOCATION (1002, 4),
        RS_SAMPLER (1003, 4),
        RS_SCRIPT (1004, 4),
        RS_MESH (1005, 4),
        RS_PROGRAM_FRAGMENT (1006, 4),
        RS_PROGRAM_VERTEX (1007, 4),
        RS_PROGRAM_RASTER (1008, 4),
        RS_PROGRAM_STORE (1009, 4);

        int mID;
        int mSize;
        DataType(int id, int size) {
            mID = id;
            mSize = size;
        }
    }

    /**
     * The special interpretation of the data if required.  This is primarly
     * useful for graphical data.  USER indicates no special interpretation is
     * expected.  PIXEL is used in conjunction with the standard data types for
     * representing texture formats.
     */
    public enum DataKind {
        USER (0),

        PIXEL_L (7),
        PIXEL_A (8),
        PIXEL_LA (9),
        PIXEL_RGB (10),
        PIXEL_RGBA (11),
        PIXEL_DEPTH (12);

        int mID;
        DataKind(int id) {
            mID = id;
        }
    }

    /**
     * Return if a element is too complex for use as a data source for a Mesh or
     * a Program.
     *
     * @return boolean
     */
    public boolean isComplex() {
        if (mElements == null) {
            return false;
        }
        for (int ct=0; ct < mElements.length; ct++) {
            if (mElements[ct].mElements != null) {
                return true;
            }
        }
        return false;
    }

    /**
    * @hide
    * @return number of sub-elements in this element
    */
    public int getSubElementCount() {
        if (mVisibleElementMap == null) {
            return 0;
        }
        return mVisibleElementMap.length;
    }

    /**
    * @hide
    * @param index index of the sub-element to return
    * @return sub-element in this element at given index
    */
    public Element getSubElement(int index) {
        if (mVisibleElementMap == null) {
            throw new RSIllegalArgumentException("Element contains no sub-elements");
        }
        if (index < 0 || index >= mVisibleElementMap.length) {
            throw new RSIllegalArgumentException("Illegal sub-element index");
        }
        return mElements[mVisibleElementMap[index]];
    }

    /**
    * @hide
    * @param index index of the sub-element
    * @return sub-element in this element at given index
    */
    public String getSubElementName(int index) {
        if (mVisibleElementMap == null) {
            throw new RSIllegalArgumentException("Element contains no sub-elements");
        }
        if (index < 0 || index >= mVisibleElementMap.length) {
            throw new RSIllegalArgumentException("Illegal sub-element index");
        }
        return mElementNames[mVisibleElementMap[index]];
    }

    /**
    * @hide
    * @param index index of the sub-element
    * @return array size of sub-element in this element at given index
    */
    public int getSubElementArraySize(int index) {
        if (mVisibleElementMap == null) {
            throw new RSIllegalArgumentException("Element contains no sub-elements");
        }
        if (index < 0 || index >= mVisibleElementMap.length) {
            throw new RSIllegalArgumentException("Illegal sub-element index");
        }
        return mArraySizes[mVisibleElementMap[index]];
    }

    /**
    * @hide
    * @param index index of the sub-element
    * @return offset in bytes of sub-element in this element at given index
    */
    public int getSubElementOffsetBytes(int index) {
        if (mVisibleElementMap == null) {
            throw new RSIllegalArgumentException("Element contains no sub-elements");
        }
        if (index < 0 || index >= mVisibleElementMap.length) {
            throw new RSIllegalArgumentException("Illegal sub-element index");
        }
        return mOffsetInBytes[mVisibleElementMap[index]];
    }

    /**
    * @hide
    * @return element data type
    */
    public DataType getDataType() {
        return mType;
    }

    /**
    * @hide
    * @return element data kind
    */
    public DataKind getDataKind() {
        return mKind;
    }

    /**
     * Utility function for returning an Element containing a single Boolean.
     *
     * @param rs Context to which the element will belong.
     *
     * @return Element
     */
    public static Element BOOLEAN(RenderScript rs) {
        if(rs.mElement_BOOLEAN == null) {
            rs.mElement_BOOLEAN = createUser(rs, DataType.BOOLEAN);
        }
        return rs.mElement_BOOLEAN;
    }

    /**
     * Utility function for returning an Element containing a single UNSIGNED_8.
     *
     * @param rs Context to which the element will belong.
     *
     * @return Element
     */
    public static Element U8(RenderScript rs) {
        if(rs.mElement_U8 == null) {
            rs.mElement_U8 = createUser(rs, DataType.UNSIGNED_8);
        }
        return rs.mElement_U8;
    }

    /**
     * Utility function for returning an Element containing a single SIGNED_8.
     *
     * @param rs Context to which the element will belong.
     *
     * @return Element
     */
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

    public static Element U64(RenderScript rs) {
        if(rs.mElement_U64 == null) {
            rs.mElement_U64 = createUser(rs, DataType.UNSIGNED_64);
        }
        return rs.mElement_U64;
    }

    public static Element I64(RenderScript rs) {
        if(rs.mElement_I64 == null) {
            rs.mElement_I64 = createUser(rs, DataType.SIGNED_64);
        }
        return rs.mElement_I64;
    }

    public static Element F32(RenderScript rs) {
        if(rs.mElement_F32 == null) {
            rs.mElement_F32 = createUser(rs, DataType.FLOAT_32);
        }
        return rs.mElement_F32;
    }

    public static Element F64(RenderScript rs) {
        if(rs.mElement_F64 == null) {
            rs.mElement_F64 = createUser(rs, DataType.FLOAT_64);
        }
        return rs.mElement_F64;
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

    public static Element F64_2(RenderScript rs) {
        if(rs.mElement_DOUBLE_2 == null) {
            rs.mElement_DOUBLE_2 = createVector(rs, DataType.FLOAT_64, 2);
        }
        return rs.mElement_DOUBLE_2;
    }

    public static Element F64_3(RenderScript rs) {
        if(rs.mElement_DOUBLE_3 == null) {
            rs.mElement_DOUBLE_3 = createVector(rs, DataType.FLOAT_64, 3);
        }
        return rs.mElement_DOUBLE_3;
    }

    public static Element F64_4(RenderScript rs) {
        if(rs.mElement_DOUBLE_4 == null) {
            rs.mElement_DOUBLE_4 = createVector(rs, DataType.FLOAT_64, 4);
        }
        return rs.mElement_DOUBLE_4;
    }

    public static Element U8_2(RenderScript rs) {
        if(rs.mElement_UCHAR_2 == null) {
            rs.mElement_UCHAR_2 = createVector(rs, DataType.UNSIGNED_8, 2);
        }
        return rs.mElement_UCHAR_2;
    }

    public static Element U8_3(RenderScript rs) {
        if(rs.mElement_UCHAR_3 == null) {
            rs.mElement_UCHAR_3 = createVector(rs, DataType.UNSIGNED_8, 3);
        }
        return rs.mElement_UCHAR_3;
    }

    public static Element U8_4(RenderScript rs) {
        if(rs.mElement_UCHAR_4 == null) {
            rs.mElement_UCHAR_4 = createVector(rs, DataType.UNSIGNED_8, 4);
        }
        return rs.mElement_UCHAR_4;
    }

    public static Element I8_2(RenderScript rs) {
        if(rs.mElement_CHAR_2 == null) {
            rs.mElement_CHAR_2 = createVector(rs, DataType.SIGNED_8, 2);
        }
        return rs.mElement_CHAR_2;
    }

    public static Element I8_3(RenderScript rs) {
        if(rs.mElement_CHAR_3 == null) {
            rs.mElement_CHAR_3 = createVector(rs, DataType.SIGNED_8, 3);
        }
        return rs.mElement_CHAR_3;
    }

    public static Element I8_4(RenderScript rs) {
        if(rs.mElement_CHAR_4 == null) {
            rs.mElement_CHAR_4 = createVector(rs, DataType.SIGNED_8, 4);
        }
        return rs.mElement_CHAR_4;
    }

    public static Element U16_2(RenderScript rs) {
        if(rs.mElement_USHORT_2 == null) {
            rs.mElement_USHORT_2 = createVector(rs, DataType.UNSIGNED_16, 2);
        }
        return rs.mElement_USHORT_2;
    }

    public static Element U16_3(RenderScript rs) {
        if(rs.mElement_USHORT_3 == null) {
            rs.mElement_USHORT_3 = createVector(rs, DataType.UNSIGNED_16, 3);
        }
        return rs.mElement_USHORT_3;
    }

    public static Element U16_4(RenderScript rs) {
        if(rs.mElement_USHORT_4 == null) {
            rs.mElement_USHORT_4 = createVector(rs, DataType.UNSIGNED_16, 4);
        }
        return rs.mElement_USHORT_4;
    }

    public static Element I16_2(RenderScript rs) {
        if(rs.mElement_SHORT_2 == null) {
            rs.mElement_SHORT_2 = createVector(rs, DataType.SIGNED_16, 2);
        }
        return rs.mElement_SHORT_2;
    }

    public static Element I16_3(RenderScript rs) {
        if(rs.mElement_SHORT_3 == null) {
            rs.mElement_SHORT_3 = createVector(rs, DataType.SIGNED_16, 3);
        }
        return rs.mElement_SHORT_3;
    }

    public static Element I16_4(RenderScript rs) {
        if(rs.mElement_SHORT_4 == null) {
            rs.mElement_SHORT_4 = createVector(rs, DataType.SIGNED_16, 4);
        }
        return rs.mElement_SHORT_4;
    }

    public static Element U32_2(RenderScript rs) {
        if(rs.mElement_UINT_2 == null) {
            rs.mElement_UINT_2 = createVector(rs, DataType.UNSIGNED_32, 2);
        }
        return rs.mElement_UINT_2;
    }

    public static Element U32_3(RenderScript rs) {
        if(rs.mElement_UINT_3 == null) {
            rs.mElement_UINT_3 = createVector(rs, DataType.UNSIGNED_32, 3);
        }
        return rs.mElement_UINT_3;
    }

    public static Element U32_4(RenderScript rs) {
        if(rs.mElement_UINT_4 == null) {
            rs.mElement_UINT_4 = createVector(rs, DataType.UNSIGNED_32, 4);
        }
        return rs.mElement_UINT_4;
    }

    public static Element I32_2(RenderScript rs) {
        if(rs.mElement_INT_2 == null) {
            rs.mElement_INT_2 = createVector(rs, DataType.SIGNED_32, 2);
        }
        return rs.mElement_INT_2;
    }

    public static Element I32_3(RenderScript rs) {
        if(rs.mElement_INT_3 == null) {
            rs.mElement_INT_3 = createVector(rs, DataType.SIGNED_32, 3);
        }
        return rs.mElement_INT_3;
    }

    public static Element I32_4(RenderScript rs) {
        if(rs.mElement_INT_4 == null) {
            rs.mElement_INT_4 = createVector(rs, DataType.SIGNED_32, 4);
        }
        return rs.mElement_INT_4;
    }

    public static Element U64_2(RenderScript rs) {
        if(rs.mElement_ULONG_2 == null) {
            rs.mElement_ULONG_2 = createVector(rs, DataType.UNSIGNED_64, 2);
        }
        return rs.mElement_ULONG_2;
    }

    public static Element U64_3(RenderScript rs) {
        if(rs.mElement_ULONG_3 == null) {
            rs.mElement_ULONG_3 = createVector(rs, DataType.UNSIGNED_64, 3);
        }
        return rs.mElement_ULONG_3;
    }

    public static Element U64_4(RenderScript rs) {
        if(rs.mElement_ULONG_4 == null) {
            rs.mElement_ULONG_4 = createVector(rs, DataType.UNSIGNED_64, 4);
        }
        return rs.mElement_ULONG_4;
    }

    public static Element I64_2(RenderScript rs) {
        if(rs.mElement_LONG_2 == null) {
            rs.mElement_LONG_2 = createVector(rs, DataType.SIGNED_64, 2);
        }
        return rs.mElement_LONG_2;
    }

    public static Element I64_3(RenderScript rs) {
        if(rs.mElement_LONG_3 == null) {
            rs.mElement_LONG_3 = createVector(rs, DataType.SIGNED_64, 3);
        }
        return rs.mElement_LONG_3;
    }

    public static Element I64_4(RenderScript rs) {
        if(rs.mElement_LONG_4 == null) {
            rs.mElement_LONG_4 = createVector(rs, DataType.SIGNED_64, 4);
        }
        return rs.mElement_LONG_4;
    }

    public static Element MATRIX_4X4(RenderScript rs) {
        if(rs.mElement_MATRIX_4X4 == null) {
            rs.mElement_MATRIX_4X4 = createUser(rs, DataType.MATRIX_4X4);
        }
        return rs.mElement_MATRIX_4X4;
    }
    public static Element MATRIX4X4(RenderScript rs) {
        return MATRIX_4X4(rs);
    }

    public static Element MATRIX_3X3(RenderScript rs) {
        if(rs.mElement_MATRIX_3X3 == null) {
            rs.mElement_MATRIX_3X3 = createUser(rs, DataType.MATRIX_3X3);
        }
        return rs.mElement_MATRIX_3X3;
    }

    public static Element MATRIX_2X2(RenderScript rs) {
        if(rs.mElement_MATRIX_2X2 == null) {
            rs.mElement_MATRIX_2X2 = createUser(rs, DataType.MATRIX_2X2);
        }
        return rs.mElement_MATRIX_2X2;
    }

    Element(int id, RenderScript rs, Element[] e, String[] n, int[] as) {
        super(id, rs);
        mSize = 0;
        mVectorSize = 1;
        mElements = e;
        mElementNames = n;
        mArraySizes = as;
        mType = DataType.NONE;
        mKind = DataKind.USER;
        mOffsetInBytes = new int[mElements.length];
        for (int ct = 0; ct < mElements.length; ct++ ) {
            mOffsetInBytes[ct] = mSize;
            mSize += mElements[ct].mSize * mArraySizes[ct];
        }
        updateVisibleSubElements();
    }

    Element(int id, RenderScript rs, DataType dt, DataKind dk, boolean norm, int size) {
        super(id, rs);
        if ((dt != DataType.UNSIGNED_5_6_5) &&
            (dt != DataType.UNSIGNED_4_4_4_4) &&
            (dt != DataType.UNSIGNED_5_5_5_1)) {
            if (size == 3) {
                mSize = dt.mSize * 4;
            } else {
                mSize = dt.mSize * size;
            }
        } else {
            mSize = dt.mSize;
        }
        mType = dt;
        mKind = dk;
        mNormalized = norm;
        mVectorSize = size;
    }

    Element(int id, RenderScript rs) {
        super(id, rs);
    }

    @Override
    void updateFromNative() {
        super.updateFromNative();

        // we will pack mType; mKind; mNormalized; mVectorSize; NumSubElements
        int[] dataBuffer = new int[5];
        mRS.nElementGetNativeData(getID(), dataBuffer);

        mNormalized = dataBuffer[2] == 1 ? true : false;
        mVectorSize = dataBuffer[3];
        mSize = 0;
        for (DataType dt: DataType.values()) {
            if(dt.mID == dataBuffer[0]){
                mType = dt;
                mSize = mType.mSize * mVectorSize;
            }
        }
        for (DataKind dk: DataKind.values()) {
            if(dk.mID == dataBuffer[1]){
                mKind = dk;
            }
        }

        int numSubElements = dataBuffer[4];
        if(numSubElements > 0) {
            mElements = new Element[numSubElements];
            mElementNames = new String[numSubElements];
            mArraySizes = new int[numSubElements];
            mOffsetInBytes = new int[numSubElements];

            int[] subElementIds = new int[numSubElements];
            mRS.nElementGetSubElements(getID(), subElementIds, mElementNames, mArraySizes);
            for(int i = 0; i < numSubElements; i ++) {
                mElements[i] = new Element(subElementIds[i], mRS);
                mElements[i].updateFromNative();
                mOffsetInBytes[i] = mSize;
                mSize += mElements[i].mSize * mArraySizes[i];
            }
        }
        updateVisibleSubElements();
    }

    /**
     * Create a custom Element of the specified DataType.  The DataKind will be
     * set to USER and the vector size to 1 indicating non-vector.
     *
     * @param rs The context associated with the new Element.
     * @param dt The DataType for the new element.
     * @return Element
     */
    static Element createUser(RenderScript rs, DataType dt) {
        DataKind dk = DataKind.USER;
        boolean norm = false;
        int vecSize = 1;
        int id = rs.nElementCreate(dt.mID, dk.mID, norm, vecSize);
        return new Element(id, rs, dt, dk, norm, vecSize);
    }

    /**
     * Create a custom vector element of the specified DataType and vector size.
     *  DataKind will be set to USER.
     *
     * @param rs The context associated with the new Element.
     * @param dt The DataType for the new element.
     * @param size Vector size for the new Element.  Range 2-4 inclusive
     *             supported.
     *
     * @return Element
     */
    public static Element createVector(RenderScript rs, DataType dt, int size) {
        if (size < 2 || size > 4) {
            throw new RSIllegalArgumentException("Vector size out of range 2-4.");
        }
        DataKind dk = DataKind.USER;
        boolean norm = false;
        int id = rs.nElementCreate(dt.mID, dk.mID, norm, size);
        return new Element(id, rs, dt, dk, norm, size);
    }

    /**
     * Create a new pixel Element type.  A matching DataType and DataKind must
     * be provided.  The DataType and DataKind must contain the same number of
     * components.  Vector size will be set to 1.
     *
     * @param rs The context associated with the new Element.
     * @param dt The DataType for the new element.
     * @param dk The DataKind to specify the mapping of each component in the
     *           DataType.
     *
     * @return Element
     */
    public static Element createPixel(RenderScript rs, DataType dt, DataKind dk) {
        if (!(dk == DataKind.PIXEL_L ||
              dk == DataKind.PIXEL_A ||
              dk == DataKind.PIXEL_LA ||
              dk == DataKind.PIXEL_RGB ||
              dk == DataKind.PIXEL_RGBA ||
              dk == DataKind.PIXEL_DEPTH)) {
            throw new RSIllegalArgumentException("Unsupported DataKind");
        }
        if (!(dt == DataType.UNSIGNED_8 ||
              dt == DataType.UNSIGNED_16 ||
              dt == DataType.UNSIGNED_5_6_5 ||
              dt == DataType.UNSIGNED_4_4_4_4 ||
              dt == DataType.UNSIGNED_5_5_5_1)) {
            throw new RSIllegalArgumentException("Unsupported DataType");
        }
        if (dt == DataType.UNSIGNED_5_6_5 && dk != DataKind.PIXEL_RGB) {
            throw new RSIllegalArgumentException("Bad kind and type combo");
        }
        if (dt == DataType.UNSIGNED_5_5_5_1 && dk != DataKind.PIXEL_RGBA) {
            throw new RSIllegalArgumentException("Bad kind and type combo");
        }
        if (dt == DataType.UNSIGNED_4_4_4_4 && dk != DataKind.PIXEL_RGBA) {
            throw new RSIllegalArgumentException("Bad kind and type combo");
        }
        if (dt == DataType.UNSIGNED_16 &&
            dk != DataKind.PIXEL_DEPTH) {
            throw new RSIllegalArgumentException("Bad kind and type combo");
        }

        int size = 1;
        switch (dk) {
        case PIXEL_LA:
            size = 2;
            break;
        case PIXEL_RGB:
            size = 3;
            break;
        case PIXEL_RGBA:
            size = 4;
            break;
        case PIXEL_DEPTH:
            size = 2;
            break;
        }

        boolean norm = true;
        int id = rs.nElementCreate(dt.mID, dk.mID, norm, size);
        return new Element(id, rs, dt, dk, norm, size);
    }

    /**
     * Check if the current Element is compatible with another Element.
     * Primitive Elements are compatible if they share the same underlying
     * size and type (i.e. U8 is compatible with A_8). User-defined Elements
     * must be equal in order to be compatible. This requires strict name
     * equivalence for all sub-Elements (in addition to structural equivalence).
     *
     * @param e The Element to check compatibility with.
     *
     * @return boolean true if the Elements are compatible, otherwise false.
     */
    public boolean isCompatible(Element e) {
        // Try strict BaseObj equality to start with.
        if (this.equals(e)) {
            return true;
        }

        // Ignore mKind because it is allowed to be different (user vs. pixel).
        // We also ignore mNormalized because it can be different. The mType
        // field must be non-null since we require name equivalence for
        // user-created Elements.
        return ((mSize == e.mSize) &&
                (mType != null) &&
                (mType == e.mType) &&
                (mVectorSize == e.mVectorSize));
    }

    /**
     * Builder class for producing complex elements with matching field and name
     * pairs.  The builder starts empty.  The order in which elements are added
     * is retained for the layout in memory.
     *
     */
    public static class Builder {
        RenderScript mRS;
        Element[] mElements;
        String[] mElementNames;
        int[] mArraySizes;
        int mCount;
        int mSkipPadding;

        /**
         * Create a builder object.
         *
         * @param rs
         */
        public Builder(RenderScript rs) {
            mRS = rs;
            mCount = 0;
            mElements = new Element[8];
            mElementNames = new String[8];
            mArraySizes = new int[8];
        }

        /**
         * Add an array of elements to this element.
         *
         * @param element
         * @param name
         * @param arraySize
         */
        public Builder add(Element element, String name, int arraySize) {
            if (arraySize < 1) {
                throw new RSIllegalArgumentException("Array size cannot be less than 1.");
            }

            // Skip padding fields after a vector 3 type.
            if (mSkipPadding != 0) {
                if (name.startsWith("#padding_")) {
                    mSkipPadding = 0;
                    return this;
                }
            }

            if (element.mVectorSize == 3) {
                mSkipPadding = 1;
            } else {
                mSkipPadding = 0;
            }

            if(mCount == mElements.length) {
                Element[] e = new Element[mCount + 8];
                String[] s = new String[mCount + 8];
                int[] as = new int[mCount + 8];
                System.arraycopy(mElements, 0, e, 0, mCount);
                System.arraycopy(mElementNames, 0, s, 0, mCount);
                System.arraycopy(mArraySizes, 0, as, 0, mCount);
                mElements = e;
                mElementNames = s;
                mArraySizes = as;
            }
            mElements[mCount] = element;
            mElementNames[mCount] = name;
            mArraySizes[mCount] = arraySize;
            mCount++;
            return this;
        }

        /**
         * Add a single element to this Element.
         *
         * @param element
         * @param name
         */
        public Builder add(Element element, String name) {
            return add(element, name, 1);
        }

        /**
         * Create the element from this builder.
         *
         *
         * @return Element
         */
        public Element create() {
            mRS.validate();
            Element[] ein = new Element[mCount];
            String[] sin = new String[mCount];
            int[] asin = new int[mCount];
            java.lang.System.arraycopy(mElements, 0, ein, 0, mCount);
            java.lang.System.arraycopy(mElementNames, 0, sin, 0, mCount);
            java.lang.System.arraycopy(mArraySizes, 0, asin, 0, mCount);

            int[] ids = new int[ein.length];
            for (int ct = 0; ct < ein.length; ct++ ) {
                ids[ct] = ein[ct].getID();
            }
            int id = mRS.nElementCreate2(ids, sin, asin);
            return new Element(id, mRS, ein, sin, asin);
        }
    }
}

