/*
 * Copyright (C) 2013 The Android Open Source Project
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


#ifndef _ANDROID_GRAPHICS_TYPEFACE_IMPL_H_
#define _ANDROID_GRAPHICS_TYPEFACE_IMPL_H_

#include "SkTypeface.h"

#include <cutils/compiler.h>
#include <minikin/FontCollection.h>
#include <vector>

namespace android {

struct ANDROID_API Typeface {
    FontCollection *fFontCollection;

    // style used for constructing and querying Typeface objects
    SkTypeface::Style fSkiaStyle;
    // base weight in CSS-style units, 100..900
    int fBaseWeight;

    // resolved style actually used for rendering
    FontStyle fStyle;

    void unref();

    static Typeface* resolveDefault(Typeface* src);

    static Typeface* createFromTypeface(Typeface* src, SkTypeface::Style style);

    static Typeface* createWeightAlias(Typeface* src, int baseweight);

    static Typeface* createFromFamilies(const std::vector<FontFamily*>& families);

    static void setDefault(Typeface* face);
};

}

#endif  // _ANDROID_GRAPHICS_TYPEFACE_IMPL_H_
