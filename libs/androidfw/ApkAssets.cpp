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
#include "android-base/stringprintf.h"
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

ApkAssets::ApkAssets(std::unique_ptr<const AssetsProvider> assets_provider,
                     std::string path,
                     time_t last_mod_time,
                     package_property_t property_flags)
    : assets_provider_(std::move(assets_provider)),
      path_(std::move(path)),
      last_mod_time_(last_mod_time),
      property_flags_(property_flags) {
}

// Provides asset files from a zip file.
class ZipAssetsProvider : public AssetsProvider {
 public:
  ~ZipAssetsProvider() override = default;

  static std::unique_ptr<const AssetsProvider> Create(const std::string& path) {
    ::ZipArchiveHandle unmanaged_handle;
    const int32_t result = ::OpenArchive(path.c_str(), &unmanaged_handle);
    if (result != 0) {
      LOG(ERROR) << "Failed to open APK '" << path << "' " << ::ErrorCodeString(result);
      ::CloseArchive(unmanaged_handle);
      return {};
    }

    return std::unique_ptr<AssetsProvider>(new ZipAssetsProvider(path, path, unmanaged_handle));
  }

  static std::unique_ptr<const AssetsProvider> Create(
      unique_fd fd, const std::string& friendly_name, const off64_t offset = 0,
      const off64_t length = ApkAssets::kUnknownLength) {

    ::ZipArchiveHandle unmanaged_handle;
    const int32_t result = (length == ApkAssets::kUnknownLength)
        ? ::OpenArchiveFd(fd.release(), friendly_name.c_str(), &unmanaged_handle)
        : ::OpenArchiveFdRange(fd.release(), friendly_name.c_str(), &unmanaged_handle, length,
                               offset);

    if (result != 0) {
      LOG(ERROR) << "Failed to open APK '" << friendly_name << "' through FD with offset " << offset
                 << " and length " << length << ": " << ::ErrorCodeString(result);
      ::CloseArchive(unmanaged_handle);
      return {};
    }

    return std::unique_ptr<AssetsProvider>(new ZipAssetsProvider({}, friendly_name,
                                                                 unmanaged_handle));
  }

  // Iterate over all files and directories within the zip. The order of iteration is not
  // guaranteed to be the same as the order of elements in the central directory but is stable for a
  // given zip file.
  bool ForEachFile(const std::string& root_path,
                   const std::function<void(const StringPiece&, FileType)>& f) const override {
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
    ::ZipEntry entry{};

    // We need to hold back directories because many paths will contain them and we want to only
    // surface one.
    std::set<std::string> dirs{};

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

 protected:
  std::unique_ptr<Asset> OpenInternal(
      const std::string& path, Asset::AccessMode mode, bool* file_exists) const override {
    if (file_exists) {
      *file_exists = false;
    }

    ::ZipEntry entry;
    int32_t result = ::FindEntry(zip_handle_.get(), path, &entry);
    if (result != 0) {
      return {};
    }

    if (file_exists) {
      *file_exists = true;
    }

    const int fd = ::GetFileDescriptor(zip_handle_.get());
     const off64_t fd_offset = ::GetFileDescriptorOffset(zip_handle_.get());
    if (entry.method == kCompressDeflated) {
      std::unique_ptr<FileMap> map = util::make_unique<FileMap>();
      if (!map->create(GetPath(), fd, entry.offset + fd_offset, entry.compressed_length,
                       true /*readOnly*/)) {
        LOG(ERROR) << "Failed to mmap file '" << path << "' in APK '" << friendly_name_ << "'";
        return {};
      }

      std::unique_ptr<Asset> asset =
          Asset::createFromCompressedMap(std::move(map), entry.uncompressed_length, mode);
      if (asset == nullptr) {
        LOG(ERROR) << "Failed to decompress '" << path << "' in APK '" << friendly_name_ << "'";
        return {};
      }
      return asset;
    } else {
      std::unique_ptr<FileMap> map = util::make_unique<FileMap>();
      if (!map->create(GetPath(), fd, entry.offset + fd_offset, entry.uncompressed_length,
                       true /*readOnly*/)) {
        LOG(ERROR) << "Failed to mmap file '" << path << "' in APK '" << friendly_name_ << "'";
        return {};
      }

      unique_fd ufd;
      if (!GetPath()) {
        // If the `path` is not set, create a new `fd` for the new Asset to own in order to create
        // new file descriptors using Asset::openFileDescriptor. If the path is set, it will be used
        // to create new file descriptors.
        ufd = unique_fd(dup(fd));
        if (!ufd.ok()) {
          LOG(ERROR) << "Unable to dup fd '" << path << "' in APK '" << friendly_name_ << "'";
          return {};
        }
      }

      std::unique_ptr<Asset> asset = Asset::createFromUncompressedMap(std::move(map),
          std::move(ufd), mode);
      if (asset == nullptr) {
        LOG(ERROR) << "Failed to mmap file '" << path << "' in APK '" << friendly_name_ << "'";
        return {};
      }
      return asset;
    }
  }

 private:
  DISALLOW_COPY_AND_ASSIGN(ZipAssetsProvider);

  explicit ZipAssetsProvider(std::string path,
                             std::string friendly_name,
                             ZipArchiveHandle unmanaged_handle)
                             : zip_handle_(unmanaged_handle, ::CloseArchive),
                               path_(std::move(path)),
                               friendly_name_(std::move(friendly_name)) { }

  const char* GetPath() const {
    return path_.empty() ? nullptr : path_.c_str();
  }

  using ZipArchivePtr = std::unique_ptr<ZipArchive, void (*)(ZipArchiveHandle)>;
  ZipArchivePtr zip_handle_;
  std::string path_;
  std::string friendly_name_;
};

class DirectoryAssetsProvider : AssetsProvider {
 public:
  ~DirectoryAssetsProvider() override = default;

