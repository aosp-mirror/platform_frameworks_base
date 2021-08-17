/*
 * Copyright (C) 2020 The Android Open Source Project
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

#pragma once

#include <inttypes.h>

// TODO: Move this to an enum class
namespace android::SaveFlags {

// These must match the corresponding Canvas API constants.
enum {
    Matrix = 0x01,
    Clip = 0x02,
    HasAlphaLayer = 0x04,
    ClipToLayer = 0x10,

    // Helper constant
    MatrixClip = Matrix | Clip,
};
typedef uint32_t Flags;

}  // namespace android::SaveFlags
