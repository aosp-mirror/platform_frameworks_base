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

#include "android-base/macros.h"
#include "androidfw/StringPiece.h"

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
  kPrivate,
  kPublic,
};

/**
 * The Public status of a resource.
 */
struct Symbol {
  SymbolState state = SymbolState::kUndefined;
  Source source;

  // Whether this entry (originating from an overlay) can be added as a new resource.
  bool allow_new = false;

  std::string comment;
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

  ResourceConfigValue(const ConfigDescription& config, const android::StringPiece& product)
      : config(config), product(product.to_string()) {}

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
  const std::string name;

  /**
   * The entry ID for this resource.
   */
  Maybe<uint16_t> id;

  /**
   * Whether this resource is public (and must maintain the same entry ID across
   * builds).
   */
  Symbol symbol_status;

  /**
   * The resource's values for each configuration.
   */
  std::vector<std::unique_ptr<ResourceConfigValue>> values;

  explicit ResourceEntry(const android::StringPiece& name) : name(name.to_string()) {}

  ResourceConfigValue* FindValue(const ConfigDescription& config);
  ResourceConfigValue* FindValue(const ConfigDescription& config,
                                 const android::StringPiece& product);
  ResourceConfigValue* FindOrCreateValue(const ConfigDescription& config,
                                         const android::StringPiece& product);
  std::vector<ResourceConfigValue*> FindAllValues(const ConfigDescription& config);
  std::vector<ResourceConfigValue*> FindValuesIf(
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
  Symbol symbol_status;

  /**
   * List of resources for this type.
   */
  std::vector<std::unique_ptr<ResourceEntry>> entries;

  explicit ResourceTableType(const ResourceType type) : type(type) {}

  ResourceEntry* FindEntry(const android::StringPiece& name);
  ResourceEntry* FindOrCreateEntry(const android::StringPiece& name);

 private:
  DISALLOW_COPY_AND_ASSIGN(ResourceTableType);
};

class ResourceTablePackage {
 public:
  Maybe<uint8_t> id;
  std::string name;

  std::vector<std::unique_ptr<ResourceTableType>> types;

  ResourceTablePackage() = default;
  ResourceTableType* FindType(ResourceType type);
  ResourceTableType* FindOrCreateType(const ResourceType type);

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

  enum class CollisionResult { kKeepOriginal, kConflict, kTakeNew };

  using CollisionResolverFunc = std::function<CollisionResult(Value*, Value*)>;

  /**
   * When a collision of resources occurs, this method decides which value to
   * keep.
   */
  static CollisionResult ResolveValueCollision(Value* existing, Value* incoming);

  bool AddResource(const ResourceNameRef& name, const ConfigDescription& config,
                   const android::StringPiece& product, std::unique_ptr<Value> value,
                   IDiagnostics* diag);

  bool AddResource(const ResourceNameRef& name, const ResourceId& res_id,
                   const ConfigDescription& config, const android::StringPiece& product,
                   std::unique_ptr<Value> value, IDiagnostics* diag);

  bool AddFileReference(const ResourceNameRef& name, const ConfigDescription& config,
                        const Source& source, const android::StringPiece& path, IDiagnostics* diag);

  bool AddFileReferenceAllowMangled(const ResourceNameRef& name, const ConfigDescription& config,
                                    const Source& source, const android::StringPiece& path,
                                    io::IFile* file, IDiagnostics* diag);

  /**
   * Same as AddResource, but doesn't verify the validity of the name. This is
   * used
   * when loading resources from an existing binary resource table that may have
   * mangled
   * names.
   */
  bool AddResourceAllowMangled(const ResourceNameRef& name, const ConfigDescription& config,
                               const android::StringPiece& product, std::unique_ptr<Value> value,
                               IDiagnostics* diag);

  bool AddResourceAllowMangled(const ResourceNameRef& name, const ResourceId& id,
                               const ConfigDescription& config, const android::StringPiece& product,
                               std::unique_ptr<Value> value, IDiagnostics* diag);

  bool SetSymbolState(const ResourceNameRef& name, const ResourceId& res_id,
                      const Symbol& symbol, IDiagnostics* diag);

  bool SetSymbolStateAllowMangled(const ResourceNameRef& name, const ResourceId& res_id,
                                  const Symbol& symbol, IDiagnostics* diag);

  struct SearchResult {
    ResourceTablePackage* package;
    ResourceTableType* type;
    ResourceEntry* entry;
  };

  Maybe<SearchResult> FindResource(const ResourceNameRef& name);

  /**
   * Returns the package struct with the given name, or nullptr if such a
   * package does not
   * exist. The empty string is a valid package and typically is used to
   * represent the
   * 'current' package before it is known to the ResourceTable.
   */
  ResourceTablePackage* FindPackage(const android::StringPiece& name);

  ResourceTablePackage* FindPackageById(uint8_t id);

  ResourceTablePackage* CreatePackage(const android::StringPiece& name, Maybe<uint8_t> id = {});

  /**
   * The string pool used by this resource table. Values that reference strings
   * must use
   * this pool to create their strings.
   *
   * NOTE: `string_pool` must come before `packages` so that it is destroyed
   * after.
   * When `string_pool` references are destroyed (as they will be when
   * `packages`
   * is destroyed), they decrement a refCount, which would cause invalid
   * memory access if the pool was already destroyed.
   */
  StringPool string_pool;

  /**
   * The list of packages in this table, sorted alphabetically by package name.
   */
  std::vector<std::unique_ptr<ResourceTablePackage>> packages;

  // Set of dynamic packages that this table may reference. Their package names get encoded
  // into the resources.arsc along with their compile-time assigned IDs.
  std::map<size_t, std::string> included_packages_;

 private:
  // The function type that validates a symbol name. Returns a non-empty StringPiece representing
  // the offending character (which may be more than one byte in UTF-8). Returns an empty string
  // if the name was valid.
  using NameValidator = android::StringPiece(const android::StringPiece&);

  ResourceTablePackage* FindOrCreatePackage(const android::StringPiece& name);

  bool AddResourceImpl(const ResourceNameRef& name, const ResourceId& res_id,
                       const ConfigDescription& config, const android::StringPiece& product,
                       std::unique_ptr<Value> value, NameValidator name_validator,
                       const CollisionResolverFunc& conflict_resolver, IDiagnostics* diag);

  bool AddFileReferenceImpl(const ResourceNameRef& name, const ConfigDescription& config,
                            const Source& source, const android::StringPiece& path, io::IFile* file,
                            NameValidator name_validator, IDiagnostics* diag);

  bool SetSymbolStateImpl(const ResourceNameRef& name, const ResourceId& res_id,
                          const Symbol& symbol, NameValidator name_validator, IDiagnostics* diag);

  DISALLOW_COPY_AND_ASSIGN(ResourceTable);
};

}  // namespace aapt

#endif  // AAPT_RESOURCE_TABLE_H
