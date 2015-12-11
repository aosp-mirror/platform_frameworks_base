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

#ifndef AAPT_IO_FILE_H
#define AAPT_IO_FILE_H

#include "Source.h"
#include "io/Data.h"

#include <memory>
#include <vector>

namespace aapt {
namespace io {

/**
 * Interface for a file, which could be a real file on the file system, or a file inside
 * a ZIP archive.
 */
class IFile {
public:
    virtual ~IFile() = default;

    /**
     * Open the file and return it as a block of contiguous memory. How this occurs is
     * implementation dependent. For example, if this is a file on the file system, it may
     * simply mmap the contents. If this file represents a compressed file in a ZIP archive,
     * it may need to inflate it to memory, incurring a copy.
     *
     * Returns nullptr on failure.
     */
    virtual std::unique_ptr<IData> openAsData() = 0;

    /**
     * Returns the source of this file. This is for presentation to the user and may not be a
     * valid file system path (for example, it may contain a '@' sign to separate the files within
     * a ZIP archive from the path to the containing ZIP archive.
     */
    virtual const Source& getSource() const = 0;
};

class IFileCollectionIterator {
public:
    virtual ~IFileCollectionIterator() = default;

    virtual bool hasNext() = 0;
    virtual IFile* next() = 0;
};

/**
 * Interface for a collection of files, all of which share a common source. That source may
 * simply be the filesystem, or a ZIP archive.
 */
class IFileCollection {
public:
    virtual ~IFileCollection() = default;

    virtual IFile* findFile(const StringPiece& path) = 0;
    virtual std::unique_ptr<IFileCollectionIterator> iterator() = 0;
};

} // namespace io
} // namespace aapt

#endif /* AAPT_IO_FILE_H */
