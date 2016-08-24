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

#include "ResourceTable.h"
#include "ResourceValues.h"
#include "ValueVisitor.h"

#include "flatten/ChunkWriter.h"
#include "flatten/ResourceTypeExtensions.h"
#include "flatten/TableFlattener.h"
#include "util/BigBuffer.h"

#include <android-base/macros.h>
#include <algorithm>
#include <type_traits>
#include <numeric>

using namespace android;

namespace aapt {

namespace {

template <typename T>
static bool cmpIds(const T* a, const T* b) {
    return a->id.value() < b->id.value();
}

static void strcpy16_htod(uint16_t* dst, size_t len, const StringPiece16& src) {
    if (len == 0) {
        return;
    }

    size_t i;
    const char16_t* srcData = src.data();
    for (i = 0; i < len - 1 && i < src.size(); i++) {
        dst[i] = util::hostToDevice16((uint16_t) srcData[i]);
    }
    dst[i] = 0;
}

static bool cmpStyleEntries(const Style::Entry& a, const Style::Entry& b) {
   if (a.key.id) {
       if (b.key.id) {
           return a.key.id.value() < b.key.id.value();
       }
       return true;
   } else if (!b.key.id) {
       return a.key.name.value() < b.key.name.value();
   }
   return false;
}

struct FlatEntry {
    ResourceEntry* entry;
    Value* value;

    // The entry string pool index to the entry's name.
    uint32_t entryKey;
};

class MapFlattenVisitor : public RawValueVisitor {
public:
    using RawValueVisitor::visit;

    MapFlattenVisitor(ResTable_entry_ext* outEntry, BigBuffer* buffer) :
            mOutEntry(outEntry), mBuffer(buffer) {
    }

    void visit(Attribute* attr) override {
        {
            Reference key = Reference(ResTable_map::ATTR_TYPE);
            BinaryPrimitive val(Res_value::TYPE_INT_DEC, attr->typeMask);
            flattenEntry(&key, &val);
        }

        if (attr->minInt != std::numeric_limits<int32_t>::min()) {
            Reference key = Reference(ResTable_map::ATTR_MIN);
            BinaryPrimitive val(Res_value::TYPE_INT_DEC, static_cast<uint32_t>(attr->minInt));
            flattenEntry(&key, &val);
        }

        if (attr->maxInt != std::numeric_limits<int32_t>::max()) {
            Reference key = Reference(ResTable_map::ATTR_MAX);
            BinaryPrimitive val(Res_value::TYPE_INT_DEC, static_cast<uint32_t>(attr->maxInt));
            flattenEntry(&key, &val);
        }

        for (Attribute::Symbol& s : attr->symbols) {
            BinaryPrimitive val(Res_value::TYPE_INT_DEC, s.value);
            flattenEntry(&s.symbol, &val);
        }
    }

    void visit(Style* style) override {
        if (style->parent) {
            const Reference& parentRef = style->parent.value();
            assert(parentRef.id && "parent has no ID");
            mOutEntry->parent.ident = util::hostToDevice32(parentRef.id.value().id);
        }

        // Sort the style.
        std::sort(style->entries.begin(), style->entries.end(), cmpStyleEntries);

        for (Style::Entry& entry : style->entries) {
            flattenEntry(&entry.key, entry.value.get());
        }
    }

    void visit(Styleable* styleable) override {
        for (auto& attrRef : styleable->entries) {
            BinaryPrimitive val(Res_value{});
            flattenEntry(&attrRef, &val);
        }

    }

    void visit(Array* array) override {
        for (auto& item : array->items) {
            ResTable_map* outEntry = mBuffer->nextBlock<ResTable_map>();
            flattenValue(item.get(), outEntry);
            outEntry->value.size = util::hostToDevice16(sizeof(outEntry->value));
            mEntryCount++;
        }
    }

