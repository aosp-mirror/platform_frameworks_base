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

#include <sys/stat.h>

#include <fstream>
#include <queue>
#include <unordered_map>
#include <vector>

#include "android-base/errors.h"
#include "android-base/file.h"
#include "android-base/stringprintf.h"
#include "androidfw/StringPiece.h"
#include "google/protobuf/io/coded_stream.h"

#include "AppInfo.h"
#include "Debug.h"
#include "Flags.h"
#include "Locale.h"
#include "NameMangler.h"
#include "ResourceUtils.h"
#include "cmd/Util.h"
#include "compile/IdAssigner.h"
#include "filter/ConfigFilter.h"
#include "flatten/Archive.h"
#include "flatten/TableFlattener.h"
#include "flatten/XmlFlattener.h"
#include "io/BigBufferInputStream.h"
#include "io/FileSystem.h"
#include "io/Util.h"
#include "io/ZipArchive.h"
#include "java/JavaClassGenerator.h"
#include "java/ManifestClassGenerator.h"
#include "java/ProguardRules.h"
#include "link/Linkers.h"
#include "link/ManifestFixer.h"
#include "link/ReferenceLinker.h"
#include "link/TableMerger.h"
#include "link/XmlCompatVersioner.h"
#include "optimize/ResourceDeduper.h"
#include "optimize/VersionCollapser.h"
#include "process/IResourceTableConsumer.h"
#include "process/SymbolTable.h"
#include "proto/ProtoSerialize.h"
#include "split/TableSplitter.h"
#include "unflatten/BinaryResourceParser.h"
#include "util/Files.h"
#include "xml/XmlDom.h"

using android::StringPiece;
using android::base::StringPrintf;

namespace aapt {

struct LinkOptions {
  std::string output_path;
  std::string manifest_path;
  std::vector<std::string> include_paths;
  std::vector<std::string> overlay_files;
  std::vector<std::string> assets_dirs;
  bool output_to_directory = false;
  bool auto_add_overlay = false;

  // Java/Proguard options.
  Maybe<std::string> generate_java_class_path;
  Maybe<std::string> custom_java_package;
  std::set<std::string> extra_java_packages;
  Maybe<std::string> generate_text_symbols_path;
  Maybe<std::string> generate_proguard_rules_path;
  Maybe<std::string> generate_main_dex_proguard_rules_path;
  bool generate_non_final_ids = false;
  std::vector<std::string> javadoc_annotations;
  Maybe<std::string> private_symbols;

  // Optimizations/features.
  bool no_auto_version = false;
  bool no_version_vectors = false;
  bool no_version_transitions = false;
  bool no_resource_deduping = false;
  bool no_xml_namespaces = false;
  bool do_not_compress_anything = false;
  std::unordered_set<std::string> extensions_to_not_compress;

  // Static lib options.
  bool no_static_lib_packages = false;

  // AndroidManifest.xml massaging options.
  ManifestFixerOptions manifest_fixer_options;

  // Products to use/filter on.
  std::unordered_set<std::string> products;

  // Flattening options.
  TableFlattenerOptions table_flattener_options;

  // Split APK options.
  TableSplitterOptions table_splitter_options;
  std::vector<SplitConstraints> split_constraints;
  std::vector<std::string> split_paths;

  // Stable ID options.
  std::unordered_map<ResourceName, ResourceId> stable_id_map;
  Maybe<std::string> resource_id_map_path;
};

class LinkContext : public IAaptContext {
 public:
  LinkContext(IDiagnostics* diagnostics)
      : diagnostics_(diagnostics), name_mangler_({}), symbols_(&name_mangler_) {
  }

  PackageType GetPackageType() override {
    return package_type_;
  }

  void SetPackageType(PackageType type) {
    package_type_ = type;
  }

  IDiagnostics* GetDiagnostics() override {
    return diagnostics_;
  }

  NameMangler* GetNameMangler() override {
    return &name_mangler_;
  }

  void SetNameManglerPolicy(const NameManglerPolicy& policy) {
    name_mangler_ = NameMangler(policy);
  }

  const std::string& GetCompilationPackage() override {
    return compilation_package_;
  }

  void SetCompilationPackage(const StringPiece& package_name) {
    compilation_package_ = package_name.to_string();
  }

  uint8_t GetPackageId() override {
    return package_id_;
  }

  void SetPackageId(uint8_t id) {
    package_id_ = id;
  }

  SymbolTable* GetExternalSymbols() override {
    return &symbols_;
  }

  bool IsVerbose() override {
    return verbose_;
  }

  void SetVerbose(bool val) {
    verbose_ = val;
  }

  int GetMinSdkVersion() override {
    return min_sdk_version_;
  }

  void SetMinSdkVersion(int minSdk) {
    min_sdk_version_ = minSdk;
  }

 private:
  DISALLOW_COPY_AND_ASSIGN(LinkContext);

  PackageType package_type_ = PackageType::kApp;
  IDiagnostics* diagnostics_;
  NameMangler name_mangler_;
  std::string compilation_package_;
  uint8_t package_id_ = 0x0;
  SymbolTable symbols_;
  bool verbose_ = false;
  int min_sdk_version_ = 0;
};

// A custom delegate that generates compatible pre-O IDs for use with feature splits.
// Feature splits use package IDs > 7f, which in Java (since Java doesn't have unsigned ints)
// is interpreted as a negative number. Some verification was wrongly assuming negative values
// were invalid.
//
// This delegate will attempt to masquerade any '@id/' references with ID 0xPPTTEEEE,
// where PP > 7f, as 0x7fPPEEEE. Any potential overlapping is verified and an error occurs if such
// an overlap exists.
class FeatureSplitSymbolTableDelegate : public DefaultSymbolTableDelegate {
 public:
  FeatureSplitSymbolTableDelegate(IAaptContext* context) : context_(context) {
  }

  virtual ~FeatureSplitSymbolTableDelegate() = default;

  virtual std::unique_ptr<SymbolTable::Symbol> FindByName(
      const ResourceName& name,
      const std::vector<std::unique_ptr<ISymbolSource>>& sources) override {
    std::unique_ptr<SymbolTable::Symbol> symbol =
        DefaultSymbolTableDelegate::FindByName(name, sources);
    if (symbol == nullptr) {
      return {};
    }

    // Check to see if this is an 'id' with the target package.
    if (name.type == ResourceType::kId && symbol->id) {
      ResourceId* id = &symbol->id.value();
      if (id->package_id() > kAppPackageId) {
        // Rewrite the resource ID to be compatible pre-O.
        ResourceId rewritten_id(kAppPackageId, id->package_id(), id->entry_id());

        // Check that this doesn't overlap another resource.
        if (DefaultSymbolTableDelegate::FindById(rewritten_id, sources) != nullptr) {
          // The ID overlaps, so log a message (since this is a weird failure) and fail.
          context_->GetDiagnostics()->Error(DiagMessage() << "Failed to rewrite " << name
                                                          << " for pre-O feature split support");
          return {};
        }

        if (context_->IsVerbose()) {
          context_->GetDiagnostics()->Note(DiagMessage() << "rewriting " << name << " (" << *id
                                                         << ") -> (" << rewritten_id << ")");
        }

        *id = rewritten_id;
      }
    }
    return symbol;
  }

 private:
  DISALLOW_COPY_AND_ASSIGN(FeatureSplitSymbolTableDelegate);

  IAaptContext* context_;
};

static bool FlattenXml(IAaptContext* context, xml::XmlResource* xml_res, const StringPiece& path,
                       bool keep_raw_values, IArchiveWriter* writer) {
  BigBuffer buffer(1024);
  XmlFlattenerOptions options = {};
  options.keep_raw_values = keep_raw_values;
  XmlFlattener flattener(&buffer, options);
  if (!flattener.Consume(context, xml_res)) {
    return false;
  }

  if (context->IsVerbose()) {
    context->GetDiagnostics()->Note(DiagMessage(path) << "writing to archive (keep_raw_values="
                                                      << (keep_raw_values ? "true" : "false")
                                                      << ")");
  }

  io::BigBufferInputStream input_stream(&buffer);
  return io::CopyInputStreamToArchive(context, &input_stream, path.to_string(),
                                      ArchiveEntry::kCompress, writer);
}

static std::unique_ptr<ResourceTable> LoadTableFromPb(const Source& source, const void* data,
                                                      size_t len, IDiagnostics* diag) {
  pb::ResourceTable pb_table;
  if (!pb_table.ParseFromArray(data, len)) {
    diag->Error(DiagMessage(source) << "invalid compiled table");
    return {};
  }

  std::unique_ptr<ResourceTable> table = DeserializeTableFromPb(pb_table, source, diag);
  if (!table) {
    return {};
  }
  return table;
}

/**
 * Inflates an XML file from the source path.
 */
static std::unique_ptr<xml::XmlResource> LoadXml(const std::string& path, IDiagnostics* diag) {
  std::ifstream fin(path, std::ifstream::binary);
  if (!fin) {
    diag->Error(DiagMessage(path) << strerror(errno));
    return {};
  }
  return xml::Inflate(&fin, diag, Source(path));
}

struct ResourceFileFlattenerOptions {
  bool no_auto_version = false;
  bool no_version_vectors = false;
  bool no_version_transitions = false;
  bool no_xml_namespaces = false;
  bool keep_raw_values = false;
  bool do_not_compress_anything = false;
  bool update_proguard_spec = false;
  std::unordered_set<std::string> extensions_to_not_compress;
};

// A sampling of public framework resource IDs.
struct R {
  struct attr {
    enum : uint32_t {
      paddingLeft = 0x010100d6u,
      paddingRight = 0x010100d8u,
      paddingHorizontal = 0x0101053du,

