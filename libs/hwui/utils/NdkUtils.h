/*
 * Copyright 2020 The Android Open Source Project
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

#include <android/hardware_buffer.h>

#include <memory>

namespace android {
namespace uirenderer {

// Deleter for an AHardwareBuffer, to be passed to an std::unique_ptr.
struct AHardwareBuffer_deleter {
    void operator()(AHardwareBuffer* ahb) const { AHardwareBuffer_release(ahb); }
};

using UniqueAHardwareBuffer = std::unique_ptr<AHardwareBuffer, AHardwareBuffer_deleter>;

// Allocates a UniqueAHardwareBuffer with the provided buffer description.
// Returns nullptr if allocation did not succeed.
UniqueAHardwareBuffer allocateAHardwareBuffer(const AHardwareBuffer_Desc& desc);

}  // namespace uirenderer
}  // namespace android
