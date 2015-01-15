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

///////////////////////////////////////////////////////////////////////////////
// Defines
///////////////////////////////////////////////////////////////////////////////

static const float EPSILON = 0.0000001f;

///////////////////////////////////////////////////////////////////////////////
// Matrix
///////////////////////////////////////////////////////////////////////////////

const Matrix4& Matrix4::identity() {
    static Matrix4 sIdentity;
    return sIdentity;
}

void Matrix4::loadIdentity() {
    data[kScaleX]       = 1.0f;
    data[kSkewY]        = 0.0f;
    data[2]             = 0.0f;
    data[kPerspective0] = 0.0f;

    data[kSkewX]        = 0.0f;
    data[kScaleY]       = 1.0f;
    data[6]             = 0.0f;
    data[kPerspective1] = 0.0f;

    data[8]             = 0.0f;
    data[9]             = 0.0f;
    data[kScaleZ]       = 1.0f;
    data[11]            = 0.0f;

    data[kTranslateX]   = 0.0f;
    data[kTranslateY]   = 0.0f;
    data[kTranslateZ]   = 0.0f;
    data[kPerspective2] = 1.0f;

    mType = kTypeIdentity | kTypeRectToRect;
}

static bool isZero(float f) {
    return fabs(f) <= EPSILON;
}

uint8_t Matrix4::getType() const {
    if (mType & kTypeUnknown) {
        mType = kTypeIdentity;

        if (data[kPerspective0] != 0.0f || data[kPerspective1] != 0.0f ||
                data[kPerspective2] != 1.0f) {
            mType |= kTypePerspective;
        }

        if (data[kTranslateX] != 0.0f || data[kTranslateY] != 0.0f) {
            mType |= kTypeTranslate;
        }

        float m00 = data[kScaleX];
        float m01 = data[kSkewX];
        float m10 = data[kSkewY];
        float m11 = data[kScaleY];
        float m32 = data[kTranslateZ];

        if (m01 != 0.0f || m10 != 0.0f || m32 != 0.0f) {
            mType |= kTypeAffine;
        }

        if (m00 != 1.0f || m11 != 1.0f) {
            mType |= kTypeScale;
        }

        // The following section determines whether the matrix will preserve
        // rectangles. For instance, a rectangle transformed by a pure
        // translation matrix will result in a rectangle. A rectangle
        // transformed by a 45 degrees rotation matrix is not a rectangle.
        // If the matrix has a perspective component then we already know
        // it doesn't preserve rectangles.
        if (!(mType & kTypePerspective)) {
            if ((isZero(m00) && isZero(m11) && !isZero(m01) && !isZero(m10)) ||
                    (isZero(m01) && isZero(m10) && !isZero(m00) && !isZero(m11))) {
                mType |= kTypeRectToRect;
            }
        }
    }
    return mType;
}

uint8_t Matrix4::getGeometryType() const {
    return getType() & sGeometryMask;
}

bool Matrix4::rectToRect() const {
    return getType() & kTypeRectToRect;
}

bool Matrix4::positiveScale() const {
    return (data[kScaleX] > 0.0f && data[kScaleY] > 0.0f);
}

bool Matrix4::changesBounds() const {
    return getType() & (kTypeScale | kTypeAffine | kTypePerspective);
}

bool Matrix4::isPureTranslate() const {
    // NOTE: temporary hack to workaround ignoreTransform behavior with Z values
    // TODO: separate this into isPure2dTranslate vs isPure3dTranslate
    return getGeometryType() <= kTypeTranslate && (data[kTranslateZ] == 0.0f);
}

bool Matrix4::isSimple() const {
    return getGeometryType() <= (kTypeScale | kTypeTranslate) && (data[kTranslateZ] == 0.0f);
}

bool Matrix4::isIdentity() const {
    return getGeometryType() == kTypeIdentity;
}

bool Matrix4::isPerspective() const {
    return getType() & kTypePerspective;
}

void Matrix4::load(const float* v) {
    memcpy(data, v, sizeof(data));
    mType = kTypeUnknown;
}

void Matrix4::load(const Matrix4& v) {
    memcpy(data, v.data, sizeof(data));
    mType = v.getType();
}

