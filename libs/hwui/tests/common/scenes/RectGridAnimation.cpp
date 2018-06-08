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

class RectGridAnimation;

static TestScene::Registrar _RectGrid(TestScene::Info{
        "rectgrid",
        "A dense grid of 1x1 rects that should visually look like a single rect. "
        "Low CPU/GPU load.",
        TestScene::simpleCreateScene<RectGridAnimation>});

class RectGridAnimation : public TestScene {
public:
    sp<RenderNode> card;
    void createContent(int width, int height, Canvas& canvas) override {
        canvas.drawColor(0xFFFFFFFF, SkBlendMode::kSrcOver);
        canvas.insertReorderBarrier(true);

        card = TestUtils::createNode(50, 50, 250, 250, [](RenderProperties& props, Canvas& canvas) {
            canvas.drawColor(0xFFFF00FF, SkBlendMode::kSrcOver);

            SkRegion region;
            for (int xOffset = 0; xOffset < 200; xOffset += 2) {
                for (int yOffset = 0; yOffset < 200; yOffset += 2) {
                    region.op(xOffset, yOffset, xOffset + 1, yOffset + 1, SkRegion::kUnion_Op);
                }
            }

            SkPaint paint;
            paint.setColor(0xff00ffff);
            canvas.drawRegion(region, paint);
        });
        canvas.drawRenderNode(card.get());

        canvas.insertReorderBarrier(false);
    }
    void doFrame(int frameNr) override {
        int curFrame = frameNr % 150;
        card->mutateStagingProperties().setTranslationX(curFrame);
        card->mutateStagingProperties().setTranslationY(curFrame);
        card->setPropertyFieldsDirty(RenderNode::X | RenderNode::Y);
    }
};
