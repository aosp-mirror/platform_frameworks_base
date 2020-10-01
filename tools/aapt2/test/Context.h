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

#ifndef AAPT_TEST_CONTEXT_H
#define AAPT_TEST_CONTEXT_H

#include <list>

#include "android-base/logging.h"
#include "android-base/macros.h"

#include "NameMangler.h"
#include "process/IResourceTableConsumer.h"
#include "process/SymbolTable.h"
#include "test/Common.h"
#include "util/Util.h"

namespace aapt {
namespace test {

class Context : public IAaptContext {
 public:
  Context() : name_mangler_({}), symbols_(&name_mangler_), min_sdk_version_(0) {}

  PackageType GetPackageType() override {
    return package_type_;
  }

  SymbolTable* GetExternalSymbols() override {
    return &symbols_;
  }

  IDiagnostics* GetDiagnostics() override {
    return &diagnostics_;
  }

  const std::string& GetCompilationPackage() override {
    CHECK(bool(compilation_package_)) << "package name not set";
    return compilation_package_.value();
  }

  void SetCompilationPackage(const android::StringPiece& package) {
    compilation_package_ = package.to_string();
  }

  uint8_t GetPackageId() override {
    CHECK(bool(package_id_)) << "package ID not set";
    return package_id_.value();
  }

  void SetPackageId(uint8_t package_id) {
    package_id_ = package_id;
  }

  NameMangler* GetNameMangler() override {
    return &name_mangler_;
  }

  void SetNameManglerPolicy(const NameManglerPolicy& policy) {
    name_mangler_ = NameMangler(policy);
  }

  bool IsVerbose() override {
    return false;
  }

  int GetMinSdkVersion() override {
    return min_sdk_version_;
  }

  void SetMinSdkVersion(int min_sdk_version) {
    min_sdk_version_ = min_sdk_version;
  }

 const std::set<std::string>& GetSplitNameDependencies() override {
    return split_name_dependencies_;
  }

 private:
  DISALLOW_COPY_AND_ASSIGN(Context);

  friend class ContextBuilder;

  PackageType package_type_ = PackageType::kApp;
  Maybe<std::string> compilation_package_;
  Maybe<uint8_t> package_id_;
  StdErrDiagnostics diagnostics_;
  NameMangler name_mangler_;
  SymbolTable symbols_;
  int min_sdk_version_;
  std::set<std::string> split_name_dependencies_;
};

class ContextBuilder {
 public:
  ContextBuilder& SetPackageType(PackageType type) {
    context_->package_type_ = type;
    return *this;
  }

  ContextBuilder& SetCompilationPackage(const android::StringPiece& package) {
    context_->compilation_package_ = package.to_string();
    return *this;
  }

  ContextBuilder& SetPackageId(uint8_t id) {
    context_->package_id_ = id;
    return *this;
  }

  ContextBuilder& SetNameManglerPolicy(const NameManglerPolicy& policy) {
    context_->name_mangler_ = NameMangler(policy);
    return *this;
  }

  ContextBuilder& AddSymbolSource(std::unique_ptr<ISymbolSource> src) {
    context_->GetExternalSymbols()->AppendSource(std::move(src));
    return *this;
  }

  ContextBuilder& SetMinSdkVersion(int min_sdk) {
    context_->min_sdk_version_ = min_sdk;
    return *this;
  }

  ContextBuilder& SetSplitNameDependencies(const std::set<std::string>& split_name_dependencies) {
    context_->split_name_dependencies_ = split_name_dependencies;
    return *this;
  }

  std::unique_ptr<Context> Build() { return std::move(context_); }

 private:
  std::unique_ptr<Context> context_ = std::unique_ptr<Context>(new Context());
};

class StaticSymbolSourceBuilder {
 public:
  StaticSymbolSourceBuilder& AddPublicSymbol(const android::StringPiece& name, ResourceId id,
                                             std::unique_ptr<Attribute> attr = {}) {
    std::unique_ptr<SymbolTable::Symbol> symbol =
        util::make_unique<SymbolTable::Symbol>(id, std::move(attr), true);
    symbol_source_->name_map_[ParseNameOrDie(name)] = symbol.get();
    symbol_source_->id_map_[id] = symbol.get();
    symbol_source_->symbols_.push_back(std::move(symbol));
    return *this;
  }

  StaticSymbolSourceBuilder& AddSymbol(const android::StringPiece& name, ResourceId id,
                                       std::unique_ptr<Attribute> attr = {}) {
    std::unique_ptr<SymbolTable::Symbol> symbol =
        util::make_unique<SymbolTable::Symbol>(id, std::move(attr), false);
    symbol_source_->name_map_[ParseNameOrDie(name)] = symbol.get();
    symbol_source_->id_map_[id] = symbol.get();
    symbol_source_->symbols_.push_back(std::move(symbol));
    return *this;
  }

  std::unique_ptr<ISymbolSource> Build() {
    return std::move(symbol_source_);
  }

 private:
  class StaticSymbolSource : public ISymbolSource {
   public:
    StaticSymbolSource() = default;

    std::unique_ptr<SymbolTable::Symbol> FindByName(const ResourceName& name) override {
      auto iter = name_map_.find(name);
      if (iter != name_map_.end()) {
        return CloneSymbol(iter->second);
      }
      return nullptr;
    }

    std::unique_ptr<SymbolTable::Symbol> FindById(ResourceId id) override {
      auto iter = id_map_.find(id);
      if (iter != id_map_.end()) {
        return CloneSymbol(iter->second);
      }
      return nullptr;
    }

    std::list<std::unique_ptr<SymbolTable::Symbol>> symbols_;
    std::map<ResourceName, SymbolTable::Symbol*> name_map_;
    std::map<ResourceId, SymbolTable::Symbol*> id_map_;

   private:
    std::unique_ptr<SymbolTable::Symbol> CloneSymbol(SymbolTable::Symbol* sym) {
      std::unique_ptr<SymbolTable::Symbol> clone = util::make_unique<SymbolTable::Symbol>();
      clone->id = sym->id;
      if (sym->attribute) {
        clone->attribute = std::unique_ptr<Attribute>(sym->attribute->Clone(nullptr));
      }
      clone->is_public = sym->is_public;
      return clone;
    }

    DISALLOW_COPY_AND_ASSIGN(StaticSymbolSource);
  };

  std::unique_ptr<StaticSymbolSource> symbol_source_ = util::make_unique<StaticSymbolSource>();
};

}  // namespace test
}  // namespace aapt

#endif /* AAPT_TEST_CONTEXT_H */
