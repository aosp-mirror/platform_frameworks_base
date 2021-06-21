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

#include <utils/NdkUtils.h>

namespace android {
namespace uirenderer {

UniqueAHardwareBuffer allocateAHardwareBuffer(const AHardwareBuffer_Desc& desc) {
    AHardwareBuffer* buffer;
    if (AHardwareBuffer_allocate(&desc, &buffer) != 0) {
        return nullptr;
    } else {
        return UniqueAHardwareBuffer{buffer};
    }
}

}  // namespace uirenderer
}  // namespace android
