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

#ifndef AAPT2_CONVERT_H
#define AAPT2_CONVERT_H

#include <optional>

#include "Command.h"
#include "LoadedApk.h"
#include "format/binary/TableFlattener.h"
#include "format/binary/XmlFlattener.h"

namespace aapt {

class ConvertCommand : public Command {
 public:
  explicit ConvertCommand() : Command("convert") {
    SetDescription("Converts an apk between binary and proto formats.");
    AddRequiredFlag("-o", "Output path", &output_path_, Command::kPath);
    AddOptionalFlag("--output-format", android::base::StringPrintf("Format of the output. "
            "Accepted values are '%s' and '%s'. When not set, defaults to '%s'.",
        kOutputFormatProto, kOutputFormatBinary, kOutputFormatBinary), &output_format_);
    AddOptionalSwitch(
        "--enable-sparse-encoding",
        "Enables encoding sparse entries using a binary search tree.\n"
        "This decreases APK size at the cost of resource retrieval performance.\n"
        "Only applies sparse encoding to Android O+ resources or all resources if minSdk of "
        "the APK is O+",
        &enable_sparse_encoding_);
    AddOptionalSwitch("--force-sparse-encoding",
                      "Enables encoding sparse entries using a binary search tree.\n"
                      "This decreases APK size at the cost of resource retrieval performance.\n"
                      "Applies sparse encoding to all resources regardless of minSdk.",
                      &force_sparse_encoding_);
    AddOptionalSwitch(
        "--enable-compact-entries",
        "This decreases APK size by using compact resource entries for simple data types.",
        &enable_compact_entries_);
    AddOptionalSwitch("--keep-raw-values",
        android::base::StringPrintf("Preserve raw attribute values in xml files when using the"
            " '%s' output format", kOutputFormatBinary),
        &xml_flattener_options_.keep_raw_values);
    AddOptionalFlag("--resources-config-path",
                    "Path to the resources.cfg file containing the list of resources and \n"
                    "directives to each resource. \n"
                    "Format: type/resource_name#[directive][,directive]",
                    &resources_config_path_);
    AddOptionalSwitch(
        "--collapse-resource-names",
        "Collapses resource names to a single value in the key string pool. Resources can \n"
        "be exempted using the \"no_collapse\" directive in a file specified by "
        "--resources-config-path.",
        &table_flattener_options_.collapse_key_stringpool);
    AddOptionalSwitch(
        "--deduplicate-entry-values",
        "Whether to deduplicate pairs of resource entry and value for simple resources.\n"
        "This is recommended to be used together with '--collapse-resource-names' flag or for\n"
        "APKs where resource names are manually collapsed. For such APKs this flag allows to\n"
        "store the same resource value only once in resource table which decreases APK size.\n"
        "Has no effect on APKs where resource names are kept.",
        &table_flattener_options_.deduplicate_entry_values);
    AddOptionalSwitch("-v", "Enables verbose logging", &verbose_);
  }

  int Action(const std::vector<std::string>& args) override;

 private:
  const static char* kOutputFormatProto;
  const static char* kOutputFormatBinary;

  TableFlattenerOptions table_flattener_options_;
  XmlFlattenerOptions xml_flattener_options_;
  std::string output_path_;
  std::optional<std::string> output_format_;
  bool verbose_ = false;
  bool enable_sparse_encoding_ = false;
  bool force_sparse_encoding_ = false;
  bool enable_compact_entries_ = false;
  std::optional<std::string> resources_config_path_;
};

int Convert(IAaptContext* context, LoadedApk* input, IArchiveWriter* output_writer,
            ApkFormat output_format,TableFlattenerOptions table_flattener_options,
            XmlFlattenerOptions xml_flattener_options);

}  // namespace aapt

#endif //AAPT2_CONVERT_H