void Matrix4::load(const SkMatrix& v) {
    memset(data, 0, sizeof(data));

    data[kScaleX]     = v[SkMatrix::kMScaleX];
    data[kSkewX]      = v[SkMatrix::kMSkewX];
    data[kTranslateX] = v[SkMatrix::kMTransX];

    data[kSkewY]      = v[SkMatrix::kMSkewY];
    data[kScaleY]     = v[SkMatrix::kMScaleY];
    data[kTranslateY] = v[SkMatrix::kMTransY];

    data[kPerspective0]  = v[SkMatrix::kMPersp0];
    data[kPerspective1]  = v[SkMatrix::kMPersp1];
    data[kPerspective2]  = v[SkMatrix::kMPersp2];

    data[kScaleZ] = 1.0f;

    // NOTE: The flags are compatible between SkMatrix and this class.
    //       However, SkMatrix::getType() does not return the flag
    //       kRectStaysRect. The return value is masked with 0xF
    //       so we need the extra rectStaysRect() check
    mType = v.getType();
    if (v.rectStaysRect()) {
        mType |= kTypeRectToRect;
    }
}

void Matrix4::copyTo(SkMatrix& v) const {
    v.reset();

    v.set(SkMatrix::kMScaleX, data[kScaleX]);
    v.set(SkMatrix::kMSkewX,  data[kSkewX]);
    v.set(SkMatrix::kMTransX, data[kTranslateX]);

    v.set(SkMatrix::kMSkewY,  data[kSkewY]);
    v.set(SkMatrix::kMScaleY, data[kScaleY]);
    v.set(SkMatrix::kMTransY, data[kTranslateY]);

    v.set(SkMatrix::kMPersp0, data[kPerspective0]);
    v.set(SkMatrix::kMPersp1, data[kPerspective1]);
    v.set(SkMatrix::kMPersp2, data[kPerspective2]);
}

void Matrix4::loadInverse(const Matrix4& v) {
    // Fast case for common translation matrices
    if (v.isPureTranslate()) {
        // Reset the matrix
        // Unnamed fields are never written to except by
        // loadIdentity(), they don't need to be reset
        data[kScaleX]       = 1.0f;
        data[kSkewX]        = 0.0f;

        data[kScaleY]       = 1.0f;
        data[kSkewY]        = 0.0f;

        data[kScaleZ]       = 1.0f;

        data[kPerspective0] = 0.0f;
        data[kPerspective1] = 0.0f;
        data[kPerspective2] = 1.0f;

        // No need to deal with kTranslateZ because isPureTranslate()
        // only returns true when the kTranslateZ component is 0
        data[kTranslateX]   = -v.data[kTranslateX];
        data[kTranslateY]   = -v.data[kTranslateY];
        data[kTranslateZ]   = 0.0f;

        // A "pure translate" matrix can be identity or translation
        mType = v.getType();
        return;
    }

    double scale = 1.0 /
            (v.data[kScaleX] * ((double) v.data[kScaleY]  * v.data[kPerspective2] -
                    (double) v.data[kTranslateY] * v.data[kPerspective1]) +
             v.data[kSkewX] * ((double) v.data[kTranslateY] * v.data[kPerspective0] -
                     (double) v.data[kSkewY] * v.data[kPerspective2]) +
             v.data[kTranslateX] * ((double) v.data[kSkewY] * v.data[kPerspective1] -
                     (double) v.data[kScaleY] * v.data[kPerspective0]));

    data[kScaleX] = (v.data[kScaleY] * v.data[kPerspective2] -
            v.data[kTranslateY] * v.data[kPerspective1]) * scale;
    data[kSkewX] = (v.data[kTranslateX] * v.data[kPerspective1] -
            v.data[kSkewX]  * v.data[kPerspective2]) * scale;
    data[kTranslateX] = (v.data[kSkewX] * v.data[kTranslateY] -
            v.data[kTranslateX] * v.data[kScaleY]) * scale;

    data[kSkewY] = (v.data[kTranslateY] * v.data[kPerspective0] -
            v.data[kSkewY]  * v.data[kPerspective2]) * scale;
    data[kScaleY] = (v.data[kScaleX] * v.data[kPerspective2] -
            v.data[kTranslateX] * v.data[kPerspective0]) * scale;
    data[kTranslateY] = (v.data[kTranslateX] * v.data[kSkewY] -
            v.data[kScaleX] * v.data[kTranslateY]) * scale;

    data[kPerspective0] = (v.data[kSkewY] * v.data[kPerspective1] -
            v.data[kScaleY] * v.data[kPerspective0]) * scale;
    data[kPerspective1] = (v.data[kSkewX] * v.data[kPerspective0] -
            v.data[kScaleX] * v.data[kPerspective1]) * scale;
    data[kPerspective2] = (v.data[kScaleX] * v.data[kScaleY] -
            v.data[kSkewX] * v.data[kSkewY]) * scale;

    mType = kTypeUnknown;
}

