/*
 * Copyright (C) 2016 The Android Open Source Project
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

#ifndef AAPT_FLATTEN_TABLEPROTOSERIALIZER_H
#define AAPT_FLATTEN_TABLEPROTOSERIALIZER_H

#include "android-base/macros.h"
#include "google/protobuf/io/coded_stream.h"
#include "google/protobuf/io/zero_copy_stream_impl_lite.h"

#include "Diagnostics.h"
#include "ResourceTable.h"
#include "Source.h"
#include "proto/ProtoHelpers.h"

namespace aapt {

class CompiledFileOutputStream {
 public:
  explicit CompiledFileOutputStream(
      google::protobuf::io::ZeroCopyOutputStream* out);

  void WriteLittleEndian32(uint32_t value);
  void WriteCompiledFile(const pb::CompiledFile* compiledFile);
  void WriteData(const BigBuffer* buffer);
  void WriteData(const void* data, size_t len);
  bool HadError();

 private:
  DISALLOW_COPY_AND_ASSIGN(CompiledFileOutputStream);

  void EnsureAlignedWrite();

  google::protobuf::io::CodedOutputStream out_;
};

class CompiledFileInputStream {
 public:
  explicit CompiledFileInputStream(const void* data, size_t size);

  bool ReadLittleEndian32(uint32_t* outVal);
  bool ReadCompiledFile(pb::CompiledFile* outVal);
  bool ReadDataMetaData(uint64_t* outOffset, uint64_t* outLen);

 private:
  DISALLOW_COPY_AND_ASSIGN(CompiledFileInputStream);

  void EnsureAlignedRead();

  google::protobuf::io::CodedInputStream in_;
};

std::unique_ptr<pb::ResourceTable> SerializeTableToPb(ResourceTable* table);
std::unique_ptr<ResourceTable> DeserializeTableFromPb(
    const pb::ResourceTable& pbTable, const Source& source, IDiagnostics* diag);

std::unique_ptr<pb::CompiledFile> SerializeCompiledFileToPb(
    const ResourceFile& file);
std::unique_ptr<ResourceFile> DeserializeCompiledFileFromPb(
    const pb::CompiledFile& pbFile, const Source& source, IDiagnostics* diag);

}  // namespace aapt

#endif /* AAPT_FLATTEN_TABLEPROTOSERIALIZER_H */
