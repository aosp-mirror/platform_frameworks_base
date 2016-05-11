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
#include "Diagnostics.h"
#include "Resource.h"
#include "ResourceValues.h"
#include "Source.h"
#include "StringPool.h"
#include "io/File.h"

#include <android-base/macros.h>
#include <functional>
#include <map>
#include <memory>
#include <string>
#include <tuple>
#include <unordered_map>
#include <vector>

namespace aapt {

enum class SymbolState {
    kUndefined,
    kPublic,
    kPrivate
};

/**
 * The Public status of a resource.
 */
struct Symbol {
    SymbolState state = SymbolState::kUndefined;
    Source source;
    std::u16string comment;
};

class ResourceConfigValue {
public:
    /**
     * The configuration for which this value is defined.
     */
    const ConfigDescription config;

    /**
     * The product for which this value is defined.
     */
    const std::string product;

    /**
     * The actual Value.
     */
    std::unique_ptr<Value> value;

    ResourceConfigValue(const ConfigDescription& config, const StringPiece& product) :
            config(config), product(product.toString()) { }

private:
    DISALLOW_COPY_AND_ASSIGN(ResourceConfigValue);
};

/**
 * Represents a resource entry, which may have
 * varying values for each defined configuration.
 */
class ResourceEntry {
public:
    /**
     * The name of the resource. Immutable, as
     * this determines the order of this resource
     * when doing lookups.
     */
    const std::u16string name;

    /**
     * The entry ID for this resource.
     */
    Maybe<uint16_t> id;

    /**
     * Whether this resource is public (and must maintain the same entry ID across builds).
     */
    Symbol symbolStatus;

    /**
     * The resource's values for each configuration.
     */
    std::vector<std::unique_ptr<ResourceConfigValue>> values;

    ResourceEntry(const StringPiece16& name) : name(name.toString()) { }

    ResourceConfigValue* findValue(const ConfigDescription& config);
    ResourceConfigValue* findValue(const ConfigDescription& config, const StringPiece& product);
    ResourceConfigValue* findOrCreateValue(const ConfigDescription& config,
                                           const StringPiece& product);
    std::vector<ResourceConfigValue*> findAllValues(const ConfigDescription& config);
    std::vector<ResourceConfigValue*> findValuesIf(
            const std::function<bool(ResourceConfigValue*)>& f);


private:
    DISALLOW_COPY_AND_ASSIGN(ResourceEntry);
};

/**
 * Represents a resource type, which holds entries defined
 * for this type.
 */
class ResourceTableType {
public:
    /**
     * The logical type of resource (string, drawable, layout, etc.).
     */
    const ResourceType type;

    /**
     * The type ID for this resource.
     */
    Maybe<uint8_t> id;

    /**
     * Whether this type is public (and must maintain the same
     * type ID across builds).
     */
    Symbol symbolStatus;

    /**
     * List of resources for this type.
     */
    std::vector<std::unique_ptr<ResourceEntry>> entries;

    explicit ResourceTableType(const ResourceType type) : type(type) { }

    ResourceEntry* findEntry(const StringPiece16& name);
    ResourceEntry* findOrCreateEntry(const StringPiece16& name);

private:
    DISALLOW_COPY_AND_ASSIGN(ResourceTableType);
};

enum class PackageType {
    System,
    Vendor,
    App,
    Dynamic
};

class ResourceTablePackage {
public:
    PackageType type = PackageType::App;
    Maybe<uint8_t> id;
    std::u16string name;

    std::vector<std::unique_ptr<ResourceTableType>> types;

    ResourceTablePackage() = default;
    ResourceTableType* findType(ResourceType type);
    ResourceTableType* findOrCreateType(const ResourceType type);

private:
    DISALLOW_COPY_AND_ASSIGN(ResourceTablePackage);
};

/**
 * The container and index for all resources defined for an app. This gets
 * flattened into a binary resource table (resources.arsc).
 */
class ResourceTable {
public:
    ResourceTable() = default;

    /**
     * When a collision of resources occurs, this method decides which value to keep.
     * Returns -1 if the existing value should be chosen.
     * Returns 0 if the collision can not be resolved (error).
     * Returns 1 if the incoming value should be chosen.
     */
    static int resolveValueCollision(Value* existing, Value* incoming);

