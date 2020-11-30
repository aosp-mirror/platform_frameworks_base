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

  if (auto target_apk_ = ApkAssets::Load(header.GetTargetPath().to_string())) {
    target_am_.SetApkAssets({target_apk_.get()});
    apk_assets_.push_back(std::move(target_apk_));
  }

  if (auto overlay_apk = ApkAssets::Load(header.GetOverlayPath().to_string())) {
    overlay_am_.SetApkAssets({overlay_apk.get()});
    apk_assets_.push_back(std::move(overlay_apk));
  }

  stream_ << "Mapping:" << std::endl;
}

void PrettyPrintVisitor::visit(const IdmapData::Header& header ATTRIBUTE_UNUSED) {
}

void PrettyPrintVisitor::visit(const IdmapData& data) {
  static constexpr const char* kUnknownResourceName = "???";

  const bool target_package_loaded = !target_am_.GetApkAssets().empty();
  const bool overlay_package_loaded = !overlay_am_.GetApkAssets().empty();

  const ResStringPool string_pool(data.GetStringPoolData().data(), data.GetStringPoolData().size());
  const size_t string_pool_offset = data.GetHeader()->GetStringPoolIndexOffset();

  for (const auto& target_entry : data.GetTargetEntries()) {
    std::string target_name = kUnknownResourceName;
    if (target_package_loaded) {
      if (auto name = utils::ResToTypeEntryName(target_am_, target_entry.target_id)) {
        target_name = *name;
      }
    }

    std::string overlay_name = kUnknownResourceName;
    if (overlay_package_loaded) {
      if (auto name = utils::ResToTypeEntryName(overlay_am_, target_entry.overlay_id)) {
        overlay_name = *name;
      }
    }

    stream_ << TAB
            << base::StringPrintf("0x%08x -> 0x%08x (%s -> %s)", target_entry.target_id,
                                  target_entry.overlay_id, target_name.c_str(),
                                  overlay_name.c_str())
            << std::endl;
  }

  for (auto& target_entry : data.GetTargetInlineEntries()) {
    stream_ << TAB << base::StringPrintf("0x%08x -> ", target_entry.target_id)
            << utils::DataTypeToString(target_entry.value.data_type);

    size_t unused;
    if (target_entry.value.data_type == Res_value::TYPE_STRING) {
      auto str = string_pool.stringAt(target_entry.value.data_value - string_pool_offset, &unused);
      stream_ << " \"" << StringPiece16(str) << "\"";
    } else {
      stream_ << " " << base::StringPrintf("0x%08x", target_entry.value.data_value);
    }

    std::string target_name = kUnknownResourceName;
    if (target_package_loaded) {
      if (auto name = utils::ResToTypeEntryName(target_am_, target_entry.target_id)) {
        target_name = *name;
      }
    }

    stream_ << " (" << target_name << ")" << std::endl;
  }
}

}  // namespace android::idmap2