      paddingTop = 0x010100d7u,
      paddingBottom = 0x010100d9u,
      paddingVertical = 0x0101053eu,

      layout_marginLeft = 0x010100f7u,
      layout_marginRight = 0x010100f9u,
      layout_marginHorizontal = 0x0101053bu,

      layout_marginTop = 0x010100f8u,
      layout_marginBottom = 0x010100fau,
      layout_marginVertical = 0x0101053cu,
    };
  };
};

class ResourceFileFlattener {
 public:
  ResourceFileFlattener(const ResourceFileFlattenerOptions& options, IAaptContext* context,
                        proguard::KeepSet* keep_set);

  bool Flatten(ResourceTable* table, IArchiveWriter* archive_writer);

 private:
  struct FileOperation {
    ConfigDescription config;

    // The entry this file came from.
    ResourceEntry* entry;

    // The file to copy as-is.
    io::IFile* file_to_copy;

    // The XML to process and flatten.
    std::unique_ptr<xml::XmlResource> xml_to_flatten;

    // The destination to write this file to.
    std::string dst_path;
  };

  uint32_t GetCompressionFlags(const StringPiece& str);

  std::vector<std::unique_ptr<xml::XmlResource>> LinkAndVersionXmlFile(ResourceTable* table,
                                                                       FileOperation* file_op);

  ResourceFileFlattenerOptions options_;
  IAaptContext* context_;
  proguard::KeepSet* keep_set_;
  XmlCompatVersioner::Rules rules_;
};

ResourceFileFlattener::ResourceFileFlattener(const ResourceFileFlattenerOptions& options,
                                             IAaptContext* context, proguard::KeepSet* keep_set)
    : options_(options), context_(context), keep_set_(keep_set) {
  SymbolTable* symm = context_->GetExternalSymbols();

  // Build up the rules for degrading newer attributes to older ones.
  // NOTE(adamlesinski): These rules are hardcoded right now, but they should be
  // generated from the attribute definitions themselves (b/62028956).
  if (const SymbolTable::Symbol* s = symm->FindById(R::attr::paddingHorizontal)) {
    std::vector<ReplacementAttr> replacements{
        {"paddingLeft", R::attr::paddingLeft,
         Attribute(false, android::ResTable_map::TYPE_DIMENSION)},
        {"paddingRight", R::attr::paddingRight,
         Attribute(false, android::ResTable_map::TYPE_DIMENSION)},
    };
    rules_[R::attr::paddingHorizontal] =
        util::make_unique<DegradeToManyRule>(std::move(replacements));
  }

  if (const SymbolTable::Symbol* s = symm->FindById(R::attr::paddingVertical)) {
    std::vector<ReplacementAttr> replacements{
        {"paddingTop", R::attr::paddingTop,
         Attribute(false, android::ResTable_map::TYPE_DIMENSION)},
        {"paddingBottom", R::attr::paddingBottom,
         Attribute(false, android::ResTable_map::TYPE_DIMENSION)},
    };
    rules_[R::attr::paddingVertical] =
        util::make_unique<DegradeToManyRule>(std::move(replacements));
  }

  if (const SymbolTable::Symbol* s = symm->FindById(R::attr::layout_marginHorizontal)) {
    std::vector<ReplacementAttr> replacements{
        {"layout_marginLeft", R::attr::layout_marginLeft,
         Attribute(false, android::ResTable_map::TYPE_DIMENSION)},
        {"layout_marginRight", R::attr::layout_marginRight,
         Attribute(false, android::ResTable_map::TYPE_DIMENSION)},
    };
    rules_[R::attr::layout_marginHorizontal] =
        util::make_unique<DegradeToManyRule>(std::move(replacements));
  }

  if (const SymbolTable::Symbol* s = symm->FindById(R::attr::layout_marginVertical)) {
    std::vector<ReplacementAttr> replacements{
        {"layout_marginTop", R::attr::layout_marginTop,
         Attribute(false, android::ResTable_map::TYPE_DIMENSION)},
        {"layout_marginBottom", R::attr::layout_marginBottom,
         Attribute(false, android::ResTable_map::TYPE_DIMENSION)},
    };
    rules_[R::attr::layout_marginVertical] =
        util::make_unique<DegradeToManyRule>(std::move(replacements));
  }
}

uint32_t ResourceFileFlattener::GetCompressionFlags(const StringPiece& str) {
  if (options_.do_not_compress_anything) {
    return 0;
  }

  for (const std::string& extension : options_.extensions_to_not_compress) {
    if (util::EndsWith(str, extension)) {
      return 0;
    }
  }
  return ArchiveEntry::kCompress;
}

static bool IsTransitionElement(const std::string& name) {
  return name == "fade" || name == "changeBounds" || name == "slide" || name == "explode" ||
         name == "changeImageTransform" || name == "changeTransform" ||
         name == "changeClipBounds" || name == "autoTransition" || name == "recolor" ||
         name == "changeScroll" || name == "transitionSet" || name == "transition" ||
         name == "transitionManager";
}

static bool IsVectorElement(const std::string& name) {
  return name == "vector" || name == "animated-vector" || name == "pathInterpolator" ||
         name == "objectAnimator";
}

template <typename T>
std::vector<T> make_singleton_vec(T&& val) {
  std::vector<T> vec;
  vec.emplace_back(std::forward<T>(val));
  return vec;
}

std::vector<std::unique_ptr<xml::XmlResource>> ResourceFileFlattener::LinkAndVersionXmlFile(
    ResourceTable* table, FileOperation* file_op) {
  xml::XmlResource* doc = file_op->xml_to_flatten.get();
  const Source& src = doc->file.source;

  if (context_->IsVerbose()) {
    context_->GetDiagnostics()->Note(DiagMessage() << "linking " << src.path);
  }

  XmlReferenceLinker xml_linker;
  if (!xml_linker.Consume(context_, doc)) {
    return {};
  }

  if (options_.update_proguard_spec && !proguard::CollectProguardRules(src, doc, keep_set_)) {
    return {};
  }

  if (options_.no_xml_namespaces) {
    XmlNamespaceRemover namespace_remover;
    if (!namespace_remover.Consume(context_, doc)) {
      return {};
    }
  }

  if (options_.no_auto_version) {
    return make_singleton_vec(std::move(file_op->xml_to_flatten));
  }

  if (options_.no_version_vectors || options_.no_version_transitions) {
    // Skip this if it is a vector or animated-vector.
    xml::Element* el = xml::FindRootElement(doc);
    if (el && el->namespace_uri.empty()) {
      if ((options_.no_version_vectors && IsVectorElement(el->name)) ||
          (options_.no_version_transitions && IsTransitionElement(el->name))) {
        return make_singleton_vec(std::move(file_op->xml_to_flatten));
      }
    }
  }

  const ConfigDescription& config = file_op->config;
  ResourceEntry* entry = file_op->entry;

  XmlCompatVersioner xml_compat_versioner(&rules_);
  const util::Range<ApiVersion> api_range{config.sdkVersion,
                                          FindNextApiVersionForConfig(entry, config)};
  return xml_compat_versioner.Process(context_, doc, api_range);
}

bool ResourceFileFlattener::Flatten(ResourceTable* table, IArchiveWriter* archive_writer) {
  bool error = false;
  std::map<std::pair<ConfigDescription, StringPiece>, FileOperation> config_sorted_files;

  for (auto& pkg : table->packages) {
    for (auto& type : pkg->types) {
      // Sort by config and name, so that we get better locality in the zip file.
      config_sorted_files.clear();
      std::queue<FileOperation> file_operations;

      // Populate the queue with all files in the ResourceTable.
      for (auto& entry : type->entries) {
        for (auto& config_value : entry->values) {
          // WARNING! Do not insert or remove any resources while executing in this scope. It will
          // corrupt the iteration order.

          FileReference* file_ref = ValueCast<FileReference>(config_value->value.get());
          if (!file_ref) {
            continue;
          }

          io::IFile* file = file_ref->file;
          if (!file) {
            context_->GetDiagnostics()->Error(DiagMessage(file_ref->GetSource())
                                              << "file not found");
            return false;
          }

          FileOperation file_op;
          file_op.entry = entry.get();
          file_op.dst_path = *file_ref->path;
          file_op.config = config_value->config;
          file_op.file_to_copy = file;

          const StringPiece src_path = file->GetSource().path;
          if (type->type != ResourceType::kRaw &&
              (util::EndsWith(src_path, ".xml.flat") || util::EndsWith(src_path, ".xml"))) {
            std::unique_ptr<io::IData> data = file->OpenAsData();
            if (!data) {
              context_->GetDiagnostics()->Error(DiagMessage(file->GetSource())
                                                << "failed to open file");
              return false;
            }

            file_op.xml_to_flatten = xml::Inflate(data->data(), data->size(),
                                                  context_->GetDiagnostics(), file->GetSource());

            if (!file_op.xml_to_flatten) {
              return false;
            }

            file_op.xml_to_flatten->file.config = config_value->config;
            file_op.xml_to_flatten->file.source = file_ref->GetSource();
            file_op.xml_to_flatten->file.name = ResourceName(pkg->name, type->type, entry->name);
          }

          // NOTE(adamlesinski): Explicitly construct a StringPiece here, or
          // else we end up copying the string in the std::make_pair() method,
          // then creating a StringPiece from the copy, which would cause us
          // to end up referencing garbage in the map.
          const StringPiece entry_name(entry->name);
          config_sorted_files[std::make_pair(config_value->config, entry_name)] =
              std::move(file_op);
        }
      }

      // Now flatten the sorted values.
      for (auto& map_entry : config_sorted_files) {
        const ConfigDescription& config = map_entry.first.first;
        FileOperation& file_op = map_entry.second;

        if (file_op.xml_to_flatten) {
          std::vector<std::unique_ptr<xml::XmlResource>> versioned_docs =
              LinkAndVersionXmlFile(table, &file_op);
          for (std::unique_ptr<xml::XmlResource>& doc : versioned_docs) {
            std::string dst_path = file_op.dst_path;
            if (doc->file.config != file_op.config) {
              // Only add the new versioned configurations.
              if (context_->IsVerbose()) {
                context_->GetDiagnostics()->Note(DiagMessage(doc->file.source)
                                                 << "auto-versioning resource from config '"
                                                 << config << "' -> '" << doc->file.config << "'");
              }

              dst_path =
                  ResourceUtils::BuildResourceFileName(doc->file, context_->GetNameMangler());
              bool result = table->AddFileReferenceAllowMangled(doc->file.name, doc->file.config,
                                                                doc->file.source, dst_path, nullptr,
                                                                context_->GetDiagnostics());
              if (!result) {
                return false;
              }
            }
            error |= !FlattenXml(context_, doc.get(), dst_path, options_.keep_raw_values,
                                 archive_writer);
          }
        } else {
          error |= !io::CopyFileToArchive(context_, file_op.file_to_copy, file_op.dst_path,
                                          GetCompressionFlags(file_op.dst_path), archive_writer);
        }
      }
    }
  }
  return !error;
}

static bool WriteStableIdMapToPath(IDiagnostics* diag,
                                   const std::unordered_map<ResourceName, ResourceId>& id_map,
                                   const std::string& id_map_path) {
  std::ofstream fout(id_map_path, std::ofstream::binary);
  if (!fout) {
    diag->Error(DiagMessage(id_map_path) << strerror(errno));
    return false;
  }

  for (const auto& entry : id_map) {
    const ResourceName& name = entry.first;
    const ResourceId& id = entry.second;
    fout << name << " = " << id << "\n";
  }

  if (!fout) {
    diag->Error(DiagMessage(id_map_path) << "failed writing to file: "
                                         << android::base::SystemErrorCodeToString(errno));
    return false;
  }

  return true;
}

static bool LoadStableIdMap(IDiagnostics* diag, const std::string& path,
                            std::unordered_map<ResourceName, ResourceId>* out_id_map) {
  std::string content;
  if (!android::base::ReadFileToString(path, &content)) {
    diag->Error(DiagMessage(path) << "failed reading stable ID file");
    return false;
  }

  out_id_map->clear();
  size_t line_no = 0;
  for (StringPiece line : util::Tokenize(content, '\n')) {
    line_no++;
    line = util::TrimWhitespace(line);
    if (line.empty()) {
      continue;
    }

    auto iter = std::find(line.begin(), line.end(), '=');
    if (iter == line.end()) {
      diag->Error(DiagMessage(Source(path, line_no)) << "missing '='");
      return false;
    }

    ResourceNameRef name;
    StringPiece res_name_str =
        util::TrimWhitespace(line.substr(0, std::distance(line.begin(), iter)));
    if (!ResourceUtils::ParseResourceName(res_name_str, &name)) {
      diag->Error(DiagMessage(Source(path, line_no)) << "invalid resource name '" << res_name_str
                                                     << "'");
      return false;
    }

    const size_t res_id_start_idx = std::distance(line.begin(), iter) + 1;
    const size_t res_id_str_len = line.size() - res_id_start_idx;
    StringPiece res_id_str = util::TrimWhitespace(line.substr(res_id_start_idx, res_id_str_len));

    Maybe<ResourceId> maybe_id = ResourceUtils::ParseResourceId(res_id_str);
    if (!maybe_id) {
      diag->Error(DiagMessage(Source(path, line_no)) << "invalid resource ID '" << res_id_str
                                                     << "'");
      return false;
    }

    (*out_id_map)[name.ToResourceName()] = maybe_id.value();
  }
  return true;
}

class LinkCommand {
 public:
  LinkCommand(LinkContext* context, const LinkOptions& options)
      : options_(options),
        context_(context),
        final_table_(),
        file_collection_(util::make_unique<io::FileCollection>()) {
  }

