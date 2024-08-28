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

#include "ResourceTable.h"

#include <algorithm>
#include <memory>
#include <optional>
#include <tuple>

#include "android-base/logging.h"
#include "androidfw/ConfigDescription.h"
#include "androidfw/ResourceTypes.h"

#include "NameMangler.h"
#include "ResourceUtils.h"
#include "ResourceValues.h"
#include "ValueVisitor.h"
#include "text/Unicode.h"
#include "trace/TraceBuffer.h"
#include "util/Util.h"

using ::aapt::text::IsValidResourceEntryName;
using ::android::ConfigDescription;
using ::android::StringPiece;
using ::android::base::StringPrintf;

namespace aapt {

const char* Overlayable::kActorScheme = "overlay";

namespace {
bool less_than_type(const std::unique_ptr<ResourceTableType>& lhs,
                    const ResourceNamedTypeRef& rhs) {
  return lhs->named_type < rhs;
}

template <typename T>
bool less_than_struct_with_name(const std::unique_ptr<T>& lhs, StringPiece rhs) {
  return lhs->name < rhs;
}

template <typename T>
bool greater_than_struct_with_name(StringPiece lhs, const std::unique_ptr<T>& rhs) {
  return rhs->name > lhs;
}

template <typename T>
struct NameEqualRange {
  bool operator()(const std::unique_ptr<T>& lhs, StringPiece rhs) const {
    return less_than_struct_with_name(lhs, rhs);
  }
  bool operator()(StringPiece lhs, const std::unique_ptr<T>& rhs) const {
    return greater_than_struct_with_name(lhs, rhs);
  }
};

template <typename T, typename U>
bool less_than_struct_with_name_and_id(const T& lhs,
                                       const std::pair<std::string_view, std::optional<U>>& rhs) {
  if (lhs.id != rhs.second) {
    return lhs.id < rhs.second;
  }
  return lhs.name < rhs.first;
}

template <typename T, typename Func, typename Elements>
T* FindElementsRunAction(android::StringPiece name, Elements& entries, Func action) {
  const auto iter =
      std::lower_bound(entries.begin(), entries.end(), name, less_than_struct_with_name<T>);
  const bool found = iter != entries.end() && name == (*iter)->name;
  return action(found, iter);
}

struct ConfigKey {
  const ConfigDescription* config;
  StringPiece product;
};

struct lt_config_key_ref {
  template <typename T>
  bool operator()(const T& lhs, const ConfigKey& rhs) const noexcept {
    int cmp = lhs->config.compare(*rhs.config);
    if (cmp == 0) {
      cmp = lhs->product.compare(rhs.product);
    }
    return cmp < 0;
  }
};

}  // namespace

ResourceTable::ResourceTable(ResourceTable::Validation validation) : validation_(validation) {
}

ResourceTablePackage* ResourceTable::FindPackage(android::StringPiece name) const {
  return FindElementsRunAction<ResourceTablePackage>(
      name, packages, [&](bool found, auto& iter) { return found ? iter->get() : nullptr; });
}

ResourceTablePackage* ResourceTable::FindOrCreatePackage(android::StringPiece name) {
  return FindElementsRunAction<ResourceTablePackage>(name, packages, [&](bool found, auto& iter) {
    return found ? iter->get() : packages.emplace(iter, new ResourceTablePackage(name))->get();
  });
}

template <typename Func, typename Elements>
static ResourceTableType* FindTypeRunAction(const ResourceNamedTypeRef& type, Elements& entries,
                                            Func action) {
  const auto iter = std::lower_bound(entries.begin(), entries.end(), type, less_than_type);
  const bool found = iter != entries.end() && type == (*iter)->named_type;
  return action(found, iter);
}

ResourceTableType* ResourceTablePackage::FindTypeWithDefaultName(const ResourceType type) const {
  auto named_type = ResourceNamedTypeWithDefaultName(type);
  return FindType(named_type);
}

ResourceTableType* ResourceTablePackage::FindType(const ResourceNamedTypeRef& type) const {
  return FindTypeRunAction(type, types,
                           [&](bool found, auto& iter) { return found ? iter->get() : nullptr; });
}

ResourceTableType* ResourceTablePackage::FindOrCreateType(const ResourceNamedTypeRef& type) {
  return FindTypeRunAction(type, types, [&](bool found, auto& iter) {
    return found ? iter->get() : types.emplace(iter, new ResourceTableType(type))->get();
  });
}

ResourceEntry* ResourceTableType::CreateEntry(android::StringPiece name) {
  return FindElementsRunAction<ResourceEntry>(name, entries, [&](bool found, auto& iter) {
    return entries.emplace(iter, new ResourceEntry(name))->get();
  });
}

ResourceEntry* ResourceTableType::FindEntry(android::StringPiece name) const {
  return FindElementsRunAction<ResourceEntry>(
      name, entries, [&](bool found, auto& iter) { return found ? iter->get() : nullptr; });
}

ResourceEntry* ResourceTableType::FindOrCreateEntry(android::StringPiece name) {
  return FindElementsRunAction<ResourceEntry>(name, entries, [&](bool found, auto& iter) {
    return found ? iter->get() : entries.emplace(iter, new ResourceEntry(name))->get();
  });
}

ResourceConfigValue* ResourceEntry::FindValue(const ConfigDescription& config,
                                              android::StringPiece product) {
  auto iter = std::lower_bound(values.begin(), values.end(), ConfigKey{&config, product},
                               lt_config_key_ref());
  if (iter != values.end()) {
    ResourceConfigValue* value = iter->get();
    if (value->config == config && value->product == product) {
      return value;
    }
  }
  return nullptr;
}

const ResourceConfigValue* ResourceEntry::FindValue(const android::ConfigDescription& config,
                                                    android::StringPiece product) const {
  auto iter = std::lower_bound(values.begin(), values.end(), ConfigKey{&config, product},
                               lt_config_key_ref());
  if (iter != values.end()) {
    ResourceConfigValue* value = iter->get();
    if (value->config == config && value->product == product) {
      return value;
    }
  }
  return nullptr;
}

ResourceConfigValue* ResourceEntry::FindOrCreateValue(const ConfigDescription& config,
                                                      StringPiece product) {
  auto iter = std::lower_bound(values.begin(), values.end(), ConfigKey{&config, product},
                               lt_config_key_ref());
  if (iter != values.end()) {
    ResourceConfigValue* value = iter->get();
    if (value->config == config && value->product == product) {
      return value;
    }
  }
  ResourceConfigValue* newValue =
      values.insert(iter, util::make_unique<ResourceConfigValue>(config, product))->get();
  return newValue;
}

std::vector<ResourceConfigValue*> ResourceEntry::FindAllValues(const ConfigDescription& config) {
  std::vector<ResourceConfigValue*> results;
  auto iter =
      std::lower_bound(values.begin(), values.end(), ConfigKey{&config, ""}, lt_config_key_ref());
  for (; iter != values.end(); ++iter) {
    ResourceConfigValue* value = iter->get();
    if (value->config != config) {
      break;
    }
    results.push_back(value);
  }
  return results;
}

bool ResourceEntry::HasDefaultValue() const {
  // The default config should be at the top of the list, since the list is sorted.
  return !values.empty() && values.front()->config == ConfigDescription::DefaultConfig();
}

ResourceTable::CollisionResult ResourceTable::ResolveFlagCollision(FlagStatus existing,
                                                                   FlagStatus incoming) {
  switch (existing) {
    case FlagStatus::NoFlag:
      switch (incoming) {
        case FlagStatus::NoFlag:
          return CollisionResult::kConflict;
        case FlagStatus::Disabled:
          return CollisionResult::kKeepOriginal;
        case FlagStatus::Enabled:
          return CollisionResult::kTakeNew;
        default:
          return CollisionResult::kConflict;
      }
    case FlagStatus::Disabled:
      switch (incoming) {
        case FlagStatus::NoFlag:
          return CollisionResult::kTakeNew;
        case FlagStatus::Disabled:
          return CollisionResult::kKeepOriginal;
        case FlagStatus::Enabled:
          return CollisionResult::kTakeNew;
        default:
          return CollisionResult::kConflict;
      }
    case FlagStatus::Enabled:
      switch (incoming) {
        case FlagStatus::NoFlag:
          return CollisionResult::kKeepOriginal;
        case FlagStatus::Disabled:
          return CollisionResult::kKeepOriginal;
        case FlagStatus::Enabled:
          return CollisionResult::kConflict;
        default:
          return CollisionResult::kConflict;
      }
    default:
      return CollisionResult::kConflict;
  }
}

// The default handler for collisions.
//
// Typically, a weak value will be overridden by a strong value. An existing weak
// value will not be overridden by an incoming weak value.
//
// There are some exceptions:
//
// Attributes: There are two types of Attribute values: USE and DECL.
//
// USE is anywhere an Attribute is declared without a format, and in a place that would
// be legal to declare if the Attribute already existed. This is typically in a
// <declare-styleable> tag. Attributes defined in a <declare-styleable> are also weak.
//
// DECL is an absolute declaration of an Attribute and specifies an explicit format.
//
// A DECL will override a USE without error. Two DECLs must match in their format for there to be
// no error.
ResourceTable::CollisionResult ResourceTable::ResolveValueCollision(Value* existing,
                                                                    Value* incoming) {
  Attribute* existing_attr = ValueCast<Attribute>(existing);
  Attribute* incoming_attr = ValueCast<Attribute>(incoming);
  if (!incoming_attr) {
    if (incoming->IsWeak()) {
      // We're trying to add a weak resource but a resource
      // already exists. Keep the existing.
      return CollisionResult::kKeepOriginal;
    } else if (existing->IsWeak()) {
      // Override the weak resource with the new strong resource.
      return CollisionResult::kTakeNew;
    }
    // The existing and incoming values are strong, this is an error
    // if the values are not both attributes.
    return CollisionResult::kConflict;
  }

  if (!existing_attr) {
    if (existing->IsWeak()) {
      // The existing value is not an attribute and it is weak,
      // so take the incoming attribute value.
      return CollisionResult::kTakeNew;
    }
    // The existing value is not an attribute and it is strong,
    // so the incoming attribute value is an error.
    return CollisionResult::kConflict;
  }

  CHECK(incoming_attr != nullptr && existing_attr != nullptr);

  //
  // Attribute specific handling. At this point we know both
  // values are attributes. Since we can declare and define
  // attributes all-over, we do special handling to see
  // which definition sticks.
  //
  if (existing_attr->IsCompatibleWith(*incoming_attr)) {
    // The two attributes are both DECLs, but they are plain attributes with compatible formats.
    // Keep the strongest one.
    return existing_attr->IsWeak() ? CollisionResult::kTakeNew : CollisionResult::kKeepOriginal;
  }

  if (existing_attr->IsWeak() && existing_attr->type_mask == android::ResTable_map::TYPE_ANY) {
    // Any incoming attribute is better than this.
    return CollisionResult::kTakeNew;
  }

  if (incoming_attr->IsWeak() && incoming_attr->type_mask == android::ResTable_map::TYPE_ANY) {
    // The incoming attribute may be a USE instead of a DECL.
    // Keep the existing attribute.
    return CollisionResult::kKeepOriginal;
  }

  return CollisionResult::kConflict;
}

namespace {
template <typename T, typename Comparer>
struct SortedVectorInserter : public Comparer {
  std::pair<bool, typename std::vector<T>::iterator> LowerBound(std::vector<T>& el,
                                                                const T& value) {
    auto it = std::lower_bound(el.begin(), el.end(), value, [&](auto& lhs, auto& rhs) {
      return Comparer::operator()(lhs, rhs);
    });
    bool found =
        it != el.end() && !Comparer::operator()(*it, value) && !Comparer::operator()(value, *it);
    return std::make_pair(found, it);
  }

