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

class ShadowGrid2Animation;

static TestScene::Registrar _ShadowGrid2(TestScene::Info{
        "shadowgrid2",
        "A dense grid of rounded rects that cast a shadow. This is a higher CPU load "
        "variant of shadowgrid. Very high CPU load, high GPU load.",
        TestScene::simpleCreateScene<ShadowGrid2Animation>});

class ShadowGrid2Animation : public TestScene {
public:
    std::vector<sp<RenderNode> > cards;
    void createContent(int width, int height, Canvas& canvas) override {
        canvas.drawColor(0xFFFFFFFF, SkBlendMode::kSrcOver);
        canvas.enableZ(true);

        for (int x = dp(8); x < (width - dp(58)); x += dp(58)) {
            for (int y = dp(8); y < (height - dp(58)); y += dp(58)) {
                sp<RenderNode> card = createCard(x, y, dp(50), dp(50));
                canvas.drawRenderNode(card.get());
                cards.push_back(card);
            }
        }

        canvas.enableZ(false);
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
                                     [width, height](RenderProperties& props, Canvas& canvas) {
                                         props.setElevation(dp(16));
                                         props.mutableOutline().setRoundRect(0, 0, width, height,
                                                                             dp(6), 1);
                                         props.mutableOutline().setShouldClip(true);
                                         canvas.drawColor(0xFFEEEEEE, SkBlendMode::kSrcOver);
                                     });
    }
};
