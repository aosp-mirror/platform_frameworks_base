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
                     std::string path,
                     time_t last_mod_time,
                     package_property_t property_flags)
    : zip_handle_(unmanaged_handle, ::CloseArchive),
      path_(std::move(path)),
      last_mod_time_(last_mod_time),
      property_flags_(property_flags) {
}

std::unique_ptr<const ApkAssets> ApkAssets::Load(const std::string& path,
                                                 const package_property_t flags) {
  ::ZipArchiveHandle unmanaged_handle;
  const int32_t result = ::OpenArchive(path.c_str(), &unmanaged_handle);
  if (result != 0) {
    LOG(ERROR) << "Failed to open APK '" << path << "' " << ::ErrorCodeString(result);
    ::CloseArchive(unmanaged_handle);
    return {};
  }

  return LoadImpl(unmanaged_handle,  path, nullptr /*idmap_asset*/, nullptr /*loaded_idmap*/,
                  flags);
}

std::unique_ptr<const ApkAssets> ApkAssets::LoadFromFd(unique_fd fd,
                                                       const std::string& friendly_name,
                                                       const package_property_t flags,
                                                       const off64_t offset,
                                                       const off64_t length) {
  CHECK(length >= kUnknownLength) << "length must be greater than or equal to " << kUnknownLength;
  CHECK(length != kUnknownLength || offset == 0) << "offset must be 0 if length is "
                                                 << kUnknownLength;

  ::ZipArchiveHandle unmanaged_handle;
  const int32_t result = (length == kUnknownLength)
      ? ::OpenArchiveFd(fd.release(), friendly_name.c_str(), &unmanaged_handle)
      : ::OpenArchiveFdRange(fd.release(), friendly_name.c_str(), &unmanaged_handle, length,
                             offset);

  if (result != 0) {
    LOG(ERROR) << "Failed to open APK '" << friendly_name << "' through FD with offset " << offset
               << " and length " << length << ": " << ::ErrorCodeString(result);
    ::CloseArchive(unmanaged_handle);
    return {};
  }

  return LoadImpl(unmanaged_handle, friendly_name, nullptr /*idmap_asset*/,
                  nullptr /*loaded_idmap*/, flags);
}

std::unique_ptr<const ApkAssets> ApkAssets::LoadTable(const std::string& path,
                                                      const package_property_t flags) {
  auto resources_asset = CreateAssetFromFile(path);
  if (!resources_asset) {
    LOG(ERROR) << "Failed to open ARSC '" << path;
    return {};
  }

  return LoadTableImpl(std::move(resources_asset), path, flags);
}

std::unique_ptr<const ApkAssets> ApkAssets::LoadTableFromFd(unique_fd fd,
                                                            const std::string& friendly_name,
                                                            const package_property_t flags,
                                                            const off64_t offset,
                                                            const off64_t length) {
  auto resources_asset = CreateAssetFromFd(std::move(fd), nullptr /* path */, offset, length);
  if (!resources_asset) {
    LOG(ERROR) << "Failed to open ARSC '" << friendly_name << "' through FD with offset " << offset
               << " and length " << length;
    return {};
  }

  return LoadTableImpl(std::move(resources_asset), friendly_name, flags);
}

std::unique_ptr<const ApkAssets> ApkAssets::LoadOverlay(const std::string& idmap_path,
                                                        const package_property_t flags) {
  CHECK((flags & PROPERTY_LOADER) == 0U) << "Cannot load RROs through loaders";

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


  ::ZipArchiveHandle unmanaged_handle;
  auto overlay_path = loaded_idmap->OverlayApkPath();
  const int32_t result = ::OpenArchive(overlay_path.c_str(), &unmanaged_handle);
  if (result != 0) {
    LOG(ERROR) << "Failed to open overlay APK '" << overlay_path << "' "
               << ::ErrorCodeString(result);
    ::CloseArchive(unmanaged_handle);
    return {};
  }

  return LoadImpl(unmanaged_handle, overlay_path, std::move(idmap_asset), std::move(loaded_idmap),
                  flags | PROPERTY_OVERLAY);
}

