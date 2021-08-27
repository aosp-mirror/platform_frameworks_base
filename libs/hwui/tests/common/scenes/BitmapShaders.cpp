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

#include <SkImagePriv.h>
#include "hwui/Paint.h"
#include "TestSceneBase.h"
#include "tests/common/BitmapAllocationTestUtils.h"
#include "utils/Color.h"

class BitmapShaders;

static bool _BitmapShaders(BitmapAllocationTestUtils::registerBitmapAllocationScene<BitmapShaders>(
        "bitmapShader", "Draws bitmap shaders with repeat and mirror modes."));

class BitmapShaders : public TestScene {
public:
    explicit BitmapShaders(BitmapAllocationTestUtils::BitmapAllocator allocator)
            : TestScene(), mAllocator(allocator) {}

    sp<RenderNode> card;
    void createContent(int width, int height, Canvas& canvas) override {
        canvas.drawColor(Color::Grey_200, SkBlendMode::kSrcOver);
        sk_sp<Bitmap> hwuiBitmap =
                mAllocator(200, 200, kRGBA_8888_SkColorType, [](SkBitmap& skBitmap) {
                    skBitmap.eraseColor(Color::White);
                    SkCanvas skCanvas(skBitmap);
                    SkPaint skPaint;
                    skPaint.setColor(Color::Red_500);
                    skCanvas.drawRect(SkRect::MakeWH(100, 100), skPaint);
                    skPaint.setColor(Color::Blue_500);
                    skCanvas.drawRect(SkRect::MakeXYWH(100, 100, 100, 100), skPaint);
                });

        SkSamplingOptions sampling;
        Paint paint;
        sk_sp<SkImage> image = hwuiBitmap->makeImage();
        sk_sp<SkShader> repeatShader =
                image->makeShader(SkTileMode::kRepeat, SkTileMode::kRepeat, sampling);
        paint.setShader(std::move(repeatShader));
        canvas.drawRoundRect(0, 0, 500, 500, 50.0f, 50.0f, paint);

        sk_sp<SkShader> mirrorShader =
                image->makeShader(SkTileMode::kMirror, SkTileMode::kMirror, sampling);
        paint.setShader(std::move(mirrorShader));
        canvas.drawRoundRect(0, 600, 500, 1100, 50.0f, 50.0f, paint);
    }

    void doFrame(int frameNr) override {}

    BitmapAllocationTestUtils::BitmapAllocator mAllocator;
};
