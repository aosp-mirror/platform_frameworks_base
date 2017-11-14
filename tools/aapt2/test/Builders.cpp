/*
 * Copyright (C) 2017 The Android Open Source Project
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

#include "test/Builders.h"

#include "android-base/logging.h"
#include "androidfw/StringPiece.h"

#include "io/StringInputStream.h"
#include "test/Common.h"
#include "util/Util.h"

using ::aapt::io::StringInputStream;
using ::android::StringPiece;

namespace aapt {
namespace test {

ResourceTableBuilder& ResourceTableBuilder::SetPackageId(const StringPiece& package_name,
                                                         uint8_t id) {
  ResourceTablePackage* package = table_->CreatePackage(package_name, id);
  CHECK(package != nullptr);
  return *this;
}

ResourceTableBuilder& ResourceTableBuilder::AddSimple(const StringPiece& name,
                                                      const ResourceId& id) {
  return AddValue(name, id, util::make_unique<Id>());
}

ResourceTableBuilder& ResourceTableBuilder::AddSimple(const StringPiece& name,
                                                      const ConfigDescription& config,
                                                      const ResourceId& id) {
  return AddValue(name, config, id, util::make_unique<Id>());
}

ResourceTableBuilder& ResourceTableBuilder::AddReference(const StringPiece& name,
                                                         const StringPiece& ref) {
  return AddReference(name, {}, ref);
}

ResourceTableBuilder& ResourceTableBuilder::AddReference(const StringPiece& name,
                                                         const ResourceId& id,
                                                         const StringPiece& ref) {
  return AddValue(name, id, util::make_unique<Reference>(ParseNameOrDie(ref)));
}

ResourceTableBuilder& ResourceTableBuilder::AddString(const StringPiece& name,
                                                      const StringPiece& str) {
  return AddString(name, {}, str);
}

ResourceTableBuilder& ResourceTableBuilder::AddString(const StringPiece& name, const ResourceId& id,
                                                      const StringPiece& str) {
  return AddValue(name, id, util::make_unique<String>(table_->string_pool.MakeRef(str)));
}

ResourceTableBuilder& ResourceTableBuilder::AddString(const StringPiece& name, const ResourceId& id,
                                                      const ConfigDescription& config,
                                                      const StringPiece& str) {
  return AddValue(name, config, id, util::make_unique<String>(table_->string_pool.MakeRef(str)));
}

ResourceTableBuilder& ResourceTableBuilder::AddFileReference(const StringPiece& name,
                                                             const StringPiece& path) {
  return AddFileReference(name, {}, path);
}

ResourceTableBuilder& ResourceTableBuilder::AddFileReference(const StringPiece& name,
                                                             const ResourceId& id,
                                                             const StringPiece& path) {
  return AddValue(name, id, util::make_unique<FileReference>(table_->string_pool.MakeRef(path)));
}

ResourceTableBuilder& ResourceTableBuilder::AddFileReference(const StringPiece& name,
                                                             const StringPiece& path,
                                                             const ConfigDescription& config) {
  return AddValue(name, config, {},
                  util::make_unique<FileReference>(table_->string_pool.MakeRef(path)));
}

ResourceTableBuilder& ResourceTableBuilder::AddValue(const StringPiece& name,
                                                     std::unique_ptr<Value> value) {
  return AddValue(name, {}, std::move(value));
}

ResourceTableBuilder& ResourceTableBuilder::AddValue(const StringPiece& name, const ResourceId& id,
                                                     std::unique_ptr<Value> value) {
  return AddValue(name, {}, id, std::move(value));
}

ResourceTableBuilder& ResourceTableBuilder::AddValue(const StringPiece& name,
                                                     const ConfigDescription& config,
                                                     const ResourceId& id,
                                                     std::unique_ptr<Value> value) {
  ResourceName res_name = ParseNameOrDie(name);
  CHECK(table_->AddResourceAllowMangled(res_name, id, config, {}, std::move(value),
                                        GetDiagnostics()));
  return *this;
}

ResourceTableBuilder& ResourceTableBuilder::SetSymbolState(const StringPiece& name,
                                                           const ResourceId& id, SymbolState state,
                                                           bool allow_new) {
  ResourceName res_name = ParseNameOrDie(name);
  Symbol symbol;
  symbol.state = state;
  symbol.allow_new = allow_new;
  CHECK(table_->SetSymbolStateAllowMangled(res_name, id, symbol, GetDiagnostics()));
  return *this;
}

StringPool* ResourceTableBuilder::string_pool() {
  return &table_->string_pool;
}

std::unique_ptr<ResourceTable> ResourceTableBuilder::Build() {
  return std::move(table_);
}

std::unique_ptr<Reference> BuildReference(const StringPiece& ref, const Maybe<ResourceId>& id) {
  std::unique_ptr<Reference> reference = util::make_unique<Reference>(ParseNameOrDie(ref));
  reference->id = id;
  return reference;
}

std::unique_ptr<BinaryPrimitive> BuildPrimitive(uint8_t type, uint32_t data) {
  android::Res_value value = {};
  value.size = sizeof(value);
  value.dataType = type;
  value.data = data;
  return util::make_unique<BinaryPrimitive>(value);
}

AttributeBuilder::AttributeBuilder(bool weak) : attr_(util::make_unique<Attribute>(weak)) {
  attr_->type_mask = android::ResTable_map::TYPE_ANY;
}

AttributeBuilder& AttributeBuilder::SetTypeMask(uint32_t typeMask) {
  attr_->type_mask = typeMask;
  return *this;
}

AttributeBuilder& AttributeBuilder::AddItem(const StringPiece& name, uint32_t value) {
  attr_->symbols.push_back(
      Attribute::Symbol{Reference(ResourceName({}, ResourceType::kId, name)), value});
  return *this;
}

std::unique_ptr<Attribute> AttributeBuilder::Build() {
  return std::move(attr_);
}

StyleBuilder& StyleBuilder::SetParent(const StringPiece& str) {
  style_->parent = Reference(ParseNameOrDie(str));
  return *this;
}

StyleBuilder& StyleBuilder::AddItem(const StringPiece& str, std::unique_ptr<Item> value) {
  style_->entries.push_back(Style::Entry{Reference(ParseNameOrDie(str)), std::move(value)});
  return *this;
}

StyleBuilder& StyleBuilder::AddItem(const StringPiece& str, const ResourceId& id,
                                    std::unique_ptr<Item> value) {
  AddItem(str, std::move(value));
  style_->entries.back().key.id = id;
  return *this;
}

std::unique_ptr<Style> StyleBuilder::Build() {
  return std::move(style_);
}

StyleableBuilder& StyleableBuilder::AddItem(const StringPiece& str, const Maybe<ResourceId>& id) {
  styleable_->entries.push_back(Reference(ParseNameOrDie(str)));
  styleable_->entries.back().id = id;
  return *this;
}

std::unique_ptr<Styleable> StyleableBuilder::Build() {
  return std::move(styleable_);
}

std::unique_ptr<xml::XmlResource> BuildXmlDom(const StringPiece& str) {
  std::string input = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n";
  input.append(str.data(), str.size());
  StringInputStream in(input);
  StdErrDiagnostics diag;
  std::unique_ptr<xml::XmlResource> doc = xml::Inflate(&in, &diag, Source("test.xml"));
  CHECK(doc != nullptr && doc->root != nullptr) << "failed to parse inline XML string";
  return doc;
}

std::unique_ptr<xml::XmlResource> BuildXmlDomForPackageName(IAaptContext* context,
                                                            const StringPiece& str) {
  std::unique_ptr<xml::XmlResource> doc = BuildXmlDom(str);
  doc->file.name.package = context->GetCompilationPackage();
  return doc;
}

}  // namespace test
}  // namespace aapt
