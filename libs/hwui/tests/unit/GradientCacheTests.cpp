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

#include <gtest/gtest.h>

#include "Extensions.h"
#include "GradientCache.h"
#include "tests/common/TestUtils.h"

using namespace android;
using namespace android::uirenderer;

RENDERTHREAD_TEST(GradientCache, addRemove) {
    Extensions extensions;
    GradientCache cache(extensions);
    ASSERT_LT(1000u, cache.getMaxSize()) << "Expect non-trivial size";

    SkColor colors[] = { 0xFF00FF00, 0xFFFF0000, 0xFF0000FF };
    float positions[] = { 1, 2, 3 };
    Texture* texture = cache.get(colors, positions, 3);
    ASSERT_TRUE(texture);
    ASSERT_FALSE(texture->cleanup);
    ASSERT_EQ((uint32_t) texture->objectSize(), cache.getSize());
    ASSERT_TRUE(cache.getSize());
    cache.clear();
    ASSERT_EQ(cache.getSize(), 0u);
}
