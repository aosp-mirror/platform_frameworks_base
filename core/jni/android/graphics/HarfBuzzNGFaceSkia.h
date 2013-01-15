/*
 * Copyright 2011, The Android Open Source Project
 * Copyright 2011, Google Inc. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 *  * Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 *  * Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS ``AS IS'' AND ANY
 * EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
 * PURPOSE ARE DISCLAIMED.  IN NO EVENT SHALL THE COPYRIGHT OWNER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
 * PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY
 * OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

#ifndef HarfBuzzNGFaceSkia_h
#define HarfBuzzNGFaceSkia_h

#include <SkScalar.h>
#include <SkPaint.h>

#include <hb.h>

namespace android {

static inline float
HBFixedToFloat (hb_position_t v)
{
    return scalbnf (v, -8);
}

static inline hb_position_t
HBFloatToFixed (float v)
{
    return scalbnf (v, +8);
}

static inline hb_position_t SkScalarToHBFixed(SkScalar value) {
    return HBFloatToFixed(SkScalarToFloat(value));
}

hb_blob_t* harfbuzzSkiaReferenceTable(hb_face_t* face, hb_tag_t tag, void* userData);

hb_font_t* createFont(hb_face_t* face, SkPaint* paint, float sizeX, float sizeY);

}  // namespace android

#endif
