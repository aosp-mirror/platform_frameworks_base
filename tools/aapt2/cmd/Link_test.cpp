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

#include "Link.h"

#include "Diagnostics.h"
#include "LoadedApk.h"
#include "android-base/file.h"
#include "android-base/stringprintf.h"
#include "test/Test.h"

using testing::Eq;
using testing::HasSubstr;
using testing::IsNull;
using testing::Ne;
using testing::NotNull;

namespace aapt {

using LinkTest = CommandTestFixture;

TEST_F(LinkTest, RemoveRawXmlStrings) {
  StdErrDiagnostics diag;
  const std::string compiled_files_dir = GetTestPath("compiled");
  ASSERT_TRUE(CompileFile(GetTestPath("res/xml/test.xml"), R"(<Item AgentCode="007"/>)",
                          compiled_files_dir, &diag));

  const std::string out_apk = GetTestPath("out.apk");
  std::vector<std::string> link_args = {
      "--manifest", GetDefaultManifest(),
      "-o", out_apk,
  };

  ASSERT_TRUE(Link(link_args, compiled_files_dir, &diag));

  // Load the binary xml tree
  android::ResXMLTree tree;
  std::unique_ptr<LoadedApk> apk = LoadedApk::LoadApkFromPath(out_apk, &diag);
  ASSERT_THAT(apk, Ne(nullptr));

  std::unique_ptr<io::IData> data = OpenFileAsData(apk.get(), "res/xml/test.xml");
  ASSERT_THAT(data, Ne(nullptr));
  AssertLoadXml(apk.get(), data.get(), &tree);

  // Check that the raw string index has not been assigned
  EXPECT_THAT(tree.getAttributeValueStringID(0), Eq(-1));
}

TEST_F(LinkTest, KeepRawXmlStrings) {
  StdErrDiagnostics diag;
  const std::string compiled_files_dir = GetTestPath("compiled");
  ASSERT_TRUE(CompileFile(GetTestPath("res/xml/test.xml"), R"(<Item AgentCode="007"/>)",
                          compiled_files_dir, &diag));

  const std::string out_apk = GetTestPath("out.apk");
  std::vector<std::string> link_args = {
      "--manifest", GetDefaultManifest(),
      "-o", out_apk,
      "--keep-raw-values"
  };

  ASSERT_TRUE(Link(link_args, compiled_files_dir, &diag));

  // Load the binary xml tree
  android::ResXMLTree tree;
  std::unique_ptr<LoadedApk> apk = LoadedApk::LoadApkFromPath(out_apk, &diag);
  ASSERT_THAT(apk, Ne(nullptr));

  std::unique_ptr<io::IData> data = OpenFileAsData(apk.get(), "res/xml/test.xml");
  ASSERT_THAT(data, Ne(nullptr));
  AssertLoadXml(apk.get(), data.get(), &tree);

  // Check that the raw string index has been set to the correct string pool entry
  int32_t raw_index = tree.getAttributeValueStringID(0);
  ASSERT_THAT(raw_index, Ne(-1));
  EXPECT_THAT(android::util::GetString(tree.getStrings(), static_cast<size_t>(raw_index)),
              Eq("007"));
}

TEST_F(LinkTest, NoCompressAssets) {
  StdErrDiagnostics diag;
  std::string content(500, 'a');
  WriteFile(GetTestPath("assets/testtxt"), content);
  WriteFile(GetTestPath("assets/testtxt2"), content);
  WriteFile(GetTestPath("assets/test.txt"), content);
  WriteFile(GetTestPath("assets/test.hello.txt"), content);
  WriteFile(GetTestPath("assets/test.hello.xml"), content);

  const std::string out_apk = GetTestPath("out.apk");
  std::vector<std::string> link_args = {
      "--manifest", GetDefaultManifest(),
      "-o", out_apk,
      "-0", ".txt",
      "-0", "txt2",
      "-0", ".hello.txt",
      "-0", "hello.xml",
      "-A", GetTestPath("assets")
  };

  ASSERT_TRUE(Link(link_args, &diag));

  std::unique_ptr<LoadedApk> apk = LoadedApk::LoadApkFromPath(out_apk, &diag);
  ASSERT_THAT(apk, Ne(nullptr));
  io::IFileCollection* zip = apk->GetFileCollection();
  ASSERT_THAT(zip, Ne(nullptr));

  auto file = zip->FindFile("assets/testtxt");
  ASSERT_THAT(file, Ne(nullptr));
  EXPECT_TRUE(file->WasCompressed());

  file = zip->FindFile("assets/testtxt2");
  ASSERT_THAT(file, Ne(nullptr));
  EXPECT_FALSE(file->WasCompressed());

  file = zip->FindFile("assets/test.txt");
  ASSERT_THAT(file, Ne(nullptr));
  EXPECT_FALSE(file->WasCompressed());

  file = zip->FindFile("assets/test.hello.txt");
  ASSERT_THAT(file, Ne(nullptr));
  EXPECT_FALSE(file->WasCompressed());

  file = zip->FindFile("assets/test.hello.xml");
  ASSERT_THAT(file, Ne(nullptr));
  EXPECT_FALSE(file->WasCompressed());
}

TEST_F(LinkTest, NoCompressResources) {
  StdErrDiagnostics diag;
  std::string content(500, 'a');
  const std::string compiled_files_dir = GetTestPath("compiled");
  ASSERT_TRUE(CompileFile(GetTestPath("res/raw/testtxt"), content, compiled_files_dir, &diag));
  ASSERT_TRUE(CompileFile(GetTestPath("res/raw/test.txt"), content, compiled_files_dir, &diag));
  ASSERT_TRUE(CompileFile(GetTestPath("res/raw/test1.hello.txt"), content, compiled_files_dir,
              &diag));
  ASSERT_TRUE(CompileFile(GetTestPath("res/raw/test2.goodbye.xml"), content, compiled_files_dir,
              &diag));

  const std::string out_apk = GetTestPath("out.apk");
  std::vector<std::string> link_args = {
      "--manifest", GetDefaultManifest(),
      "-o", out_apk,
      "-0", ".txt",
      "-0", ".hello.txt",
      "-0", "goodbye.xml",
  };

  ASSERT_TRUE(Link(link_args, compiled_files_dir, &diag));

  std::unique_ptr<LoadedApk> apk = LoadedApk::LoadApkFromPath(out_apk, &diag);
  ASSERT_THAT(apk, Ne(nullptr));
  io::IFileCollection* zip = apk->GetFileCollection();
  ASSERT_THAT(zip, Ne(nullptr));

  auto file = zip->FindFile("res/raw/testtxt");
  ASSERT_THAT(file, Ne(nullptr));
  EXPECT_TRUE(file->WasCompressed());

  file = zip->FindFile("res/raw/test.txt");
  ASSERT_THAT(file, Ne(nullptr));
  EXPECT_FALSE(file->WasCompressed());

  file = zip->FindFile("res/raw/test1.hello.hello.txt");
  ASSERT_THAT(file, Ne(nullptr));
  EXPECT_FALSE(file->WasCompressed());

  file = zip->FindFile("res/raw/test2.goodbye.goodbye.xml");
  ASSERT_THAT(file, Ne(nullptr));
  EXPECT_FALSE(file->WasCompressed());
}

TEST_F(LinkTest, OverlayStyles) {
  StdErrDiagnostics diag;
  const std::string compiled_files_dir = GetTestPath("compiled");
  const std::string override_files_dir = GetTestPath("compiled-override");
  ASSERT_TRUE(CompileFile(GetTestPath("res/values/values.xml"),
                          R"(<resources>
                               <style name="MyStyle">
                                 <item name="android:textColor">#123</item>
                               </style>
                             </resources>)",
                          compiled_files_dir, &diag));
  ASSERT_TRUE(CompileFile(GetTestPath("res/values/values-override.xml"),
                          R"(<resources>
                               <style name="MyStyle">
                                 <item name="android:background">#456</item>
                               </style>
                             </resources>)",
                          override_files_dir, &diag));


  const std::string out_apk = GetTestPath("out.apk");
  std::vector<std::string> link_args = {
      "--manifest", GetDefaultManifest(kDefaultPackageName),
      "-o", out_apk,
  };
  const auto override_files = file::FindFiles(override_files_dir, &diag);
  for (const auto &override_file : override_files.value()) {
      link_args.push_back("-R");
      link_args.push_back(file::BuildPath({override_files_dir, override_file}));
  }
  ASSERT_TRUE(Link(link_args, compiled_files_dir, &diag));

  std::unique_ptr<LoadedApk> apk = LoadedApk::LoadApkFromPath(out_apk, &diag);
  ASSERT_THAT(apk, Ne(nullptr));

  const Style* actual_style = test::GetValue<Style>(
      apk->GetResourceTable(), std::string(kDefaultPackageName) + ":style/MyStyle");
  ASSERT_NE(actual_style, nullptr);
  ASSERT_EQ(actual_style->entries.size(), 2);
  EXPECT_EQ(actual_style->entries[0].key.id, 0x01010098);  // android:textColor
  EXPECT_EQ(actual_style->entries[1].key.id, 0x010100d4);  // android:background
}

