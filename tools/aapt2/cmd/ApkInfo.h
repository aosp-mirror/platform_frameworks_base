/*
 * Copyright (C) 2022 The Android Open Source Project
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

#ifndef AAPT2_APKINFO_H
#define AAPT2_APKINFO_H

#include "Command.h"
#include "androidfw/IDiagnostics.h"

namespace aapt {

class ApkInfoCommand : public Command {
 public:
  explicit ApkInfoCommand(android::IDiagnostics* diag) : Command("apkinfo"), diag_(diag) {
    SetDescription("Dump information about an APK in binary proto format.");
    AddRequiredFlag("-o", "Output path", &output_path_, Command::kPath);
    AddOptionalSwitch("--include-resource-table", "Include the resource table data into output.",
                      &include_resource_table_);
    AddOptionalFlagList("--include-xml",
                        "Include an XML file content into output. Multiple XML files might be "
                        "requested during single invocation.",
                        &xml_resources_);
  }

  int Action(const std::vector<std::string>& args) override;

 private:
  android::IDiagnostics* diag_;
  std::string output_path_;
  bool include_resource_table_ = false;
  std::unordered_set<std::string> xml_resources_;
};

}  // namespace aapt

#endif  // AAPT2_APKINFO_H
