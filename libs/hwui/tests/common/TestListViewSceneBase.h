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
#pragma once

#include <RenderNode.h>
#include <RenderProperties.h>
#include "TestScene.h"

namespace android {
namespace uirenderer {
namespace test {

class TestListViewSceneBase : public TestScene {
public:
    virtual void createListItem(RenderProperties& props, Canvas& canvas, int id, int itemWidth,
                                int itemHeight) = 0;

private:
    int mItemHeight;
    int mItemSpacing;
    int mItemWidth;
    int mItemLeft;
    sp<RenderNode> mListView;
    std::vector<sp<RenderNode> > mListItems;

    void createContent(int width, int height, Canvas& canvas) override;
    void doFrame(int frameNr) override;
};

}  // namespace test
}  // namespace uirenderer
}  // namespace android
