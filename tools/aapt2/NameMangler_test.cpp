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

#include "NameMangler.h"

#include <gtest/gtest.h>
#include <string>

namespace aapt {

TEST(NameManglerTest, MangleName) {
    std::u16string package = u"android.appcompat";
    std::u16string name = u"Platform.AppCompat";

    NameMangler::mangle(package, &name);
    EXPECT_EQ(name, u"android.appcompat$Platform.AppCompat");

    std::u16string newPackage;
    ASSERT_TRUE(NameMangler::unmangle(&name, &newPackage));
    EXPECT_EQ(name, u"Platform.AppCompat");
    EXPECT_EQ(newPackage, u"android.appcompat");
}

TEST(NameManglerTest, IgnoreUnmangledName) {
    std::u16string package;
    std::u16string name = u"foo_bar";

    EXPECT_FALSE(NameMangler::unmangle(&name, &package));
    EXPECT_EQ(name, u"foo_bar");
}

} // namespace aapt
