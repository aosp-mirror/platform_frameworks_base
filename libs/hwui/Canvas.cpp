/*
 * Copyright (C) 2015 The Android Open Source Project
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

#include "Canvas.h"

#include "DisplayListCanvas.h"
#include "RecordingCanvas.h"
#include <SkDrawFilter.h>

namespace android {

Canvas* Canvas::create_recording_canvas(int width, int height) {
#if HWUI_NEW_OPS
    return new uirenderer::RecordingCanvas(width, height);
#else
    return new uirenderer::DisplayListCanvas(width, height);
#endif
}

void Canvas::drawTextDecorations(float x, float y, float length, const SkPaint& paint) {
    uint32_t flags;
    SkDrawFilter* drawFilter = getDrawFilter();
    if (drawFilter) {
        SkPaint paintCopy(paint);
        drawFilter->filter(&paintCopy, SkDrawFilter::kText_Type);
        flags = paintCopy.getFlags();
    } else {
        flags = paint.getFlags();
    }
    if (flags & (SkPaint::kUnderlineText_Flag | SkPaint::kStrikeThruText_Flag)) {
        // Same values used by Skia
        const float kStdStrikeThru_Offset   = (-6.0f / 21.0f);
        const float kStdUnderline_Offset    = (1.0f / 9.0f);
        const float kStdUnderline_Thickness = (1.0f / 18.0f);

        SkScalar left = x;
        SkScalar right = x + length;
        float textSize = paint.getTextSize();
        float strokeWidth = fmax(textSize * kStdUnderline_Thickness, 1.0f);
        if (flags & SkPaint::kUnderlineText_Flag) {
            SkScalar top = y + textSize * kStdUnderline_Offset - 0.5f * strokeWidth;
            SkScalar bottom = y + textSize * kStdUnderline_Offset + 0.5f * strokeWidth;
            drawRect(left, top, right, bottom, paint);
        }
        if (flags & SkPaint::kStrikeThruText_Flag) {
            SkScalar top = y + textSize * kStdStrikeThru_Offset - 0.5f * strokeWidth;
            SkScalar bottom = y + textSize * kStdStrikeThru_Offset + 0.5f * strokeWidth;
            drawRect(left, top, right, bottom, paint);
        }
    }
}

} // namespace android
