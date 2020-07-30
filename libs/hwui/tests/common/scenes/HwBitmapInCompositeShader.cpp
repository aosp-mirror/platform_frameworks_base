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
#include "utils/Color.h"

#include <SkGradientShader.h>
#include <SkImagePriv.h>
#include <ui/PixelFormat.h>
#include <shader/BitmapShader.h>
#include <shader/LinearGradientShader.h>
#include <shader/RadialGradientShader.h>
#include <shader/ComposeShader.h>

class HwBitmapInCompositeShader;

static TestScene::Registrar _HwBitmapInCompositeShader(TestScene::Info{
        "hwbitmapcompositeshader", "Draws composite shader with hardware bitmap",
        TestScene::simpleCreateScene<HwBitmapInCompositeShader>});

class HwBitmapInCompositeShader : public TestScene {
public:
    sp<RenderNode> card;
    void createContent(int width, int height, Canvas& canvas) override {
        canvas.drawColor(Color::Red_500, SkBlendMode::kSrcOver);

        uint32_t usage = GraphicBuffer::USAGE_HW_TEXTURE | GraphicBuffer::USAGE_SW_READ_NEVER |
                         GRALLOC_USAGE_SW_WRITE_RARELY;

        sp<GraphicBuffer> buffer = new GraphicBuffer(400, 200, PIXEL_FORMAT_RGBA_8888, usage);

        unsigned char* pixels = nullptr;
        buffer->lock(GraphicBuffer::USAGE_SW_WRITE_RARELY, ((void**)&pixels));
        size_t size =
                bytesPerPixel(buffer->getPixelFormat()) * buffer->getStride() * buffer->getHeight();
        memset(pixels, 0, size);
        for (int i = 0; i < 6000; i++) {
            pixels[4000 + 4 * i + 0] = 255;
            pixels[4000 + 4 * i + 1] = 255;
            pixels[4000 + 4 * i + 2] = 0;
            pixels[4000 + 4 * i + 3] = 255;
        }
        buffer->unlock();

        sk_sp<BitmapShader> bitmapShader = sk_make_sp<BitmapShader>(
                Bitmap::createFrom(
                        buffer->toAHardwareBuffer(),
                        SkColorSpace::MakeSRGB()
                )->makeImage(),
                SkTileMode::kClamp,
                SkTileMode::kClamp,
                nullptr
        );

        SkPoint center;
        center.set(50, 50);

        std::vector<SkColor4f> vColors(2);
        vColors[0] = SkColors::kBlack;
        vColors[1] = SkColors::kWhite;

        sk_sp<RadialGradientShader> radialShader = sk_make_sp<RadialGradientShader>(
                center,
                50,
                vColors,
                SkColorSpace::MakeSRGB(),
                nullptr,
                SkTileMode::kRepeat,
                0,
                nullptr
            );

        sk_sp<ComposeShader> compositeShader = sk_make_sp<ComposeShader>(
                    *bitmapShader.get(),
                    *radialShader.get(),
                    SkBlendMode::kDstATop,
                    nullptr
                );

        Paint paint;
        paint.setShader(std::move(compositeShader));
        canvas.drawRoundRect(0, 0, 400, 200, 10.0f, 10.0f, paint);
    }

    void doFrame(int frameNr) override {}

    sk_sp<SkShader> createBitmapShader(Bitmap& bitmap) {
        sk_sp<SkImage> image = bitmap.makeImage();
        return image->makeShader();
    }
};
