/*
 * Copyright (C) 2017 The Android Open Source Project
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

#ifndef AAPT_FORMAT_CONTAINER_H
#define AAPT_FORMAT_CONTAINER_H

#include <inttypes.h>

#include "Resources.pb.h"
#include "ResourcesInternal.pb.h"
#include "androidfw/BigBuffer.h"
#include "androidfw/Streams.h"
#include "google/protobuf/io/coded_stream.h"
#include "google/protobuf/io/zero_copy_stream.h"
#include "io/Util.h"

namespace aapt {

enum ContainerEntryType : uint8_t {
  kResTable = 0x00u,
  kResFile = 0x01u,
};

class ContainerWriter {
 public:
  explicit ContainerWriter(::google::protobuf::io::ZeroCopyOutputStream* out, size_t entry_count);

  bool AddResTableEntry(const pb::ResourceTable& table);
  bool AddResFileEntry(const pb::internal::CompiledFile& file, android::KnownSizeInputStream* in);
  bool HadError() const;
  std::string GetError() const;

 private:
  DISALLOW_COPY_AND_ASSIGN(ContainerWriter);

  ::google::protobuf::io::ZeroCopyOutputStream* out_;
  size_t total_entry_count_;
  size_t current_entry_count_;
  std::string error_;
};

class ContainerReader;

class ContainerReaderEntry {
 public:
  ContainerEntryType Type() const;

  bool GetResTable(pb::ResourceTable* out_table);
  bool GetResFileOffsets(pb::internal::CompiledFile* out_file, off64_t* out_offset,
                         size_t* out_len);

  bool HadError() const;
  std::string GetError() const;

 private:
  DISALLOW_COPY_AND_ASSIGN(ContainerReaderEntry);

  friend class ContainerReader;

  explicit ContainerReaderEntry(ContainerReader* reader);

  ContainerReader* reader_;
  ContainerEntryType type_ = ContainerEntryType::kResTable;
  size_t length_ = 0u;
};

class ContainerReader {
 public:
  explicit ContainerReader(android::InputStream* in);

  ContainerReaderEntry* Next();

  bool HadError() const;
  std::string GetError() const;

 private:
  DISALLOW_COPY_AND_ASSIGN(ContainerReader);

  friend class ContainerReaderEntry;

  android::InputStream* in_;
  io::ZeroCopyInputAdaptor adaptor_;
  ::google::protobuf::io::CodedInputStream coded_in_;
  size_t total_entry_count_;
  size_t current_entry_count_;
  ContainerReaderEntry entry_;
  std::string error_;
};

}  // namespace aapt

#endif /* AAPT_FORMAT_CONTAINER_H */