  T* Insert(std::vector<T>& el, T&& value) {
    auto [found, it] = LowerBound(el, value);
    if (found) {
      return &*it;
    }
    return &*el.insert(it, std::move(value));
  }
};

struct PackageViewComparer {
  bool operator()(const ResourceTablePackageView& lhs, const ResourceTablePackageView& rhs) {
    return less_than_struct_with_name_and_id<ResourceTablePackageView, uint8_t>(
        lhs, std::tie(rhs.name, rhs.id));
  }
};

struct TypeViewComparer {
  bool operator()(const ResourceTableTypeView& lhs, const ResourceTableTypeView& rhs) {
    return lhs.id != rhs.id ? lhs.id < rhs.id : lhs.named_type < rhs.named_type;
  }
};

struct EntryViewComparer {
  bool operator()(const ResourceTableEntryView& lhs, const ResourceTableEntryView& rhs) {
    return less_than_struct_with_name_and_id<ResourceTableEntryView, uint16_t>(
        lhs, std::tie(rhs.name, rhs.id));
  }
};

void InsertEntryIntoTableView(ResourceTableView& table, const ResourceTablePackage* package,
                              const ResourceTableType* type, const std::string& entry_name,
                              const std::optional<ResourceId>& id, const Visibility& visibility,
                              const std::optional<AllowNew>& allow_new,
                              const std::optional<OverlayableItem>& overlayable_item,
                              const std::optional<StagedId>& staged_id,
                              const std::vector<std::unique_ptr<ResourceConfigValue>>& values) {
  SortedVectorInserter<ResourceTablePackageView, PackageViewComparer> package_inserter;
  SortedVectorInserter<ResourceTableTypeView, TypeViewComparer> type_inserter;
  SortedVectorInserter<ResourceTableEntryView, EntryViewComparer> entry_inserter;

  ResourceTablePackageView new_package{package->name,
                                       id ? id.value().package_id() : std::optional<uint8_t>{}};
  auto view_package = package_inserter.Insert(table.packages, std::move(new_package));

  ResourceTableTypeView new_type{type->named_type,
                                 id ? id.value().type_id() : std::optional<uint8_t>{}};
  auto view_type = type_inserter.Insert(view_package->types, std::move(new_type));

  if (visibility.level == Visibility::Level::kPublic) {
    // Only mark the type visibility level as public, it doesn't care about being private.
    view_type->visibility_level = Visibility::Level::kPublic;
  }

  ResourceTableEntryView new_entry{.name = entry_name,
                                   .id = id ? id.value().entry_id() : std::optional<uint16_t>{},
                                   .visibility = visibility,
                                   .allow_new = allow_new,
                                   .overlayable_item = overlayable_item,
                                   .staged_id = staged_id};
  for (auto& value : values) {
    new_entry.values.emplace_back(value.get());
  }

  entry_inserter.Insert(view_type->entries, std::move(new_entry));
}
}  // namespace

const ResourceConfigValue* ResourceTableEntryView::FindValue(const ConfigDescription& config,
                                                             android::StringPiece product) const {
  auto iter = std::lower_bound(values.begin(), values.end(), ConfigKey{&config, product},
                               lt_config_key_ref());
  if (iter != values.end()) {
    const ResourceConfigValue* value = *iter;
    if (value->config == config && value->product == product) {
      return value;
    }
  }
  return nullptr;
}

ResourceTableView ResourceTable::GetPartitionedView(const ResourceTableViewOptions& options) const {
  ResourceTableView view;
  for (const auto& package : packages) {
    for (const auto& type : package->types) {
      for (const auto& entry : type->entries) {
        InsertEntryIntoTableView(view, package.get(), type.get(), entry->name, entry->id,
                                 entry->visibility, entry->allow_new, entry->overlayable_item,
                                 entry->staged_id, entry->values);

        if (options.create_alias_entries && entry->staged_id) {
          auto alias_id = entry->staged_id.value().id;
          InsertEntryIntoTableView(view, package.get(), type.get(), entry->name, alias_id,
                                   entry->visibility, entry->allow_new, entry->overlayable_item, {},
                                   entry->values);
        }
      }
    }
  }

  // The android runtime does not support querying resources when the there are multiple type ids
  // for the same resource type within the same package. For this reason, if there are types with
  // multiple type ids, each type needs to exist in its own package in order to be queried by name.
  std::vector<ResourceTablePackageView> new_packages;
  SortedVectorInserter<ResourceTablePackageView, PackageViewComparer> package_inserter;
  SortedVectorInserter<ResourceTableTypeView, TypeViewComparer> type_inserter;
  for (auto& package : view.packages) {
    // If a new package was already created for a different type within this package, then
    // we can reuse those packages for other types that need to be extracted from this package.
    // `start_index` is the index of the first newly created package that can be reused.
    const size_t start_index = new_packages.size();
    std::map<ResourceNamedType, size_t> type_new_package_index;
    for (auto type_it = package.types.begin(); type_it != package.types.end();) {
      auto& type = *type_it;
      auto type_index_iter = type_new_package_index.find(type.named_type);
      if (type_index_iter == type_new_package_index.end()) {
        // First occurrence of the resource type in this package. Keep it in this package.
        type_new_package_index.insert(type_index_iter,
                                      std::make_pair(type.named_type, start_index));
        ++type_it;
        continue;
      }

      // The resource type has already been seen for this package, so this type must be extracted to
      // a new separate package.
      const size_t index = type_index_iter->second;
      if (new_packages.size() == index) {
        new_packages.emplace_back(ResourceTablePackageView{package.name, package.id});
      }

      // Move the type into a new package
      auto& other_package = new_packages[index];
      type_new_package_index[type.named_type] = index + 1;
      type_inserter.Insert(other_package.types, std::move(type));
      type_it = package.types.erase(type_it);
    }
  }

  for (auto& new_package : new_packages) {
    // Insert newly created packages after their original packages
    auto [_, it] = package_inserter.LowerBound(view.packages, new_package);
    view.packages.insert(++it, std::move(new_package));
  }

  return view;
}

bool ResourceTable::AddResource(NewResource&& res, android::IDiagnostics* diag) {
  CHECK(diag != nullptr) << "Diagnostic pointer is null";

  const bool validate = validation_ == Validation::kEnabled;
  const android::Source source = res.value ? res.value->GetSource() : android::Source{};
  if (validate && !res.allow_mangled && !IsValidResourceEntryName(res.name.entry)) {
    diag->Error(android::DiagMessage(source)
                << "resource '" << res.name << "' has invalid entry name '" << res.name.entry);
    return false;
  }

  if (res.id.has_value() && !res.id->first.is_valid()) {
    diag->Error(android::DiagMessage(source)
                << "trying to add resource '" << res.name << "' with ID " << res.id->first
                << " but that ID is invalid");
    return false;
  }

  auto package = FindOrCreatePackage(res.name.package);
  auto type = package->FindOrCreateType(res.name.type);
  auto entry_it = std::equal_range(type->entries.begin(), type->entries.end(), res.name.entry,
                                   NameEqualRange<ResourceEntry>{});
  const size_t entry_count = std::distance(entry_it.first, entry_it.second);

  ResourceEntry* entry;
  if (entry_count == 0) {
    // Adding a new resource
    entry = type->CreateEntry(res.name.entry);
  } else if (entry_count == 1) {
    // Assume that the existing resource is being modified
    entry = entry_it.first->get();
  } else {
    // Multiple resources with the same name exist in the resource table. The only way to
    // distinguish between them is using resource id since each resource should have a unique id.
    CHECK(res.id.has_value()) << "ambiguous modification of resource entry '" << res.name
                              << "' without specifying a resource id.";
    entry = entry_it.first->get();
    for (auto it = entry_it.first; it != entry_it.second; ++it) {
      CHECK((bool)(*it)->id) << "ambiguous modification of resource entry '" << res.name
                             << "' with multiple entries without resource ids";
      if ((*it)->id == res.id->first) {
        entry = it->get();
        break;
      }
    }
  }

  if (res.id.has_value()) {
    if (entry->id && entry->id.value() != res.id->first) {
      if (res.id->second != OnIdConflict::CREATE_ENTRY) {
        diag->Error(android::DiagMessage(source)
                    << "trying to add resource '" << res.name << "' with ID " << res.id->first
                    << " but resource already has ID " << entry->id.value());
        return false;
      }
      entry = type->CreateEntry(res.name.entry);
    }
    entry->id = res.id->first;
  }

  if (res.visibility.has_value()) {
    // Only mark the type visibility level as public, it doesn't care about being private.
    if (res.visibility->level == Visibility::Level::kPublic) {
      type->visibility_level = Visibility::Level::kPublic;
    }

    if (res.visibility->level > entry->visibility.level) {
      // This symbol definition takes precedence, replace.
      entry->visibility = res.visibility.value();
    }

    if (res.visibility->staged_api) {
      entry->visibility.staged_api = entry->visibility.staged_api;
    }
  }

  if (res.overlayable.has_value()) {
    if (entry->overlayable_item) {
      diag->Error(android::DiagMessage(res.overlayable->source)
                  << "duplicate overlayable declaration for resource '" << res.name << "'");
      diag->Error(android::DiagMessage(entry->overlayable_item.value().source)
                  << "previous declaration here");
      return false;
    }
    entry->overlayable_item = res.overlayable.value();
  }

  if (res.allow_new.has_value()) {
    entry->allow_new = res.allow_new.value();
  }

  if (res.staged_id.has_value()) {
    entry->staged_id = res.staged_id.value();
  }

  if (res.value != nullptr) {
    auto config_value = entry->FindOrCreateValue(res.config, res.product);
    if (!config_value->value) {
      // Resource does not exist, add it now.
      config_value->value = std::move(res.value);
    } else {
      // When validation is enabled, ensure that a resource cannot have multiple values defined for
      // the same configuration unless protected by flags.
      auto result =
          validate ? ResolveFlagCollision(config_value->value->GetFlagStatus(), res.flag_status)
                   : CollisionResult::kKeepBoth;
      if (result == CollisionResult::kConflict) {
        result = ResolveValueCollision(config_value->value.get(), res.value.get());
      }
      switch (result) {
        case CollisionResult::kKeepBoth: {
          // Insert the value ignoring for duplicate configurations
          auto it = entry->values.insert(
              std::lower_bound(entry->values.begin(), entry->values.end(),
                               ConfigKey{&res.config, res.product}, lt_config_key_ref()),
              util::make_unique<ResourceConfigValue>(res.config, res.product));
          (*it)->value = std::move(res.value);
          break;
        }

        case CollisionResult::kTakeNew:
          // Take the incoming value.
          config_value->value = std::move(res.value);
          break;

        case CollisionResult::kConflict:
          diag->Error(android::DiagMessage(source)
                      << "duplicate value for resource '" << res.name << "' "
                      << "with config '" << res.config << "'");
          diag->Error(android::DiagMessage(source) << "resource previously defined here");
          return false;

        case CollisionResult::kKeepOriginal:
          break;
      }
    }
  }

  return true;
}

std::optional<ResourceTable::SearchResult> ResourceTable::FindResource(
    const ResourceNameRef& name) const {
  ResourceTablePackage* package = FindPackage(name.package);
  if (package == nullptr) {
    return {};
  }

  ResourceTableType* type = package->FindType(name.type);
  if (type == nullptr) {
    return {};
  }

  ResourceEntry* entry = type->FindEntry(name.entry);
  if (entry == nullptr) {
    return {};
  }
  return SearchResult{package, type, entry};
}

std::optional<ResourceTable::SearchResult> ResourceTable::FindResource(const ResourceNameRef& name,
                                                                       ResourceId id) const {
  ResourceTablePackage* package = FindPackage(name.package);
  if (package == nullptr) {
    return {};
  }

  ResourceTableType* type = package->FindType(name.type);
  if (type == nullptr) {
    return {};
  }

  auto entry_it = std::equal_range(type->entries.begin(), type->entries.end(), name.entry,
                                   NameEqualRange<ResourceEntry>{});
  for (auto it = entry_it.first; it != entry_it.second; ++it) {
    if ((*it)->id == id) {
      return SearchResult{package, type, it->get()};
    }
  }
  return {};
}

bool ResourceTable::RemoveResource(const ResourceNameRef& name, ResourceId id) const {
  ResourceTablePackage* package = FindPackage(name.package);
  if (package == nullptr) {
    return {};
  }

  ResourceTableType* type = package->FindType(name.type);
  if (type == nullptr) {
    return {};
  }

  auto entry_it = std::equal_range(type->entries.begin(), type->entries.end(), name.entry,
                                   NameEqualRange<ResourceEntry>{});
  for (auto it = entry_it.first; it != entry_it.second; ++it) {
    if ((*it)->id == id) {
      type->entries.erase(it);
      return true;
    }
  }
  return false;
}

std::unique_ptr<ResourceTable> ResourceTable::Clone() const {
  std::unique_ptr<ResourceTable> new_table = util::make_unique<ResourceTable>();
  CloningValueTransformer cloner(&new_table->string_pool);
  for (const auto& pkg : packages) {
    ResourceTablePackage* new_pkg = new_table->FindOrCreatePackage(pkg->name);
    for (const auto& type : pkg->types) {
      ResourceTableType* new_type = new_pkg->FindOrCreateType(type->named_type);
      new_type->visibility_level = type->visibility_level;

      for (const auto& entry : type->entries) {
        ResourceEntry* new_entry = new_type->CreateEntry(entry->name);
        new_entry->id = entry->id;
        new_entry->visibility = entry->visibility;
        new_entry->allow_new = entry->allow_new;
        new_entry->overlayable_item = entry->overlayable_item;

        for (const auto& config_value : entry->values) {
          ResourceConfigValue* new_value =
              new_entry->FindOrCreateValue(config_value->config, config_value->product);
          new_value->value = config_value->value->Transform(cloner);
        }
      }
    }
  }
  return new_table;
}

NewResourceBuilder::NewResourceBuilder(const ResourceNameRef& name) {
  res_.name = name.ToResourceName();
}

NewResourceBuilder::NewResourceBuilder(const std::string& name) {
  ResourceNameRef ref;
  CHECK(ResourceUtils::ParseResourceName(name, &ref)) << "invalid resource name: " << name;
  res_.name = ref.ToResourceName();
}

NewResourceBuilder& NewResourceBuilder::SetValue(std::unique_ptr<Value> value,
                                                 android::ConfigDescription config,
                                                 std::string product) {
  res_.value = std::move(value);
  res_.config = std::move(config);
  res_.product = std::move(product);
  return *this;
}

NewResourceBuilder& NewResourceBuilder::SetId(ResourceId id, OnIdConflict on_conflict) {
  res_.id = std::make_pair(id, on_conflict);
  return *this;
}

NewResourceBuilder& NewResourceBuilder::SetVisibility(Visibility visibility) {
  res_.visibility = std::move(visibility);
  return *this;
}

NewResourceBuilder& NewResourceBuilder::SetOverlayable(OverlayableItem overlayable) {
  res_.overlayable = std::move(overlayable);
  return *this;
}
NewResourceBuilder& NewResourceBuilder::SetAllowNew(AllowNew allow_new) {
  res_.allow_new = std::move(allow_new);
  return *this;
}

NewResourceBuilder& NewResourceBuilder::SetStagedId(StagedId staged_alias) {
  res_.staged_id = std::move(staged_alias);
  return *this;
}

NewResourceBuilder& NewResourceBuilder::SetAllowMangled(bool allow_mangled) {
  res_.allow_mangled = allow_mangled;
  return *this;
}

NewResourceBuilder& NewResourceBuilder::SetFlagStatus(FlagStatus flag_status) {
  res_.flag_status = flag_status;
  return *this;
}

NewResource NewResourceBuilder::Build() {
  return std::move(res_);
}

}  // namespace aapt
