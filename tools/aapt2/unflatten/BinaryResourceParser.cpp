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
#include "ResourceUtils.h"
#include "ResourceValues.h"
#include "Source.h"
#include "ValueVisitor.h"

#include "flatten/ResourceTypeExtensions.h"
#include "unflatten/BinaryResourceParser.h"
#include "unflatten/ResChunkPullParser.h"
#include "util/Util.h"

#include <androidfw/ResourceTypes.h>
#include <androidfw/TypeWrappers.h>
#include <base/macros.h>

#include <map>
#include <string>

namespace aapt {

using namespace android;

/*
 * Visitor that converts a reference's resource ID to a resource name,
 * given a mapping from resource ID to resource name.
 */
class ReferenceIdToNameVisitor : public ValueVisitor {
private:
    const std::map<ResourceId, ResourceName>* mMapping;

public:
    using ValueVisitor::visit;

    ReferenceIdToNameVisitor(const std::map<ResourceId, ResourceName>* mapping) :
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
            reference->name = cacheIter->second;
            reference->id = {};
        }
    }
};

BinaryResourceParser::BinaryResourceParser(IAaptContext* context, ResourceTable* table,
                                           const Source& source, const void* data, size_t len) :
        mContext(context), mTable(table), mSource(source), mData(data), mDataLen(len) {
}

bool BinaryResourceParser::parse() {
    ResChunkPullParser parser(mData, mDataLen);

    bool error = false;
    while(ResChunkPullParser::isGoodEvent(parser.next())) {
        if (parser.getChunk()->type != android::RES_TABLE_TYPE) {
            mContext->getDiagnostics()->warn(DiagMessage(mSource)
                                             << "unknown chunk of type '"
                                             << (int) parser.getChunk()->type << "'");
            continue;
        }

        if (!parseTable(parser.getChunk())) {
            error = true;
        }
    }

    if (parser.getEvent() == ResChunkPullParser::Event::BadDocument) {
        mContext->getDiagnostics()->error(DiagMessage(mSource)
                                          << "corrupt resource table: "
                                          << parser.getLastError());
        return false;
    }
    return !error;
}

bool BinaryResourceParser::getSymbol(const void* data, ResourceNameRef* outSymbol) {
    if (!mSymbolEntries || mSymbolEntryCount == 0) {
        return false;
    }

    if ((uintptr_t) data < (uintptr_t) mData) {
        return false;
    }

    // We only support 32 bit offsets right now.
    const uintptr_t offset = (uintptr_t) data - (uintptr_t) mData;
    if (offset > std::numeric_limits<uint32_t>::max()) {
        return false;
    }

    for (size_t i = 0; i < mSymbolEntryCount; i++) {
        if (util::deviceToHost32(mSymbolEntries[i].offset) == offset) {
            // This offset is a symbol!
            const StringPiece16 str = util::getString(
                    mSymbolPool, util::deviceToHost32(mSymbolEntries[i].stringIndex));

            StringPiece16 typeStr;
            ResourceUtils::extractResourceName(str, &outSymbol->package, &typeStr,
                                               &outSymbol->entry);
            const ResourceType* type = parseResourceType(typeStr);
            if (!type) {
                return false;
            }

            outSymbol->type = *type;

            // Since we scan the symbol table in order, we can start looking for the
            // next symbol from this point.
            mSymbolEntryCount -= i + 1;
            mSymbolEntries += i + 1;
            return true;
        }
    }
    return false;
}

/**
 * Parses the SymbolTable_header, which is present on non-final resource tables
 * after the compile phase.
 *
 * | SymbolTable_header |
 * |--------------------|
 * |SymbolTable_entry 0 |
 * |SymbolTable_entry 1 |
 * | ...                |
 * |SymbolTable_entry n |
 * |--------------------|
 *
 */
