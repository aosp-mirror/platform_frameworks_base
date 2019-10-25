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

#include "idmap2/PrettyPrintVisitor.h"

#include <istream>
#include <string>

#include "android-base/macros.h"
#include "android-base/stringprintf.h"
#include "androidfw/ApkAssets.h"
#include "idmap2/ResourceUtils.h"
#include "idmap2/Result.h"

namespace android::idmap2 {

#define RESID(pkg, type, entry) (((pkg) << 24) | ((type) << 16) | (entry))

#define TAB "    "

void PrettyPrintVisitor::visit(const Idmap& idmap ATTRIBUTE_UNUSED) {
}

void PrettyPrintVisitor::visit(const IdmapHeader& header) {
  stream_ << "Paths:" << std::endl
          << TAB "target apk path  : " << header.GetTargetPath() << std::endl
          << TAB "overlay apk path : " << header.GetOverlayPath() << std::endl;
  const std::string& debug = header.GetDebugInfo();
  if (!debug.empty()) {
    std::istringstream debug_stream(debug);
    std::string line;
    stream_ << "Debug info:" << std::endl;
    while (std::getline(debug_stream, line)) {
      stream_ << TAB << line << std::endl;
    }
  }

  target_apk_ = ApkAssets::Load(header.GetTargetPath().to_string());
  if (target_apk_) {
    target_am_.SetApkAssets({target_apk_.get()});
  }
  stream_ << "Mapping:" << std::endl;
}

void PrettyPrintVisitor::visit(const IdmapData::Header& header ATTRIBUTE_UNUSED) {
}

void PrettyPrintVisitor::visit(const IdmapData& data) {
  const bool target_package_loaded = !target_am_.GetApkAssets().empty();
  const ResStringPool string_pool(data.GetStringPoolData(),
                                  data.GetHeader()->GetStringPoolLength());
  const size_t string_pool_offset = data.GetHeader()->GetStringPoolIndexOffset();

  for (auto& target_entry : data.GetTargetEntries()) {
    stream_ << TAB << base::StringPrintf("0x%08x ->", target_entry.target_id);

    if (target_entry.data_type != Res_value::TYPE_REFERENCE &&
        target_entry.data_type != Res_value::TYPE_DYNAMIC_REFERENCE) {
      stream_ << " " << utils::DataTypeToString(target_entry.data_type);
    }

    if (target_entry.data_type == Res_value::TYPE_STRING) {
      stream_ << " \""
              << string_pool.string8ObjectAt(target_entry.data_value - string_pool_offset).c_str()
              << "\"";
    } else {
      stream_ << " " << base::StringPrintf("0x%08x", target_entry.data_value);
    }

    if (target_package_loaded) {
      Result<std::string> name = utils::ResToTypeEntryName(target_am_, target_entry.target_id);
      if (name) {
        stream_ << " " << *name;
      }
    }
    stream_ << std::endl;
  }
}

}  // namespace android::idmap2
