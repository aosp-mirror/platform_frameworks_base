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

#include "idmap2/RawPrintVisitor.h"

#include <algorithm>
#include <cstdarg>
#include <string>

#include "android-base/macros.h"
#include "android-base/stringprintf.h"
#include "androidfw/ApkAssets.h"
#include "idmap2/PolicyUtils.h"
#include "idmap2/ResourceUtils.h"
#include "idmap2/Result.h"

using android::ApkAssets;
using android::idmap2::policy::PoliciesToDebugString;

namespace android::idmap2 {

void RawPrintVisitor::visit(const Idmap& idmap ATTRIBUTE_UNUSED) {
}

void RawPrintVisitor::visit(const IdmapHeader& header) {
  print(header.GetMagic(), "magic");
  print(header.GetVersion(), "version");
  print(header.GetTargetCrc(), "target crc");
  print(header.GetOverlayCrc(), "overlay crc");
  print(header.GetFulfilledPolicies(), "fulfilled policies: %s",
        PoliciesToDebugString(header.GetFulfilledPolicies()).c_str());
  print(static_cast<uint32_t>(header.GetEnforceOverlayable()), "enforce overlayable");
  print(header.GetTargetPath().to_string(), kIdmapStringLength, "target path");
  print(header.GetOverlayPath().to_string(), kIdmapStringLength, "overlay path");

  uint32_t debug_info_size = header.GetDebugInfo().size();
  print(debug_info_size, "debug info size");
  print("...", debug_info_size + CalculatePadding(debug_info_size), "debug info");

  auto target_apk_ = ApkAssets::Load(header.GetTargetPath().to_string());
  if (target_apk_) {
    target_am_.SetApkAssets({target_apk_.get()});
    apk_assets_.push_back(std::move(target_apk_));
  }

  auto overlay_apk_ = ApkAssets::Load(header.GetOverlayPath().to_string());
  if (overlay_apk_) {
    overlay_am_.SetApkAssets({overlay_apk_.get()});
    apk_assets_.push_back(std::move(overlay_apk_));
  }
}

void RawPrintVisitor::visit(const IdmapData& data ATTRIBUTE_UNUSED) {
  const bool target_package_loaded = !target_am_.GetApkAssets().empty();
  const bool overlay_package_loaded = !overlay_am_.GetApkAssets().empty();

  for (auto& target_entry : data.GetTargetEntries()) {
    Result<std::string> target_name(Error(""));
    if (target_package_loaded) {
      target_name = utils::ResToTypeEntryName(target_am_, target_entry.target_id);
    }
    if (target_name) {
      print(target_entry.target_id, "target id: %s", target_name->c_str());
    } else {
      print(target_entry.target_id, "target id");
    }

    Result<std::string> overlay_name(Error(""));
    if (overlay_package_loaded) {
      overlay_name = utils::ResToTypeEntryName(overlay_am_, target_entry.overlay_id);
    }
    if (overlay_name) {
      print(target_entry.overlay_id, "overlay id: %s", overlay_name->c_str());
    } else {
      print(target_entry.overlay_id, "overlay id");
    }
  }

  for (auto& target_entry : data.GetTargetInlineEntries()) {
    Result<std::string> target_name(Error(""));
    if (target_package_loaded) {
      target_name = utils::ResToTypeEntryName(target_am_, target_entry.target_id);
    }
    if (target_name) {
      print(target_entry.target_id, "target id: %s", target_name->c_str());
    } else {
      print(target_entry.target_id, "target id");
    }

    print("...", sizeof(Res_value::size) + sizeof(Res_value::res0), "padding");

    print(target_entry.value.data_type, "type: %s",
          utils::DataTypeToString(target_entry.value.data_type).data());

    Result<std::string> overlay_name(Error(""));
    if (overlay_package_loaded &&
        (target_entry.value.data_value == Res_value::TYPE_REFERENCE ||
         target_entry.value.data_value == Res_value::TYPE_DYNAMIC_REFERENCE)) {
      overlay_name = utils::ResToTypeEntryName(overlay_am_, target_entry.value.data_value);
    }

    if (overlay_name) {
      print(target_entry.value.data_value, "data: %s", overlay_name->c_str());
    } else {
      print(target_entry.value.data_value, "data");
    }
  }

  for (auto& overlay_entry : data.GetOverlayEntries()) {
    Result<std::string> overlay_name(Error(""));
    if (overlay_package_loaded) {
      overlay_name = utils::ResToTypeEntryName(overlay_am_, overlay_entry.overlay_id);
    }

    if (overlay_name) {
      print(overlay_entry.overlay_id, "overlay id: %s", overlay_name->c_str());
    } else {
      print(overlay_entry.overlay_id, "overlay id");
    }

    Result<std::string> target_name(Error(""));
    if (target_package_loaded) {
      target_name = utils::ResToTypeEntryName(target_am_, overlay_entry.target_id);
    }

    if (target_name) {
      print(overlay_entry.target_id, "target id: %s", target_name->c_str());
    } else {
      print(overlay_entry.target_id, "target id");
    }
  }

  uint32_t string_pool_size = data.GetStringPoolData().size();
  print(string_pool_size, "string pool size");
  print("...", string_pool_size + CalculatePadding(string_pool_size), "string pool");
}

void RawPrintVisitor::visit(const IdmapData::Header& header) {
  print(header.GetTargetPackageId(), "target package id");
  print(header.GetOverlayPackageId(), "overlay package id");
  print("...", sizeof(Idmap_data_header::p0), "padding");
  print(header.GetTargetEntryCount(), "target entry count");
  print(header.GetTargetInlineEntryCount(), "target inline entry count");
  print(header.GetOverlayEntryCount(), "overlay entry count");
  print(header.GetStringPoolIndexOffset(), "string pool index offset");
}

// NOLINTNEXTLINE(cert-dcl50-cpp)
void RawPrintVisitor::print(uint8_t value, const char* fmt, ...) {
  va_list ap;
  va_start(ap, fmt);
  std::string comment;
  base::StringAppendV(&comment, fmt, ap);
  va_end(ap);

  stream_ << base::StringPrintf("%08zx:       %02x", offset_, value) << "  " << comment
          << std::endl;

  offset_ += sizeof(uint8_t);
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
void RawPrintVisitor::print(const std::string& value, size_t encoded_size, const char* fmt, ...) {
  va_list ap;
  va_start(ap, fmt);
  std::string comment;
  base::StringAppendV(&comment, fmt, ap);
  va_end(ap);

  stream_ << base::StringPrintf("%08zx: ", offset_) << "........  " << comment << ": " << value
          << std::endl;

  offset_ += encoded_size;
}

}  // namespace android::idmap2
