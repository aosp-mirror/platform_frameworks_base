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

#include <memory>
#include <vector>

#include "android-base/file.h"
#include "android-base/stringprintf.h"

#include "androidfw/ResourceTypes.h"
#include "androidfw/StringPiece.h"

#include "Diagnostics.h"
#include "Flags.h"
#include "LoadedApk.h"
#include "ResourceUtils.h"
#include "SdkConstants.h"
#include "ValueVisitor.h"
#include "cmd/Util.h"
#include "configuration/ConfigurationParser.h"
#include "filter/AbiFilter.h"
#include "format/binary/TableFlattener.h"
#include "format/binary/XmlFlattener.h"
#include "io/BigBufferStream.h"
#include "io/Util.h"
#include "optimize/MultiApkGenerator.h"
#include "optimize/ResourceDeduper.h"
#include "optimize/ResourceFilter.h"
#include "optimize/VersionCollapser.h"
#include "split/TableSplitter.h"
#include "util/Files.h"
#include "util/Util.h"

using ::aapt::configuration::Abi;
using ::aapt::configuration::OutputArtifact;
using ::android::ResTable_config;
using ::android::StringPiece;
using ::android::base::ReadFileToString;
using ::android::base::StringAppendF;
using ::android::base::StringPrintf;

namespace aapt {

struct OptimizeOptions {
  // Path to the output APK.
  Maybe<std::string> output_path;
  // Path to the output APK directory for splits.
  Maybe<std::string> output_dir;

  // Details of the app extracted from the AndroidManifest.xml
  AppInfo app_info;

  // Blacklist of unused resources that should be removed from the apk.
  std::unordered_set<ResourceName> resources_blacklist;

  // Split APK options.
  TableSplitterOptions table_splitter_options;

  // List of output split paths. These are in the same order as `split_constraints`.
  std::vector<std::string> split_paths;

  // List of SplitConstraints governing what resources go into each split. Ordered by `split_paths`.
  std::vector<SplitConstraints> split_constraints;

  TableFlattenerOptions table_flattener_options;

  Maybe<std::vector<OutputArtifact>> apk_artifacts;

  // Set of artifacts to keep when generating multi-APK splits. If the list is empty, all artifacts
  // are kept and will be written as output.
  std::unordered_set<std::string> kept_artifacts;
};

class OptimizeContext : public IAaptContext {
 public:
  OptimizeContext() = default;

  PackageType GetPackageType() override {
    // Not important here. Using anything other than kApp adds EXTRA validation, which we want to
    // avoid.
    return PackageType::kApp;
  }

  IDiagnostics* GetDiagnostics() override {
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
  }

  void SetMinSdkVersion(int sdk_version) {
    sdk_version_ = sdk_version;
  }

  int GetMinSdkVersion() override {
    return sdk_version_;
  }

 private:
  DISALLOW_COPY_AND_ASSIGN(OptimizeContext);

  StdErrDiagnostics diagnostics_;
  bool verbose_ = false;
  int sdk_version_ = 0;
};

class OptimizeCommand {
 public:
  OptimizeCommand(OptimizeContext* context, const OptimizeOptions& options)
      : options_(options), context_(context) {
  }

