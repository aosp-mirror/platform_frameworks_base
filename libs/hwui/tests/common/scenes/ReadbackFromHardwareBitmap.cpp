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

class ReadbackFromHardware;

static TestScene::Registrar _SaveLayer(TestScene::Info{
        "readbackFromHBitmap", "Allocates hardware bitmap and readback data from it.",
        TestScene::simpleCreateScene<ReadbackFromHardware>});

class ReadbackFromHardware : public TestScene {
public:
    static sk_sp<Bitmap> createHardwareBitmap() {
        SkBitmap skBitmap;
        SkImageInfo info = SkImageInfo::Make(400, 400, kN32_SkColorType, kPremul_SkAlphaType);
        skBitmap.allocPixels(info);
        skBitmap.eraseColor(Color::Red_500);
        SkCanvas canvas(skBitmap);
        SkPaint paint;
        paint.setColor(Color::Blue_500);
        canvas.drawRect(SkRect::MakeXYWH(30, 30, 30, 150), paint);
        canvas.drawRect(SkRect::MakeXYWH(30, 30, 100, 30), paint);
        canvas.drawRect(SkRect::MakeXYWH(30, 100, 70, 30), paint);
        return Bitmap::allocateHardwareBitmap(skBitmap);
    }

    void createContent(int width, int height, Canvas& canvas) override {
        canvas.drawColor(Color::White, SkBlendMode::kSrcOver);  // background

        sk_sp<Bitmap> hardwareBitmap(createHardwareBitmap());

        SkBitmap readback;
        hardwareBitmap->getSkBitmap(&readback);

        SkBitmap canvasBitmap;
        sk_sp<Bitmap> heapBitmap(TestUtils::createBitmap(hardwareBitmap->width(),
                                                         hardwareBitmap->height(), &canvasBitmap));

        SkCanvas skCanvas(canvasBitmap);
        skCanvas.drawBitmap(readback, 0, 0);
        canvas.drawBitmap(*heapBitmap, 0, 0, nullptr);

        canvas.drawBitmap(*hardwareBitmap, 0, 500, nullptr);
    }

    void doFrame(int frameNr) override {}
};