TEST_F(LinkTest, OverrideStylesInsteadOfOverlaying) {
  StdErrDiagnostics diag;
  const std::string compiled_files_dir = GetTestPath("compiled");
  const std::string override_files_dir = GetTestPath("compiled-override");
  ASSERT_TRUE(CompileFile(GetTestPath("res/values/values.xml"),
                          R"(<resources>
                               <style name="MyStyle">
                                 <item name="android:textColor">#123</item>
                               </style>
                             </resources>)",
                          compiled_files_dir, &diag));
  ASSERT_TRUE(CompileFile(GetTestPath("res/values/values-override.xml"),
                          R"(<resources>
                               <style name="MyStyle">
                                 <item name="android:background">#456</item>
                               </style>
                             </resources>)",
                          override_files_dir, &diag));


  const std::string out_apk = GetTestPath("out.apk");
  std::vector<std::string> link_args = {
      "--manifest", GetDefaultManifest(kDefaultPackageName),
      "--override-styles-instead-of-overlaying",
      "-o", out_apk,
  };
  const auto override_files = file::FindFiles(override_files_dir, &diag);
  for (const auto &override_file : override_files.value()) {
      link_args.push_back("-R");
      link_args.push_back(file::BuildPath({override_files_dir, override_file}));
  }
  ASSERT_TRUE(Link(link_args, compiled_files_dir, &diag));

  std::unique_ptr<LoadedApk> apk = LoadedApk::LoadApkFromPath(out_apk, &diag);
  ASSERT_THAT(apk, Ne(nullptr));

  const Style* actual_style = test::GetValue<Style>(
      apk->GetResourceTable(), std::string(kDefaultPackageName) + ":style/MyStyle");
  ASSERT_NE(actual_style, nullptr);
  ASSERT_EQ(actual_style->entries.size(), 1);
  EXPECT_EQ(actual_style->entries[0].key.id, 0x010100d4);  // android:background
}

TEST_F(LinkTest, AppInfoWithUsesSplit) {
  StdErrDiagnostics diag;
  const std::string base_files_dir = GetTestPath("base");
  ASSERT_TRUE(CompileFile(GetTestPath("res/values/values.xml"),
                          R"(<resources>
                               <string name="bar">bar</string>
                             </resources>)",
                          base_files_dir, &diag));
  const std::string base_apk = GetTestPath("base.apk");
  std::vector<std::string> link_args = {
      "--manifest", GetDefaultManifest("com.aapt2.app"),
      "-o", base_apk,
  };
  ASSERT_TRUE(Link(link_args, base_files_dir, &diag));

  const std::string feature_manifest = GetTestPath("feature_manifest.xml");
  WriteFile(feature_manifest, android::base::StringPrintf(R"(
      <manifest xmlns:android="http://schemas.android.com/apk/res/android"
          package="com.aapt2.app" split="feature1">
      </manifest>)"));
  const std::string feature_files_dir = GetTestPath("feature");
  ASSERT_TRUE(CompileFile(GetTestPath("res/values/values.xml"),
                          R"(<resources>
                               <string name="foo">foo</string>
                             </resources>)",
                          feature_files_dir, &diag));
  const std::string feature_apk = GetTestPath("feature.apk");
  const std::string feature_package_id = "0x80";
  link_args = {
      "--manifest", feature_manifest,
      "-I", base_apk,
      "--package-id", feature_package_id,
      "-o", feature_apk,
  };
  ASSERT_TRUE(Link(link_args, feature_files_dir, &diag));

  const std::string feature2_manifest = GetTestPath("feature2_manifest.xml");
  WriteFile(feature2_manifest, android::base::StringPrintf(R"(
        <manifest xmlns:android="http://schemas.android.com/apk/res/android"
            package="com.aapt2.app" split="feature2">
          <uses-split android:name="feature1"/>
        </manifest>)"));
  const std::string feature2_files_dir = GetTestPath("feature2");
  ASSERT_TRUE(CompileFile(GetTestPath("res/values/values.xml"),
                          R"(<resources>
                               <string-array name="string_array">
                                 <item>@string/bar</item>
                                 <item>@string/foo</item>
                               </string-array>
                             </resources>)",
                          feature2_files_dir, &diag));
  const std::string feature2_apk = GetTestPath("feature2.apk");
  const std::string feature2_package_id = "0x81";
  link_args = {
      "--manifest", feature2_manifest,
      "-I", base_apk,
      "-I", feature_apk,
      "--package-id", feature2_package_id,
      "-o", feature2_apk,
  };
  ASSERT_TRUE(Link(link_args, feature2_files_dir, &diag));
}

