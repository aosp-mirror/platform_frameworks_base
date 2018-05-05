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

#include "tests/common/TestUtils.h"

#include <SkBlurDrawLooper.h>
#include <SkCanvasStateUtils.h>
#include <SkPicture.h>
#include <SkPictureRecorder.h>
#include <gtest/gtest.h>

using namespace android;
using namespace android::uirenderer;

TEST(SkiaCanvas, drawShadowLayer) {
    auto surface = SkSurface::MakeRasterN32Premul(10, 10);
    SkiaCanvas canvas(surface->getCanvas());

    // clear to white
    canvas.drawColor(SK_ColorWHITE, SkBlendMode::kSrc);

    SkPaint paint;
    // it is transparent to ensure that we still draw the rect since it has a looper
    paint.setColor(SK_ColorTRANSPARENT);
    // this is how view's shadow layers are implemented
    paint.setLooper(SkBlurDrawLooper::Make(0xF0000000, 6.0f, 0, 10));
    canvas.drawRect(3, 3, 7, 7, paint);

    ASSERT_EQ(TestUtils::getColor(surface, 0, 0), SK_ColorWHITE);
    ASSERT_NE(TestUtils::getColor(surface, 5, 5), SK_ColorWHITE);
}

TEST(SkiaCanvas, colorSpaceXform) {
    sk_sp<SkColorSpace> adobe = SkColorSpace::MakeRGB(SkColorSpace::kSRGB_RenderTargetGamma,
                                                      SkColorSpace::kAdobeRGB_Gamut);

    SkImageInfo adobeInfo = SkImageInfo::Make(1, 1, kN32_SkColorType, kOpaque_SkAlphaType, adobe);
    sk_sp<Bitmap> adobeBitmap = Bitmap::allocateHeapBitmap(adobeInfo);
    SkBitmap adobeSkBitmap;
    adobeBitmap->getSkBitmap(&adobeSkBitmap);
    *adobeSkBitmap.getAddr32(0, 0) = 0xFF0000F0;  // Opaque, almost fully-red

    SkImageInfo info = adobeInfo.makeColorSpace(nullptr);
    sk_sp<Bitmap> bitmap = Bitmap::allocateHeapBitmap(info);
    SkBitmap skBitmap;
    bitmap->getSkBitmap(&skBitmap);

    // Create a software canvas.
    SkiaCanvas canvas(skBitmap);
    canvas.drawBitmap(*adobeBitmap, 0, 0, nullptr);
    // The result should be fully red, since we convert to sRGB at draw time.
    ASSERT_EQ(0xFF0000FF, *skBitmap.getAddr32(0, 0));

    // Create a software canvas with an Adobe color space.
    SkiaCanvas adobeSkCanvas(adobeSkBitmap);
    adobeSkCanvas.drawBitmap(*bitmap, 0, 0, nullptr);
    // The result should be less than fully red, since we convert to Adobe RGB at draw time.
    ASSERT_EQ(0xFF0000DC, *adobeSkBitmap.getAddr32(0, 0));

    // Test picture recording.
    SkPictureRecorder recorder;
    SkCanvas* skPicCanvas = recorder.beginRecording(1, 1, NULL, 0);
    SkiaCanvas picCanvas(skPicCanvas);
    picCanvas.drawBitmap(*adobeBitmap, 0, 0, nullptr);
    sk_sp<SkPicture> picture = recorder.finishRecordingAsPicture();

    // Playback to an software canvas.  The result should be fully red.
    canvas.asSkCanvas()->drawPicture(picture);
    ASSERT_EQ(0xFF0000FF, *skBitmap.getAddr32(0, 0));
}

TEST(SkiaCanvas, captureCanvasState) {
    // Create a software canvas.
    SkImageInfo info = SkImageInfo::Make(1, 1, kN32_SkColorType, kOpaque_SkAlphaType);
    sk_sp<Bitmap> bitmap = Bitmap::allocateHeapBitmap(info);
    SkBitmap skBitmap;
    bitmap->getSkBitmap(&skBitmap);
    skBitmap.eraseColor(0);
    SkiaCanvas canvas(skBitmap);

    // Translate, then capture and verify the CanvasState.
    canvas.translate(1.0f, 1.0f);
    SkCanvasState* state = canvas.captureCanvasState();
    ASSERT_NE(state, nullptr);
    std::unique_ptr<SkCanvas> newCanvas = SkCanvasStateUtils::MakeFromCanvasState(state);
    ASSERT_NE(newCanvas.get(), nullptr);
    newCanvas->translate(-1.0f, -1.0f);
    ASSERT_TRUE(newCanvas->getTotalMatrix().isIdentity());
    SkCanvasStateUtils::ReleaseCanvasState(state);

    // Create a picture canvas.
    SkPictureRecorder recorder;
    SkCanvas* skPicCanvas = recorder.beginRecording(1, 1, NULL, 0);
    SkiaCanvas picCanvas(skPicCanvas);
    state = picCanvas.captureCanvasState();

    // Verify that we cannot get the CanvasState.
    ASSERT_EQ(state, nullptr);
}
