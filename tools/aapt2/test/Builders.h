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

#include "android-base/macros.h"

#include "Resource.h"
#include "ResourceTable.h"
#include "ResourceValues.h"
#include "process/IResourceTableConsumer.h"
#include "util/Maybe.h"
#include "xml/XmlDom.h"

namespace aapt {
namespace test {

class ResourceTableBuilder {
 public:
  ResourceTableBuilder() = default;

  ResourceTableBuilder& SetPackageId(const android::StringPiece& package_name, uint8_t id);
  ResourceTableBuilder& AddSimple(const android::StringPiece& name, const ResourceId& id = {});
  ResourceTableBuilder& AddSimple(const android::StringPiece& name, const ConfigDescription& config,
                                  const ResourceId& id = {});
  ResourceTableBuilder& AddReference(const android::StringPiece& name,
                                     const android::StringPiece& ref);
  ResourceTableBuilder& AddReference(const android::StringPiece& name, const ResourceId& id,
                                     const android::StringPiece& ref);
  ResourceTableBuilder& AddString(const android::StringPiece& name,
                                  const android::StringPiece& str);
  ResourceTableBuilder& AddString(const android::StringPiece& name, const ResourceId& id,
                                  const android::StringPiece& str);
  ResourceTableBuilder& AddString(const android::StringPiece& name, const ResourceId& id,
                                  const ConfigDescription& config, const android::StringPiece& str);
  ResourceTableBuilder& AddFileReference(const android::StringPiece& name,
                                         const android::StringPiece& path);
  ResourceTableBuilder& AddFileReference(const android::StringPiece& name, const ResourceId& id,
                                         const android::StringPiece& path);
  ResourceTableBuilder& AddFileReference(const android::StringPiece& name,
                                         const android::StringPiece& path,
                                         const ConfigDescription& config);
  ResourceTableBuilder& AddValue(const android::StringPiece& name, std::unique_ptr<Value> value);
  ResourceTableBuilder& AddValue(const android::StringPiece& name, const ResourceId& id,
                                 std::unique_ptr<Value> value);
  ResourceTableBuilder& AddValue(const android::StringPiece& name, const ConfigDescription& config,
                                 const ResourceId& id, std::unique_ptr<Value> value);
  ResourceTableBuilder& SetSymbolState(const android::StringPiece& name, const ResourceId& id,
                                       SymbolState state, bool allow_new = false);

  StringPool* string_pool();
  std::unique_ptr<ResourceTable> Build();

 private:
  DISALLOW_COPY_AND_ASSIGN(ResourceTableBuilder);

  std::unique_ptr<ResourceTable> table_ = util::make_unique<ResourceTable>();
};

std::unique_ptr<Reference> BuildReference(const android::StringPiece& ref,
                                          const Maybe<ResourceId>& id = {});
std::unique_ptr<BinaryPrimitive> BuildPrimitive(uint8_t type, uint32_t data);

template <typename T>
class ValueBuilder {
 public:
  template <typename... Args>
  explicit ValueBuilder(Args&&... args) : value_(new T{std::forward<Args>(args)...}) {
  }

  template <typename... Args>
  ValueBuilder& SetSource(Args&&... args) {
    value_->SetSource(Source{std::forward<Args>(args)...});
    return *this;
  }

  ValueBuilder& SetComment(const android::StringPiece& str) {
    value_->SetComment(str);
    return *this;
  }

  std::unique_ptr<Value> Build() {
    return std::move(value_);
  }

 private:
  DISALLOW_COPY_AND_ASSIGN(ValueBuilder);

  std::unique_ptr<Value> value_;
};

class AttributeBuilder {
 public:
  explicit AttributeBuilder(bool weak = false);
  AttributeBuilder& SetTypeMask(uint32_t typeMask);
  AttributeBuilder& AddItem(const android::StringPiece& name, uint32_t value);
  std::unique_ptr<Attribute> Build();

 private:
  DISALLOW_COPY_AND_ASSIGN(AttributeBuilder);

  std::unique_ptr<Attribute> attr_;
};

class StyleBuilder {
 public:
  StyleBuilder() = default;
  StyleBuilder& SetParent(const android::StringPiece& str);
  StyleBuilder& AddItem(const android::StringPiece& str, std::unique_ptr<Item> value);
  StyleBuilder& AddItem(const android::StringPiece& str, const ResourceId& id,
                        std::unique_ptr<Item> value);
  std::unique_ptr<Style> Build();

 private:
  DISALLOW_COPY_AND_ASSIGN(StyleBuilder);

  std::unique_ptr<Style> style_ = util::make_unique<Style>();
};

class StyleableBuilder {
 public:
  StyleableBuilder() = default;
  StyleableBuilder& AddItem(const android::StringPiece& str, const Maybe<ResourceId>& id = {});
  std::unique_ptr<Styleable> Build();

 private:
  DISALLOW_COPY_AND_ASSIGN(StyleableBuilder);

  std::unique_ptr<Styleable> styleable_ = util::make_unique<Styleable>();
};

std::unique_ptr<xml::XmlResource> BuildXmlDom(const android::StringPiece& str);
std::unique_ptr<xml::XmlResource> BuildXmlDomForPackageName(IAaptContext* context,
                                                            const android::StringPiece& str);

}  // namespace test
}  // namespace aapt

#endif /* AAPT_TEST_BUILDERS_H */
