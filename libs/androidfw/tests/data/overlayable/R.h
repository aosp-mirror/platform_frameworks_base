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

#ifndef TESTS_DATA_OVERLAYABLE_R_H_
#define TESTS_DATA_OVERLAYABLE_R_H_

#include <cstdint>

namespace com {
namespace android {
namespace overlayable {

struct R {
  struct string {
    enum : uint32_t {
      not_overlayable = 0x7f010000,
      overlayable1 = 0x7f010001,
      overlayable2 = 0x7f010002,
      overlayable3 = 0x7f010003,
      overlayable4 = 0x7f010004,
      overlayable5 = 0x7f010005,
      overlayable6 = 0x7f010006,
      overlayable7 = 0x7f010007,
      overlayable8 = 0x7f010008,
      overlayable9 = 0x7f010009,
      overlayable10 = 0x7f01000a,
      overlayable11 = 0x7f01000b,
    };
  };

  struct attr {
    enum : uint32_t  {
      max_lines = 0x7f020000,
    };
  };

  struct boolean {
    enum : uint32_t {
      config_bool = 0x7f030000,
    };
  };

  struct id {
    enum : uint32_t  {
      hello_view = 0x7f040000,
    };
  };

  struct integer {
    enum : uint32_t {
      config_integer = 0x7f050000,
    };
  };

  struct layout {
    enum : uint32_t  {
      hello_view = 0x7f060000,
    };
  };
};

}  // namespace overlayable
}  // namespace android
}  // namespace com

#endif /* TESTS_DATA_OVERLAYABLE_R_H_ */
