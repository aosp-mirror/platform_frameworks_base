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

#include "Compile.h"

#include "android-base/file.h"
#include "io/StringStream.h"
#include "io/ZipArchive.h"
#include "java/AnnotationProcessor.h"
#include "test/Test.h"

namespace aapt {

int TestCompile(const std::string& path, const std::string& outDir, bool legacy,
    StdErrDiagnostics& diag) {
  std::vector<android::StringPiece> args;
  args.push_back(path);
  args.push_back("-o");
  args.push_back(outDir);
  if (legacy) {
    args.push_back("--legacy");
  }
  return CompileCommand(&diag).Execute(args, &std::cerr);
}

TEST(CompilerTest, MultiplePeriods) {
  StdErrDiagnostics diag;
  std::unique_ptr<IAaptContext> context = test::ContextBuilder().Build();
  const std::string kResDir = android::base::Dirname(android::base::GetExecutablePath())
      + "/integration-tests/CompileTest/res";

  // Resource files without periods in the file name should not throw errors
  const std::string path0 = kResDir + "/values/values.xml";
  const std::string path0_out = kResDir + "/values_values.arsc.flat";

  remove(path0_out.c_str());
  ASSERT_EQ(TestCompile(path0, kResDir, /** legacy */ false, diag), 0);
  ASSERT_EQ(remove(path0_out.c_str()), 0);
  ASSERT_EQ(TestCompile(path0, kResDir, /** legacy */ true, diag), 0);
  ASSERT_EQ(remove(path0_out.c_str()), 0);

  const std::string path1 = kResDir + "/drawable/image.png";
  const std::string path1_out = kResDir + "/drawable_image.png.flat";
  remove(path1_out.c_str());
  ASSERT_EQ(TestCompile(path1, kResDir, /** legacy */ false, diag), 0);
  ASSERT_EQ(remove(path1_out.c_str()), 0);
  ASSERT_EQ(TestCompile(path1, kResDir, /** legacy */ true, diag), 0);
  ASSERT_EQ(remove(path1_out.c_str()), 0);

  const std::string path2 = kResDir + "/drawable/image.9.png";
  const std::string path2_out = kResDir + "/drawable_image.9.png.flat";
  remove(path2_out.c_str());
  ASSERT_EQ(TestCompile(path2, kResDir, /** legacy */ false, diag), 0);
  ASSERT_EQ(remove(path2_out.c_str()), 0);
  ASSERT_EQ(TestCompile(path2, kResDir, /** legacy */ true, diag), 0);
  ASSERT_EQ(remove(path2_out.c_str()), 0);

  // Resource files with periods in the file name should fail on non-legacy compilations
  const std::string path3 = kResDir + "/values/values.all.xml";
  const std::string path3_out = kResDir + "/values_values.all.arsc.flat";
  remove(path3_out.c_str());
  ASSERT_NE(TestCompile(path3, kResDir, /** legacy */ false, diag), 0);
  ASSERT_NE(remove(path3_out.c_str()), 0);
  ASSERT_EQ(TestCompile(path3, kResDir, /** legacy */ true, diag), 0);
  ASSERT_EQ(remove(path3_out.c_str()), 0);

  const std::string path4 = kResDir + "/drawable/image.small.png";
  const std::string path4_out = (kResDir + std::string("/drawable_image.small.png.flat")).c_str();
  remove(path4_out.c_str());
  ASSERT_NE(TestCompile(path4, kResDir, /** legacy */ false, diag), 0);
  ASSERT_NE(remove(path4_out.c_str()), 0);
  ASSERT_EQ(TestCompile(path4, kResDir, /** legacy */ true, diag), 0);
  ASSERT_EQ(remove(path4_out.c_str()), 0);

  const std::string path5 = kResDir + "/drawable/image.small.9.png";
  const std::string path5_out = (kResDir + std::string("/drawable_image.small.9.png.flat")).c_str();
  remove(path5_out.c_str());
  ASSERT_NE(TestCompile(path5, kResDir, /** legacy */ false, diag), 0);
  ASSERT_NE(remove(path5_out.c_str()), 0);
  ASSERT_EQ(TestCompile(path5, kResDir, /** legacy */ true, diag), 0);
  ASSERT_EQ(remove(path5_out.c_str()), 0);
}

TEST(CompilerTest, DirInput) {
  StdErrDiagnostics diag;
  std::unique_ptr<IAaptContext> context = test::ContextBuilder().Build();
  const std::string kResDir = android::base::Dirname(android::base::GetExecutablePath())
                            + "/integration-tests/CompileTest/DirInput/res";
  const std::string kOutputFlata = android::base::Dirname(android::base::GetExecutablePath())
                                 + "/integration-tests/CompileTest/DirInput/compiled.flata";
  remove(kOutputFlata.c_str());

  std::vector<android::StringPiece> args;
  args.push_back("--dir");
  args.push_back(kResDir);
  args.push_back("-o");
  args.push_back(kOutputFlata);
  ASSERT_EQ(CompileCommand(&diag).Execute(args, &std::cerr), 0);

  // Check for the presence of the compiled files
  std::string err;
  std::unique_ptr<io::ZipFileCollection> zip = io::ZipFileCollection::Create(kOutputFlata, &err);
  ASSERT_NE(zip, nullptr) << err;
  ASSERT_NE(zip->FindFile("drawable_image.png.flat"), nullptr);
  ASSERT_NE(zip->FindFile("layout_layout.xml.flat"), nullptr);
  ASSERT_NE(zip->FindFile("values_values.arsc.flat"), nullptr);
  ASSERT_EQ(remove(kOutputFlata.c_str()), 0);
}

TEST(CompilerTest, ZipInput) {
  StdErrDiagnostics diag;
  std::unique_ptr<IAaptContext> context = test::ContextBuilder().Build();
  const std::string kResZip = android::base::Dirname(android::base::GetExecutablePath())
                            + "/integration-tests/CompileTest/ZipInput/res.zip";
  const std::string kOutputFlata = android::base::Dirname(android::base::GetExecutablePath())
                                 + "/integration-tests/CompileTest/ZipInput/compiled.flata";
  remove(kOutputFlata.c_str());

  std::vector<android::StringPiece> args;
  args.push_back("--zip");
  args.push_back(kResZip);
  args.push_back("-o");
  args.push_back(kOutputFlata);
  ASSERT_EQ(CompileCommand(&diag).Execute(args, &std::cerr), 0);

  // Check for the presence of the compiled files
  std::string err;
  std::unique_ptr<io::ZipFileCollection> zip = io::ZipFileCollection::Create(kOutputFlata, &err);
  ASSERT_NE(zip, nullptr) << err;
  ASSERT_NE(zip->FindFile("drawable_image.png.flat"), nullptr);
  ASSERT_NE(zip->FindFile("layout_layout.xml.flat"), nullptr);
  ASSERT_NE(zip->FindFile("values_values.arsc.flat"), nullptr);
  ASSERT_EQ(remove(kOutputFlata.c_str()), 0);
}

} // namespace aapt