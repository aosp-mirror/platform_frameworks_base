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

IFile* IFile::CreateFileSegment(size_t offset, size_t len) {
  FileSegment* file_segment = new FileSegment(this, offset, len);
  segments_.push_back(std::unique_ptr<IFile>(file_segment));
  return file_segment;
}

std::unique_ptr<IData> FileSegment::OpenAsData() {
  std::unique_ptr<IData> data = file_->OpenAsData();
  if (!data) {
    return {};
  }

  if (offset_ <= data->size() - len_) {
    return util::make_unique<DataSegment>(std::move(data), offset_, len_);
  }
  return {};
}

std::unique_ptr<android::InputStream> FileSegment::OpenInputStream() {
  return OpenAsData();
}

}  // namespace io
}  // namespace aapt
