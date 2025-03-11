/*
 * Copyright 2024 The Android Open Source Project
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

#ifdef __ANDROID__
#include <dlfcn.h>
#include <log/log.h>
#include <statslog_hwui.h>
#include <statssocket_lazy.h>
#include <utils/Errors.h>

#include <mutex>
#endif

#include <unistd.h>

#include "StatsUtils.h"

namespace android {
namespace uirenderer {

#ifdef __ANDROID__

namespace {

int32_t toStatsColorSpaceTransfer(skcms_TFType transferType) {
    switch (transferType) {
        case skcms_TFType_sRGBish:
            return stats::IMAGE_DECODED__COLOR_SPACE_TRANSFER__COLOR_SPACE_TRANSFER_SRGBISH;
        case skcms_TFType_PQish:
            return stats::IMAGE_DECODED__COLOR_SPACE_TRANSFER__COLOR_SPACE_TRANSFER_PQISH;
        case skcms_TFType_HLGish:
            return stats::IMAGE_DECODED__COLOR_SPACE_TRANSFER__COLOR_SPACE_TRANSFER_HLGISH;
        default:
            return stats::IMAGE_DECODED__COLOR_SPACE_TRANSFER__COLOR_SPACE_TRANSFER_UNKNOWN;
    }
}

int32_t toStatsBitmapFormat(SkColorType type) {
    switch (type) {
        case kAlpha_8_SkColorType:
            return stats::IMAGE_DECODED__FORMAT__BITMAP_FORMAT_A_8;
        case kRGB_565_SkColorType:
            return stats::IMAGE_DECODED__FORMAT__BITMAP_FORMAT_RGB_565;
        case kN32_SkColorType:
            return stats::IMAGE_DECODED__FORMAT__BITMAP_FORMAT_ARGB_8888;
        case kRGBA_F16_SkColorType:
            return stats::IMAGE_DECODED__FORMAT__BITMAP_FORMAT_RGBA_F16;
        case kRGBA_1010102_SkColorType:
            return stats::IMAGE_DECODED__FORMAT__BITMAP_FORMAT_RGBA_1010102;
        default:
            return stats::IMAGE_DECODED__FORMAT__BITMAP_FORMAT_UNKNOWN;
    }
}

}  // namespace

#endif

void logBitmapDecode(const SkImageInfo& info, bool hasGainmap) {
#ifdef __ANDROID__

    if (!statssocket::lazy::IsAvailable()) {
        std::once_flag once;
        std::call_once(once, []() { ALOGD("libstatssocket not available, dropping stats"); });
        return;
    }

    skcms_TFType tfnType = skcms_TFType_Invalid;

    if (info.colorSpace()) {
        skcms_TransferFunction tfn;
        info.colorSpace()->transferFn(&tfn);
        tfnType = skcms_TransferFunction_getType(&tfn);
    }

    auto status =
            stats::stats_write(uirenderer::stats::IMAGE_DECODED, static_cast<int32_t>(getuid()),
                               uirenderer::toStatsColorSpaceTransfer(tfnType), hasGainmap,
                               uirenderer::toStatsBitmapFormat(info.colorType()));
    ALOGW_IF(status != OK, "Image decoding logging dropped!");
#endif
}

void logBitmapDecode(const Bitmap& bitmap) {
    logBitmapDecode(bitmap.info(), bitmap.hasGainmap());
}

}  // namespace uirenderer
}  // namespace android
