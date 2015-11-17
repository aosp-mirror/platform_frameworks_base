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

class RecentsAnimation;

static Benchmark _Recents(BenchmarkInfo{
    "recents",
    "A recents-like scrolling list of textures. "
    "Consists of updating a texture every frame",
    simpleCreateScene<RecentsAnimation>
});

class RecentsAnimation : public TestScene {
public:
    void createContent(int width, int height, TestCanvas& renderer) override {
        static SkColor COLORS[] = {
                0xFFF44336,
                0xFF9C27B0,
                0xFF2196F3,
                0xFF4CAF50,
        };

        thumbnailSize = std::min(std::min(width, height) / 2, 720);
        int cardsize = std::min(width, height) - dp(64);

        renderer.drawColor(0xFFFFFFFF, SkXfermode::kSrcOver_Mode);
        renderer.insertReorderBarrier(true);

        int x = dp(32);
        for (int i = 0; i < 4; i++) {
            int y = (height / 4) * i;
            SkBitmap thumb = TestUtils::createSkBitmap(thumbnailSize, thumbnailSize);
            thumb.eraseColor(COLORS[i]);
            sp<RenderNode> card = createCard(x, y, cardsize, cardsize, thumb);
            card->mutateStagingProperties().setElevation(i * dp(8));
            renderer.drawRenderNode(card.get());
            mThumbnail = thumb;
            mCards.push_back(card);
        }

        renderer.insertReorderBarrier(false);
    }

    void doFrame(int frameNr) override {
        int curFrame = frameNr % 150;
        for (size_t ci = 0; ci < mCards.size(); ci++) {
            mCards[ci]->mutateStagingProperties().setTranslationY(curFrame);
            mCards[ci]->setPropertyFieldsDirty(RenderNode::Y);
        }
        mThumbnail.eraseColor(TestUtils::interpolateColor(
                curFrame / 150.0f, 0xFF4CAF50, 0xFFFF5722));
    }

private:
    sp<RenderNode> createCard(int x, int y, int width, int height,
            const SkBitmap& thumb) {
        return TestUtils::createNode(x, y, x + width, y + height,
                [&thumb, width, height](RenderProperties& props, TestCanvas& canvas) {
            props.setElevation(dp(16));
            props.mutableOutline().setRoundRect(0, 0, width, height, dp(10), 1);
            props.mutableOutline().setShouldClip(true);

            canvas.drawColor(0xFFEEEEEE, SkXfermode::kSrcOver_Mode);
            canvas.drawBitmap(thumb, 0, 0, thumb.width(), thumb.height(),
                    0, 0, width, height, nullptr);
        });
    }

    SkBitmap mThumbnail;
    std::vector< sp<RenderNode> > mCards;
    int thumbnailSize;
};
