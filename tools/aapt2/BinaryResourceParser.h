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

#ifndef AAPT_BINARY_RESOURCE_PARSER_H
#define AAPT_BINARY_RESOURCE_PARSER_H

#include "Resolver.h"
#include "ResourceTable.h"
#include "ResourceValues.h"
#include "Source.h"

#include <androidfw/ResourceTypes.h>
#include <string>

namespace aapt {

struct SymbolTable_entry;

/*
 * Parses a binary resource table (resources.arsc) and adds the entries
 * to a ResourceTable. This is different than the libandroidfw ResTable
 * in that it scans the table from top to bottom and doesn't require
 * support for random access. It is also able to parse non-runtime
 * chunks and types.
 */
class BinaryResourceParser {
public:
    /*
     * Creates a parser, which will read `len` bytes from `data`, and
     * add any resources parsed to `table`. `source` is for logging purposes.
     */
    BinaryResourceParser(const std::shared_ptr<ResourceTable>& table,
                         const std::shared_ptr<IResolver>& resolver,
                         const Source& source,
                         const std::u16string& defaultPackage,
                         const void* data, size_t len);

    BinaryResourceParser(const BinaryResourceParser&) = delete; // No copy.

    /*
     * Parses the binary resource table and returns true if successful.
     */
    bool parse();

private:
    // Helper method to retrieve the symbol name for a given table offset specified
    // as a pointer.
    bool getSymbol(const void* data, ResourceNameRef* outSymbol);

    bool parseTable(const android::ResChunk_header* chunk);
    bool parseSymbolTable(const android::ResChunk_header* chunk);

    // Looks up the resource ID in the reference and converts it to a name if available.
    bool idToName(Reference* reference);

    bool parsePackage(const android::ResChunk_header* chunk);
    bool parsePublic(const android::ResChunk_header* chunk);
    bool parseTypeSpec(const android::ResChunk_header* chunk);
    bool parseType(const android::ResChunk_header* chunk);

    std::unique_ptr<Item> parseValue(const ResourceNameRef& name,
            const ConfigDescription& config, const android::Res_value* value, uint16_t flags);

    std::unique_ptr<Value> parseMapEntry(const ResourceNameRef& name,
            const ConfigDescription& config, const android::ResTable_map_entry* map);

    std::unique_ptr<Style> parseStyle(const ResourceNameRef& name,
            const ConfigDescription& config, const android::ResTable_map_entry* map);

    std::unique_ptr<Attribute> parseAttr(const ResourceNameRef& name,
            const ConfigDescription& config, const android::ResTable_map_entry* map);

    std::unique_ptr<Array> parseArray(const ResourceNameRef& name,
            const ConfigDescription& config, const android::ResTable_map_entry* map);

    std::unique_ptr<Plural> parsePlural(const ResourceNameRef& name,
            const ConfigDescription& config, const android::ResTable_map_entry* map);

    std::unique_ptr<Styleable> parseStyleable(const ResourceNameRef& name,
            const ConfigDescription& config, const android::ResTable_map_entry* map);

    std::shared_ptr<ResourceTable> mTable;

    std::shared_ptr<IResolver> mResolver;

    const Source mSource;

    // The package name of the resource table.
    std::u16string mDefaultPackage;

    const void* mData;
    const size_t mDataLen;

    // The array of symbol entries. Each element points to an offset
    // in the table and an index into the symbol table string pool.
    const SymbolTable_entry* mSymbolEntries = nullptr;

    // Number of symbol entries.
    size_t mSymbolEntryCount = 0;

    // The symbol table string pool. Holds the names of symbols
    // referenced in this table but not defined nor resolved to an
    // ID.
    android::ResStringPool mSymbolPool;

    // The source string pool. Resource entries may have an extra
    // field that points into this string pool, which denotes where
    // the resource was parsed from originally.
    android::ResStringPool mSourcePool;

    // The standard value string pool for resource values.
    android::ResStringPool mValuePool;

    // The string pool that holds the names of the types defined
    // in this table.
    android::ResStringPool mTypePool;

    // The string pool that holds the names of the entries defined
    // in this table.
    android::ResStringPool mKeyPool;

    // A mapping of resource ID to resource name. When we finish parsing
    // we use this to convert all resource IDs to symbolic references.
    std::map<ResourceId, ResourceName> mIdIndex;
};

} // namespace aapt

namespace android {

/**
 * Iterator functionality for ResTable_map_entry.
 */

inline const ResTable_map* begin(const ResTable_map_entry* map) {
    return reinterpret_cast<const ResTable_map*>(
            reinterpret_cast<const uint8_t*>(map) + map->size);
}

inline const ResTable_map* end(const ResTable_map_entry* map) {
    return reinterpret_cast<const ResTable_map*>(
            reinterpret_cast<const uint8_t*>(map) + map->size) + map->count;
}

} // namespace android

#endif // AAPT_BINARY_RESOURCE_PARSER_H
