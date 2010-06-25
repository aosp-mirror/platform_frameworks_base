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

#define LOG_TAG "Matrix"

#include <math.h>
#include <stdlib.h>
#include <string.h>

#include <utils/Log.h>

#include <SkMatrix.h>

#include "Matrix.h"

namespace android {
namespace uirenderer {

void Matrix4::loadIdentity() {
	mMat[0]  = 1.0f;
	mMat[1]  = 0.0f;
	mMat[2]  = 0.0f;
	mMat[3]  = 0.0f;

	mMat[4]  = 0.0f;
	mMat[5]  = 1.0f;
	mMat[6]  = 0.0f;
	mMat[7]  = 0.0f;

	mMat[8]  = 0.0f;
	mMat[9]  = 0.0f;
	mMat[10] = 1.0f;
	mMat[11] = 0.0f;

	mMat[12] = 0.0f;
	mMat[13] = 0.0f;
	mMat[14] = 0.0f;
	mMat[15] = 1.0f;
}

void Matrix4::load(const float* v) {
	memcpy(mMat, v, sizeof(mMat));
}

void Matrix4::load(const Matrix4& v) {
	memcpy(mMat, v.mMat, sizeof(mMat));
}

void Matrix4::load(const SkMatrix& v) {
	memset(mMat, 0, sizeof(mMat));

	mMat[0]  = v[SkMatrix::kMScaleX];
	mMat[4]  = v[SkMatrix::kMSkewX];
	mMat[12] = v[SkMatrix::kMTransX];

	mMat[1]  = v[SkMatrix::kMSkewY];
	mMat[5]  = v[SkMatrix::kMScaleY];
	mMat[13] = v[SkMatrix::kMTransY];

	mMat[3]  = v[SkMatrix::kMPersp0];
	mMat[7]  = v[SkMatrix::kMPersp1];
	mMat[15] = v[SkMatrix::kMPersp2];

	mMat[10] = 1.0f;
}

void Matrix4::copyTo(SkMatrix& v) const {
	v.reset();

	v.set(SkMatrix::kMScaleX, mMat[0]);
	v.set(SkMatrix::kMSkewX,  mMat[4]);
	v.set(SkMatrix::kMTransX, mMat[12]);

	v.set(SkMatrix::kMSkewY,  mMat[1]);
	v.set(SkMatrix::kMScaleY, mMat[5]);
	v.set(SkMatrix::kMTransY, mMat[13]);

	v.set(SkMatrix::kMPersp0, mMat[3]);
	v.set(SkMatrix::kMPersp1, mMat[7]);
	v.set(SkMatrix::kMPersp2, mMat[15]);
}

void Matrix4::copyTo(float* v) const {
	memcpy(v, mMat, sizeof(mMat));
}

void Matrix4::loadTranslate(float x, float y, float z) {
	loadIdentity();
	mMat[12] = x;
	mMat[13] = y;
	mMat[14] = z;
}

void Matrix4::loadScale(float sx, float sy, float sz) {
	loadIdentity();
	mMat[0]  = sx;
	mMat[5]  = sy;
	mMat[10] = sz;
}

void Matrix4::loadRotate(float angle, float x, float y, float z) {
	mMat[3]  = 0.0f;
	mMat[7]  = 0.0f;
	mMat[11] = 0.0f;
	mMat[12] = 0.0f;
	mMat[13] = 0.0f;
	mMat[14] = 0.0f;
	mMat[15] = 1.0f;

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

	mMat[0]  = x * x * nc +  c;
	mMat[4]  =    xy * nc - zs;
	mMat[8]  =    zx * nc + ys;
	mMat[1]  =    xy * nc + zs;
	mMat[5]  = y * y * nc +  c;
	mMat[9]  =    yz * nc - xs;
	mMat[2]  =    zx * nc - ys;
	mMat[6]  =    yz * nc + xs;
	mMat[10] = z * z * nc +  c;
}

void Matrix4::loadMultiply(const Matrix4& u, const Matrix4& v) {
    for (int i = 0 ; i < 4 ; i++) {
        float x = 0;
        float y = 0;
        float z = 0;
        float w = 0;

        for (int j = 0 ; j < 4 ; j++) {
            const float e = v.get(i,j);
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
    mMat[0]  = 2.0f / (right - left);
    mMat[5]  = 2.0f / (top - bottom);
    mMat[10] = -2.0f / (far - near);
    mMat[12] = -(right + left) / (right - left);
    mMat[13] = -(top + bottom) / (top - bottom);
    mMat[14] = -(far + near) / (far - near);
}

#define MUL_ADD_STORE(a, b, c) a = (a) * (b) + (c)

void Matrix4::mapRect(Rect& r) const {
	const float sx = mMat[0];
	const float sy = mMat[5];

	const float tx = mMat[12];
	const float ty = mMat[13];

	MUL_ADD_STORE(r.left, sx, tx);
	MUL_ADD_STORE(r.right, sx, tx);
	MUL_ADD_STORE(r.top, sy, ty);
	MUL_ADD_STORE(r.bottom, sy, ty);
}

void Matrix4::dump() const {
	LOGD("Matrix4[");
	LOGD("  %f %f %f %f", mMat[0], mMat[4], mMat[ 8], mMat[12]);
	LOGD("  %f %f %f %f", mMat[1], mMat[5], mMat[ 9], mMat[13]);
	LOGD("  %f %f %f %f", mMat[2], mMat[6], mMat[10], mMat[14]);
	LOGD("  %f %f %f %f", mMat[3], mMat[7], mMat[11], mMat[15]);
	LOGD("]");
}

}; // namespace uirenderer
}; // namespace android
