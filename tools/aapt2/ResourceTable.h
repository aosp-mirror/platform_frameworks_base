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

#include <functional>
#include <map>
#include <memory>
#include <string>
#include <tuple>
#include <unordered_map>
#include <vector>

#include "Resource.h"
#include "ResourceValues.h"
#include "android-base/macros.h"
#include "androidfw/ConfigDescription.h"
#include "androidfw/IDiagnostics.h"
#include "androidfw/Source.h"
#include "androidfw/StringPiece.h"
#include "androidfw/StringPool.h"
#include "io/File.h"

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
  android::Source source;
  std::string comment;

  // Indicates that the resource id may change across builds and that the public R.java identifier
  // for this resource should not be final. This is set to `true` for resources in `staging-group`
  // tags.
  bool staged_api = false;
};

// Represents <add-resource> in an overlay.
struct AllowNew {
  android::Source source;
  std::string comment;
};

// Represents the staged resource id of a finalized resource.
struct StagedId {
  ResourceId id;
  android::Source source;
};

struct Overlayable {
  Overlayable() = default;
  Overlayable(android::StringPiece name, android::StringPiece actor) : name(name), actor(actor) {
  }
  Overlayable(android::StringPiece name, android::StringPiece actor, const android::Source& source)
      : name(name), actor(actor), source(source) {
  }

  static const char* kActorScheme;
  std::string name;
  std::string actor;
  android::Source source;
};

// Represents a declaration that a resource is overlayable at runtime.
struct OverlayableItem {
  explicit OverlayableItem(const std::shared_ptr<Overlayable>& overlayable)
      : overlayable(overlayable) {}
  std::shared_ptr<Overlayable> overlayable;
  PolicyFlags policies = PolicyFlags::NONE;
  std::string comment;
  android::Source source;
};

class ResourceConfigValue {
 public:
  // The configuration for which this value is defined.
  const android::ConfigDescription config;

  // The product for which this value is defined.
  const std::string product;

  // The actual Value.
  std::unique_ptr<Value> value;

  ResourceConfigValue(const android::ConfigDescription& config, android::StringPiece product)
      : config(config), product(product) {
  }

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
  std::optional<ResourceId> id;

  // Whether this resource is public (and must maintain the same entry ID across builds).
  Visibility visibility;

  std::optional<AllowNew> allow_new;

  // The declarations of this resource as overlayable for RROs
  std::optional<OverlayableItem> overlayable_item;

  // The staged resource id for a finalized resource.
  std::optional<StagedId> staged_id;

  // The resource's values for each configuration.
  std::vector<std::unique_ptr<ResourceConfigValue>> values;

  explicit ResourceEntry(android::StringPiece name) : name(name) {
  }

  ResourceConfigValue* FindValue(const android::ConfigDescription& config,
                                 android::StringPiece product = {});
  const ResourceConfigValue* FindValue(const android::ConfigDescription& config,
                                       android::StringPiece product = {}) const;

  ResourceConfigValue* FindOrCreateValue(const android::ConfigDescription& config,
                                         android::StringPiece product);
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
  const ResourceNamedType named_type;

  // Whether this type is public (and must maintain the same type ID across builds).
  Visibility::Level visibility_level = Visibility::Level::kUndefined;

  // List of resources for this type.
  std::vector<std::unique_ptr<ResourceEntry>> entries;

  explicit ResourceTableType(const ResourceNamedTypeRef& type)
      : named_type(type.ToResourceNamedType()) {
  }

  ResourceEntry* CreateEntry(android::StringPiece name);
  ResourceEntry* FindEntry(android::StringPiece name) const;
  ResourceEntry* FindOrCreateEntry(android::StringPiece name);

 private:
  DISALLOW_COPY_AND_ASSIGN(ResourceTableType);
};

class ResourceTablePackage {
 public:
  std::string name;

  std::vector<std::unique_ptr<ResourceTableType>> types;

  explicit ResourceTablePackage(android::StringPiece name) : name(name) {
  }

  ResourceTablePackage() = default;
  ResourceTableType* FindTypeWithDefaultName(const ResourceType type) const;
  ResourceTableType* FindType(const ResourceNamedTypeRef& type) const;
  ResourceTableType* FindOrCreateType(const ResourceNamedTypeRef& type);