std::unique_ptr<const ApkAssets> ApkAssets::LoadEmpty(const package_property_t flags) {
  std::unique_ptr<ApkAssets> loaded_apk(new ApkAssets(nullptr, "empty", -1, flags));
  loaded_apk->loaded_arsc_ = LoadedArsc::CreateEmpty();
  // Need to force a move for mingw32.
  return std::move(loaded_apk);
}

std::unique_ptr<Asset> ApkAssets::CreateAssetFromFile(const std::string& path) {
  unique_fd fd(base::utf8::open(path.c_str(), O_RDONLY | O_BINARY | O_CLOEXEC));
  if (!fd.ok()) {
    LOG(ERROR) << "Failed to open file '" << path << "': " << SystemErrorCodeToString(errno);
    return {};
  }

  return CreateAssetFromFd(std::move(fd), path.c_str());
}

std::unique_ptr<Asset> ApkAssets::CreateAssetFromFd(base::unique_fd fd,
                                                    const char* path,
                                                    off64_t offset,
                                                    off64_t length) {
  CHECK(length >= kUnknownLength) << "length must be greater than or equal to " << kUnknownLength;
  CHECK(length != kUnknownLength || offset == 0) << "offset must be 0 if length is "
                                                 << kUnknownLength;
  if (length == kUnknownLength) {
    length = lseek64(fd, 0, SEEK_END);
    if (length < 0) {
      LOG(ERROR) << "Failed to get size of file '" << ((path) ? path : "anon") << "': "
                 << SystemErrorCodeToString(errno);
      return {};
    }
  }

  std::unique_ptr<FileMap> file_map = util::make_unique<FileMap>();
  if (!file_map->create(path, fd, offset, static_cast<size_t>(length), true /*readOnly*/)) {
    LOG(ERROR) << "Failed to mmap file '" << ((path) ? path : "anon") << "': "
               << SystemErrorCodeToString(errno);
    return {};
  }

  // If `path` is set, do not pass ownership of the `fd` to the new Asset since
  // Asset::openFileDescriptor can use `path` to create new file descriptors.
  return Asset::createFromUncompressedMap(std::move(file_map),
                                          (path) ? base::unique_fd(-1) : std::move(fd),
                                          Asset::AccessMode::ACCESS_RANDOM);
}

std::unique_ptr<const ApkAssets> ApkAssets::LoadImpl(ZipArchiveHandle unmanaged_handle,
                                                     const std::string& path,
                                                     std::unique_ptr<Asset> idmap_asset,
                                                     std::unique_ptr<const LoadedIdmap> idmap,
                                                     package_property_t property_flags) {
  const time_t last_mod_time = getFileModDate(path.c_str());

  // Wrap the handle in a unique_ptr so it gets automatically closed.
  std::unique_ptr<ApkAssets>
      loaded_apk(new ApkAssets(unmanaged_handle, path, last_mod_time, property_flags));

  // Find the resource table.
  ::ZipEntry entry;
  int32_t result = ::FindEntry(loaded_apk->zip_handle_.get(), kResourcesArsc, &entry);
  if (result != 0) {
    // There is no resources.arsc, so create an empty LoadedArsc and return.
    loaded_apk->loaded_arsc_ = LoadedArsc::CreateEmpty();
    return std::move(loaded_apk);
  }

  if (entry.method == kCompressDeflated) {
    ANDROID_LOG(WARNING) << kResourcesArsc << " in APK '" << path << "' is compressed.";
  }

  // Open the resource table via mmap unless it is compressed. This logic is taken care of by Open.
  loaded_apk->resources_asset_ = loaded_apk->Open(kResourcesArsc, Asset::AccessMode::ACCESS_BUFFER);
  if (loaded_apk->resources_asset_ == nullptr) {
    LOG(ERROR) << "Failed to open '" << kResourcesArsc << "' in APK '" << path << "'.";
    return {};
  }

  // Must retain ownership of the IDMAP Asset so that all pointers to its mmapped data remain valid.
  loaded_apk->idmap_asset_ = std::move(idmap_asset);
  loaded_apk->loaded_idmap_ = std::move(idmap);

  const StringPiece data(
      reinterpret_cast<const char*>(loaded_apk->resources_asset_->getBuffer(true /*wordAligned*/)),
      loaded_apk->resources_asset_->getLength());
  loaded_apk->loaded_arsc_ = LoadedArsc::Load(data, loaded_apk->loaded_idmap_.get(),
                                              property_flags);
  if (loaded_apk->loaded_arsc_ == nullptr) {
    LOG(ERROR) << "Failed to load '" << kResourcesArsc << "' in APK '" << path << "'.";
    return {};
  }

  // Need to force a move for mingw32.
  return std::move(loaded_apk);
}

