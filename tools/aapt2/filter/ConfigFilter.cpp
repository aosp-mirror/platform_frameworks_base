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

#include "ConfigDescription.h"
#include "filter/ConfigFilter.h"

#include <androidfw/ResourceTypes.h>

namespace aapt {

void AxisConfigFilter::addConfig(ConfigDescription config) {
    uint32_t diffMask = ConfigDescription::defaultConfig().diff(config);

    // Ignore the version
    diffMask &= ~android::ResTable_config::CONFIG_VERSION;

    // Ignore any densities. Those are best handled in --preferred-density
    if ((diffMask & android::ResTable_config::CONFIG_DENSITY) != 0) {
        config.density = 0;
        diffMask &= ~android::ResTable_config::CONFIG_DENSITY;
    }

    mConfigs.insert(std::make_pair(config, diffMask));
    mConfigMask |= diffMask;
}

bool AxisConfigFilter::match(const ConfigDescription& config) const {
    const uint32_t mask = ConfigDescription::defaultConfig().diff(config);
    if ((mConfigMask & mask) == 0) {
        // The two configurations don't have any common axis.
        return true;
    }

    uint32_t matchedAxis = 0;
    for (const auto& entry : mConfigs) {
        const ConfigDescription& target = entry.first;
        const uint32_t diffMask = entry.second;
        uint32_t diff = target.diff(config);
        if ((diff & diffMask) == 0) {
            // Mark the axis that was matched.
            matchedAxis |= diffMask;
        } else if ((diff & diffMask) == android::ResTable_config::CONFIG_LOCALE) {
            // If the locales differ, but the languages are the same and
            // the locale we are matching only has a language specified,
            // we match.
            if (config.language[0] &&
                    memcmp(config.language, target.language, sizeof(config.language)) == 0) {
                if (config.country[0] == 0) {
                    matchedAxis |= android::ResTable_config::CONFIG_LOCALE;
                }
            }
        } else if ((diff & diffMask) == android::ResTable_config::CONFIG_SMALLEST_SCREEN_SIZE) {
            // Special case if the smallest screen width doesn't match. We check that the
            // config being matched has a smaller screen width than the filter specified.
            if (config.smallestScreenWidthDp != 0 &&
                    config.smallestScreenWidthDp < target.smallestScreenWidthDp) {
                matchedAxis |= android::ResTable_config::CONFIG_SMALLEST_SCREEN_SIZE;
            }
        }
    }
    return matchedAxis == (mConfigMask & mask);
}

} // namespace aapt
