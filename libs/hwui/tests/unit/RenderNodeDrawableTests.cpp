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
#include "renderthread/CanvasContext.h"
#include "tests/common/TestUtils.h"
#include "SkiaCanvas.h"
#include <SkLiteRecorder.h>
#include <string.h>


using namespace android;
using namespace android::uirenderer;
using namespace android::uirenderer::renderthread;
using namespace android::uirenderer::skiapipeline;

static sp<RenderNode> createSkiaNode(int left, int top, int right, int bottom,
        std::function<void(RenderProperties& props, SkiaRecordingCanvas& canvas)> setup,
        const char* name = nullptr, SkiaDisplayList* displayList = nullptr) {
#if HWUI_NULL_GPU
    // if RenderNodes are being sync'd/used, device info will be needed, since
    // DeviceInfo::maxTextureSize() affects layer property
    DeviceInfo::initialize();
#endif
    sp<RenderNode> node = new RenderNode();
    if (name) {
        node->setName(name);
    }
    RenderProperties& props = node->mutateStagingProperties();
    props.setLeftTopRightBottom(left, top, right, bottom);
    if (displayList) {
        node->setStagingDisplayList(displayList, nullptr);
    }
    if (setup) {
        std::unique_ptr<SkiaRecordingCanvas> canvas(new SkiaRecordingCanvas(nullptr,
            props.getWidth(), props.getHeight()));
        setup(props, *canvas.get());
        node->setStagingDisplayList(canvas->finishRecording(), nullptr);
    }
    node->setPropertyFieldsDirty(0xFFFFFFFF);
    TestUtils::syncHierarchyPropertiesAndDisplayList(node);
    return node;
}

TEST(RenderNodeDrawable, create) {
    auto rootNode = TestUtils::createNode(0, 0, 200, 400,
            [](RenderProperties& props, Canvas& canvas) {
                canvas.drawColor(Color::Red_500, SkBlendMode::kSrcOver);
            });

    auto skLiteDL = SkLiteDL::New(SkRect::MakeWH(1, 1));
    SkLiteRecorder canvas;
    canvas.reset(skLiteDL.get());
    canvas.translate(100, 100);
    RenderNodeDrawable drawable(rootNode.get(), &canvas);

    ASSERT_EQ(drawable.getRenderNode(), rootNode.get());
    ASSERT_EQ(&drawable.getNodeProperties(), &rootNode->properties());
    ASSERT_EQ(drawable.getRecordedMatrix(), canvas.getTotalMatrix());
}

TEST(RenderNodeDrawable, drawContent) {
    auto surface = SkSurface::MakeRasterN32Premul(1, 1);
    SkCanvas& canvas = *surface->getCanvas();
    canvas.drawColor(SK_ColorBLUE, SkBlendMode::kSrcOver);
    ASSERT_EQ(TestUtils::getColor(surface, 0, 0), SK_ColorBLUE);

    //create a RenderNodeDrawable backed by a RenderNode backed by a SkLiteRecorder
    auto rootNode = createSkiaNode(0, 0, 1, 1,
        [](RenderProperties& props, SkiaRecordingCanvas& recorder) {
            recorder.drawColor(SK_ColorRED, SkBlendMode::kSrcOver);
        });
    RenderNodeDrawable drawable(rootNode.get(), &canvas, false);

    //negative and positive Z order are drawn out of order
    rootNode->animatorProperties().setElevation(10.0f);
    canvas.drawDrawable(&drawable);
    ASSERT_EQ(TestUtils::getColor(surface, 0, 0), SK_ColorBLUE);
    rootNode->animatorProperties().setElevation(-10.0f);
    canvas.drawDrawable(&drawable);
    ASSERT_EQ(TestUtils::getColor(surface, 0, 0), SK_ColorBLUE);

    //zero Z are drawn immediately
    rootNode->animatorProperties().setElevation(0.0f);
    canvas.drawDrawable(&drawable);
    ASSERT_EQ(TestUtils::getColor(surface, 0, 0), SK_ColorRED);
}

//TODO: another test that verifies equal z values are drawn in order, and barriers prevent Z
//intermixing (model after FrameBuilder zReorder)
TEST(RenderNodeDrawable, drawAndReorder) {
    //this test exercises StartReorderBarrierDrawable, EndReorderBarrierDrawable and
    //SkiaRecordingCanvas
    auto surface = SkSurface::MakeRasterN32Premul(4, 4);
    SkCanvas& canvas = *surface->getCanvas();

    canvas.drawColor(SK_ColorWHITE, SkBlendMode::kSrcOver);
    ASSERT_EQ(TestUtils::getColor(surface, 0, 0), SK_ColorWHITE);

    //-z draws to all 4 pixels (RED)
    auto redNode = createSkiaNode(0, 0, 4, 4,
        [](RenderProperties& props, SkiaRecordingCanvas& redCanvas) {
            redCanvas.drawColor(SK_ColorRED, SkBlendMode::kSrcOver);
            props.setElevation(-10.0f);
        }, "redNode");

    //0z draws to bottom 2 pixels (GREEN)
    auto bottomHalfGreenNode = createSkiaNode(0, 0, 4, 4,
            [](RenderProperties& props, SkiaRecordingCanvas& bottomHalfGreenCanvas) {
                SkPaint greenPaint;
                greenPaint.setColor(SK_ColorGREEN);
                greenPaint.setStyle(SkPaint::kFill_Style);
                bottomHalfGreenCanvas.drawRect(0, 2, 4, 4, greenPaint);
                props.setElevation(0.0f);
            }, "bottomHalfGreenNode");

    //+z draws to right 2 pixels (BLUE)
    auto rightHalfBlueNode = createSkiaNode(0, 0, 4, 4,
        [](RenderProperties& props, SkiaRecordingCanvas& rightHalfBlueCanvas) {
            SkPaint bluePaint;
            bluePaint.setColor(SK_ColorBLUE);
            bluePaint.setStyle(SkPaint::kFill_Style);
            rightHalfBlueCanvas.drawRect(2, 0, 4, 4, bluePaint);
            props.setElevation(10.0f);
        }, "rightHalfBlueNode");

    auto rootNode = createSkiaNode(0, 0, 4, 4,
            [&](RenderProperties& props, SkiaRecordingCanvas& rootRecorder) {
                rootRecorder.insertReorderBarrier(true);
                //draw in reverse Z order, so Z alters draw order
                rootRecorder.drawRenderNode(rightHalfBlueNode.get());
                rootRecorder.drawRenderNode(bottomHalfGreenNode.get());
                rootRecorder.drawRenderNode(redNode.get());
            }, "rootNode");

    RenderNodeDrawable drawable3(rootNode.get(), &canvas, false);
    canvas.drawDrawable(&drawable3);
    ASSERT_EQ(TestUtils::getColor(surface, 0, 0), SK_ColorRED);
    ASSERT_EQ(TestUtils::getColor(surface, 0, 3), SK_ColorGREEN);
    ASSERT_EQ(TestUtils::getColor(surface, 3, 3), SK_ColorBLUE);
}

