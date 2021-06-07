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

#ifndef IDMAP2_TESTS_R_H_
#define IDMAP2_TESTS_R_H_

#include <string>

#include "idmap2/ResourceUtils.h"

namespace android::idmap2 {

// clang-format off
namespace R::target {
  namespace integer {  // NOLINT(runtime/indentation_namespace)
    constexpr ResourceId int1 = 0x7f010000;
  }
  namespace string {  // NOLINT(runtime/indentation_namespace)
    constexpr ResourceId not_overlayable = 0x7f020003;
    constexpr ResourceId other = 0x7f020004;
    constexpr ResourceId policy_actor = 0x7f020005;
    constexpr ResourceId policy_config_signature = 0x7f020006;
    constexpr ResourceId policy_odm = 0x7f020007;
    constexpr ResourceId policy_oem = 0x7f020008;
    constexpr ResourceId policy_product = 0x7f020009;
    constexpr ResourceId policy_public = 0x7f02000a;
    constexpr ResourceId policy_signature = 0x7f02000b;
    constexpr ResourceId policy_system = 0x7f02000c;
    constexpr ResourceId policy_system_vendor = 0x7f02000d;
    constexpr ResourceId str1 = 0x7f02000e;
    constexpr ResourceId str3 = 0x7f020010;
    constexpr ResourceId str4 = 0x7f020011;
  }  // namespace string
}  // namespace R::target

namespace R::overlay {
  namespace integer {  // NOLINT(runtime/indentation_namespace)
    constexpr ResourceId int1 = 0x7f010000;
    constexpr ResourceId not_in_target = 0x7f010001;
  }
  namespace string {  // NOLINT(runtime/indentation_namespace)
    constexpr ResourceId not_overlayable = 0x7f020000;
    constexpr ResourceId other = 0x7f020001;
    constexpr ResourceId policy_actor = 0x7f020002;
    constexpr ResourceId policy_config_signature = 0x7f020003;
    constexpr ResourceId policy_odm = 0x7f020004;
    constexpr ResourceId policy_oem = 0x7f020005;
    constexpr ResourceId policy_product = 0x7f020006;
    constexpr ResourceId policy_public = 0x7f020007;
    constexpr ResourceId policy_signature = 0x7f020008;
    constexpr ResourceId policy_system = 0x7f020009;
    constexpr ResourceId policy_system_vendor = 0x7f02000a;
    constexpr ResourceId str1 = 0x7f02000b;
    constexpr ResourceId str3 = 0x7f02000c;
    constexpr ResourceId str4 = 0x7f02000d;
  }  // namespace string
}  // namespace R::overlay
// clang-format on

}  // namespace android::idmap2

#endif  // IDMAP2_TESTS_R_H_
