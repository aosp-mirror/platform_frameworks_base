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

#include "optimize/Obfuscator.h"

#include <map>
#include <memory>
#include <string>
#include <utility>

#include "ResourceTable.h"
#include "android-base/file.h"
#include "test/Test.h"

using ::aapt::test::GetValue;
using ::testing::AnyOf;
using ::testing::Contains;
using ::testing::Eq;
using ::testing::HasSubstr;
using ::testing::IsFalse;
using ::testing::IsTrue;
using ::testing::Not;
using ::testing::NotNull;

namespace aapt {

namespace {

android::StringPiece GetExtension(android::StringPiece path) {
  auto iter = std::find(path.begin(), path.end(), '.');
  return android::StringPiece(iter, path.end() - iter);
}

void FillTable(aapt::test::ResourceTableBuilder& builder, int start, int end) {
  for (int i = start; i < end; i++) {
    builder.AddFileReference("android:drawable/xmlfile" + std::to_string(i),
                             "res/drawable/xmlfile" + std::to_string(i) + ".xml");
  }
}

class FakeObfuscator : public Obfuscator {
 public:
  explicit FakeObfuscator(OptimizeOptions& optimize_options,
                          const std::unordered_map<std::string, std::string>& shortened_name_map)
      : Obfuscator(optimize_options), shortened_name_map_(shortened_name_map) {
  }

 protected:
  std::string ShortenFileName(android::StringPiece file_path, int output_length) override {
    return shortened_name_map_[std::string(file_path)];
  }

