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

#pragma once
#include "../utils/RingBuffer.h"

#include <utils/String8.h>

namespace android {
namespace uirenderer {

class CacheTexture;
struct CachedGlyphInfo;

// Tracks glyph uploads and recent rendered/skipped glyphs, so it can give an idea
// what a missing character is: skipped glyph, wrong coordinates in cache texture etc.
class FontCacheHistoryTracker {
public:
    void glyphRendered(CachedGlyphInfo*, int penX, int penY);
    void glyphUploaded(CacheTexture*, uint32_t x, uint32_t y, uint16_t glyphW, uint16_t glyphH);
    void glyphsCleared(CacheTexture*);
    void frameCompleted();

    void dump(String8& log) const;

private:
    struct CachedGlyph {
        void* texture;
        uint16_t generation;
        uint16_t startX;
        uint16_t startY;
        uint16_t bitmapW;
        uint16_t bitmapH;
    };

    struct RenderEntry {
        CachedGlyph glyph;
        int penX;
        int penY;
    };

    static void dumpCachedGlyph(String8& log, const CachedGlyph& glyph);
    static void dumpRenderEntry(String8& log, const RenderEntry& entry);
    static void dumpUploadEntry(String8& log, const CachedGlyph& glyph);

    RingBuffer<RenderEntry, 300> mRenderHistory;
    RingBuffer<CachedGlyph, 120> mUploadHistory;
    uint16_t generation = 0;
};

};  // namespace uirenderer
};  // namespace android