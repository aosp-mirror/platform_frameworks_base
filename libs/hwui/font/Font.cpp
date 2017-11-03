/*
 * Copyright (C) 2012 The Android Open Source Project
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

#include <cutils/compiler.h>

#include <utils/JenkinsHash.h>
#include <utils/Trace.h>

#include <SkGlyph.h>
#include <SkGlyphCache.h>
#include <SkSurfaceProps.h>
#include <SkUtils.h>

#include "../Debug.h"
#include "../FontRenderer.h"
#include "../PixelBuffer.h"
#include "../Properties.h"
#include "Font.h"
#include "FontUtil.h"

namespace android {
namespace uirenderer {

///////////////////////////////////////////////////////////////////////////////
// Font
///////////////////////////////////////////////////////////////////////////////

Font::Font(FontRenderer* state, const Font::FontDescription& desc)
        : mState(state), mDescription(desc) {}

Font::FontDescription::FontDescription(const SkPaint* paint, const SkMatrix& rasterMatrix)
        : mLookupTransform(rasterMatrix) {
    mFontId = SkTypeface::UniqueID(paint->getTypeface());
    mFontSize = paint->getTextSize();
    mFlags = 0;
    if (paint->isFakeBoldText()) {
        mFlags |= Font::kFakeBold;
    }
    mItalicStyle = paint->getTextSkewX();
    mScaleX = paint->getTextScaleX();
    mStyle = paint->getStyle();
    mStrokeWidth = paint->getStrokeWidth();
    mAntiAliasing = paint->isAntiAlias();
    mHinting = paint->getHinting();
    if (!mLookupTransform.invert(&mInverseLookupTransform)) {
        ALOGW("Could not query the inverse lookup transform for this font");
    }
}

Font::~Font() {
    for (uint32_t i = 0; i < mCachedGlyphs.size(); i++) {
        delete mCachedGlyphs.valueAt(i);
    }
}

hash_t Font::FontDescription::hash() const {
    uint32_t hash = JenkinsHashMix(0, mFontId);
    hash = JenkinsHashMix(hash, android::hash_type(mFontSize));
    hash = JenkinsHashMix(hash, android::hash_type(mFlags));
    hash = JenkinsHashMix(hash, android::hash_type(mItalicStyle));
    hash = JenkinsHashMix(hash, android::hash_type(mScaleX));
    hash = JenkinsHashMix(hash, android::hash_type(mStyle));
    hash = JenkinsHashMix(hash, android::hash_type(mStrokeWidth));
    hash = JenkinsHashMix(hash, int(mAntiAliasing));
    hash = JenkinsHashMix(hash, android::hash_type(mHinting));
    hash = JenkinsHashMix(hash, android::hash_type(mLookupTransform[SkMatrix::kMScaleX]));
    hash = JenkinsHashMix(hash, android::hash_type(mLookupTransform[SkMatrix::kMScaleY]));
    return JenkinsHashWhiten(hash);
}

int Font::FontDescription::compare(const Font::FontDescription& lhs,
                                   const Font::FontDescription& rhs) {
    int deltaInt = int(lhs.mFontId) - int(rhs.mFontId);
    if (deltaInt != 0) return deltaInt;

    if (lhs.mFontSize < rhs.mFontSize) return -1;
    if (lhs.mFontSize > rhs.mFontSize) return +1;

    if (lhs.mItalicStyle < rhs.mItalicStyle) return -1;
    if (lhs.mItalicStyle > rhs.mItalicStyle) return +1;

    deltaInt = int(lhs.mFlags) - int(rhs.mFlags);
    if (deltaInt != 0) return deltaInt;

    if (lhs.mScaleX < rhs.mScaleX) return -1;
    if (lhs.mScaleX > rhs.mScaleX) return +1;

    deltaInt = int(lhs.mStyle) - int(rhs.mStyle);
    if (deltaInt != 0) return deltaInt;

    if (lhs.mStrokeWidth < rhs.mStrokeWidth) return -1;
    if (lhs.mStrokeWidth > rhs.mStrokeWidth) return +1;

    deltaInt = int(lhs.mAntiAliasing) - int(rhs.mAntiAliasing);
    if (deltaInt != 0) return deltaInt;

    deltaInt = int(lhs.mHinting) - int(rhs.mHinting);
    if (deltaInt != 0) return deltaInt;

    if (lhs.mLookupTransform[SkMatrix::kMScaleX] < rhs.mLookupTransform[SkMatrix::kMScaleX])
        return -1;
    if (lhs.mLookupTransform[SkMatrix::kMScaleX] > rhs.mLookupTransform[SkMatrix::kMScaleX])
        return +1;

    if (lhs.mLookupTransform[SkMatrix::kMScaleY] < rhs.mLookupTransform[SkMatrix::kMScaleY])
        return -1;
    if (lhs.mLookupTransform[SkMatrix::kMScaleY] > rhs.mLookupTransform[SkMatrix::kMScaleY])
        return +1;

    return 0;
}

void Font::invalidateTextureCache(CacheTexture* cacheTexture) {
    for (uint32_t i = 0; i < mCachedGlyphs.size(); i++) {
        CachedGlyphInfo* cachedGlyph = mCachedGlyphs.valueAt(i);
        if (!cacheTexture || cachedGlyph->mCacheTexture == cacheTexture) {
            cachedGlyph->mIsValid = false;
        }
    }
}

void Font::measureCachedGlyph(CachedGlyphInfo* glyph, int x, int y, uint8_t* bitmap,
                              uint32_t bitmapW, uint32_t bitmapH, Rect* bounds, const float* pos) {
    int width = (int)glyph->mBitmapWidth;
    int height = (int)glyph->mBitmapHeight;

    int nPenX = x + glyph->mBitmapLeft;
    int nPenY = y + glyph->mBitmapTop;

    if (bounds->bottom > nPenY) {
        bounds->bottom = nPenY;
    }
    if (bounds->left > nPenX) {
        bounds->left = nPenX;
    }
    if (bounds->right < nPenX + width) {
        bounds->right = nPenX + width;
    }
    if (bounds->top < nPenY + height) {
        bounds->top = nPenY + height;
    }
}

void Font::drawCachedGlyph(CachedGlyphInfo* glyph, int x, int y, uint8_t* bitmap, uint32_t bitmapW,
                           uint32_t bitmapH, Rect* bounds, const float* pos) {
    float width = (float)glyph->mBitmapWidth;
    float height = (float)glyph->mBitmapHeight;

    float nPenX = x + glyph->mBitmapLeft;
    float nPenY = y + glyph->mBitmapTop + height;

    float u1 = glyph->mBitmapMinU;
    float u2 = glyph->mBitmapMaxU;
    float v1 = glyph->mBitmapMinV;
    float v2 = glyph->mBitmapMaxV;

    mState->appendMeshQuad(nPenX, nPenY, u1, v2, nPenX + width, nPenY, u2, v2, nPenX + width,
                           nPenY - height, u2, v1, nPenX, nPenY - height, u1, v1,
                           glyph->mCacheTexture);
}

void Font::drawCachedGlyphTransformed(CachedGlyphInfo* glyph, int x, int y, uint8_t* bitmap,
                                      uint32_t bitmapW, uint32_t bitmapH, Rect* bounds,
                                      const float* pos) {
    float width = (float)glyph->mBitmapWidth;
    float height = (float)glyph->mBitmapHeight;

    SkPoint p[4];
    p[0].iset(glyph->mBitmapLeft, glyph->mBitmapTop + height);
    p[1].iset(glyph->mBitmapLeft + width, glyph->mBitmapTop + height);
    p[2].iset(glyph->mBitmapLeft + width, glyph->mBitmapTop);
    p[3].iset(glyph->mBitmapLeft, glyph->mBitmapTop);

    mDescription.mInverseLookupTransform.mapPoints(p, 4);

    p[0].offset(x, y);
    p[1].offset(x, y);
    p[2].offset(x, y);
    p[3].offset(x, y);

    float u1 = glyph->mBitmapMinU;
    float u2 = glyph->mBitmapMaxU;
    float v1 = glyph->mBitmapMinV;
    float v2 = glyph->mBitmapMaxV;

    mState->appendRotatedMeshQuad(p[0].x(), p[0].y(), u1, v2, p[1].x(), p[1].y(), u2, v2, p[2].x(),
                                  p[2].y(), u2, v1, p[3].x(), p[3].y(), u1, v1,
                                  glyph->mCacheTexture);
}

void Font::drawCachedGlyphBitmap(CachedGlyphInfo* glyph, int x, int y, uint8_t* bitmap,
                                 uint32_t bitmapWidth, uint32_t bitmapHeight, Rect* bounds,
                                 const float* pos) {
    int dstX = x + glyph->mBitmapLeft;
    int dstY = y + glyph->mBitmapTop;

    CacheTexture* cacheTexture = glyph->mCacheTexture;
    PixelBuffer* pixelBuffer = cacheTexture->getPixelBuffer();

    uint32_t formatSize = PixelBuffer::formatSize(pixelBuffer->getFormat());
    uint32_t alpha_channel_offset = PixelBuffer::formatAlphaOffset(pixelBuffer->getFormat());
    uint32_t cacheWidth = cacheTexture->getWidth();
    uint32_t srcStride = formatSize * cacheWidth;
    uint32_t startY = glyph->mStartY * srcStride;
    uint32_t endY = startY + (glyph->mBitmapHeight * srcStride);

    const uint8_t* cacheBuffer = pixelBuffer->map();

    for (uint32_t cacheY = startY, bitmapY = dstY * bitmapWidth; cacheY < endY;
         cacheY += srcStride, bitmapY += bitmapWidth) {
        for (uint32_t i = 0; i < glyph->mBitmapWidth; ++i) {
            uint8_t* dst = &(bitmap[bitmapY + dstX + i]);
            const uint8_t& src =
                    cacheBuffer[cacheY + (glyph->mStartX + i) * formatSize + alpha_channel_offset];
            // Add alpha values to a max of 255, full opacity. This is done to handle
            // fonts/strings where glyphs overlap.
            *dst = std::min(*dst + src, 255);
        }
    }
}

void Font::drawCachedGlyph(CachedGlyphInfo* glyph, float x, float hOffset, float vOffset,
                           SkPathMeasure& measure, SkPoint* position, SkVector* tangent) {
    const float halfWidth = glyph->mBitmapWidth * 0.5f;
    const float height = glyph->mBitmapHeight;

    vOffset += glyph->mBitmapTop + height;

    SkPoint destination[4];
    bool ok = measure.getPosTan(x + hOffset + glyph->mBitmapLeft + halfWidth, position, tangent);
    if (!ok) {
        ALOGW("The path for drawTextOnPath is empty or null");
    }

    // Move along the tangent and offset by the normal
    destination[0].set(-tangent->fX * halfWidth - tangent->fY * vOffset,
                       -tangent->fY * halfWidth + tangent->fX * vOffset);
    destination[1].set(tangent->fX * halfWidth - tangent->fY * vOffset,
                       tangent->fY * halfWidth + tangent->fX * vOffset);
    destination[2].set(destination[1].fX + tangent->fY * height,
                       destination[1].fY - tangent->fX * height);
    destination[3].set(destination[0].fX + tangent->fY * height,
                       destination[0].fY - tangent->fX * height);

    const float u1 = glyph->mBitmapMinU;
    const float u2 = glyph->mBitmapMaxU;
    const float v1 = glyph->mBitmapMinV;
    const float v2 = glyph->mBitmapMaxV;

    mState->appendRotatedMeshQuad(
            position->x() + destination[0].x(), position->y() + destination[0].y(), u1, v2,
            position->x() + destination[1].x(), position->y() + destination[1].y(), u2, v2,
            position->x() + destination[2].x(), position->y() + destination[2].y(), u2, v1,
            position->x() + destination[3].x(), position->y() + destination[3].y(), u1, v1,
            glyph->mCacheTexture);
}

CachedGlyphInfo* Font::getCachedGlyph(const SkPaint* paint, glyph_t textUnit, bool precaching) {
    CachedGlyphInfo* cachedGlyph = mCachedGlyphs.valueFor(textUnit);
    if (cachedGlyph) {
        // Is the glyph still in texture cache?
        if (!cachedGlyph->mIsValid) {
            SkSurfaceProps surfaceProps(0, kUnknown_SkPixelGeometry);
            SkAutoGlyphCacheNoGamma autoCache(*paint, &surfaceProps,
                                              &mDescription.mLookupTransform);
            const SkGlyph& skiaGlyph = GET_METRICS(autoCache.getCache(), textUnit);
            updateGlyphCache(paint, skiaGlyph, autoCache.getCache(), cachedGlyph, precaching);
        }
    } else {
        cachedGlyph = cacheGlyph(paint, textUnit, precaching);
    }

    return cachedGlyph;
}

void Font::render(const SkPaint* paint, const glyph_t* glyphs, int numGlyphs, int x, int y,
                  const float* positions) {
    render(paint, glyphs, numGlyphs, x, y, FRAMEBUFFER, nullptr, 0, 0, nullptr, positions);
}

void Font::render(const SkPaint* paint, const glyph_t* glyphs, int numGlyphs, const SkPath* path,
                  float hOffset, float vOffset) {
    if (numGlyphs == 0 || glyphs == nullptr) {
        return;
    }

    int glyphsCount = 0;
    int prevRsbDelta = 0;

    float penX = 0.0f;

    SkPoint position;
    SkVector tangent;

    SkPathMeasure measure(*path, false);
    float pathLength = SkScalarToFloat(measure.getLength());

    if (paint->getTextAlign() != SkPaint::kLeft_Align) {
        float textWidth = SkScalarToFloat(paint->measureText(glyphs, numGlyphs * 2));
        float pathOffset = pathLength;
        if (paint->getTextAlign() == SkPaint::kCenter_Align) {
            textWidth *= 0.5f;
            pathOffset *= 0.5f;
        }
        penX += pathOffset - textWidth;
    }

    while (glyphsCount < numGlyphs && penX < pathLength) {
        glyph_t glyph = *(glyphs++);

        if (IS_END_OF_STRING(glyph)) {
            break;
        }

        CachedGlyphInfo* cachedGlyph = getCachedGlyph(paint, glyph);
        penX += AUTO_KERN(prevRsbDelta, cachedGlyph->mLsbDelta);
        prevRsbDelta = cachedGlyph->mRsbDelta;

        if (cachedGlyph->mIsValid && cachedGlyph->mCacheTexture) {
            drawCachedGlyph(cachedGlyph, penX, hOffset, vOffset, measure, &position, &tangent);
        }

        penX += cachedGlyph->mAdvanceX;

        glyphsCount++;
    }
}

void Font::measure(const SkPaint* paint, const glyph_t* glyphs, int numGlyphs, Rect* bounds,
                   const float* positions) {
    if (bounds == nullptr) {
        ALOGE("No return rectangle provided to measure text");
        return;
    }
    bounds->set(1e6, -1e6, -1e6, 1e6);
    render(paint, glyphs, numGlyphs, 0, 0, MEASURE, nullptr, 0, 0, bounds, positions);
}

void Font::precache(const SkPaint* paint, const glyph_t* glyphs, int numGlyphs) {
    if (numGlyphs == 0 || glyphs == nullptr) {
        return;
    }

    int glyphsCount = 0;
    while (glyphsCount < numGlyphs) {
        glyph_t glyph = *(glyphs++);

        // Reached the end of the string
        if (IS_END_OF_STRING(glyph)) {
            break;
        }

        getCachedGlyph(paint, glyph, true);
        glyphsCount++;
    }
}

void Font::render(const SkPaint* paint, const glyph_t* glyphs, int numGlyphs, int x, int y,
                  RenderMode mode, uint8_t* bitmap, uint32_t bitmapW, uint32_t bitmapH,
                  Rect* bounds, const float* positions) {
    if (numGlyphs == 0 || glyphs == nullptr) {
        return;
    }

    static RenderGlyph gRenderGlyph[] = {&android::uirenderer::Font::drawCachedGlyph,
                                         &android::uirenderer::Font::drawCachedGlyphTransformed,
                                         &android::uirenderer::Font::drawCachedGlyphBitmap,
                                         &android::uirenderer::Font::drawCachedGlyphBitmap,
                                         &android::uirenderer::Font::measureCachedGlyph,
                                         &android::uirenderer::Font::measureCachedGlyph};
    RenderGlyph render = gRenderGlyph[(mode << 1) + !mIdentityTransform];

    int glyphsCount = 0;

    while (glyphsCount < numGlyphs) {
        glyph_t glyph = *(glyphs++);

        // Reached the end of the string
        if (IS_END_OF_STRING(glyph)) {
            break;
        }

        CachedGlyphInfo* cachedGlyph = getCachedGlyph(paint, glyph);

        // If it's still not valid, we couldn't cache it, so we shouldn't
        // draw garbage; also skip empty glyphs (spaces)
        if (cachedGlyph->mIsValid && cachedGlyph->mCacheTexture) {
            int penX = x + (int)roundf(positions[(glyphsCount << 1)]);
            int penY = y + (int)roundf(positions[(glyphsCount << 1) + 1]);
#ifdef BUGREPORT_FONT_CACHE_USAGE
            mState->historyTracker().glyphRendered(cachedGlyph, penX, penY);
#endif
            (*this.*render)(cachedGlyph, penX, penY, bitmap, bitmapW, bitmapH, bounds, positions);
        } else {
#ifdef BUGREPORT_FONT_CACHE_USAGE
            mState->historyTracker().glyphRendered(cachedGlyph, -1, -1);
#endif
        }

        glyphsCount++;
    }
}

void Font::updateGlyphCache(const SkPaint* paint, const SkGlyph& skiaGlyph,
                            SkGlyphCache* skiaGlyphCache, CachedGlyphInfo* glyph, bool precaching) {
    glyph->mAdvanceX = skiaGlyph.fAdvanceX;
    glyph->mAdvanceY = skiaGlyph.fAdvanceY;
    glyph->mBitmapLeft = skiaGlyph.fLeft;
    glyph->mBitmapTop = skiaGlyph.fTop;
    glyph->mLsbDelta = skiaGlyph.fLsbDelta;
    glyph->mRsbDelta = skiaGlyph.fRsbDelta;

    uint32_t startX = 0;
    uint32_t startY = 0;

    // Get the bitmap for the glyph
    if (!skiaGlyph.fImage) {
        skiaGlyphCache->findImage(skiaGlyph);
    }
    mState->cacheBitmap(skiaGlyph, glyph, &startX, &startY, precaching);

    if (!glyph->mIsValid) {
        return;
    }

    uint32_t endX = startX + skiaGlyph.fWidth;
    uint32_t endY = startY + skiaGlyph.fHeight;

    glyph->mStartX = startX;
    glyph->mStartY = startY;
    glyph->mBitmapWidth = skiaGlyph.fWidth;
    glyph->mBitmapHeight = skiaGlyph.fHeight;

    bool empty = skiaGlyph.fWidth == 0 || skiaGlyph.fHeight == 0;
    if (!empty) {
        uint32_t cacheWidth = glyph->mCacheTexture->getWidth();
        uint32_t cacheHeight = glyph->mCacheTexture->getHeight();

        glyph->mBitmapMinU = startX / (float)cacheWidth;
        glyph->mBitmapMinV = startY / (float)cacheHeight;
        glyph->mBitmapMaxU = endX / (float)cacheWidth;
        glyph->mBitmapMaxV = endY / (float)cacheHeight;

        mState->setTextureDirty();
    }
}

CachedGlyphInfo* Font::cacheGlyph(const SkPaint* paint, glyph_t glyph, bool precaching) {
    CachedGlyphInfo* newGlyph = new CachedGlyphInfo();
    mCachedGlyphs.add(glyph, newGlyph);

    SkSurfaceProps surfaceProps(0, kUnknown_SkPixelGeometry);
    SkAutoGlyphCacheNoGamma autoCache(*paint, &surfaceProps, &mDescription.mLookupTransform);
    const SkGlyph& skiaGlyph = GET_METRICS(autoCache.getCache(), glyph);
    newGlyph->mIsValid = false;
    newGlyph->mGlyphIndex = skiaGlyph.fID;

    updateGlyphCache(paint, skiaGlyph, autoCache.getCache(), newGlyph, precaching);

    return newGlyph;
}

Font* Font::create(FontRenderer* state, const SkPaint* paint, const SkMatrix& matrix) {
    FontDescription description(paint, matrix);
    Font* font = state->mActiveFonts.get(description);

    if (!font) {
        font = new Font(state, description);
        state->mActiveFonts.put(description, font);
    }
    font->mIdentityTransform = matrix.isIdentity();

    return font;
}

};  // namespace uirenderer
};  // namespace android