    bool addResource(const ResourceNameRef& name,
                     const ConfigDescription& config,
                     const StringPiece& product,
                     std::unique_ptr<Value> value,
                     IDiagnostics* diag);

    bool addResource(const ResourceNameRef& name,
                     const ResourceId resId,
                     const ConfigDescription& config,
                     const StringPiece& product,
                     std::unique_ptr<Value> value,
                     IDiagnostics* diag);

    bool addFileReference(const ResourceNameRef& name,
                              const ConfigDescription& config,
                              const Source& source,
                              const StringPiece16& path,
                              IDiagnostics* diag);

    bool addFileReferenceAllowMangled(const ResourceNameRef& name,
                                      const ConfigDescription& config,
                                      const Source& source,
                                      const StringPiece16& path,
                                      io::IFile* file,
                                      IDiagnostics* diag);

    /**
     * Same as addResource, but doesn't verify the validity of the name. This is used
     * when loading resources from an existing binary resource table that may have mangled
     * names.
     */
    bool addResourceAllowMangled(const ResourceNameRef& name,
                                 const ConfigDescription& config,
                                 const StringPiece& product,
                                 std::unique_ptr<Value> value,
                                 IDiagnostics* diag);

    bool addResourceAllowMangled(const ResourceNameRef& name,
                                 const ResourceId id,
                                 const ConfigDescription& config,
                                 const StringPiece& product,
                                 std::unique_ptr<Value> value,
                                 IDiagnostics* diag);

    bool setSymbolState(const ResourceNameRef& name,
                        const ResourceId resId,
                        const Symbol& symbol,
                        IDiagnostics* diag);

    bool setSymbolStateAllowMangled(const ResourceNameRef& name,
                                    const ResourceId resId,
                                    const Symbol& symbol,
                                    IDiagnostics* diag);

    struct SearchResult {
        ResourceTablePackage* package;
        ResourceTableType* type;
        ResourceEntry* entry;
    };

    Maybe<SearchResult> findResource(const ResourceNameRef& name);

    /**
     * The string pool used by this resource table. Values that reference strings must use
     * this pool to create their strings.
     *
     * NOTE: `stringPool` must come before `packages` so that it is destroyed after.
     * When `string pool` references are destroyed (as they will be when `packages`
     * is destroyed), they decrement a refCount, which would cause invalid
     * memory access if the pool was already destroyed.
     */
    StringPool stringPool;

    /**
     * The list of packages in this table, sorted alphabetically by package name.
     */
    std::vector<std::unique_ptr<ResourceTablePackage>> packages;

    /**
     * Returns the package struct with the given name, or nullptr if such a package does not
     * exist. The empty string is a valid package and typically is used to represent the
     * 'current' package before it is known to the ResourceTable.
     */
    ResourceTablePackage* findPackage(const StringPiece16& name);

    ResourceTablePackage* findPackageById(uint8_t id);

    ResourceTablePackage* createPackage(const StringPiece16& name, Maybe<uint8_t> id = {});

private:
    ResourceTablePackage* findOrCreatePackage(const StringPiece16& name);

    bool addFileReferenceImpl(const ResourceNameRef& name,
                              const ConfigDescription& config,
                              const Source& source,
                              const StringPiece16& path,
                              io::IFile* file,
                              const char16_t* validChars,
                              IDiagnostics* diag);

    bool addResourceImpl(const ResourceNameRef& name,
                         ResourceId resId,
                         const ConfigDescription& config,
                         const StringPiece& product,
                         std::unique_ptr<Value> value,
                         const char16_t* validChars,
                         std::function<int(Value*,Value*)> conflictResolver,
                         IDiagnostics* diag);

    bool setSymbolStateImpl(const ResourceNameRef& name,
                            ResourceId resId,
                            const Symbol& symbol,
                            const char16_t* validChars,
                            IDiagnostics* diag);

    DISALLOW_COPY_AND_ASSIGN(ResourceTable);
};

} // namespace aapt

#endif // AAPT_RESOURCE_TABLE_H
