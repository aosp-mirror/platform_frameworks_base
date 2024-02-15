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
#include <cassert>
#include <istream>
#include <iterator>
#include <limits>
#include <memory>
#include <string>
#include <utility>

#include "android-base/format.h"
#include "android-base/macros.h"
#include "androidfw/AssetManager2.h"
#include "idmap2/ResourceMapping.h"
#include "idmap2/ResourceUtils.h"
#include "idmap2/Result.h"
#include "idmap2/SysTrace.h"

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

bool WARN_UNUSED Read16(std::istream& stream, uint16_t* out) {
  uint16_t value;
  if (stream.read(reinterpret_cast<char*>(&value), sizeof(uint16_t))) {
    *out = dtohs(value);
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

bool WARN_UNUSED ReadString(std::istream& stream, std::string* out) {
  uint32_t size;
  if (!Read32(stream, &size)) {
    return false;
  }
  if (size == 0) {
    *out = "";
    return true;
  }
  std::string buf(size, '\0');
  if (!stream.read(buf.data(), size)) {
    return false;
  }
  uint32_t padding_size = CalculatePadding(size);
  if (padding_size != 0 && !stream.seekg(padding_size, std::ios_base::cur)) {
    return false;
  }
  *out = std::move(buf);
  return true;
}

}  // namespace

std::unique_ptr<const IdmapHeader> IdmapHeader::FromBinaryStream(std::istream& stream) {
  std::unique_ptr<IdmapHeader> idmap_header(new IdmapHeader());
  if (!Read32(stream, &idmap_header->magic_) || !Read32(stream, &idmap_header->version_)) {
    return nullptr;
  }

  if (idmap_header->magic_ != kIdmapMagic || idmap_header->version_ != kIdmapCurrentVersion) {
    // Do not continue parsing if the file is not a current version idmap.
    return nullptr;
  }

  uint32_t enforce_overlayable;
  if (!Read32(stream, &idmap_header->target_crc_) || !Read32(stream, &idmap_header->overlay_crc_) ||
      !Read32(stream, &idmap_header->fulfilled_policies_) ||
      !Read32(stream, &enforce_overlayable) || !ReadString(stream, &idmap_header->target_path_) ||
      !ReadString(stream, &idmap_header->overlay_path_) ||
      !ReadString(stream, &idmap_header->overlay_name_) ||
      !ReadString(stream, &idmap_header->debug_info_)) {
    return nullptr;
  }

  idmap_header->enforce_overlayable_ = enforce_overlayable != 0U;
  return std::move(idmap_header);
}

Result<Unit> IdmapHeader::IsUpToDate(const TargetResourceContainer& target,
                                     const OverlayResourceContainer& overlay,
                                     const std::string& overlay_name,
                                     PolicyBitmask fulfilled_policies,
                                     bool enforce_overlayable) const {
  const Result<uint32_t> target_crc = target.GetCrc();
  if (!target_crc) {
    return Error("failed to get target crc");
  }

  const Result<uint32_t> overlay_crc = overlay.GetCrc();
  if (!overlay_crc) {
    return Error("failed to get overlay crc");
  }

  return IsUpToDate(target.GetPath(), overlay.GetPath(), overlay_name, *target_crc, *overlay_crc,
                    fulfilled_policies, enforce_overlayable);
}

Result<Unit> IdmapHeader::IsUpToDate(const std::string& target_path,
                                     const std::string& overlay_path,
                                     const std::string& overlay_name, uint32_t target_crc,
                                     uint32_t overlay_crc, PolicyBitmask fulfilled_policies,
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

  if (target_path != target_path_) {
    return Error("bad target path: idmap version %s, file system version %s", target_path.c_str(),
                 target_path_.c_str());
  }

  if (overlay_path != overlay_path_) {
    return Error("bad overlay path: idmap version %s, file system version %s", overlay_path.c_str(),
                 overlay_path_.c_str());
  }

  if (overlay_name != overlay_name_) {
    return Error("bad overlay name: idmap version %s, file system version %s", overlay_name.c_str(),
                 overlay_name_.c_str());
  }

  return Unit{};
}

std::unique_ptr<const IdmapData::Header> IdmapData::Header::FromBinaryStream(std::istream& stream) {
  std::unique_ptr<IdmapData::Header> idmap_data_header(new IdmapData::Header());
  if (!Read32(stream, &idmap_data_header->target_entry_count) ||
      !Read32(stream, &idmap_data_header->target_entry_inline_count) ||
      !Read32(stream, &idmap_data_header->target_entry_inline_value_count) ||
      !Read32(stream, &idmap_data_header->config_count) ||
      !Read32(stream, &idmap_data_header->overlay_entry_count) ||
      !Read32(stream, &idmap_data_header->string_pool_index_offset)) {
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
    if (!Read32(stream, &target_entry.target_id) || !Read32(stream, &target_entry.overlay_id)) {
      return nullptr;
    }
    data->target_entries_.emplace_back(target_entry);
  }

  // Read the mapping of target resource id to inline overlay values.
  std::vector<std::tuple<TargetInlineEntry, uint32_t, uint32_t>> target_inline_entries;
  for (size_t i = 0; i < data->header_->GetTargetInlineEntryCount(); i++) {
    TargetInlineEntry target_entry{};
    uint32_t entry_offset;
    uint32_t entry_count;
    if (!Read32(stream, &target_entry.target_id) || !Read32(stream, &entry_offset)
        || !Read32(stream, &entry_count)) {
      return nullptr;
    }
    target_inline_entries.emplace_back(target_entry, entry_offset, entry_count);
  }

  // Read the inline overlay resource values
  std::vector<std::pair<uint32_t, TargetValue>> target_values;
  uint8_t unused1;
  uint16_t unused2;
  for (size_t i = 0; i < data->header_->GetTargetInlineEntryValueCount(); i++) {
    uint32_t config_index;
    if (!Read32(stream, &config_index)) {
      return nullptr;
    }
    TargetValue value;
    if (!Read16(stream, &unused2)
        || !Read8(stream, &unused1)
        || !Read8(stream, &value.data_type)
        || !Read32(stream, &value.data_value)) {
      return nullptr;
    }
    target_values.emplace_back(config_index, value);
  }

  // Read the configurations
  std::vector<ConfigDescription> configurations;
  for (size_t i = 0; i < data->header_->GetConfigCount(); i++) {
    ConfigDescription cd;
    if (!stream.read(reinterpret_cast<char*>(&cd), sizeof(ConfigDescription))) {
      return nullptr;
    }
    configurations.emplace_back(cd);
  }

  // Construct complete target inline entries
  for (auto [target_entry, entry_offset, entry_count] : target_inline_entries) {
    for(size_t i = 0; i < entry_count; i++) {
      const auto& target_value = target_values[entry_offset + i];
      const auto& config = configurations[target_value.first];
      target_entry.values[config] = target_value.second;
    }
    data->target_inline_entries_.emplace_back(target_entry);
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
  if (!ReadString(stream, &data->string_pool_data_)) {
    return nullptr;
  }
  return std::move(data);
}

std::string Idmap::CanonicalIdmapPathFor(std::string_view absolute_dir,
                                         std::string_view absolute_apk_path) {
  assert(absolute_dir.size() > 0 && absolute_dir[0] == "/");
  assert(absolute_apk_path.size() > 0 && absolute_apk_path[0] == "/");
  std::string copy(absolute_apk_path.begin() + 1, absolute_apk_path.end());
  replace(copy.begin(), copy.end(), '/', '@');
  return fmt::format("{}/{}@idmap", absolute_dir, copy);
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
  data->string_pool_data_ = std::string(resource_mapping.GetStringPoolData());
  uint32_t inline_value_count = 0;
  std::set<std::string> config_set;
  for (const auto& mapping : resource_mapping.GetTargetToOverlayMap()) {
    if (auto overlay_resource = std::get_if<ResourceId>(&mapping.second)) {
      data->target_entries_.push_back({mapping.first, *overlay_resource});
    } else {
      std::map<ConfigDescription, TargetValue> values;
      for (const auto& [config, value] : std::get<ConfigMap>(mapping.second)) {
        config_set.insert(config);
        ConfigDescription cd;
        ConfigDescription::Parse(config, &cd);
        values[cd] = value;
        inline_value_count++;
      }
      data->target_inline_entries_.push_back({mapping.first, std::move(values)});
    }
  }

  for (const auto& mapping : resource_mapping.GetOverlayToTargetMap()) {
    data->overlay_entries_.emplace_back(IdmapData::OverlayEntry{mapping.first, mapping.second});
  }

  std::unique_ptr<IdmapData::Header> data_header(new IdmapData::Header());
  data_header->target_entry_count = static_cast<uint32_t>(data->target_entries_.size());
  data_header->target_entry_inline_count =
      static_cast<uint32_t>(data->target_inline_entries_.size());
  data_header->target_entry_inline_value_count = inline_value_count;
  data_header->config_count = config_set.size();
  data_header->overlay_entry_count = static_cast<uint32_t>(data->overlay_entries_.size());
  data_header->string_pool_index_offset = resource_mapping.GetStringPoolOffset();
  data->header_ = std::move(data_header);
  return {std::move(data)};
}

Result<std::unique_ptr<const Idmap>> Idmap::FromContainers(const TargetResourceContainer& target,
                                                           const OverlayResourceContainer& overlay,
                                                           const std::string& overlay_name,
                                                           const PolicyBitmask& fulfilled_policies,
                                                           bool enforce_overlayable) {
  SYSTRACE << "Idmap::FromApkAssets";
  std::unique_ptr<IdmapHeader> header(new IdmapHeader());
  header->magic_ = kIdmapMagic;
  header->version_ = kIdmapCurrentVersion;

  const auto target_crc = target.GetCrc();
  if (!target_crc) {
    return Error(target_crc.GetError(), "failed to get zip CRC for '%s'", target.GetPath().data());
  }
  header->target_crc_ = *target_crc;

  const auto overlay_crc = overlay.GetCrc();
  if (!overlay_crc) {
    return Error(overlay_crc.GetError(), "failed to get zip CRC for '%s'",
                 overlay.GetPath().data());
  }
  header->overlay_crc_ = *overlay_crc;

  header->fulfilled_policies_ = fulfilled_policies;
  header->enforce_overlayable_ = enforce_overlayable;
  header->target_path_ = target.GetPath();
  header->overlay_path_ = overlay.GetPath();
  header->overlay_name_ = overlay_name;

  auto info = overlay.FindOverlayInfo(overlay_name);
  if (!info) {
    return Error(info.GetError(), "failed to get overlay info for '%s'", overlay.GetPath().data());
  }

  LogInfo log_info;
  auto resource_mapping = ResourceMapping::FromContainers(
      target, overlay, *info, fulfilled_policies, enforce_overlayable, log_info);
  if (!resource_mapping) {
    return Error(resource_mapping.GetError(), "failed to generate resource map for '%s'",
                 overlay.GetPath().data());
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
