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

#include <string>

#include "test/Test.h"

namespace aapt {

TEST(NameManglerTest, MangleName) {
  std::string package = "android.appcompat";
  std::string name = "Platform.AppCompat";

  std::string mangled_name = NameMangler::MangleEntry(package, name);
  EXPECT_EQ(mangled_name, "android.appcompat$Platform.AppCompat");

  std::string unmangled_package;
  std::string unmangled_name = mangled_name;
  ASSERT_TRUE(NameMangler::Unmangle(&unmangled_name, &unmangled_package));
  EXPECT_EQ(unmangled_name, "Platform.AppCompat");
  EXPECT_EQ(unmangled_package, "android.appcompat");
}

TEST(NameManglerTest, IgnoreUnmangledName) {
  std::string package;
  std::string name = "foo_bar";

  EXPECT_FALSE(NameMangler::Unmangle(&name, &package));
  EXPECT_EQ(name, "foo_bar");
}

}  // namespace aapt