  int Run(std::unique_ptr<LoadedApk> apk) {
    if (context_->IsVerbose()) {
      context_->GetDiagnostics()->Note(DiagMessage() << "Optimizing APK...");
    }
    if (!options_.resources_blacklist.empty()) {
      ResourceFilter filter(options_.resources_blacklist);
      if (!filter.Consume(context_, apk->GetResourceTable())) {
        context_->GetDiagnostics()->Error(DiagMessage() << "failed filtering resources");
        return 1;
      }
    }

    VersionCollapser collapser;
    if (!collapser.Consume(context_, apk->GetResourceTable())) {
      return 1;
    }

    ResourceDeduper deduper;
    if (!deduper.Consume(context_, apk->GetResourceTable())) {
      context_->GetDiagnostics()->Error(DiagMessage() << "failed deduping resources");
      return 1;
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
        context_->GetDiagnostics()->Note(
            DiagMessage(*path_iter) << "generating split with configurations '"
                                    << util::Joiner(split_constraints_iter->configs, ", ") << "'");
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
    BigBuffer manifest_buffer(4096);
    XmlFlattener xml_flattener(&manifest_buffer, {});
    if (!xml_flattener.Consume(context_, manifest)) {
      return false;
    }

    io::BigBufferInputStream manifest_buffer_in(&manifest_buffer);
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
              ResourceNameRef name(pkg->name, type->type, entry->name);
              context_->GetDiagnostics()->Warn(DiagMessage(file_ref->GetSource())
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

    BigBuffer table_buffer(4096);
    TableFlattener table_flattener(options_.table_flattener_options, &table_buffer);
    if (!table_flattener.Consume(context_, table)) {
      return false;
    }

    io::BigBufferInputStream table_buffer_in(&table_buffer);
    return io::CopyInputStreamToArchive(context_, &table_buffer_in, "resources.arsc",
                                        ArchiveEntry::kAlign, writer);
  }

  OptimizeOptions options_;
  OptimizeContext* context_;
};

bool ExtractObfuscationWhitelistFromConfig(const std::string& path, OptimizeContext* context,
                                           OptimizeOptions* options) {
  std::string contents;
  if (!ReadFileToString(path, &contents, true)) {
    context->GetDiagnostics()->Error(DiagMessage()
                                     << "failed to parse whitelist from config file: " << path);
    return false;
  }
  for (StringPiece resource_name : util::Tokenize(contents, ',')) {
    options->table_flattener_options.whitelisted_resources.insert(
        resource_name.to_string());
  }
  return true;
}

bool ExtractConfig(const std::string& path, OptimizeContext* context,
                                    OptimizeOptions* options) {
  std::string content;
  if (!android::base::ReadFileToString(path, &content, true /*follow_symlinks*/)) {
    context->GetDiagnostics()->Error(DiagMessage(path) << "failed reading whitelist");
    return false;
  }

  size_t line_no = 0;
  for (StringPiece line : util::Tokenize(content, '\n')) {
    line_no++;
    line = util::TrimWhitespace(line);
    if (line.empty()) {
      continue;
    }

    auto split_line = util::Split(line, '#');
    if (split_line.size() < 2) {
      context->GetDiagnostics()->Error(DiagMessage(line) << "No # found in line");
      return false;
    }
    StringPiece resource_string = split_line[0];
    StringPiece directives = split_line[1];
    ResourceNameRef resource_name;
    if (!ResourceUtils::ParseResourceName(resource_string, &resource_name)) {
      context->GetDiagnostics()->Error(DiagMessage(line) << "Malformed resource name");
      return false;
    }
    if (!resource_name.package.empty()) {
      context->GetDiagnostics()->Error(DiagMessage(line)
                                       << "Package set for resource. Only use type/name");
      return false;
    }
    for (StringPiece directive : util::Tokenize(directives, ',')) {
      if (directive == "remove") {
        options->resources_blacklist.insert(resource_name.ToResourceName());
      } else if (directive == "no_obfuscate") {
        options->table_flattener_options.whitelisted_resources.insert(
            resource_name.entry.to_string());
      }
    }
  }
  return true;
}

bool ExtractAppDataFromManifest(OptimizeContext* context, const LoadedApk* apk,
                                OptimizeOptions* out_options) {
  const xml::XmlResource* manifest = apk->GetManifest();
  if (manifest == nullptr) {
    return false;
  }

  Maybe<AppInfo> app_info = ExtractAppInfoFromBinaryManifest(*manifest, context->GetDiagnostics());
  if (!app_info) {
    context->GetDiagnostics()->Error(DiagMessage()
                                     << "failed to extract data from AndroidManifest.xml");
    return false;
  }

  out_options->app_info = std::move(app_info.value());
  context->SetMinSdkVersion(out_options->app_info.min_sdk_version.value_or_default(0));
  return true;
}

int Optimize(const std::vector<StringPiece>& args) {
  OptimizeContext context;
  OptimizeOptions options;
  Maybe<std::string> config_path;
  Maybe<std::string> whitelist_path;
  Maybe<std::string> resources_config_path;
  Maybe<std::string> target_densities;
  std::vector<std::string> configs;
  std::vector<std::string> split_args;
  std::unordered_set<std::string> kept_artifacts;
  bool verbose = false;
  bool print_only = false;
  Flags flags =
      Flags()
          .OptionalFlag("-o", "Path to the output APK.", &options.output_path)
          .OptionalFlag("-d", "Path to the output directory (for splits).", &options.output_dir)
          .OptionalFlag("-x", "Path to XML configuration file.", &config_path)
          .OptionalSwitch("-p", "Print the multi APK artifacts and exit.", &print_only)
          .OptionalFlag(
              "--target-densities",
              "Comma separated list of the screen densities that the APK will be optimized for.\n"
              "All the resources that would be unused on devices of the given densities will be \n"
              "removed from the APK.",
              &target_densities)
          .OptionalFlag("--whitelist-path",
                        "Path to the whitelist.cfg file containing whitelisted resources \n"
                        "whose names should not be altered in final resource tables.",
                        &whitelist_path)
          .OptionalFlag("--resources-config-path",
                        "Path to the resources.cfg file containing the list of resources and \n"
                        "directives to each resource. \n"
                        "Format: type/resource_name#[directive][,directive]",
                        &resources_config_path)
          .OptionalFlagList("-c",
                            "Comma separated list of configurations to include. The default\n"
                            "is all configurations.",
                            &configs)
          .OptionalFlagList("--split",
                            "Split resources matching a set of configs out to a "
                            "Split APK.\nSyntax: path/to/output.apk;<config>[,<config>[...]].\n"
                            "On Windows, use a semicolon ';' separator instead.",
                            &split_args)
          .OptionalFlagList("--keep-artifacts",
                            "Comma separated list of artifacts to keep. If none are specified,\n"
                            "all artifacts will be kept.",
                            &kept_artifacts)
          .OptionalSwitch("--enable-sparse-encoding",
                          "Enables encoding sparse entries using a binary search tree.\n"
                          "This decreases APK size at the cost of resource retrieval performance.",
                          &options.table_flattener_options.use_sparse_entries)
          .OptionalSwitch("--enable-resource-obfuscation",
                          "Enables obfuscation of key string pool to single value",
                          &options.table_flattener_options.collapse_key_stringpool)
          .OptionalSwitch("-v", "Enables verbose logging", &verbose);

  if (!flags.Parse("aapt2 optimize", args, &std::cerr)) {
    return 1;
  }

  if (flags.GetArgs().size() != 1u) {
    std::cerr << "must have one APK as argument.\n\n";
    flags.Usage("aapt2 optimize", &std::cerr);
    return 1;
  }

  const std::string& apk_path = flags.GetArgs()[0];

  context.SetVerbose(verbose);
  IDiagnostics* diag = context.GetDiagnostics();

  if (config_path) {
    std::string& path = config_path.value();
    Maybe<ConfigurationParser> for_path = ConfigurationParser::ForPath(path);
    if (for_path) {
      options.apk_artifacts = for_path.value().WithDiagnostics(diag).Parse(apk_path);
      if (!options.apk_artifacts) {
        diag->Error(DiagMessage() << "Failed to parse the output artifact list");
        return 1;
      }

    } else {
      diag->Error(DiagMessage() << "Could not parse config file " << path);
      return 1;
    }

    if (print_only) {
      for (const OutputArtifact& artifact : options.apk_artifacts.value()) {
        std::cout << artifact.name << std::endl;
      }
      return 0;
    }

    if (!kept_artifacts.empty()) {
      for (const std::string& artifact_str : kept_artifacts) {
        for (const StringPiece& artifact : util::Tokenize(artifact_str, ',')) {
          options.kept_artifacts.insert(artifact.to_string());
        }
      }
    }

    // Since we know that we are going to process the APK (not just print targets), make sure we
    // have somewhere to write them to.
    if (!options.output_dir) {
      diag->Error(DiagMessage() << "Output directory is required when using a configuration file");
      return 1;
    }
  } else if (print_only) {
    diag->Error(DiagMessage() << "Asked to print artifacts without providing a configurations");
    return 1;
  }

  std::unique_ptr<LoadedApk> apk = LoadedApk::LoadApkFromPath(apk_path, context.GetDiagnostics());
  if (!apk) {
    return 1;
  }

  if (target_densities) {
    // Parse the target screen densities.
    for (const StringPiece& config_str : util::Tokenize(target_densities.value(), ',')) {
      Maybe<uint16_t> target_density = ParseTargetDensityParameter(config_str, diag);
      if (!target_density) {
        return 1;
      }
      options.table_splitter_options.preferred_densities.push_back(target_density.value());
    }
  }

  std::unique_ptr<IConfigFilter> filter;
  if (!configs.empty()) {
    filter = ParseConfigFilterParameters(configs, diag);
    if (filter == nullptr) {
      return 1;
    }
    options.table_splitter_options.config_filter = filter.get();
  }

  // Parse the split parameters.
  for (const std::string& split_arg : split_args) {
    options.split_paths.emplace_back();
    options.split_constraints.emplace_back();
    if (!ParseSplitParameter(split_arg, diag, &options.split_paths.back(),
                             &options.split_constraints.back())) {
      return 1;
    }
  }

  if (options.table_flattener_options.collapse_key_stringpool) {
    if (whitelist_path) {
      std::string& path = whitelist_path.value();
      if (!ExtractObfuscationWhitelistFromConfig(path, &context, &options)) {
        return 1;
      }
    }
  }

  if (resources_config_path) {
    std::string& path = resources_config_path.value();
    if (!ExtractConfig(path, &context, &options)) {
      return 1;
    }
  }

  if (!ExtractAppDataFromManifest(&context, apk.get(), &options)) {
    return 1;
  }

  OptimizeCommand cmd(&context, options);
  return cmd.Run(std::move(apk));
}

}  // namespace aapt
