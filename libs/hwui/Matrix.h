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

#pragma once

#include "Rect.h"

#include <SkMatrix.h>
#include <cutils/compiler.h>
#include <iomanip>
#include <ostream>

namespace android {
namespace uirenderer {

#define SK_MATRIX_STRING "[%.2f %.2f %.2f] [%.2f %.2f %.2f] [%.2f %.2f %.2f]"
#define SK_MATRIX_STRING_V "[%.9f %.9f %.9f] [%.9f %.9f %.9f] [%.9f %.9f %.9f]"
#define SK_MATRIX_ARGS(m)                                                                      \
    (m)->get(0), (m)->get(1), (m)->get(2), (m)->get(3), (m)->get(4), (m)->get(5), (m)->get(6), \
            (m)->get(7), (m)->get(8)

#define MATRIX_4_STRING                           \
    "[%.2f %.2f %.2f %.2f] [%.2f %.2f %.2f %.2f]" \
    " [%.2f %.2f %.2f %.2f] [%.2f %.2f %.2f %.2f]"
#define MATRIX_4_ARGS(m)                                                                           \
    (m)->data[0], (m)->data[4], (m)->data[8], (m)->data[12], (m)->data[1], (m)->data[5],           \
            (m)->data[9], (m)->data[13], (m)->data[2], (m)->data[6], (m)->data[10], (m)->data[14], \
            (m)->data[3], (m)->data[7], (m)->data[11], (m)->data[15]

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

    Matrix4() { loadIdentity(); }

    explicit Matrix4(const float* v) { load(v); }

    Matrix4(const SkMatrix& v) {  // NOLINT(google-explicit-constructor)
        load(v);
    }

    float operator[](int index) const { return data[index]; }

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

    friend bool operator!=(const Matrix4& a, const Matrix4& b) { return !(a == b); }

    void loadIdentity();

    void load(const float* v);
    void load(const SkMatrix& v);

    void loadInverse(const Matrix4& v);

    void loadTranslate(float x, float y, float z);
    void loadScale(float sx, float sy, float sz);
    void loadSkew(float sx, float sy);
    void loadRotate(float angle);
    void loadRotate(float angle, float x, float y, float z);
    void loadMultiply(const Matrix4& u, const Matrix4& v);

    void loadOrtho(float left, float right, float bottom, float top, float near, float far);
    void loadOrtho(int width, int height) { loadOrtho(0, width, height, 0, -1, 1); }

    uint8_t getType() const;

    void multiplyInverse(const Matrix4& v) {
        Matrix4 inv;
        inv.loadInverse(v);
        multiply(inv);
    }

    void multiply(const Matrix4& v) {
        if (!v.isIdentity()) {
            Matrix4 u;
            u.loadMultiply(*this, v);
            *this = u;
        }
    }

    void multiply(float v);

    void translate(float x, float y, float z = 0) {
        if ((getType() & sGeometryMask) <= kTypeTranslate) {
            data[kTranslateX] += x;
            data[kTranslateY] += y;
            data[kTranslateZ] += z;
            mType |= kTypeUnknown;
        } else {
            // Doing a translation will only affect the translate bit of the type
            // Save the type
            uint8_t type = mType;

            Matrix4 u;
            u.loadTranslate(x, y, z);
            multiply(u);

            // Restore the type and fix the translate bit
            mType = type;
            if (data[kTranslateX] != 0.0f || data[kTranslateY] != 0.0f) {
                mType |= kTypeTranslate;
            } else {
                mType &= ~kTypeTranslate;
            }
        }
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
    bool positiveScale() const;

    bool changesBounds() const;

    void copyTo(float* v) const;
    void copyTo(SkMatrix& v) const;

    float mapZ(const Vector3& orig) const;
    void mapPoint3d(Vector3& vec) const;
    void mapPoint(float& x, float& y) const;  // 2d only
    void mapRect(Rect& r) const;              // 2d only

    float getTranslateX() const;
    float getTranslateY() const;

    void decomposeScale(float& sx, float& sy) const;

    void dump(const char* label = nullptr) const;

    friend std::ostream& operator<<(std::ostream& os, const Matrix4& matrix) {
        if (matrix.isSimple()) {
            os << "offset " << matrix.getTranslateX() << "x" << matrix.getTranslateY();
            if (!matrix.isPureTranslate()) {
                os << ", scale " << matrix[kScaleX] << "x" << matrix[kScaleY];
            }
        } else {
            os << "[" << matrix[0];
            for (int i = 1; i < 16; i++) {
                os << ", " << matrix[i];
            }
            os << "]";
        }
        return os;
    }

    static const Matrix4& identity();

    void invalidateType() { mType = kTypeUnknown; }

private:
    mutable uint8_t mType;

    inline float get(int i, int j) const { return data[i * 4 + j]; }

    inline void set(int i, int j, float v) { data[i * 4 + j] = v; }

    uint8_t getGeometryType() const;

};  // class Matrix4

///////////////////////////////////////////////////////////////////////////////
// Types
///////////////////////////////////////////////////////////////////////////////

typedef Matrix4 mat4;

}  // namespace uirenderer
}  // namespace android