  static std::unique_ptr<const AssetsProvider> Create(const std::string& path) {
    struct stat sb{};
    const int result = stat(path.c_str(), &sb);
    if (result == -1) {
      LOG(ERROR) << "Failed to find directory '" << path << "'.";
      return nullptr;
    }

    if (!S_ISDIR(sb.st_mode)) {
      LOG(ERROR) << "Path '" << path << "' is not a directory.";
      return nullptr;
    }

    return std::unique_ptr<AssetsProvider>(new DirectoryAssetsProvider(path));
  }

 protected:
  std::unique_ptr<Asset> OpenInternal(
      const std::string& path, Asset::AccessMode /* mode */, bool* file_exists) const override {
    const std::string resolved_path = ResolvePath(path);
    if (file_exists) {
      struct stat sb{};
      const int result = stat(resolved_path.c_str(), &sb);
      *file_exists = result != -1 && S_ISREG(sb.st_mode);
    }

    return ApkAssets::CreateAssetFromFile(resolved_path);
  }

 private:
  DISALLOW_COPY_AND_ASSIGN(DirectoryAssetsProvider);

  explicit DirectoryAssetsProvider(std::string path) : path_(std::move(path)) { }

  inline std::string ResolvePath(const std::string& path) const {
    return base::StringPrintf("%s%c%s", path_.c_str(), OS_PATH_SEPARATOR, path.c_str());
  }

  const std::string path_;
};

// AssetProvider implementation that does not provide any assets. Used for ApkAssets::LoadEmpty.
class EmptyAssetsProvider : public AssetsProvider {
 public:
  EmptyAssetsProvider() = default;
  ~EmptyAssetsProvider() override = default;

 protected:
  std::unique_ptr<Asset> OpenInternal(const std::string& /*path */,
                                      Asset::AccessMode /* mode */,
                                      bool* file_exists) const override {
    if (file_exists) {
      *file_exists = false;
    }
    return nullptr;
  }

 private:
  DISALLOW_COPY_AND_ASSIGN(EmptyAssetsProvider);
};

// AssetProvider implementation
class MultiAssetsProvider : public AssetsProvider {
 public:
  ~MultiAssetsProvider() override = default;

  static std::unique_ptr<const AssetsProvider> Create(
      std::unique_ptr<const AssetsProvider> child, std::unique_ptr<const AssetsProvider> parent) {
    CHECK(parent != nullptr) << "parent provider must not be null";
    return (!child) ? std::move(parent)
                    : std::unique_ptr<const AssetsProvider>(new MultiAssetsProvider(
                        std::move(child), std::move(parent)));
  }

  bool ForEachFile(const std::string& root_path,
                   const std::function<void(const StringPiece&, FileType)>& f) const override {
    // TODO: Only call the function once for files defined in the parent and child
    return child_->ForEachFile(root_path, f) && parent_->ForEachFile(root_path, f);
  }

