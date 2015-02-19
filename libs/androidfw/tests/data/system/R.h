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

#ifndef __ANDROID_R_H
#define __ANDROID_R_H

namespace android {
namespace R {

namespace attr {
    enum {
        background  = 0x01010000, // default
        foreground  = 0x01010001, // default
    };
}

namespace style {
    enum {
        Theme_One      = 0x01020000,   // default
    };
}

} // namespace R
} // namespace android

#endif // __ANDROID_R_H
