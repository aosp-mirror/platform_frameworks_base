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

#include "Diagnostics.h"
#include "Resource.h"
#include "ResourceValues.h"
#include "Source.h"
#include "StringPool.h"
#include "io/File.h"

#include "android-base/macros.h"
#include "androidfw/ConfigDescription.h"
#include "androidfw/StringPiece.h"

#include <functional>
#include <map>
#include <memory>
#include <string>
#include <tuple>
#include <unordered_map>
#include <vector>

using PolicyFlags = android::ResTable_overlayable_policy_header::PolicyFlags;

namespace aapt {

// The Public status of a resource.
struct Visibility {
  enum class Level {
    kUndefined,
    kPrivate,
    kPublic,
  };

  Level level = Level::kUndefined;
  Source source;
  std::string comment;

  // Indicates that the resource id may change across builds and that the public R.java identifier
  // for this resource should not be final. This is set to `true` for resources in `staging-group`
  // tags.
  bool staged_api = false;
};

// Represents <add-resource> in an overlay.
struct AllowNew {
  Source source;
  std::string comment;
};

struct Overlayable {
  Overlayable() = default;
   Overlayable(const android::StringPiece& name, const android::StringPiece& actor)
       : name(name.to_string()), actor(actor.to_string()) {}
   Overlayable(const android::StringPiece& name, const android::StringPiece& actor,
                    const Source& source)
       : name(name.to_string()), actor(actor.to_string()), source(source ){}

  static const char* kActorScheme;
  std::string name;
  std::string actor;
  Source source;
};

// Represents a declaration that a resource is overlayable at runtime.
struct OverlayableItem {
  explicit OverlayableItem(const std::shared_ptr<Overlayable>& overlayable)
      : overlayable(overlayable) {}
  std::shared_ptr<Overlayable> overlayable;
  PolicyFlags policies = PolicyFlags::NONE;
  std::string comment;
  Source source;
};

class ResourceConfigValue {
 public:
  // The configuration for which this value is defined.
  const android::ConfigDescription config;

  // The product for which this value is defined.
  const std::string product;

  // The actual Value.
  std::unique_ptr<Value> value;

  ResourceConfigValue(const android::ConfigDescription& config, const android::StringPiece& product)
      : config(config), product(product.to_string()) {}

 private:
  DISALLOW_COPY_AND_ASSIGN(ResourceConfigValue);
};

// Represents a resource entry, which may have varying values for each defined configuration.
class ResourceEntry {
 public:
  // The name of the resource. Immutable, as this determines the order of this resource
  // when doing lookups.
  const std::string name;

  // The entry ID for this resource (the EEEE in 0xPPTTEEEE).
  Maybe<ResourceId> id;

  // Whether this resource is public (and must maintain the same entry ID across builds).
  Visibility visibility;

  Maybe<AllowNew> allow_new;

  // The declarations of this resource as overlayable for RROs
  Maybe<OverlayableItem> overlayable_item;

  // The resource's values for each configuration.
  std::vector<std::unique_ptr<ResourceConfigValue>> values;

  explicit ResourceEntry(const android::StringPiece& name) : name(name.to_string()) {}

  ResourceConfigValue* FindValue(const android::ConfigDescription& config,
                                 android::StringPiece product = {});
  const ResourceConfigValue* FindValue(const android::ConfigDescription& config,
                                       android::StringPiece product = {}) const;

  ResourceConfigValue* FindOrCreateValue(const android::ConfigDescription& config,
                                         const android::StringPiece& product);
  std::vector<ResourceConfigValue*> FindAllValues(const android::ConfigDescription& config);

  template <typename Func>
  std::vector<ResourceConfigValue*> FindValuesIf(Func f) {
    std::vector<ResourceConfigValue*> results;
    for (auto& config_value : values) {
      if (f(config_value.get())) {
        results.push_back(config_value.get());
      }
    }
    return results;
  }

  bool HasDefaultValue() const;

 private:
  DISALLOW_COPY_AND_ASSIGN(ResourceEntry);
};

// Represents a resource type (eg. string, drawable, layout, etc.) containing resource entries.
class ResourceTableType {
 public:
  // The logical type of resource (string, drawable, layout, etc.).
  const ResourceType type;

  // Whether this type is public (and must maintain the same type ID across builds).
  Visibility::Level visibility_level = Visibility::Level::kUndefined;

  // List of resources for this type.
  std::vector<std::unique_ptr<ResourceEntry>> entries;

  explicit ResourceTableType(const ResourceType type) : type(type) {}

  ResourceEntry* CreateEntry(const android::StringPiece& name);
  ResourceEntry* FindEntry(const android::StringPiece& name) const;
  ResourceEntry* FindOrCreateEntry(const android::StringPiece& name);

 private:
  DISALLOW_COPY_AND_ASSIGN(ResourceTableType);
};

class ResourceTablePackage {
 public:
  std::string name;

  std::vector<std::unique_ptr<ResourceTableType>> types;

