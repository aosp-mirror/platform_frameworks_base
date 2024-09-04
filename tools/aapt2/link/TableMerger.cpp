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
#include "trace/TraceBuffer.h"
#include "ValueVisitor.h"
#include "util/Util.h"

using ::android::StringPiece;

namespace aapt {

TableMerger::TableMerger(IAaptContext* context, ResourceTable* out_table,
                         const TableMergerOptions& options)
    : context_(context), main_table_(out_table), options_(options) {
  // Create the desired package that all tables will be merged into.
  main_package_ = main_table_->FindOrCreatePackage(context_->GetCompilationPackage());
  CHECK(main_package_ != nullptr) << "package name or ID already taken";
}

bool TableMerger::Merge(const android::Source& src, ResourceTable* table, bool overlay) {
  TRACE_CALL();
  // We allow adding new resources if this is not an overlay, or if the options allow overlays
  // to add new resources.
  return MergeImpl(src, table, overlay, options_.auto_add_overlay || !overlay /*allow_new*/);
}

// This will merge packages with the same package name (or no package name).
bool TableMerger::MergeImpl(const android::Source& src, ResourceTable* table, bool overlay,
                            bool allow_new) {
  bool error = false;
  for (auto& package : table->packages) {
    // Only merge an empty package or the package we're building.
    // Other packages may exist, which likely contain attribute definitions.
    // This is because at compile time it is unknown if the attributes are
    // simply uses of the attribute or definitions.
    if (package->name.empty() || context_->GetCompilationPackage() == package->name) {
      // Merge here. Once the entries are merged and mangled, any references to them are still
      // valid. This is because un-mangled references are mangled, then looked up at resolution
      // time. Also, when linking, we convert references with no package name to use the compilation
      // package name.
      error |= !DoMerge(src, package.get(), false /*mangle*/, overlay, allow_new);
    }
  }
  return !error;
}

// This will merge and mangle resources from a static library. It is assumed that all FileReferences
// have correctly set their io::IFile*.
bool TableMerger::MergeAndMangle(const android::Source& src, StringPiece package_name,
                                 ResourceTable* table) {
  bool error = false;
  for (auto& package : table->packages) {
    // Warn of packages with an unrelated ID.
    if (package_name != package->name) {
      context_->GetDiagnostics()->Warn(android::DiagMessage(src)
                                       << "ignoring package " << package->name);
      continue;
    }

    bool mangle = package_name != context_->GetCompilationPackage();
    merged_packages_.insert(package->name);
    error |= !DoMerge(src, package.get(), mangle, false /*overlay*/, true /*allow_new*/);
  }
  return !error;
}

static bool MergeType(IAaptContext* context, const android::Source& src,
                      ResourceTableType* dst_type, ResourceTableType* src_type) {
  if (src_type->visibility_level >= dst_type->visibility_level) {
    // The incoming type's visibility is stronger, so we should override the visibility.
    dst_type->visibility_level = src_type->visibility_level;
  }
  return true;
}

static bool MergeEntry(IAaptContext* context, const android::Source& src, ResourceEntry* dst_entry,
                       ResourceEntry* src_entry, bool strict_visibility) {
  if (strict_visibility
      && dst_entry->visibility.level != Visibility::Level::kUndefined
      && src_entry->visibility.level != dst_entry->visibility.level) {
    context->GetDiagnostics()->Error(android::DiagMessage(src)
                                     << "cannot merge resource '" << dst_entry->name
                                     << "' with conflicting visibilities: "
                                     << "public and private");
    return false;
  }

  // Copy over the strongest visibility.
  if (src_entry->visibility.level > dst_entry->visibility.level) {
    // Only copy the ID if the source is public, or else the ID is meaningless.
    if (src_entry->visibility.level == Visibility::Level::kPublic) {
      dst_entry->id = src_entry->id;
    }
    dst_entry->visibility = std::move(src_entry->visibility);
  } else if (src_entry->visibility.level == Visibility::Level::kPublic &&
             dst_entry->visibility.level == Visibility::Level::kPublic && dst_entry->id &&
             src_entry->id && src_entry->id != dst_entry->id) {
    // Both entries are public and have different IDs.
    context->GetDiagnostics()->Error(android::DiagMessage(src)
                                     << "cannot merge entry '" << src_entry->name
                                     << "': conflicting public IDs");
    return false;
  }

  // Copy over the rest of the properties, if needed.
  if (src_entry->allow_new) {
    dst_entry->allow_new = std::move(src_entry->allow_new);
  }

  if (src_entry->overlayable_item) {
    if (dst_entry->overlayable_item) {
      CHECK(src_entry->overlayable_item.value().overlayable != nullptr);
      Overlayable* src_overlayable = src_entry->overlayable_item.value().overlayable.get();

      CHECK(dst_entry->overlayable_item.value().overlayable != nullptr);
      Overlayable* dst_overlayable = dst_entry->overlayable_item.value().overlayable.get();

      if (src_overlayable->name != dst_overlayable->name
          || src_overlayable->actor != dst_overlayable->actor
          || src_entry->overlayable_item.value().policies !=
             dst_entry->overlayable_item.value().policies) {

        // Do not allow a resource with an overlayable declaration to have that overlayable
        // declaration redefined.
        context->GetDiagnostics()->Error(
            android::DiagMessage(src_entry->overlayable_item.value().source)
            << "duplicate overlayable declaration for resource '" << src_entry->name << "'");
        context->GetDiagnostics()->Error(
            android::DiagMessage(dst_entry->overlayable_item.value().source)
            << "previous declaration here");
        return false;
      }
    }

    dst_entry->overlayable_item = std::move(src_entry->overlayable_item);
  }

  if (src_entry->staged_id) {
    if (dst_entry->staged_id &&
        dst_entry->staged_id.value().id != src_entry->staged_id.value().id) {
      context->GetDiagnostics()->Error(android::DiagMessage(src_entry->staged_id.value().source)
                                       << "conflicting staged id declaration for resource '"
                                       << src_entry->name << "'");
      context->GetDiagnostics()->Error(android::DiagMessage(dst_entry->staged_id.value().source)
                                       << "previous declaration here");
    }
    dst_entry->staged_id = std::move(src_entry->staged_id);
  }

  return true;
}

// Modified CollisionResolver which will merge Styleables and Styles. Used with overlays.
//
// Styleables are not actual resources, but they are treated as such during the compilation phase.
//
// Styleables and Styles don't simply overlay each other, their definitions merge and accumulate.
// If both values are Styleables/Styles, we just merge them into the existing value.
static ResourceTable::CollisionResult ResolveMergeCollision(
    bool override_styles_instead_of_overlaying, Value* existing, Value* incoming,
    android::StringPool* pool) {
  if (Styleable* existing_styleable = ValueCast<Styleable>(existing)) {
    if (Styleable* incoming_styleable = ValueCast<Styleable>(incoming)) {
      // Styleables get merged.
      existing_styleable->MergeWith(incoming_styleable);
      return ResourceTable::CollisionResult::kKeepOriginal;
    }
  } else if (!override_styles_instead_of_overlaying) {
    if (Style* existing_style = ValueCast<Style>(existing)) {
      if (Style* incoming_style = ValueCast<Style>(incoming)) {
        // Styles get merged.
        existing_style->MergeWith(incoming_style, pool);
        return ResourceTable::CollisionResult::kKeepOriginal;
      }
    }
  }
  // Delegate to the default handler.
  return ResourceTable::ResolveValueCollision(existing, incoming);
}

static ResourceTable::CollisionResult MergeConfigValue(
    IAaptContext* context, const ResourceNameRef& res_name, bool overlay,
    bool override_styles_instead_of_overlaying, ResourceConfigValue* dst_config_value,
    ResourceConfigValue* src_config_value, android::StringPool* pool) {
  using CollisionResult = ResourceTable::CollisionResult;

  Value* dst_value = dst_config_value->value.get();
  Value* src_value = src_config_value->value.get();

  CollisionResult collision_result;
  if (overlay) {
    collision_result =
        ResolveMergeCollision(override_styles_instead_of_overlaying, dst_value, src_value, pool);
  } else {
    collision_result =
        ResourceTable::ResolveFlagCollision(dst_value->GetFlagStatus(), src_value->GetFlagStatus());
    if (collision_result == CollisionResult::kConflict) {
      collision_result = ResourceTable::ResolveValueCollision(dst_value, src_value);
    }
  }

  if (collision_result == CollisionResult::kConflict) {
    if (overlay) {
      return CollisionResult::kTakeNew;
    }

    // Error!
    context->GetDiagnostics()->Error(android::DiagMessage(src_value->GetSource())
                                     << "resource '" << res_name << "' has a conflicting value for "
                                     << "configuration (" << src_config_value->config << ")");
    context->GetDiagnostics()->Note(android::DiagMessage(dst_value->GetSource())
                                    << "originally defined here");
    return CollisionResult::kConflict;
  }
  return collision_result;
}

bool TableMerger::DoMerge(const android::Source& src, ResourceTablePackage* src_package,
                          bool mangle_package, bool overlay, bool allow_new_resources) {
  bool error = false;

  for (auto& src_type : src_package->types) {
    ResourceTableType* dst_type = main_package_->FindOrCreateType(src_type->named_type);
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
      if (allow_new_resources || src_entry->allow_new) {
        dst_entry = dst_type->FindOrCreateEntry(entry_name);
      } else {
        dst_entry = dst_type->FindEntry(entry_name);
      }

      const ResourceNameRef res_name(src_package->name, src_type->named_type, src_entry->name);

      if (!dst_entry) {
        context_->GetDiagnostics()->Error(android::DiagMessage(src)
                                          << "resource " << res_name
                                          << " does not override an existing resource");
        context_->GetDiagnostics()->Note(android::DiagMessage(src)
                                         << "define an <add-resource> tag or use "
                                         << "--auto-add-overlay");
        error = true;
        continue;
      }

      if (!MergeEntry(context_, src, dst_entry, src_entry.get(), options_.strict_visibility)) {
        error = true;
        continue;
      }

      for (auto& src_config_value : src_entry->values) {
        using CollisionResult = ResourceTable::CollisionResult;

        ResourceConfigValue* dst_config_value = dst_entry->FindValue(
            src_config_value->config, src_config_value->product);
        if (dst_config_value) {
          CollisionResult collision_result = MergeConfigValue(
              context_, res_name, overlay, options_.override_styles_instead_of_overlaying,
              dst_config_value, src_config_value.get(), &main_table_->string_pool);
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
        CloningValueTransformer cloner(&main_table_->string_pool);
        if (FileReference* f = ValueCast<FileReference>(src_config_value->value.get())) {
          std::unique_ptr<FileReference> new_file_ref;
          if (mangle_package) {
            new_file_ref = CloneAndMangleFile(src_package->name, *f);
          } else {
            new_file_ref = std::unique_ptr<FileReference>(f->Transform(cloner));
          }
          dst_config_value->value = std::move(new_file_ref);

        } else {
          auto original_comment = (dst_config_value->value)
              ? dst_config_value->value->GetComment() : std::optional<std::string>();

          dst_config_value->value = src_config_value->value->Transform(cloner);

          // Keep the comment from the original resource and ignore all comments from overlaying
          // resources
          if (overlay && original_comment) {
            dst_config_value->value->SetComment(original_comment.value());
          }
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
    std::string mangled_entry = NameMangler::MangleEntry(package, entry);
    std::string newPath = (std::string(prefix) += mangled_entry) += suffix;
    std::unique_ptr<FileReference> new_file_ref =
        util::make_unique<FileReference>(main_table_->string_pool.MakeRef(newPath));
    new_file_ref->SetComment(file_ref.GetComment());
    new_file_ref->SetSource(file_ref.GetSource());
    new_file_ref->type = file_ref.type;
    new_file_ref->file = file_ref.file;
    return new_file_ref;
  }

  CloningValueTransformer cloner(&main_table_->string_pool);
  return std::unique_ptr<FileReference>(file_ref.Transform(cloner));
}

bool TableMerger::MergeFile(const ResourceFile& file_desc, bool overlay, io::IFile* file) {
  ResourceTable table;
  std::string path = ResourceUtils::BuildResourceFileName(file_desc);
  std::unique_ptr<FileReference> file_ref =
      util::make_unique<FileReference>(table.string_pool.MakeRef(path));
  file_ref->SetSource(file_desc.source);
  file_ref->type = file_desc.type;
  file_ref->file = file;

  ResourceTablePackage* pkg = table.FindOrCreatePackage(file_desc.name.package);
  pkg->FindOrCreateType(file_desc.name.type)
      ->FindOrCreateEntry(file_desc.name.entry)
      ->FindOrCreateValue(file_desc.config, {})
      ->value = std::move(file_ref);

  return DoMerge(file->GetSource(), pkg, false /*mangle*/, overlay /*overlay*/, true /*allow_new*/);
}

}  // namespace aapt
