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

#include "android-base/errors.h"
#include "android-base/logging.h"
#include "android-base/utf8.h"

namespace android {

using base::SystemErrorCodeToString;
using base::unique_fd;

constexpr const char* kResourcesArsc = "resources.arsc";

ApkAssets::ApkAssets(PrivateConstructorUtil, std::unique_ptr<Asset> resources_asset,
                     std::unique_ptr<LoadedArsc> loaded_arsc,
                     std::unique_ptr<AssetsProvider> assets, package_property_t property_flags,
                     std::unique_ptr<Asset> idmap_asset, std::unique_ptr<LoadedIdmap> loaded_idmap)
    : resources_asset_(std::move(resources_asset)),
      loaded_arsc_(std::move(loaded_arsc)),
      assets_provider_(std::move(assets)),
      property_flags_(property_flags),
      idmap_asset_(std::move(idmap_asset)),
      loaded_idmap_(std::move(loaded_idmap)) {
}

ApkAssetsPtr ApkAssets::Load(const std::string& path, package_property_t flags) {
  return Load(ZipAssetsProvider::Create(path, flags), flags);
}

ApkAssetsPtr ApkAssets::LoadFromFd(base::unique_fd fd, const std::string& debug_name,
                                   package_property_t flags, off64_t offset, off64_t len) {
  return Load(ZipAssetsProvider::Create(std::move(fd), debug_name, offset, len), flags);
}

ApkAssetsPtr ApkAssets::Load(std::unique_ptr<AssetsProvider> assets, package_property_t flags) {
  return LoadImpl(std::move(assets), flags, nullptr /* idmap_asset */, nullptr /* loaded_idmap */);
}

ApkAssetsPtr ApkAssets::LoadTable(std::unique_ptr<Asset> resources_asset,
                                  std::unique_ptr<AssetsProvider> assets,
                                  package_property_t flags) {
  if (resources_asset == nullptr) {
    return {};
  }
  return LoadImpl(std::move(resources_asset), std::move(assets), flags, nullptr /* idmap_asset */,
                  nullptr /* loaded_idmap */);
}

ApkAssetsPtr ApkAssets::LoadOverlay(const std::string& idmap_path, package_property_t flags) {
  CHECK((flags & PROPERTY_LOADER) == 0U) << "Cannot load RROs through loaders";
  auto idmap_asset = AssetsProvider::CreateAssetFromFile(idmap_path);
  if (idmap_asset == nullptr) {
    LOG(ERROR) << "failed to read IDMAP " << idmap_path;
    return {};
  }

  StringPiece idmap_data(reinterpret_cast<const char*>(idmap_asset->getBuffer(true /* aligned */)),
                         static_cast<size_t>(idmap_asset->getLength()));
  auto loaded_idmap = LoadedIdmap::Load(idmap_path, idmap_data);
  if (loaded_idmap == nullptr) {
    LOG(ERROR) << "failed to load IDMAP " << idmap_path;
    return {};
  }

  std::string overlay_path(loaded_idmap->OverlayApkPath());
  auto fd = unique_fd(base::utf8::open(overlay_path.c_str(), O_RDONLY | O_CLOEXEC));
  std::unique_ptr<AssetsProvider> overlay_assets;
  if (IsFabricatedOverlayName(overlay_path) && IsFabricatedOverlay(fd)) {
    // Fabricated overlays do not contain resource definitions. All of the overlay resource values
    // are defined inline in the idmap.
    overlay_assets = EmptyAssetsProvider::Create(std::move(overlay_path));
  } else {
    // The overlay should be an APK.
    overlay_assets = ZipAssetsProvider::Create(std::move(overlay_path), flags, std::move(fd));
  }
  if (overlay_assets == nullptr) {
    return {};
  }

  return LoadImpl(std::move(overlay_assets), flags | PROPERTY_OVERLAY, std::move(idmap_asset),
                  std::move(loaded_idmap));
}

ApkAssetsPtr ApkAssets::LoadImpl(std::unique_ptr<AssetsProvider> assets,
                                 package_property_t property_flags,
                                 std::unique_ptr<Asset> idmap_asset,
                                 std::unique_ptr<LoadedIdmap> loaded_idmap) {
  if (assets == nullptr) {
    return {};
  }

  // Open the resource table via mmap unless it is compressed. This logic is taken care of by Open.
  bool resources_asset_exists = false;
  auto resources_asset = assets->Open(kResourcesArsc, Asset::AccessMode::ACCESS_BUFFER,
                                      &resources_asset_exists);
  if (resources_asset == nullptr && resources_asset_exists) {
    LOG(ERROR) << "Failed to open '" << kResourcesArsc << "' in APK '" << assets->GetDebugName()
               << "'.";
    return {};
  }

  return LoadImpl(std::move(resources_asset), std::move(assets), property_flags,
                  std::move(idmap_asset), std::move(loaded_idmap));
}

ApkAssetsPtr ApkAssets::LoadImpl(std::unique_ptr<Asset> resources_asset,
                                 std::unique_ptr<AssetsProvider> assets,
                                 package_property_t property_flags,
                                 std::unique_ptr<Asset> idmap_asset,
                                 std::unique_ptr<LoadedIdmap> loaded_idmap) {
  if (assets == nullptr ) {
    return {};
  }

  std::unique_ptr<LoadedArsc> loaded_arsc;
  if (resources_asset != nullptr) {
    const auto data = resources_asset->getIncFsBuffer(true /* aligned */);
    const size_t length = resources_asset->getLength();
    if (!data || length == 0) {
      LOG(ERROR) << "Failed to read resources table in APK '" << assets->GetDebugName() << "'.";
      return {};
    }
    loaded_arsc = LoadedArsc::Load(data, length, loaded_idmap.get(), property_flags);
  } else if (loaded_idmap != nullptr && IsFabricatedOverlay(loaded_idmap->OverlayApkPath())) {
    loaded_arsc = LoadedArsc::Load(loaded_idmap.get());
  } else {
    loaded_arsc = LoadedArsc::CreateEmpty();
  }

  if (loaded_arsc == nullptr) {
    LOG(ERROR) << "Failed to load resources table in APK '" << assets->GetDebugName() << "'.";
    return {};
  }

  return ApkAssetsPtr::make(PrivateConstructorUtil{}, std::move(resources_asset),
                            std::move(loaded_arsc), std::move(assets), property_flags,
                            std::move(idmap_asset), std::move(loaded_idmap));
}

std::optional<std::string_view> ApkAssets::GetPath() const {
  return assets_provider_->GetPath();
}

const std::string& ApkAssets::GetDebugName() const {
  return assets_provider_->GetDebugName();
}

bool ApkAssets::IsUpToDate() const {
  // Loaders are invalidated by the app, not the system, so assume they are up to date.
  return IsLoader() || ((!loaded_idmap_ || loaded_idmap_->IsUpToDate())
                        && assets_provider_->IsUpToDate());
}

}  // namespace android
