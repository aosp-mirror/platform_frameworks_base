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

#include "xml/XmlUtil.h"

#include "test/Test.h"

namespace aapt {

TEST(XmlUtilTest, ExtractPackageFromNamespace) {
  ASSERT_FALSE(xml::ExtractPackageFromNamespace("com.android"));
  ASSERT_FALSE(xml::ExtractPackageFromNamespace("http://schemas.android.com/apk"));
  ASSERT_FALSE(xml::ExtractPackageFromNamespace("http://schemas.android.com/apk/res"));
  ASSERT_FALSE(xml::ExtractPackageFromNamespace("http://schemas.android.com/apk/res/"));
  ASSERT_FALSE(xml::ExtractPackageFromNamespace("http://schemas.android.com/apk/prv/res/"));

  Maybe<xml::ExtractedPackage> p =
      xml::ExtractPackageFromNamespace("http://schemas.android.com/apk/res/a");
  ASSERT_TRUE(p);
  EXPECT_EQ(std::string("a"), p.value().package);
  EXPECT_FALSE(p.value().private_namespace);

  p = xml::ExtractPackageFromNamespace("http://schemas.android.com/apk/prv/res/android");
  ASSERT_TRUE(p);
  EXPECT_EQ(std::string("android"), p.value().package);
  EXPECT_TRUE(p.value().private_namespace);

  p = xml::ExtractPackageFromNamespace("http://schemas.android.com/apk/prv/res/com.test");
  ASSERT_TRUE(p);
  EXPECT_EQ(std::string("com.test"), p.value().package);
  EXPECT_TRUE(p.value().private_namespace);

  p = xml::ExtractPackageFromNamespace("http://schemas.android.com/apk/res-auto");
  ASSERT_TRUE(p);
  EXPECT_EQ(std::string(), p.value().package);
  EXPECT_TRUE(p.value().private_namespace);
}

}  // namespace aapt
