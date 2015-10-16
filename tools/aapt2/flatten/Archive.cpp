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

#include <fstream>
#include <memory>
#include <string>
#include <vector>
#include <ziparchive/zip_writer.h>

namespace aapt {

namespace {

struct DirectoryWriter : public IArchiveWriter {
    std::string mOutDir;
    std::vector<std::unique_ptr<ArchiveEntry>> mEntries;

    explicit DirectoryWriter(const StringPiece& outDir) : mOutDir(outDir.toString()) {
    }

    ArchiveEntry* writeEntry(const StringPiece& path, uint32_t flags,
                             const BigBuffer& buffer) override {
        std::string fullPath = mOutDir;
        file::appendPath(&fullPath, path);
        file::mkdirs(file::getStem(fullPath));

        std::ofstream fout(fullPath, std::ofstream::binary);
        if (!fout) {
            return nullptr;
        }

        if (!util::writeAll(fout, buffer)) {
            return nullptr;
        }

        mEntries.push_back(util::make_unique<ArchiveEntry>(fullPath, flags, buffer.size()));
        return mEntries.back().get();
    }

    ArchiveEntry* writeEntry(const StringPiece& path, uint32_t flags, android::FileMap* fileMap,
                             size_t offset, size_t len) override {
        std::string fullPath = mOutDir;
        file::appendPath(&fullPath, path);
        file::mkdirs(file::getStem(fullPath));

        std::ofstream fout(fullPath, std::ofstream::binary);
        if (!fout) {
            return nullptr;
        }

        if (!fout.write((const char*) fileMap->getDataPtr() + offset, len)) {
            return nullptr;
        }

        mEntries.push_back(util::make_unique<ArchiveEntry>(fullPath, flags, len));
        return mEntries.back().get();
    }

    virtual ~DirectoryWriter() {

    }
};

struct ZipFileWriter : public IArchiveWriter {
    FILE* mFile;
    std::unique_ptr<ZipWriter> mWriter;
    std::vector<std::unique_ptr<ArchiveEntry>> mEntries;

    explicit ZipFileWriter(const StringPiece& path) {
        mFile = fopen(path.data(), "w+b");
        if (mFile) {
            mWriter = util::make_unique<ZipWriter>(mFile);
        }
    }

    ArchiveEntry* writeEntry(const StringPiece& path, uint32_t flags,
                             const BigBuffer& buffer) override {
        if (!mWriter) {
            return nullptr;
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
            return nullptr;
        }

        for (const BigBuffer::Block& b : buffer) {
            result = mWriter->WriteBytes(reinterpret_cast<const uint8_t*>(b.buffer.get()), b.size);
            if (result != 0) {
                return nullptr;
            }
        }

        result = mWriter->FinishEntry();
        if (result != 0) {
            return nullptr;
        }

        mEntries.push_back(util::make_unique<ArchiveEntry>(path.toString(), flags, buffer.size()));
        return mEntries.back().get();
    }

    ArchiveEntry* writeEntry(const StringPiece& path, uint32_t flags, android::FileMap* fileMap,
                             size_t offset, size_t len) override {
        if (!mWriter) {
            return nullptr;
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
            return nullptr;
        }

        result = mWriter->WriteBytes((const char*) fileMap->getDataPtr() + offset, len);
        if (result != 0) {
            return nullptr;
        }

        result = mWriter->FinishEntry();
        if (result != 0) {
            return nullptr;
        }

        mEntries.push_back(util::make_unique<ArchiveEntry>(path.toString(), flags, len));
        return mEntries.back().get();
    }

    virtual ~ZipFileWriter() {
        if (mWriter) {
            mWriter->Finish();
            fclose(mFile);
        }
    }
};

} // namespace

std::unique_ptr<IArchiveWriter> createDirectoryArchiveWriter(const StringPiece& path) {
    return util::make_unique<DirectoryWriter>(path);
}

std::unique_ptr<IArchiveWriter> createZipFileArchiveWriter(const StringPiece& path) {
    return util::make_unique<ZipFileWriter>(path);
}

} // namespace aapt