void Matrix4::copyTo(float* v) const {
    memcpy(v, data, sizeof(data));
}

float Matrix4::getTranslateX() const {
    return data[kTranslateX];
}

float Matrix4::getTranslateY() const {
    return data[kTranslateY];
}

void Matrix4::multiply(float v) {
    for (int i = 0; i < 16; i++) {
        data[i] *= v;
    }
    mType = kTypeUnknown;
}

void Matrix4::loadTranslate(float x, float y, float z) {
    loadIdentity();

    data[kTranslateX] = x;
    data[kTranslateY] = y;
    data[kTranslateZ] = z;

    mType = kTypeTranslate | kTypeRectToRect;
}

void Matrix4::loadScale(float sx, float sy, float sz) {
    loadIdentity();

    data[kScaleX] = sx;
    data[kScaleY] = sy;
    data[kScaleZ] = sz;

    mType = kTypeScale | kTypeRectToRect;
}

void Matrix4::loadSkew(float sx, float sy) {
    loadIdentity();

    data[kScaleX]       = 1.0f;
    data[kSkewX]        = sx;
    data[kTranslateX]   = 0.0f;

    data[kSkewY]        = sy;
    data[kScaleY]       = 1.0f;
    data[kTranslateY]   = 0.0f;

    data[kPerspective0] = 0.0f;
    data[kPerspective1] = 0.0f;
    data[kPerspective2] = 1.0f;

    mType = kTypeUnknown;
}

void Matrix4::loadRotate(float angle) {
    angle *= float(M_PI / 180.0f);
    float c = cosf(angle);
    float s = sinf(angle);

    loadIdentity();

    data[kScaleX]     = c;
    data[kSkewX]      = -s;

    data[kSkewY]      = s;
    data[kScaleY]     = c;

    mType = kTypeUnknown;
}

void Matrix4::loadRotate(float angle, float x, float y, float z) {
    data[kPerspective0]  = 0.0f;
    data[kPerspective1]  = 0.0f;
    data[11]             = 0.0f;
    data[kTranslateX]    = 0.0f;
    data[kTranslateY]    = 0.0f;
    data[kTranslateZ]    = 0.0f;
    data[kPerspective2]  = 1.0f;

    angle *= float(M_PI / 180.0f);
    float c = cosf(angle);
    float s = sinf(angle);

    const float length = sqrtf(x * x + y * y + z * z);
    float recipLen = 1.0f / length;
    x *= recipLen;
    y *= recipLen;
    z *= recipLen;

    const float nc = 1.0f - c;
    const float xy = x * y;
    const float yz = y * z;
    const float zx = z * x;
    const float xs = x * s;
    const float ys = y * s;
    const float zs = z * s;

    data[kScaleX] = x * x * nc +  c;
    data[kSkewX]  =    xy * nc - zs;
    data[8]       =    zx * nc + ys;
    data[kSkewY]  =    xy * nc + zs;
    data[kScaleY] = y * y * nc +  c;
    data[9]       =    yz * nc - xs;
    data[2]       =    zx * nc - ys;
    data[6]       =    yz * nc + xs;
    data[kScaleZ] = z * z * nc +  c;

    mType = kTypeUnknown;
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

    mType = kTypeUnknown;
}

void Matrix4::loadOrtho(float left, float right, float bottom, float top, float near, float far) {
    loadIdentity();

    data[kScaleX] = 2.0f / (right - left);
    data[kScaleY] = 2.0f / (top - bottom);
    data[kScaleZ] = -2.0f / (far - near);
    data[kTranslateX] = -(right + left) / (right - left);
    data[kTranslateY] = -(top + bottom) / (top - bottom);
    data[kTranslateZ] = -(far + near) / (far - near);

    mType = kTypeTranslate | kTypeScale | kTypeRectToRect;
}

float Matrix4::mapZ(const Vector3& orig) const {
    // duplicates logic for mapPoint3d's z coordinate
    return orig.x * data[2] + orig.y * data[6] + orig.z * data[kScaleZ] + data[kTranslateZ];
}

