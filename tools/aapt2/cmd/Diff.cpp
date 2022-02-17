/*
 * Copyright (C) 2016 The Android Open Source Project
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

#include "Diff.h"

#include "android-base/macros.h"

#include "LoadedApk.h"
#include "ValueVisitor.h"
#include "process/IResourceTableConsumer.h"
#include "process/SymbolTable.h"

using ::android::StringPiece;

namespace aapt {

class DiffContext : public IAaptContext {
 public:
  DiffContext() : name_mangler_({}), symbol_table_(&name_mangler_) {
  }

  PackageType GetPackageType() override {
    // Doesn't matter.
    return PackageType::kApp;
  }

  const std::string& GetCompilationPackage() override {
    return empty_;
  }

  uint8_t GetPackageId() override {
    return 0x0;
  }

  IDiagnostics* GetDiagnostics() override {
    return &diagnostics_;
  }

  NameMangler* GetNameMangler() override {
    return &name_mangler_;
  }

  SymbolTable* GetExternalSymbols() override {
    return &symbol_table_;
  }

  bool IsVerbose() override {
    return false;
  }

  int GetMinSdkVersion() override {
    return 0;
  }

  const std::set<std::string>& GetSplitNameDependencies() override {
    UNIMPLEMENTED(FATAL) << "Split Name Dependencies should not be necessary";
    static std::set<std::string> empty;
    return empty;
  }

 private:
  std::string empty_;
  StdErrDiagnostics diagnostics_;
  NameMangler name_mangler_;
  SymbolTable symbol_table_;
};

static void EmitDiffLine(const Source& source, const StringPiece& message) {
  std::cerr << source << ": " << message << "\n";
}

static bool IsSymbolVisibilityDifferent(const Visibility& vis_a, const Visibility& vis_b) {
  return vis_a.level != vis_b.level || vis_a.staged_api != vis_b.staged_api;
}

template <typename Id>
static bool IsIdDiff(const Visibility::Level& level_a, const std::optional<Id>& id_a,
                     const Visibility::Level& level_b, const std::optional<Id>& id_b) {
  if (level_a == Visibility::Level::kPublic || level_b == Visibility::Level::kPublic) {
    return id_a != id_b;
  }
  return false;
}

static bool EmitResourceConfigValueDiff(
    IAaptContext* context, LoadedApk* apk_a, const ResourceTablePackageView& pkg_a,
    const ResourceTableTypeView& type_a, const ResourceTableEntryView& entry_a,
    const ResourceConfigValue* config_value_a, LoadedApk* apk_b,
    const ResourceTablePackageView& pkg_b, const ResourceTableTypeView& type_b,
    const ResourceTableEntryView& entry_b, const ResourceConfigValue* config_value_b) {
  Value* value_a = config_value_a->value.get();
  Value* value_b = config_value_b->value.get();
  if (!value_a->Equals(value_b)) {
    std::stringstream str_stream;
    str_stream << "value " << pkg_a.name << ":" << type_a.type << "/" << entry_a.name
               << " config=" << config_value_a->config << " does not match:\n";
    value_a->Print(&str_stream);
    str_stream << "\n vs \n";
    value_b->Print(&str_stream);
    EmitDiffLine(apk_b->GetSource(), str_stream.str());
    return true;
  }
  return false;
}

static bool EmitResourceEntryDiff(IAaptContext* context, LoadedApk* apk_a,
                                  const ResourceTablePackageView& pkg_a,
                                  const ResourceTableTypeView& type_a,
                                  const ResourceTableEntryView& entry_a, LoadedApk* apk_b,
                                  const ResourceTablePackageView& pkg_b,
                                  const ResourceTableTypeView& type_b,
                                  const ResourceTableEntryView& entry_b) {
  bool diff = false;
  for (const ResourceConfigValue* config_value_a : entry_a.values) {
    auto config_value_b = entry_b.FindValue(config_value_a->config);
    if (!config_value_b) {
      std::stringstream str_stream;
      str_stream << "missing " << pkg_a.name << ":" << type_a.type << "/" << entry_a.name
                 << " config=" << config_value_a->config;
      EmitDiffLine(apk_b->GetSource(), str_stream.str());
      diff = true;
    } else {
      diff |= EmitResourceConfigValueDiff(context, apk_a, pkg_a, type_a, entry_a, config_value_a,
                                          apk_b, pkg_b, type_b, entry_b, config_value_b);
    }
  }

  // Check for any newly added config values.
  for (const ResourceConfigValue* config_value_b : entry_b.values) {
    auto config_value_a = entry_a.FindValue(config_value_b->config);
    if (!config_value_a) {
      std::stringstream str_stream;
      str_stream << "new config " << pkg_b.name << ":" << type_b.type << "/" << entry_b.name
                 << " config=" << config_value_b->config;
      EmitDiffLine(apk_b->GetSource(), str_stream.str());
      diff = true;
    }
  }
  return diff;
}

static bool EmitResourceTypeDiff(IAaptContext* context, LoadedApk* apk_a,
                                 const ResourceTablePackageView& pkg_a,
                                 const ResourceTableTypeView& type_a, LoadedApk* apk_b,
                                 const ResourceTablePackageView& pkg_b,
                                 const ResourceTableTypeView& type_b) {
  bool diff = false;
  auto entry_a_iter = type_a.entries.begin();
  auto entry_b_iter = type_b.entries.begin();
  while (entry_a_iter != type_a.entries.end() || entry_b_iter != type_b.entries.end()) {
    if (entry_b_iter == type_b.entries.end()) {
      // Type A contains a type that type B does not have.
      std::stringstream str_stream;
      str_stream << "missing " << pkg_a.name << ":" << type_a.type << "/" << entry_a_iter->name;
      EmitDiffLine(apk_a->GetSource(), str_stream.str());
      diff = true;
    } else if (entry_a_iter == type_a.entries.end()) {
      // Type B contains a type that type A does not have.
      std::stringstream str_stream;
      str_stream << "new entry " << pkg_b.name << ":" << type_b.type << "/" << entry_b_iter->name;
      EmitDiffLine(apk_b->GetSource(), str_stream.str());
      diff = true;
    } else {
      const auto& entry_a = *entry_a_iter;
      const auto& entry_b = *entry_b_iter;
      if (IsSymbolVisibilityDifferent(entry_a.visibility, entry_b.visibility)) {
        std::stringstream str_stream;
        str_stream << pkg_a.name << ":" << type_a.type << "/" << entry_a.name
                   << " has different visibility (";
        if (entry_b.visibility.staged_api) {
          str_stream << "STAGED ";
        }
        if (entry_b.visibility.level == Visibility::Level::kPublic) {
          str_stream << "PUBLIC";
        } else {
          str_stream << "PRIVATE";
        }
        str_stream << " vs ";
        if (entry_a.visibility.staged_api) {
          str_stream << "STAGED ";
        }
        if (entry_a.visibility.level == Visibility::Level::kPublic) {
          str_stream << "PUBLIC";
        } else {
          str_stream << "PRIVATE";
        }
        str_stream << ")";
        EmitDiffLine(apk_b->GetSource(), str_stream.str());
        diff = true;
      } else if (IsIdDiff(entry_a.visibility.level, entry_a.id, entry_b.visibility.level,
                          entry_b.id)) {
        std::stringstream str_stream;
        str_stream << pkg_a.name << ":" << type_a.type << "/" << entry_a.name
                   << " has different public ID (";
        if (entry_b.id) {
          str_stream << "0x" << std::hex << entry_b.id.value();
        } else {
          str_stream << "none";
        }
        str_stream << " vs ";
        if (entry_a.id) {
          str_stream << "0x " << std::hex << entry_a.id.value();
        } else {
          str_stream << "none";
        }
        str_stream << ")";
        EmitDiffLine(apk_b->GetSource(), str_stream.str());
        diff = true;
      }
      diff |= EmitResourceEntryDiff(context, apk_a, pkg_a, type_a, entry_a, apk_b, pkg_b, type_b,
                                    entry_b);
    }
    if (entry_a_iter != type_a.entries.end()) {
      ++entry_a_iter;
    }
    if (entry_b_iter != type_b.entries.end()) {
      ++entry_b_iter;
    }
  }
  return diff;
}

static bool EmitResourcePackageDiff(IAaptContext* context, LoadedApk* apk_a,
                                    const ResourceTablePackageView& pkg_a, LoadedApk* apk_b,
                                    const ResourceTablePackageView& pkg_b) {
  bool diff = false;
  auto type_a_iter = pkg_a.types.begin();
  auto type_b_iter = pkg_b.types.begin();
  while (type_a_iter != pkg_a.types.end() || type_b_iter != pkg_b.types.end()) {
    if (type_b_iter == pkg_b.types.end()) {
      // Type A contains a type that type B does not have.
      std::stringstream str_stream;
      str_stream << "missing " << pkg_a.name << ":" << type_a_iter->type;
      EmitDiffLine(apk_a->GetSource(), str_stream.str());
      diff = true;
    } else if (type_a_iter == pkg_a.types.end()) {
      // Type B contains a type that type A does not have.
      std::stringstream str_stream;
      str_stream << "new type " << pkg_b.name << ":" << type_b_iter->type;
      EmitDiffLine(apk_b->GetSource(), str_stream.str());
      diff = true;
    } else {
      const auto& type_a = *type_a_iter;
      const auto& type_b = *type_b_iter;
      if (type_a.visibility_level != type_b.visibility_level) {
        std::stringstream str_stream;
        str_stream << pkg_a.name << ":" << type_a.type << " has different visibility (";
        if (type_b.visibility_level == Visibility::Level::kPublic) {
          str_stream << "PUBLIC";
        } else {
          str_stream << "PRIVATE";
        }
        str_stream << " vs ";
        if (type_a.visibility_level == Visibility::Level::kPublic) {
          str_stream << "PUBLIC";
        } else {
          str_stream << "PRIVATE";
        }
        str_stream << ")";
        EmitDiffLine(apk_b->GetSource(), str_stream.str());
        diff = true;
      } else if (IsIdDiff(type_a.visibility_level, type_a.id, type_b.visibility_level, type_b.id)) {
        std::stringstream str_stream;
        str_stream << pkg_a.name << ":" << type_a.type << " has different public ID (";
        if (type_b.id) {
          str_stream << "0x" << std::hex << type_b.id.value();
        } else {
          str_stream << "none";
        }
        str_stream << " vs ";
        if (type_a.id) {
          str_stream << "0x " << std::hex << type_a.id.value();
        } else {
          str_stream << "none";
        }
        str_stream << ")";
        EmitDiffLine(apk_b->GetSource(), str_stream.str());
        diff = true;
      }
      diff |= EmitResourceTypeDiff(context, apk_a, pkg_a, type_a, apk_b, pkg_b, type_b);
    }
    if (type_a_iter != pkg_a.types.end()) {
      ++type_a_iter;
    }
    if (type_b_iter != pkg_b.types.end()) {
      ++type_b_iter;
    }
  }
  return diff;
}

static bool EmitResourceTableDiff(IAaptContext* context, LoadedApk* apk_a, LoadedApk* apk_b) {
  const auto table_a = apk_a->GetResourceTable()->GetPartitionedView();
  const auto table_b = apk_b->GetResourceTable()->GetPartitionedView();

  bool diff = false;
  auto package_a_iter = table_a.packages.begin();
  auto package_b_iter = table_b.packages.begin();
  while (package_a_iter != table_a.packages.end() || package_b_iter != table_b.packages.end()) {
    if (package_b_iter == table_b.packages.end()) {
      // Table A contains a package that table B does not have.
      std::stringstream str_stream;
      str_stream << "missing package " << package_a_iter->name;
      EmitDiffLine(apk_a->GetSource(), str_stream.str());
      diff = true;
    } else if (package_a_iter == table_a.packages.end()) {
      // Table B contains a package that table A does not have.
      std::stringstream str_stream;
      str_stream << "new package " << package_b_iter->name;
      EmitDiffLine(apk_b->GetSource(), str_stream.str());
      diff = true;
    } else {
      const auto& package_a = *package_a_iter;
      const auto& package_b = *package_b_iter;
      if (package_a.id != package_b.id) {
        std::stringstream str_stream;
        str_stream << "package '" << package_a.name << "' has different id (";
        if (package_b.id) {
          str_stream << "0x" << std::hex << package_b.id.value();
        } else {
          str_stream << "none";
        }
        str_stream << " vs ";
        if (package_a.id) {
          str_stream << "0x" << std::hex << package_b.id.value();
        } else {
          str_stream << "none";
        }
        str_stream << ")";
        EmitDiffLine(apk_b->GetSource(), str_stream.str());
        diff = true;
      }
      diff |= EmitResourcePackageDiff(context, apk_a, package_a, apk_b, package_b);
    }
    if (package_a_iter != table_a.packages.end()) {
      ++package_a_iter;
    }
    if (package_b_iter != table_b.packages.end()) {
      ++package_b_iter;
    }
  }

  return diff;
}

class ZeroingReferenceVisitor : public DescendingValueVisitor {
 public:
  using DescendingValueVisitor::Visit;

  void Visit(Reference* ref) override {
    if (ref->name && ref->id) {
      if (ref->id.value().package_id() == kAppPackageId) {
        ref->id = {};
      }
    }
  }
};

static void ZeroOutAppReferences(ResourceTable* table) {
  ZeroingReferenceVisitor visitor;
  VisitAllValuesInTable(table, &visitor);
}

int DiffCommand::Action(const std::vector<std::string>& args) {
  DiffContext context;

  if (args.size() != 2u) {
    std::cerr << "must have two apks as arguments.\n\n";
    Usage(&std::cerr);
    return 1;
  }

  IDiagnostics* diag = context.GetDiagnostics();
  std::unique_ptr<LoadedApk> apk_a = LoadedApk::LoadApkFromPath(args[0], diag);
  std::unique_ptr<LoadedApk> apk_b = LoadedApk::LoadApkFromPath(args[1], diag);
  if (!apk_a || !apk_b) {
    return 1;
  }

  // Zero out Application IDs in references.
  ZeroOutAppReferences(apk_a->GetResourceTable());
  ZeroOutAppReferences(apk_b->GetResourceTable());

  if (EmitResourceTableDiff(&context, apk_a.get(), apk_b.get())) {
    // We emitted a diff, so return 1 (failure).
    return 1;
  }
  return 0;
}

}  // namespace aapt
