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

#ifndef ANDROID_RS_MATRIX_4x4_H
#define ANDROID_RS_MATRIX_4x4_H

#include "rsType.h"


// ---------------------------------------------------------------------------
namespace android {
namespace renderscript {

struct Matrix4x4 : public rs_matrix4x4 {
    float get(uint32_t row, uint32_t col) const {
        return m[row*4 + col];
    }

    void set(uint32_t row, uint32_t col, float v) {
        m[row*4 + col] = v;
    }

    void loadIdentity();
    void load(const float *);
    void load(const rs_matrix4x4 *);
    void load(const rs_matrix3x3 *);
    void load(const rs_matrix2x2 *);

    void loadRotate(float rot, float x, float y, float z);
    void loadScale(float x, float y, float z);
    void loadTranslate(float x, float y, float z);
    void loadMultiply(const rs_matrix4x4 *lhs, const rs_matrix4x4 *rhs);

    void loadOrtho(float l, float r, float b, float t, float n, float f);
    void loadFrustum(float l, float r, float b, float t, float n, float f);
    void loadPerspective(float fovy, float aspect, float near, float far);

    void vectorMultiply(float *v4out, const float *v3in) const;

    bool inverse();
    bool inverseTranspose();
    void transpose();

    void logv(const char *s) const;


    void multiply(const rs_matrix4x4 *rhs) {
        Matrix4x4 tmp;
        tmp.loadMultiply(this, rhs);
        load(&tmp);
    }
    void rotate(float rot, float x, float y, float z) {
        Matrix4x4 tmp;
        tmp.loadRotate(rot, x, y, z);
        multiply(&tmp);
    }
    void scale(float x, float y, float z) {
        Matrix4x4 tmp;
        tmp.loadScale(x, y, z);
        multiply(&tmp);
    }
    void translate(float x, float y, float z) {
        Matrix4x4 tmp;
        tmp.loadTranslate(x, y, z);
        multiply(&tmp);
    }
};

}
}




#endif




