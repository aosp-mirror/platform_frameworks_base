/*
 * Copyright (C) 2014 The Android Open Source Project
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
#ifndef PAINT_UTILS_H
#define PAINT_UTILS_H

#include <SkColorFilter.h>
#include <SkXfermode.h>

namespace android {
namespace uirenderer {

class PaintUtils {
public:

   /**
     * Safely retrieves the mode from the specified xfermode. If the specified
     * xfermode is null, the mode is assumed to be SkXfermode::kSrcOver_Mode.
     */
    static inline SkXfermode::Mode getXfermode(SkXfermode* mode) {
        SkXfermode::Mode resultMode;
        if (!SkXfermode::AsMode(mode, &resultMode)) {
            resultMode = SkXfermode::kSrcOver_Mode;
        }
        return resultMode;
    }

    static inline GLenum getFilter(const SkPaint* paint) {
        if (!paint || paint->getFilterQuality() != kNone_SkFilterQuality) {
            return GL_LINEAR;
        }
        return GL_NEAREST;
    }

    // TODO: move to a method on android:Paint? replace with SkPaint::nothingToDraw()?
    static inline bool paintWillNotDraw(const SkPaint& paint) {
        return paint.getAlpha() == 0
                && !paint.getColorFilter()
                && getXfermode(paint.getXfermode()) == SkXfermode::kSrcOver_Mode;
    }

    // TODO: move to a method on android:Paint? replace with SkPaint::nothingToDraw()?
    static inline bool paintWillNotDrawText(const SkPaint& paint) {
        return paint.getAlpha() == 0
                && paint.getLooper() == nullptr
                && !paint.getColorFilter()
                && getXfermode(paint.getXfermode()) == SkXfermode::kSrcOver_Mode;
    }

    static bool isBlendedShader(const SkShader* shader) {
        if (shader == nullptr) {
            return false;
        }
        return !shader->isOpaque();
    }

    static bool isBlendedColorFilter(const SkColorFilter* filter) {
        if (filter == nullptr) {
            return false;
        }
        return (filter->getFlags() & SkColorFilter::kAlphaUnchanged_Flag) == 0;
    }

}; // class PaintUtils

} /* namespace uirenderer */
} /* namespace android */

#endif /* PAINT_UTILS_H */
