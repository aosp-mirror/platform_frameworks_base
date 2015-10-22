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
#include "util/Util.h"
#include "XmlDom.h"

#include "test/Common.h"

#include <memory>

namespace aapt {
namespace test {

class ResourceTableBuilder {
private:
    DummyDiagnosticsImpl mDiagnostics;
    std::unique_ptr<ResourceTable> mTable = util::make_unique<ResourceTable>();

public:
    ResourceTableBuilder() = default;

    ResourceTableBuilder& setPackageId(const StringPiece16& packageName, uint8_t id) {
        ResourceTablePackage* package = mTable->createPackage(packageName, id);
        assert(package);
        return *this;
    }

    ResourceTableBuilder& addSimple(const StringPiece16& name, ResourceId id = {}) {
        return addValue(name, id, util::make_unique<Id>());
    }

    ResourceTableBuilder& addReference(const StringPiece16& name, const StringPiece16& ref) {
        return addReference(name, {}, ref);
    }

    ResourceTableBuilder& addReference(const StringPiece16& name, ResourceId id,
                                       const StringPiece16& ref) {
        return addValue(name, id, util::make_unique<Reference>(parseNameOrDie(ref)));
    }

    ResourceTableBuilder& addString(const StringPiece16& name, const StringPiece16& str) {
        return addString(name, {}, str);
    }

    ResourceTableBuilder& addString(const StringPiece16& name, ResourceId id,
                                    const StringPiece16& str) {
        return addValue(name, id, util::make_unique<String>(mTable->stringPool.makeRef(str)));
    }

    ResourceTableBuilder& addFileReference(const StringPiece16& name, const StringPiece16& path) {
        return addFileReference(name, {}, path);
    }

    ResourceTableBuilder& addFileReference(const StringPiece16& name, ResourceId id,
                                           const StringPiece16& path) {
        return addValue(name, id,
                        util::make_unique<FileReference>(mTable->stringPool.makeRef(path)));
    }


    ResourceTableBuilder& addValue(const StringPiece16& name, std::unique_ptr<Value> value) {
        return addValue(name, {}, std::move(value));
    }

    ResourceTableBuilder& addValue(const StringPiece16& name, ResourceId id,
                                       std::unique_ptr<Value> value) {
        return addValue(name, id, {}, std::move(value));
    }

    ResourceTableBuilder& addValue(const StringPiece16& name, ResourceId id,
                                   const ConfigDescription& config, std::unique_ptr<Value> value) {
        ResourceName resName = parseNameOrDie(name);
        bool result = mTable->addResourceAllowMangled(resName, id, config, {}, std::move(value),
                                                      &mDiagnostics);
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

inline std::unique_ptr<XmlResource> buildXmlDom(const StringPiece& str) {
    std::stringstream in;
    in << "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" << str;
    StdErrDiagnostics diag;
    std::unique_ptr<XmlResource> doc = xml::inflate(&in, &diag, {});
    assert(doc);
    return doc;
}

} // namespace test
} // namespace aapt

#endif /* AAPT_TEST_BUILDERS_H */
