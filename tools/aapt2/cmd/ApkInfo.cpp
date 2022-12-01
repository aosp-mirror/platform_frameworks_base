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

#include "ApkInfo.h"

#include <fcntl.h>

#include <iostream>
#include <memory>

#include "LoadedApk.h"
#include "android-base/file.h"  // for O_BINARY
#include "android-base/utf8.h"
#include "androidfw/IDiagnostics.h"
#include "androidfw/StringPiece.h"
#include "dump/DumpManifest.h"
#include "format/proto/ProtoSerialize.h"

using ::android::StringPiece;

namespace aapt {

int ExportApkInfo(LoadedApk* apk, bool include_resource_table,
                  const std::unordered_set<std::string>& xml_resources, pb::ApkInfo* out_apk_info,
                  android::IDiagnostics* diag) {
  auto result = DumpBadgingProto(apk, out_apk_info->mutable_badging(), diag);
  if (result != 0) {
    return result;
  }

  if (include_resource_table) {
    SerializeTableToPb(*apk->GetResourceTable(), out_apk_info->mutable_resource_table(), diag);
  }

  for (auto& xml_resource : xml_resources) {
    auto xml = apk->LoadXml(xml_resource, diag);
    if (xml) {
      auto out_xml = out_apk_info->add_xml_files();
      out_xml->set_path(xml_resource);
      SerializeXmlResourceToPb(*xml, out_xml->mutable_root(),
                               {/* remove_empty_text_nodes= */ true});
    }
  }

  return 0;
}

int ApkInfoCommand::Action(const std::vector<std::string>& args) {
  if (args.size() != 1) {
    std::cerr << "must supply a single APK\n";
    Usage(&std::cerr);
    return 1;
  }
  StringPiece path = args[0];
  std::unique_ptr<LoadedApk> apk = LoadedApk::LoadApkFromPath(path, diag_);
  if (!apk) {
    return 1;
  }

  pb::ApkInfo out_apk_info;
  int result =
      ExportApkInfo(apk.get(), include_resource_table_, xml_resources_, &out_apk_info, diag_);
  if (result != 0) {
    diag_->Error(android::DiagMessage() << "Failed to serialize ApkInfo into proto.");
    return result;
  }

  int mode = O_WRONLY | O_CREAT | O_TRUNC | O_BINARY;
  int outfd = ::android::base::utf8::open(output_path_.c_str(), mode, 0666);
  if (outfd == -1) {
    diag_->Error(android::DiagMessage() << "Failed to open output file.");
    return 1;
  }

  bool is_serialized = out_apk_info.SerializeToFileDescriptor(outfd);
  close(outfd);

  return is_serialized ? 0 : 1;
}

}  // namespace aapt