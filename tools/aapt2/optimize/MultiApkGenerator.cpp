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

#include "MultiApkGenerator.h"

#include <algorithm>
#include <string>

#include "androidfw/StringPiece.h"

#include "LoadedApk.h"
#include "configuration/ConfigurationParser.h"
#include "filter/AbiFilter.h"
#include "filter/Filter.h"
#include "flatten/Archive.h"
#include "optimize/VersionCollapser.h"
#include "process/IResourceTableConsumer.h"
#include "split/TableSplitter.h"
#include "util/Files.h"

namespace aapt {

using ::aapt::configuration::AndroidSdk;
using ::aapt::configuration::Artifact;
using ::aapt::configuration::PostProcessingConfiguration;
using ::android::StringPiece;

/**
 * Context wrapper that allows the min Android SDK value to be overridden.
 */
class ContextWrapper : public IAaptContext {
 public:
  explicit ContextWrapper(IAaptContext* context)
      : context_(context), min_sdk_(context_->GetMinSdkVersion()) {
  }

  PackageType GetPackageType() override {
    return context_->GetPackageType();
  }

  SymbolTable* GetExternalSymbols() override {
    return context_->GetExternalSymbols();
  }

  IDiagnostics* GetDiagnostics() override {
    return context_->GetDiagnostics();
  }

  const std::string& GetCompilationPackage() override {
    return context_->GetCompilationPackage();
  }

  uint8_t GetPackageId() override {
    return context_->GetPackageId();
  }

  NameMangler* GetNameMangler() override {
    return context_->GetNameMangler();
  }

  bool IsVerbose() override {
    return context_->IsVerbose();
  }

  int GetMinSdkVersion() override {
    return min_sdk_;
  }

  void SetMinSdkVersion(int min_sdk) {
    min_sdk_ = min_sdk;
  }

 private:
  IAaptContext* context_;

  int min_sdk_ = -1;
};

MultiApkGenerator::MultiApkGenerator(LoadedApk* apk, IAaptContext* context)
    : apk_(apk), context_(context) {
}

bool MultiApkGenerator::FromBaseApk(const MultiApkGeneratorOptions& options) {
  // TODO(safarmer): Handle APK version codes for the generated APKs.
  IDiagnostics* diag = context_->GetDiagnostics();
  const PostProcessingConfiguration& config = options.config;

  const std::string& apk_name = file::GetFilename(apk_->GetSource().path).to_string();
  const StringPiece ext = file::GetExtension(apk_name);
  const std::string base_name = apk_name.substr(0, apk_name.rfind(ext.to_string()));

  // For now, just write out the stripped APK since ABI splitting doesn't modify anything else.
  for (const Artifact& artifact : config.artifacts) {
    FilterChain filters;

    if (!artifact.name && !config.artifact_format) {
      diag->Error(
          DiagMessage() << "Artifact does not have a name and no global name template defined");
      return false;
    }

    Maybe<std::string> artifact_name =
        (artifact.name) ? artifact.Name(apk_name, diag)
                        : artifact.ToArtifactName(config.artifact_format.value(), apk_name, diag);

    if (!artifact_name) {
      diag->Error(DiagMessage() << "Could not determine split APK artifact name");
      return false;
    }

    std::unique_ptr<ResourceTable> table =
        FilterTable(artifact, config, *apk_->GetResourceTable(), &filters);
    if (!table) {
      return false;
    }

    std::string out = options.out_dir;
    if (!file::mkdirs(out)) {
      context_->GetDiagnostics()->Warn(DiagMessage() << "could not create out dir: " << out);
    }
    file::AppendPath(&out, artifact_name.value());

    if (context_->IsVerbose()) {
      context_->GetDiagnostics()->Note(DiagMessage() << "Generating split: " << out);
    }

    std::unique_ptr<IArchiveWriter> writer =
        CreateZipFileArchiveWriter(context_->GetDiagnostics(), out);

    if (context_->IsVerbose()) {
      diag->Note(DiagMessage() << "Writing output: " << out);
    }

    if (!apk_->WriteToArchive(context_, table.get(), options.table_flattener_options, &filters,
                              writer.get())) {
      return false;
    }
  }

  return true;
}

std::unique_ptr<ResourceTable> MultiApkGenerator::FilterTable(
    const configuration::Artifact& artifact,
    const configuration::PostProcessingConfiguration& config, const ResourceTable& old_table,
    FilterChain* filters) {
  TableSplitterOptions splits;
  AxisConfigFilter axis_filter;
  ContextWrapper wrappedContext{context_};

  if (artifact.abi_group) {
    const std::string& group_name = artifact.abi_group.value();

    auto group = config.abi_groups.find(group_name);
    // TODO: Remove validation when configuration parser ensures referential integrity.
    if (group == config.abi_groups.end()) {
      context_->GetDiagnostics()->Error(DiagMessage() << "could not find referenced ABI group '"
                                                      << group_name << "'");
      return {};
    }
    filters->AddFilter(AbiFilter::FromAbiList(group->second));
  }

  if (artifact.screen_density_group) {
    const std::string& group_name = artifact.screen_density_group.value();

    auto group = config.screen_density_groups.find(group_name);
    // TODO: Remove validation when configuration parser ensures referential integrity.
    if (group == config.screen_density_groups.end()) {
      context_->GetDiagnostics()->Error(DiagMessage() << "could not find referenced group '"
                                                      << group_name << "'");
      return {};
    }

    const std::vector<ConfigDescription>& densities = group->second;
    for(const auto& density_config : densities) {
      splits.preferred_densities.push_back(density_config.density);
    }
  }

  if (artifact.locale_group) {
    const std::string& group_name = artifact.locale_group.value();
    auto group = config.locale_groups.find(group_name);
    // TODO: Remove validation when configuration parser ensures referential integrity.
    if (group == config.locale_groups.end()) {
      context_->GetDiagnostics()->Error(DiagMessage() << "could not find referenced group '"
                                                      << group_name << "'");
      return {};
    }

    const std::vector<ConfigDescription>& locales = group->second;
    for (const auto& locale : locales) {
      axis_filter.AddConfig(locale);
    }
    splits.config_filter = &axis_filter;
  }

  if (artifact.android_sdk_group) {
    const std::string& group_name = artifact.android_sdk_group.value();
    auto group = config.android_sdk_groups.find(group_name);
    // TODO: Remove validation when configuration parser ensures referential integrity.
    if (group == config.android_sdk_groups.end()) {
      context_->GetDiagnostics()->Error(DiagMessage() << "could not find referenced group '"
                                                      << group_name << "'");
      return {};
    }

    const AndroidSdk& sdk = group->second;
    if (!sdk.min_sdk_version) {
      context_->GetDiagnostics()->Error(DiagMessage()
                                        << "skipping SDK version. No min SDK: " << group_name);
      return {};
    }

    ConfigDescription c;
    const std::string& version = sdk.min_sdk_version.value();
    if (!ConfigDescription::Parse(version, &c)) {
      context_->GetDiagnostics()->Error(DiagMessage() << "could not parse min SDK: " << version);
      return {};
    }

    wrappedContext.SetMinSdkVersion(c.sdkVersion);
  }

  std::unique_ptr<ResourceTable> table = old_table.Clone();

  VersionCollapser collapser;
  if (!collapser.Consume(context_, table.get())) {
    context_->GetDiagnostics()->Error(DiagMessage() << "Failed to strip versioned resources");
    return {};
  }

  TableSplitter splitter{{}, splits};
  splitter.SplitTable(table.get());
  return table;
}

}  // namespace aapt
