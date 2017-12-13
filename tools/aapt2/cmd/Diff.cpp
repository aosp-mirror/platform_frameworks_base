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

#include "android-base/macros.h"

#include "Flags.h"
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
  return vis_a.level != vis_b.level;
}

template <typename Id>
static bool IsIdDiff(const Visibility::Level& level_a, const Maybe<Id>& id_a,
                     const Visibility::Level& level_b, const Maybe<Id>& id_b) {
  if (level_a == Visibility::Level::kPublic || level_b == Visibility::Level::kPublic) {
    return id_a != id_b;
  }
  return false;
}

static bool EmitResourceConfigValueDiff(IAaptContext* context, LoadedApk* apk_a,
                                        ResourceTablePackage* pkg_a, ResourceTableType* type_a,
                                        ResourceEntry* entry_a, ResourceConfigValue* config_value_a,
                                        LoadedApk* apk_b, ResourceTablePackage* pkg_b,
                                        ResourceTableType* type_b, ResourceEntry* entry_b,
                                        ResourceConfigValue* config_value_b) {
  Value* value_a = config_value_a->value.get();
  Value* value_b = config_value_b->value.get();
  if (!value_a->Equals(value_b)) {
    std::stringstream str_stream;
    str_stream << "value " << pkg_a->name << ":" << type_a->type << "/" << entry_a->name
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
                                  ResourceTablePackage* pkg_a, ResourceTableType* type_a,
                                  ResourceEntry* entry_a, LoadedApk* apk_b,
                                  ResourceTablePackage* pkg_b, ResourceTableType* type_b,
                                  ResourceEntry* entry_b) {
  bool diff = false;
  for (std::unique_ptr<ResourceConfigValue>& config_value_a : entry_a->values) {
    ResourceConfigValue* config_value_b = entry_b->FindValue(config_value_a->config);
    if (!config_value_b) {
      std::stringstream str_stream;
      str_stream << "missing " << pkg_a->name << ":" << type_a->type << "/" << entry_a->name
                 << " config=" << config_value_a->config;
      EmitDiffLine(apk_b->GetSource(), str_stream.str());
      diff = true;
    } else {
      diff |=
          EmitResourceConfigValueDiff(context, apk_a, pkg_a, type_a, entry_a, config_value_a.get(),
                                      apk_b, pkg_b, type_b, entry_b, config_value_b);
    }
  }

  // Check for any newly added config values.
  for (std::unique_ptr<ResourceConfigValue>& config_value_b : entry_b->values) {
    ResourceConfigValue* config_value_a = entry_a->FindValue(config_value_b->config);
    if (!config_value_a) {
      std::stringstream str_stream;
      str_stream << "new config " << pkg_b->name << ":" << type_b->type << "/" << entry_b->name
                 << " config=" << config_value_b->config;
      EmitDiffLine(apk_b->GetSource(), str_stream.str());
      diff = true;
    }
  }
  return false;
}

static bool EmitResourceTypeDiff(IAaptContext* context, LoadedApk* apk_a,
                                 ResourceTablePackage* pkg_a, ResourceTableType* type_a,
                                 LoadedApk* apk_b, ResourceTablePackage* pkg_b,
                                 ResourceTableType* type_b) {
  bool diff = false;
  for (std::unique_ptr<ResourceEntry>& entry_a : type_a->entries) {
    ResourceEntry* entry_b = type_b->FindEntry(entry_a->name);
    if (!entry_b) {
      std::stringstream str_stream;
      str_stream << "missing " << pkg_a->name << ":" << type_a->type << "/" << entry_a->name;
      EmitDiffLine(apk_b->GetSource(), str_stream.str());
      diff = true;
    } else {
      if (IsSymbolVisibilityDifferent(entry_a->visibility, entry_b->visibility)) {
        std::stringstream str_stream;
        str_stream << pkg_a->name << ":" << type_a->type << "/" << entry_a->name
                   << " has different visibility (";
        if (entry_b->visibility.level == Visibility::Level::kPublic) {
          str_stream << "PUBLIC";
        } else {
          str_stream << "PRIVATE";
        }
        str_stream << " vs ";
        if (entry_a->visibility.level == Visibility::Level::kPublic) {
          str_stream << "PUBLIC";
        } else {
          str_stream << "PRIVATE";
        }
        str_stream << ")";
        EmitDiffLine(apk_b->GetSource(), str_stream.str());
        diff = true;
      } else if (IsIdDiff(entry_a->visibility.level, entry_a->id, entry_b->visibility.level,
                          entry_b->id)) {
        std::stringstream str_stream;
        str_stream << pkg_a->name << ":" << type_a->type << "/" << entry_a->name
                   << " has different public ID (";
        if (entry_b->id) {
          str_stream << "0x" << std::hex << entry_b->id.value();
        } else {
          str_stream << "none";
        }
        str_stream << " vs ";
        if (entry_a->id) {
          str_stream << "0x " << std::hex << entry_a->id.value();
        } else {
          str_stream << "none";
        }
        str_stream << ")";
        EmitDiffLine(apk_b->GetSource(), str_stream.str());
        diff = true;
      }
      diff |= EmitResourceEntryDiff(context, apk_a, pkg_a, type_a, entry_a.get(), apk_b, pkg_b,
                                    type_b, entry_b);
    }
  }

  // Check for any newly added entries.
  for (std::unique_ptr<ResourceEntry>& entry_b : type_b->entries) {
    ResourceEntry* entry_a = type_a->FindEntry(entry_b->name);
    if (!entry_a) {
      std::stringstream str_stream;
      str_stream << "new entry " << pkg_b->name << ":" << type_b->type << "/" << entry_b->name;
      EmitDiffLine(apk_b->GetSource(), str_stream.str());
      diff = true;
    }
  }
  return diff;
}

static bool EmitResourcePackageDiff(IAaptContext* context, LoadedApk* apk_a,
                                    ResourceTablePackage* pkg_a, LoadedApk* apk_b,
                                    ResourceTablePackage* pkg_b) {
  bool diff = false;
  for (std::unique_ptr<ResourceTableType>& type_a : pkg_a->types) {
    ResourceTableType* type_b = pkg_b->FindType(type_a->type);
    if (!type_b) {
      std::stringstream str_stream;
      str_stream << "missing " << pkg_a->name << ":" << type_a->type;
      EmitDiffLine(apk_a->GetSource(), str_stream.str());
      diff = true;
    } else {
      if (type_a->visibility_level != type_b->visibility_level) {
        std::stringstream str_stream;
        str_stream << pkg_a->name << ":" << type_a->type << " has different visibility (";
        if (type_b->visibility_level == Visibility::Level::kPublic) {
          str_stream << "PUBLIC";
        } else {
          str_stream << "PRIVATE";
        }
        str_stream << " vs ";
        if (type_a->visibility_level == Visibility::Level::kPublic) {
          str_stream << "PUBLIC";
        } else {
          str_stream << "PRIVATE";
        }
        str_stream << ")";
        EmitDiffLine(apk_b->GetSource(), str_stream.str());
        diff = true;
      } else if (IsIdDiff(type_a->visibility_level, type_a->id, type_b->visibility_level,
                          type_b->id)) {
        std::stringstream str_stream;
        str_stream << pkg_a->name << ":" << type_a->type << " has different public ID (";
        if (type_b->id) {
          str_stream << "0x" << std::hex << type_b->id.value();
        } else {
          str_stream << "none";
        }
        str_stream << " vs ";
        if (type_a->id) {
          str_stream << "0x " << std::hex << type_a->id.value();
        } else {
          str_stream << "none";
        }
        str_stream << ")";
        EmitDiffLine(apk_b->GetSource(), str_stream.str());
        diff = true;
      }
      diff |= EmitResourceTypeDiff(context, apk_a, pkg_a, type_a.get(), apk_b, pkg_b, type_b);
    }
  }

  // Check for any newly added types.
  for (std::unique_ptr<ResourceTableType>& type_b : pkg_b->types) {
    ResourceTableType* type_a = pkg_a->FindType(type_b->type);
    if (!type_a) {
      std::stringstream str_stream;
      str_stream << "new type " << pkg_b->name << ":" << type_b->type;
      EmitDiffLine(apk_b->GetSource(), str_stream.str());
      diff = true;
    }
  }
  return diff;
}

static bool EmitResourceTableDiff(IAaptContext* context, LoadedApk* apk_a, LoadedApk* apk_b) {
  ResourceTable* table_a = apk_a->GetResourceTable();
  ResourceTable* table_b = apk_b->GetResourceTable();

  bool diff = false;
  for (std::unique_ptr<ResourceTablePackage>& pkg_a : table_a->packages) {
    ResourceTablePackage* pkg_b = table_b->FindPackage(pkg_a->name);
    if (!pkg_b) {
      std::stringstream str_stream;
      str_stream << "missing package " << pkg_a->name;
      EmitDiffLine(apk_b->GetSource(), str_stream.str());
      diff = true;
    } else {
      if (pkg_a->id != pkg_b->id) {
        std::stringstream str_stream;
        str_stream << "package '" << pkg_a->name << "' has different id (";
        if (pkg_b->id) {
          str_stream << "0x" << std::hex << pkg_b->id.value();
        } else {
          str_stream << "none";
        }
        str_stream << " vs ";
        if (pkg_a->id) {
          str_stream << "0x" << std::hex << pkg_a->id.value();
        } else {
          str_stream << "none";
        }
        str_stream << ")";
        EmitDiffLine(apk_b->GetSource(), str_stream.str());
        diff = true;
      }
      diff |= EmitResourcePackageDiff(context, apk_a, pkg_a.get(), apk_b, pkg_b);
    }
  }

  // Check for any newly added packages.
  for (std::unique_ptr<ResourceTablePackage>& pkg_b : table_b->packages) {
    ResourceTablePackage* pkg_a = table_a->FindPackage(pkg_b->name);
    if (!pkg_a) {
      std::stringstream str_stream;
      str_stream << "new package " << pkg_b->name;
      EmitDiffLine(apk_b->GetSource(), str_stream.str());
      diff = true;
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

int Diff(const std::vector<StringPiece>& args) {
  DiffContext context;

  Flags flags;
  if (!flags.Parse("aapt2 diff", args, &std::cerr)) {
    return 1;
  }

  if (flags.GetArgs().size() != 2u) {
    std::cerr << "must have two apks as arguments.\n\n";
    flags.Usage("aapt2 diff", &std::cerr);
    return 1;
  }

  IDiagnostics* diag = context.GetDiagnostics();
  std::unique_ptr<LoadedApk> apk_a = LoadedApk::LoadApkFromPath(flags.GetArgs()[0], diag);
  std::unique_ptr<LoadedApk> apk_b = LoadedApk::LoadApkFromPath(flags.GetArgs()[1], diag);
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
