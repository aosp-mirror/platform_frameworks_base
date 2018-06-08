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

#pragma once

#include "TestScene.h"

#include <SkBitmap.h>
#include <string>

namespace android {
namespace uirenderer {
namespace test {

class BitmapAllocationTestUtils {
public:
    static sk_sp<Bitmap> allocateHeapBitmap(int width, int height, SkColorType colorType,
                                            std::function<void(SkBitmap& bitmap)> setup) {
        sk_sp<Bitmap> bitmap = TestUtils::createBitmap(width, height, colorType);
        SkBitmap skBitmap;
        bitmap->getSkBitmap(&skBitmap);
        setup(skBitmap);
        return bitmap;
    }

    static sk_sp<Bitmap> allocateHardwareBitmap(int width, int height, SkColorType colorType,
                                                std::function<void(SkBitmap& bitmap)> setup) {
        SkBitmap skBitmap;
        SkImageInfo info = SkImageInfo::Make(width, height, colorType, kPremul_SkAlphaType);
        skBitmap.setInfo(info);
        sk_sp<Bitmap> heapBitmap(Bitmap::allocateHeapBitmap(&skBitmap));
        setup(skBitmap);
        return Bitmap::allocateHardwareBitmap(skBitmap);
    }

    typedef sk_sp<Bitmap> (*BitmapAllocator)(int, int, SkColorType,
                                             std::function<void(SkBitmap& bitmap)> setup);

    template <class T, BitmapAllocator allocator>
    static test::TestScene* createBitmapAllocationScene(const TestScene::Options&) {
        return new T(allocator);
    }

    template <class BaseScene>
    static bool registerBitmapAllocationScene(std::string name, std::string description) {
        TestScene::registerScene({name + "GlTex", description + " (GlTex version).",
                                  createBitmapAllocationScene<BaseScene, &allocateHeapBitmap>});

        TestScene::registerScene({name + "EglImage", description + " (EglImage version).",
                                  createBitmapAllocationScene<BaseScene, &allocateHardwareBitmap>});
        return true;
    }
};

}  // namespace test
}  // namespace uirenderer
}  // namespace android
