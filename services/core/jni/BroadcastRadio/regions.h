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

#ifndef _ANDROID_SERVER_BROADCASTRADIO_REGIONS_H
#define _ANDROID_SERVER_BROADCASTRADIO_REGIONS_H

#include "types.h"

#include <android/hardware/broadcastradio/1.1/types.h>

namespace android {
namespace server {
namespace BroadcastRadio {
namespace regions {

namespace V1_0 = hardware::broadcastradio::V1_0;

struct RegionalBandConfig {
    Region region;
    V1_0::BandConfig bandConfig;
};

std::vector<RegionalBandConfig>
mapRegions(const hardware::hidl_vec<V1_0::BandConfig>& bands);

} // namespace regions
} // namespace BroadcastRadio
} // namespace server
} // namespace android

#endif // _ANDROID_SERVER_BROADCASTRADIO_REGIONS_H
