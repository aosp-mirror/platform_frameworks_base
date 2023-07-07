/*
 * Copyright (C) 2023 The Android Open Source Project
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

#include <SkGainmapInfo.h>
#include <SkImage.h>
#include <hwui/Bitmap.h>
#include <utils/LightRefBase.h>

namespace android::uirenderer {

class Gainmap : public LightRefBase<Gainmap> {
public:
    SkGainmapInfo info;
    sk_sp<Bitmap> bitmap;
    static sp<Gainmap> allocateHardwareGainmap(const sp<Gainmap>& srcGainmap);
};

}  // namespace android::uirenderer
