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
public class Matrix2f {

    public Matrix2f() {
        mMat = new float[4];
        loadIdentity();
    }

    public float get(int i, int j) {
        return mMat[i*2 + j];
    }

    public void set(int i, int j, float v) {
        mMat[i*2 + j] = v;
    }

    public void loadIdentity() {
        mMat[0] = 1;
        mMat[1] = 0;

        mMat[2] = 0;
        mMat[3] = 1;
    }

    public void load(Matrix2f src) {
        System.arraycopy(mMat, 0, src, 0, 4);
    }

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

    public void loadScale(float x, float y) {
        loadIdentity();
        mMat[0] = x;
        mMat[3] = y;
    }
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

    public void multiply(Matrix2f rhs) {
        Matrix2f tmp = new Matrix2f();
        tmp.loadMultiply(this, rhs);
        load(tmp);
    }
    public void rotate(float rot) {
        Matrix2f tmp = new Matrix2f();
        tmp.loadRotate(rot);
        multiply(tmp);
    }
    public void scale(float x, float y) {
        Matrix2f tmp = new Matrix2f();
        tmp.loadScale(x, y);
        multiply(tmp);
    }

    final float[] mMat;
}



