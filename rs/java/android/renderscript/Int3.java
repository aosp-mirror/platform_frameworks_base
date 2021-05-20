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
 * Provides three int fields packed.
 *
 * @deprecated Renderscript has been deprecated in API level 31. Please refer to the <a
 * href="https://developer.android.com/guide/topics/renderscript/migration-guide">migration
 * guide</a> for the proposed alternatives.
 */
@Deprecated
public class Int3 {
    public int x;
    public int y;
    public int z;

    public Int3() {
    }

    /** @hide */
    public Int3(int i) {
        this.x = this.y = this.z = i;
    }

    public Int3(int x, int y, int z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    /** @hide */
    public Int3(Int3 source) {
        this.x = source.x;
        this.y = source.y;
        this.z = source.z;
    }

    /** @hide
     * Vector add
     *
     * @param a
     */
    public void add(Int3 a) {
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
    public static Int3 add(Int3 a, Int3 b) {
        Int3 result = new Int3();
        result.x = a.x + b.x;
        result.y = a.y + b.y;
        result.z = a.z + b.z;

        return result;
    }

    /** @hide
     * Vector add
     *
     * @param value
     */
    public void add(int value) {
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
    public static Int3 add(Int3 a, int b) {
        Int3 result = new Int3();
        result.x = a.x + b;
        result.y = a.y + b;
        result.z = a.z + b;

        return result;
    }

    /** @hide
     * Vector subtraction
     *
     * @param a
     */
    public void sub(Int3 a) {
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
    public static Int3 sub(Int3 a, Int3 b) {
        Int3 result = new Int3();
        result.x = a.x - b.x;
        result.y = a.y - b.y;
        result.z = a.z - b.z;

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
        z -= value;
    }

    /** @hide
     * Vector subtraction
     *
     * @param a
     * @param b
     * @return
     */
    public static Int3 sub(Int3 a, int b) {
        Int3 result = new Int3();
        result.x = a.x - b;
        result.y = a.y - b;
        result.z = a.z - b;

        return result;
    }

    /** @hide
     * Vector multiplication
     *
     * @param a
     */
    public void mul(Int3 a) {
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
    public static Int3 mul(Int3 a, Int3 b) {
        Int3 result = new Int3();
        result.x = a.x * b.x;
        result.y = a.y * b.y;
        result.z = a.z * b.z;

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
        z *= value;
    }

    /** @hide
     * Vector multiplication
     *
     * @param a
     * @param b
     * @return
     */
    public static Int3 mul(Int3 a, int b) {
        Int3 result = new Int3();
        result.x = a.x * b;
        result.y = a.y * b;
        result.z = a.z * b;

        return result;
    }

    /** @hide
     * Vector division
     *
     * @param a
     */
    public void div(Int3 a) {
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
    public static Int3 div(Int3 a, Int3 b) {
        Int3 result = new Int3();
        result.x = a.x / b.x;
        result.y = a.y / b.y;
        result.z = a.z / b.z;

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
        z /= value;
    }

    /** @hide
     * Vector division
     *
     * @param a
     * @param b
     * @return
     */
    public static Int3 div(Int3 a, int b) {
        Int3 result = new Int3();
        result.x = a.x / b;
        result.y = a.y / b;
        result.z = a.z / b;

        return result;
    }

    /** @hide
     * Vector Modulo
     *
     * @param a
     */
    public void mod(Int3 a) {
        this.x %= a.x;
        this.y %= a.y;
        this.z %= a.z;
    }

    /** @hide
     * Vector Modulo
     *
     * @param a
     * @param b
     * @return
     */
    public static Int3 mod(Int3 a, Int3 b) {
        Int3 result = new Int3();
        result.x = a.x % b.x;
        result.y = a.y % b.y;
        result.z = a.z % b.z;

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
        z %= value;
    }

    /** @hide
     * Vector Modulo
     *
     * @param a
     * @param b
     * @return
     */
    public static Int3 mod(Int3 a, int b) {
        Int3 result = new Int3();
        result.x = a.x % b;
        result.y = a.y % b;
        result.z = a.z % b;

        return result;
    }

    /** @hide
     * get vector length
     *
     * @return
     */
    public int length() {
        return 3;
    }

    /** @hide
     * set vector negate
     */
    public void negate() {
        this.x = -x;
        this.y = -y;
        this.z = -z;
    }

    /** @hide
     * Vector dot Product
     *
     * @param a
     * @return
     */
    public int dotProduct(Int3 a) {
        return (int)((x * a.x) + (y * a.y) + (z * a.z));
    }

    /** @hide
     * Vector dot Product
     *
     * @param a
     * @param b
     * @return
     */
    public static int dotProduct(Int3 a, Int3 b) {
        return (int)((b.x * a.x) + (b.y * a.y) + (b.z * a.z));
    }

    /** @hide
     * Vector add Multiple
     *
     * @param a
     * @param factor
     */
    public void addMultiple(Int3 a, int factor) {
        x += a.x * factor;
        y += a.y * factor;
        z += a.z * factor;
    }

    /** @hide
     * set vector value by Int3
     *
     * @param a
     */
    public void set(Int3 a) {
        this.x = a.x;
        this.y = a.y;
        this.z = a.z;
    }

    /** @hide
     * set the vector field value by Int
     *
     * @param a
     * @param b
     * @param c
     */
    public void setValues(int a, int b, int c) {
        this.x = a;
        this.y = b;
        this.z = c;
    }

    /** @hide
     * return the element sum of vector
     *
     * @return
     */
    public int elementSum() {
        return (int)(x + y + z);
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
        case 2:
            return (int)(z);
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
    public void addAt(int i, int value) {
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
     * copy the vector to int array
     *
     * @param data
     * @param offset
     */
    public void copyTo(int[] data, int offset) {
        data[offset] = (int)(x);
        data[offset + 1] = (int)(y);
        data[offset + 2] = (int)(z);
    }
}
