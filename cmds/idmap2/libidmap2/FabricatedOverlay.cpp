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

#include <sys/stat.h>   // umask
#include <sys/types.h>  // umask

#include <android-base/file.h>
#include <android-base/strings.h>
#include <androidfw/BigBuffer.h>
#include <androidfw/BigBufferStream.h>
#include <androidfw/FileStream.h>
#include <androidfw/Image.h>
#include <androidfw/Png.h>
#include <androidfw/ResourceUtils.h>
#include <androidfw/StringPiece.h>
#include <androidfw/StringPool.h>
#include <androidfw/Streams.h>
#include <google/protobuf/io/coded_stream.h>
#include <google/protobuf/io/zero_copy_stream_impl.h>
#include <utils/ByteOrder.h>
#include <zlib.h>

#include <fstream>
#include <map>
#include <memory>
#include <string>
#include <utility>
#include <sys/utsname.h>

namespace android::idmap2 {
constexpr auto kBufferSize = 1024;

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
                                     std::string&& string_pool_data,
                                     std::vector<FabricatedOverlay::BinaryData> binary_files,
                                     off_t total_binary_bytes,
                                     std::optional<uint32_t> crc_from_disk)
    : overlay_pb_(std::forward<pb::FabricatedOverlay>(overlay)),
    string_pool_data_(std::move(string_pool_data)),
    binary_files_(std::move(binary_files)),
    total_binary_bytes_(total_binary_bytes),
    crc_from_disk_(crc_from_disk) {
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
    const std::string& resource_name, uint8_t data_type, uint32_t data_value,
    const std::string& configuration) {
  entries_.emplace_back(
      Entry{resource_name, data_type, data_value, "", std::nullopt, 0, 0, configuration, false});
  return *this;
}

FabricatedOverlay::Builder& FabricatedOverlay::Builder::SetResourceValue(
    const std::string& resource_name, uint8_t data_type, const std::string& data_string_value,
    const std::string& configuration) {
  entries_.emplace_back(
      Entry{resource_name,
            data_type,
            0,
            data_string_value,
            std::nullopt,
            0,
            0,
            configuration,
            false});
  return *this;
}

FabricatedOverlay::Builder& FabricatedOverlay::Builder::SetResourceValue(
    const std::string& resource_name, std::optional<android::base::borrowed_fd>&& binary_value,
    off64_t data_binary_offset, size_t data_binary_size, const std::string& configuration,
    bool nine_patch) {
  entries_.emplace_back(Entry{resource_name, 0, 0, "", binary_value,
                              data_binary_offset, data_binary_size, configuration, nine_patch});
  return *this;
}

static Result<FabricatedOverlay::BinaryData> buildBinaryData(
        pb::ResourceValue* pb_value, const TargetValue &value) {
  pb_value->set_data_type(Res_value::TYPE_STRING);
  size_t binary_size;
  off64_t binary_offset;
  std::unique_ptr<android::InputStream> binary_stream;

  if (value.nine_patch) {
    std::string file_contents;
    file_contents.resize(value.data_binary_size);
    if (!base::ReadFullyAtOffset(value.data_binary_value->get(), file_contents.data(),
                                 value.data_binary_size, value.data_binary_offset)) {
      return Error("Failed to read binary file data.");
    }
    const StringPiece content(file_contents.c_str(), file_contents.size());
    android::PngChunkFilter png_chunk_filter(content);
    android::AndroidLogDiagnostics diag;
    auto png = android::ReadPng(&png_chunk_filter, &diag);
    if (!png) {
      return Error("Error opening file as png");
    }

    std::string err;
    std::unique_ptr<NinePatch> nine_patch = NinePatch::Create(png->rows.get(),
                                                              png->width, png->height,
                                                              &err);
    if (!nine_patch) {
      return Error("%s", err.c_str());
    }

    png->width -= 2;
    png->height -= 2;
    memmove(png->rows.get(), png->rows.get() + 1, png->height * sizeof(uint8_t**));
    for (int32_t h = 0; h < png->height; h++) {
      memmove(png->rows[h], png->rows[h] + 4, png->width * 4);
    }

    android::BigBuffer buffer(value.data_binary_size);
    android::BigBufferOutputStream buffer_output_stream(&buffer);
    if (!android::WritePng(png.get(), nine_patch.get(), &buffer_output_stream, {},
                           &diag, false)) {
      return Error("Error writing frro png");
    }

    binary_size = buffer.size();
    binary_offset = 0;
    android::BigBufferInputStream *buffer_input_stream
            = new android::BigBufferInputStream(std::move(buffer));
    binary_stream.reset(buffer_input_stream);
  } else {
    binary_size = value.data_binary_size;
    binary_offset = value.data_binary_offset;
    android::FileInputStream *fis
            = new android::FileInputStream(value.data_binary_value.value());
    binary_stream.reset(fis);
  }

  return FabricatedOverlay::BinaryData{
          std::move(binary_stream),
          binary_offset,
          binary_size};
}

