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

#include <androidfw/AssetManager.h>
#include <algorithm>
#include <map>
#include <memory>
#include <vector>

namespace aapt {

struct ISymbolTable {
    virtual ~ISymbolTable() = default;

    struct Symbol {
        ResourceId id;
        std::unique_ptr<Attribute> attribute;
        bool isPublic;
    };

    /**
     * Never hold on to the result between calls to findByName or findById. The results
     * are typically stored in a cache which may evict entries.
     */
    virtual const Symbol* findByName(const ResourceName& name) = 0;
    virtual const Symbol* findById(ResourceId id) = 0;
};

inline android::hash_t hash_type(const ResourceName& name) {
    std::hash<std::u16string> strHash;
    android::hash_t hash = 0;
    hash = android::JenkinsHashMix(hash, strHash(name.package));
    hash = android::JenkinsHashMix(hash, (uint32_t) name.type);
    hash = android::JenkinsHashMix(hash, strHash(name.entry));
    return hash;
}

inline android::hash_t hash_type(const ResourceId& id) {
    return android::hash_type(id.id);
}

/**
 * Presents a ResourceTable as an ISymbolTable, caching results.
 * Instances of this class must outlive the encompassed ResourceTable.
 * Since symbols are cached, the ResourceTable should not change during the
 * lifetime of this SymbolTableWrapper.
 *
 * If a resource in the ResourceTable does not have a ResourceID assigned to it,
 * it is ignored.
 *
 * Lookups by ID are ignored.
 */
class SymbolTableWrapper : public ISymbolTable {
private:
    ResourceTable* mTable;

    // We use shared_ptr because unique_ptr is not supported and
    // we need automatic deletion.
    android::LruCache<ResourceName, std::shared_ptr<Symbol>> mCache;

public:
    SymbolTableWrapper(ResourceTable* table) : mTable(table), mCache(200) {
    }

    const Symbol* findByName(const ResourceName& name) override;

    // Unsupported, all queries to ResourceTable should be done by name.
    const Symbol* findById(ResourceId id) override {
        return {};
    }
};

class AssetManagerSymbolTableBuilder {
private:
    struct AssetManagerSymbolTable : public ISymbolTable {
        std::vector<std::unique_ptr<android::AssetManager>> mAssets;

        // We use shared_ptr because unique_ptr is not supported and
        // we need automatic deletion.
        android::LruCache<ResourceName, std::shared_ptr<Symbol>> mCache;
        android::LruCache<ResourceId, std::shared_ptr<Symbol>> mIdCache;

        AssetManagerSymbolTable() : mCache(200), mIdCache(200) {
        }

        const Symbol* findByName(const ResourceName& name) override;
        const Symbol* findById(ResourceId id) override;
    };

    std::unique_ptr<AssetManagerSymbolTable> mSymbolTable =
            util::make_unique<AssetManagerSymbolTable>();

public:
    AssetManagerSymbolTableBuilder& add(std::unique_ptr<android::AssetManager> assetManager) {
        mSymbolTable->mAssets.push_back(std::move(assetManager));
        return *this;
    }

    std::unique_ptr<ISymbolTable> build() {
        return std::move(mSymbolTable);
    }
};

class JoinedSymbolTableBuilder {
private:
    struct JoinedSymbolTable : public ISymbolTable {
        std::vector<std::unique_ptr<ISymbolTable>> mSymbolTables;

        const Symbol* findByName(const ResourceName& name) override;
        const Symbol* findById(ResourceId id) override;
    };

    std::unique_ptr<JoinedSymbolTable> mSymbolTable = util::make_unique<JoinedSymbolTable>();

public:
    JoinedSymbolTableBuilder& addSymbolTable(std::unique_ptr<ISymbolTable> table) {
        mSymbolTable->mSymbolTables.push_back(std::move(table));
        return *this;
    }

    std::unique_ptr<ISymbolTable> build() {
        return std::move(mSymbolTable);
    }
};

} // namespace aapt

#endif /* AAPT_PROCESS_SYMBOLTABLE_H */
