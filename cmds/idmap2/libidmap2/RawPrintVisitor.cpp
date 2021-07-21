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
#include <utility>

#include "android-base/macros.h"
#include "android-base/stringprintf.h"
#include "idmap2/PolicyUtils.h"
#include "idmap2/ResourceUtils.h"
#include "idmap2/Result.h"

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
  print(header.GetTargetPath(), true /* print_value */, "target path");
  print(header.GetOverlayPath(), true /* print_value */, "overlay path");
  print(header.GetOverlayName(), true /* print_value */, "overlay name");
  print(header.GetDebugInfo(), false /* print_value */, "debug info");

  if (auto target = TargetResourceContainer::FromPath(header.GetTargetPath())) {
    target_ = std::move(*target);
  }
  if (auto overlay = OverlayResourceContainer::FromPath(header.GetOverlayPath())) {
    overlay_ = std::move(*overlay);
  }
}

void RawPrintVisitor::visit(const IdmapData& data ATTRIBUTE_UNUSED) {
  for (auto& target_entry : data.GetTargetEntries()) {
    Result<std::string> target_name(Error(""));
    if (target_ != nullptr) {
      target_name = target_->GetResourceName(target_entry.target_id);
    }
    if (target_name) {
      print(target_entry.target_id, "target id: %s", target_name->c_str());
    } else {
      print(target_entry.target_id, "target id");
    }

    Result<std::string> overlay_name(Error(""));
    if (overlay_ != nullptr) {
      overlay_name = overlay_->GetResourceName(target_entry.overlay_id);
    }
    if (overlay_name) {
      print(target_entry.overlay_id, "overlay id: %s", overlay_name->c_str());
    } else {
      print(target_entry.overlay_id, "overlay id");
    }
  }

  for (auto& target_entry : data.GetTargetInlineEntries()) {
    Result<std::string> target_name(Error(""));
    if (target_ != nullptr) {
      target_name = target_->GetResourceName(target_entry.target_id);
    }
    if (target_name) {
      print(target_entry.target_id, "target id: %s", target_name->c_str());
    } else {
      print(target_entry.target_id, "target id");
    }

    pad(sizeof(Res_value::size) + sizeof(Res_value::res0));

    print(target_entry.value.data_type, "type: %s",
          utils::DataTypeToString(target_entry.value.data_type).data());

    Result<std::string> overlay_name(Error(""));
    if (overlay_ != nullptr &&
        (target_entry.value.data_value == Res_value::TYPE_REFERENCE ||
         target_entry.value.data_value == Res_value::TYPE_DYNAMIC_REFERENCE)) {
      overlay_name = overlay_->GetResourceName(target_entry.value.data_value);
    }

    if (overlay_name) {
      print(target_entry.value.data_value, "data: %s", overlay_name->c_str());
    } else {
      print(target_entry.value.data_value, "data");
    }
  }

  for (auto& overlay_entry : data.GetOverlayEntries()) {
    Result<std::string> overlay_name(Error(""));
    if (overlay_ != nullptr) {
      overlay_name = overlay_->GetResourceName(overlay_entry.overlay_id);
    }

    if (overlay_name) {
      print(overlay_entry.overlay_id, "overlay id: %s", overlay_name->c_str());
    } else {
      print(overlay_entry.overlay_id, "overlay id");
    }

    Result<std::string> target_name(Error(""));
    if (target_ != nullptr) {
      target_name = target_->GetResourceName(overlay_entry.target_id);
    }

    if (target_name) {
      print(overlay_entry.target_id, "target id: %s", target_name->c_str());
    } else {
      print(overlay_entry.target_id, "target id");
    }
  }

  print(data.GetStringPoolData(), false /* print_value */, "string pool");
}

void RawPrintVisitor::visit(const IdmapData::Header& header) {
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
void RawPrintVisitor::print(const std::string& value, bool print_value, const char* fmt, ...) {
  va_list ap;
  va_start(ap, fmt);
  std::string comment;
  base::StringAppendV(&comment, fmt, ap);
  va_end(ap);

  stream_ << base::StringPrintf("%08zx: %08x", offset_, (uint32_t)value.size()) << "  " << comment
          << " size" << std::endl;
  offset_ += sizeof(uint32_t);

  stream_ << base::StringPrintf("%08zx: ", offset_) << "........  " << comment;
  offset_ += value.size() + CalculatePadding(value.size());

  if (print_value) {
    stream_ << ": " << value;
  }
  stream_ << std::endl;
}

void RawPrintVisitor::align() {
  offset_ += CalculatePadding(offset_);
}

void RawPrintVisitor::pad(size_t padding) {
  offset_ += padding;
}
}  // namespace android::idmap2