TEST(RenderNodeDrawable, composeOnLayer)
{
    auto surface = SkSurface::MakeRasterN32Premul(1, 1);
    SkCanvas& canvas = *surface->getCanvas();
    canvas.drawColor(SK_ColorBLUE, SkBlendMode::kSrcOver);
    ASSERT_EQ(TestUtils::getColor(surface, 0, 0), SK_ColorBLUE);

    auto rootNode = createSkiaNode(0, 0, 1, 1,
        [](RenderProperties& props, SkiaRecordingCanvas& recorder) {
            recorder.drawColor(SK_ColorRED, SkBlendMode::kSrcOver);
        });

    //attach a layer to the render node
    auto surfaceLayer = SkSurface::MakeRasterN32Premul(1, 1);
    auto canvas2 = surfaceLayer->getCanvas();
    canvas2->drawColor(SK_ColorWHITE, SkBlendMode::kSrcOver);
    rootNode->setLayerSurface( surfaceLayer  );

    RenderNodeDrawable drawable1(rootNode.get(), &canvas, false);
    canvas.drawDrawable(&drawable1);
    ASSERT_EQ(SK_ColorRED, TestUtils::getColor(surface, 0, 0));

    RenderNodeDrawable drawable2(rootNode.get(), &canvas, true);
    canvas.drawDrawable(&drawable2);
    ASSERT_EQ(SK_ColorWHITE, TestUtils::getColor(surface, 0, 0));

    RenderNodeDrawable drawable3(rootNode.get(), &canvas, false);
    canvas.drawDrawable(&drawable3);
    ASSERT_EQ(SK_ColorRED, TestUtils::getColor(surface, 0, 0));

    rootNode->setLayerSurface( sk_sp<SkSurface>()  );
}

//TODO: refactor to cover test cases from FrameBuilderTests_projectionReorder
//validate with bounds and projection path mask.
//TODO: research if we could hook in and mock/validate different aspects of the drawing,
//instead of validating pixels
TEST(RenderNodeDrawable, projectDraw) {
    auto surface = SkSurface::MakeRasterN32Premul(1, 1);
    SkCanvas& canvas = *surface->getCanvas();
    canvas.drawColor(SK_ColorBLUE, SkBlendMode::kSrcOver);
    ASSERT_EQ(TestUtils::getColor(surface, 0, 0), SK_ColorBLUE);

    auto redNode = createSkiaNode(0, 0, 1, 1,
        [](RenderProperties& props, SkiaRecordingCanvas& redCanvas) {
            redCanvas.drawColor(SK_ColorRED, SkBlendMode::kSrcOver);
        }, "redNode");

    auto greenNodeWithRedChild = createSkiaNode(0, 0, 1, 1,
        [&](RenderProperties& props, SkiaRecordingCanvas& greenCanvasWithRedChild) {
            greenCanvasWithRedChild.drawRenderNode(redNode.get());
            greenCanvasWithRedChild.drawColor(SK_ColorGREEN, SkBlendMode::kSrcOver);
        }, "greenNodeWithRedChild");

    auto rootNode = createSkiaNode(0, 0, 1, 1,
        [&](RenderProperties& props, SkiaRecordingCanvas& rootCanvas) {
            rootCanvas.drawRenderNode(greenNodeWithRedChild.get());
        }, "rootNode");
    SkiaDisplayList* rootDisplayList = static_cast<SkiaDisplayList*>(
        (const_cast<DisplayList*>(rootNode->getDisplayList())));

    RenderNodeDrawable rootDrawable(rootNode.get(), &canvas, false);
    canvas.drawDrawable(&rootDrawable);
    ASSERT_EQ(TestUtils::getColor(surface, 0, 0), SK_ColorGREEN);

    //project redNode on rootNode, which will change the test outcome,
    //because redNode will draw after greenNodeWithRedChild
    rootDisplayList->mIsProjectionReceiver = true;
    redNode->animatorProperties().setProjectBackwards(true);
    canvas.drawDrawable(&rootDrawable);
    ASSERT_EQ(TestUtils::getColor(surface, 0, 0), SK_ColorRED);
}