void Matrix4::mapPoint3d(Vector3& vec) const {
    //TODO: optimize simple case
    const Vector3 orig(vec);
    vec.x = orig.x * data[kScaleX] + orig.y * data[kSkewX] + orig.z * data[8] + data[kTranslateX];
    vec.y = orig.x * data[kSkewY] + orig.y * data[kScaleY] + orig.z * data[9] + data[kTranslateY];
    vec.z = orig.x * data[2] + orig.y * data[6] + orig.z * data[kScaleZ] + data[kTranslateZ];
}

#define MUL_ADD_STORE(a, b, c) a = (a) * (b) + (c)

void Matrix4::mapPoint(float& x, float& y) const {
    if (isSimple()) {
        MUL_ADD_STORE(x, data[kScaleX], data[kTranslateX]);
        MUL_ADD_STORE(y, data[kScaleY], data[kTranslateY]);
        return;
    }

    float dx = x * data[kScaleX] + y * data[kSkewX] + data[kTranslateX];
    float dy = x * data[kSkewY] + y * data[kScaleY] + data[kTranslateY];
    float dz = x * data[kPerspective0] + y * data[kPerspective1] + data[kPerspective2];
    if (dz) dz = 1.0f / dz;

    x = dx * dz;
    y = dy * dz;
}

void Matrix4::mapRect(Rect& r) const {
    if (isIdentity()) return;

    if (isSimple()) {
        MUL_ADD_STORE(r.left, data[kScaleX], data[kTranslateX]);
        MUL_ADD_STORE(r.right, data[kScaleX], data[kTranslateX]);
        MUL_ADD_STORE(r.top, data[kScaleY], data[kTranslateY]);
        MUL_ADD_STORE(r.bottom, data[kScaleY], data[kTranslateY]);

        if (r.left > r.right) {
            float x = r.left;
            r.left = r.right;
            r.right = x;
        }

        if (r.top > r.bottom) {
            float y = r.top;
            r.top = r.bottom;
            r.bottom = y;
        }

        return;
    }

    float vertices[] = {
        r.left, r.top,
        r.right, r.top,
        r.right, r.bottom,
        r.left, r.bottom
    };

    float x, y, z;

    for (int i = 0; i < 8; i+= 2) {
        float px = vertices[i];
        float py = vertices[i + 1];

        x = px * data[kScaleX] + py * data[kSkewX] + data[kTranslateX];
        y = px * data[kSkewY] + py * data[kScaleY] + data[kTranslateY];
        z = px * data[kPerspective0] + py * data[kPerspective1] + data[kPerspective2];
        if (z) z = 1.0f / z;

        vertices[i] = x * z;
        vertices[i + 1] = y * z;
    }

    r.left = r.right = vertices[0];
    r.top = r.bottom = vertices[1];

    for (int i = 2; i < 8; i += 2) {
        x = vertices[i];
        y = vertices[i + 1];

        if (x < r.left) r.left = x;
        else if (x > r.right) r.right = x;
        if (y < r.top) r.top = y;
        else if (y > r.bottom) r.bottom = y;
    }
}

void Matrix4::decomposeScale(float& sx, float& sy) const {
    float len;
    len = data[mat4::kScaleX] * data[mat4::kScaleX] + data[mat4::kSkewX] * data[mat4::kSkewX];
    sx = copysignf(sqrtf(len), data[mat4::kScaleX]);
    len = data[mat4::kScaleY] * data[mat4::kScaleY] + data[mat4::kSkewY] * data[mat4::kSkewY];
    sy = copysignf(sqrtf(len), data[mat4::kScaleY]);
}

void Matrix4::dump(const char* label) const {
    ALOGD("%s[simple=%d, type=0x%x", label ? label : "Matrix4", isSimple(), getType());
    ALOGD("  %f %f %f %f", data[kScaleX], data[kSkewX], data[8], data[kTranslateX]);
    ALOGD("  %f %f %f %f", data[kSkewY], data[kScaleY], data[9], data[kTranslateY]);
    ALOGD("  %f %f %f %f", data[2], data[6], data[kScaleZ], data[kTranslateZ]);
    ALOGD("  %f %f %f %f", data[kPerspective0], data[kPerspective1], data[11], data[kPerspective2]);
    ALOGD("]");
}

}; // namespace uirenderer
}; // namespace android
