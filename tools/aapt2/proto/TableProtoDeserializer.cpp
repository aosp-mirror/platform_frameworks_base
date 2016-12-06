/*
 * Copyright (C) 2016 The Android Open Source Project
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

#include "ResourceTable.h"
#include "ResourceUtils.h"
#include "ValueVisitor.h"
#include "proto/ProtoHelpers.h"
#include "proto/ProtoSerialize.h"

#include <androidfw/ResourceTypes.h>

namespace aapt {

namespace {

class ReferenceIdToNameVisitor : public ValueVisitor {
public:
    using ValueVisitor::visit;

    ReferenceIdToNameVisitor(const std::map<ResourceId, ResourceNameRef>* mapping) :
            mMapping(mapping) {
        assert(mMapping);
    }

    void visit(Reference* reference) override {
        if (!reference->id || !reference->id.value().isValid()) {
            return;
        }

        ResourceId id = reference->id.value();
        auto cacheIter = mMapping->find(id);
        if (cacheIter != mMapping->end()) {
            reference->name = cacheIter->second.toResourceName();
        }
    }

private:
    const std::map<ResourceId, ResourceNameRef>* mMapping;
};

class PackagePbDeserializer {
public:
    PackagePbDeserializer(const android::ResStringPool* valuePool,
                          const android::ResStringPool* sourcePool,
                          const android::ResStringPool* symbolPool,
                          const Source& source, IDiagnostics* diag) :
            mValuePool(valuePool), mSourcePool(sourcePool), mSymbolPool(symbolPool),
            mSource(source), mDiag(diag) {
    }

public:
    bool deserializeFromPb(const pb::Package& pbPackage, ResourceTable* table) {
        Maybe<uint8_t> id;
        if (pbPackage.has_package_id()) {
            id = static_cast<uint8_t>(pbPackage.package_id());
        }

        std::map<ResourceId, ResourceNameRef> idIndex;

        ResourceTablePackage* pkg = table->createPackage(
                util::utf8ToUtf16(pbPackage.package_name()), id);
        for (const pb::Type& pbType : pbPackage.types()) {
            const ResourceType* resType = parseResourceType(util::utf8ToUtf16(pbType.name()));
            if (!resType) {
                mDiag->error(DiagMessage(mSource) << "unknown type '" << pbType.name() << "'");
                return {};
            }

            ResourceTableType* type = pkg->findOrCreateType(*resType);

            for (const pb::Entry& pbEntry : pbType.entries()) {
                ResourceEntry* entry = type->findOrCreateEntry(util::utf8ToUtf16(pbEntry.name()));

                // Deserialize the symbol status (public/private with source and comments).
                if (pbEntry.has_symbol_status()) {
                    const pb::SymbolStatus& pbStatus = pbEntry.symbol_status();
                    if (pbStatus.has_source()) {
                        deserializeSourceFromPb(pbStatus.source(), *mSourcePool,
                                                &entry->symbolStatus.source);
                    }

                    if (pbStatus.has_comment()) {
                        entry->symbolStatus.comment = util::utf8ToUtf16(pbStatus.comment());
                    }

                    SymbolState visibility = deserializeVisibilityFromPb(pbStatus.visibility());
                    entry->symbolStatus.state = visibility;

                    if (visibility == SymbolState::kPublic) {
                        // This is a public symbol, we must encode the ID now if there is one.
                        if (pbEntry.has_id()) {
                            entry->id = static_cast<uint16_t>(pbEntry.id());
                        }

                        if (type->symbolStatus.state != SymbolState::kPublic) {
                            // If the type has not been made public, do so now.
                            type->symbolStatus.state = SymbolState::kPublic;
                            if (pbType.has_id()) {
                                type->id = static_cast<uint8_t>(pbType.id());
                            }
                        }
                    } else if (visibility == SymbolState::kPrivate) {
                        if (type->symbolStatus.state == SymbolState::kUndefined) {
                            type->symbolStatus.state = SymbolState::kPrivate;
                        }
                    }
                }

                ResourceId resId(pbPackage.package_id(), pbType.id(), pbEntry.id());
                if (resId.isValid()) {
                    idIndex[resId] = ResourceNameRef(pkg->name, type->type, entry->name);
                }

                for (const pb::ConfigValue& pbConfigValue : pbEntry.config_values()) {
                    const pb::ConfigDescription& pbConfig = pbConfigValue.config();

                    ConfigDescription config;
                    if (!deserializeConfigDescriptionFromPb(pbConfig, &config)) {
                        mDiag->error(DiagMessage(mSource) << "invalid configuration");
                        return {};
                    }

                    ResourceConfigValue* configValue = entry->findOrCreateValue(config,
                                                                                pbConfig.product());
                    if (configValue->value) {
                        // Duplicate config.
                        mDiag->error(DiagMessage(mSource) << "duplicate configuration");
                        return {};
                    }

                    configValue->value = deserializeValueFromPb(pbConfigValue.value(),
                                                                config, &table->stringPool);
                    if (!configValue->value) {
                        return {};
                    }
                }
            }
        }

        ReferenceIdToNameVisitor visitor(&idIndex);
        visitAllValuesInPackage(pkg, &visitor);
        return true;
    }

private:
    std::unique_ptr<Item> deserializeItemFromPb(const pb::Item& pbItem,
                                                const ConfigDescription& config,
                                                StringPool* pool) {
        if (pbItem.has_ref()) {
            const pb::Reference& pbRef = pbItem.ref();
            std::unique_ptr<Reference> ref = util::make_unique<Reference>();
            if (!deserializeReferenceFromPb(pbRef, ref.get())) {
                return {};
            }
            return std::move(ref);

        } else if (pbItem.has_prim()) {
            const pb::Primitive& pbPrim = pbItem.prim();
            android::Res_value prim = {};
            prim.dataType = static_cast<uint8_t>(pbPrim.type());
            prim.data = pbPrim.data();
            return util::make_unique<BinaryPrimitive>(prim);

        } else if (pbItem.has_id()) {
            return util::make_unique<Id>();

        } else if (pbItem.has_str()) {
            const uint32_t idx = pbItem.str().idx();
            StringPiece16 str = util::getString(*mValuePool, idx);

            const android::ResStringPool_span* spans = mValuePool->styleAt(idx);
            if (spans && spans->name.index != android::ResStringPool_span::END) {
                StyleString styleStr = { str.toString() };
                while (spans->name.index != android::ResStringPool_span::END) {
                    styleStr.spans.push_back(Span{
                            util::getString(*mValuePool, spans->name.index).toString(),
                            spans->firstChar,
                            spans->lastChar
                    });
                    spans++;
                }
                return util::make_unique<StyledString>(
                        pool->makeRef(styleStr, StringPool::Context{ 1, config }));
            }
            return util::make_unique<String>(
                    pool->makeRef(str, StringPool::Context{ 1, config }));

        } else if (pbItem.has_raw_str()) {
            const uint32_t idx = pbItem.raw_str().idx();
            StringPiece16 str = util::getString(*mValuePool, idx);
            return util::make_unique<RawString>(
                    pool->makeRef(str, StringPool::Context{ 1, config }));

        } else if (pbItem.has_file()) {
            const uint32_t idx = pbItem.file().path_idx();
            StringPiece16 str = util::getString(*mValuePool, idx);
            return util::make_unique<FileReference>(
                    pool->makeRef(str, StringPool::Context{ 0, config }));

        } else {
            mDiag->error(DiagMessage(mSource) << "unknown item");
        }
        return {};
    }

    std::unique_ptr<Value> deserializeValueFromPb(const pb::Value& pbValue,
                                                  const ConfigDescription& config,
                                                  StringPool* pool) {
        const bool isWeak = pbValue.has_weak() ? pbValue.weak() : false;

        std::unique_ptr<Value> value;
        if (pbValue.has_item()) {
            value = deserializeItemFromPb(pbValue.item(), config, pool);
            if (!value) {
                return {};
            }

        } else if (pbValue.has_compound_value()) {
            const pb::CompoundValue pbCompoundValue = pbValue.compound_value();
            if (pbCompoundValue.has_attr()) {
                const pb::Attribute& pbAttr = pbCompoundValue.attr();
                std::unique_ptr<Attribute> attr = util::make_unique<Attribute>(isWeak);
                attr->typeMask = pbAttr.format_flags();
                attr->minInt = pbAttr.min_int();
                attr->maxInt = pbAttr.max_int();
                for (const pb::Attribute_Symbol& pbSymbol : pbAttr.symbols()) {
                    Attribute::Symbol symbol;
                    deserializeItemCommon(pbSymbol, &symbol.symbol);
                    if (!deserializeReferenceFromPb(pbSymbol.name(), &symbol.symbol)) {
                        return {};
                    }
                    symbol.value = pbSymbol.value();
                    attr->symbols.push_back(std::move(symbol));
                }
                value = std::move(attr);

            } else if (pbCompoundValue.has_style()) {
                const pb::Style& pbStyle = pbCompoundValue.style();
                std::unique_ptr<Style> style = util::make_unique<Style>();
                if (pbStyle.has_parent()) {
                    style->parent = Reference();
                    if (!deserializeReferenceFromPb(pbStyle.parent(), &style->parent.value())) {
                        return {};
                    }

                    if (pbStyle.has_parent_source()) {
                        Source parentSource;
                        deserializeSourceFromPb(pbStyle.parent_source(), *mSourcePool,
                                                &parentSource);
                        style->parent.value().setSource(std::move(parentSource));
                    }
                }

                for (const pb::Style_Entry& pbEntry : pbStyle.entries()) {
                    Style::Entry entry;
                    deserializeItemCommon(pbEntry, &entry.key);
                    if (!deserializeReferenceFromPb(pbEntry.key(), &entry.key)) {
                        return {};
                    }

                    entry.value = deserializeItemFromPb(pbEntry.item(), config, pool);
                    if (!entry.value) {
                        return {};
                    }

                    deserializeItemCommon(pbEntry, entry.value.get());
                    style->entries.push_back(std::move(entry));
                }
                value = std::move(style);

            } else if (pbCompoundValue.has_styleable()) {
                const pb::Styleable& pbStyleable = pbCompoundValue.styleable();
                std::unique_ptr<Styleable> styleable = util::make_unique<Styleable>();
                for (const pb::Styleable_Entry& pbEntry : pbStyleable.entries()) {
                    Reference attrRef;
                    deserializeItemCommon(pbEntry, &attrRef);
                    deserializeReferenceFromPb(pbEntry.attr(), &attrRef);
                    styleable->entries.push_back(std::move(attrRef));
                }
                value = std::move(styleable);

            } else if (pbCompoundValue.has_array()) {
                const pb::Array& pbArray = pbCompoundValue.array();
                std::unique_ptr<Array> array = util::make_unique<Array>();
                for (const pb::Array_Entry& pbEntry : pbArray.entries()) {
                    std::unique_ptr<Item> item = deserializeItemFromPb(pbEntry.item(), config,
                                                                       pool);
                    if (!item) {
                        return {};
                    }

                    deserializeItemCommon(pbEntry, item.get());
                    array->items.push_back(std::move(item));
                }
                value = std::move(array);

            } else if (pbCompoundValue.has_plural()) {
                const pb::Plural& pbPlural = pbCompoundValue.plural();
                std::unique_ptr<Plural> plural = util::make_unique<Plural>();
                for (const pb::Plural_Entry& pbEntry : pbPlural.entries()) {
                    size_t pluralIdx = deserializePluralEnumFromPb(pbEntry.arity());
                    plural->values[pluralIdx] = deserializeItemFromPb(pbEntry.item(), config,
                                                                      pool);
                    if (!plural->values[pluralIdx]) {
                        return {};
                    }

                    deserializeItemCommon(pbEntry, plural->values[pluralIdx].get());
                }
                value = std::move(plural);

            } else {
                mDiag->error(DiagMessage(mSource) << "unknown compound value");
                return {};
            }
        } else {
            mDiag->error(DiagMessage(mSource) << "unknown value");
            return {};
        }

        assert(value && "forgot to set value");

        value->setWeak(isWeak);
        deserializeItemCommon(pbValue, value.get());
        return value;
    }

    bool deserializeReferenceFromPb(const pb::Reference& pbRef, Reference* outRef) {
        outRef->referenceType = deserializeReferenceTypeFromPb(pbRef.type());
        outRef->privateReference = pbRef.private_();

        if (!pbRef.has_id() && !pbRef.has_symbol_idx()) {
            return false;
        }

        if (pbRef.has_id()) {
            outRef->id = ResourceId(pbRef.id());
        }

        if (pbRef.has_symbol_idx()) {
            StringPiece16 strSymbol = util::getString(*mSymbolPool, pbRef.symbol_idx());
            ResourceNameRef nameRef;
            if (!ResourceUtils::parseResourceName(strSymbol, &nameRef, nullptr)) {
                mDiag->error(DiagMessage(mSource) << "invalid reference name '"
                             << strSymbol << "'");
                return false;
            }

            outRef->name = nameRef.toResourceName();
        }
        return true;
    }

    template <typename T>
    void deserializeItemCommon(const T& pbItem, Value* outValue) {
        if (pbItem.has_source()) {
            Source source;
            deserializeSourceFromPb(pbItem.source(), *mSourcePool, &source);
            outValue->setSource(std::move(source));
        }

        if (pbItem.has_comment()) {
            outValue->setComment(util::utf8ToUtf16(pbItem.comment()));
        }
    }

private:
    const android::ResStringPool* mValuePool;
    const android::ResStringPool* mSourcePool;
    const android::ResStringPool* mSymbolPool;
    const Source mSource;
    IDiagnostics* mDiag;
};

} // namespace

std::unique_ptr<ResourceTable> deserializeTableFromPb(const pb::ResourceTable& pbTable,
                                                      const Source& source,
                                                      IDiagnostics* diag) {
    // We import the android namespace because on Windows NO_ERROR is a macro, not an enum, which
    // causes errors when qualifying it with android::
    using namespace android;

    std::unique_ptr<ResourceTable> table = util::make_unique<ResourceTable>();

    if (!pbTable.has_string_pool()) {
        diag->error(DiagMessage(source) << "no string pool found");
        return {};
    }

    ResStringPool valuePool;
    status_t result = valuePool.setTo(pbTable.string_pool().data().data(),
                                      pbTable.string_pool().data().size());
    if (result != NO_ERROR) {
        diag->error(DiagMessage(source) << "invalid string pool");
        return {};
    }

    ResStringPool sourcePool;
    if (pbTable.has_source_pool()) {
        result = sourcePool.setTo(pbTable.source_pool().data().data(),
                                  pbTable.source_pool().data().size());
        if (result != NO_ERROR) {
            diag->error(DiagMessage(source) << "invalid source pool");
            return {};
        }
    }

    ResStringPool symbolPool;
    if (pbTable.has_symbol_pool()) {
        result = symbolPool.setTo(pbTable.symbol_pool().data().data(),
                                  pbTable.symbol_pool().data().size());
        if (result != NO_ERROR) {
            diag->error(DiagMessage(source) << "invalid symbol pool");
            return {};
        }
    }

    PackagePbDeserializer packagePbDeserializer(&valuePool, &sourcePool, &symbolPool, source, diag);
    for (const pb::Package& pbPackage : pbTable.packages()) {
        if (!packagePbDeserializer.deserializeFromPb(pbPackage, table.get())) {
            return {};
        }
    }
    return table;
}

std::unique_ptr<ResourceFile> deserializeCompiledFileFromPb(const pb::CompiledFile& pbFile,
                                                            const Source& source,
                                                            IDiagnostics* diag) {
    std::unique_ptr<ResourceFile> file = util::make_unique<ResourceFile>();

    ResourceNameRef nameRef;

    // Need to create an lvalue here so that nameRef can point to something real.
    std::u16string utf16Name = util::utf8ToUtf16(pbFile.resource_name());
    if (!ResourceUtils::parseResourceName(utf16Name, &nameRef)) {
        diag->error(DiagMessage(source) << "invalid resource name in compiled file header: "
                    << pbFile.resource_name());
        return {};
    }
    file->name = nameRef.toResourceName();
    file->source.path = pbFile.source_path();
    deserializeConfigDescriptionFromPb(pbFile.config(), &file->config);

    for (const pb::CompiledFile_Symbol& pbSymbol : pbFile.exported_symbols()) {
        // Need to create an lvalue here so that nameRef can point to something real.
        utf16Name = util::utf8ToUtf16(pbSymbol.resource_name());
        if (!ResourceUtils::parseResourceName(utf16Name, &nameRef)) {
            diag->error(DiagMessage(source) << "invalid resource name for exported symbol in "
                                               "compiled file header: "
                                            << pbFile.resource_name());
            return {};
        }
        file->exportedSymbols.push_back(
                SourcedResourceName{ nameRef.toResourceName(), pbSymbol.line_no() });
    }
    return file;
}

CompiledFileInputStream::CompiledFileInputStream(const void* data, size_t size) :
        mIn(static_cast<const uint8_t*>(data), size), mPbFile(),
        mData(static_cast<const uint8_t*>(data)), mSize(size) {
}

const pb::CompiledFile* CompiledFileInputStream::CompiledFile() {
    if (!mPbFile) {
        std::unique_ptr<pb::CompiledFile> pbFile = util::make_unique<pb::CompiledFile>();
        uint64_t pbSize = 0u;
        if (!mIn.ReadLittleEndian64(&pbSize)) {
            return nullptr;
        }
        mIn.PushLimit(static_cast<int>(pbSize));
        if (!pbFile->ParsePartialFromCodedStream(&mIn)) {
            return nullptr;
        }

        const size_t padding = 4 - (pbSize & 0x03);
        const size_t offset = sizeof(uint64_t) + pbSize + padding;
        if (offset > mSize) {
            return nullptr;
        }

        mData += offset;
        mSize -= offset;
        mPbFile = std::move(pbFile);
    }
    return mPbFile.get();
}

const void* CompiledFileInputStream::data() {
    if (!mPbFile) {
        if (!CompiledFile()) {
            return nullptr;
        }
    }
    return mData;
}

size_t CompiledFileInputStream::size() {
    if (!mPbFile) {
        if (!CompiledFile()) {
            return 0;
        }
    }
    return mSize;
}

} // namespace aapt
