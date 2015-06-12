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

#include "BinaryResourceParser.h"
#include "Logger.h"
#include "ResChunkPullParser.h"
#include "Resolver.h"
#include "ResourceParser.h"
#include "ResourceTable.h"
#include "ResourceTypeExtensions.h"
#include "ResourceValues.h"
#include "Source.h"
#include "Util.h"

#include <androidfw/ResourceTypes.h>
#include <androidfw/TypeWrappers.h>
#include <map>
#include <string>

namespace aapt {

using namespace android;

/*
 * Visitor that converts a reference's resource ID to a resource name,
 * given a mapping from resource ID to resource name.
 */
struct ReferenceIdToNameVisitor : ValueVisitor {
    ReferenceIdToNameVisitor(const std::shared_ptr<IResolver>& resolver,
                             std::map<ResourceId, ResourceName>* cache) :
            mResolver(resolver), mCache(cache) {
    }

    void visit(Reference& reference, ValueVisitorArgs&) override {
        idToName(reference);
    }

    void visit(Attribute& attr, ValueVisitorArgs&) override {
        for (auto& entry : attr.symbols) {
            idToName(entry.symbol);
        }
    }

    void visit(Style& style, ValueVisitorArgs&) override {
        if (style.parent.id.isValid()) {
            idToName(style.parent);
        }

        for (auto& entry : style.entries) {
            idToName(entry.key);
            entry.value->accept(*this, {});
        }
    }

    void visit(Styleable& styleable, ValueVisitorArgs&) override {
        for (auto& attr : styleable.entries) {
            idToName(attr);
        }
    }

    void visit(Array& array, ValueVisitorArgs&) override {
        for (auto& item : array.items) {
            item->accept(*this, {});
        }
    }

    void visit(Plural& plural, ValueVisitorArgs&) override {
        for (auto& item : plural.values) {
            if (item) {
                item->accept(*this, {});
            }
        }
    }

private:
    void idToName(Reference& reference) {
        if (!reference.id.isValid()) {
            return;
        }

        auto cacheIter = mCache->find(reference.id);
        if (cacheIter != mCache->end()) {
            reference.name = cacheIter->second;
            reference.id = 0;
        } else {
            Maybe<ResourceName> result = mResolver->findName(reference.id);
            if (result) {
                reference.name = result.value();

                // Add to cache.
                mCache->insert({reference.id, reference.name});

                reference.id = 0;
            }
        }
    }

