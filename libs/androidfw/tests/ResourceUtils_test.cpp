/*
 * Copyright (C) 2017 The Android Open Source Project
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

#include "androidfw/ResourceUtils.h"

#include "TestHelpers.h"

namespace android {

TEST(ResourceUtilsTest, ExtractResourceName) {
  StringPiece package, type, entry;
  ASSERT_TRUE(ExtractResourceName("android:string/foo", &package, &type, &entry));
  EXPECT_EQ("android", package);
  EXPECT_EQ("string", type);
  EXPECT_EQ("foo", entry);

  ASSERT_TRUE(ExtractResourceName("@android:string/foo", &package, &type, &entry));
  EXPECT_EQ("android", package);
  EXPECT_EQ("string", type);
  EXPECT_EQ("foo", entry);

  ASSERT_TRUE(ExtractResourceName("string/foo", &package, &type, &entry));
  EXPECT_EQ("", package);
  EXPECT_EQ("string", type);
  EXPECT_EQ("foo", entry);

  ASSERT_TRUE(ExtractResourceName("@string/foo", &package, &type, &entry));
  EXPECT_EQ("", package);
  EXPECT_EQ("string", type);
  EXPECT_EQ("foo", entry);

  ASSERT_TRUE(ExtractResourceName("foo", &package, &type, &entry));
  EXPECT_EQ("", package);
  EXPECT_EQ("", type);
  EXPECT_EQ("foo", entry);

  ASSERT_TRUE(ExtractResourceName("@foo", &package, &type, &entry));
  EXPECT_EQ("", package);
  EXPECT_EQ("", type);
  EXPECT_EQ("foo", entry);

  ASSERT_TRUE(ExtractResourceName("android:foo", &package, &type, &entry));
  EXPECT_EQ("android", package);
  EXPECT_EQ("", type);
  EXPECT_EQ("foo", entry);

//  ASSERT_TRUE(ExtractResourceName("@android:foo", &package, &type, &entry));
//  EXPECT_EQ("android", package);
//  EXPECT_EQ("", type);
//  EXPECT_EQ("foo", entry);

  EXPECT_FALSE(ExtractResourceName(":string/foo", &package, &type, &entry));

  EXPECT_FALSE(ExtractResourceName("@:string/foo", &package, &type, &entry));

  EXPECT_FALSE(ExtractResourceName("/foo", &package, &type, &entry));

  EXPECT_FALSE(ExtractResourceName("@/foo", &package, &type, &entry));
}

}  // namespace android
