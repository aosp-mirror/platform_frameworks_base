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
#include "androidfw/AssetsProvider.h"
#include "androidfw/Idmap.h"
#include "androidfw/LoadedArsc.h"
#include "androidfw/misc.h"

namespace android {

// Holds an APK.
class ApkAssets {
 public:

  // Creates an ApkAssets from a path on device.
  static std::unique_ptr<ApkAssets> Load(const std::string& path,
                                         package_property_t flags = 0U);

  // Creates an ApkAssets from an open file descriptor.
  static std::unique_ptr<ApkAssets> LoadFromFd(base::unique_fd fd,
                                               const std::string& debug_name,
                                               package_property_t flags = 0U,
                                               off64_t offset = 0,
                                               off64_t len = AssetsProvider::kUnknownLength);

  // Creates an ApkAssets from an AssetProvider.
  // The ApkAssets will take care of destroying the AssetsProvider when it is destroyed.
  static std::unique_ptr<ApkAssets> Load(std::unique_ptr<AssetsProvider> assets,
                                         package_property_t flags = 0U);

  // Creates an ApkAssets from the given asset file representing a resources.arsc.
  static std::unique_ptr<ApkAssets> LoadTable(std::unique_ptr<Asset> resources_asset,
                                              std::unique_ptr<AssetsProvider> assets,
                                              package_property_t flags = 0U);

  // Creates an ApkAssets from an IDMAP, which contains the original APK path, and the overlay
  // data.
  static std::unique_ptr<ApkAssets> LoadOverlay(const std::string& idmap_path,
                                                package_property_t flags = 0U);

  // TODO(177101983): Remove all uses of GetPath for checking whether two ApkAssets are the same.
  //  With the introduction of ResourcesProviders, not all ApkAssets have paths. This could cause
  //  bugs when path is used for comparison because multiple ApkAssets could have the same "firendly
  //  name". Use pointer equality instead. ResourceManager caches and reuses ApkAssets so the
  //  same asset should have the same pointer.
  const std::string& GetPath() const;

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
    return resources_asset_ != nullptr && resources_asset_->isAllocated();
  }

  bool IsUpToDate() const;

 private:
  static std::unique_ptr<ApkAssets> LoadImpl(std::unique_ptr<AssetsProvider> assets,
                                             package_property_t property_flags,
                                             std::unique_ptr<Asset> idmap_asset,
                                             std::unique_ptr<LoadedIdmap> loaded_idmap);

  static std::unique_ptr<ApkAssets> LoadImpl(std::unique_ptr<Asset> resources_asset,
                                             std::unique_ptr<AssetsProvider> assets,
                                             package_property_t property_flags,
                                             std::unique_ptr<Asset> idmap_asset,
                                             std::unique_ptr<LoadedIdmap> loaded_idmap);

  ApkAssets(std::unique_ptr<Asset> resources_asset,
            std::unique_ptr<LoadedArsc> loaded_arsc,
            std::unique_ptr<AssetsProvider> assets,
            package_property_t property_flags,
            std::unique_ptr<Asset> idmap_asset,
            std::unique_ptr<LoadedIdmap> loaded_idmap);

  std::unique_ptr<Asset> resources_asset_;
  std::unique_ptr<LoadedArsc> loaded_arsc_;

  std::unique_ptr<AssetsProvider> assets_provider_;
  package_property_t property_flags_ = 0U;

  std::unique_ptr<Asset> idmap_asset_;
  std::unique_ptr<LoadedIdmap> loaded_idmap_;
};

} // namespace android

#endif // APKASSETS_H_