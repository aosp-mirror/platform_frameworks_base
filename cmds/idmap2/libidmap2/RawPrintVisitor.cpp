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

namespace {

size_t StringSizeWhenEncoded(const std::string& s) {
  size_t null_bytes = 4 - (s.size() % 4);
  return sizeof(uint32_t) + s.size() + null_bytes;
}

}  // namespace

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
  print(static_cast<uint8_t>(header.GetEnforceOverlayable()), "enforce overlayable");
  print(header.GetTargetPath().to_string(), kIdmapStringLength, "target path");
  print(header.GetOverlayPath().to_string(), kIdmapStringLength, "overlay path");
  print("...", StringSizeWhenEncoded(header.GetDebugInfo()), "debug info");

  target_apk_ = ApkAssets::Load(header.GetTargetPath().to_string());
  if (target_apk_) {
    target_am_.SetApkAssets({target_apk_.get()});
  }

  overlay_apk_ = ApkAssets::Load(header.GetOverlayPath().to_string());
  if (overlay_apk_) {
    overlay_am_.SetApkAssets({overlay_apk_.get()});
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

    print(target_entry.data_type, "type: %s",
          utils::DataTypeToString(target_entry.data_type).data());

    Result<std::string> overlay_name(Error(""));
    if (overlay_package_loaded && (target_entry.data_type == Res_value::TYPE_REFERENCE ||
                                   target_entry.data_type == Res_value::TYPE_DYNAMIC_REFERENCE)) {
      overlay_name = utils::ResToTypeEntryName(overlay_am_, target_entry.data_value);
    }
    if (overlay_name) {
      print(target_entry.data_value, "value: %s", overlay_name->c_str());
    } else {
      print(target_entry.data_value, "value");
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

  const size_t string_pool_length = data.GetHeader()->GetStringPoolLength();
  if (string_pool_length > 0) {
    print_raw(string_pool_length, "%zu raw string pool bytes", string_pool_length);
  }
}

void RawPrintVisitor::visit(const IdmapData::Header& header) {
  print(header.GetTargetPackageId(), "target package id");
  print(header.GetOverlayPackageId(), "overlay package id");
  print(header.GetTargetEntryCount(), "target entry count");
  print(header.GetOverlayEntryCount(), "overlay entry count");
  print(header.GetStringPoolIndexOffset(), "string pool index offset");
  print(header.GetStringPoolLength(), "string pool byte length");
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

// NOLINTNEXTLINE(cert-dcl50-cpp)
void RawPrintVisitor::print_raw(uint32_t length, const char* fmt, ...) {
  va_list ap;
  va_start(ap, fmt);
  std::string comment;
  base::StringAppendV(&comment, fmt, ap);
  va_end(ap);

  stream_ << base::StringPrintf("%08zx: ", offset_) << "........  " << comment << std::endl;

  offset_ += length;
}

}  // namespace android::idmap2
