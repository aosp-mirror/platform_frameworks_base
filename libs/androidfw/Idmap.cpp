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

#include "android-base/logging.h"
#include "android-base/stringprintf.h"
#include "utils/ByteOrder.h"
#include "utils/Trace.h"

#ifdef _WIN32
#ifdef ERROR
#undef ERROR
#endif
#endif

#include "androidfw/ResourceTypes.h"

using ::android::base::StringPrintf;

namespace android {

constexpr static inline bool is_valid_package_id(uint16_t id) {
  return id != 0 && id <= 255;
}

constexpr static inline bool is_valid_type_id(uint16_t id) {
  // Type IDs and package IDs have the same constraints in the IDMAP.
  return is_valid_package_id(id);
}

bool LoadedIdmap::Lookup(const IdmapEntry_header* header, uint16_t input_entry_id,
                         uint16_t* output_entry_id) {
  if (input_entry_id < dtohs(header->entry_id_offset)) {
    // After applying the offset, the entry is not present.
    return false;
  }

  input_entry_id -= dtohs(header->entry_id_offset);
  if (input_entry_id >= dtohs(header->entry_count)) {
    // The entry is not present.
    return false;
  }

  uint32_t result = dtohl(header->entries[input_entry_id]);
  if (result == 0xffffffffu) {
    return false;
  }
  *output_entry_id = static_cast<uint16_t>(result);
  return true;
}

static bool is_word_aligned(const void* data) {
  return (reinterpret_cast<uintptr_t>(data) & 0x03) == 0;
}

static bool IsValidIdmapHeader(const StringPiece& data) {
  if (!is_word_aligned(data.data())) {
    LOG(ERROR) << "Idmap header is not word aligned.";
    return false;
  }

  if (data.size() < sizeof(Idmap_header)) {
    LOG(ERROR) << "Idmap header is too small.";
    return false;
  }

  const Idmap_header* header = reinterpret_cast<const Idmap_header*>(data.data());
  if (dtohl(header->magic) != kIdmapMagic) {
    LOG(ERROR) << StringPrintf("Invalid Idmap file: bad magic value (was 0x%08x, expected 0x%08x)",
                               dtohl(header->magic), kIdmapMagic);
    return false;
  }

  if (dtohl(header->version) != kIdmapCurrentVersion) {
    // We are strict about versions because files with this format are auto-generated and don't need
    // backwards compatibility.
    LOG(ERROR) << StringPrintf("Version mismatch in Idmap (was 0x%08x, expected 0x%08x)",
                               dtohl(header->version), kIdmapCurrentVersion);
    return false;
  }

  if (!is_valid_package_id(dtohs(header->target_package_id))) {
    LOG(ERROR) << StringPrintf("Target package ID in Idmap is invalid: 0x%02x",
                               dtohs(header->target_package_id));
    return false;
  }

  if (dtohs(header->type_count) > 255) {
    LOG(ERROR) << StringPrintf("Idmap has too many type mappings (was %d, max 255)",
                               (int)dtohs(header->type_count));
    return false;
  }
  return true;
}

LoadedIdmap::LoadedIdmap(const Idmap_header* header) : header_(header) {
  size_t length = strnlen(reinterpret_cast<const char*>(header_->overlay_path),
                          arraysize(header_->overlay_path));
  overlay_apk_path_.assign(reinterpret_cast<const char*>(header_->overlay_path), length);
}

std::unique_ptr<const LoadedIdmap> LoadedIdmap::Load(const StringPiece& idmap_data) {
  ATRACE_CALL();
  if (!IsValidIdmapHeader(idmap_data)) {
    return {};
  }

  const Idmap_header* header = reinterpret_cast<const Idmap_header*>(idmap_data.data());

  // Can't use make_unique because LoadedImpl constructor is private.
  std::unique_ptr<LoadedIdmap> loaded_idmap = std::unique_ptr<LoadedIdmap>(new LoadedIdmap(header));

  const uint8_t* data_ptr = reinterpret_cast<const uint8_t*>(idmap_data.data()) + sizeof(*header);
  size_t data_size = idmap_data.size() - sizeof(*header);

  size_t type_maps_encountered = 0u;
  while (data_size >= sizeof(IdmapEntry_header)) {
    if (!is_word_aligned(data_ptr)) {
      LOG(ERROR) << "Type mapping in Idmap is not word aligned";
      return {};
    }

    // Validate the type IDs.
    const IdmapEntry_header* entry_header = reinterpret_cast<const IdmapEntry_header*>(data_ptr);
    if (!is_valid_type_id(dtohs(entry_header->target_type_id)) || !is_valid_type_id(dtohs(entry_header->overlay_type_id))) {
      LOG(ERROR) << StringPrintf("Invalid type map (0x%02x -> 0x%02x)",
                                 dtohs(entry_header->target_type_id),
                                 dtohs(entry_header->overlay_type_id));
      return {};
    }

    // Make sure there is enough space for the entries declared in the header.
    if ((data_size - sizeof(*entry_header)) / sizeof(uint32_t) <
        static_cast<size_t>(dtohs(entry_header->entry_count))) {
      LOG(ERROR) << StringPrintf("Idmap too small for the number of entries (%d)",
                                 (int)dtohs(entry_header->entry_count));
      return {};
    }

    // Only add a non-empty overlay.
    if (dtohs(entry_header->entry_count != 0)) {
      loaded_idmap->type_map_[static_cast<uint8_t>(dtohs(entry_header->overlay_type_id))] =
          entry_header;
    }

    const size_t entry_size_bytes =
        sizeof(*entry_header) + (dtohs(entry_header->entry_count) * sizeof(uint32_t));
    data_ptr += entry_size_bytes;
    data_size -= entry_size_bytes;
    type_maps_encountered++;
  }

  // Verify that we parsed all the type maps.
  if (type_maps_encountered != static_cast<size_t>(dtohs(header->type_count))) {
    LOG(ERROR) << "Parsed " << type_maps_encountered << " type maps but expected "
               << (int)dtohs(header->type_count);
    return {};
  }
  return std::move(loaded_idmap);
}

uint8_t LoadedIdmap::TargetPackageId() const {
  return static_cast<uint8_t>(dtohs(header_->target_package_id));
}

const IdmapEntry_header* LoadedIdmap::GetEntryMapForType(uint8_t type_id) const {
  auto iter = type_map_.find(type_id);
  if (iter != type_map_.end()) {
    return iter->second;
  }
  return nullptr;
}

}  // namespace android
