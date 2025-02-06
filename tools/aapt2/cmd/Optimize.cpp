/*
 * Copyright (C) 2017 The Android Open Source Project
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

#include "Optimize.h"

#include <map>
#include <memory>
#include <set>
#include <string>
#include <utility>
#include <vector>

#include "Diagnostics.h"
#include "LoadedApk.h"
#include "ResourceUtils.h"
#include "SdkConstants.h"
#include "ValueVisitor.h"
#include "android-base/file.h"
#include "android-base/stringprintf.h"
#include "androidfw/BigBufferStream.h"
#include "androidfw/ConfigDescription.h"
#include "androidfw/IDiagnostics.h"
#include "androidfw/ResourceTypes.h"
#include "androidfw/StringPiece.h"
#include "cmd/Util.h"
#include "configuration/ConfigurationParser.h"
#include "filter/AbiFilter.h"
#include "format/binary/TableFlattener.h"
#include "format/binary/XmlFlattener.h"
#include "io/Util.h"
#include "optimize/MultiApkGenerator.h"
#include "optimize/Obfuscator.h"
#include "optimize/ResourceDeduper.h"
#include "optimize/ResourceFilter.h"
#include "optimize/VersionCollapser.h"
#include "split/TableSplitter.h"
#include "util/Files.h"
#include "util/Util.h"

using ::aapt::configuration::Abi;
using ::aapt::configuration::OutputArtifact;
using ::android::ConfigDescription;
using ::android::ResTable_config;
using ::android::StringPiece;
using ::android::base::ReadFileToString;
using ::android::base::StringAppendF;
using ::android::base::StringPrintf;
using ::android::base::WriteStringToFile;

namespace aapt {

class OptimizeContext : public IAaptContext {
 public:
  OptimizeContext() = default;

  PackageType GetPackageType() override {
    // Not important here. Using anything other than kApp adds EXTRA validation, which we want to
    // avoid.
    return PackageType::kApp;
  }

  android::IDiagnostics* GetDiagnostics() override {
    return &diagnostics_;
  }

  NameMangler* GetNameMangler() override {
    UNIMPLEMENTED(FATAL);
    return nullptr;
  }

  const std::string& GetCompilationPackage() override {
    static std::string empty;
    return empty;
  }

  uint8_t GetPackageId() override {
    return 0;
  }

  SymbolTable* GetExternalSymbols() override {
    UNIMPLEMENTED(FATAL);
    return nullptr;
  }

  bool IsVerbose() override {
    return verbose_;
  }

  void SetVerbose(bool val) {
    verbose_ = val;
    diagnostics_.SetVerbose(val);
  }

  void SetMinSdkVersion(int sdk_version) {
    sdk_version_ = sdk_version;
  }

  int GetMinSdkVersion() override {
    return sdk_version_;
  }

  const std::set<std::string>& GetSplitNameDependencies() override {
    UNIMPLEMENTED(FATAL) << "Split Name Dependencies should not be necessary";
    static std::set<std::string> empty;
    return empty;
  }

 private:
  StdErrDiagnostics diagnostics_;
  bool verbose_ = false;
  int sdk_version_ = 0;

  DISALLOW_COPY_AND_ASSIGN(OptimizeContext);
};

class Optimizer {
 public:
  Optimizer(OptimizeContext* context, const OptimizeOptions& options)
      : options_(options), context_(context) {
  }

  int Run(std::unique_ptr<LoadedApk> apk) {
    if (context_->IsVerbose()) {
      context_->GetDiagnostics()->Note(android::DiagMessage() << "Optimizing APK...");
    }
    if (!options_.resources_exclude_list.empty()) {
      ResourceFilter filter(options_.resources_exclude_list);
      if (!filter.Consume(context_, apk->GetResourceTable())) {
        context_->GetDiagnostics()->Error(android::DiagMessage() << "failed filtering resources");
        return 1;
      }
    }

    VersionCollapser collapser;
    if (!collapser.Consume(context_, apk->GetResourceTable())) {
      return 1;
    }

    ResourceDeduper deduper;
    if (!deduper.Consume(context_, apk->GetResourceTable())) {
      context_->GetDiagnostics()->Error(android::DiagMessage() << "failed deduping resources");
      return 1;
    }

    Obfuscator obfuscator(options_);
    if (obfuscator.IsEnabled()) {
      if (!obfuscator.Consume(context_, apk->GetResourceTable())) {
        context_->GetDiagnostics()->Error(android::DiagMessage()
                                          << "failed shortening resource paths");
        return 1;
      }

      if (options_.obfuscation_map_path &&
          !obfuscator.WriteObfuscationMap(options_.obfuscation_map_path.value())) {
        context_->GetDiagnostics()->Error(android::DiagMessage()
                                          << "failed to write the obfuscation map to file");
        return 1;
      }

      // TODO(b/246489170): keep the old option and format until transform to the new one
      if (options_.shortened_paths_map_path
          && !WriteShortenedPathsMap(options_.table_flattener_options.shortened_path_map,
                                      options_.shortened_paths_map_path.value())) {
        context_->GetDiagnostics()->Error(android::DiagMessage()
                                          << "failed to write shortened resource paths to file");
        return 1;
      }
    }

    // Adjust the SplitConstraints so that their SDK version is stripped if it is less than or
    // equal to the minSdk.
    options_.split_constraints =
        AdjustSplitConstraintsForMinSdk(context_->GetMinSdkVersion(), options_.split_constraints);

    // Stripping the APK using the TableSplitter. The resource table is modified in place in the
    // LoadedApk.
    TableSplitter splitter(options_.split_constraints, options_.table_splitter_options);
    if (!splitter.VerifySplitConstraints(context_)) {
      return 1;
    }
    splitter.SplitTable(apk->GetResourceTable());

    auto path_iter = options_.split_paths.begin();
    auto split_constraints_iter = options_.split_constraints.begin();
    for (std::unique_ptr<ResourceTable>& split_table : splitter.splits()) {
      if (context_->IsVerbose()) {
        context_->GetDiagnostics()->Note(android::DiagMessage(*path_iter)
                                         << "generating split with configurations '"
                                         << util::Joiner(split_constraints_iter->configs, ", ")
                                         << "'");
      }

      // Generate an AndroidManifest.xml for each split.
      std::unique_ptr<xml::XmlResource> split_manifest =
          GenerateSplitManifest(options_.app_info, *split_constraints_iter);
      std::unique_ptr<IArchiveWriter> split_writer =
          CreateZipFileArchiveWriter(context_->GetDiagnostics(), *path_iter);
      if (!split_writer) {
        return 1;
      }

      if (!WriteSplitApk(split_table.get(), split_manifest.get(), split_writer.get())) {
        return 1;
      }

      ++path_iter;
      ++split_constraints_iter;
    }

    if (options_.apk_artifacts && options_.output_dir) {
      MultiApkGenerator generator{apk.get(), context_};
      MultiApkGeneratorOptions generator_options = {
          options_.output_dir.value(), options_.apk_artifacts.value(),
          options_.table_flattener_options, options_.kept_artifacts};
      if (!generator.FromBaseApk(generator_options)) {
        return 1;
      }
    }

    if (options_.output_path) {
      std::unique_ptr<IArchiveWriter> writer =
          CreateZipFileArchiveWriter(context_->GetDiagnostics(), options_.output_path.value());
      if (!apk->WriteToArchive(context_, options_.table_flattener_options, writer.get())) {
        return 1;
      }
    }

    return 0;
  }

 private:
  bool WriteSplitApk(ResourceTable* table, xml::XmlResource* manifest, IArchiveWriter* writer) {
    android::BigBuffer manifest_buffer(4096);
    XmlFlattener xml_flattener(&manifest_buffer, {});
    if (!xml_flattener.Consume(context_, manifest)) {
      return false;
    }

    android::BigBufferInputStream manifest_buffer_in(&manifest_buffer);
    if (!io::CopyInputStreamToArchive(context_, &manifest_buffer_in, "AndroidManifest.xml",
                                      ArchiveEntry::kCompress, writer)) {
      return false;
    }

    std::map<std::pair<ConfigDescription, StringPiece>, FileReference*> config_sorted_files;
    for (auto& pkg : table->packages) {
      for (auto& type : pkg->types) {
        // Sort by config and name, so that we get better locality in the zip file.
        config_sorted_files.clear();

        for (auto& entry : type->entries) {
          for (auto& config_value : entry->values) {
            auto* file_ref = ValueCast<FileReference>(config_value->value.get());
            if (file_ref == nullptr) {
              continue;
            }

            if (file_ref->file == nullptr) {
              ResourceNameRef name(pkg->name, type->named_type, entry->name);
              context_->GetDiagnostics()->Warn(android::DiagMessage(file_ref->GetSource())
                                               << "file for resource " << name << " with config '"
                                               << config_value->config << "' not found");
              continue;
            }

            const StringPiece entry_name = entry->name;
            config_sorted_files[std::make_pair(config_value->config, entry_name)] = file_ref;
          }
        }

        for (auto& entry : config_sorted_files) {
          FileReference* file_ref = entry.second;
          if (!io::CopyFileToArchivePreserveCompression(context_, file_ref->file, *file_ref->path,
                                                        writer)) {
            return false;
          }
        }
      }
    }

    android::BigBuffer table_buffer(4096);
    TableFlattener table_flattener(options_.table_flattener_options, &table_buffer);
    if (!table_flattener.Consume(context_, table)) {
      return false;
    }

    android::BigBufferInputStream table_buffer_in(&table_buffer);
    return io::CopyInputStreamToArchive(context_, &table_buffer_in, "resources.arsc",
                                        ArchiveEntry::kAlign, writer);
  }

  // TODO(b/246489170): keep the old option and format until transform to the new one
  bool WriteShortenedPathsMap(const std::map<std::string, std::string> &path_map,
                               const std::string &file_path) {
    std::stringstream ss;
    for (auto it = path_map.cbegin(); it != path_map.cend(); ++it) {
      ss << it->first << " -> " << it->second << "\n";
    }
    return WriteStringToFile(ss.str(), file_path);
  }

  OptimizeOptions options_;
  OptimizeContext* context_;
};

bool ExtractConfig(const std::string& path, IAaptContext* context, OptimizeOptions* options) {
  std::string content;
  if (!android::base::ReadFileToString(path, &content, true /*follow_symlinks*/)) {
    context->GetDiagnostics()->Error(android::DiagMessage(path) << "failed reading config file");
    return false;
  }
  return ParseResourceConfig(content, context, options->resources_exclude_list,
                             options->table_flattener_options.name_collapse_exemptions,
                             options->table_flattener_options.path_shorten_exemptions);
}

