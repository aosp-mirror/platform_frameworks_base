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

#include "android-base/logging.h"
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

  loaded_apk->resources_asset_ =
      loaded_apk->Open("resources.arsc", Asset::AccessMode::ACCESS_BUFFER);
  if (loaded_apk->resources_asset_ == nullptr) {
    return {};
  }

  loaded_apk->path_ = path;
  loaded_apk->loaded_arsc_ =
      LoadedArsc::Load(loaded_apk->resources_asset_->getBuffer(true /*wordAligned*/),
                       loaded_apk->resources_asset_->getLength(), system, load_as_shared_library);
  if (loaded_apk->loaded_arsc_ == nullptr) {
    return {};
  }

  // Need to force a move for mingw32.
  return std::move(loaded_apk);
}

std::unique_ptr<Asset> ApkAssets::Open(const std::string& path, Asset::AccessMode /*mode*/) const {
  ATRACE_NAME("ApkAssets::Open");
  CHECK(zip_handle_ != nullptr);

  ::ZipString name(path.c_str());
  ::ZipEntry entry;
  int32_t result = ::FindEntry(zip_handle_.get(), name, &entry);
  if (result != 0) {
    LOG(ERROR) << "No entry '" << path << "' found in APK.";
    return {};
  }

  if (entry.method == kCompressDeflated) {
    auto compressed_asset = util::make_unique<_CompressedAsset>();
    if (compressed_asset->openChunk(::GetFileDescriptor(zip_handle_.get()), entry.offset,
                                    entry.method, entry.uncompressed_length,
                                    entry.compressed_length) != NO_ERROR) {
      LOG(ERROR) << "Failed to decompress '" << path << "'.";
      return {};
    }
    return std::move(compressed_asset);
  } else {
    auto uncompressed_asset = util::make_unique<_FileAsset>();
    if (uncompressed_asset->openChunk(path.c_str(), ::GetFileDescriptor(zip_handle_.get()),
                                      entry.offset, entry.uncompressed_length) != NO_ERROR) {
      LOG(ERROR) << "Failed to mmap '" << path << "'.";
      return {};
    }
    return std::move(uncompressed_asset);
  }
  return {};
}

}  // namespace android
