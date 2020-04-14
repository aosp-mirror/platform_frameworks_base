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

#ifndef APKASSETS_H_
#define APKASSETS_H_

#include <memory>
#include <string>

#include "android-base/macros.h"
#include "android-base/unique_fd.h"

#include "androidfw/Asset.h"
#include "androidfw/Idmap.h"
#include "androidfw/LoadedArsc.h"
#include "androidfw/misc.h"

struct ZipArchive;
typedef ZipArchive* ZipArchiveHandle;

namespace android {

class LoadedIdmap;

// Interface for retrieving assets provided by an ApkAssets.
class AssetsProvider {
 public:
  virtual ~AssetsProvider() = default;

  // Opens a file for reading.
  std::unique_ptr<Asset> Open(const std::string& path,
                              Asset::AccessMode mode = Asset::AccessMode::ACCESS_RANDOM,
                              bool* file_exists = nullptr) const {
    return OpenInternal(path, mode, file_exists);
  }

  // Iterate over all files and directories provided by the zip. The order of iteration is stable.
  virtual bool ForEachFile(const std::string& /* path */,
                           const std::function<void(const StringPiece&, FileType)>& /* f */) const {
    return true;
  }

 protected:
  AssetsProvider() = default;

  virtual std::unique_ptr<Asset> OpenInternal(const std::string& path,
                                              Asset::AccessMode mode,
                                              bool* file_exists) const = 0;

 private:
  DISALLOW_COPY_AND_ASSIGN(AssetsProvider);
};

class ZipAssetsProvider;

// Holds an APK.
class ApkAssets {
 public:
  // This means the data extends to the end of the file.
  static constexpr off64_t kUnknownLength = -1;

  // Creates an ApkAssets.
  // If `system` is true, the package is marked as a system package, and allows some functions to
  // filter out this package when computing what configurations/resources are available.
  static std::unique_ptr<const ApkAssets> Load(
      const std::string& path, package_property_t flags = 0U,
      std::unique_ptr<const AssetsProvider> override_asset = nullptr);

  // Creates an ApkAssets from the given file descriptor, and takes ownership of the file
  // descriptor. The `friendly_name` is some name that will be used to identify the source of
  // this ApkAssets in log messages and other debug scenarios.
  // If `length` equals kUnknownLength, offset must equal 0; otherwise, the apk data will be read
  // using the `offset` into the file descriptor and will be `length` bytes long.
  static std::unique_ptr<const ApkAssets> LoadFromFd(
      base::unique_fd fd, const std::string& friendly_name, package_property_t flags = 0U,
      std::unique_ptr<const AssetsProvider> override_asset = nullptr, off64_t offset = 0,
      off64_t length = kUnknownLength);

  // Creates an ApkAssets from the given path which points to a resources.arsc.
  static std::unique_ptr<const ApkAssets> LoadTable(
      const std::string& path, package_property_t flags = 0U,
      std::unique_ptr<const AssetsProvider> override_asset = nullptr);

  // Creates an ApkAssets from the given file descriptor which points to an resources.arsc, and
  // takes ownership of the file descriptor.
  // If `length` equals kUnknownLength, offset must equal 0; otherwise, the .arsc data will be read
  // using the `offset` into the file descriptor and will be `length` bytes long.
  static std::unique_ptr<const ApkAssets> LoadTableFromFd(
      base::unique_fd fd, const std::string& friendly_name, package_property_t flags = 0U,
      std::unique_ptr<const AssetsProvider> override_asset = nullptr, off64_t offset = 0,
      off64_t length = kUnknownLength);

  // Creates an ApkAssets from an IDMAP, which contains the original APK path, and the overlay
  // data.
  static std::unique_ptr<const ApkAssets> LoadOverlay(const std::string& idmap_path,
                                                      package_property_t flags = 0U);

  // Creates an ApkAssets from the directory path. File-based resources are read within the
  // directory as if the directory is an APK.
  static std::unique_ptr<const ApkAssets> LoadFromDir(
      const std::string& path, package_property_t flags = 0U,
      std::unique_ptr<const AssetsProvider> override_asset = nullptr);

  // Creates a totally empty ApkAssets with no resources table and no file entries.
  static std::unique_ptr<const ApkAssets> LoadEmpty(
      package_property_t flags = 0U,
      std::unique_ptr<const AssetsProvider> override_asset = nullptr);

  const std::string& GetPath() const {
    return path_;
  }

  const AssetsProvider* GetAssetsProvider() const {
    return assets_provider_.get();
  }

  // This is never nullptr.
  const LoadedArsc* GetLoadedArsc() const {
    return loaded_arsc_.get();
  }

  const LoadedIdmap* GetLoadedIdmap() const {
    return loaded_idmap_.get();
  }

  bool IsLoader() const {
    return (property_flags_ & PROPERTY_LOADER) != 0;
  }

  bool IsOverlay() const {
    return loaded_idmap_ != nullptr;
  }

  // Returns whether the resources.arsc is allocated in RAM (not mmapped).
  bool IsTableAllocated() const {
    return resources_asset_ && resources_asset_->isAllocated();
  }

  bool IsUpToDate() const;

  // Creates an Asset from a file on disk.
  static std::unique_ptr<Asset> CreateAssetFromFile(const std::string& path);

  // Creates an Asset from a file descriptor.
  //
  // The asset takes ownership of the file descriptor. If `length` equals kUnknownLength, offset
  // must equal 0; otherwise, the asset data will be read using the `offset` into the file
  // descriptor and will be `length` bytes long.
  static std::unique_ptr<Asset> CreateAssetFromFd(base::unique_fd fd,
                                                  const char* path,
                                                  off64_t offset = 0,
                                                  off64_t length = kUnknownLength);
 private:
  DISALLOW_COPY_AND_ASSIGN(ApkAssets);

  static std::unique_ptr<const ApkAssets> LoadImpl(
      std::unique_ptr<const AssetsProvider> assets, const std::string& path,
      package_property_t property_flags,
      std::unique_ptr<const AssetsProvider> override_assets = nullptr,
      std::unique_ptr<Asset> idmap_asset = nullptr,
      std::unique_ptr<const LoadedIdmap> idmap  = nullptr);

  static std::unique_ptr<const ApkAssets> LoadTableImpl(
      std::unique_ptr<Asset> resources_asset, const std::string& path,
      package_property_t property_flags,
      std::unique_ptr<const AssetsProvider> override_assets = nullptr);

  ApkAssets(std::unique_ptr<const AssetsProvider> assets_provider,
            std::string path,
            time_t last_mod_time,
            package_property_t property_flags);

  std::unique_ptr<const AssetsProvider> assets_provider_;
  const std::string path_;
  time_t last_mod_time_;
  package_property_t property_flags_ = 0U;
  std::unique_ptr<Asset> resources_asset_;
  std::unique_ptr<Asset> idmap_asset_;
  std::unique_ptr<const LoadedArsc> loaded_arsc_;
  std::unique_ptr<const LoadedIdmap> loaded_idmap_;
};

}  // namespace android

#endif /* APKASSETS_H_ */
