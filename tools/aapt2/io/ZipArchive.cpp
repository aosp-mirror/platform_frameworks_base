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

#include "Source.h"
#include "io/ZipArchive.h"
#include "util/Util.h"

#include <utils/FileMap.h>
#include <ziparchive/zip_archive.h>

namespace aapt {
namespace io {

ZipFile::ZipFile(ZipArchiveHandle handle, const ZipEntry& entry, const Source& source) :
        mZipHandle(handle), mZipEntry(entry), mSource(source) {
}

std::unique_ptr<IData> ZipFile::openAsData() {
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

const Source& ZipFile::getSource() const {
    return mSource;
}

ZipFileCollectionIterator::ZipFileCollectionIterator(ZipFileCollection* collection) :
        mCurrent(collection->mFiles.begin()), mEnd(collection->mFiles.end()) {
}

bool ZipFileCollectionIterator::hasNext() {
    return mCurrent != mEnd;
}

IFile* ZipFileCollectionIterator::next() {
    IFile* result = mCurrent->second.get();
    ++mCurrent;
    return result;
}

ZipFileCollection::ZipFileCollection() : mHandle(nullptr) {
}

std::unique_ptr<ZipFileCollection> ZipFileCollection::create(const StringPiece& path,
                                                             std::string* outError) {
    constexpr static const int32_t kEmptyArchive = -6;

    std::unique_ptr<ZipFileCollection> collection = std::unique_ptr<ZipFileCollection>(
            new ZipFileCollection());

    int32_t result = OpenArchive(path.data(), &collection->mHandle);
    if (result != 0) {
        // If a zip is empty, result will be an error code. This is fine and we should
        // return an empty ZipFileCollection.
        if (result == kEmptyArchive) {
            return collection;
        }

        if (outError) *outError = ErrorCodeString(result);
        return {};
    }

    void* cookie = nullptr;
    result = StartIteration(collection->mHandle, &cookie, nullptr, nullptr);
    if (result != 0) {
        if (outError) *outError = ErrorCodeString(result);
        return {};
    }

    using IterationEnder = std::unique_ptr<void, decltype(EndIteration)*>;
    IterationEnder iterationEnder(cookie, EndIteration);

    ZipString zipEntryName;
    ZipEntry zipData;
    while ((result = Next(cookie, &zipData, &zipEntryName)) == 0) {
        std::string zipEntryPath = std::string(reinterpret_cast<const char*>(zipEntryName.name),
                                               zipEntryName.name_length);
        std::string nestedPath = path.toString() + "@" + zipEntryPath;
        collection->mFiles[zipEntryPath] = util::make_unique<ZipFile>(collection->mHandle,
                                                                      zipData,
                                                                      Source(nestedPath));
    }

    if (result != -1) {
        if (outError) *outError = ErrorCodeString(result);
        return {};
    }
    return collection;
}

IFile* ZipFileCollection::findFile(const StringPiece& path) {
    auto iter = mFiles.find(path.toString());
    if (iter != mFiles.end()) {
        return iter->second.get();
    }
    return nullptr;
}

std::unique_ptr<IFileCollectionIterator> ZipFileCollection::iterator() {
    return util::make_unique<ZipFileCollectionIterator>(this);
}

ZipFileCollection::~ZipFileCollection() {
    if (mHandle) {
        CloseArchive(mHandle);
    }
}

} // namespace io
} // namespace aapt
