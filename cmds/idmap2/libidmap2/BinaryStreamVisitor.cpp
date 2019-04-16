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

void BinaryStreamVisitor::Write16(uint16_t value) {
  uint16_t x = htodl(value);
  stream_.write(reinterpret_cast<char*>(&x), sizeof(uint16_t));
}

void BinaryStreamVisitor::Write32(uint32_t value) {
  uint32_t x = htodl(value);
  stream_.write(reinterpret_cast<char*>(&x), sizeof(uint32_t));
}

void BinaryStreamVisitor::WriteString(const StringPiece& value) {
  char buf[kIdmapStringLength];
  memset(buf, 0, sizeof(buf));
  memcpy(buf, value.data(), std::min(value.size(), sizeof(buf)));
  stream_.write(buf, sizeof(buf));
}

void BinaryStreamVisitor::visit(const Idmap& idmap ATTRIBUTE_UNUSED) {
  // nothing to do
}

void BinaryStreamVisitor::visit(const IdmapHeader& header) {
  Write32(header.GetMagic());
  Write32(header.GetVersion());
  Write32(header.GetTargetCrc());
  Write32(header.GetOverlayCrc());
  WriteString(header.GetTargetPath());
  WriteString(header.GetOverlayPath());
}

void BinaryStreamVisitor::visit(const IdmapData& data ATTRIBUTE_UNUSED) {
  // nothing to do
}

void BinaryStreamVisitor::visit(const IdmapData::Header& header) {
  Write16(header.GetTargetPackageId());
  Write16(header.GetTypeCount());
}

void BinaryStreamVisitor::visit(const IdmapData::TypeEntry& type_entry) {
  const uint16_t entryCount = type_entry.GetEntryCount();

  Write16(type_entry.GetTargetTypeId());
  Write16(type_entry.GetOverlayTypeId());
  Write16(entryCount);
  Write16(type_entry.GetEntryOffset());
  for (uint16_t i = 0; i < entryCount; i++) {
    EntryId entry_id = type_entry.GetEntry(i);
    Write32(entry_id != kNoEntry ? static_cast<uint32_t>(entry_id) : kPadding);
  }
}

}  // namespace android::idmap2