 private:
  DISALLOW_COPY_AND_ASSIGN(ResourceTablePackage);
};

struct ResourceTableEntryView {
  std::string name;
  std::optional<uint16_t> id;
  Visibility visibility;
  std::optional<AllowNew> allow_new;
  std::optional<OverlayableItem> overlayable_item;
  std::optional<StagedId> staged_id;
  std::vector<const ResourceConfigValue*> values;

  const ResourceConfigValue* FindValue(const android::ConfigDescription& config,
                                       android::StringPiece product = {}) const;
};

struct ResourceTableTypeView {
  ResourceNamedType named_type;
  std::optional<uint8_t> id;
  Visibility::Level visibility_level = Visibility::Level::kUndefined;

  // Entries sorted in ascending entry id order. If ids have not been assigned, the entries are
  // sorted lexicographically.
  std::vector<ResourceTableEntryView> entries;
};

struct ResourceTablePackageView {
  std::string name;
  std::optional<uint8_t> id;
  // Types sorted in ascending type id order. If ids have not been assigned, the types are sorted by
  // their declaration order in the ResourceType enum.
  std::vector<ResourceTableTypeView> types;
};

struct ResourceTableViewOptions {
  bool create_alias_entries = false;
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
  std::optional<StagedId> staged_id;
  bool allow_mangled = false;
  FlagStatus flag_status = FlagStatus::NoFlag;
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
  NewResourceBuilder& SetStagedId(StagedId id);
  NewResourceBuilder& SetAllowMangled(bool allow_mangled);
  NewResourceBuilder& SetFlagStatus(FlagStatus flag_status);
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

  bool AddResource(NewResource&& res, android::IDiagnostics* diag);

  // Retrieves a sorted a view of the packages, types, and entries sorted in ascending resource id
  // order.
  ResourceTableView GetPartitionedView(const ResourceTableViewOptions& options = {}) const;

  using ReferencedPackages = std::map<uint8_t, std::string>;
  const ReferencedPackages& GetReferencedPackages() const {
    return included_packages_;
  }

  struct SearchResult {
    ResourceTablePackage* package;
    ResourceTableType* type;
    ResourceEntry* entry;
  };

  std::optional<SearchResult> FindResource(const ResourceNameRef& name) const;
  std::optional<SearchResult> FindResource(const ResourceNameRef& name, ResourceId id) const;
  bool RemoveResource(const ResourceNameRef& name, ResourceId id) const;

  // Returns the package struct with the given name, or nullptr if such a package does not
  // exist. The empty string is a valid package and typically is used to represent the
  // 'current' package before it is known to the ResourceTable.
  ResourceTablePackage* FindPackage(android::StringPiece name) const;
  ResourceTablePackage* FindOrCreatePackage(android::StringPiece name);

  std::unique_ptr<ResourceTable> Clone() const;

  // When a collision of resources occurs, these methods decide which value to keep.
  static CollisionResult ResolveFlagCollision(FlagStatus existing, FlagStatus incoming);
  static CollisionResult ResolveValueCollision(Value* existing, Value* incoming);

  // The string pool used by this resource table. Values that reference strings must use
  // this pool to create their strings.
  // NOTE: `string_pool` must come before `packages` so that it is destroyed after.
  // When `string_pool` references are destroyed (as they will be when `packages` is destroyed),
  // they decrement a refCount, which would cause invalid memory access if the pool was already
  // destroyed.
  android::StringPool string_pool;

  // The list of packages in this table, sorted alphabetically by package name and increasing
  // package ID (missing ID being the lowest).
  std::vector<std::unique_ptr<ResourceTablePackage>> packages;

  // Set of dynamic packages that this table may reference. Their package names get encoded
  // into the resources.arsc along with their compile-time assigned IDs.
  ReferencedPackages included_packages_;

 private:
  DISALLOW_COPY_AND_ASSIGN(ResourceTable);

  Validation validation_ = Validation::kEnabled;
};

}  // namespace aapt

#endif  // AAPT_RESOURCE_TABLE_H
