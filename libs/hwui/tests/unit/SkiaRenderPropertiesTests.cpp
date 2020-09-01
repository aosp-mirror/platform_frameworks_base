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

#include <VectorDrawable.h>
#include <gtest/gtest.h>

#include <SkClipStack.h>
#include <SkSurface_Base.h>
#include <string.h>
#include "AnimationContext.h"
#include "DamageAccumulator.h"
#include "FatalTestCanvas.h"
#include "IContextFactory.h"
#include "hwui/Paint.h"
#include "SkiaCanvas.h"
#include "pipeline/skia/SkiaDisplayList.h"
#include "pipeline/skia/SkiaPipeline.h"
#include "pipeline/skia/SkiaRecordingCanvas.h"
#include "renderthread/CanvasContext.h"
#include "tests/common/TestUtils.h"

using namespace android;
using namespace android::uirenderer;
using namespace android::uirenderer::renderthread;
using namespace android::uirenderer::skiapipeline;

namespace {

static void testProperty(std::function<void(RenderProperties&)> propSetupCallback,
                         std::function<void(const SkCanvas&)> opValidateCallback) {
    static const int CANVAS_WIDTH = 100;
    static const int CANVAS_HEIGHT = 100;
    class PropertyTestCanvas : public TestCanvasBase {
    public:
        explicit PropertyTestCanvas(std::function<void(const SkCanvas&)> callback)
                : TestCanvasBase(CANVAS_WIDTH, CANVAS_HEIGHT), mCallback(callback) {}
        void onDrawRect(const SkRect& rect, const SkPaint& paint) override {
            EXPECT_EQ(mDrawCounter++, 0);
            mCallback(*this);
        }
        void onClipRRect(const SkRRect& rrect, SkClipOp op, ClipEdgeStyle style) {
            SkCanvas::onClipRRect(rrect, op, style);
        }
        std::function<void(const SkCanvas&)> mCallback;
    };

    auto node = TestUtils::createSkiaNode(
            0, 0, CANVAS_WIDTH, CANVAS_HEIGHT,
            [propSetupCallback](RenderProperties& props, SkiaRecordingCanvas& canvas) {
                propSetupCallback(props);
                Paint paint;
                paint.setColor(SK_ColorWHITE);
                canvas.drawRect(0, 0, CANVAS_WIDTH, CANVAS_HEIGHT, paint);
            });

    PropertyTestCanvas canvas(opValidateCallback);
    RenderNodeDrawable drawable(node.get(), &canvas, true);
    canvas.drawDrawable(&drawable);
    EXPECT_EQ(1, canvas.mDrawCounter);
}
}

TEST(RenderNodeDrawable, renderPropClipping) {
    testProperty(
            [](RenderProperties& properties) {
                properties.setClipToBounds(true);
                properties.setClipBounds(android::uirenderer::Rect(10, 20, 300, 400));
            },
            [](const SkCanvas& canvas) {
                EXPECT_EQ(SkRect::MakeLTRB(10, 20, 100, 100), TestUtils::getClipBounds(&canvas))
                        << "Clip rect should be intersection of node bounds and clip bounds";
            });
}

TEST(RenderNodeDrawable, renderPropRevealClip) {
    testProperty(
            [](RenderProperties& properties) {
                properties.mutableRevealClip().set(true, 50, 50, 25);
            },
            [](const SkCanvas& canvas) {
                EXPECT_EQ(SkRect::MakeLTRB(25, 25, 75, 75), TestUtils::getClipBounds(&canvas));
            });
}

TEST(RenderNodeDrawable, renderPropOutlineClip) {
    testProperty(
            [](RenderProperties& properties) {
                properties.mutableOutline().setShouldClip(true);
                properties.mutableOutline().setRoundRect(10, 20, 30, 40, 5.0f, 0.5f);
            },
            [](const SkCanvas& canvas) {
                EXPECT_EQ(SkRect::MakeLTRB(10, 20, 30, 40), TestUtils::getClipBounds(&canvas));
            });
}

TEST(RenderNodeDrawable, renderPropTransform) {
    testProperty(
            [](RenderProperties& properties) {
                properties.setLeftTopRightBottom(10, 10, 110, 110);

                SkMatrix staticMatrix = SkMatrix::MakeScale(1.2f, 1.2f);
                properties.setStaticMatrix(&staticMatrix);

                // ignored, since static overrides animation
                SkMatrix animationMatrix = SkMatrix::MakeTrans(15, 15);
                properties.setAnimationMatrix(&animationMatrix);

                properties.setTranslationX(10);
                properties.setTranslationY(20);
                properties.setScaleX(0.5f);
                properties.setScaleY(0.7f);
            },
            [](const SkCanvas& canvas) {
                Matrix4 matrix;
                matrix.loadTranslate(10, 10, 0);  // left, top
                matrix.scale(1.2f, 1.2f, 1);      // static matrix
                // ignore animation matrix, since static overrides it

                // translation xy
                matrix.translate(10, 20);

                // scale xy (from default pivot - center)
                matrix.translate(50, 50);
                matrix.scale(0.5f, 0.7f, 1);
                matrix.translate(-50, -50);
                Matrix4 actual(canvas.getTotalMatrix());
                EXPECT_MATRIX_APPROX_EQ(matrix, actual) << "Op draw matrix must match expected "
                                                           "combination of transformation "
                                                           "properties";
            });
}
