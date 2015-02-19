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

#ifndef __BASE_R_H
#define __BASE_R_H

namespace base {
namespace R {

namespace attr {
    enum {
        attr1       = 0x7f010000, // default
        attr2       = 0x7f010001, // default
    };
}

namespace layout {
    enum {
        main        = 0x7f020000,  // default, fr-sw600dp-v13
    };
}

namespace string {
    enum {
        test1       = 0x7f030000,   // default
        test2       = 0x7f030001,   // default
        density     = 0x7f030002,   // default

        test3       = 0x7f080000,   // default (in feature)
        test4       = 0x7f080001,   // default (in feature)
    };
}

namespace integer {
    enum {
        number1     = 0x7f040000,   // default, sv
        number2     = 0x7f040001,   // default

        test3       = 0x7f090000,   // default (in feature)
    };
}

namespace style {
    enum {
        Theme1      = 0x7f050000,   // default
        Theme2      = 0x7f050001,   // default
    };
}

namespace array {
    enum {
        integerArray1 = 0x7f060000,   // default
    };
}

} // namespace R
} // namespace base

#endif // __BASE_R_H
