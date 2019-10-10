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

class MatchingResources {
 public:
  void Add(ResourceId target_resid, ResourceId overlay_resid) {
    TypeId target_typeid = EXTRACT_TYPE(target_resid);
    if (map_.find(target_typeid) == map_.end()) {
      map_.emplace(target_typeid, std::set<std::pair<ResourceId, ResourceId>>());
    }
    map_[target_typeid].insert(std::make_pair(target_resid, overlay_resid));
  }

  inline const std::map<TypeId, std::set<std::pair<ResourceId, ResourceId>>>& WARN_UNUSED
  Map() const {
    return map_;
  }

 private:
  // target type id -> set { pair { overlay entry id, overlay entry id } }
  std::map<TypeId, std::set<std::pair<ResourceId, ResourceId>>> map_;
};

bool WARN_UNUSED Read16(std::istream& stream, uint16_t* out) {
  uint16_t value;
  if (stream.read(reinterpret_cast<char*>(&value), sizeof(uint16_t))) {
    *out = dtohl(value);
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

// a string is encoded as a kIdmapStringLength char array; the array is always null-terminated
bool WARN_UNUSED ReadString(std::istream& stream, char out[kIdmapStringLength]) {
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

Result<uint32_t> GetCrc(const ZipFile& zip) {
  const Result<uint32_t> a = zip.Crc("resources.arsc");
  const Result<uint32_t> b = zip.Crc("AndroidManifest.xml");
  return a && b
             ? Result<uint32_t>(*a ^ *b)
             : Error("failed to get CRC for \"%s\"", a ? "AndroidManifest.xml" : "resources.arsc");
}

}  // namespace

std::unique_ptr<const IdmapHeader> IdmapHeader::FromBinaryStream(std::istream& stream) {
  std::unique_ptr<IdmapHeader> idmap_header(new IdmapHeader());

  if (!Read32(stream, &idmap_header->magic_) || !Read32(stream, &idmap_header->version_) ||
      !Read32(stream, &idmap_header->target_crc_) || !Read32(stream, &idmap_header->overlay_crc_) ||
      !ReadString(stream, idmap_header->target_path_) ||
      !ReadString(stream, idmap_header->overlay_path_)) {
    return nullptr;
  }

  return std::move(idmap_header);
}

Result<Unit> IdmapHeader::IsUpToDate() const {
  if (magic_ != kIdmapMagic) {
    return Error("bad magic: actual 0x%08x, expected 0x%08x", magic_, kIdmapMagic);
  }

  if (version_ != kIdmapCurrentVersion) {
    return Error("bad version: actual 0x%08x, expected 0x%08x", version_, kIdmapCurrentVersion);
  }

  const std::unique_ptr<const ZipFile> target_zip = ZipFile::Open(target_path_);
  if (!target_zip) {
    return Error("failed to open target %s", GetTargetPath().to_string().c_str());
  }

  Result<uint32_t> target_crc = GetCrc(*target_zip);
  if (!target_crc) {
    return Error("failed to get target crc");
  }

  if (target_crc_ != *target_crc) {
    return Error("bad target crc: idmap version 0x%08x, file system version 0x%08x", target_crc_,
                 *target_crc);
  }

  const std::unique_ptr<const ZipFile> overlay_zip = ZipFile::Open(overlay_path_);
  if (!overlay_zip) {
    return Error("failed to open overlay %s", GetOverlayPath().to_string().c_str());
  }

  Result<uint32_t> overlay_crc = GetCrc(*overlay_zip);
  if (!overlay_crc) {
    return Error("failed to get overlay crc");
  }

  if (overlay_crc_ != *overlay_crc) {
    return Error("bad overlay crc: idmap version 0x%08x, file system version 0x%08x", overlay_crc_,
                 *overlay_crc);
  }

  return Unit{};
}

std::unique_ptr<const IdmapData::Header> IdmapData::Header::FromBinaryStream(std::istream& stream) {
  std::unique_ptr<IdmapData::Header> idmap_data_header(new IdmapData::Header());

  uint16_t target_package_id16;
  if (!Read16(stream, &target_package_id16) || !Read16(stream, &idmap_data_header->type_count_)) {
    return nullptr;
  }
  idmap_data_header->target_package_id_ = target_package_id16;

  return std::move(idmap_data_header);
}

std::unique_ptr<const IdmapData::TypeEntry> IdmapData::TypeEntry::FromBinaryStream(
    std::istream& stream) {
  std::unique_ptr<IdmapData::TypeEntry> data(new IdmapData::TypeEntry());
  uint16_t target_type16;
  uint16_t overlay_type16;
  uint16_t entry_count;
  if (!Read16(stream, &target_type16) || !Read16(stream, &overlay_type16) ||
      !Read16(stream, &entry_count) || !Read16(stream, &data->entry_offset_)) {
    return nullptr;
  }
  data->target_type_id_ = target_type16;
  data->overlay_type_id_ = overlay_type16;
  for (uint16_t i = 0; i < entry_count; i++) {
    ResourceId resid;
    if (!Read32(stream, &resid)) {
      return nullptr;
    }
    data->entries_.push_back(resid);
  }

  return std::move(data);
}

std::unique_ptr<const IdmapData> IdmapData::FromBinaryStream(std::istream& stream) {
  std::unique_ptr<IdmapData> data(new IdmapData());
  data->header_ = IdmapData::Header::FromBinaryStream(stream);
  if (!data->header_) {
    return nullptr;
  }
  for (size_t type_count = 0; type_count < data->header_->GetTypeCount(); type_count++) {
    std::unique_ptr<const TypeEntry> type = IdmapData::TypeEntry::FromBinaryStream(stream);
    if (!type) {
      return nullptr;
    }
    data->type_entries_.push_back(std::move(type));
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

  MatchingResources matching_resources;
  for (const auto mapping : resource_mapping.GetTargetToOverlayMap()) {
    if (mapping.second.data_type != Res_value::TYPE_REFERENCE) {
      // The idmap format must change to support non-references.
      continue;
    }

    matching_resources.Add(mapping.first, mapping.second.data_value);
  }

  // encode idmap data
  std::unique_ptr<IdmapData> data(new IdmapData());
  const auto types_end = matching_resources.Map().cend();
  for (auto ti = matching_resources.Map().cbegin(); ti != types_end; ++ti) {
    auto ei = ti->second.cbegin();
    std::unique_ptr<IdmapData::TypeEntry> type(new IdmapData::TypeEntry());
    type->target_type_id_ = EXTRACT_TYPE(ei->first);
    type->overlay_type_id_ = EXTRACT_TYPE(ei->second);
    type->entry_offset_ = EXTRACT_ENTRY(ei->first);
    EntryId last_target_entry = kNoEntry;
    for (; ei != ti->second.cend(); ++ei) {
      if (last_target_entry != kNoEntry) {
        int count = EXTRACT_ENTRY(ei->first) - last_target_entry - 1;
        type->entries_.insert(type->entries_.end(), count, kNoEntry);
      }
      type->entries_.push_back(EXTRACT_ENTRY(ei->second));
      last_target_entry = EXTRACT_ENTRY(ei->first);
    }
    data->type_entries_.push_back(std::move(type));
  }

  std::unique_ptr<IdmapData::Header> data_header(new IdmapData::Header());
  data_header->target_package_id_ = resource_mapping.GetTargetPackageId();
  data_header->type_count_ = data->type_entries_.size();
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

  Result<uint32_t> crc = GetCrc(*target_zip);
  if (!crc) {
    return Error(crc.GetError(), "failed to get zip CRC for target");
  }
  header->target_crc_ = *crc;

  crc = GetCrc(*overlay_zip);
  if (!crc) {
    return Error(crc.GetError(), "failed to get zip CRC for overlay");
  }
  header->overlay_crc_ = *crc;

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

  std::unique_ptr<Idmap> idmap(new Idmap());
  idmap->header_ = std::move(header);

  auto overlay_info = utils::ExtractOverlayManifestInfo(overlay_apk_path);
  if (!overlay_info) {
    return overlay_info.GetError();
  }

  auto resource_mapping =
      ResourceMapping::FromApkAssets(target_apk_assets, overlay_apk_assets, *overlay_info,
                                     fulfilled_policies, enforce_overlayable);
  if (!resource_mapping) {
    return resource_mapping.GetError();
  }

  auto idmap_data = IdmapData::FromResourceMapping(*resource_mapping);
  if (!idmap_data) {
    return idmap_data.GetError();
  }

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

void IdmapData::TypeEntry::accept(Visitor* v) const {
  assert(v != nullptr);
  v->visit(*this);
}

void IdmapData::accept(Visitor* v) const {
  assert(v != nullptr);
  v->visit(*this);
  header_->accept(v);
  auto end = type_entries_.cend();
  for (auto iter = type_entries_.cbegin(); iter != end; ++iter) {
    (*iter)->accept(v);
  }
}

void Idmap::accept(Visitor* v) const {
  assert(v != nullptr);
  v->visit(*this);
  header_->accept(v);
  auto end = data_.cend();
  for (auto iter = data_.cbegin(); iter != end; ++iter) {
    (*iter)->accept(v);
  }
}

}  // namespace android::idmap2