bool ExtractAppDataFromManifest(OptimizeContext* context, const LoadedApk* apk,
                                OptimizeOptions* out_options) {
  const xml::XmlResource* manifest = apk->GetManifest();
  if (manifest == nullptr) {
    return false;
  }

  auto app_info = ExtractAppInfoFromBinaryManifest(*manifest, context->GetDiagnostics());
  if (!app_info) {
    context->GetDiagnostics()->Error(android::DiagMessage()
                                     << "failed to extract data from AndroidManifest.xml");
    return false;
  }

  out_options->app_info = std::move(app_info.value());
  context->SetMinSdkVersion(out_options->app_info.min_sdk_version.value_or(0));
  return true;
}

int OptimizeCommand::Action(const std::vector<std::string>& args) {
  if (args.size() != 1u) {
    std::cerr << "must have one APK as argument.\n\n";
    Usage(&std::cerr);
    return 1;
  }

  const std::string& apk_path = args[0];
  OptimizeContext context;
  context.SetVerbose(verbose_);
  android::IDiagnostics* diag = context.GetDiagnostics();

  if (config_path_) {
    std::string& path = config_path_.value();
    std::optional<ConfigurationParser> for_path = ConfigurationParser::ForPath(path);
    if (for_path) {
      options_.apk_artifacts = for_path.value().WithDiagnostics(diag).Parse(apk_path);
      if (!options_.apk_artifacts) {
        diag->Error(android::DiagMessage() << "Failed to parse the output artifact list");
        return 1;
      }

    } else {
      diag->Error(android::DiagMessage() << "Could not parse config file " << path);
      return 1;
    }

    if (print_only_) {
      for (const OutputArtifact& artifact : options_.apk_artifacts.value()) {
        std::cout << artifact.name << std::endl;
      }
      return 0;
    }

    if (!kept_artifacts_.empty()) {
      for (const std::string& artifact_str : kept_artifacts_) {
        for (StringPiece artifact : util::Tokenize(artifact_str, ',')) {
          options_.kept_artifacts.emplace(artifact);
        }
      }
    }

    // Since we know that we are going to process the APK (not just print targets), make sure we
    // have somewhere to write them to.
    if (!options_.output_dir) {
      diag->Error(android::DiagMessage()
                  << "Output directory is required when using a configuration file");
      return 1;
    }
  } else if (print_only_) {
    diag->Error(android::DiagMessage()
                << "Asked to print artifacts without providing a configurations");
    return 1;
  }

  std::unique_ptr<LoadedApk> apk = LoadedApk::LoadApkFromPath(apk_path, context.GetDiagnostics());
  if (!apk) {
    return 1;
  }

  if (options_.force_sparse_encoding) {
    options_.table_flattener_options.sparse_entries = SparseEntriesMode::Forced;
  }

  if (target_densities_) {
    // Parse the target screen densities.
    for (StringPiece config_str : util::Tokenize(target_densities_.value(), ',')) {
      std::optional<uint16_t> target_density = ParseTargetDensityParameter(config_str, diag);
      if (!target_density) {
        return 1;
      }
      options_.table_splitter_options.preferred_densities.push_back(target_density.value());
    }
  }

  std::unique_ptr<IConfigFilter> filter;
  if (!configs_.empty()) {
    filter = ParseConfigFilterParameters(configs_, diag);
    if (filter == nullptr) {
      return 1;
    }
    options_.table_splitter_options.config_filter = filter.get();
  }

  // Parse the split parameters.
  for (const std::string& split_arg : split_args_) {
    options_.split_paths.emplace_back();
    options_.split_constraints.emplace_back();
    if (!ParseSplitParameter(split_arg, diag, &options_.split_paths.back(),
        &options_.split_constraints.back())) {
      return 1;
    }
  }

  if (resources_config_path_) {
    std::string& path = resources_config_path_.value();
    if (!ExtractConfig(path, &context, &options_)) {
      return 1;
    }
  }

  if (!ExtractAppDataFromManifest(&context, apk.get(), &options_)) {
    return 1;
  }

  Optimizer cmd(&context, options_);
  return cmd.Run(std::move(apk));
}

}  // namespace aapt
