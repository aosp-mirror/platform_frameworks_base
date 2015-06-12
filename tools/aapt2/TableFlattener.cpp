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

#include "BigBuffer.h"
#include "ConfigDescription.h"
#include "Logger.h"
#include "ResourceTable.h"
#include "ResourceTypeExtensions.h"
#include "ResourceValues.h"
#include "StringPool.h"
#include "TableFlattener.h"
#include "Util.h"

#include <algorithm>
#include <androidfw/ResourceTypes.h>
#include <sstream>

namespace aapt {

struct FlatEntry {
    const ResourceEntry* entry;
    const Value* value;
    uint32_t entryKey;
    uint32_t sourcePathKey;
    uint32_t sourceLine;
};

/**
 * Visitor that knows how to encode Map values.
 */
class MapFlattener : public ConstValueVisitor {
public:
    MapFlattener(BigBuffer* out, const FlatEntry& flatEntry, SymbolEntryVector* symbols) :
            mOut(out), mSymbols(symbols) {
        mMap = mOut->nextBlock<android::ResTable_map_entry>();
        mMap->key.index = flatEntry.entryKey;
        mMap->flags = android::ResTable_entry::FLAG_COMPLEX;
        if (flatEntry.entry->publicStatus.isPublic) {
            mMap->flags |= android::ResTable_entry::FLAG_PUBLIC;
        }
        if (flatEntry.value->isWeak()) {
            mMap->flags |= android::ResTable_entry::FLAG_WEAK;
        }

        ResTable_entry_source* sourceBlock = mOut->nextBlock<ResTable_entry_source>();
        sourceBlock->pathIndex = flatEntry.sourcePathKey;
        sourceBlock->line = flatEntry.sourceLine;

        mMap->size = sizeof(*mMap) + sizeof(*sourceBlock);
    }

    void flattenParent(const Reference& ref) {
        if (!ref.id.isValid()) {
            mSymbols->push_back({
                    ResourceNameRef(ref.name),
                    (mOut->size() - mMap->size) + sizeof(*mMap) - sizeof(android::ResTable_entry)
            });
        }
        mMap->parent.ident = ref.id.id;
    }

    void flattenEntry(const Reference& key, const Item& value) {
        mMap->count++;

        android::ResTable_map* outMapEntry = mOut->nextBlock<android::ResTable_map>();

        // Write the key.
        if (!Res_INTERNALID(key.id.id) && !key.id.isValid()) {
            assert(!key.name.entry.empty());
            mSymbols->push_back(std::make_pair(ResourceNameRef(key.name),
                    mOut->size() - sizeof(*outMapEntry)));
        }
        outMapEntry->name.ident = key.id.id;

        // Write the value.
        value.flatten(outMapEntry->value);

        if (outMapEntry->value.data == 0x0) {
            visitFunc<Reference>(value, [&](const Reference& reference) {
                mSymbols->push_back(std::make_pair(ResourceNameRef(reference.name),
                        mOut->size() - sizeof(outMapEntry->value.data)));
            });
        }
        outMapEntry->value.size = sizeof(outMapEntry->value);
    }

    void flattenValueOnly(const Item& value) {
        mMap->count++;

        android::ResTable_map* outMapEntry = mOut->nextBlock<android::ResTable_map>();

        // Write the value.
        value.flatten(outMapEntry->value);

        if (outMapEntry->value.data == 0x0) {
            visitFunc<Reference>(value, [&](const Reference& reference) {
                mSymbols->push_back(std::make_pair(ResourceNameRef(reference.name),
                        mOut->size() - sizeof(outMapEntry->value.data)));
            });
        }
        outMapEntry->value.size = sizeof(outMapEntry->value);
    }

    static bool compareStyleEntries(const Style::Entry* lhs, const Style::Entry* rhs) {
        return lhs->key.id < rhs->key.id;
    }

