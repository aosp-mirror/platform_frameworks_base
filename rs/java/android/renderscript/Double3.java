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
 * Vector version of the basic double type.
 * Provides three double fields packed.
 *
 * @deprecated Renderscript has been deprecated in API level 31. Please refer to the <a
 * href="https://developer.android.com/guide/topics/renderscript/migration-guide">migration
 * guide</a> for the proposed alternatives.
 */
@Deprecated
public class Double3 {
    public double x;
    public double y;
    public double z;

    public Double3() {
    }
    /** @hide */
    public Double3(Double3 data) {
        this.x = data.x;
        this.y = data.y;
        this.z = data.z;
    }

    public Double3(double x, double y, double z) {
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
    public static Double3 add(Double3 a, Double3 b) {
        Double3 res = new Double3();
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
    public void add(Double3 value) {
        x += value.x;
        y += value.y;
        z += value.z;
    }

    /** @hide
     * Vector add
     *
     * @param value
     */
    public void add(double value) {
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
    public static Double3 add(Double3 a, double b) {
        Double3 res = new Double3();
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
    public void sub(Double3 value) {
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
    public static Double3 sub(Double3 a, Double3 b) {
        Double3 res = new Double3();
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
    public void sub(double value) {
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
    public static Double3 sub(Double3 a, double b) {
        Double3 res = new Double3();
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
    public void mul(Double3 value) {
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
    public static Double3 mul(Double3 a, Double3 b) {
        Double3 res = new Double3();
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
    public void mul(double value) {
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
    public static Double3 mul(Double3 a, double b) {
        Double3 res = new Double3();
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
    public void div(Double3 value) {
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
    public static Double3 div(Double3 a, Double3 b) {
        Double3 res = new Double3();
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
    public void div(double value) {
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
    public static Double3 div(Double3 a, double b) {
        Double3 res = new Double3();
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
    public double dotProduct(Double3 a) {
        return (x * a.x) + (y * a.y) + (z * a.z);
    }

    /** @hide
     * Vector dot Product
     *
     * @param a
     * @param b
     * @return
     */
    public static double dotProduct(Double3 a, Double3 b) {
        return (b.x * a.x) + (b.y * a.y) + (b.z * a.z);
    }

    /** @hide
     * Vector add Multiple
     *
     * @param a
     * @param factor
     */
    public void addMultiple(Double3 a, double factor) {
        x += a.x * factor;
        y += a.y * factor;
        z += a.z * factor;
    }

    /** @hide
     * Set vector value by double3
     *
     * @param a
     */
    public void set(Double3 a) {
        this.x = a.x;
        this.y = a.y;
        this.z = a.z;
    }

    /** @hide
     * Set vector negate
     */
    public void negate() {
        x = -x;
        y = -y;
        z = -z;
    }

    /** @hide
     * Get vector length
     *
     * @return
     */
    public int length() {
        return 3;
    }

    /** @hide
     * Return the element sum of vector
     *
     * @return
     */
    public double elementSum() {
        return x + y + z;
    }

    /** @hide
     * Get the vector field value by index
     *
     * @param i
     * @return
     */
    public double get(int i) {
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
     * Set the vector field value by index
     *
     * @param i
     * @param value
     */
    public void setAt(int i, double value) {
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
     * Add the vector field value by index
     *
     * @param i
     * @param value
     */
    public void addAt(int i, double value) {
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
     * Set the vector field value
     *
     * @param x
     * @param y
     * @param z
     */
    public void setValues(double x, double y, double z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    /** @hide
     * Copy the vector to double array
     *
     * @param data
     * @param offset
     */
    public void copyTo(double[] data, int offset) {
        data[offset] = x;
        data[offset + 1] = y;
        data[offset + 2] = z;
    }
}
