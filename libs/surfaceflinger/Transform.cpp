/*
 * Copyright (C) 2007 The Android Open Source Project
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

#include <ui/Region.h>

#include <private/pixelflinger/ggl_fixed.h>

#include "Transform.h"

// ---------------------------------------------------------------------------

#define LIKELY( exp )       (__builtin_expect( (exp) != 0, true  ))
#define UNLIKELY( exp )     (__builtin_expect( (exp) != 0, false ))

// ---------------------------------------------------------------------------

namespace android {

// ---------------------------------------------------------------------------

Transform::Transform()
    : mType(0)
{
    mTransform.reset();
}

Transform::Transform(const Transform&  other)
    : mTransform(other.mTransform), mType(other.mType)
{
}

Transform::~Transform() {
}

Transform Transform::operator * (const Transform& rhs) const
{
    if (LIKELY(mType == 0))
        return rhs;

    Transform r(*this);
    r.mTransform.preConcat(rhs.mTransform);
    r.mType |= rhs.mType;
    return r;
}

float Transform::operator [] (int i) const
{
    float r = 0;
    switch(i) {
        case 0: r = SkScalarToFloat( mTransform[SkMatrix::kMScaleX] );  break;
        case 1: r = SkScalarToFloat( mTransform[SkMatrix::kMSkewX] );   break;
        case 2: r = SkScalarToFloat( mTransform[SkMatrix::kMSkewY] );   break;
        case 3: r = SkScalarToFloat( mTransform[SkMatrix::kMScaleY] );  break;
    }
    return r;
}

uint8_t Transform::type() const
{
    if (UNLIKELY(mType & 0x80000000)) {
        mType = mTransform.getType();
    }
    return uint8_t(mType & 0xFF);
}

bool Transform::transformed() const {
    return type() > SkMatrix::kTranslate_Mask;
}

int Transform::tx() const {
    return SkScalarRound( mTransform[SkMatrix::kMTransX] );
}

int Transform::ty() const {
    return SkScalarRound( mTransform[SkMatrix::kMTransY] );
}

void Transform::reset() {
    mTransform.reset();
    mType = 0;
}

void Transform::set( float xx, float xy,
                     float yx, float yy)
{
    mTransform.set(SkMatrix::kMScaleX, SkFloatToScalar(xx));
    mTransform.set(SkMatrix::kMSkewX, SkFloatToScalar(xy));
    mTransform.set(SkMatrix::kMSkewY, SkFloatToScalar(yx));
    mTransform.set(SkMatrix::kMScaleY, SkFloatToScalar(yy));
    mType |= 0x80000000;
}

void Transform::set(float radian, float x, float y)
{
    float r00 = cosf(radian);    float r01 = -sinf(radian);
    float r10 = sinf(radian);    float r11 =  cosf(radian);
    mTransform.set(SkMatrix::kMScaleX, SkFloatToScalar(r00));
    mTransform.set(SkMatrix::kMSkewX, SkFloatToScalar(r01));
    mTransform.set(SkMatrix::kMSkewY, SkFloatToScalar(r10));
    mTransform.set(SkMatrix::kMScaleY, SkFloatToScalar(r11));
    mTransform.set(SkMatrix::kMTransX, SkIntToScalar(x - r00*x - r01*y));
    mTransform.set(SkMatrix::kMTransY, SkIntToScalar(y - r10*x - r11*y));
    mType |= 0x80000000 | SkMatrix::kTranslate_Mask;
}

void Transform::scale(float s, float x, float y)
{
    mTransform.postScale(s, s, x, y); 
    mType |= 0x80000000;
}

void Transform::set(int tx, int ty)
{
    if (tx | ty) {
        mTransform.set(SkMatrix::kMTransX, SkIntToScalar(tx));
        mTransform.set(SkMatrix::kMTransY, SkIntToScalar(ty));
        mType |= SkMatrix::kTranslate_Mask;
    } else {
        mTransform.set(SkMatrix::kMTransX, 0);
        mTransform.set(SkMatrix::kMTransY, 0);
        mType &= ~SkMatrix::kTranslate_Mask;
    }
}

void Transform::transform(GLfixed* point, int x, int y) const
{
    SkPoint s;
    mTransform.mapXY(SkIntToScalar(x), SkIntToScalar(y), &s);
    point[0] = SkScalarToFixed(s.fX);
    point[1] = SkScalarToFixed(s.fY);
}

Rect Transform::makeBounds(int w, int h) const
{
    Rect r;
    SkRect d, s;
    s.set(0, 0, SkIntToScalar(w), SkIntToScalar(h));
    mTransform.mapRect(&d, s);
    r.left   = SkScalarRound( d.fLeft );
    r.top    = SkScalarRound( d.fTop );
    r.right  = SkScalarRound( d.fRight );
    r.bottom = SkScalarRound( d.fBottom );
    return r;
}

Rect Transform::transform(const Rect& bounds) const
{
    Rect r;
    SkRect d, s;
    s.set(  SkIntToScalar( bounds.left ),
            SkIntToScalar( bounds.top ),
            SkIntToScalar( bounds.right ),
            SkIntToScalar( bounds.bottom ));
    mTransform.mapRect(&d, s);
    r.left   = SkScalarRound( d.fLeft );
    r.top    = SkScalarRound( d.fTop );
    r.right  = SkScalarRound( d.fRight );
    r.bottom = SkScalarRound( d.fBottom );
    return r;
}

Region Transform::transform(const Region& reg) const
{
    Region out;
    if (UNLIKELY(transformed())) {
        if (LIKELY(preserveRects())) {
            Rect r;
            Region::iterator iterator(reg);
            while (iterator.iterate(&r)) {
                out.orSelf(transform(r));
            }
        } else {
            out.set(transform(reg.bounds()));
        }
    } else {
        out = reg.translate(tx(), ty());
    }
    return out;
}

int32_t Transform::getOrientation() const
{
    uint32_t flags = 0;
    if (UNLIKELY(transformed())) {
        SkScalar a = mTransform[SkMatrix::kMScaleX];
        SkScalar b = mTransform[SkMatrix::kMSkewX];
        SkScalar c = mTransform[SkMatrix::kMSkewY];
        SkScalar d = mTransform[SkMatrix::kMScaleY];
        if (b==0 && c==0 && a && d) {
            if (a<0)    flags |= FLIP_H;
            if (d<0)    flags |= FLIP_V;
        } else if (b && c && a==0 && d==0) {
            flags |= ROT_90;
            if (b>0)    flags |= FLIP_H;
            if (c<0)    flags |= FLIP_V;
        } else {
            flags = 0x80000000;
        }
    }
    return flags;
}

bool Transform::preserveRects() const
{
    return mTransform.rectStaysRect();
}

// ---------------------------------------------------------------------------

}; // namespace android
