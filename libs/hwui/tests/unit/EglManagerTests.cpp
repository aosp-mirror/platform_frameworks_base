/*
 * Copyright (C) 2021 The Android Open Source Project
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

#include "renderthread/EglManager.h"
#include "tests/common/TestContext.h"

using namespace android;
using namespace android::uirenderer;
using namespace android::uirenderer::renderthread;
using namespace android::uirenderer::test;

TEST(EglManager, doesSurfaceLeak) {
    EglManager eglManager;
    eglManager.initialize();

    ASSERT_TRUE(eglManager.hasEglContext());

    auto colorSpace = SkColorSpace::MakeSRGB();
    for (int i = 0; i < 100; i++) {
        TestContext context;
        auto result =
                eglManager.createSurface(context.surface().get(), ColorMode::Default, colorSpace);
        EXPECT_TRUE(result);
        EGLSurface surface = result.unwrap();
        eglManager.destroySurface(surface);
    }

    eglManager.destroy();
}