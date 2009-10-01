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

#ifndef ANDROID_RS_MATRIX_H
#define ANDROID_RS_MATRIX_H



// ---------------------------------------------------------------------------
namespace android {
namespace renderscript {

struct Matrix
{
    float m[16];

    inline float get(int i, int j) const {
        return m[i*4 + j];
    }

    inline void set(int i, int j, float v) {
        m[i*4 + j] = v;
    }

    void loadIdentity();
    void load(const float *);
    void load(const Matrix *);

    void loadRotate(float rot, float x, float y, float z);
    void loadScale(float x, float y, float z);
    void loadTranslate(float x, float y, float z);
    void loadMultiply(const Matrix *lhs, const Matrix *rhs);

    void loadOrtho(float l, float r, float b, float t, float n, float f);
    void loadFrustum(float l, float r, float b, float t, float n, float f);

    void vectorMultiply(float *v4out, const float *v3in) const;

    void multiply(const Matrix *rhs) {
        Matrix tmp;
        tmp.loadMultiply(this, rhs);
        load(&tmp);
    }
    void rotate(float rot, float x, float y, float z) {
        Matrix tmp;
        tmp.loadRotate(rot, x, y, z);
        multiply(&tmp);
    }
    void scale(float x, float y, float z) {
        Matrix tmp;
        tmp.loadScale(x, y, z);
        multiply(&tmp);
    }
    void translate(float x, float y, float z) {
        Matrix tmp;
        tmp.loadTranslate(x, y, z);
        multiply(&tmp);
    }



};



}
}




#endif




