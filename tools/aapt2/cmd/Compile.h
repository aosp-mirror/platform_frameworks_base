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

#include "Command.h"
#include "Diagnostics.h"
#include "ResourceTable.h"

namespace aapt {

struct CompileOptions {
  std::string output_path;
  Maybe<std::string> res_dir;
  Maybe<std::string> generate_text_symbols_path;
  Maybe<Visibility::Level> visibility;
  bool pseudolocalize = false;
  bool no_png_crunch = false;
  bool legacy_mode = false;
  bool verbose = false;
};

class CompileCommand : public Command {
 public:
  explicit CompileCommand(IDiagnostics* diagnostic) : Command("compile", "c"),
                                                      diagnostic_(diagnostic) {
    SetDescription("Compiles resources to be linked into an apk.");
    AddRequiredFlag("-o", "Output path", &options_.output_path);
    AddOptionalFlag("--dir", "Directory to scan for resources", &options_.res_dir);
    AddOptionalFlag("--output-text-symbols",
        "Generates a text file containing the resource symbols in the\n"
            "specified file", &options_.generate_text_symbols_path);
    AddOptionalSwitch("--pseudo-localize", "Generate resources for pseudo-locales "
        "(en-XA and ar-XB)", &options_.pseudolocalize);
    AddOptionalSwitch("--no-crunch", "Disables PNG processing", &options_.no_png_crunch);
    AddOptionalSwitch("--legacy", "Treat errors that used to be valid in AAPT as warnings",
        &options_.legacy_mode);
    AddOptionalSwitch("-v", "Enables verbose logging", &options_.verbose);
    AddOptionalFlag("--visibility",
        "Sets the visibility of the compiled resources to the specified\n"
            "level. Accepted levels: public, private, default", &visibility_);
  }

  int Action(const std::vector<std::string>& args) override;

 private:
  IDiagnostics* diagnostic_;
  CompileOptions options_;
  Maybe<std::string> visibility_;
};

}// namespace aapt

#endif //AAPT2_COMPILE_H
