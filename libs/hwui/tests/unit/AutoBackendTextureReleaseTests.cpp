/*
 * Copyright (C) 2022 The Android Open Source Project
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

#include "AutoBackendTextureRelease.h"
#include "tests/common/TestUtils.h"

using namespace android;
using namespace android::uirenderer;

AHardwareBuffer* allocHardwareBuffer() {
    AHardwareBuffer* buffer;
    AHardwareBuffer_Desc desc = {
            .width = 16,
            .height = 16,
            .layers = 1,
            .format = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM,
            .usage = AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE,
    };
    constexpr int kSucceeded = 0;
    int status = AHardwareBuffer_allocate(&desc, &buffer);
    EXPECT_EQ(kSucceeded, status);
    return buffer;
}

// Expands to AutoBackendTextureRelease_makeImage_invalid_RenderThreadTest,
// set as friend in AutoBackendTextureRelease.h
RENDERTHREAD_TEST(AutoBackendTextureRelease, makeImage_invalid) {
    AHardwareBuffer* buffer = allocHardwareBuffer();
    AutoBackendTextureRelease* textureRelease =
            new AutoBackendTextureRelease(renderThread.getGrContext(), buffer);

    EXPECT_EQ(1, TestUtils::getUsageCount(textureRelease));

    // SkImages::BorrowTextureFrom should fail if given null GrDirectContext.
    textureRelease->makeImage(buffer, HAL_DATASPACE_UNKNOWN, /*context = */ nullptr);

    EXPECT_EQ(1, TestUtils::getUsageCount(textureRelease));

    textureRelease->unref(true);
    AHardwareBuffer_release(buffer);
}

// Expands to AutoBackendTextureRelease_makeImage_valid_RenderThreadTest,
// set as friend in AutoBackendTextureRelease.h
RENDERTHREAD_TEST(AutoBackendTextureRelease, makeImage_valid) {
    AHardwareBuffer* buffer = allocHardwareBuffer();
    AutoBackendTextureRelease* textureRelease =
            new AutoBackendTextureRelease(renderThread.getGrContext(), buffer);

    EXPECT_EQ(1, TestUtils::getUsageCount(textureRelease));

    textureRelease->makeImage(buffer, HAL_DATASPACE_UNKNOWN, renderThread.getGrContext());

    EXPECT_EQ(2, TestUtils::getUsageCount(textureRelease));

    textureRelease->unref(true);
    AHardwareBuffer_release(buffer);
}
