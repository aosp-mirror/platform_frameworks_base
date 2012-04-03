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
 * Utility class for packing arguments and structures from Android system objects to
 * Renderscript objects.
 *
 **/
public class FieldPacker {
    public FieldPacker(int len) {
        mPos = 0;
        mLen = len;
        mData = new byte[len];
    }

    public void align(int v) {
        if ((v <= 0) || ((v & (v - 1)) != 0)) {
            throw new RSIllegalArgumentException("argument must be a non-negative non-zero power of 2: " + v);
        }

        while ((mPos & (v - 1)) != 0) {
            mData[mPos++] = 0;
        }
    }

    public void reset() {
        mPos = 0;
    }
    public void reset(int i) {
        if ((i < 0) || (i >= mLen)) {
            throw new RSIllegalArgumentException("out of range argument: " + i);
        }
        mPos = i;
    }

    public void skip(int i) {
        int res = mPos + i;
        if ((res < 0) || (res > mLen)) {
            throw new RSIllegalArgumentException("out of range argument: " + i);
        }
        mPos = res;
    }

    public void addI8(byte v) {
        mData[mPos++] = v;
    }

    public void addI16(short v) {
        align(2);
        mData[mPos++] = (byte)(v & 0xff);
        mData[mPos++] = (byte)(v >> 8);
    }

    public void addI32(int v) {
        align(4);
        mData[mPos++] = (byte)(v & 0xff);
        mData[mPos++] = (byte)((v >> 8) & 0xff);
        mData[mPos++] = (byte)((v >> 16) & 0xff);
        mData[mPos++] = (byte)((v >> 24) & 0xff);
    }

    public void addI64(long v) {
        align(8);
        mData[mPos++] = (byte)(v & 0xff);
        mData[mPos++] = (byte)((v >> 8) & 0xff);
        mData[mPos++] = (byte)((v >> 16) & 0xff);
        mData[mPos++] = (byte)((v >> 24) & 0xff);
        mData[mPos++] = (byte)((v >> 32) & 0xff);
        mData[mPos++] = (byte)((v >> 40) & 0xff);
        mData[mPos++] = (byte)((v >> 48) & 0xff);
        mData[mPos++] = (byte)((v >> 56) & 0xff);
    }

    public void addU8(short v) {
        if ((v < 0) || (v > 0xff)) {
            android.util.Log.e("rs", "FieldPacker.addU8( " + v + " )");
            throw new IllegalArgumentException("Saving value out of range for type");
        }
        mData[mPos++] = (byte)v;
    }

    public void addU16(int v) {
        if ((v < 0) || (v > 0xffff)) {
            android.util.Log.e("rs", "FieldPacker.addU16( " + v + " )");
            throw new IllegalArgumentException("Saving value out of range for type");
        }
        align(2);
        mData[mPos++] = (byte)(v & 0xff);
        mData[mPos++] = (byte)(v >> 8);
    }

    public void addU32(long v) {
        if ((v < 0) || (v > 0xffffffffL)) {
            android.util.Log.e("rs", "FieldPacker.addU32( " + v + " )");
            throw new IllegalArgumentException("Saving value out of range for type");
        }
        align(4);
        mData[mPos++] = (byte)(v & 0xff);
        mData[mPos++] = (byte)((v >> 8) & 0xff);
        mData[mPos++] = (byte)((v >> 16) & 0xff);
        mData[mPos++] = (byte)((v >> 24) & 0xff);
    }

    public void addU64(long v) {
        if (v < 0) {
            android.util.Log.e("rs", "FieldPacker.addU64( " + v + " )");
            throw new IllegalArgumentException("Saving value out of range for type");
        }
        align(8);
        mData[mPos++] = (byte)(v & 0xff);
        mData[mPos++] = (byte)((v >> 8) & 0xff);
        mData[mPos++] = (byte)((v >> 16) & 0xff);
        mData[mPos++] = (byte)((v >> 24) & 0xff);
        mData[mPos++] = (byte)((v >> 32) & 0xff);
        mData[mPos++] = (byte)((v >> 40) & 0xff);
        mData[mPos++] = (byte)((v >> 48) & 0xff);
        mData[mPos++] = (byte)((v >> 56) & 0xff);
    }

    public void addF32(float v) {
        addI32(Float.floatToRawIntBits(v));
    }

    public void addF64(double v) {
        addI64(Double.doubleToRawLongBits(v));
    }

