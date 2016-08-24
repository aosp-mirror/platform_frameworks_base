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

#include "test/Common.h"
#include "xml/XmlUtil.h"

#include <gtest/gtest.h>

namespace aapt {

TEST(XmlUtilTest, ExtractPackageFromNamespace) {
    AAPT_ASSERT_FALSE(xml::extractPackageFromNamespace(u"com.android"));
    AAPT_ASSERT_FALSE(xml::extractPackageFromNamespace(u"http://schemas.android.com/apk"));
    AAPT_ASSERT_FALSE(xml::extractPackageFromNamespace(u"http://schemas.android.com/apk/res"));
    AAPT_ASSERT_FALSE(xml::extractPackageFromNamespace(u"http://schemas.android.com/apk/res/"));
    AAPT_ASSERT_FALSE(xml::extractPackageFromNamespace(
            u"http://schemas.android.com/apk/prv/res/"));

    Maybe<xml::ExtractedPackage> p =
            xml::extractPackageFromNamespace(u"http://schemas.android.com/apk/res/a");
    AAPT_ASSERT_TRUE(p);
    EXPECT_EQ(std::u16string(u"a"), p.value().package);
    EXPECT_FALSE(p.value().privateNamespace);

    p = xml::extractPackageFromNamespace(u"http://schemas.android.com/apk/prv/res/android");
    AAPT_ASSERT_TRUE(p);
    EXPECT_EQ(std::u16string(u"android"), p.value().package);
    EXPECT_TRUE(p.value().privateNamespace);

    p = xml::extractPackageFromNamespace(u"http://schemas.android.com/apk/prv/res/com.test");
    AAPT_ASSERT_TRUE(p);
    EXPECT_EQ(std::u16string(u"com.test"), p.value().package);
    EXPECT_TRUE(p.value().privateNamespace);

    p = xml::extractPackageFromNamespace(u"http://schemas.android.com/apk/res-auto");
    AAPT_ASSERT_TRUE(p);
    EXPECT_EQ(std::u16string(), p.value().package);
    EXPECT_TRUE(p.value().privateNamespace);
}

} // namespace aapt