Result<FabricatedOverlay> FabricatedOverlay::Builder::Build() {
  using ConfigMap = std::map<std::string, TargetValue, std::less<>>;
  using EntryMap = std::map<std::string, ConfigMap, std::less<>>;
  using TypeMap = std::map<std::string, EntryMap, std::less<>>;
  using PackageMap = std::map<std::string, TypeMap, std::less<>>;
  PackageMap package_map;
  android::StringPool string_pool;
  for (const auto& res_entry : entries_) {
    StringPiece package_substr;
    StringPiece type_name;
    StringPiece entry_name;
    if (!android::ExtractResourceName(StringPiece(res_entry.resource_name), &package_substr,
                                      &type_name, &entry_name)) {
      return Error("failed to parse resource name '%s'", res_entry.resource_name.c_str());
    }

    std::string_view package_name = package_substr.empty() ? target_package_name_ : package_substr;
    if (type_name.empty()) {
      return Error("resource name '%s' missing type name", res_entry.resource_name.c_str());
    }

    if (entry_name.empty()) {
      return Error("resource name '%s' missing entry name", res_entry.resource_name.c_str());
    }

    auto package = package_map.find(package_name);
    if (package == package_map.end()) {
      package = package_map
                    .insert(std::make_pair(package_name, TypeMap()))
                    .first;
    }

    auto type = package->second.find(type_name);
    if (type == package->second.end()) {
      type = package->second.insert(std::make_pair(type_name, EntryMap())).first;
    }

    auto entry = type->second.find(entry_name);
    if (entry == type->second.end()) {
      entry = type->second.insert(std::make_pair(entry_name, ConfigMap())).first;
    }

    auto value = entry->second.find(res_entry.configuration);
    if (value == entry->second.end()) {
      value = entry->second.insert(std::make_pair(res_entry.configuration, TargetValue())).first;
    }

    value->second = TargetValue{res_entry.data_type, res_entry.data_value,
                                res_entry.data_string_value, res_entry.data_binary_value,
                                res_entry.data_binary_offset, res_entry.data_binary_size,
                                res_entry.nine_patch};
  }

  pb::FabricatedOverlay overlay_pb;
  overlay_pb.set_package_name(package_name_);
  overlay_pb.set_name(name_);
  overlay_pb.set_target_package_name(target_package_name_);
  overlay_pb.set_target_overlayable(target_overlayable_);

  std::vector<FabricatedOverlay::BinaryData> binary_files;
  size_t total_binary_bytes = 0;
  // 16 for the number of bytes in the frro file before the binary data
  const size_t FRRO_HEADER_SIZE = 16;

  for (auto& package : package_map) {
    auto package_pb = overlay_pb.add_packages();
    package_pb->set_name(package.first);

    for (auto& type : package.second) {
      auto type_pb = package_pb->add_types();
      type_pb->set_name(type.first);

      for (auto& entry : type.second) {
        for (const auto& value: entry.second) {
          auto entry_pb = type_pb->add_entries();
          entry_pb->set_name(entry.first);
          entry_pb->set_configuration(value.first);
          pb::ResourceValue* pb_value = entry_pb->mutable_res_value();
          pb_value->set_data_type(value.second.data_type);
          if (value.second.data_type == Res_value::TYPE_STRING) {
            auto ref = string_pool.MakeRef(value.second.data_string_value);
            pb_value->set_data_value(ref.index());
          } else if (value.second.data_binary_value.has_value()) {
            auto binary_data = buildBinaryData(pb_value, value.second);
            if (!binary_data) {
              return binary_data.GetError();
            }
            pb_value->set_data_type(Res_value::TYPE_STRING);

            std::string uri
                = StringPrintf("frro:/%s?offset=%d&size=%d", frro_path_.c_str(),
                               static_cast<int> (FRRO_HEADER_SIZE + total_binary_bytes),
                               static_cast<int> (binary_data->size));
            total_binary_bytes += binary_data->size;
            binary_files.emplace_back(std::move(*binary_data));
            auto ref = string_pool.MakeRef(std::move(uri));
            pb_value->set_data_value(ref.index());
          } else {
            pb_value->set_data_value(value.second.data_value);
          }
        }
      }
    }
  }
  android::BigBuffer string_buffer(kBufferSize);
  android::StringPool::FlattenUtf8(&string_buffer, string_pool, nullptr);
  return FabricatedOverlay(std::move(overlay_pb), string_buffer.to_string(),
      std::move(binary_files), total_binary_bytes);
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

  if (version < 1 || version > 3) {
    return Error("Invalid fabricated overlay version '%u'.", version);
  }

  uint32_t crc;
  if (!Read32(stream, &crc)) {
    return Error("Failed to read fabricated overlay crc.");
  }

  pb::FabricatedOverlay overlay{};
  std::string sp_data;
  uint32_t total_binary_bytes;
  if (version == 3) {
    if (!Read32(stream, &total_binary_bytes)) {
      return Error("Failed read total binary bytes.");
    }
    stream.seekg(total_binary_bytes, std::istream::cur);
  }
  if (version >= 2) {
    uint32_t sp_size;
    if (!Read32(stream, &sp_size)) {
      return Error("Failed read string pool size.");
    }
    std::string buf(sp_size, '\0');
    if (!stream.read(buf.data(), sp_size)) {
      return Error("Failed to read string pool.");
    }
    sp_data = buf;
  }
  if (!overlay.ParseFromIstream(&stream)) {
    return Error("Failed read fabricated overlay proto.");
  }

  // If the proto version is the latest version, then the contents of the proto must be the same
  // when the proto is re-serialized; otherwise, the crc must be calculated because migrating the
  // proto to the latest version will likely change the contents of the fabricated overlay.
  return FabricatedOverlay(std::move(overlay), std::move(sp_data), {}, total_binary_bytes,
                           version == kFabricatedOverlayCurrentVersion
                                                   ? std::optional<uint32_t>(crc)
                                                   : std::nullopt);
}

