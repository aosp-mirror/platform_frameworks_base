/*
 * Copyright (C) 2015 The Android Open Source Project
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

#include "SdkConstants.h"

#include <algorithm>
#include <string>
#include <unordered_set>
#include <vector>

using android::StringPiece;

namespace aapt {

static ApiVersion sDevelopmentSdkLevel = 10000;
static const auto sDevelopmentSdkCodeNames = std::unordered_set<StringPiece>({
    "Q", "R"
});

static const std::vector<std::pair<uint16_t, ApiVersion>> sAttrIdMap = {
    {0x021c, 1},
    {0x021d, 2},
    {0x0269, SDK_CUPCAKE},
    {0x028d, SDK_DONUT},
    {0x02ad, SDK_ECLAIR},
    {0x02b3, SDK_ECLAIR_0_1},
    {0x02b5, SDK_ECLAIR_MR1},
    {0x02bd, SDK_FROYO},
    {0x02cb, SDK_GINGERBREAD},
    {0x0361, SDK_HONEYCOMB},
    {0x0363, SDK_HONEYCOMB_MR1},
    {0x0366, SDK_HONEYCOMB_MR2},
    {0x03a6, SDK_ICE_CREAM_SANDWICH},
    {0x03ae, SDK_JELLY_BEAN},
    {0x03cc, SDK_JELLY_BEAN_MR1},
    {0x03da, SDK_JELLY_BEAN_MR2},
    {0x03f1, SDK_KITKAT},
    {0x03f6, SDK_KITKAT_WATCH},
    {0x04ce, SDK_LOLLIPOP},
    {0x04d8, SDK_LOLLIPOP_MR1},
    {0x04f1, SDK_MARSHMALLOW},
    {0x0527, SDK_NOUGAT},
    {0x0530, SDK_NOUGAT_MR1},
    {0x0568, SDK_O},
    {0x056d, SDK_O_MR1},
    {0x0586, SDK_P},
    {0x0606, SDK_Q},
    {0x0617, SDK_R},
};

static bool less_entry_id(const std::pair<uint16_t, ApiVersion>& p, uint16_t entryId) {
  return p.first < entryId;
}

ApiVersion FindAttributeSdkLevel(const ResourceId& id) {
  if (id.package_id() != 0x01 || id.type_id() != 0x01) {
    return 0;
  }
  auto iter = std::lower_bound(sAttrIdMap.begin(), sAttrIdMap.end(), id.entry_id(), less_entry_id);
  if (iter == sAttrIdMap.end()) {
    return SDK_LOLLIPOP_MR1;
  }
  return iter->second;
}

Maybe<ApiVersion> GetDevelopmentSdkCodeNameVersion(const StringPiece& code_name) {
  return (sDevelopmentSdkCodeNames.find(code_name) == sDevelopmentSdkCodeNames.end())
      ? Maybe<ApiVersion>() : sDevelopmentSdkLevel;
}

}  // namespace aapt
