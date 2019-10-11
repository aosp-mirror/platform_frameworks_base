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

#ifndef TEST_DATA_LIB_TWO_R_H_
#define TEST_DATA_LIB_TWO_R_H_

#include <cstdint>

namespace com {
namespace android {
namespace lib_two {

struct R {
  struct attr {
    enum : uint32_t {
      attr3 = 0x02010000, // default
    };
  };

  struct string {
    enum : uint32_t {
      LibraryString = 0x02020000,  // default
      foo = 0x02020001, // default
    };
  };

  struct style {
    enum : uint32_t {
      Theme = 0x02030000, // default
    };
  };
};

}  // namespace lib_two
}  // namespace android
}  // namespace com

#endif  // TEST_DATA_LIB_TWO_R_H_
