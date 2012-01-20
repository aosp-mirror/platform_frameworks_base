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

namespace android {
namespace uirenderer {

///////////////////////////////////////////////////////////////////////////////
// Classes
///////////////////////////////////////////////////////////////////////////////

struct Vector2 {
    float x;
    float y;

    Vector2() :
        x(0.0f), y(0.0f) {
    }

    Vector2(float px, float py) :
        x(px), y(py) {
    }

    float length() const {
        return sqrt(x * x + y * y);
    }

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

    Vector2 operator+(const Vector2& v) const {
        return Vector2(x + v.x, y + v.y);
    }

    Vector2 operator-(const Vector2& v) const {
        return Vector2(x - v.x, y - v.y);
    }

    Vector2 operator/(float s) const {
        return Vector2(x / s, y / s);
    }

    Vector2 operator*(float s) const {
        return Vector2(x * s, y * s);
    }

    void normalize() {
        float s = 1.0f / length();
        x *= s;
        y *= s;
    }

    Vector2 copyNormalized() const {
        Vector2 v(x, y);
        v.normalize();
        return v;
    }

    float dot(const Vector2& v) const {
        return x * v.x + y * v.y;
    }

    void dump() {
        ALOGD("Vector2[%.2f, %.2f]", x, y);
    }
}; // class Vector2

///////////////////////////////////////////////////////////////////////////////
// Types
///////////////////////////////////////////////////////////////////////////////

typedef Vector2 vec2;

}; // namespace uirenderer
}; // namespace android

#endif // ANDROID_HWUI_VECTOR_H
