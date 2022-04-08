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

#ifndef ANDROIDFW_RESOURCEUTILS_H
#define ANDROIDFW_RESOURCEUTILS_H

#include "androidfw/AssetManager2.h"
#include "androidfw/StringPiece.h"

namespace android {

// Extracts the package, type, and name from a string of the format: [[package:]type/]name
// Validation must be performed on each extracted piece.
// Returns false if there was a syntax error.
bool ExtractResourceName(const StringPiece& str, StringPiece* out_package, StringPiece* out_type,
                         StringPiece* out_entry);

// Convert a type_string_ref, entry_string_ref, and package to AssetManager2::ResourceName.
// Useful for getting resource name without re-running AssetManager2::FindEntry searches.
bool ToResourceName(const StringPoolRef& type_string_ref,
                    const StringPoolRef& entry_string_ref,
                    const StringPiece& package_name,
                    AssetManager2::ResourceName* out_name);

// Formats a ResourceName to "package:type/entry_name".
std::string ToFormattedResourceString(AssetManager2::ResourceName* resource_name);

inline uint32_t fix_package_id(uint32_t resid, uint8_t package_id) {
  return (resid & 0x00ffffffu) | (static_cast<uint32_t>(package_id) << 24);
}

inline uint8_t get_package_id(uint32_t resid) {
  return static_cast<uint8_t>((resid >> 24) & 0x000000ffu);
}

// The type ID is 1-based, so if the returned value is 0 it is invalid.
inline uint8_t get_type_id(uint32_t resid) {
  return static_cast<uint8_t>((resid >> 16) & 0x000000ffu);
}

inline uint16_t get_entry_id(uint32_t resid) {
  return static_cast<uint16_t>(resid & 0x0000ffffu);
}

inline bool is_internal_resid(uint32_t resid) {
  return (resid & 0xffff0000u) != 0 && (resid & 0x00ff0000u) == 0;
}

inline bool is_valid_resid(uint32_t resid) {
  return (resid & 0x00ff0000u) != 0 && (resid & 0xff000000u) != 0;
}

inline uint32_t make_resid(uint8_t package_id, uint8_t type_id, uint16_t entry_id) {
  return (static_cast<uint32_t>(package_id) << 24) | (static_cast<uint32_t>(type_id) << 16) |
         entry_id;
}

}  // namespace android

#endif /* ANDROIDFW_RESOURCEUTILS_H */