 protected:
  std::unique_ptr<Asset> OpenInternal(
      const std::string& path, Asset::AccessMode mode, bool* file_exists) const override {
    auto asset = child_->Open(path, mode, file_exists);
    return (asset) ? std::move(asset) : parent_->Open(path, mode, file_exists);
  }

 private:
  DISALLOW_COPY_AND_ASSIGN(MultiAssetsProvider);

  MultiAssetsProvider(std::unique_ptr<const AssetsProvider> child,
                      std::unique_ptr<const AssetsProvider> parent)
                      : child_(std::move(child)), parent_(std::move(parent)) { }

  std::unique_ptr<const AssetsProvider> child_;
  std::unique_ptr<const AssetsProvider> parent_;
};

// Opens the archive using the file path. Calling CloseArchive on the zip handle will close the
// file.
std::unique_ptr<const ApkAssets> ApkAssets::Load(
    const std::string& path, const package_property_t flags,
    std::unique_ptr<const AssetsProvider> override_asset) {
  auto assets = ZipAssetsProvider::Create(path);
  return (assets) ? LoadImpl(std::move(assets), path, flags, std::move(override_asset))
                  : nullptr;
}

// Opens the archive using the file file descriptor with the specified file offset and read length.
// If the `assume_ownership` parameter is 'true' calling CloseArchive will close the file.
std::unique_ptr<const ApkAssets> ApkAssets::LoadFromFd(
    unique_fd fd, const std::string& friendly_name, const package_property_t flags,
    std::unique_ptr<const AssetsProvider> override_asset, const off64_t offset,
    const off64_t length) {
  CHECK(length >= kUnknownLength) << "length must be greater than or equal to " << kUnknownLength;
  CHECK(length != kUnknownLength || offset == 0) << "offset must be 0 if length is "
                                                 << kUnknownLength;

  auto assets = ZipAssetsProvider::Create(std::move(fd), friendly_name, offset, length);
  return (assets) ? LoadImpl(std::move(assets), friendly_name, flags, std::move(override_asset))
                  : nullptr;
}

std::unique_ptr<const ApkAssets> ApkAssets::LoadTable(
    const std::string& path, const package_property_t flags,
    std::unique_ptr<const AssetsProvider> override_asset) {

  auto assets = CreateAssetFromFile(path);
  return (assets) ? LoadTableImpl(std::move(assets), path, flags, std::move(override_asset))
                  : nullptr;
}

std::unique_ptr<const ApkAssets> ApkAssets::LoadTableFromFd(
    unique_fd fd, const std::string& friendly_name, const package_property_t flags,
    std::unique_ptr<const AssetsProvider> override_asset, const off64_t offset,
    const off64_t length) {

  auto assets = CreateAssetFromFd(std::move(fd), nullptr /* path */, offset, length);
  return (assets) ? LoadTableImpl(std::move(assets), friendly_name, flags,
                                  std::move(override_asset))
                  : nullptr;
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
  std::unique_ptr<const LoadedIdmap> loaded_idmap = LoadedIdmap::Load(idmap_path, idmap_data);
  if (loaded_idmap == nullptr) {
    LOG(ERROR) << "failed to load IDMAP " << idmap_path;
    return {};
  }
  
  auto overlay_path = loaded_idmap->OverlayApkPath();
  auto assets = ZipAssetsProvider::Create(overlay_path);
  return (assets) ? LoadImpl(std::move(assets), overlay_path, flags | PROPERTY_OVERLAY,
                             nullptr /* override_asset */, std::move(idmap_asset),
                             std::move(loaded_idmap))
                  : nullptr;
}

std::unique_ptr<const ApkAssets> ApkAssets::LoadFromDir(
    const std::string& path, const package_property_t flags,
    std::unique_ptr<const AssetsProvider> override_asset) {

  auto assets = DirectoryAssetsProvider::Create(path);
  return (assets) ? LoadImpl(std::move(assets), path, flags, std::move(override_asset))
                  : nullptr;
}

std::unique_ptr<const ApkAssets> ApkAssets::LoadEmpty(
    const package_property_t flags, std::unique_ptr<const AssetsProvider> override_asset) {

  auto assets = (override_asset) ? std::move(override_asset)
                                 : std::unique_ptr<const AssetsProvider>(new EmptyAssetsProvider());
  std::unique_ptr<ApkAssets> loaded_apk(new ApkAssets(std::move(assets), "empty" /* path */,
                                                      -1 /* last_mod-time */, flags));
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

std::unique_ptr<const ApkAssets> ApkAssets::LoadImpl(
    std::unique_ptr<const AssetsProvider> assets, const std::string& path,
    package_property_t property_flags, std::unique_ptr<const AssetsProvider> override_assets,
    std::unique_ptr<Asset> idmap_asset, std::unique_ptr<const LoadedIdmap> idmap) {

  const time_t last_mod_time = getFileModDate(path.c_str());

  // Open the resource table via mmap unless it is compressed. This logic is taken care of by Open.
  bool resources_asset_exists = false;
  auto resources_asset_ = assets->Open(kResourcesArsc, Asset::AccessMode::ACCESS_BUFFER,
                                       &resources_asset_exists);

  assets = MultiAssetsProvider::Create(std::move(override_assets), std::move(assets));

  // Wrap the handle in a unique_ptr so it gets automatically closed.
  std::unique_ptr<ApkAssets>
      loaded_apk(new ApkAssets(std::move(assets), path, last_mod_time, property_flags));

  if (!resources_asset_exists) {
    loaded_apk->loaded_arsc_ = LoadedArsc::CreateEmpty();
    return std::move(loaded_apk);
  }

  loaded_apk->resources_asset_ = std::move(resources_asset_);
  if (!loaded_apk->resources_asset_) {
    LOG(ERROR) << "Failed to open '" << kResourcesArsc << "' in APK '" << path << "'.";
    return {};
  }

  // Must retain ownership of the IDMAP Asset so that all pointers to its mmapped data remain valid.
  loaded_apk->idmap_asset_ = std::move(idmap_asset);
  loaded_apk->loaded_idmap_ = std::move(idmap);

  const StringPiece data(
      reinterpret_cast<const char*>(loaded_apk->resources_asset_->getBuffer(true /*wordAligned*/)),
      loaded_apk->resources_asset_->getLength());
  if (data.data() == nullptr || data.empty()) {
    LOG(ERROR) << "Failed to read '" << kResourcesArsc << "' data in APK '" << path << "'.";
    return {};
  }

  loaded_apk->loaded_arsc_ = LoadedArsc::Load(data, loaded_apk->loaded_idmap_.get(),
                                              property_flags);
  if (!loaded_apk->loaded_arsc_) {
    LOG(ERROR) << "Failed to load '" << kResourcesArsc << "' in APK '" << path << "'.";
    return {};
  }

  // Need to force a move for mingw32.
  return std::move(loaded_apk);
}

std::unique_ptr<const ApkAssets> ApkAssets::LoadTableImpl(
    std::unique_ptr<Asset> resources_asset, const std::string& path,
    package_property_t property_flags, std::unique_ptr<const AssetsProvider> override_assets) {

  const time_t last_mod_time = getFileModDate(path.c_str());

  auto assets = (override_assets) ? std::move(override_assets)
                                  : std::unique_ptr<AssetsProvider>(new EmptyAssetsProvider());

  std::unique_ptr<ApkAssets> loaded_apk(
      new ApkAssets(std::move(assets), path, last_mod_time, property_flags));
  loaded_apk->resources_asset_ = std::move(resources_asset);

  const StringPiece data(
      reinterpret_cast<const char*>(loaded_apk->resources_asset_->getBuffer(true /*wordAligned*/)),
      loaded_apk->resources_asset_->getLength());
  if (data.data() == nullptr || data.empty()) {
    LOG(ERROR) << "Failed to read resources table data in '" << path << "'.";
    return {};
  }

  loaded_apk->loaded_arsc_ = LoadedArsc::Load(data, nullptr, property_flags);
  if (loaded_apk->loaded_arsc_ == nullptr) {
    LOG(ERROR) << "Failed to read resources table in '" << path << "'.";
    return {};
  }

  // Need to force a move for mingw32.
  return std::move(loaded_apk);
}

bool ApkAssets::IsUpToDate() const {
  if (IsLoader()) {
    // Loaders are invalidated by the app, not the system, so assume they are up to date.
    return true;
  }
  return (!loaded_idmap_ || loaded_idmap_->IsUpToDate()) &&
      last_mod_time_ == getFileModDate(path_.c_str());

}

}  // namespace android
