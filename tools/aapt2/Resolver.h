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

#ifndef AAPT_RESOLVER_H
#define AAPT_RESOLVER_H

#include "Maybe.h"
#include "Resource.h"
#include "ResourceTable.h"
#include "ResourceValues.h"

#include <androidfw/AssetManager.h>
#include <androidfw/ResourceTypes.h>
#include <memory>
#include <vector>

namespace aapt {

/**
 * Resolves symbolic references (package:type/entry) into resource IDs/objects.
 * Encapsulates the search of library sources as well as the local ResourceTable.
 */
class Resolver {
public:
    /**
     * Creates a resolver with a local ResourceTable and an AssetManager
     * loaded with library packages.
     */
    Resolver(std::shared_ptr<const ResourceTable> table,
             std::shared_ptr<const android::AssetManager> sources);

    Resolver(const Resolver&) = delete; // Not copyable.

    /**
     * Holds the result of a resource name lookup.
     */
    struct Entry {
        /**
         * The ID of the resource. ResourceId::isValid() may
         * return false if the resource has not been assigned
         * an ID.
         */
        ResourceId id;

        /**
         * If the resource is an attribute, this will point
         * to a valid Attribute object, or else it will be
         * nullptr.
         */
        const Attribute* attr;
    };

    /**
     * Return the package to use when none is specified. This
     * is the package name of the app being built.
     */
    const std::u16string& getDefaultPackage() const;

    /**
     * Returns a ResourceID if the name is found. The ResourceID
     * may not be valid if the resource was not assigned an ID.
     */
    Maybe<ResourceId> findId(const ResourceName& name);

    /**
     * Returns an Entry if the name is found. Entry::attr
     * may be nullptr if the resource is not an attribute.
     */
    Maybe<Entry> findAttribute(const ResourceName& name);

    const android::ResTable& getResTable() const;

private:
    struct CacheEntry {
        ResourceId id;
        std::unique_ptr<Attribute> attr;
    };

    const CacheEntry* buildCacheEntry(const ResourceName& name);

    std::shared_ptr<const ResourceTable> mTable;
    std::shared_ptr<const android::AssetManager> mSources;
    std::map<ResourceName, CacheEntry> mCache;
};

inline const std::u16string& Resolver::getDefaultPackage() const {
    return mTable->getPackage();
}

inline const android::ResTable& Resolver::getResTable() const {
    return mSources->getResources(false);
}

} // namespace aapt

#endif // AAPT_RESOLVER_H
