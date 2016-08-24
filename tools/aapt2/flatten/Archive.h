/*
 * Copyright (C) 2015 The Android Open Source Project
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

#ifndef AAPT_FLATTEN_ARCHIVE_H
#define AAPT_FLATTEN_ARCHIVE_H

#include "Diagnostics.h"
#include "util/BigBuffer.h"
#include "util/Files.h"
#include "util/StringPiece.h"

#include <google/protobuf/io/zero_copy_stream_impl_lite.h>
#include <fstream>
#include <memory>
#include <string>
#include <vector>

namespace aapt {

struct ArchiveEntry {
    enum : uint32_t {
        kCompress = 0x01,
        kAlign    = 0x02,
    };

    std::string path;
    uint32_t flags;
    size_t uncompressedSize;
};

struct IArchiveWriter : public google::protobuf::io::CopyingOutputStream {
    virtual ~IArchiveWriter() = default;

    virtual bool startEntry(const StringPiece& path, uint32_t flags) = 0;
    virtual bool writeEntry(const BigBuffer& buffer) = 0;
    virtual bool writeEntry(const void* data, size_t len) = 0;
    virtual bool finishEntry() = 0;

    // CopyingOutputStream implementations.
    bool Write(const void* buffer, int size) override {
        return writeEntry(buffer, size);
    }
};

std::unique_ptr<IArchiveWriter> createDirectoryArchiveWriter(IDiagnostics* diag,
                                                             const StringPiece& path);

std::unique_ptr<IArchiveWriter> createZipFileArchiveWriter(IDiagnostics* diag,
                                                           const StringPiece& path);

} // namespace aapt

#endif /* AAPT_FLATTEN_ARCHIVE_H */
