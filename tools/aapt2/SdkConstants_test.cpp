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

#include "SdkConstants.h"

#include <gtest/gtest.h>

namespace aapt {

TEST(SdkConstantsTest, FirstAttributeIsSdk1) {
    EXPECT_EQ(1u, findAttributeSdkLevel(ResourceId(0x01010000)));
}

TEST(SdkConstantsTest, AllAttributesAfterLollipopAreLollipopMR1) {
    EXPECT_EQ(SDK_LOLLIPOP, findAttributeSdkLevel(ResourceId(0x010103f7)));
    EXPECT_EQ(SDK_LOLLIPOP, findAttributeSdkLevel(ResourceId(0x010104ce)));

    EXPECT_EQ(SDK_LOLLIPOP_MR1, findAttributeSdkLevel(ResourceId(0x010104cf)));
    EXPECT_EQ(SDK_LOLLIPOP_MR1, findAttributeSdkLevel(ResourceId(0x010104d8)));

    EXPECT_EQ(SDK_LOLLIPOP_MR1, findAttributeSdkLevel(ResourceId(0x010104d9)));
    EXPECT_EQ(SDK_LOLLIPOP_MR1, findAttributeSdkLevel(ResourceId(0x0101ffff)));
}

} // namespace aapt
