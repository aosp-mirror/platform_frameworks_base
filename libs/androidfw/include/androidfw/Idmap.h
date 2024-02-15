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

#ifndef IDMAP_H_
#define IDMAP_H_

#include <memory>
#include <string>
#include <unordered_map>
#include <variant>

#include "android-base/macros.h"
#include "androidfw/ConfigDescription.h"
#include "androidfw/StringPiece.h"
#include "androidfw/ResourceTypes.h"
#include "utils/ByteOrder.h"

namespace android {

class LoadedIdmap;
class IdmapResMap;
struct Idmap_header;
struct Idmap_data_header;
struct Idmap_target_entry;
struct Idmap_target_entry_inline;
struct Idmap_target_entry_inline_value;
struct Idmap_overlay_entry;

// A string pool for overlay apk assets. The string pool holds the strings of the overlay resources
// table and additionally allows for loading strings from the idmap string pool. The idmap string
// pool strings are offset after the end of the overlay resource table string pool entries so
// queries for strings defined inline in the idmap do not conflict with queries for overlay
// resource table strings.
class OverlayStringPool : public ResStringPool {
 public:
  virtual ~OverlayStringPool();
  base::expected<StringPiece16, NullOrIOError> stringAt(size_t idx) const override;
  base::expected<StringPiece, NullOrIOError> string8At(size_t idx) const override;
  size_t size() const override;

  explicit OverlayStringPool(const LoadedIdmap* loaded_idmap);
 private:
    const Idmap_data_header* data_header_;
    const ResStringPool* idmap_string_pool_;
};

// A dynamic reference table for loaded overlay packages that rewrites the resource id of overlay
// resources to the resource id of corresponding target resources.
class OverlayDynamicRefTable : public DynamicRefTable {
 public:
  ~OverlayDynamicRefTable() override = default;
  status_t lookupResourceId(uint32_t* resId) const override;

 private:
  explicit OverlayDynamicRefTable(const Idmap_data_header* data_header,
                                  const Idmap_overlay_entry* entries,
                                  uint8_t target_assigned_package_id);

  // Rewrites a compile-time overlay resource id to the runtime resource id of corresponding target
  // resource.
  status_t lookupResourceIdNoRewrite(uint32_t* resId) const;

  const Idmap_data_header* data_header_;
  const Idmap_overlay_entry* entries_;
  const int8_t target_assigned_package_id_;

  friend LoadedIdmap;
  friend IdmapResMap;
};

// A mapping of target resource ids to a values or resource ids that should overlay the target.
class IdmapResMap {
 public:
  // Represents the result of a idmap lookup. The result can be one of three possibilities:
  // 1) The result is a resource id which represents the overlay resource that should act as an
  //    alias of the target resource.
  // 2) The result is a table entry which overlays the type and value of the target resource.
  // 3) The result is neither and the target resource is not overlaid.
  class Result {
   public:
    Result() = default;
    explicit Result(uint32_t value) : data_(value) {};
    explicit Result(std::map<ConfigDescription, Res_value> value) : data_(std::move(value)) {
    }

    // Returns `true` if the resource is overlaid.
    explicit operator bool() const {
      return std::get_if<std::monostate>(&data_) == nullptr;
    }

    bool IsResourceId() const {
      return std::get_if<uint32_t>(&data_) != nullptr;
    }

    uint32_t GetResourceId() const {
      return std::get<uint32_t>(data_);
    }

    bool IsInlineValue() const {
      return std::get_if<2>(&data_) != nullptr;
    }

    const std::map<ConfigDescription, Res_value>& GetInlineValue() const {
      return std::get<2>(data_);
    }

   private:
      std::variant<std::monostate, uint32_t,
          std::map<ConfigDescription, Res_value> > data_;
  };

  // Looks up the value that overlays the target resource id.
  Result Lookup(uint32_t target_res_id) const;

  inline const OverlayDynamicRefTable* GetOverlayDynamicRefTable() const {
    return overlay_ref_table_;
  }

