/**
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
#include "Gainmap.h"

#include <SkBitmap.h>
#include <SkCanvas.h>
#include <SkColorFilter.h>
#include <SkImagePriv.h>
#include <SkPaint.h>

#include "HardwareBitmapUploader.h"

namespace android::uirenderer {

sp<Gainmap> Gainmap::allocateHardwareGainmap(const sp<Gainmap>& srcGainmap) {
    auto gainmap = sp<Gainmap>::make();
    gainmap->info = srcGainmap->info;
    SkBitmap skSrcBitmap = srcGainmap->bitmap->getSkBitmap();
    if (skSrcBitmap.info().colorType() == kAlpha_8_SkColorType &&
        !HardwareBitmapUploader::hasAlpha8Support()) {
        // The regular Bitmap::allocateHardwareBitmap will do a conversion that preserves channels,
        // so alpha8 maps to the alpha channel of rgba. However, for gainmaps we will interpret
        // the data of an rgba buffer differently as we'll only look at the rgb channels
        // So we need to map alpha8 to rgbx_8888 essentially
        SkBitmap bitmap;
        bitmap.allocPixels(skSrcBitmap.info().makeColorType(kN32_SkColorType));
        SkCanvas canvas(bitmap);
        SkPaint paint;
        const float alphaToOpaque[] = {0, 0, 0, 1, 0, 0, 0, 0, 1, 0,
                                       0, 0, 0, 1, 0, 0, 0, 0, 0, 255};
        paint.setColorFilter(SkColorFilters::Matrix(alphaToOpaque, SkColorFilters::Clamp::kNo));
        canvas.drawImage(SkMakeImageFromRasterBitmap(skSrcBitmap, kNever_SkCopyPixelsMode), 0, 0,
                         SkSamplingOptions{}, &paint);
        skSrcBitmap = bitmap;
    }
    sk_sp<Bitmap> skBitmap(Bitmap::allocateHardwareBitmap(skSrcBitmap));
    if (!skBitmap.get()) {
        return nullptr;
    }
    gainmap->bitmap = std::move(skBitmap);
    return gainmap;
}

}  // namespace android::uirenderer