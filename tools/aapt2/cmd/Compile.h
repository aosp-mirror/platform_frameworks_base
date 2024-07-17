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

#ifndef AAPT2_COMPILE_H
#define AAPT2_COMPILE_H

#include <androidfw/StringPiece.h>

#include <optional>

#include "Command.h"
#include "ResourceTable.h"
#include "androidfw/IDiagnostics.h"
#include "cmd/Util.h"
#include "format/Archive.h"
#include "process/IResourceTableConsumer.h"

namespace aapt {

struct CompileOptions {
  std::string output_path;
  std::optional<std::string> source_path;
  std::optional<std::string> res_dir;
  std::optional<std::string> res_zip;
  std::optional<std::string> generate_text_symbols_path;
  std::optional<std::string> pseudo_localize_gender_values;
  std::optional<std::string> pseudo_localize_gender_ratio;
  std::optional<Visibility::Level> visibility;
  bool pseudolocalize = false;
  bool no_png_crunch = false;
  bool legacy_mode = false;
  // See comments on aapt::ResourceParserOptions.
  bool preserve_visibility_of_styleables = false;
  bool verbose = false;
  std::optional<std::string> product_;
  FeatureFlagValues feature_flag_values;
};

/** Parses flags and compiles resources to be used in linking.  */
class CompileCommand : public Command {
 public:
  explicit CompileCommand(android::IDiagnostics* diagnostic)
      : Command("compile", "c"), diagnostic_(diagnostic) {
    SetDescription("Compiles resources to be linked into an apk.");
    AddRequiredFlag("-o", "Output path", &options_.output_path, Command::kPath);
    AddOptionalFlag("--dir", "Directory to scan for resources", &options_.res_dir, Command::kPath);
    AddOptionalFlag("--zip", "Zip file containing the res directory to scan for resources",
        &options_.res_zip, Command::kPath);
    AddOptionalFlag("--output-text-symbols",
        "Generates a text file containing the resource symbols in the\n"
            "specified file", &options_.generate_text_symbols_path, Command::kPath);
    AddOptionalSwitch("--pseudo-localize", "Generate resources for pseudo-locales "
        "(en-XA and ar-XB)", &options_.pseudolocalize);
    AddOptionalSwitch("--no-crunch", "Disables PNG processing", &options_.no_png_crunch);
    AddOptionalSwitch("--legacy", "Treat errors that used to be valid in AAPT as warnings",
        &options_.legacy_mode);
    AddOptionalSwitch("--preserve-visibility-of-styleables",
                      "If specified, apply the same visibility rules for\n"
                      "styleables as are used for all other resources.\n"
                      "Otherwise, all stylesables will be made public.",
                      &options_.preserve_visibility_of_styleables);
    AddOptionalFlag("--visibility",
        "Sets the visibility of the compiled resources to the specified\n"
            "level. Accepted levels: public, private, default", &visibility_);
    AddOptionalSwitch("-v", "Enables verbose logging", &options_.verbose);
    AddOptionalFlag("--trace-folder", "Generate systrace json trace fragment to specified folder.",
                    &trace_folder_);
    AddOptionalFlag("--source-path",
                      "Sets the compiled resource file source file path to the given string.",
                      &options_.source_path);
    AddOptionalFlag("--pseudo-localize-gender-values",
                    "Sets the gender values to pick up for generating grammatical gender strings, "
                    "gender values should be f, m, or n, which are shortcuts for feminine, "
                    "masculine and neuter, and split with comma.",
                    &options_.pseudo_localize_gender_values);
    AddOptionalFlag("--pseudo-localize-gender-ratio",
                    "Sets the ratio of resources to generate grammatical gender strings for. The "
                    "ratio has to be a float number between 0 and 1.",
                    &options_.pseudo_localize_gender_ratio);
    AddOptionalFlag("--filter-product",
                    "Leave only resources specific to the given product. All "
                    "other resources (including defaults) are removed.",
                    &options_.product_);
    AddOptionalFlagList("--feature-flags",
                        "Specify the values of feature flags. The pairs in the argument\n"
                        "are separated by ',' the name is separated from the value by '='.\n"
                        "The name can have a suffix of ':ro' to indicate it is read only."
                        "Example: \"flag1=true,flag2:ro=false,flag3=\" (flag3 has no given value).",
                        &feature_flags_args_);
  }

  int Action(const std::vector<std::string>& args) override;

 private:
  android::IDiagnostics* diagnostic_;
  CompileOptions options_;
  std::optional<std::string> visibility_;
  std::optional<std::string> trace_folder_;
  std::vector<std::string> feature_flags_args_;
};

int Compile(IAaptContext* context, io::IFileCollection* inputs, IArchiveWriter* output_writer,
            CompileOptions& options);

}// namespace aapt

#endif //AAPT2_COMPILE_H
