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

class ShadowShaderAnimation;

static TestScene::Registrar _ShadowShader(TestScene::Info{
        "shadowshader",
        "A set of overlapping shadowed areas with simple tessellation useful for"
        " benchmarking shadow shader performance.",
        TestScene::simpleCreateScene<ShadowShaderAnimation>});

class ShadowShaderAnimation : public TestScene {
public:
    std::vector<sp<RenderNode> > cards;
    void createContent(int width, int height, Canvas& canvas) override {
        canvas.drawColor(0xFFFFFFFF, SkBlendMode::kSrcOver);
        canvas.insertReorderBarrier(true);

        int outset = 50;
        for (int i = 0; i < 10; i++) {
            sp<RenderNode> card =
                    createCard(outset, outset, width - (outset * 2), height - (outset * 2));
            canvas.drawRenderNode(card.get());
            cards.push_back(card);
        }

        canvas.insertReorderBarrier(false);
    }
    void doFrame(int frameNr) override {
        int curFrame = frameNr % 10;
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
                                         props.setElevation(1000);

                                         // Set 0 radius, no clipping, so shadow is easy to compute.
                                         // Slightly transparent outline
                                         // to signal contents aren't opaque (not necessary though,
                                         // as elevation is so high, no
                                         // inner content to cut out)
                                         props.mutableOutline().setRoundRect(0, 0, width, height, 0,
                                                                             0.99f);
                                         props.mutableOutline().setShouldClip(false);

                                         // don't draw anything to card's canvas - we just want the
                                         // shadow
                                     });
    }
};
