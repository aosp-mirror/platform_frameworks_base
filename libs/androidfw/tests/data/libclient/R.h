/*
 * Copyright (C) 2014 The Android Open Source Project
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

#ifndef TEST_DATA_LIB_R_H_
#define TEST_DATA_LIB_R_H_

#include <cstdint>

namespace com {
namespace android {
namespace libclient {

struct R {
  struct attr {
    enum : uint32_t {
      foo = 0x7f010000,  // default
      bar = 0x7f010001,  // default
    };
  };

  struct style {
    enum : uint32_t {
      Theme = 0x7f020000,  // default
      ThemeMultiLib = 0x7f020001,  // default
    };
  };

  struct string {
    enum : uint32_t {
      foo_one = 0x7f030000,  // default
      foo_two = 0x7f030001,  // default
    };
  };
};

}  // namespace libclient
}  // namespace android
}  // namespace com

#endif  // TEST_DATA_R_H_
