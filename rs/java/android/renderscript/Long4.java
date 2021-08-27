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
 * Provides four long fields packed.
 *
 * @deprecated Renderscript has been deprecated in API level 31. Please refer to the <a
 * href="https://developer.android.com/guide/topics/renderscript/migration-guide">migration
 * guide</a> for the proposed alternatives.
 */
@Deprecated
public class Long4 {
    public long x;
    public long y;
    public long z;
    public long w;

    public Long4() {
    }

    /** @hide */
    public Long4(long i) {
        this.x = this.y = this.z = this.w = i;
    }

    public Long4(long x, long y, long z, long w) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.w = w;
    }

    /** @hide */
    public Long4(Long4 source) {
        this.x = source.x;
        this.y = source.y;
        this.z = source.z;
        this.w = source.w;
    }

    /** @hide
     * Vector add
     *
     * @param a
     */
    public void add(Long4 a) {
        this.x += a.x;
        this.y += a.y;
        this.z += a.z;
        this.w += a.w;
    }

    /** @hide
     * Vector add
     *
     * @param a
     * @param b
     * @return
     */
    public static Long4 add(Long4 a, Long4 b) {
        Long4 result = new Long4();
        result.x = a.x + b.x;
        result.y = a.y + b.y;
        result.z = a.z + b.z;
        result.w = a.w + b.w;

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
        w += value;
    }

    /** @hide
     * Vector add
     *
     * @param a
     * @param b
     * @return
     */
    public static Long4 add(Long4 a, long b) {
        Long4 result = new Long4();
        result.x = a.x + b;
        result.y = a.y + b;
        result.z = a.z + b;
        result.w = a.w + b;

        return result;
    }

    /** @hide
     * Vector subtraction
     *
     * @param a
     */
    public void sub(Long4 a) {
        this.x -= a.x;
        this.y -= a.y;
        this.z -= a.z;
        this.w -= a.w;
    }

    /** @hide
     * Vector subtraction
     *
     * @param a
     * @param b
     * @return
     */
    public static Long4 sub(Long4 a, Long4 b) {
        Long4 result = new Long4();
        result.x = a.x - b.x;
        result.y = a.y - b.y;
        result.z = a.z - b.z;
        result.w = a.w - b.w;

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
        w -= value;
    }

    /** @hide
     * Vector subtraction
     *
     * @param a
     * @param b
     * @return
     */
    public static Long4 sub(Long4 a, long b) {
        Long4 result = new Long4();
        result.x = a.x - b;
        result.y = a.y - b;
        result.z = a.z - b;
        result.w = a.w - b;

        return result;
    }

    /** @hide
     * Vector multiplication
     *
     * @param a
     */
    public void mul(Long4 a) {
        this.x *= a.x;
        this.y *= a.y;
        this.z *= a.z;
        this.w *= a.w;
    }

    /** @hide
     * Vector multiplication
     *
     * @param a
     * @param b
     * @return
     */
    public static Long4 mul(Long4 a, Long4 b) {
        Long4 result = new Long4();
        result.x = a.x * b.x;
        result.y = a.y * b.y;
        result.z = a.z * b.z;
        result.w = a.w * b.w;

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
        w *= value;
    }

    /** @hide
     * Vector multiplication
     *
     * @param a
     * @param b
     * @return
     */
    public static Long4 mul(Long4 a, long b) {
        Long4 result = new Long4();
        result.x = a.x * b;
        result.y = a.y * b;
        result.z = a.z * b;
        result.w = a.w * b;

        return result;
    }

    /** @hide
     * Vector division
     *
     * @param a
     */
    public void div(Long4 a) {
        this.x /= a.x;
        this.y /= a.y;
        this.z /= a.z;
        this.w /= a.w;
    }

    /** @hide
     * Vector division
     *
     * @param a
     * @param b
     * @return
     */
    public static Long4 div(Long4 a, Long4 b) {
        Long4 result = new Long4();
        result.x = a.x / b.x;
        result.y = a.y / b.y;
        result.z = a.z / b.z;
        result.w = a.w / b.w;

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
        w /= value;
    }

    /** @hide
     * Vector division
     *
     * @param a
     * @param b
     * @return
     */
    public static Long4 div(Long4 a, long b) {
        Long4 result = new Long4();
        result.x = a.x / b;
        result.y = a.y / b;
        result.z = a.z / b;
        result.w = a.w / b;

        return result;
    }

    /** @hide
     * Vector Modulo
     *
     * @param a
     */
    public void mod(Long4 a) {
        this.x %= a.x;
        this.y %= a.y;
        this.z %= a.z;
        this.w %= a.w;
    }

    /** @hide
     * Vector Modulo
     *
     * @param a
     * @param b
     * @return
     */
    public static Long4 mod(Long4 a, Long4 b) {
        Long4 result = new Long4();
        result.x = a.x % b.x;
        result.y = a.y % b.y;
        result.z = a.z % b.z;
        result.w = a.w % b.w;

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
        w %= value;
    }

    /** @hide
     * Vector Modulo
     *
     * @param a
     * @param b
     * @return
     */
    public static Long4 mod(Long4 a, long b) {
        Long4 result = new Long4();
        result.x = a.x % b;
        result.y = a.y % b;
        result.z = a.z % b;
        result.w = a.w % b;

        return result;
    }

    /** @hide
     * get vector length
     *
     * @return
     */
    public long length() {
        return 4;
    }

    /** @hide
     * set vector negate
     */
    public void negate() {
        this.x = -x;
        this.y = -y;
        this.z = -z;
        this.w = -w;
    }

    /** @hide
     * Vector dot Product
     *
     * @param a
     * @return
     */
    public long dotProduct(Long4 a) {
        return (long)((x * a.x) + (y * a.y) + (z * a.z) + (w * a.w));
    }

    /** @hide
     * Vector dot Product
     *
     * @param a
     * @param b
     * @return
     */
    public static long dotProduct(Long4 a, Long4 b) {
        return (long)((b.x * a.x) + (b.y * a.y) + (b.z * a.z) + (b.w * a.w));
    }

    /** @hide
     * Vector add Multiple
     *
     * @param a
     * @param factor
     */
    public void addMultiple(Long4 a, long factor) {
        x += a.x * factor;
        y += a.y * factor;
        z += a.z * factor;
        w += a.w * factor;
    }

    /** @hide
     * set vector value by Long4
     *
     * @param a
     */
    public void set(Long4 a) {
        this.x = a.x;
        this.y = a.y;
        this.z = a.z;
        this.w = a.w;
    }

    /** @hide
     * set the vector field value by Long
     *
     * @param a
     * @param b
     * @param c
     * @param d
     */
    public void setValues(long a, long b, long c, long d) {
        this.x = a;
        this.y = b;
        this.z = c;
        this.w = d;
    }

    /** @hide
     * return the element sum of vector
     *
     * @return
     */
    public long elementSum() {
        return (long)(x + y + z + w);
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
        case 3:
            return (long)(w);
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
        case 3:
            w = value;
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
        case 3:
            w += value;
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
        data[offset + 3] = (long)(w);
    }
}
