/*
 * Copyright (C) 2015 The Android Open Source Project
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

#include "TestUtils.h"

#include "hwui/Paint.h"
#include "DeferredLayerUpdater.h"
#include "LayerRenderer.h"

namespace android {
namespace uirenderer {

SkColor TestUtils::interpolateColor(float fraction, SkColor start, SkColor end) {
    int startA = (start >> 24) & 0xff;
    int startR = (start >> 16) & 0xff;
    int startG = (start >> 8) & 0xff;
    int startB = start & 0xff;

    int endA = (end >> 24) & 0xff;
    int endR = (end >> 16) & 0xff;
    int endG = (end >> 8) & 0xff;
    int endB = end & 0xff;

    return (int)((startA + (int)(fraction * (endA - startA))) << 24)
            | (int)((startR + (int)(fraction * (endR - startR))) << 16)
            | (int)((startG + (int)(fraction * (endG - startG))) << 8)
            | (int)((startB + (int)(fraction * (endB - startB))));
}

sp<DeferredLayerUpdater> TestUtils::createTextureLayerUpdater(
        renderthread::RenderThread& renderThread, uint32_t width, uint32_t height,
        const SkMatrix& transform) {
    Layer* layer = LayerRenderer::createTextureLayer(renderThread.renderState());
    layer->getTransform().load(transform);

    sp<DeferredLayerUpdater> layerUpdater = new DeferredLayerUpdater(layer);
    layerUpdater->setSize(width, height);
    layerUpdater->setTransform(&transform);

    // updateLayer so it's ready to draw
    bool isOpaque = true;
    bool forceFilter = true;
    GLenum renderTarget = GL_TEXTURE_EXTERNAL_OES;
    LayerRenderer::updateTextureLayer(layer, width, height, isOpaque, forceFilter,
    renderTarget, Matrix4::identity().data);

    return layerUpdater;
}

void TestUtils::layoutTextUnscaled(const SkPaint& paint, const char* text,
        std::vector<glyph_t>* outGlyphs, std::vector<float>* outPositions,
        float* outTotalAdvance, Rect* outBounds) {
    Rect bounds;
    float totalAdvance = 0;
    SkSurfaceProps surfaceProps(0, kUnknown_SkPixelGeometry);
    SkAutoGlyphCacheNoGamma autoCache(paint, &surfaceProps, &SkMatrix::I());
    while (*text != '\0') {
        SkUnichar unichar = SkUTF8_NextUnichar(&text);
        glyph_t glyph = autoCache.getCache()->unicharToGlyph(unichar);
        autoCache.getCache()->unicharToGlyph(unichar);

        // push glyph and its relative position
        outGlyphs->push_back(glyph);
        outPositions->push_back(totalAdvance);
        outPositions->push_back(0);

        // compute bounds
        SkGlyph skGlyph = autoCache.getCache()->getUnicharMetrics(unichar);
        Rect glyphBounds(skGlyph.fWidth, skGlyph.fHeight);
        glyphBounds.translate(totalAdvance + skGlyph.fLeft, skGlyph.fTop);
        bounds.unionWith(glyphBounds);

        // advance next character
        SkScalar skWidth;
        paint.getTextWidths(&glyph, sizeof(glyph), &skWidth, NULL);
        totalAdvance += skWidth;
    }
    *outBounds = bounds;
    *outTotalAdvance = totalAdvance;
}


void TestUtils::drawUtf8ToCanvas(Canvas* canvas, const char* text,
        const SkPaint& paint, float x, float y) {
    auto utf16 = asciiToUtf16(text);
    canvas->drawText(utf16.get(), 0, strlen(text), strlen(text), x, y, 0, paint, nullptr);
}

void TestUtils::drawUtf8ToCanvas(Canvas* canvas, const char* text,
        const SkPaint& paint, const SkPath& path) {
    auto utf16 = asciiToUtf16(text);
    canvas->drawTextOnPath(utf16.get(), strlen(text), 0, path, 0, 0, paint, nullptr);
}

void TestUtils::TestTask::run() {
    // RenderState only valid once RenderThread is running, so queried here
    RenderState& renderState = renderthread::RenderThread::getInstance().renderState();

    renderState.onGLContextCreated();
    rtCallback(renderthread::RenderThread::getInstance());
    renderState.flush(Caches::FlushMode::Full);
    renderState.onGLContextDestroyed();
}

std::unique_ptr<uint16_t[]> TestUtils::asciiToUtf16(const char* str) {
    const int length = strlen(str);
    std::unique_ptr<uint16_t[]> utf16(new uint16_t[length]);
    for (int i = 0; i < length; i++) {
        utf16.get()[i] = str[i];
    }
    return utf16;
}

} /* namespace uirenderer */
} /* namespace android */
