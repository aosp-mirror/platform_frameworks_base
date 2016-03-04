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

#ifndef AAPT_PROCESS_SYMBOLTABLE_H
#define AAPT_PROCESS_SYMBOLTABLE_H

#include "Resource.h"
#include "ResourceTable.h"
#include "ResourceValues.h"
#include "util/Util.h"

#include <utils/JenkinsHash.h>
#include <utils/LruCache.h>

#include <android-base/macros.h>
#include <androidfw/AssetManager.h>
#include <algorithm>
#include <memory>
#include <vector>

namespace aapt {

inline android::hash_t hash_type(const ResourceName& name) {
    std::hash<std::u16string> strHash;
    android::hash_t hash = 0;
    hash = android::JenkinsHashMix(hash, (uint32_t) strHash(name.package));
    hash = android::JenkinsHashMix(hash, (uint32_t) name.type);
    hash = android::JenkinsHashMix(hash, (uint32_t) strHash(name.entry));
    return hash;
}

inline android::hash_t hash_type(const ResourceId& id) {
    return android::hash_type(id.id);
}

class ISymbolSource;

class SymbolTable {
public:
    struct Symbol {
        Maybe<ResourceId> id;
        std::unique_ptr<Attribute> attribute;
        bool isPublic;
    };

    SymbolTable() : mCache(200), mIdCache(200) {
    }

    void appendSource(std::unique_ptr<ISymbolSource> source);
    void prependSource(std::unique_ptr<ISymbolSource> source);

    /**
     * Never hold on to the result between calls to findByName or findById. The results
     * are typically stored in a cache which may evict entries.
     */
    const Symbol* findByName(const ResourceName& name);
    const Symbol* findById(ResourceId id);

private:
    std::vector<std::unique_ptr<ISymbolSource>> mSources;

    // We use shared_ptr because unique_ptr is not supported and
    // we need automatic deletion.
    android::LruCache<ResourceName, std::shared_ptr<Symbol>> mCache;
    android::LruCache<ResourceId, std::shared_ptr<Symbol>> mIdCache;

    DISALLOW_COPY_AND_ASSIGN(SymbolTable);
};

/**
 * An interface that a symbol source implements in order to surface symbol information
 * to the symbol table.
 */
class ISymbolSource {
public:
    virtual ~ISymbolSource() = default;

    virtual std::unique_ptr<SymbolTable::Symbol> findByName(const ResourceName& name) = 0;
    virtual std::unique_ptr<SymbolTable::Symbol> findById(ResourceId id) = 0;
};

/**
 * Exposes the resources in a ResourceTable as symbols for SymbolTable.
 * Instances of this class must outlive the encompassed ResourceTable.
 * Lookups by ID are ignored.
 */
class ResourceTableSymbolSource : public ISymbolSource {
public:
    explicit ResourceTableSymbolSource(ResourceTable* table) : mTable(table) {
    }

    std::unique_ptr<SymbolTable::Symbol> findByName(const ResourceName& name) override;

    std::unique_ptr<SymbolTable::Symbol> findById(ResourceId id) override {
        return {};
    }

private:
    ResourceTable* mTable;

    DISALLOW_COPY_AND_ASSIGN(ResourceTableSymbolSource);
};

class AssetManagerSymbolSource : public ISymbolSource {
public:
    AssetManagerSymbolSource() = default;

    bool addAssetPath(const StringPiece& path);

    std::unique_ptr<SymbolTable::Symbol> findByName(const ResourceName& name) override;
    std::unique_ptr<SymbolTable::Symbol> findById(ResourceId id) override;

private:
    android::AssetManager mAssets;

    DISALLOW_COPY_AND_ASSIGN(AssetManagerSymbolSource);
};

} // namespace aapt

#endif /* AAPT_PROCESS_SYMBOLTABLE_H */
