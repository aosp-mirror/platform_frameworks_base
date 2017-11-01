/*
 * Copyright (C) 2017 The Android Open Source Project
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

#include <gtest/gtest.h>

#include "Extensions.h"
#include "TextureCache.h"
#include "tests/common/TestUtils.h"

using namespace android;
using namespace android::uirenderer;

RENDERTHREAD_OPENGL_PIPELINE_TEST(TextureCache, clear) {
    TextureCache cache;
    ASSERT_EQ(cache.getSize(), 0u);
    // it is not 0, because FontRenderer allocates one texture
    int initialCount = GpuMemoryTracker::getInstanceCount(GpuObjectType::Texture);
    SkBitmap skBitmap;
    SkImageInfo info = SkImageInfo::Make(100, 100, kN32_SkColorType, kPremul_SkAlphaType);
    skBitmap.setInfo(info);
    sk_sp<Bitmap> hwBitmap(renderThread.allocateHardwareBitmap(skBitmap));
    cache.get(hwBitmap.get());
    ASSERT_EQ(GpuMemoryTracker::getInstanceCount(GpuObjectType::Texture), initialCount + 1);
    cache.clear();
    ASSERT_EQ(GpuMemoryTracker::getInstanceCount(GpuObjectType::Texture), initialCount);
}
