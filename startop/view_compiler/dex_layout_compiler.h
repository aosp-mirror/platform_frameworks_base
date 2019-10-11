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

#ifndef DEX_LAYOUT_COMPILER_H_
#define DEX_LAYOUT_COMPILER_H_

#include "dex_builder.h"

#include <codecvt>
#include <locale>
#include <string>
#include <vector>

namespace startop {

// This visitor does the actual view compilation, using a supplied builder.
template <typename Builder>
class LayoutCompilerVisitor {
 public:
  explicit LayoutCompilerVisitor(Builder* builder) : builder_{builder} {}

  void VisitStartDocument() { builder_->Start(); }
  void VisitEndDocument() { builder_->Finish(); }
  void VisitStartTag(const std::u16string& name) {
    parent_stack_.push_back(ViewEntry{
        std::wstring_convert<std::codecvt_utf8_utf16<char16_t>, char16_t>{}.to_bytes(name), {}});
  }
  void VisitEndTag() {
    auto entry = parent_stack_.back();
    parent_stack_.pop_back();

    if (parent_stack_.empty()) {
      GenerateCode(entry);
    } else {
      parent_stack_.back().children.push_back(entry);
    }
  }

 private:
  struct ViewEntry {
    std::string name;
    std::vector<ViewEntry> children;
  };

  void GenerateCode(const ViewEntry& view) {
    builder_->StartView(view.name, !view.children.empty());
    for (const auto& child : view.children) {
      GenerateCode(child);
    }
    builder_->FinishView();
  }

  Builder* builder_;

  std::vector<ViewEntry> parent_stack_;
};

class DexViewBuilder {
 public:
  DexViewBuilder(dex::MethodBuilder* method);

  void Start();
  void Finish();
  void StartView(const std::string& name, bool is_viewgroup);
  void FinishView();

 private:
  // Accessors for the stack of views that are under construction.
  dex::LiveRegister AcquireRegister();
  dex::Value GetCurrentView() const;
  dex::Value GetCurrentLayoutParams() const;
  dex::Value GetParentView() const;
  void PopViewStack();

  // Methods to simplify building different code fragments.
  void BuildGetLayoutInflater(dex::Value dest);
  void BuildGetResources(dex::Value dest);
  void BuildGetLayoutResource(dex::Value dest, dex::Value resources, dex::Value resid);
  void BuildLayoutResourceToAttributeSet(dex::Value dest, dex::Value layout_resource);
  void BuildXmlNext();
  void BuildTryCreateView(dex::Value dest, dex::Value parent, dex::Value classname);

  dex::MethodBuilder* method_;

  // Parameters to the generated method
  dex::Value const context_;
  dex::Value const resid_;

  // Registers used for code generation
  const dex::LiveRegister inflater_;
  const dex::LiveRegister xml_;
  const dex::LiveRegister attrs_;
  const dex::LiveRegister classname_tmp_;

  const dex::MethodDeclData xml_next_;
  const dex::MethodDeclData try_create_view_;
  const dex::MethodDeclData generate_layout_params_;
  const dex::MethodDeclData add_view_;

  // Keep track of the views currently in progress.
  struct ViewEntry {
    dex::LiveRegister view;
    std::optional<dex::LiveRegister> layout_params;
  };
  std::vector<ViewEntry> view_stack_;
};

}  // namespace startop

#endif  // DEX_LAYOUT_COMPILER_H_
