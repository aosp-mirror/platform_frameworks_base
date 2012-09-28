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

#define LOG_TAG "OpenGLRenderer"

#include <cutils/compiler.h>

#include <SkUtils.h>

#include "Debug.h"
#include "FontUtil.h"
#include "Font.h"
#include "FontRenderer.h"
#include "Properties.h"

namespace android {
namespace uirenderer {

///////////////////////////////////////////////////////////////////////////////
// Font
///////////////////////////////////////////////////////////////////////////////

Font::Font(FontRenderer* state, uint32_t fontId, float fontSize,
        int flags, uint32_t italicStyle, uint32_t scaleX,
        SkPaint::Style style, uint32_t strokeWidth) :
        mState(state), mFontId(fontId), mFontSize(fontSize),
        mFlags(flags), mItalicStyle(italicStyle), mScaleX(scaleX),
        mStyle(style), mStrokeWidth(mStrokeWidth) {
}


Font::~Font() {
    mState->removeFont(this);

    for (uint32_t i = 0; i < mCachedGlyphs.size(); i++) {
        delete mCachedGlyphs.valueAt(i);
    }
}

void Font::invalidateTextureCache(CacheTexture* cacheTexture) {
    for (uint32_t i = 0; i < mCachedGlyphs.size(); i++) {
        CachedGlyphInfo* cachedGlyph = mCachedGlyphs.valueAt(i);
        if (!cacheTexture || cachedGlyph->mCacheTexture == cacheTexture) {
            cachedGlyph->mIsValid = false;
        }
    }
}

void Font::measureCachedGlyph(CachedGlyphInfo *glyph, int x, int y,
        uint8_t* bitmap, uint32_t bitmapW, uint32_t bitmapH, Rect* bounds, const float* pos) {
    int nPenX = x + glyph->mBitmapLeft;
    int nPenY = y + glyph->mBitmapTop;

    int width = (int) glyph->mBitmapWidth;
    int height = (int) glyph->mBitmapHeight;

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

void Font::drawCachedGlyph(CachedGlyphInfo* glyph, int x, int y,
        uint8_t* bitmap, uint32_t bitmapW, uint32_t bitmapH, Rect* bounds, const float* pos) {
    int nPenX = x + glyph->mBitmapLeft;
    int nPenY = y + glyph->mBitmapTop + glyph->mBitmapHeight;

    float u1 = glyph->mBitmapMinU;
    float u2 = glyph->mBitmapMaxU;
    float v1 = glyph->mBitmapMinV;
    float v2 = glyph->mBitmapMaxV;

    int width = (int) glyph->mBitmapWidth;
    int height = (int) glyph->mBitmapHeight;

    mState->appendMeshQuad(nPenX, nPenY, u1, v2,
            nPenX + width, nPenY, u2, v2,
            nPenX + width, nPenY - height, u2, v1,
            nPenX, nPenY - height, u1, v1, glyph->mCacheTexture);
}

void Font::drawCachedGlyphBitmap(CachedGlyphInfo* glyph, int x, int y,
        uint8_t* bitmap, uint32_t bitmapW, uint32_t bitmapH, Rect* bounds, const float* pos) {
    int nPenX = x + glyph->mBitmapLeft;
    int nPenY = y + glyph->mBitmapTop;

    uint32_t endX = glyph->mStartX + glyph->mBitmapWidth;
    uint32_t endY = glyph->mStartY + glyph->mBitmapHeight;

    CacheTexture* cacheTexture = glyph->mCacheTexture;
    uint32_t cacheWidth = cacheTexture->getWidth();
    const uint8_t* cacheBuffer = cacheTexture->getTexture();

    uint32_t cacheX = 0, cacheY = 0;
    int32_t bX = 0, bY = 0;
    for (cacheX = glyph->mStartX, bX = nPenX; cacheX < endX; cacheX++, bX++) {
        for (cacheY = glyph->mStartY, bY = nPenY; cacheY < endY; cacheY++, bY++) {
#if DEBUG_FONT_RENDERER
            if (bX < 0 || bY < 0 || bX >= (int32_t) bitmapW || bY >= (int32_t) bitmapH) {
                ALOGE("Skipping invalid index");
                continue;
            }
#endif
            uint8_t tempCol = cacheBuffer[cacheY * cacheWidth + cacheX];
            bitmap[bY * bitmapW + bX] = tempCol;
        }
    }
}

void Font::drawCachedGlyph(CachedGlyphInfo* glyph, float x, float hOffset, float vOffset,
        SkPathMeasure& measure, SkPoint* position, SkVector* tangent) {
    const float halfWidth = glyph->mBitmapWidth * 0.5f;
    const float height = glyph->mBitmapHeight;

    vOffset += glyph->mBitmapTop + height;

    SkPoint destination[4];
    measure.getPosTan(x + hOffset +  glyph->mBitmapLeft + halfWidth, position, tangent);

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
            position->fX + destination[0].fX,
            position->fY + destination[0].fY, u1, v2,
            position->fX + destination[1].fX,
            position->fY + destination[1].fY, u2, v2,
            position->fX + destination[2].fX,
            position->fY + destination[2].fY, u2, v1,
            position->fX + destination[3].fX,
            position->fY + destination[3].fY, u1, v1,
            glyph->mCacheTexture);
}

CachedGlyphInfo* Font::getCachedGlyph(SkPaint* paint, glyph_t textUnit, bool precaching) {
    CachedGlyphInfo* cachedGlyph = NULL;
    ssize_t index = mCachedGlyphs.indexOfKey(textUnit);
    if (index >= 0) {
        cachedGlyph = mCachedGlyphs.valueAt(index);
    } else {
        cachedGlyph = cacheGlyph(paint, textUnit, precaching);
    }

    // Is the glyph still in texture cache?
    if (!cachedGlyph->mIsValid) {
        const SkGlyph& skiaGlyph = GET_METRICS(paint, textUnit);
        updateGlyphCache(paint, skiaGlyph, cachedGlyph, precaching);
    }

    return cachedGlyph;
}

void Font::render(SkPaint* paint, const char* text, uint32_t start, uint32_t len,
        int numGlyphs, int x, int y, uint8_t *bitmap, uint32_t bitmapW, uint32_t bitmapH) {
    if (bitmap != NULL && bitmapW > 0 && bitmapH > 0) {
        render(paint, text, start, len, numGlyphs, x, y, BITMAP, bitmap,
                bitmapW, bitmapH, NULL, NULL);
    } else {
        render(paint, text, start, len, numGlyphs, x, y, FRAMEBUFFER, NULL,
                0, 0, NULL, NULL);
    }
}

void Font::render(SkPaint* paint, const char *text, uint32_t start, uint32_t len,
            int numGlyphs, int x, int y, const float* positions) {
    render(paint, text, start, len, numGlyphs, x, y, FRAMEBUFFER, NULL,
            0, 0, NULL, positions);
}

void Font::render(SkPaint* paint, const char *text, uint32_t start, uint32_t len,
        int numGlyphs, SkPath* path, float hOffset, float vOffset) {
    if (numGlyphs == 0 || text == NULL || len == 0) {
        return;
    }

    text += start;

    int glyphsCount = 0;
    SkFixed prevRsbDelta = 0;

    float penX = 0.0f;

    SkPoint position;
    SkVector tangent;

    SkPathMeasure measure(*path, false);
    float pathLength = SkScalarToFloat(measure.getLength());

    if (paint->getTextAlign() != SkPaint::kLeft_Align) {
        float textWidth = SkScalarToFloat(paint->measureText(text, len));
        float pathOffset = pathLength;
        if (paint->getTextAlign() == SkPaint::kCenter_Align) {
            textWidth *= 0.5f;
            pathOffset *= 0.5f;
        }
        penX += pathOffset - textWidth;
    }

    while (glyphsCount < numGlyphs && penX < pathLength) {
        glyph_t glyph = GET_GLYPH(text);

        if (IS_END_OF_STRING(glyph)) {
            break;
        }

        CachedGlyphInfo* cachedGlyph = getCachedGlyph(paint, glyph);
        penX += SkFixedToFloat(AUTO_KERN(prevRsbDelta, cachedGlyph->mLsbDelta));
        prevRsbDelta = cachedGlyph->mRsbDelta;

        if (cachedGlyph->mIsValid) {
            drawCachedGlyph(cachedGlyph, penX, hOffset, vOffset, measure, &position, &tangent);
        }

        penX += SkFixedToFloat(cachedGlyph->mAdvanceX);

        glyphsCount++;
    }
}

void Font::measure(SkPaint* paint, const char* text, uint32_t start, uint32_t len,
        int numGlyphs, Rect *bounds, const float* positions) {
    if (bounds == NULL) {
        ALOGE("No return rectangle provided to measure text");
        return;
    }
    bounds->set(1e6, -1e6, -1e6, 1e6);
    render(paint, text, start, len, numGlyphs, 0, 0, MEASURE, NULL, 0, 0, bounds, positions);
}

void Font::precache(SkPaint* paint, const char* text, int numGlyphs) {

    if (numGlyphs == 0 || text == NULL) {
        return;
    }
    int glyphsCount = 0;

    while (glyphsCount < numGlyphs) {
        glyph_t glyph = GET_GLYPH(text);

        // Reached the end of the string
        if (IS_END_OF_STRING(glyph)) {
            break;
        }

        CachedGlyphInfo* cachedGlyph = getCachedGlyph(paint, glyph, true);

        glyphsCount++;
    }
}

void Font::render(SkPaint* paint, const char* text, uint32_t start, uint32_t len,
        int numGlyphs, int x, int y, RenderMode mode, uint8_t *bitmap,
        uint32_t bitmapW, uint32_t bitmapH, Rect* bounds, const float* positions) {
    if (numGlyphs == 0 || text == NULL || len == 0) {
        return;
    }

    static RenderGlyph gRenderGlyph[] = {
            &android::uirenderer::Font::drawCachedGlyph,
            &android::uirenderer::Font::drawCachedGlyphBitmap,
            &android::uirenderer::Font::measureCachedGlyph
    };
    RenderGlyph render = gRenderGlyph[mode];

    text += start;
    int glyphsCount = 0;

    if (CC_LIKELY(positions == NULL)) {
        SkFixed prevRsbDelta = 0;

        float penX = x + 0.5f;
        int penY = y;

        while (glyphsCount < numGlyphs) {
            glyph_t glyph = GET_GLYPH(text);

            // Reached the end of the string
            if (IS_END_OF_STRING(glyph)) {
                break;
            }

            CachedGlyphInfo* cachedGlyph = getCachedGlyph(paint, glyph);
            penX += SkFixedToFloat(AUTO_KERN(prevRsbDelta, cachedGlyph->mLsbDelta));
            prevRsbDelta = cachedGlyph->mRsbDelta;

            // If it's still not valid, we couldn't cache it, so we shouldn't draw garbage
            if (cachedGlyph->mIsValid) {
                (*this.*render)(cachedGlyph, (int) floorf(penX), penY,
                        bitmap, bitmapW, bitmapH, bounds, positions);
            }

            penX += SkFixedToFloat(cachedGlyph->mAdvanceX);

            glyphsCount++;
        }
    } else {
        const SkPaint::Align align = paint->getTextAlign();

        // This is for renderPosText()
        while (glyphsCount < numGlyphs) {
            glyph_t glyph = GET_GLYPH(text);

            // Reached the end of the string
            if (IS_END_OF_STRING(glyph)) {
                break;
            }

            CachedGlyphInfo* cachedGlyph = getCachedGlyph(paint, glyph);

            // If it's still not valid, we couldn't cache it, so we shouldn't draw garbage
            if (cachedGlyph->mIsValid) {
                int penX = x + positions[(glyphsCount << 1)];
                int penY = y + positions[(glyphsCount << 1) + 1];

                switch (align) {
                    case SkPaint::kRight_Align:
                        penX -= SkFixedToFloat(cachedGlyph->mAdvanceX);
                        penY -= SkFixedToFloat(cachedGlyph->mAdvanceY);
                        break;
                    case SkPaint::kCenter_Align:
                        penX -= SkFixedToFloat(cachedGlyph->mAdvanceX >> 1);
                        penY -= SkFixedToFloat(cachedGlyph->mAdvanceY >> 1);
                    default:
                        break;
                }

                (*this.*render)(cachedGlyph, penX, penY,
                        bitmap, bitmapW, bitmapH, bounds, positions);
            }

            glyphsCount++;
        }
    }
}

void Font::updateGlyphCache(SkPaint* paint, const SkGlyph& skiaGlyph, CachedGlyphInfo* glyph,
        bool precaching) {
    glyph->mAdvanceX = skiaGlyph.fAdvanceX;
    glyph->mAdvanceY = skiaGlyph.fAdvanceY;
    glyph->mBitmapLeft = skiaGlyph.fLeft;
    glyph->mBitmapTop = skiaGlyph.fTop;
    glyph->mLsbDelta = skiaGlyph.fLsbDelta;
    glyph->mRsbDelta = skiaGlyph.fRsbDelta;

    uint32_t startX = 0;
    uint32_t startY = 0;

    // Get the bitmap for the glyph
    paint->findImage(skiaGlyph);
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

    uint32_t cacheWidth = glyph->mCacheTexture->getWidth();
    uint32_t cacheHeight = glyph->mCacheTexture->getHeight();

    glyph->mBitmapMinU = startX / (float) cacheWidth;
    glyph->mBitmapMinV = startY / (float) cacheHeight;
    glyph->mBitmapMaxU = endX / (float) cacheWidth;
    glyph->mBitmapMaxV = endY / (float) cacheHeight;

    mState->setTextureDirty();
}

CachedGlyphInfo* Font::cacheGlyph(SkPaint* paint, glyph_t glyph, bool precaching) {
    CachedGlyphInfo* newGlyph = new CachedGlyphInfo();
    mCachedGlyphs.add(glyph, newGlyph);

    const SkGlyph& skiaGlyph = GET_METRICS(paint, glyph);
    newGlyph->mGlyphIndex = skiaGlyph.fID;
    newGlyph->mIsValid = false;

    updateGlyphCache(paint, skiaGlyph, newGlyph, precaching);

    return newGlyph;
}

Font* Font::create(FontRenderer* state, uint32_t fontId, float fontSize,
        int flags, uint32_t italicStyle, uint32_t scaleX,
        SkPaint::Style style, uint32_t strokeWidth) {
    Vector<Font*> &activeFonts = state->mActiveFonts;

    for (uint32_t i = 0; i < activeFonts.size(); i++) {
        Font* font = activeFonts[i];
        if (font->mFontId == fontId && font->mFontSize == fontSize &&
                font->mFlags == flags && font->mItalicStyle == italicStyle &&
                font->mScaleX == scaleX && font->mStyle == style &&
                (style == SkPaint::kFill_Style || font->mStrokeWidth == strokeWidth)) {
            return font;
        }
    }

    Font* newFont = new Font(state, fontId, fontSize, flags, italicStyle,
            scaleX, style, strokeWidth);
    activeFonts.push(newFont);
    return newFont;
}

}; // namespace uirenderer
}; // namespace android
