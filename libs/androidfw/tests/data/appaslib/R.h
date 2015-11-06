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

#ifndef __APPASLIB_R_H
#define __APPASLIB_R_H

namespace appaslib {
namespace R {
namespace lib {
namespace integer {
    enum {
        number1     = 0x02020000,   // default
    };
}

namespace array {
    enum {
        integerArray1 = 0x02030000,   // default
    };
}
} // namespace lib

namespace app {
namespace integer {
    enum {
        number1     = 0x7f020000,     // default
    };
}

namespace array {
    enum {
        integerArray1 = 0x7f030000,   // default
    };
}
} // namespace app
} // namespace R
} // namespace appaslib

#endif // __APPASLIB_R_H
