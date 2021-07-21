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

#include "idmap2/FabricatedOverlay.h"

#include <androidfw/ResourceUtils.h>
#include <google/protobuf/io/coded_stream.h>
#include <google/protobuf/io/zero_copy_stream_impl_lite.h>
#include <utils/ByteOrder.h>
#include <zlib.h>

#include <fstream>
#include <map>
#include <memory>
#include <string>
#include <utility>

namespace android::idmap2 {

namespace {
bool Read32(std::istream& stream, uint32_t* out) {
  uint32_t value;
  if (stream.read(reinterpret_cast<char*>(&value), sizeof(uint32_t))) {
    *out = dtohl(value);
    return true;
  }
  return false;
}

void Write32(std::ostream& stream, uint32_t value) {
  uint32_t x = htodl(value);
  stream.write(reinterpret_cast<char*>(&x), sizeof(uint32_t));
}
}  // namespace

FabricatedOverlay::FabricatedOverlay(pb::FabricatedOverlay&& overlay,
                                     std::optional<uint32_t> crc_from_disk)
    : overlay_pb_(std::forward<pb::FabricatedOverlay>(overlay)), crc_from_disk_(crc_from_disk) {
}

FabricatedOverlay::Builder::Builder(const std::string& package_name, const std::string& name,
                                    const std::string& target_package_name) {
  package_name_ = package_name;
  name_ = name;
  target_package_name_ = target_package_name;
}

FabricatedOverlay::Builder& FabricatedOverlay::Builder::SetOverlayable(const std::string& name) {
  target_overlayable_ = name;
  return *this;
}

FabricatedOverlay::Builder& FabricatedOverlay::Builder::SetResourceValue(
    const std::string& resource_name, uint8_t data_type, uint32_t data_value) {
  entries_.emplace_back(Entry{resource_name, data_type, data_value});
  return *this;
}

Result<FabricatedOverlay> FabricatedOverlay::Builder::Build() {
  std::map<std::string, std::map<std::string, std::map<std::string, TargetValue>>> entries;
  for (const auto& res_entry : entries_) {
    StringPiece package_substr;
    StringPiece type_name;
    StringPiece entry_name;
    if (!android::ExtractResourceName(StringPiece(res_entry.resource_name), &package_substr,
                                      &type_name, &entry_name)) {
      return Error("failed to parse resource name '%s'", res_entry.resource_name.c_str());
    }

    std::string package_name =
        package_substr.empty() ? target_package_name_ : package_substr.to_string();
    if (type_name.empty()) {
      return Error("resource name '%s' missing type name", res_entry.resource_name.c_str());
    }

    if (entry_name.empty()) {
      return Error("resource name '%s' missing entry name", res_entry.resource_name.c_str());
    }

    auto package = entries.find(package_name);
    if (package == entries.end()) {
      package = entries
                    .insert(std::make_pair(
                        package_name, std::map<std::string, std::map<std::string, TargetValue>>()))
                    .first;
    }

    auto type = package->second.find(type_name.to_string());
    if (type == package->second.end()) {
      type =
          package->second
              .insert(std::make_pair(type_name.to_string(), std::map<std::string, TargetValue>()))
              .first;
    }

    auto entry = type->second.find(entry_name.to_string());
    if (entry == type->second.end()) {
      entry = type->second.insert(std::make_pair(entry_name.to_string(), TargetValue())).first;
    }

    entry->second = TargetValue{res_entry.data_type, res_entry.data_value};
  }

  pb::FabricatedOverlay overlay_pb;
  overlay_pb.set_package_name(package_name_);
  overlay_pb.set_name(name_);
  overlay_pb.set_target_package_name(target_package_name_);
  overlay_pb.set_target_overlayable(target_overlayable_);

  for (const auto& package : entries) {
    auto package_pb = overlay_pb.add_packages();
    package_pb->set_name(package.first);

    for (const auto& type : package.second) {
      auto type_pb = package_pb->add_types();
      type_pb->set_name(type.first);

      for (const auto& entry : type.second) {
        auto entry_pb = type_pb->add_entries();
        entry_pb->set_name(entry.first);
        pb::ResourceValue* value = entry_pb->mutable_res_value();
        value->set_data_type(entry.second.data_type);
        value->set_data_value(entry.second.data_value);
      }
    }
  }

  return FabricatedOverlay(std::move(overlay_pb));
}

Result<FabricatedOverlay> FabricatedOverlay::FromBinaryStream(std::istream& stream) {
  uint32_t magic;
  if (!Read32(stream, &magic)) {
    return Error("Failed to read fabricated overlay magic.");
  }

  if (magic != kFabricatedOverlayMagic) {
    return Error("Not a fabricated overlay file.");
  }

  uint32_t version;
  if (!Read32(stream, &version)) {
    return Error("Failed to read fabricated overlay version.");
  }

  if (version != 1) {
    return Error("Invalid fabricated overlay version '%u'.", version);
  }

  uint32_t crc;
  if (!Read32(stream, &crc)) {
    return Error("Failed to read fabricated overlay version.");
  }

  pb::FabricatedOverlay overlay{};
  if (!overlay.ParseFromIstream(&stream)) {
    return Error("Failed read fabricated overlay proto.");
  }

  // If the proto version is the latest version, then the contents of the proto must be the same
  // when the proto is re-serialized; otherwise, the crc must be calculated because migrating the
  // proto to the latest version will likely change the contents of the fabricated overlay.
  return FabricatedOverlay(std::move(overlay), version == kFabricatedOverlayCurrentVersion
                                                   ? std::optional<uint32_t>(crc)
                                                   : std::nullopt);
}

Result<FabricatedOverlay::SerializedData*> FabricatedOverlay::InitializeData() const {
  if (!data_.has_value()) {
    auto size = overlay_pb_.ByteSizeLong();
    auto data = std::unique_ptr<uint8_t[]>(new uint8_t[size]);

    // Ensure serialization is deterministic
    google::protobuf::io::ArrayOutputStream array_stream(data.get(), size);
    google::protobuf::io::CodedOutputStream output_stream(&array_stream);
    output_stream.SetSerializationDeterministic(true);
    overlay_pb_.SerializeWithCachedSizes(&output_stream);
    if (output_stream.HadError() || size != output_stream.ByteCount()) {
      return Error("Failed to serialize fabricated overlay.");
    }

    // Calculate the crc using the proto data and the version.
    uint32_t crc = crc32(0L, Z_NULL, 0);
    crc = crc32(crc, reinterpret_cast<const uint8_t*>(&kFabricatedOverlayCurrentVersion),
                sizeof(uint32_t));
    crc = crc32(crc, data.get(), size);
    data_ = SerializedData{std::move(data), size, crc};
  }
  return &(*data_);
}
Result<uint32_t> FabricatedOverlay::GetCrc() const {
  if (crc_from_disk_.has_value()) {
    return *crc_from_disk_;
  }
  auto data = InitializeData();
  if (!data) {
    return data.GetError();
  }
  return (*data)->crc;
}

Result<Unit> FabricatedOverlay::ToBinaryStream(std::ostream& stream) const {
  auto data = InitializeData();
  if (!data) {
    return data.GetError();
  }

  Write32(stream, kFabricatedOverlayMagic);
  Write32(stream, kFabricatedOverlayCurrentVersion);
  Write32(stream, (*data)->crc);
  stream.write(reinterpret_cast<const char*>((*data)->data.get()), (*data)->data_size);
  if (stream.bad()) {
    return Error("Failed to write serialized fabricated overlay.");
  }

  return Unit{};
}

using FabContainer = FabricatedOverlayContainer;
FabContainer::FabricatedOverlayContainer(FabricatedOverlay&& overlay, std::string&& path)
    : overlay_(std::forward<FabricatedOverlay>(overlay)), path_(std::forward<std::string>(path)) {
}

FabContainer::~FabricatedOverlayContainer() = default;

Result<std::unique_ptr<FabContainer>> FabContainer::FromPath(std::string path) {
  std::fstream fin(path);
  auto overlay = FabricatedOverlay::FromBinaryStream(fin);
  if (!overlay) {
    return overlay.GetError();
  }
  return std::unique_ptr<FabContainer>(
      new FabricatedOverlayContainer(std::move(*overlay), std::move(path)));
}

std::unique_ptr<FabricatedOverlayContainer> FabContainer::FromOverlay(FabricatedOverlay&& overlay) {
  return std::unique_ptr<FabContainer>(
      new FabricatedOverlayContainer(std::move(overlay), {} /* path */));
}

OverlayManifestInfo FabContainer::GetManifestInfo() const {
  const pb::FabricatedOverlay& overlay_pb = overlay_.overlay_pb_;
  return OverlayManifestInfo{
      .package_name = overlay_pb.package_name(),
      .name = overlay_pb.name(),
      .target_package = overlay_pb.target_package_name(),
      .target_name = overlay_pb.target_overlayable(),
  };
}

Result<OverlayManifestInfo> FabContainer::FindOverlayInfo(const std::string& name) const {
  const OverlayManifestInfo info = GetManifestInfo();
  if (name != info.name) {
    return Error("Failed to find name '%s' in fabricated overlay", name.c_str());
  }
  return info;
}

Result<OverlayData> FabContainer::GetOverlayData(const OverlayManifestInfo& info) const {
  const pb::FabricatedOverlay& overlay_pb = overlay_.overlay_pb_;
  if (info.name != overlay_pb.name()) {
    return Error("Failed to find name '%s' in fabricated overlay", info.name.c_str());
  }

  OverlayData result{};
  for (const auto& package : overlay_pb.packages()) {
    for (const auto& type : package.types()) {
      for (const auto& entry : type.entries()) {
        auto name = base::StringPrintf("%s:%s/%s", package.name().c_str(), type.name().c_str(),
                                       entry.name().c_str());
        const auto& res_value = entry.res_value();
        result.pairs.emplace_back(OverlayData::Value{
            name, TargetValue{.data_type = static_cast<uint8_t>(res_value.data_type()),
                              .data_value = res_value.data_value()}});
      }
    }
  }
  return result;
}

Result<uint32_t> FabContainer::GetCrc() const {
  return overlay_.GetCrc();
}

const std::string& FabContainer::GetPath() const {
  return path_;
}

Result<std::string> FabContainer::GetResourceName(ResourceId /* id */) const {
  return Error("Fabricated overlay does not contain resources.");
}

}  // namespace android::idmap2
