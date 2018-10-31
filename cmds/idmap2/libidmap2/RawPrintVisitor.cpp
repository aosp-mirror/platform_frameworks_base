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

#include <cstdarg>
#include <string>
#include <utility>

#include "android-base/macros.h"
#include "android-base/stringprintf.h"
#include "androidfw/ApkAssets.h"

#include "idmap2/RawPrintVisitor.h"
#include "idmap2/ResourceUtils.h"

using android::ApkAssets;

namespace android {
namespace idmap2 {

// verbatim copy fomr PrettyPrintVisitor.cpp, move to common utils
#define RESID(pkg, type, entry) (((pkg) << 24) | ((type) << 16) | (entry))

void RawPrintVisitor::visit(const Idmap& idmap ATTRIBUTE_UNUSED) {
}

void RawPrintVisitor::visit(const IdmapHeader& header) {
  print(header.GetMagic(), "magic");
  print(header.GetVersion(), "version");
  print(header.GetTargetCrc(), "target crc");
  print(header.GetOverlayCrc(), "overlay crc");
  print(header.GetTargetPath().to_string(), "target path");
  print(header.GetOverlayPath().to_string(), "overlay path");

  target_apk_ = ApkAssets::Load(header.GetTargetPath().to_string());
  if (target_apk_) {
    target_am_.SetApkAssets({target_apk_.get()});
  }
}

void RawPrintVisitor::visit(const IdmapData& data ATTRIBUTE_UNUSED) {
}

void RawPrintVisitor::visit(const IdmapData::Header& header) {
  print(static_cast<uint16_t>(header.GetTargetPackageId()), "target package id");
  print(header.GetTypeCount(), "type count");
  last_seen_package_id_ = header.GetTargetPackageId();
}

void RawPrintVisitor::visit(const IdmapData::TypeEntry& te) {
  const bool target_package_loaded = !target_am_.GetApkAssets().empty();

  print(static_cast<uint16_t>(te.GetTargetTypeId()), "target type");
  print(static_cast<uint16_t>(te.GetOverlayTypeId()), "overlay type");
  print(static_cast<uint16_t>(te.GetEntryCount()), "entry count");
  print(static_cast<uint16_t>(te.GetEntryOffset()), "entry offset");

  for (uint16_t i = 0; i < te.GetEntryCount(); i++) {
    const EntryId entry = te.GetEntry(i);
    if (entry == kNoEntry) {
      print(kPadding, "no entry");
    } else {
      const ResourceId target_resid =
          RESID(last_seen_package_id_, te.GetTargetTypeId(), te.GetEntryOffset() + i);
      const ResourceId overlay_resid = RESID(last_seen_package_id_, te.GetOverlayTypeId(), entry);
      bool lookup_ok = false;
      std::string name;
      if (target_package_loaded) {
        std::tie(lookup_ok, name) = utils::ResToTypeEntryName(target_am_, target_resid);
      }
      if (lookup_ok) {
        print(static_cast<uint32_t>(entry), "0x%08x -> 0x%08x %s", target_resid, overlay_resid,
              name.c_str());
      } else {
        print(static_cast<uint32_t>(entry), "0x%08x -> 0x%08x", target_resid, overlay_resid);
      }
    }
  }
}

void RawPrintVisitor::print(uint16_t value, const char* fmt, ...) {
  va_list ap;
  va_start(ap, fmt);
  std::string comment;
  base::StringAppendV(&comment, fmt, ap);
  va_end(ap);

  stream_ << base::StringPrintf("%08zx:     %04x", offset_, value) << "  " << comment << std::endl;

  offset_ += sizeof(uint16_t);
}

void RawPrintVisitor::print(uint32_t value, const char* fmt, ...) {
  va_list ap;
  va_start(ap, fmt);
  std::string comment;
  base::StringAppendV(&comment, fmt, ap);
  va_end(ap);

  stream_ << base::StringPrintf("%08zx: %08x", offset_, value) << "  " << comment << std::endl;

  offset_ += sizeof(uint32_t);
}

void RawPrintVisitor::print(const std::string& value, const char* fmt, ...) {
  va_list ap;
  va_start(ap, fmt);
  std::string comment;
  base::StringAppendV(&comment, fmt, ap);
  va_end(ap);

  stream_ << base::StringPrintf("%08zx: ", offset_) << "........ " << comment << ": " << value
          << std::endl;

  offset_ += kIdmapStringLength;
}

}  // namespace idmap2
}  // namespace android
