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

#ifndef HarfbuzzSkia_h
#define HarfbuzzSkia_h

#include "SkScalar.h"
#include "SkTypeface.h"
#include "SkPaint.h"

extern "C" {
#include "harfbuzz-shaper.h"
}

namespace android {

static inline float HBFixedToFloat(HB_Fixed v) {
    // Harfbuzz uses 26.6 fixed point values for pixel offsets
    return v * (1.0f / 64);
}

static inline HB_Fixed SkScalarToHBFixed(SkScalar value) {
    // HB_Fixed is a 26.6 fixed point format.
    return SkScalarToFloat(value) * 64.0f;
}

HB_Error harfbuzzSkiaGetTable(void* voidface, const HB_Tag, HB_Byte* buffer, HB_UInt* len);
extern const HB_FontClass harfbuzzSkiaClass;

}  // namespace android

#endif
