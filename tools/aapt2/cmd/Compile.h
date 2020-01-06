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

#include "androidfw/StringPiece.h"
#include "format/Archive.h"
#include "process/IResourceTableConsumer.h"
#include "Command.h"
#include "Diagnostics.h"
#include "ResourceTable.h"

namespace aapt {

struct CompileOptions {
  std::string output_path;
  Maybe<std::string> res_dir;
  Maybe<std::string> res_zip;
  Maybe<std::string> generate_text_symbols_path;
  Maybe<Visibility::Level> visibility;
  bool pseudolocalize = false;
  bool no_png_crunch = false;
  bool legacy_mode = false;
  // See comments on aapt::ResourceParserOptions.
  bool preserve_visibility_of_styleables = false;
  bool verbose = false;
};

/** Parses flags and compiles resources to be used in linking.  */
class CompileCommand : public Command {
 public:
  explicit CompileCommand(IDiagnostics* diagnostic) : Command("compile", "c"),
                                                      diagnostic_(diagnostic) {
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
  }

  int Action(const std::vector<std::string>& args) override;

 private:
  IDiagnostics* diagnostic_;
  CompileOptions options_;
  Maybe<std::string> visibility_;
  Maybe<std::string> trace_folder_;
};

int Compile(IAaptContext* context, io::IFileCollection* inputs, IArchiveWriter* output_writer,
            CompileOptions& options);

}// namespace aapt

#endif //AAPT2_COMPILE_H
