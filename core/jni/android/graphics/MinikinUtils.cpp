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

#include "SkPaint.h"
#include "minikin/Layout.h"
#include "TypefaceImpl.h"

#include "MinikinUtils.h"

namespace android {

void MinikinUtils::SetLayoutProperties(Layout* layout, SkPaint* paint, int flags,
    TypefaceImpl* typeface) {
    TypefaceImpl* resolvedFace = TypefaceImpl_resolveDefault(typeface);
    layout->setFontCollection(resolvedFace->fFontCollection);
    FontStyle style = resolvedFace->fStyle;
    char css[256];
    sprintf(css, "font-size: %d; font-weight: %d; font-style: %s; -minikin-bidi: %d",
        (int)paint->getTextSize(),
        style.getWeight() * 100,
        style.getItalic() ? "italic" : "normal",
        flags);
    layout->setProperties(css);
}

}