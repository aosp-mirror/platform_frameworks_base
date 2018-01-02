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
#include "renderthread/RenderProxy.h"
#include "utils/Color.h"

class MagnifierAnimation;

static TestScene::Registrar _Magnifier(TestScene::Info{
        "magnifier", "A sample magnifier using Readback",
        TestScene::simpleCreateScene<MagnifierAnimation>});

class MagnifierAnimation : public TestScene {
public:
    sp<RenderNode> card;
    sp<RenderNode> zoomImageView;

    void createContent(int width, int height, Canvas& canvas) override {
        magnifier = TestUtils::createBitmap(200, 100);
        SkBitmap temp;
        magnifier->getSkBitmap(&temp);
        temp.eraseColor(Color::White);
        canvas.drawColor(Color::White, SkBlendMode::kSrcOver);
        card = TestUtils::createNode(
                0, 0, width, height, [&](RenderProperties& props, Canvas& canvas) {
                    SkPaint paint;
                    paint.setAntiAlias(true);
                    paint.setTextSize(50);

                    paint.setColor(Color::Black);
                    TestUtils::drawUtf8ToCanvas(&canvas, "Test string", paint, 10, 400);
                });
        canvas.drawRenderNode(card.get());
        zoomImageView = TestUtils::createNode(
                100, 100, 500, 300, [&](RenderProperties& props, Canvas& canvas) {
                    props.setElevation(dp(16));
                    props.mutableOutline().setRoundRect(0, 0, props.getWidth(), props.getHeight(),
                                                        dp(6), 1);
                    props.mutableOutline().setShouldClip(true);
                    canvas.drawBitmap(*magnifier, 0.0f, 0.0f, (float)magnifier->width(),
                                      (float)magnifier->height(), 0, 0, (float)props.getWidth(),
                                      (float)props.getHeight(), nullptr);
                });
        canvas.insertReorderBarrier(true);
        canvas.drawRenderNode(zoomImageView.get());
        canvas.insertReorderBarrier(false);
    }

    void doFrame(int frameNr) override {
        int curFrame = frameNr % 150;
        card->mutateStagingProperties().setTranslationX(curFrame);
        card->setPropertyFieldsDirty(RenderNode::X | RenderNode::Y);
        if (renderTarget) {
            SkBitmap temp;
            magnifier->getSkBitmap(&temp);
            constexpr int x = 90;
            constexpr int y = 325;
            RenderProxy::copySurfaceInto(renderTarget, x, y, x + magnifier->width(),
                                         y + magnifier->height(), &temp);
        }
    }

    sk_sp<Bitmap> magnifier;
};
