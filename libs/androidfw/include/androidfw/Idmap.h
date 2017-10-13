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

#include "android-base/macros.h"

#include "androidfw/StringPiece.h"

namespace android {

struct Idmap_header;
struct IdmapEntry_header;

// Represents a loaded/parsed IDMAP for a Runtime Resource Overlay (RRO).
// An RRO and its target APK have different resource IDs assigned to their resources. Overlaying
// a resource is done by resource name. An IDMAP is a generated mapping between the resource IDs
// of the RRO and the target APK for each resource with the same name.
// A LoadedIdmap can be set alongside the overlay's LoadedArsc to allow the overlay ApkAssets to
// masquerade as the target ApkAssets resources.
class LoadedIdmap {
 public:
  // Loads an IDMAP from a chunk of memory. Returns nullptr if the IDMAP data was malformed.
  static std::unique_ptr<const LoadedIdmap> Load(const StringPiece& idmap_data);

  // Performs a lookup of the expected entry ID for the given IDMAP entry header.
  // Returns true if the mapping exists and fills `output_entry_id` with the result.
  static bool Lookup(const IdmapEntry_header* header, uint16_t input_entry_id,
                     uint16_t* output_entry_id);

  // Returns the package ID for which this overlay should apply.
  uint8_t TargetPackageId() const;

  // Returns the path to the RRO (Runtime Resource Overlay) APK for which this IDMAP was generated.
  inline const std::string& OverlayApkPath() const {
    return overlay_apk_path_;
  }

  // Returns the mapping of target entry ID to overlay entry ID for the given target type.
  const IdmapEntry_header* GetEntryMapForType(uint8_t type_id) const;

 protected:
  // Exposed as protected so that tests can subclass and mock this class out.
  LoadedIdmap() = default;

  const Idmap_header* header_ = nullptr;
  std::string overlay_apk_path_;
  std::unordered_map<uint8_t, const IdmapEntry_header*> type_map_;

 private:
  DISALLOW_COPY_AND_ASSIGN(LoadedIdmap);

  explicit LoadedIdmap(const Idmap_header* header);
};

}  // namespace android

#endif  // IDMAP_H_
