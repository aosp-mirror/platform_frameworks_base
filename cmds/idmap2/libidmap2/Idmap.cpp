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
#include "utils/String16.h"
#include "utils/String8.h"

#include "idmap2/Idmap.h"
#include "idmap2/ResourceUtils.h"
#include "idmap2/Result.h"
#include "idmap2/ZipFile.h"

namespace android::idmap2 {

namespace {

#define EXTRACT_TYPE(resid) ((0x00ff0000 & (resid)) >> 16)

#define EXTRACT_ENTRY(resid) (0x0000ffff & (resid))

class MatchingResources {
 public:
  void Add(ResourceId target_resid, ResourceId overlay_resid) {
    TypeId target_typeid = EXTRACT_TYPE(target_resid);
    if (map_.find(target_typeid) == map_.end()) {
      map_.emplace(target_typeid, std::set<std::pair<ResourceId, ResourceId>>());
    }
    map_[target_typeid].insert(std::make_pair(target_resid, overlay_resid));
  }

  inline const std::map<TypeId, std::set<std::pair<ResourceId, ResourceId>>>& Map() const {
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

ResourceId NameToResid(const AssetManager2& am, const std::string& name) {
  return am.GetResourceId(name);
}

// TODO(martenkongstad): scan for package name instead of assuming package at index 0
//
// idmap version 0x01 naively assumes that the package to use is always the first ResTable_package
// in the resources.arsc blob. In most cases, there is only a single ResTable_package anyway, so
// this assumption tends to work out. That said, the correct thing to do is to scan
// resources.arsc for a package with a given name as read from the package manifest instead of
// relying on a hard-coded index. This however requires storing the package name in the idmap
// header, which in turn requires incrementing the idmap version. Because the initial version of
// idmap2 is compatible with idmap, this will have to wait for now.
const LoadedPackage* GetPackageAtIndex0(const LoadedArsc& loaded_arsc) {
  const std::vector<std::unique_ptr<const LoadedPackage>>& packages = loaded_arsc.GetPackages();
  if (packages.empty()) {
    return nullptr;
  }
  int id = packages[0]->GetPackageId();
  return loaded_arsc.GetPackageById(id);
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

bool IdmapHeader::IsUpToDate(std::ostream& out_error) const {
  if (magic_ != kIdmapMagic) {
    out_error << base::StringPrintf("error: bad magic: actual 0x%08x, expected 0x%08x", magic_,
                                    kIdmapMagic)
              << std::endl;
    return false;
  }

  if (version_ != kIdmapCurrentVersion) {
    out_error << base::StringPrintf("error: bad version: actual 0x%08x, expected 0x%08x", version_,
                                    kIdmapCurrentVersion)
              << std::endl;
    return false;
  }

  const std::unique_ptr<const ZipFile> target_zip = ZipFile::Open(target_path_);
  if (!target_zip) {
    out_error << "error: failed to open target " << target_path_ << std::endl;
    return false;
  }

  Result<uint32_t> target_crc = target_zip->Crc("resources.arsc");
  if (!target_crc) {
    out_error << "error: failed to get target crc" << std::endl;
    return false;
  }

  if (target_crc_ != *target_crc) {
    out_error << base::StringPrintf(
                     "error: bad target crc: idmap version 0x%08x, file system version 0x%08x",
                     target_crc_, *target_crc)
              << std::endl;
    return false;
  }

  const std::unique_ptr<const ZipFile> overlay_zip = ZipFile::Open(overlay_path_);
  if (!overlay_zip) {
    out_error << "error: failed to open overlay " << overlay_path_ << std::endl;
    return false;
  }

  Result<uint32_t> overlay_crc = overlay_zip->Crc("resources.arsc");
  if (!overlay_crc) {
    out_error << "error: failed to get overlay crc" << std::endl;
    return false;
  }

  if (overlay_crc_ != *overlay_crc) {
    out_error << base::StringPrintf(
                     "error: bad overlay crc: idmap version 0x%08x, file system version 0x%08x",
                     overlay_crc_, *overlay_crc)
              << std::endl;
    return false;
  }

  return true;
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

std::unique_ptr<const Idmap> Idmap::FromBinaryStream(std::istream& stream,
                                                     std::ostream& out_error) {
  std::unique_ptr<Idmap> idmap(new Idmap());

  idmap->header_ = IdmapHeader::FromBinaryStream(stream);
  if (!idmap->header_) {
    out_error << "error: failed to parse idmap header" << std::endl;
    return nullptr;
  }

  // idmap version 0x01 does not specify the number of data blocks that follow
  // the idmap header; assume exactly one data block
  for (int i = 0; i < 1; i++) {
    std::unique_ptr<const IdmapData> data = IdmapData::FromBinaryStream(stream);
    if (!data) {
      out_error << "error: failed to parse data block " << i << std::endl;
      return nullptr;
    }
    idmap->data_.push_back(std::move(data));
  }

  return std::move(idmap);
}

bool CheckOverlayable(const LoadedPackage& target_package,
                      const utils::OverlayManifestInfo& overlay_info,
                      const PolicyBitmask& fulfilled_polices, const ResourceId& resid) {
  const OverlayableInfo* overlayable_info = target_package.GetOverlayableInfo(resid);
  if (overlayable_info == nullptr) {
    // If the resource does not have an overlayable definition, allow the resource to be overlaid.
    // Once overlayable enforcement is turned on, this check will return false.
    return true;
  }

  if (!overlay_info.target_name.empty() && overlay_info.target_name != overlayable_info->name) {
    // If the overlay supplies a target overlayable name, the resource must belong to the
    // overlayable defined with the specified name to be overlaid.
    return false;
  }

  // Enforce policy restrictions if the resource is declared as overlayable.
  return (overlayable_info->policy_flags & fulfilled_polices) != 0;
}

std::unique_ptr<const Idmap> Idmap::FromApkAssets(
    const std::string& target_apk_path, const ApkAssets& target_apk_assets,
    const std::string& overlay_apk_path, const ApkAssets& overlay_apk_assets,
    const PolicyBitmask& fulfilled_policies, bool enforce_overlayable, std::ostream& out_error) {
  AssetManager2 target_asset_manager;
  if (!target_asset_manager.SetApkAssets({&target_apk_assets}, true, false)) {
    out_error << "error: failed to create target asset manager" << std::endl;
    return nullptr;
  }

  AssetManager2 overlay_asset_manager;
  if (!overlay_asset_manager.SetApkAssets({&overlay_apk_assets}, true, false)) {
    out_error << "error: failed to create overlay asset manager" << std::endl;
    return nullptr;
  }

  const LoadedArsc* target_arsc = target_apk_assets.GetLoadedArsc();
  if (target_arsc == nullptr) {
    out_error << "error: failed to load target resources.arsc" << std::endl;
    return nullptr;
  }

  const LoadedArsc* overlay_arsc = overlay_apk_assets.GetLoadedArsc();
  if (overlay_arsc == nullptr) {
    out_error << "error: failed to load overlay resources.arsc" << std::endl;
    return nullptr;
  }

  const LoadedPackage* target_pkg = GetPackageAtIndex0(*target_arsc);
  if (target_pkg == nullptr) {
    out_error << "error: failed to load target package from resources.arsc" << std::endl;
    return nullptr;
  }

  const LoadedPackage* overlay_pkg = GetPackageAtIndex0(*overlay_arsc);
  if (overlay_pkg == nullptr) {
    out_error << "error: failed to load overlay package from resources.arsc" << std::endl;
    return nullptr;
  }

  const std::unique_ptr<const ZipFile> target_zip = ZipFile::Open(target_apk_path);
  if (!target_zip) {
    out_error << "error: failed to open target as zip" << std::endl;
    return nullptr;
  }

  const std::unique_ptr<const ZipFile> overlay_zip = ZipFile::Open(overlay_apk_path);
  if (!overlay_zip) {
    out_error << "error: failed to open overlay as zip" << std::endl;
    return nullptr;
  }

  Result<utils::OverlayManifestInfo> overlay_info =
      utils::ExtractOverlayManifestInfo(overlay_apk_path, out_error);
  if (!overlay_info) {
    return nullptr;
  }

  std::unique_ptr<IdmapHeader> header(new IdmapHeader());
  header->magic_ = kIdmapMagic;
  header->version_ = kIdmapCurrentVersion;

  Result<uint32_t> crc = target_zip->Crc("resources.arsc");
  if (!crc) {
    out_error << "error: failed to get zip crc for target" << std::endl;
    return nullptr;
  }
  header->target_crc_ = *crc;

  crc = overlay_zip->Crc("resources.arsc");
  if (!crc) {
    out_error << "error: failed to get zip crc for overlay" << std::endl;
    return nullptr;
  }
  header->overlay_crc_ = *crc;

  if (target_apk_path.size() > sizeof(header->target_path_)) {
    out_error << "error: target apk path \"" << target_apk_path << "\" longer that maximum size "
              << sizeof(header->target_path_) << std::endl;
    return nullptr;
  }
  memset(header->target_path_, 0, sizeof(header->target_path_));
  memcpy(header->target_path_, target_apk_path.data(), target_apk_path.size());

  if (overlay_apk_path.size() > sizeof(header->overlay_path_)) {
    out_error << "error: overlay apk path \"" << overlay_apk_path << "\" longer that maximum size "
              << sizeof(header->overlay_path_) << std::endl;
    return nullptr;
  }
  memset(header->overlay_path_, 0, sizeof(header->overlay_path_));
  memcpy(header->overlay_path_, overlay_apk_path.data(), overlay_apk_path.size());

  std::unique_ptr<Idmap> idmap(new Idmap());
  idmap->header_ = std::move(header);

  // find the resources that exist in both packages
  MatchingResources matching_resources;
  const auto end = overlay_pkg->end();
  for (auto iter = overlay_pkg->begin(); iter != end; ++iter) {
    const ResourceId overlay_resid = *iter;
    Result<std::string> name = utils::ResToTypeEntryName(overlay_asset_manager, overlay_resid);
    if (!name) {
      continue;
    }
    // prepend "<package>:" to turn name into "<package>:<type>/<name>"
    const std::string full_name =
        base::StringPrintf("%s:%s", target_pkg->GetPackageName().c_str(), name->c_str());
    const ResourceId target_resid = NameToResid(target_asset_manager, full_name);
    if (target_resid == 0) {
      continue;
    }

    if (enforce_overlayable &&
        !CheckOverlayable(*target_pkg, *overlay_info, fulfilled_policies, target_resid)) {
      LOG(WARNING) << "overlay \"" << overlay_apk_path << "\" is not allowed to overlay resource \""
                   << full_name << "\"" << std::endl;
      continue;
    }

    matching_resources.Add(target_resid, overlay_resid);
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
  data_header->target_package_id_ = target_pkg->GetPackageId();
  data_header->type_count_ = data->type_entries_.size();
  data->header_ = std::move(data_header);

  idmap->data_.push_back(std::move(data));

  return std::move(idmap);
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
