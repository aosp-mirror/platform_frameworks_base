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

#ifndef AAPT_IO_FILESYSTEM_H
#define AAPT_IO_FILESYSTEM_H

#include "io/File.h"

#include <map>

namespace aapt {
namespace io {

/**
 * A regular file from the file system. Uses mmap to open the data.
 */
class RegularFile : public IFile {
public:
    RegularFile(const Source& source);

    std::unique_ptr<IData> openAsData() override;
    const Source& getSource() const override;

private:
    Source mSource;
};

class FileCollection;

class FileCollectionIterator : public IFileCollectionIterator {
public:
    FileCollectionIterator(FileCollection* collection);

    bool hasNext() override;
    io::IFile* next() override;

private:
    std::map<std::string, std::unique_ptr<IFile>>::const_iterator mCurrent, mEnd;
};

/**
 * An IFileCollection representing the file system.
 */
class FileCollection : public IFileCollection {
public:
    /**
     * Adds a file located at path. Returns the IFile representation of that file.
     */
    IFile* insertFile(const StringPiece& path);
    IFile* findFile(const StringPiece& path) override;
    std::unique_ptr<IFileCollectionIterator> iterator() override;

private:
    friend class FileCollectionIterator;
    std::map<std::string, std::unique_ptr<IFile>> mFiles;
};

} // namespace io
} // namespace aapt

#endif // AAPT_IO_FILESYSTEM_H