  /**
   * Creates a SymbolTable that loads symbols from the various APKs and caches
   * the results for faster lookup.
   */
  bool LoadSymbolsFromIncludePaths() {
    std::unique_ptr<AssetManagerSymbolSource> asset_source =
        util::make_unique<AssetManagerSymbolSource>();
    for (const std::string& path : options_.include_paths) {
      if (context_->IsVerbose()) {
        context_->GetDiagnostics()->Note(DiagMessage(path) << "loading include path");
      }

      // First try to load the file as a static lib.
      std::string error_str;
      std::unique_ptr<ResourceTable> include_static = LoadStaticLibrary(path, &error_str);
      if (include_static) {
        if (context_->GetPackageType() != PackageType::kStaticLib) {
          // Can't include static libraries when not building a static library (they have no IDs
          // assigned).
          context_->GetDiagnostics()->Error(
              DiagMessage(path) << "can't include static library when not building a static lib");
          return false;
        }

        // If we are using --no-static-lib-packages, we need to rename the
        // package of this table to our compilation package.
        if (options_.no_static_lib_packages) {
          // Since package names can differ, and multiple packages can exist in a ResourceTable,
          // we place the requirement that all static libraries are built with the package
          // ID 0x7f. So if one is not found, this is an error.
          if (ResourceTablePackage* pkg = include_static->FindPackageById(kAppPackageId)) {
            pkg->name = context_->GetCompilationPackage();
          } else {
            context_->GetDiagnostics()->Error(DiagMessage(path)
                                              << "no package with ID 0x7f found in static library");
            return false;
          }
        }

        context_->GetExternalSymbols()->AppendSource(
            util::make_unique<ResourceTableSymbolSource>(include_static.get()));

        static_table_includes_.push_back(std::move(include_static));

      } else if (!error_str.empty()) {
        // We had an error with reading, so fail.
        context_->GetDiagnostics()->Error(DiagMessage(path) << error_str);
        return false;
      }

      if (!asset_source->AddAssetPath(path)) {
        context_->GetDiagnostics()->Error(DiagMessage(path) << "failed to load include path");
        return false;
      }
    }

    // Capture the shared libraries so that the final resource table can be properly flattened
    // with support for shared libraries.
    for (auto& entry : asset_source->GetAssignedPackageIds()) {
      if (entry.first > kFrameworkPackageId && entry.first < kAppPackageId) {
        final_table_.included_packages_[entry.first] = entry.second;
      }
    }

    context_->GetExternalSymbols()->AppendSource(std::move(asset_source));
    return true;
  }

  Maybe<AppInfo> ExtractAppInfoFromManifest(xml::XmlResource* xml_res, IDiagnostics* diag) {
    // Make sure the first element is <manifest> with package attribute.
    xml::Element* manifest_el = xml::FindRootElement(xml_res->root.get());
    if (manifest_el == nullptr) {
      return {};
    }

    AppInfo app_info;

    if (!manifest_el->namespace_uri.empty() || manifest_el->name != "manifest") {
      diag->Error(DiagMessage(xml_res->file.source) << "root tag must be <manifest>");
      return {};
    }

    xml::Attribute* package_attr = manifest_el->FindAttribute({}, "package");
    if (!package_attr) {
      diag->Error(DiagMessage(xml_res->file.source)
                  << "<manifest> must have a 'package' attribute");
      return {};
    }
    app_info.package = package_attr->value;

    if (xml::Attribute* version_code_attr =
            manifest_el->FindAttribute(xml::kSchemaAndroid, "versionCode")) {
      Maybe<uint32_t> maybe_code = ResourceUtils::ParseInt(version_code_attr->value);
      if (!maybe_code) {
        diag->Error(DiagMessage(xml_res->file.source.WithLine(manifest_el->line_number))
                    << "invalid android:versionCode '" << version_code_attr->value << "'");
        return {};
      }
      app_info.version_code = maybe_code.value();
    }

    if (xml::Attribute* revision_code_attr =
            manifest_el->FindAttribute(xml::kSchemaAndroid, "revisionCode")) {
      Maybe<uint32_t> maybe_code = ResourceUtils::ParseInt(revision_code_attr->value);
      if (!maybe_code) {
        diag->Error(DiagMessage(xml_res->file.source.WithLine(manifest_el->line_number))
                    << "invalid android:revisionCode '" << revision_code_attr->value << "'");
        return {};
      }
      app_info.revision_code = maybe_code.value();
    }

    if (xml::Attribute* split_name_attr = manifest_el->FindAttribute({}, "split")) {
      if (!split_name_attr->value.empty()) {
        app_info.split_name = split_name_attr->value;
      }
    }

    if (xml::Element* uses_sdk_el = manifest_el->FindChild({}, "uses-sdk")) {
      if (xml::Attribute* min_sdk =
              uses_sdk_el->FindAttribute(xml::kSchemaAndroid, "minSdkVersion")) {
        app_info.min_sdk_version = ResourceUtils::ParseSdkVersion(min_sdk->value);
      }
    }
    return app_info;
  }

