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

#ifndef ANDROID_HWUI_RECT_H
#define ANDROID_HWUI_RECT_H

#include <cmath>

#include <utils/Log.h>

namespace android {
namespace uirenderer {

///////////////////////////////////////////////////////////////////////////////
// Structs
///////////////////////////////////////////////////////////////////////////////

class Rect {
public:
    float left;
    float top;
    float right;
    float bottom;

    // Used by Region
    typedef float value_type;

    // we don't provide copy-ctor and operator= on purpose
    // because we want the compiler generated versions

    inline Rect():
            left(0),
            top(0),
            right(0),
            bottom(0) {
    }

    inline Rect(float left, float top, float right, float bottom):
            left(left),
            top(top),
            right(right),
            bottom(bottom) {
    }

    inline Rect(float width, float height):
            left(0.0f),
            top(0.0f),
            right(width),
            bottom(height) {
    }

    friend int operator==(const Rect& a, const Rect& b) {
        return !memcmp(&a, &b, sizeof(a));
    }

    friend int operator!=(const Rect& a, const Rect& b) {
        return memcmp(&a, &b, sizeof(a));
    }

    inline void clear() {
        left = top = right = bottom = 0.0f;
    }

    inline bool isEmpty() const {
        // this is written in such way this it'll handle NANs to return
        // true (empty)
        return !((left < right) && (top < bottom));
    }

    inline void setEmpty() {
        left = top = right = bottom = 0.0f;
    }

    inline void set(float left, float top, float right, float bottom) {
        this->left = left;
        this->right = right;
        this->top = top;
        this->bottom = bottom;
    }

    inline void set(const Rect& r) {
        set(r.left, r.top, r.right, r.bottom);
    }

    inline float getWidth() const {
        return right - left;
    }

    inline float getHeight() const {
        return bottom - top;
    }

    bool intersects(float l, float t, float r, float b) const {
        return !intersectWith(l, t, r, b).isEmpty();
    }

    bool intersects(const Rect& r) const {
        return intersects(r.left, r.top, r.right, r.bottom);
    }

    bool intersect(float l, float t, float r, float b) {
        Rect tmp(intersectWith(l, t, r, b));
        if (!tmp.isEmpty()) {
            set(tmp);
            return true;
        }
        return false;
    }

    bool intersect(const Rect& r) {
        return intersect(r.left, r.top, r.right, r.bottom);
    }

    bool unionWith(const Rect& r) {
        if (r.left < r.right && r.top < r.bottom) {
            if (left < right && top < bottom) {
                if (left > r.left) left = r.left;
                if (top > r.top) top = r.top;
                if (right < r.right) right = r.right;
                if (bottom < r.bottom) bottom = r.bottom;
                return true;
            } else {
                left = r.left;
                top = r.top;
                right = r.right;
                bottom = r.bottom;
                return true;
            }
        }
        return false;
    }

    void translate(float dx, float dy) {
        left += dx;
        right += dx;
        top += dy;
        bottom += dy;
    }

    void snapToPixelBoundaries() {
        left = floorf(left + 0.5f);
        top = floorf(top + 0.5f);
        right = floorf(right + 0.5f);
        bottom = floorf(bottom + 0.5f);
    }

    void dump() const {
        LOGD("Rect[l=%f t=%f r=%f b=%f]", left, top, right, bottom);
    }

private:
    static inline float min(float a, float b) { return (a < b) ? a : b; }
    static inline float max(float a, float b) { return (a > b) ? a : b; }

    Rect intersectWith(float l, float t, float r, float b) const {
        Rect tmp;
        tmp.left = max(left, l);
        tmp.top = max(top, t);
        tmp.right = min(right, r);
        tmp.bottom = min(bottom, b);
        return tmp;
    }

}; // class Rect

}; // namespace uirenderer
}; // namespace android

#endif // ANDROID_HWUI_RECT_H
