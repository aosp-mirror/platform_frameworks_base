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
#include "io/FileSystem.h"
#include "util/Files.h"
#include "util/Maybe.h"
#include "util/StringPiece.h"
#include "util/Util.h"

#include <utils/FileMap.h>

namespace aapt {
namespace io {

RegularFile::RegularFile(const Source& source) : mSource(source) {
}

std::unique_ptr<IData> RegularFile::openAsData() {
    android::FileMap map;
    if (Maybe<android::FileMap> map = file::mmapPath(mSource.path, nullptr)) {
        if (map.value().getDataPtr() && map.value().getDataLength() > 0) {
            return util::make_unique<MmappedData>(std::move(map.value()));
        }
        return util::make_unique<EmptyData>();
    }
    return {};
}

const Source& RegularFile::getSource() const {
    return mSource;
}

FileCollectionIterator::FileCollectionIterator(FileCollection* collection) :
        mCurrent(collection->mFiles.begin()), mEnd(collection->mFiles.end()) {
}

bool FileCollectionIterator::hasNext() {
    return mCurrent != mEnd;
}

IFile* FileCollectionIterator::next() {
    IFile* result = mCurrent->second.get();
    ++mCurrent;
    return result;
}

IFile* FileCollection::insertFile(const StringPiece& path) {
    return (mFiles[path.toString()] = util::make_unique<RegularFile>(Source(path))).get();
}

IFile* FileCollection::findFile(const StringPiece& path) {
    auto iter = mFiles.find(path.toString());
    if (iter != mFiles.end()) {
        return iter->second.get();
    }
    return nullptr;
}

std::unique_ptr<IFileCollectionIterator> FileCollection::iterator() {
    return util::make_unique<FileCollectionIterator>(this);
}

} // namespace io
} // namespace aapt
