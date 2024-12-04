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

#include <utils/RefBase.h>

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

class ApkAssets;

using ApkAssetsPtr = sp<ApkAssets>;

// Holds an APK.
class ApkAssets : public RefBase {
 public:
  // Creates an ApkAssets from a path on device.
  static ApkAssetsPtr Load(const std::string& path, package_property_t flags = 0U);

  // Creates an ApkAssets from an open file descriptor.
  static ApkAssetsPtr LoadFromFd(base::unique_fd fd, const std::string& debug_name,
                                 package_property_t flags = 0U, off64_t offset = 0,
                                 off64_t len = AssetsProvider::kUnknownLength);

  //
  // Creates an ApkAssets from an AssetProvider.
  // The ApkAssets will take care of destroying the AssetsProvider when it is destroyed;
  // the original argument is not moved from if loading fails.
  //
  // Note: this function takes care of the case when you pass a move(unique_ptr<Derived>)
  //    that would create a temporary unique_ptr<AssetsProvider> by moving your pointer into
  //    it before the function call, making it impossible to not move from the parameter
  //    on loading failure. The two overloads take care of moving the pointer back if needed.
  //

  template <class T>
  static ApkAssetsPtr Load(std::unique_ptr<T>&& assets, package_property_t flags = 0U)
      requires(std::is_same_v<T, AssetsProvider>) {
    return LoadImpl(std::move(assets), flags);
  }

  template <class T>
  static ApkAssetsPtr Load(std::unique_ptr<T>&& assets, package_property_t flags = 0U)
      requires(!std::is_same_v<T, AssetsProvider> && std::is_base_of_v<AssetsProvider, T>) {
    std::unique_ptr<AssetsProvider> base_assets(std::move(assets));
    auto res = LoadImpl(std::move(base_assets), flags);
    if (!res) {
      assets.reset(static_cast<T*>(base_assets.release()));
    }
    return res;
  }

  // Creates an ApkAssets from the given asset file representing a resources.arsc.
  static ApkAssetsPtr LoadTable(std::unique_ptr<Asset>&& resources_asset,
                                std::unique_ptr<AssetsProvider>&& assets,
                                package_property_t flags = 0U);

  // Creates an ApkAssets from an IDMAP, which contains the original APK path, and the overlay
  // data.
  static ApkAssetsPtr LoadOverlay(const std::string& idmap_path, package_property_t flags = 0U);

  // Path to the contents of the ApkAssets on disk. The path could represent an APk, a directory,
  // or some other file type.
  std::optional<std::string_view> GetPath() const;

  const std::string& GetDebugName() const;

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

  // DANGER!
  // This is a destructive method that rips the assets provider out of ApkAssets object.
  // It is only useful when one knows this assets object can't be used anymore, and they
  // need the underlying assets provider back (e.g. when initialization fails for some
  // reason).
  std::unique_ptr<AssetsProvider> TakeAssetsProvider() && {
    return std::move(assets_provider_);
  }

 private:
  static ApkAssetsPtr LoadImpl(std::unique_ptr<AssetsProvider>&& assets,
                               package_property_t property_flags,
                               std::unique_ptr<Asset>&& idmap_asset,
                               std::unique_ptr<LoadedIdmap>&& loaded_idmap);

  static ApkAssetsPtr LoadImpl(std::unique_ptr<Asset>&& resources_asset,
                               std::unique_ptr<AssetsProvider>&& assets,
                               package_property_t property_flags,
                               std::unique_ptr<Asset>&& idmap_asset,
                               std::unique_ptr<LoadedIdmap>&& loaded_idmap);

  static ApkAssetsPtr LoadImpl(std::unique_ptr<AssetsProvider>&& assets,
                               package_property_t flags = 0U);

  // Allows us to make it possible to call make_shared from inside the class but still keeps the
  // ctor 'private' for all means and purposes.
  struct PrivateConstructorUtil {
    explicit PrivateConstructorUtil() = default;
  };

 public:
  ApkAssets(PrivateConstructorUtil, std::unique_ptr<Asset> resources_asset,
            std::unique_ptr<LoadedArsc> loaded_arsc, std::unique_ptr<AssetsProvider> assets,
            package_property_t property_flags, std::unique_ptr<Asset> idmap_asset,
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