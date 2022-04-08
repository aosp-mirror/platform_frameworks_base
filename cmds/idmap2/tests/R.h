/*
 * Copyright (C) 2019 The Android Open Source Project
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

#ifndef IDMAP2_TESTS_R_H
#define IDMAP2_TESTS_R_H

#include <idmap2/ResourceUtils.h>

namespace android::idmap2 {

static std::string hexify(ResourceId id) {
  std::stringstream stream;
  stream << std::hex << static_cast<uint32_t>(id);
  return stream.str();
}

// clang-format off
namespace R::target {
  namespace integer {
    constexpr ResourceId int1 = 0x7f010000;

    namespace literal {
      inline const std::string int1 = hexify(R::target::integer::int1);
    }
  }

  namespace string {
    constexpr ResourceId not_overlayable = 0x7f020003;
    constexpr ResourceId other = 0x7f020004;
    constexpr ResourceId policy_actor = 0x7f020005;
    constexpr ResourceId policy_odm = 0x7f020006;
    constexpr ResourceId policy_oem = 0x7f020007;
    constexpr ResourceId policy_product = 0x7f020008;
    constexpr ResourceId policy_public = 0x7f020009;
    constexpr ResourceId policy_signature = 0x7f02000a;
    constexpr ResourceId policy_system = 0x7f02000b;
    constexpr ResourceId policy_system_vendor = 0x7f02000c;
    constexpr ResourceId str1 = 0x7f02000d;
    constexpr ResourceId str3 = 0x7f02000f;
    constexpr ResourceId str4 = 0x7f020010;

    namespace literal {
      inline const std::string str1 = hexify(R::target::string::str1);
      inline const std::string str3 = hexify(R::target::string::str3);
      inline const std::string str4 = hexify(R::target::string::str4);
    }
  }
}

namespace R::overlay {
  namespace integer {
    constexpr ResourceId int1 = 0x7f010000;
  }
  namespace string {
    constexpr ResourceId str1 = 0x7f020000;
    constexpr ResourceId str3 = 0x7f020001;
    constexpr ResourceId str4 = 0x7f020002;
  }
}

namespace R::overlay_shared {
  namespace integer {
    constexpr ResourceId int1 = 0x00010000;
  }
  namespace string {
    constexpr ResourceId str1 = 0x00020000;
    constexpr ResourceId str3 = 0x00020001;
    constexpr ResourceId str4 = 0x00020002;
  }
}

namespace R::system_overlay::string {
  constexpr ResourceId policy_public = 0x7f010000;
  constexpr ResourceId policy_system = 0x7f010001;
  constexpr ResourceId policy_system_vendor = 0x7f010002;
}

namespace R::system_overlay_invalid::string {
  constexpr ResourceId not_overlayable = 0x7f010000;
  constexpr ResourceId other = 0x7f010001;
  constexpr ResourceId policy_actor = 0x7f010002;
  constexpr ResourceId policy_odm = 0x7f010003;
  constexpr ResourceId policy_oem = 0x7f010004;
  constexpr ResourceId policy_product = 0x7f010005;
  constexpr ResourceId policy_public = 0x7f010006;
  constexpr ResourceId policy_signature = 0x7f010007;
  constexpr ResourceId policy_system = 0x7f010008;
  constexpr ResourceId policy_system_vendor = 0x7f010009;
};
// clang-format on

}  // namespace android::idmap2

#endif  // IDMAP2_TESTS_R_H
