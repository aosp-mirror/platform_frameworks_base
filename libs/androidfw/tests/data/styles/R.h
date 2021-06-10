/*
 * Copyright (C) 2016 The Android Open Source Project
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

#ifndef TEST_DATA_STYLES_R_H_
#define TEST_DATA_STYLES_R_H_

#include <cstdint>

namespace com {
namespace android {
namespace app {

struct R {
  struct attr {
    enum : uint32_t {
      attr_one = 0x7f010000u,
      attr_two = 0x7f010001u,
      attr_three = 0x7f010002u,
      attr_four = 0x7f010003u,
      attr_five = 0x7f010004u,
      attr_indirect = 0x7f010005u,
      attr_six = 0x7f010006u,
      attr_empty = 0x7f010007u,
    };
  };

  struct string {
    enum : uint32_t {
      string_one = 0x7f030000u,
    };
  };

  struct style {
    enum : uint32_t {
      StyleOne = 0x7f020000u,
      StyleTwo = 0x7f020001u,
      StyleThree = 0x7f020002u,
      StyleFour = 0x7f020003u,
      StyleFive = 0x7f020004u,
      StyleSix = 0x7f020005u,
      StyleSeven = 0x7f020006u,
      StyleDayNight = 0x7f020007u,
    };
  };
};

}  // namespace app
}  // namespace android
}  // namespace com

#endif  // TEST_DATA_STYLES_R_H_
