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
 * Vector version of the basic long type.
 * Provides three long fields packed.
 */
public class Long3 {
    public long x;
    public long y;
    public long z;

    public Long3() {
    }

    /** @hide */
    public Long3(long i) {
        this.x = this.y = this.z = i;
    }

    public Long3(long x, long y, long z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    /** @hide */
    public Long3(Long3 source) {
        this.x = source.x;
        this.y = source.y;
        this.z = source.z;
    }

    /** @hide
     * Vector add
     *
     * @param a
     */
    public void add(Long3 a) {
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
    public static Long3 add(Long3 a, Long3 b) {
        Long3 result = new Long3();
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
    public void add(long value) {
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
    public static Long3 add(Long3 a, long b) {
        Long3 result = new Long3();
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
    public void sub(Long3 a) {
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
    public static Long3 sub(Long3 a, Long3 b) {
        Long3 result = new Long3();
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
    public void sub(long value) {
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
    public static Long3 sub(Long3 a, long b) {
        Long3 result = new Long3();
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
    public void mul(Long3 a) {
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
    public static Long3 mul(Long3 a, Long3 b) {
        Long3 result = new Long3();
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
    public void mul(long value) {
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
    public static Long3 mul(Long3 a, long b) {
        Long3 result = new Long3();
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
    public void div(Long3 a) {
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
    public static Long3 div(Long3 a, Long3 b) {
        Long3 result = new Long3();
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
    public void div(long value) {
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
    public static Long3 div(Long3 a, long b) {
        Long3 result = new Long3();
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
    public void mod(Long3 a) {
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
    public static Long3 mod(Long3 a, Long3 b) {
        Long3 result = new Long3();
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
    public void mod(long value) {
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
    public static Long3 mod(Long3 a, long b) {
        Long3 result = new Long3();
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
    public long length() {
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
    public long dotProduct(Long3 a) {
        return (long)((x * a.x) + (y * a.y) + (z * a.z));
    }

    /** @hide
     * Vector dot Product
     *
     * @param a
     * @param b
     * @return
     */
    public static long dotProduct(Long3 a, Long3 b) {
        return (long)((b.x * a.x) + (b.y * a.y) + (b.z * a.z));
    }

    /** @hide
     * Vector add Multiple
     *
     * @param a
     * @param factor
     */
    public void addMultiple(Long3 a, long factor) {
        x += a.x * factor;
        y += a.y * factor;
        z += a.z * factor;
    }

    /** @hide
     * set vector value by Long3
     *
     * @param a
     */
    public void set(Long3 a) {
        this.x = a.x;
        this.y = a.y;
        this.z = a.z;
    }

    /** @hide
     * set the vector field value by Long
     *
     * @param a
     * @param b
     * @param c
     */
    public void setValues(long a, long b, long c) {
        this.x = a;
        this.y = b;
        this.z = c;
    }

    /** @hide
     * return the element sum of vector
     *
     * @return
     */
    public long elementSum() {
        return (long)(x + y + z);
    }

    /** @hide
     * get the vector field value by index
     *
     * @param i
     * @return
     */
    public long get(int i) {
        switch (i) {
        case 0:
            return (long)(x);
        case 1:
            return (long)(y);
        case 2:
            return (long)(z);
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
    public void setAt(int i, long value) {
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
    public void addAt(int i, long value) {
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
     * copy the vector to long array
     *
     * @param data
     * @param offset
     */
    public void copyTo(long[] data, int offset) {
        data[offset] = (long)(x);
        data[offset + 1] = (long)(y);
        data[offset + 2] = (long)(z);
    }
}
