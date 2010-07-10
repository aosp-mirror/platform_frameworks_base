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

#include <ui/Rect.h>

namespace android {

static inline int32_t min(int32_t a, int32_t b) {
    return (a<b) ? a : b;
}

static inline int32_t max(int32_t a, int32_t b) {
    return (a>b) ? a : b;
}

void Rect::makeInvalid() {
    left = 0;
    top = 0;
    right = -1;
    bottom = -1;
}

bool Rect::operator < (const Rect& rhs) const
{
    if (top<rhs.top) {
        return true;
    } else if (top == rhs.top) {
        if (left < rhs.left) {
            return true;
        } else if (left == rhs.left) {
            if (bottom<rhs.bottom) {
                return true;
            } else if (bottom == rhs.bottom) {
                if (right<rhs.right) {
                    return true;
                }
            }
        }
    }
    return false;
}

Rect& Rect::offsetTo(int32_t x, int32_t y)
{
    right -= left - x;
    bottom -= top - y;
    left = x;
    top = y;
    return *this;
}

Rect& Rect::offsetBy(int32_t x, int32_t y)
{
    left += x;
    top  += y;
    right+= x;
    bottom+=y;
    return *this;
}

const Rect Rect::operator + (const Point& rhs) const
{
    const Rect result(left+rhs.x, top+rhs.y, right+rhs.x, bottom+rhs.y);
    return result;
}

const Rect Rect::operator - (const Point& rhs) const
{
    const Rect result(left-rhs.x, top-rhs.y, right-rhs.x, bottom-rhs.y);
    return result;
}

bool Rect::intersect(const Rect& with, Rect* result) const
{
    result->left    = max(left, with.left);
    result->top     = max(top, with.top);
    result->right   = min(right, with.right);
    result->bottom  = min(bottom, with.bottom);
    return !(result->isEmpty());
}

}; // namespace android
