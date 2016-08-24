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

#include "ResourceTable.h"
#include "ResourceValues.h"
#include "test/Common.h"
#include "util/Util.h"
#include "xml/XmlDom.h"

#include <memory>

namespace aapt {
namespace test {

class ResourceTableBuilder {
private:
    DummyDiagnosticsImpl mDiagnostics;
    std::unique_ptr<ResourceTable> mTable = util::make_unique<ResourceTable>();

public:
    ResourceTableBuilder() = default;

    StringPool* getStringPool() {
        return &mTable->stringPool;
    }

    ResourceTableBuilder& setPackageId(const StringPiece16& packageName, uint8_t id) {
        ResourceTablePackage* package = mTable->createPackage(packageName, id);
        assert(package);
        return *this;
    }

    ResourceTableBuilder& addSimple(const StringPiece16& name, const ResourceId id = {}) {
        return addValue(name, id, util::make_unique<Id>());
    }

    ResourceTableBuilder& addReference(const StringPiece16& name, const StringPiece16& ref) {
        return addReference(name, {}, ref);
    }

    ResourceTableBuilder& addReference(const StringPiece16& name, const ResourceId id,
                                       const StringPiece16& ref) {
        return addValue(name, id, util::make_unique<Reference>(parseNameOrDie(ref)));
    }

    ResourceTableBuilder& addString(const StringPiece16& name, const StringPiece16& str) {
        return addString(name, {}, str);
    }

    ResourceTableBuilder& addString(const StringPiece16& name, const ResourceId id,
                                    const StringPiece16& str) {
        return addValue(name, id, util::make_unique<String>(mTable->stringPool.makeRef(str)));
    }

    ResourceTableBuilder& addString(const StringPiece16& name, const ResourceId id,
                                    const ConfigDescription& config, const StringPiece16& str) {
        return addValue(name, id, config,
                        util::make_unique<String>(mTable->stringPool.makeRef(str)));
    }

    ResourceTableBuilder& addFileReference(const StringPiece16& name, const StringPiece16& path) {
        return addFileReference(name, {}, path);
    }

    ResourceTableBuilder& addFileReference(const StringPiece16& name, const ResourceId id,
                                           const StringPiece16& path) {
        return addValue(name, id,
                        util::make_unique<FileReference>(mTable->stringPool.makeRef(path)));
    }

    ResourceTableBuilder& addFileReference(const StringPiece16& name, const StringPiece16& path,
                                           const ConfigDescription& config) {
        return addValue(name, {}, config,
                        util::make_unique<FileReference>(mTable->stringPool.makeRef(path)));
    }

    ResourceTableBuilder& addValue(const StringPiece16& name,
                                   std::unique_ptr<Value> value) {
        return addValue(name, {}, std::move(value));
    }

    ResourceTableBuilder& addValue(const StringPiece16& name, const ResourceId id,
                                       std::unique_ptr<Value> value) {
        return addValue(name, id, {}, std::move(value));
    }

    ResourceTableBuilder& addValue(const StringPiece16& name, const ResourceId id,
                                   const ConfigDescription& config,
                                   std::unique_ptr<Value> value) {
        ResourceName resName = parseNameOrDie(name);
        bool result = mTable->addResourceAllowMangled(resName, id, config, std::string(),
                                                      std::move(value), &mDiagnostics);
        assert(result);
        return *this;
    }

    ResourceTableBuilder& setSymbolState(const StringPiece16& name, ResourceId id,
                                         SymbolState state) {
        ResourceName resName = parseNameOrDie(name);
        Symbol symbol;
        symbol.state = state;
        bool result = mTable->setSymbolStateAllowMangled(resName, id, symbol, &mDiagnostics);
        assert(result);
        return *this;
    }