  /**
   * Precondition: ResourceTable doesn't have any IDs assigned yet, nor is it linked.
   * Postcondition: ResourceTable has only one package left. All others are
   * stripped, or there is an error and false is returned.
   */
  bool VerifyNoExternalPackages() {
    auto is_ext_package_func = [&](const std::unique_ptr<ResourceTablePackage>& pkg) -> bool {
      return context_->GetCompilationPackage() != pkg->name || !pkg->id ||
             pkg->id.value() != context_->GetPackageId();
    };

    bool error = false;
    for (const auto& package : final_table_.packages) {
      if (is_ext_package_func(package)) {
        // We have a package that is not related to the one we're building!
        for (const auto& type : package->types) {
          for (const auto& entry : type->entries) {
            ResourceNameRef res_name(package->name, type->type, entry->name);

            for (const auto& config_value : entry->values) {
              // Special case the occurrence of an ID that is being generated
              // for the 'android' package. This is due to legacy reasons.
              if (ValueCast<Id>(config_value->value.get()) && package->name == "android") {
                context_->GetDiagnostics()->Warn(DiagMessage(config_value->value->GetSource())
                                                 << "generated id '" << res_name
                                                 << "' for external package '" << package->name
                                                 << "'");
              } else {
                context_->GetDiagnostics()->Error(DiagMessage(config_value->value->GetSource())
                                                  << "defined resource '" << res_name
                                                  << "' for external package '" << package->name
                                                  << "'");
                error = true;
              }
            }
          }
        }
      }
    }

    auto new_end_iter = std::remove_if(final_table_.packages.begin(), final_table_.packages.end(),
                                       is_ext_package_func);
    final_table_.packages.erase(new_end_iter, final_table_.packages.end());
    return !error;
  }

  /**
   * Returns true if no IDs have been set, false otherwise.
   */
  bool VerifyNoIdsSet() {
    for (const auto& package : final_table_.packages) {
      for (const auto& type : package->types) {
        if (type->id) {
          context_->GetDiagnostics()->Error(DiagMessage() << "type " << type->type << " has ID "
                                                          << StringPrintf("%02x", type->id.value())
                                                          << " assigned");
          return false;
        }

        for (const auto& entry : type->entries) {
          if (entry->id) {
            ResourceNameRef res_name(package->name, type->type, entry->name);
            context_->GetDiagnostics()->Error(
                DiagMessage() << "entry " << res_name << " has ID "
                              << StringPrintf("%02x", entry->id.value()) << " assigned");
            return false;
          }
        }
      }
    }
    return true;
  }

  std::unique_ptr<IArchiveWriter> MakeArchiveWriter(const StringPiece& out) {
    if (options_.output_to_directory) {
      return CreateDirectoryArchiveWriter(context_->GetDiagnostics(), out);
    } else {
      return CreateZipFileArchiveWriter(context_->GetDiagnostics(), out);
    }
  }

  bool FlattenTable(ResourceTable* table, IArchiveWriter* writer) {
    BigBuffer buffer(1024);
    TableFlattener flattener(options_.table_flattener_options, &buffer);
    if (!flattener.Consume(context_, table)) {
      context_->GetDiagnostics()->Error(DiagMessage() << "failed to flatten resource table");
      return false;
    }

    io::BigBufferInputStream input_stream(&buffer);
    return io::CopyInputStreamToArchive(context_, &input_stream, "resources.arsc",
                                        ArchiveEntry::kAlign, writer);
  }

  bool FlattenTableToPb(ResourceTable* table, IArchiveWriter* writer) {
    std::unique_ptr<pb::ResourceTable> pb_table = SerializeTableToPb(table);
    return io::CopyProtoToArchive(context_, pb_table.get(), "resources.arsc.flat", 0, writer);
  }

  bool WriteJavaFile(ResourceTable* table, const StringPiece& package_name_to_generate,
                     const StringPiece& out_package, const JavaClassGeneratorOptions& java_options,
                     const Maybe<std::string> out_text_symbols_path = {}) {
    if (!options_.generate_java_class_path) {
      return true;
    }

    std::string out_path = options_.generate_java_class_path.value();
    file::AppendPath(&out_path, file::PackageToPath(out_package));
    if (!file::mkdirs(out_path)) {
      context_->GetDiagnostics()->Error(DiagMessage() << "failed to create directory '" << out_path
                                                      << "'");
      return false;
    }

    file::AppendPath(&out_path, "R.java");

    std::ofstream fout(out_path, std::ofstream::binary);
    if (!fout) {
      context_->GetDiagnostics()->Error(DiagMessage()
                                        << "failed writing to '" << out_path
                                        << "': " << android::base::SystemErrorCodeToString(errno));
      return false;
    }

    std::unique_ptr<std::ofstream> fout_text;
    if (out_text_symbols_path) {
      fout_text =
          util::make_unique<std::ofstream>(out_text_symbols_path.value(), std::ofstream::binary);
      if (!*fout_text) {
        context_->GetDiagnostics()->Error(
            DiagMessage() << "failed writing to '" << out_text_symbols_path.value()
                          << "': " << android::base::SystemErrorCodeToString(errno));
        return false;
      }
    }

    JavaClassGenerator generator(context_, table, java_options);
    if (!generator.Generate(package_name_to_generate, out_package, &fout, fout_text.get())) {
      context_->GetDiagnostics()->Error(DiagMessage(out_path) << generator.getError());
      return false;
    }

    if (!fout) {
      context_->GetDiagnostics()->Error(DiagMessage()
                                        << "failed writing to '" << out_path
                                        << "': " << android::base::SystemErrorCodeToString(errno));
    }
    return true;
  }

  bool WriteManifestJavaFile(xml::XmlResource* manifest_xml) {
    if (!options_.generate_java_class_path) {
      return true;
    }

    std::unique_ptr<ClassDefinition> manifest_class =
        GenerateManifestClass(context_->GetDiagnostics(), manifest_xml);

    if (!manifest_class) {
      // Something bad happened, but we already logged it, so exit.
      return false;
    }

    if (manifest_class->empty()) {
      // Empty Manifest class, no need to generate it.
      return true;
    }

    // Add any JavaDoc annotations to the generated class.
    for (const std::string& annotation : options_.javadoc_annotations) {
      std::string proper_annotation = "@";
      proper_annotation += annotation;
      manifest_class->GetCommentBuilder()->AppendComment(proper_annotation);
    }

    const std::string& package_utf8 = context_->GetCompilationPackage();

    std::string out_path = options_.generate_java_class_path.value();
    file::AppendPath(&out_path, file::PackageToPath(package_utf8));

    if (!file::mkdirs(out_path)) {
      context_->GetDiagnostics()->Error(DiagMessage() << "failed to create directory '" << out_path
                                                      << "'");
      return false;
    }

    file::AppendPath(&out_path, "Manifest.java");

    std::ofstream fout(out_path, std::ofstream::binary);
    if (!fout) {
      context_->GetDiagnostics()->Error(DiagMessage()
                                        << "failed writing to '" << out_path
                                        << "': " << android::base::SystemErrorCodeToString(errno));
      return false;
    }

    if (!ClassDefinition::WriteJavaFile(manifest_class.get(), package_utf8, true, &fout)) {
      context_->GetDiagnostics()->Error(DiagMessage()
                                        << "failed writing to '" << out_path
                                        << "': " << android::base::SystemErrorCodeToString(errno));
      return false;
    }
    return true;
  }

  bool WriteProguardFile(const Maybe<std::string>& out, const proguard::KeepSet& keep_set) {
    if (!out) {
      return true;
    }

    const std::string& out_path = out.value();
    std::ofstream fout(out_path, std::ofstream::binary);
    if (!fout) {
      context_->GetDiagnostics()->Error(DiagMessage()
                                        << "failed to open '" << out_path
                                        << "': " << android::base::SystemErrorCodeToString(errno));
      return false;
    }

    proguard::WriteKeepSet(&fout, keep_set);
    if (!fout) {
      context_->GetDiagnostics()->Error(DiagMessage()
                                        << "failed writing to '" << out_path
                                        << "': " << android::base::SystemErrorCodeToString(errno));
      return false;
    }
    return true;
  }

  std::unique_ptr<ResourceTable> LoadStaticLibrary(const std::string& input,
                                                   std::string* out_error) {
    std::unique_ptr<io::ZipFileCollection> collection =
        io::ZipFileCollection::Create(input, out_error);
    if (!collection) {
      return {};
    }
    return LoadTablePbFromCollection(collection.get());
  }

  std::unique_ptr<ResourceTable> LoadTablePbFromCollection(io::IFileCollection* collection) {
    io::IFile* file = collection->FindFile("resources.arsc.flat");
    if (!file) {
      return {};
    }

    std::unique_ptr<io::IData> data = file->OpenAsData();
    return LoadTableFromPb(file->GetSource(), data->data(), data->size(),
                           context_->GetDiagnostics());
  }

