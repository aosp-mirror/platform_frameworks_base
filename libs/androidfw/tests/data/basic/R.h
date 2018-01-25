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

#ifndef TESTS_DATA_BASIC_R_H_
#define TESTS_DATA_BASIC_R_H_

#include <cstdint>

namespace com {
namespace android {
namespace basic {

struct R {
  struct attr {
    enum : uint32_t {
      attr1 = 0x7f010000,
      attr2 = 0x7f010001,
    };
  };

  struct layout {
    enum : uint32_t {
      main = 0x7f020000,
      layoutt = 0x7f020001,
    };
  };

  struct string {
    enum : uint32_t {
      test1 = 0x7f030000,
      test2 = 0x7f030001,
      density = 0x7f030002,

      // From feature
      test3 = 0x80020000,
      test4 = 0x80020001,
    };
  };

  struct integer {
    enum : uint32_t {
      number1 = 0x7f040000,
      number2 = 0x7f040001,
      ref1 = 0x7f040002,
      ref2 = 0x7f040003,
      deep_ref = 0x7f040004,

      // From feature
      number3 = 0x80030000,
    };
  };

  struct style {
    enum : uint32_t {
      Theme1 = 0x7f050000,
      Theme2 = 0x7f050001,
    };
  };

  struct array {
    enum : uint32_t {
      integerArray1 = 0x7f060000,
    };
  };
};

}  // namespace basic
}  // namespace android
}  // namespace com

#endif /* TESTS_DATA_BASIC_R_H_ */
