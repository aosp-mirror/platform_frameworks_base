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

#include "android-base/stringprintf.h"
#include "apk_layout_compiler.h"
#include "dex_builder.h"
#include "dex_layout_compiler.h"
#include "java_lang_builder.h"
#include "layout_validation.h"
#include "tinyxml_layout_parser.h"
#include "util.h"

#include "tinyxml2.h"

#include <fstream>
#include <iostream>
#include <sstream>
#include <string>
#include <vector>

namespace {

using namespace tinyxml2;
using android::base::StringPrintf;
using startop::dex::ClassBuilder;
using startop::dex::DexBuilder;
using startop::dex::MethodBuilder;
using startop::dex::Prototype;
using startop::dex::TypeDescriptor;
using namespace startop::util;
using std::string;

constexpr char kStdoutFilename[]{"stdout"};

DEFINE_bool(apk, false, "Compile layouts in an APK");
DEFINE_bool(dex, false, "Generate a DEX file instead of Java");
DEFINE_int32(infd, -1, "Read input from the given file descriptor");
DEFINE_string(out, kStdoutFilename, "Where to write the generated class");
DEFINE_string(package, "", "The package name for the generated class (required)");

template <typename Visitor>
class XmlVisitorAdapter : public XMLVisitor {
 public:
  explicit XmlVisitorAdapter(Visitor* visitor) : visitor_{visitor} {}

  bool VisitEnter(const XMLDocument& /*doc*/) override {
    visitor_->VisitStartDocument();
    return true;
  }

  bool VisitExit(const XMLDocument& /*doc*/) override {
    visitor_->VisitEndDocument();
    return true;
  }

  bool VisitEnter(const XMLElement& element, const XMLAttribute* /*firstAttribute*/) override {
    visitor_->VisitStartTag(
        std::wstring_convert<std::codecvt_utf8_utf16<char16_t>, char16_t>{}.from_bytes(
            element.Name()));
    return true;
  }

  bool VisitExit(const XMLElement& /*element*/) override {
    visitor_->VisitEndTag();
    return true;
  }

 private:
  Visitor* visitor_;
};

template <typename Builder>
void CompileLayout(XMLDocument* xml, Builder* builder) {
  startop::LayoutCompilerVisitor visitor{builder};
  XmlVisitorAdapter<decltype(visitor)> adapter{&visitor};
  xml->Accept(&adapter);
}

}  // end namespace

int main(int argc, char** argv) {
  constexpr size_t kProgramName = 0;
  constexpr size_t kFileNameParam = 1;
  constexpr size_t kNumRequiredArgs = 1;

  gflags::SetUsageMessage(
      "Compile XML layout files into equivalent Java language code\n"
      "\n"
      "  example usage:  viewcompiler layout.xml --package com.example.androidapp");
  gflags::ParseCommandLineFlags(&argc, &argv, /*remove_flags*/ true);

  gflags::CommandLineFlagInfo cmd = gflags::GetCommandLineFlagInfoOrDie("package");
  if (argc < kNumRequiredArgs || cmd.is_default) {
    gflags::ShowUsageWithFlags(argv[kProgramName]);
    return 1;
  }

  const bool is_stdout = FLAGS_out == kStdoutFilename;

  std::ofstream outfile;
  if (!is_stdout) {
    outfile.open(FLAGS_out);
  }

  if (FLAGS_apk) {
    const startop::CompilationTarget target =
        FLAGS_dex ? startop::CompilationTarget::kDex : startop::CompilationTarget::kJavaLanguage;
    if (FLAGS_infd >= 0) {
      startop::CompileApkLayoutsFd(
          android::base::unique_fd{FLAGS_infd}, target, is_stdout ? std::cout : outfile);
    } else {
      if (argc < 2) {
        gflags::ShowUsageWithFlags(argv[kProgramName]);
        return 1;
      }
      const char* const filename = argv[kFileNameParam];
      startop::CompileApkLayouts(filename, target, is_stdout ? std::cout : outfile);
    }
    return 0;
  }

  const char* const filename = argv[kFileNameParam];
  const string layout_name = startop::util::FindLayoutNameFromFilename(filename);

  XMLDocument xml;
  xml.LoadFile(filename);

  string message{};
  if (!startop::CanCompileLayout(xml, &message)) {
    LOG(ERROR) << "Layout not supported: " << message;
    return 1;
  }

  if (FLAGS_dex) {
    DexBuilder dex_file;
    string class_name = StringPrintf("%s.CompiledView", FLAGS_package.c_str());
    ClassBuilder compiled_view{dex_file.MakeClass(class_name)};
    MethodBuilder method{compiled_view.CreateMethod(
        layout_name,
        Prototype{TypeDescriptor::FromClassname("android.view.View"),
                  TypeDescriptor::FromClassname("android.content.Context"),
                  TypeDescriptor::Int()})};
    startop::DexViewBuilder builder{&method};
    CompileLayout(&xml, &builder);
    method.Encode();

    slicer::MemView image{dex_file.CreateImage()};

    (is_stdout ? std::cout : outfile).write(image.ptr<const char>(), image.size());
  } else {
    // Generate Java language output.
    JavaLangViewBuilder builder{FLAGS_package, layout_name, is_stdout ? std::cout : outfile};

    CompileLayout(&xml, &builder);
  }
  return 0;
}