  bool MergeStaticLibrary(const std::string& input, bool override) {
    if (context_->IsVerbose()) {
      context_->GetDiagnostics()->Note(DiagMessage() << "merging static library " << input);
    }

    std::string error_str;
    std::unique_ptr<io::ZipFileCollection> collection =
        io::ZipFileCollection::Create(input, &error_str);
    if (!collection) {
      context_->GetDiagnostics()->Error(DiagMessage(input) << error_str);
      return false;
    }

    std::unique_ptr<ResourceTable> table = LoadTablePbFromCollection(collection.get());
    if (!table) {
      context_->GetDiagnostics()->Error(DiagMessage(input) << "invalid static library");
      return false;
    }

    ResourceTablePackage* pkg = table->FindPackageById(kAppPackageId);
    if (!pkg) {
      context_->GetDiagnostics()->Error(DiagMessage(input) << "static library has no package");
      return false;
    }

    bool result;
    if (options_.no_static_lib_packages) {
      // Merge all resources as if they were in the compilation package. This is
      // the old behavior of aapt.

      // Add the package to the set of --extra-packages so we emit an R.java for
      // each library package.
      if (!pkg->name.empty()) {
        options_.extra_java_packages.insert(pkg->name);
      }

      pkg->name = "";
      if (override) {
        result = table_merger_->MergeOverlay(Source(input), table.get(), collection.get());
      } else {
        result = table_merger_->Merge(Source(input), table.get(), collection.get());
      }

    } else {
      // This is the proper way to merge libraries, where the package name is
      // preserved and resource names are mangled.
      result =
          table_merger_->MergeAndMangle(Source(input), pkg->name, table.get(), collection.get());
    }

    if (!result) {
      return false;
    }

    // Make sure to move the collection into the set of IFileCollections.
    collections_.push_back(std::move(collection));
    return true;
  }

  bool MergeResourceTable(io::IFile* file, bool override) {
    if (context_->IsVerbose()) {
      context_->GetDiagnostics()->Note(DiagMessage() << "merging resource table "
                                                     << file->GetSource());
    }

    std::unique_ptr<io::IData> data = file->OpenAsData();
    if (!data) {
      context_->GetDiagnostics()->Error(DiagMessage(file->GetSource()) << "failed to open file");
      return false;
    }

    std::unique_ptr<ResourceTable> table =
        LoadTableFromPb(file->GetSource(), data->data(), data->size(), context_->GetDiagnostics());
    if (!table) {
      return false;
    }

    bool result = false;
    if (override) {
      result = table_merger_->MergeOverlay(file->GetSource(), table.get());
    } else {
      result = table_merger_->Merge(file->GetSource(), table.get());
    }
    return result;
  }

  bool MergeCompiledFile(io::IFile* file, ResourceFile* file_desc, bool override) {
    if (context_->IsVerbose()) {
      context_->GetDiagnostics()->Note(DiagMessage() << "merging '" << file_desc->name
                                                     << "' from compiled file "
                                                     << file->GetSource());
    }

    bool result = false;
    if (override) {
      result = table_merger_->MergeFileOverlay(*file_desc, file);
    } else {
      result = table_merger_->MergeFile(*file_desc, file);
    }

    if (!result) {
      return false;
    }

    // Add the exports of this file to the table.
    for (SourcedResourceName& exported_symbol : file_desc->exported_symbols) {
      if (exported_symbol.name.package.empty()) {
        exported_symbol.name.package = context_->GetCompilationPackage();
      }

      ResourceNameRef res_name = exported_symbol.name;

      Maybe<ResourceName> mangled_name =
          context_->GetNameMangler()->MangleName(exported_symbol.name);
      if (mangled_name) {
        res_name = mangled_name.value();
      }

      std::unique_ptr<Id> id = util::make_unique<Id>();
      id->SetSource(file_desc->source.WithLine(exported_symbol.line));
      bool result = final_table_.AddResourceAllowMangled(
          res_name, ConfigDescription::DefaultConfig(), std::string(), std::move(id),
          context_->GetDiagnostics());
      if (!result) {
        return false;
      }
    }
    return true;
  }

  /**
   * Takes a path to load as a ZIP file and merges the files within into the
   * master ResourceTable.
   * If override is true, conflicting resources are allowed to override each
   * other, in order of last seen.
   *
   * An io::IFileCollection is created from the ZIP file and added to the set of
   * io::IFileCollections that are open.
   */
  bool MergeArchive(const std::string& input, bool override) {
    if (context_->IsVerbose()) {
      context_->GetDiagnostics()->Note(DiagMessage() << "merging archive " << input);
    }

    std::string error_str;
    std::unique_ptr<io::ZipFileCollection> collection =
        io::ZipFileCollection::Create(input, &error_str);
    if (!collection) {
      context_->GetDiagnostics()->Error(DiagMessage(input) << error_str);
      return false;
    }

    bool error = false;
    for (auto iter = collection->Iterator(); iter->HasNext();) {
      if (!MergeFile(iter->Next(), override)) {
        error = true;
      }
    }

    // Make sure to move the collection into the set of IFileCollections.
    collections_.push_back(std::move(collection));
    return !error;
  }

  /**
   * Takes a path to load and merge into the master ResourceTable. If override
   * is true,
   * conflicting resources are allowed to override each other, in order of last
   * seen.
   *
   * If the file path ends with .flata, .jar, .jack, or .zip the file is treated
   * as ZIP archive
   * and the files within are merged individually.
   *
   * Otherwise the files is processed on its own.
   */
  bool MergePath(const std::string& path, bool override) {
    if (util::EndsWith(path, ".flata") || util::EndsWith(path, ".jar") ||
        util::EndsWith(path, ".jack") || util::EndsWith(path, ".zip")) {
      return MergeArchive(path, override);
    } else if (util::EndsWith(path, ".apk")) {
      return MergeStaticLibrary(path, override);
    }

    io::IFile* file = file_collection_->InsertFile(path);
    return MergeFile(file, override);
  }

  /**
   * Takes a file to load and merge into the master ResourceTable. If override
   * is true,
   * conflicting resources are allowed to override each other, in order of last
   * seen.
   *
   * If the file ends with .arsc.flat, then it is loaded as a ResourceTable and
   * merged into the
   * master ResourceTable. If the file ends with .flat, then it is treated like
   * a compiled file
   * and the header data is read and merged into the final ResourceTable.
   *
   * All other file types are ignored. This is because these files could be
   * coming from a zip,
   * where we could have other files like classes.dex.
   */
  bool MergeFile(io::IFile* file, bool override) {
    const Source& src = file->GetSource();
    if (util::EndsWith(src.path, ".arsc.flat")) {
      return MergeResourceTable(file, override);

    } else if (util::EndsWith(src.path, ".flat")) {
      // Try opening the file and looking for an Export header.
      std::unique_ptr<io::IData> data = file->OpenAsData();
      if (!data) {
        context_->GetDiagnostics()->Error(DiagMessage(src) << "failed to open");
        return false;
      }

      CompiledFileInputStream input_stream(data->data(), data->size());
      uint32_t num_files = 0;
      if (!input_stream.ReadLittleEndian32(&num_files)) {
        context_->GetDiagnostics()->Error(DiagMessage(src) << "failed read num files");
        return false;
      }

      for (uint32_t i = 0; i < num_files; i++) {
        pb::CompiledFile compiled_file;
        if (!input_stream.ReadCompiledFile(&compiled_file)) {
          context_->GetDiagnostics()->Error(DiagMessage(src)
                                            << "failed to read compiled file header");
          return false;
        }

        uint64_t offset, len;
        if (!input_stream.ReadDataMetaData(&offset, &len)) {
          context_->GetDiagnostics()->Error(DiagMessage(src) << "failed to read data meta data");
          return false;
        }

        std::unique_ptr<ResourceFile> resource_file = DeserializeCompiledFileFromPb(
            compiled_file, file->GetSource(), context_->GetDiagnostics());
        if (!resource_file) {
          return false;
        }

        if (!MergeCompiledFile(file->CreateFileSegment(offset, len), resource_file.get(),
                               override)) {
          return false;
        }
      }
      return true;
    } else if (util::EndsWith(src.path, ".xml") || util::EndsWith(src.path, ".png")) {
      // Since AAPT compiles these file types and appends .flat to them, seeing
      // their raw extensions is a sign that they weren't compiled.
      const StringPiece file_type = util::EndsWith(src.path, ".xml") ? "XML" : "PNG";
      context_->GetDiagnostics()->Error(DiagMessage(src) << "uncompiled " << file_type
                                                         << " file passed as argument. Must be "
                                                            "compiled first into .flat file.");
      return false;
    }

    // Ignore non .flat files. This could be classes.dex or something else that
    // happens
    // to be in an archive.
    return true;
  }

  bool CopyAssetsDirsToApk(IArchiveWriter* writer) {
    std::map<std::string, std::unique_ptr<io::RegularFile>> merged_assets;
    for (const std::string& assets_dir : options_.assets_dirs) {
      Maybe<std::vector<std::string>> files =
          file::FindFiles(assets_dir, context_->GetDiagnostics(), nullptr);
      if (!files) {
        return false;
      }

      for (const std::string& file : files.value()) {
        std::string full_key = "assets/" + file;
        std::string full_path = assets_dir;
        file::AppendPath(&full_path, file);

        auto iter = merged_assets.find(full_key);
        if (iter == merged_assets.end()) {
          merged_assets.emplace(std::move(full_key),
                                util::make_unique<io::RegularFile>(Source(std::move(full_path))));
        } else if (context_->IsVerbose()) {
          context_->GetDiagnostics()->Warn(DiagMessage(iter->second->GetSource())
                                           << "asset file overrides '" << full_path << "'");
        }
      }
    }

    for (auto& entry : merged_assets) {
      uint32_t compression_flags = ArchiveEntry::kCompress;
      std::string extension = file::GetExtension(entry.first).to_string();
      if (options_.extensions_to_not_compress.count(extension) > 0) {
        compression_flags = 0u;
      }

      if (!io::CopyFileToArchive(context_, entry.second.get(), entry.first, compression_flags,
                                 writer)) {
        return false;
      }
    }
    return true;
  }