bool BinaryResourceParser::parseSymbolTable(const ResChunk_header* chunk) {
    const SymbolTable_header* header = convertTo<SymbolTable_header>(chunk);
    if (!header) {
        mContext->getDiagnostics()->error(DiagMessage(mSource)
                                          << "corrupt SymbolTable_header");
        return false;
    }

    const uint32_t entrySizeBytes =
            util::deviceToHost32(header->count) * sizeof(SymbolTable_entry);
    if (entrySizeBytes > getChunkDataLen(&header->header)) {
        mContext->getDiagnostics()->error(DiagMessage(mSource)
                                          << "SymbolTable_header data section too long");
        return false;
    }

    mSymbolEntries = (const SymbolTable_entry*) getChunkData(&header->header);
    mSymbolEntryCount = util::deviceToHost32(header->count);

    // Skip over the symbol entries and parse the StringPool chunk that should be next.
    ResChunkPullParser parser(getChunkData(&header->header) + entrySizeBytes,
                              getChunkDataLen(&header->header) - entrySizeBytes);
    if (!ResChunkPullParser::isGoodEvent(parser.next())) {
        mContext->getDiagnostics()->error(DiagMessage(mSource)
                                          << "failed to parse chunk in SymbolTable: "
                                          << parser.getLastError());
        return false;
    }

    const ResChunk_header* nextChunk = parser.getChunk();
    if (util::deviceToHost16(nextChunk->type) != android::RES_STRING_POOL_TYPE) {
        mContext->getDiagnostics()->error(DiagMessage(mSource)
                                          << "expected string pool in SymbolTable but got "
                                          << "chunk of type "
                                          << (int) util::deviceToHost16(nextChunk->type));
        return false;
    }

    if (mSymbolPool.setTo(nextChunk, util::deviceToHost32(nextChunk->size)) != NO_ERROR) {
        mContext->getDiagnostics()->error(DiagMessage(mSource)
                                          << "corrupt string pool in SymbolTable: "
                                          << mSymbolPool.getError());
        return false;
    }
    return true;
}

/**
 * Parses the resource table, which contains all the packages, types, and entries.
 */
bool BinaryResourceParser::parseTable(const ResChunk_header* chunk) {
    const ResTable_header* tableHeader = convertTo<ResTable_header>(chunk);
    if (!tableHeader) {
        mContext->getDiagnostics()->error(DiagMessage(mSource) << "corrupt ResTable_header chunk");
        return false;
    }

    ResChunkPullParser parser(getChunkData(&tableHeader->header),
                              getChunkDataLen(&tableHeader->header));
    while (ResChunkPullParser::isGoodEvent(parser.next())) {
        switch (util::deviceToHost16(parser.getChunk()->type)) {
        case android::RES_STRING_POOL_TYPE:
            if (mValuePool.getError() == NO_INIT) {
                status_t err = mValuePool.setTo(parser.getChunk(),
                                                util::deviceToHost32(parser.getChunk()->size));
                if (err != NO_ERROR) {
                    mContext->getDiagnostics()->error(DiagMessage(mSource)
                                                      << "corrupt string pool in ResTable: "
                                                      << mValuePool.getError());
                    return false;
                }

                // Reserve some space for the strings we are going to add.
                mTable->stringPool.hintWillAdd(mValuePool.size(), mValuePool.styleCount());
            } else {
                mContext->getDiagnostics()->warn(DiagMessage(mSource)
                                                 << "unexpected string pool in ResTable");
            }
            break;

        case RES_TABLE_SYMBOL_TABLE_TYPE:
            if (!parseSymbolTable(parser.getChunk())) {
                return false;
            }
            break;

        case RES_TABLE_SOURCE_POOL_TYPE: {
            status_t err = mSourcePool.setTo(getChunkData(parser.getChunk()),
                                             getChunkDataLen(parser.getChunk()));
            if (err != NO_ERROR) {
                mContext->getDiagnostics()->error(DiagMessage(mSource)
                                                  << "corrupt source string pool in ResTable: "
                                                  << mSourcePool.getError());
                return false;
            }
            break;
        }

        case android::RES_TABLE_PACKAGE_TYPE:
            if (!parsePackage(parser.getChunk())) {
                return false;
            }
            break;

        default:
            mContext->getDiagnostics()
                    ->warn(DiagMessage(mSource)
                           << "unexpected chunk type "
                           << (int) util::deviceToHost16(parser.getChunk()->type));
            break;
        }
    }

    if (parser.getEvent() == ResChunkPullParser::Event::BadDocument) {
        mContext->getDiagnostics()->error(DiagMessage(mSource)
                                          << "corrupt resource table: " << parser.getLastError());
        return false;
    }
    return true;
}


