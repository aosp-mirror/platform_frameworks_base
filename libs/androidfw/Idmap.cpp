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

uint32_t round_to_4_bytes(uint32_t size) {
  return size + (4U - (size % 4U)) % 4U;
}

size_t Idmap_header::Size() const {
  return sizeof(Idmap_header) + sizeof(uint8_t) * round_to_4_bytes(dtohl(debug_info_size));
}

OverlayStringPool::OverlayStringPool(const LoadedIdmap* loaded_idmap)
    : data_header_(loaded_idmap->data_header_),
      idmap_string_pool_(loaded_idmap->string_pool_.get()) { };

OverlayStringPool::~OverlayStringPool() {
  uninit();
}

const char16_t* OverlayStringPool::stringAt(size_t idx, size_t* outLen) const {
  const size_t offset = dtohl(data_header_->string_pool_index_offset);
  if (idmap_string_pool_ != nullptr && idx >= ResStringPool::size() && idx >= offset) {
    return idmap_string_pool_->stringAt(idx - offset, outLen);
  }

  return ResStringPool::stringAt(idx, outLen);
}

const char* OverlayStringPool::string8At(size_t idx, size_t* outLen) const {
  const size_t offset = dtohl(data_header_->string_pool_index_offset);
  if (idmap_string_pool_ != nullptr && idx >= ResStringPool::size() && idx >= offset) {
    return idmap_string_pool_->string8At(idx - offset, outLen);
  }

  return ResStringPool::string8At(idx, outLen);
}

size_t OverlayStringPool::size() const {
  return ResStringPool::size() + (idmap_string_pool_ != nullptr ? idmap_string_pool_->size() : 0U);
}

OverlayDynamicRefTable::OverlayDynamicRefTable(const Idmap_data_header* data_header,
                                               const Idmap_overlay_entry* entries,
                                               uint8_t target_assigned_package_id)
    : data_header_(data_header),
      entries_(entries),
      target_assigned_package_id_(target_assigned_package_id) { };

status_t OverlayDynamicRefTable::lookupResourceId(uint32_t* resId) const {
  const Idmap_overlay_entry* first_entry = entries_;
  const Idmap_overlay_entry* end_entry = entries_ + dtohl(data_header_->overlay_entry_count);
  auto entry = std::lower_bound(first_entry, end_entry, *resId,
                                [](const Idmap_overlay_entry& e1, const uint32_t overlay_id) {
    return dtohl(e1.overlay_id) < overlay_id;
  });

  if (entry == end_entry || dtohl(entry->overlay_id) != *resId) {
    // A mapping for the target resource id could not be found.
    return DynamicRefTable::lookupResourceId(resId);
  }

  *resId = (0x00FFFFFFU & dtohl(entry->target_id))
      | (((uint32_t) target_assigned_package_id_) << 24U);
  return NO_ERROR;
}

status_t OverlayDynamicRefTable::lookupResourceIdNoRewrite(uint32_t* resId) const {
  return DynamicRefTable::lookupResourceId(resId);
}

IdmapResMap::IdmapResMap(const Idmap_data_header* data_header,
                         const Idmap_target_entry* entries,
                         const Idmap_target_entry_inline* inline_entries,
                         uint8_t target_assigned_package_id,
                         const OverlayDynamicRefTable* overlay_ref_table)
    : data_header_(data_header),
      entries_(entries),
      inline_entries_(inline_entries),
      target_assigned_package_id_(target_assigned_package_id),
      overlay_ref_table_(overlay_ref_table) { }

IdmapResMap::Result IdmapResMap::Lookup(uint32_t target_res_id) const {
  if ((target_res_id >> 24U) != target_assigned_package_id_) {
    // The resource id must have the same package id as the target package.
    return {};
  }

  // The resource ids encoded within the idmap are build-time resource ids.
  target_res_id = (0x00FFFFFFU & target_res_id)
      | (((uint32_t) data_header_->target_package_id) << 24U);

  // Check if the target resource is mapped to an overlay resource.
  auto first_entry = entries_;
  auto end_entry = entries_ + dtohl(data_header_->target_entry_count);
  auto entry = std::lower_bound(first_entry, end_entry, target_res_id,
                                [](const Idmap_target_entry &e, const uint32_t target_id) {
    return dtohl(e.target_id) < target_id;
  });

  if (entry != end_entry && dtohl(entry->target_id) == target_res_id) {
    uint32_t overlay_resource_id = dtohl(entry->overlay_id);
    // Lookup the resource without rewriting the overlay resource id back to the target resource id
    // being looked up.
    overlay_ref_table_->lookupResourceIdNoRewrite(&overlay_resource_id);
    return Result(overlay_resource_id);
  }

  // Check if the target resources is mapped to an inline table entry.
  auto first_inline_entry = inline_entries_;
  auto end_inline_entry = inline_entries_ + dtohl(data_header_->target_inline_entry_count);
  auto inline_entry = std::lower_bound(first_inline_entry, end_inline_entry, target_res_id,
                                       [](const Idmap_target_entry_inline &e,
                                          const uint32_t target_id) {
    return dtohl(e.target_id) < target_id;
  });

  if (inline_entry != end_inline_entry && dtohl(inline_entry->target_id) == target_res_id) {
    return Result(inline_entry->value);
  }
  return {};
}

static bool is_word_aligned(const void* data) {
  return (reinterpret_cast<uintptr_t>(data) & 0x03U) == 0U;
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

  auto header = reinterpret_cast<const Idmap_header*>(data.data());
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

  return true;
}

