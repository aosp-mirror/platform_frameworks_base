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

class SaveLayerAnimation;

static TestScene::Registrar _SaveLayer(TestScene::Info{
    "savelayer",
    "A nested pair of clipped saveLayer operations. "
    "Tests the clipped saveLayer codepath. Draws content into offscreen buffers and back again.",
    TestScene::simpleCreateScene<SaveLayerAnimation>
});

class SaveLayerAnimation : public TestScene {
public:
    sp<RenderNode> card;
    void createContent(int width, int height, TestCanvas& canvas) override {
        canvas.drawColor(0xFFFFFFFF, SkXfermode::kSrcOver_Mode); // background

        card = TestUtils::createNode(0, 0, 200, 200,
                [](RenderProperties& props, TestCanvas& canvas) {
            canvas.saveLayerAlpha(0, 0, 200, 200, 128, SkCanvas::kClipToLayer_SaveFlag);
            canvas.drawColor(0xFF00FF00, SkXfermode::kSrcOver_Mode); // outer, unclipped
            canvas.saveLayerAlpha(50, 50, 150, 150, 128, SkCanvas::kClipToLayer_SaveFlag);
            canvas.drawColor(0xFF0000FF, SkXfermode::kSrcOver_Mode); // inner, clipped
            canvas.restore();
            canvas.restore();
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
