/*
 * Copyright (C) 2009 The Android Open Source Project
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
 * Class for exposing the native RenderScript byte3 type back to the Android system.
 *
 * @deprecated Renderscript has been deprecated in API level 31. Please refer to the <a
 * href="https://developer.android.com/guide/topics/renderscript/migration-guide">migration
 * guide</a> for the proposed alternatives.
 **/
@Deprecated
public class Byte3 {
    public byte x;
    public byte y;
    public byte z;

    public Byte3() {
    }

    public Byte3(byte initX, byte initY, byte initZ) {
        x = initX;
        y = initY;
        z = initZ;
    }

    /** @hide */
    public Byte3(Byte3 source) {
        this.x = source.x;
        this.y = source.y;
        this.z = source.z;
    }

    /** @hide
     * Vector add
     *
     * @param a
     */
    public void add(Byte3 a) {
        this.x += a.x;
        this.y += a.y;
        this.z += a.z;
    }

    /** @hide
     * Vector add
     *
     * @param a
     * @param b
     * @return
     */
    public static Byte3 add(Byte3 a, Byte3 b) {
        Byte3 result = new Byte3();
        result.x = (byte)(a.x + b.x);
        result.y = (byte)(a.y + b.y);
        result.z = (byte)(a.z + b.z);

        return result;
    }

    /** @hide
     * Vector add
     *
     * @param value
     */
    public void add(byte value) {
        x += value;
        y += value;
        z += value;
    }

    /** @hide
     * Vector add
     *
     * @param a
     * @param b
     * @return
     */
    public static Byte3 add(Byte3 a, byte b) {
        Byte3 result = new Byte3();
        result.x = (byte)(a.x + b);
        result.y = (byte)(a.y + b);
        result.z = (byte)(a.z + b);

        return result;
    }

    /** @hide
     * Vector subtraction
     *
     * @param a
     */
    public void sub(Byte3 a) {
        this.x -= a.x;
        this.y -= a.y;
        this.z -= a.z;
    }

    /** @hide
     * Vector subtraction
     *
     * @param a
     * @param b
     * @return
     */
    public static Byte3 sub(Byte3 a, Byte3 b) {
        Byte3 result = new Byte3();
        result.x = (byte)(a.x - b.x);
        result.y = (byte)(a.y - b.y);
        result.z = (byte)(a.z - b.z);

        return result;
    }

    /** @hide
     * Vector subtraction
     *
     * @param value
     */
    public void sub(byte value) {
        x -= value;
        y -= value;
        z -= value;
    }

    /** @hide
     * Vector subtraction
     *
     * @param a
     * @param b
     * @return
     */
    public static Byte3 sub(Byte3 a, byte b) {
        Byte3 result = new Byte3();
        result.x = (byte)(a.x - b);
        result.y = (byte)(a.y - b);
        result.z = (byte)(a.z - b);

        return result;
    }

    /** @hide
     * Vector multiplication
     *
     * @param a
     */
    public void mul(Byte3 a) {
        this.x *= a.x;
        this.y *= a.y;
        this.z *= a.z;
    }

    /** @hide
     * Vector multiplication
     *
     * @param a
     * @param b
     * @return
     */
    public static Byte3 mul(Byte3 a, Byte3 b) {
        Byte3 result = new Byte3();
        result.x = (byte)(a.x * b.x);
        result.y = (byte)(a.y * b.y);
        result.z = (byte)(a.z * b.z);

        return result;
    }

    /** @hide
     * Vector multiplication
     *
     * @param value
     */
    public void mul(byte value) {
        x *= value;
        y *= value;
        z *= value;
    }

    /** @hide
     * Vector multiplication
     *
     * @param a
     * @param b
     * @return
     */
    public static Byte3 mul(Byte3 a, byte b) {
        Byte3 result = new Byte3();
        result.x = (byte)(a.x * b);
        result.y = (byte)(a.y * b);
        result.z = (byte)(a.z * b);

        return result;
    }

