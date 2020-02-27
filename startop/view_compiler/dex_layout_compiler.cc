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
using dex::Instruction;
using dex::LiveRegister;
using dex::Prototype;
using dex::TypeDescriptor;
using dex::Value;

namespace {
// TODO: these are a bunch of static initializers, which we should avoid. See if
// we can make them constexpr.
const TypeDescriptor kAttributeSet = TypeDescriptor::FromClassname("android.util.AttributeSet");
const TypeDescriptor kContext = TypeDescriptor::FromClassname("android.content.Context");
const TypeDescriptor kLayoutInflater = TypeDescriptor::FromClassname("android.view.LayoutInflater");
const TypeDescriptor kResources = TypeDescriptor::FromClassname("android.content.res.Resources");
const TypeDescriptor kString = TypeDescriptor::FromClassname("java.lang.String");
const TypeDescriptor kView = TypeDescriptor::FromClassname("android.view.View");
const TypeDescriptor kViewGroup = TypeDescriptor::FromClassname("android.view.ViewGroup");
const TypeDescriptor kXmlResourceParser =
    TypeDescriptor::FromClassname("android.content.res.XmlResourceParser");
}  // namespace

DexViewBuilder::DexViewBuilder(dex::MethodBuilder* method)
    : method_{method},
      context_{Value::Parameter(0)},
      resid_{Value::Parameter(1)},
      inflater_{method->AllocRegister()},
      xml_{method->AllocRegister()},
      attrs_{method->AllocRegister()},
      classname_tmp_{method->AllocRegister()},
      xml_next_{method->dex_file()->GetOrDeclareMethod(kXmlResourceParser, "next",
                                                       Prototype{TypeDescriptor::Int()})},
      try_create_view_{method->dex_file()->GetOrDeclareMethod(
          kLayoutInflater, "tryCreateView",
          Prototype{kView, kView, kString, kContext, kAttributeSet})},
      generate_layout_params_{method->dex_file()->GetOrDeclareMethod(
          kViewGroup, "generateLayoutParams",
          Prototype{TypeDescriptor::FromClassname("android.view.ViewGroup$LayoutParams"),
                    kAttributeSet})},
      add_view_{method->dex_file()->GetOrDeclareMethod(
          kViewGroup, "addView",
          Prototype{TypeDescriptor::Void(),
                    kView,
                    TypeDescriptor::FromClassname("android.view.ViewGroup$LayoutParams")})} {}

void DexViewBuilder::BuildGetLayoutInflater(Value dest) {
  // dest = LayoutInflater.from(context);
  auto layout_inflater_from = method_->dex_file()->GetOrDeclareMethod(
      kLayoutInflater, "from", Prototype{kLayoutInflater, kContext});
  method_->AddInstruction(Instruction::InvokeStaticObject(layout_inflater_from.id, dest, context_));
}

void DexViewBuilder::BuildGetResources(Value dest) {
  // dest = context.getResources();
  auto get_resources =
      method_->dex_file()->GetOrDeclareMethod(kContext, "getResources", Prototype{kResources});
  method_->AddInstruction(Instruction::InvokeVirtualObject(get_resources.id, dest, context_));
}

void DexViewBuilder::BuildGetLayoutResource(Value dest, Value resources, Value resid) {
  // dest = resources.getLayout(resid);
  auto get_layout = method_->dex_file()->GetOrDeclareMethod(
      kResources, "getLayout", Prototype{kXmlResourceParser, TypeDescriptor::Int()});
  method_->AddInstruction(Instruction::InvokeVirtualObject(get_layout.id, dest, resources, resid));
}

void DexViewBuilder::BuildLayoutResourceToAttributeSet(dex::Value dest,
                                                       dex::Value layout_resource) {
  // dest = Xml.asAttributeSet(layout_resource);
  auto as_attribute_set = method_->dex_file()->GetOrDeclareMethod(
      TypeDescriptor::FromClassname("android.util.Xml"),
      "asAttributeSet",
      Prototype{kAttributeSet, TypeDescriptor::FromClassname("org.xmlpull.v1.XmlPullParser")});
  method_->AddInstruction(
      Instruction::InvokeStaticObject(as_attribute_set.id, dest, layout_resource));
}

void DexViewBuilder::BuildXmlNext() {
  // xml_.next();
  method_->AddInstruction(Instruction::InvokeInterface(xml_next_.id, {}, xml_));
}

