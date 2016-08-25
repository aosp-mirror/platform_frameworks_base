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

#include "io/File.h"

#include <memory>

namespace aapt {
namespace io {

IFile* IFile::createFileSegment(size_t offset, size_t len) {
   FileSegment* fileSegment = new FileSegment(this, offset, len);
   mSegments.push_back(std::unique_ptr<IFile>(fileSegment));
   return fileSegment;
}

std::unique_ptr<IData> FileSegment::openAsData() {
    std::unique_ptr<IData> data = mFile->openAsData();
    if (!data) {
        return {};
    }

    if (mOffset <= data->size() - mLen) {
        return util::make_unique<DataSegment>(std::move(data), mOffset, mLen);
    }
    return {};
}

} // namespace io
} // namespace aapt
