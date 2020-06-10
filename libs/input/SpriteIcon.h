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

#ifndef _UI_SPRITE_ICON_H
#define _UI_SPRITE_ICON_H

#include <SkBitmap.h>
#include <gui/Surface.h>

namespace android {

/*
 * Icon that a sprite displays, including its hotspot.
 */
struct SpriteIcon {
    inline SpriteIcon() : style(0), hotSpotX(0), hotSpotY(0) { }
    inline SpriteIcon(const SkBitmap& bitmap, int32_t style, float hotSpotX, float hotSpotY) :
            bitmap(bitmap), style(style), hotSpotX(hotSpotX), hotSpotY(hotSpotY) { }

    SkBitmap bitmap;
    int32_t style;
    float hotSpotX;
    float hotSpotY;

    inline SpriteIcon copy() const {
        SkBitmap bitmapCopy;
        if (bitmapCopy.tryAllocPixels(bitmap.info().makeColorType(kN32_SkColorType))) {
            bitmap.readPixels(bitmapCopy.info(), bitmapCopy.getPixels(), bitmapCopy.rowBytes(),
                    0, 0);
        }
        return SpriteIcon(bitmapCopy, style, hotSpotX, hotSpotY);
    }

    inline void reset() {
        bitmap.reset();
        style = 0;
        hotSpotX = 0;
        hotSpotY = 0;
    }

    inline bool isValid() const { return !bitmap.isNull() && !bitmap.empty(); }

    inline int32_t width() const { return bitmap.width(); }
    inline int32_t height() const { return bitmap.height(); }

    // Draw the bitmap onto the given surface. Returns true if it's successful, or false otherwise.
    // Note it doesn't set any metadata to the surface.
    bool draw(const sp<Surface> surface) const;
};


} // namespace android

#endif // _UI_SPRITE_ICON_H
