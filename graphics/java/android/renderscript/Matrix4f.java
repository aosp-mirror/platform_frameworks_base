/*
 * Copyright (C) 2009 The Android Open Source Project
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
 * @hide
 *
 **/
public class Matrix4f {

    public Matrix4f() {
        mMat = new float[16];
        loadIdentity();
    }

    public float get(int i, int j) {
        return mMat[i*4 + j];
    }

    public void set(int i, int j, float v) {
        mMat[i*4 + j] = v;
    }

    public void loadIdentity() {
        mMat[0] = 1;
        mMat[1] = 0;
        mMat[2] = 0;
        mMat[3] = 0;

        mMat[4] = 0;
        mMat[5] = 1;
        mMat[6] = 0;
        mMat[7] = 0;

        mMat[8] = 0;
        mMat[9] = 0;
        mMat[10] = 1;
        mMat[11] = 0;

        mMat[12] = 0;
        mMat[13] = 0;
        mMat[14] = 0;
        mMat[15] = 1;
    }

    public void load(Matrix4f src) {
        System.arraycopy(mMat, 0, src, 0, 16);
    }

    public void loadRotate(float rot, float x, float y, float z) {
        float c, s;
        mMat[3] = 0;
        mMat[7] = 0;
        mMat[11]= 0;
        mMat[12]= 0;
        mMat[13]= 0;
        mMat[14]= 0;
        mMat[15]= 1;
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
        mMat[ 0] = x*x*nc +  c;
        mMat[ 4] =  xy*nc - zs;
        mMat[ 8] =  zx*nc + ys;
        mMat[ 1] =  xy*nc + zs;
        mMat[ 5] = y*y*nc +  c;
        mMat[ 9] =  yz*nc - xs;
        mMat[ 2] =  zx*nc - ys;
        mMat[ 6] =  yz*nc + xs;
        mMat[10] = z*z*nc +  c;
    }

    public void loadScale(float x, float y, float z) {
        loadIdentity();
        mMat[0] = x;
        mMat[5] = y;
        mMat[10] = z;
    }

    public void loadTranslate(float x, float y, float z) {
        loadIdentity();
        mMat[12] = x;
        mMat[13] = y;
        mMat[14] = z;
    }

    public void loadMultiply(Matrix4f lhs, Matrix4f rhs) {
        for (int i=0 ; i<4 ; i++) {
            float ri0 = 0;
            float ri1 = 0;
            float ri2 = 0;
            float ri3 = 0;
            for (int j=0 ; j<4 ; j++) {
                float rhs_ij = rhs.get(i,j);
                ri0 += lhs.get(j,0) * rhs_ij;
                ri1 += lhs.get(j,1) * rhs_ij;
                ri2 += lhs.get(j,2) * rhs_ij;
                ri3 += lhs.get(j,3) * rhs_ij;
            }
            set(i,0, ri0);
            set(i,1, ri1);
            set(i,2, ri2);
            set(i,3, ri3);
        }
    }

    public void loadOrtho(float l, float r, float b, float t, float n, float f) {
        loadIdentity();
        mMat[0] = 2 / (r - l);
        mMat[5] = 2 / (t - b);
        mMat[10]= -2 / (f - n);
        mMat[12]= -(r + l) / (r - l);
        mMat[13]= -(t + b) / (t - b);
        mMat[14]= -(f + n) / (f - n);
    }

    public void loadFrustum(float l, float r, float b, float t, float n, float f) {
        loadIdentity();
        mMat[0] = 2 * n / (r - l);
        mMat[5] = 2 * n / (t - b);
        mMat[8] = (r + l) / (r - l);
        mMat[9] = (t + b) / (t - b);
        mMat[10]= -(f + n) / (f - n);
        mMat[11]= -1;
        mMat[14]= -2*f*n / (f - n);
        mMat[15]= 0;
    }

    public void multiply(Matrix4f rhs) {
        Matrix4f tmp = new Matrix4f();
        tmp.loadMultiply(this, rhs);
        load(tmp);
    }
    public void rotate(float rot, float x, float y, float z) {
        Matrix4f tmp = new Matrix4f();
        tmp.loadRotate(rot, x, y, z);
        multiply(tmp);
    }
    public void scale(float x, float y, float z) {
        Matrix4f tmp = new Matrix4f();
        tmp.loadScale(x, y, z);
        multiply(tmp);
    }
    public void translate(float x, float y, float z) {
        Matrix4f tmp = new Matrix4f();
        tmp.loadTranslate(x, y, z);
        multiply(tmp);
    }

    final float[] mMat;
}





