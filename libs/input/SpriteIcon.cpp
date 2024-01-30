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

#include <android/graphics/bitmap.h>
#include <android/graphics/canvas.h>
#include <android/graphics/paint.h>
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

    graphics::Paint paint;
    paint.setBlendMode(ABLEND_MODE_SRC);
    if (drawNativeDropShadow) {
        paint.setImageFilter(AIMAGE_FILTER_DROP_SHADOW_FOR_POINTER_ICON);
    }

    graphics::Canvas canvas(outBuffer, (int32_t)surface->getBuffersDataSpace());
    canvas.drawBitmap(bitmap, 0, 0, &paint);

    const int iconWidth = width();
    const int iconHeight = height();

    if (outBuffer.width > iconWidth) {
        paint.setBlendMode(ABLEND_MODE_CLEAR); // clear to transparent
        canvas.drawRect({iconWidth, 0, outBuffer.width, iconHeight}, paint);
    }
    if (outBuffer.height > iconHeight) {
        paint.setBlendMode(ABLEND_MODE_CLEAR); // clear to transparent
        canvas.drawRect({0, iconHeight, outBuffer.width, outBuffer.height}, paint);
    }

    status = surface->unlockAndPost();
    if (status) {
        ALOGE("Error %d unlocking and posting sprite surface after drawing.", status);
    }
    return !status;
}

} // namespace android
