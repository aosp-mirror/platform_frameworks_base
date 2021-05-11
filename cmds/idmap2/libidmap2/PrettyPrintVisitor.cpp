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
#include <utility>

#include "android-base/macros.h"
#include "android-base/stringprintf.h"
#include "androidfw/ApkAssets.h"
#include "idmap2/ResourceUtils.h"
#include "idmap2/Result.h"

namespace android::idmap2 {

#define TAB "    "

void PrettyPrintVisitor::visit(const Idmap& idmap ATTRIBUTE_UNUSED) {
}

void PrettyPrintVisitor::visit(const IdmapHeader& header) {
  stream_ << "Paths:" << std::endl
          << TAB "target path  : " << header.GetTargetPath() << std::endl
          << TAB "overlay path : " << header.GetOverlayPath() << std::endl;

  if (!header.GetOverlayName().empty()) {
    stream_ << "Overlay name: " << header.GetOverlayName() << std::endl;
  }

  const std::string& debug = header.GetDebugInfo();
  if (!debug.empty()) {
    std::istringstream debug_stream(debug);
    std::string line;
    stream_ << "Debug info:" << std::endl;
    while (std::getline(debug_stream, line)) {
      stream_ << TAB << line << std::endl;
    }
  }

  if (auto target = TargetResourceContainer::FromPath(header.GetTargetPath())) {
    target_ = std::move(*target);
  }
  if (auto overlay = OverlayResourceContainer::FromPath(header.GetOverlayPath())) {
    overlay_ = std::move(*overlay);
  }

  stream_ << "Mapping:" << std::endl;
}

void PrettyPrintVisitor::visit(const IdmapData::Header& header ATTRIBUTE_UNUSED) {
}

void PrettyPrintVisitor::visit(const IdmapData& data) {
  static constexpr const char* kUnknownResourceName = "???";

  const ResStringPool string_pool(data.GetStringPoolData().data(), data.GetStringPoolData().size());
  const size_t string_pool_offset = data.GetHeader()->GetStringPoolIndexOffset();

  for (const auto& target_entry : data.GetTargetEntries()) {
    std::string target_name = kUnknownResourceName;
    if (target_ != nullptr) {
      if (auto name = target_->GetResourceName(target_entry.target_id)) {
        target_name = *name;
      }
    }

    std::string overlay_name = kUnknownResourceName;
    if (overlay_ != nullptr) {
      if (auto name = overlay_->GetResourceName(target_entry.overlay_id)) {
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

    if (target_entry.value.data_type == Res_value::TYPE_STRING) {
      auto str = string_pool.stringAt(target_entry.value.data_value - string_pool_offset);
      stream_ << " \"" << str.value_or(StringPiece16(u"")) << "\"";
    } else {
      stream_ << " " << base::StringPrintf("0x%08x", target_entry.value.data_value);
    }

    std::string target_name = kUnknownResourceName;
    if (target_ != nullptr) {
      if (auto name = target_->GetResourceName(target_entry.target_id)) {
        target_name = *name;
      }
    }

    stream_ << " (" << target_name << ")" << std::endl;
  }
}

}  // namespace android::idmap2
