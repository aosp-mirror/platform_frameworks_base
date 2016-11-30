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

#include <gtest/gtest.h>
#include <VectorDrawable.h>

#include "AnimationContext.h"
#include "DamageAccumulator.h"
#include "IContextFactory.h"
#include "pipeline/skia/SkiaDisplayList.h"
#include "pipeline/skia/SkiaRecordingCanvas.h"
#include "pipeline/skia/SkiaOpenGLPipeline.h"
#include "renderthread/CanvasContext.h"
#include "tests/common/TestUtils.h"
#include "SkiaCanvas.h"
#include <SkLiteRecorder.h>
#include <string.h>

using namespace android;
using namespace android::uirenderer;
using namespace android::uirenderer::renderthread;
using namespace android::uirenderer::skiapipeline;

RENDERTHREAD_TEST(SkiaPipeline, renderFrame) {
    auto redNode = TestUtils::createSkiaNode(0, 0, 1, 1,
        [](RenderProperties& props, SkiaRecordingCanvas& redCanvas) {
            redCanvas.drawColor(SK_ColorRED, SkBlendMode::kSrcOver);
        });
    LayerUpdateQueue layerUpdateQueue;
    SkRect dirty = SkRect::MakeLargest();
    std::vector<sp<RenderNode>> renderNodes;
    renderNodes.push_back(redNode);
    bool opaque = true;
    android::uirenderer::Rect contentDrawBounds(0, 0, 1, 1);
    auto pipeline = std::make_unique<SkiaOpenGLPipeline>(renderThread);
    auto surface = SkSurface::MakeRasterN32Premul(1, 1);
    surface->getCanvas()->drawColor(SK_ColorBLUE, SkBlendMode::kSrcOver);
    ASSERT_EQ(TestUtils::getColor(surface, 0, 0), SK_ColorBLUE);
    pipeline->renderFrame(layerUpdateQueue, dirty, renderNodes, opaque, contentDrawBounds, surface);
    ASSERT_EQ(TestUtils::getColor(surface, 0, 0), SK_ColorRED);
}

RENDERTHREAD_TEST(SkiaPipeline, renderFrameCheckBounds) {
    auto backdropRedNode = TestUtils::createSkiaNode(1, 1, 4, 4,
        [](RenderProperties& props, SkiaRecordingCanvas& redCanvas) {
            redCanvas.drawColor(SK_ColorRED, SkBlendMode::kSrcOver);
        });
    auto contentGreenNode = TestUtils::createSkiaNode(2, 2, 5, 5,
        [](RenderProperties& props, SkiaRecordingCanvas& blueCanvas) {
            blueCanvas.drawColor(SK_ColorGREEN, SkBlendMode::kSrcOver);
        });
    LayerUpdateQueue layerUpdateQueue;
    SkRect dirty = SkRect::MakeLargest();
    std::vector<sp<RenderNode>> renderNodes;
    renderNodes.push_back(backdropRedNode);  //first node is backdrop
    renderNodes.push_back(contentGreenNode); //second node is content drawn on top of the backdrop
    android::uirenderer::Rect contentDrawBounds(1, 1, 3, 3);
    auto pipeline = std::make_unique<SkiaOpenGLPipeline>(renderThread);
    auto surface = SkSurface::MakeRasterN32Premul(5, 5);
    surface->getCanvas()->drawColor(SK_ColorBLUE, SkBlendMode::kSrcOver);
    ASSERT_EQ(TestUtils::getColor(surface, 0, 0), SK_ColorBLUE);
    //backdropBounds is (1, 1, 3, 3), content clip is (1, 1, 3, 3), content translate is (0, 0)
    pipeline->renderFrame(layerUpdateQueue, dirty, renderNodes, true, contentDrawBounds, surface);
    ASSERT_EQ(TestUtils::getColor(surface, 0, 0), SK_ColorBLUE);
    ASSERT_EQ(TestUtils::getColor(surface, 1, 1), SK_ColorRED);
    ASSERT_EQ(TestUtils::getColor(surface, 2, 2), SK_ColorGREEN);
    ASSERT_EQ(TestUtils::getColor(surface, 3, 3), SK_ColorRED);
    ASSERT_EQ(TestUtils::getColor(surface, 4, 4), SK_ColorBLUE);

    surface->getCanvas()->drawColor(SK_ColorBLUE, SkBlendMode::kSrcOver);
    contentDrawBounds.set(0, 0, 5, 5);
    //backdropBounds is (1, 1, 4, 4), content clip is (0, 0, 3, 3), content translate is (1, 1)
    pipeline->renderFrame(layerUpdateQueue, dirty, renderNodes, true, contentDrawBounds, surface);
    ASSERT_EQ(TestUtils::getColor(surface, 0, 0), SK_ColorBLUE);
    ASSERT_EQ(TestUtils::getColor(surface, 1, 1), SK_ColorRED);
    ASSERT_EQ(TestUtils::getColor(surface, 2, 2), SK_ColorRED);
    ASSERT_EQ(TestUtils::getColor(surface, 3, 3), SK_ColorGREEN);
    ASSERT_EQ(TestUtils::getColor(surface, 4, 4), SK_ColorBLUE);
}

