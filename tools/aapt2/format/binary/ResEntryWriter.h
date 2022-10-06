/*
 * Copyright (C) 2022 The Android Open Source Project
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

#ifndef AAPT_FORMAT_BINARY_RESENTRY_SERIALIZER_H
#define AAPT_FORMAT_BINARY_RESENTRY_SERIALIZER_H

#include <unordered_map>

#include "ResourceTable.h"
#include "ValueVisitor.h"
#include "android-base/macros.h"
#include "androidfw/BigBuffer.h"
#include "androidfw/ResourceTypes.h"

namespace aapt {

struct FlatEntry {
  const ResourceTableEntryView* entry;
  const Value* value;

  // The entry string pool index to the entry's name.
  uint32_t entry_key;
};

// Pair of ResTable_entry and Res_value. These pairs are stored sequentially in values buffer.
// We introduce this structure for ResEntryWriter to a have single allocation using
// BigBuffer::NextBlock which allows to return it back with BigBuffer::Backup.
struct ResEntryValuePair {
  android::ResTable_entry entry;
  android::Res_value value;
};

// References ResEntryValuePair object stored in BigBuffer used as a key in std::unordered_map.
// Allows access to memory address where ResEntryValuePair is stored.
union ResEntryValuePairRef {
  const std::reference_wrapper<const ResEntryValuePair> pair;
  const u_char* ptr;

  explicit ResEntryValuePairRef(const ResEntryValuePair& ref) : pair(ref) {
  }
};

// Hasher which computes hash of ResEntryValuePair using its bytes representation in memory.
struct ResEntryValuePairContentHasher {
  std::size_t operator()(const ResEntryValuePairRef& ref) const;
};

// Equaler which compares ResEntryValuePairs using theirs bytes representation in memory.
struct ResEntryValuePairContentEqualTo {
  bool operator()(const ResEntryValuePairRef& a, const ResEntryValuePairRef& b) const;
};

// Base class that allows to write FlatEntries into entries_buffer.
class ResEntryWriter {
 public:
  virtual ~ResEntryWriter() = default;

  // Writes resource table entry and its value into 'entries_buffer_' and returns offset
  // in the buffer where entry was written.
  int32_t Write(const FlatEntry* entry) {
    if (ValueCast<Item>(entry->value) != nullptr) {
      return WriteItem(entry);
    } else {
      return WriteMap(entry);
    }
  }

 protected:
  ResEntryWriter(android::BigBuffer* entries_buffer) : entries_buffer_(entries_buffer) {
  }
  android::BigBuffer* entries_buffer_;

  virtual int32_t WriteItem(const FlatEntry* entry) = 0;

  virtual int32_t WriteMap(const FlatEntry* entry) = 0;

 private:
  DISALLOW_COPY_AND_ASSIGN(ResEntryWriter);
};

// ResEntryWriter which writes FlatEntries sequentially into entries_buffer.
// Next entry is always written right after previous one in the buffer.
class SequentialResEntryWriter : public ResEntryWriter {
 public:
  explicit SequentialResEntryWriter(android::BigBuffer* entries_buffer)
      : ResEntryWriter(entries_buffer) {
  }
  ~SequentialResEntryWriter() override = default;

  int32_t WriteItem(const FlatEntry* entry) override;

  int32_t WriteMap(const FlatEntry* entry) override;

 private:
  DISALLOW_COPY_AND_ASSIGN(SequentialResEntryWriter);
};

// ResEntryWriter that writes only unique entry and value pairs into entries_buffer.
// Next entry is written into buffer only if there is no entry with the same bytes representation
// in memory written before. Otherwise returns offset of already written entry.
class DeduplicateItemsResEntryWriter : public ResEntryWriter {
 public:
  explicit DeduplicateItemsResEntryWriter(android::BigBuffer* entries_buffer)
      : ResEntryWriter(entries_buffer) {
  }
  ~DeduplicateItemsResEntryWriter() override = default;

  int32_t WriteItem(const FlatEntry* entry) override;

  int32_t WriteMap(const FlatEntry* entry) override;

 private:
  DISALLOW_COPY_AND_ASSIGN(DeduplicateItemsResEntryWriter);

  std::unordered_map<ResEntryValuePairRef, int32_t, ResEntryValuePairContentHasher,
                     ResEntryValuePairContentEqualTo>
      entry_offsets;
};

}  // namespace aapt

#endif