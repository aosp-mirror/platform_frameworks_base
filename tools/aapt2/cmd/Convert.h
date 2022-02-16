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
    AddOptionalSwitch("--enable-sparse-encoding",
        "Enables encoding sparse entries using a binary search tree.\n"
        "This decreases APK size at the cost of resource retrieval performance.",
         &table_flattener_options_.use_sparse_entries);
    AddOptionalSwitch("--keep-raw-values",
        android::base::StringPrintf("Preserve raw attribute values in xml files when using the"
            " '%s' output format", kOutputFormatBinary),
        &xml_flattener_options_.keep_raw_values);
    AddOptionalSwitch("-v", "Enables verbose logging", &verbose_);
  }

  int Action(const std::vector<std::string>& args) override;

 private:
  const static char* kOutputFormatProto;
  const static char* kOutputFormatBinary;

  TableFlattenerOptions table_flattener_options_;
  XmlFlattenerOptions xml_flattener_options_;
  std::string output_path_;
  Maybe<std::string> output_format_;
  bool verbose_ = false;
};

int Convert(IAaptContext* context, LoadedApk* input, IArchiveWriter* output_writer,
            ApkFormat output_format,TableFlattenerOptions table_flattener_options,
            XmlFlattenerOptions xml_flattener_options);

}  // namespace aapt

#endif //AAPT2_CONVERT_H