TEST_F(LinkTest, SharedLibraryAttributeRJava) {
  StdErrDiagnostics diag;
  const std::string lib_values =
      R"(<resources>
           <attr name="foo"/>
           <public type="attr" name="foo" id="0x00010001"/>
           <declare-styleable name="LibraryStyleable">
             <attr name="foo" />
           </declare-styleable>
         </resources>)";

  const std::string client_values =
      R"(<resources>
           <attr name="bar" />
           <declare-styleable name="ClientStyleable">
             <attr name="com.example.lib:foo" />
             <attr name="bar" />
           </declare-styleable>
         </resources>)";

  // Build a library with a public attribute
  const std::string lib_res = GetTestPath("library-res");
  ASSERT_TRUE(CompileFile(GetTestPath("res/values/values.xml"), lib_values, lib_res, &diag));

  const std::string lib_apk = GetTestPath("library.apk");
  const std::string lib_java = GetTestPath("library_java");
  // clang-format off
  auto lib_manifest = ManifestBuilder(this)
      .SetPackageName("com.example.lib")
      .Build();

  auto lib_link_args = LinkCommandBuilder(this)
      .SetManifestFile(lib_manifest)
      .AddFlag("--shared-lib")
      .AddParameter("--java", lib_java)
      .AddCompiledResDir(lib_res, &diag)
      .Build(lib_apk);
  // clang-format on
  ASSERT_TRUE(Link(lib_link_args, &diag));

  const std::string lib_r_java = lib_java + "/com/example/lib/R.java";
  std::string lib_r_contents;
  ASSERT_TRUE(android::base::ReadFileToString(lib_r_java, &lib_r_contents));
  EXPECT_THAT(lib_r_contents, HasSubstr(" public static int foo=0x00010001;"));
  EXPECT_THAT(lib_r_contents, HasSubstr(" com.example.lib.R.attr.foo"));

  // Build a client that uses the library attribute in a declare-styleable
  const std::string client_res = GetTestPath("client-res");
  ASSERT_TRUE(CompileFile(GetTestPath("res/values/values.xml"), client_values, client_res, &diag));

  const std::string client_apk = GetTestPath("client.apk");
  const std::string client_java = GetTestPath("client_java");
  // clang-format off
  auto client_manifest = ManifestBuilder(this)
      .SetPackageName("com.example.client")
      .Build();

  auto client_link_args = LinkCommandBuilder(this)
      .SetManifestFile(client_manifest)
      .AddParameter("--java", client_java)
      .AddParameter("-I", lib_apk)
      .AddCompiledResDir(client_res, &diag)
      .Build(client_apk);
  // clang-format on
  ASSERT_TRUE(Link(client_link_args, &diag));

  const std::string client_r_java = client_java + "/com/example/client/R.java";
  std::string client_r_contents;
  ASSERT_TRUE(android::base::ReadFileToString(client_r_java, &client_r_contents));
  EXPECT_THAT(client_r_contents, HasSubstr(" com.example.lib.R.attr.foo, 0x7f010000"));
}

struct SourceXML {
  std::string res_file_path;
  std::string file_contents;
};

static void BuildApk(const std::vector<SourceXML>& source_files, const std::string& apk_path,
                     LinkCommandBuilder&& link_args, CommandTestFixture* fixture,
                     android::IDiagnostics* diag) {
  TemporaryDir res_dir;
  TemporaryDir compiled_res_dir;
  for (auto& source_file : source_files) {
    ASSERT_TRUE(fixture->CompileFile(res_dir.path + source_file.res_file_path,
                                     source_file.file_contents, compiled_res_dir.path, diag));
  }
  ASSERT_TRUE(fixture->Link(
      link_args.AddCompiledResDir(compiled_res_dir.path, diag).Build(apk_path), diag));
}

static void BuildSDK(const std::vector<SourceXML>& source_files, const std::string& apk_path,
                     const std::string& java_root_path, CommandTestFixture* fixture,
                     android::IDiagnostics* diag) {
  auto android_manifest = ManifestBuilder(fixture).SetPackageName("android").Build();

  auto android_link_args = LinkCommandBuilder(fixture)
                               .SetManifestFile(android_manifest)
                               .AddParameter("--private-symbols", "com.android.internal")
                               .AddParameter("--java", java_root_path);

  BuildApk(source_files, apk_path, std::move(android_link_args), fixture, diag);
}

static void BuildNonFinalizedSDK(const std::string& apk_path, const std::string& java_path,
                                 CommandTestFixture* fixture, android::IDiagnostics* diag) {
  const std::string android_values =
      R"(<resources>
          <public type="attr" name="finalized_res" id="0x01010001"/>

          <!-- S staged attributes (Not support staged resources in the same type id) -->
          <staging-public-group type="attr" first-id="0x01fc0050">
            <public name="staged_s_res" />
          </staging-public-group>

          <staging-public-group type="string" first-id="0x01fd0080">
            <public name="staged_s_string" />
          </staging-public-group>

          <!-- SV2 staged attributes (support staged resources in a separate type id) -->
          <staging-public-group type="attr" first-id="0x01ff0049">
            <public name="staged_s2_res" />
          </staging-public-group>

          <!-- T staged attributes (support staged resources in multiple separate type ids) -->
          <staging-public-group type="attr" first-id="0x01fe0063">
            <public name="staged_t_res" />
          </staging-public-group>

          <attr name="finalized_res" />
          <attr name="staged_s_res" />
          <attr name="staged_s2_res" />
          <attr name="staged_t_res" />
          <string name="staged_s_string">Hello</string>
         </resources>)";

  SourceXML source_xml{.res_file_path = "/res/values/values.xml", .file_contents = android_values};
  BuildSDK({source_xml}, apk_path, java_path, fixture, diag);
}

