/*
 * Copyright (C) 2013 The Android Open Source Project
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
 * Class for exposing the native RenderScript Short2 type back to the Android system.
 *
 * Vector version of the basic short type.
 * Provides two short fields packed.
 */
public class Short2 {
    public short x;
    public short y;

    public Short2() {
    }

    /** @hide */
    public Short2(short i) {
        this.x = this.y = i;
    }

    public Short2(short x, short y) {
        this.x = x;
        this.y = y;
    }

    /** @hide */
    public Short2(Short2 source) {
        this.x = source.x;
        this.y = source.y;
    }

    /** @hide
     * Vector add
     *
     * @param a
     */
    public void add(Short2 a) {
        this.x += a.x;
        this.y += a.y;
    }

    /** @hide
     * Vector add
     *
     * @param a
     * @param b
     * @return
     */
    public static Short2 add(Short2 a, Short2 b) {
        Short2 result = new Short2();
        result.x = (short)(a.x + b.x);
        result.y = (short)(a.y + b.y);

        return result;
    }

    /** @hide
     * Vector add
     *
     * @param value
     */
    public void add(short value) {
        x += value;
        y += value;
    }

    /** @hide
     * Vector add
     *
     * @param a
     * @param b
     * @return
     */
    public static Short2 add(Short2 a, short b) {
        Short2 result = new Short2();
        result.x = (short)(a.x + b);
        result.y = (short)(a.y + b);

        return result;
    }

    /** @hide
     * Vector subtraction
     *
     * @param a
     */
    public void sub(Short2 a) {
        this.x -= a.x;
        this.y -= a.y;
    }

    /** @hide
     * Vector subtraction
     *
     * @param a
     * @param b
     * @return
     */
    public static Short2 sub(Short2 a, Short2 b) {
        Short2 result = new Short2();
        result.x = (short)(a.x - b.x);
        result.y = (short)(a.y - b.y);

        return result;
    }

    /** @hide
     * Vector subtraction
     *
     * @param value
     */
    public void sub(short value) {
        x -= value;
        y -= value;
    }

    /** @hide
     * Vector subtraction
     *
     * @param a
     * @param b
     * @return
     */
    public static Short2 sub(Short2 a, short b) {
        Short2 result = new Short2();
        result.x = (short)(a.x - b);
        result.y = (short)(a.y - b);

        return result;
    }

    /** @hide
     * Vector multiplication
     *
     * @param a
     */
    public void mul(Short2 a) {
        this.x *= a.x;
        this.y *= a.y;
    }

    /** @hide
     * Vector multiplication
     *
     * @param a
     * @param b
     * @return
     */
    public static Short2 mul(Short2 a, Short2 b) {
        Short2 result = new Short2();
        result.x = (short)(a.x * b.x);
        result.y = (short)(a.y * b.y);

        return result;
    }

    /** @hide
     * Vector multiplication
     *
     * @param value
     */
    public void mul(short value) {
        x *= value;
        y *= value;
    }

    /** @hide
     * Vector multiplication
     *
     * @param a
     * @param b
     * @return
     */
    public static Short2 mul(Short2 a, short b) {
        Short2 result = new Short2();
        result.x = (short)(a.x * b);
        result.y = (short)(a.y * b);

        return result;
    }

    /** @hide
     * Vector division
     *
     * @param a
     */
    public void div(Short2 a) {
        this.x /= a.x;
        this.y /= a.y;
    }

    /** @hide
     * Vector division
     *
     * @param a
     * @param b
     * @return
     */
    public static Short2 div(Short2 a, Short2 b) {
        Short2 result = new Short2();
        result.x = (short)(a.x / b.x);
        result.y = (short)(a.y / b.y);

        return result;
    }

    /** @hide
     * Vector division
     *
     * @param value
     */
    public void div(short value) {
        x /= value;
        y /= value;
    }

    /** @hide
     * Vector division
     *
     * @param a
     * @param b
     * @return
     */
    public static Short2 div(Short2 a, short b) {
        Short2 result = new Short2();
        result.x = (short)(a.x / b);
        result.y = (short)(a.y / b);

        return result;
    }

    /** @hide
     * Vector Modulo
     *
     * @param a
     */
    public void mod(Short2 a) {
        this.x %= a.x;
        this.y %= a.y;
    }

    /** @hide
     * Vector Modulo
     *
     * @param a
     * @param b
     * @return
     */
    public static Short2 mod(Short2 a, Short2 b) {
        Short2 result = new Short2();
        result.x = (short)(a.x % b.x);
        result.y = (short)(a.y % b.y);

        return result;
    }

    /** @hide
     * Vector Modulo
     *
     * @param value
     */
    public void mod(short value) {
        x %= value;
        y %= value;
    }

    /** @hide
     * Vector Modulo
     *
     * @param a
     * @param b
     * @return
     */
    public static Short2 mod(Short2 a, short b) {
        Short2 result = new Short2();
        result.x = (short)(a.x % b);
        result.y = (short)(a.y % b);

        return result;
    }

    /** @hide
     * get vector length
     *
     * @return
     */
    public short length() {
        return 2;
    }

    /** @hide
     * set vector negate
     */
    public void negate() {
        this.x = (short)(-x);
        this.y = (short)(-y);
    }

    /** @hide
     * Vector dot Product
     *
     * @param a
     * @return
     */
    public short dotProduct(Short2 a) {
        return (short)((x * a.x) + (y * a.y));
    }

    /** @hide
     * Vector dot Product
     *
     * @param a
     * @param b
     * @return
     */
    public static short dotProduct(Short2 a, Short2 b) {
        return (short)((b.x * a.x) + (b.y * a.y));
    }

    /** @hide
     * Vector add Multiple
     *
     * @param a
     * @param factor
     */
    public void addMultiple(Short2 a, short factor) {
        x += a.x * factor;
        y += a.y * factor;
    }

    /** @hide
     * set vector value by Short2
     *
     * @param a
     */
    public void set(Short2 a) {
        this.x = a.x;
        this.y = a.y;
    }

    /** @hide
     * set the vector field value by Short
     *
     * @param a
     * @param b
     */
    public void setValues(short a, short b) {
        this.x = a;
        this.y = b;
    }

    /** @hide
     * return the element sum of vector
     *
     * @return
     */
    public short elementSum() {
        return (short)(x + y);
    }

    /** @hide
     * get the vector field value by index
     *
     * @param i
     * @return
     */
    public short get(int i) {
        switch (i) {
        case 0:
            return (short)(x);
        case 1:
            return (short)(y);
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
    public void setAt(int i, short value) {
        switch (i) {
        case 0:
            x = value;
            return;
        case 1:
            y = value;
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
    public void addAt(int i, short value) {
        switch (i) {
        case 0:
            x += value;
            return;
        case 1:
            y += value;
            return;
        default:
            throw new IndexOutOfBoundsException("Index: i");
        }
    }

    /** @hide
     * copy the vector to short array
     *
     * @param data
     * @param offset
     */
    public void copyTo(short[] data, int offset) {
        data[offset] = (short)(x);
        data[offset + 1] = (short)(y);
    }
}
