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

#include <hwui/Paint.h>
#include <minikin/Layout.h>

#include <cstdio>

class GlyphStressAnimation;

static TestScene::Registrar _GlyphStress(TestScene::Info{
        "glyphstress", "A stress test for both the glyph cache, and glyph rendering.",
        TestScene::simpleCreateScene<GlyphStressAnimation>});

class GlyphStressAnimation : public TestScene {
public:
    sp<RenderNode> container;
    void createContent(int width, int height, Canvas& canvas) override {
        container = TestUtils::createNode(0, 0, width, height, nullptr);
        doFrame(0);  // update container

        canvas.drawColor(Color::White, SkBlendMode::kSrcOver);
        canvas.drawRenderNode(container.get());
    }

    void doFrame(int frameNr) override {
        const char* text = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";

        std::unique_ptr<Canvas> canvas(
                Canvas::create_recording_canvas(container->stagingProperties().getWidth(),
                                                container->stagingProperties().getHeight(),
                                                container.get()));

        Paint paint;
        paint.setAntiAlias(true);
        paint.setColor(Color::Black);
        for (int i = 0; i < 5; i++) {
            paint.setTextSize(10 + (frameNr % 20) + i * 20);
            TestUtils::drawUtf8ToCanvas(canvas.get(), text, paint, 0, 100 * (i + 2));
        }

        container->setStagingDisplayList(canvas->finishRecording());
    }
};