    public void addObj(BaseObj obj) {
        if (obj != null) {
            addI32(obj.getID(null));
        } else {
            addI32(0);
        }
    }

    public void addF32(Float2 v) {
        addF32(v.x);
        addF32(v.y);
    }
    public void addF32(Float3 v) {
        addF32(v.x);
        addF32(v.y);
        addF32(v.z);
    }
    public void addF32(Float4 v) {
        addF32(v.x);
        addF32(v.y);
        addF32(v.z);
        addF32(v.w);
    }

    public void addF64(Double2 v) {
        addF64(v.x);
        addF64(v.y);
    }
    public void addF64(Double3 v) {
        addF64(v.x);
        addF64(v.y);
        addF64(v.z);
    }
    public void addF64(Double4 v) {
        addF64(v.x);
        addF64(v.y);
        addF64(v.z);
        addF64(v.w);
    }

    public void addI8(Byte2 v) {
        addI8(v.x);
        addI8(v.y);
    }
    public void addI8(Byte3 v) {
        addI8(v.x);
        addI8(v.y);
        addI8(v.z);
    }
    public void addI8(Byte4 v) {
        addI8(v.x);
        addI8(v.y);
        addI8(v.z);
        addI8(v.w);
    }

    public void addU8(Short2 v) {
        addU8(v.x);
        addU8(v.y);
    }
    public void addU8(Short3 v) {
        addU8(v.x);
        addU8(v.y);
        addU8(v.z);
    }
    public void addU8(Short4 v) {
        addU8(v.x);
        addU8(v.y);
        addU8(v.z);
        addU8(v.w);
    }

    public void addI16(Short2 v) {
        addI16(v.x);
        addI16(v.y);
    }
    public void addI16(Short3 v) {
        addI16(v.x);
        addI16(v.y);
        addI16(v.z);
    }
    public void addI16(Short4 v) {
        addI16(v.x);
        addI16(v.y);
        addI16(v.z);
        addI16(v.w);
    }

    public void addU16(Int2 v) {
        addU16(v.x);
        addU16(v.y);
    }
    public void addU16(Int3 v) {
        addU16(v.x);
        addU16(v.y);
        addU16(v.z);
    }
    public void addU16(Int4 v) {
        addU16(v.x);
        addU16(v.y);
        addU16(v.z);
        addU16(v.w);
    }

    public void addI32(Int2 v) {
        addI32(v.x);
        addI32(v.y);
    }
    public void addI32(Int3 v) {
        addI32(v.x);
        addI32(v.y);
        addI32(v.z);
    }
    public void addI32(Int4 v) {
        addI32(v.x);
        addI32(v.y);
        addI32(v.z);
        addI32(v.w);
    }

    public void addU32(Long2 v) {
        addU32(v.x);
        addU32(v.y);
    }
    public void addU32(Long3 v) {
        addU32(v.x);
        addU32(v.y);
        addU32(v.z);
    }
    public void addU32(Long4 v) {
        addU32(v.x);
        addU32(v.y);
        addU32(v.z);
        addU32(v.w);
    }

    public void addI64(Long2 v) {
        addI64(v.x);
        addI64(v.y);
    }
    public void addI64(Long3 v) {
        addI64(v.x);
        addI64(v.y);
        addI64(v.z);
    }
    public void addI64(Long4 v) {
        addI64(v.x);
        addI64(v.y);
        addI64(v.z);
        addI64(v.w);
    }

    public void addU64(Long2 v) {
        addU64(v.x);
        addU64(v.y);
    }
    public void addU64(Long3 v) {
        addU64(v.x);
        addU64(v.y);
        addU64(v.z);
    }
    public void addU64(Long4 v) {
        addU64(v.x);
        addU64(v.y);
        addU64(v.z);
        addU64(v.w);
    }

    public void addMatrix(Matrix4f v) {
        for (int i=0; i < v.mMat.length; i++) {
            addF32(v.mMat[i]);
        }
    }

    public void addMatrix(Matrix3f v) {
        for (int i=0; i < v.mMat.length; i++) {
            addF32(v.mMat[i]);
        }
    }

    public void addMatrix(Matrix2f v) {
        for (int i=0; i < v.mMat.length; i++) {
            addF32(v.mMat[i]);
        }
    }

    public void addBoolean(boolean v) {
        addI8((byte)(v ? 1 : 0));
    }

    public final byte[] getData() {
        return mData;
    }

    private final byte mData[];
    private int mPos;
    private int mLen;

}


