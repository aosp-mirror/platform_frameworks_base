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

#include "dex_layout_compiler.h"
#include "layout_validation.h"

#include "android-base/stringprintf.h"

namespace startop {

using android::base::StringPrintf;

void LayoutValidationVisitor::VisitStartTag(const std::u16string& name) {
  if (0 == name.compare(u"merge")) {
    message_ = "Merge tags are not supported";
    can_compile_ = false;
  }
  if (0 == name.compare(u"include")) {
    message_ = "Include tags are not supported";
    can_compile_ = false;
  }
  if (0 == name.compare(u"view")) {
    message_ = "View tags are not supported";
    can_compile_ = false;
  }
  if (0 == name.compare(u"fragment")) {
    message_ = "Fragment tags are not supported";
    can_compile_ = false;
  }
}

DexViewBuilder::DexViewBuilder(dex::MethodBuilder* method)
    : method_{method},
      context_{dex::Value::Parameter(0)},
      resid_{dex::Value::Parameter(1)},
      inflater_{method->MakeRegister()},
      xml_{method->MakeRegister()},
      attrs_{method->MakeRegister()},
      classname_tmp_{method->MakeRegister()},
      xml_next_{method->dex_file()->GetOrDeclareMethod(
          dex::TypeDescriptor::FromClassname("android.content.res.XmlResourceParser"), "next",
          dex::Prototype{dex::TypeDescriptor::Int()})},
      try_create_view_{method->dex_file()->GetOrDeclareMethod(
          dex::TypeDescriptor::FromClassname("android.view.LayoutInflater"), "tryCreateView",
          dex::Prototype{dex::TypeDescriptor::FromClassname("android.view.View"),
                         dex::TypeDescriptor::FromClassname("android.view.View"),
                         dex::TypeDescriptor::FromClassname("java.lang.String"),
                         dex::TypeDescriptor::FromClassname("android.content.Context"),
                         dex::TypeDescriptor::FromClassname("android.util.AttributeSet")})},
      generate_layout_params_{method->dex_file()->GetOrDeclareMethod(
          dex::TypeDescriptor::FromClassname("android.view.ViewGroup"), "generateLayoutParams",
          dex::Prototype{dex::TypeDescriptor::FromClassname("android.view.ViewGroup$LayoutParams"),
                         dex::TypeDescriptor::FromClassname("android.util.AttributeSet")})},
      add_view_{method->dex_file()->GetOrDeclareMethod(
          dex::TypeDescriptor::FromClassname("android.view.ViewGroup"), "addView",
          dex::Prototype{
              dex::TypeDescriptor::Void(),
              dex::TypeDescriptor::FromClassname("android.view.View"),
              dex::TypeDescriptor::FromClassname("android.view.ViewGroup$LayoutParams")})},
      // The register stack starts with one register, which will be null for the root view.
      register_stack_{{method->MakeRegister()}} {}

void DexViewBuilder::Start() {
  dex::DexBuilder* const dex = method_->dex_file();

  // LayoutInflater inflater = LayoutInflater.from(context);
  auto layout_inflater_from = dex->GetOrDeclareMethod(
      dex::TypeDescriptor::FromClassname("android.view.LayoutInflater"),
      "from",
      dex::Prototype{dex::TypeDescriptor::FromClassname("android.view.LayoutInflater"),
                     dex::TypeDescriptor::FromClassname("android.content.Context")});
  method_->AddInstruction(
      dex::Instruction::InvokeStaticObject(layout_inflater_from.id, /*dest=*/inflater_, context_));

  // Resources res = context.getResources();
  auto context_type = dex::TypeDescriptor::FromClassname("android.content.Context");
  auto resources_type = dex::TypeDescriptor::FromClassname("android.content.res.Resources");
  auto get_resources =
      dex->GetOrDeclareMethod(context_type, "getResources", dex::Prototype{resources_type});
  method_->AddInstruction(dex::Instruction::InvokeVirtualObject(get_resources.id, xml_, context_));

  // XmlResourceParser xml = res.getLayout(resid);
  auto xml_resource_parser_type =
      dex::TypeDescriptor::FromClassname("android.content.res.XmlResourceParser");
  auto get_layout =
      dex->GetOrDeclareMethod(resources_type,
                              "getLayout",
                              dex::Prototype{xml_resource_parser_type, dex::TypeDescriptor::Int()});
  method_->AddInstruction(dex::Instruction::InvokeVirtualObject(get_layout.id, xml_, xml_, resid_));

  // AttributeSet attrs = Xml.asAttributeSet(xml);
  auto as_attribute_set = dex->GetOrDeclareMethod(
      dex::TypeDescriptor::FromClassname("android.util.Xml"),
      "asAttributeSet",
      dex::Prototype{dex::TypeDescriptor::FromClassname("android.util.AttributeSet"),
                     dex::TypeDescriptor::FromClassname("org.xmlpull.v1.XmlPullParser")});
  method_->AddInstruction(dex::Instruction::InvokeStaticObject(as_attribute_set.id, attrs_, xml_));

  // xml.next(); // start document
  method_->AddInstruction(dex::Instruction::InvokeInterface(xml_next_.id, {}, xml_));
}

void DexViewBuilder::Finish() {}

namespace {
std::string ResolveName(const std::string& name) {
  if (name == "View") return "android.view.View";
  if (name == "ViewGroup") return "android.view.ViewGroup";
  if (name.find(".") == std::string::npos) {
    return StringPrintf("android.widget.%s", name.c_str());
  }
  return name;
}
}  // namespace

void DexViewBuilder::StartView(const std::string& name, bool is_viewgroup) {
  bool const is_root_view = view_stack_.empty();

  // xml.next(); // start tag
  method_->AddInstruction(dex::Instruction::InvokeInterface(xml_next_.id, {}, xml_));

  dex::Value view = AcquireRegister();
  // try to create the view using the factories
  method_->BuildConstString(classname_tmp_,
                            name);  // TODO: the need to fully qualify the classname
  if (is_root_view) {
    dex::Value null = AcquireRegister();
    method_->BuildConst4(null, 0);
    method_->AddInstruction(dex::Instruction::InvokeVirtualObject(
        try_create_view_.id, view, inflater_, null, classname_tmp_, context_, attrs_));
    ReleaseRegister();
  } else {
    method_->AddInstruction(dex::Instruction::InvokeVirtualObject(
        try_create_view_.id, view, inflater_, GetCurrentView(), classname_tmp_, context_, attrs_));
  }
  auto label = method_->MakeLabel();
  // branch if not null
  method_->AddInstruction(
      dex::Instruction::OpWithArgs(dex::Instruction::Op::kBranchNEqz, /*dest=*/{}, view, label));

  // If null, create the class directly.
  method_->BuildNew(view,
                    dex::TypeDescriptor::FromClassname(ResolveName(name)),
                    dex::Prototype{dex::TypeDescriptor::Void(),
                                   dex::TypeDescriptor::FromClassname("android.content.Context"),
                                   dex::TypeDescriptor::FromClassname("android.util.AttributeSet")},
                    context_,
                    attrs_);

  method_->AddInstruction(
      dex::Instruction::OpWithArgs(dex::Instruction::Op::kBindLabel, /*dest=*/{}, label));

  if (is_viewgroup) {
    // Cast to a ViewGroup so we can add children later.
    const ir::Type* view_group_def = method_->dex_file()->GetOrAddType(
        dex::TypeDescriptor::FromClassname("android.view.ViewGroup").descriptor());
    method_->AddInstruction(dex::Instruction::Cast(view, dex::Value::Type(view_group_def->orig_index)));
  }

  if (!is_root_view) {
    // layout_params = parent.generateLayoutParams(attrs);
    dex::Value layout_params{AcquireRegister()};
    method_->AddInstruction(dex::Instruction::InvokeVirtualObject(
        generate_layout_params_.id, layout_params, GetCurrentView(), attrs_));
    view_stack_.push_back({view, layout_params});
  } else {
    view_stack_.push_back({view, {}});
  }
}

void DexViewBuilder::FinishView() {
  if (view_stack_.size() == 1) {
    method_->BuildReturn(GetCurrentView(), /*is_object=*/true);
  } else {
    // parent.add(view, layout_params)
    method_->AddInstruction(dex::Instruction::InvokeVirtual(
        add_view_.id, /*dest=*/{}, GetParentView(), GetCurrentView(), GetCurrentLayoutParams()));
    // xml.next(); // end tag
    method_->AddInstruction(dex::Instruction::InvokeInterface(xml_next_.id, {}, xml_));
  }
  PopViewStack();
}

dex::Value DexViewBuilder::AcquireRegister() {
  top_register_++;
  if (register_stack_.size() == top_register_) {
    register_stack_.push_back(method_->MakeRegister());
  }
  return register_stack_[top_register_];
}

void DexViewBuilder::ReleaseRegister() { top_register_--; }

dex::Value DexViewBuilder::GetCurrentView() const { return view_stack_.back().view; }
dex::Value DexViewBuilder::GetCurrentLayoutParams() const {
  return view_stack_.back().layout_params.value();
}
dex::Value DexViewBuilder::GetParentView() const {
  return view_stack_[view_stack_.size() - 2].view;
}

void DexViewBuilder::PopViewStack() {
  const auto& top = view_stack_.back();
  // release the layout params if we have them
  if (top.layout_params.has_value()) {
    ReleaseRegister();
  }
  // Unconditionally release the view register.
  ReleaseRegister();
  view_stack_.pop_back();
}

}  // namespace startop