bool BinaryResourceParser::parsePackage(const ResChunk_header* chunk) {
    const ResTable_package* packageHeader = convertTo<ResTable_package>(chunk);
    if (!packageHeader) {
        mContext->getDiagnostics()->error(DiagMessage(mSource)
                                          << "corrupt ResTable_package chunk");
        return false;
    }

    uint32_t packageId = util::deviceToHost32(packageHeader->id);
    if (packageId > std::numeric_limits<uint8_t>::max()) {
        mContext->getDiagnostics()->error(DiagMessage(mSource)
                                          << "package ID is too big (" << packageId << ")");
        return false;
    }

    // Extract the package name.
    size_t len = strnlen16((const char16_t*) packageHeader->name, arraysize(packageHeader->name));
    std::u16string packageName;
    packageName.resize(len);
    for (size_t i = 0; i < len; i++) {
        packageName[i] = util::deviceToHost16(packageHeader->name[i]);
    }

    ResourceTablePackage* package = mTable->createPackage(packageName, (uint8_t) packageId);
    if (!package) {
        mContext->getDiagnostics()->error(DiagMessage(mSource)
                                          << "incompatible package '" << packageName
                                          << "' with ID " << packageId);
        return false;
    }

    ResChunkPullParser parser(getChunkData(&packageHeader->header),
                              getChunkDataLen(&packageHeader->header));
    while (ResChunkPullParser::isGoodEvent(parser.next())) {
        switch (util::deviceToHost16(parser.getChunk()->type)) {
        case android::RES_STRING_POOL_TYPE:
            if (mTypePool.getError() == NO_INIT) {
                status_t err = mTypePool.setTo(parser.getChunk(),
                                               util::deviceToHost32(parser.getChunk()->size));
                if (err != NO_ERROR) {
                    mContext->getDiagnostics()->error(DiagMessage(mSource)
                                                      << "corrupt type string pool in "
                                                      << "ResTable_package: "
                                                      << mTypePool.getError());
                    return false;
                }
            } else if (mKeyPool.getError() == NO_INIT) {
                status_t err = mKeyPool.setTo(parser.getChunk(),
                                              util::deviceToHost32(parser.getChunk()->size));
                if (err != NO_ERROR) {
                    mContext->getDiagnostics()->error(DiagMessage(mSource)
                                                      << "corrupt key string pool in "
                                                      << "ResTable_package: "
                                                      << mKeyPool.getError());
                    return false;
                }
            } else {
                mContext->getDiagnostics()->warn(DiagMessage(mSource) << "unexpected string pool");
            }
            break;

        case android::RES_TABLE_TYPE_SPEC_TYPE:
            if (!parseTypeSpec(parser.getChunk())) {
                return false;
            }
            break;

        case android::RES_TABLE_TYPE_TYPE:
            if (!parseType(package, parser.getChunk())) {
                return false;
            }
            break;

        case RES_TABLE_PUBLIC_TYPE:
            if (!parsePublic(package, parser.getChunk())) {
                return false;
            }
            break;

        default:
            mContext->getDiagnostics()
                    ->warn(DiagMessage(mSource)
                           << "unexpected chunk type "
                           << (int) util::deviceToHost16(parser.getChunk()->type));
            break;
        }
    }

    if (parser.getEvent() == ResChunkPullParser::Event::BadDocument) {
        mContext->getDiagnostics()->error(DiagMessage(mSource)
                                          << "corrupt ResTable_package: "
                                          << parser.getLastError());
        return false;
    }

    // Now go through the table and change local resource ID references to
    // symbolic references.
    ReferenceIdToNameVisitor visitor(&mIdIndex);
    for (auto& package : mTable->packages) {
        for (auto& type : package->types) {
            for (auto& entry : type->entries) {
                for (auto& configValue : entry->values) {
                    configValue.value->accept(&visitor);
                }
            }
        }
    }
    return true;
}

