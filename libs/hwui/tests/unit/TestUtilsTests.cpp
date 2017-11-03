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

#include "Properties.h"
#include "tests/common/TestUtils.h"

#include <gtest/gtest.h>

using namespace android::uirenderer;

TEST(ScopedProperty, simpleBool) {
    bool previous = Properties::debugOverdraw;
    {
        ScopedProperty<bool> debugOverdraw(Properties::debugOverdraw, true);
        EXPECT_TRUE(Properties::debugOverdraw);
    }
    EXPECT_EQ(previous, Properties::debugOverdraw);
    {
        ScopedProperty<bool> debugOverdraw(Properties::debugOverdraw, false);
        EXPECT_FALSE(Properties::debugOverdraw);
    }
    EXPECT_EQ(previous, Properties::debugOverdraw);
}
