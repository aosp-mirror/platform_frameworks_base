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

#include "DeferredLayerUpdater.h"
#include "hwui/Paint.h"

#include <minikin/Layout.h>
#include <pipeline/skia/SkiaOpenGLPipeline.h>
#include <pipeline/skia/SkiaVulkanPipeline.h>
#include <renderthread/EglManager.h>
#include <renderthread/VulkanManager.h>
#include <utils/Unicode.h>

#include "SkColorData.h"
#include "SkUnPreMultiply.h"

namespace android {
namespace uirenderer {

std::unordered_map<int, TestUtils::CallCounts> TestUtils::sMockFunctorCounts{};

SkColor TestUtils::interpolateColor(float fraction, SkColor start, SkColor end) {
    int startA = (start >> 24) & 0xff;
    int startR = (start >> 16) & 0xff;
    int startG = (start >> 8) & 0xff;
    int startB = start & 0xff;

    int endA = (end >> 24) & 0xff;
    int endR = (end >> 16) & 0xff;
    int endG = (end >> 8) & 0xff;
    int endB = end & 0xff;

    return (int)((startA + (int)(fraction * (endA - startA))) << 24) |
           (int)((startR + (int)(fraction * (endR - startR))) << 16) |
           (int)((startG + (int)(fraction * (endG - startG))) << 8) |
           (int)((startB + (int)(fraction * (endB - startB))));
}

sp<DeferredLayerUpdater> TestUtils::createTextureLayerUpdater(
        renderthread::RenderThread& renderThread) {
    android::uirenderer::renderthread::IRenderPipeline* pipeline;
    if (Properties::getRenderPipelineType() == RenderPipelineType::SkiaGL) {
        pipeline = new skiapipeline::SkiaOpenGLPipeline(renderThread);
    } else {
        pipeline = new skiapipeline::SkiaVulkanPipeline(renderThread);
    }
    sp<DeferredLayerUpdater> layerUpdater = pipeline->createTextureLayer();
    layerUpdater->apply();
    delete pipeline;
    return layerUpdater;
}

sp<DeferredLayerUpdater> TestUtils::createTextureLayerUpdater(
        renderthread::RenderThread& renderThread, uint32_t width, uint32_t height,
        const SkMatrix& transform) {
    sp<DeferredLayerUpdater> layerUpdater = createTextureLayerUpdater(renderThread);
    layerUpdater->backingLayer()->getTransform() = transform;
    layerUpdater->setSize(width, height);
    layerUpdater->setTransform(&transform);

    // updateLayer so it's ready to draw
    layerUpdater->updateLayer(true, SkMatrix::I(), nullptr);
    return layerUpdater;
}

void TestUtils::drawUtf8ToCanvas(Canvas* canvas, const char* text, const Paint& paint, float x,
                                 float y) {
    auto utf16 = asciiToUtf16(text);
    uint32_t length = strlen(text);

    canvas->drawText(utf16.get(), length,  // text buffer
                     0, length,            // draw range
                     0, length,            // context range
                     x, y, minikin::Bidi::LTR, paint, nullptr, nullptr /* measured text */);
}

void TestUtils::drawUtf8ToCanvas(Canvas* canvas, const char* text, const Paint& paint,
                                 const SkPath& path) {
    auto utf16 = asciiToUtf16(text);
    canvas->drawTextOnPath(utf16.get(), strlen(text), minikin::Bidi::LTR, path, 0, 0, paint,
                           nullptr);
}

void TestUtils::TestTask::run() {
    // RenderState only valid once RenderThread is running, so queried here
    renderthread::RenderThread& renderThread = renderthread::RenderThread::getInstance();
    if (Properties::getRenderPipelineType() == RenderPipelineType::SkiaVulkan) {
        renderThread.requireVkContext();
    } else {
        renderThread.requireGlContext();
    }

    rtCallback(renderThread);

    renderThread.destroyRenderingContext();
}

std::unique_ptr<uint16_t[]> TestUtils::asciiToUtf16(const char* str) {
    const int length = strlen(str);
    std::unique_ptr<uint16_t[]> utf16(new uint16_t[length]);
    for (int i = 0; i < length; i++) {
        utf16.get()[i] = str[i];
    }
    return utf16;
}

SkColor TestUtils::getColor(const sk_sp<SkSurface>& surface, int x, int y) {
    SkPixmap pixmap;
    if (!surface->peekPixels(&pixmap)) {
        return 0;
    }
    switch (pixmap.colorType()) {
        case kGray_8_SkColorType: {
            const uint8_t* addr = pixmap.addr8(x, y);
            return SkColorSetRGB(*addr, *addr, *addr);
        }
        case kAlpha_8_SkColorType: {
            const uint8_t* addr = pixmap.addr8(x, y);
            return SkColorSetA(0, addr[0]);
        }
        case kRGB_565_SkColorType: {
            const uint16_t* addr = pixmap.addr16(x, y);
            return SkPixel16ToColor(addr[0]);
        }
        case kARGB_4444_SkColorType: {
            const uint16_t* addr = pixmap.addr16(x, y);
            SkPMColor c = SkPixel4444ToPixel32(addr[0]);
            return SkUnPreMultiply::PMColorToColor(c);
        }
        case kBGRA_8888_SkColorType: {
            const uint32_t* addr = pixmap.addr32(x, y);
            SkPMColor c = SkSwizzle_BGRA_to_PMColor(addr[0]);
            return SkUnPreMultiply::PMColorToColor(c);
        }
        case kRGBA_8888_SkColorType: {
            const uint32_t* addr = pixmap.addr32(x, y);
            SkPMColor c = SkSwizzle_RGBA_to_PMColor(addr[0]);
            return SkUnPreMultiply::PMColorToColor(c);
        }
        default:
            return 0;
    }
    return 0;
}

SkRect TestUtils::getClipBounds(const SkCanvas* canvas) {
    return SkRect::Make(canvas->getDeviceClipBounds());
}

SkRect TestUtils::getLocalClipBounds(const SkCanvas* canvas) {
    SkMatrix invertedTotalMatrix;
    if (!canvas->getTotalMatrix().invert(&invertedTotalMatrix)) {
        return SkRect::MakeEmpty();
    }
    SkRect outlineInDeviceCoord = TestUtils::getClipBounds(canvas);
    SkRect outlineInLocalCoord;
    invertedTotalMatrix.mapRect(&outlineInLocalCoord, outlineInDeviceCoord);
    return outlineInLocalCoord;
}

} /* namespace uirenderer */
} /* namespace android */
