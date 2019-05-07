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

#include "TestHelpers.h"

#include "ziparchive/zip_archive.h"

using ::testing::AssertionFailure;
using ::testing::AssertionResult;
using ::testing::AssertionSuccess;

namespace android {

AssertionResult ReadFileFromZipToString(const std::string& zip_path, const std::string& file,
                                        std::string* out_contents) {
  out_contents->clear();
  ::ZipArchiveHandle handle;
  int32_t result = OpenArchive(zip_path.c_str(), &handle);
  if (result != 0) {
    return AssertionFailure() << "Failed to open zip '" << zip_path
                              << "': " << ::ErrorCodeString(result);
  }

  ::ZipEntry entry;
  result = ::FindEntry(handle, file.c_str(), &entry);
  if (result != 0) {
    ::CloseArchive(handle);
    return AssertionFailure() << "Could not find file '" << file << "' in zip '" << zip_path
                              << "' : " << ::ErrorCodeString(result);
  }

  out_contents->resize(entry.uncompressed_length);
  result = ::ExtractToMemory(
      handle, &entry, const_cast<uint8_t*>(reinterpret_cast<const uint8_t*>(out_contents->data())),
      out_contents->size());
  if (result != 0) {
    ::CloseArchive(handle);
    return AssertionFailure() << "Failed to extract file '" << file << "' from zip '" << zip_path
                              << "': " << ::ErrorCodeString(result);
  }

  ::CloseArchive(handle);
  return AssertionSuccess();
}

AssertionResult IsStringEqual(const ResTable& table, uint32_t resource_id,
                              const char* expected_str) {
  Res_value val;
  ssize_t block = table.getResource(resource_id, &val, MAY_NOT_BE_BAG);
  if (block < 0) {
    return AssertionFailure() << "could not find resource";
  }

  if (val.dataType != Res_value::TYPE_STRING) {
    return AssertionFailure() << "resource is not a string";
  }

  const ResStringPool* pool = table.getTableStringBlock(block);
  if (pool == NULL) {
    return AssertionFailure() << "table has no string pool for block " << block;
  }

  const String8 actual_str = pool->string8ObjectAt(val.data);
  if (String8(expected_str) != actual_str) {
    return AssertionFailure() << actual_str.string();
  }
  return AssertionSuccess() << actual_str.string();
}

}  // namespace android
