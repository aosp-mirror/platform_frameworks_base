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

#include "AppInfo.h"
#include "ManifestParser.h"
#include "SourceXmlPullParser.h"

#include <gtest/gtest.h>
#include <sstream>
#include <string>

namespace aapt {

TEST(ManifestParserTest, FindPackage) {
    std::stringstream input;
    input << "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
             "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\"\n"
             "package=\"android\">\n"
             "</manifest>\n";

    ManifestParser parser;
    AppInfo info;
    std::shared_ptr<XmlPullParser> xmlParser = std::make_shared<SourceXmlPullParser>(input);
    ASSERT_TRUE(parser.parse(Source{ "AndroidManifest.xml" }, xmlParser, &info));

    EXPECT_EQ(std::u16string(u"android"), info.package);
}

} // namespace aapt