  /**
   * Writes the AndroidManifest, ResourceTable, and all XML files referenced by
   * the ResourceTable to the IArchiveWriter.
   */
  bool WriteApk(IArchiveWriter* writer, proguard::KeepSet* keep_set, xml::XmlResource* manifest,
                ResourceTable* table) {
    const bool keep_raw_values = context_->GetPackageType() == PackageType::kStaticLib;
    bool result = FlattenXml(context_, manifest, "AndroidManifest.xml", keep_raw_values, writer);
    if (!result) {
      return false;
    }

    ResourceFileFlattenerOptions file_flattener_options;
    file_flattener_options.keep_raw_values = keep_raw_values;
    file_flattener_options.do_not_compress_anything = options_.do_not_compress_anything;
    file_flattener_options.extensions_to_not_compress = options_.extensions_to_not_compress;
    file_flattener_options.no_auto_version = options_.no_auto_version;
    file_flattener_options.no_version_vectors = options_.no_version_vectors;
    file_flattener_options.no_version_transitions = options_.no_version_transitions;
    file_flattener_options.no_xml_namespaces = options_.no_xml_namespaces;
    file_flattener_options.update_proguard_spec =
        static_cast<bool>(options_.generate_proguard_rules_path);

    ResourceFileFlattener file_flattener(file_flattener_options, context_, keep_set);

    if (!file_flattener.Flatten(table, writer)) {
      context_->GetDiagnostics()->Error(DiagMessage() << "failed linking file resources");
      return false;
    }

    if (context_->GetPackageType() == PackageType::kStaticLib) {
      if (!FlattenTableToPb(table, writer)) {
        return false;
      }
    } else {
      if (!FlattenTable(table, writer)) {
        context_->GetDiagnostics()->Error(DiagMessage() << "failed to write resources.arsc");
        return false;
      }
    }
    return true;
  }

  int Run(const std::vector<std::string>& input_files) {
    // Load the AndroidManifest.xml
    std::unique_ptr<xml::XmlResource> manifest_xml =
        LoadXml(options_.manifest_path, context_->GetDiagnostics());
    if (!manifest_xml) {
      return 1;
    }

    // First extract the Package name without modifying it (via --rename-manifest-package).
    if (Maybe<AppInfo> maybe_app_info =
            ExtractAppInfoFromManifest(manifest_xml.get(), context_->GetDiagnostics())) {
      const AppInfo& app_info = maybe_app_info.value();
      context_->SetCompilationPackage(app_info.package);
    }

    ManifestFixer manifest_fixer(options_.manifest_fixer_options);
    if (!manifest_fixer.Consume(context_, manifest_xml.get())) {
      return 1;
    }

    Maybe<AppInfo> maybe_app_info =
        ExtractAppInfoFromManifest(manifest_xml.get(), context_->GetDiagnostics());
    if (!maybe_app_info) {
      return 1;
    }

    const AppInfo& app_info = maybe_app_info.value();
    context_->SetMinSdkVersion(app_info.min_sdk_version.value_or_default(0));

    context_->SetNameManglerPolicy(NameManglerPolicy{context_->GetCompilationPackage()});

    // Override the package ID when it is "android".
    if (context_->GetCompilationPackage() == "android") {
      context_->SetPackageId(0x01);

      // Verify we're building a regular app.
      if (context_->GetPackageType() != PackageType::kApp) {
        context_->GetDiagnostics()->Error(
            DiagMessage() << "package 'android' can only be built as a regular app");
        return 1;
      }
    }

    if (!LoadSymbolsFromIncludePaths()) {
      return 1;
    }

    TableMergerOptions table_merger_options;
    table_merger_options.auto_add_overlay = options_.auto_add_overlay;
    table_merger_ = util::make_unique<TableMerger>(context_, &final_table_, table_merger_options);

    if (context_->IsVerbose()) {
      context_->GetDiagnostics()->Note(DiagMessage()
                                       << StringPrintf("linking package '%s' using package ID %02x",
                                                       context_->GetCompilationPackage().data(),
                                                       context_->GetPackageId()));
    }

    for (const std::string& input : input_files) {
      if (!MergePath(input, false)) {
        context_->GetDiagnostics()->Error(DiagMessage() << "failed parsing input");
        return 1;
      }
    }

    for (const std::string& input : options_.overlay_files) {
      if (!MergePath(input, true)) {
        context_->GetDiagnostics()->Error(DiagMessage() << "failed parsing overlays");
        return 1;
      }
    }

    if (!VerifyNoExternalPackages()) {
      return 1;
    }

    if (context_->GetPackageType() != PackageType::kStaticLib) {
      PrivateAttributeMover mover;
      if (!mover.Consume(context_, &final_table_)) {
        context_->GetDiagnostics()->Error(DiagMessage() << "failed moving private attributes");
        return 1;
      }

      // Assign IDs if we are building a regular app.
      IdAssigner id_assigner(&options_.stable_id_map);
      if (!id_assigner.Consume(context_, &final_table_)) {
        context_->GetDiagnostics()->Error(DiagMessage() << "failed assigning IDs");
        return 1;
      }

      // Now grab each ID and emit it as a file.
      if (options_.resource_id_map_path) {
        for (auto& package : final_table_.packages) {
          for (auto& type : package->types) {
            for (auto& entry : type->entries) {
              ResourceName name(package->name, type->type, entry->name);
              // The IDs are guaranteed to exist.
              options_.stable_id_map[std::move(name)] =
                  ResourceId(package->id.value(), type->id.value(), entry->id.value());
            }
          }
        }

        if (!WriteStableIdMapToPath(context_->GetDiagnostics(), options_.stable_id_map,
                                    options_.resource_id_map_path.value())) {
          return 1;
        }
      }
    } else {
      // Static libs are merged with other apps, and ID collisions are bad, so
      // verify that
      // no IDs have been set.
      if (!VerifyNoIdsSet()) {
        return 1;
      }
    }

    // Add the names to mangle based on our source merge earlier.
    context_->SetNameManglerPolicy(
        NameManglerPolicy{context_->GetCompilationPackage(), table_merger_->merged_packages()});

    // Add our table to the symbol table.
    context_->GetExternalSymbols()->PrependSource(
        util::make_unique<ResourceTableSymbolSource>(&final_table_));

    // Workaround for pre-O runtime that would treat negative resource IDs
    // (any ID with a package ID > 7f) as invalid. Intercept any ID (PPTTEEEE) with PP > 0x7f
    // and type == 'id', and return the ID 0x7fPPEEEE. IDs don't need to be real resources, they
    // are just identifiers.
    if (context_->GetMinSdkVersion() < SDK_O && context_->GetPackageType() == PackageType::kApp) {
      if (context_->IsVerbose()) {
        context_->GetDiagnostics()->Note(DiagMessage()
                                         << "enabling pre-O feature split ID rewriting");
      }
      context_->GetExternalSymbols()->SetDelegate(
          util::make_unique<FeatureSplitSymbolTableDelegate>(context_));
    }

    ReferenceLinker linker;
    if (!linker.Consume(context_, &final_table_)) {
      context_->GetDiagnostics()->Error(DiagMessage() << "failed linking references");
      return 1;
    }

    if (context_->GetPackageType() == PackageType::kStaticLib) {
      if (!options_.products.empty()) {
        context_->GetDiagnostics()->Warn(DiagMessage()
                                         << "can't select products when building static library");
      }
    } else {
      ProductFilter product_filter(options_.products);
      if (!product_filter.Consume(context_, &final_table_)) {
        context_->GetDiagnostics()->Error(DiagMessage() << "failed stripping products");
        return 1;
      }
    }

    if (!options_.no_auto_version) {
      AutoVersioner versioner;
      if (!versioner.Consume(context_, &final_table_)) {
        context_->GetDiagnostics()->Error(DiagMessage() << "failed versioning styles");
        return 1;
      }
    }

    if (context_->GetPackageType() != PackageType::kStaticLib && context_->GetMinSdkVersion() > 0) {
      if (context_->IsVerbose()) {
        context_->GetDiagnostics()->Note(DiagMessage()
                                         << "collapsing resource versions for minimum SDK "
                                         << context_->GetMinSdkVersion());
      }

      VersionCollapser collapser;
      if (!collapser.Consume(context_, &final_table_)) {
        return 1;
      }
    }

    if (!options_.no_resource_deduping) {
      ResourceDeduper deduper;
      if (!deduper.Consume(context_, &final_table_)) {
        context_->GetDiagnostics()->Error(DiagMessage() << "failed deduping resources");
        return 1;
      }
    }

    proguard::KeepSet proguard_keep_set;
    proguard::KeepSet proguard_main_dex_keep_set;

    if (context_->GetPackageType() == PackageType::kStaticLib) {
      if (options_.table_splitter_options.config_filter != nullptr ||
          !options_.table_splitter_options.preferred_densities.empty()) {
        context_->GetDiagnostics()->Warn(DiagMessage()
                                         << "can't strip resources when building static library");
      }
    } else {
      // Adjust the SplitConstraints so that their SDK version is stripped if it is less than or
      // equal to the minSdk.
      options_.split_constraints =
          AdjustSplitConstraintsForMinSdk(context_->GetMinSdkVersion(), options_.split_constraints);

      TableSplitter table_splitter(options_.split_constraints, options_.table_splitter_options);
      if (!table_splitter.VerifySplitConstraints(context_)) {
        return 1;
      }
      table_splitter.SplitTable(&final_table_);

      // Now we need to write out the Split APKs.
      auto path_iter = options_.split_paths.begin();
      auto split_constraints_iter = options_.split_constraints.begin();
      for (std::unique_ptr<ResourceTable>& split_table : table_splitter.splits()) {
        if (context_->IsVerbose()) {
          context_->GetDiagnostics()->Note(DiagMessage(*path_iter)
                                           << "generating split with configurations '"
                                           << util::Joiner(split_constraints_iter->configs, ", ")
                                           << "'");
        }

        std::unique_ptr<IArchiveWriter> archive_writer = MakeArchiveWriter(*path_iter);
        if (!archive_writer) {
          context_->GetDiagnostics()->Error(DiagMessage() << "failed to create archive");
          return 1;
        }

        // Generate an AndroidManifest.xml for each split.
        std::unique_ptr<xml::XmlResource> split_manifest =
            GenerateSplitManifest(app_info, *split_constraints_iter);

        XmlReferenceLinker linker;
        if (!linker.Consume(context_, split_manifest.get())) {
          context_->GetDiagnostics()->Error(DiagMessage()
                                            << "failed to create Split AndroidManifest.xml");
          return 1;
        }

        if (!WriteApk(archive_writer.get(), &proguard_keep_set, split_manifest.get(),
                      split_table.get())) {
          return 1;
        }

        ++path_iter;
        ++split_constraints_iter;
      }
    }

    // Start writing the base APK.
    std::unique_ptr<IArchiveWriter> archive_writer = MakeArchiveWriter(options_.output_path);
    if (!archive_writer) {
      context_->GetDiagnostics()->Error(DiagMessage() << "failed to create archive");
      return 1;
    }

    bool error = false;
    {
      // AndroidManifest.xml has no resource name, but the CallSite is built
      // from the name
      // (aka, which package the AndroidManifest.xml is coming from).
      // So we give it a package name so it can see local resources.
      manifest_xml->file.name.package = context_->GetCompilationPackage();

      XmlReferenceLinker manifest_linker;
      if (manifest_linker.Consume(context_, manifest_xml.get())) {
        if (options_.generate_proguard_rules_path &&
            !proguard::CollectProguardRulesForManifest(Source(options_.manifest_path),
                                                       manifest_xml.get(), &proguard_keep_set)) {
          error = true;
        }

        if (options_.generate_main_dex_proguard_rules_path &&
            !proguard::CollectProguardRulesForManifest(Source(options_.manifest_path),
                                                       manifest_xml.get(),
                                                       &proguard_main_dex_keep_set, true)) {
          error = true;
        }

        if (options_.generate_java_class_path) {
          if (!WriteManifestJavaFile(manifest_xml.get())) {
            error = true;
          }
        }

        if (options_.no_xml_namespaces) {
          // PackageParser will fail if URIs are removed from
          // AndroidManifest.xml.
          XmlNamespaceRemover namespace_remover(true /* keepUris */);
          if (!namespace_remover.Consume(context_, manifest_xml.get())) {
            error = true;
          }
        }
      } else {
        error = true;
      }
    }

    if (error) {
      context_->GetDiagnostics()->Error(DiagMessage() << "failed processing manifest");
      return 1;
    }

    if (!WriteApk(archive_writer.get(), &proguard_keep_set, manifest_xml.get(), &final_table_)) {
      return 1;
    }

    if (!CopyAssetsDirsToApk(archive_writer.get())) {
      return 1;
    }

    if (options_.generate_java_class_path) {
      // The set of packages whose R class to call in the main classes
      // onResourcesLoaded callback.
      std::vector<std::string> packages_to_callback;

      JavaClassGeneratorOptions template_options;
      template_options.types = JavaClassGeneratorOptions::SymbolTypes::kAll;
      template_options.javadoc_annotations = options_.javadoc_annotations;

      if (context_->GetPackageType() == PackageType::kStaticLib ||
          options_.generate_non_final_ids) {
        template_options.use_final = false;
      }

      if (context_->GetPackageType() == PackageType::kSharedLib) {
        template_options.use_final = false;
        template_options.rewrite_callback_options = OnResourcesLoadedCallbackOptions{};
      }

      const StringPiece actual_package = context_->GetCompilationPackage();
      StringPiece output_package = context_->GetCompilationPackage();
      if (options_.custom_java_package) {
        // Override the output java package to the custom one.
        output_package = options_.custom_java_package.value();
      }

      // Generate the private symbols if required.
      if (options_.private_symbols) {
        packages_to_callback.push_back(options_.private_symbols.value());

        // If we defined a private symbols package, we only emit Public symbols
        // to the original package, and private and public symbols to the
        // private package.
        JavaClassGeneratorOptions options = template_options;
        options.types = JavaClassGeneratorOptions::SymbolTypes::kPublicPrivate;
        if (!WriteJavaFile(&final_table_, actual_package, options_.private_symbols.value(),
                           options)) {
          return 1;
        }
      }

      // Generate all the symbols for all extra packages.
      for (const std::string& extra_package : options_.extra_java_packages) {
        packages_to_callback.push_back(extra_package);

        JavaClassGeneratorOptions options = template_options;
        options.types = JavaClassGeneratorOptions::SymbolTypes::kAll;
        if (!WriteJavaFile(&final_table_, actual_package, extra_package, options)) {
          return 1;
        }
      }

      // Generate the main public R class.
      JavaClassGeneratorOptions options = template_options;

      // Only generate public symbols if we have a private package.
      if (options_.private_symbols) {
        options.types = JavaClassGeneratorOptions::SymbolTypes::kPublic;
      }

      if (options.rewrite_callback_options) {
        options.rewrite_callback_options.value().packages_to_callback =
            std::move(packages_to_callback);
      }

      if (!WriteJavaFile(&final_table_, actual_package, output_package, options,
                         options_.generate_text_symbols_path)) {
        return 1;
      }
    }

    if (!WriteProguardFile(options_.generate_proguard_rules_path, proguard_keep_set)) {
      return 1;
    }

    if (!WriteProguardFile(options_.generate_main_dex_proguard_rules_path,
                           proguard_main_dex_keep_set)) {
      return 1;
    }
    return 0;
  }

