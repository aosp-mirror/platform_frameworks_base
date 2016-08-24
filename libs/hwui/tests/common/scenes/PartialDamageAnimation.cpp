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

class PartialDamageAnimation;

static TestScene::Registrar _PartialDamage(TestScene::Info{
    "partialdamage",
    "Tests the partial invalidation path. Draws a grid of rects and animates 1 "
    "of them, should be low CPU & GPU load if EGL_EXT_buffer_age or "
    "EGL_KHR_partial_update is supported by the device & are enabled in hwui.",
    TestScene::simpleCreateScene<PartialDamageAnimation>
});

class PartialDamageAnimation : public TestScene {
public:
    std::vector< sp<RenderNode> > cards;
    void createContent(int width, int height, TestCanvas& canvas) override {
        static SkColor COLORS[] = {
                0xFFF44336,
                0xFF9C27B0,
                0xFF2196F3,
                0xFF4CAF50,
        };

        canvas.drawColor(0xFFFFFFFF, SkXfermode::kSrcOver_Mode);

        for (int x = dp(16); x < (width - dp(116)); x += dp(116)) {
            for (int y = dp(16); y < (height - dp(116)); y += dp(116)) {
                SkColor color = COLORS[static_cast<int>((y / dp(116))) % 4];
                sp<RenderNode> card = TestUtils::createNode(x, y,
                        x + dp(100), y + dp(100),
                        [color](RenderProperties& props, TestCanvas& canvas) {
                    canvas.drawColor(color, SkXfermode::kSrcOver_Mode);
                });
                canvas.drawRenderNode(card.get());
                cards.push_back(card);
            }
        }
    }
    void doFrame(int frameNr) override {
        int curFrame = frameNr % 150;
        cards[0]->mutateStagingProperties().setTranslationX(curFrame);
        cards[0]->mutateStagingProperties().setTranslationY(curFrame);
        cards[0]->setPropertyFieldsDirty(RenderNode::X | RenderNode::Y);

        TestUtils::recordNode(*cards[0], [curFrame](TestCanvas& canvas) {
            SkColor color = TestUtils::interpolateColor(
                    curFrame / 150.0f, 0xFFF44336, 0xFFF8BBD0);
            canvas.drawColor(color, SkXfermode::kSrcOver_Mode);
        });
    }
};
