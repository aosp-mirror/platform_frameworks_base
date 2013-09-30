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

package android.util;

import java.util.Arrays;

/**
 * A class that contains utility methods related to matrices.
 *
 * @hide
 */
public class MatrixUtils {
    private MatrixUtils() {
        // This class is non-instantiable.
    }

    /**
     * Sets a matrix to the identity matrix.
     *
     * @param out output matrix of size n*m
     * @param n number of rows in the output matrix
     * @param m number of columns in the output matrix
     */
    public static void setIdentityM(float[] out, int n, int m) {
        final int size = n * m;
        if (out.length != size) {
            throw new IllegalArgumentException("Invalid dimensions for output matrix");
        }

        Arrays.fill(out, 0, size, 0);
        for (int i = Math.min(m,n) - 1; i >= 0; i--) {
            out[i * (m + 1)] = 1;
        }
    }

    /**
     * Add two matrices. May be used in-place by specifying the same value for
     * the out matrix and one of the input matrices.
     *
     * @param ab output matrix of size n*m
     * @param a left-hand matrix of size n*m
     * @param n number of rows in the left-hand matrix
     * @param m number of columns in the left-hand matrix
     * @param b right-hand matrix of size n*m
     */
    public static void addMM(float[] ab, float[] a, int n, int m, float[] b) {
        final int size = n * m;
        if (a.length != size) {
            throw new IllegalArgumentException("Invalid dimensions for matrix a");
        } else if (b.length != size) {
            throw new IllegalArgumentException("Invalid dimensions for matrix b");
        } else if (ab.length != size) {
            throw new IllegalArgumentException("Invalid dimensions for matrix ab");
        }

        for (int i = 0; i < size; i++) {
            ab[i] = a[i] + b[i];
        }
    }

    /**
     * Multiply two matrices.
     *
     * @param ab output matrix of size n*p
     * @param a left-hand matrix of size n*m
     * @param n number of rows in the left-hand matrix
     * @param m number of columns in the left-hand matrix
     * @param b right-hand matrix of size m*p
     * @param p number of columns in the right-hand matrix
     */
    public static void multiplyMM(float[] ab, float[] a, int n, int m, float[] b, int p) {
        if (a.length != n * m) {
            throw new IllegalArgumentException("Invalid dimensions for matrix a");
        } else if (b.length != m * p) {
            throw new IllegalArgumentException("Invalid dimensions for matrix b");
        } else if (ab.length != n * p) {
            throw new IllegalArgumentException("Invalid dimensions for matrix ab");
        }

        for (int i = 0; i < n; i++) {
            for (int j = 0; j < p; j++) {
                float sum = 0;
                for (int k = 0; k < m; k++) {
                    sum += a[i * m + k] * b[k * p + j];
                }
                ab[i * p + j] = sum;
            }
        }
    }

    /**
     * Multiply a matrix by a scalar value. May be used in-place by specifying
     * the same value for the input and output matrices.
     *
     * @param out output matrix
     * @param in input matrix
     * @param scalar scalar value
     */
    public static void multiplyMS(float[] out, float[] in, float scalar) {
        final int n = out.length;
        for (int i = 0; i < n; i++) {
            out[i] = in[i] * scalar;
        }
    }
}