static void BuildFinalizedSDK(const std::string& apk_path, const std::string& java_path,
                              CommandTestFixture* fixture, android::IDiagnostics* diag) {
  const std::string android_values =
      R"(<resources>
          <public type="attr" name="finalized_res" id="0x01010001"/>
          <public type="attr" name="staged_s_res" id="0x01010002"/>
          <public type="attr" name="staged_s2_res" id="0x01010003"/>
          <public type="string" name="staged_s_string" id="0x01020000"/>

          <!-- S staged attributes (Not support staged resources in the same type id) -->
          <staging-public-group-final type="attr" first-id="0x01fc0050">
            <public name="staged_s_res" />
          </staging-public-group-final>

          <staging-public-group-final type="string" first-id="0x01fd0080">
            <public name="staged_s_string" />
          </staging-public-group-final>

          <!-- SV2 staged attributes (support staged resources in a separate type id) -->
          <staging-public-group-final type="attr" first-id="0x01ff0049">
            <public name="staged_s2_res" />
          </staging-public-group-final>

          <!-- T staged attributes (support staged resources in multiple separate type ids) -->
          <staging-public-group type="attr" first-id="0x01fe0063">
            <public name="staged_t_res" />
          </staging-public-group>

          <attr name="finalized_res" />
          <attr name="staged_s_res" />
          <attr name="staged_s2_res" />
          <attr name="staged_t_res" />
          <string name="staged_s_string">Hello</string>
         </resources>)";

  SourceXML source_xml{.res_file_path = "/res/values/values.xml", .file_contents = android_values};
  BuildSDK({source_xml}, apk_path, java_path, fixture, diag);
}

static void BuildAppAgainstSDK(const std::string& apk_path, const std::string& java_path,
                               const std::string& sdk_path, CommandTestFixture* fixture,
                               android::IDiagnostics* diag) {
  const std::string app_values =
      R"(<resources xmlns:android="http://schemas.android.com/apk/res/android">
           <attr name="bar" />
           <style name="MyStyle">
             <item name="android:staged_s_res">@android:string/staged_s_string</item>
           </style>
           <declare-styleable name="ClientStyleable">
             <attr name="android:finalized_res" />
             <attr name="android:staged_s_res" />
             <attr name="bar" />
           </declare-styleable>
           <public name="MyStyle" type="style" id="0x7f020000" />
         </resources>)";

  SourceXML source_xml{.res_file_path = "/res/values/values.xml", .file_contents = app_values};

  auto app_manifest = ManifestBuilder(fixture).SetPackageName("com.example.app").Build();

  auto app_link_args = LinkCommandBuilder(fixture)
                           .SetManifestFile(app_manifest)
                           .AddParameter("--java", java_path)
                           .AddParameter("-I", sdk_path);

  BuildApk({source_xml}, apk_path, std::move(app_link_args), fixture, diag);
}

TEST_F(LinkTest, StagedAndroidApi) {
  StdErrDiagnostics diag;
  const std::string android_apk = GetTestPath("android.apk");
  const std::string android_java = GetTestPath("android-java");
  BuildNonFinalizedSDK(android_apk, android_java, this, &diag);

  const std::string android_r_java = android_java + "/android/R.java";
  std::string android_r_contents;
  ASSERT_TRUE(android::base::ReadFileToString(android_r_java, &android_r_contents));
  EXPECT_THAT(android_r_contents, HasSubstr("public static final int finalized_res=0x01010001;"));
  EXPECT_THAT(
      android_r_contents,
      HasSubstr("public static final int staged_s_res; static { staged_s_res=0x01fc0050; }"));
  EXPECT_THAT(
      android_r_contents,
      HasSubstr("public static final int staged_s_string; static { staged_s_string=0x01fd0080; }"));
  EXPECT_THAT(
      android_r_contents,
      HasSubstr("public static final int staged_s2_res; static { staged_s2_res=0x01ff0049; }"));
  EXPECT_THAT(
      android_r_contents,
      HasSubstr("public static final int staged_t_res; static { staged_t_res=0x01fe0063; }"));

  const std::string app_apk = GetTestPath("app.apk");
  const std::string app_java = GetTestPath("app-java");
  BuildAppAgainstSDK(app_apk, app_java, android_apk, this, &diag);

  const std::string client_r_java = app_java + "/com/example/app/R.java";
  std::string client_r_contents;
  ASSERT_TRUE(android::base::ReadFileToString(client_r_java, &client_r_contents));
  EXPECT_THAT(client_r_contents, HasSubstr(" 0x01010001, android.R.attr.staged_s_res, 0x7f010000"));

  // Test that the resource ids of staged and non-staged resource can be retrieved
  android::AssetManager2 am;
  auto android_asset = android::ApkAssets::Load(android_apk);
  ASSERT_THAT(android_asset, NotNull());
  ASSERT_TRUE(am.SetApkAssets({android_asset}));

  auto result = am.GetResourceId("android:attr/finalized_res");
  ASSERT_TRUE(result.has_value());
  EXPECT_THAT(*result, Eq(0x01010001));

  result = am.GetResourceId("android:attr/staged_s_res");
  ASSERT_TRUE(result.has_value());
  EXPECT_THAT(*result, Eq(0x01fc0050));

  result = am.GetResourceId("android:string/staged_s_string");
  ASSERT_TRUE(result.has_value());
  EXPECT_THAT(*result, Eq(0x01fd0080));

  result = am.GetResourceId("android:attr/staged_s2_res");
  ASSERT_TRUE(result.has_value());
  EXPECT_THAT(*result, Eq(0x01ff0049));

  result = am.GetResourceId("android:attr/staged_t_res");
  ASSERT_TRUE(result.has_value());
  EXPECT_THAT(*result, Eq(0x01fe0063));
}

