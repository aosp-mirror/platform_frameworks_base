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

#include <RenderProperties.h>

using namespace android;
using namespace android::uirenderer;

TEST(RenderProperties, layerValidity) {
    DeviceInfo::initialize();

    const int maxTextureSize = DeviceInfo::get()->maxTextureSize();
    ASSERT_LE(2048, maxTextureSize);
    ASSERT_GT(100000, maxTextureSize);

    RenderProperties props;

    // simple cases that all should fit on layers
    props.setLeftTopRightBottom(0, 0, 100, 100);
    ASSERT_TRUE(props.fitsOnLayer());
    props.setLeftTopRightBottom(100, 2000, 300, 4000);
    ASSERT_TRUE(props.fitsOnLayer());
    props.setLeftTopRightBottom(-10, -10, 510, 512);
    ASSERT_TRUE(props.fitsOnLayer());

    // Too big - can't have layer bigger than max texture size
    props.setLeftTopRightBottom(0, 0, maxTextureSize + 1, maxTextureSize + 1);
    ASSERT_FALSE(props.fitsOnLayer());

    // Too small, but still 'fits'. Not fitting is an error case, so don't report empty as such.
    props.setLeftTopRightBottom(0, 0, 100, 0);
    ASSERT_TRUE(props.fitsOnLayer());
}
