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

#include <SkColorMatrixFilter.h>
#include <SkGradientShader.h>

class SimpleColorMatrixAnimation;

static TestScene::Registrar _SimpleColorMatrix(TestScene::Info{
        "simpleColorMatrix",
        "A color matrix shader benchmark for the simple scale/translate case, which has R, G, and "
        "B "
        "all scaled and translated the same amount.",
        TestScene::simpleCreateScene<SimpleColorMatrixAnimation>});

class SimpleColorMatrixAnimation : public TestScene {
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
                    Paint paint;
                    // Simple scale/translate case where R, G, and B are all treated equivalently
                    SkColorMatrix cm;
                    cm.setScale(1.1f, 1.1f, 1.1f, 0.5f);
                    cm.postTranslate(5.0f/255, 5.0f/255, 5.0f/255, 10.0f/255);

                    paint.setColorFilter(SkColorFilters::Matrix(cm));

                    // set a shader so it's not likely for the matrix to be optimized away (since a
                    // clever
                    // enough renderer might apply it directly to the paint color)
                    float pos[] = {0, 1};
                    SkPoint pts[] = {SkPoint::Make(0, 0), SkPoint::Make(width, height)};
                    SkColor colors[2] = {Color::DeepPurple_500, Color::DeepOrange_500};
                    paint.setShader(SkGradientShader::MakeLinear(pts, colors, pos, 2,
                                                                 SkTileMode::kClamp));

                    // overdraw several times to emphasize shader cost
                    for (int i = 0; i < 10; i++) {
                        canvas.drawRect(i, i, width, height, paint);
                    }
                });
    }
};
