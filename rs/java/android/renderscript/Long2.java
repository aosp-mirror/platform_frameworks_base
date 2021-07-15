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
 * Provides two long fields packed.
 *
 * @deprecated Renderscript has been deprecated in API level 31. Please refer to the <a
 * href="https://developer.android.com/guide/topics/renderscript/migration-guide">migration
 * guide</a> for the proposed alternatives.
 */
@Deprecated
public class Long2 {
    public long x;
    public long y;

    public Long2() {
    }

    /** @hide */
    public Long2(long i) {
        this.x = this.y = i;
    }

    public Long2(long x, long y) {
        this.x = x;
        this.y = y;
    }

    /** @hide */
    public Long2(Long2 source) {
        this.x = source.x;
        this.y = source.y;
    }

    /** @hide
     * Vector add
     *
     * @param a
     */
    public void add(Long2 a) {
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
    public static Long2 add(Long2 a, Long2 b) {
        Long2 result = new Long2();
        result.x = a.x + b.x;
        result.y = a.y + b.y;

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
    }

    /** @hide
     * Vector add
     *
     * @param a
     * @param b
     * @return
     */
    public static Long2 add(Long2 a, long b) {
        Long2 result = new Long2();
        result.x = a.x + b;
        result.y = a.y + b;

        return result;
    }

    /** @hide
     * Vector subtraction
     *
     * @param a
     */
    public void sub(Long2 a) {
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
    public static Long2 sub(Long2 a, Long2 b) {
        Long2 result = new Long2();
        result.x = a.x - b.x;
        result.y = a.y - b.y;

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
    }

    /** @hide
     * Vector subtraction
     *
     * @param a
     * @param b
     * @return
     */
    public static Long2 sub(Long2 a, long b) {
        Long2 result = new Long2();
        result.x = a.x - b;
        result.y = a.y - b;

        return result;
    }

    /** @hide
     * Vector multiplication
     *
     * @param a
     */
    public void mul(Long2 a) {
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
    public static Long2 mul(Long2 a, Long2 b) {
        Long2 result = new Long2();
        result.x = a.x * b.x;
        result.y = a.y * b.y;

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
    }

    /** @hide
     * Vector multiplication
     *
     * @param a
     * @param b
     * @return
     */
    public static Long2 mul(Long2 a, long b) {
        Long2 result = new Long2();
        result.x = a.x * b;
        result.y = a.y * b;

        return result;
    }

    /** @hide
     * Vector division
     *
     * @param a
     */
    public void div(Long2 a) {
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
    public static Long2 div(Long2 a, Long2 b) {
        Long2 result = new Long2();
        result.x = a.x / b.x;
        result.y = a.y / b.y;

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
    }

    /** @hide
     * Vector division
     *
     * @param a
     * @param b
     * @return
     */
    public static Long2 div(Long2 a, long b) {
        Long2 result = new Long2();
        result.x = a.x / b;
        result.y = a.y / b;

        return result;
    }

    /** @hide
     * Vector Modulo
     *
     * @param a
     */
    public void mod(Long2 a) {
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
    public static Long2 mod(Long2 a, Long2 b) {
        Long2 result = new Long2();
        result.x = a.x % b.x;
        result.y = a.y % b.y;

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
    }

    /** @hide
     * Vector Modulo
     *
     * @param a
     * @param b
     * @return
     */
    public static Long2 mod(Long2 a, long b) {
        Long2 result = new Long2();
        result.x = a.x % b;
        result.y = a.y % b;

        return result;
    }

    /** @hide
     * get vector length
     *
     * @return
     */
    public long length() {
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
    public long dotProduct(Long2 a) {
        return (long)((x * a.x) + (y * a.y));
    }

    /** @hide
     * Vector dot Product
     *
     * @param a
     * @param b
     * @return
     */
    public static long dotProduct(Long2 a, Long2 b) {
        return (long)((b.x * a.x) + (b.y * a.y));
    }

    /** @hide
     * Vector add Multiple
     *
     * @param a
     * @param factor
     */
    public void addMultiple(Long2 a, long factor) {
        x += a.x * factor;
        y += a.y * factor;
    }

    /** @hide
     * set vector value by Long2
     *
     * @param a
     */
    public void set(Long2 a) {
        this.x = a.x;
        this.y = a.y;
    }

    /** @hide
     * set the vector field value by Long
     *
     * @param a
     * @param b
     */
    public void setValues(long a, long b) {
        this.x = a;
        this.y = b;
    }

    /** @hide
     * return the element sum of vector
     *
     * @return
     */
    public long elementSum() {
        return (long)(x + y);
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
    }
}
