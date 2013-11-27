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
 * Provides two double fields packed.
 */
public class Double2 {
    public double x;
    public double y;

    public Double2() {
    }

    /** @hide */
    public Double2(Double2 data) {
        this.x = data.x;
        this.y = data.y;
    }

    public Double2(double x, double y) {
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
    public static Double2 add(Double2 a, Double2 b) {
        Double2 res = new Double2();
        res.x = a.x + b.x;
        res.y = a.y + b.y;

        return res;
    }

    /** @hide
     * Vector add
     *
     * @param value
     */
    public void add(Double2 value) {
        x += value.x;
        y += value.y;
    }

    /** @hide
     * Vector add
     *
     * @param value
     */
    public void add(double value) {
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
    public static Double2 add(Double2 a, double b) {
        Double2 res = new Double2();
        res.x = a.x + b;
        res.y = a.y + b;

        return res;
    }

    /** @hide
     * Vector subtraction
     *
     * @param value
     */
    public void sub(Double2 value) {
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
    public static Double2 sub(Double2 a, Double2 b) {
        Double2 res = new Double2();
        res.x = a.x - b.x;
        res.y = a.y - b.y;

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
    }

    /** @hide
     * Vector subtraction
     *
     * @param a
     * @param b
     * @return
     */
    public static Double2 sub(Double2 a, double b) {
        Double2 res = new Double2();
        res.x = a.x - b;
        res.y = a.y - b;

        return res;
    }

    /** @hide
     * Vector multiplication
     *
     * @param value
     */
    public void mul(Double2 value) {
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
    public static Double2 mul(Double2 a, Double2 b) {
        Double2 res = new Double2();
        res.x = a.x * b.x;
        res.y = a.y * b.y;

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
    }

    /** @hide
     * Vector multiplication
     *
     * @param a
     * @param b
     * @return
     */
    public static Double2 mul(Double2 a, double b) {
        Double2 res = new Double2();
        res.x = a.x * b;
        res.y = a.y * b;

        return res;
    }

    /** @hide
     * Vector division
     *
     * @param value
     */
    public void div(Double2 value) {
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
    public static Double2 div(Double2 a, Double2 b) {
        Double2 res = new Double2();
        res.x = a.x / b.x;
        res.y = a.y / b.y;

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
    }

    /** @hide
     * Vector division
     *
     * @param a
     * @param b
     * @return
     */
    public static Double2 div(Double2 a, double b) {
        Double2 res = new Double2();
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
    public double dotProduct(Double2 a) {
        return (x * a.x) + (y * a.y);
    }

    /** @hide
     * Vector dot Product
     *
     * @param a
     * @param b
     * @return
     */
    public static Double dotProduct(Double2 a, Double2 b) {
        return (b.x * a.x) + (b.y * a.y);
    }

    /** @hide
     * Vector add Multiple
     *
     * @param a
     * @param factor
     */
    public void addMultiple(Double2 a, double factor) {
        x += a.x * factor;
        y += a.y * factor;
    }

    /** @hide
     * Set vector value by double2
     *
     * @param a
     */
    public void set(Double2 a) {
        this.x = a.x;
        this.y = a.y;
    }

    /** @hide
     * Set vector negate
     */
    public void negate() {
        x = -x;
        y = -y;
    }

    /** @hide
     * Get vector length
     *
     * @return
     */
    public int length() {
        return 2;
    }

    /** @hide
     * Return the element sum of vector
     *
     * @return
     */
    public double elementSum() {
        return x + y;
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
        default:
            throw new IndexOutOfBoundsException("Index: i");
        }
    }

    /** @hide
     * Set the vector field value
     *
     * @param x
     * @param y
     */
    public void setValues(double x, double y) {
        this.x = x;
        this.y = y;
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
    }
}