bool BinaryResourceParser::parsePublic(const ResourceTablePackage* package,
                                       const ResChunk_header* chunk) {
    const Public_header* header = convertTo<Public_header>(chunk);
    if (!header) {
        mContext->getDiagnostics()->error(DiagMessage(mSource)
                                          << "corrupt Public_header chunk");
        return false;
    }

    if (header->typeId == 0) {
        mContext->getDiagnostics()->error(DiagMessage(mSource)
                                          << "invalid type ID "
                                          << (int) header->typeId);
        return false;
    }

    StringPiece16 typeStr16 = util::getString(mTypePool, header->typeId - 1);
    const ResourceType* parsedType = parseResourceType(typeStr16);
    if (!parsedType) {
        mContext->getDiagnostics()->error(DiagMessage(mSource)
                                          << "invalid type '" << typeStr16 << "'");
        return false;
    }

    const uintptr_t chunkEnd = (uintptr_t) chunk + util::deviceToHost32(chunk->size);
    const Public_entry* entry = (const Public_entry*) getChunkData(&header->header);
    for (uint32_t i = 0; i < util::deviceToHost32(header->count); i++) {
        if ((uintptr_t) entry + sizeof(*entry) > chunkEnd) {
            mContext->getDiagnostics()->error(DiagMessage(mSource)
                                              << "Public_entry data section is too long");
            return false;
        }

        const ResourceId resId(package->id.value(), header->typeId,
                               util::deviceToHost16(entry->entryId));

        const ResourceName name(package->name, *parsedType,
                                util::getString(mKeyPool, entry->key.index).toString());

        Source source;
        if (mSourcePool.getError() == NO_ERROR) {
            source.path = util::utf16ToUtf8(util::getString(
                    mSourcePool, util::deviceToHost32(entry->source.index)));
            source.line = util::deviceToHost32(entry->sourceLine);
        }

        SymbolState state = SymbolState::kUndefined;
        switch (util::deviceToHost16(entry->state)) {
        case Public_entry::kPrivate:
            state = SymbolState::kPrivate;
            break;

        case Public_entry::kPublic:
            state = SymbolState::kPublic;
            break;
        }

        if (!mTable->setSymbolStateAllowMangled(name, resId, source, state,
                                                mContext->getDiagnostics())) {
            return false;
        }

        // Add this resource name->id mapping to the index so
        // that we can resolve all ID references to name references.
        auto cacheIter = mIdIndex.find(resId);
        if (cacheIter == mIdIndex.end()) {
            mIdIndex.insert({ resId, name });
        }

        entry++;
    }
    return true;
}

bool BinaryResourceParser::parseTypeSpec(const ResChunk_header* chunk) {
    if (mTypePool.getError() != NO_ERROR) {
        mContext->getDiagnostics()->error(DiagMessage(mSource)
                                          << "missing type string pool");
        return false;
    }

    const ResTable_typeSpec* typeSpec = convertTo<ResTable_typeSpec>(chunk);
    if (!typeSpec) {
        mContext->getDiagnostics()->error(DiagMessage(mSource)
                                          << "corrupt ResTable_typeSpec chunk");
        return false;
    }

    if (typeSpec->id == 0) {
        mContext->getDiagnostics()->error(DiagMessage(mSource)
                                          << "ResTable_typeSpec has invalid id: " << typeSpec->id);
        return false;
    }
    return true;
}

