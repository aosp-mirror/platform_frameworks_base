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

#include "link/TableMerger.h"

#include "android-base/logging.h"

#include "ResourceTable.h"
#include "ResourceUtils.h"
#include "ResourceValues.h"
#include "ValueVisitor.h"
#include "util/Util.h"

using android::StringPiece;

namespace aapt {

TableMerger::TableMerger(IAaptContext* context, ResourceTable* out_table,
                         const TableMergerOptions& options)
    : context_(context), master_table_(out_table), options_(options) {
  // Create the desired package that all tables will be merged into.
  master_package_ = master_table_->CreatePackage(
      context_->GetCompilationPackage(), context_->GetPackageId());
  CHECK(master_package_ != nullptr) << "package name or ID already taken";
}

bool TableMerger::Merge(const Source& src, ResourceTable* table,
                        io::IFileCollection* collection) {
  return MergeImpl(src, table, collection, false /* overlay */, true /* allow new */);
}

bool TableMerger::MergeOverlay(const Source& src, ResourceTable* table,
                               io::IFileCollection* collection) {
  return MergeImpl(src, table, collection, true /* overlay */, options_.auto_add_overlay);
}

/**
 * This will merge packages with the same package name (or no package name).
 */
bool TableMerger::MergeImpl(const Source& src, ResourceTable* table,
                            io::IFileCollection* collection, bool overlay,
                            bool allow_new) {
  bool error = false;
  for (auto& package : table->packages) {
    // Only merge an empty package or the package we're building.
    // Other packages may exist, which likely contain attribute definitions.
    // This is because at compile time it is unknown if the attributes are
    // simply uses of the attribute or definitions.
    if (package->name.empty() || context_->GetCompilationPackage() == package->name) {
      FileMergeCallback callback;
      if (collection) {
        callback = [&](const ResourceNameRef& name,
                       const ConfigDescription& config, FileReference* new_file,
                       FileReference* old_file) -> bool {
          // The old file's path points inside the APK, so we can use it as is.
          io::IFile* f = collection->FindFile(*old_file->path);
          if (!f) {
            context_->GetDiagnostics()->Error(DiagMessage(src)
                                              << "file '" << *old_file->path << "' not found");
            return false;
          }

          new_file->file = f;
          return true;
        };
      }

      // Merge here. Once the entries are merged and mangled, any references to
      // them are still valid. This is because un-mangled references are
      // mangled, then looked up at resolution time.
      // Also, when linking, we convert references with no package name to use
      // the compilation package name.
      error |= !DoMerge(src, table, package.get(), false /* mangle */, overlay,
                        allow_new, callback);
    }
  }
  return !error;
}

/**
 * This will merge and mangle resources from a static library.
 */
bool TableMerger::MergeAndMangle(const Source& src,
                                 const StringPiece& package_name,
                                 ResourceTable* table,
                                 io::IFileCollection* collection) {
  bool error = false;
  for (auto& package : table->packages) {
    // Warn of packages with an unrelated ID.
    if (package_name != package->name) {
      context_->GetDiagnostics()->Warn(DiagMessage(src) << "ignoring package "
                                                        << package->name);
      continue;
    }

    bool mangle = package_name != context_->GetCompilationPackage();
    merged_packages_.insert(package->name);

    auto callback = [&](
        const ResourceNameRef& name, const ConfigDescription& config,
        FileReference* new_file, FileReference* old_file) -> bool {
      // The old file's path points inside the APK, so we can use it as is.
      io::IFile* f = collection->FindFile(*old_file->path);
      if (!f) {
        context_->GetDiagnostics()->Error(
            DiagMessage(src) << "file '" << *old_file->path << "' not found");
        return false;
      }

      new_file->file = f;
      return true;
    };

    error |= !DoMerge(src, table, package.get(), mangle, false /* overlay */,
                      true /* allow new */, callback);
  }
  return !error;
}

static bool MergeType(IAaptContext* context, const Source& src,
                      ResourceTableType* dst_type,
                      ResourceTableType* src_type) {
  if (dst_type->symbol_status.state < src_type->symbol_status.state) {
    // The incoming type's visibility is stronger, so we should override
    // the visibility.
    if (src_type->symbol_status.state == SymbolState::kPublic) {
      // Only copy the ID if the source is public, or else the ID is
      // meaningless.
      dst_type->id = src_type->id;
    }
    dst_type->symbol_status = std::move(src_type->symbol_status);
  } else if (dst_type->symbol_status.state == SymbolState::kPublic &&
             src_type->symbol_status.state == SymbolState::kPublic &&
             dst_type->id && src_type->id &&
             dst_type->id.value() != src_type->id.value()) {
    // Both types are public and have different IDs.
    context->GetDiagnostics()->Error(DiagMessage(src)
                                     << "cannot merge type '" << src_type->type
                                     << "': conflicting public IDs");
    return false;
  }
  return true;
}

static bool MergeEntry(IAaptContext* context, const Source& src,
                       ResourceEntry* dst_entry, ResourceEntry* src_entry) {
  if (dst_entry->symbol_status.state < src_entry->symbol_status.state) {
    // The incoming type's visibility is stronger, so we should override
    // the visibility.
    if (src_entry->symbol_status.state == SymbolState::kPublic) {
      // Only copy the ID if the source is public, or else the ID is
      // meaningless.
      dst_entry->id = src_entry->id;
    }
    dst_entry->symbol_status = std::move(src_entry->symbol_status);
  } else if (src_entry->symbol_status.state == SymbolState::kPublic &&
             dst_entry->symbol_status.state == SymbolState::kPublic &&
             dst_entry->id && src_entry->id &&
             dst_entry->id.value() != src_entry->id.value()) {
    // Both entries are public and have different IDs.
    context->GetDiagnostics()->Error(
        DiagMessage(src) << "cannot merge entry '" << src_entry->name
                         << "': conflicting public IDs");
    return false;
  }
  return true;
}

// Modified CollisionResolver which will merge Styleables and Styles. Used with overlays.
//
// Styleables are not actual resources, but they are treated as such during the
// compilation phase.
//
// Styleables and Styles don't simply overlay each other, their definitions merge
// and accumulate. If both values are Styleables/Styles, we just merge them into the
// existing value.
static ResourceTable::CollisionResult ResolveMergeCollision(Value* existing, Value* incoming,
                                                            StringPool* pool) {
  if (Styleable* existing_styleable = ValueCast<Styleable>(existing)) {
    if (Styleable* incoming_styleable = ValueCast<Styleable>(incoming)) {
      // Styleables get merged.
      existing_styleable->MergeWith(incoming_styleable);
      return ResourceTable::CollisionResult::kKeepOriginal;
    }
  } else if (Style* existing_style = ValueCast<Style>(existing)) {
    if (Style* incoming_style = ValueCast<Style>(incoming)) {
      // Styles get merged.
      existing_style->MergeWith(incoming_style, pool);
      return ResourceTable::CollisionResult::kKeepOriginal;
    }
  }
  // Delegate to the default handler.
  return ResourceTable::ResolveValueCollision(existing, incoming);
}

static ResourceTable::CollisionResult MergeConfigValue(IAaptContext* context,
                                                       const ResourceNameRef& res_name,
                                                       const bool overlay,
                                                       ResourceConfigValue* dst_config_value,
                                                       ResourceConfigValue* src_config_value,
                                                       StringPool* pool) {
  using CollisionResult = ResourceTable::CollisionResult;

  Value* dst_value = dst_config_value->value.get();
  Value* src_value = src_config_value->value.get();

  CollisionResult collision_result;
  if (overlay) {
    collision_result = ResolveMergeCollision(dst_value, src_value, pool);
  } else {
    collision_result = ResourceTable::ResolveValueCollision(dst_value, src_value);
  }

  if (collision_result == CollisionResult::kConflict) {
    if (overlay) {
      return CollisionResult::kTakeNew;
    }

    // Error!
    context->GetDiagnostics()->Error(DiagMessage(src_value->GetSource())
                                     << "resource '" << res_name << "' has a conflicting value for "
                                     << "configuration (" << src_config_value->config << ")");
    context->GetDiagnostics()->Note(DiagMessage(dst_value->GetSource())
                                    << "originally defined here");
    return CollisionResult::kConflict;
  }
  return collision_result;
}

bool TableMerger::DoMerge(const Source& src, ResourceTable* src_table,
                          ResourceTablePackage* src_package,
                          const bool mangle_package, const bool overlay,
                          const bool allow_new_resources,
                          const FileMergeCallback& callback) {
  bool error = false;

  for (auto& src_type : src_package->types) {
    ResourceTableType* dst_type = master_package_->FindOrCreateType(src_type->type);
    if (!MergeType(context_, src, dst_type, src_type.get())) {
      error = true;
      continue;
    }

    for (auto& src_entry : src_type->entries) {
      std::string entry_name = src_entry->name;
      if (mangle_package) {
        entry_name = NameMangler::MangleEntry(src_package->name, src_entry->name);
      }

      ResourceEntry* dst_entry;
      if (allow_new_resources || src_entry->symbol_status.allow_new) {
        dst_entry = dst_type->FindOrCreateEntry(entry_name);
      } else {
        dst_entry = dst_type->FindEntry(entry_name);
      }

      const ResourceNameRef res_name(src_package->name, src_type->type, src_entry->name);

      if (!dst_entry) {
        context_->GetDiagnostics()->Error(DiagMessage(src)
                                          << "resource " << res_name
                                          << " does not override an existing resource");
        context_->GetDiagnostics()->Note(DiagMessage(src) << "define an <add-resource> tag or use "
                                                          << "--auto-add-overlay");
        error = true;
        continue;
      }

      if (!MergeEntry(context_, src, dst_entry, src_entry.get())) {
        error = true;
        continue;
      }

      for (auto& src_config_value : src_entry->values) {
        using CollisionResult = ResourceTable::CollisionResult;

        ResourceConfigValue* dst_config_value = dst_entry->FindValue(
            src_config_value->config, src_config_value->product);
        if (dst_config_value) {
          CollisionResult collision_result =
              MergeConfigValue(context_, res_name, overlay, dst_config_value,
                               src_config_value.get(), &master_table_->string_pool);
          if (collision_result == CollisionResult::kConflict) {
            error = true;
            continue;
          } else if (collision_result == CollisionResult::kKeepOriginal) {
            continue;
          }
        } else {
          dst_config_value =
              dst_entry->FindOrCreateValue(src_config_value->config, src_config_value->product);
        }

        // Continue if we're taking the new resource.

        if (FileReference* f = ValueCast<FileReference>(src_config_value->value.get())) {
          std::unique_ptr<FileReference> new_file_ref;
          if (mangle_package) {
            new_file_ref = CloneAndMangleFile(src_package->name, *f);
          } else {
            new_file_ref = std::unique_ptr<FileReference>(f->Clone(&master_table_->string_pool));
          }

          if (callback) {
            if (!callback(res_name, src_config_value->config, new_file_ref.get(), f)) {
              error = true;
              continue;
            }
          }
          dst_config_value->value = std::move(new_file_ref);

        } else {
          dst_config_value->value = std::unique_ptr<Value>(
              src_config_value->value->Clone(&master_table_->string_pool));
        }
      }
    }
  }
  return !error;
}

std::unique_ptr<FileReference> TableMerger::CloneAndMangleFile(
    const std::string& package, const FileReference& file_ref) {
  StringPiece prefix, entry, suffix;
  if (util::ExtractResFilePathParts(*file_ref.path, &prefix, &entry, &suffix)) {
    std::string mangled_entry = NameMangler::MangleEntry(package, entry.to_string());
    std::string newPath = prefix.to_string() + mangled_entry + suffix.to_string();
    std::unique_ptr<FileReference> new_file_ref =
        util::make_unique<FileReference>(master_table_->string_pool.MakeRef(newPath));
    new_file_ref->SetComment(file_ref.GetComment());
    new_file_ref->SetSource(file_ref.GetSource());
    return new_file_ref;
  }
  return std::unique_ptr<FileReference>(file_ref.Clone(&master_table_->string_pool));
}

bool TableMerger::MergeFileImpl(const ResourceFile& file_desc, io::IFile* file, bool overlay) {
  ResourceTable table;
  std::string path = ResourceUtils::BuildResourceFileName(file_desc);
  std::unique_ptr<FileReference> file_ref =
      util::make_unique<FileReference>(table.string_pool.MakeRef(path));
  file_ref->SetSource(file_desc.source);
  file_ref->file = file;

  ResourceTablePackage* pkg = table.CreatePackage(file_desc.name.package, 0x0);
  pkg->FindOrCreateType(file_desc.name.type)
      ->FindOrCreateEntry(file_desc.name.entry)
      ->FindOrCreateValue(file_desc.config, {})
      ->value = std::move(file_ref);

  return DoMerge(file->GetSource(), &table, pkg, false /* mangle */,
                 overlay /* overlay */, true /* allow_new */, {});
}

bool TableMerger::MergeFile(const ResourceFile& file_desc, io::IFile* file) {
  return MergeFileImpl(file_desc, file, false /* overlay */);
}

bool TableMerger::MergeFileOverlay(const ResourceFile& file_desc,
                                   io::IFile* file) {
  return MergeFileImpl(file_desc, file, true /* overlay */);
}

}  // namespace aapt
