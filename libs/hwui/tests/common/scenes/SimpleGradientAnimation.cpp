/*
 * Copyright (C) 2017 The Android Open Source Project
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

#include <SkGradientShader.h>

class SimpleGradientAnimation;

static TestScene::Registrar _SimpleGradient(TestScene::Info{
        "simpleGradient",
        "A benchmark of shader performance of linear, 2 color gradients with black in them.",
        TestScene::simpleCreateScene<SimpleGradientAnimation>});

class SimpleGradientAnimation : public TestScene {
public:
    std::vector<sp<RenderNode> > cards;
    void createContent(int width, int height, Canvas& canvas) override {
        canvas.drawColor(Color::White, SkBlendMode::kSrcOver);

        sp<RenderNode> card = createCard(0, 0, width, height);
        canvas.drawRenderNode(card.get());
        cards.push_back(card);
    }
    void doFrame(int frameNr) override {
        int curFrame = frameNr % 20;
        for (size_t ci = 0; ci < cards.size(); ci++) {
            cards[ci]->mutateStagingProperties().setTranslationX(curFrame);
            cards[ci]->mutateStagingProperties().setTranslationY(curFrame);
            cards[ci]->setPropertyFieldsDirty(RenderNode::X | RenderNode::Y);
        }
    }

private:
    sp<RenderNode> createCard(int x, int y, int width, int height) {
        return TestUtils::createNode(
                x, y, x + width, y + height,
                [width, height](RenderProperties& props, Canvas& canvas) {
                    float pos[] = {0, 1};
                    SkPoint pts[] = {SkPoint::Make(0, 0), SkPoint::Make(width, height)};
                    SkPaint paint;
                    // overdraw several times to emphasize shader cost
                    for (int i = 0; i < 10; i++) {
                        // use i%2 start position to pick 2 color combo with black in it
                        SkColor colors[3] = {Color::Transparent, Color::Black, Color::Cyan_500};
                        paint.setShader(SkGradientShader::MakeLinear(pts, colors + (i % 2), pos, 2,
                                                                     SkShader::kClamp_TileMode));
                        canvas.drawRect(i, i, width, height, paint);
                    }
                });
    }
};
