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

#ifndef AAPT_PROCESS_SYMBOLTABLE_H
#define AAPT_PROCESS_SYMBOLTABLE_H

#include <algorithm>
#include <memory>
#include <vector>

#include "android-base/macros.h"
#include "androidfw/Asset.h"
#include "androidfw/AssetManager2.h"
#include "utils/JenkinsHash.h"
#include "utils/LruCache.h"

#include "Resource.h"
#include "ResourceTable.h"
#include "ResourceValues.h"
#include "util/Util.h"

namespace aapt {

inline android::hash_t hash_type(const ResourceName& name) {
  std::hash<std::string> str_hash;
  android::hash_t hash = 0;
  hash = android::JenkinsHashMix(hash, (uint32_t)str_hash(name.package));
  hash = android::JenkinsHashMix(hash, (uint32_t)str_hash(name.type.name));
  hash = android::JenkinsHashMix(hash, (uint32_t)str_hash(name.entry));
  return hash;
}

inline android::hash_t hash_type(const ResourceId& id) {
  return android::hash_type(id.id);
}

class ISymbolSource;
class ISymbolTableDelegate;
class NameMangler;

class SymbolTable {
 public:
  struct Symbol {
    Symbol() = default;

    explicit Symbol(const std::optional<ResourceId>& i, const std::shared_ptr<Attribute>& attr = {},
                    bool pub = false)
        : id(i), attribute(attr), is_public(pub) {
    }

    Symbol(const Symbol&) = default;
    Symbol(Symbol&&) = default;
    Symbol& operator=(const Symbol&) = default;
    Symbol& operator=(Symbol&&) = default;

    std::optional<ResourceId> id;
    std::shared_ptr<Attribute> attribute;
    bool is_public = false;
    bool is_dynamic = false;
  };

  explicit SymbolTable(NameMangler* mangler);

  // Overrides the default ISymbolTableDelegate, which allows a custom defined strategy for
  // looking up resources from a set of sources.
  void SetDelegate(std::unique_ptr<ISymbolTableDelegate> delegate);

  // Appends a symbol source. The cache is not cleared since entries that
  // have already been found would take precedence due to ordering.
  void AppendSource(std::unique_ptr<ISymbolSource> source);

  // Prepends a symbol source so that its symbols take precedence. This will
  // cause the existing cache to be cleared.
  void PrependSource(std::unique_ptr<ISymbolSource> source);

  // NOTE: Never hold on to the result between calls to FindByXXX. The
  // results are stored in a cache which may evict entries on subsequent calls.
  const Symbol* FindByName(const ResourceName& name);

  // NOTE: Never hold on to the result between calls to FindByXXX. The
  // results are stored in a cache which may evict entries on subsequent calls.
  const Symbol* FindById(const ResourceId& id);

  // Let's the ISymbolSource decide whether looking up by name or ID is faster,
  // if both are available.
  // NOTE: Never hold on to the result between calls to FindByXXX. The
  // results are stored in a cache which may evict entries on subsequent calls.
  const Symbol* FindByReference(const Reference& ref);

 private:
  NameMangler* mangler_;
  std::unique_ptr<ISymbolTableDelegate> delegate_;
  std::vector<std::unique_ptr<ISymbolSource>> sources_;

  // We use shared_ptr because unique_ptr is not supported and
  // we need automatic deletion.
  android::LruCache<ResourceName, std::shared_ptr<Symbol>> cache_;
  android::LruCache<ResourceId, std::shared_ptr<Symbol>> id_cache_;

  DISALLOW_COPY_AND_ASSIGN(SymbolTable);
};

// Allows the customization of the lookup strategy/order of a symbol from a set of
// symbol sources.
class ISymbolTableDelegate {
 public:
  ISymbolTableDelegate() = default;
  virtual ~ISymbolTableDelegate() = default;

  // The name is already mangled and does not need further processing.
  virtual std::unique_ptr<SymbolTable::Symbol> FindByName(
      const ResourceName& name, const std::vector<std::unique_ptr<ISymbolSource>>& sources) = 0;

  virtual std::unique_ptr<SymbolTable::Symbol> FindById(
      ResourceId id, const std::vector<std::unique_ptr<ISymbolSource>>& sources) = 0;

 private:
  DISALLOW_COPY_AND_ASSIGN(ISymbolTableDelegate);
};

class DefaultSymbolTableDelegate : public ISymbolTableDelegate {
 public:
  DefaultSymbolTableDelegate() = default;
  virtual ~DefaultSymbolTableDelegate() = default;

  virtual std::unique_ptr<SymbolTable::Symbol> FindByName(
      const ResourceName& name,
      const std::vector<std::unique_ptr<ISymbolSource>>& sources) override;
  virtual std::unique_ptr<SymbolTable::Symbol> FindById(
      ResourceId id, const std::vector<std::unique_ptr<ISymbolSource>>& sources) override;

 private:
  DISALLOW_COPY_AND_ASSIGN(DefaultSymbolTableDelegate);
};

// An interface that a symbol source implements in order to surface symbol information
// to the symbol table.
class ISymbolSource {
 public:
  virtual ~ISymbolSource() = default;

  virtual std::unique_ptr<SymbolTable::Symbol> FindByName(
      const ResourceName& name) = 0;
  virtual std::unique_ptr<SymbolTable::Symbol> FindById(ResourceId id) = 0;

  // Default implementation tries the name if it exists, else the ID.
  virtual std::unique_ptr<SymbolTable::Symbol> FindByReference(
      const Reference& ref) {
    if (ref.name) {
      return FindByName(ref.name.value());
    } else if (ref.id) {
      return FindById(ref.id.value());
    }
    return {};
  }
};

// Exposes the resources in a ResourceTable as symbols for SymbolTable.
// Instances of this class must outlive the encompassed ResourceTable.
// Lookups by ID are ignored.
class ResourceTableSymbolSource : public ISymbolSource {
 public:
  explicit ResourceTableSymbolSource(ResourceTable* table) : table_(table) {}

  std::unique_ptr<SymbolTable::Symbol> FindByName(
      const ResourceName& name) override;

  std::unique_ptr<SymbolTable::Symbol> FindById(ResourceId id) override {
    return {};
  }

 private:
  ResourceTable* table_;

  DISALLOW_COPY_AND_ASSIGN(ResourceTableSymbolSource);
};

class AssetManagerSymbolSource : public ISymbolSource {
 public:
  AssetManagerSymbolSource() = default;

  bool AddAssetPath(const android::StringPiece& path);
  std::map<size_t, std::string> GetAssignedPackageIds() const;
  bool IsPackageDynamic(uint32_t packageId, const std::string& package_name) const;

  std::unique_ptr<SymbolTable::Symbol> FindByName(
      const ResourceName& name) override;
  std::unique_ptr<SymbolTable::Symbol> FindById(ResourceId id) override;
  std::unique_ptr<SymbolTable::Symbol> FindByReference(
      const Reference& ref) override;

  android::AssetManager2* GetAssetManager() {
    return &asset_manager_;
  }

 private:
  android::AssetManager2 asset_manager_;
  std::vector<std::unique_ptr<const android::ApkAssets>> apk_assets_;

  DISALLOW_COPY_AND_ASSIGN(AssetManagerSymbolSource);
};

}  // namespace aapt

#endif /* AAPT_PROCESS_SYMBOLTABLE_H */