    std::shared_ptr<IResolver> mResolver;
    std::map<ResourceId, ResourceName>* mCache;
};


BinaryResourceParser::BinaryResourceParser(const std::shared_ptr<ResourceTable>& table,
                                           const std::shared_ptr<IResolver>& resolver,
                                           const Source& source,
                                           const std::u16string& defaultPackage,
                                           const void* data,
                                           size_t len) :
        mTable(table), mResolver(resolver), mSource(source), mDefaultPackage(defaultPackage),
        mData(data), mDataLen(len) {
}

bool BinaryResourceParser::parse() {
    ResChunkPullParser parser(mData, mDataLen);

    bool error = false;
    while(ResChunkPullParser::isGoodEvent(parser.next())) {
        if (parser.getChunk()->type != android::RES_TABLE_TYPE) {
            Logger::warn(mSource)
                    << "unknown chunk of type '"
                    << parser.getChunk()->type
                    << "'."
                    << std::endl;
            continue;
        }

        error |= !parseTable(parser.getChunk());
    }

    if (parser.getEvent() == ResChunkPullParser::Event::BadDocument) {
        Logger::error(mSource)
                << "bad document: "
                << parser.getLastError()
                << "."
                << std::endl;
        return false;
    }
    return !error;
}

bool BinaryResourceParser::getSymbol(const void* data, ResourceNameRef* outSymbol) {
    if (!mSymbolEntries || mSymbolEntryCount == 0) {
        return false;
    }

    if (reinterpret_cast<uintptr_t>(data) < reinterpret_cast<uintptr_t>(mData)) {
        return false;
    }

    // We only support 32 bit offsets right now.
    const uintptr_t offset = reinterpret_cast<uintptr_t>(data) -
            reinterpret_cast<uintptr_t>(mData);
    if (offset > std::numeric_limits<uint32_t>::max()) {
        return false;
    }

    for (size_t i = 0; i < mSymbolEntryCount; i++) {
        if (mSymbolEntries[i].offset == offset) {
            // This offset is a symbol!
            const StringPiece16 str = util::getString(mSymbolPool,
                                                      mSymbolEntries[i].stringIndex);
            StringPiece16 typeStr;
            ResourceParser::extractResourceName(str, &outSymbol->package, &typeStr,
                                                &outSymbol->entry);
            const ResourceType* type = parseResourceType(typeStr);
            if (!type) {
                return false;
            }
            if (outSymbol->package.empty()) {
                outSymbol->package = mTable->getPackage();
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

bool BinaryResourceParser::parseSymbolTable(const ResChunk_header* chunk) {
    const SymbolTable_header* symbolTableHeader = convertTo<SymbolTable_header>(chunk);
    if (!symbolTableHeader) {
        Logger::error(mSource)
                << "could not parse chunk as SymbolTable_header."
                << std::endl;
        return false;
    }

    const size_t entrySizeBytes = symbolTableHeader->count * sizeof(SymbolTable_entry);
    if (entrySizeBytes > getChunkDataLen(symbolTableHeader->header)) {
        Logger::error(mSource)
                << "entries extend beyond chunk."
                << std::endl;
        return false;
    }

    mSymbolEntries = reinterpret_cast<const SymbolTable_entry*>(
            getChunkData(symbolTableHeader->header));
    mSymbolEntryCount = symbolTableHeader->count;

    ResChunkPullParser parser(getChunkData(symbolTableHeader->header) + entrySizeBytes,
                              getChunkDataLen(symbolTableHeader->header) - entrySizeBytes);
    if (!ResChunkPullParser::isGoodEvent(parser.next())) {
        Logger::error(mSource)
                << "failed to parse chunk: "
                << parser.getLastError()
                << "."
                << std::endl;
        return false;
    }

    if (parser.getChunk()->type != android::RES_STRING_POOL_TYPE) {
        Logger::error(mSource)
                << "expected Symbol string pool."
                << std::endl;
        return false;
    }

    if (mSymbolPool.setTo(parser.getChunk(), parser.getChunk()->size) != NO_ERROR) {
        Logger::error(mSource)
                << "failed to parse symbol string pool with code: "
                << mSymbolPool.getError()
                << "."
                << std::endl;
        return false;
    }
    return true;
}

bool BinaryResourceParser::parseTable(const ResChunk_header* chunk) {
    const ResTable_header* tableHeader = convertTo<ResTable_header>(chunk);
    if (!tableHeader) {
        Logger::error(mSource)
                << "could not parse chunk as ResTable_header."
                << std::endl;
        return false;
    }

    ResChunkPullParser parser(getChunkData(tableHeader->header),
                              getChunkDataLen(tableHeader->header));
    while (ResChunkPullParser::isGoodEvent(parser.next())) {
        switch (parser.getChunk()->type) {
        case android::RES_STRING_POOL_TYPE:
            if (mValuePool.getError() == NO_INIT) {
                if (mValuePool.setTo(parser.getChunk(), parser.getChunk()->size) !=
                        NO_ERROR) {
                    Logger::error(mSource)
                            << "failed to parse value string pool with code: "
                            << mValuePool.getError()
                            << "."
                            << std::endl;
                    return false;
                }

                // Reserve some space for the strings we are going to add.
                mTable->getValueStringPool().hintWillAdd(
                        mValuePool.size(), mValuePool.styleCount());
            } else {
                Logger::warn(mSource)
                    << "unexpected string pool."
                    << std::endl;
            }
            break;

        case RES_TABLE_SYMBOL_TABLE_TYPE:
            if (!parseSymbolTable(parser.getChunk())) {
                return false;
            }
            break;

        case RES_TABLE_SOURCE_POOL_TYPE: {
            if (mSourcePool.setTo(getChunkData(*parser.getChunk()),
                        getChunkDataLen(*parser.getChunk())) != NO_ERROR) {
                Logger::error(mSource)
                        << "failed to parse source pool with code: "
                        << mSourcePool.getError()
                        << "."
                        << std::endl;
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
            Logger::warn(mSource)
                << "unexpected chunk of type "
                << parser.getChunk()->type
                << "."
                << std::endl;
            break;
        }
    }

    if (parser.getEvent() == ResChunkPullParser::Event::BadDocument) {
        Logger::error(mSource)
            << "bad resource table: " << parser.getLastError()
            << "."
            << std::endl;
        return false;
    }
    return true;
}

bool BinaryResourceParser::parsePackage(const ResChunk_header* chunk) {
    if (mValuePool.getError() != NO_ERROR) {
        Logger::error(mSource)
                << "no value string pool for ResTable."
                << std::endl;
        return false;
    }

    const ResTable_package* packageHeader = convertTo<ResTable_package>(chunk);
    if (!packageHeader) {
        Logger::error(mSource)
                << "could not parse chunk as ResTable_header."
                << std::endl;
        return false;
    }

    if (mTable->getPackageId() == ResourceTable::kUnsetPackageId) {
        // This is the first time the table has it's package ID set.
        mTable->setPackageId(packageHeader->id);
    } else if (mTable->getPackageId() != packageHeader->id) {
        Logger::error(mSource)
                << "ResTable_package has package ID "
                << std::hex << packageHeader->id << std::dec
                << " but ResourceTable has package ID "
                << std::hex << mTable->getPackageId() << std::dec
                << std::endl;
        return false;
    }

    size_t len = strnlen16(reinterpret_cast<const char16_t*>(packageHeader->name),
            sizeof(packageHeader->name) / sizeof(packageHeader->name[0]));
    if (mTable->getPackage().empty() && len == 0) {
        mTable->setPackage(mDefaultPackage);
    } else if (len > 0) {
        StringPiece16 thisPackage(reinterpret_cast<const char16_t*>(packageHeader->name), len);
        if (mTable->getPackage().empty()) {
            mTable->setPackage(thisPackage);
        } else if (thisPackage != mTable->getPackage()) {
            Logger::error(mSource)
                    << "incompatible packages: "
                    << mTable->getPackage()
                    << " vs. "
                    << thisPackage
                    << std::endl;
            return false;
        }
    }

    ResChunkPullParser parser(getChunkData(packageHeader->header),
                              getChunkDataLen(packageHeader->header));
    while (ResChunkPullParser::isGoodEvent(parser.next())) {
        switch (parser.getChunk()->type) {
        case android::RES_STRING_POOL_TYPE:
            if (mTypePool.getError() == NO_INIT) {
                if (mTypePool.setTo(parser.getChunk(), parser.getChunk()->size) !=
                        NO_ERROR) {
                    Logger::error(mSource)
                            << "failed to parse type string pool with code "
                            << mTypePool.getError()
                            << "."
                            << std::endl;
                    return false;
                }
            } else if (mKeyPool.getError() == NO_INIT) {
                if (mKeyPool.setTo(parser.getChunk(), parser.getChunk()->size) !=
                        NO_ERROR) {
                    Logger::error(mSource)
                            << "failed to parse key string pool with code "
                            << mKeyPool.getError()
                            << "."
                            << std::endl;
                    return false;
                }
            } else {
                Logger::warn(mSource)
                        << "unexpected string pool."
                        << std::endl;
            }
            break;

        case android::RES_TABLE_TYPE_SPEC_TYPE:
            if (!parseTypeSpec(parser.getChunk())) {
                return false;
            }
            break;

        case android::RES_TABLE_TYPE_TYPE:
            if (!parseType(parser.getChunk())) {
                return false;
            }
            break;

        case RES_TABLE_PUBLIC_TYPE:
            if (!parsePublic(parser.getChunk())) {
                return false;
            }
            break;

        default:
            Logger::warn(mSource)
                    << "unexpected chunk of type "
                    << parser.getChunk()->type
                    << "."
                    << std::endl;
            break;
        }
    }

    if (parser.getEvent() == ResChunkPullParser::Event::BadDocument) {
        Logger::error(mSource)
                << "bad package: "
                << parser.getLastError()
                << "."
                << std::endl;
        return false;
    }

    // Now go through the table and change resource ID references to
    // symbolic references.

    ReferenceIdToNameVisitor visitor(mResolver, &mIdIndex);
    for (auto& type : *mTable) {
        for (auto& entry : type->entries) {
            for (auto& configValue : entry->values) {
                configValue.value->accept(visitor, {});
            }
        }
    }
    return true;
}

bool BinaryResourceParser::parsePublic(const ResChunk_header* chunk) {
    const Public_header* header = convertTo<Public_header>(chunk);

    if (header->typeId == 0) {
        Logger::error(mSource)
                << "invalid type ID " << header->typeId << std::endl;
        return false;
    }

    const ResourceType* parsedType = parseResourceType(util::getString(mTypePool,
                                                                       header->typeId - 1));
    if (!parsedType) {
        Logger::error(mSource)
                << "invalid type " << util::getString(mTypePool, header->typeId - 1) << std::endl;
        return false;
    }

    const uintptr_t chunkEnd = reinterpret_cast<uintptr_t>(chunk) + chunk->size;
    const Public_entry* entry = reinterpret_cast<const Public_entry*>(
            getChunkData(header->header));
    for (uint32_t i = 0; i < header->count; i++) {
        if (reinterpret_cast<uintptr_t>(entry) + sizeof(*entry) > chunkEnd) {
            Logger::error(mSource)
                    << "Public_entry extends beyond chunk."
                    << std::endl;
            return false;
        }

        const ResourceId resId = { mTable->getPackageId(), header->typeId, entry->entryId };
        const ResourceName name = {
                mTable->getPackage(),
                *parsedType,
                util::getString(mKeyPool, entry->key.index).toString() };

        SourceLine source;
        if (mSourcePool.getError() == NO_ERROR) {
            source.path = util::utf16ToUtf8(util::getString(mSourcePool, entry->source.index));
            source.line = entry->sourceLine;
        }

        if (!mTable->markPublicAllowMangled(name, resId, source)) {
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
        Logger::error(mSource)
                << "no type string pool available for ResTable_typeSpec."
                << std::endl;
        return false;
    }

    const ResTable_typeSpec* typeSpec = convertTo<ResTable_typeSpec>(chunk);
    if (!typeSpec) {
        Logger::error(mSource)
                << "could not parse chunk as ResTable_typeSpec."
                << std::endl;
        return false;
    }

    if (typeSpec->id == 0) {
        Logger::error(mSource)
                << "ResTable_typeSpec has invalid id: "
                << typeSpec->id
                << "."
                << std::endl;
        return false;
    }
    return true;
}

bool BinaryResourceParser::parseType(const ResChunk_header* chunk) {
    if (mTypePool.getError() != NO_ERROR) {
        Logger::error(mSource)
                << "no type string pool available for ResTable_typeSpec."
                << std::endl;
        return false;
    }

    if (mKeyPool.getError() != NO_ERROR) {
        Logger::error(mSource)
                << "no key string pool available for ResTable_type."
                << std::endl;
        return false;
    }

    const ResTable_type* type = convertTo<ResTable_type>(chunk);
    if (!type) {
        Logger::error(mSource)
                << "could not parse chunk as ResTable_type."
                << std::endl;
        return false;
    }

    if (type->id == 0) {
        Logger::error(mSource)
                << "ResTable_type has invalid id: "
                << type->id
                << "."
                << std::endl;
        return false;
    }

    const ConfigDescription config(type->config);
    const StringPiece16 typeName = util::getString(mTypePool, type->id - 1);

    const ResourceType* parsedType = parseResourceType(typeName);
    if (!parsedType) {
        Logger::error(mSource)
                << "invalid type name '"
                << typeName
                << "' for type with ID "
                << uint32_t(type->id)
                << "." << std::endl;
        return false;
    }

    android::TypeVariant tv(type);
    for (auto it = tv.beginEntries(); it != tv.endEntries(); ++it) {
        if (!*it) {
            continue;
        }

        const ResTable_entry* entry = *it;
        const ResourceName name = {
                mTable->getPackage(),
                *parsedType,
                util::getString(mKeyPool, entry->key.index).toString()
        };

        const ResourceId resId = { mTable->getPackageId(), type->id, it.index() };

        std::unique_ptr<Value> resourceValue;
        const ResTable_entry_source* sourceBlock = nullptr;
        if (entry->flags & ResTable_entry::FLAG_COMPLEX) {
            const ResTable_map_entry* mapEntry = static_cast<const ResTable_map_entry*>(entry);
            if (mapEntry->size - sizeof(*mapEntry) == sizeof(*sourceBlock)) {
                const uint8_t* data = reinterpret_cast<const uint8_t*>(mapEntry);
                data += mapEntry->size - sizeof(*sourceBlock);
                sourceBlock = reinterpret_cast<const ResTable_entry_source*>(data);
            }

            // TODO(adamlesinski): Check that the entry count is valid.
            resourceValue = parseMapEntry(name, config, mapEntry);
        } else {
            if (entry->size - sizeof(*entry) == sizeof(*sourceBlock)) {
                const uint8_t* data = reinterpret_cast<const uint8_t*>(entry);
                data += entry->size - sizeof(*sourceBlock);
                sourceBlock = reinterpret_cast<const ResTable_entry_source*>(data);
            }

            const Res_value* value = reinterpret_cast<const Res_value*>(
                    reinterpret_cast<const uint8_t*>(entry) + entry->size);
            resourceValue = parseValue(name, config, value, entry->flags);
        }

        if (!resourceValue) {
            // TODO(adamlesinski): For now this is ok, but it really shouldn't be.
            continue;
        }

        SourceLine source = mSource.line(0);
        if (sourceBlock) {
            size_t len;
            const char* str = mSourcePool.string8At(sourceBlock->pathIndex, &len);
            if (str) {
                source.path.assign(str, len);
            }
            source.line = sourceBlock->line;
        }

        if (!mTable->addResourceAllowMangled(name, config, source, std::move(resourceValue))) {
            return false;
        }

        if ((entry->flags & ResTable_entry::FLAG_PUBLIC) != 0) {
            if (!mTable->markPublicAllowMangled(name, resId, mSource.line(0))) {
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

    if (value->dataType == Res_value::TYPE_STRING) {
        StringPiece16 str = util::getString(mValuePool, value->data);

        const ResStringPool_span* spans = mValuePool.styleAt(value->data);
        if (spans != nullptr) {
            StyleString styleStr = { str.toString() };
            while (spans->name.index != ResStringPool_span::END) {
                styleStr.spans.push_back(Span{
                        util::getString(mValuePool, spans->name.index).toString(),
                        spans->firstChar,
                        spans->lastChar
                });
                spans++;
            }
            return util::make_unique<StyledString>(
                    mTable->getValueStringPool().makeRef(
                            styleStr, StringPool::Context{1, config}));
        } else {
            if (name.type != ResourceType::kString &&
                    util::stringStartsWith<char16_t>(str, u"res/")) {
                // This must be a FileReference.
                return util::make_unique<FileReference>(mTable->getValueStringPool().makeRef(
                            str, StringPool::Context{ 0, config }));
            }

            // There are no styles associated with this string, so treat it as
            // a simple string.
            return util::make_unique<String>(
                    mTable->getValueStringPool().makeRef(
                            str, StringPool::Context{1, config}));
        }
    }

    if (value->dataType == Res_value::TYPE_REFERENCE ||
            value->dataType == Res_value::TYPE_ATTRIBUTE) {
        const Reference::Type type = (value->dataType == Res_value::TYPE_REFERENCE) ?
                    Reference::Type::kResource : Reference::Type::kAttribute;

        if (value->data != 0) {
            // This is a normal reference.
            return util::make_unique<Reference>(value->data, type);
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
        return util::make_unique<RawString>(
                mTable->getValueStringPool().makeRef(util::getString(mValuePool, value->data),
                                                    StringPool::Context{ 1, config }));
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
        case ResourceType::kAttr:
            return parseAttr(name, config, map);
        case ResourceType::kArray:
            return parseArray(name, config, map);
        case ResourceType::kStyleable:
            return parseStyleable(name, config, map);
        case ResourceType::kPlurals:
            return parsePlural(name, config, map);
        default:
            break;
    }
    return {};
}

std::unique_ptr<Style> BinaryResourceParser::parseStyle(const ResourceNameRef& name,
                                                        const ConfigDescription& config,
                                                        const ResTable_map_entry* map) {
    std::unique_ptr<Style> style = util::make_unique<Style>();
    if (map->parent.ident == 0) {
        // The parent is either not set or it is an unresolved symbol.
        // Check to see if it is a symbol.
        ResourceNameRef symbol;
        if (getSymbol(&map->parent.ident, &symbol)) {
            style->parent.name = symbol.toResourceName();
        }
    } else {
         // The parent is a regular reference to a resource.
        style->parent.id = map->parent.ident;
    }

    for (const ResTable_map& mapEntry : map) {
        style->entries.emplace_back();
        Style::Entry& styleEntry = style->entries.back();

        if (mapEntry.name.ident == 0) {
            // The map entry's key (attribute) is not set. This must be
            // a symbol reference, so resolve it.
            ResourceNameRef symbol;
            bool result = getSymbol(&mapEntry.name.ident, &symbol);
            assert(result);
            styleEntry.key.name = symbol.toResourceName();
        } else {
            // The map entry's key (attribute) is a regular reference.
            styleEntry.key.id = mapEntry.name.ident;
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
    const bool isWeak = (map->flags & ResTable_entry::FLAG_WEAK) != 0;
    std::unique_ptr<Attribute> attr = util::make_unique<Attribute>(isWeak);

    // First we must discover what type of attribute this is. Find the type mask.
    auto typeMaskIter = std::find_if(begin(map), end(map), [](const ResTable_map& entry) -> bool {
        return entry.name.ident == ResTable_map::ATTR_TYPE;
    });

    if (typeMaskIter != end(map)) {
        attr->typeMask = typeMaskIter->value.data;
    }

    if (attr->typeMask & (ResTable_map::TYPE_ENUM | ResTable_map::TYPE_FLAGS)) {
        for (const ResTable_map& mapEntry : map) {
            if (Res_INTERNALID(mapEntry.name.ident)) {
                continue;
            }

            Attribute::Symbol symbol;
            symbol.value = mapEntry.value.data;
            if (mapEntry.name.ident == 0) {
                // The map entry's key (id) is not set. This must be
                // a symbol reference, so resolve it.
                ResourceNameRef symbolName;
                bool result = getSymbol(&mapEntry.name.ident, &symbolName);
                assert(result);
                symbol.symbol.name = symbolName.toResourceName();
            } else {
                // The map entry's key (id) is a regular reference.
                symbol.symbol.id = mapEntry.name.ident;
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
        if (mapEntry.name.ident == 0) {
            // The map entry's key (attribute) is not set. This must be
            // a symbol reference, so resolve it.
            ResourceNameRef symbol;
            bool result = getSymbol(&mapEntry.name.ident, &symbol);
            assert(result);
            styleable->entries.emplace_back(symbol);
        } else {
            // The map entry's key (attribute) is a regular reference.
            styleable->entries.emplace_back(mapEntry.name.ident);
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

        switch (mapEntry.name.ident) {
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
