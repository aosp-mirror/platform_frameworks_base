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

namespace android::uirenderer {

// Must match the constants in ActivityInfo.java
enum class ColorMode {
    // SRGB means HWUI will produce buffer in SRGB color space.
    Default = 0,
    // WideColorGamut selects the most optimal colorspace & format for the device's display
    // Most commonly DisplayP3 + RGBA_8888 currently.
    WideColorGamut = 1,
    // HDR Rec2020 + F16
    Hdr = 2,
    // HDR Rec2020 + 1010102
    Hdr10 = 3,
};

} // namespace android::uirenderer
