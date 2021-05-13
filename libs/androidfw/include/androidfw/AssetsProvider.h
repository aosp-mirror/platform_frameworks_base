/*
 * Copyright (C) 2021 The Android Open Source Project
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

#ifndef ANDROIDFW_ASSETSPROVIDER_H
#define ANDROIDFW_ASSETSPROVIDER_H

#include <memory>
#include <string>

#include "android-base/macros.h"
#include "android-base/unique_fd.h"

#include "androidfw/Asset.h"
#include "androidfw/Idmap.h"
#include "androidfw/LoadedArsc.h"
#include "androidfw/misc.h"

struct ZipArchive;

namespace android {

// Interface responsible for opening and iterating through asset files.
struct AssetsProvider {
  static constexpr off64_t kUnknownLength = -1;

  // Opens a file for reading. If `file_exists` is not null, it will be set to `true` if the file
  // exists. This is useful for determining if the file exists but was unable to be opened due to
  // an I/O error.
  std::unique_ptr<Asset> Open(const std::string& path,
                              Asset::AccessMode mode = Asset::AccessMode::ACCESS_RANDOM,
                              bool* file_exists = nullptr) const;

  // Iterate over all files and directories provided by the interface. The order of iteration is
  // stable.
  virtual bool ForEachFile(const std::string& path,
                           const std::function<void(const StringPiece&, FileType)>& f) const = 0;

  // Retrieves the path to the contents of the AssetsProvider on disk. The path could represent an
  // APk, a directory, or some other file type.
  WARN_UNUSED virtual std::optional<std::string_view> GetPath() const = 0;

  // Retrieves a name that represents the interface. This may or may not be the path of the
  // interface source.
  WARN_UNUSED virtual const std::string& GetDebugName() const = 0;

  // Returns whether the interface provides the most recent version of its files.
  WARN_UNUSED virtual bool IsUpToDate() const = 0;

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
                                                  off64_t length = AssetsProvider::kUnknownLength);

  virtual ~AssetsProvider() = default;
 protected:
  virtual std::unique_ptr<Asset> OpenInternal(const std::string& path, Asset::AccessMode mode,
                                              bool* file_exists) const = 0;
};

// Supplies assets from a zip archive.
struct ZipAssetsProvider : public AssetsProvider {
  static std::unique_ptr<ZipAssetsProvider> Create(std::string path);
  static std::unique_ptr<ZipAssetsProvider> Create(base::unique_fd fd,
                                                   std::string friendly_name,
                                                   off64_t offset = 0,
                                                   off64_t len = kUnknownLength);

  bool ForEachFile(const std::string& root_path,
                   const std::function<void(const StringPiece&, FileType)>& f) const override;

  WARN_UNUSED std::optional<std::string_view> GetPath() const override;
  WARN_UNUSED const std::string& GetDebugName() const override;
  WARN_UNUSED bool IsUpToDate() const override;
  WARN_UNUSED std::optional<uint32_t> GetCrc(std::string_view path) const;

  ~ZipAssetsProvider() override = default;
 protected:
  std::unique_ptr<Asset> OpenInternal(const std::string& path, Asset::AccessMode mode,
                                      bool* file_exists) const override;

 private:
  struct PathOrDebugName;
  ZipAssetsProvider(ZipArchive* handle, PathOrDebugName&& path, time_t last_mod_time);

  struct PathOrDebugName {
    PathOrDebugName(std::string&& value, bool is_path);

    // Retrieves the path or null if this class represents a debug name.
    WARN_UNUSED const std::string* GetPath() const;

    // Retrieves a name that represents the interface. This may or may not represent a path.
    WARN_UNUSED const std::string& GetDebugName() const;

   private:
    std::string value_;
    bool is_path_;
  };

  std::unique_ptr<ZipArchive, void (*)(ZipArchive*)> zip_handle_;
  PathOrDebugName name_;
  time_t last_mod_time_;
};

// Supplies assets from a root directory.
struct DirectoryAssetsProvider : public AssetsProvider {
  static std::unique_ptr<DirectoryAssetsProvider> Create(std::string root_dir);

  bool ForEachFile(const std::string& path,
                   const std::function<void(const StringPiece&, FileType)>& f) const override;

  WARN_UNUSED std::optional<std::string_view> GetPath() const override;
  WARN_UNUSED const std::string& GetDebugName() const override;
  WARN_UNUSED bool IsUpToDate() const override;

  ~DirectoryAssetsProvider() override = default;
 protected:
  std::unique_ptr<Asset> OpenInternal(const std::string& path,
                                      Asset::AccessMode mode,
                                      bool* file_exists) const override;

 private:
  explicit DirectoryAssetsProvider(std::string&& path, time_t last_mod_time);
  std::string dir_;
  time_t last_mod_time_;
};

// Supplies assets from a `primary` asset provider and falls back to supplying assets from the
// `secondary` asset provider if the asset cannot be found in the `primary`.
struct MultiAssetsProvider : public AssetsProvider {
  static std::unique_ptr<AssetsProvider> Create(std::unique_ptr<AssetsProvider>&& primary,
                                                std::unique_ptr<AssetsProvider>&& secondary);

  bool ForEachFile(const std::string& root_path,
                   const std::function<void(const StringPiece&, FileType)>& f) const override;

  WARN_UNUSED std::optional<std::string_view> GetPath() const override;
  WARN_UNUSED const std::string& GetDebugName() const override;
  WARN_UNUSED bool IsUpToDate() const override;

  ~MultiAssetsProvider() override = default;
 protected:
  std::unique_ptr<Asset> OpenInternal(
      const std::string& path, Asset::AccessMode mode, bool* file_exists) const override;

 private:
  MultiAssetsProvider(std::unique_ptr<AssetsProvider>&& primary,
                      std::unique_ptr<AssetsProvider>&& secondary);

  std::unique_ptr<AssetsProvider> primary_;
  std::unique_ptr<AssetsProvider> secondary_;
  std::optional<std::string_view> path_;
  std::string debug_name_;
};

// Does not provide any assets.
struct EmptyAssetsProvider : public AssetsProvider {
  static std::unique_ptr<AssetsProvider> Create();
  static std::unique_ptr<AssetsProvider> Create(const std::string& path);

  bool ForEachFile(const std::string& path,
                  const std::function<void(const StringPiece&, FileType)>& f) const override;

  WARN_UNUSED std::optional<std::string_view> GetPath() const override;
  WARN_UNUSED const std::string& GetDebugName() const override;
  WARN_UNUSED bool IsUpToDate() const override;

  ~EmptyAssetsProvider() override = default;
 protected:
  std::unique_ptr<Asset> OpenInternal(const std::string& path, Asset::AccessMode mode,
                                      bool* file_exists) const override;

 private:
  explicit EmptyAssetsProvider(std::optional<std::string>&& path);
  std::optional<std::string> path_;
};

}  // namespace android

#endif /* ANDROIDFW_ASSETSPROVIDER_H */