RENDERTHREAD_TEST(SkiaPipeline, renderFrameCheckOpaque) {
    auto halfGreenNode = TestUtils::createSkiaNode(0, 0, 2, 2,
        [](RenderProperties& props, SkiaRecordingCanvas& bottomHalfGreenCanvas) {
            SkPaint greenPaint;
            greenPaint.setColor(SK_ColorGREEN);
            greenPaint.setStyle(SkPaint::kFill_Style);
            bottomHalfGreenCanvas.drawRect(0, 1, 2, 2, greenPaint);
        });
    LayerUpdateQueue layerUpdateQueue;
    SkRect dirty = SkRect::MakeLargest();
    std::vector<sp<RenderNode>> renderNodes;
    renderNodes.push_back(halfGreenNode);
    android::uirenderer::Rect contentDrawBounds(0, 0, 2, 2);
    auto pipeline = std::make_unique<SkiaOpenGLPipeline>(renderThread);
    auto surface = SkSurface::MakeRasterN32Premul(2, 2);
    surface->getCanvas()->drawColor(SK_ColorBLUE, SkBlendMode::kSrcOver);
    ASSERT_EQ(TestUtils::getColor(surface, 0, 0), SK_ColorBLUE);
    pipeline->renderFrame(layerUpdateQueue, dirty, renderNodes, true, contentDrawBounds, surface);
    ASSERT_EQ(TestUtils::getColor(surface, 0, 0), SK_ColorBLUE);
    ASSERT_EQ(TestUtils::getColor(surface, 0, 1), SK_ColorGREEN);
    pipeline->renderFrame(layerUpdateQueue, dirty, renderNodes, false, contentDrawBounds, surface);
    ASSERT_EQ(TestUtils::getColor(surface, 0, 0), (unsigned int)SK_ColorTRANSPARENT);
    ASSERT_EQ(TestUtils::getColor(surface, 0, 1), SK_ColorGREEN);
}

RENDERTHREAD_TEST(SkiaPipeline, renderFrameCheckDirtyRect) {
    auto redNode = TestUtils::createSkiaNode(0, 0, 2, 2,
        [](RenderProperties& props, SkiaRecordingCanvas& redCanvas) {
            redCanvas.drawColor(SK_ColorRED, SkBlendMode::kSrcOver);
        });
    LayerUpdateQueue layerUpdateQueue;
    SkRect dirty = SkRect::MakeXYWH(0, 1, 2, 1);
    std::vector<sp<RenderNode>> renderNodes;
    renderNodes.push_back(redNode);
    android::uirenderer::Rect contentDrawBounds(0, 0, 2, 2);
    auto pipeline = std::make_unique<SkiaOpenGLPipeline>(renderThread);
    auto surface = SkSurface::MakeRasterN32Premul(2, 2);
    surface->getCanvas()->drawColor(SK_ColorBLUE, SkBlendMode::kSrcOver);
    ASSERT_EQ(TestUtils::getColor(surface, 0, 0), SK_ColorBLUE);
    pipeline->renderFrame(layerUpdateQueue, dirty, renderNodes, true, contentDrawBounds, surface);
    ASSERT_EQ(TestUtils::getColor(surface, 0, 0), SK_ColorBLUE);
    ASSERT_EQ(TestUtils::getColor(surface, 1, 0), SK_ColorBLUE);
    ASSERT_EQ(TestUtils::getColor(surface, 0, 1), SK_ColorRED);
    ASSERT_EQ(TestUtils::getColor(surface, 1, 1), SK_ColorRED);
}

