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
 * Vector version of the basic float type.
 * Provides two float fields packed.
 */
public  class Float2 {
    public float x;
    public float y;

    public Float2() {
    }
    /** @hide */
    public Float2(Float2 data) {
        this.x = data.x;
        this.y = data.y;
    }

    public Float2(float x, float y) {
        this.x = x;
        this.y = y;
    }

    /** @hide
     * Vector add
     *
     * @param a
     * @param b
     * @return
     */
    public static Float2 add(Float2 a, Float2 b) {
        Float2 res = new Float2();
        res.x = a.x + b.x;
        res.y = a.y + b.y;

        return res;
    }

    /** @hide
     * Vector add
     *
     * @param value
     */
    public void add(Float2 value) {
        x += value.x;
        y += value.y;
    }

    /** @hide
     * Vector add
     *
     * @param value
     */
    public void add(float value) {
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
    public static Float2 add(Float2 a, float b) {
        Float2 res = new Float2();
        res.x = a.x + b;
        res.y = a.y + b;

        return res;
    }

    /** @hide
     * Vector subtraction
     *
     * @param value
     */
    public void sub(Float2 value) {
        x -= value.x;
        y -= value.y;
    }

    /** @hide
     * Vector subtraction
     *
     * @param a
     * @param b
     * @return
     */
    public static Float2 sub(Float2 a, Float2 b) {
        Float2 res = new Float2();
        res.x = a.x - b.x;
        res.y = a.y - b.y;

        return res;
    }

    /** @hide
     * Vector subtraction
     *
     * @param value
     */
    public void sub(float value) {
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
    public static Float2 sub(Float2 a, float b) {
        Float2 res = new Float2();
        res.x = a.x - b;
        res.y = a.y - b;

        return res;
    }

    /** @hide
     * Vector multiplication
     *
     * @param value
     */
    public void mul(Float2 value) {
        x *= value.x;
        y *= value.y;
    }

    /** @hide
     * Vector multiplication
     *
     * @param a
     * @param b
     * @return
     */
    public static Float2 mul(Float2 a, Float2 b) {
        Float2 res = new Float2();
        res.x = a.x * b.x;
        res.y = a.y * b.y;

        return res;
    }

    /** @hide
     * Vector multiplication
     *
     * @param value
     */
    public void mul(float value) {
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
    public static Float2 mul(Float2 a, float b) {
        Float2 res = new Float2();
        res.x = a.x * b;
        res.y = a.y * b;

        return res;
    }

    /** @hide
     * Vector division
     *
     * @param value
     */
    public void div(Float2 value) {
        x /= value.x;
        y /= value.y;
    }

    /** @hide
     * Vector division
     *
     * @param a
     * @param b
     * @return
     */
    public static Float2 div(Float2 a, Float2 b) {
        Float2 res = new Float2();
        res.x = a.x / b.x;
        res.y = a.y / b.y;

        return res;
    }

    /** @hide
     * Vector division
     *
     * @param value
     */
    public void div(float value) {
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
    public static Float2 div(Float2 a, float b) {
        Float2 res = new Float2();
        res.x = a.x / b;
        res.y = a.y / b;

        return res;
    }

    /** @hide
     * Vector dot Product
     *
     * @param a
     * @return
     */
    public float dotProduct(Float2 a) {
        return (x * a.x) + (y * a.y);
    }

    /** @hide
     * Vector dot Product
     *
     * @param a
     * @param b
     * @return
     */
    public static float dotProduct(Float2 a, Float2 b) {
        return (b.x * a.x) + (b.y * a.y);
    }

    /** @hide
     * Vector add Multiple
     *
     * @param a
     * @param factor
     */
    public void addMultiple(Float2 a, float factor) {
        x += a.x * factor;
        y += a.y * factor;
    }

    /** @hide
     * set vector value by float2
     *
     * @param a
     */
    public void set(Float2 a) {
        this.x = a.x;
        this.y = a.y;
    }

    /** @hide
     * set vector negate
     */
    public void negate() {
        x = -x;
        y = -y;
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
     * return the element sum of vector
     *
     * @return
     */
    public float elementSum() {
        return x + y;
    }

    /** @hide
     * get the vector field value by index
     *
     * @param i
     * @return
     */
    public float get(int i) {
        switch (i) {
        case 0:
            return x;
        case 1:
            return y;
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
    public void setAt(int i, float value) {
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
    public void addAt(int i, float value) {
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
     * set the vector field value
     *
     * @param x
     * @param y
     */
    public void setValues(float x, float y) {
        this.x = x;
        this.y = y;
    }

    /** @hide
     * copy the vector to float array
     *
     * @param data
     * @param offset
     */
    public void copyTo(float[] data, int offset) {
        data[offset] = x;
        data[offset + 1] = y;
    }
}