Result<FabricatedOverlay::SerializedData*> FabricatedOverlay::InitializeData() const {
  if (!data_.has_value()) {
    auto pb_size = overlay_pb_.ByteSizeLong();
    auto pb_data = std::unique_ptr<uint8_t[]>(new uint8_t[pb_size]);

    // Ensure serialization is deterministic
    google::protobuf::io::ArrayOutputStream array_stream(pb_data.get(), pb_size);
    google::protobuf::io::CodedOutputStream output_stream(&array_stream);
    output_stream.SetSerializationDeterministic(true);
    overlay_pb_.SerializeWithCachedSizes(&output_stream);
    if (output_stream.HadError() || pb_size != output_stream.ByteCount()) {
      return Error("Failed to serialize fabricated overlay.");
    }

    // Calculate the crc using the proto data and the version.
    uint32_t pb_crc = crc32(0L, Z_NULL, 0);
    pb_crc = crc32(pb_crc, reinterpret_cast<const uint8_t*>(&kFabricatedOverlayCurrentVersion),
                sizeof(uint32_t));
    pb_crc = crc32(pb_crc, pb_data.get(), pb_size);

    data_ = SerializedData{std::move(pb_data), pb_size, pb_crc, string_pool_data_};
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
  return (*data)->pb_crc;
}

Result<Unit> FabricatedOverlay::ToBinaryStream(std::ostream& stream) const {
  auto data = InitializeData();
  if (!data) {
    return data.GetError();
  }

  Write32(stream, kFabricatedOverlayMagic);
  Write32(stream, kFabricatedOverlayCurrentVersion);
  Write32(stream, (*data)->pb_crc);
  Write32(stream, total_binary_bytes_);
  std::string file_contents;
  for (const FabricatedOverlay::BinaryData& bd : binary_files_) {
    file_contents.resize(bd.size);
    if (!bd.input_stream->ReadFullyAtOffset(file_contents.data(), bd.size, bd.offset)) {
      return Error("Failed to read binary file data.");
    }
    stream.write(file_contents.data(), file_contents.length());
  }
  Write32(stream, (*data)->sp_data.length());
  stream.write((*data)->sp_data.data(), (*data)->sp_data.length());
  if (stream.bad()) {
    return Error("Failed to write string pool data.");
  }
  stream.write(reinterpret_cast<const char*>((*data)->pb_data.get()), (*data)->pb_data_size);
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
            name, TargetValueWithConfig{.value = TargetValue{
                    .data_type = static_cast<uint8_t>(res_value.data_type()),
                    .data_value = res_value.data_value()}, .config = entry.configuration()}});
      }
    }
  }
  const uint32_t string_pool_data_length = overlay_.string_pool_data_.length();
  result.string_pool_data = OverlayData::InlineStringPoolData{
      .data = std::unique_ptr<uint8_t[]>(new uint8_t[string_pool_data_length]),
      .data_length = string_pool_data_length,
      .string_pool_offset = 0,
  };
  memcpy(result.string_pool_data->data.get(), overlay_.string_pool_data_.data(),
       string_pool_data_length);
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