bool BinaryResourceParser::parseType(const ResourceTablePackage* package,
                                     const ResChunk_header* chunk) {
    if (mTypePool.getError() != NO_ERROR) {
        mContext->getDiagnostics()->error(DiagMessage(mSource)
                                          << "missing type string pool");
        return false;
    }

    if (mKeyPool.getError() != NO_ERROR) {
        mContext->getDiagnostics()->error(DiagMessage(mSource)
                                          << "missing key string pool");
        return false;
    }

    const ResTable_type* type = convertTo<ResTable_type>(chunk);
    if (!type) {
        mContext->getDiagnostics()->error(DiagMessage(mSource)
                                          << "corrupt ResTable_type chunk");
        return false;
    }

    if (type->id == 0) {
        mContext->getDiagnostics()->error(DiagMessage(mSource)
                                          << "ResTable_type has invalid id: " << (int) type->id);
        return false;
    }

    ConfigDescription config;
    config.copyFromDtoH(type->config);

    StringPiece16 typeStr16 = util::getString(mTypePool, type->id - 1);

    const ResourceType* parsedType = parseResourceType(typeStr16);
    if (!parsedType) {
        mContext->getDiagnostics()->error(DiagMessage(mSource)
                                          << "invalid type name '" << typeStr16
                                          << "' for type with ID " << (int) type->id);
        return false;
    }

    TypeVariant tv(type);
    for (auto it = tv.beginEntries(); it != tv.endEntries(); ++it) {
        const ResTable_entry* entry = *it;
        if (!entry) {
            continue;
        }

        const ResourceName name(package->name, *parsedType,
                                util::getString(mKeyPool,
                                                util::deviceToHost32(entry->key.index)).toString());

        const ResourceId resId(package->id.value(), type->id, static_cast<uint16_t>(it.index()));

        std::unique_ptr<Value> resourceValue;
        const ResTable_entry_source* sourceBlock = nullptr;

        if (entry->flags & ResTable_entry::FLAG_COMPLEX) {
            const ResTable_map_entry* mapEntry = static_cast<const ResTable_map_entry*>(entry);
            if (util::deviceToHost32(mapEntry->size) - sizeof(*mapEntry) == sizeof(*sourceBlock)) {
                const uint8_t* data = (const uint8_t*) mapEntry;
                data += util::deviceToHost32(mapEntry->size) - sizeof(*sourceBlock);
                sourceBlock = (const ResTable_entry_source*) data;
            }

            // TODO(adamlesinski): Check that the entry count is valid.
            resourceValue = parseMapEntry(name, config, mapEntry);
        } else {
            if (util::deviceToHost32(entry->size) - sizeof(*entry) == sizeof(*sourceBlock)) {
                const uint8_t* data = (const uint8_t*) entry;
                data += util::deviceToHost32(entry->size) - sizeof(*sourceBlock);
                sourceBlock = (const ResTable_entry_source*) data;
            }

            const Res_value* value = (const Res_value*)(
                    (const uint8_t*) entry + util::deviceToHost32(entry->size));
            resourceValue = parseValue(name, config, value, entry->flags);
        }

        assert(resourceValue && "failed to interpret valid resource");

        Source source = mSource;
        if (sourceBlock) {
            size_t len;
            const char* str = mSourcePool.string8At(util::deviceToHost32(sourceBlock->pathIndex),
                                                    &len);
            if (str) {
                source.path.assign(str, len);
            }
            source.line = util::deviceToHost32(sourceBlock->line);
        }

        if (!mTable->addResourceAllowMangled(name, config, source, std::move(resourceValue),
                                             mContext->getDiagnostics())) {
            return false;
        }

        if ((entry->flags & ResTable_entry::FLAG_PUBLIC) != 0) {
            if (!mTable->setSymbolStateAllowMangled(name, resId, mSource.withLine(0),
                                                    SymbolState::kPublic,
                                                    mContext->getDiagnostics())) {
                return false;
            }
        }

        // Add this resource name->id mapping to the index so
        // that we can resolve all ID references to name references.
        auto cacheIter = mIdIndex.find(resId);
        if (cacheIter == mIdIndex.end()) {
            mIdIndex.insert({ resId, name });
        }
    }
    return true;
}

