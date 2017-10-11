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

#define ATRACE_TAG ATRACE_TAG_RESOURCES

#include "androidfw/ApkAssets.h"

#include <algorithm>

#include "android-base/logging.h"
#include "utils/FileMap.h"
#include "utils/Trace.h"
#include "ziparchive/zip_archive.h"

#include "androidfw/Asset.h"
#include "androidfw/Util.h"

namespace android {

std::unique_ptr<const ApkAssets> ApkAssets::Load(const std::string& path, bool system) {
  return ApkAssets::LoadImpl(path, system, false /*load_as_shared_library*/);
}

std::unique_ptr<const ApkAssets> ApkAssets::LoadAsSharedLibrary(const std::string& path,
                                                                bool system) {
  return ApkAssets::LoadImpl(path, system, true /*load_as_shared_library*/);
}

std::unique_ptr<const ApkAssets> ApkAssets::LoadImpl(const std::string& path, bool system,
                                                     bool load_as_shared_library) {
  ATRACE_CALL();
  ::ZipArchiveHandle unmanaged_handle;
  int32_t result = ::OpenArchive(path.c_str(), &unmanaged_handle);
  if (result != 0) {
    LOG(ERROR) << ::ErrorCodeString(result);
    return {};
  }

  // Wrap the handle in a unique_ptr so it gets automatically closed.
  std::unique_ptr<ApkAssets> loaded_apk(new ApkAssets());
  loaded_apk->zip_handle_.reset(unmanaged_handle);

  ::ZipString entry_name("resources.arsc");
  ::ZipEntry entry;
  result = ::FindEntry(loaded_apk->zip_handle_.get(), entry_name, &entry);
  if (result != 0) {
    LOG(ERROR) << ::ErrorCodeString(result);
    return {};
  }

  if (entry.method == kCompressDeflated) {
    LOG(WARNING) << "resources.arsc is compressed.";
  }

  loaded_apk->path_ = path;
  loaded_apk->resources_asset_ =
      loaded_apk->Open("resources.arsc", Asset::AccessMode::ACCESS_BUFFER);
  if (loaded_apk->resources_asset_ == nullptr) {
    return {};
  }

  loaded_apk->loaded_arsc_ =
      LoadedArsc::Load(loaded_apk->resources_asset_->getBuffer(true /*wordAligned*/),
                       loaded_apk->resources_asset_->getLength(), system, load_as_shared_library);
  if (loaded_apk->loaded_arsc_ == nullptr) {
    return {};
  }

  // Need to force a move for mingw32.
  return std::move(loaded_apk);
}

std::unique_ptr<Asset> ApkAssets::Open(const std::string& path, Asset::AccessMode mode) const {
  ATRACE_CALL();
  CHECK(zip_handle_ != nullptr);

  ::ZipString name(path.c_str());
  ::ZipEntry entry;
  int32_t result = ::FindEntry(zip_handle_.get(), name, &entry);
  if (result != 0) {
    LOG(ERROR) << "No entry '" << path << "' found in APK '" << path_ << "'";
    return {};
  }

  if (entry.method == kCompressDeflated) {
    std::unique_ptr<FileMap> map = util::make_unique<FileMap>();
    if (!map->create(path_.c_str(), ::GetFileDescriptor(zip_handle_.get()), entry.offset,
                     entry.compressed_length, true /*readOnly*/)) {
      LOG(ERROR) << "Failed to mmap file '" << path << "' in APK '" << path_ << "'";
      return {};
    }

    std::unique_ptr<Asset> asset =
        Asset::createFromCompressedMap(std::move(map), entry.uncompressed_length, mode);
    if (asset == nullptr) {
      LOG(ERROR) << "Failed to decompress '" << path << "'.";
      return {};
    }
    return asset;
  } else {
    std::unique_ptr<FileMap> map = util::make_unique<FileMap>();
    if (!map->create(path_.c_str(), ::GetFileDescriptor(zip_handle_.get()), entry.offset,
                     entry.uncompressed_length, true /*readOnly*/)) {
      LOG(ERROR) << "Failed to mmap file '" << path << "' in APK '" << path_ << "'";
      return {};
    }

    std::unique_ptr<Asset> asset = Asset::createFromUncompressedMap(std::move(map), mode);
    if (asset == nullptr) {
      LOG(ERROR) << "Failed to mmap file '" << path << "' in APK '" << path_ << "'";
      return {};
    }
    return asset;
  }
}

bool ApkAssets::ForEachFile(const std::string& root_path,
                            const std::function<void(const StringPiece&, FileType)>& f) const {
  CHECK(zip_handle_ != nullptr);

  std::string root_path_full = root_path;
  if (root_path_full.back() != '/') {
    root_path_full += '/';
  }

  ::ZipString prefix(root_path_full.c_str());
  void* cookie;
  if (::StartIteration(zip_handle_.get(), &cookie, &prefix, nullptr) != 0) {
    return false;
  }

  ::ZipString name;
  ::ZipEntry entry;

  // We need to hold back directories because many paths will contain them and we want to only
  // surface one.
  std::set<std::string> dirs;

  int32_t result;
  while ((result = ::Next(cookie, &entry, &name)) == 0) {
    StringPiece full_file_path(reinterpret_cast<const char*>(name.name), name.name_length);
    StringPiece leaf_file_path = full_file_path.substr(root_path_full.size());
    auto iter = std::find(leaf_file_path.begin(), leaf_file_path.end(), '/');
    if (iter != leaf_file_path.end()) {
      dirs.insert(
          leaf_file_path.substr(0, std::distance(leaf_file_path.begin(), iter)).to_string());
    } else if (!leaf_file_path.empty()) {
      f(leaf_file_path, kFileTypeRegular);
    }
  }
  ::EndIteration(cookie);

  // Now present the unique directories.
  for (const std::string& dir : dirs) {
    f(dir, kFileTypeDirectory);
  }

  // -1 is end of iteration, anything else is an error.
  return result == -1;
}

}  // namespace android
