/*
 * Copyright (C) 2016 The Android Open Source Project
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

#include <vector>

#include "TestSceneBase.h"

class PathClippingAnimation : public TestScene {
public:
    int mSpacing, mSize;
    bool mClip, mAnimateClip;
    int mMaxCards;
    std::vector<sp<RenderNode> > cards;

    PathClippingAnimation(int spacing, int size, bool clip, bool animateClip, int maxCards)
            : mSpacing(spacing)
            , mSize(size)
            , mClip(clip)
            , mAnimateClip(animateClip)
            , mMaxCards(maxCards) {}

    PathClippingAnimation(int spacing, int size, bool clip, bool animateClip)
            : PathClippingAnimation(spacing, size, clip, animateClip, INT_MAX) {}

    void createContent(int width, int height, Canvas& canvas) override {
        canvas.drawColor(0xFFFFFFFF, SkBlendMode::kSrcOver);
        canvas.enableZ(true);
        int ci = 0;
        int numCards = 0;

        for (int x = 0; x < width; x += mSpacing) {
            for (int y = 0; y < height; y += mSpacing) {
                auto color = BrightColors[ci++ % BrightColorsCount];
                auto card = TestUtils::createNode(
                        x, y, x + mSize, y + mSize, [&](RenderProperties& props, Canvas& canvas) {
                            canvas.drawColor(color, SkBlendMode::kSrcOver);
                            if (mClip) {
                                // Create circular path that rounds around the inside of all
                                // four corners of the given square defined by mSize*mSize
                                SkPath path = setPath(mSize);
                                props.mutableOutline().setPath(&path, 1);
                                props.mutableOutline().setShouldClip(true);
                            }
                        });
                canvas.drawRenderNode(card.get());
                cards.push_back(card);
                ++numCards;
                if (numCards >= mMaxCards) {
                    break;
                }
            }
            if (numCards >= mMaxCards) {
                break;
            }
        }

        canvas.enableZ(false);
    }

    SkPath setPath(int size) {
        SkPath path;
        path.moveTo(0, size / 2);
        path.cubicTo(0, size * .75, size * .25, size, size / 2, size);
        path.cubicTo(size * .75, size, size, size * .75, size, size / 2);
        path.cubicTo(size, size * .25, size * .75, 0, size / 2, 0);
        path.cubicTo(size / 4, 0, 0, size / 4, 0, size / 2);
        return path;
    }

    void doFrame(int frameNr) override {
        int curFrame = frameNr % 50;
        if (curFrame > 25) curFrame = 50 - curFrame;
        for (auto& card : cards) {
            if (mAnimateClip) {
                SkPath path = setPath(mSize - curFrame);
                card->mutateStagingProperties().mutableOutline().setPath(&path, 1);
            }
            card->mutateStagingProperties().setTranslationX(curFrame);
            card->mutateStagingProperties().setTranslationY(curFrame);
            card->setPropertyFieldsDirty(RenderNode::X | RenderNode::Y | RenderNode::DISPLAY_LIST);
        }
    }
};

static TestScene::Registrar _PathClippingUnclipped(TestScene::Info{
        "pathClipping-unclipped", "Multiple RenderNodes, unclipped.",
        [](const TestScene::Options&) -> test::TestScene* {
            return new PathClippingAnimation(dp(100), dp(80), false, false);
        }});

static TestScene::Registrar _PathClippingUnclippedSingle(TestScene::Info{
        "pathClipping-unclippedsingle", "A single RenderNode, unclipped.",
        [](const TestScene::Options&) -> test::TestScene* {
            return new PathClippingAnimation(dp(100), dp(80), false, false, 1);
        }});

static TestScene::Registrar _PathClippingUnclippedSingleLarge(TestScene::Info{
        "pathClipping-unclippedsinglelarge", "A single large RenderNode, unclipped.",
        [](const TestScene::Options&) -> test::TestScene* {
            return new PathClippingAnimation(dp(100), dp(350), false, false, 1);
        }});

static TestScene::Registrar _PathClippingClipped80(TestScene::Info{
        "pathClipping-clipped80", "Multiple RenderNodes, clipped by paths.",
        [](const TestScene::Options&) -> test::TestScene* {
            return new PathClippingAnimation(dp(100), dp(80), true, false);
        }});

static TestScene::Registrar _PathClippingClippedSingle(TestScene::Info{
        "pathClipping-clippedsingle", "A single RenderNode, clipped by a path.",
        [](const TestScene::Options&) -> test::TestScene* {
            return new PathClippingAnimation(dp(100), dp(80), true, false, 1);
        }});

static TestScene::Registrar _PathClippingClippedSingleLarge(TestScene::Info{
        "pathClipping-clippedsinglelarge", "A single large RenderNode, clipped by a path.",
        [](const TestScene::Options&) -> test::TestScene* {
            return new PathClippingAnimation(dp(100), dp(350), true, false, 1);
        }});

static TestScene::Registrar _PathClippingAnimated(TestScene::Info{
        "pathClipping-animated",
        "Multiple RenderNodes, clipped by paths which are being altered every frame.",
        [](const TestScene::Options&) -> test::TestScene* {
            return new PathClippingAnimation(dp(100), dp(80), true, true);
        }});

static TestScene::Registrar _PathClippingAnimatedSingle(TestScene::Info{
        "pathClipping-animatedsingle",
        "A single RenderNode, clipped by a path which is being altered every frame.",
        [](const TestScene::Options&) -> test::TestScene* {
            return new PathClippingAnimation(dp(100), dp(80), true, true, 1);
        }});

static TestScene::Registrar _PathClippingAnimatedSingleLarge(TestScene::Info{
        "pathClipping-animatedsinglelarge",
        "A single large RenderNode, clipped by a path which is being altered every frame.",
        [](const TestScene::Options&) -> test::TestScene* {
            return new PathClippingAnimation(dp(100), dp(350), true, true, 1);
        }});