LoadedIdmap::LoadedIdmap(std::string&& idmap_path,
                         const time_t last_mod_time,
                         const Idmap_header* header,
                         const Idmap_data_header* data_header,
                         const Idmap_target_entry* target_entries,
                         const Idmap_target_entry_inline* target_inline_entries,
                         const Idmap_overlay_entry* overlay_entries,
                         ResStringPool* string_pool)
     : header_(header),
       data_header_(data_header),
       target_entries_(target_entries),
       target_inline_entries_(target_inline_entries),
       overlay_entries_(overlay_entries),
       string_pool_(string_pool),
       idmap_path_(std::move(idmap_path)),
       idmap_last_mod_time_(last_mod_time) {

  size_t length = strnlen(reinterpret_cast<const char*>(header_->overlay_path),
                          arraysize(header_->overlay_path));
  overlay_apk_path_.assign(reinterpret_cast<const char*>(header_->overlay_path), length);

  length = strnlen(reinterpret_cast<const char*>(header_->target_path),
                          arraysize(header_->target_path));
  target_apk_path_.assign(reinterpret_cast<const char*>(header_->target_path), length);
}

std::unique_ptr<const LoadedIdmap> LoadedIdmap::Load(const StringPiece& idmap_path,
                                                     const StringPiece& idmap_data) {
  ATRACE_CALL();
  if (!IsValidIdmapHeader(idmap_data)) {
    return {};
  }

  auto header = reinterpret_cast<const Idmap_header*>(idmap_data.data());
  const uint8_t* data_ptr = reinterpret_cast<const uint8_t*>(idmap_data.data()) + header->Size();
  size_t data_size = idmap_data.size() - header->Size();

  // Currently idmap2 can only generate one data block.
  auto data_header = reinterpret_cast<const Idmap_data_header*>(data_ptr);
  data_ptr += sizeof(*data_header);
  data_size -= sizeof(*data_header);

  // Make sure there is enough space for the target entries declared in the header
  const auto target_entries = reinterpret_cast<const Idmap_target_entry*>(data_ptr);
  if (data_size / sizeof(Idmap_target_entry) <
      static_cast<size_t>(dtohl(data_header->target_entry_count))) {
    LOG(ERROR) << StringPrintf("Idmap too small for the number of target entries (%d)",
                               (int)dtohl(data_header->target_entry_count));
    return {};
  }

  // Advance the data pointer past the target entries.
  const size_t target_entry_size_bytes =
      (dtohl(data_header->target_entry_count) * sizeof(Idmap_target_entry));
  data_ptr += target_entry_size_bytes;
  data_size -= target_entry_size_bytes;

  // Make sure there is enough space for the target entries declared in the header.
  const auto target_inline_entries = reinterpret_cast<const Idmap_target_entry_inline*>(data_ptr);
  if (data_size / sizeof(Idmap_target_entry_inline) <
      static_cast<size_t>(dtohl(data_header->target_inline_entry_count))) {
    LOG(ERROR) << StringPrintf("Idmap too small for the number of target inline entries (%d)",
                               (int)dtohl(data_header->target_inline_entry_count));
    return {};
  }

  // Advance the data pointer past the target entries.
  const size_t target_inline_entry_size_bytes =
      (dtohl(data_header->target_inline_entry_count) * sizeof(Idmap_target_entry_inline));
  data_ptr += target_inline_entry_size_bytes;
  data_size -= target_inline_entry_size_bytes;

  // Make sure there is enough space for the overlay entries declared in the header.
  const auto overlay_entries = reinterpret_cast<const Idmap_overlay_entry*>(data_ptr);
  if (data_size / sizeof(Idmap_overlay_entry) <
      static_cast<size_t>(dtohl(data_header->overlay_entry_count))) {
    LOG(ERROR) << StringPrintf("Idmap too small for the number of overlay entries (%d)",
                               (int)dtohl(data_header->overlay_entry_count));
    return {};
  }

  // Advance the data pointer past the overlay entries.
  const size_t overlay_entry_size_bytes =
      (dtohl(data_header->overlay_entry_count) * sizeof(Idmap_overlay_entry));
  data_ptr += overlay_entry_size_bytes;
  data_size -= overlay_entry_size_bytes;

  // Read the idmap string pool that holds the value of inline string entries.
  uint32_t string_pool_size = dtohl(*reinterpret_cast<const uint32_t*>(data_ptr));
  data_ptr += sizeof(uint32_t);
  data_size -= sizeof(uint32_t);

  if (data_size < string_pool_size) {
    LOG(ERROR) << StringPrintf("Idmap too small for string pool (length %d)",
                               (int)string_pool_size);
    return {};
  }

  auto idmap_string_pool = util::make_unique<ResStringPool>();
  if (string_pool_size > 0) {
    status_t err = idmap_string_pool->setTo(data_ptr, string_pool_size);
    if (err != NO_ERROR) {
      LOG(ERROR) << "idmap string pool corrupt.";
      return {};
    }
  }

  // Can't use make_unique because LoadedIdmap constructor is private.
  auto loaded_idmap = std::unique_ptr<LoadedIdmap>(
      new LoadedIdmap(idmap_path.to_string(), getFileModDate(idmap_path.data()), header,
                      data_header, target_entries, target_inline_entries, overlay_entries,
                      idmap_string_pool.release()));

  return std::move(loaded_idmap);
}

bool LoadedIdmap::IsUpToDate() const {
  return idmap_last_mod_time_ == getFileModDate(idmap_path_.c_str());
}

}  // namespace android
