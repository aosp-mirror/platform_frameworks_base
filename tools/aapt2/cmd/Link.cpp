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

#include "Link.h"

#include <sys/stat.h>
#include <cinttypes>

#include <algorithm>
#include <queue>
#include <unordered_map>
#include <vector>

#include "android-base/errors.h"
#include "android-base/expected.h"
#include "android-base/file.h"
#include "android-base/stringprintf.h"
#include "androidfw/Locale.h"
#include "androidfw/StringPiece.h"

#include "AppInfo.h"
#include "Debug.h"
#include "LoadedApk.h"
#include "NameMangler.h"
#include "ResourceUtils.h"
#include "ResourceValues.h"
#include "ValueVisitor.h"
#include "cmd/Util.h"
#include "compile/IdAssigner.h"
#include "compile/XmlIdCollector.h"
#include "filter/ConfigFilter.h"
#include "format/Archive.h"
#include "format/Container.h"
#include "format/binary/TableFlattener.h"
#include "format/binary/XmlFlattener.h"
#include "format/proto/ProtoDeserialize.h"
#include "format/proto/ProtoSerialize.h"
#include "io/BigBufferStream.h"
#include "io/FileStream.h"
#include "io/FileSystem.h"
#include "io/Util.h"
#include "io/ZipArchive.h"
#include "java/JavaClassGenerator.h"
#include "java/ManifestClassGenerator.h"
#include "java/ProguardRules.h"
#include "link/Linkers.h"
#include "link/ManifestFixer.h"
#include "link/NoDefaultResourceRemover.h"
#include "link/ReferenceLinker.h"
#include "link/ResourceExcluder.h"
#include "link/TableMerger.h"
#include "link/XmlCompatVersioner.h"
#include "optimize/ResourceDeduper.h"
#include "optimize/VersionCollapser.h"
#include "process/IResourceTableConsumer.h"
#include "process/SymbolTable.h"
#include "split/TableSplitter.h"
#include "trace/TraceBuffer.h"
#include "util/Files.h"
#include "xml/XmlDom.h"

using ::aapt::io::FileInputStream;
using ::android::ConfigDescription;
using ::android::StringPiece;
using ::android::base::expected;
using ::android::base::StringPrintf;
using ::android::base::unexpected;

namespace aapt {

namespace {

expected<ResourceTablePackage*, const char*> GetStaticLibraryPackage(ResourceTable* table) {
  // Resource tables built by aapt2 always contain one package. This is a post condition of
  // VerifyNoExternalPackages.
  if (table->packages.size() != 1u) {
    return unexpected("static library contains more than one package");
  }
  return table->packages.back().get();
}

}  // namespace

constexpr uint8_t kAndroidPackageId = 0x01;

class LinkContext : public IAaptContext {
 public:
  explicit LinkContext(IDiagnostics* diagnostics)
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

  const std::set<std::string>& GetSplitNameDependencies() override {
    return split_name_dependencies_;
  }

