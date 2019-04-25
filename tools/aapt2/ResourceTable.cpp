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
#include <string>
#include <tuple>

#include "android-base/logging.h"
#include "android-base/stringprintf.h"
#include "androidfw/ConfigDescription.h"
#include "androidfw/ResourceTypes.h"

#include "Debug.h"
#include "NameMangler.h"
#include "ResourceValues.h"
#include "ValueVisitor.h"
#include "trace/TraceBuffer.h"
#include "text/Unicode.h"
#include "util/Util.h"

using ::aapt::text::IsValidResourceEntryName;
using ::android::ConfigDescription;
using ::android::StringPiece;
using ::android::base::StringPrintf;

namespace aapt {

const char* Overlayable::kActorScheme = "overlay";

static bool less_than_type_and_id(const std::unique_ptr<ResourceTableType>& lhs,
                                  const std::pair<ResourceType, Maybe<uint8_t>>& rhs) {
  return lhs->type < rhs.first || (lhs->type == rhs.first && rhs.second && lhs->id < rhs.second);
}

template <typename T>
static bool less_than_struct_with_name(const std::unique_ptr<T>& lhs, const StringPiece& rhs) {
  return lhs->name.compare(0, lhs->name.size(), rhs.data(), rhs.size()) < 0;
}

template <typename T>
static bool less_than_struct_with_name_and_id(const std::unique_ptr<T>& lhs,
                                              const std::pair<StringPiece, Maybe<uint16_t>>& rhs) {
  int name_cmp = lhs->name.compare(0, lhs->name.size(), rhs.first.data(), rhs.first.size());
  return name_cmp < 0 || (name_cmp == 0 && rhs.second && lhs->id < rhs.second);
}

ResourceTablePackage* ResourceTable::FindPackage(const StringPiece& name) const {
  const auto last = packages.end();
  auto iter = std::lower_bound(packages.begin(), last, name,
                               less_than_struct_with_name<ResourceTablePackage>);
  if (iter != last && name == (*iter)->name) {
    return iter->get();
  }
  return nullptr;
}

ResourceTablePackage* ResourceTable::FindPackageById(uint8_t id) const {
  for (auto& package : packages) {
    if (package->id && package->id.value() == id) {
      return package.get();
    }
  }
  return nullptr;
}

ResourceTablePackage* ResourceTable::CreatePackage(const StringPiece& name, Maybe<uint8_t> id) {
  TRACE_CALL();
  ResourceTablePackage* package = FindOrCreatePackage(name);
  if (id && !package->id) {
    package->id = id;
    return package;
  }

  if (id && package->id && package->id.value() != id.value()) {
    return nullptr;
  }
  return package;
}

ResourceTablePackage* ResourceTable::CreatePackageAllowingDuplicateNames(const StringPiece& name,
                                                                         const Maybe<uint8_t> id) {
  const auto last = packages.end();
  auto iter = std::lower_bound(packages.begin(), last, std::make_pair(name, id),
                               less_than_struct_with_name_and_id<ResourceTablePackage>);

  if (iter != last && name == (*iter)->name && id == (*iter)->id) {
    return iter->get();
  }

  std::unique_ptr<ResourceTablePackage> new_package = util::make_unique<ResourceTablePackage>();
  new_package->name = name.to_string();
  new_package->id = id;
  return packages.emplace(iter, std::move(new_package))->get();
}

ResourceTablePackage* ResourceTable::FindOrCreatePackage(const StringPiece& name) {
  const auto last = packages.end();
  auto iter = std::lower_bound(packages.begin(), last, name,
                               less_than_struct_with_name<ResourceTablePackage>);
  if (iter != last && name == (*iter)->name) {
    return iter->get();
  }

  std::unique_ptr<ResourceTablePackage> new_package = util::make_unique<ResourceTablePackage>();
  new_package->name = name.to_string();
  return packages.emplace(iter, std::move(new_package))->get();
}

ResourceTableType* ResourceTablePackage::FindType(ResourceType type, const Maybe<uint8_t> id) {
  const auto last = types.end();
  auto iter = std::lower_bound(types.begin(), last, std::make_pair(type, id),
                               less_than_type_and_id);
  if (iter != last && (*iter)->type == type && (!id || id == (*iter)->id)) {
    return iter->get();
  }
  return nullptr;
}

ResourceTableType* ResourceTablePackage::FindOrCreateType(ResourceType type,
                                                          const Maybe<uint8_t> id) {
  const auto last = types.end();
  auto iter = std::lower_bound(types.begin(), last, std::make_pair(type, id),
                               less_than_type_and_id);
  if (iter != last && (*iter)->type == type && (!id || id == (*iter)->id)) {
    return iter->get();
  }

  auto new_type = new ResourceTableType(type);
  new_type->id = id;
  return types.emplace(iter, std::move(new_type))->get();
}

ResourceEntry* ResourceTableType::FindEntry(const StringPiece& name, const Maybe<uint16_t> id) {
  const auto last = entries.end();
  auto iter = std::lower_bound(entries.begin(), last, std::make_pair(name, id),
      less_than_struct_with_name_and_id<ResourceEntry>);
  if (iter != last && name == (*iter)->name && (!id || id == (*iter)->id)) {
    return iter->get();
  }
  return nullptr;
}

ResourceEntry* ResourceTableType::FindOrCreateEntry(const StringPiece& name,
                                                    const Maybe<uint16_t > id) {
  auto last = entries.end();
  auto iter = std::lower_bound(entries.begin(), last, std::make_pair(name, id),
                               less_than_struct_with_name_and_id<ResourceEntry>);
  if (iter != last && name == (*iter)->name && (!id || id == (*iter)->id)) {
    return iter->get();
  }

  auto new_entry = new ResourceEntry(name);
  new_entry->id = id;
  return entries.emplace(iter, std::move(new_entry))->get();
}

ResourceConfigValue* ResourceEntry::FindValue(const ConfigDescription& config) {
  return FindValue(config, StringPiece());
}

struct ConfigKey {
  const ConfigDescription* config;
  const StringPiece& product;
};

bool lt_config_key_ref(const std::unique_ptr<ResourceConfigValue>& lhs, const ConfigKey& rhs) {
  int cmp = lhs->config.compare(*rhs.config);
  if (cmp == 0) {
    cmp = StringPiece(lhs->product).compare(rhs.product);
  }
  return cmp < 0;
}

ResourceConfigValue* ResourceEntry::FindValue(const ConfigDescription& config,
                                              const StringPiece& product) {
  auto iter = std::lower_bound(values.begin(), values.end(), ConfigKey{&config, product},
                               lt_config_key_ref);
  if (iter != values.end()) {
    ResourceConfigValue* value = iter->get();
    if (value->config == config && StringPiece(value->product) == product) {
      return value;
    }
  }
  return nullptr;
}

ResourceConfigValue* ResourceEntry::FindOrCreateValue(const ConfigDescription& config,
                                                      const StringPiece& product) {
  auto iter = std::lower_bound(values.begin(), values.end(), ConfigKey{&config, product},
                               lt_config_key_ref);
  if (iter != values.end()) {
    ResourceConfigValue* value = iter->get();
    if (value->config == config && StringPiece(value->product) == product) {
      return value;
    }
  }
  ResourceConfigValue* newValue =
      values.insert(iter, util::make_unique<ResourceConfigValue>(config, product))->get();
  return newValue;
}

std::vector<ResourceConfigValue*> ResourceEntry::FindAllValues(const ConfigDescription& config) {
  std::vector<ResourceConfigValue*> results;

  auto iter = values.begin();
  for (; iter != values.end(); ++iter) {
    ResourceConfigValue* value = iter->get();
    if (value->config == config) {
      results.push_back(value);
      ++iter;
      break;
    }
  }

  for (; iter != values.end(); ++iter) {
    ResourceConfigValue* value = iter->get();
    if (value->config == config) {
      results.push_back(value);
    }
  }
  return results;
}

bool ResourceEntry::HasDefaultValue() const {
  const ConfigDescription& default_config = ConfigDescription::DefaultConfig();

  // The default config should be at the top of the list, since the list is sorted.
  for (auto& config_value : values) {
    if (config_value->config == default_config) {
      return true;
    }
  }
  return false;
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
                                                                    Value* incoming,
                                                                    bool overlay) {
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
    return overlay ? CollisionResult::kTakeNew : CollisionResult::kConflict;
  }

