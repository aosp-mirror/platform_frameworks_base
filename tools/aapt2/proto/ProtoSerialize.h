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

#include "Diagnostics.h"
#include "ResourceTable.h"
#include "Source.h"
#include "proto/ProtoHelpers.h"

#include <android-base/macros.h>
#include <google/protobuf/io/coded_stream.h>
#include <google/protobuf/io/zero_copy_stream_impl_lite.h>

namespace aapt {

std::unique_ptr<pb::ResourceTable> serializeTableToPb(ResourceTable* table);
std::unique_ptr<ResourceTable> deserializeTableFromPb(const pb::ResourceTable& pbTable,
                                                      const Source& source,
                                                      IDiagnostics* diag);

std::unique_ptr<pb::CompiledFile> serializeCompiledFileToPb(const ResourceFile& file);
std::unique_ptr<ResourceFile> deserializeCompiledFileFromPb(const pb::CompiledFile& pbFile,
                                                            const Source& source,
                                                            IDiagnostics* diag);

class CompiledFileOutputStream : public google::protobuf::io::CopyingOutputStream {
public:
    CompiledFileOutputStream(google::protobuf::io::ZeroCopyOutputStream* out,
                             pb::CompiledFile* pbFile);
    bool Write(const void* data, int size) override;
    bool Finish();

private:
    bool ensureFileWritten();

    google::protobuf::io::CodedOutputStream mOut;
    pb::CompiledFile* mPbFile;

    DISALLOW_COPY_AND_ASSIGN(CompiledFileOutputStream);
};

class CompiledFileInputStream {
public:
    CompiledFileInputStream(const void* data, size_t size);

    const pb::CompiledFile* CompiledFile();

    const void* data();
    size_t size();

private:
    google::protobuf::io::CodedInputStream mIn;
    std::unique_ptr<pb::CompiledFile> mPbFile;
    const uint8_t* mData;
    size_t mSize;

    DISALLOW_COPY_AND_ASSIGN(CompiledFileInputStream);
};

} // namespace aapt

#endif /* AAPT_FLATTEN_TABLEPROTOSERIALIZER_H */
