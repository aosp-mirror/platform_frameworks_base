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

#include "gflags/gflags.h"

#include "dex_builder.h"
#include "java_lang_builder.h"
#include "util.h"

#include "tinyxml2.h"

#include <fstream>
#include <iostream>
#include <sstream>
#include <string>
#include <vector>

namespace {

using namespace tinyxml2;
using std::string;

constexpr char kStdoutFilename[]{"stdout"};

DEFINE_bool(dex, false, "Generate a DEX file instead of Java");
DEFINE_string(out, kStdoutFilename, "Where to write the generated class");
DEFINE_string(package, "", "The package name for the generated class (required)");

class ViewCompilerXmlVisitor : public XMLVisitor {
 public:
  ViewCompilerXmlVisitor(JavaLangViewBuilder* builder) : builder_(builder) {}

  bool VisitEnter(const XMLDocument& /*doc*/) override {
    builder_->Start();
    return true;
  }

  bool VisitExit(const XMLDocument& /*doc*/) override {
    builder_->Finish();
    return true;
  }

  bool VisitEnter(const XMLElement& element, const XMLAttribute* /*firstAttribute*/) override {
    builder_->StartView(element.Name());
    return true;
  }

  bool VisitExit(const XMLElement& /*element*/) override {
    builder_->FinishView();
    return true;
  }

 private:
  JavaLangViewBuilder* builder_;
};

}  // end namespace

int main(int argc, char** argv) {
  constexpr size_t kProgramName = 0;
  constexpr size_t kFileNameParam = 1;
  constexpr size_t kNumRequiredArgs = 2;

  gflags::SetUsageMessage(
      "Compile XML layout files into equivalent Java language code\n"
      "\n"
      "  example usage:  viewcompiler layout.xml --package com.example.androidapp");
  gflags::ParseCommandLineFlags(&argc, &argv, /*remove_flags*/ true);

  gflags::CommandLineFlagInfo cmd = gflags::GetCommandLineFlagInfoOrDie("package");
  if (argc != kNumRequiredArgs || cmd.is_default) {
    gflags::ShowUsageWithFlags(argv[kProgramName]);
    return 1;
  }

  if (FLAGS_dex) {
    startop::dex::WriteTestDexFile("test.dex");
    return 0;
  }

  const char* const filename = argv[kFileNameParam];
  const string layout_name = FindLayoutNameFromFilename(filename);

  // We want to generate Java language code to inflate exactly this layout. This means
  // generating code to walk the resource XML too.

  XMLDocument xml;
  xml.LoadFile(filename);

  std::ofstream outfile;
  if (FLAGS_out != kStdoutFilename) {
    outfile.open(FLAGS_out);
  }
  JavaLangViewBuilder builder{
      FLAGS_package, layout_name, FLAGS_out == kStdoutFilename ? std::cout : outfile};

  ViewCompilerXmlVisitor visitor{&builder};
  xml.Accept(&visitor);

  return 0;
}
