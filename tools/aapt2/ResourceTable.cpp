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
#include "ConfigDescription.h"
#include "NameMangler.h"
#include "ResourceValues.h"
#include "ValueVisitor.h"
#include "util/Util.h"

#include <android-base/logging.h>
#include <androidfw/ResourceTypes.h>
#include <algorithm>
#include <memory>
#include <string>
#include <tuple>

using android::StringPiece;

namespace aapt {

static bool less_than_type(const std::unique_ptr<ResourceTableType>& lhs, ResourceType rhs) {
  return lhs->type < rhs;
}

template <typename T>
static bool less_than_struct_with_name(const std::unique_ptr<T>& lhs, const StringPiece& rhs) {
  return lhs->name.compare(0, lhs->name.size(), rhs.data(), rhs.size()) < 0;
}

ResourceTablePackage* ResourceTable::FindPackage(const StringPiece& name) {
  const auto last = packages.end();
  auto iter = std::lower_bound(packages.begin(), last, name,
                               less_than_struct_with_name<ResourceTablePackage>);
  if (iter != last && name == (*iter)->name) {
    return iter->get();
  }
  return nullptr;
}

ResourceTablePackage* ResourceTable::FindPackageById(uint8_t id) {
  for (auto& package : packages) {
    if (package->id && package->id.value() == id) {
      return package.get();
    }
  }
  return nullptr;
}

ResourceTablePackage* ResourceTable::CreatePackage(const StringPiece& name, Maybe<uint8_t> id) {
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

ResourceTableType* ResourceTablePackage::FindType(ResourceType type) {
  const auto last = types.end();
  auto iter = std::lower_bound(types.begin(), last, type, less_than_type);
  if (iter != last && (*iter)->type == type) {
    return iter->get();
  }
  return nullptr;
}

ResourceTableType* ResourceTablePackage::FindOrCreateType(ResourceType type) {
  const auto last = types.end();
  auto iter = std::lower_bound(types.begin(), last, type, less_than_type);
  if (iter != last && (*iter)->type == type) {
    return iter->get();
  }
  return types.emplace(iter, new ResourceTableType(type))->get();
}

ResourceEntry* ResourceTableType::FindEntry(const StringPiece& name) {
  const auto last = entries.end();
  auto iter =
      std::lower_bound(entries.begin(), last, name, less_than_struct_with_name<ResourceEntry>);
  if (iter != last && name == (*iter)->name) {
    return iter->get();
  }
  return nullptr;
}

ResourceEntry* ResourceTableType::FindOrCreateEntry(const StringPiece& name) {
  auto last = entries.end();
  auto iter =
      std::lower_bound(entries.begin(), last, name, less_than_struct_with_name<ResourceEntry>);
  if (iter != last && name == (*iter)->name) {
    return iter->get();
  }
  return entries.emplace(iter, new ResourceEntry(name))->get();
}

ResourceConfigValue* ResourceEntry::FindValue(const ConfigDescription& config) {
  return FindValue(config, StringPiece());
}

struct ConfigKey {
  const ConfigDescription* config;
  const StringPiece& product;
};

bool ltConfigKeyRef(const std::unique_ptr<ResourceConfigValue>& lhs, const ConfigKey& rhs) {
  int cmp = lhs->config.compare(*rhs.config);
  if (cmp == 0) {
    cmp = StringPiece(lhs->product).compare(rhs.product);
  }
  return cmp < 0;
}

ResourceConfigValue* ResourceEntry::FindValue(const ConfigDescription& config,
                                              const StringPiece& product) {
  auto iter =
      std::lower_bound(values.begin(), values.end(), ConfigKey{&config, product}, ltConfigKeyRef);
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
  auto iter =
      std::lower_bound(values.begin(), values.end(), ConfigKey{&config, product}, ltConfigKeyRef);
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

std::vector<ResourceConfigValue*> ResourceEntry::FindValuesIf(
    const std::function<bool(ResourceConfigValue*)>& f) {
  std::vector<ResourceConfigValue*> results;
  for (auto& configValue : values) {
    if (f(configValue.get())) {
      results.push_back(configValue.get());
    }
  }
  return results;
}

/**
 * The default handler for collisions.
 *
 * Typically, a weak value will be overridden by a strong value. An existing
 * weak
 * value will not be overridden by an incoming weak value.
 *
 * There are some exceptions:
 *
 * Attributes: There are two types of Attribute values: USE and DECL.
 *
 * USE is anywhere an Attribute is declared without a format, and in a place
 * that would
 * be legal to declare if the Attribute already existed. This is typically in a
 * <declare-styleable> tag. Attributes defined in a <declare-styleable> are also
 * weak.
 *
 * DECL is an absolute declaration of an Attribute and specifies an explicit
 * format.
 *
 * A DECL will override a USE without error. Two DECLs must match in their
 * format for there to be
 * no error.
 */
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
  if (existing_attr->type_mask == incoming_attr->type_mask) {
    // The two attributes are both DECLs, but they are plain attributes
    // with the same formats.
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

static constexpr const char* kValidNameChars = "._-";

static StringPiece ValidateName(const StringPiece& name) {
  auto iter = util::FindNonAlphaNumericAndNotInSet(name, kValidNameChars);
  if (iter != name.end()) {
    return StringPiece(iter, 1);
  }
  return {};
}

static StringPiece SkipValidateName(const StringPiece& /*name*/) {
  return {};
}

bool ResourceTable::AddResource(const ResourceNameRef& name,
                                const ConfigDescription& config,
                                const StringPiece& product,
                                std::unique_ptr<Value> value,
                                IDiagnostics* diag) {
  return AddResourceImpl(name, {}, config, product, std::move(value), ValidateName,
                         ResolveValueCollision, diag);
}

bool ResourceTable::AddResource(const ResourceNameRef& name,
                                const ResourceId& res_id,
                                const ConfigDescription& config,
                                const StringPiece& product,
                                std::unique_ptr<Value> value,
                                IDiagnostics* diag) {
  return AddResourceImpl(name, res_id, config, product, std::move(value), ValidateName,
                         ResolveValueCollision, diag);
}

bool ResourceTable::AddFileReference(const ResourceNameRef& name,
                                     const ConfigDescription& config,
                                     const Source& source,
                                     const StringPiece& path,
                                     IDiagnostics* diag) {
  return AddFileReferenceImpl(name, config, source, path, nullptr, ValidateName, diag);
}

bool ResourceTable::AddFileReferenceAllowMangled(
    const ResourceNameRef& name, const ConfigDescription& config,
    const Source& source, const StringPiece& path, io::IFile* file,
    IDiagnostics* diag) {
  return AddFileReferenceImpl(name, config, source, path, file, SkipValidateName, diag);
}

bool ResourceTable::AddFileReferenceImpl(const ResourceNameRef& name,
                                         const ConfigDescription& config, const Source& source,
                                         const StringPiece& path, io::IFile* file,
                                         NameValidator name_validator, IDiagnostics* diag) {
  std::unique_ptr<FileReference> fileRef =
      util::make_unique<FileReference>(string_pool.MakeRef(path));
  fileRef->SetSource(source);
  fileRef->file = file;
  return AddResourceImpl(name, ResourceId{}, config, StringPiece{}, std::move(fileRef),
                         name_validator, ResolveValueCollision, diag);
}

bool ResourceTable::AddResourceAllowMangled(const ResourceNameRef& name,
                                            const ConfigDescription& config,
                                            const StringPiece& product,
                                            std::unique_ptr<Value> value,
                                            IDiagnostics* diag) {
  return AddResourceImpl(name, ResourceId{}, config, product, std::move(value), SkipValidateName,
                         ResolveValueCollision, diag);
}

bool ResourceTable::AddResourceAllowMangled(const ResourceNameRef& name,
                                            const ResourceId& id,
                                            const ConfigDescription& config,
                                            const StringPiece& product,
                                            std::unique_ptr<Value> value,
                                            IDiagnostics* diag) {
  return AddResourceImpl(name, id, config, product, std::move(value), SkipValidateName,
                         ResolveValueCollision, diag);
}

bool ResourceTable::AddResourceImpl(const ResourceNameRef& name, const ResourceId& res_id,
                                    const ConfigDescription& config, const StringPiece& product,
                                    std::unique_ptr<Value> value, NameValidator name_validator,
                                    const CollisionResolverFunc& conflictResolver,
                                    IDiagnostics* diag) {
  CHECK(value != nullptr);
  CHECK(diag != nullptr);

  const StringPiece bad_char = name_validator(name.entry);
  if (!bad_char.empty()) {
    diag->Error(DiagMessage(value->GetSource()) << "resource '" << name
                                                << "' has invalid entry name '" << name.entry
                                                << "'. Invalid character '" << bad_char << "'");

    return false;
  }

  ResourceTablePackage* package = FindOrCreatePackage(name.package);
  if (res_id.is_valid_dynamic() && package->id && package->id.value() != res_id.package_id()) {
    diag->Error(DiagMessage(value->GetSource())
                << "trying to add resource '" << name << "' with ID " << res_id
                << " but package '" << package->name << "' already has ID "
                << std::hex << (int)package->id.value() << std::dec);
    return false;
  }

  ResourceTableType* type = package->FindOrCreateType(name.type);
  if (res_id.is_valid_dynamic() && type->id && type->id.value() != res_id.type_id()) {
    diag->Error(DiagMessage(value->GetSource())
                << "trying to add resource '" << name << "' with ID " << res_id
                << " but type '" << type->type << "' already has ID "
                << std::hex << (int)type->id.value() << std::dec);
    return false;
  }

  ResourceEntry* entry = type->FindOrCreateEntry(name.entry);
  if (res_id.is_valid_dynamic() && entry->id && entry->id.value() != res_id.entry_id()) {
    diag->Error(DiagMessage(value->GetSource())
                << "trying to add resource '" << name << "' with ID " << res_id
                << " but resource already has ID "
                << ResourceId(package->id.value(), type->id.value(),
                              entry->id.value()));
    return false;
  }

  ResourceConfigValue* config_value = entry->FindOrCreateValue(config, product);
  if (!config_value->value) {
    // Resource does not exist, add it now.
    config_value->value = std::move(value);

  } else {
    switch (conflictResolver(config_value->value.get(), value.get())) {
      case CollisionResult::kTakeNew:
        // Take the incoming value.
        config_value->value = std::move(value);
        break;

      case CollisionResult::kConflict:
        diag->Error(DiagMessage(value->GetSource())
                    << "duplicate value for resource '" << name << "' "
                    << "with config '" << config << "'");
        diag->Error(DiagMessage(config_value->value->GetSource())
                    << "resource previously defined here");
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

bool ResourceTable::SetSymbolState(const ResourceNameRef& name, const ResourceId& res_id,
                                   const Symbol& symbol, IDiagnostics* diag) {
  return SetSymbolStateImpl(name, res_id, symbol, ValidateName, diag);
}

bool ResourceTable::SetSymbolStateAllowMangled(const ResourceNameRef& name,
                                               const ResourceId& res_id,
                                               const Symbol& symbol,
                                               IDiagnostics* diag) {
  return SetSymbolStateImpl(name, res_id, symbol, SkipValidateName, diag);
}

bool ResourceTable::SetSymbolStateImpl(const ResourceNameRef& name, const ResourceId& res_id,
                                       const Symbol& symbol, NameValidator name_validator,
                                       IDiagnostics* diag) {
  CHECK(diag != nullptr);

  const StringPiece bad_char = name_validator(name.entry);
  if (!bad_char.empty()) {
    diag->Error(DiagMessage(symbol.source) << "resource '" << name << "' has invalid entry name '"
                                           << name.entry << "'. Invalid character '" << bad_char
                                           << "'");
    return false;
  }

  ResourceTablePackage* package = FindOrCreatePackage(name.package);
  if (res_id.is_valid_dynamic() && package->id && package->id.value() != res_id.package_id()) {
    diag->Error(DiagMessage(symbol.source)
                << "trying to add resource '" << name << "' with ID " << res_id
                << " but package '" << package->name << "' already has ID "
                << std::hex << (int)package->id.value() << std::dec);
    return false;
  }

  ResourceTableType* type = package->FindOrCreateType(name.type);
  if (res_id.is_valid_dynamic() && type->id && type->id.value() != res_id.type_id()) {
    diag->Error(DiagMessage(symbol.source)
                << "trying to add resource '" << name << "' with ID " << res_id
                << " but type '" << type->type << "' already has ID "
                << std::hex << (int)type->id.value() << std::dec);
    return false;
  }

  ResourceEntry* entry = type->FindOrCreateEntry(name.entry);
  if (res_id.is_valid_dynamic() && entry->id && entry->id.value() != res_id.entry_id()) {
    diag->Error(DiagMessage(symbol.source)
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

  // Only mark the type state as public, it doesn't care about being private.
  if (symbol.state == SymbolState::kPublic) {
    type->symbol_status.state = SymbolState::kPublic;
  }

  if (symbol.allow_new) {
    // This symbol can be added as a new resource when merging (if it belongs to an overlay).
    entry->symbol_status.allow_new = true;
  }

  if (symbol.state == SymbolState::kUndefined &&
      entry->symbol_status.state != SymbolState::kUndefined) {
    // We can't undefine a symbol (remove its visibility). Ignore.
    return true;
  }

  if (symbol.state == SymbolState::kPrivate &&
      entry->symbol_status.state == SymbolState::kPublic) {
    // We can't downgrade public to private. Ignore.
    return true;
  }

  // This symbol definition takes precedence, replace.
  entry->symbol_status.state = symbol.state;
  entry->symbol_status.source = symbol.source;
  entry->symbol_status.comment = symbol.comment;
  return true;
}

Maybe<ResourceTable::SearchResult> ResourceTable::FindResource(const ResourceNameRef& name) {
  ResourceTablePackage* package = FindPackage(name.package);
  if (!package) {
    return {};
  }

  ResourceTableType* type = package->FindType(name.type);
  if (!type) {
    return {};
  }

  ResourceEntry* entry = type->FindEntry(name.entry);
  if (!entry) {
    return {};
  }
  return SearchResult{package, type, entry};
}

}  // namespace aapt
