/*
 * Copyright 2006 The Android Open Source Project
 *
 * Use of this source code is governed by a BSD-style license that can be
 * found in the LICENSE file.
 */

#include "AvoidXfermode.h"
#include "SkColorPriv.h"
#include "SkReadBuffer.h"
#include "SkWriteBuffer.h"
#include "SkString.h"

AvoidXfermode::AvoidXfermode(SkColor opColor, U8CPU tolerance, Mode mode) {
    if (tolerance > 255) {
        tolerance = 255;
    }
    fTolerance = SkToU8(tolerance);
    fOpColor = opColor;
    fDistMul = (256 << 14) / (tolerance + 1);
    fMode = mode;
}

SkFlattenable* AvoidXfermode::CreateProc(SkReadBuffer& buffer) {
    const SkColor color = buffer.readColor();
    const unsigned tolerance = buffer.readUInt();
    const unsigned mode = buffer.readUInt();
    return Create(color, tolerance, (Mode)mode);
}

void AvoidXfermode::flatten(SkWriteBuffer& buffer) const {
    buffer.writeColor(fOpColor);
    buffer.writeUInt(fTolerance);
    buffer.writeUInt(fMode);
}

// returns 0..31
static unsigned color_dist16(uint16_t c, unsigned r, unsigned g, unsigned b) {
    SkASSERT(r <= SK_R16_MASK);
    SkASSERT(g <= SK_G16_MASK);
    SkASSERT(b <= SK_B16_MASK);

    unsigned dr = SkAbs32(SkGetPackedR16(c) - r);
    unsigned dg = SkAbs32(SkGetPackedG16(c) - g) >> (SK_G16_BITS - SK_R16_BITS);
    unsigned db = SkAbs32(SkGetPackedB16(c) - b);

    return SkMax32(dr, SkMax32(dg, db));
}

// returns 0..255
static unsigned color_dist32(SkPMColor c, U8CPU r, U8CPU g, U8CPU b) {
    SkASSERT(r <= 0xFF);
    SkASSERT(g <= 0xFF);
    SkASSERT(b <= 0xFF);

    unsigned dr = SkAbs32(SkGetPackedR32(c) - r);
    unsigned dg = SkAbs32(SkGetPackedG32(c) - g);
    unsigned db = SkAbs32(SkGetPackedB32(c) - b);

    return SkMax32(dr, SkMax32(dg, db));
}

static int scale_dist_14(int dist, uint32_t mul, uint32_t sub) {
    int tmp = dist * mul - sub;
    int result = (tmp + (1 << 13)) >> 14;

    return result;
}

static inline unsigned Accurate255To256(unsigned x) {
    return x + (x >> 7);
}

void AvoidXfermode::xfer32(SkPMColor dst[], const SkPMColor src[], int count,
                             const SkAlpha aa[]) const {
    unsigned    opR = SkColorGetR(fOpColor);
    unsigned    opG = SkColorGetG(fOpColor);
    unsigned    opB = SkColorGetB(fOpColor);
    uint32_t    mul = fDistMul;
    uint32_t    sub = (fDistMul - (1 << 14)) << 8;

    int MAX, mask;

    if (kTargetColor_Mode == fMode) {
        mask = -1;
        MAX = 255;
    } else {
        mask = 0;
        MAX = 0;
    }

    for (int i = 0; i < count; i++) {
        int d = color_dist32(dst[i], opR, opG, opB);
        // now reverse d if we need to
        d = MAX + (d ^ mask) - mask;
        SkASSERT((unsigned)d <= 255);
        d = Accurate255To256(d);

        d = scale_dist_14(d, mul, sub);
        SkASSERT(d <= 256);

        if (d > 0) {
            if (aa) {
                d = SkAlphaMul(d, Accurate255To256(*aa++));
                if (0 == d) {
                    continue;
                }
            }
            dst[i] = SkFourByteInterp256(src[i], dst[i], d);
        }
    }
}

static inline U16CPU SkBlend3216(SkPMColor src, U16CPU dst, unsigned scale) {
    SkASSERT(scale <= 32);
    scale <<= 3;

    return SkPackRGB16( SkAlphaBlend(SkPacked32ToR16(src), SkGetPackedR16(dst), scale),
                        SkAlphaBlend(SkPacked32ToG16(src), SkGetPackedG16(dst), scale),
                        SkAlphaBlend(SkPacked32ToB16(src), SkGetPackedB16(dst), scale));
}

void AvoidXfermode::xfer16(uint16_t dst[], const SkPMColor src[], int count,
                             const SkAlpha aa[]) const {
    unsigned    opR = SkColorGetR(fOpColor) >> (8 - SK_R16_BITS);
    unsigned    opG = SkColorGetG(fOpColor) >> (8 - SK_G16_BITS);
    unsigned    opB = SkColorGetB(fOpColor) >> (8 - SK_R16_BITS);
    uint32_t    mul = fDistMul;
    uint32_t    sub = (fDistMul - (1 << 14)) << SK_R16_BITS;

    int MAX, mask;

    if (kTargetColor_Mode == fMode) {
        mask = -1;
        MAX = 31;
    } else {
        mask = 0;
        MAX = 0;
    }

    for (int i = 0; i < count; i++) {
        int d = color_dist16(dst[i], opR, opG, opB);
        // now reverse d if we need to
        d = MAX + (d ^ mask) - mask;
        SkASSERT((unsigned)d <= 31);
        // convert from 0..31 to 0..32
        d += d >> 4;
        d = scale_dist_14(d, mul, sub);
        SkASSERT(d <= 32);

        if (d > 0) {
            if (aa) {
                d = SkAlphaMul(d, Accurate255To256(*aa++));
                if (0 == d) {
                    continue;
                }
            }
            dst[i] = SkBlend3216(src[i], dst[i], d);
        }
    }
}

void AvoidXfermode::xferA8(SkAlpha dst[], const SkPMColor src[], int count,
        const SkAlpha aa[]) const {
}

#ifndef SK_IGNORE_TO_STRING
void AvoidXfermode::toString(SkString* str) const {
    str->append("AvoidXfermode: opColor: ");
    str->appendHex(fOpColor);
    str->appendf("distMul: %d ", fDistMul);

    static const char* gModeStrings[] = { "Avoid", "Target" };

    str->appendf("mode: %s", gModeStrings[fMode]);
}
#endif
