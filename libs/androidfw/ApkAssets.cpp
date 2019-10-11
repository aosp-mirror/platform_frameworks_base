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

#include "androidfw/ApkAssets.h"

#include <algorithm>

#include "android-base/errors.h"
#include "android-base/file.h"
#include "android-base/logging.h"
#include "android-base/unique_fd.h"
#include "android-base/utf8.h"
#include "utils/Compat.h"
#include "utils/FileMap.h"
#include "ziparchive/zip_archive.h"

#include "androidfw/Asset.h"
#include "androidfw/Idmap.h"
#include "androidfw/misc.h"
#include "androidfw/ResourceTypes.h"
#include "androidfw/Util.h"

namespace android {

using base::SystemErrorCodeToString;
using base::unique_fd;

static const std::string kResourcesArsc("resources.arsc");

ApkAssets::ApkAssets(ZipArchiveHandle unmanaged_handle,
                     const std::string& path,
                     time_t last_mod_time)
    : zip_handle_(unmanaged_handle, ::CloseArchive), path_(path), last_mod_time_(last_mod_time) {
}

std::unique_ptr<const ApkAssets> ApkAssets::Load(const std::string& path, bool system) {
  return LoadImpl({} /*fd*/, path, nullptr, nullptr, system, false /*load_as_shared_library*/);
}

std::unique_ptr<const ApkAssets> ApkAssets::LoadAsSharedLibrary(const std::string& path,
                                                                bool system) {
  return LoadImpl({} /*fd*/, path, nullptr, nullptr, system, true /*load_as_shared_library*/);
}

std::unique_ptr<const ApkAssets> ApkAssets::LoadOverlay(const std::string& idmap_path,
                                                        bool system) {
  std::unique_ptr<Asset> idmap_asset = CreateAssetFromFile(idmap_path);
  if (idmap_asset == nullptr) {
    return {};
  }

  const StringPiece idmap_data(
      reinterpret_cast<const char*>(idmap_asset->getBuffer(true /*wordAligned*/)),
      static_cast<size_t>(idmap_asset->getLength()));
  std::unique_ptr<const LoadedIdmap> loaded_idmap = LoadedIdmap::Load(idmap_data);
  if (loaded_idmap == nullptr) {
    LOG(ERROR) << "failed to load IDMAP " << idmap_path;
    return {};
  }
  return LoadImpl({} /*fd*/, loaded_idmap->OverlayApkPath(), std::move(idmap_asset),
                  std::move(loaded_idmap), system, false /*load_as_shared_library*/);
}

std::unique_ptr<const ApkAssets> ApkAssets::LoadFromFd(unique_fd fd,
                                                       const std::string& friendly_name,
                                                       bool system, bool force_shared_lib) {
  return LoadImpl(std::move(fd), friendly_name, nullptr /*idmap_asset*/, nullptr /*loaded_idmap*/,
                  system, force_shared_lib);
}

std::unique_ptr<Asset> ApkAssets::CreateAssetFromFile(const std::string& path) {
  unique_fd fd(base::utf8::open(path.c_str(), O_RDONLY | O_BINARY | O_CLOEXEC));
  if (fd == -1) {
    LOG(ERROR) << "Failed to open file '" << path << "': " << SystemErrorCodeToString(errno);
    return {};
  }

  const off64_t file_len = lseek64(fd, 0, SEEK_END);
  if (file_len < 0) {
    LOG(ERROR) << "Failed to get size of file '" << path << "': " << SystemErrorCodeToString(errno);
    return {};
  }

  std::unique_ptr<FileMap> file_map = util::make_unique<FileMap>();
  if (!file_map->create(path.c_str(), fd, 0, static_cast<size_t>(file_len), true /*readOnly*/)) {
    LOG(ERROR) << "Failed to mmap file '" << path << "': " << SystemErrorCodeToString(errno);
    return {};
  }
  return Asset::createFromUncompressedMap(std::move(file_map), Asset::AccessMode::ACCESS_RANDOM);
}

std::unique_ptr<const ApkAssets> ApkAssets::LoadImpl(
    unique_fd fd, const std::string& path, std::unique_ptr<Asset> idmap_asset,
    std::unique_ptr<const LoadedIdmap> loaded_idmap, bool system, bool load_as_shared_library) {
  ::ZipArchiveHandle unmanaged_handle;
  int32_t result;
  if (fd >= 0) {
    result =
        ::OpenArchiveFd(fd.release(), path.c_str(), &unmanaged_handle, true /*assume_ownership*/);
  } else {
    result = ::OpenArchive(path.c_str(), &unmanaged_handle);
  }

  if (result != 0) {
    LOG(ERROR) << "Failed to open APK '" << path << "' " << ::ErrorCodeString(result);
    return {};
  }

  time_t last_mod_time = getFileModDate(path.c_str());

  // Wrap the handle in a unique_ptr so it gets automatically closed.
  std::unique_ptr<ApkAssets> loaded_apk(new ApkAssets(unmanaged_handle, path, last_mod_time));

  // Find the resource table.
  ::ZipEntry entry;
  result = ::FindEntry(loaded_apk->zip_handle_.get(), kResourcesArsc, &entry);
  if (result != 0) {
    // There is no resources.arsc, so create an empty LoadedArsc and return.
    loaded_apk->loaded_arsc_ = LoadedArsc::CreateEmpty();
    return std::move(loaded_apk);
  }

  if (entry.method == kCompressDeflated) {
    LOG(WARNING) << kResourcesArsc << " in APK '" << path << "' is compressed.";
  }

  // Open the resource table via mmap unless it is compressed. This logic is taken care of by Open.
  loaded_apk->resources_asset_ = loaded_apk->Open(kResourcesArsc, Asset::AccessMode::ACCESS_BUFFER);
  if (loaded_apk->resources_asset_ == nullptr) {
    LOG(ERROR) << "Failed to open '" << kResourcesArsc << "' in APK '" << path << "'.";
    return {};
  }

  // Must retain ownership of the IDMAP Asset so that all pointers to its mmapped data remain valid.
  loaded_apk->idmap_asset_ = std::move(idmap_asset);

  const StringPiece data(
      reinterpret_cast<const char*>(loaded_apk->resources_asset_->getBuffer(true /*wordAligned*/)),
      loaded_apk->resources_asset_->getLength());
  loaded_apk->loaded_arsc_ =
      LoadedArsc::Load(data, loaded_idmap.get(), system, load_as_shared_library);
  if (loaded_apk->loaded_arsc_ == nullptr) {
    LOG(ERROR) << "Failed to load '" << kResourcesArsc << "' in APK '" << path << "'.";
    return {};
  }

  // Need to force a move for mingw32.
  return std::move(loaded_apk);
}

std::unique_ptr<Asset> ApkAssets::Open(const std::string& path, Asset::AccessMode mode) const {
  CHECK(zip_handle_ != nullptr);

  ::ZipEntry entry;
  int32_t result = ::FindEntry(zip_handle_.get(), path, &entry);
  if (result != 0) {
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

  void* cookie;
  if (::StartIteration(zip_handle_.get(), &cookie, root_path_full, "") != 0) {
    return false;
  }

  std::string name;
  ::ZipEntry entry;

  // We need to hold back directories because many paths will contain them and we want to only
  // surface one.
  std::set<std::string> dirs;

  int32_t result;
  while ((result = ::Next(cookie, &entry, &name)) == 0) {
    StringPiece full_file_path(name);
    StringPiece leaf_file_path = full_file_path.substr(root_path_full.size());

    if (!leaf_file_path.empty()) {
      auto iter = std::find(leaf_file_path.begin(), leaf_file_path.end(), '/');
      if (iter != leaf_file_path.end()) {
        std::string dir =
            leaf_file_path.substr(0, std::distance(leaf_file_path.begin(), iter)).to_string();
        dirs.insert(std::move(dir));
      } else {
        f(leaf_file_path, kFileTypeRegular);
      }
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

bool ApkAssets::IsUpToDate() const {
  return last_mod_time_ == getFileModDate(path_.c_str());
}

}  // namespace android
