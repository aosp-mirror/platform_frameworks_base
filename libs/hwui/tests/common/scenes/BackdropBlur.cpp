/*
 * Copyright (C) 2024 The Android Open Source Project
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

#include <SkBlendMode.h>

#include "SkImageFilter.h"
#include "SkImageFilters.h"
#include "TestSceneBase.h"
#include "utils/Blur.h"

class BackdropBlurAnimation : public TestScene {
private:
    std::unique_ptr<TestScene> listView;

public:
    explicit BackdropBlurAnimation(const TestScene::Options& opts) {
        listView.reset(TestScene::testMap()["listview"].createScene(opts));
    }

    void createContent(int width, int height, Canvas& canvas) override {
        sp<RenderNode> list = TestUtils::createNode(
                0, 0, width, height,
                [this, width, height](RenderProperties& props, Canvas& canvas) {
                    props.setClipToBounds(false);
                    listView->createContent(width, height, canvas);
                });

        canvas.drawRenderNode(list.get());

        int x = width / 8;
        int y = height / 4;
        sp<RenderNode> blurNode = TestUtils::createNode(
                x, y, width - x, height - y, [](RenderProperties& props, Canvas& canvas) {
                    props.mutableOutline().setRoundRect(0, 0, props.getWidth(), props.getHeight(),
                                                        dp(16), 1);
                    props.mutableOutline().setShouldClip(true);
                    sk_sp<SkImageFilter> blurFilter = SkImageFilters::Blur(
                            Blur::convertRadiusToSigma(dp(8)), Blur::convertRadiusToSigma(dp(8)),
                            SkTileMode::kClamp, nullptr, nullptr);
                    props.mutateLayerProperties().setBackdropImageFilter(blurFilter.get());
                    canvas.drawColor(0x33000000, SkBlendMode::kSrcOver);
                });

        canvas.drawRenderNode(blurNode.get());
    }

    void doFrame(int frameNr) override { listView->doFrame(frameNr); }
};

static TestScene::Registrar _BackdropBlur(TestScene::Info{
        "backdropblur", "A rounded rect that does a blur-behind of a sky animation.",
        [](const TestScene::Options& opts) -> test::TestScene* {
            return new BackdropBlurAnimation(opts);
        }});
