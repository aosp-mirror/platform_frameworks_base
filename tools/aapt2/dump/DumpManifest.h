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

#ifndef AAPT2_DUMP_MANIFEST_H
#define AAPT2_DUMP_MANIFEST_H

#include <Diagnostics.h>
#include <ValueVisitor.h>
#include <io/ZipArchive.h>


#include "cmd/Command.h"
#include "process/IResourceTableConsumer.h"
#include "text/Printer.h"
#include "xml/XmlDom.h"

namespace aapt {

class DumpBadgingCommand : public Command {
 public:
  explicit DumpBadgingCommand(IDiagnostics* diag) : Command("badging"), diag_(diag) {
    SetDescription("Print information extracted from the manifest of the APK.");
    AddOptionalSwitch("--include-meta-data", "Include meta-data information.",
                      &include_metadata_);
  }

  int Action(const std::vector<std::string>& args) override;

 private:
  IDiagnostics* diag_;
  bool include_metadata_ = false;
};

class DumpPermissionsCommand : public Command {
 public:
  explicit DumpPermissionsCommand(IDiagnostics* diag) : Command("permissions"), diag_(diag) {
    SetDescription("Print the permissions extracted from the manifest of the APK.");
  }

  int Action(const std::vector<std::string>& args) override;

 private:
  IDiagnostics* diag_;
};

}// namespace aapt

#endif //AAPT2_DUMP_MANIFEST_H