void DexViewBuilder::Start() {
  BuildGetLayoutInflater(/*dest=*/inflater_);
  BuildGetResources(/*dest=*/xml_);
  BuildGetLayoutResource(/*dest=*/xml_, /*resources=*/xml_, resid_);
  BuildLayoutResourceToAttributeSet(/*dest=*/attrs_, /*layout_resource=*/xml_);

  // Advance past start document tag
  BuildXmlNext();
}

void DexViewBuilder::Finish() {}

namespace {
std::string ResolveName(const std::string& name) {
  if (name == "View") return "android.view.View";
  if (name == "ViewGroup") return "android.view.ViewGroup";
  if (name.find('.') == std::string::npos) {
    return StringPrintf("android.widget.%s", name.c_str());
  }
  return name;
}
}  // namespace

void DexViewBuilder::BuildTryCreateView(Value dest, Value parent, Value classname) {
  // dest = inflater_.tryCreateView(parent, classname, context_, attrs_);
  method_->AddInstruction(Instruction::InvokeVirtualObject(
      try_create_view_.id, dest, inflater_, parent, classname, context_, attrs_));
}

void DexViewBuilder::StartView(const std::string& name, bool is_viewgroup) {
  bool const is_root_view = view_stack_.empty();

  // Advance to start tag
  BuildXmlNext();

  LiveRegister view = AcquireRegister();
  // try to create the view using the factories
  method_->BuildConstString(classname_tmp_,
                            name);  // TODO: the need to fully qualify the classname
  if (is_root_view) {
    LiveRegister null = AcquireRegister();
    method_->BuildConst4(null, 0);
    BuildTryCreateView(/*dest=*/view, /*parent=*/null, classname_tmp_);
  } else {
    BuildTryCreateView(/*dest=*/view, /*parent=*/GetCurrentView(), classname_tmp_);
  }
  auto label = method_->MakeLabel();
  // branch if not null
  method_->AddInstruction(
      Instruction::OpWithArgs(Instruction::Op::kBranchNEqz, /*dest=*/{}, view, label));

  // If null, create the class directly.
  method_->BuildNew(view,
                    TypeDescriptor::FromClassname(ResolveName(name)),
                    Prototype{TypeDescriptor::Void(), kContext, kAttributeSet},
                    context_,
                    attrs_);

  method_->AddInstruction(Instruction::OpWithArgs(Instruction::Op::kBindLabel, /*dest=*/{}, label));

  if (is_viewgroup) {
    // Cast to a ViewGroup so we can add children later.
    const ir::Type* view_group_def = method_->dex_file()->GetOrAddType(kViewGroup.descriptor());
    method_->AddInstruction(Instruction::Cast(view, Value::Type(view_group_def->orig_index)));
  }

  if (!is_root_view) {
    // layout_params = parent.generateLayoutParams(attrs);
    LiveRegister layout_params{AcquireRegister()};
    method_->AddInstruction(Instruction::InvokeVirtualObject(
        generate_layout_params_.id, layout_params, GetCurrentView(), attrs_));
    view_stack_.push_back({std::move(view), std::move(layout_params)});
  } else {
    view_stack_.push_back({std::move(view), {}});
  }
}

void DexViewBuilder::FinishView() {
  if (view_stack_.size() == 1) {
    method_->BuildReturn(GetCurrentView(), /*is_object=*/true);
  } else {
    // parent.add(view, layout_params)
    method_->AddInstruction(Instruction::InvokeVirtual(
        add_view_.id, /*dest=*/{}, GetParentView(), GetCurrentView(), GetCurrentLayoutParams()));
    // xml.next(); // end tag
    method_->AddInstruction(Instruction::InvokeInterface(xml_next_.id, {}, xml_));
  }
  PopViewStack();
}

LiveRegister DexViewBuilder::AcquireRegister() { return method_->AllocRegister(); }

Value DexViewBuilder::GetCurrentView() const { return view_stack_.back().view; }
Value DexViewBuilder::GetCurrentLayoutParams() const {
  return view_stack_.back().layout_params.value();
}
Value DexViewBuilder::GetParentView() const { return view_stack_[view_stack_.size() - 2].view; }

void DexViewBuilder::PopViewStack() {
  // Unconditionally release the view register.
  view_stack_.pop_back();
}

}  // namespace startop
