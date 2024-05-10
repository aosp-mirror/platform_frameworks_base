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

using android::BigBuffer;
using android::Res_value;
using android::ResTable_entry;
using android::ResTable_map;

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
  ResTable_entry entry;
  Res_value value;
};

static_assert(sizeof(ResEntryValuePair) == sizeof(ResTable_entry) + sizeof(Res_value),
              "ResEntryValuePair must not have padding between entry and value.");

template <bool compact>
using ResEntryValue = std::conditional_t<compact, ResTable_entry, ResEntryValuePair>;

// References ResEntryValue object stored in BigBuffer used as a key in std::unordered_map.
// Allows access to memory address where ResEntryValue is stored.
template <bool compact>
union ResEntryValueRef {
  using T = ResEntryValue<compact>;
  const std::reference_wrapper<const T> ref;
  const u_char* ptr;

  explicit ResEntryValueRef(const T& rev) : ref(rev) {
  }
};

// Hasher which computes hash of ResEntryValue using its bytes representation in memory.
struct ResEntryValueContentHasher {
  template <typename R>
  std::size_t operator()(const R& ref) const {
    return android::JenkinsHashMixBytes(0, ref.ptr, sizeof(typename R::T));
  }
};

// Equaler which compares ResEntryValuePairs using theirs bytes representation in memory.
struct ResEntryValueContentEqualTo {
  template <typename R>
  bool operator()(const R& a, const R& b) const {
    return std::memcmp(a.ptr, b.ptr, sizeof(typename R::T)) == 0;
  }
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
  ResEntryWriter(BigBuffer* entries_buffer) : entries_buffer_(entries_buffer) {
  }
  BigBuffer* entries_buffer_;

  virtual int32_t WriteItem(const FlatEntry* entry) = 0;

  virtual int32_t WriteMap(const FlatEntry* entry) = 0;

 private:
  DISALLOW_COPY_AND_ASSIGN(ResEntryWriter);
};

int32_t WriteMapToBuffer(const FlatEntry* map_entry, BigBuffer* buffer);

template <bool compact_entry, typename T=ResEntryValue<compact_entry>>
std::pair<int32_t, T*> WriteItemToBuffer(const FlatEntry* item_entry, BigBuffer* buffer);

// ResEntryWriter which writes FlatEntries sequentially into entries_buffer.
// Next entry is always written right after previous one in the buffer.
template <bool compact_entry = false>
class SequentialResEntryWriter : public ResEntryWriter {
 public:
  explicit SequentialResEntryWriter(BigBuffer* entries_buffer)
      : ResEntryWriter(entries_buffer) {
  }
  ~SequentialResEntryWriter() override = default;

  int32_t WriteItem(const FlatEntry* entry) override {
      auto result = WriteItemToBuffer<compact_entry>(entry, entries_buffer_);
      return result.first;
  }

  int32_t WriteMap(const FlatEntry* entry) override {
      return WriteMapToBuffer(entry, entries_buffer_);
  }

 private:
  DISALLOW_COPY_AND_ASSIGN(SequentialResEntryWriter);
};

// ResEntryWriter that writes only unique entry and value pairs into entries_buffer.
// Next entry is written into buffer only if there is no entry with the same bytes representation
// in memory written before. Otherwise returns offset of already written entry.
template <bool compact_entry = false>
class DeduplicateItemsResEntryWriter : public ResEntryWriter {
 public:
  explicit DeduplicateItemsResEntryWriter(BigBuffer* entries_buffer)
      : ResEntryWriter(entries_buffer) {
  }
  ~DeduplicateItemsResEntryWriter() override = default;

  int32_t WriteItem(const FlatEntry* entry) override {
    const auto& [offset, out_entry] = WriteItemToBuffer<compact_entry>(entry, entries_buffer_);

    auto [it, inserted] = entry_offsets.insert({Ref{*out_entry}, offset});
    if (inserted) {
      // If inserted just return a new offset as this is a first time we store
      // this entry
      return offset;
    }

    // If not inserted this means that this is a duplicate, backup allocated block to the buffer
    // and return offset of previously stored entry
    entries_buffer_->BackUp(sizeof(*out_entry));
    return it->second;
  }

  int32_t WriteMap(const FlatEntry* entry) override {
      return WriteMapToBuffer(entry, entries_buffer_);
  }

 private:
  DISALLOW_COPY_AND_ASSIGN(DeduplicateItemsResEntryWriter);

  using Ref = ResEntryValueRef<compact_entry>;
  using Map = std::unordered_map<Ref, int32_t,
                        ResEntryValueContentHasher,
                        ResEntryValueContentEqualTo>;
  Map entry_offsets;
};

}  // namespace aapt

#endif
