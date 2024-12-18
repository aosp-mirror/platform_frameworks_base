/*
 * Copyright (C) 2024 The Android Open Source Project
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

#include <SkBitmap.h>
#include <SkBlendMode.h>
#include <SkCanvas.h>
#include <SkPaint.h>
#include <SkRefCnt.h>
#include <SkRuntimeEffect.h>
#include <SkSurface.h>
#include <include/gpu/ganesh/SkSurfaceGanesh.h>
#include <math.h>

#include "SkImageFilters.h"
#include "TestSceneBase.h"
#include "include/gpu/GpuTypes.h"  // from Skia
#include "tests/common/BitmapAllocationTestUtils.h"
#include "utils/Color.h"

class WindowBlurSkia;

static TestScene::Registrar _WindowBlurSkia(TestScene::Info{
        "windowblurskia", "Draws window Skia blur", TestScene::simpleCreateScene<WindowBlurSkia>});

/**
 * Simulates the Skia window blur in
 * frameworks/native/libs/renderengine/skia/filters/GaussianBlurFilter.cpp
 */
class WindowBlurSkia : public TestScene {
private:
    // Keep in sync with frameworks/native/libs/renderengine/skia/filters/BlurFilter.h
    static constexpr float kInputScale = 0.25f;

    static constexpr uint32_t kLoopLength = 500;
    static constexpr uint32_t kMaxBlurRadius = 300;

    sp<RenderNode> card;
    sp<RenderNode> contentNode;

public:
    void createContent(int width, int height, Canvas& canvas) override {
        contentNode = TestUtils::createNode(
                0, 0, width, height, [width, height](RenderProperties& props, Canvas& canvas) {
                    canvas.drawColor(Color::White, SkBlendMode::kSrcOver);
                    Paint paint;
                    paint.setColor(Color::Red_500);
                    canvas.drawRect(0, 0, width / 2, height / 2, paint);
                    paint.setColor(Color::Blue_500);
                    canvas.drawRect(width / 2, height / 2, width, height, paint);
                });

        card = TestUtils::createNode(
                0, 0, width, height,
                [this](RenderProperties& props, Canvas& canvas) { blurFrame(canvas, 0); });
        canvas.drawRenderNode(card.get());
    }

    void doFrame(int frameNr) override {
        int curFrame = frameNr % kLoopLength;
        float blurRadius =
                (sin((float)curFrame / kLoopLength * M_PI * 2) + 1) * 0.5 * kMaxBlurRadius;
        TestUtils::recordNode(
                *card, [this, blurRadius](Canvas& canvas) { blurFrame(canvas, blurRadius); });
    }

    void blurFrame(Canvas& canvas, float blurRadius) {
        if (blurRadius == 0) {
            canvas.drawRenderNode(contentNode.get());
            return;
        }

        int width = canvas.width();
        int height = canvas.height();

        // Downsample and blur the image with the Skia blur filter.
        sp<RenderNode> node = contentNode;
        sk_sp<SkImageFilter> blurFilter =
                SkImageFilters::Blur(blurRadius, blurRadius, SkTileMode::kClamp, nullptr, nullptr);
        node = TestUtils::createNode(
                0, 0, width * kInputScale, height * kInputScale,
                [node, blurFilter](RenderProperties& props, Canvas& canvas) {
                    props.mutateLayerProperties().setImageFilter(blurFilter.get());
                    canvas.scale(kInputScale, kInputScale);
                    canvas.drawRenderNode(node.get());
                });

        // Upsample the image to its original size.
        canvas.scale(1 / kInputScale, 1 / kInputScale);
        canvas.drawRenderNode(node.get());
    }
};