    void visit(Plural* plural) override {
        const size_t count = plural->values.size();
        for (size_t i = 0; i < count; i++) {
            if (!plural->values[i]) {
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

            Reference key(q);
            flattenEntry(&key, plural->values[i].get());
        }
    }

    /**
     * Call this after visiting a Value. This will finish any work that
     * needs to be done to prepare the entry.
     */
    void finish() {
        mOutEntry->count = util::hostToDevice32(mEntryCount);
    }

private:
    void flattenKey(Reference* key, ResTable_map* outEntry) {
        assert(key->id && "key has no ID");
        outEntry->name.ident = util::hostToDevice32(key->id.value().id);
    }

    void flattenValue(Item* value, ResTable_map* outEntry) {
        bool result = value->flatten(&outEntry->value);
        assert(result && "flatten failed");
    }

    void flattenEntry(Reference* key, Item* value) {
        ResTable_map* outEntry = mBuffer->nextBlock<ResTable_map>();
        flattenKey(key, outEntry);
        flattenValue(value, outEntry);
        outEntry->value.size = util::hostToDevice16(sizeof(outEntry->value));
        mEntryCount++;
    }

    ResTable_entry_ext* mOutEntry;
    BigBuffer* mBuffer;
    size_t mEntryCount = 0;
};

class PackageFlattener {
public:
    PackageFlattener(IDiagnostics* diag, ResourceTablePackage* package) :
            mDiag(diag), mPackage(package) {
    }

    bool flattenPackage(BigBuffer* buffer) {
        ChunkWriter pkgWriter(buffer);
        ResTable_package* pkgHeader = pkgWriter.startChunk<ResTable_package>(
                RES_TABLE_PACKAGE_TYPE);
        pkgHeader->id = util::hostToDevice32(mPackage->id.value());

        if (mPackage->name.size() >= arraysize(pkgHeader->name)) {
            mDiag->error(DiagMessage() <<
                         "package name '" << mPackage->name << "' is too long");
            return false;
        }

        // Copy the package name in device endianness.
        strcpy16_htod(pkgHeader->name, arraysize(pkgHeader->name), mPackage->name);

        // Serialize the types. We do this now so that our type and key strings
        // are populated. We write those first.
        BigBuffer typeBuffer(1024);
        flattenTypes(&typeBuffer);

        pkgHeader->typeStrings = util::hostToDevice32(pkgWriter.size());
        StringPool::flattenUtf16(pkgWriter.getBuffer(), mTypePool);

        pkgHeader->keyStrings = util::hostToDevice32(pkgWriter.size());
        StringPool::flattenUtf16(pkgWriter.getBuffer(), mKeyPool);

        // Append the types.
        buffer->appendBuffer(std::move(typeBuffer));

        pkgWriter.finish();
        return true;
    }

private:
    IDiagnostics* mDiag;
    ResourceTablePackage* mPackage;
    StringPool mTypePool;
    StringPool mKeyPool;

    template <typename T, bool IsItem>
    T* writeEntry(FlatEntry* entry, BigBuffer* buffer) {
        static_assert(std::is_same<ResTable_entry, T>::value ||
                      std::is_same<ResTable_entry_ext, T>::value,
                      "T must be ResTable_entry or ResTable_entry_ext");

        T* result = buffer->nextBlock<T>();
        ResTable_entry* outEntry = (ResTable_entry*)(result);
        if (entry->entry->symbolStatus.state == SymbolState::kPublic) {
            outEntry->flags |= ResTable_entry::FLAG_PUBLIC;
        }

        if (entry->value->isWeak()) {
            outEntry->flags |= ResTable_entry::FLAG_WEAK;
        }

        if (!IsItem) {
            outEntry->flags |= ResTable_entry::FLAG_COMPLEX;
        }

        outEntry->flags = util::hostToDevice16(outEntry->flags);
        outEntry->key.index = util::hostToDevice32(entry->entryKey);
        outEntry->size = util::hostToDevice16(sizeof(T));
        return result;
    }

    bool flattenValue(FlatEntry* entry, BigBuffer* buffer) {
        if (Item* item = valueCast<Item>(entry->value)) {
            writeEntry<ResTable_entry, true>(entry, buffer);
            Res_value* outValue = buffer->nextBlock<Res_value>();
            bool result = item->flatten(outValue);
            assert(result && "flatten failed");
            outValue->size = util::hostToDevice16(sizeof(*outValue));
        } else {
            ResTable_entry_ext* outEntry = writeEntry<ResTable_entry_ext, false>(entry, buffer);
            MapFlattenVisitor visitor(outEntry, buffer);
            entry->value->accept(&visitor);
            visitor.finish();
        }
        return true;
    }

