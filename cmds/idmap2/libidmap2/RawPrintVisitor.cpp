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

#include "android-base/macros.h"
#include "android-base/stringprintf.h"
#include "androidfw/ApkAssets.h"

#include "idmap2/RawPrintVisitor.h"
#include "idmap2/ResourceUtils.h"
#include "idmap2/Result.h"

using android::ApkAssets;

namespace android::idmap2 {

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

void RawPrintVisitor::visit(const IdmapData::TypeEntry& type_entry) {
  const bool target_package_loaded = !target_am_.GetApkAssets().empty();

  print(static_cast<uint16_t>(type_entry.GetTargetTypeId()), "target type");
  print(static_cast<uint16_t>(type_entry.GetOverlayTypeId()), "overlay type");
  print(static_cast<uint16_t>(type_entry.GetEntryCount()), "entry count");
  print(static_cast<uint16_t>(type_entry.GetEntryOffset()), "entry offset");

  for (uint16_t i = 0; i < type_entry.GetEntryCount(); i++) {
    const EntryId entry = type_entry.GetEntry(i);
    if (entry == kNoEntry) {
      print(kPadding, "no entry");
    } else {
      const ResourceId target_resid = RESID(last_seen_package_id_, type_entry.GetTargetTypeId(),
                                            type_entry.GetEntryOffset() + i);
      const ResourceId overlay_resid =
          RESID(last_seen_package_id_, type_entry.GetOverlayTypeId(), entry);
      Result<std::string> name;
      if (target_package_loaded) {
        name = utils::ResToTypeEntryName(target_am_, target_resid);
      }
      if (name) {
        print(static_cast<uint32_t>(entry), "0x%08x -> 0x%08x %s", target_resid, overlay_resid,
              name->c_str());
      } else {
        print(static_cast<uint32_t>(entry), "0x%08x -> 0x%08x", target_resid, overlay_resid);
      }
    }
  }
}

// NOLINTNEXTLINE(cert-dcl50-cpp)
void RawPrintVisitor::print(uint16_t value, const char* fmt, ...) {
  va_list ap;
  va_start(ap, fmt);
  std::string comment;
  base::StringAppendV(&comment, fmt, ap);
  va_end(ap);

  stream_ << base::StringPrintf("%08zx:     %04x", offset_, value) << "  " << comment << std::endl;

  offset_ += sizeof(uint16_t);
}

// NOLINTNEXTLINE(cert-dcl50-cpp)
void RawPrintVisitor::print(uint32_t value, const char* fmt, ...) {
  va_list ap;
  va_start(ap, fmt);
  std::string comment;
  base::StringAppendV(&comment, fmt, ap);
  va_end(ap);

  stream_ << base::StringPrintf("%08zx: %08x", offset_, value) << "  " << comment << std::endl;

  offset_ += sizeof(uint32_t);
}

// NOLINTNEXTLINE(cert-dcl50-cpp)
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

}  // namespace android::idmap2