TEST_F(LinkTest, FinalizedAndroidApi) {
  StdErrDiagnostics diag;
  const std::string android_apk = GetTestPath("android.apk");
  const std::string android_java = GetTestPath("android-java");
  BuildFinalizedSDK(android_apk, android_java, this, &diag);

  const std::string android_r_java = android_java + "/android/R.java";
  std::string android_r_contents;
  ASSERT_TRUE(android::base::ReadFileToString(android_r_java, &android_r_contents));
  EXPECT_THAT(android_r_contents, HasSubstr("public static final int finalized_res=0x01010001;"));
  EXPECT_THAT(android_r_contents, HasSubstr("public static final int staged_s_res=0x01010002;"));
  EXPECT_THAT(android_r_contents, HasSubstr("public static final int staged_s_string=0x01020000;"));
  EXPECT_THAT(android_r_contents, HasSubstr("public static final int staged_s2_res=0x01010003;"));
  EXPECT_THAT(
      android_r_contents,
      HasSubstr("public static final int staged_t_res; static { staged_t_res=0x01fe0063; }"));
  ;

  // Build an application against the non-finalized SDK and then load it into an AssetManager with
  // the finalized SDK.
  const std::string non_finalized_android_apk = GetTestPath("non-finalized-android.apk");
  const std::string non_finalized_android_java = GetTestPath("non-finalized-android-java");
  BuildNonFinalizedSDK(non_finalized_android_apk, non_finalized_android_java, this, &diag);

  const std::string app_apk = GetTestPath("app.apk");
  const std::string app_java = GetTestPath("app-java");
  BuildAppAgainstSDK(app_apk, app_java, non_finalized_android_apk, this, &diag);

  android::AssetManager2 am;
  auto android_asset = android::ApkAssets::Load(android_apk);
  auto app_against_non_final = android::ApkAssets::Load(app_apk);
  ASSERT_THAT(android_asset, NotNull());
  ASSERT_THAT(app_against_non_final, NotNull());
  ASSERT_TRUE(am.SetApkAssets({android_asset, app_against_non_final}));

  auto result = am.GetResourceId("android:attr/finalized_res");
  ASSERT_TRUE(result.has_value());
  EXPECT_THAT(*result, Eq(0x01010001));

  result = am.GetResourceId("android:attr/staged_s_res");
  ASSERT_TRUE(result.has_value());
  EXPECT_THAT(*result, Eq(0x01010002));

  result = am.GetResourceId("android:string/staged_s_string");
  ASSERT_TRUE(result.has_value());
  EXPECT_THAT(*result, Eq(0x01020000));

  result = am.GetResourceId("android:attr/staged_s2_res");
  ASSERT_TRUE(result.has_value());
  EXPECT_THAT(*result, Eq(0x01010003));

  {
    auto style = am.GetBag(0x7f020000);
    ASSERT_TRUE(style.has_value());

    auto& entry = (*style)->entries[0];
    EXPECT_THAT(entry.key, Eq(0x01010002));
    EXPECT_THAT(entry.value.dataType, Eq(android::Res_value::TYPE_REFERENCE));
    EXPECT_THAT(entry.value.data, Eq(0x01020000));
  }

  // Re-compile the application against the finalized SDK and then load it into an AssetManager with
  // the finalized SDK.
  const std::string app_apk_respin = GetTestPath("app-respin.apk");
  const std::string app_java_respin = GetTestPath("app-respin-java");
  BuildAppAgainstSDK(app_apk_respin, app_java_respin, android_apk, this, &diag);

  auto app_against_final = android::ApkAssets::Load(app_apk_respin);
  ASSERT_THAT(app_against_final, NotNull());
  ASSERT_TRUE(am.SetApkAssets({android_asset, app_against_final}));

  {
    auto style = am.GetBag(0x7f020000);
    ASSERT_TRUE(style.has_value());

    auto& entry = (*style)->entries[0];
    EXPECT_THAT(entry.key, Eq(0x01010002));
    EXPECT_THAT(entry.value.dataType, Eq(android::Res_value::TYPE_REFERENCE));
    EXPECT_THAT(entry.value.data, Eq(0x01020000));
  }
}

TEST_F(LinkTest, MacroSubstitution) {
  StdErrDiagnostics diag;
  const std::string values =
      R"(<resources xmlns:an="http://schemas.android.com/apk/res/android">
           <macro name="is_enabled">true</macro>
           <macro name="deep_is_enabled">@macro/is_enabled</macro>
           <macro name="attr_ref">?is_enabled_attr</macro>
           <macro name="raw_string">Hello World!</macro>
           <macro name="android_ref">@an:color/primary_text_dark</macro>

           <attr name="is_enabled_attr" />
           <public type="attr" name="is_enabled_attr" id="0x7f010000"/>

           <string name="is_enabled_str">@macro/is_enabled</string>
           <bool name="is_enabled_bool">@macro/deep_is_enabled</bool>

           <array name="my_array">
             <item>@macro/is_enabled</item>
           </array>

           <style name="MyStyle">
              <item name="android:background">@macro/attr_ref</item>
              <item name="android:fontFamily">@macro/raw_string</item>
           </style>
         </resources>)";

  const std::string xml_values =
      R"(<SomeLayout xmlns:android="http://schemas.android.com/apk/res/android"
                     android:background="@macro/android_ref"
                     android:fontFamily="@macro/raw_string">
         </SomeLayout>)";

  // Build a library with a public attribute
  const std::string lib_res = GetTestPath("test-res");
  ASSERT_TRUE(CompileFile(GetTestPath("res/values/values.xml"), values, lib_res, &diag));
  ASSERT_TRUE(CompileFile(GetTestPath("res/layout/layout.xml"), xml_values, lib_res, &diag));

  const std::string lib_apk = GetTestPath("test.apk");
  // clang-format off
  auto lib_link_args = LinkCommandBuilder(this)
      .SetManifestFile(ManifestBuilder(this).SetPackageName("com.test").Build())
      .AddCompiledResDir(lib_res, &diag)
      .AddFlag("--no-auto-version")
      .Build(lib_apk);
  // clang-format on
  ASSERT_TRUE(Link(lib_link_args, &diag));

  auto apk = LoadedApk::LoadApkFromPath(lib_apk, &diag);
  ASSERT_THAT(apk, NotNull());

  // Test that the type flags determines the value type
  auto actual_bool =
      test::GetValue<BinaryPrimitive>(apk->GetResourceTable(), "com.test:bool/is_enabled_bool");
  ASSERT_THAT(actual_bool, NotNull());
  EXPECT_EQ(android::Res_value::TYPE_INT_BOOLEAN, actual_bool->value.dataType);
  EXPECT_EQ(0xffffffffu, actual_bool->value.data);

  auto actual_str =
      test::GetValue<String>(apk->GetResourceTable(), "com.test:string/is_enabled_str");
  ASSERT_THAT(actual_str, NotNull());
  EXPECT_EQ(*actual_str->value, "true");

  // Test nested data structures
  auto actual_array = test::GetValue<Array>(apk->GetResourceTable(), "com.test:array/my_array");
  ASSERT_THAT(actual_array, NotNull());
  EXPECT_THAT(actual_array->elements.size(), Eq(1));

  auto array_el_ref = ValueCast<BinaryPrimitive>(actual_array->elements[0].get());
  ASSERT_THAT(array_el_ref, NotNull());
  EXPECT_THAT(array_el_ref->value.dataType, Eq(android::Res_value::TYPE_INT_BOOLEAN));
  EXPECT_THAT(array_el_ref->value.data, Eq(0xffffffffu));

  auto actual_style = test::GetValue<Style>(apk->GetResourceTable(), "com.test:style/MyStyle");
  ASSERT_THAT(actual_style, NotNull());
  EXPECT_THAT(actual_style->entries.size(), Eq(2));

  {
    auto style_el = ValueCast<Reference>(actual_style->entries[0].value.get());
    ASSERT_THAT(style_el, NotNull());
    EXPECT_THAT(style_el->reference_type, Eq(Reference::Type::kAttribute));
    EXPECT_THAT(style_el->id, Eq(0x7f010000));
  }

  {
    auto style_el = ValueCast<String>(actual_style->entries[1].value.get());
    ASSERT_THAT(style_el, NotNull());
    EXPECT_THAT(*style_el->value, Eq("Hello World!"));
  }

  // Test substitution in compiled xml files
  auto xml = apk->LoadXml("res/layout/layout.xml", &diag);
  ASSERT_THAT(xml, NotNull());

  auto& xml_attrs = xml->root->attributes;
  ASSERT_THAT(xml_attrs.size(), Eq(2));

  auto attr_value = ValueCast<Reference>(xml_attrs[0].compiled_value.get());
  ASSERT_THAT(attr_value, NotNull());
  EXPECT_THAT(attr_value->reference_type, Eq(Reference::Type::kResource));
  EXPECT_THAT(attr_value->id, Eq(0x01060001));

  EXPECT_THAT(xml_attrs[1].compiled_value.get(), IsNull());
  EXPECT_THAT(xml_attrs[1].value, Eq("Hello World!"));
}

