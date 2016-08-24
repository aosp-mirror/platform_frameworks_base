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

#include <map>
#include <ziparchive/zip_archive.h>

namespace aapt {
namespace io {

/**
 * An IFile representing a file within a ZIP archive. If the file is compressed, it is uncompressed
 * and copied into memory when opened. Otherwise it is mmapped from the ZIP archive.
 */
class ZipFile : public IFile {
public:
    ZipFile(ZipArchiveHandle handle, const ZipEntry& entry, const Source& source);

    std::unique_ptr<IData> openAsData() override;
    const Source& getSource() const override;

private:
    ZipArchiveHandle mZipHandle;
    ZipEntry mZipEntry;
    Source mSource;
};

class ZipFileCollection;

class ZipFileCollectionIterator : public IFileCollectionIterator {
public:
    ZipFileCollectionIterator(ZipFileCollection* collection);

    bool hasNext() override;
    io::IFile* next() override;

private:
    std::map<std::string, std::unique_ptr<IFile>>::const_iterator mCurrent, mEnd;
};

/**
 * An IFileCollection that represents a ZIP archive and the entries within it.
 */
class ZipFileCollection : public IFileCollection {
public:
    static std::unique_ptr<ZipFileCollection> create(const StringPiece& path,
                                                     std::string* outError);

    io::IFile* findFile(const StringPiece& path) override;
    std::unique_ptr<IFileCollectionIterator> iterator() override;

    ~ZipFileCollection() override;

private:
    friend class ZipFileCollectionIterator;
    ZipFileCollection();

    ZipArchiveHandle mHandle;
    std::map<std::string, std::unique_ptr<IFile>> mFiles;
};

} // namespace io
} // namespace aapt

#endif /* AAPT_IO_ZIPARCHIVE_H */
