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

#include "ResourceTable.h"
#include "ValueVisitor.h"
#include "android-base/macros.h"
#include "androidfw/BigBuffer.h"

namespace aapt {

struct FlatEntry {
  const ResourceTableEntryView* entry;
  const Value* value;

  // The entry string pool index to the entry's name.
  uint32_t entry_key;
};

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

}  // namespace aapt

#endif