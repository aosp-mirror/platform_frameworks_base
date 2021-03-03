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

#include "test/Fixture.h"

#include <dirent.h>

#include <android-base/errors.h>
#include <android-base/file.h>
#include <android-base/stringprintf.h>
#include <android-base/utf8.h>
#include <androidfw/StringPiece.h>
#include <gmock/gmock.h>
#include <gtest/gtest.h>

#include "cmd/Compile.h"
#include "cmd/Link.h"
#include "io/FileStream.h"
#include "util/Files.h"

using testing::Eq;
using testing::Ne;

namespace aapt {

const char* CommandTestFixture::kDefaultPackageName = "com.aapt.command.test";

void ClearDirectory(const android::StringPiece& path) {
  const std::string root_dir = path.to_string();
  std::unique_ptr<DIR, decltype(closedir)*> dir(opendir(root_dir.data()), closedir);
  if (!dir) {
    StdErrDiagnostics().Error(DiagMessage() << android::base::SystemErrorCodeToString(errno));
    return;
  }

  while (struct dirent* entry = readdir(dir.get())) {
    // Do not delete hidden files and do not recurse to the parent of this directory
    if (util::StartsWith(entry->d_name, ".")) {
      continue;
    }

    std::string full_path = file::BuildPath({root_dir, entry->d_name});
    if (file::GetFileType(full_path) == file::FileType::kDirectory) {
      ClearDirectory(full_path);
#ifdef _WIN32
      _rmdir(full_path.c_str());
#else
      rmdir(full_path.c_str());
#endif
    } else {
      android::base::utf8::unlink(full_path.c_str());
    }
  }
}

void TestDirectoryFixture::SetUp() {
  temp_dir_ = file::BuildPath({android::base::GetExecutableDirectory(),
                               "_temp",
                               testing::UnitTest::GetInstance()->current_test_case()->name(),
                               testing::UnitTest::GetInstance()->current_test_info()->name()});
  ASSERT_TRUE(file::mkdirs(temp_dir_));
  ClearDirectory(temp_dir_);
}

void TestDirectoryFixture::TearDown() {
  ClearDirectory(temp_dir_);
}

void TestDirectoryFixture::WriteFile(const std::string& path, const std::string& contents) {
  CHECK(util::StartsWith(path, temp_dir_))
      << "Attempting to create a file outside of test temporary directory.";

  // Create any intermediate directories specified in the path
  auto pos = std::find(path.rbegin(), path.rend(), file::sDirSep);
  if (pos != path.rend()) {
    std::string dirs = path.substr(0, (&*pos - path.data()));
    file::mkdirs(dirs);
  }

  CHECK(android::base::WriteStringToFile(contents, path));
}

bool CommandTestFixture::CompileFile(const std::string& path, const std::string& contents,
                                     const android::StringPiece& out_dir, IDiagnostics* diag) {
  WriteFile(path, contents);
  CHECK(file::mkdirs(out_dir.data()));
  return CompileCommand(diag).Execute({path, "-o", out_dir, "-v"}, &std::cerr) == 0;
}

bool CommandTestFixture::Link(const std::vector<std::string>& args, IDiagnostics* diag) {
  std::vector<android::StringPiece> link_args;
  for(const std::string& arg : args) {
    link_args.emplace_back(arg);
  }

  // Link against the android SDK
  std::string android_sdk = file::BuildPath({android::base::GetExecutableDirectory(),
                                             "integration-tests", "CommandTests",
                                             "android-28.jar"});
  link_args.insert(link_args.end(), {"-I", android_sdk});

  return LinkCommand(diag).Execute(link_args, &std::cerr) == 0;
}

bool CommandTestFixture::Link(const std::vector<std::string>& args,
                              const android::StringPiece& flat_dir, IDiagnostics* diag) {
  std::vector<android::StringPiece> link_args;
  for(const std::string& arg : args) {
    link_args.emplace_back(arg);
  }

  // Link against the android SDK
  std::string android_sdk = file::BuildPath({android::base::GetExecutableDirectory(),
                                             "integration-tests", "CommandTests",
                                             "android-28.jar"});
  link_args.insert(link_args.end(), {"-I", android_sdk});

  // Add the files from the compiled resources directory to the link file arguments
  Maybe<std::vector<std::string>> compiled_files = file::FindFiles(flat_dir, diag);
  if (compiled_files) {
    for (std::string& compile_file : compiled_files.value()) {
      compile_file = file::BuildPath({flat_dir, compile_file});
      link_args.emplace_back(std::move(compile_file));
    }
  }

  return LinkCommand(diag).Execute(link_args, &std::cerr) == 0;
}

std::string CommandTestFixture::GetDefaultManifest(const char* package_name) {
  const std::string manifest_file = GetTestPath("AndroidManifest.xml");
  WriteFile(manifest_file, android::base::StringPrintf(R"(
      <manifest xmlns:android="http://schemas.android.com/apk/res/android"
          package="%s">
      </manifest>)", package_name));
  return manifest_file;
}

std::unique_ptr<io::IData> CommandTestFixture::OpenFileAsData(LoadedApk* apk,
                                                              const android::StringPiece& path) {
  return apk
      ->GetFileCollection()
      ->FindFile(path)
      ->OpenAsData();
}

void CommandTestFixture::AssertLoadXml(LoadedApk* apk, const io::IData* data,
                                       android::ResXMLTree *out_tree) {
  ASSERT_THAT(apk, Ne(nullptr));

  out_tree->setTo(data->data(), data->size());
  ASSERT_THAT(out_tree->getError(), Eq(android::OK));
  while (out_tree->next() != android::ResXMLTree::START_TAG) {
    ASSERT_THAT(out_tree->getEventType(), Ne(android::ResXMLTree::BAD_DOCUMENT));
    ASSERT_THAT(out_tree->getEventType(), Ne(android::ResXMLTree::END_DOCUMENT));
  }
}

ManifestBuilder::ManifestBuilder(CommandTestFixture* fixture) : fixture_(fixture) {
}

ManifestBuilder& ManifestBuilder::SetPackageName(const std::string& package_name) {
  package_name_ = package_name;
  return *this;
}

ManifestBuilder& ManifestBuilder::AddContents(const std::string& contents) {
  contents_ += contents + "\n";
  return *this;
}

std::string ManifestBuilder::Build(const std::string& file_path) {
  const char* manifest_template = R"(
      <manifest xmlns:android="http://schemas.android.com/apk/res/android"
          package="%s">
          %s
      </manifest>)";

  fixture_->WriteFile(file_path, android::base::StringPrintf(
                                     manifest_template, package_name_.c_str(), contents_.c_str()));
  return file_path;
}

std::string ManifestBuilder::Build() {
  return Build(fixture_->GetTestPath("AndroidManifest.xml"));
}

LinkCommandBuilder::LinkCommandBuilder(CommandTestFixture* fixture) : fixture_(fixture) {
}

LinkCommandBuilder& LinkCommandBuilder::SetManifestFile(const std::string& file) {
  manifest_supplied_ = true;
  args_.emplace_back("--manifest");
  args_.emplace_back(file);
  return *this;
}

LinkCommandBuilder& LinkCommandBuilder::AddFlag(const std::string& flag) {
  args_.emplace_back(flag);
  return *this;
}

LinkCommandBuilder& LinkCommandBuilder::AddCompiledResDir(const std::string& dir,
                                                          IDiagnostics* diag) {
  if (auto files = file::FindFiles(dir, diag)) {
    for (std::string& compile_file : files.value()) {
      args_.emplace_back(file::BuildPath({dir, compile_file}));
    }
  }
  return *this;
}

LinkCommandBuilder& LinkCommandBuilder::AddParameter(const std::string& param,
                                                     const std::string& value) {
  args_.emplace_back(param);
  args_.emplace_back(value);
  return *this;
}

std::vector<std::string> LinkCommandBuilder::Build(const std::string& out_apk) {
  if (!manifest_supplied_) {
    SetManifestFile(ManifestBuilder(fixture_).Build());
  }
  args_.emplace_back("-o");
  args_.emplace_back(out_apk);
  return args_;
}

} // namespace aapt