 private:
  std::unordered_map<std::string, std::string> shortened_name_map_;
  DISALLOW_COPY_AND_ASSIGN(FakeObfuscator);
};

TEST(ObfuscatorTest, FileRefPathsChangedInResourceTable) {
  std::unique_ptr<IAaptContext> context = test::ContextBuilder().Build();

  std::unique_ptr<ResourceTable> table =
      test::ResourceTableBuilder()
          .AddFileReference("android:drawable/xmlfile", "res/drawables/xmlfile.xml")
          .AddFileReference("android:drawable/xmlfile2", "res/drawables/xmlfile2.xml")
          .AddString("android:string/string", "res/should/still/be/the/same.png")
          .Build();

  OptimizeOptions options{.shorten_resource_paths = true};
  std::map<std::string, std::string>& path_map = options.table_flattener_options.shortened_path_map;
  ASSERT_TRUE(Obfuscator(options).Consume(context.get(), table.get()));

  // Expect that the path map is populated
  ASSERT_THAT(path_map.find("res/drawables/xmlfile.xml"), Not(Eq(path_map.end())));
  ASSERT_THAT(path_map.find("res/drawables/xmlfile2.xml"), Not(Eq(path_map.end())));

  // The file paths were changed
  EXPECT_THAT(path_map.at("res/drawables/xmlfile.xml"), Not(Eq("res/drawables/xmlfile.xml")));
  EXPECT_THAT(path_map.at("res/drawables/xmlfile2.xml"), Not(Eq("res/drawables/xmlfile2.xml")));

  // Different file paths should remain different
  EXPECT_THAT(path_map["res/drawables/xmlfile.xml"],
              Not(Eq(path_map["res/drawables/xmlfile2.xml"])));

  FileReference* ref = GetValue<FileReference>(table.get(), "android:drawable/xmlfile");
  ASSERT_THAT(ref, NotNull());
  // The map correctly points to the new location of the file
  EXPECT_THAT(path_map["res/drawables/xmlfile.xml"], Eq(*ref->path));

  // Strings should not be affected, only file paths
  EXPECT_THAT(*GetValue<String>(table.get(), "android:string/string")->value,
              Eq("res/should/still/be/the/same.png"));
  EXPECT_THAT(path_map.find("res/should/still/be/the/same.png"), Eq(path_map.end()));
}

TEST(ObfuscatorTest, SkipColorFileRefPaths) {
  std::unique_ptr<IAaptContext> context = test::ContextBuilder().Build();

  std::unique_ptr<ResourceTable> table =
      test::ResourceTableBuilder()
          .AddFileReference("android:color/colorlist", "res/color/colorlist.xml")
          .AddFileReference("android:color/colorlist", "res/color-mdp-v21/colorlist.xml",
                            test::ParseConfigOrDie("mdp-v21"))
          .Build();

  OptimizeOptions options{.shorten_resource_paths = true};
  std::map<std::string, std::string>& path_map = options.table_flattener_options.shortened_path_map;
  ASSERT_TRUE(Obfuscator(options).Consume(context.get(), table.get()));

  // Expect that the path map to not contain the ColorStateList
  ASSERT_THAT(path_map.find("res/color/colorlist.xml"), Eq(path_map.end()));
  ASSERT_THAT(path_map.find("res/color-mdp-v21/colorlist.xml"), Eq(path_map.end()));
}

TEST(ObfuscatorTest, SkipPathShortenExemptions) {
  std::unique_ptr<IAaptContext> context = test::ContextBuilder().Build();

  std::unique_ptr<ResourceTable> table =
      test::ResourceTableBuilder()
          .AddFileReference("android:drawable/xmlfile", "res/drawables/xmlfile.xml")
          .AddFileReference("android:drawable/xmlfile2", "res/drawables/xmlfile2.xml")
          .AddString("android:string/string", "res/should/still/be/the/same.png")
          .Build();

  OptimizeOptions options{.shorten_resource_paths = true};
  TableFlattenerOptions& flattenerOptions = options.table_flattener_options;
  flattenerOptions.path_shorten_exemptions.insert(
      ResourceName({}, ResourceType::kDrawable, "xmlfile"));
  std::map<std::string, std::string>& path_map = options.table_flattener_options.shortened_path_map;
  ASSERT_TRUE(Obfuscator(options).Consume(context.get(), table.get()));

  // Expect that the path map to not contain the first drawable which is in exemption set
  EXPECT_THAT(path_map.find("res/drawables/xmlfile.xml"), Eq(path_map.end()));

  // Expect that the path map to contain the second drawable which is not in exemption set
  EXPECT_THAT(path_map.find("res/drawables/xmlfile2.xml"), Not(Eq(path_map.end())));

  FileReference* ref = GetValue<FileReference>(table.get(), "android:drawable/xmlfile");
  ASSERT_THAT(ref, NotNull());
  ASSERT_THAT(HasFailure(), IsFalse());
  // The path of first drawable in exemption was not changed
  EXPECT_THAT("res/drawables/xmlfile.xml", Eq(*ref->path));

  // The file path of second drawable not in exemption set was changed
  EXPECT_THAT(path_map.at("res/drawables/xmlfile2.xml"), Not(Eq("res/drawables/xmlfile2.xml")));

  FileReference* ref2 = GetValue<FileReference>(table.get(), "android:drawable/xmlfile2");
  ASSERT_THAT(ref, NotNull());
  // The map of second drawable not in exemption correctly points to the new location of the file
  EXPECT_THAT(path_map["res/drawables/xmlfile2.xml"], Eq(*ref2->path));
}

TEST(ObfuscatorTest, KeepExtensions) {
  std::unique_ptr<IAaptContext> context = test::ContextBuilder().Build();

  std::string original_xml_path = "res/drawable/xmlfile.xml";
  std::string original_png_path = "res/drawable/pngfile.png";

  std::unique_ptr<ResourceTable> table =
      test::ResourceTableBuilder()
          .AddFileReference("android:color/xmlfile", original_xml_path)
          .AddFileReference("android:color/pngfile", original_png_path)
          .Build();

  OptimizeOptions options{.shorten_resource_paths = true};
  std::map<std::string, std::string>& path_map = options.table_flattener_options.shortened_path_map;
  ASSERT_TRUE(Obfuscator(options).Consume(context.get(), table.get()));

  // Expect that the path map is populated
  ASSERT_THAT(path_map.find("res/drawable/xmlfile.xml"), Not(Eq(path_map.end())));
  ASSERT_THAT(path_map.find("res/drawable/pngfile.png"), Not(Eq(path_map.end())));

  EXPECT_THAT(GetExtension(path_map[original_xml_path]), Eq(android::StringPiece(".xml")));
  EXPECT_THAT(GetExtension(path_map[original_png_path]), Eq(android::StringPiece(".png")));
}

TEST(ObfuscatorTest, ShortenedToReservedWindowsNames) {
  std::unique_ptr<IAaptContext> context = test::ContextBuilder().Build();

  std::string original_path_1 = "res/drawable/pngfile_1.png";
  std::string original_path_2 = "res/drawable/pngfile_2.png";
  std::string original_path_3 = "res/drawable/pngfile_3.png";
  std::string original_path_4 = "res/drawable/pngfile_4.png";
  std::string original_path_5 = "res/drawable/pngfile_5.png";
  std::string original_path_6 = "res/drawable/pngfile_6.png";
  std::string original_path_7 = "res/drawable/pngfile_7.png";
  std::string original_path_8 = "res/drawable/pngfile_8.png";
  std::string original_path_9 = "res/drawable/pngfile_9.png";

  std::unique_ptr<ResourceTable> table =
      test::ResourceTableBuilder()
          .AddFileReference("android:drawable/pngfile_1", original_path_1)
          .AddFileReference("android:drawable/pngfile_2", original_path_2)
          .AddFileReference("android:drawable/pngfile_3", original_path_3)
          .AddFileReference("android:drawable/pngfile_4", original_path_4)
          .AddFileReference("android:drawable/pngfile_5", original_path_5)
          .AddFileReference("android:drawable/pngfile_6", original_path_6)
          .AddFileReference("android:drawable/pngfile_7", original_path_7)
          .AddFileReference("android:drawable/pngfile_8", original_path_8)
          .AddFileReference("android:drawable/pngfile_9", original_path_9)
          .Build();

  OptimizeOptions options{.shorten_resource_paths = true};
  std::map<std::string, std::string>& path_map = options.table_flattener_options.shortened_path_map;
  auto obfuscator = FakeObfuscator(
      options,
      {
          {original_path_1, "CON"},
          {original_path_2, "Prn"},
          {original_path_3, "AuX"},
          {original_path_4, "nul"},
          {original_path_5, "cOM"},
          {original_path_6, "lPt"},
          {original_path_7, "lPt"},
          {original_path_8, "lPt"},  // 6, 7, and 8 will be appended with a number to disambiguate
          {original_path_9, "F0o"},  // This one is not reserved
      });
  ASSERT_TRUE(obfuscator.Consume(context.get(), table.get()));

  // Expect that the path map is populated
  ASSERT_THAT(path_map.find(original_path_1), Not(Eq(path_map.end())));
  ASSERT_THAT(path_map.find(original_path_2), Not(Eq(path_map.end())));
  ASSERT_THAT(path_map.find(original_path_3), Not(Eq(path_map.end())));
  ASSERT_THAT(path_map.find(original_path_4), Not(Eq(path_map.end())));
  ASSERT_THAT(path_map.find(original_path_5), Not(Eq(path_map.end())));
  ASSERT_THAT(path_map.find(original_path_6), Not(Eq(path_map.end())));
  ASSERT_THAT(path_map.find(original_path_7), Not(Eq(path_map.end())));
  ASSERT_THAT(path_map.find(original_path_8), Not(Eq(path_map.end())));
  ASSERT_THAT(path_map.find(original_path_9), Not(Eq(path_map.end())));

  EXPECT_THAT(path_map[original_path_1], Eq("res/_CON.png"));
  EXPECT_THAT(path_map[original_path_2], Eq("res/_Prn.png"));
  EXPECT_THAT(path_map[original_path_3], Eq("res/_AuX.png"));
  EXPECT_THAT(path_map[original_path_4], Eq("res/_nul.png"));
  EXPECT_THAT(path_map[original_path_5], Eq("res/_cOM.png"));
  EXPECT_THAT(path_map[original_path_9], Eq("res/F0o.png"));

  std::set<std::string> lpt_shortened_names{path_map[original_path_6], path_map[original_path_7],
                                            path_map[original_path_8]};
  EXPECT_THAT(lpt_shortened_names, Contains("res/_lPt.png"));
  EXPECT_THAT(lpt_shortened_names, Contains("res/_lPt1.png"));
  EXPECT_THAT(lpt_shortened_names, Contains("res/_lPt2.png"));
}

TEST(ObfuscatorTest, DeterministicallyHandleCollisions) {
  std::unique_ptr<IAaptContext> context = test::ContextBuilder().Build();

  // 4000 resources is the limit at which the hash space is expanded to 3
  // letters to reduce collisions, we want as many collisions as possible thus
  // N-1.
  const auto kNumResources = 3999;
  const auto kNumTries = 5;

  test::ResourceTableBuilder builder1;
  FillTable(builder1, 0, kNumResources);
  std::unique_ptr<ResourceTable> table1 = builder1.Build();
  OptimizeOptions options{.shorten_resource_paths = true};
  std::map<std::string, std::string>& expected_mapping =
      options.table_flattener_options.shortened_path_map;
  ASSERT_TRUE(Obfuscator(options).Consume(context.get(), table1.get()));

  // We are trying to ensure lack of non-determinism, it is not simple to prove
  // a negative, thus we must try the test a few times so that the test itself
  // is non-flaky. Basically create the pathmap 5 times from the same set of
  // resources but a different order of addition and then ensure they are always
  // mapped to the same short path.
  for (int i = 0; i < kNumTries; i++) {
    test::ResourceTableBuilder builder2;
    // This loop adds resources to the resource table in the range of
    // [0:kNumResources).  Adding the file references in different order makes
    // non-determinism more likely to surface. Thus we add resources
    // [start_index:kNumResources) first then [0:start_index). We also use a
    // different start_index each run.
    int start_index = (kNumResources / kNumTries) * i;
    FillTable(builder2, start_index, kNumResources);
    FillTable(builder2, 0, start_index);
    std::unique_ptr<ResourceTable> table2 = builder2.Build();

    OptimizeOptions actualOptimizerOptions{.shorten_resource_paths = true};
    TableFlattenerOptions& actual_options = actualOptimizerOptions.table_flattener_options;
    std::map<std::string, std::string>& actual_mapping = actual_options.shortened_path_map;
    ASSERT_TRUE(Obfuscator(actualOptimizerOptions).Consume(context.get(), table2.get()));

    for (auto& item : actual_mapping) {
      ASSERT_THAT(expected_mapping[item.first], Eq(item.second));
    }
  }
}

TEST(ObfuscatorTest, DumpIdResourceMap) {
  std::unique_ptr<IAaptContext> context = test::ContextBuilder().Build();

  OverlayableItem overlayable_item(std::make_shared<Overlayable>("TestName", "overlay://theme"));
  overlayable_item.policies |= PolicyFlags::PRODUCT_PARTITION;
  overlayable_item.policies |= PolicyFlags::SYSTEM_PARTITION;
  overlayable_item.policies |= PolicyFlags::VENDOR_PARTITION;

  std::string original_xml_path = "res/drawable/xmlfile.xml";
  std::string original_png_path = "res/drawable/pngfile.png";

  std::string name = "com.app.test:string/overlayable";
  std::unique_ptr<ResourceTable> table =
      test::ResourceTableBuilder()
          .AddFileReference("android:color/xmlfile", original_xml_path)
          .AddFileReference("android:color/pngfile", original_png_path)
          .AddValue("com.app.test:color/mycolor", aapt::ResourceId(0x7f020000),
                    aapt::util::make_unique<aapt::BinaryPrimitive>(
                        uint8_t(android::Res_value::TYPE_INT_COLOR_ARGB8), 0xffaabbcc))
          .AddString("com.app.test:string/mystring", ResourceId(0x7f030000), "hi")
          .AddString("com.app.test:string/in_exemption", ResourceId(0x7f030001), "Hi")
          .AddString(name, ResourceId(0x7f030002), "HI")
          .SetOverlayable(name, overlayable_item)
          .Build();

  OptimizeOptions options{.shorten_resource_paths = true};
  TableFlattenerOptions& flattenerOptions = options.table_flattener_options;
  flattenerOptions.collapse_key_stringpool = true;
  flattenerOptions.name_collapse_exemptions.insert(
      ResourceName({}, ResourceType::kString, "in_exemption"));
  auto& id_resource_map = flattenerOptions.id_resource_map;
  ASSERT_TRUE(Obfuscator(options).Consume(context.get(), table.get()));

  // Expect that the id resource name map is populated
  ASSERT_THAT(id_resource_map.find(0x7f020000), Not(Eq(id_resource_map.end())));
  EXPECT_THAT(id_resource_map.at(0x7f020000), Eq("mycolor"));
  ASSERT_THAT(id_resource_map.find(0x7f030000), Not(Eq(id_resource_map.end())));
  EXPECT_THAT(id_resource_map.at(0x7f030000), Eq("mystring"));
  EXPECT_THAT(id_resource_map.find(0x7f030001), Eq(id_resource_map.end()));
  EXPECT_THAT(id_resource_map.find(0x7f030002), Eq(id_resource_map.end()));
}

TEST(ObfuscatorTest, IsEnabledWithDefaultOption) {
  OptimizeOptions options;
  Obfuscator obfuscatorWithDefaultOption(options);
  ASSERT_THAT(obfuscatorWithDefaultOption.IsEnabled(), Eq(false));
}

TEST(ObfuscatorTest, IsEnabledWithShortenPathOption) {
  OptimizeOptions options{.shorten_resource_paths = true};
  Obfuscator obfuscatorWithShortenPathOption(options);
  ASSERT_THAT(obfuscatorWithShortenPathOption.IsEnabled(), Eq(true));
}

TEST(ObfuscatorTest, IsEnabledWithCollapseStringPoolOption) {
  OptimizeOptions options;
  options.table_flattener_options.collapse_key_stringpool = true;
  Obfuscator obfuscatorWithCollapseStringPoolOption(options);
  ASSERT_THAT(obfuscatorWithCollapseStringPoolOption.IsEnabled(), Eq(true));
}

TEST(ObfuscatorTest, IsEnabledWithShortenPathAndCollapseStringPoolOption) {
  OptimizeOptions options{.shorten_resource_paths = true};
  options.table_flattener_options.collapse_key_stringpool = true;
  Obfuscator obfuscatorWithCollapseStringPoolOption(options);
  ASSERT_THAT(obfuscatorWithCollapseStringPoolOption.IsEnabled(), Eq(true));
}

static std::unique_ptr<ResourceTable> getProtocolBufferTableUnderTest() {
  std::string original_xml_path = "res/drawable/xmlfile.xml";
  std::string original_png_path = "res/drawable/pngfile.png";

  return test::ResourceTableBuilder()
      .AddFileReference("com.app.test:drawable/xmlfile", original_xml_path)
      .AddFileReference("com.app.test:drawable/pngfile", original_png_path)
      .AddValue("com.app.test:color/mycolor", aapt::ResourceId(0x7f020000),
                aapt::util::make_unique<aapt::BinaryPrimitive>(
                    uint8_t(android::Res_value::TYPE_INT_COLOR_ARGB8), 0xffaabbcc))
      .AddString("com.app.test:string/mystring", ResourceId(0x7f030000), "hello world")
      .Build();
}

TEST(ObfuscatorTest, WriteObfuscationMapInProtocolBufferFormat) {
  OptimizeOptions options{.shorten_resource_paths = true};
  options.table_flattener_options.collapse_key_stringpool = true;
  Obfuscator obfuscator(options);
  ASSERT_TRUE(obfuscator.Consume(test::ContextBuilder().Build().get(),
                                 getProtocolBufferTableUnderTest().get()));

  const auto map_path = testing::TempDir() + "/obfuscated_map.pb";
  ASSERT_TRUE(obfuscator.WriteObfuscationMap(map_path));

  std::string pbOut;
  ASSERT_TRUE(android::base::ReadFileToString(map_path, &pbOut, false /* follow_symlinks */));
  EXPECT_THAT(pbOut, HasSubstr("drawable/xmlfile.xml"));
  EXPECT_THAT(pbOut, HasSubstr("drawable/pngfile.png"));
  EXPECT_THAT(pbOut, HasSubstr("mycolor"));
  EXPECT_THAT(pbOut, HasSubstr("mystring"));
  pb::ResourceMappings resourceMappings;
  ASSERT_THAT(resourceMappings.ParseFromString(pbOut), IsTrue());
  ASSERT_THAT(resourceMappings.collapsed_names().resource_names_size(), Eq(2));
  auto& resource_names = resourceMappings.collapsed_names().resource_names();
  EXPECT_THAT(resource_names.at(0).name(), AnyOf(Eq("mycolor"), Eq("mystring")));
  EXPECT_THAT(resource_names.at(1).name(), AnyOf(Eq("mycolor"), Eq("mystring")));
  auto& shortened_paths = resourceMappings.shortened_paths();
  EXPECT_THAT(shortened_paths.resource_paths_size(), Eq(2));
  EXPECT_THAT(shortened_paths.resource_paths(0).original_path(),
              AnyOf(Eq("res/drawable/pngfile.png"), Eq("res/drawable/xmlfile.xml")));
  EXPECT_THAT(shortened_paths.resource_paths(1).original_path(),
              AnyOf(Eq("res/drawable/pngfile.png"), Eq("res/drawable/xmlfile.xml")));
}

TEST(ObfuscatorTest, WriteObfuscatingMapWithNonEnabledOption) {
  OptimizeOptions options;
  Obfuscator obfuscator(options);
  ASSERT_TRUE(obfuscator.Consume(test::ContextBuilder().Build().get(),
                                 getProtocolBufferTableUnderTest().get()));

  const auto map_path = testing::TempDir() + "/obfuscated_map.pb";
  ASSERT_TRUE(obfuscator.WriteObfuscationMap(map_path));

  std::string pbOut;
  ASSERT_TRUE(android::base::ReadFileToString(map_path, &pbOut, false /* follow_symlinks */));
  ASSERT_THAT(pbOut, Eq(""));
}

}  // namespace

}  // namespace aapt
