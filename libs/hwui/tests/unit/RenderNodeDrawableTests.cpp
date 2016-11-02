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

static void drawOrderedRect(Canvas* canvas, uint8_t expectedDrawOrder) {
    SkPaint paint;
    // order put in blue channel, transparent so overlapped content doesn't get rejected
    paint.setColor(SkColorSetARGB(1, 0, 0, expectedDrawOrder));
    canvas->drawRect(0, 0, 100, 100, paint);
}

static void drawOrderedNode(Canvas* canvas, uint8_t expectedDrawOrder, float z) {
    auto node = TestUtils::createSkiaNode(0, 0, 100, 100,
            [expectedDrawOrder, z](RenderProperties& props, SkiaRecordingCanvas& canvas) {
        drawOrderedRect(&canvas, expectedDrawOrder);
        props.setTranslationZ(z);
    });
    canvas->drawRenderNode(node.get()); // canvas takes reference/sole ownership
}

TEST(RenderNodeDrawable, zReorder) {
    class ZReorderCanvas : public SkCanvas {
    public:
        ZReorderCanvas(int width, int height) : SkCanvas(width, height) {}
        void onDrawRect(const SkRect& rect, const SkPaint& paint) override {
            int expectedOrder = SkColorGetB(paint.getColor()); // extract order from blue channel
            EXPECT_EQ(expectedOrder, mIndex++) << "An op was drawn out of order";
        }
        int getIndex() { return mIndex; }
    protected:
        int mIndex = 0;
    };

    auto parent = TestUtils::createSkiaNode(0, 0, 100, 100,
            [](RenderProperties& props, SkiaRecordingCanvas& canvas) {
        drawOrderedNode(&canvas, 0, 10.0f); // in reorder=false at this point, so played inorder
        drawOrderedRect(&canvas, 1);
        canvas.insertReorderBarrier(true);
        drawOrderedNode(&canvas, 6, 2.0f);
        drawOrderedRect(&canvas, 3);
        drawOrderedNode(&canvas, 4, 0.0f);
        drawOrderedRect(&canvas, 5);
        drawOrderedNode(&canvas, 2, -2.0f);
        drawOrderedNode(&canvas, 7, 2.0f);
        canvas.insertReorderBarrier(false);
        drawOrderedRect(&canvas, 8);
        drawOrderedNode(&canvas, 9, -10.0f); // in reorder=false at this point, so played inorder
    });

    //create a canvas not backed by any device/pixels, but with dimensions to avoid quick rejection
    ZReorderCanvas canvas(100, 100);
    RenderNodeDrawable drawable(parent.get(), &canvas, false);
    canvas.drawDrawable(&drawable);
    EXPECT_EQ(10, canvas.getIndex());
}

TEST(RenderNodeDrawable, composeOnLayer)
{
    auto surface = SkSurface::MakeRasterN32Premul(1, 1);
    SkCanvas& canvas = *surface->getCanvas();
    canvas.drawColor(SK_ColorBLUE, SkBlendMode::kSrcOver);
    ASSERT_EQ(TestUtils::getColor(surface, 0, 0), SK_ColorBLUE);

    auto rootNode = TestUtils::createSkiaNode(0, 0, 1, 1,
        [](RenderProperties& props, SkiaRecordingCanvas& recorder) {
            recorder.drawColor(SK_ColorRED, SkBlendMode::kSrcOver);
        });

    //attach a layer to the render node
    auto surfaceLayer = SkSurface::MakeRasterN32Premul(1, 1);
    auto canvas2 = surfaceLayer->getCanvas();
    canvas2->drawColor(SK_ColorWHITE, SkBlendMode::kSrcOver);
    rootNode->setLayerSurface(surfaceLayer);

    RenderNodeDrawable drawable1(rootNode.get(), &canvas, false);
    canvas.drawDrawable(&drawable1);
    ASSERT_EQ(SK_ColorRED, TestUtils::getColor(surface, 0, 0));

    RenderNodeDrawable drawable2(rootNode.get(), &canvas, true);
    canvas.drawDrawable(&drawable2);
    ASSERT_EQ(SK_ColorWHITE, TestUtils::getColor(surface, 0, 0));

    RenderNodeDrawable drawable3(rootNode.get(), &canvas, false);
    canvas.drawDrawable(&drawable3);
    ASSERT_EQ(SK_ColorRED, TestUtils::getColor(surface, 0, 0));

    rootNode->setLayerSurface(sk_sp<SkSurface>());
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

    auto redNode = TestUtils::createSkiaNode(0, 0, 1, 1,
        [](RenderProperties& props, SkiaRecordingCanvas& redCanvas) {
            redCanvas.drawColor(SK_ColorRED, SkBlendMode::kSrcOver);
        }, "redNode");

    auto greenNodeWithRedChild = TestUtils::createSkiaNode(0, 0, 1, 1,
        [&](RenderProperties& props, SkiaRecordingCanvas& greenCanvasWithRedChild) {
            greenCanvasWithRedChild.drawRenderNode(redNode.get());
            greenCanvasWithRedChild.drawColor(SK_ColorGREEN, SkBlendMode::kSrcOver);
        }, "greenNodeWithRedChild");

    auto rootNode = TestUtils::createSkiaNode(0, 0, 1, 1,
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
