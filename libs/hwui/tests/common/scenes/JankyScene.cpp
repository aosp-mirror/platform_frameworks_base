/*
 * Copyright (C) 2018 The Android Open Source Project
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

#include <unistd.h>

class JankyScene;

static TestScene::Registrar _JankyScene(TestScene::Info{
        "janky",
        "A scene that intentionally janks just enough to stay in "
        "triple buffering.",
        TestScene::simpleCreateScene<JankyScene>});

class JankyScene : public TestScene {
public:
    sp<RenderNode> card;

    void createContent(int width, int height, Canvas& canvas) override {
        card = TestUtils::createNode(0, 0, 200, 200, [](RenderProperties& props, Canvas& canvas) {
            canvas.drawColor(0xFF0000FF, SkBlendMode::kSrcOver);
        });
        canvas.drawColor(0xFFFFFFFF, SkBlendMode::kSrcOver);  // background
        canvas.drawRenderNode(card.get());
    }

    void doFrame(int frameNr) override {
        int curFrame = frameNr % 150;
        if (curFrame & 1) {
            usleep(15000);
        }
        // we animate left and top coordinates, which in turn animates width and
        // height (bottom/right coordinates are fixed)
        card->mutateStagingProperties().setLeftTop(curFrame, curFrame);
        card->setPropertyFieldsDirty(RenderNode::X | RenderNode::Y);
    }
};