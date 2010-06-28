/*
 * Copyright (C) 2010 The Android Open Source Project
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

#define LOG_TAG "OpenGLRenderer"

#include <math.h>
#include <stdlib.h>
#include <string.h>

#include <utils/Log.h>

#include <SkMatrix.h>

#include "Matrix.h"

namespace android {
namespace uirenderer {

void Matrix4::loadIdentity() {
    data[0]  = 1.0f;
    data[1]  = 0.0f;
    data[2]  = 0.0f;
    data[3]  = 0.0f;

    data[4]  = 0.0f;
    data[5]  = 1.0f;
    data[6]  = 0.0f;
    data[7]  = 0.0f;

    data[8]  = 0.0f;
    data[9]  = 0.0f;
    data[10] = 1.0f;
    data[11] = 0.0f;

    data[12] = 0.0f;
    data[13] = 0.0f;
    data[14] = 0.0f;
    data[15] = 1.0f;
}

void Matrix4::load(const float* v) {
    memcpy(data, v, sizeof(data));
}

void Matrix4::load(const Matrix4& v) {
    memcpy(data, v.data, sizeof(data));
}

void Matrix4::load(const SkMatrix& v) {
    memset(data, 0, sizeof(data));

    data[0]  = v[SkMatrix::kMScaleX];
    data[4]  = v[SkMatrix::kMSkewX];
    data[12] = v[SkMatrix::kMTransX];

    data[1]  = v[SkMatrix::kMSkewY];
    data[5]  = v[SkMatrix::kMScaleY];
    data[13] = v[SkMatrix::kMTransY];

    data[3]  = v[SkMatrix::kMPersp0];
    data[7]  = v[SkMatrix::kMPersp1];
    data[15] = v[SkMatrix::kMPersp2];

    data[10] = 1.0f;
}

void Matrix4::copyTo(SkMatrix& v) const {
    v.reset();

    v.set(SkMatrix::kMScaleX, data[0]);
    v.set(SkMatrix::kMSkewX,  data[4]);
    v.set(SkMatrix::kMTransX, data[12]);

    v.set(SkMatrix::kMSkewY,  data[1]);
    v.set(SkMatrix::kMScaleY, data[5]);
    v.set(SkMatrix::kMTransY, data[13]);

    v.set(SkMatrix::kMPersp0, data[3]);
    v.set(SkMatrix::kMPersp1, data[7]);
    v.set(SkMatrix::kMPersp2, data[15]);
}

void Matrix4::copyTo(float* v) const {
    memcpy(v, data, sizeof(data));
}

float Matrix4::getTranslateX() {
    return data[12];
}

float Matrix4::getTranslateY() {
    return data[13];
}

void Matrix4::loadTranslate(float x, float y, float z) {
    loadIdentity();
    data[12] = x;
    data[13] = y;
    data[14] = z;
}

void Matrix4::loadScale(float sx, float sy, float sz) {
    loadIdentity();
    data[0]  = sx;
    data[5]  = sy;
    data[10] = sz;
}

void Matrix4::loadRotate(float angle, float x, float y, float z) {
    data[3]  = 0.0f;
    data[7]  = 0.0f;
    data[11] = 0.0f;
    data[12] = 0.0f;
    data[13] = 0.0f;
    data[14] = 0.0f;
    data[15] = 1.0f;

    angle *= float(M_PI / 180.0f);
    float c = cosf(angle);
    float s = sinf(angle);

    const float length = sqrtf(x * x + y * y + z * z);
    const float nc = 1.0f - c;
    const float xy = x * y;
    const float yz = y * z;
    const float zx = z * x;
    const float xs = x * s;
    const float ys = y * s;
    const float zs = z * s;

    data[0]  = x * x * nc +  c;
    data[4]  =    xy * nc - zs;
    data[8]  =    zx * nc + ys;
    data[1]  =    xy * nc + zs;
    data[5]  = y * y * nc +  c;
    data[9]  =    yz * nc - xs;
    data[2]  =    zx * nc - ys;
    data[6]  =    yz * nc + xs;
    data[10] = z * z * nc +  c;
}

void Matrix4::loadMultiply(const Matrix4& u, const Matrix4& v) {
    for (int i = 0 ; i < 4 ; i++) {
        float x = 0;
        float y = 0;
        float z = 0;
        float w = 0;

        for (int j = 0 ; j < 4 ; j++) {
            const float e = v.get(i, j);
            x += u.get(j, 0) * e;
            y += u.get(j, 1) * e;
            z += u.get(j, 2) * e;
            w += u.get(j, 3) * e;
        }

        set(i, 0, x);
        set(i, 1, y);
        set(i, 2, z);
        set(i, 3, w);
    }
}

void Matrix4::loadOrtho(float left, float right, float bottom, float top, float near, float far) {
    loadIdentity();
    data[0]  = 2.0f / (right - left);
    data[5]  = 2.0f / (top - bottom);
    data[10] = -2.0f / (far - near);
    data[12] = -(right + left) / (right - left);
    data[13] = -(top + bottom) / (top - bottom);
    data[14] = -(far + near) / (far - near);
}

#define MUL_ADD_STORE(a, b, c) a = (a) * (b) + (c)

void Matrix4::mapRect(Rect& r) const {
    const float sx = data[0];
    const float sy = data[5];

    const float tx = data[12];
    const float ty = data[13];

    MUL_ADD_STORE(r.left, sx, tx);
    MUL_ADD_STORE(r.right, sx, tx);
    MUL_ADD_STORE(r.top, sy, ty);
    MUL_ADD_STORE(r.bottom, sy, ty);
}

void Matrix4::dump() const {
    LOGD("Matrix4[");
    LOGD("  %f %f %f %f", data[0], data[4], data[ 8], data[12]);
    LOGD("  %f %f %f %f", data[1], data[5], data[ 9], data[13]);
    LOGD("  %f %f %f %f", data[2], data[6], data[10], data[14]);
    LOGD("  %f %f %f %f", data[3], data[7], data[11], data[15]);
    LOGD("]");
}

}; // namespace uirenderer
}; // namespace android
