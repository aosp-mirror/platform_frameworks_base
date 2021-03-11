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
 * Provides three float fields packed.
 *
 * @deprecated Renderscript has been deprecated in API level 31. Please refer to the <a
 * href="https://developer.android.com/guide/topics/renderscript/migration-guide">migration
 * guide</a> for the proposed alternatives.
 */
@Deprecated
public class Float3 {
    public float x;
    public float y;
    public float z;

    public Float3() {
    }
    /** @hide */
    public Float3(Float3 data) {
        this.x = data.x;
        this.y = data.y;
        this.z = data.z;
    }

    public Float3(float x, float y, float z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    /** @hide
     * Vector add
     *
     * @param a
     * @param b
     * @return
     */
    public static Float3 add(Float3 a, Float3 b) {
        Float3 res = new Float3();
        res.x = a.x + b.x;
        res.y = a.y + b.y;
        res.z = a.z + b.z;

        return res;
    }

    /** @hide
     * Vector add
     *
     * @param value
     */
    public void add(Float3 value) {
        x += value.x;
        y += value.y;
        z += value.z;
    }

    /** @hide
     * Vector add
     *
     * @param value
     */
    public void add(float value) {
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
    public static Float3 add(Float3 a, float b) {
        Float3 res = new Float3();
        res.x = a.x + b;
        res.y = a.y + b;
        res.z = a.z + b;

        return res;
    }

    /** @hide
     * Vector subtraction
     *
     * @param value
     */
    public void sub(Float3 value) {
        x -= value.x;
        y -= value.y;
        z -= value.z;
    }

    /** @hide
     * Vector subtraction
     *
     * @param a
     * @param b
     * @return
     */
    public static Float3 sub(Float3 a, Float3 b) {
        Float3 res = new Float3();
        res.x = a.x - b.x;
        res.y = a.y - b.y;
        res.z = a.z - b.z;

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
        z -= value;
    }

    /** @hide
     * Vector subtraction
     *
     * @param a
     * @param b
     * @return
     */
    public static Float3 sub(Float3 a, float b) {
        Float3 res = new Float3();
        res.x = a.x - b;
        res.y = a.y - b;
        res.z = a.z - b;

        return res;
    }

    /** @hide
     * Vector multiplication
     *
     * @param value
     */
    public void mul(Float3 value) {
        x *= value.x;
        y *= value.y;
        z *= value.z;
    }

    /** @hide
     * Vector multiplication
     *
     * @param a
     * @param b
     * @return
     */
    public static Float3 mul(Float3 a, Float3 b) {
        Float3 res = new Float3();
        res.x = a.x * b.x;
        res.y = a.y * b.y;
        res.z = a.z * b.z;

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
        z *= value;
    }

    /** @hide
     * Vector multiplication
     *
     * @param a
     * @param b
     * @return
     */
    public static Float3 mul(Float3 a, float b) {
        Float3 res = new Float3();
        res.x = a.x * b;
        res.y = a.y * b;
        res.z = a.z * b;

        return res;
    }

    /** @hide
     * Vector division
     *
     * @param value
     */
    public void div(Float3 value) {
        x /= value.x;
        y /= value.y;
        z /= value.z;
    }

    /** @hide
     * Vector division
     *
     * @param a
     * @param b
     * @return
     */
    public static Float3 div(Float3 a, Float3 b) {
        Float3 res = new Float3();
        res.x = a.x / b.x;
        res.y = a.y / b.y;
        res.z = a.z / b.z;

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
        z /= value;
    }

    /** @hide
     * Vector division
     *
     * @param a
     * @param b
     * @return
     */
    public static Float3 div(Float3 a, float b) {
        Float3 res = new Float3();
        res.x = a.x / b;
        res.y = a.y / b;
        res.z = a.z / b;

        return res;
    }

    /** @hide
     * Vector dot Product
     *
     * @param a
     * @return
     */
    public Float dotProduct(Float3 a) {
        return new Float((x * a.x) + (y * a.y) + (z * a.z));
    }

    /** @hide
     * Vector dot Product
     *
     * @param a
     * @param b
     * @return
     */
    public static Float dotProduct(Float3 a, Float3 b) {
        return new Float((b.x * a.x) + (b.y * a.y) + (b.z * a.z));
    }

    /** @hide
     * Vector add Multiple
     *
     * @param a
     * @param factor
     */
    public void addMultiple(Float3 a, float factor) {
        x += a.x * factor;
        y += a.y * factor;
        z += a.z * factor;
    }

    /** @hide
     * set vector value by float3
     *
     * @param a
     */
    public void set(Float3 a) {
        this.x = a.x;
        this.y = a.y;
        this.z = a.z;
    }

    /** @hide
     * set vector negate
     */
    public void negate() {
        x = -x;
        y = -y;
        z = -z;
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
     * return the element sum of vector
     *
     * @return
     */
    public Float elementSum() {
        return new Float(x + y + z);
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
    public void setAt(int i, float value) {
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
    public void addAt(int i, float value) {
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
     * set the vector field value
     *
     * @param x
     * @param y
     * @param z
     */
    public void setValues(float x, float y, float z) {
        this.x = x;
        this.y = y;
        this.z = z;
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
        data[offset + 2] = z;
    }
}