    /** @hide
     * Vector division
     *
     * @param a
     */
    public void div(Byte3 a) {
        this.x /= a.x;
        this.y /= a.y;
        this.z /= a.z;
    }

    /** @hide
     * Vector division
     *
     * @param a
     * @param b
     * @return
     */
    public static Byte3 div(Byte3 a, Byte3 b) {
        Byte3 result = new Byte3();
        result.x = (byte)(a.x / b.x);
        result.y = (byte)(a.y / b.y);
        result.z = (byte)(a.z / b.z);

        return result;
    }

    /** @hide
     * Vector division
     *
     * @param value
     */
    public void div(byte value) {
        x /= value;
        y /= value;
        z /= value;
    }

    /** @hide
     * Vector division
     *
     * @param a
     * @param b
     * @return
     */
    public static Byte3 div(Byte3 a, byte b) {
        Byte3 result = new Byte3();
        result.x = (byte)(a.x / b);
        result.y = (byte)(a.y / b);
        result.z = (byte)(a.z / b);

        return result;
    }

    /** @hide
     * get vector length
     *
     * @return
     */
    public byte length() {
        return 3;
    }

    /** @hide
     * set vector negate
     */
    public void negate() {
        this.x = (byte)(-x);
        this.y = (byte)(-y);
        this.z = (byte)(-z);
    }

    /** @hide
     * Vector dot Product
     *
     * @param a
     * @return
     */
    public byte dotProduct(Byte3 a) {
        return (byte)((byte)((byte)(x * a.x) + (byte)(y * a.y)) + (byte)(z * a.z));
    }

    /** @hide
     * Vector dot Product
     *
     * @param a
     * @param b
     * @return
     */
    public static byte dotProduct(Byte3 a, Byte3 b) {
        return (byte)((byte)((byte)(b.x * a.x) + (byte)(b.y * a.y)) + (byte)(b.z * a.z));
    }

    /** @hide
     * Vector add Multiple
     *
     * @param a
     * @param factor
     */
    public void addMultiple(Byte3 a, byte factor) {
        x += a.x * factor;
        y += a.y * factor;
        z += a.z * factor;
    }

    /** @hide
     * set vector value by Byte3
     *
     * @param a
     */
    public void set(Byte3 a) {
        this.x = a.x;
        this.y = a.y;
        this.z = a.z;
    }

    /** @hide
     * set the vector field value by Char
     *
     * @param a
     * @param b
     * @param c
     */
    public void setValues(byte a, byte b, byte c) {
        this.x = a;
        this.y = b;
        this.z = c;
    }

    /** @hide
     * return the element sum of vector
     *
     * @return
     */
    public byte elementSum() {
        return (byte)(x + y + z);
    }

    /** @hide
     * get the vector field value by index
     *
     * @param i
     * @return
     */
    public byte get(int i) {
        switch (i) {
        case 0:
            return x;
        case 1:
            return y;
        case 2:
            return z;
        default:
            throw new IndexOutOfBoundsException("Index: i");
        }
    }

    /** @hide
     * set the vector field value by index
     *
     * @param i
     * @param value
     */
    public void setAt(int i, byte value) {
        switch (i) {
        case 0:
            x = value;
            return;
        case 1:
            y = value;
            return;
        case 2:
            z = value;
            return;
        default:
            throw new IndexOutOfBoundsException("Index: i");
        }
    }

    /** @hide
     * add the vector field value by index
     *
     * @param i
     * @param value
     */
    public void addAt(int i, byte value) {
        switch (i) {
        case 0:
            x += value;
            return;
        case 1:
            y += value;
            return;
        case 2:
            z += value;
            return;
        default:
            throw new IndexOutOfBoundsException("Index: i");
        }
    }

    /** @hide
     * copy the vector to Char array
     *
     * @param data
     * @param offset
     */
    public void copyTo(byte[] data, int offset) {
        data[offset] = x;
        data[offset + 1] = y;
        data[offset + 2] = z;
    }
}




