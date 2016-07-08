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
#include "test/Test.h"

#include <string>

namespace aapt {

TEST(NameManglerTest, MangleName) {
    std::string package = "android.appcompat";
    std::string name = "Platform.AppCompat";

    std::string mangledName = NameMangler::mangleEntry(package, name);
    EXPECT_EQ(mangledName, "android.appcompat$Platform.AppCompat");

    std::string unmangledPackage;
    std::string unmangledName = mangledName;
    ASSERT_TRUE(NameMangler::unmangle(&unmangledName, &unmangledPackage));
    EXPECT_EQ(unmangledName, "Platform.AppCompat");
    EXPECT_EQ(unmangledPackage, "android.appcompat");
}

TEST(NameManglerTest, IgnoreUnmangledName) {
    std::string package;
    std::string name = "foo_bar";

    EXPECT_FALSE(NameMangler::unmangle(&name, &package));
    EXPECT_EQ(name, "foo_bar");
}

} // namespace aapt
