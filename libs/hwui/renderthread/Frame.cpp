/*
 * Copyright (C) 2016 The Android Open Source Project
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

#include "Frame.h"
#include <SkRect.h>

namespace android {
namespace uirenderer {
namespace renderthread {

void Frame::map(const SkRect& in, int32_t* out) const {
    /* The rectangles are specified relative to the bottom-left of the surface
     * and the x and y components of each rectangle specify the bottom-left
     * position of that rectangle.
     *
     * HWUI does everything with 0,0 being top-left, so need to map
     * the rect
     */
    SkIRect idirty;
    in.roundOut(&idirty);
    int32_t y = mHeight - (idirty.y() + idirty.height());
    // layout: {x, y, width, height}
    out[0] = idirty.x();
    out[1] = y;
    out[2] = idirty.width();
    out[3] = idirty.height();
}

} /* namespace renderthread */
} /* namespace uirenderer */
} /* namespace android */