  if (!existing_attr) {
    if (existing->IsWeak()) {
      // The existing value is not an attribute and it is weak,
      // so take the incoming attribute value.
      return CollisionResult::kTakeNew;
    }
    // The existing value is not an attribute and it is strong,
    // so the incoming attribute value is an error.
    return overlay ? CollisionResult::kTakeNew : CollisionResult::kConflict;
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

ResourceTable::CollisionResult ResourceTable::IgnoreCollision(Value* /* existing */,
                                                              Value* /* incoming */,
                                                              bool /* overlay */) {
  return CollisionResult::kKeepBoth;
}

static StringPiece ResourceNameValidator(const StringPiece& name) {
  if (!IsValidResourceEntryName(name)) {
    return name;
  }
  return {};
}

static StringPiece SkipNameValidator(const StringPiece& /*name*/) {
  return {};
}

bool ResourceTable::AddResource(const ResourceNameRef& name,
                                const ConfigDescription& config,
                                const StringPiece& product,
                                std::unique_ptr<Value> value,
                                IDiagnostics* diag) {
  return AddResourceImpl(name, ResourceId{}, config, product, std::move(value),
                         (validate_resources_ ? ResourceNameValidator : SkipNameValidator),
                         (validate_resources_ ? ResolveValueCollision : IgnoreCollision), diag);
}

bool ResourceTable::AddResourceWithId(const ResourceNameRef& name, const ResourceId& res_id,
                                      const ConfigDescription& config, const StringPiece& product,
                                      std::unique_ptr<Value> value, IDiagnostics* diag) {
  return AddResourceImpl(name, res_id, config, product, std::move(value),
                         (validate_resources_ ? ResourceNameValidator : SkipNameValidator),
                         (validate_resources_ ? ResolveValueCollision : IgnoreCollision), diag);
}

bool ResourceTable::AddResourceMangled(const ResourceNameRef& name, const ConfigDescription& config,
                                       const StringPiece& product, std::unique_ptr<Value> value,
                                       IDiagnostics* diag) {
  return AddResourceImpl(name, ResourceId{}, config, product, std::move(value), SkipNameValidator,
                         (validate_resources_ ? ResolveValueCollision : IgnoreCollision), diag);
}

bool ResourceTable::AddResourceWithIdMangled(const ResourceNameRef& name, const ResourceId& id,
                                             const ConfigDescription& config,
                                             const StringPiece& product,
                                             std::unique_ptr<Value> value, IDiagnostics* diag) {
  return AddResourceImpl(name, id, config, product, std::move(value), SkipNameValidator,
                         (validate_resources_ ? ResolveValueCollision : IgnoreCollision), diag);
}

bool ResourceTable::ValidateName(NameValidator name_validator, const ResourceNameRef& name,
                                 const Source& source, IDiagnostics* diag) {
  const StringPiece bad_char = name_validator(name.entry);
  if (!bad_char.empty()) {
    diag->Error(DiagMessage(source) << "resource '" << name << "' has invalid entry name '"
                                    << name.entry << "'. Invalid character '" << bad_char << "'");
    return false;
  }
  return true;
}

bool ResourceTable::AddResourceImpl(const ResourceNameRef& name, const ResourceId& res_id,
                                    const ConfigDescription& config, const StringPiece& product,
                                    std::unique_ptr<Value> value, NameValidator name_validator,
                                    const CollisionResolverFunc& conflict_resolver,
                                    IDiagnostics* diag) {
  CHECK(value != nullptr);
  CHECK(diag != nullptr);

  const Source& source = value->GetSource();
  if (!ValidateName(name_validator, name, source, diag)) {
    return false;
  }

  // Check for package names appearing twice with two different package ids
  ResourceTablePackage* package = FindOrCreatePackage(name.package);
  if (res_id.is_valid_dynamic() && package->id && package->id.value() != res_id.package_id()) {
    diag->Error(DiagMessage(source)
                    << "trying to add resource '" << name << "' with ID " << res_id
                    << " but package '" << package->name << "' already has ID "
                    << StringPrintf("%02x", package->id.value()));
    return false;
  }

  // Whether or not to error on duplicate resources
  bool check_id = validate_resources_ && res_id.is_valid_dynamic();
  // Whether or not to create a duplicate resource if the id does not match
  bool use_id = !validate_resources_ && res_id.is_valid_dynamic();

  ResourceTableType* type = package->FindOrCreateType(name.type, use_id ? res_id.type_id()
                                                                        : Maybe<uint8_t>());

  // Check for types appearing twice with two different type ids
  if (check_id && type->id && type->id.value() != res_id.type_id()) {
    diag->Error(DiagMessage(source)
                    << "trying to add resource '" << name << "' with ID " << res_id
                    << " but type '" << type->type << "' already has ID "
                    << StringPrintf("%02x", type->id.value()));
    return false;
  }

  ResourceEntry* entry = type->FindOrCreateEntry(name.entry, use_id ? res_id.entry_id()
                                                                    : Maybe<uint16_t>());

  // Check for entries appearing twice with two different entry ids
  if (check_id && entry->id && entry->id.value() != res_id.entry_id()) {
    diag->Error(DiagMessage(source)
                    << "trying to add resource '" << name << "' with ID " << res_id
                    << " but resource already has ID "
                    << ResourceId(package->id.value(), type->id.value(), entry->id.value()));
    return false;
  }

  ResourceConfigValue* config_value = entry->FindOrCreateValue(config, product);
  if (!config_value->value) {
    // Resource does not exist, add it now.
    config_value->value = std::move(value);
  } else {
    switch (conflict_resolver(config_value->value.get(), value.get(), false /* overlay */)) {
      case CollisionResult::kKeepBoth:
        // Insert the value ignoring for duplicate configurations
        entry->values.push_back(util::make_unique<ResourceConfigValue>(config, product));
        entry->values.back()->value = std::move(value);
        break;

      case CollisionResult::kTakeNew:
        // Take the incoming value.
        config_value->value = std::move(value);
        break;

      case CollisionResult::kConflict:
        diag->Error(DiagMessage(source) << "duplicate value for resource '" << name << "' "
                                        << "with config '" << config << "'");
        diag->Error(DiagMessage(source) << "resource previously defined here");
        return false;

      case CollisionResult::kKeepOriginal:
        break;
    }
  }

  if (res_id.is_valid_dynamic()) {
    package->id = res_id.package_id();
    type->id = res_id.type_id();
    entry->id = res_id.entry_id();
  }

  return true;
}

bool ResourceTable::GetValidateResources() {
  return validate_resources_;
}

bool ResourceTable::SetVisibility(const ResourceNameRef& name, const Visibility& visibility,
                                  IDiagnostics* diag) {
  return SetVisibilityImpl(name, visibility, {}, ResourceNameValidator, diag);
}

bool ResourceTable::SetVisibilityWithId(const ResourceNameRef& name, const Visibility& visibility,
                                        const ResourceId& res_id, IDiagnostics* diag) {
  return SetVisibilityImpl(name, visibility, res_id, ResourceNameValidator, diag);
}

bool ResourceTable::SetVisibilityWithIdMangled(const ResourceNameRef& name,
                                               const Visibility& visibility,
                                               const ResourceId& res_id, IDiagnostics* diag) {
  return SetVisibilityImpl(name, visibility, res_id, SkipNameValidator, diag);
}

bool ResourceTable::SetVisibilityImpl(const ResourceNameRef& name, const Visibility& visibility,
                                      const ResourceId& res_id, NameValidator name_validator,
                                      IDiagnostics* diag) {
  CHECK(diag != nullptr);

  const Source& source = visibility.source;
  if (!ValidateName(name_validator, name, source, diag)) {
    return false;
  }

  // Check for package names appearing twice with two different package ids
  ResourceTablePackage* package = FindOrCreatePackage(name.package);
  if (res_id.is_valid_dynamic() && package->id && package->id.value() != res_id.package_id()) {
    diag->Error(DiagMessage(source)
                    << "trying to add resource '" << name << "' with ID " << res_id
                    << " but package '" << package->name << "' already has ID "
                    << StringPrintf("%02x", package->id.value()));
    return false;
  }

  // Whether or not to error on duplicate resources
  bool check_id = validate_resources_ && res_id.is_valid_dynamic();
  // Whether or not to create a duplicate resource if the id does not match
  bool use_id = !validate_resources_ && res_id.is_valid_dynamic();

  ResourceTableType* type = package->FindOrCreateType(name.type, use_id ? res_id.type_id()
                                                                        : Maybe<uint8_t>());

  // Check for types appearing twice with two different type ids
  if (check_id && type->id && type->id.value() != res_id.type_id()) {
    diag->Error(DiagMessage(source)
                    << "trying to add resource '" << name << "' with ID " << res_id
                    << " but type '" << type->type << "' already has ID "
                    << StringPrintf("%02x", type->id.value()));
    return false;
  }

  ResourceEntry* entry = type->FindOrCreateEntry(name.entry, use_id ? res_id.entry_id()
                                                                    : Maybe<uint16_t>());

  // Check for entries appearing twice with two different entry ids
  if (check_id && entry->id && entry->id.value() != res_id.entry_id()) {
    diag->Error(DiagMessage(source)
                    << "trying to add resource '" << name << "' with ID " << res_id
                    << " but resource already has ID "
                    << ResourceId(package->id.value(), type->id.value(), entry->id.value()));
    return false;
  }

  if (res_id.is_valid_dynamic()) {
    package->id = res_id.package_id();
    type->id = res_id.type_id();
    entry->id = res_id.entry_id();
  }

  // Only mark the type visibility level as public, it doesn't care about being private.
  if (visibility.level == Visibility::Level::kPublic) {
    type->visibility_level = Visibility::Level::kPublic;
  }

  if (visibility.level == Visibility::Level::kUndefined &&
      entry->visibility.level != Visibility::Level::kUndefined) {
    // We can't undefine a symbol (remove its visibility). Ignore.
    return true;
  }

  if (visibility.level < entry->visibility.level) {
    // We can't downgrade public to private. Ignore.
    return true;
  }

  // This symbol definition takes precedence, replace.
  entry->visibility = visibility;
  return true;
}

bool ResourceTable::SetAllowNew(const ResourceNameRef& name, const AllowNew& allow_new,
                                IDiagnostics* diag) {
  return SetAllowNewImpl(name, allow_new, ResourceNameValidator, diag);
}

bool ResourceTable::SetAllowNewMangled(const ResourceNameRef& name, const AllowNew& allow_new,
                                       IDiagnostics* diag) {
  return SetAllowNewImpl(name, allow_new, SkipNameValidator, diag);
}

bool ResourceTable::SetAllowNewImpl(const ResourceNameRef& name, const AllowNew& allow_new,
                                    NameValidator name_validator, IDiagnostics* diag) {
  CHECK(diag != nullptr);

  if (!ValidateName(name_validator, name, allow_new.source, diag)) {
    return false;
  }

  ResourceTablePackage* package = FindOrCreatePackage(name.package);
  ResourceTableType* type = package->FindOrCreateType(name.type);
  ResourceEntry* entry = type->FindOrCreateEntry(name.entry);
  entry->allow_new = allow_new;
  return true;
}

bool ResourceTable::SetOverlayable(const ResourceNameRef& name, const OverlayableItem& overlayable,
                                   IDiagnostics* diag) {
  return SetOverlayableImpl(name, overlayable, ResourceNameValidator, diag);
}

bool ResourceTable::SetOverlayableImpl(const ResourceNameRef& name,
                                       const OverlayableItem& overlayable,
                                       NameValidator name_validator, IDiagnostics *diag) {
  CHECK(diag != nullptr);

  if (!ValidateName(name_validator, name, overlayable.source, diag)) {
    return false;
  }

  ResourceTablePackage* package = FindOrCreatePackage(name.package);
  ResourceTableType* type = package->FindOrCreateType(name.type);
  ResourceEntry* entry = type->FindOrCreateEntry(name.entry);

  if (entry->overlayable_item) {
    diag->Error(DiagMessage(overlayable.source)
                << "duplicate overlayable declaration for resource '" << name << "'");
    diag->Error(DiagMessage(entry->overlayable_item.value().source)
                << "previous declaration here");
    return false;
  }

  entry->overlayable_item = overlayable;
  return true;
}

Maybe<ResourceTable::SearchResult> ResourceTable::FindResource(const ResourceNameRef& name) const {
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

std::unique_ptr<ResourceTable> ResourceTable::Clone() const {
  std::unique_ptr<ResourceTable> new_table = util::make_unique<ResourceTable>();
  for (const auto& pkg : packages) {
    ResourceTablePackage* new_pkg = new_table->CreatePackage(pkg->name, pkg->id);
    for (const auto& type : pkg->types) {
      ResourceTableType* new_type = new_pkg->FindOrCreateType(type->type);
      new_type->id = type->id;
      new_type->visibility_level = type->visibility_level;

      for (const auto& entry : type->entries) {
        ResourceEntry* new_entry = new_type->FindOrCreateEntry(entry->name);
        new_entry->id = entry->id;
        new_entry->visibility = entry->visibility;
        new_entry->allow_new = entry->allow_new;
        new_entry->overlayable_item = entry->overlayable_item;

        for (const auto& config_value : entry->values) {
          ResourceConfigValue* new_value =
              new_entry->FindOrCreateValue(config_value->config, config_value->product);
          new_value->value.reset(config_value->value->Clone(&new_table->string_pool));
        }
      }
    }
  }
  return new_table;
}

}  // namespace aapt