TEST_F(LinkTest, LocaleConfigVerification) {
  StdErrDiagnostics diag;
  const std::string compiled_files_dir = GetTestPath("compiled");

  // Normal case
  ASSERT_TRUE(CompileFile(GetTestPath("res/xml/locales_config.xml"), R"(
    <locale-config xmlns:android="http://schemas.android.com/apk/res/android">
      <locale android:name="en-US"/>
      <locale android:name="pt"/>
      <locale android:name="es-419"/>
      <locale android:name="zh-Hans-SG"/>
    </locale-config>)",
                          compiled_files_dir, &diag));

  const std::string localeconfig_manifest = GetTestPath("localeconfig_manifest.xml");
  WriteFile(localeconfig_manifest, android::base::StringPrintf(R"(
    <manifest xmlns:android="http://schemas.android.com/apk/res/android"
      package="com.aapt2.app">

      <application
        android:localeConfig="@xml/locales_config">
      </application>
    </manifest>)"));

  const std::string out_apk = GetTestPath("out.apk");

  auto link_args = LinkCommandBuilder(this)
                       .SetManifestFile(localeconfig_manifest)
                       .AddCompiledResDir(compiled_files_dir, &diag)
                       .Build(out_apk);
  ASSERT_TRUE(Link(link_args, &diag));

  // Empty locale list
  ASSERT_TRUE(CompileFile(GetTestPath("res/xml/empty_locales_config.xml"), R"(
    <locale-config xmlns:android="http://schemas.android.com/apk/res/android">
    </locale-config>)",
                          compiled_files_dir, &diag));

  const std::string empty_localeconfig_manifest = GetTestPath("empty_localeconfig_manifest.xml");
  WriteFile(empty_localeconfig_manifest, android::base::StringPrintf(R"(
    <manifest xmlns:android="http://schemas.android.com/apk/res/android"
      package="com.aapt2.app">

      <application
        android:localeConfig="@xml/empty_locales_config">
      </application>
    </manifest>)"));

  auto link1_args = LinkCommandBuilder(this)
                        .SetManifestFile(empty_localeconfig_manifest)
                        .AddCompiledResDir(compiled_files_dir, &diag)
                        .Build(out_apk);
  ASSERT_TRUE(Link(link1_args, &diag));
}

TEST_F(LinkTest, LocaleConfigVerificationExternalSymbol) {
  StdErrDiagnostics diag;
  const std::string base_files_dir = GetTestPath("base");
  ASSERT_TRUE(CompileFile(GetTestPath("res/xml/locales_config.xml"), R"(
    <locale-config xmlns:android="http://schemas.android.com/apk/res/android">
      <locale android:name="en-US"/>
      <locale android:name="pt"/>
      <locale android:name="es-419"/>
      <locale android:name="zh-Hans-SG"/>
    </locale-config>)",
                          base_files_dir, &diag));
  const std::string base_apk = GetTestPath("base.apk");
  std::vector<std::string> link_args = {
      "--manifest",
      GetDefaultManifest("com.aapt2.app"),
      "-o",
      base_apk,
  };
  ASSERT_TRUE(Link(link_args, base_files_dir, &diag));

  const std::string localeconfig_manifest = GetTestPath("localeconfig_manifest.xml");
  const std::string out_apk = GetTestPath("out.apk");
  WriteFile(localeconfig_manifest, android::base::StringPrintf(R"(
    <manifest xmlns:android="http://schemas.android.com/apk/res/android"
      package="com.aapt2.app">

      <application
        android:localeConfig="@xml/locales_config">
      </application>
    </manifest>)"));
  link_args = LinkCommandBuilder(this)
                  .SetManifestFile(localeconfig_manifest)
                  .AddParameter("-I", base_apk)
                  .Build(out_apk);
  ASSERT_TRUE(Link(link_args, &diag));
}