  void SetSplitNameDependencies(const std::set<std::string>& split_name_dependencies) {
    split_name_dependencies_ = split_name_dependencies;
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
  std::set<std::string> split_name_dependencies_;
};

// A custom delegate that generates compatible pre-O IDs for use with feature splits.
// Feature splits use package IDs > 7f, which in Java (since Java doesn't have unsigned ints)
// is interpreted as a negative number. Some verification was wrongly assuming negative values
// were invalid.
//
// This delegate will attempt to masquerade any '@id/' references with ID 0xPPTTEEEE,
// where PP > 7f, as 0x7fPPEEEE. Any potential overlapping is verified and an error occurs if such
// an overlap exists.
//
// See b/37498913.
class FeatureSplitSymbolTableDelegate : public DefaultSymbolTableDelegate {
 public:
  explicit FeatureSplitSymbolTableDelegate(IAaptContext* context) : context_(context) {
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

static bool FlattenXml(IAaptContext* context, const xml::XmlResource& xml_res,
                       const StringPiece& path, bool keep_raw_values, bool utf16,
                       OutputFormat format, IArchiveWriter* writer) {
  TRACE_CALL();
  if (context->IsVerbose()) {
    context->GetDiagnostics()->Note(DiagMessage(path) << "writing to archive (keep_raw_values="
                                                      << (keep_raw_values ? "true" : "false")
                                                      << ")");
  }

  switch (format) {
    case OutputFormat::kApk: {
      BigBuffer buffer(1024);
      XmlFlattenerOptions options = {};
      options.keep_raw_values = keep_raw_values;
      options.use_utf16 = utf16;
      XmlFlattener flattener(&buffer, options);
      if (!flattener.Consume(context, &xml_res)) {
        return false;
      }

      io::BigBufferInputStream input_stream(&buffer);
      return io::CopyInputStreamToArchive(context, &input_stream, path.to_string(),
                                          ArchiveEntry::kCompress, writer);
    } break;

    case OutputFormat::kProto: {
      pb::XmlNode pb_node;
      // Strip whitespace text nodes from tha AndroidManifest.xml
      SerializeXmlOptions options;
      options.remove_empty_text_nodes = (path == kAndroidManifestPath);
      SerializeXmlResourceToPb(xml_res, &pb_node);
      return io::CopyProtoToArchive(context, &pb_node, path.to_string(), ArchiveEntry::kCompress,
                                    writer);
    } break;
  }
  return false;
}

// Inflates an XML file from the source path.
static std::unique_ptr<xml::XmlResource> LoadXml(const std::string& path, IDiagnostics* diag) {
  TRACE_CALL();
  FileInputStream fin(path);
  if (fin.HadError()) {
    diag->Error(DiagMessage(path) << "failed to load XML file: " << fin.GetError());
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
  bool do_not_fail_on_missing_resources = false;
  OutputFormat output_format = OutputFormat::kApk;
  std::unordered_set<std::string> extensions_to_not_compress;
  Maybe<std::regex> regex_to_not_compress;
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

template <typename T>
uint32_t GetCompressionFlags(const StringPiece& str, T options) {
  if (options.do_not_compress_anything) {
    return 0;
  }

  if (options.regex_to_not_compress
      && std::regex_search(str.to_string(), options.regex_to_not_compress.value())) {
    return 0;
  }

  for (const std::string& extension : options.extensions_to_not_compress) {
    if (util::EndsWith(str, extension)) {
      return 0;
    }
  }
  return ArchiveEntry::kCompress;
}

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
        {"paddingLeft", R::attr::paddingLeft, Attribute(android::ResTable_map::TYPE_DIMENSION)},
        {"paddingRight", R::attr::paddingRight, Attribute(android::ResTable_map::TYPE_DIMENSION)},
    };
    rules_[R::attr::paddingHorizontal] =
        util::make_unique<DegradeToManyRule>(std::move(replacements));
  }

  if (const SymbolTable::Symbol* s = symm->FindById(R::attr::paddingVertical)) {
    std::vector<ReplacementAttr> replacements{
        {"paddingTop", R::attr::paddingTop, Attribute(android::ResTable_map::TYPE_DIMENSION)},
        {"paddingBottom", R::attr::paddingBottom, Attribute(android::ResTable_map::TYPE_DIMENSION)},
    };
    rules_[R::attr::paddingVertical] =
        util::make_unique<DegradeToManyRule>(std::move(replacements));
  }

  if (const SymbolTable::Symbol* s = symm->FindById(R::attr::layout_marginHorizontal)) {
    std::vector<ReplacementAttr> replacements{
        {"layout_marginLeft", R::attr::layout_marginLeft,
         Attribute(android::ResTable_map::TYPE_DIMENSION)},
        {"layout_marginRight", R::attr::layout_marginRight,
         Attribute(android::ResTable_map::TYPE_DIMENSION)},
    };
    rules_[R::attr::layout_marginHorizontal] =
        util::make_unique<DegradeToManyRule>(std::move(replacements));
  }

  if (const SymbolTable::Symbol* s = symm->FindById(R::attr::layout_marginVertical)) {
    std::vector<ReplacementAttr> replacements{
        {"layout_marginTop", R::attr::layout_marginTop,
         Attribute(android::ResTable_map::TYPE_DIMENSION)},
        {"layout_marginBottom", R::attr::layout_marginBottom,
         Attribute(android::ResTable_map::TYPE_DIMENSION)},
    };
    rules_[R::attr::layout_marginVertical] =
        util::make_unique<DegradeToManyRule>(std::move(replacements));
  }
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
         name == "objectAnimator" || name == "gradient" || name == "animated-selector" ||
         name == "set";
}

template <typename T>
std::vector<T> make_singleton_vec(T&& val) {
  std::vector<T> vec;
  vec.emplace_back(std::forward<T>(val));
  return vec;
}

std::vector<std::unique_ptr<xml::XmlResource>> ResourceFileFlattener::LinkAndVersionXmlFile(
    ResourceTable* table, FileOperation* file_op) {
  TRACE_CALL();
  xml::XmlResource* doc = file_op->xml_to_flatten.get();
  const Source& src = doc->file.source;

  if (context_->IsVerbose()) {
    context_->GetDiagnostics()->Note(DiagMessage()
                                     << "linking " << src.path << " (" << doc->file.name << ")");
  }

  // First, strip out any tools namespace attributes. AAPT stripped them out early, which means
  // that existing projects have out-of-date references which pass compilation.
  xml::StripAndroidStudioAttributes(doc->root.get());

  XmlReferenceLinker xml_linker(table);
  if (!options_.do_not_fail_on_missing_resources && !xml_linker.Consume(context_, doc)) {
    return {};
  }

  if (options_.update_proguard_spec && !proguard::CollectProguardRules(context_, doc, keep_set_)) {
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
    xml::Element* el = doc->root.get();
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

ResourceFile::Type XmlFileTypeForOutputFormat(OutputFormat format) {
  switch (format) {
    case OutputFormat::kApk:
      return ResourceFile::Type::kBinaryXml;
    case OutputFormat::kProto:
      return ResourceFile::Type::kProtoXml;
  }
  LOG_ALWAYS_FATAL("unreachable");
  return ResourceFile::Type::kUnknown;
}

static auto kDrawableVersions = std::map<std::string, ApiVersion>{
    { "adaptive-icon" , SDK_O },
};

bool ResourceFileFlattener::Flatten(ResourceTable* table, IArchiveWriter* archive_writer) {
  TRACE_CALL();
  bool error = false;
  std::map<std::pair<ConfigDescription, StringPiece>, FileOperation> config_sorted_files;

  proguard::CollectResourceReferences(context_, table, keep_set_);

  for (auto& pkg : table->packages) {
    CHECK(!pkg->name.empty()) << "Packages must have names when being linked";

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

          if (type->type != ResourceType::kRaw &&
              (file_ref->type == ResourceFile::Type::kBinaryXml ||
               file_ref->type == ResourceFile::Type::kProtoXml)) {
            std::unique_ptr<io::IData> data = file->OpenAsData();
            if (!data) {
              context_->GetDiagnostics()->Error(DiagMessage(file->GetSource())
                                                << "failed to open file");
              return false;
            }

            if (file_ref->type == ResourceFile::Type::kProtoXml) {
              pb::XmlNode pb_xml_node;
              if (!pb_xml_node.ParseFromArray(data->data(), static_cast<int>(data->size()))) {
                context_->GetDiagnostics()->Error(DiagMessage(file->GetSource())
                                                  << "failed to parse proto XML");
                return false;
              }

              std::string error;
              file_op.xml_to_flatten = DeserializeXmlResourceFromPb(pb_xml_node, &error);
              if (file_op.xml_to_flatten == nullptr) {
                context_->GetDiagnostics()->Error(DiagMessage(file->GetSource())
                                                  << "failed to deserialize proto XML: " << error);
                return false;
              }
            } else {
              std::string error_str;
              file_op.xml_to_flatten = xml::Inflate(data->data(), data->size(), &error_str);
              if (file_op.xml_to_flatten == nullptr) {
                context_->GetDiagnostics()->Error(DiagMessage(file->GetSource())
                                                  << "failed to parse binary XML: " << error_str);
                return false;
              }
            }

            // Update the type that this file will be written as.
            file_ref->type = XmlFileTypeForOutputFormat(options_.output_format);

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
          // Check minimum sdk versions supported for drawables
          auto drawable_entry = kDrawableVersions.find(file_op.xml_to_flatten->root->name);
          if (drawable_entry != kDrawableVersions.end()) {
            if (drawable_entry->second > context_->GetMinSdkVersion()
                && drawable_entry->second > config.sdkVersion) {
              context_->GetDiagnostics()->Error(DiagMessage(file_op.xml_to_flatten->file.source)
                                                    << "<" << drawable_entry->first << "> elements "
                                                    << "require a sdk version of at least "
                                                    << (int16_t) drawable_entry->second);
              error = true;
              continue;
            }
          }

          std::vector<std::unique_ptr<xml::XmlResource>> versioned_docs =
              LinkAndVersionXmlFile(table, &file_op);
          if (versioned_docs.empty()) {
            error = true;
            continue;
          }

          for (std::unique_ptr<xml::XmlResource>& doc : versioned_docs) {
            std::string dst_path = file_op.dst_path;
            if (doc->file.config != file_op.config) {
              // Only add the new versioned configurations.
              if (context_->IsVerbose()) {
                context_->GetDiagnostics()->Note(DiagMessage(doc->file.source)
                                                 << "auto-versioning resource from config '"
                                                 << config << "' -> '" << doc->file.config << "'");
              }

              const ResourceFile& file = doc->file;
              dst_path = ResourceUtils::BuildResourceFileName(file, context_->GetNameMangler());

              auto file_ref =
                  util::make_unique<FileReference>(table->string_pool.MakeRef(dst_path));
              file_ref->SetSource(doc->file.source);

              // Update the output format of this XML file.
              file_ref->type = XmlFileTypeForOutputFormat(options_.output_format);
              bool result = table->AddResource(NewResourceBuilder(file.name)
                                                   .SetValue(std::move(file_ref), file.config)
                                                   .SetAllowMangled(true)
                                                   .Build(),
                                               context_->GetDiagnostics());
              if (!result) {
                return false;
              }
            }

            error |= !FlattenXml(context_, *doc, dst_path, options_.keep_raw_values,
                                 false /*utf16*/, options_.output_format, archive_writer);
          }
        } else {
          error |= !io::CopyFileToArchive(context_, file_op.file_to_copy, file_op.dst_path,
                                          GetCompressionFlags(file_op.dst_path, options_),
                                          archive_writer);
        }
      }
    }
  }
  return !error;
}

static bool WriteStableIdMapToPath(IDiagnostics* diag,
                                   const std::unordered_map<ResourceName, ResourceId>& id_map,
                                   const std::string& id_map_path) {
  io::FileOutputStream fout(id_map_path);
  if (fout.HadError()) {
    diag->Error(DiagMessage(id_map_path) << "failed to open: " << fout.GetError());
    return false;
  }

  text::Printer printer(&fout);
  for (const auto& entry : id_map) {
    const ResourceName& name = entry.first;
    const ResourceId& id = entry.second;
    printer.Print(name.to_string());
    printer.Print(" = ");
    printer.Println(id.to_string());
  }
  fout.Flush();

  if (fout.HadError()) {
    diag->Error(DiagMessage(id_map_path) << "failed writing to file: " << fout.GetError());
    return false;
  }
  return true;
}

static bool LoadStableIdMap(IDiagnostics* diag, const std::string& path,
                            std::unordered_map<ResourceName, ResourceId>* out_id_map) {
  std::string content;
  if (!android::base::ReadFileToString(path, &content, true /*follow_symlinks*/)) {
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

class Linker {
 public:
  Linker(LinkContext* context, const LinkOptions& options)
      : options_(options),
        context_(context),
        final_table_(),
        file_collection_(util::make_unique<io::FileCollection>()) {
  }

  void ExtractCompileSdkVersions(android::AssetManager2* assets) {
    using namespace android;

    // Find the system package (0x01). AAPT always generates attributes with the type 0x01, so
    // we're looking for the first attribute resource in the system package.
    android::ApkAssetsCookie cookie;
    if (auto value = assets->GetResource(0x01010000, true /** may_be_bag */); value.has_value()) {
      cookie = value->cookie;
    } else {
      // No Framework assets loaded. Not a failure.
      return;
    }

    std::unique_ptr<Asset> manifest(
        assets->OpenNonAsset(kAndroidManifestPath, cookie, Asset::AccessMode::ACCESS_BUFFER));
    if (manifest == nullptr) {
      // No errors.
      return;
    }

    std::string error;
    std::unique_ptr<xml::XmlResource> manifest_xml =
        xml::Inflate(manifest->getBuffer(true /*wordAligned*/), manifest->getLength(), &error);
    if (manifest_xml == nullptr) {
      // No errors.
      return;
    }

    if (!options_.manifest_fixer_options.compile_sdk_version) {
      xml::Attribute* attr = manifest_xml->root->FindAttribute(xml::kSchemaAndroid, "versionCode");
      if (attr != nullptr) {
        Maybe<std::string>& compile_sdk_version = options_.manifest_fixer_options.compile_sdk_version;
        if (BinaryPrimitive* prim = ValueCast<BinaryPrimitive>(attr->compiled_value.get())) {
          switch (prim->value.dataType) {
            case Res_value::TYPE_INT_DEC:
              compile_sdk_version = StringPrintf("%" PRId32, static_cast<int32_t>(prim->value.data));
              break;
            case Res_value::TYPE_INT_HEX:
              compile_sdk_version = StringPrintf("%" PRIx32, prim->value.data);
              break;
            default:
              break;
          }
        } else if (String* str = ValueCast<String>(attr->compiled_value.get())) {
          compile_sdk_version = *str->value;
        } else {
          compile_sdk_version = attr->value;
        }
      }
    }

    if (!options_.manifest_fixer_options.compile_sdk_version_codename) {
      xml::Attribute* attr = manifest_xml->root->FindAttribute(xml::kSchemaAndroid, "versionName");
      if (attr != nullptr) {
        Maybe<std::string>& compile_sdk_version_codename =
            options_.manifest_fixer_options.compile_sdk_version_codename;
        if (String* str = ValueCast<String>(attr->compiled_value.get())) {
          compile_sdk_version_codename = *str->value;
        } else {
          compile_sdk_version_codename = attr->value;
        }
      }
    }
  }

  // Creates a SymbolTable that loads symbols from the various APKs.
  // Pre-condition: context_->GetCompilationPackage() needs to be set.
  bool LoadSymbolsFromIncludePaths() {
    TRACE_NAME("LoadSymbolsFromIncludePaths: #" + std::to_string(options_.include_paths.size()));
    auto asset_source = util::make_unique<AssetManagerSymbolSource>();
    for (const std::string& path : options_.include_paths) {
      if (context_->IsVerbose()) {
        context_->GetDiagnostics()->Note(DiagMessage() << "including " << path);
      }

      std::string error;
      auto zip_collection = io::ZipFileCollection::Create(path, &error);
      if (zip_collection == nullptr) {
        context_->GetDiagnostics()->Error(DiagMessage() << "failed to open APK: " << error);
        return false;
      }

      if (zip_collection->FindFile(kProtoResourceTablePath) != nullptr) {
        // Load this as a static library include.
        std::unique_ptr<LoadedApk> static_apk = LoadedApk::LoadProtoApkFromFileCollection(
            Source(path), std::move(zip_collection), context_->GetDiagnostics());
        if (static_apk == nullptr) {
          return false;
        }

        if (context_->GetPackageType() != PackageType::kStaticLib) {
          // Can't include static libraries when not building a static library (they have no IDs
          // assigned).
          context_->GetDiagnostics()->Error(
              DiagMessage(path) << "can't include static library when not building a static lib");
          return false;
        }

        ResourceTable* table = static_apk->GetResourceTable();

        // If we are using --no-static-lib-packages, we need to rename the package of this table to
        // our compilation package so the symbol package name does not get mangled into the entry
        // name.
        if (options_.no_static_lib_packages && !table->packages.empty()) {
          auto lib_package_result = GetStaticLibraryPackage(table);
          if (!lib_package_result.has_value()) {
            context_->GetDiagnostics()->Error(DiagMessage(path) << lib_package_result.error());
            return false;
          }
          lib_package_result.value()->name = context_->GetCompilationPackage();
        }

        context_->GetExternalSymbols()->AppendSource(
            util::make_unique<ResourceTableSymbolSource>(table));
        static_library_includes_.push_back(std::move(static_apk));
      } else {
        if (!asset_source->AddAssetPath(path)) {
          context_->GetDiagnostics()->Error(DiagMessage()
                                            << "failed to load include path " << path);
          return false;
        }
      }
    }

    // Capture the shared libraries so that the final resource table can be properly flattened
    // with support for shared libraries.
    for (auto& entry : asset_source->GetAssignedPackageIds()) {
      if (entry.first == kAppPackageId) {
        // Capture the included base feature package.
        included_feature_base_ = entry.second;
      } else if (entry.first == kFrameworkPackageId) {
        // Try to embed which version of the framework we're compiling against.
        // First check if we should use compileSdkVersion at all. Otherwise compilation may fail
        // when linking our synthesized 'android:compileSdkVersion' attribute.
        std::unique_ptr<SymbolTable::Symbol> symbol = asset_source->FindByName(
            ResourceName("android", ResourceType::kAttr, "compileSdkVersion"));
        if (symbol != nullptr && symbol->is_public) {
          // The symbol is present and public, extract the android:versionName and
          // android:versionCode from the framework AndroidManifest.xml.
          ExtractCompileSdkVersions(asset_source->GetAssetManager());
        }
      } else if (asset_source->IsPackageDynamic(entry.first, entry.second)) {
        final_table_.included_packages_[entry.first] = entry.second;
      }
    }

    context_->GetExternalSymbols()->AppendSource(std::move(asset_source));
    return true;
  }

  Maybe<AppInfo> ExtractAppInfoFromManifest(xml::XmlResource* xml_res, IDiagnostics* diag) {
    TRACE_CALL();
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

    if (xml::Attribute* version_code_major_attr =
        manifest_el->FindAttribute(xml::kSchemaAndroid, "versionCodeMajor")) {
      Maybe<uint32_t> maybe_code = ResourceUtils::ParseInt(version_code_major_attr->value);
      if (!maybe_code) {
        diag->Error(DiagMessage(xml_res->file.source.WithLine(manifest_el->line_number))
                        << "invalid android:versionCodeMajor '"
                        << version_code_major_attr->value << "'");
        return {};
      }
      app_info.version_code_major = maybe_code.value();
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

    for (const xml::Element* child_el : manifest_el->GetChildElements()) {
      if (child_el->namespace_uri.empty() && child_el->name == "uses-split") {
        if (const xml::Attribute* split_name =
            child_el->FindAttribute(xml::kSchemaAndroid, "name")) {
          if (!split_name->value.empty()) {
            app_info.split_name_dependencies.insert(split_name->value);
          }
        }
      }
    }
    return app_info;
  }

  // Precondition: ResourceTable doesn't have any IDs assigned yet, nor is it linked.
  // Postcondition: ResourceTable has only one package left. All others are
  // stripped, or there is an error and false is returned.
  bool VerifyNoExternalPackages() {
    auto is_ext_package_func = [&](const std::unique_ptr<ResourceTablePackage>& pkg) -> bool {
      return context_->GetCompilationPackage() != pkg->name;
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
        for (const auto& entry : type->entries) {
          if (entry->id) {
            ResourceNameRef res_name(package->name, type->type, entry->name);
            context_->GetDiagnostics()->Error(DiagMessage() << "resource " << res_name << " has ID "
                                                            << entry->id.value() << " assigned");
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

  bool FlattenTable(ResourceTable* table, OutputFormat format, IArchiveWriter* writer) {
    TRACE_CALL();
    switch (format) {
      case OutputFormat::kApk: {
        BigBuffer buffer(1024);
        TableFlattener flattener(options_.table_flattener_options, &buffer);
        if (!flattener.Consume(context_, table)) {
          context_->GetDiagnostics()->Error(DiagMessage() << "failed to flatten resource table");
          return false;
        }

        io::BigBufferInputStream input_stream(&buffer);
        return io::CopyInputStreamToArchive(context_, &input_stream, kApkResourceTablePath,
                                            ArchiveEntry::kAlign, writer);
      } break;

      case OutputFormat::kProto: {
        pb::ResourceTable pb_table;
        SerializeTableToPb(*table, &pb_table, context_->GetDiagnostics(),
                           options_.proto_table_flattener_options);
        return io::CopyProtoToArchive(context_, &pb_table, kProtoResourceTablePath,
                                      ArchiveEntry::kCompress, writer);
      } break;
    }
    return false;
  }

  bool WriteJavaFile(ResourceTable* table, const StringPiece& package_name_to_generate,
                     const StringPiece& out_package, const JavaClassGeneratorOptions& java_options,
                     const Maybe<std::string>& out_text_symbols_path = {}) {
    if (!options_.generate_java_class_path && !out_text_symbols_path) {
      return true;
    }

    std::string out_path;
    std::unique_ptr<io::FileOutputStream> fout;
    if (options_.generate_java_class_path) {
      out_path = options_.generate_java_class_path.value();
      file::AppendPath(&out_path, file::PackageToPath(out_package));
      if (!file::mkdirs(out_path)) {
        context_->GetDiagnostics()->Error(DiagMessage()
                                          << "failed to create directory '" << out_path << "'");
        return false;
      }

      file::AppendPath(&out_path, "R.java");

      fout = util::make_unique<io::FileOutputStream>(out_path);
      if (fout->HadError()) {
        context_->GetDiagnostics()->Error(DiagMessage() << "failed writing to '" << out_path
                                                        << "': " << fout->GetError());
        return false;
      }
    }

    std::unique_ptr<io::FileOutputStream> fout_text;
    if (out_text_symbols_path) {
      fout_text = util::make_unique<io::FileOutputStream>(out_text_symbols_path.value());
      if (fout_text->HadError()) {
        context_->GetDiagnostics()->Error(DiagMessage()
                                          << "failed writing to '" << out_text_symbols_path.value()
                                          << "': " << fout_text->GetError());
        return false;
      }
    }

    JavaClassGenerator generator(context_, table, java_options);
    if (!generator.Generate(package_name_to_generate, out_package, fout.get(), fout_text.get())) {
      context_->GetDiagnostics()->Error(DiagMessage(out_path) << generator.GetError());
      return false;
    }

    return true;
  }

  bool GenerateJavaClasses() {
    TRACE_CALL();
    // The set of packages whose R class to call in the main classes onResourcesLoaded callback.
    std::vector<std::string> packages_to_callback;

    JavaClassGeneratorOptions template_options;
    template_options.types = JavaClassGeneratorOptions::SymbolTypes::kAll;
    template_options.javadoc_annotations = options_.javadoc_annotations;

    if (context_->GetPackageType() == PackageType::kStaticLib || options_.generate_non_final_ids) {
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
      // to the original package, and private and public symbols to the private package.
      JavaClassGeneratorOptions options = template_options;
      options.types = JavaClassGeneratorOptions::SymbolTypes::kPublicPrivate;
      if (!WriteJavaFile(&final_table_, actual_package, options_.private_symbols.value(),
                         options)) {
        return false;
      }
    }

    // Generate copies of the original package R class but with different package names.
    // This is to support non-namespaced builds.
    for (const std::string& extra_package : options_.extra_java_packages) {
      packages_to_callback.push_back(extra_package);

      JavaClassGeneratorOptions options = template_options;
      options.types = JavaClassGeneratorOptions::SymbolTypes::kAll;
      if (!WriteJavaFile(&final_table_, actual_package, extra_package, options)) {
        return false;
      }
    }

    // Generate R classes for each package that was merged (static library).
    // Use the actual package's resources only.
    for (const std::string& package : table_merger_->merged_packages()) {
      packages_to_callback.push_back(package);

      JavaClassGeneratorOptions options = template_options;
      options.types = JavaClassGeneratorOptions::SymbolTypes::kAll;
      if (!WriteJavaFile(&final_table_, package, package, options)) {
        return false;
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
      return false;
    }

    return true;
  }

  bool WriteManifestJavaFile(xml::XmlResource* manifest_xml) {
    TRACE_CALL();
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

    const std::string package_utf8 =
        options_.custom_java_package.value_or_default(context_->GetCompilationPackage());

    std::string out_path = options_.generate_java_class_path.value();
    file::AppendPath(&out_path, file::PackageToPath(package_utf8));

    if (!file::mkdirs(out_path)) {
      context_->GetDiagnostics()->Error(DiagMessage() << "failed to create directory '" << out_path
                                                      << "'");
      return false;
    }

    file::AppendPath(&out_path, "Manifest.java");

    io::FileOutputStream fout(out_path);
    if (fout.HadError()) {
      context_->GetDiagnostics()->Error(DiagMessage() << "failed to open '" << out_path
                                                      << "': " << fout.GetError());
      return false;
    }

    ClassDefinition::WriteJavaFile(manifest_class.get(), package_utf8, true,
                                   false /* strip_api_annotations */, &fout);
    fout.Flush();

    if (fout.HadError()) {
      context_->GetDiagnostics()->Error(DiagMessage() << "failed writing to '" << out_path
                                                      << "': " << fout.GetError());
      return false;
    }
    return true;
  }

  bool WriteProguardFile(const Maybe<std::string>& out, const proguard::KeepSet& keep_set) {
    TRACE_CALL();
    if (!out) {
      return true;
    }

    const std::string& out_path = out.value();
    io::FileOutputStream fout(out_path);
    if (fout.HadError()) {
      context_->GetDiagnostics()->Error(DiagMessage() << "failed to open '" << out_path
                                                      << "': " << fout.GetError());
      return false;
    }

    proguard::WriteKeepSet(keep_set, &fout, options_.generate_minimal_proguard_rules,
                           options_.no_proguard_location_reference);
    fout.Flush();

    if (fout.HadError()) {
      context_->GetDiagnostics()->Error(DiagMessage() << "failed writing to '" << out_path
                                                      << "': " << fout.GetError());
      return false;
    }
    return true;
  }

  bool MergeStaticLibrary(const std::string& input, bool override) {
    TRACE_CALL();
    if (context_->IsVerbose()) {
      context_->GetDiagnostics()->Note(DiagMessage() << "merging static library " << input);
    }

    std::unique_ptr<LoadedApk> apk = LoadedApk::LoadApkFromPath(input, context_->GetDiagnostics());
    if (apk == nullptr) {
      context_->GetDiagnostics()->Error(DiagMessage(input) << "invalid static library");
      return false;
    }

    ResourceTable* table = apk->GetResourceTable();
    if (table->packages.empty()) {
      return true;
    }

    auto lib_package_result = GetStaticLibraryPackage(table);
    if (!lib_package_result.has_value()) {
      context_->GetDiagnostics()->Error(DiagMessage(input) << lib_package_result.error());
      return false;
    }

    ResourceTablePackage* pkg = lib_package_result.value();
    bool result;
    if (options_.no_static_lib_packages) {
      // Merge all resources as if they were in the compilation package. This is the old behavior
      // of aapt.

      // Add the package to the set of --extra-packages so we emit an R.java for each library
      // package.
      if (!pkg->name.empty()) {
        options_.extra_java_packages.insert(pkg->name);
      }

      // Clear the package name, so as to make the resources look like they are coming from the
      // local package.
      pkg->name = "";
      result = table_merger_->Merge(Source(input), table, override);

    } else {
      // This is the proper way to merge libraries, where the package name is
      // preserved and resource names are mangled.
      result = table_merger_->MergeAndMangle(Source(input), pkg->name, table);
    }

    if (!result) {
      return false;
    }

    // Make sure to move the collection into the set of IFileCollections.
    merged_apks_.push_back(std::move(apk));
    return true;
  }

  bool MergeExportedSymbols(const Source& source,
                            const std::vector<SourcedResourceName>& exported_symbols) {
    TRACE_CALL();
    // Add the exports of this file to the table.
    for (const SourcedResourceName& exported_symbol : exported_symbols) {
      ResourceName res_name = exported_symbol.name;
      if (res_name.package.empty()) {
        res_name.package = context_->GetCompilationPackage();
      }

      Maybe<ResourceName> mangled_name = context_->GetNameMangler()->MangleName(res_name);
      if (mangled_name) {
        res_name = mangled_name.value();
      }

      auto id = util::make_unique<Id>();
      id->SetSource(source.WithLine(exported_symbol.line));
      bool result = final_table_.AddResource(
          NewResourceBuilder(res_name).SetValue(std::move(id)).SetAllowMangled(true).Build(),
          context_->GetDiagnostics());
      if (!result) {
        return false;
      }
    }
    return true;
  }

  bool MergeCompiledFile(const ResourceFile& compiled_file, io::IFile* file, bool override) {
    TRACE_CALL();
    if (context_->IsVerbose()) {
      context_->GetDiagnostics()->Note(DiagMessage()
                                       << "merging '" << compiled_file.name
                                       << "' from compiled file " << compiled_file.source);
    }

    if (!table_merger_->MergeFile(compiled_file, override, file)) {
      return false;
    }
    return MergeExportedSymbols(compiled_file.source, compiled_file.exported_symbols);
  }

  // Takes a path to load as a ZIP file and merges the files within into the main ResourceTable.
  // If override is true, conflicting resources are allowed to override each other, in order of last
  // seen.
  // An io::IFileCollection is created from the ZIP file and added to the set of
  // io::IFileCollections that are open.
  bool MergeArchive(const std::string& input, bool override) {
    TRACE_CALL();
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

  // Takes a path to load and merge into the main ResourceTable. If override is true,
  // conflicting resources are allowed to override each other, in order of last seen.
  // If the file path ends with .flata, .jar, .jack, or .zip the file is treated
  // as ZIP archive and the files within are merged individually.
  // Otherwise the file is processed on its own.
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

  // Takes an AAPT Container file (.apc/.flat) to load and merge into the main ResourceTable.
  // If override is true, conflicting resources are allowed to override each other, in order of last
  // seen.
  // All other file types are ignored. This is because these files could be coming from a zip,
  // where we could have other files like classes.dex.
  bool MergeFile(io::IFile* file, bool override) {
    TRACE_CALL();
    const Source& src = file->GetSource();

    if (util::EndsWith(src.path, ".xml") || util::EndsWith(src.path, ".png")) {
      // Since AAPT compiles these file types and appends .flat to them, seeing
      // their raw extensions is a sign that they weren't compiled.
      const StringPiece file_type = util::EndsWith(src.path, ".xml") ? "XML" : "PNG";
      context_->GetDiagnostics()->Error(DiagMessage(src) << "uncompiled " << file_type
                                                         << " file passed as argument. Must be "
                                                            "compiled first into .flat file.");
      return false;
    } else if (!util::EndsWith(src.path, ".apc") && !util::EndsWith(src.path, ".flat")) {
      if (context_->IsVerbose()) {
        context_->GetDiagnostics()->Warn(DiagMessage(src) << "ignoring unrecognized file");
        return true;
      }
    }

    std::unique_ptr<io::InputStream> input_stream = file->OpenInputStream();
    if (input_stream == nullptr) {
      context_->GetDiagnostics()->Error(DiagMessage(src) << "failed to open file");
      return false;
    }

    if (input_stream->HadError()) {
      context_->GetDiagnostics()->Error(DiagMessage(src)
                                        << "failed to open file: " << input_stream->GetError());
      return false;
    }

    ContainerReaderEntry* entry;
    ContainerReader reader(input_stream.get());

    if (reader.HadError()) {
      context_->GetDiagnostics()->Error(DiagMessage(src)
                                        << "failed to read file: " << reader.GetError());
      return false;
    }

    while ((entry = reader.Next()) != nullptr) {
      if (entry->Type() == ContainerEntryType::kResTable) {
        TRACE_NAME(std::string("Process ResTable:") + file->GetSource().path);
        pb::ResourceTable pb_table;
        if (!entry->GetResTable(&pb_table)) {
          context_->GetDiagnostics()->Error(DiagMessage(src) << "failed to read resource table: "
                                                             << entry->GetError());
          return false;
        }

        ResourceTable table;
        std::string error;
        if (!DeserializeTableFromPb(pb_table, nullptr /*files*/, &table, &error)) {
          context_->GetDiagnostics()->Error(DiagMessage(src)
                                            << "failed to deserialize resource table: " << error);
          return false;
        }

        if (!table_merger_->Merge(src, &table, override)) {
          context_->GetDiagnostics()->Error(DiagMessage(src) << "failed to merge resource table");
          return false;
        }
      } else if (entry->Type() == ContainerEntryType::kResFile) {
        TRACE_NAME(std::string("Process ResFile") + file->GetSource().path);
        pb::internal::CompiledFile pb_compiled_file;
        off64_t offset;
        size_t len;
        if (!entry->GetResFileOffsets(&pb_compiled_file, &offset, &len)) {
          context_->GetDiagnostics()->Error(DiagMessage(src) << "failed to get resource file: "
                                                             << entry->GetError());
          return false;
        }

        ResourceFile resource_file;
        std::string error;
        if (!DeserializeCompiledFileFromPb(pb_compiled_file, &resource_file, &error)) {
          context_->GetDiagnostics()->Error(DiagMessage(src)
                                            << "failed to read compiled header: " << error);
          return false;
        }

        if (!MergeCompiledFile(resource_file, file->CreateFileSegment(offset, len), override)) {
          return false;
        }
      }
    }
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
      uint32_t compression_flags = GetCompressionFlags(entry.first, options_);
      if (!io::CopyFileToArchive(context_, entry.second.get(), entry.first, compression_flags,
                                 writer)) {
        return false;
      }
    }
    return true;
  }

  ResourceEntry* ResolveTableEntry(LinkContext* context, ResourceTable* table,
                                   Reference* reference) {
    if (!reference || !reference->name) {
      return nullptr;
    }
    auto name_ref = ResourceNameRef(reference->name.value());
    if (name_ref.package.empty()) {
      name_ref.package = context->GetCompilationPackage();
    }
    const auto search_result = table->FindResource(name_ref);
    if (!search_result) {
      return nullptr;
    }
    return search_result.value().entry;
  }

  void AliasAdaptiveIcon(xml::XmlResource* manifest, ResourceTable* table) {
    const xml::Element* application = manifest->root->FindChild("", "application");
    if (!application) {
      return;
    }

    const xml::Attribute* icon = application->FindAttribute(xml::kSchemaAndroid, "icon");
    const xml::Attribute* round_icon = application->FindAttribute(xml::kSchemaAndroid, "roundIcon");
    if (!icon || !round_icon) {
      return;
    }

    // Find the icon resource defined within the application.
    const auto icon_reference = ValueCast<Reference>(icon->compiled_value.get());
    const auto icon_entry = ResolveTableEntry(context_, table, icon_reference);
    if (!icon_entry) {
      return;
    }

    int icon_max_sdk = 0;
    for (auto& config_value : icon_entry->values) {
      icon_max_sdk = (icon_max_sdk < config_value->config.sdkVersion)
          ? config_value->config.sdkVersion : icon_max_sdk;
    }
    if (icon_max_sdk < SDK_O) {
      // Adaptive icons must be versioned with v26 qualifiers, so this is not an adaptive icon.
      return;
    }

    // Find the roundIcon resource defined within the application.
    const auto round_icon_reference = ValueCast<Reference>(round_icon->compiled_value.get());
    const auto round_icon_entry = ResolveTableEntry(context_, table, round_icon_reference);
    if (!round_icon_entry) {
      return;
    }

    int round_icon_max_sdk = 0;
    for (auto& config_value : round_icon_entry->values) {
      round_icon_max_sdk = (round_icon_max_sdk < config_value->config.sdkVersion)
                     ? config_value->config.sdkVersion : round_icon_max_sdk;
    }
    if (round_icon_max_sdk >= SDK_O) {
      // The developer explicitly used a v26 compatible drawable as the roundIcon, meaning we should
      // not generate an alias to the icon drawable.
      return;
    }

    // Add an equivalent v26 entry to the roundIcon for each v26 variant of the regular icon.
    for (auto& config_value : icon_entry->values) {
      if (config_value->config.sdkVersion < SDK_O) {
        continue;
      }

      context_->GetDiagnostics()->Note(DiagMessage() << "generating "
                                                     << round_icon_reference->name.value()
                                                     << " with config \"" << config_value->config
                                                     << "\" for round icon compatibility");

      CloningValueTransformer cloner(&table->string_pool);
      auto value = icon_reference->Transform(cloner);
      auto round_config_value =
          round_icon_entry->FindOrCreateValue(config_value->config, config_value->product);
      round_config_value->value = std::move(value);
    }
  }

  bool VerifySharedUserId(xml::XmlResource* manifest, ResourceTable* table) {
    const xml::Element* manifest_el = xml::FindRootElement(manifest->root.get());
    if (manifest_el == nullptr) {
      return true;
    }
    if (!manifest_el->namespace_uri.empty() || manifest_el->name != "manifest") {
      return true;
    }
    const xml::Attribute* attr = manifest_el->FindAttribute(xml::kSchemaAndroid, "sharedUserId");
    if (!attr) {
      return true;
    }
    const auto validate = [&](const std::string& shared_user_id) -> bool {
      if (util::IsAndroidSharedUserId(context_->GetCompilationPackage(), shared_user_id)) {
        return true;
      }
      DiagMessage error_msg(manifest_el->line_number);
      error_msg << "attribute 'sharedUserId' in <manifest> tag is not a valid shared user id: '"
                << shared_user_id << "'";
      if (options_.manifest_fixer_options.warn_validation) {
        // Treat the error only as a warning.
        context_->GetDiagnostics()->Warn(error_msg);
        return true;
      }
      context_->GetDiagnostics()->Error(error_msg);
      return false;
    };
    // If attr->compiled_value is not null, check if it is a ref
    if (attr->compiled_value) {
      const auto ref = ValueCast<Reference>(attr->compiled_value.get());
      if (ref == nullptr) {
        return true;
      }
      const auto shared_user_id_entry = ResolveTableEntry(context_, table, ref);
      if (!shared_user_id_entry) {
        return true;
      }
      for (const auto& value : shared_user_id_entry->values) {
        const auto str_value = ValueCast<String>(value->value.get());
        if (str_value != nullptr && !validate(*str_value->value)) {
          return false;
        }
      }
      return true;
    }

    // Fallback to checking the raw value
    return validate(attr->value);
  }

  // Writes the AndroidManifest, ResourceTable, and all XML files referenced by the ResourceTable
  // to the IArchiveWriter.
  bool WriteApk(IArchiveWriter* writer, proguard::KeepSet* keep_set, xml::XmlResource* manifest,
                ResourceTable* table) {
    TRACE_CALL();
    const bool keep_raw_values = (context_->GetPackageType() == PackageType::kStaticLib)
                                 || options_.keep_raw_values;
    bool result = FlattenXml(context_, *manifest, kAndroidManifestPath, keep_raw_values,
                             true /*utf16*/, options_.output_format, writer);
    if (!result) {
      return false;
    }

    // When a developer specifies an adaptive application icon, and a non-adaptive round application
    // icon, create an alias from the round icon to the regular icon for v26 APIs and up. We do this
    // because certain devices prefer android:roundIcon over android:icon regardless of the API
    // levels of the drawables set for either. This auto-aliasing behaviour allows an app to prefer
    // the android:roundIcon on API 25 devices, and prefer the adaptive icon on API 26 devices.
    // See (b/34829129)
    AliasAdaptiveIcon(manifest, table);

    // Verify the shared user id here to handle the case of reference value.
    if (!VerifySharedUserId(manifest, table)) {
      return false;
    }

    ResourceFileFlattenerOptions file_flattener_options;
    file_flattener_options.keep_raw_values = keep_raw_values;
    file_flattener_options.do_not_compress_anything = options_.do_not_compress_anything;
    file_flattener_options.extensions_to_not_compress = options_.extensions_to_not_compress;
    file_flattener_options.regex_to_not_compress = options_.regex_to_not_compress;
    file_flattener_options.no_auto_version = options_.no_auto_version;
    file_flattener_options.no_version_vectors = options_.no_version_vectors;
    file_flattener_options.no_version_transitions = options_.no_version_transitions;
    file_flattener_options.no_xml_namespaces = options_.no_xml_namespaces;
    file_flattener_options.update_proguard_spec =
        static_cast<bool>(options_.generate_proguard_rules_path);
    file_flattener_options.output_format = options_.output_format;
    file_flattener_options.do_not_fail_on_missing_resources = options_.merge_only;

    ResourceFileFlattener file_flattener(file_flattener_options, context_, keep_set);
    if (!file_flattener.Flatten(table, writer)) {
      context_->GetDiagnostics()->Error(DiagMessage() << "failed linking file resources");
      return false;
    }

    // Hack to fix b/68820737.
    // We need to modify the ResourceTable's package name, but that should NOT affect
    // anything else being generated, which includes the Java classes.
    // If required, the package name is modifed before flattening, and then modified back
    // to its original name.
    ResourceTablePackage* package_to_rewrite = nullptr;
    // Pre-O, the platform treats negative resource IDs [those with a package ID of 0x80
    // or higher] as invalid. In order to work around this limitation, we allow the use
    // of traditionally reserved resource IDs [those between 0x02 and 0x7E]. Allow the
    // definition of what a valid "split" package ID is to account for this.
    const bool isSplitPackage = (options_.allow_reserved_package_id &&
          context_->GetPackageId() != kAppPackageId &&
          context_->GetPackageId() != kFrameworkPackageId)
        || (!options_.allow_reserved_package_id && context_->GetPackageId() > kAppPackageId);
    if (isSplitPackage && included_feature_base_ == context_->GetCompilationPackage()) {
      // The base APK is included, and this is a feature split. If the base package is
      // the same as this package, then we are building an old style Android Instant Apps feature
      // split and must apply this workaround to avoid requiring namespaces support.
      if (!table->packages.empty() &&
          table->packages.back()->name == context_->GetCompilationPackage()) {
        package_to_rewrite = table->packages.back().get();
        std::string new_package_name =
            StringPrintf("%s.%s", package_to_rewrite->name.c_str(),
                         app_info_.split_name.value_or_default("feature").c_str());

        if (context_->IsVerbose()) {
          context_->GetDiagnostics()->Note(
              DiagMessage() << "rewriting resource package name for feature split to '"
                            << new_package_name << "'");
        }
        package_to_rewrite->name = new_package_name;
      }
    }

    bool success = FlattenTable(table, options_.output_format, writer);

    if (package_to_rewrite != nullptr) {
      // Change the name back.
      package_to_rewrite->name = context_->GetCompilationPackage();

      // TableFlattener creates an `included_packages_` mapping entry for each package with a
      // non-standard package id (not 0x01 or 0x7f). Since this is a feature split and not a shared
      // library, do not include a mapping from the feature package name to the feature package id
      // in the feature's dynamic reference table.
      table->included_packages_.erase(context_->GetPackageId());
    }

    if (!success) {
      context_->GetDiagnostics()->Error(DiagMessage() << "failed to write resource table");
    }
    return success;
  }

  int Run(const std::vector<std::string>& input_files) {
    TRACE_CALL();
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

    // Determine the package name under which to merge resources.
    if (options_.rename_resources_package) {
      if (!options_.custom_java_package) {
        // Generate the R.java under the original package name instead of the package name specified
        // through --rename-resources-package.
        options_.custom_java_package = context_->GetCompilationPackage();
      }
      context_->SetCompilationPackage(options_.rename_resources_package.value());
    }

    // Now that the compilation package is set, load the dependencies. This will also extract
    // the Android framework's versionCode and versionName, if they exist.
    if (!LoadSymbolsFromIncludePaths()) {
      return 1;
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

    app_info_ = maybe_app_info.value();
    context_->SetMinSdkVersion(app_info_.min_sdk_version.value_or_default(0));

    context_->SetNameManglerPolicy(NameManglerPolicy{context_->GetCompilationPackage()});
    context_->SetSplitNameDependencies(app_info_.split_name_dependencies);

    // Override the package ID when it is "android".
    if (context_->GetCompilationPackage() == "android") {
      context_->SetPackageId(kAndroidPackageId);

      // Verify we're building a regular app.
      if (context_->GetPackageType() != PackageType::kApp) {
        context_->GetDiagnostics()->Error(
            DiagMessage() << "package 'android' can only be built as a regular app");
        return 1;
      }
    }

    TableMergerOptions table_merger_options;
    table_merger_options.auto_add_overlay = options_.auto_add_overlay;
    table_merger_options.override_styles_instead_of_overlaying =
        options_.override_styles_instead_of_overlaying;
    table_merger_options.strict_visibility = options_.strict_visibility;
    table_merger_ = util::make_unique<TableMerger>(context_, &final_table_, table_merger_options);

    if (context_->IsVerbose()) {
      context_->GetDiagnostics()->Note(DiagMessage()
                                       << StringPrintf("linking package '%s' using package ID %02x",
                                                       context_->GetCompilationPackage().data(),
                                                       context_->GetPackageId()));
    }

    // Extract symbols from AndroidManifest.xml, since this isn't merged like the other XML files
    // in res/**/*.
    {
      XmlIdCollector collector;
      if (!collector.Consume(context_, manifest_xml.get())) {
        return false;
      }

      if (!MergeExportedSymbols(manifest_xml->file.source, manifest_xml->file.exported_symbols)) {
        return false;
      }
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
      if (context_->GetPackageId() == kAndroidPackageId &&
          !mover.Consume(context_, &final_table_)) {
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
              options_.stable_id_map[std::move(name)] = entry->id.value();
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

    // Before we process anything, remove the resources whose default values don't exist.
    // We want to force any references to these to fail the build.
    if (!options_.no_resource_removal) {
      if (!NoDefaultResourceRemover{}.Consume(context_, &final_table_)) {
        context_->GetDiagnostics()->Error(DiagMessage()
                                          << "failed removing resources with no defaults");
        return 1;
      }
    }

    ReferenceLinker linker;
    if (!options_.merge_only && !linker.Consume(context_, &final_table_)) {
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

    if (!options_.exclude_configs_.empty()) {
      std::vector<ConfigDescription> excluded_configs;

      for (auto& config_string : options_.exclude_configs_) {
        TRACE_NAME("ConfigDescription::Parse");
        ConfigDescription config_description;

        if (!ConfigDescription::Parse(config_string, &config_description)) {
          context_->GetDiagnostics()->Error(DiagMessage()
                                                << "failed to parse --excluded-configs "
                                                << config_string);
          return 1;
        }

        excluded_configs.push_back(config_description);
      }

      ResourceExcluder excluder(excluded_configs);
      if (!excluder.Consume(context_, &final_table_)) {
        context_->GetDiagnostics()->Error(DiagMessage() << "failed excluding configurations");
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

    proguard::KeepSet proguard_keep_set =
        proguard::KeepSet(options_.generate_conditional_proguard_rules);
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
      const size_t origConstraintSize = options_.split_constraints.size();
      options_.split_constraints =
          AdjustSplitConstraintsForMinSdk(context_->GetMinSdkVersion(), options_.split_constraints);

      if (origConstraintSize != options_.split_constraints.size()) {
        context_->GetDiagnostics()->Warn(DiagMessage()
                                         << "requested to split resources prior to min sdk of "
                                         << context_->GetMinSdkVersion());
      }
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
            GenerateSplitManifest(app_info_, *split_constraints_iter);

        XmlReferenceLinker linker(&final_table_);
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
      // AndroidManifest.xml has no resource name, but the CallSite is built from the name
      // (aka, which package the AndroidManifest.xml is coming from).
      // So we give it a package name so it can see local resources.
      manifest_xml->file.name.package = context_->GetCompilationPackage();

      XmlReferenceLinker manifest_linker(&final_table_);
      if (options_.merge_only || manifest_linker.Consume(context_, manifest_xml.get())) {
        if (options_.generate_proguard_rules_path &&
            !proguard::CollectProguardRulesForManifest(manifest_xml.get(), &proguard_keep_set)) {
          error = true;
        }

        if (options_.generate_main_dex_proguard_rules_path &&
            !proguard::CollectProguardRulesForManifest(manifest_xml.get(),
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

    if (options_.generate_java_class_path || options_.generate_text_symbols_path) {
      if (!GenerateJavaClasses()) {
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

  AppInfo app_info_;

  std::unique_ptr<TableMerger> table_merger_;

  // A pointer to the FileCollection representing the filesystem (not archives).
  std::unique_ptr<io::FileCollection> file_collection_;

  // A vector of IFileCollections. This is mainly here to retain ownership of the
  // collections.
  std::vector<std::unique_ptr<io::IFileCollection>> collections_;

  // The set of merged APKs. This is mainly here to retain ownership of the APKs.
  std::vector<std::unique_ptr<LoadedApk>> merged_apks_;

  // The set of included APKs (not merged). This is mainly here to retain ownership of the APKs.
  std::vector<std::unique_ptr<LoadedApk>> static_library_includes_;

  // The set of shared libraries being used, mapping their assigned package ID to package name.
  std::map<size_t, std::string> shared_libs_;

  // The package name of the base application, if it is included.
  Maybe<std::string> included_feature_base_;
};

int LinkCommand::Action(const std::vector<std::string>& args) {
  TRACE_FLUSH(trace_folder_ ? trace_folder_.value() : "", "LinkCommand::Action");
  LinkContext context(diag_);

  // Expand all argument-files passed into the command line. These start with '@'.
  std::vector<std::string> arg_list;
  for (const std::string& arg : args) {
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
  for (const std::string& arg : overlay_arg_list_) {
    if (util::StartsWith(arg, "@")) {
      const std::string path = arg.substr(1, arg.size() - 1);
      std::string error;
      if (!file::AppendArgsFromFile(path, &options_.overlay_files, &error)) {
        context.GetDiagnostics()->Error(DiagMessage(path) << error);
        return 1;
      }
    } else {
      options_.overlay_files.push_back(arg);
    }
  }

  if (verbose_) {
    context.SetVerbose(verbose_);
  }

  if (int{shared_lib_} + int{static_lib_} + int{proto_format_} > 1) {
    context.GetDiagnostics()->Error(
        DiagMessage()
            << "only one of --shared-lib, --static-lib, or --proto_format can be defined");
    return 1;
  }

  if (shared_lib_ && options_.private_symbols) {
    // If a shared library styleable in a public R.java uses a private attribute, attempting to
    // reference the private attribute within the styleable array will cause a link error because
    // the private attribute will not be emitted in the public R.java.
    context.GetDiagnostics()->Error(DiagMessage()
                                    << "--shared-lib cannot currently be used in combination with"
                                    << " --private-symbols");
    return 1;
  }

  if (options_.merge_only && !static_lib_) {
    context.GetDiagnostics()->Error(
        DiagMessage() << "the --merge-only flag can be only used when building a static library");
    return 1;
  }

  // The default build type.
  context.SetPackageType(PackageType::kApp);
  context.SetPackageId(kAppPackageId);

  if (shared_lib_) {
    context.SetPackageType(PackageType::kSharedLib);
    context.SetPackageId(0x00);
  } else if (static_lib_) {
    context.SetPackageType(PackageType::kStaticLib);
    options_.output_format = OutputFormat::kProto;
  } else if (proto_format_) {
    options_.output_format = OutputFormat::kProto;
  }

  if (package_id_) {
    if (context.GetPackageType() != PackageType::kApp) {
      context.GetDiagnostics()->Error(
          DiagMessage() << "can't specify --package-id when not building a regular app");
      return 1;
    }

    const Maybe<uint32_t> maybe_package_id_int = ResourceUtils::ParseInt(package_id_.value());
    if (!maybe_package_id_int) {
      context.GetDiagnostics()->Error(DiagMessage() << "package ID '" << package_id_.value()
                                                    << "' is not a valid integer");
      return 1;
    }

    const uint32_t package_id_int = maybe_package_id_int.value();
    if (package_id_int > std::numeric_limits<uint8_t>::max()
        || package_id_int == kFrameworkPackageId
        || (!options_.allow_reserved_package_id && package_id_int < kAppPackageId)) {
      context.GetDiagnostics()->Error(
          DiagMessage() << StringPrintf(
              "invalid package ID 0x%02x. Must be in the range 0x7f-0xff.", package_id_int));
      return 1;
    }
    context.SetPackageId(static_cast<uint8_t>(package_id_int));
  }

  // Populate the set of extra packages for which to generate R.java.
  for (std::string& extra_package : extra_java_packages_) {
    // A given package can actually be a colon separated list of packages.
    for (StringPiece package : util::Split(extra_package, ':')) {
      options_.extra_java_packages.insert(package.to_string());
    }
  }

  if (product_list_) {
    for (StringPiece product : util::Tokenize(product_list_.value(), ',')) {
      if (product != "" && product != "default") {
        options_.products.insert(product.to_string());
      }
    }
  }

  std::unique_ptr<IConfigFilter> filter;
  if (!configs_.empty()) {
    filter = ParseConfigFilterParameters(configs_, context.GetDiagnostics());
    if (filter == nullptr) {
      return 1;
    }
    options_.table_splitter_options.config_filter = filter.get();
  }

  if (preferred_density_) {
    Maybe<uint16_t> density =
        ParseTargetDensityParameter(preferred_density_.value(), context.GetDiagnostics());
    if (!density) {
      return 1;
    }
    options_.table_splitter_options.preferred_densities.push_back(density.value());
  }

  // Parse the split parameters.
  for (const std::string& split_arg : split_args_) {
    options_.split_paths.push_back({});
    options_.split_constraints.push_back({});
    if (!ParseSplitParameter(split_arg, context.GetDiagnostics(), &options_.split_paths.back(),
        &options_.split_constraints.back())) {
      return 1;
    }
  }

  if (context.GetPackageType() != PackageType::kStaticLib && stable_id_file_path_) {
    if (!LoadStableIdMap(context.GetDiagnostics(), stable_id_file_path_.value(),
        &options_.stable_id_map)) {
      return 1;
    }
  }

  if (no_compress_regex) {
    std::string regex = no_compress_regex.value();
    if (util::StartsWith(regex, "@")) {
      const std::string path = regex.substr(1, regex.size() -1);
      std::string error;
      if (!file::AppendSetArgsFromFile(path, &options_.extensions_to_not_compress, &error)) {
        context.GetDiagnostics()->Error(DiagMessage(path) << error);
        return 1;
      }
    } else {
      options_.regex_to_not_compress = GetRegularExpression(no_compress_regex.value());
    }
  }

  // Populate some default no-compress extensions that are already compressed.
  options_.extensions_to_not_compress.insert({
      // Image extensions
      ".jpg", ".jpeg", ".png", ".gif", ".webp",
      // Audio extensions
      ".wav", ".mp2", ".mp3", ".ogg", ".aac", ".mid", ".midi", ".smf", ".jet", ".rtttl", ".imy",
      ".xmf", ".amr", ".awb",
      // Audio/video extensions
      ".mpg", ".mpeg", ".mp4", ".m4a", ".m4v", ".3gp", ".3gpp", ".3g2", ".3gpp2", ".wma", ".wmv",
      ".webm", ".mkv"});

  // Turn off auto versioning for static-libs.
  if (context.GetPackageType() == PackageType::kStaticLib) {
    options_.no_auto_version = true;
    options_.no_version_vectors = true;
    options_.no_version_transitions = true;
  }

  Linker cmd(&context, options_);
  return cmd.Run(arg_list);
}

}  // namespace aapt
