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

#include "idmap2/ResourceUtils.h"

#include <memory>
#include <string>

#include "androidfw/StringPiece.h"
#include "androidfw/Util.h"
#include "idmap2/Result.h"

using android::StringPiece16;
using android::idmap2::Result;
using android::util::Utf16ToUtf8;

namespace android::idmap2::utils {

bool IsReference(uint8_t data_type) {
  return data_type == Res_value::TYPE_REFERENCE || data_type == Res_value::TYPE_DYNAMIC_REFERENCE;
}

StringPiece DataTypeToString(uint8_t data_type) {
  switch (data_type) {
    case Res_value::TYPE_NULL:
      return "null";
    case Res_value::TYPE_REFERENCE:
      return "reference";
    case Res_value::TYPE_ATTRIBUTE:
      return "attribute";
    case Res_value::TYPE_STRING:
      return "string";
    case Res_value::TYPE_FLOAT:
      return "float";
    case Res_value::TYPE_DIMENSION:
      return "dimension";
    case Res_value::TYPE_FRACTION:
      return "fraction";
    case Res_value::TYPE_DYNAMIC_REFERENCE:
      return "reference (dynamic)";
    case Res_value::TYPE_DYNAMIC_ATTRIBUTE:
      return "attribute (dynamic)";
    case Res_value::TYPE_INT_DEC:
    case Res_value::TYPE_INT_HEX:
      return "integer";
    case Res_value::TYPE_INT_BOOLEAN:
      return "boolean";
    case Res_value::TYPE_INT_COLOR_ARGB8:
    case Res_value::TYPE_INT_COLOR_RGB8:
    case Res_value::TYPE_INT_COLOR_RGB4:
      return "color";
    default:
      return "unknown";
  }
}

Result<std::string> ResToTypeEntryName(const AssetManager2& am, uint32_t resid) {
  const auto name = am.GetResourceName(resid);
  if (!name.has_value()) {
    return Error("no resource 0x%08x in asset manager", resid);
  }
  std::string out;
  if (name->type != nullptr) {
    out.append(name->type, name->type_len);
  } else {
    out += Utf16ToUtf8(StringPiece16(name->type16, name->type_len));
  }
  out.append("/");
  if (name->entry != nullptr) {
    out.append(name->entry, name->entry_len);
  } else {
    out += Utf16ToUtf8(StringPiece16(name->entry16, name->entry_len));
  }
  return out;
}

}  // namespace android::idmap2::utils
