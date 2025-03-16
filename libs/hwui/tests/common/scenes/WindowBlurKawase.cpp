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

class WindowBlurKawase;

static TestScene::Registrar _WindowBlurKawase(TestScene::Info{
        "windowblurkawase", "Draws window Kawase blur",
        TestScene::simpleCreateScene<WindowBlurKawase>});

/**
 * Simulates the multi-pass Kawase blur algorithm in
 * frameworks/native/libs/renderengine/skia/filters/WindowBlurKawaseFilter.cpp
 */
class WindowBlurKawase : public TestScene {
private:
    // Keep in sync with
    // frameworks/native/libs/renderengine/skia/filters/KawaseBlurFilter.h
    static constexpr uint32_t kMaxPasses = 4;
    // Keep in sync with frameworks/native/libs/renderengine/skia/filters/BlurFilter.h
    static constexpr float kInputScale = 0.25f;

    static constexpr uint32_t kLoopLength = 500;
    static constexpr uint32_t kMaxBlurRadius = 300;
    sk_sp<SkRuntimeEffect> mBlurEffect;

    sp<RenderNode> card;
    sp<RenderNode> contentNode;

public:
    explicit WindowBlurKawase() {
        SkString blurString(
                "uniform shader child;"
                "uniform float in_blurOffset;"

                "half4 main(float2 xy) {"
                "half4 c = child.eval(xy);"
                "c += child.eval(xy + float2(+in_blurOffset, +in_blurOffset));"
                "c += child.eval(xy + float2(+in_blurOffset, -in_blurOffset));"
                "c += child.eval(xy + float2(-in_blurOffset, -in_blurOffset));"
                "c += child.eval(xy + float2(-in_blurOffset, +in_blurOffset));"
                "return half4(c.rgb * 0.2, 1.0);"
                "}");

        auto [blurEffect, error] = SkRuntimeEffect::MakeForShader(blurString);
        if (!blurEffect) {
            LOG_ALWAYS_FATAL("RuntimeShader error: %s", error.c_str());
        }
        mBlurEffect = std::move(blurEffect);
    }

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
        float tmpRadius = (float)blurRadius / 2.0f;
        uint32_t numberOfPasses = std::min(kMaxPasses, (uint32_t)ceil(tmpRadius));
        float radiusByPasses = tmpRadius / (float)numberOfPasses;

        SkRuntimeShaderBuilder blurBuilder(mBlurEffect);

        sp<RenderNode> node = contentNode;
        for (int i = 0; i < numberOfPasses; i++) {
            blurBuilder.uniform("in_blurOffset") = radiusByPasses * kInputScale * (i + 1);
            sk_sp<SkImageFilter> blurFilter =
                    SkImageFilters::RuntimeShader(blurBuilder, radiusByPasses, "child", nullptr);
            // Also downsample the image in the first pass.
            float canvasScale = i == 0 ? kInputScale : 1;

            // Apply the blur effect as an image filter.
            node = TestUtils::createNode(
                    0, 0, width * kInputScale, height * kInputScale,
                    [node, blurFilter, canvasScale](RenderProperties& props, Canvas& canvas) {
                        props.mutateLayerProperties().setImageFilter(blurFilter.get());
                        canvas.scale(canvasScale, canvasScale);
                        canvas.drawRenderNode(node.get());
                    });
        }

        // Finally upsample the image to its original size.
        canvas.scale(1 / kInputScale, 1 / kInputScale);
        canvas.drawRenderNode(node.get());
    }
};
