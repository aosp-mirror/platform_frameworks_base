/*
 * Copyright (C) 2017 The Android Open Source Project
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

#include "androidfw/Idmap.h"

#include "android-base/file.h"
#include "android-base/logging.h"
#include "android-base/stringprintf.h"
#include "android-base/utf8.h"
#include "androidfw/misc.h"
#include "androidfw/ResourceTypes.h"
#include "androidfw/Util.h"
#include "utils/ByteOrder.h"
#include "utils/Trace.h"

#ifdef _WIN32
#ifdef ERROR
#undef ERROR
#endif
#endif

using ::android::base::StringPrintf;

namespace android {

// See frameworks/base/cmds/idmap2/include/idmap2/Idmap.h for full idmap file format specification.
struct Idmap_header {
  // Always 0x504D4449 ('IDMP')
  uint32_t magic;
  uint32_t version;

  uint32_t target_crc32;
  uint32_t overlay_crc32;

  uint32_t fulfilled_policies;
  uint32_t enforce_overlayable;

  // overlay_path, target_path, and other string values encoded in the idmap header and read and
  // stored in separate structures. This allows the idmap header data to be casted to this struct
  // without having to read/store each header entry separately.
};

struct Idmap_data_header {
  uint32_t target_entry_count;
  uint32_t target_inline_entry_count;
  uint32_t target_inline_entry_value_count;
  uint32_t configuration_count;
  uint32_t overlay_entry_count;

  uint32_t string_pool_index_offset;
};

struct Idmap_target_entry_inline {
  uint32_t start_value_index;
  uint32_t value_count;
};

struct Idmap_target_entry_inline_value {
  uint32_t config_index;
  Res_value value;
};

static constexpr uint32_t convert_dev_target_id(uint32_t dev_target_id) {
  return (0x00FFFFFFU & dtohl(dev_target_id));
}

OverlayStringPool::OverlayStringPool(const LoadedIdmap* loaded_idmap)
    : data_header_(loaded_idmap->data_header_),
      idmap_string_pool_(loaded_idmap->string_pool_.get()) { };

OverlayStringPool::~OverlayStringPool() {
  uninit();
}

base::expected<StringPiece16, NullOrIOError> OverlayStringPool::stringAt(size_t idx) const {
  const size_t offset = dtohl(data_header_->string_pool_index_offset);
  if (idmap_string_pool_ != nullptr && idx >= ResStringPool::size() && idx >= offset) {
    return idmap_string_pool_->stringAt(idx - offset);
  }

  return ResStringPool::stringAt(idx);
}

base::expected<StringPiece, NullOrIOError> OverlayStringPool::string8At(size_t idx) const {
  const size_t offset = dtohl(data_header_->string_pool_index_offset);
  if (idmap_string_pool_ != nullptr && idx >= ResStringPool::size() && idx >= offset) {
    return idmap_string_pool_->string8At(idx - offset);
  }

  return ResStringPool::string8At(idx);
}

size_t OverlayStringPool::size() const {
  return ResStringPool::size() + (idmap_string_pool_ != nullptr ? idmap_string_pool_->size() : 0U);
}

OverlayDynamicRefTable::OverlayDynamicRefTable(const Idmap_data_header* data_header,
                                               Idmap_overlay_entries entries,
                                               uint8_t target_assigned_package_id)
    : data_header_(data_header),
      entries_(entries),
      target_assigned_package_id_(target_assigned_package_id) {
}

status_t OverlayDynamicRefTable::lookupResourceId(uint32_t* resId) const {
  const auto count = dtohl(data_header_->overlay_entry_count);
  const auto overlay_it_end = entries_.overlay_id + count;
  const auto entry_it = std::lower_bound(entries_.overlay_id, overlay_it_end, *resId,
                                         [](uint32_t dev_overlay_id, uint32_t overlay_id) {
                                           return dtohl(dev_overlay_id) < overlay_id;
                                         });

  if (entry_it == overlay_it_end || dtohl(*entry_it) != *resId) {
    // A mapping for the target resource id could not be found.
    return DynamicRefTable::lookupResourceId(resId);
  }

  const auto index = entry_it - entries_.overlay_id;
  *resId = convert_dev_target_id(entries_.target_id[index]) |
           (((uint32_t)target_assigned_package_id_) << 24U);
  return NO_ERROR;
}

status_t OverlayDynamicRefTable::lookupResourceIdNoRewrite(uint32_t* resId) const {
  return DynamicRefTable::lookupResourceId(resId);
}

IdmapResMap::IdmapResMap(const Idmap_data_header* data_header, Idmap_target_entries entries,
                         Idmap_target_inline_entries inline_entries,
                         const Idmap_target_entry_inline_value* inline_entry_values,
                         const ConfigDescription* configs, uint8_t target_assigned_package_id,
                         const OverlayDynamicRefTable* overlay_ref_table)
    : data_header_(data_header),
      entries_(entries),
      inline_entries_(inline_entries),
      inline_entry_values_(inline_entry_values),
      configurations_(configs),
      target_assigned_package_id_(target_assigned_package_id),
      overlay_ref_table_(overlay_ref_table) {
}

IdmapResMap::Result IdmapResMap::Lookup(uint32_t target_res_id) const {
  if ((target_res_id >> 24U) != target_assigned_package_id_) {
    // The resource id must have the same package id as the target package.
    return {};
  }

  // The resource ids encoded within the idmap are build-time resource ids so do not consider the
  // package id when determining if the resource in the target package is overlaid.
  target_res_id &= 0x00FFFFFFU;

  // Check if the target resource is mapped to an overlay resource.
  const auto target_end = entries_.target_id + dtohl(data_header_->target_entry_count);
  auto target_it = std::lower_bound(entries_.target_id, target_end, target_res_id,
                                    [](uint32_t dev_target_id, uint32_t target_id) {
                                      return convert_dev_target_id(dev_target_id) < target_id;
                                    });

  if (target_it != target_end && convert_dev_target_id(*target_it) == target_res_id) {
    const auto index = target_it - entries_.target_id;
    uint32_t overlay_resource_id = dtohl(entries_.overlay_id[index]);
    // Lookup the resource without rewriting the overlay resource id back to the target resource id
    // being looked up.
    overlay_ref_table_->lookupResourceIdNoRewrite(&overlay_resource_id);
    return Result(overlay_resource_id);
  }

  // Check if the target resources is mapped to an inline table entry.
  const auto inline_entry_target_end =
      inline_entries_.target_id + dtohl(data_header_->target_inline_entry_count);
  const auto inline_entry_target_it =
      std::lower_bound(inline_entries_.target_id, inline_entry_target_end, target_res_id,
                       [](uint32_t dev_target_id, uint32_t target_id) {
                         return convert_dev_target_id(dev_target_id) < target_id;
                       });

  if (inline_entry_target_it != inline_entry_target_end &&
      convert_dev_target_id(*inline_entry_target_it) == target_res_id) {
    const auto index = inline_entry_target_it - inline_entries_.target_id;
    std::map<ConfigDescription, Res_value> values_map;
    const auto& inline_entry = inline_entries_.entry[index];
    for (int i = 0; i < dtohl(inline_entry.value_count); i++) {
      const auto& value = inline_entry_values_[dtohl(inline_entry.start_value_index) + i];
      const auto& config = configurations_[dtohl(value.config_index)];
      values_map[config] = value.value;
    }
    return Result(std::move(values_map));
  }
  return {};
}

namespace {
template <typename T>
const T* ReadType(const uint8_t** in_out_data_ptr, size_t* in_out_size, const char* label,
                  size_t count = 1) {
  if (!util::IsFourByteAligned(*in_out_data_ptr)) {
    LOG(ERROR) << "Idmap " << label << " in " << __func__ << " is not word aligned.";
    return {};
  }
  if ((*in_out_size / sizeof(T)) < count) {
    LOG(ERROR) << "Idmap too small for the number of " << label << " in " << __func__
               << " entries (" << count << ").";
    return nullptr;
  }
  auto data_ptr = *in_out_data_ptr;
  const size_t read_size = sizeof(T) * count;
  *in_out_data_ptr += read_size;
  *in_out_size -= read_size;
  return reinterpret_cast<const T*>(data_ptr);
}

std::optional<std::string_view> ReadString(const uint8_t** in_out_data_ptr, size_t* in_out_size,
                                           const char* label) {
  const auto* len = ReadType<uint32_t>(in_out_data_ptr, in_out_size, label);
  if (len == nullptr) {
    return {};
  }
  const auto* data = ReadType<char>(in_out_data_ptr, in_out_size, label, *len);
  if (data == nullptr) {
    return {};
  }
  // Strings are padded to the next 4 byte boundary.
  const uint32_t padding_size = (4U - ((size_t)*in_out_data_ptr & 0x3U)) % 4U;
  for (uint32_t i = 0; i < padding_size; i++) {
    if (**in_out_data_ptr != 0) {
      LOG(ERROR) << " Idmap padding of " << label << " in " << __func__ << " is non-zero.";
      return {};
    }
    *in_out_data_ptr += sizeof(uint8_t);
    *in_out_size -= sizeof(uint8_t);
  }
  return std::string_view(data, *len);
}
} // namespace

// O_PATH is a lightweight way of creating an FD, only exists on Linux
#ifndef O_PATH
#define O_PATH (0)
#endif

LoadedIdmap::LoadedIdmap(const std::string& idmap_path, const Idmap_header* header,
                         const Idmap_data_header* data_header, Idmap_target_entries target_entries,
                         Idmap_target_inline_entries target_inline_entries,
                         const Idmap_target_entry_inline_value* inline_entry_values,
                         const ConfigDescription* configs, Idmap_overlay_entries overlay_entries,
                         std::unique_ptr<ResStringPool>&& string_pool,
                         std::string_view overlay_apk_path, std::string_view target_apk_path)
    : header_(header),
      data_header_(data_header),
      target_entries_(target_entries),
      target_inline_entries_(target_inline_entries),
      inline_entry_values_(inline_entry_values),
      configurations_(configs),
      overlay_entries_(overlay_entries),
      string_pool_(std::move(string_pool)),
      idmap_fd_(
          android::base::utf8::open(idmap_path.c_str(), O_RDONLY | O_CLOEXEC | O_BINARY | O_PATH)),
      overlay_apk_path_(overlay_apk_path),
      target_apk_path_(target_apk_path),
      idmap_last_mod_time_(getFileModDate(idmap_fd_.get())) {
}

std::unique_ptr<LoadedIdmap> LoadedIdmap::Load(StringPiece idmap_path, StringPiece idmap_data) {
  ATRACE_CALL();
  size_t data_size = idmap_data.size();
  auto data_ptr = reinterpret_cast<const uint8_t*>(idmap_data.data());

  // Parse the idmap header
  auto header = ReadType<Idmap_header>(&data_ptr, &data_size, "header");
  if (header == nullptr) {
    return {};
  }
  if (dtohl(header->magic) != kIdmapMagic) {
    LOG(ERROR) << StringPrintf("Invalid Idmap file: bad magic value (was 0x%08x, expected 0x%08x)",
                               dtohl(header->magic), kIdmapMagic);
    return {};
  }
  if (dtohl(header->version) != kIdmapCurrentVersion) {
    // We are strict about versions because files with this format are generated at runtime and
    // don't need backwards compatibility.
    LOG(ERROR) << StringPrintf("Version mismatch in Idmap (was 0x%08x, expected 0x%08x)",
                               dtohl(header->version), kIdmapCurrentVersion);
    return {};
  }
  std::optional<std::string_view> target_path = ReadString(&data_ptr, &data_size, "target path");
    if (!target_path) {
      return {};
    }
  std::optional<std::string_view> overlay_path = ReadString(&data_ptr, &data_size, "overlay path");
  if (!overlay_path) {
    return {};
  }
  if (!ReadString(&data_ptr, &data_size, "target name") ||
      !ReadString(&data_ptr, &data_size, "debug info")) {
    return {};
  }

  // Parse the idmap data blocks. Currently idmap2 can only generate one data block.
  auto data_header = ReadType<Idmap_data_header>(&data_ptr, &data_size, "data header");
  if (data_header == nullptr) {
    return {};
  }
  Idmap_target_entries target_entries{
      .target_id = ReadType<uint32_t>(&data_ptr, &data_size, "entries.target_id",
                                      dtohl(data_header->target_entry_count)),
      .overlay_id = ReadType<uint32_t>(&data_ptr, &data_size, "entries.overlay_id",
                                       dtohl(data_header->target_entry_count)),
  };
  if (!target_entries.target_id || !target_entries.overlay_id) {
    return {};
  }
  Idmap_target_inline_entries target_inline_entries{
      .target_id = ReadType<uint32_t>(&data_ptr, &data_size, "target inline.target_id",
                                      dtohl(data_header->target_inline_entry_count)),
      .entry = ReadType<Idmap_target_entry_inline>(&data_ptr, &data_size, "target inline.entry",
                                                   dtohl(data_header->target_inline_entry_count))};
  if (!target_inline_entries.target_id || !target_inline_entries.entry) {
    return {};
  }

  auto target_inline_entry_values = ReadType<Idmap_target_entry_inline_value>(
      &data_ptr, &data_size, "target inline values",
      dtohl(data_header->target_inline_entry_value_count));
  if (target_inline_entry_values == nullptr) {
    return {};
  }

  auto configurations = ReadType<ConfigDescription>(
      &data_ptr, &data_size, "configurations",
      dtohl(data_header->configuration_count));
  if (configurations == nullptr) {
    return {};
  }

  Idmap_overlay_entries overlay_entries{
      .overlay_id = ReadType<uint32_t>(&data_ptr, &data_size, "overlay entries.overlay_id",
                                       dtohl(data_header->overlay_entry_count)),
      .target_id = ReadType<uint32_t>(&data_ptr, &data_size, "overlay entries.target_id",
                                      dtohl(data_header->overlay_entry_count)),
  };
  if (!overlay_entries.overlay_id || !overlay_entries.target_id) {
    return {};
  }
  std::optional<std::string_view> string_pool = ReadString(&data_ptr, &data_size, "string pool");
  if (!string_pool) {
    return {};
  }
  auto idmap_string_pool = util::make_unique<ResStringPool>();
  if (!string_pool->empty()) {
    const status_t err = idmap_string_pool->setTo(string_pool->data(), string_pool->size());
    if (err != NO_ERROR) {
      LOG(ERROR) << "idmap string pool corrupt.";
      return {};
    }
  }

  if (data_size != 0) {
    LOG(ERROR) << "idmap parsed with " << data_size << "bytes remaining";
    return {};
  }

  // Can't use make_unique because LoadedIdmap constructor is private.
  return std::unique_ptr<LoadedIdmap>(
      new LoadedIdmap(std::string(idmap_path), header, data_header, target_entries,
                      target_inline_entries, target_inline_entry_values, configurations,
                      overlay_entries, std::move(idmap_string_pool), *overlay_path, *target_path));
}

bool LoadedIdmap::IsUpToDate() const {
  return idmap_last_mod_time_ == getFileModDate(idmap_fd_.get());
}

}  // namespace android