    bool flattenConfig(const ResourceTableType* type, const ConfigDescription& config,
                       std::vector<FlatEntry>* entries, BigBuffer* buffer) {
        ChunkWriter typeWriter(buffer);
        ResTable_type* typeHeader = typeWriter.startChunk<ResTable_type>(RES_TABLE_TYPE_TYPE);
        typeHeader->id = type->id.value();
        typeHeader->config = config;
        typeHeader->config.swapHtoD();

        auto maxAccum = [](uint32_t max, const std::unique_ptr<ResourceEntry>& a) -> uint32_t {
            return std::max(max, (uint32_t) a->id.value());
        };

        // Find the largest entry ID. That is how many entries we will have.
        const uint32_t entryCount =
                std::accumulate(type->entries.begin(), type->entries.end(), 0, maxAccum) + 1;

        typeHeader->entryCount = util::hostToDevice32(entryCount);
        uint32_t* indices = typeWriter.nextBlock<uint32_t>(entryCount);

        assert((size_t) entryCount <= std::numeric_limits<uint16_t>::max() + 1);
        memset(indices, 0xff, entryCount * sizeof(uint32_t));

        typeHeader->entriesStart = util::hostToDevice32(typeWriter.size());

        const size_t entryStart = typeWriter.getBuffer()->size();
        for (FlatEntry& flatEntry : *entries) {
            assert(flatEntry.entry->id.value() < entryCount);
            indices[flatEntry.entry->id.value()] = util::hostToDevice32(
                    typeWriter.getBuffer()->size() - entryStart);
            if (!flattenValue(&flatEntry, typeWriter.getBuffer())) {
                mDiag->error(DiagMessage()
                             << "failed to flatten resource '"
                             << ResourceNameRef(mPackage->name, type->type, flatEntry.entry->name)
                             << "' for configuration '" << config << "'");
                return false;
            }
        }
        typeWriter.finish();
        return true;
    }

    std::vector<ResourceTableType*> collectAndSortTypes() {
        std::vector<ResourceTableType*> sortedTypes;
        for (auto& type : mPackage->types) {
            if (type->type == ResourceType::kStyleable) {
                // Styleables aren't real Resource Types, they are represented in the R.java
                // file.
                continue;
            }

            assert(type->id && "type must have an ID set");

            sortedTypes.push_back(type.get());
        }
        std::sort(sortedTypes.begin(), sortedTypes.end(), cmpIds<ResourceTableType>);
        return sortedTypes;
    }

    std::vector<ResourceEntry*> collectAndSortEntries(ResourceTableType* type) {
        // Sort the entries by entry ID.
        std::vector<ResourceEntry*> sortedEntries;
        for (auto& entry : type->entries) {
            assert(entry->id && "entry must have an ID set");
            sortedEntries.push_back(entry.get());
        }
        std::sort(sortedEntries.begin(), sortedEntries.end(), cmpIds<ResourceEntry>);
        return sortedEntries;
    }

