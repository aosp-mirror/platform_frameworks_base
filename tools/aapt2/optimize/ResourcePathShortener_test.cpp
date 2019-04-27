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

android::StringPiece GetExtension(android::StringPiece path) {
  auto iter = std::find(path.begin(), path.end(), '.');
  return android::StringPiece(iter, path.end() - iter);
}

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

TEST(ResourcePathShortenerTest, SkipColorFileRefPaths) {
  std::unique_ptr<IAaptContext> context = test::ContextBuilder().Build();

  std::unique_ptr<ResourceTable> table =
      test::ResourceTableBuilder()
          .AddFileReference("android:color/colorlist", "res/color/colorlist.xml")
          .Build();

  std::map<std::string, std::string> path_map;
  ASSERT_TRUE(ResourcePathShortener(path_map).Consume(context.get(), table.get()));

  // Expect that the path map to not contain the ColorStateList
  ASSERT_THAT(path_map.find("res/color/colorlist.xml"), Eq(path_map.end()));
}

TEST(ResourcePathShortenerTest, KeepExtensions) {
  std::unique_ptr<IAaptContext> context = test::ContextBuilder().Build();

  std::string original_xml_path = "res/drawable/xmlfile.xml";
  std::string original_png_path = "res/drawable/pngfile.png";

  std::unique_ptr<ResourceTable> table =
      test::ResourceTableBuilder()
          .AddFileReference("android:color/xmlfile", original_xml_path)
          .AddFileReference("android:color/pngfile", original_png_path)
          .Build();

  std::map<std::string, std::string> path_map;
  ASSERT_TRUE(ResourcePathShortener(path_map).Consume(context.get(), table.get()));

  // Expect that the path map is populated
  ASSERT_THAT(path_map.find("res/drawable/xmlfile.xml"), Not(Eq(path_map.end())));
  ASSERT_THAT(path_map.find("res/drawable/pngfile.png"), Not(Eq(path_map.end())));

  auto shortend_xml_path = path_map[original_xml_path];
  auto shortend_png_path = path_map[original_png_path];

  EXPECT_THAT(GetExtension(path_map[original_xml_path]), Eq(android::StringPiece(".xml")));
  EXPECT_THAT(GetExtension(path_map[original_png_path]), Eq(android::StringPiece(".png")));
}

}   // namespace aapt
