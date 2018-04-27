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

#include "process/SymbolTable.h"

#include <iostream>

#include "android-base/logging.h"
#include "android-base/stringprintf.h"
#include "androidfw/AssetManager.h"
#include "androidfw/ResourceTypes.h"

#include "ConfigDescription.h"
#include "NameMangler.h"
#include "Resource.h"
#include "ResourceUtils.h"
#include "ValueVisitor.h"
#include "util/Util.h"

using ::android::StringPiece;
using ::android::StringPiece16;

namespace aapt {

SymbolTable::SymbolTable(NameMangler* mangler)
    : mangler_(mangler),
      delegate_(util::make_unique<DefaultSymbolTableDelegate>()),
      cache_(200),
      id_cache_(200) {
}

void SymbolTable::SetDelegate(std::unique_ptr<ISymbolTableDelegate> delegate) {
  CHECK(delegate != nullptr) << "can't set a nullptr delegate";
  delegate_ = std::move(delegate);

  // Clear the cache in case this delegate changes the order of lookup.
  cache_.clear();
}

void SymbolTable::AppendSource(std::unique_ptr<ISymbolSource> source) {
  sources_.push_back(std::move(source));

  // We do not clear the cache, because sources earlier in the list take
  // precedent.
}

void SymbolTable::PrependSource(std::unique_ptr<ISymbolSource> source) {
  sources_.insert(sources_.begin(), std::move(source));

  // We must clear the cache in case we did a lookup before adding this
  // resource.
  cache_.clear();
}

const SymbolTable::Symbol* SymbolTable::FindByName(const ResourceName& name) {
  const ResourceName* name_with_package = &name;

  // Fill in the package name if necessary.
  // If there is no package in `name`, we will need to copy the ResourceName
  // and store it somewhere; we use the Maybe<> class to reserve storage.
  Maybe<ResourceName> name_with_package_impl;
  if (name.package.empty()) {
    name_with_package_impl = ResourceName(mangler_->GetTargetPackageName(), name.type, name.entry);
    name_with_package = &name_with_package_impl.value();
  }

  // We store the name unmangled in the cache, so look it up as-is.
  if (const std::shared_ptr<Symbol>& s = cache_.get(*name_with_package)) {
    return s.get();
  }

  // The name was not found in the cache. Mangle it (if necessary) and find it in our sources.
  // Again, here we use a Maybe<> object to reserve storage if we need to mangle.
  const ResourceName* mangled_name = name_with_package;
  Maybe<ResourceName> mangled_name_impl;
  if (mangler_->ShouldMangle(name_with_package->package)) {
    mangled_name_impl = mangler_->MangleName(*name_with_package);
    mangled_name = &mangled_name_impl.value();
  }

  std::unique_ptr<Symbol> symbol = delegate_->FindByName(*mangled_name, sources_);
  if (symbol == nullptr) {
    return nullptr;
  }

  // Take ownership of the symbol into a shared_ptr. We do this because
  // LruCache doesn't support unique_ptr.
  std::shared_ptr<Symbol> shared_symbol(std::move(symbol));

  // Since we look in the cache with the unmangled, but package prefixed
  // name, we must put the same name into the cache.
  cache_.put(*name_with_package, shared_symbol);

  if (shared_symbol->id) {
    // The symbol has an ID, so we can also cache this!
    id_cache_.put(shared_symbol->id.value(), shared_symbol);
  }

  // Returns the raw pointer. Callers are not expected to hold on to this
  // between calls to Find*.
  return shared_symbol.get();
}

const SymbolTable::Symbol* SymbolTable::FindByNameInAnyPackage(const ResourceName& name) {
  for (auto& source : sources_) {
    std::string package = source->GetPackageForSymbol(name);
    if (!package.empty()) {
      return FindByName(ResourceName(package, name.type, name.entry));
    }
  }
  return {};
}

const SymbolTable::Symbol* SymbolTable::FindById(const ResourceId& id) {
  if (const std::shared_ptr<Symbol>& s = id_cache_.get(id)) {
    return s.get();
  }

  // We did not find it in the cache, so look through the sources.
  std::unique_ptr<Symbol> symbol = delegate_->FindById(id, sources_);
  if (symbol == nullptr) {
    return nullptr;
  }

  // Take ownership of the symbol into a shared_ptr. We do this because LruCache
  // doesn't support unique_ptr.
  std::shared_ptr<Symbol> shared_symbol(std::move(symbol));
  id_cache_.put(id, shared_symbol);

  // Returns the raw pointer. Callers are not expected to hold on to this
  // between calls to Find*.
  return shared_symbol.get();
}

const SymbolTable::Symbol* SymbolTable::FindByReference(const Reference& ref) {
  // First try the ID. This is because when we lookup by ID, we only fill in the ID cache.
  // Looking up by name fills in the name and ID cache. So a cache miss will cause a failed
  // ID lookup, then a successful name lookup. Subsequent look ups will hit immediately
  // because the ID is cached too.
  //
  // If we looked up by name first, a cache miss would mean we failed to lookup by name, then
  // succeeded to lookup by ID. Subsequent lookups will miss then hit.
  const SymbolTable::Symbol* symbol = nullptr;
  if (ref.id) {
    symbol = FindById(ref.id.value());
  }

  if (ref.name && !symbol) {
    symbol = FindByName(ref.name.value());
  }
  return symbol;
}

std::unique_ptr<SymbolTable::Symbol> DefaultSymbolTableDelegate::FindByName(
    const ResourceName& name, const std::vector<std::unique_ptr<ISymbolSource>>& sources) {
  for (auto& source : sources) {
    std::unique_ptr<SymbolTable::Symbol> symbol = source->FindByName(name);
    if (symbol) {
      return symbol;
    }
  }
  return {};
}

std::unique_ptr<SymbolTable::Symbol> DefaultSymbolTableDelegate::FindById(
    ResourceId id, const std::vector<std::unique_ptr<ISymbolSource>>& sources) {
  for (auto& source : sources) {
    std::unique_ptr<SymbolTable::Symbol> symbol = source->FindById(id);
    if (symbol) {
      return symbol;
    }
  }
  return {};
}

std::unique_ptr<SymbolTable::Symbol> ResourceTableSymbolSource::FindByName(
    const ResourceName& name) {
  Maybe<ResourceTable::SearchResult> result = table_->FindResource(name);
  if (!result) {
    if (name.type == ResourceType::kAttr) {
      // Recurse and try looking up a private attribute.
      return FindByName(ResourceName(name.package, ResourceType::kAttrPrivate, name.entry));
    }
    return {};
  }

  ResourceTable::SearchResult sr = result.value();

  std::unique_ptr<SymbolTable::Symbol> symbol = util::make_unique<SymbolTable::Symbol>();
  symbol->is_public = (sr.entry->visibility.level == Visibility::Level::kPublic);

  if (sr.package->id && sr.type->id && sr.entry->id) {
    symbol->id = ResourceId(sr.package->id.value(), sr.type->id.value(), sr.entry->id.value());
  }

  if (name.type == ResourceType::kAttr || name.type == ResourceType::kAttrPrivate) {
    const ConfigDescription kDefaultConfig;
    ResourceConfigValue* config_value = sr.entry->FindValue(kDefaultConfig);
    if (config_value) {
      // This resource has an Attribute.
      if (Attribute* attr = ValueCast<Attribute>(config_value->value.get())) {
        symbol->attribute = std::make_shared<Attribute>(*attr);
      } else {
        return {};
      }
    }
  }
  return symbol;
}

std::string ResourceTableSymbolSource::GetPackageForSymbol(const ResourceName& name) {
  for (auto& package : table_->packages) {
    ResourceTableType* type = package->FindType(name.type);
    if (type == nullptr) {
      continue;
    }
    ResourceEntry* entry = type->FindEntry(name.entry);
    if (entry == nullptr) {
      continue;
    }
    return package->name;
  }
  if (name.type == ResourceType::kAttr) {
    // Recurse and try looking up a private attribute.
    return GetPackageForSymbol(ResourceName(name.package, ResourceType::kAttrPrivate, name.entry));
  }
  return {};
}

bool AssetManagerSymbolSource::AddAssetPath(const StringPiece& path) {
  int32_t cookie = 0;
  return assets_.addAssetPath(android::String8(path.data(), path.size()), &cookie);
}

std::map<size_t, std::string> AssetManagerSymbolSource::GetAssignedPackageIds() const {
  std::map<size_t, std::string> package_map;
  const android::ResTable& table = assets_.getResources(false);
  const size_t package_count = table.getBasePackageCount();
  for (size_t i = 0; i < package_count; i++) {
    package_map[table.getBasePackageId(i)] =
        util::Utf16ToUtf8(android::StringPiece16(table.getBasePackageName(i).string()));
  }
  return package_map;
}

bool AssetManagerSymbolSource::IsPackageDynamic(uint32_t packageId) const {
  return assets_.getResources(false).isPackageDynamic(packageId);
}

static std::unique_ptr<SymbolTable::Symbol> LookupAttributeInTable(
    const android::ResTable& table, ResourceId id) {
  // Try as a bag.
  const android::ResTable::bag_entry* entry;
  ssize_t count = table.lockBag(id.id, &entry);
  if (count < 0) {
    table.unlockBag(entry);
    return nullptr;
  }

  // We found a resource.
  std::unique_ptr<SymbolTable::Symbol> s = util::make_unique<SymbolTable::Symbol>(id);

  // Check to see if it is an attribute.
  for (size_t i = 0; i < (size_t)count; i++) {
    if (entry[i].map.name.ident == android::ResTable_map::ATTR_TYPE) {
      s->attribute = std::make_shared<Attribute>(entry[i].map.value.data);
      break;
    }
  }

  if (s->attribute) {
    for (size_t i = 0; i < (size_t)count; i++) {
      const android::ResTable_map& map_entry = entry[i].map;
      if (Res_INTERNALID(map_entry.name.ident)) {
        switch (map_entry.name.ident) {
          case android::ResTable_map::ATTR_MIN:
            s->attribute->min_int = static_cast<int32_t>(map_entry.value.data);
            break;
          case android::ResTable_map::ATTR_MAX:
            s->attribute->max_int = static_cast<int32_t>(map_entry.value.data);
            break;
        }
        continue;
      }

      android::ResTable::resource_name entry_name;
      if (!table.getResourceName(map_entry.name.ident, false, &entry_name)) {
        table.unlockBag(entry);
        return nullptr;
      }

      Maybe<ResourceName> parsed_name = ResourceUtils::ToResourceName(entry_name);
      if (!parsed_name) {
        return nullptr;
      }

      Attribute::Symbol symbol;
      symbol.symbol.name = parsed_name.value();
      symbol.symbol.id = ResourceId(map_entry.name.ident);
      symbol.value = map_entry.value.data;
      s->attribute->symbols.push_back(std::move(symbol));
    }
  }
  table.unlockBag(entry);
  return s;
}

std::unique_ptr<SymbolTable::Symbol> AssetManagerSymbolSource::FindByName(
    const ResourceName& name) {
  const android::ResTable& table = assets_.getResources(false);

  const std::u16string package16 = util::Utf8ToUtf16(name.package);
  const std::u16string type16 = util::Utf8ToUtf16(to_string(name.type));
  const std::u16string entry16 = util::Utf8ToUtf16(name.entry);
  const std::u16string mangled_entry16 =
      util::Utf8ToUtf16(NameMangler::MangleEntry(name.package, name.entry));

  uint32_t type_spec_flags;
  ResourceId res_id;

  // There can be mangled resources embedded within other packages. Here we will
  // look into each package and look-up the mangled name until we find the resource.
  const size_t count = table.getBasePackageCount();
  for (size_t i = 0; i < count; i++) {
    const android::String16 package_name = table.getBasePackageName(i);
    StringPiece16 real_package16 = package16;
    StringPiece16 real_entry16 = entry16;
    std::u16string scratch_entry16;
    if (StringPiece16(package_name) != package16) {
      real_entry16 = mangled_entry16;
      real_package16 = package_name.string();
    }

    type_spec_flags = 0;
    res_id = table.identifierForName(real_entry16.data(), real_entry16.size(), type16.data(),
                                     type16.size(), real_package16.data(), real_package16.size(),
                                     &type_spec_flags);
    if (res_id.is_valid()) {
      break;
    }
  }

  if (!res_id.is_valid()) {
    return {};
  }

  std::unique_ptr<SymbolTable::Symbol> s;
  if (name.type == ResourceType::kAttr) {
    s = LookupAttributeInTable(table, res_id);
  } else {
    s = util::make_unique<SymbolTable::Symbol>();
    s->id = res_id;
    s->is_dynamic = table.isResourceDynamic(res_id.id);
  }

  if (s) {
    s->is_public = (type_spec_flags & android::ResTable_typeSpec::SPEC_PUBLIC) != 0;
    return s;
  }
  return {};
}

static Maybe<ResourceName> GetResourceName(const android::ResTable& table,
                                           ResourceId id) {
  android::ResTable::resource_name res_name = {};
  if (!table.getResourceName(id.id, true, &res_name)) {
    return {};
  }
  return ResourceUtils::ToResourceName(res_name);
}

std::unique_ptr<SymbolTable::Symbol> AssetManagerSymbolSource::FindById(
    ResourceId id) {
  if (!id.is_valid()) {
    // Exit early and avoid the error logs from AssetManager.
    return {};
  }
  const android::ResTable& table = assets_.getResources(false);
  Maybe<ResourceName> maybe_name = GetResourceName(table, id);
  if (!maybe_name) {
    return {};
  }

  uint32_t type_spec_flags = 0;
  table.getResourceFlags(id.id, &type_spec_flags);

  std::unique_ptr<SymbolTable::Symbol> s;
  if (maybe_name.value().type == ResourceType::kAttr) {
    s = LookupAttributeInTable(table, id);
  } else {
    s = util::make_unique<SymbolTable::Symbol>();
    s->id = id;
    s->is_dynamic = table.isResourceDynamic(id.id);
  }

  if (s) {
    s->is_public = (type_spec_flags & android::ResTable_typeSpec::SPEC_PUBLIC) != 0;
    return s;
  }
  return {};
}

std::unique_ptr<SymbolTable::Symbol> AssetManagerSymbolSource::FindByReference(
    const Reference& ref) {
  // AssetManager always prefers IDs.
  if (ref.id) {
    return FindById(ref.id.value());
  } else if (ref.name) {
    return FindByName(ref.name.value());
  }
  return {};
}

}  // namespace aapt
