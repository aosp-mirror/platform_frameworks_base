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

namespace android::uirenderer {

sp<Gainmap> Gainmap::allocateHardwareGainmap(const sp<Gainmap>& srcGainmap) {
    auto gainmap = sp<Gainmap>::make();
    gainmap->info = srcGainmap->info;
    const SkBitmap skSrcBitmap = srcGainmap->bitmap->getSkBitmap();
    sk_sp<Bitmap> skBitmap(Bitmap::allocateHardwareBitmap(skSrcBitmap));
    if (!skBitmap.get()) {
        return nullptr;
    }
    gainmap->bitmap = std::move(skBitmap);
    return gainmap;
}

}  // namespace android::uirenderer