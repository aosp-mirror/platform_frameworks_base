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

#include <SkColorSpace.h>
#include <SkGradientShader.h>
#include <SkImagePriv.h>
#include <ui/PixelFormat.h>

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
        sk_sp<Bitmap> hardwareBitmap(Bitmap::createFrom(buffer->toAHardwareBuffer(),
                                                        SkColorSpace::MakeSRGB()));
        sk_sp<SkShader> hardwareShader(createBitmapShader(*hardwareBitmap));

        SkPoint center;
        center.set(50, 50);
        SkColor colors[2];
        colors[0] = Color::Black;
        colors[1] = Color::White;
        sk_sp<SkShader> gradientShader = SkGradientShader::MakeRadial(
                center, 50, colors, nullptr, 2, SkTileMode::kRepeat);

        sk_sp<SkShader> compositeShader(
                SkShaders::Blend(SkBlendMode::kDstATop, hardwareShader, gradientShader));

        Paint paint;
        paint.setShader(std::move(compositeShader));
        canvas.drawRoundRect(0, 0, 400, 200, 10.0f, 10.0f, paint);
    }

    void doFrame(int frameNr) override {}

    sk_sp<SkShader> createBitmapShader(Bitmap& bitmap) {
        sk_sp<SkImage> image = bitmap.makeImage();
        return image->makeShader(SkSamplingOptions());
    }
};
