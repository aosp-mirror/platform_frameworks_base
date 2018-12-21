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

#include <string>

#include "android-base/macros.h"
#include "android-base/stringprintf.h"
#include "androidfw/ApkAssets.h"

#include "idmap2/PrettyPrintVisitor.h"
#include "idmap2/ResourceUtils.h"
#include "idmap2/Result.h"

namespace android::idmap2 {

#define RESID(pkg, type, entry) (((pkg) << 24) | ((type) << 16) | (entry))

void PrettyPrintVisitor::visit(const Idmap& idmap ATTRIBUTE_UNUSED) {
}

void PrettyPrintVisitor::visit(const IdmapHeader& header) {
  stream_ << "target apk path  : " << header.GetTargetPath() << std::endl
          << "overlay apk path : " << header.GetOverlayPath() << std::endl;

  target_apk_ = ApkAssets::Load(header.GetTargetPath().to_string());
  if (target_apk_) {
    target_am_.SetApkAssets({target_apk_.get()});
  }
}

void PrettyPrintVisitor::visit(const IdmapData& data ATTRIBUTE_UNUSED) {
}

void PrettyPrintVisitor::visit(const IdmapData::Header& header ATTRIBUTE_UNUSED) {
  last_seen_package_id_ = header.GetTargetPackageId();
}

void PrettyPrintVisitor::visit(const IdmapData::TypeEntry& type_entry) {
  const bool target_package_loaded = !target_am_.GetApkAssets().empty();
  for (uint16_t i = 0; i < type_entry.GetEntryCount(); i++) {
    const EntryId entry = type_entry.GetEntry(i);
    if (entry == kNoEntry) {
      continue;
    }

    const ResourceId target_resid =
        RESID(last_seen_package_id_, type_entry.GetTargetTypeId(), type_entry.GetEntryOffset() + i);
    const ResourceId overlay_resid =
        RESID(last_seen_package_id_, type_entry.GetOverlayTypeId(), entry);

    stream_ << base::StringPrintf("0x%08x -> 0x%08x", target_resid, overlay_resid);
    if (target_package_loaded) {
      Result<std::string> name = utils::ResToTypeEntryName(target_am_, target_resid);
      if (name) {
        stream_ << " " << *name;
      }
    }
    stream_ << std::endl;
  }
}

}  // namespace android::idmap2
