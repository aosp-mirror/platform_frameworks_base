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
public class FieldPacker {
    public FieldPacker(int len) {
        mPos = 0;
        mData = new byte[len];
    }

    public void align(int v) {
        while ((mPos & (v - 1)) != 0) {
            mData[mPos++] = 0;
        }
    }

    void reset() {
        mPos = 0;
    }

    void addI8(byte v) {
        mData[mPos++] = v;
    }

    void addI16(short v) {
        align(2);
        mData[mPos++] = (byte)(v & 0xff);
        mData[mPos++] = (byte)(v >> 8);
    }

    void addI32(int v) {
        align(4);
        mData[mPos++] = (byte)(v & 0xff);
        mData[mPos++] = (byte)((v >> 8) & 0xff);
        mData[mPos++] = (byte)((v >> 16) & 0xff);
        mData[mPos++] = (byte)((v >> 24) & 0xff);
    }

    void addI64(long v) {
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

    void addU8(short v) {
        if ((v < 0) || (v > 0xff)) {
            throw new IllegalArgumentException("Saving value out of range for type");
        }
        mData[mPos++] = (byte)v;
    }

    void addU16(int v) {
        if ((v < 0) || (v > 0xffff)) {
            throw new IllegalArgumentException("Saving value out of range for type");
        }
        align(2);
        mData[mPos++] = (byte)(v & 0xff);
        mData[mPos++] = (byte)(v >> 8);
    }

    void addU32(long v) {
        if ((v < 0) || (v > 0xffffffff)) {
            throw new IllegalArgumentException("Saving value out of range for type");
        }
        align(4);
        mData[mPos++] = (byte)(v & 0xff);
        mData[mPos++] = (byte)((v >> 8) & 0xff);
        mData[mPos++] = (byte)((v >> 16) & 0xff);
        mData[mPos++] = (byte)((v >> 24) & 0xff);
    }

    void addU64(long v) {
        if (v < 0) {
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

    void addF32(float v) {
        addI32(Float.floatToRawIntBits(v));
    }

    void addF64(float v) {
        addI64(Double.doubleToRawLongBits(v));
    }

    final byte[] getData() {
        return mData;
    }

    private final byte mData[];
    private int mPos;

}


