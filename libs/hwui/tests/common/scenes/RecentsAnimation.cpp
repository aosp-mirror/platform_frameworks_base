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

class RecentsAnimation;

static TestScene::Registrar _Recents(TestScene::Info{
        "recents",
        "A recents-like scrolling list of textures. "
        "Consists of updating a texture every frame",
        TestScene::simpleCreateScene<RecentsAnimation>});

class RecentsAnimation : public TestScene {
public:
    void createContent(int width, int height, Canvas& renderer) override {
        static SkColor COLORS[] = {
                Color::Red_500, Color::Purple_500, Color::Blue_500, Color::Green_500,
        };

        thumbnailSize = std::min(std::min(width, height) / 2, 720);
        int cardsize = std::min(width, height) - dp(64);

        renderer.drawColor(Color::White, SkBlendMode::kSrcOver);
        renderer.enableZ(true);

        int x = dp(32);
        for (int i = 0; i < 4; i++) {
            int y = (height / 4) * i;
            SkBitmap bitmap;
            sk_sp<Bitmap> thumb(TestUtils::createBitmap(thumbnailSize, thumbnailSize, &bitmap));

            bitmap.eraseColor(COLORS[i]);
            sp<RenderNode> card = createCard(x, y, cardsize, cardsize, *thumb);
            card->mutateStagingProperties().setElevation(i * dp(8));
            renderer.drawRenderNode(card.get());
            mThumbnail = bitmap;
            mCards.push_back(card);
        }

        renderer.enableZ(false);
    }

    void doFrame(int frameNr) override {
        int curFrame = frameNr % 150;
        for (size_t ci = 0; ci < mCards.size(); ci++) {
            mCards[ci]->mutateStagingProperties().setTranslationY(curFrame);
            mCards[ci]->setPropertyFieldsDirty(RenderNode::Y);
        }
        mThumbnail.eraseColor(TestUtils::interpolateColor(curFrame / 150.0f, Color::Green_500,
                                                          Color::DeepOrange_500));
    }

private:
    sp<RenderNode> createCard(int x, int y, int width, int height, Bitmap& thumb) {
        return TestUtils::createNode(
                x, y, x + width, y + height,
                [&thumb, width, height](RenderProperties& props, Canvas& canvas) {
                    props.setElevation(dp(16));
                    props.mutableOutline().setRoundRect(0, 0, width, height, dp(10), 1);
                    props.mutableOutline().setShouldClip(true);

                    canvas.drawColor(Color::Grey_200, SkBlendMode::kSrcOver);
                    canvas.drawBitmap(thumb, 0, 0, thumb.width(), thumb.height(), 0, 0, width,
                                      height, nullptr);
                });
    }

    SkBitmap mThumbnail;
    std::vector<sp<RenderNode> > mCards;
    int thumbnailSize;
};
