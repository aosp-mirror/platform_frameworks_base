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
#include "hwui/Paint.h"

#include <SkBitmap.h>
#include <SkBlendMode.h>
#include <SkFont.h>

class MagnifierAnimation;

using Rect = android::uirenderer::Rect;

static TestScene::Registrar _Magnifier(TestScene::Info{
        "magnifier", "A sample magnifier using Readback",
        TestScene::simpleCreateScene<MagnifierAnimation>});

class BlockingCopyRequest : public CopyRequest {
    sk_sp<Bitmap> mDestination;
    std::mutex mLock;
    std::condition_variable mCondVar;
    CopyResult mResult;

public:
    BlockingCopyRequest(::Rect rect, sk_sp<Bitmap> bitmap)
            : CopyRequest(rect), mDestination(bitmap) {}

    virtual SkBitmap getDestinationBitmap(int srcWidth, int srcHeight) override {
        SkBitmap bitmap;
        mDestination->getSkBitmap(&bitmap);
        return bitmap;
    }

    virtual void onCopyFinished(CopyResult result) override {
        std::unique_lock _lock{mLock};
        mResult = result;
        mCondVar.notify_all();
    }

    CopyResult waitForResult() {
        std::unique_lock _lock{mLock};
        mCondVar.wait(_lock);
        return mResult;
    }
};

class MagnifierAnimation : public TestScene {
public:
    sp<RenderNode> card;
    sp<RenderNode> zoomImageView;
    sk_sp<Bitmap> magnifier;
    std::shared_ptr<BlockingCopyRequest> copyRequest;

    void createContent(int width, int height, Canvas& canvas) override {
        magnifier = TestUtils::createBitmap(200, 100);
        setupCopyRequest();
        SkBitmap temp;
        magnifier->getSkBitmap(&temp);
        temp.eraseColor(Color::White);
        canvas.drawColor(Color::White, SkBlendMode::kSrcOver);
        card = TestUtils::createNode(
                0, 0, width, height, [&](RenderProperties& props, Canvas& canvas) {
                    Paint paint;
                    paint.setAntiAlias(true);
                    paint.getSkFont().setSize(50);

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
        canvas.enableZ(true);
        canvas.drawRenderNode(zoomImageView.get());
        canvas.enableZ(false);
    }

    void setupCopyRequest() {
        constexpr int x = 90;
        constexpr int y = 325;
        copyRequest = std::make_shared<BlockingCopyRequest>(
                ::Rect(x, y, x + magnifier->width(), y + magnifier->height()), magnifier);
    }

    void doFrame(int frameNr) override {
        int curFrame = frameNr % 150;
        card->mutateStagingProperties().setTranslationX(curFrame);
        card->setPropertyFieldsDirty(RenderNode::X | RenderNode::Y);
        if (renderTarget) {
            RenderProxy::copySurfaceInto(renderTarget.get(), copyRequest);
            copyRequest->waitForResult();
        }
    }
};
