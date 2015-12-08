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

class ShadowGridAnimation;

static TestScene::Registrar _ShadowGrid(TestScene::Info{
    "shadowgrid",
    "A grid of rounded rects that cast a shadow. Simplified scenario of an "
    "Android TV-style launcher interface. High CPU/GPU load.",
    TestScene::simpleCreateScene<ShadowGridAnimation>
});

class ShadowGridAnimation : public TestScene {
public:
    std::vector< sp<RenderNode> > cards;
    void createContent(int width, int height, TestCanvas& canvas) override {
        canvas.drawColor(0xFFFFFFFF, SkXfermode::kSrcOver_Mode);
        canvas.insertReorderBarrier(true);

        for (int x = dp(16); x < (width - dp(116)); x += dp(116)) {
            for (int y = dp(16); y < (height - dp(116)); y += dp(116)) {
                sp<RenderNode> card = createCard(x, y, dp(100), dp(100));
                canvas.drawRenderNode(card.get());
                cards.push_back(card);
            }
        }

        canvas.insertReorderBarrier(false);
    }
    void doFrame(int frameNr) override {
        int curFrame = frameNr % 150;
        for (size_t ci = 0; ci < cards.size(); ci++) {
            cards[ci]->mutateStagingProperties().setTranslationX(curFrame);
            cards[ci]->mutateStagingProperties().setTranslationY(curFrame);
            cards[ci]->setPropertyFieldsDirty(RenderNode::X | RenderNode::Y);
        }
    }
private:
    sp<RenderNode> createCard(int x, int y, int width, int height) {
        return TestUtils::createNode(x, y, x + width, y + height,
                [width, height](RenderProperties& props, TestCanvas& canvas) {
            props.setElevation(dp(16));
            props.mutableOutline().setRoundRect(0, 0, width, height, dp(6), 1);
            props.mutableOutline().setShouldClip(true);
            canvas.drawColor(0xFFEEEEEE, SkXfermode::kSrcOver_Mode);
        });
    }
};
