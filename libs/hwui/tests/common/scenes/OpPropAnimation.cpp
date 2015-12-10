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
#include "utils/Color.h"

class OpPropAnimation;

static TestScene::Registrar _Shapes(TestScene::Info{
    "opprops",
    "A minimal demonstration of CanvasProperty drawing operations.",
    TestScene::simpleCreateScene<OpPropAnimation>
});

class OpPropAnimation : public TestScene {
public:
    sp<CanvasPropertyPaint> mPaint = new CanvasPropertyPaint(SkPaint());

    sp<CanvasPropertyPrimitive> mRoundRectLeft = new CanvasPropertyPrimitive(0);
    sp<CanvasPropertyPrimitive> mRoundRectTop = new CanvasPropertyPrimitive(0);
    sp<CanvasPropertyPrimitive> mRoundRectRight = new CanvasPropertyPrimitive(0);
    sp<CanvasPropertyPrimitive> mRoundRectBottom = new CanvasPropertyPrimitive(0);
    sp<CanvasPropertyPrimitive> mRoundRectRx = new CanvasPropertyPrimitive(0);
    sp<CanvasPropertyPrimitive> mRoundRectRy = new CanvasPropertyPrimitive(0);

    sp<CanvasPropertyPrimitive> mCircleX = new CanvasPropertyPrimitive(0);
    sp<CanvasPropertyPrimitive> mCircleY = new CanvasPropertyPrimitive(0);
    sp<CanvasPropertyPrimitive> mCircleRadius = new CanvasPropertyPrimitive(0);

    sp<RenderNode> content;
    void createContent(int width, int height, TestCanvas& canvas) override {
        content = TestUtils::createNode(0, 0, width, height,
                [this, width, height](RenderProperties& props, TestCanvas& canvas) {
            mPaint->value.setAntiAlias(true);
            mPaint->value.setColor(Color::Blue_500);

            mRoundRectRight->value = width / 2;
            mRoundRectBottom->value = height / 2;

            mCircleX->value = width * 0.75;
            mCircleY->value = height * 0.75;

            canvas.drawColor(Color::White, SkXfermode::Mode::kSrcOver_Mode);
            canvas.drawRoundRect(mRoundRectLeft.get(), mRoundRectTop.get(),
                    mRoundRectRight.get(), mRoundRectBottom.get(),
                    mRoundRectRx.get(), mRoundRectRy.get(), mPaint.get());
            canvas.drawCircle(mCircleX.get(), mCircleY.get(), mCircleRadius.get(), mPaint.get());
        });
        canvas.drawRenderNode(content.get());
    }

    void doFrame(int frameNr) override {
        float value = (abs((frameNr % 200) - 100)) / 100.0f;
        mRoundRectRx->value = dp(10) + value * dp(40);
        mRoundRectRy->value = dp(10) + value * dp(80);
        mCircleRadius->value = value * dp(200);
        content->setPropertyFieldsDirty(RenderNode::GENERIC);
    }
};