TEST_F(LinkTest, LocaleConfigWrongTag) {
  StdErrDiagnostics diag;
  const std::string compiled_files_dir = GetTestPath("compiled");

  // Invalid element: locale1-config
  ASSERT_TRUE(CompileFile(GetTestPath("res/xml/wrong_locale_config.xml"), R"(
    <locale1-config xmlns:android="http://schemas.android.com/apk/res/android">
      <locale android:name="en-US"/>
      <locale android:name="pt"/>
      <locale android:name="es-419"/>
      <locale android:name="zh-Hans-SG"/>
    </locale1-config>)",
                          compiled_files_dir, &diag));

  const std::string locale1config_manifest = GetTestPath("locale1config_manifest.xml");
  WriteFile(locale1config_manifest, android::base::StringPrintf(R"(
    <manifest xmlns:android="http://schemas.android.com/apk/res/android"
      package="com.aapt2.app">

      <application
        android:localeConfig="@xml/wrong_locale_config">
      </application>
    </manifest>)"));

  const std::string out_apk = GetTestPath("out.apk");
  auto link_args = LinkCommandBuilder(this)
                       .SetManifestFile(locale1config_manifest)
                       .AddCompiledResDir(compiled_files_dir, &diag)
                       .Build(out_apk);
  ASSERT_FALSE(Link(link_args, &diag));

  // Invalid element: locale1
  ASSERT_TRUE(CompileFile(GetTestPath("res/xml/wrong_locale.xml"), R"(
    <locale-config xmlns:android="http://schemas.android.com/apk/res/android">
      <locale1 android:name="en-US"/>
      <locale android:name="pt"/>
      <locale android:name="es-419"/>
      <locale android:name="zh-Hans-SG"/>
    </locale-config>)",
                          compiled_files_dir, &diag));

  const std::string locale1_manifest = GetTestPath("locale1_manifest.xml");
  WriteFile(locale1_manifest, android::base::StringPrintf(R"(
    <manifest xmlns:android="http://schemas.android.com/apk/res/android"
      package="com.aapt2.app">

      <application
        android:localeConfig="@xml/wrong_locale">
      </application>
    </manifest>)"));

  auto link1_args = LinkCommandBuilder(this)
                        .SetManifestFile(locale1_manifest)
                        .AddCompiledResDir(compiled_files_dir, &diag)
                        .Build(out_apk);
  ASSERT_FALSE(Link(link1_args, &diag));

  // Invalid attribute: android:name1
  ASSERT_TRUE(CompileFile(GetTestPath("res/xml/wrong_attribute.xml"), R"(
    <locale-config xmlns:android="http://schemas.android.com/apk/res/android">
      <locale android:name1="en-US"/>
      <locale android:name="pt"/>
      <locale android:name="es-419"/>
      <locale android:name="zh-Hans-SG"/>
    </locale-config>)",
                          compiled_files_dir, &diag));

  const std::string wrong_attribute_manifest = GetTestPath("wrong_attribute_manifest.xml");
  WriteFile(wrong_attribute_manifest, android::base::StringPrintf(R"(
    <manifest xmlns:android="http://schemas.android.com/apk/res/android"
      package="com.aapt2.app">

      <application
        android:localeConfig="@xml/wrong_attribute">
      </application>
    </manifest>)"));

  auto link2_args = LinkCommandBuilder(this)
                        .SetManifestFile(wrong_attribute_manifest)
                        .AddCompiledResDir(compiled_files_dir, &diag)
                        .Build(out_apk);
  ASSERT_FALSE(Link(link2_args, &diag));
}

TEST_F(LinkTest, LocaleConfigWrongLocaleFormat) {
  StdErrDiagnostics diag;
  const std::string compiled_files_dir = GetTestPath("compiled");

  // Invalid locale: en-U
  ASSERT_TRUE(CompileFile(GetTestPath("res/xml/wrong_locale.xml"), R"(
    <locale-config xmlns:android="http://schemas.android.com/apk/res/android">
      <locale android:name="en-U"/>
      <locale android:name="pt"/>
      <locale android:name="es-419"/>
      <locale android:name="zh-Hans-SG"/>
    </locale-config>)",
                          compiled_files_dir, &diag));

  const std::string wrong_locale_manifest = GetTestPath("wrong_locale_manifest.xml");
  WriteFile(wrong_locale_manifest, android::base::StringPrintf(R"(
    <manifest xmlns:android="http://schemas.android.com/apk/res/android"
      package="com.aapt2.app">

      <application
        android:localeConfig="@xml/wrong_locale">
      </application>
    </manifest>)"));

  const std::string out_apk = GetTestPath("out.apk");
  auto link_args = LinkCommandBuilder(this)
                       .SetManifestFile(wrong_locale_manifest)
                       .AddCompiledResDir(compiled_files_dir, &diag)
                       .Build(out_apk);
  ASSERT_FALSE(Link(link_args, &diag));
}

static void BuildSDKWithFeatureFlagAttr(const std::string& apk_path, const std::string& java_path,
                                        CommandTestFixture* fixture, android::IDiagnostics* diag) {
  const std::string android_values =
      R"(<resources>
          <staging-public-group type="attr" first-id="0x01fe0063">
            <public name="featureFlag" />
          </staging-public-group>
          <attr name="featureFlag" format="string" />
         </resources>)";

  SourceXML source_xml{.res_file_path = "/res/values/values.xml", .file_contents = android_values};
  BuildSDK({source_xml}, apk_path, java_path, fixture, diag);
}

TEST_F(LinkTest, FeatureFlagDisabled_SdkAtMostUDC) {
  StdErrDiagnostics diag;
  const std::string android_apk = GetTestPath("android.apk");
  const std::string android_java = GetTestPath("android-java");
  BuildSDKWithFeatureFlagAttr(android_apk, android_java, this, &diag);

  const std::string manifest_contents = android::base::StringPrintf(
      R"(<uses-sdk android:minSdkVersion="%d" />"
          <permission android:name="FOO" android:featureFlag="flag" />)",
      SDK_UPSIDE_DOWN_CAKE);
  auto app_manifest = ManifestBuilder(this)
                          .SetPackageName("com.example.app")
                          .AddContents(manifest_contents)
                          .Build();

  const std::string app_java = GetTestPath("app-java");
  auto app_link_args = LinkCommandBuilder(this)
                           .SetManifestFile(app_manifest)
                           .AddParameter("-I", android_apk)
                           .AddParameter("--java", app_java)
                           .AddParameter("--feature-flags", "flag=false");

  const std::string app_apk = GetTestPath("app.apk");
  BuildApk({}, app_apk, std::move(app_link_args), this, &diag);

  // Permission element should be removed if flag is disabled
  auto apk = LoadedApk::LoadApkFromPath(app_apk, &diag);
  ASSERT_THAT(apk, NotNull());
  auto apk_manifest = apk->GetManifest();
  ASSERT_THAT(apk_manifest, NotNull());
  auto root = apk_manifest->root.get();
  ASSERT_THAT(root, NotNull());
  auto maybe_removed = root->FindChild({}, "permission");
  ASSERT_THAT(maybe_removed, IsNull());

  // Code for the permission should be generated even if the element is removed
  const std::string manifest_java = app_java + "/com/example/app/Manifest.java";
  std::string manifest_java_contents;
  ASSERT_TRUE(android::base::ReadFileToString(manifest_java, &manifest_java_contents));
  EXPECT_THAT(manifest_java_contents, HasSubstr(" public static final String FOO=\"FOO\";"));
}

TEST_F(LinkTest, FeatureFlagEnabled_SdkAtMostUDC) {
  StdErrDiagnostics diag;
  const std::string android_apk = GetTestPath("android.apk");
  const std::string android_java = GetTestPath("android-java");
  BuildSDKWithFeatureFlagAttr(android_apk, android_java, this, &diag);

  const std::string manifest_contents = android::base::StringPrintf(
      R"(<uses-sdk android:minSdkVersion="%d" />"
          <permission android:name="FOO" android:featureFlag="flag" />)",
      SDK_UPSIDE_DOWN_CAKE);
  auto app_manifest = ManifestBuilder(this)
                          .SetPackageName("com.example.app")
                          .AddContents(manifest_contents)
                          .Build();

  auto app_link_args = LinkCommandBuilder(this)
                           .SetManifestFile(app_manifest)
                           .AddParameter("-I", android_apk)
                           .AddParameter("--feature-flags", "flag=true");

  const std::string app_apk = GetTestPath("app.apk");
  BuildApk({}, app_apk, std::move(app_link_args), this, &diag);

  // Permission element should be kept if flag is enabled
  auto apk = LoadedApk::LoadApkFromPath(app_apk, &diag);
  ASSERT_THAT(apk, NotNull());
  auto apk_manifest = apk->GetManifest();
  ASSERT_THAT(apk_manifest, NotNull());
  auto root = apk_manifest->root.get();
  ASSERT_THAT(root, NotNull());
  auto maybe_removed = root->FindChild({}, "permission");
  ASSERT_THAT(maybe_removed, NotNull());
}

