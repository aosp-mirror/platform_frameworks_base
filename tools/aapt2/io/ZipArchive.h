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

#ifndef AAPT_IO_ZIPARCHIVE_H
#define AAPT_IO_ZIPARCHIVE_H

#include "io/File.h"
#include "util/StringPiece.h"

#include <utils/FileMap.h>
#include <ziparchive/zip_archive.h>

namespace aapt {
namespace io {

/**
 * An IFile representing a file within a ZIP archive. If the file is compressed, it is uncompressed
 * and copied into memory when opened. Otherwise it is mmapped from the ZIP archive.
 */
class ZipFile : public IFile {
public:
    ZipFile(ZipArchiveHandle handle, const ZipEntry& entry, const Source& source) :
            mZipHandle(handle), mZipEntry(entry), mSource(source) {
    }

    std::unique_ptr<IData> openAsData() override {
        if (mZipEntry.method == kCompressStored) {
            int fd = GetFileDescriptor(mZipHandle);

            android::FileMap fileMap;
            bool result = fileMap.create(nullptr, fd, mZipEntry.offset,
                                         mZipEntry.uncompressed_length, true);
            if (!result) {
                return {};
            }
            return util::make_unique<MmappedData>(std::move(fileMap));

        } else {
            std::unique_ptr<uint8_t[]> data = std::unique_ptr<uint8_t[]>(
                    new uint8_t[mZipEntry.uncompressed_length]);
            int32_t result = ExtractToMemory(mZipHandle, &mZipEntry, data.get(),
                                             static_cast<uint32_t>(mZipEntry.uncompressed_length));
            if (result != 0) {
                return {};
            }
            return util::make_unique<MallocData>(std::move(data), mZipEntry.uncompressed_length);
        }
    }

    const Source& getSource() const override {
        return mSource;
    }

private:
    ZipArchiveHandle mZipHandle;
    ZipEntry mZipEntry;
    Source mSource;
};

/**
 * An IFileCollection that represents a ZIP archive and the entries within it.
 */
class ZipFileCollection : public IFileCollection {
public:
    static std::unique_ptr<ZipFileCollection> create(const StringPiece& path,
                                                     std::string* outError) {
        std::unique_ptr<ZipFileCollection> collection = std::unique_ptr<ZipFileCollection>(
                new ZipFileCollection());

        int32_t result = OpenArchive(path.data(), &collection->mHandle);
        if (result != 0) {
            if (outError) *outError = ErrorCodeString(result);
            return {};
        }

        ZipString suffix(".flat");
        void* cookie = nullptr;
        result = StartIteration(collection->mHandle, &cookie, nullptr, &suffix);
        if (result != 0) {
            if (outError) *outError = ErrorCodeString(result);
            return {};
        }

        using IterationEnder = std::unique_ptr<void, decltype(EndIteration)*>;
        IterationEnder iterationEnder(cookie, EndIteration);

        ZipString zipEntryName;
        ZipEntry zipData;
        while ((result = Next(cookie, &zipData, &zipEntryName)) == 0) {
            std::string nestedPath = path.toString();
            nestedPath += "@" + std::string(reinterpret_cast<const char*>(zipEntryName.name),
                                            zipEntryName.name_length);
            collection->mFiles.push_back(util::make_unique<ZipFile>(collection->mHandle,
                                                                    zipData,
                                                                    Source(nestedPath)));
        }

        if (result != -1) {
            if (outError) *outError = ErrorCodeString(result);
            return {};
        }
        return collection;
    }

    const_iterator begin() const override {
        return mFiles.begin();
    }

    const_iterator end() const override {
        return mFiles.end();
    }

    ~ZipFileCollection() override {
        if (mHandle) {
            CloseArchive(mHandle);
        }
    }

private:
    ZipFileCollection() : mHandle(nullptr) {
    }

    ZipArchiveHandle mHandle;
    std::vector<std::unique_ptr<IFile>> mFiles;
};

} // namespace io
} // namespace aapt

#endif /* AAPT_IO_ZIPARCHIVE_H */
