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

#ifndef AAPT2_DUMP_H
#define AAPT2_DUMP_H

#include "Command.h"
#include "Debug.h"

namespace aapt {

struct DumpOptions {
  DebugPrintTableOptions print_options;

  // The path to a file within an APK to dump.
  Maybe<std::string> file_to_dump_path;
};

class DumpCommand : public Command {
 public:
  DumpCommand() : Command("dump", "d") {
    SetDescription("Prints resource and manifest information.");
    AddOptionalSwitch("--no-values", "Suppresses output of values when displaying resource tables.",
        &no_values_);
    AddOptionalFlag("--file", "Dumps the specified file from the APK passed as arg.",
        &options_.file_to_dump_path);
    AddOptionalSwitch("-v", "increase verbosity of output", &verbose_);
  }

  int Action(const std::vector<std::string>& args) override;

 private:
  DumpOptions options_;

  bool verbose_ = false;
  bool no_values_ = false;
};

}// namespace aapt

#endif //AAPT2_DUMP_H