std::unique_ptr<const ApkAssets> ApkAssets::LoadTableImpl(std::unique_ptr<Asset> resources_asset,
                                                          const std::string& path,
                                                          package_property_t property_flags) {
  const time_t last_mod_time = getFileModDate(path.c_str());

  std::unique_ptr<ApkAssets> loaded_apk(
      new ApkAssets(nullptr, path, last_mod_time, property_flags));
  loaded_apk->resources_asset_ = std::move(resources_asset);

  const StringPiece data(
      reinterpret_cast<const char*>(loaded_apk->resources_asset_->getBuffer(true /*wordAligned*/)),
      loaded_apk->resources_asset_->getLength());
  loaded_apk->loaded_arsc_ = LoadedArsc::Load(data, nullptr, property_flags);
  if (loaded_apk->loaded_arsc_ == nullptr) {
    LOG(ERROR) << "Failed to load '" << kResourcesArsc << path;
    return {};
  }

  // Need to force a move for mingw32.
  return std::move(loaded_apk);
}

std::unique_ptr<Asset> ApkAssets::Open(const std::string& path, Asset::AccessMode mode) const {
  // If this is a resource loader from an .arsc, there will be no zip handle
  if (zip_handle_ == nullptr) {
    return {};
  }

  ::ZipEntry entry;
  int32_t result = ::FindEntry(zip_handle_.get(), path, &entry);
  if (result != 0) {
    return {};
  }

  const int fd = ::GetFileDescriptor(zip_handle_.get());
  const off64_t fd_offset = ::GetFileDescriptorOffset(zip_handle_.get());
  if (entry.method == kCompressDeflated) {
    std::unique_ptr<FileMap> map = util::make_unique<FileMap>();
    if (!map->create(path_.c_str(), fd, fd_offset + entry.offset, entry.compressed_length,
                     true /*readOnly*/)) {
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
    if (!map->create(path_.c_str(), fd, fd_offset + entry.offset, entry.uncompressed_length,
                     true /*readOnly*/)) {
      LOG(ERROR) << "Failed to mmap file '" << path << "' in APK '" << path_ << "'";
      return {};
    }

    // TODO: apks created from file descriptors residing in RAM currently cannot open file
    //  descriptors to the assets they contain. This is because the Asset::openFileDeescriptor uses
    //  the zip path on disk to create a new file descriptor. This is fixed in a future change
    //  in the change topic.
    std::unique_ptr<Asset> asset = Asset::createFromUncompressedMap(std::move(map),
        unique_fd(-1) /* fd*/, mode);
    if (asset == nullptr) {
      LOG(ERROR) << "Failed to mmap file '" << path << "' in APK '" << path_ << "'";
      return {};
    }
    return asset;
  }
}

bool ApkAssets::ForEachFile(const std::string& root_path,
                            const std::function<void(const StringPiece&, FileType)>& f) const {
  // If this is a resource loader from an .arsc, there will be no zip handle
  if (zip_handle_ == nullptr) {
    return false;
  }

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
  if (IsLoader()) {
    // Loaders are invalidated by the app, not the system, so assume up to date.
    return true;
  }

  return last_mod_time_ == getFileModDate(path_.c_str());
}

}  // namespace android
