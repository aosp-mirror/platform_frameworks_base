/*
 * Copyright (C) 2018 The Android Open Source Project
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

#ifndef AAPT2_OPTIMIZE_H
#define AAPT2_OPTIMIZE_H

#include "AppInfo.h"
#include "Command.h"
#include "configuration/ConfigurationParser.h"
#include "format/binary/TableFlattener.h"
#include "split/TableSplitter.h"

namespace aapt {

struct OptimizeOptions {
  friend class OptimizeCommand;

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

  Maybe<std::vector<aapt::configuration::OutputArtifact>> apk_artifacts;

  // Set of artifacts to keep when generating multi-APK splits. If the list is empty, all artifacts
  // are kept and will be written as output.
  std::unordered_set<std::string> kept_artifacts;

  // Whether or not to shorten resource paths in the APK.
  bool shorten_resource_paths = false;

  // Path to the output map of original resource paths to shortened paths.
  Maybe<std::string> shortened_paths_map_path;
};

class OptimizeCommand : public Command {
 public:
  explicit OptimizeCommand() : Command("optimize") {
    SetDescription("Preforms resource optimizations on an apk.");
    AddOptionalFlag("-o", "Path to the output APK.", &options_.output_path, Command::kPath);
    AddOptionalFlag("-d", "Path to the output directory (for splits).", &options_.output_dir,
        Command::kPath);
    AddOptionalFlag("-x", "Path to XML configuration file.", &config_path_, Command::kPath);
    AddOptionalSwitch("-p", "Print the multi APK artifacts and exit.", &print_only_);
    AddOptionalFlag(
        "--target-densities",
        "Comma separated list of the screen densities that the APK will be optimized for.\n"
            "All the resources that would be unused on devices of the given densities will be \n"
            "removed from the APK.",
        &target_densities_);
    AddOptionalFlag("--whitelist-path",
        "Path to the whitelist.cfg file containing whitelisted resources \n"
            "whose names should not be altered in final resource tables.",
        &whitelist_path_);
    AddOptionalFlag("--resources-config-path",
        "Path to the resources.cfg file containing the list of resources and \n"
            "directives to each resource. \n"
            "Format: type/resource_name#[directive][,directive]",
        &resources_config_path_);
    AddOptionalFlagList("-c",
        "Comma separated list of configurations to include. The default\n"
            "is all configurations.",
        &configs_);
    AddOptionalFlagList("--split",
        "Split resources matching a set of configs out to a "
            "Split APK.\nSyntax: path/to/output.apk;<config>[,<config>[...]].\n"
            "On Windows, use a semicolon ';' separator instead.",
        &split_args_);
    AddOptionalFlagList("--keep-artifacts",
        "Comma separated list of artifacts to keep. If none are specified,\n"
            "all artifacts will be kept.",
        &kept_artifacts_);
    AddOptionalSwitch("--enable-sparse-encoding",
        "Enables encoding sparse entries using a binary search tree.\n"
            "This decreases APK size at the cost of resource retrieval performance.",
        &options_.table_flattener_options.use_sparse_entries);
    AddOptionalSwitch("--collapse-resource-names",
        "Collapses resource names to a single value in the key string pool. Resources can \n"
            "be exempted using the \"no_collapse\" directive in a file specified by "
            "--resources-config-path.",
        &options_.table_flattener_options.collapse_key_stringpool);
    AddOptionalSwitch("--shorten-resource-paths",
        "Shortens the paths of resources inside the APK.",
        &options_.shorten_resource_paths);
    AddOptionalFlag("--resource-path-shortening-map",
        "Path to output the map of old resource paths to shortened paths.",
        &options_.shortened_paths_map_path);
    AddOptionalSwitch("-v", "Enables verbose logging", &verbose_);
  }

  int Action(const std::vector<std::string>& args) override;

 private:
  OptimizeOptions options_;

  bool WriteObfuscatedPathsMap(const std::map<std::string, std::string> &path_map,
                               const std::string &file_path);

  Maybe<std::string> config_path_;
  Maybe<std::string> whitelist_path_;
  Maybe<std::string> resources_config_path_;
  Maybe<std::string> target_densities_;
  std::vector<std::string> configs_;
  std::vector<std::string> split_args_;
  std::unordered_set<std::string> kept_artifacts_;
  bool print_only_ = false;
  bool verbose_ = false;
};

}// namespace aapt

#endif //AAPT2_OPTIMIZE_H
