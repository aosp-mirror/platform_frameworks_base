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

class HwBitmap565;

static TestScene::Registrar _HwBitmap565(TestScene::Info{
        "hwBitmap565", "Draws composite shader with hardware bitmap",
        TestScene::simpleCreateScene<HwBitmap565>});

class HwBitmap565 : public TestScene {
public:
    sp<RenderNode> card;
    void createContent(int width, int height, Canvas& canvas) override {
        canvas.drawColor(Color::Grey_200, SkBlendMode::kSrcOver);

        sk_sp<Bitmap> hardwareBitmap = BitmapAllocationTestUtils::allocateHardwareBitmap(
                200, 200, kRGB_565_SkColorType, [](SkBitmap& skBitmap) {
                    skBitmap.eraseColor(Color::White);
                    SkCanvas skCanvas(skBitmap);
                    SkPaint skPaint;
                    skPaint.setColor(Color::Red_500);
                    skCanvas.drawRect(SkRect::MakeWH(100, 100), skPaint);
                    skPaint.setColor(Color::Blue_500);
                    skCanvas.drawRect(SkRect::MakeXYWH(100, 100, 100, 100), skPaint);
                });
        canvas.drawBitmap(*hardwareBitmap, 10.0f, 10.0f, nullptr);
    }

    void doFrame(int frameNr) override {}
};