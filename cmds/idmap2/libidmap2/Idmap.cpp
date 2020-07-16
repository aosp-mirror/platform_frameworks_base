/*
 * Copyright (C) 2018 The Android Open Source Project
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

#include "idmap2/Idmap.h"

#include <algorithm>
#include <iostream>
#include <iterator>
#include <limits>
#include <map>
#include <memory>
#include <set>
#include <string>
#include <utility>
#include <vector>

#include "android-base/macros.h"
#include "android-base/stringprintf.h"
#include "androidfw/AssetManager2.h"
#include "idmap2/ResourceMapping.h"
#include "idmap2/ResourceUtils.h"
#include "idmap2/Result.h"
#include "idmap2/SysTrace.h"
#include "idmap2/ZipFile.h"
#include "utils/String16.h"
#include "utils/String8.h"

namespace android::idmap2 {

namespace {

bool WARN_UNUSED Read8(std::istream& stream, uint8_t* out) {
  uint8_t value;
  if (stream.read(reinterpret_cast<char*>(&value), sizeof(uint8_t))) {
    *out = value;
    return true;
  }
  return false;
}

bool WARN_UNUSED Read32(std::istream& stream, uint32_t* out) {
  uint32_t value;
  if (stream.read(reinterpret_cast<char*>(&value), sizeof(uint32_t))) {
    *out = dtohl(value);
    return true;
  }
  return false;
}

bool WARN_UNUSED ReadBuffer(std::istream& stream, std::unique_ptr<uint8_t[]>* out, size_t length) {
  auto buffer = std::unique_ptr<uint8_t[]>(new uint8_t[length]);
  if (stream.read(reinterpret_cast<char*>(buffer.get()), length)) {
    *out = std::move(buffer);
    return true;
  }
  return false;
}

// a string is encoded as a kIdmapStringLength char array; the array is always null-terminated
bool WARN_UNUSED ReadString256(std::istream& stream, char out[kIdmapStringLength]) {
  char buf[kIdmapStringLength];
  memset(buf, 0, sizeof(buf));
  if (!stream.read(buf, sizeof(buf))) {
    return false;
  }
  if (buf[sizeof(buf) - 1] != '\0') {
    return false;
  }
  memcpy(out, buf, sizeof(buf));
  return true;
}

Result<std::string> ReadString(std::istream& stream) {
  uint32_t size;
  if (!Read32(stream, &size)) {
    return Error("failed to read string size");
  }
  if (size == 0) {
    return std::string("");
  }
  std::string buf(size, '\0');
  if (!stream.read(buf.data(), size)) {
    return Error("failed to read string of size %u", size);
  }
  // buf is guaranteed to be null terminated (with enough nulls to end on a word boundary)
  buf.resize(strlen(buf.c_str()));
  return buf;
}

}  // namespace

Result<uint32_t> GetPackageCrc(const ZipFile& zip) {
  const Result<uint32_t> a = zip.Crc("resources.arsc");
  const Result<uint32_t> b = zip.Crc("AndroidManifest.xml");
  return a && b
             ? Result<uint32_t>(*a ^ *b)
             : Error("failed to get CRC for \"%s\"", a ? "AndroidManifest.xml" : "resources.arsc");
}

std::unique_ptr<const IdmapHeader> IdmapHeader::FromBinaryStream(std::istream& stream) {
  std::unique_ptr<IdmapHeader> idmap_header(new IdmapHeader());
  uint8_t enforce_overlayable;
  if (!Read32(stream, &idmap_header->magic_) || !Read32(stream, &idmap_header->version_) ||
      !Read32(stream, &idmap_header->target_crc_) || !Read32(stream, &idmap_header->overlay_crc_) ||
      !Read32(stream, &idmap_header->fulfilled_policies_) || !Read8(stream, &enforce_overlayable) ||
      !ReadString256(stream, idmap_header->target_path_) ||
      !ReadString256(stream, idmap_header->overlay_path_)) {
    return nullptr;
  }

  idmap_header->enforce_overlayable_ = static_cast<bool>(enforce_overlayable);

  auto debug_str = ReadString(stream);
  if (!debug_str) {
    return nullptr;
  }
  idmap_header->debug_info_ = std::move(*debug_str);

  return std::move(idmap_header);
}

Result<Unit> IdmapHeader::IsUpToDate(const char* target_path, const char* overlay_path,
                                     PolicyBitmask fulfilled_policies,
                                     bool enforce_overlayable) const {
  const std::unique_ptr<const ZipFile> target_zip = ZipFile::Open(target_path);
  if (!target_zip) {
    return Error("failed to open target %s", target_path);
  }

  const Result<uint32_t> target_crc = GetPackageCrc(*target_zip);
  if (!target_crc) {
    return Error("failed to get target crc");
  }

  const std::unique_ptr<const ZipFile> overlay_zip = ZipFile::Open(overlay_path);
  if (!overlay_zip) {
    return Error("failed to overlay target %s", overlay_path);
  }

  const Result<uint32_t> overlay_crc = GetPackageCrc(*overlay_zip);
  if (!overlay_crc) {
    return Error("failed to get overlay crc");
  }

  return IsUpToDate(target_path, overlay_path, *target_crc, *overlay_crc, fulfilled_policies,
                    enforce_overlayable);
}

Result<Unit> IdmapHeader::IsUpToDate(const char* target_path, const char* overlay_path,
                                     uint32_t target_crc, uint32_t overlay_crc,
                                     PolicyBitmask fulfilled_policies,
                                     bool enforce_overlayable) const {
  if (magic_ != kIdmapMagic) {
    return Error("bad magic: actual 0x%08x, expected 0x%08x", magic_, kIdmapMagic);
  }

  if (version_ != kIdmapCurrentVersion) {
    return Error("bad version: actual 0x%08x, expected 0x%08x", version_, kIdmapCurrentVersion);
  }

  if (target_crc_ != target_crc) {
    return Error("bad target crc: idmap version 0x%08x, file system version 0x%08x", target_crc_,
                 target_crc);
  }

  if (overlay_crc_ != overlay_crc) {
    return Error("bad overlay crc: idmap version 0x%08x, file system version 0x%08x", overlay_crc_,
                 overlay_crc);
  }

  if (fulfilled_policies_ != fulfilled_policies) {
    return Error("bad fulfilled policies: idmap version 0x%08x, file system version 0x%08x",
                 fulfilled_policies, fulfilled_policies_);
  }

  if (enforce_overlayable != enforce_overlayable_) {
    return Error("bad enforce overlayable: idmap version %s, file system version %s",
                 enforce_overlayable ? "true" : "false", enforce_overlayable_ ? "true" : "false");
  }

  if (strcmp(target_path, target_path_) != 0) {
    return Error("bad target path: idmap version %s, file system version %s", target_path,
                 target_path_);
  }

  if (strcmp(overlay_path, overlay_path_) != 0) {
    return Error("bad overlay path: idmap version %s, file system version %s", overlay_path,
                 overlay_path_);
  }

  return Unit{};
}

std::unique_ptr<const IdmapData::Header> IdmapData::Header::FromBinaryStream(std::istream& stream) {
  std::unique_ptr<IdmapData::Header> idmap_data_header(new IdmapData::Header());

  if (!Read8(stream, &idmap_data_header->target_package_id_) ||
      !Read8(stream, &idmap_data_header->overlay_package_id_) ||
      !Read32(stream, &idmap_data_header->target_entry_count) ||
      !Read32(stream, &idmap_data_header->overlay_entry_count) ||
      !Read32(stream, &idmap_data_header->string_pool_index_offset) ||
      !Read32(stream, &idmap_data_header->string_pool_len)) {
    return nullptr;
  }

  return std::move(idmap_data_header);
}

std::unique_ptr<const IdmapData> IdmapData::FromBinaryStream(std::istream& stream) {
  std::unique_ptr<IdmapData> data(new IdmapData());
  data->header_ = IdmapData::Header::FromBinaryStream(stream);
  if (!data->header_) {
    return nullptr;
  }
  // Read the mapping of target resource id to overlay resource value.
  for (size_t i = 0; i < data->header_->GetTargetEntryCount(); i++) {
    TargetEntry target_entry{};
    if (!Read32(stream, &target_entry.target_id) || !Read8(stream, &target_entry.data_type) ||
        !Read32(stream, &target_entry.data_value)) {
      return nullptr;
    }
    data->target_entries_.emplace_back(target_entry);
  }

  // Read the mapping of overlay resource id to target resource id.
  for (size_t i = 0; i < data->header_->GetOverlayEntryCount(); i++) {
    OverlayEntry overlay_entry{};
    if (!Read32(stream, &overlay_entry.overlay_id) || !Read32(stream, &overlay_entry.target_id)) {
      return nullptr;
    }
    data->overlay_entries_.emplace_back(overlay_entry);
  }

  // Read raw string pool bytes.
  if (!ReadBuffer(stream, &data->string_pool_, data->header_->string_pool_len)) {
    return nullptr;
  }

  return std::move(data);
}

std::string Idmap::CanonicalIdmapPathFor(const std::string& absolute_dir,
                                         const std::string& absolute_apk_path) {
  assert(absolute_dir.size() > 0 && absolute_dir[0] == "/");
  assert(absolute_apk_path.size() > 0 && absolute_apk_path[0] == "/");
  std::string copy(++absolute_apk_path.cbegin(), absolute_apk_path.cend());
  replace(copy.begin(), copy.end(), '/', '@');
  return absolute_dir + "/" + copy + "@idmap";
}

Result<std::unique_ptr<const Idmap>> Idmap::FromBinaryStream(std::istream& stream) {
  SYSTRACE << "Idmap::FromBinaryStream";
  std::unique_ptr<Idmap> idmap(new Idmap());

  idmap->header_ = IdmapHeader::FromBinaryStream(stream);
  if (!idmap->header_) {
    return Error("failed to parse idmap header");
  }

  // idmap version 0x01 does not specify the number of data blocks that follow
  // the idmap header; assume exactly one data block
  for (int i = 0; i < 1; i++) {
    std::unique_ptr<const IdmapData> data = IdmapData::FromBinaryStream(stream);
    if (!data) {
      return Error("failed to parse data block %d", i);
    }
    idmap->data_.push_back(std::move(data));
  }

  return {std::move(idmap)};
}

Result<std::unique_ptr<const IdmapData>> IdmapData::FromResourceMapping(
    const ResourceMapping& resource_mapping) {
  if (resource_mapping.GetTargetToOverlayMap().empty()) {
    return Error("no resources were overlaid");
  }

  std::unique_ptr<IdmapData> data(new IdmapData());
  for (const auto& mappings : resource_mapping.GetTargetToOverlayMap()) {
    data->target_entries_.emplace_back(IdmapData::TargetEntry{
        mappings.first, mappings.second.data_type, mappings.second.data_value});
  }

  for (const auto& mappings : resource_mapping.GetOverlayToTargetMap()) {
    data->overlay_entries_.emplace_back(IdmapData::OverlayEntry{mappings.first, mappings.second});
  }

  std::unique_ptr<IdmapData::Header> data_header(new IdmapData::Header());
  data_header->target_package_id_ = resource_mapping.GetTargetPackageId();
  data_header->overlay_package_id_ = resource_mapping.GetOverlayPackageId();
  data_header->target_entry_count = static_cast<uint32_t>(data->target_entries_.size());
  data_header->overlay_entry_count = static_cast<uint32_t>(data->overlay_entries_.size());
  data_header->string_pool_index_offset = resource_mapping.GetStringPoolOffset();

  const auto string_pool_data = resource_mapping.GetStringPoolData();
  data_header->string_pool_len = string_pool_data.second;
  data->string_pool_ = std::unique_ptr<uint8_t[]>(new uint8_t[data_header->string_pool_len]);
  memcpy(data->string_pool_.get(), string_pool_data.first, data_header->string_pool_len);

  data->header_ = std::move(data_header);
  return {std::move(data)};
}

Result<std::unique_ptr<const Idmap>> Idmap::FromApkAssets(const ApkAssets& target_apk_assets,
                                                          const ApkAssets& overlay_apk_assets,
                                                          const PolicyBitmask& fulfilled_policies,
                                                          bool enforce_overlayable) {
  SYSTRACE << "Idmap::FromApkAssets";
  const std::string& target_apk_path = target_apk_assets.GetPath();
  const std::string& overlay_apk_path = overlay_apk_assets.GetPath();

  const std::unique_ptr<const ZipFile> target_zip = ZipFile::Open(target_apk_path);
  if (!target_zip) {
    return Error("failed to open target as zip");
  }

  const std::unique_ptr<const ZipFile> overlay_zip = ZipFile::Open(overlay_apk_path);
  if (!overlay_zip) {
    return Error("failed to open overlay as zip");
  }

  std::unique_ptr<IdmapHeader> header(new IdmapHeader());
  header->magic_ = kIdmapMagic;
  header->version_ = kIdmapCurrentVersion;

  Result<uint32_t> crc = GetPackageCrc(*target_zip);
  if (!crc) {
    return Error(crc.GetError(), "failed to get zip CRC for target");
  }
  header->target_crc_ = *crc;

  crc = GetPackageCrc(*overlay_zip);
  if (!crc) {
    return Error(crc.GetError(), "failed to get zip CRC for overlay");
  }
  header->overlay_crc_ = *crc;

  header->fulfilled_policies_ = fulfilled_policies;
  header->enforce_overlayable_ = enforce_overlayable;

  if (target_apk_path.size() > sizeof(header->target_path_)) {
    return Error("target apk path \"%s\" longer than maximum size %zu", target_apk_path.c_str(),
                 sizeof(header->target_path_));
  }
  memset(header->target_path_, 0, sizeof(header->target_path_));
  memcpy(header->target_path_, target_apk_path.data(), target_apk_path.size());

  if (overlay_apk_path.size() > sizeof(header->overlay_path_)) {
    return Error("overlay apk path \"%s\" longer than maximum size %zu", overlay_apk_path.c_str(),
                 sizeof(header->target_path_));
  }
  memset(header->overlay_path_, 0, sizeof(header->overlay_path_));
  memcpy(header->overlay_path_, overlay_apk_path.data(), overlay_apk_path.size());

  auto overlay_info = utils::ExtractOverlayManifestInfo(overlay_apk_path);
  if (!overlay_info) {
    return overlay_info.GetError();
  }

  LogInfo log_info;
  auto resource_mapping =
      ResourceMapping::FromApkAssets(target_apk_assets, overlay_apk_assets, *overlay_info,
                                     fulfilled_policies, enforce_overlayable, log_info);
  if (!resource_mapping) {
    return resource_mapping.GetError();
  }

  auto idmap_data = IdmapData::FromResourceMapping(*resource_mapping);
  if (!idmap_data) {
    return idmap_data.GetError();
  }

  std::unique_ptr<Idmap> idmap(new Idmap());
  header->debug_info_ = log_info.GetString();
  idmap->header_ = std::move(header);
  idmap->data_.push_back(std::move(*idmap_data));

  return {std::move(idmap)};
}

void IdmapHeader::accept(Visitor* v) const {
  assert(v != nullptr);
  v->visit(*this);
}

void IdmapData::Header::accept(Visitor* v) const {
  assert(v != nullptr);
  v->visit(*this);
}

void IdmapData::accept(Visitor* v) const {
  assert(v != nullptr);
  header_->accept(v);
  v->visit(*this);
}

void Idmap::accept(Visitor* v) const {
  assert(v != nullptr);
  header_->accept(v);
  v->visit(*this);
  auto end = data_.cend();
  for (auto iter = data_.cbegin(); iter != end; ++iter) {
    (*iter)->accept(v);
  }
}

}  // namespace android::idmap2
