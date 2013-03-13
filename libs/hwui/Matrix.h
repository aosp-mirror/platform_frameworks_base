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

#ifndef ANDROID_HWUI_MATRIX_H
#define ANDROID_HWUI_MATRIX_H

#include <SkMatrix.h>

#include <cutils/compiler.h>

#include "Rect.h"

namespace android {
namespace uirenderer {

///////////////////////////////////////////////////////////////////////////////
// Classes
///////////////////////////////////////////////////////////////////////////////

class ANDROID_API Matrix4 {
public:
    float data[16];

    enum Entry {
        kScaleX = 0,
        kSkewY = 1,
        kPerspective0 = 3,
        kSkewX = 4,
        kScaleY = 5,
        kPerspective1 = 7,
        kScaleZ = 10,
        kTranslateX = 12,
        kTranslateY = 13,
        kTranslateZ = 14,
        kPerspective2 = 15
    };

    // NOTE: The flags from kTypeIdentity to kTypePerspective
    //       must be kept in sync with the type flags found
    //       in SkMatrix
    enum Type {
        kTypeIdentity = 0,
        kTypeTranslate = 0x1,
        kTypeScale = 0x2,
        kTypeAffine = 0x4,
        kTypePerspective = 0x8,
        kTypeRectToRect = 0x10,
        kTypeUnknown = 0x20,
    };

    static const int sGeometryMask = 0xf;

    Matrix4() {
        loadIdentity();
    }

    Matrix4(const float* v) {
        load(v);
    }

    Matrix4(const Matrix4& v) {
        load(v);
    }

    Matrix4(const SkMatrix& v) {
        load(v);
    }

    float operator[](int index) const {
        return data[index];
    }

    float& operator[](int index) {
        mType = kTypeUnknown;
        return data[index];
    }

    Matrix4& operator=(const SkMatrix& v) {
        load(v);
        return *this;
    }

    friend bool operator==(const Matrix4& a, const Matrix4& b) {
        return !memcmp(&a.data[0], &b.data[0], 16 * sizeof(float));
    }

    friend bool operator!=(const Matrix4& a, const Matrix4& b) {
        return !(a == b);
    }

    void loadIdentity();

    void load(const float* v);
    void load(const Matrix4& v);
    void load(const SkMatrix& v);

    void loadInverse(const Matrix4& v);

    void loadTranslate(float x, float y, float z);
    void loadScale(float sx, float sy, float sz);
    void loadSkew(float sx, float sy);
    void loadRotate(float angle);
    void loadRotate(float angle, float x, float y, float z);
    void loadMultiply(const Matrix4& u, const Matrix4& v);

    void loadOrtho(float left, float right, float bottom, float top, float near, float far);

    uint32_t getType() const;

    void multiply(const Matrix4& v) {
        Matrix4 u;
        u.loadMultiply(*this, v);
        load(u);
    }

    void multiply(float v);

    void translate(float x, float y, float z) {
        Matrix4 u;
        u.loadTranslate(x, y, z);
        multiply(u);
    }

    void scale(float sx, float sy, float sz) {
        Matrix4 u;
        u.loadScale(sx, sy, sz);
        multiply(u);
    }

    void skew(float sx, float sy) {
        Matrix4 u;
        u.loadSkew(sx, sy);
        multiply(u);
    }

    void rotate(float angle, float x, float y, float z) {
        Matrix4 u;
        u.loadRotate(angle, x, y, z);
        multiply(u);
    }

    /**
     * If the matrix is identity or translate and/or scale.
     */
    bool isSimple() const;
    bool isPureTranslate() const;
    bool isIdentity() const;
    bool isPerspective() const;
    bool rectToRect() const;

    bool changesBounds() const;

    void copyTo(float* v) const;
    void copyTo(SkMatrix& v) const;

    void mapRect(Rect& r) const;
    void mapPoint(float& x, float& y) const;

    float getTranslateX() const;
    float getTranslateY() const;

    void decomposeScale(float& sx, float& sy) const;

    void dump() const;

    static const Matrix4& identity();

private:
    mutable uint32_t mType;

    inline float get(int i, int j) const {
        return data[i * 4 + j];
    }

    inline void set(int i, int j, float v) {
        data[i * 4 + j] = v;
    }

    uint32_t getGeometryType() const;

}; // class Matrix4

///////////////////////////////////////////////////////////////////////////////
// Types
///////////////////////////////////////////////////////////////////////////////

typedef Matrix4 mat4;

}; // namespace uirenderer
}; // namespace android

#endif // ANDROID_HWUI_MATRIX_H