    std::unique_ptr<ResourceTable> build() {
        return std::move(mTable);
    }
};

inline std::unique_ptr<Reference> buildReference(const StringPiece16& ref,
                                                 Maybe<ResourceId> id = {}) {
    std::unique_ptr<Reference> reference = util::make_unique<Reference>(parseNameOrDie(ref));
    reference->id = id;
    return reference;
}

inline std::unique_ptr<BinaryPrimitive> buildPrimitive(uint8_t type, uint32_t data) {
    android::Res_value value = {};
    value.size = sizeof(value);
    value.dataType = type;
    value.data = data;
    return util::make_unique<BinaryPrimitive>(value);
}

template <typename T>
class ValueBuilder {
private:
    std::unique_ptr<Value> mValue;

public:
    template <typename... Args>
    ValueBuilder(Args&&... args) : mValue(new T{ std::forward<Args>(args)... }) {
    }

    template <typename... Args>
    ValueBuilder& setSource(Args&&... args) {
        mValue->setSource(Source{ std::forward<Args>(args)... });
        return *this;
    }

    ValueBuilder& setComment(const StringPiece16& str) {
        mValue->setComment(str);
        return *this;
    }

    std::unique_ptr<Value> build() {
        return std::move(mValue);
    }
};

class AttributeBuilder {
private:
    std::unique_ptr<Attribute> mAttr;

public:
    AttributeBuilder(bool weak = false) : mAttr(util::make_unique<Attribute>(weak)) {
        mAttr->typeMask = android::ResTable_map::TYPE_ANY;
    }

    AttributeBuilder& setTypeMask(uint32_t typeMask) {
        mAttr->typeMask = typeMask;
        return *this;
    }

    AttributeBuilder& addItem(const StringPiece16& name, uint32_t value) {
        mAttr->symbols.push_back(Attribute::Symbol{
                Reference(ResourceName{ {}, ResourceType::kId, name.toString()}),
                value});
        return *this;
    }

    std::unique_ptr<Attribute> build() {
        return std::move(mAttr);
    }
};

class StyleBuilder {
private:
    std::unique_ptr<Style> mStyle = util::make_unique<Style>();

public:
    StyleBuilder& setParent(const StringPiece16& str) {
        mStyle->parent = Reference(parseNameOrDie(str));
        return *this;
    }

    StyleBuilder& addItem(const StringPiece16& str, std::unique_ptr<Item> value) {
        mStyle->entries.push_back(Style::Entry{ Reference(parseNameOrDie(str)), std::move(value) });
        return *this;
    }

    StyleBuilder& addItem(const StringPiece16& str, ResourceId id, std::unique_ptr<Item> value) {
        addItem(str, std::move(value));
        mStyle->entries.back().key.id = id;
        return *this;
    }

    std::unique_ptr<Style> build() {
        return std::move(mStyle);
    }
};

class StyleableBuilder {
private:
    std::unique_ptr<Styleable> mStyleable = util::make_unique<Styleable>();

public:
    StyleableBuilder& addItem(const StringPiece16& str, Maybe<ResourceId> id = {}) {
        mStyleable->entries.push_back(Reference(parseNameOrDie(str)));
        mStyleable->entries.back().id = id;
        return *this;
    }

    std::unique_ptr<Styleable> build() {
        return std::move(mStyleable);
    }
};

inline std::unique_ptr<xml::XmlResource> buildXmlDom(const StringPiece& str) {
    std::stringstream in;
    in << "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" << str;
    StdErrDiagnostics diag;
    std::unique_ptr<xml::XmlResource> doc = xml::inflate(&in, &diag, Source("test.xml"));
    assert(doc);
    return doc;
}

inline std::unique_ptr<xml::XmlResource> buildXmlDomForPackageName(IAaptContext* context,
                                                                   const StringPiece& str) {
    std::unique_ptr<xml::XmlResource> doc = buildXmlDom(str);
    doc->file.name.package = context->getCompilationPackage();
    return doc;
}

} // namespace test
} // namespace aapt

#endif /* AAPT_TEST_BUILDERS_H */