std::unique_ptr<Item> BinaryResourceParser::parseValue(const ResourceNameRef& name,
                                                       const ConfigDescription& config,
                                                       const Res_value* value,
                                                       uint16_t flags) {
    if (name.type == ResourceType::kId) {
        return util::make_unique<Id>();
    }

    const uint32_t data = util::deviceToHost32(value->data);

    if (value->dataType == Res_value::TYPE_STRING) {
        StringPiece16 str = util::getString(mValuePool, data);

        const ResStringPool_span* spans = mValuePool.styleAt(data);

        // Check if the string has a valid style associated with it.
        if (spans != nullptr && spans->name.index != ResStringPool_span::END) {
            StyleString styleStr = { str.toString() };
            while (spans->name.index != ResStringPool_span::END) {
                styleStr.spans.push_back(Span{
                        util::getString(mValuePool, spans->name.index).toString(),
                        spans->firstChar,
                        spans->lastChar
                });
                spans++;
            }
            return util::make_unique<StyledString>(mTable->stringPool.makeRef(
                    styleStr, StringPool::Context{1, config}));
        } else {
            if (name.type != ResourceType::kString &&
                    util::stringStartsWith<char16_t>(str, u"res/")) {
                // This must be a FileReference.
                return util::make_unique<FileReference>(mTable->stringPool.makeRef(
                            str, StringPool::Context{ 0, config }));
            }

            // There are no styles associated with this string, so treat it as
            // a simple string.
            return util::make_unique<String>(mTable->stringPool.makeRef(
                    str, StringPool::Context{1, config}));
        }
    }

    if (value->dataType == Res_value::TYPE_REFERENCE ||
            value->dataType == Res_value::TYPE_ATTRIBUTE) {
        const Reference::Type type = (value->dataType == Res_value::TYPE_REFERENCE) ?
                    Reference::Type::kResource : Reference::Type::kAttribute;

        if (data != 0) {
            // This is a normal reference.
            return util::make_unique<Reference>(data, type);
        }

        // This reference has an invalid ID. Check if it is an unresolved symbol.
        ResourceNameRef symbol;
        if (getSymbol(&value->data, &symbol)) {
            return util::make_unique<Reference>(symbol, type);
        }

        // This is not an unresolved symbol, so it must be the magic @null reference.
        Res_value nullType = {};
        nullType.dataType = Res_value::TYPE_REFERENCE;
        return util::make_unique<BinaryPrimitive>(nullType);
    }

    if (value->dataType == ExtendedTypes::TYPE_RAW_STRING) {
        return util::make_unique<RawString>(mTable->stringPool.makeRef(
                util::getString(mValuePool, data), StringPool::Context{ 1, config }));
    }

    // Treat this as a raw binary primitive.
    return util::make_unique<BinaryPrimitive>(*value);
}

std::unique_ptr<Value> BinaryResourceParser::parseMapEntry(const ResourceNameRef& name,
                                                           const ConfigDescription& config,
                                                           const ResTable_map_entry* map) {
    switch (name.type) {
        case ResourceType::kStyle:
            return parseStyle(name, config, map);
        case ResourceType::kAttrPrivate:
            // fallthrough
        case ResourceType::kAttr:
            return parseAttr(name, config, map);
        case ResourceType::kIntegerArray:
            // fallthrough
        case ResourceType::kArray:
            return parseArray(name, config, map);
        case ResourceType::kStyleable:
            return parseStyleable(name, config, map);
        case ResourceType::kPlurals:
            return parsePlural(name, config, map);
        default:
            assert(false && "unknown map type");
            break;
    }
    return {};
}

std::unique_ptr<Style> BinaryResourceParser::parseStyle(const ResourceNameRef& name,
                                                        const ConfigDescription& config,
                                                        const ResTable_map_entry* map) {
    std::unique_ptr<Style> style = util::make_unique<Style>();
    if (util::deviceToHost32(map->parent.ident) == 0) {
        // The parent is either not set or it is an unresolved symbol.
        // Check to see if it is a symbol.
        ResourceNameRef symbol;
        if (getSymbol(&map->parent.ident, &symbol)) {
            style->parent = Reference(symbol.toResourceName());
        }
    } else {
         // The parent is a regular reference to a resource.
        style->parent = Reference(util::deviceToHost32(map->parent.ident));
    }

    for (const ResTable_map& mapEntry : map) {
        style->entries.emplace_back();
        Style::Entry& styleEntry = style->entries.back();

        if (util::deviceToHost32(mapEntry.name.ident) == 0) {
            // The map entry's key (attribute) is not set. This must be
            // a symbol reference, so resolve it.
            ResourceNameRef symbol;
            bool result = getSymbol(&mapEntry.name.ident, &symbol);
            assert(result);
            styleEntry.key.name = symbol.toResourceName();
        } else {
            // The map entry's key (attribute) is a regular reference.
            styleEntry.key.id = ResourceId(util::deviceToHost32(mapEntry.name.ident));
        }

        // Parse the attribute's value.
        styleEntry.value = parseValue(name, config, &mapEntry.value, 0);
        assert(styleEntry.value);
    }
    return style;
}