    void visit(const Style& style, ValueVisitorArgs&) override {
        if (style.parent.name.isValid()) {
            flattenParent(style.parent);
        }

        // First sort the entries by ID.
        std::vector<const Style::Entry*> sortedEntries;
        for (const auto& styleEntry : style.entries) {
            auto iter = std::lower_bound(sortedEntries.begin(), sortedEntries.end(),
                    &styleEntry, compareStyleEntries);
            sortedEntries.insert(iter, &styleEntry);
        }

        for (const Style::Entry* styleEntry : sortedEntries) {
            flattenEntry(styleEntry->key, *styleEntry->value);
        }
    }

    void visit(const Attribute& attr, ValueVisitorArgs&) override {
        android::Res_value tempVal;
        tempVal.dataType = android::Res_value::TYPE_INT_DEC;
        tempVal.data = attr.typeMask;
        flattenEntry(Reference(ResourceId{android::ResTable_map::ATTR_TYPE}),
                BinaryPrimitive(tempVal));

        for (const auto& symbol : attr.symbols) {
            tempVal.data = symbol.value;
            flattenEntry(symbol.symbol, BinaryPrimitive(tempVal));
        }
    }

    void visit(const Styleable& styleable, ValueVisitorArgs&) override {
        for (const auto& attr : styleable.entries) {
            flattenEntry(attr, BinaryPrimitive(android::Res_value{}));
        }
    }

    void visit(const Array& array, ValueVisitorArgs&) override {
        for (const auto& item : array.items) {
            flattenValueOnly(*item);
        }
    }

    void visit(const Plural& plural, ValueVisitorArgs&) override {
        const size_t count = plural.values.size();
        for (size_t i = 0; i < count; i++) {
            if (!plural.values[i]) {
                continue;
            }

            ResourceId q;
            switch (i) {
                case Plural::Zero:
                    q.id = android::ResTable_map::ATTR_ZERO;
                    break;

                case Plural::One:
                    q.id = android::ResTable_map::ATTR_ONE;
                    break;

                case Plural::Two:
                    q.id = android::ResTable_map::ATTR_TWO;
                    break;

                case Plural::Few:
                    q.id = android::ResTable_map::ATTR_FEW;
                    break;

                case Plural::Many:
                    q.id = android::ResTable_map::ATTR_MANY;
                    break;

                case Plural::Other:
                    q.id = android::ResTable_map::ATTR_OTHER;
                    break;

                default:
                    assert(false);
                    break;
            }

            flattenEntry(Reference(q), *plural.values[i]);
        }
    }

private:
    BigBuffer* mOut;
    SymbolEntryVector* mSymbols;
    android::ResTable_map_entry* mMap;
};

/**
 * Flattens a value, with special handling for References.
 */
struct ValueFlattener : ConstValueVisitor {
    ValueFlattener(BigBuffer* out, SymbolEntryVector* symbols) :
            result(false), mOut(out), mOutValue(nullptr), mSymbols(symbols) {
        mOutValue = mOut->nextBlock<android::Res_value>();
    }

    virtual void visit(const Reference& ref, ValueVisitorArgs& a) override {
        visitItem(ref, a);
        if (mOutValue->data == 0x0) {
            mSymbols->push_back({
                    ResourceNameRef(ref.name),
                    mOut->size() - sizeof(mOutValue->data)});
        }
    }

    virtual void visitItem(const Item& item, ValueVisitorArgs&) override {
        result = item.flatten(*mOutValue);
        mOutValue->res0 = 0;
        mOutValue->size = sizeof(*mOutValue);
    }

