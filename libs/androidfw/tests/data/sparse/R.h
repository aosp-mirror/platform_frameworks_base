/*
 * Copyright (C) 2017 The Android Open Source Project
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

#ifndef TESTS_DATA_SPARSE_R_H_
#define TESTS_DATA_SPARSE_R_H_

#include <cstdint>

namespace com {
namespace android {
namespace sparse {

struct R {
  struct integer {
    enum : uint32_t {
      foo_0 = 0x7f010000,
      foo_1 = 0x7f010001,
      foo_2 = 0x7f010002,
      foo_3 = 0x7f010003,
      foo_4 = 0x7f010004,
      foo_5 = 0x7f010005,
      foo_6 = 0x7f010006,
      foo_7 = 0x7f010007,
      foo_8 = 0x7f010008,
      foo_9 = 0x7f010009,
    };
  };

  struct string {
    enum : uint32_t {
      foo_999 = 0x7f0203e7,
      only_v26 = 0x7f0203e8
    };
  };
};

}  // namespace sparse
}  // namespace android
}  // namespace com

#endif /* TESTS_DATA_SPARSE_R_H_ */