 private:
  LinkOptions options_;
  LinkContext* context_;
  ResourceTable final_table_;

  std::unique_ptr<TableMerger> table_merger_;

  // A pointer to the FileCollection representing the filesystem (not archives).
  std::unique_ptr<io::FileCollection> file_collection_;

  // A vector of IFileCollections. This is mainly here to keep ownership of the
  // collections.
  std::vector<std::unique_ptr<io::IFileCollection>> collections_;

  // A vector of ResourceTables. This is here to retain ownership, so that the
  // SymbolTable can use these.
  std::vector<std::unique_ptr<ResourceTable>> static_table_includes_;

  // The set of shared libraries being used, mapping their assigned package ID to package name.
  std::map<size_t, std::string> shared_libs_;
};

int Link(const std::vector<StringPiece>& args, IDiagnostics* diagnostics) {
  LinkContext context(diagnostics);
  LinkOptions options;
  std::vector<std::string> overlay_arg_list;
  std::vector<std::string> extra_java_packages;
  Maybe<std::string> package_id;
  std::vector<std::string> configs;
  Maybe<std::string> preferred_density;
  Maybe<std::string> product_list;
  bool legacy_x_flag = false;
  bool require_localization = false;
  bool verbose = false;
  bool shared_lib = false;
  bool static_lib = false;
  Maybe<std::string> stable_id_file_path;
  std::vector<std::string> split_args;
  Flags flags =
      Flags()
          .RequiredFlag("-o", "Output path.", &options.output_path)
          .RequiredFlag("--manifest", "Path to the Android manifest to build.",
                        &options.manifest_path)
          .OptionalFlagList("-I", "Adds an Android APK to link against.", &options.include_paths)
          .OptionalFlagList("-A",
                            "An assets directory to include in the APK. These are unprocessed.",
                            &options.assets_dirs)
          .OptionalFlagList("-R",
                            "Compilation unit to link, using `overlay` semantics.\n"
                            "The last conflicting resource given takes precedence.",
                            &overlay_arg_list)
          .OptionalFlag("--package-id",
                        "Specify the package ID to use for this app. Must be greater or equal to\n"
                        "0x7f and can't be used with --static-lib or --shared-lib.",
                        &package_id)
          .OptionalFlag("--java", "Directory in which to generate R.java.",
                        &options.generate_java_class_path)
          .OptionalFlag("--proguard", "Output file for generated Proguard rules.",
                        &options.generate_proguard_rules_path)
          .OptionalFlag("--proguard-main-dex",
                        "Output file for generated Proguard rules for the main dex.",
                        &options.generate_main_dex_proguard_rules_path)
          .OptionalSwitch("--no-auto-version",
                          "Disables automatic style and layout SDK versioning.",
                          &options.no_auto_version)
          .OptionalSwitch("--no-version-vectors",
                          "Disables automatic versioning of vector drawables. Use this only\n"
                          "when building with vector drawable support library.",
                          &options.no_version_vectors)
          .OptionalSwitch("--no-version-transitions",
                          "Disables automatic versioning of transition resources. Use this only\n"
                          "when building with transition support library.",
                          &options.no_version_transitions)
          .OptionalSwitch("--no-resource-deduping",
                          "Disables automatic deduping of resources with\n"
                          "identical values across compatible configurations.",
                          &options.no_resource_deduping)
          .OptionalSwitch("--enable-sparse-encoding",
                          "Enables encoding sparse entries using a binary search tree.\n"
                          "This decreases APK size at the cost of resource retrieval performance.",
                          &options.table_flattener_options.use_sparse_entries)
          .OptionalSwitch("-x", "Legacy flag that specifies to use the package identifier 0x01.",
                          &legacy_x_flag)
          .OptionalSwitch("-z", "Require localization of strings marked 'suggested'.",
                          &require_localization)
          .OptionalFlagList("-c",
                            "Comma separated list of configurations to include. The default\n"
                            "is all configurations.",
                            &configs)
          .OptionalFlag("--preferred-density",
                        "Selects the closest matching density and strips out all others.",
                        &preferred_density)
          .OptionalFlag("--product", "Comma separated list of product names to keep", &product_list)
          .OptionalSwitch("--output-to-dir",
                          "Outputs the APK contents to a directory specified by -o.",
                          &options.output_to_directory)
          .OptionalSwitch("--no-xml-namespaces",
                          "Removes XML namespace prefix and URI information from\n"
                          "AndroidManifest.xml and XML binaries in res/*.",
                          &options.no_xml_namespaces)
          .OptionalFlag("--min-sdk-version",
                        "Default minimum SDK version to use for AndroidManifest.xml.",
                        &options.manifest_fixer_options.min_sdk_version_default)
          .OptionalFlag("--target-sdk-version",
                        "Default target SDK version to use for AndroidManifest.xml.",
                        &options.manifest_fixer_options.target_sdk_version_default)
          .OptionalFlag("--version-code",
                        "Version code (integer) to inject into the AndroidManifest.xml if none is\n"
                        "present.",
                        &options.manifest_fixer_options.version_code_default)
          .OptionalFlag("--version-name",
                        "Version name to inject into the AndroidManifest.xml if none is present.",
                        &options.manifest_fixer_options.version_name_default)
          .OptionalSwitch("--shared-lib", "Generates a shared Android runtime library.",
                          &shared_lib)
          .OptionalSwitch("--static-lib", "Generate a static Android library.", &static_lib)
          .OptionalSwitch("--no-static-lib-packages",
                          "Merge all library resources under the app's package.",
                          &options.no_static_lib_packages)
          .OptionalSwitch("--non-final-ids",
                          "Generates R.java without the final modifier. This is implied when\n"
                          "--static-lib is specified.",
                          &options.generate_non_final_ids)
          .OptionalFlag("--stable-ids", "File containing a list of name to ID mapping.",
                        &stable_id_file_path)
          .OptionalFlag("--emit-ids",
                        "Emit a file at the given path with a list of name to ID mappings,\n"
                        "suitable for use with --stable-ids.",
                        &options.resource_id_map_path)
          .OptionalFlag("--private-symbols",
                        "Package name to use when generating R.java for private symbols.\n"
                        "If not specified, public and private symbols will use the application's\n"
                        "package name.",
                        &options.private_symbols)
          .OptionalFlag("--custom-package", "Custom Java package under which to generate R.java.",
                        &options.custom_java_package)
          .OptionalFlagList("--extra-packages",
                            "Generate the same R.java but with different package names.",
                            &extra_java_packages)
          .OptionalFlagList("--add-javadoc-annotation",
                            "Adds a JavaDoc annotation to all generated Java classes.",
                            &options.javadoc_annotations)
          .OptionalFlag("--output-text-symbols",
                        "Generates a text file containing the resource symbols of the R class in\n"
                        "the specified folder.",
                        &options.generate_text_symbols_path)
          .OptionalSwitch("--auto-add-overlay",
                          "Allows the addition of new resources in overlays without\n"
                          "<add-resource> tags.",
                          &options.auto_add_overlay)
          .OptionalFlag("--rename-manifest-package", "Renames the package in AndroidManifest.xml.",
                        &options.manifest_fixer_options.rename_manifest_package)
          .OptionalFlag("--rename-instrumentation-target-package",
                        "Changes the name of the target package for instrumentation. Most useful\n"
                        "when used in conjunction with --rename-manifest-package.",
                        &options.manifest_fixer_options.rename_instrumentation_target_package)
          .OptionalFlagList("-0", "File extensions not to compress.",
                            &options.extensions_to_not_compress)
          .OptionalFlagList("--split",
                            "Split resources matching a set of configs out to a Split APK.\n"
                            "Syntax: path/to/output.apk:<config>[,<config>[...]].\n"
                            "On Windows, use a semicolon ';' separator instead.",
                            &split_args)
          .OptionalSwitch("-v", "Enables verbose logging.", &verbose);

  if (!flags.Parse("aapt2 link", args, &std::cerr)) {
    return 1;
  }

  // Expand all argument-files passed into the command line. These start with '@'.
  std::vector<std::string> arg_list;
  for (const std::string& arg : flags.GetArgs()) {
    if (util::StartsWith(arg, "@")) {
      const std::string path = arg.substr(1, arg.size() - 1);
      std::string error;
      if (!file::AppendArgsFromFile(path, &arg_list, &error)) {
        context.GetDiagnostics()->Error(DiagMessage(path) << error);
        return 1;
      }
    } else {
      arg_list.push_back(arg);
    }
  }

  // Expand all argument-files passed to -R.
  for (const std::string& arg : overlay_arg_list) {
    if (util::StartsWith(arg, "@")) {
      const std::string path = arg.substr(1, arg.size() - 1);
      std::string error;
      if (!file::AppendArgsFromFile(path, &options.overlay_files, &error)) {
        context.GetDiagnostics()->Error(DiagMessage(path) << error);
        return 1;
      }
    } else {
      options.overlay_files.push_back(arg);
    }
  }

  if (verbose) {
    context.SetVerbose(verbose);
  }

  if (shared_lib && static_lib) {
    context.GetDiagnostics()->Error(DiagMessage()
                                    << "only one of --shared-lib and --static-lib can be defined");
    return 1;
  }

  if (shared_lib) {
    context.SetPackageType(PackageType::kSharedLib);
    context.SetPackageId(0x00);
  } else if (static_lib) {
    context.SetPackageType(PackageType::kStaticLib);
    context.SetPackageId(kAppPackageId);
  } else {
    context.SetPackageType(PackageType::kApp);
    context.SetPackageId(kAppPackageId);
  }

  if (package_id) {
    if (context.GetPackageType() != PackageType::kApp) {
      context.GetDiagnostics()->Error(
          DiagMessage() << "can't specify --package-id when not building a regular app");
      return 1;
    }

    const Maybe<uint32_t> maybe_package_id_int = ResourceUtils::ParseInt(package_id.value());
    if (!maybe_package_id_int) {
      context.GetDiagnostics()->Error(DiagMessage() << "package ID '" << package_id.value()
                                                    << "' is not a valid integer");
      return 1;
    }

    const uint32_t package_id_int = maybe_package_id_int.value();
    if (package_id_int < kAppPackageId || package_id_int > std::numeric_limits<uint8_t>::max()) {
      context.GetDiagnostics()->Error(
          DiagMessage() << StringPrintf(
              "invalid package ID 0x%02x. Must be in the range 0x7f-0xff.", package_id_int));
      return 1;
    }
    context.SetPackageId(static_cast<uint8_t>(package_id_int));
  }

  // Populate the set of extra packages for which to generate R.java.
  for (std::string& extra_package : extra_java_packages) {
    // A given package can actually be a colon separated list of packages.
    for (StringPiece package : util::Split(extra_package, ':')) {
      options.extra_java_packages.insert(package.to_string());
    }
  }

  if (product_list) {
    for (StringPiece product : util::Tokenize(product_list.value(), ',')) {
      if (product != "" && product != "default") {
        options.products.insert(product.to_string());
      }
    }
  }

  std::unique_ptr<IConfigFilter> filter;
  if (!configs.empty()) {
    filter = ParseConfigFilterParameters(configs, context.GetDiagnostics());
    if (filter == nullptr) {
      return 1;
    }
    options.table_splitter_options.config_filter = filter.get();
  }

  if (preferred_density) {
    Maybe<uint16_t> density =
        ParseTargetDensityParameter(preferred_density.value(), context.GetDiagnostics());
    if (!density) {
      return 1;
    }
    options.table_splitter_options.preferred_densities.push_back(density.value());
  }

  // Parse the split parameters.
  for (const std::string& split_arg : split_args) {
    options.split_paths.push_back({});
    options.split_constraints.push_back({});
    if (!ParseSplitParameter(split_arg, context.GetDiagnostics(), &options.split_paths.back(),
                             &options.split_constraints.back())) {
      return 1;
    }
  }

  if (context.GetPackageType() != PackageType::kStaticLib && stable_id_file_path) {
    if (!LoadStableIdMap(context.GetDiagnostics(), stable_id_file_path.value(),
                         &options.stable_id_map)) {
      return 1;
    }
  }

  // Populate some default no-compress extensions that are already compressed.
  options.extensions_to_not_compress.insert(
      {".jpg",   ".jpeg", ".png",  ".gif", ".wav",  ".mp2",  ".mp3",  ".ogg",
       ".aac",   ".mpg",  ".mpeg", ".mid", ".midi", ".smf",  ".jet",  ".rtttl",
       ".imy",   ".xmf",  ".mp4",  ".m4a", ".m4v",  ".3gp",  ".3gpp", ".3g2",
       ".3gpp2", ".amr",  ".awb",  ".wma", ".wmv",  ".webm", ".mkv"});

  // Turn off auto versioning for static-libs.
  if (context.GetPackageType() == PackageType::kStaticLib) {
    options.no_auto_version = true;
    options.no_version_vectors = true;
    options.no_version_transitions = true;
  }

  LinkCommand cmd(&context, options);
  return cmd.Run(arg_list);
}

}  // namespace aapt