TEST_F(LinkTest, FeatureFlagWithNoValue_SdkAtMostUDC) {
  StdErrDiagnostics diag;
  const std::string android_apk = GetTestPath("android.apk");
  const std::string android_java = GetTestPath("android-java");
  BuildSDKWithFeatureFlagAttr(android_apk, android_java, this, &diag);

  const std::string manifest_contents = android::base::StringPrintf(
      R"(<uses-sdk android:minSdkVersion="%d" />"
          <permission android:name="FOO" android:featureFlag="flag" />)",
      SDK_UPSIDE_DOWN_CAKE);
  auto app_manifest = ManifestBuilder(this)
                          .SetPackageName("com.example.app")
                          .AddContents(manifest_contents)
                          .Build();

  auto app_link_args = LinkCommandBuilder(this)
                           .SetManifestFile(app_manifest)
                           .AddParameter("-I", android_apk)
                           .AddParameter("--feature-flags", "flag=");

  // Flags must have values if <= UDC
  const std::string app_apk = GetTestPath("app.apk");
  ASSERT_FALSE(Link(app_link_args.Build(app_apk), &diag));
}

TEST_F(LinkTest, FeatureFlagDisabled_SdkAfterUDC) {
  StdErrDiagnostics diag;
  const std::string android_apk = GetTestPath("android.apk");
  const std::string android_java = GetTestPath("android-java");
  BuildSDKWithFeatureFlagAttr(android_apk, android_java, this, &diag);

  const std::string manifest_contents = android::base::StringPrintf(
      R"(<uses-sdk android:minSdkVersion="%d" />"
          <permission android:name="FOO" android:featureFlag="flag" />)",
      SDK_CUR_DEVELOPMENT);
  auto app_manifest = ManifestBuilder(this)
                          .SetPackageName("com.example.app")
                          .AddContents(manifest_contents)
                          .Build();

  auto app_link_args = LinkCommandBuilder(this)
                           .SetManifestFile(app_manifest)
                           .AddParameter("-I", android_apk)
                           .AddParameter("--feature-flags", "flag=false");

  const std::string app_apk = GetTestPath("app.apk");
  BuildApk({}, app_apk, std::move(app_link_args), this, &diag);

  // Permission element should be kept if > UDC, regardless of flag value
  auto apk = LoadedApk::LoadApkFromPath(app_apk, &diag);
  ASSERT_THAT(apk, NotNull());
  auto apk_manifest = apk->GetManifest();
  ASSERT_THAT(apk_manifest, NotNull());
  auto root = apk_manifest->root.get();
  ASSERT_THAT(root, NotNull());
  auto maybe_removed = root->FindChild({}, "permission");
  ASSERT_THAT(maybe_removed, NotNull());
}

TEST_F(LinkTest, FeatureFlagEnabled_SdkAfterUDC) {
  StdErrDiagnostics diag;
  const std::string android_apk = GetTestPath("android.apk");
  const std::string android_java = GetTestPath("android-java");
  BuildSDKWithFeatureFlagAttr(android_apk, android_java, this, &diag);

  const std::string manifest_contents = android::base::StringPrintf(
      R"(<uses-sdk android:minSdkVersion="%d" />"
          <permission android:name="FOO" android:featureFlag="flag" />)",
      SDK_CUR_DEVELOPMENT);
  auto app_manifest = ManifestBuilder(this)
                          .SetPackageName("com.example.app")
                          .AddContents(manifest_contents)
                          .Build();

  auto app_link_args = LinkCommandBuilder(this)
                           .SetManifestFile(app_manifest)
                           .AddParameter("-I", android_apk)
                           .AddParameter("--feature-flags", "flag=true");

  const std::string app_apk = GetTestPath("app.apk");
  BuildApk({}, app_apk, std::move(app_link_args), this, &diag);

  // Permission element should be kept if > UDC, regardless of flag value
  auto apk = LoadedApk::LoadApkFromPath(app_apk, &diag);
  ASSERT_THAT(apk, NotNull());
  auto apk_manifest = apk->GetManifest();
  ASSERT_THAT(apk_manifest, NotNull());
  auto root = apk_manifest->root.get();
  ASSERT_THAT(root, NotNull());
  auto maybe_removed = root->FindChild({}, "permission");
  ASSERT_THAT(maybe_removed, NotNull());
}

TEST_F(LinkTest, FeatureFlagWithNoValue_SdkAfterUDC) {
  StdErrDiagnostics diag;
  const std::string android_apk = GetTestPath("android.apk");
  const std::string android_java = GetTestPath("android-java");
  BuildSDKWithFeatureFlagAttr(android_apk, android_java, this, &diag);

  const std::string manifest_contents = android::base::StringPrintf(
      R"(<uses-sdk android:minSdkVersion="%d" />"
          <permission android:name="FOO" android:featureFlag="flag" />)",
      SDK_CUR_DEVELOPMENT);
  auto app_manifest = ManifestBuilder(this)
                          .SetPackageName("com.example.app")
                          .AddContents(manifest_contents)
                          .Build();

  auto app_link_args = LinkCommandBuilder(this)
                           .SetManifestFile(app_manifest)
                           .AddParameter("-I", android_apk)
                           .AddParameter("--feature-flags", "flag=");

  const std::string app_apk = GetTestPath("app.apk");
  BuildApk({}, app_apk, std::move(app_link_args), this, &diag);

  // Permission element should be kept if > UDC, regardless of flag value
  auto apk = LoadedApk::LoadApkFromPath(app_apk, &diag);
  ASSERT_THAT(apk, NotNull());
  auto apk_manifest = apk->GetManifest();
  ASSERT_THAT(apk_manifest, NotNull());
  auto root = apk_manifest->root.get();
  ASSERT_THAT(root, NotNull());
  auto maybe_removed = root->FindChild({}, "permission");
  ASSERT_THAT(maybe_removed, NotNull());
}

}  // namespace aapt