RENDERTHREAD_TEST(SkiaPipeline, renderLayer) {
    auto redNode = TestUtils::createSkiaNode(0, 0, 1, 1,
        [](RenderProperties& props, SkiaRecordingCanvas& redCanvas) {
            redCanvas.drawColor(SK_ColorRED, SkBlendMode::kSrcOver);
        });
    auto surfaceLayer1 = SkSurface::MakeRasterN32Premul(1, 1);
    surfaceLayer1->getCanvas()->drawColor(SK_ColorWHITE, SkBlendMode::kSrcOver);
    ASSERT_EQ(TestUtils::getColor(surfaceLayer1, 0, 0), SK_ColorWHITE);
    redNode->setLayerSurface(surfaceLayer1);

    //create a 2nd 2x2 layer and add it to the queue as well.
    //make the layer's dirty area one half of the layer and verify only the dirty half is updated.
    auto blueNode = TestUtils::createSkiaNode(0, 0, 2, 2,
        [](RenderProperties& props, SkiaRecordingCanvas& blueCanvas) {
            blueCanvas.drawColor(SK_ColorBLUE, SkBlendMode::kSrcOver);
        });
    auto surfaceLayer2 = SkSurface::MakeRasterN32Premul(2, 2);
    surfaceLayer2->getCanvas()->drawColor(SK_ColorWHITE, SkBlendMode::kSrcOver);
    ASSERT_EQ(TestUtils::getColor(surfaceLayer2, 0, 0), SK_ColorWHITE);
    blueNode->setLayerSurface(surfaceLayer2);

    //attach both layers to the update queue
    LayerUpdateQueue layerUpdateQueue;
    SkRect dirty = SkRect::MakeLargest();
    layerUpdateQueue.enqueueLayerWithDamage(redNode.get(), dirty);
    layerUpdateQueue.enqueueLayerWithDamage(blueNode.get(), SkRect::MakeWH(2, 1));
    ASSERT_EQ(layerUpdateQueue.entries().size(), 2UL);

    bool opaque = true;
    FrameBuilder::LightGeometry lightGeometry;
    lightGeometry.radius = 1.0f;
    lightGeometry.center = { 0.0f, 0.0f, 0.0f };
    BakedOpRenderer::LightInfo lightInfo;
    auto pipeline = std::make_unique<SkiaOpenGLPipeline>(renderThread);
    pipeline->renderLayers(lightGeometry, &layerUpdateQueue, opaque, lightInfo);
    ASSERT_EQ(TestUtils::getColor(surfaceLayer1, 0, 0), SK_ColorRED);
    ASSERT_EQ(TestUtils::getColor(surfaceLayer2, 0, 0), SK_ColorBLUE);
    ASSERT_EQ(TestUtils::getColor(surfaceLayer2, 0, 1), SK_ColorWHITE);
    ASSERT_TRUE(layerUpdateQueue.entries().empty());
    redNode->setLayerSurface(sk_sp<SkSurface>());
    blueNode->setLayerSurface(sk_sp<SkSurface>());
}

RENDERTHREAD_TEST(SkiaPipeline, renderOverdraw) {
    ScopedProperty<bool> prop(Properties::debugOverdraw, true);

    auto whiteNode = TestUtils::createSkiaNode(0, 0, 1, 1,
        [](RenderProperties& props, SkiaRecordingCanvas& canvas) {
            canvas.drawColor(SK_ColorWHITE, SkBlendMode::kSrcOver);
        });
    LayerUpdateQueue layerUpdateQueue;
    SkRect dirty = SkRect::MakeXYWH(0, 0, 1, 1);
    std::vector<sp<RenderNode>> renderNodes;
    renderNodes.push_back(whiteNode);
    bool opaque = true;
    android::uirenderer::Rect contentDrawBounds(0, 0, 1, 1);
    auto pipeline = std::make_unique<SkiaOpenGLPipeline>(renderThread);
    auto surface = SkSurface::MakeRasterN32Premul(1, 1);

    // Initialize the canvas to blue.
    surface->getCanvas()->drawColor(SK_ColorBLUE, SkBlendMode::kSrcOver);
    ASSERT_EQ(TestUtils::getColor(surface, 0, 0), SK_ColorBLUE);

    // Single draw, should be white.
    pipeline->renderFrame(layerUpdateQueue, dirty, renderNodes, opaque, contentDrawBounds, surface);
    ASSERT_EQ(TestUtils::getColor(surface, 0, 0), SK_ColorWHITE);

    // 1 Overdraw, should be blue blended onto white.
    renderNodes.push_back(whiteNode);
    pipeline->renderFrame(layerUpdateQueue, dirty, renderNodes, opaque, contentDrawBounds, surface);
    ASSERT_EQ(TestUtils::getColor(surface, 0, 0), (unsigned) 0xffd0d0ff);

    // 2 Overdraw, should be green blended onto white
    renderNodes.push_back(whiteNode);
    pipeline->renderFrame(layerUpdateQueue, dirty, renderNodes, opaque, contentDrawBounds, surface);
    ASSERT_EQ(TestUtils::getColor(surface, 0, 0), (unsigned) 0xffd0ffd0);

    // 3 Overdraw, should be pink blended onto white.
    renderNodes.push_back(whiteNode);
    pipeline->renderFrame(layerUpdateQueue, dirty, renderNodes, opaque, contentDrawBounds, surface);
    ASSERT_EQ(TestUtils::getColor(surface, 0, 0), (unsigned) 0xffffc0c0);

    // 4 Overdraw, should be red blended onto white.
    renderNodes.push_back(whiteNode);
    pipeline->renderFrame(layerUpdateQueue, dirty, renderNodes, opaque, contentDrawBounds, surface);
    ASSERT_EQ(TestUtils::getColor(surface, 0, 0), (unsigned) 0xffff8080);

    // 5 Overdraw, should be red blended onto white.
    renderNodes.push_back(whiteNode);
    pipeline->renderFrame(layerUpdateQueue, dirty, renderNodes, opaque, contentDrawBounds, surface);
    ASSERT_EQ(TestUtils::getColor(surface, 0, 0), (unsigned) 0xffff8080);
}
