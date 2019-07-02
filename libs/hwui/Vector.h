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

#ifndef ANDROID_HWUI_VECTOR_H
#define ANDROID_HWUI_VECTOR_H

#include <math.h>
#include <utils/Log.h>

namespace android {
namespace uirenderer {

///////////////////////////////////////////////////////////////////////////////
// Classes
///////////////////////////////////////////////////////////////////////////////

// MUST BE A POD - this means no ctor or dtor!
struct Vector2 {
    float x;
    float y;

    float lengthSquared() const { return x * x + y * y; }

    float length() const { return sqrt(x * x + y * y); }

    void operator+=(const Vector2& v) {
        x += v.x;
        y += v.y;
    }

    void operator-=(const Vector2& v) {
        x -= v.x;
        y -= v.y;
    }

    void operator+=(const float v) {
        x += v;
        y += v;
    }

    void operator-=(const float v) {
        x -= v;
        y -= v;
    }

    void operator/=(float s) {
        x /= s;
        y /= s;
    }

    void operator*=(float s) {
        x *= s;
        y *= s;
    }

    Vector2 operator+(const Vector2& v) const { return (Vector2){x + v.x, y + v.y}; }

    Vector2 operator-(const Vector2& v) const { return (Vector2){x - v.x, y - v.y}; }

    Vector2 operator/(float s) const { return (Vector2){x / s, y / s}; }

    Vector2 operator*(float s) const { return (Vector2){x * s, y * s}; }

    void normalize() {
        float s = 1.0f / length();
        x *= s;
        y *= s;
    }

    Vector2 copyNormalized() const {
        Vector2 v = {x, y};
        v.normalize();
        return v;
    }

    float dot(const Vector2& v) const { return x * v.x + y * v.y; }

    float cross(const Vector2& v) const { return x * v.y - y * v.x; }

    void dump() { ALOGD("Vector2[%.2f, %.2f]", x, y); }
};  // class Vector2

// MUST BE A POD - this means no ctor or dtor!
class Vector3 {
public:
    float x;
    float y;
    float z;

    Vector3 operator+(const Vector3& v) const { return (Vector3){x + v.x, y + v.y, z + v.z}; }

    Vector3 operator-(const Vector3& v) const { return (Vector3){x - v.x, y - v.y, z - v.z}; }

    Vector3 operator/(float s) const { return (Vector3){x / s, y / s, z / s}; }

    Vector3 operator*(float s) const { return (Vector3){x * s, y * s, z * s}; }

    void dump(const char* label = "Vector3") const {
        ALOGD("%s[%.2f, %.2f, %.2f]", label, x, y, z);
    }
};

}  // namespace uirenderer
}  // namespace android

#endif  // ANDROID_HWUI_VECTOR_H
