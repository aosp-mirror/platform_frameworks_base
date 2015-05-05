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

#ifndef AAPT_RESOURCE_TABLE_H
#define AAPT_RESOURCE_TABLE_H

#include "ConfigDescription.h"
#include "Resource.h"
#include "ResourceValues.h"
#include "Source.h"
#include "StringPool.h"

#include <memory>
#include <string>
#include <tuple>
#include <vector>

namespace aapt {

/**
 * The Public status of a resource.
 */
struct Public {
    bool isPublic = false;
    SourceLine source;
    std::u16string comment;
};

/**
 * The resource value for a specific configuration.
 */
struct ResourceConfigValue {
    ConfigDescription config;
    SourceLine source;
    std::u16string comment;
    std::unique_ptr<Value> value;
};

/**
 * Represents a resource entry, which may have
 * varying values for each defined configuration.
 */
struct ResourceEntry {
    enum {
        kUnsetEntryId = 0xffffffffu
    };

    /**
     * The name of the resource. Immutable, as
     * this determines the order of this resource
     * when doing lookups.
     */
    const std::u16string name;

    /**
     * The entry ID for this resource.
     */
    size_t entryId;

    /**
     * Whether this resource is public (and must maintain the same
     * entry ID across builds).
     */
    Public publicStatus;

    /**
     * The resource's values for each configuration.
     */
    std::vector<ResourceConfigValue> values;

    inline ResourceEntry(const StringPiece16& _name);
    inline ResourceEntry(const ResourceEntry* rhs);
};

/**
 * Represents a resource type, which holds entries defined
 * for this type.
 */
struct ResourceTableType {
    enum {
        kUnsetTypeId = 0xffffffffu
    };

    /**
     * The logical type of resource (string, drawable, layout, etc.).
     */
    const ResourceType type;

    /**
     * The type ID for this resource.
     */
    size_t typeId;

    /**
     * Whether this type is public (and must maintain the same
     * type ID across builds).
     */
    Public publicStatus;

    /**
     * List of resources for this type.
     */
    std::vector<std::unique_ptr<ResourceEntry>> entries;

    ResourceTableType(const ResourceType _type);
    ResourceTableType(const ResourceTableType* rhs);
};

/**
 * The container and index for all resources defined for an app. This gets
 * flattened into a binary resource table (resources.arsc).
 */
class ResourceTable {
public:
    using iterator = std::vector<std::unique_ptr<ResourceTableType>>::iterator;
    using const_iterator = std::vector<std::unique_ptr<ResourceTableType>>::const_iterator;

    enum {
        kUnsetPackageId = 0xffffffff
    };

    ResourceTable();

    size_t getPackageId() const;
    void setPackageId(size_t packageId);

    const std::u16string& getPackage() const;
    void setPackage(const StringPiece16& package);

    bool addResource(const ResourceNameRef& name, const ConfigDescription& config,
                     const SourceLine& source, std::unique_ptr<Value> value);

    /**
     * Same as addResource, but doesn't verify the validity of the name. This is used
     * when loading resources from an existing binary resource table that may have mangled
     * names.
     */
    bool addResourceAllowMangled(const ResourceNameRef& name, const ConfigDescription& config,
                                 const SourceLine& source, std::unique_ptr<Value> value);

    bool addResource(const ResourceNameRef& name, const ResourceId resId,
                     const ConfigDescription& config, const SourceLine& source,
                     std::unique_ptr<Value> value);

    bool markPublic(const ResourceNameRef& name, const ResourceId resId, const SourceLine& source);
    bool markPublicAllowMangled(const ResourceNameRef& name, const ResourceId resId,
                                const SourceLine& source);

    /*
     * Merges the resources from `other` into this table, mangling the names of the resources
     * if `other` has a different package name.
     */
    bool merge(ResourceTable&& other);

    /**
     * Returns the string pool used by this ResourceTable.
     * Values that reference strings should use this pool to create
     * their strings.
     */
    StringPool& getValueStringPool();
    const StringPool& getValueStringPool() const;

    std::tuple<const ResourceTableType*, const ResourceEntry*>
    findResource(const ResourceNameRef& name) const;

    iterator begin();
    iterator end();
    const_iterator begin() const;
    const_iterator end() const;

private:
    std::unique_ptr<ResourceTableType>& findOrCreateType(ResourceType type);
    std::unique_ptr<ResourceEntry>& findOrCreateEntry(std::unique_ptr<ResourceTableType>& type,
                                                      const StringPiece16& name);

    bool addResourceImpl(const ResourceNameRef& name, const ResourceId resId,
                         const ConfigDescription& config, const SourceLine& source,
                         std::unique_ptr<Value> value, const char16_t* validChars);
    bool markPublicImpl(const ResourceNameRef& name, const ResourceId resId,
                        const SourceLine& source, const char16_t* validChars);

    std::u16string mPackage;
    size_t mPackageId;

    // StringPool must come before mTypes so that it is destroyed after.
    // When StringPool references are destroyed (as they will be when mTypes
    // is destroyed), they decrement a refCount, which would cause invalid
    // memory access if the pool was already destroyed.
    StringPool mValuePool;

    std::vector<std::unique_ptr<ResourceTableType>> mTypes;
};

//
// ResourceEntry implementation.
//

inline ResourceEntry::ResourceEntry(const StringPiece16& _name) :
        name(_name.toString()), entryId(kUnsetEntryId) {
}

inline ResourceEntry::ResourceEntry(const ResourceEntry* rhs) :
        name(rhs->name), entryId(rhs->entryId), publicStatus(rhs->publicStatus) {
}

//
// ResourceTableType implementation.
//

inline ResourceTableType::ResourceTableType(const ResourceType _type) :
        type(_type), typeId(kUnsetTypeId) {
}

inline ResourceTableType::ResourceTableType(const ResourceTableType* rhs) :
        type(rhs->type), typeId(rhs->typeId), publicStatus(rhs->publicStatus) {
}

//
// ResourceTable implementation.
//

inline StringPool& ResourceTable::getValueStringPool() {
    return mValuePool;
}

inline const StringPool& ResourceTable::getValueStringPool() const {
    return mValuePool;
}

inline ResourceTable::iterator ResourceTable::begin() {
    return mTypes.begin();
}

inline ResourceTable::iterator ResourceTable::end() {
    return mTypes.end();
}

inline ResourceTable::const_iterator ResourceTable::begin() const {
    return mTypes.begin();
}

inline ResourceTable::const_iterator ResourceTable::end() const {
    return mTypes.end();
}

inline const std::u16string& ResourceTable::getPackage() const {
    return mPackage;
}

inline size_t ResourceTable::getPackageId() const {
    return mPackageId;
}

inline void ResourceTable::setPackage(const StringPiece16& package) {
    mPackage = package.toString();
}

inline void ResourceTable::setPackageId(size_t packageId) {
    mPackageId = packageId;
}

} // namespace aapt

#endif // AAPT_RESOURCE_TABLE_H
