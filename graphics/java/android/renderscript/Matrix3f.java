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

import java.lang.Math;
import android.util.Log;


/**
 * Class for exposing the native Renderscript rs_matrix3x3 type back to the Android system.
 *
 **/
public class Matrix3f {

    /**
    * Creates a new identity 3x3 matrix
    */
    public Matrix3f() {
        mMat = new float[9];
        loadIdentity();
    }

    /**
    * Creates a new matrix and sets its values from the given
    * parameter
    *
    * @param dataArray values to set the matrix to, must be 9
    *                  floats long
    */
    public Matrix3f(float[] dataArray) {
        mMat = new float[9];
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
        return mMat[x*3 + y];
    }

    /**
    * Sets the value for a given row and column
    *
    * @param x column of the value to set
    * @param y row of the value to set
    */
    public void set(int x, int y, float v) {
        mMat[x*3 + y] = v;
    }

    /**
    * Sets the matrix values to identity
    */
    public void loadIdentity() {
        mMat[0] = 1;
        mMat[1] = 0;
        mMat[2] = 0;

        mMat[3] = 0;
        mMat[4] = 1;
        mMat[5] = 0;

        mMat[6] = 0;
        mMat[7] = 0;
        mMat[8] = 1;
    }

    /**
    * Sets the values of the matrix to those of the parameter
    *
    * @param src matrix to load the values from
    */
    public void load(Matrix3f src) {
        System.arraycopy(src.getArray(), 0, mMat, 0, mMat.length);
    }

    /**
    * Sets current values to be a rotation matrix of certain angle
    * about a given axis
    *
    * @param rot angle of rotation
    * @param x rotation axis x
    * @param y rotation axis y
    * @param z rotation axis z
    */
    public void loadRotate(float rot, float x, float y, float z) {
        float c, s;
        rot *= (float)(java.lang.Math.PI / 180.0f);
        c = (float)java.lang.Math.cos(rot);
        s = (float)java.lang.Math.sin(rot);

        float len = (float)java.lang.Math.sqrt(x*x + y*y + z*z);
        if (!(len != 1)) {
            float recipLen = 1.f / len;
            x *= recipLen;
            y *= recipLen;
            z *= recipLen;
        }
        float nc = 1.0f - c;
        float xy = x * y;
        float yz = y * z;
        float zx = z * x;
        float xs = x * s;
        float ys = y * s;
        float zs = z * s;
        mMat[0] = x*x*nc +  c;
        mMat[3] =  xy*nc - zs;
        mMat[6] =  zx*nc + ys;
        mMat[1] =  xy*nc + zs;
        mMat[4] = y*y*nc +  c;
        mMat[7] =  yz*nc - xs;
        mMat[2] =  zx*nc - ys;
        mMat[5] =  yz*nc + xs;
        mMat[8] = z*z*nc +  c;
    }

    /**
    * Makes the upper 2x2 a rotation matrix of the given angle
    *
    * @param rot rotation angle
    */
    public void loadRotate(float rot) {
        loadIdentity();
        float c, s;
        rot *= (float)(java.lang.Math.PI / 180.0f);
        c = (float)java.lang.Math.cos(rot);
        s = (float)java.lang.Math.sin(rot);
        mMat[0] = c;
        mMat[1] = -s;
        mMat[3] = s;
        mMat[4] = c;
    }

    /**
    * Makes the upper 2x2 a scale matrix of given dimensions
    *
    * @param x scale component x
    * @param y scale component y
    */
    public void loadScale(float x, float y) {
        loadIdentity();
        mMat[0] = x;
        mMat[4] = y;
    }

    /**
    * Sets current values to be a scale matrix of given dimensions
    *
    * @param x scale component x
    * @param y scale component y
    * @param z scale component z
    */
    public void loadScale(float x, float y, float z) {
        loadIdentity();
        mMat[0] = x;
        mMat[4] = y;
        mMat[8] = z;
    }

    /**
    * Sets current values to be a translation matrix of given
    * dimensions
    *
    * @param x translation component x
    * @param y translation component y
    */
    public void loadTranslate(float x, float y) {
        loadIdentity();
        mMat[6] = x;
        mMat[7] = y;
    }

    /**
    * Sets current values to be the result of multiplying two given
    * matrices
    *
    * @param lhs left hand side matrix
    * @param rhs right hand side matrix
    */
    public void loadMultiply(Matrix3f lhs, Matrix3f rhs) {
        for (int i=0 ; i<3 ; i++) {
            float ri0 = 0;
            float ri1 = 0;
            float ri2 = 0;
            for (int j=0 ; j<3 ; j++) {
                float rhs_ij = rhs.get(i,j);
                ri0 += lhs.get(j,0) * rhs_ij;
                ri1 += lhs.get(j,1) * rhs_ij;
                ri2 += lhs.get(j,2) * rhs_ij;
            }
            set(i,0, ri0);
            set(i,1, ri1);
            set(i,2, ri2);
        }
    }

    /**
    * Post-multiplies the current matrix by a given parameter
    *
    * @param rhs right hand side to multiply by
    */
    public void multiply(Matrix3f rhs) {
        Matrix3f tmp = new Matrix3f();
        tmp.loadMultiply(this, rhs);
        load(tmp);
    }

    /**
    * Modifies the current matrix by post-multiplying it with a
    * rotation matrix of certain angle about a given axis
    *
    * @param rot angle of rotation
    * @param x rotation axis x
    * @param y rotation axis y
    * @param z rotation axis z
    */
    public void rotate(float rot, float x, float y, float z) {
        Matrix3f tmp = new Matrix3f();
        tmp.loadRotate(rot, x, y, z);
        multiply(tmp);
    }

    /**
    * Modifies the upper 2x2 of the current matrix by
    * post-multiplying it with a rotation matrix of given angle
    *
    * @param rot angle of rotation
    */
    public void rotate(float rot) {
        Matrix3f tmp = new Matrix3f();
        tmp.loadRotate(rot);
        multiply(tmp);
    }

    /**
    * Modifies the upper 2x2 of the current matrix by
    * post-multiplying it with a scale matrix of given dimensions
    *
    * @param x scale component x
    * @param y scale component y
    */
    public void scale(float x, float y) {
        Matrix3f tmp = new Matrix3f();
        tmp.loadScale(x, y);
        multiply(tmp);
    }

    /**
    * Modifies the current matrix by post-multiplying it with a
    * scale matrix of given dimensions
    *
    * @param x scale component x
    * @param y scale component y
    * @param z scale component z
    */
    public void scale(float x, float y, float z) {
        Matrix3f tmp = new Matrix3f();
        tmp.loadScale(x, y, z);
        multiply(tmp);
    }

    /**
    * Modifies the current matrix by post-multiplying it with a
    * translation matrix of given dimensions
    *
    * @param x translation component x
    * @param y translation component y
    */
    public void translate(float x, float y) {
        Matrix3f tmp = new Matrix3f();
        tmp.loadTranslate(x, y);
        multiply(tmp);
    }

    /**
    * Sets the current matrix to its transpose
    */
    public void transpose() {
        for(int i = 0; i < 2; ++i) {
            for(int j = i + 1; j < 3; ++j) {
                float temp = mMat[i*3 + j];
                mMat[i*3 + j] = mMat[j*3 + i];
                mMat[j*3 + i] = temp;
            }
        }
    }

    final float[] mMat;
}


