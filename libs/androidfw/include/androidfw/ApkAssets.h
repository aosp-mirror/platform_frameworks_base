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
#include "androidfw/LoadedArsc.h"
#include "androidfw/misc.h"

struct ZipArchive;
typedef ZipArchive* ZipArchiveHandle;

namespace android {

class LoadedIdmap;

// Holds an APK.
class ApkAssets {
 public:
  // Creates an ApkAssets.
  // If `system` is true, the package is marked as a system package, and allows some functions to
  // filter out this package when computing what configurations/resources are available.
  static std::unique_ptr<const ApkAssets> Load(const std::string& path, bool system = false);

  // Creates an ApkAssets, but forces any package with ID 0x7f to be loaded as a shared library.
  // If `system` is true, the package is marked as a system package, and allows some functions to
  // filter out this package when computing what configurations/resources are available.
  static std::unique_ptr<const ApkAssets> LoadAsSharedLibrary(const std::string& path,
                                                              bool system = false);

  // Creates an ApkAssets from an IDMAP, which contains the original APK path, and the overlay
  // data.
  // If `system` is true, the package is marked as a system package, and allows some functions to
  // filter out this package when computing what configurations/resources are available.
  static std::unique_ptr<const ApkAssets> LoadOverlay(const std::string& idmap_path,
                                                      bool system = false);

  // Creates an ApkAssets from the given file descriptor, and takes ownership of the file
  // descriptor. The `friendly_name` is some name that will be used to identify the source of
  // this ApkAssets in log messages and other debug scenarios.
  // If `system` is true, the package is marked as a system package, and allows some functions to
  // filter out this package when computing what configurations/resources are available.
  // If `force_shared_lib` is true, any package with ID 0x7f is loaded as a shared library.
  static std::unique_ptr<const ApkAssets> LoadFromFd(base::unique_fd fd,
                                                     const std::string& friendly_name, bool system,
                                                     bool force_shared_lib);

  std::unique_ptr<Asset> Open(const std::string& path,
                              Asset::AccessMode mode = Asset::AccessMode::ACCESS_RANDOM) const;

  bool ForEachFile(const std::string& path,
                   const std::function<void(const StringPiece&, FileType)>& f) const;

  inline const std::string& GetPath() const {
    return path_;
  }

  // This is never nullptr.
  inline const LoadedArsc* GetLoadedArsc() const {
    return loaded_arsc_.get();
  }

  inline bool IsOverlay() const {
    return idmap_asset_.get() != nullptr;
  }

  bool IsUpToDate() const;

 private:
  DISALLOW_COPY_AND_ASSIGN(ApkAssets);

  static std::unique_ptr<const ApkAssets> LoadImpl(base::unique_fd fd, const std::string& path,
                                                   std::unique_ptr<Asset> idmap_asset,
                                                   std::unique_ptr<const LoadedIdmap> loaded_idmap,
                                                   bool system, bool load_as_shared_library);

  // Creates an Asset from any file on the file system.
  static std::unique_ptr<Asset> CreateAssetFromFile(const std::string& path);

  ApkAssets(ZipArchiveHandle unmanaged_handle, const std::string& path, time_t last_mod_time);

  using ZipArchivePtr = std::unique_ptr<ZipArchive, void(*)(ZipArchiveHandle)>;

  ZipArchivePtr zip_handle_;
  const std::string path_;
  time_t last_mod_time_;
  std::unique_ptr<Asset> resources_asset_;
  std::unique_ptr<Asset> idmap_asset_;
  std::unique_ptr<const LoadedArsc> loaded_arsc_;
};

}  // namespace android

#endif /* APKASSETS_H_ */
