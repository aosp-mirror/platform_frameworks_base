/**
 * Copyright (C) 2017 The Android Open Source Project
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

#define LOG_TAG "BroadcastRadioService.regions.jni"
#define LOG_NDEBUG 0

#include "regions.h"

#include <broadcastradio-utils-1x/Utils.h>
#include <utils/Log.h>

namespace android {
namespace server {
namespace BroadcastRadio {
namespace regions {

namespace utils = hardware::broadcastradio::utils;

using hardware::hidl_vec;

using V1_0::Band;
using V1_0::BandConfig;
using V1_0::Deemphasis;
using V1_0::Rds;

class RegionalBandDefinition {
public:
    std::vector<Region> mRegions;
    std::vector<Band> mTypes;
    uint32_t mLowerLimit;
    uint32_t mUpperLimit;
    uint32_t mSpacing;

    Deemphasis mFmDeemphasis = {};
    Rds mFmRds = Rds::NONE;

    bool fitsInsideBand(const BandConfig &bandConfig) const;
    std::vector<RegionalBandConfig> withConfig(BandConfig bandConfig) const;
};

static const RegionalBandDefinition kKnownRegionConfigs[] = {
    {
        { Region::ITU_1 },
        { Band::FM },
        87500,
        108000,
        100,
        Deemphasis::D50,
        Rds::WORLD,
    },
    {
        { Region::ITU_2 },
        { Band::FM, Band::FM_HD },
        87700,
        107900,
        200,
        Deemphasis::D75,
        Rds::US,
    },
    {
        { Region::OIRT },
        { Band::FM },
        65800,
        74000,
        10,
        Deemphasis::D50,
        Rds::WORLD,
    },
    {
        { Region::JAPAN },
        { Band::FM },
        76000,
        90000,
        100,
        Deemphasis::D50,
        Rds::WORLD,
    },
    {
        { Region::KOREA },
        { Band::FM },
        87500,
        108000,
        100,
        Deemphasis::D75,
        Rds::WORLD,
    },
    {  // AM LW
        { Region::ITU_1, Region::OIRT, Region::JAPAN, Region::KOREA },
        { Band::AM },
        153,
        282,
        9,
    },
    {  // AM MW
        { Region::ITU_1, Region::OIRT, Region::JAPAN, Region::KOREA },
        { Band::AM },
        531,
        1620,
        9,
    },
    {  // AM SW
        { Region::ITU_1, Region::OIRT, Region::JAPAN, Region::KOREA },
        { Band::AM },
        2300,
        26100,
        5,
    },
    {  // AM LW ITU2
        { Region::ITU_2 },
        { Band::AM, Band::AM_HD },
        153,
        279,
        9,
    },
    {  // AM MW ITU2
        { Region::ITU_2 },
        { Band::AM, Band::AM_HD },
        530,
        1700,
        10,
    },
    {  // AM SW ITU2
        { Region::ITU_2 },
        { Band::AM, Band::AM_HD },
        2300,
        26100,
        5,
    },
};

bool RegionalBandDefinition::fitsInsideBand(const BandConfig &bandConfig) const {
    if (std::find(mTypes.begin(), mTypes.end(), bandConfig.type) == mTypes.end()) return false;
    if (mLowerLimit < bandConfig.lowerLimit) return false;
    if (mUpperLimit > bandConfig.upperLimit) return false;
    auto&& spacings = bandConfig.spacings;
    if (std::find(spacings.begin(), spacings.end(), mSpacing) == spacings.end()) return false;
    if (utils::isFm(bandConfig.type)) {
        if (0 == (mFmDeemphasis & bandConfig.ext.fm.deemphasis)) return false;
    }

    return true;
}

std::vector<RegionalBandConfig> RegionalBandDefinition::withConfig(BandConfig config) const {
    config.lowerLimit = mLowerLimit;
    config.upperLimit = mUpperLimit;
    config.spacings = hidl_vec<uint32_t>({ mSpacing });
    if (utils::isFm(config.type)) {
        auto&& fm = config.ext.fm;
        fm.deemphasis = mFmDeemphasis;
        fm.rds = static_cast<Rds>(mFmRds & fm.rds);
    }

    std::vector<RegionalBandConfig> configs;
    for (auto region : mRegions) {
        configs.push_back({region, config});
    }

    return configs;
}

std::vector<RegionalBandConfig> mapRegions(const hidl_vec<BandConfig>& bands) {
    ALOGV("%s", __func__);

    std::vector<RegionalBandConfig> out;

    for (auto&& regionalBand : kKnownRegionConfigs) {
        for (auto&& tunerBand : bands) {
            if (regionalBand.fitsInsideBand(tunerBand)) {
                auto mapped = regionalBand.withConfig(tunerBand);
                out.insert(out.end(), mapped.begin(), mapped.end());
            }
        }
    }

    ALOGI("Mapped %zu tuner bands to %zu regional bands", bands.size(), out.size());
    return out;
}

} // namespace regions
} // namespace BroadcastRadio
} // namespace server
} // namespace android
