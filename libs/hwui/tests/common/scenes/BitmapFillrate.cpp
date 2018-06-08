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

#include "TestSceneBase.h"
#include "tests/common/BitmapAllocationTestUtils.h"
#include "utils/Color.h"

#include <SkBitmap.h>

using namespace android;
using namespace android::uirenderer;

class BitmapFillrate;

static bool _BitmapFillrate(
        BitmapAllocationTestUtils::registerBitmapAllocationScene<BitmapFillrate>(
                "bitmapFillrate", "Draws multiple large half transparent bitmaps."));

class BitmapFillrate : public TestScene {
public:
    BitmapFillrate(BitmapAllocationTestUtils::BitmapAllocator allocator)
            : TestScene(), mAllocator(allocator) {}

    void createContent(int width, int height, Canvas& canvas) override {
        canvas.drawColor(Color::White, SkBlendMode::kSrcOver);
        createNode(canvas, 0x909C27B0, 0, 0, width, height);
        createNode(canvas, 0xA0CDDC39, width / 3, height / 3, width, height);
        createNode(canvas, 0x90009688, width / 3, 0, width, height);
        createNode(canvas, 0xA0FF5722, 0, height / 3, width, height);
        createNode(canvas, 0x9000796B, width / 6, height / 6, width, height);
        createNode(canvas, 0xA0FFC107, width / 6, 0, width, height);
    }

    void doFrame(int frameNr) override {
        for (size_t ci = 0; ci < mNodes.size(); ci++) {
            mNodes[ci]->mutateStagingProperties().setTranslationX(frameNr % 200);
            mNodes[ci]->mutateStagingProperties().setTranslationY(frameNr % 200);
            mNodes[ci]->setPropertyFieldsDirty(RenderNode::X | RenderNode::Y);
        }
    }

private:
    void createNode(Canvas& canvas, SkColor color, int left, int top, int width, int height) {
        int itemWidth = 2 * width / 3;
        int itemHeight = 2 * height / 3;
        auto card = TestUtils::createNode(
                left, top, left + itemWidth, top + itemHeight,
                [this, itemWidth, itemHeight, color](RenderProperties& props, Canvas& canvas) {
                    sk_sp<Bitmap> bitmap =
                            mAllocator(itemWidth, itemHeight, kRGBA_8888_SkColorType,
                                       [color](SkBitmap& skBitmap) { skBitmap.eraseColor(color); });
                    canvas.drawBitmap(*bitmap, 0, 0, nullptr);
                });
        canvas.drawRenderNode(card.get());
        mNodes.push_back(card);
    }

    BitmapAllocationTestUtils::BitmapAllocator mAllocator;
    std::vector<sp<RenderNode> > mNodes;
};