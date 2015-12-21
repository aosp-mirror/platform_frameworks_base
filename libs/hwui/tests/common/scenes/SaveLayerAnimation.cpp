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
        canvas.drawColor(Color::White, SkXfermode::kSrcOver_Mode); // background

        card = TestUtils::createNode(0, 0, 400, 800,
                [](RenderProperties& props, TestCanvas& canvas) {
            // nested clipped saveLayers
            canvas.saveLayerAlpha(0, 0, 400, 400, 200, SaveFlags::ClipToLayer);
            canvas.drawColor(Color::Green_700, SkXfermode::kSrcOver_Mode);
            canvas.clipRect(50, 50, 350, 350, SkRegion::kIntersect_Op);
            canvas.saveLayerAlpha(100, 100, 300, 300, 128, SaveFlags::ClipToLayer);
            canvas.drawColor(Color::Blue_500, SkXfermode::kSrcOver_Mode);
            canvas.restore();
            canvas.restore();

            // single unclipped saveLayer
            canvas.save(SaveFlags::MatrixClip);
            canvas.translate(0, 400);
            canvas.saveLayerAlpha(100, 100, 300, 300, 128, SaveFlags::Flags(0)); // unclipped
            SkPaint paint;
            paint.setAntiAlias(true);
            paint.setColor(Color::Green_700);
            canvas.drawCircle(200, 200, 200, paint);
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
