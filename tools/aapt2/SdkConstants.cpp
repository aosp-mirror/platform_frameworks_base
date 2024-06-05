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

#include <stdint.h>

#include <algorithm>
#include <string>
#include <string_view>

using android::StringPiece;
using namespace std::literals;

namespace aapt {

static constexpr ApiVersion sDevelopmentSdkLevel = 10000;
static constexpr StringPiece sDevelopmentSdkCodeNames[] = {
    "Q"sv, "R"sv, "S"sv, "Sv2"sv, "Tiramisu"sv, "UpsideDownCake"sv, "VanillaIceCream"sv};

static constexpr auto sPrivacySandboxSuffix = "PrivacySandbox"sv;

static constexpr std::pair<uint16_t, ApiVersion> sAttrIdMap[] = {
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
    {0x0616, SDK_R},
    {0x064b, SDK_S},
    {0x064c, SDK_S_V2},
};

static_assert(std::is_sorted(std::begin(sAttrIdMap), std::end(sAttrIdMap),
                             [](auto&& l, auto&& r) { return l.first < r.first; }));

ApiVersion FindAttributeSdkLevel(const ResourceId& id) {
  if (id.package_id() != 0x01 || id.type_id() != 0x01) {
    return 0;
  }
  const auto it =
      std::lower_bound(std::begin(sAttrIdMap), std::end(sAttrIdMap), id.entry_id(),
                       [](const auto& pair, uint16_t entryId) { return pair.first < entryId; });
  if (it == std::end(sAttrIdMap)) {
    return SDK_LOLLIPOP_MR1;
  }
  return it->second;
}

std::optional<ApiVersion> GetDevelopmentSdkCodeNameVersion(StringPiece code_name) {
  const auto it =
      std::find_if(std::begin(sDevelopmentSdkCodeNames), std::end(sDevelopmentSdkCodeNames),
                   [code_name](const auto& item) { return code_name.starts_with(item); });
  if (it == std::end(sDevelopmentSdkCodeNames)) {
    return {};
  }
  if (code_name.size() == it->size()) {
    return sDevelopmentSdkLevel;
  }
  if (code_name.size() == it->size() + sPrivacySandboxSuffix.size() &&
      code_name.ends_with(sPrivacySandboxSuffix)) {
    return sDevelopmentSdkLevel;
  }
  return {};
}

}  // namespace aapt
