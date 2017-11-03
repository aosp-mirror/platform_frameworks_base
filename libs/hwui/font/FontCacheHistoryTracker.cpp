/*
 * Copyright (C) 2016 The Android Open Source Project
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

#include "FontCacheHistoryTracker.h"

#include "CacheTexture.h"
#include "CachedGlyphInfo.h"

namespace android {
namespace uirenderer {

void FontCacheHistoryTracker::dumpCachedGlyph(String8& log, const CachedGlyph& glyph) {
    log.appendFormat("glyph (texture %p, position: (%d, %d), size: %dx%d, gen: %d)", glyph.texture,
                     glyph.startX, glyph.startY, glyph.bitmapW, glyph.bitmapH, glyph.generation);
}

void FontCacheHistoryTracker::dumpRenderEntry(String8& log, const RenderEntry& entry) {
    if (entry.penX == -1 && entry.penY == -1) {
        log.appendFormat("      glyph skipped in gen: %d\n", entry.glyph.generation);
    } else {
        log.appendFormat("      rendered ");
        dumpCachedGlyph(log, entry.glyph);
        log.appendFormat(" at (%d, %d)\n", entry.penX, entry.penY);
    }
}

void FontCacheHistoryTracker::dumpUploadEntry(String8& log, const CachedGlyph& glyph) {
    if (glyph.bitmapW == 0 && glyph.bitmapH == 0) {
        log.appendFormat("      cleared cachetexture %p in gen %d\n", glyph.texture,
                         glyph.generation);
    } else {
        log.appendFormat("      uploaded ");
        dumpCachedGlyph(log, glyph);
        log.appendFormat("\n");
    }
}

void FontCacheHistoryTracker::dump(String8& log) const {
    log.appendFormat("FontCacheHistory: \n");
    log.appendFormat("  Upload history: \n");
    for (size_t i = 0; i < mUploadHistory.size(); i++) {
        dumpUploadEntry(log, mUploadHistory[i]);
    }
    log.appendFormat("  Render history: \n");
    for (size_t i = 0; i < mRenderHistory.size(); i++) {
        dumpRenderEntry(log, mRenderHistory[i]);
    }
}

void FontCacheHistoryTracker::glyphRendered(CachedGlyphInfo* glyphInfo, int penX, int penY) {
    RenderEntry& entry = mRenderHistory.next();
    entry.glyph.generation = generation;
    entry.glyph.texture = glyphInfo->mCacheTexture;
    entry.glyph.startX = glyphInfo->mStartX;
    entry.glyph.startY = glyphInfo->mStartY;
    entry.glyph.bitmapW = glyphInfo->mBitmapWidth;
    entry.glyph.bitmapH = glyphInfo->mBitmapHeight;
    entry.penX = penX;
    entry.penY = penY;
}

void FontCacheHistoryTracker::glyphUploaded(CacheTexture* texture, uint32_t x, uint32_t y,
                                            uint16_t glyphW, uint16_t glyphH) {
    CachedGlyph& glyph = mUploadHistory.next();
    glyph.generation = generation;
    glyph.texture = texture;
    glyph.startX = x;
    glyph.startY = y;
    glyph.bitmapW = glyphW;
    glyph.bitmapH = glyphH;
}

void FontCacheHistoryTracker::glyphsCleared(CacheTexture* texture) {
    CachedGlyph& glyph = mUploadHistory.next();
    glyph.generation = generation;
    glyph.texture = texture;
    glyph.startX = 0;
    glyph.startY = 0;
    glyph.bitmapW = 0;
    glyph.bitmapH = 0;
}

void FontCacheHistoryTracker::frameCompleted() {
    generation++;
}
};  // namespace uirenderer
};  // namespace android
