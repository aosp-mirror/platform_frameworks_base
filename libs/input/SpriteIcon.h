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

#include <android/graphics/bitmap.h>
#include <gui/Surface.h>
#include <input/Input.h>

namespace android {

/*
 * Icon that a sprite displays, including its hotspot.
 */
struct SpriteIcon {
    inline SpriteIcon() : style(PointerIconStyle::TYPE_NULL), hotSpotX(0), hotSpotY(0) {}
    inline SpriteIcon(const graphics::Bitmap& bitmap, PointerIconStyle style, float hotSpotX,
                      float hotSpotY, bool drawNativeDropShadow)
          : bitmap(bitmap),
            style(style),
            hotSpotX(hotSpotX),
            hotSpotY(hotSpotY),
            drawNativeDropShadow(drawNativeDropShadow) {}

    graphics::Bitmap bitmap;
    PointerIconStyle style;
    float hotSpotX;
    float hotSpotY;
    bool drawNativeDropShadow;

    inline SpriteIcon copy() const {
        return SpriteIcon(bitmap.copy(ANDROID_BITMAP_FORMAT_RGBA_8888), style, hotSpotX, hotSpotY,
                          drawNativeDropShadow);
    }

    inline void reset() {
        bitmap.reset();
        style = PointerIconStyle::TYPE_NULL;
        hotSpotX = 0;
        hotSpotY = 0;
        drawNativeDropShadow = false;
    }

    inline bool isValid() const { return bitmap.isValid() && !bitmap.isEmpty(); }

    inline int32_t width() const { return bitmap.getInfo().width; }
    inline int32_t height() const { return bitmap.getInfo().height; }

    // Draw the bitmap onto the given surface. Returns true if it's successful, or false otherwise.
    // Note it doesn't set any metadata to the surface.
    bool draw(const sp<Surface> surface) const;
};

} // namespace android

#endif // _UI_SPRITE_ICON_H
