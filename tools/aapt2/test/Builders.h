/*
 * Copyright (C) 2015 The Android Open Source Project
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

#ifndef AAPT_TEST_BUILDERS_H
#define AAPT_TEST_BUILDERS_H

#include <memory>

#include "android-base/logging.h"
#include "android-base/macros.h"

#include "ResourceTable.h"
#include "ResourceValues.h"
#include "test/Common.h"
#include "util/Util.h"
#include "xml/XmlDom.h"

namespace aapt {
namespace test {

class ResourceTableBuilder {
 public:
  ResourceTableBuilder() = default;

  StringPool* string_pool() { return &table_->string_pool; }

  ResourceTableBuilder& SetPackageId(const android::StringPiece& package_name, uint8_t id) {
    ResourceTablePackage* package = table_->CreatePackage(package_name, id);
    CHECK(package != nullptr);
    return *this;
  }

  ResourceTableBuilder& AddSimple(const android::StringPiece& name, const ResourceId& id = {}) {
    return AddValue(name, id, util::make_unique<Id>());
  }

  ResourceTableBuilder& AddSimple(const android::StringPiece& name, const ConfigDescription& config,
                                  const ResourceId& id = {}) {
    return AddValue(name, config, id, util::make_unique<Id>());
  }

  ResourceTableBuilder& AddReference(const android::StringPiece& name,
                                     const android::StringPiece& ref) {
    return AddReference(name, {}, ref);
  }

  ResourceTableBuilder& AddReference(const android::StringPiece& name, const ResourceId& id,
                                     const android::StringPiece& ref) {
    return AddValue(name, id, util::make_unique<Reference>(ParseNameOrDie(ref)));
  }

  ResourceTableBuilder& AddString(const android::StringPiece& name,
                                  const android::StringPiece& str) {
    return AddString(name, {}, str);
  }

  ResourceTableBuilder& AddString(const android::StringPiece& name, const ResourceId& id,
                                  const android::StringPiece& str) {
    return AddValue(
        name, id, util::make_unique<String>(table_->string_pool.MakeRef(str)));
  }

  ResourceTableBuilder& AddString(const android::StringPiece& name, const ResourceId& id,
                                  const ConfigDescription& config,
                                  const android::StringPiece& str) {
    return AddValue(name, config, id, util::make_unique<String>(
                                          table_->string_pool.MakeRef(str)));
  }

  ResourceTableBuilder& AddFileReference(const android::StringPiece& name,
                                         const android::StringPiece& path) {
    return AddFileReference(name, {}, path);
  }

  ResourceTableBuilder& AddFileReference(const android::StringPiece& name, const ResourceId& id,
                                         const android::StringPiece& path) {
    return AddValue(name, id, util::make_unique<FileReference>(
                                  table_->string_pool.MakeRef(path)));
  }

  ResourceTableBuilder& AddFileReference(const android::StringPiece& name,
                                         const android::StringPiece& path,
                                         const ConfigDescription& config) {
    return AddValue(name, config, {}, util::make_unique<FileReference>(
                                          table_->string_pool.MakeRef(path)));
  }

  ResourceTableBuilder& AddValue(const android::StringPiece& name, std::unique_ptr<Value> value) {
    return AddValue(name, {}, std::move(value));
  }

  ResourceTableBuilder& AddValue(const android::StringPiece& name, const ResourceId& id,
                                 std::unique_ptr<Value> value) {
    return AddValue(name, {}, id, std::move(value));
  }

  ResourceTableBuilder& AddValue(const android::StringPiece& name, const ConfigDescription& config,
                                 const ResourceId& id, std::unique_ptr<Value> value) {
    ResourceName res_name = ParseNameOrDie(name);
    CHECK(table_->AddResourceAllowMangled(res_name, id, config, {}, std::move(value),
                                          GetDiagnostics()));
    return *this;
  }

  ResourceTableBuilder& SetSymbolState(const android::StringPiece& name, const ResourceId& id,
                                       SymbolState state, bool allow_new = false) {
    ResourceName res_name = ParseNameOrDie(name);
    Symbol symbol;
    symbol.state = state;
    symbol.allow_new = allow_new;
    CHECK(table_->SetSymbolStateAllowMangled(res_name, id, symbol, GetDiagnostics()));
    return *this;
  }

  std::unique_ptr<ResourceTable> Build() { return std::move(table_); }

 private:
  DISALLOW_COPY_AND_ASSIGN(ResourceTableBuilder);

  std::unique_ptr<ResourceTable> table_ = util::make_unique<ResourceTable>();
};

inline std::unique_ptr<Reference> BuildReference(const android::StringPiece& ref,
                                                 const Maybe<ResourceId>& id = {}) {
  std::unique_ptr<Reference> reference =
      util::make_unique<Reference>(ParseNameOrDie(ref));
  reference->id = id;
  return reference;
}

inline std::unique_ptr<BinaryPrimitive> BuildPrimitive(uint8_t type,
                                                       uint32_t data) {
  android::Res_value value = {};
  value.size = sizeof(value);
  value.dataType = type;
  value.data = data;
  return util::make_unique<BinaryPrimitive>(value);
}

template <typename T>
class ValueBuilder {
 public:
  template <typename... Args>
  explicit ValueBuilder(Args&&... args)
      : value_(new T{std::forward<Args>(args)...}) {}

  template <typename... Args>
  ValueBuilder& SetSource(Args&&... args) {
    value_->SetSource(Source{std::forward<Args>(args)...});
    return *this;
  }

  ValueBuilder& SetComment(const android::StringPiece& str) {
    value_->SetComment(str);
    return *this;
  }

  std::unique_ptr<Value> Build() { return std::move(value_); }

 private:
  DISALLOW_COPY_AND_ASSIGN(ValueBuilder);

  std::unique_ptr<Value> value_;
};

class AttributeBuilder {
 public:
  explicit AttributeBuilder(bool weak = false)
      : attr_(util::make_unique<Attribute>(weak)) {
    attr_->type_mask = android::ResTable_map::TYPE_ANY;
  }

  AttributeBuilder& SetTypeMask(uint32_t typeMask) {
    attr_->type_mask = typeMask;
    return *this;
  }

  AttributeBuilder& AddItem(const android::StringPiece& name, uint32_t value) {
    attr_->symbols.push_back(Attribute::Symbol{
        Reference(ResourceName({}, ResourceType::kId, name)), value});
    return *this;
  }

  std::unique_ptr<Attribute> Build() { return std::move(attr_); }

 private:
  DISALLOW_COPY_AND_ASSIGN(AttributeBuilder);

  std::unique_ptr<Attribute> attr_;
};

class StyleBuilder {
 public:
  StyleBuilder() = default;

  StyleBuilder& SetParent(const android::StringPiece& str) {
    style_->parent = Reference(ParseNameOrDie(str));
    return *this;
  }

  StyleBuilder& AddItem(const android::StringPiece& str, std::unique_ptr<Item> value) {
    style_->entries.push_back(
        Style::Entry{Reference(ParseNameOrDie(str)), std::move(value)});
    return *this;
  }

  StyleBuilder& AddItem(const android::StringPiece& str, const ResourceId& id,
                        std::unique_ptr<Item> value) {
    AddItem(str, std::move(value));
    style_->entries.back().key.id = id;
    return *this;
  }

  std::unique_ptr<Style> Build() { return std::move(style_); }

 private:
  DISALLOW_COPY_AND_ASSIGN(StyleBuilder);

  std::unique_ptr<Style> style_ = util::make_unique<Style>();
};

class StyleableBuilder {
 public:
  StyleableBuilder() = default;

  StyleableBuilder& AddItem(const android::StringPiece& str, const Maybe<ResourceId>& id = {}) {
    styleable_->entries.push_back(Reference(ParseNameOrDie(str)));
    styleable_->entries.back().id = id;
    return *this;
  }

  std::unique_ptr<Styleable> Build() { return std::move(styleable_); }

 private:
  DISALLOW_COPY_AND_ASSIGN(StyleableBuilder);

  std::unique_ptr<Styleable> styleable_ = util::make_unique<Styleable>();
};

inline std::unique_ptr<xml::XmlResource> BuildXmlDom(const android::StringPiece& str) {
  std::stringstream in;
  in << "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" << str;
  StdErrDiagnostics diag;
  std::unique_ptr<xml::XmlResource> doc =
      xml::Inflate(&in, &diag, Source("test.xml"));
  CHECK(doc != nullptr) << "failed to parse inline XML string";
  return doc;
}

inline std::unique_ptr<xml::XmlResource> BuildXmlDomForPackageName(
    IAaptContext* context, const android::StringPiece& str) {
  std::unique_ptr<xml::XmlResource> doc = BuildXmlDom(str);
  doc->file.name.package = context->GetCompilationPackage();
  return doc;
}

}  // namespace test
}  // namespace aapt

#endif /* AAPT_TEST_BUILDERS_H */
