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
#include "split/TableSplitter.h"

using android::StringPiece;

namespace aapt {

struct StripOptions {
  /** Path to the output APK. */
  std::string output_path;

  /** List of screen density configurations the APK will be optimized for. */
  std::vector<ConfigDescription> target_configs;
};

class StripContext : public IAaptContext {
 public:
  IDiagnostics* GetDiagnostics() override { return &diagnostics_; }

  NameMangler* GetNameMangler() override {
    abort();
    return nullptr;
  }

  const std::string& GetCompilationPackage() override {
    static std::string empty;
    return empty;
  }

  uint8_t GetPackageId() override { return 0; }

  SymbolTable* GetExternalSymbols() override {
    abort();
    return nullptr;
  }

  bool IsVerbose() override { return verbose_; }

  void SetVerbose(bool val) { verbose_ = val; }

  int GetMinSdkVersion() override { return 0; }

 private:
  StdErrDiagnostics diagnostics_;
  bool verbose_ = false;
};

class StripCommand {
 public:
  StripCommand(StripContext* context, const StripOptions& options)
      : options_(options),
        context_(context) {}

  int Run(std::unique_ptr<LoadedApk> apk) {
    if (context_->IsVerbose()) {
      context_->GetDiagnostics()->Note(DiagMessage() << "Stripping APK...");
    }

    // Stripping the APK using the TableSplitter with no splits and the target
    // densities as the preferred densities. The resource table is modified in
    // place in the LoadedApk.
    TableSplitterOptions splitter_options;
    for (auto& config : options_.target_configs) {
      splitter_options.preferred_densities.push_back(config.density);
    }
    std::vector<SplitConstraints> splits;
    TableSplitter splitter(splits, splitter_options);
    splitter.SplitTable(apk->GetResourceTable());

    std::unique_ptr<IArchiveWriter> writer =
        CreateZipFileArchiveWriter(context_->GetDiagnostics(), options_.output_path);
    if (!apk->WriteToArchive(context_, writer.get())) {
      return 1;
    }

    return 0;
  }

 private:
  StripOptions options_;
  StripContext* context_;
};

int Strip(const std::vector<StringPiece>& args) {
  StripContext context;
  StripOptions options;
  std::string target_densities;
  bool verbose = false;
  Flags flags =
      Flags()
          .RequiredFlag("-o", "Path to the output APK.", &options.output_path)
          .RequiredFlag(
              "--target-densities",
              "Comma separated list of the screen densities that the APK will "
              "be optimized for. All the resources that would be unused on "
              "devices of the given densities will be removed from the APK.",
              &target_densities)
          .OptionalSwitch("-v", "Enables verbose logging", &verbose);

  if (!flags.Parse("aapt2 strip", args, &std::cerr)) {
    return 1;
  }

  if (flags.GetArgs().size() != 1u) {
    std::cerr << "must have one APK as argument.\n\n";
    flags.Usage("aapt2 strip", &std::cerr);
    return 1;
  }

  std::unique_ptr<LoadedApk> apk =
      LoadedApk::LoadApkFromPath(&context, flags.GetArgs()[0]);
  if (!apk) {
    return 1;
  }

  if (verbose) {
    context.SetVerbose(verbose);
  }

  // Parse the target screen densities.
  for (const StringPiece& config_str : util::Tokenize(target_densities, ',')) {
    ConfigDescription config;
    if (!ConfigDescription::Parse(config_str, &config) || config.density == 0) {
      context.GetDiagnostics()->Error(
          DiagMessage() << "invalid density '" << config_str
                        << "' for --target-densities option");
      return 1;
    }

    // Clear the version that can be automatically added.
    config.sdkVersion = 0;

    if (config.diff(ConfigDescription::DefaultConfig()) !=
        ConfigDescription::CONFIG_DENSITY) {
      context.GetDiagnostics()->Error(
          DiagMessage() << "invalid density '" << config_str
                        << "' for --target-densities option. Must be only a "
                        << "density value.");
      return 1;
    }

    options.target_configs.push_back(config);
  }

  StripCommand cmd(&context, options);
  return cmd.Run(std::move(apk));
}

}  // namespace aapt
