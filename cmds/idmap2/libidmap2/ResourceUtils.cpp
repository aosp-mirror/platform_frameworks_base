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

#include <string>
#include <utility>

#include "androidfw/StringPiece.h"
#include "androidfw/Util.h"

#include "idmap2/ResourceUtils.h"

using android::StringPiece16;
using android::util::Utf16ToUtf8;

namespace android {
namespace idmap2 {
namespace utils {

std::pair<bool, std::string> WARN_UNUSED ResToTypeEntryName(const AssetManager2& am,
                                                            ResourceId resid) {
  AssetManager2::ResourceName name;
  if (!am.GetResourceName(resid, &name)) {
    return std::make_pair(false, "");
  }
  std::string out;
  if (name.type != nullptr) {
    out.append(name.type, name.type_len);
  } else {
    out += Utf16ToUtf8(StringPiece16(name.type16, name.type_len));
  }
  out.append("/");
  if (name.entry != nullptr) {
    out.append(name.entry, name.entry_len);
  } else {
    out += Utf16ToUtf8(StringPiece16(name.entry16, name.entry_len));
  }
  return std::make_pair(true, out);
}

}  // namespace utils
}  // namespace idmap2
}  // namespace android
