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

#include "flatten/Archive.h"
#include "util/Files.h"
#include "util/StringPiece.h"

#include <cstdio>
#include <memory>
#include <string>
#include <vector>
#include <ziparchive/zip_writer.h>

namespace aapt {

namespace {

struct DirectoryWriter : public IArchiveWriter {
    std::string mOutDir;
    std::unique_ptr<FILE, decltype(fclose)*> mFile = { nullptr, fclose };

    bool open(IDiagnostics* diag, const StringPiece& outDir) {
        mOutDir = outDir.toString();
        file::FileType type = file::getFileType(mOutDir);
        if (type == file::FileType::kNonexistant) {
            diag->error(DiagMessage() << "directory " << mOutDir << " does not exist");
            return false;
        } else if (type != file::FileType::kDirectory) {
            diag->error(DiagMessage() << mOutDir << " is not a directory");
            return false;
        }
        return true;
    }

    bool startEntry(const StringPiece& path, uint32_t flags) override {
        if (mFile) {
            return false;
        }

        std::string fullPath = mOutDir;
        file::appendPath(&fullPath, path);
        file::mkdirs(file::getStem(fullPath));

        mFile = { fopen(fullPath.data(), "wb"), fclose };
        if (!mFile) {
            return false;
        }
        return true;
    }

    bool writeEntry(const BigBuffer& buffer) override {
        if (!mFile) {
            return false;
        }

        for (const BigBuffer::Block& b : buffer) {
            if (fwrite(b.buffer.get(), 1, b.size, mFile.get()) != b.size) {
                mFile.reset(nullptr);
                return false;
            }
        }
        return true;
    }

    bool writeEntry(const void* data, size_t len) override {
        if (fwrite(data, 1, len, mFile.get()) != len) {
            mFile.reset(nullptr);
            return false;
        }
        return true;
    }

    bool finishEntry() override {
        if (!mFile) {
            return false;
        }
        mFile.reset(nullptr);
        return true;
    }
};

struct ZipFileWriter : public IArchiveWriter {
    std::unique_ptr<FILE, decltype(fclose)*> mFile = { nullptr, fclose };
    std::unique_ptr<ZipWriter> mWriter;

    bool open(IDiagnostics* diag, const StringPiece& path) {
        mFile = { fopen(path.data(), "w+b"), fclose };
        if (!mFile) {
            diag->error(DiagMessage() << "failed to open " << path << ": " << strerror(errno));
            return false;
        }
        mWriter = util::make_unique<ZipWriter>(mFile.get());
        return true;
    }

    bool startEntry(const StringPiece& path, uint32_t flags) override {
        if (!mWriter) {
            return false;
        }

        size_t zipFlags = 0;
        if (flags & ArchiveEntry::kCompress) {
            zipFlags |= ZipWriter::kCompress;
        }

        if (flags & ArchiveEntry::kAlign) {
            zipFlags |= ZipWriter::kAlign32;
        }

        int32_t result = mWriter->StartEntry(path.data(), zipFlags);
        if (result != 0) {
            return false;
        }
        return true;
    }

    bool writeEntry(const void* data, size_t len) override {
        int32_t result = mWriter->WriteBytes(data, len);
        if (result != 0) {
            return false;
        }
        return true;
    }

    bool writeEntry(const BigBuffer& buffer) override {
        for (const BigBuffer::Block& b : buffer) {
            int32_t result = mWriter->WriteBytes(b.buffer.get(), b.size);
            if (result != 0) {
                return false;
            }
        }
        return true;
    }

    bool finishEntry() override {
        int32_t result = mWriter->FinishEntry();
        if (result != 0) {
            return false;
        }
        return true;
    }

    virtual ~ZipFileWriter() {
        if (mWriter) {
            mWriter->Finish();
        }
    }
};

} // namespace

std::unique_ptr<IArchiveWriter> createDirectoryArchiveWriter(IDiagnostics* diag,
                                                             const StringPiece& path) {

    std::unique_ptr<DirectoryWriter> writer = util::make_unique<DirectoryWriter>();
    if (!writer->open(diag, path)) {
        return {};
    }
    return std::move(writer);
}

std::unique_ptr<IArchiveWriter> createZipFileArchiveWriter(IDiagnostics* diag,
                                                           const StringPiece& path) {
    std::unique_ptr<ZipFileWriter> writer = util::make_unique<ZipFileWriter>();
    if (!writer->open(diag, path)) {
        return {};
    }
    return std::move(writer);
}

} // namespace aapt
