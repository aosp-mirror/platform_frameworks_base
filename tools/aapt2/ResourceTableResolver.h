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

#ifndef AAPT_RESOURCE_TABLE_RESOLVER_H
#define AAPT_RESOURCE_TABLE_RESOLVER_H

#include "Maybe.h"
#include "Resolver.h"
#include "Resource.h"
#include "ResourceTable.h"
#include "ResourceValues.h"

#include <androidfw/AssetManager.h>
#include <memory>
#include <vector>
#include <unordered_set>

namespace aapt {

/**
 * Encapsulates the search of library sources as well as the local ResourceTable.
 */
class ResourceTableResolver : public IResolver {
public:
    /**
     * Creates a resolver with a local ResourceTable and an AssetManager
     * loaded with library packages.
     */
    ResourceTableResolver(
            std::shared_ptr<const ResourceTable> table,
            const std::vector<std::shared_ptr<const android::AssetManager>>& sources);

    ResourceTableResolver(const ResourceTableResolver&) = delete; // Not copyable.

    virtual Maybe<ResourceId> findId(const ResourceName& name) override;

    virtual Maybe<Entry> findAttribute(const ResourceName& name) override;

    virtual Maybe<ResourceName> findName(ResourceId resId) override;

private:
    struct CacheEntry {
        ResourceId id;
        std::unique_ptr<Attribute> attr;
    };

    const CacheEntry* buildCacheEntry(const ResourceName& name);

    std::shared_ptr<const ResourceTable> mTable;
    std::vector<std::shared_ptr<const android::AssetManager>> mSources;
    std::map<ResourceName, CacheEntry> mCache;
    std::unordered_set<std::u16string> mIncludedPackages;
};

} // namespace aapt

#endif // AAPT_RESOURCE_TABLE_RESOLVER_H
