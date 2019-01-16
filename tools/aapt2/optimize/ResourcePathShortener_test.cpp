/*
 * Copyright (C) 2018 The Android Open Source Project
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

#include "optimize/ResourcePathShortener.h"

#include "ResourceTable.h"
#include "test/Test.h"

using ::aapt::test::GetValue;
using ::testing::Not;
using ::testing::NotNull;
using ::testing::Eq;

namespace aapt {

TEST(ResourcePathShortenerTest, FileRefPathsChangedInResourceTable) {
  std::unique_ptr<IAaptContext> context = test::ContextBuilder().Build();

  std::unique_ptr<ResourceTable> table =
      test::ResourceTableBuilder()
          .AddFileReference("android:drawable/xmlfile", "res/drawables/xmlfile.xml")
          .AddFileReference("android:drawable/xmlfile2", "res/drawables/xmlfile2.xml")
          .AddString("android:string/string", "res/should/still/be/the/same.png")
          .Build();

  std::map<std::string, std::string> path_map;
  ASSERT_TRUE(ResourcePathShortener(path_map).Consume(context.get(), table.get()));

  // Expect that the path map is populated
  ASSERT_THAT(path_map.find("res/drawables/xmlfile.xml"), Not(Eq(path_map.end())));
  ASSERT_THAT(path_map.find("res/drawables/xmlfile2.xml"), Not(Eq(path_map.end())));

  // The file paths were changed
  EXPECT_THAT(path_map.at("res/drawables/xmlfile.xml"), Not(Eq("res/drawables/xmlfile.xml")));
  EXPECT_THAT(path_map.at("res/drawables/xmlfile2.xml"), Not(Eq("res/drawables/xmlfile2.xml")));

  // Different file paths should remain different
  EXPECT_THAT(path_map["res/drawables/xmlfile.xml"],
              Not(Eq(path_map["res/drawables/xmlfile2.xml"])));

  FileReference* ref =
      GetValue<FileReference>(table.get(), "android:drawable/xmlfile");
  ASSERT_THAT(ref, NotNull());
  // The map correctly points to the new location of the file
  EXPECT_THAT(path_map["res/drawables/xmlfile.xml"], Eq(*ref->path));

  // Strings should not be affected, only file paths
  EXPECT_THAT(
      *GetValue<String>(table.get(), "android:string/string")->value,
              Eq("res/should/still/be/the/same.png"));
  EXPECT_THAT(path_map.find("res/should/still/be/the/same.png"), Eq(path_map.end()));
}

}   // namespace aapt
