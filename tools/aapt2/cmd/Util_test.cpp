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

#include "Util.h"

#include "AppInfo.h"
#include "split/TableSplitter.h"
#include "test/Test.h"

namespace aapt {

TEST(UtilTest, SplitNamesAreSanitized) {
    AppInfo app_info{"com.pkg"};
    SplitConstraints split_constraints{
        {test::ParseConfigOrDie("en-rUS-land"), test::ParseConfigOrDie("b+sr+Latn")}};

    const auto doc = GenerateSplitManifest(app_info, split_constraints);
    const auto &root = doc->root;
    EXPECT_EQ(root->name, "manifest");
    // split names cannot contain hyphens or plus signs.
    EXPECT_EQ(root->FindAttribute("", "split")->value, "config.b_sr_Latn_en_rUS_land");
    // but we should use resource qualifiers verbatim in 'targetConfig'.
    EXPECT_EQ(root->FindAttribute("", "targetConfig")->value, "b+sr+Latn,en-rUS-land");
}

}  // namespace aapt
