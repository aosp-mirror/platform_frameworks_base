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
 * Vector version of the basic int type.
 * Provides two int fields packed.
 */
public class Int2 {
    public int x;
    public int y;

    public Int2() {
    }

    /** @hide */
    public Int2(int i) {
        this.x = this.y = i;
    }

    public Int2(int x, int y) {
        this.x = x;
        this.y = y;
    }

    /** @hide */
    public Int2(Int2 source) {
        this.x = source.x;
        this.y = source.y;
    }

    /** @hide
     * Vector add
     *
     * @param a
     */
    public void add(Int2 a) {
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
    public static Int2 add(Int2 a, Int2 b) {
        Int2 result = new Int2();
        result.x = a.x + b.x;
        result.y = a.y + b.y;

        return result;
    }

    /**  @hide
     * Vector add
     *
     * @param value
     */
    public void add(int value) {
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
    public static Int2 add(Int2 a, int b) {
        Int2 result = new Int2();
        result.x = a.x + b;
        result.y = a.y + b;

        return result;
    }

    /** @hide
     * Vector subtraction
     *
     * @param a
     */
    public void sub(Int2 a) {
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
    public static Int2 sub(Int2 a, Int2 b) {
        Int2 result = new Int2();
        result.x = a.x - b.x;
        result.y = a.y - b.y;

        return result;
    }

    /** @hide
     * Vector subtraction
     *
     * @param value
     */
    public void sub(int value) {
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
    public static Int2 sub(Int2 a, int b) {
        Int2 result = new Int2();
        result.x = a.x - b;
        result.y = a.y - b;

        return result;
    }

    /** @hide
     * Vector multiplication
     *
     * @param a
     */
    public void mul(Int2 a) {
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
    public static Int2 mul(Int2 a, Int2 b) {
        Int2 result = new Int2();
        result.x = a.x * b.x;
        result.y = a.y * b.y;

        return result;
    }

    /** @hide
     * Vector multiplication
     *
     * @param value
     */
    public void mul(int value) {
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
    public static Int2 mul(Int2 a, int b) {
        Int2 result = new Int2();
        result.x = a.x * b;
        result.y = a.y * b;

        return result;
    }

    /** @hide
     * Vector division
     *
     * @param a
     */
    public void div(Int2 a) {
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
    public static Int2 div(Int2 a, Int2 b) {
        Int2 result = new Int2();
        result.x = a.x / b.x;
        result.y = a.y / b.y;

        return result;
    }

    /** @hide
     * Vector division
     *
     * @param value
     */
    public void div(int value) {
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
    public static Int2 div(Int2 a, int b) {
        Int2 result = new Int2();
        result.x = a.x / b;
        result.y = a.y / b;

        return result;
    }

    /** @hide
     * Vector Modulo
     *
     * @param a
     */
    public void mod(Int2 a) {
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
    public static Int2 mod(Int2 a, Int2 b) {
        Int2 result = new Int2();
        result.x = a.x % b.x;
        result.y = a.y % b.y;

        return result;
    }

    /** @hide
     * Vector Modulo
     *
     * @param value
     */
    public void mod(int value) {
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
    public static Int2 mod(Int2 a, int b) {
        Int2 result = new Int2();
        result.x = a.x % b;
        result.y = a.y % b;

        return result;
    }

    /** @hide
     * get vector length
     *
     * @return
     */
    public int length() {
        return 2;
    }

    /** @hide
     * set vector negate
     */
    public void negate() {
        this.x = -x;
        this.y = -y;
    }

    /** @hide
     * Vector dot Product
     *
     * @param a
     * @return
     */
    public int dotProduct(Int2 a) {
        return (int)((x * a.x) + (y * a.y));
    }

    /** @hide
     * Vector dot Product
     *
     * @param a
     * @param b
     * @return
     */
    public static int dotProduct(Int2 a, Int2 b) {
        return (int)((b.x * a.x) + (b.y * a.y));
    }

    /** @hide
     * Vector add Multiple
     *
     * @param a
     * @param factor
     */
    public void addMultiple(Int2 a, int factor) {
        x += a.x * factor;
        y += a.y * factor;
    }

    /** @hide
     * set vector value by Int2
     *
     * @param a
     */
    public void set(Int2 a) {
        this.x = a.x;
        this.y = a.y;
    }

    /** @hide
     * set the vector field value by Int
     *
     * @param a
     * @param b
     */
    public void setValues(int a, int b) {
        this.x = a;
        this.y = b;
    }

    /** @hide
     * return the element sum of vector
     *
     * @return
     */
    public int elementSum() {
        return (int)(x + y);
    }

    /** @hide
     * get the vector field value by index
     *
     * @param i
     * @return
     */
    public int get(int i) {
        switch (i) {
        case 0:
            return (int)(x);
        case 1:
            return (int)(y);
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
    public void setAt(int i, int value) {
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
    public void addAt(int i, int value) {
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
     * copy the vector to int array
     *
     * @param data
     * @param offset
     */
    public void copyTo(int[] data, int offset) {
        data[offset] = (int)(x);
        data[offset + 1] = (int)(y);
    }
}