 private:
  explicit IdmapResMap(const Idmap_data_header* data_header,
                       const Idmap_target_entry* entries,
                       const Idmap_target_entry_inline* inline_entries,
                       const Idmap_target_entry_inline_value* inline_entry_values,
                       const ConfigDescription* configs,
                       uint8_t target_assigned_package_id,
                       const OverlayDynamicRefTable* overlay_ref_table);

  const Idmap_data_header* data_header_;
  const Idmap_target_entry* entries_;
  const Idmap_target_entry_inline* inline_entries_;
  const Idmap_target_entry_inline_value* inline_entry_values_;
  const ConfigDescription* configurations_;
  const uint8_t target_assigned_package_id_;
  const OverlayDynamicRefTable* overlay_ref_table_;

  friend LoadedIdmap;
};

// Represents a loaded/parsed IDMAP for a Runtime Resource Overlay (RRO).
// An RRO and its target APK have different resource IDs assigned to their resources.
// An IDMAP is a generated mapping between the resource IDs of the RRO and the target APK.
// A LoadedIdmap can be set alongside the overlay's LoadedArsc to allow the overlay ApkAssets to
// masquerade as the target ApkAssets resources.
class LoadedIdmap {
 public:
  // Loads an IDMAP from a chunk of memory. Returns nullptr if the IDMAP data was malformed.
  static std::unique_ptr<LoadedIdmap> Load(StringPiece idmap_path, StringPiece idmap_data);

  // Returns the path to the IDMAP.
  std::string_view IdmapPath() const {
    return idmap_path_;
  }

  // Returns the path to the RRO (Runtime Resource Overlay) APK for which this IDMAP was generated.
  std::string_view OverlayApkPath() const {
    return overlay_apk_path_;
  }

  // Returns the path to the RRO (Runtime Resource Overlay) APK for which this IDMAP was generated.
  std::string_view TargetApkPath() const {
    return target_apk_path_;
  }

  // Returns a mapping from target resource ids to overlay values.
  const IdmapResMap GetTargetResourcesMap(uint8_t target_assigned_package_id,
                                          const OverlayDynamicRefTable* overlay_ref_table) const {
    return IdmapResMap(data_header_, target_entries_, target_inline_entries_, inline_entry_values_,
                       configurations_, target_assigned_package_id, overlay_ref_table);
  }

  // Returns a dynamic reference table for a loaded overlay package.
  const OverlayDynamicRefTable GetOverlayDynamicRefTable(uint8_t target_assigned_package_id) const {
    return OverlayDynamicRefTable(data_header_, overlay_entries_, target_assigned_package_id);
  }

  // Returns whether the idmap file on disk has not been modified since the construction of this
  // LoadedIdmap.
  bool IsUpToDate() const;

 protected:
  // Exposed as protected so that tests can subclass and mock this class out.
  LoadedIdmap() = default;

  const Idmap_header* header_;
  const Idmap_data_header* data_header_;
  const Idmap_target_entry* target_entries_;
  const Idmap_target_entry_inline* target_inline_entries_;
  const Idmap_target_entry_inline_value* inline_entry_values_;
  const ConfigDescription* configurations_;
  const Idmap_overlay_entry* overlay_entries_;
  const std::unique_ptr<ResStringPool> string_pool_;

  std::string idmap_path_;
  std::string_view overlay_apk_path_;
  std::string_view target_apk_path_;
  time_t idmap_last_mod_time_;

 private:
  DISALLOW_COPY_AND_ASSIGN(LoadedIdmap);

  explicit LoadedIdmap(std::string&& idmap_path,
                       const Idmap_header* header,
                       const Idmap_data_header* data_header,
                       const Idmap_target_entry* target_entries,
                       const Idmap_target_entry_inline* target_inline_entries,
                       const Idmap_target_entry_inline_value* inline_entry_values_,
                       const ConfigDescription* configs,
                       const Idmap_overlay_entry* overlay_entries,
                       std::unique_ptr<ResStringPool>&& string_pool,
                       std::string_view overlay_apk_path,
                       std::string_view target_apk_path);

  friend OverlayStringPool;
};

}  // namespace android

#endif  // IDMAP_H_
