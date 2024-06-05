/*
 * Copyright (C) 2022 The Android Open Source Project
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

#include "MemoryPolicy.h"

#include <android-base/properties.h>

#include <optional>
#include <string_view>

#include "Properties.h"

namespace android::uirenderer {

constexpr static MemoryPolicy sDefaultMemoryPolicy;
constexpr static MemoryPolicy sPersistentOrSystemPolicy{
        .contextTimeout = 10_s,
        .minimumResourceRetention = 1_s,
        .maximumResourceRetention = 10_s,
        .useAlternativeUiHidden = true,
        .purgeScratchOnly = false,
};
constexpr static MemoryPolicy sLowRamPolicy{
        .useAlternativeUiHidden = true,
        .purgeScratchOnly = false,
};
constexpr static MemoryPolicy sExtremeLowRam{
        .initialMaxSurfaceAreaScale = 0.2f,
        .surfaceSizeMultiplier = 5 * 4.0f,
        .backgroundRetentionPercent = 0.2f,
        .contextTimeout = 5_s,
        .minimumResourceRetention = 1_s,
        .useAlternativeUiHidden = true,
        .purgeScratchOnly = false,
        .releaseContextOnStoppedOnly = true,
};

const MemoryPolicy& loadMemoryPolicy() {
    if (Properties::isSystemOrPersistent) {
        return sPersistentOrSystemPolicy;
    }
    std::string memoryPolicy = base::GetProperty(PROPERTY_MEMORY_POLICY, "");
    if (memoryPolicy == "default") {
        return sDefaultMemoryPolicy;
    }
    if (memoryPolicy == "lowram") {
        return sLowRamPolicy;
    }
    if (memoryPolicy == "extremelowram") {
        return sExtremeLowRam;
    }

    if (Properties::isLowRam) {
        return sLowRamPolicy;
    }
    return sDefaultMemoryPolicy;
}

}  // namespace android::uirenderer