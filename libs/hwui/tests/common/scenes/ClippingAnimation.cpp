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

class ClippingAnimation;

static TestScene::Registrar _RectGrid(TestScene::Info{
    "clip",
    "Complex clip cases"
    "Low CPU/GPU load.",
    TestScene::simpleCreateScene<ClippingAnimation>
});

class ClippingAnimation : public TestScene {
public:
    sp<RenderNode> card;
    void createContent(int width, int height, TestCanvas& canvas) override {
        canvas.drawColor(Color::White, SkXfermode::kSrcOver_Mode);
        card = TestUtils::createNode(0, 0, 200, 400,
                [](RenderProperties& props, TestCanvas& canvas) {
            canvas.save(SaveFlags::MatrixClip);
            {
                canvas.clipRect(0, 0, 200, 200, SkRegion::kIntersect_Op);
                canvas.translate(100, 100);
                canvas.rotate(45);
                canvas.translate(-100, -100);
                canvas.clipRect(0, 0, 200, 200, SkRegion::kIntersect_Op);
                canvas.drawColor(Color::Blue_500, SkXfermode::kSrcOver_Mode);
            }
            canvas.restore();

            canvas.save(SaveFlags::MatrixClip);
            {
                SkPath clipCircle;
                clipCircle.addCircle(100, 300, 100);
                canvas.clipPath(&clipCircle, SkRegion::kIntersect_Op);
                canvas.drawColor(Color::Red_500, SkXfermode::kSrcOver_Mode);
            }
            canvas.restore();

            // put on a layer, to test stencil attachment
            props.mutateLayerProperties().setType(LayerType::RenderLayer);
            props.setAlpha(0.9f);
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
