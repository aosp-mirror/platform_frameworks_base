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

#include "androidfw/ResourceTypes.h"

#include "TestHelpers.h"
#include "data/appaslib/R.h"

namespace app = com::android::appaslib::app;
namespace lib = com::android::appaslib::lib;

namespace android {

// This tests the app resources loaded as app.
TEST(AppAsLibTest, LoadedAsApp) {
  std::string contents;
  ASSERT_TRUE(
      ReadFileFromZipToString(GetTestDataPath() + "/appaslib/appaslib.apk",
                              "resources.arsc", &contents));

  ResTable table;
  ASSERT_EQ(NO_ERROR, table.add(contents.data(), contents.size()));

  Res_value val;
  ssize_t block = table.getResource(app::R::integer::number1, &val);
  ASSERT_GE(block, 0);
  ASSERT_EQ(Res_value::TYPE_REFERENCE, val.dataType);
  ASSERT_EQ(app::R::array::integerArray1, val.data);
}

// This tests the app resources loaded as shared-lib.
TEST(AppAsLibTest, LoadedAsSharedLib) {
  std::string contents;
  ASSERT_TRUE(
      ReadFileFromZipToString(GetTestDataPath() + "/appaslib/appaslib.apk",
                              "resources.arsc", &contents));

  ResTable table;
  // Load as shared library.
  ASSERT_EQ(NO_ERROR, table.add(contents.data(), contents.size(), NULL, 0, -1,
                                false, true));

  Res_value val;
  ssize_t block = table.getResource(lib::R::integer::number1, &val);
  ASSERT_GE(block, 0);
  ASSERT_EQ(Res_value::TYPE_REFERENCE, val.dataType);
  ASSERT_EQ(lib::R::array::integerArray1, val.data);
}

// This tests the shared-lib loaded with appAsLib as true.
TEST(AppAsLibTest, LoadedSharedLib) {
  std::string contents;
  ASSERT_TRUE(
      ReadFileFromZipToString(GetTestDataPath() + "/appaslib/appaslib_lib.apk",
                              "resources.arsc", &contents));

  ResTable table;
  // Load shared library with appAsLib as true.
  ASSERT_EQ(NO_ERROR, table.add(contents.data(), contents.size(), NULL, 0, -1,
                                false, true));

  Res_value val;
  ssize_t block = table.getResource(lib::R::integer::number1, &val);
  ASSERT_GE(block, 0);
  ASSERT_EQ(Res_value::TYPE_REFERENCE, val.dataType);
  ASSERT_EQ(lib::R::array::integerArray1, val.data);
}

}  // namespace android
