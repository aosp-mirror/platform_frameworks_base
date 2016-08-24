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
#ifndef DEVICEINFO_H
#define DEVICEINFO_H

#include "Extensions.h"
#include "utils/Macros.h"

namespace android {
namespace uirenderer {

class DeviceInfo {
    PREVENT_COPY_AND_ASSIGN(DeviceInfo);
public:
    // returns nullptr if DeviceInfo is not initialized yet
    // Note this does not have a memory fence so it's up to the caller
    // to use one if required. Normally this should not be necessary
    static const DeviceInfo* get();

    // only call this after GL has been initialized, or at any point if compiled
    // with HWUI_NULL_GPU
    static void initialize();

    const Extensions& extensions() const { return mExtensions; }

    int maxTextureSize() const { return mMaxTextureSize; }

private:
    DeviceInfo() {}
    ~DeviceInfo() {}

    void load();

    Extensions mExtensions;
    int mMaxTextureSize;
};

} /* namespace uirenderer */
} /* namespace android */

#endif /* DEVICEINFO_H */
