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

#include "androidfw/StringPiece.h"

#include "Diagnostics.h"
#include "Flags.h"
#include "LoadedApk.h"
#include "ResourceUtils.h"
#include "SdkConstants.h"
#include "ValueVisitor.h"
#include "cmd/Util.h"
#include "flatten/TableFlattener.h"
#include "flatten/XmlFlattener.h"
#include "io/BigBufferInputStream.h"
#include "io/Util.h"
#include "optimize/ResourceDeduper.h"
#include "optimize/VersionCollapser.h"
#include "split/TableSplitter.h"

using android::StringPiece;

namespace aapt {

struct OptimizeOptions {
  // Path to the output APK.
  std::string output_path;

  // Details of the app extracted from the AndroidManifest.xml
  AppInfo app_info;

  // Split APK options.
  TableSplitterOptions table_splitter_options;

  // List of output split paths. These are in the same order as `split_constraints`.
  std::vector<std::string> split_paths;

  // List of SplitConstraints governing what resources go into each split. Ordered by `split_paths`.
  std::vector<SplitConstraints> split_constraints;

  TableFlattenerOptions table_flattener_options;
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

    std::unique_ptr<IArchiveWriter> writer =
        CreateZipFileArchiveWriter(context_->GetDiagnostics(), options_.output_path);
    if (!apk->WriteToArchive(context_, options_.table_flattener_options, writer.get())) {
      return 1;
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
            FileReference* file_ref = ValueCast<FileReference>(config_value->value.get());
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
          uint32_t compression_flags =
              file_ref->file->WasCompressed() ? ArchiveEntry::kCompress : 0u;
          if (!io::CopyFileToArchive(context_, file_ref->file, *file_ref->path, compression_flags,
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
    if (!io::CopyInputStreamToArchive(context_, &table_buffer_in, "resources.arsc",
                                      ArchiveEntry::kAlign, writer)) {
      return false;
    }
    return true;
  }

  OptimizeOptions options_;
  OptimizeContext* context_;
};

bool ExtractAppDataFromManifest(OptimizeContext* context, LoadedApk* apk,
                                OptimizeOptions* out_options) {
  io::IFile* manifest_file = apk->GetFileCollection()->FindFile("AndroidManifest.xml");
  if (manifest_file == nullptr) {
    context->GetDiagnostics()->Error(DiagMessage(apk->GetSource())
                                     << "missing AndroidManifest.xml");
    return false;
  }

  std::unique_ptr<io::IData> data = manifest_file->OpenAsData();
  if (data == nullptr) {
    context->GetDiagnostics()->Error(DiagMessage(manifest_file->GetSource())
                                     << "failed to open file");
    return false;
  }

  std::unique_ptr<xml::XmlResource> manifest = xml::Inflate(
      data->data(), data->size(), context->GetDiagnostics(), manifest_file->GetSource());
  if (manifest == nullptr) {
    context->GetDiagnostics()->Error(DiagMessage() << "failed to read binary AndroidManifest.xml");
    return false;
  }

  Maybe<AppInfo> app_info =
      ExtractAppInfoFromBinaryManifest(manifest.get(), context->GetDiagnostics());
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
  Maybe<std::string> target_densities;
  std::vector<std::string> configs;
  std::vector<std::string> split_args;
  bool verbose = false;
  Flags flags =
      Flags()
          .RequiredFlag("-o", "Path to the output APK.", &options.output_path)
          .OptionalFlag(
              "--target-densities",
              "Comma separated list of the screen densities that the APK will be optimized for.\n"
              "All the resources that would be unused on devices of the given densities will be \n"
              "removed from the APK.",
              &target_densities)
          .OptionalFlagList("-c",
                            "Comma separated list of configurations to include. The default\n"
                            "is all configurations.",
                            &configs)
          .OptionalFlagList("--split",
                            "Split resources matching a set of configs out to a "
                            "Split APK.\nSyntax: path/to/output.apk;<config>[,<config>[...]].\n"
                            "On Windows, use a semicolon ';' separator instead.",
                            &split_args)
          .OptionalSwitch("--enable-sparse-encoding",
                          "Enables encoding sparse entries using a binary search tree.\n"
                          "This decreases APK size at the cost of resource retrieval performance.",
                          &options.table_flattener_options.use_sparse_entries)
          .OptionalSwitch("-v", "Enables verbose logging", &verbose);

  if (!flags.Parse("aapt2 optimize", args, &std::cerr)) {
    return 1;
  }

  if (flags.GetArgs().size() != 1u) {
    std::cerr << "must have one APK as argument.\n\n";
    flags.Usage("aapt2 optimize", &std::cerr);
    return 1;
  }

  std::unique_ptr<LoadedApk> apk = LoadedApk::LoadApkFromPath(&context, flags.GetArgs()[0]);
  if (!apk) {
    return 1;
  }

  context.SetVerbose(verbose);

  if (target_densities) {
    // Parse the target screen densities.
    for (const StringPiece& config_str : util::Tokenize(target_densities.value(), ',')) {
      Maybe<uint16_t> target_density =
          ParseTargetDensityParameter(config_str, context.GetDiagnostics());
      if (!target_density) {
        return 1;
      }
      options.table_splitter_options.preferred_densities.push_back(target_density.value());
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

  // Parse the split parameters.
  for (const std::string& split_arg : split_args) {
    options.split_paths.push_back({});
    options.split_constraints.push_back({});
    if (!ParseSplitParameter(split_arg, context.GetDiagnostics(), &options.split_paths.back(),
                             &options.split_constraints.back())) {
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