  explicit ResourceTablePackage(const android::StringPiece& name) : name(name.to_string()) {
  }

  ResourceTablePackage() = default;
  ResourceTableType* FindType(ResourceType type) const;
  ResourceTableType* FindOrCreateType(ResourceType type);

 private:
  DISALLOW_COPY_AND_ASSIGN(ResourceTablePackage);
};

struct ResourceTableTypeView {
  ResourceType type;
  Maybe<uint8_t> id;
  Visibility::Level visibility_level = Visibility::Level::kUndefined;

  // Entries sorted in ascending entry id order. If ids have not been assigned, the entries are
  //  // sorted lexicographically.
  std::vector<const ResourceEntry*> entries;
};

struct ResourceTablePackageView {
  std::string name;
  Maybe<uint8_t> id;
  // Types sorted in ascending type id order. If ids have not been assigned, the types are sorted by
  // their declaration order in the ResourceType enum.
  std::vector<ResourceTableTypeView> types;
};

struct ResourceTableView {
  // Packages sorted in ascending package id order. If ids have not been assigned, the packages are
  // sorted lexicographically.
  std::vector<ResourceTablePackageView> packages;
};

enum class OnIdConflict {
  // If the resource entry already exists but has a different resource id, the resource value will
  // not be added to the table.
  ERROR,

  // If the resource entry already exists but has a different resource id, create a new resource
  // with this resource name and id combination.
  CREATE_ENTRY,
};

struct NewResource {
  ResourceName name;
  std::unique_ptr<Value> value;
  android::ConfigDescription config;
  std::string product;
  std::optional<std::pair<ResourceId, OnIdConflict>> id;
  std::optional<Visibility> visibility;
  std::optional<OverlayableItem> overlayable;
  std::optional<AllowNew> allow_new;
  bool allow_mangled = false;
};

struct NewResourceBuilder {
  explicit NewResourceBuilder(const ResourceNameRef& name);
  explicit NewResourceBuilder(const std::string& name);
  NewResourceBuilder& SetValue(std::unique_ptr<Value> value, android::ConfigDescription config = {},
                               std::string product = {});
  NewResourceBuilder& SetId(ResourceId id, OnIdConflict on_conflict = OnIdConflict::ERROR);
  NewResourceBuilder& SetVisibility(Visibility id);
  NewResourceBuilder& SetOverlayable(OverlayableItem overlayable);
  NewResourceBuilder& SetAllowNew(AllowNew allow_new);
  NewResourceBuilder& SetAllowMangled(bool allow_mangled);
  NewResource Build();

 private:
  NewResource res_;
};

// The container and index for all resources defined for an app.
class ResourceTable {
 public:
  enum class Validation {
    kEnabled,
    kDisabled,
  };

  enum class CollisionResult { kKeepBoth, kKeepOriginal, kConflict, kTakeNew };

  ResourceTable() = default;
  explicit ResourceTable(Validation validation);

  bool AddResource(NewResource&& res, IDiagnostics* diag);

  // Retrieves a sorted a view of the packages, types, and entries sorted in ascending resource id
  // order.
  ResourceTableView GetPartitionedView() const;

  struct SearchResult {
    ResourceTablePackage* package;
    ResourceTableType* type;
    ResourceEntry* entry;
  };

  Maybe<SearchResult> FindResource(const ResourceNameRef& name) const;
  Maybe<SearchResult> FindResource(const ResourceNameRef& name, ResourceId id) const;

  // Returns the package struct with the given name, or nullptr if such a package does not
  // exist. The empty string is a valid package and typically is used to represent the
  // 'current' package before it is known to the ResourceTable.
  ResourceTablePackage* FindPackage(const android::StringPiece& name) const;
  ResourceTablePackage* FindOrCreatePackage(const android::StringPiece& name);

  std::unique_ptr<ResourceTable> Clone() const;

  // When a collision of resources occurs, this method decides which value to keep.
  static CollisionResult ResolveValueCollision(Value* existing, Value* incoming);

  // The string pool used by this resource table. Values that reference strings must use
  // this pool to create their strings.
  // NOTE: `string_pool` must come before `packages` so that it is destroyed after.
  // When `string_pool` references are destroyed (as they will be when `packages` is destroyed),
  // they decrement a refCount, which would cause invalid memory access if the pool was already
  // destroyed.
  StringPool string_pool;

  // The list of packages in this table, sorted alphabetically by package name and increasing
  // package ID (missing ID being the lowest).
  std::vector<std::unique_ptr<ResourceTablePackage>> packages;

  // Set of dynamic packages that this table may reference. Their package names get encoded
  // into the resources.arsc along with their compile-time assigned IDs.
  std::map<size_t, std::string> included_packages_;

 private:
  DISALLOW_COPY_AND_ASSIGN(ResourceTable);

  Validation validation_ = Validation::kEnabled;
};

}  // namespace aapt

#endif  // AAPT_RESOURCE_TABLE_H
