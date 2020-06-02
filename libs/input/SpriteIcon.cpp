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

#include "SpriteIcon.h"

#include <SkBitmap.h>
#include <SkCanvas.h>
#include <SkColor.h>
#include <SkPaint.h>

#include <android/native_window.h>
#include <log/log.h>

namespace android {

bool SpriteIcon::draw(sp<Surface> surface) const {
    ANativeWindow_Buffer outBuffer;
    status_t status = surface->lock(&outBuffer, NULL);
    if (status) {
        ALOGE("Error %d locking sprite surface before drawing.", status);
        return false;
    }

    SkBitmap surfaceBitmap;
    ssize_t bpr = outBuffer.stride * bytesPerPixel(outBuffer.format);
    surfaceBitmap.installPixels(SkImageInfo::MakeN32Premul(outBuffer.width, outBuffer.height),
                                outBuffer.bits, bpr);

    SkCanvas surfaceCanvas(surfaceBitmap);

    SkPaint paint;
    paint.setBlendMode(SkBlendMode::kSrc);
    surfaceCanvas.drawBitmap(bitmap, 0, 0, &paint);

    if (outBuffer.width > width()) {
        paint.setColor(0); // transparent fill color
        surfaceCanvas.drawRect(SkRect::MakeLTRB(width(), 0, outBuffer.width, height()), paint);
    }
    if (outBuffer.height > height()) {
        paint.setColor(0); // transparent fill color
        surfaceCanvas.drawRect(SkRect::MakeLTRB(0, height(), outBuffer.width, outBuffer.height),
                               paint);
    }

    status = surface->unlockAndPost();
    if (status) {
        ALOGE("Error %d unlocking and posting sprite surface after drawing.", status);
    }
    return !status;
}

} // namespace android
