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
#include "renderthread/RenderEffectCapabilityQuery.h"
#include "tests/common/TestContext.h"

TEST(RenderEffectCapabilityQuery, testSupportedVendor) {
  ASSERT_TRUE(supportsRenderEffectCache("Google", "OpenGL ES 1.4 V@0.0"));
}

TEST(RenderEffectCapabilityQuery, testSupportedVendorWithDifferentVersion) {
  ASSERT_TRUE(supportsRenderEffectCache("Google", "OpenGL ES 1.3 V@571.0"));
}

TEST(RenderEffectCapabilityQuery, testVendorWithSupportedVersion) {
  ASSERT_TRUE(supportsRenderEffectCache("Qualcomm", "OpenGL ES 1.5 V@571.0"));
}

TEST(RenderEffectCapabilityQuery, testVendorWithSupportedPatchVersion) {
  ASSERT_TRUE(supportsRenderEffectCache("Qualcomm", "OpenGL ES 1.5 V@571.1"));
}

TEST(RenderEffectCapabilityQuery, testVendorWithNewerThanSupportedMajorVersion) {
  ASSERT_TRUE(supportsRenderEffectCache("Qualcomm", "OpenGL ES 1.5 V@572.0"));
}

TEST(RenderEffectCapabilityQuery, testVendorWithNewerThanSupportedMinorVersion) {
  ASSERT_TRUE(supportsRenderEffectCache("Qualcomm", "OpenGL ES 1.5 V@571.2"));
}

TEST(RenderEffectCapabilityQuery, testVendorWithUnsupportedMajorVersion) {
  ASSERT_FALSE(supportsRenderEffectCache("Qualcomm", "OpenGL ES 1.0 V@570.1"));
}

TEST(RenderEffectCapabilityQuery, testVendorWithUnsupportedVersion) {
  ASSERT_FALSE(supportsRenderEffectCache("Qualcomm", "OpenGL ES 1.1 V@570.0"));
}

