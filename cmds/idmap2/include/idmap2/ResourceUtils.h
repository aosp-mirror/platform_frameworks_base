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

#ifndef IDMAP2_INCLUDE_IDMAP2_RESOURCEUTILS_H_
#define IDMAP2_INCLUDE_IDMAP2_RESOURCEUTILS_H_

#include <optional>
#include <string>
#include <android-base/unique_fd.h>

#include "androidfw/AssetManager2.h"
#include "idmap2/Result.h"

namespace android::idmap2 {

#define EXTRACT_TYPE(resid) ((0x00ff0000 & (resid)) >> 16)
#define EXTRACT_ENTRY(resid) (0x0000ffff & (resid))

// use typedefs to let the compiler warn us about implicit casts
using ResourceId = android::ResourceId;  // 0xpptteeee
using PackageId = uint8_t;    // pp in 0xpptteeee
using TypeId = uint8_t;       // tt in 0xpptteeee
using EntryId = uint16_t;     // eeee in 0xpptteeee

using DataType = android::DataType;    // Res_value::dataType
using DataValue = android::DataValue;  // Res_value::data

struct TargetValue {
  DataType data_type;
  DataValue data_value;
  std::string data_string_value;
  std::optional<android::base::borrowed_fd> data_binary_value;
  off64_t data_binary_offset;
  size_t data_binary_size;
  bool nine_patch;
};

struct TargetValueWithConfig {
  TargetValue value;
  std::string config;

  [[nodiscard]] std::pair<std::string, TargetValue> to_pair() const {
    return std::make_pair(config, value);
  }
};

namespace utils {

// Returns whether the Res_value::data_type represents a dynamic or regular resource reference.
bool IsReference(uint8_t data_type);

// Converts the Res_value::data_type to a human-readable string representation.
StringPiece DataTypeToString(uint8_t data_type);

// Retrieves the type and entry name of the resource in the AssetManager in the form type/entry.
Result<std::string> ResToTypeEntryName(const AssetManager2& am, ResourceId resid);

}  // namespace utils
}  // namespace android::idmap2

#endif  // IDMAP2_INCLUDE_IDMAP2_RESOURCEUTILS_H_