std::unique_ptr<Attribute> BinaryResourceParser::parseAttr(const ResourceNameRef& name,
                                                           const ConfigDescription& config,
                                                           const ResTable_map_entry* map) {
    const bool isWeak = (util::deviceToHost16(map->flags) & ResTable_entry::FLAG_WEAK) != 0;
    std::unique_ptr<Attribute> attr = util::make_unique<Attribute>(isWeak);

    // First we must discover what type of attribute this is. Find the type mask.
    auto typeMaskIter = std::find_if(begin(map), end(map), [](const ResTable_map& entry) -> bool {
        return util::deviceToHost32(entry.name.ident) == ResTable_map::ATTR_TYPE;
    });

    if (typeMaskIter != end(map)) {
        attr->typeMask = util::deviceToHost32(typeMaskIter->value.data);
    }

    if (attr->typeMask & (ResTable_map::TYPE_ENUM | ResTable_map::TYPE_FLAGS)) {
        for (const ResTable_map& mapEntry : map) {
            if (Res_INTERNALID(util::deviceToHost32(mapEntry.name.ident))) {
                continue;
            }

            Attribute::Symbol symbol;
            symbol.value = util::deviceToHost32(mapEntry.value.data);
            if (util::deviceToHost32(mapEntry.name.ident) == 0) {
                // The map entry's key (id) is not set. This must be
                // a symbol reference, so resolve it.
                ResourceNameRef symbolName;
                bool result = getSymbol(&mapEntry.name.ident, &symbolName);
                assert(result);
                symbol.symbol.name = symbolName.toResourceName();
            } else {
                // The map entry's key (id) is a regular reference.
                symbol.symbol.id = ResourceId(util::deviceToHost32(mapEntry.name.ident));
            }

            attr->symbols.push_back(std::move(symbol));
        }
    }

    // TODO(adamlesinski): Find min, max, i80n, etc attributes.
    return attr;
}

std::unique_ptr<Array> BinaryResourceParser::parseArray(const ResourceNameRef& name,
                                                        const ConfigDescription& config,
                                                        const ResTable_map_entry* map) {
    std::unique_ptr<Array> array = util::make_unique<Array>();
    for (const ResTable_map& mapEntry : map) {
        array->items.push_back(parseValue(name, config, &mapEntry.value, 0));
    }
    return array;
}

std::unique_ptr<Styleable> BinaryResourceParser::parseStyleable(const ResourceNameRef& name,
                                                                const ConfigDescription& config,
                                                                const ResTable_map_entry* map) {
    std::unique_ptr<Styleable> styleable = util::make_unique<Styleable>();
    for (const ResTable_map& mapEntry : map) {
        if (util::deviceToHost32(mapEntry.name.ident) == 0) {
            // The map entry's key (attribute) is not set. This must be
            // a symbol reference, so resolve it.
            ResourceNameRef symbol;
            bool result = getSymbol(&mapEntry.name.ident, &symbol);
            assert(result);
            styleable->entries.emplace_back(symbol);
        } else {
            // The map entry's key (attribute) is a regular reference.
            styleable->entries.emplace_back(util::deviceToHost32(mapEntry.name.ident));
        }
    }
    return styleable;
}

std::unique_ptr<Plural> BinaryResourceParser::parsePlural(const ResourceNameRef& name,
                                                          const ConfigDescription& config,
                                                          const ResTable_map_entry* map) {
    std::unique_ptr<Plural> plural = util::make_unique<Plural>();
    for (const ResTable_map& mapEntry : map) {
        std::unique_ptr<Item> item = parseValue(name, config, &mapEntry.value, 0);

        switch (util::deviceToHost32(mapEntry.name.ident)) {
            case android::ResTable_map::ATTR_ZERO:
                plural->values[Plural::Zero] = std::move(item);
                break;
            case android::ResTable_map::ATTR_ONE:
                plural->values[Plural::One] = std::move(item);
                break;
            case android::ResTable_map::ATTR_TWO:
                plural->values[Plural::Two] = std::move(item);
                break;
            case android::ResTable_map::ATTR_FEW:
                plural->values[Plural::Few] = std::move(item);
                break;
            case android::ResTable_map::ATTR_MANY:
                plural->values[Plural::Many] = std::move(item);
                break;
            case android::ResTable_map::ATTR_OTHER:
                plural->values[Plural::Other] = std::move(item);
                break;
        }
    }
    return plural;
}

} // namespace aapt