    bool result;

private:
    BigBuffer* mOut;
    android::Res_value* mOutValue;
    SymbolEntryVector* mSymbols;
};

TableFlattener::TableFlattener(Options options)
: mOptions(options) {
}

bool TableFlattener::flattenValue(BigBuffer* out, const FlatEntry& flatEntry,
                                  SymbolEntryVector* symbols) {
    if (flatEntry.value->isItem()) {
        android::ResTable_entry* entry = out->nextBlock<android::ResTable_entry>();

        if (flatEntry.entry->publicStatus.isPublic) {
            entry->flags |= android::ResTable_entry::FLAG_PUBLIC;
        }

        if (flatEntry.value->isWeak()) {
            entry->flags |= android::ResTable_entry::FLAG_WEAK;
        }

        entry->key.index = flatEntry.entryKey;
        entry->size = sizeof(*entry);

        if (mOptions.useExtendedChunks) {
            // Write the extra source block. This will be ignored by
            // the Android runtime.
            ResTable_entry_source* sourceBlock = out->nextBlock<ResTable_entry_source>();
            sourceBlock->pathIndex = flatEntry.sourcePathKey;
            sourceBlock->line = flatEntry.sourceLine;
            entry->size += sizeof(*sourceBlock);
        }

        const Item* item = static_cast<const Item*>(flatEntry.value);
        ValueFlattener flattener(out, symbols);
        item->accept(flattener, {});
        return flattener.result;
    }

    MapFlattener flattener(out, flatEntry, symbols);
    flatEntry.value->accept(flattener, {});
    return true;
}

bool TableFlattener::flatten(BigBuffer* out, const ResourceTable& table) {
    const size_t beginning = out->size();

    if (table.getPackageId() == ResourceTable::kUnsetPackageId) {
        Logger::error()
                << "ResourceTable has no package ID set."
                << std::endl;
        return false;
    }

    SymbolEntryVector symbolEntries;

    StringPool typePool;
    StringPool keyPool;
    StringPool sourcePool;

    // Sort the types by their IDs. They will be inserted into the StringPool
    // in this order.
    std::vector<ResourceTableType*> sortedTypes;
    for (const auto& type : table) {
        if (type->type == ResourceType::kStyleable && !mOptions.useExtendedChunks) {
            continue;
        }

        auto iter = std::lower_bound(std::begin(sortedTypes), std::end(sortedTypes), type.get(),
                [](const ResourceTableType* lhs, const ResourceTableType* rhs) -> bool {
                    return lhs->typeId < rhs->typeId;
                });
        sortedTypes.insert(iter, type.get());
    }

    BigBuffer typeBlock(1024);
    size_t expectedTypeId = 1;
    for (const ResourceTableType* type : sortedTypes) {
        if (type->typeId == ResourceTableType::kUnsetTypeId
                || type->typeId == 0) {
            Logger::error()
                    << "resource type '"
                    << type->type
                    << "' from package '"
                    << table.getPackage()
                    << "' has no ID."
                    << std::endl;
            return false;
        }

        // If there is a gap in the type IDs, fill in the StringPool
        // with empty values until we reach the ID we expect.
        while (type->typeId > expectedTypeId) {
            std::u16string typeName(u"?");
            typeName += expectedTypeId;
            typePool.makeRef(typeName);
            expectedTypeId++;
        }
        expectedTypeId++;
        typePool.makeRef(toString(type->type));

        android::ResTable_typeSpec* spec = typeBlock.nextBlock<android::ResTable_typeSpec>();
        spec->header.type = android::RES_TABLE_TYPE_SPEC_TYPE;
        spec->header.headerSize = sizeof(*spec);
        spec->header.size = spec->header.headerSize + (type->entries.size() * sizeof(uint32_t));
        spec->id = type->typeId;
        spec->entryCount = type->entries.size();

        if (type->entries.empty()) {
            continue;
        }

        // Reserve space for the masks of each resource in this type. These
        // show for which configuration axis the resource changes.
        uint32_t* configMasks = typeBlock.nextBlock<uint32_t>(type->entries.size());

        // Sort the entries by entry ID and write their configuration masks.
        std::vector<ResourceEntry*> entries;
        const size_t entryCount = type->entries.size();
        for (size_t entryIndex = 0; entryIndex < entryCount; entryIndex++) {
            const auto& entry = type->entries[entryIndex];

            if (entry->entryId == ResourceEntry::kUnsetEntryId) {
                Logger::error()
                        << "resource '"
                        << ResourceName{ table.getPackage(), type->type, entry->name }
                        << "' has no ID."
                        << std::endl;
                return false;
            }

            auto iter = std::lower_bound(std::begin(entries), std::end(entries), entry.get(),
                    [](const ResourceEntry* lhs, const ResourceEntry* rhs) -> bool {
                        return lhs->entryId < rhs->entryId;
                    });
            entries.insert(iter, entry.get());

            // Populate the config masks for this entry.
            if (entry->publicStatus.isPublic) {
                configMasks[entry->entryId] |= android::ResTable_typeSpec::SPEC_PUBLIC;
            }

            const size_t configCount = entry->values.size();
            for (size_t i = 0; i < configCount; i++) {
                const ConfigDescription& config = entry->values[i].config;
                for (size_t j = i + 1; j < configCount; j++) {
                    configMasks[entry->entryId] |= config.diff(entry->values[j].config);
                }
            }
        }

        const size_t beforePublicHeader = typeBlock.size();
        Public_header* publicHeader = nullptr;
        if (mOptions.useExtendedChunks) {
            publicHeader = typeBlock.nextBlock<Public_header>();
            publicHeader->header.type = RES_TABLE_PUBLIC_TYPE;
            publicHeader->header.headerSize = sizeof(*publicHeader);
            publicHeader->typeId = type->typeId;
        }

        // The binary resource table lists resource entries for each configuration.
        // We store them inverted, where a resource entry lists the values for each
        // configuration available. Here we reverse this to match the binary table.
        std::map<ConfigDescription, std::vector<FlatEntry>> data;
        for (const ResourceEntry* entry : entries) {
            size_t keyIndex = keyPool.makeRef(entry->name).getIndex();

            if (keyIndex > std::numeric_limits<uint32_t>::max()) {
                Logger::error()
                        << "resource key string pool exceeded max size."
                        << std::endl;
                return false;
            }

            if (publicHeader && entry->publicStatus.isPublic) {
                // Write the public status of this entry.
                Public_entry* publicEntry = typeBlock.nextBlock<Public_entry>();
                publicEntry->entryId = static_cast<uint32_t>(entry->entryId);
                publicEntry->key.index = static_cast<uint32_t>(keyIndex);
                publicEntry->source.index = static_cast<uint32_t>(sourcePool.makeRef(
                            util::utf8ToUtf16(entry->publicStatus.source.path)).getIndex());
                publicEntry->sourceLine = static_cast<uint32_t>(entry->publicStatus.source.line);
                publicHeader->count += 1;
            }

            for (const auto& configValue : entry->values) {
                data[configValue.config].push_back(FlatEntry{
                        entry,
                        configValue.value.get(),
                        static_cast<uint32_t>(keyIndex),
                        static_cast<uint32_t>(sourcePool.makeRef(util::utf8ToUtf16(
                                    configValue.source.path)).getIndex()),
                        static_cast<uint32_t>(configValue.source.line)
                });
            }
        }

        if (publicHeader) {
            typeBlock.align4();
            publicHeader->header.size =
                    static_cast<uint32_t>(typeBlock.size() - beforePublicHeader);
        }

        // Begin flattening a configuration for the current type.
        for (const auto& entry : data) {
            const size_t typeHeaderStart = typeBlock.size();
            android::ResTable_type* typeHeader = typeBlock.nextBlock<android::ResTable_type>();
            typeHeader->header.type = android::RES_TABLE_TYPE_TYPE;
            typeHeader->header.headerSize = sizeof(*typeHeader);
            typeHeader->id = type->typeId;
            typeHeader->entryCount = type->entries.size();
            typeHeader->entriesStart = typeHeader->header.headerSize
                    + (sizeof(uint32_t) * type->entries.size());
            typeHeader->config = entry.first;

            uint32_t* indices = typeBlock.nextBlock<uint32_t>(type->entries.size());
            memset(indices, 0xff, type->entries.size() * sizeof(uint32_t));

            const size_t entryStart = typeBlock.size();
            for (const FlatEntry& flatEntry : entry.second) {
                assert(flatEntry.entry->entryId < type->entries.size());
                indices[flatEntry.entry->entryId] = typeBlock.size() - entryStart;
                if (!flattenValue(&typeBlock, flatEntry, &symbolEntries)) {
                    Logger::error()
                            << "failed to flatten resource '"
                            << ResourceNameRef {
                                    table.getPackage(), type->type, flatEntry.entry->name }
                            << "' for configuration '"
                            << entry.first
                            << "'."
                            << std::endl;
                    return false;
                }
            }

            typeBlock.align4();
            typeHeader->header.size = typeBlock.size() - typeHeaderStart;
        }
    }

    const size_t beforeTable = out->size();
    android::ResTable_header* header = out->nextBlock<android::ResTable_header>();
    header->header.type = android::RES_TABLE_TYPE;
    header->header.headerSize = sizeof(*header);
    header->packageCount = 1;

    SymbolTable_entry* symbolEntryData = nullptr;
    if (!symbolEntries.empty() && mOptions.useExtendedChunks) {
        const size_t beforeSymbolTable = out->size();
        StringPool symbolPool;
        SymbolTable_header* symbolHeader = out->nextBlock<SymbolTable_header>();
        symbolHeader->header.type = RES_TABLE_SYMBOL_TABLE_TYPE;
        symbolHeader->header.headerSize = sizeof(*symbolHeader);
        symbolHeader->count = symbolEntries.size();

        symbolEntryData = out->nextBlock<SymbolTable_entry>(symbolHeader->count);

        size_t i = 0;
        for (const auto& entry : symbolEntries) {
            symbolEntryData[i].offset = entry.second;
            StringPool::Ref ref = symbolPool.makeRef(
                    entry.first.package.toString() + u":" +
                    toString(entry.first.type).toString() + u"/" +
                    entry.first.entry.toString());
            symbolEntryData[i].stringIndex = ref.getIndex();
            i++;
        }

        StringPool::flattenUtf8(out, symbolPool);
        out->align4();
        symbolHeader->header.size = out->size() - beforeSymbolTable;
    }

    if (sourcePool.size() > 0 && mOptions.useExtendedChunks) {
        const size_t beforeSourcePool = out->size();
        android::ResChunk_header* sourceHeader = out->nextBlock<android::ResChunk_header>();
        sourceHeader->type = RES_TABLE_SOURCE_POOL_TYPE;
        sourceHeader->headerSize = sizeof(*sourceHeader);
        StringPool::flattenUtf8(out, sourcePool);
        out->align4();
        sourceHeader->size = out->size() - beforeSourcePool;
    }

    StringPool::flattenUtf8(out, table.getValueStringPool());

    const size_t beforePackageIndex = out->size();
    android::ResTable_package* package = out->nextBlock<android::ResTable_package>();
    package->header.type = android::RES_TABLE_PACKAGE_TYPE;
    package->header.headerSize = sizeof(*package);

    if (table.getPackageId() > std::numeric_limits<uint8_t>::max()) {
        Logger::error()
                << "package ID 0x'"
                << std::hex << table.getPackageId() << std::dec
                << "' is invalid."
                << std::endl;
        return false;
    }
    package->id = table.getPackageId();

    if (table.getPackage().size() >= sizeof(package->name) / sizeof(package->name[0])) {
        Logger::error()
                << "package name '"
                << table.getPackage()
                << "' is too long."
                << std::endl;
        return false;
    }
    memcpy(package->name, reinterpret_cast<const uint16_t*>(table.getPackage().data()),
            table.getPackage().length() * sizeof(char16_t));
    package->name[table.getPackage().length()] = 0;

    package->typeStrings = package->header.headerSize;
    StringPool::flattenUtf16(out, typePool);
    package->keyStrings = out->size() - beforePackageIndex;
    StringPool::flattenUtf16(out, keyPool);

    if (symbolEntryData != nullptr) {
        for (size_t i = 0; i < symbolEntries.size(); i++) {
            symbolEntryData[i].offset += out->size() - beginning;
        }
    }

    out->appendBuffer(std::move(typeBlock));

    package->header.size = out->size() - beforePackageIndex;
    header->header.size = out->size() - beforeTable;
    return true;
}

} // namespace aapt
