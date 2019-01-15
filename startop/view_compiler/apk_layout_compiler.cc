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

#include "apk_layout_compiler.h"
#include "dex_layout_compiler.h"
#include "java_lang_builder.h"
#include "layout_validation.h"
#include "util.h"

#include "androidfw/ApkAssets.h"
#include "androidfw/AssetManager2.h"
#include "androidfw/ResourceTypes.h"

#include <iostream>
#include <locale>

#include "android-base/stringprintf.h"

namespace startop {

using android::ResXMLParser;
using android::base::StringPrintf;

class ResXmlVisitorAdapter {
 public:
  ResXmlVisitorAdapter(ResXMLParser* parser) : parser_{parser} {}

  template <typename Visitor>
  void Accept(Visitor* visitor) {
    size_t depth{0};
    do {
      switch (parser_->next()) {
        case ResXMLParser::START_DOCUMENT:
          depth++;
          visitor->VisitStartDocument();
          break;
        case ResXMLParser::END_DOCUMENT:
          depth--;
          visitor->VisitEndDocument();
          break;
        case ResXMLParser::START_TAG: {
          depth++;
          size_t name_length = 0;
          const char16_t* name = parser_->getElementName(&name_length);
          visitor->VisitStartTag(std::u16string{name, name_length});
          break;
        }
        case ResXMLParser::END_TAG:
          depth--;
          visitor->VisitEndTag();
          break;
        default:;
      }
    } while (depth > 0 || parser_->getEventType() == ResXMLParser::FIRST_CHUNK_CODE);
  }

 private:
  ResXMLParser* parser_;
};

bool CanCompileLayout(ResXMLParser* parser) {
  ResXmlVisitorAdapter adapter{parser};
  LayoutValidationVisitor visitor;
  adapter.Accept(&visitor);

  return visitor.can_compile();
}

namespace {
void CompileApkAssetsLayouts(const std::unique_ptr<const android::ApkAssets>& assets,
                             CompilationTarget target, std::ostream& target_out) {
  android::AssetManager2 resources;
  resources.SetApkAssets({assets.get()});

  std::string package_name;

  // TODO: handle multiple packages better
  bool first = true;
  for (const auto& package : assets->GetLoadedArsc()->GetPackages()) {
    CHECK(first);
    package_name = package->GetPackageName();
    first = false;
  }

  dex::DexBuilder dex_file;
  dex::ClassBuilder compiled_view{
      dex_file.MakeClass(StringPrintf("%s.CompiledView", package_name.c_str()))};
  std::vector<dex::MethodBuilder> methods;

  assets->ForEachFile("res/", [&](const android::StringPiece& s, android::FileType) {
    if (s == "layout") {
      auto path = StringPrintf("res/%s/", s.to_string().c_str());
      assets->ForEachFile(path, [&](const android::StringPiece& layout_file, android::FileType) {
        auto layout_path = StringPrintf("%s%s", path.c_str(), layout_file.to_string().c_str());
        android::ApkAssetsCookie cookie = android::kInvalidCookie;
        auto asset = resources.OpenNonAsset(layout_path, android::Asset::ACCESS_RANDOM, &cookie);
        CHECK(asset);
        CHECK(android::kInvalidCookie != cookie);
        const auto dynamic_ref_table = resources.GetDynamicRefTableForCookie(cookie);
        CHECK(nullptr != dynamic_ref_table);
        android::ResXMLTree xml_tree{dynamic_ref_table};
        xml_tree.setTo(asset->getBuffer(/*wordAligned=*/true),
                       asset->getLength(),
                       /*copy_data=*/true);
        android::ResXMLParser parser{xml_tree};
        parser.restart();
        if (CanCompileLayout(&parser)) {
          parser.restart();
          const std::string layout_name = startop::util::FindLayoutNameFromFilename(layout_path);
          ResXmlVisitorAdapter adapter{&parser};
          switch (target) {
            case CompilationTarget::kDex: {
              methods.push_back(compiled_view.CreateMethod(
                  layout_name,
                  dex::Prototype{dex::TypeDescriptor::FromClassname("android.view.View"),
                                 dex::TypeDescriptor::FromClassname("android.content.Context"),
                                 dex::TypeDescriptor::Int()}));
              DexViewBuilder builder(&methods.back());
              builder.Start();
              LayoutCompilerVisitor visitor{&builder};
              adapter.Accept(&visitor);
              builder.Finish();
              methods.back().Encode();
              break;
            }
            case CompilationTarget::kJavaLanguage: {
              JavaLangViewBuilder builder{package_name, layout_name, target_out};
              builder.Start();
              LayoutCompilerVisitor visitor{&builder};
              adapter.Accept(&visitor);
              builder.Finish();
              break;
            }
          }
        }
      });
    }
  });

  if (target == CompilationTarget::kDex) {
    slicer::MemView image{dex_file.CreateImage()};
    target_out.write(image.ptr<const char>(), image.size());
  }
}
}  // namespace

void CompileApkLayouts(const std::string& filename, CompilationTarget target,
                       std::ostream& target_out) {
  auto assets = android::ApkAssets::Load(filename);
  CompileApkAssetsLayouts(assets, target, target_out);
}

void CompileApkLayoutsFd(android::base::unique_fd fd, CompilationTarget target,
                         std::ostream& target_out) {
  constexpr const char* friendly_name{"viewcompiler assets"};
  auto assets = android::ApkAssets::LoadFromFd(
      std::move(fd), friendly_name, /*system=*/false, /*force_shared_lib=*/false);
  CompileApkAssetsLayouts(assets, target, target_out);
}

}  // namespace startop
