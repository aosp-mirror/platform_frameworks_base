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

#include "TestSceneBase.h"
#include "utils/Color.h"

#include <minikin/Layout.h>
#include <hwui/Paint.h>

#include <cstdio>

class GlyphStressAnimation;

static TestScene::Registrar _GlyphStress(TestScene::Info{
    "glyphstress",
    "A stress test for both the glyph cache, and glyph rendering.",
    TestScene::simpleCreateScene<GlyphStressAnimation>
});

class GlyphStressAnimation : public TestScene {
public:
    sp<RenderNode> container;
    void createContent(int width, int height, TestCanvas& canvas) override {
        container = TestUtils::createNode(0, 0, width, height, nullptr);
        doFrame(0); // update container

        canvas.drawColor(Color::White, SkXfermode::kSrcOver_Mode);
        canvas.drawRenderNode(container.get());
    }

    void doFrame(int frameNr) override {
        std::unique_ptr<uint16_t[]> text = TestUtils::asciiToUtf16(
                "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ");
        ssize_t textLength = 26 * 2;

        TestCanvas canvas(
                container->stagingProperties().getWidth(),
                container->stagingProperties().getHeight());
        Paint paint;
        paint.setTextEncoding(SkPaint::kGlyphID_TextEncoding);
        paint.setAntiAlias(true);
        paint.setColor(Color::Black);
        for (int i = 0; i < 5; i++) {
            paint.setTextSize(10 + (frameNr % 20) + i * 20);
            canvas.drawText(text.get(), 0, textLength, textLength,
                    0, 100 * (i + 2), kBidi_Force_LTR, paint, nullptr);
        }

        container->setStagingDisplayList(canvas.finishRecording(), nullptr);
    }
};
