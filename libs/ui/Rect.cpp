/*
 *  Rect.cpp
 *  Android
 *
 *  Created on 10/14/05.
 *  Copyright 2005 The Android Open Source Project
 *
 */

#include <ui/Rect.h>

namespace android {

inline int min(int a, int b) {
    return (a<b) ? a : b;
}

inline int max(int a, int b) {
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

Rect& Rect::offsetTo(int x, int y)
{
    right -= left - x;
    bottom -= top - y;
    left = x;
    top = y;
    return *this;
}

Rect& Rect::offsetBy(int x, int y)
{
    left += x;
    top  += y;
    right+= x;
    bottom+=y;
    return *this;
}

Rect Rect::operator + (const Point& rhs) const
{
    return Rect(left+rhs.x, top+rhs.y, right+rhs.x, bottom+rhs.y); 
}

Rect Rect::operator - (const Point& rhs) const
{
    return Rect(left-rhs.x, top-rhs.y, right-rhs.x, bottom-rhs.y); 
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