    bool flattenTypeSpec(ResourceTableType* type, std::vector<ResourceEntry*>* sortedEntries,
                         BigBuffer* buffer) {
        ChunkWriter typeSpecWriter(buffer);
        ResTable_typeSpec* specHeader = typeSpecWriter.startChunk<ResTable_typeSpec>(
                RES_TABLE_TYPE_SPEC_TYPE);
        specHeader->id = type->id.value();

        if (sortedEntries->empty()) {
            typeSpecWriter.finish();
            return true;
        }

        // We can't just take the size of the vector. There may be holes in the entry ID space.
        // Since the entries are sorted by ID, the last one will be the biggest.
        const size_t numEntries = sortedEntries->back()->id.value() + 1;

        specHeader->entryCount = util::hostToDevice32(numEntries);

        // Reserve space for the masks of each resource in this type. These
        // show for which configuration axis the resource changes.
        uint32_t* configMasks = typeSpecWriter.nextBlock<uint32_t>(numEntries);

        const size_t actualNumEntries = sortedEntries->size();
        for (size_t entryIndex = 0; entryIndex < actualNumEntries; entryIndex++) {
            ResourceEntry* entry = sortedEntries->at(entryIndex);

            // Populate the config masks for this entry.

            if (entry->symbolStatus.state == SymbolState::kPublic) {
                configMasks[entry->id.value()] |=
                        util::hostToDevice32(ResTable_typeSpec::SPEC_PUBLIC);
            }

            const size_t configCount = entry->values.size();
            for (size_t i = 0; i < configCount; i++) {
                const ConfigDescription& config = entry->values[i]->config;
                for (size_t j = i + 1; j < configCount; j++) {
                    configMasks[entry->id.value()] |= util::hostToDevice32(
                            config.diff(entry->values[j]->config));
                }
            }
        }
        typeSpecWriter.finish();
        return true;
    }

    bool flattenTypes(BigBuffer* buffer) {
        // Sort the types by their IDs. They will be inserted into the StringPool in this order.
        std::vector<ResourceTableType*> sortedTypes = collectAndSortTypes();

        size_t expectedTypeId = 1;
        for (ResourceTableType* type : sortedTypes) {
            // If there is a gap in the type IDs, fill in the StringPool
            // with empty values until we reach the ID we expect.
            while (type->id.value() > expectedTypeId) {
                std::u16string typeName(u"?");
                typeName += expectedTypeId;
                mTypePool.makeRef(typeName);
                expectedTypeId++;
            }
            expectedTypeId++;
            mTypePool.makeRef(toString(type->type));

            std::vector<ResourceEntry*> sortedEntries = collectAndSortEntries(type);

            if (!flattenTypeSpec(type, &sortedEntries, buffer)) {
                return false;
            }

            // The binary resource table lists resource entries for each configuration.
            // We store them inverted, where a resource entry lists the values for each
            // configuration available. Here we reverse this to match the binary table.
            std::map<ConfigDescription, std::vector<FlatEntry>> configToEntryListMap;
            for (ResourceEntry* entry : sortedEntries) {
                const uint32_t keyIndex = (uint32_t) mKeyPool.makeRef(entry->name).getIndex();

                // Group values by configuration.
                for (auto& configValue : entry->values) {
                    configToEntryListMap[configValue->config].push_back(FlatEntry{
                            entry, configValue->value.get(), keyIndex });
                }
            }

            // Flatten a configuration value.
            for (auto& entry : configToEntryListMap) {
                if (!flattenConfig(type, entry.first, &entry.second, buffer)) {
                    return false;
                }
            }
        }
        return true;
    }
};

} // namespace

bool TableFlattener::consume(IAaptContext* context, ResourceTable* table) {
    // We must do this before writing the resources, since the string pool IDs may change.
    table->stringPool.sort([](const StringPool::Entry& a, const StringPool::Entry& b) -> bool {
        int diff = a.context.priority - b.context.priority;
        if (diff < 0) return true;
        if (diff > 0) return false;
        diff = a.context.config.compare(b.context.config);
        if (diff < 0) return true;
        if (diff > 0) return false;
        return a.value < b.value;
    });
    table->stringPool.prune();

    // Write the ResTable header.
    ChunkWriter tableWriter(mBuffer);
    ResTable_header* tableHeader = tableWriter.startChunk<ResTable_header>(RES_TABLE_TYPE);
    tableHeader->packageCount = util::hostToDevice32(table->packages.size());

    // Flatten the values string pool.
    StringPool::flattenUtf8(tableWriter.getBuffer(), table->stringPool);

    BigBuffer packageBuffer(1024);

    // Flatten each package.
    for (auto& package : table->packages) {
        PackageFlattener flattener(context->getDiagnostics(), package.get());
        if (!flattener.flattenPackage(&packageBuffer)) {
            return false;
        }
    }

    // Finally merge all the packages into the main buffer.
    tableWriter.getBuffer()->appendBuffer(std::move(packageBuffer));
    tableWriter.finish();
    return true;
}

} // namespace aapt
