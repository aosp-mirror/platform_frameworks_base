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

#include <androidfw/ResourceTypes.h>

#include "data/basic/R.h"
#include "data/appaslib/R.h"

#include <gtest/gtest.h>

using namespace android;

namespace {

#include "data/basic/basic_arsc.h"

TEST(AppAsLibTest, loadedAsApp) {
  ResTable table;
  ASSERT_EQ(NO_ERROR, table.add(basic_arsc, basic_arsc_len));

  Res_value val;
  ssize_t block = table.getResource(base::R::integer::number2, &val);
  ASSERT_GE(block, 0);
  ASSERT_EQ(Res_value::TYPE_REFERENCE, val.dataType);
  ASSERT_EQ(base::R::array::integerArray1, val.data);
}

TEST(AppAsLibTest, loadedAsSharedLib) {
  ResTable table;
  // Load as shared library.
  ASSERT_EQ(NO_ERROR, table.add(basic_arsc, basic_arsc_len, NULL, 0, -1, false, true));

  Res_value val;
  ssize_t block = table.getResource(appaslib::R::integer::number2, &val);
  ASSERT_GE(block, 0);
  ASSERT_EQ(Res_value::TYPE_REFERENCE, val.dataType);
  ASSERT_EQ(appaslib::R::array::integerArray1, val.data);
}

}
