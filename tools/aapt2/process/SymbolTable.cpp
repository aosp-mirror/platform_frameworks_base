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
#include "androidfw/Asset.h"
#include "androidfw/AssetManager2.h"
#include "androidfw/ConfigDescription.h"
#include "androidfw/ResourceTypes.h"
#include "androidfw/ResourceUtils.h"

#include "NameMangler.h"
#include "Resource.h"
#include "ResourceUtils.h"
#include "ValueVisitor.h"
#include "trace/TraceBuffer.h"
#include "util/Util.h"

using ::android::ApkAssets;
using ::android::ConfigDescription;
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
  // and store it somewhere; we use the std::optional<> class to reserve storage.
  std::optional<ResourceName> name_with_package_impl;
  if (name.package.empty()) {
    name_with_package_impl = ResourceName(mangler_->GetTargetPackageName(), name.type, name.entry);
    name_with_package = &name_with_package_impl.value();
  }

  // We store the name unmangled in the cache, so look it up as-is.
  if (const std::shared_ptr<Symbol>& s = cache_.get(*name_with_package)) {
    return s.get();
  }

  // The name was not found in the cache. Mangle it (if necessary) and find it in our sources.
  // Again, here we use a std::optional<> object to reserve storage if we need to mangle.
  const ResourceName* mangled_name = name_with_package;
  std::optional<ResourceName> mangled_name_impl;
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
  std::optional<ResourceTable::SearchResult> result = table_->FindResource(name);
  if (!result) {
    if (name.type.type == ResourceType::kAttr) {
      // Recurse and try looking up a private attribute.
      return FindByName(ResourceName(name.package, ResourceType::kAttrPrivate, name.entry));
    }
    return {};
  }

  ResourceTable::SearchResult sr = result.value();

  std::unique_ptr<SymbolTable::Symbol> symbol = util::make_unique<SymbolTable::Symbol>();
  symbol->is_public = (sr.entry->visibility.level == Visibility::Level::kPublic);

  if (sr.entry->id) {
    symbol->id = sr.entry->id.value();
    symbol->is_dynamic =
        (sr.entry->id.value().package_id() == 0) || sr.entry->visibility.staged_api;
  }

  if (name.type.type == ResourceType::kAttr || name.type.type == ResourceType::kAttrPrivate) {
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

bool AssetManagerSymbolSource::AddAssetPath(const StringPiece& path) {
  TRACE_CALL();
  if (std::unique_ptr<const ApkAssets> apk = ApkAssets::Load(path.data())) {
    apk_assets_.push_back(std::move(apk));

    std::vector<const ApkAssets*> apk_assets;
    for (const std::unique_ptr<const ApkAssets>& apk_asset : apk_assets_) {
      apk_assets.push_back(apk_asset.get());
    }

    asset_manager_.SetApkAssets(apk_assets);
    return true;
  }
  return false;
}

std::map<size_t, std::string> AssetManagerSymbolSource::GetAssignedPackageIds() const {
  TRACE_CALL();
  std::map<size_t, std::string> package_map;
  asset_manager_.ForEachPackage([&package_map](const std::string& name, uint8_t id) -> bool {
    package_map.insert(std::make_pair(id, name));
    return true;
  });

  return package_map;
}

bool AssetManagerSymbolSource::IsPackageDynamic(uint32_t packageId,
    const std::string& package_name) const {
  if (packageId == 0) {
    return true;
  }

  for (const std::unique_ptr<const ApkAssets>& assets : apk_assets_) {
    for (const std::unique_ptr<const android::LoadedPackage>& loaded_package
         : assets->GetLoadedArsc()->GetPackages()) {
      if (package_name == loaded_package->GetPackageName() && loaded_package->IsDynamic()) {
        return true;
      }
    }
  }

  return false;
}

static std::unique_ptr<SymbolTable::Symbol> LookupAttributeInTable(
    android::AssetManager2& am, ResourceId id) {
  using namespace android;
  if (am.GetApkAssets().empty()) {
    return {};
  }

  auto bag_result = am.GetBag(id.id);
  if (!bag_result.has_value()) {
    return nullptr;
  }

  // We found a resource.
  std::unique_ptr<SymbolTable::Symbol> s = util::make_unique<SymbolTable::Symbol>(id);
  const ResolvedBag* bag = *bag_result;
  const size_t count = bag->entry_count;
  for (uint32_t i = 0; i < count; i++) {
    if (bag->entries[i].key == ResTable_map::ATTR_TYPE) {
      s->attribute = std::make_shared<Attribute>(bag->entries[i].value.data);
      break;
    }
  }

  if (s->attribute) {
    for (size_t i = 0; i < count; i++) {
      const ResolvedBag::Entry& map_entry = bag->entries[i];
      if (Res_INTERNALID(map_entry.key)) {
        switch (map_entry.key) {
          case ResTable_map::ATTR_MIN:
            s->attribute->min_int = static_cast<int32_t>(map_entry.value.data);
            break;
          case ResTable_map::ATTR_MAX:
            s->attribute->max_int = static_cast<int32_t>(map_entry.value.data);
            break;
        }
        continue;
      }

      auto name = am.GetResourceName(map_entry.key);
      if (!name.has_value()) {
        return nullptr;
      }

      std::optional<ResourceName> parsed_name = ResourceUtils::ToResourceName(*name);
      if (!parsed_name) {
        return nullptr;
      }

      Attribute::Symbol symbol;
      symbol.symbol.name = parsed_name.value();
      symbol.symbol.id = ResourceId(map_entry.key);
      symbol.value = map_entry.value.data;
      symbol.type = map_entry.value.dataType;
      s->attribute->symbols.push_back(std::move(symbol));
    }
  }

  return s;
}

std::unique_ptr<SymbolTable::Symbol> AssetManagerSymbolSource::FindByName(
    const ResourceName& name) {
  const std::string mangled_entry = NameMangler::MangleEntry(name.package, name.entry);

  bool found = false;
  ResourceId res_id = 0;
  uint32_t type_spec_flags = 0;
  ResourceName real_name;

  // There can be mangled resources embedded within other packages. Here we will
  // look into each package and look-up the mangled name until we find the resource.
  asset_manager_.ForEachPackage([&](const std::string& package_name, uint8_t id) -> bool {
    real_name = ResourceName(name.package, name.type, name.entry);
    if (package_name != name.package) {
      real_name.entry = mangled_entry;
      real_name.package = package_name;
    }

    auto real_res_id = asset_manager_.GetResourceId(real_name.to_string());
    if (!real_res_id.has_value()) {
      return true;
    }

    res_id.id = *real_res_id;
    if (!res_id.is_valid_static()) {
      return true;
    }

    auto flags = asset_manager_.GetResourceTypeSpecFlags(res_id.id);
    if (flags.has_value()) {
      type_spec_flags = *flags;
      found = true;
      return false;
    }

    return true;
  });

  if (!found) {
    return {};
  }

  std::unique_ptr<SymbolTable::Symbol> s;
  if (real_name.type.type == ResourceType::kAttr) {
    s = LookupAttributeInTable(asset_manager_, res_id);
  } else {
    s = util::make_unique<SymbolTable::Symbol>();
    s->id = res_id;
  }

  if (s) {
    s->is_public = (type_spec_flags & android::ResTable_typeSpec::SPEC_PUBLIC) != 0;
    s->is_dynamic = IsPackageDynamic(ResourceId(res_id).package_id(), real_name.package) ||
                    (type_spec_flags & android::ResTable_typeSpec::SPEC_STAGED_API) != 0;
    return s;
  }
  return {};
}

static std::optional<ResourceName> GetResourceName(android::AssetManager2& am, ResourceId id) {
  auto name = am.GetResourceName(id.id);
  if (!name.has_value()) {
    return {};
  }
  return ResourceUtils::ToResourceName(*name);
}

std::unique_ptr<SymbolTable::Symbol> AssetManagerSymbolSource::FindById(
    ResourceId id) {
  if (!id.is_valid_static()) {
    // Exit early and avoid the error logs from AssetManager.
    return {};
  }

  if (apk_assets_.empty()) {
    return {};
  }

  std::optional<ResourceName> maybe_name = GetResourceName(asset_manager_, id);
  if (!maybe_name) {
    return {};
  }

  auto flags = asset_manager_.GetResourceTypeSpecFlags(id.id);
  if (!flags.has_value()) {
    return {};
  }

  ResourceName& name = maybe_name.value();
  std::unique_ptr<SymbolTable::Symbol> s;
  if (name.type.type == ResourceType::kAttr) {
    s = LookupAttributeInTable(asset_manager_, id);
  } else {
    s = util::make_unique<SymbolTable::Symbol>();
    s->id = id;
  }

  if (s) {
    s->is_public = (*flags & android::ResTable_typeSpec::SPEC_PUBLIC) != 0;
    s->is_dynamic = IsPackageDynamic(ResourceId(id).package_id(), name.package) ||
                    (*flags & android::ResTable_typeSpec::SPEC_STAGED_API) != 0;
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
