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
#include <SkRect.h>

#include <utils/Log.h>

#include "Vertex.h"

namespace android {
namespace uirenderer {

#define RECT_STRING "%5.2f %5.2f %5.2f %5.2f"
#define RECT_ARGS(r) \
    (r).left, (r).top, (r).right, (r).bottom
#define SK_RECT_ARGS(r) \
    (r).left(), (r).top(), (r).right(), (r).bottom()

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

    inline Rect(const SkRect& rect):
            left(rect.fLeft),
            top(rect.fTop),
            right(rect.fRight),
            bottom(rect.fBottom) {
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
        Rect tmp(l, t, r, b);
        intersectWith(tmp);
        if (!tmp.isEmpty()) {
            set(tmp);
            return true;
        }
        return false;
    }

    bool intersect(const Rect& r) {
        return intersect(r.left, r.top, r.right, r.bottom);
    }

    inline bool contains(float l, float t, float r, float b) const {
        return l >= left && t >= top && r <= right && b <= bottom;
    }

    inline bool contains(const Rect& r) const {
        return contains(r.left, r.top, r.right, r.bottom);
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

    void inset(float delta) {
        outset(-delta);
    }

    void outset(float delta) {
        left -= delta;
        top -= delta;
        right += delta;
        bottom += delta;
    }

    void outset(float xdelta, float ydelta) {
        left -= xdelta;
        top -= ydelta;
        right += xdelta;
        bottom += ydelta;
    }

    /**
     * Similar to snapToPixelBoundaries, but estimates bounds conservatively to handle GL rounding
     * errors.
     *
     * This function should be used whenever estimating the damage rect of geometry already mapped
     * into layer space.
     */
    void snapGeometryToPixelBoundaries(bool snapOut) {
        if (snapOut) {
            /* For AA geometry with a ramp perimeter, don't snap by rounding - AA geometry will have
             * a 0.5 pixel perimeter not accounted for in its bounds. Instead, snap by
             * conservatively rounding out the bounds with floor/ceil.
             *
             * In order to avoid changing integer bounds with floor/ceil due to rounding errors
             * inset the bounds first by the fudge factor. Very small fraction-of-a-pixel errors
             * from this inset will only incur similarly small errors in output, due to transparency
             * in extreme outside of the geometry.
             */
            left = floorf(left + Vertex::GeometryFudgeFactor());
            top = floorf(top + Vertex::GeometryFudgeFactor());
            right = ceilf(right - Vertex::GeometryFudgeFactor());
            bottom = ceilf(bottom - Vertex::GeometryFudgeFactor());
        } else {
            /* For other geometry, we do the regular rounding in order to snap, but also outset the
             * bounds by a fudge factor. This ensures that ambiguous geometry (e.g. a non-AA Rect
             * with top left at (0.5, 0.5)) will err on the side of a larger damage rect.
             */
            left = floorf(left + 0.5f - Vertex::GeometryFudgeFactor());
            top = floorf(top + 0.5f - Vertex::GeometryFudgeFactor());
            right = floorf(right + 0.5f + Vertex::GeometryFudgeFactor());
            bottom = floorf(bottom + 0.5f + Vertex::GeometryFudgeFactor());
        }
    }

    void snapToPixelBoundaries() {
        left = floorf(left + 0.5f);
        top = floorf(top + 0.5f);
        right = floorf(right + 0.5f);
        bottom = floorf(bottom + 0.5f);
    }

    void roundOut() {
        left = floorf(left);
        top = floorf(top);
        right = ceilf(right);
        bottom = ceilf(bottom);
    }

    void expandToCoverVertex(float x, float y) {
        left = fminf(left, x);
        top = fminf(top, y);
        right = fmaxf(right, x);
        bottom = fmaxf(bottom, y);
    }

    void dump(const char* label = NULL) const {
        ALOGD("%s[l=%f t=%f r=%f b=%f]", label ? label : "Rect", left, top, right, bottom);
    }

private:
    void intersectWith(Rect& tmp) const {
        tmp.left = fmaxf(left, tmp.left);
        tmp.top = fmaxf(top, tmp.top);
        tmp.right = fminf(right, tmp.right);
        tmp.bottom = fminf(bottom, tmp.bottom);
    }

    Rect intersectWith(float l, float t, float r, float b) const {
        Rect tmp;
        tmp.left = fmaxf(left, l);
        tmp.top = fmaxf(top, t);
        tmp.right = fminf(right, r);
        tmp.bottom = fminf(bottom, b);
        return tmp;
    }

}; // class Rect

}; // namespace uirenderer
}; // namespace android

#endif // ANDROID_HWUI_RECT_H
