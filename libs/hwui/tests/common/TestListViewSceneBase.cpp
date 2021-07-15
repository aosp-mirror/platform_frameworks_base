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

#include "TestListViewSceneBase.h"

#include "TestContext.h"
#include "TestUtils.h"

#include <utils/Color.h>

namespace android {
namespace uirenderer {
namespace test {

void TestListViewSceneBase::createContent(int width, int height, Canvas& canvas) {
    srand(0);
    mItemHeight = dp(60);
    mItemSpacing = dp(16);
    mItemWidth = std::min((height - mItemSpacing * 2), (int)dp(300));
    mItemLeft = (width - mItemWidth) / 2;
    int heightWithSpacing = mItemHeight + mItemSpacing;
    for (int y = 0; y < height + (heightWithSpacing - 1); y += heightWithSpacing) {
        int id = mListItems.size();
        auto setup = std::bind(&TestListViewSceneBase::createListItem, this, std::placeholders::_1,
                               std::placeholders::_2, id, mItemWidth, mItemHeight);
        auto node =
                TestUtils::createNode(mItemLeft, y, mItemLeft + mItemWidth, y + mItemHeight, setup);
        mListItems.push_back(node);
    }
    mListView = TestUtils::createNode(0, 0, width, height,
                                      [this](RenderProperties& props, Canvas& canvas) {
                                          for (size_t ci = 0; ci < mListItems.size(); ci++) {
                                              canvas.drawRenderNode(mListItems[ci].get());
                                          }
                                      });

    canvas.drawColor(Color::Grey_500, SkBlendMode::kSrcOver);
    canvas.drawRenderNode(mListView.get());
}

void TestListViewSceneBase::doFrame(int frameNr) {
    int scrollPx = dp(frameNr) * 3;
    int itemIndexOffset = scrollPx / (mItemSpacing + mItemHeight);
    int pxOffset = -(scrollPx % (mItemSpacing + mItemHeight));

    std::unique_ptr<Canvas> canvas(Canvas::create_recording_canvas(
            mListView->stagingProperties().getWidth(), mListView->stagingProperties().getHeight(),
            mListView.get()));
    for (size_t ci = 0; ci < mListItems.size(); ci++) {
        // update item position
        auto listItem = mListItems[(ci + itemIndexOffset) % mListItems.size()];
        int top = ((int)ci) * (mItemSpacing + mItemHeight) + pxOffset;
        listItem->mutateStagingProperties().setLeftTopRightBottom(
                mItemLeft, top, mItemLeft + mItemWidth, top + mItemHeight);
        listItem->setPropertyFieldsDirty(RenderNode::X | RenderNode::Y);

        // draw it to parent DisplayList
        canvas->drawRenderNode(mListItems[ci].get());
    }
    canvas->finishRecording(mListView.get());
}

}  // namespace test
}  // namespace uirenderer
}  // namespace android
