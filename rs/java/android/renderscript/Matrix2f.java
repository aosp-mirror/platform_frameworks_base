/*
 * Copyright (C) 2009-2012 The Android Open Source Project
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
 * Class for exposing the native RenderScript rs_matrix2x2 type back to the Android system.
 *
 * @deprecated Renderscript has been deprecated in API level 31. Please refer to the <a
 * href="https://developer.android.com/guide/topics/renderscript/migration-guide">migration
 * guide</a> for the proposed alternatives.
 **/
@Deprecated
public class Matrix2f {

    /**
    * Creates a new identity 2x2 matrix
    */
    public Matrix2f() {
        mMat = new float[4];
        loadIdentity();
    }

    /**
    * Creates a new matrix and sets its values from the given
    * parameter
    *
    * @param dataArray values to set the matrix to, must be 4
    *                  floats long
    */
    public Matrix2f(float[] dataArray) {
        mMat = new float[4];
        System.arraycopy(dataArray, 0, mMat, 0, mMat.length);
    }

    /**
    * Return a reference to the internal array representing matrix
    * values. Modifying this array will also change the matrix
    *
    * @return internal array representing the matrix
    */
    public float[] getArray() {
        return mMat;
    }

    /**
    * Returns the value for a given row and column
    *
    * @param x column of the value to return
    * @param y row of the value to return
    *
    * @return value in the yth row and xth column
    */
    public float get(int x, int y) {
        return mMat[x*2 + y];
    }

    /**
    * Sets the value for a given row and column
    *
    * @param x column of the value to set
    * @param y row of the value to set
    */
    public void set(int x, int y, float v) {
        mMat[x*2 + y] = v;
    }

    /**
    * Sets the matrix values to identity
    */
    public void loadIdentity() {
        mMat[0] = 1;
        mMat[1] = 0;

        mMat[2] = 0;
        mMat[3] = 1;
    }

    /**
    * Sets the values of the matrix to those of the parameter
    *
    * @param src matrix to load the values from
    */
    public void load(Matrix2f src) {
        System.arraycopy(src.getArray(), 0, mMat, 0, mMat.length);
    }

    /**
    * Sets current values to be a rotation matrix of given angle
    *
    * @param rot rotation angle
    */
    public void loadRotate(float rot) {
        float c, s;
        rot *= (float)(java.lang.Math.PI / 180.0f);
        c = (float)java.lang.Math.cos(rot);
        s = (float)java.lang.Math.sin(rot);
        mMat[0] = c;
        mMat[1] = -s;
        mMat[2] = s;
        mMat[3] = c;
    }

    /**
    * Sets current values to be a scale matrix of given dimensions
    *
    * @param x scale component x
    * @param y scale component y
    */
    public void loadScale(float x, float y) {
        loadIdentity();
        mMat[0] = x;
        mMat[3] = y;
    }

    /**
    * Sets current values to be the result of multiplying two given
    * matrices
    *
    * @param lhs left hand side matrix
    * @param rhs right hand side matrix
    */
    public void loadMultiply(Matrix2f lhs, Matrix2f rhs) {
        for (int i=0 ; i<2 ; i++) {
            float ri0 = 0;
            float ri1 = 0;
            for (int j=0 ; j<2 ; j++) {
                float rhs_ij = rhs.get(i,j);
                ri0 += lhs.get(j,0) * rhs_ij;
                ri1 += lhs.get(j,1) * rhs_ij;
            }
            set(i,0, ri0);
            set(i,1, ri1);
        }
    }

    /**
    * Post-multiplies the current matrix by a given parameter
    *
    * @param rhs right hand side to multiply by
    */
    public void multiply(Matrix2f rhs) {
        Matrix2f tmp = new Matrix2f();
        tmp.loadMultiply(this, rhs);
        load(tmp);
    }
    /**
    * Modifies the current matrix by post-multiplying it with a
    * rotation matrix of given angle
    *
    * @param rot angle of rotation
    */
    public void rotate(float rot) {
        Matrix2f tmp = new Matrix2f();
        tmp.loadRotate(rot);
        multiply(tmp);
    }
    /**
    * Modifies the current matrix by post-multiplying it with a
    * scale matrix of given dimensions
    *
    * @param x scale component x
    * @param y scale component y
    */
    public void scale(float x, float y) {
        Matrix2f tmp = new Matrix2f();
        tmp.loadScale(x, y);
        multiply(tmp);
    }
    /**
    * Sets the current matrix to its transpose
    */
    public void transpose() {
        float temp = mMat[1];
        mMat[1] = mMat[2];
        mMat[2] = temp;
    }

    final float[] mMat;
}



