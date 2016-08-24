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

class TextAnimation;

static TestScene::Registrar _Text(TestScene::Info{
    "text",
    "Draws a bunch of text.",
    TestScene::simpleCreateScene<TextAnimation>
});

class TextAnimation : public TestScene {
public:
    sp<RenderNode> card;
    void createContent(int width, int height, TestCanvas& canvas) override {
        canvas.drawColor(Color::White, SkXfermode::kSrcOver_Mode);
        card = TestUtils::createNode(0, 0, width, height,
                [](RenderProperties& props, TestCanvas& canvas) {
            SkPaint paint;
            paint.setTextEncoding(SkPaint::kGlyphID_TextEncoding);
            paint.setAntiAlias(true);
            paint.setTextSize(50);

            paint.setColor(Color::Black);
            for (int i = 0; i < 10; i++) {
                TestUtils::drawUtf8ToCanvas(&canvas, "Test string", paint, 400, i * 100);
            }

            SkPath path;
            path.addOval(SkRect::MakeLTRB(100, 100, 300, 300));

            paint.setColor(Color::Blue_500);
            TestUtils::drawUtf8ToCanvas(&canvas, "This is a neat circle of text!", paint, path);
        });
        canvas.drawRenderNode(card.get());
    }

    void doFrame(int frameNr) override {
        int curFrame = frameNr % 150;
        card->mutateStagingProperties().setTranslationX(curFrame);
        card->mutateStagingProperties().setTranslationY(curFrame);
        card->setPropertyFieldsDirty(RenderNode::X | RenderNode::Y);
    }
};
