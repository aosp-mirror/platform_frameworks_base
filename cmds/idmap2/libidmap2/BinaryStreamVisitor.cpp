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

#include "idmap2/BinaryStreamVisitor.h"

#include <algorithm>
#include <cstring>
#include <string>

#include "android-base/macros.h"

namespace android::idmap2 {

void BinaryStreamVisitor::Write8(uint8_t value) {
  stream_.write(reinterpret_cast<char*>(&value), sizeof(uint8_t));
}

void BinaryStreamVisitor::Write16(uint16_t value) {
  uint16_t x = htodl(value);
  stream_.write(reinterpret_cast<char*>(&x), sizeof(uint16_t));
}

void BinaryStreamVisitor::Write32(uint32_t value) {
  uint32_t x = htodl(value);
  stream_.write(reinterpret_cast<char*>(&x), sizeof(uint32_t));
}

void BinaryStreamVisitor::WriteString256(const StringPiece& value) {
  char buf[kIdmapStringLength];
  memset(buf, 0, sizeof(buf));
  memcpy(buf, value.data(), std::min(value.size(), sizeof(buf)));
  stream_.write(buf, sizeof(buf));
}

void BinaryStreamVisitor::WriteString(const StringPiece& value) {
  // pad with null to nearest word boundary;
  size_t padding_size = CalculatePadding(value.size());
  Write32(value.size());
  stream_.write(value.data(), value.size());
  stream_.write("\0\0\0\0", padding_size);
}

void BinaryStreamVisitor::visit(const Idmap& idmap ATTRIBUTE_UNUSED) {
  // nothing to do
}

void BinaryStreamVisitor::visit(const IdmapHeader& header) {
  Write32(header.GetMagic());
  Write32(header.GetVersion());
  Write32(header.GetTargetCrc());
  Write32(header.GetOverlayCrc());
  Write32(header.GetFulfilledPolicies());
  Write32(static_cast<uint8_t>(header.GetEnforceOverlayable()));
  WriteString256(header.GetTargetPath());
  WriteString256(header.GetOverlayPath());
  WriteString(header.GetDebugInfo());
}

void BinaryStreamVisitor::visit(const IdmapData& data) {
  for (const auto& target_entry : data.GetTargetEntries()) {
    Write32(target_entry.target_id);
    Write32(target_entry.overlay_id);
  }

  static constexpr uint16_t kValueSize = 8U;
  for (const auto& target_entry : data.GetTargetInlineEntries()) {
    Write32(target_entry.target_id);
    Write16(kValueSize);
    Write8(0U);  // padding
    Write8(target_entry.value.data_type);
    Write32(target_entry.value.data_value);
  }

  for (const auto& overlay_entry : data.GetOverlayEntries()) {
    Write32(overlay_entry.overlay_id);
    Write32(overlay_entry.target_id);
  }

  WriteString(data.GetStringPoolData());
}

void BinaryStreamVisitor::visit(const IdmapData::Header& header) {
  Write8(header.GetTargetPackageId());
  Write8(header.GetOverlayPackageId());
  Write8(0U);  // padding
  Write8(0U);  // padding
  Write32(header.GetTargetEntryCount());
  Write32(header.GetTargetInlineEntryCount());
  Write32(header.GetOverlayEntryCount());
  Write32(header.GetStringPoolIndexOffset());
}

}  // namespace android::idmap2
