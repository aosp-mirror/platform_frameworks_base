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

/**
 * This is the implementation of the Typeface object. Historically, it has
 * just been SkTypeface, but we are migrating to Minikin. For the time
 * being, that choice is hidden under the USE_MINIKIN compile-time flag.
 */

#include "Typeface.h"

#include <pthread.h>

#include "MinikinSkia.h"
#include "SkTypeface.h"
#include "SkPaint.h"

#include <minikin/FontCollection.h>
#include <minikin/FontFamily.h>
#include <minikin/Layout.h>
#include <utils/Log.h>

namespace android {

// Resolve the 1..9 weight based on base weight and bold flag
static void resolveStyle(Typeface* typeface) {
    int weight = typeface->fBaseWeight / 100;
    if (typeface->fSkiaStyle & SkTypeface::kBold) {
        weight += 3;
    }
    if (weight > 9) {
        weight = 9;
    }
    bool italic = (typeface->fSkiaStyle & SkTypeface::kItalic) != 0;
    typeface->fStyle = minikin::FontStyle(weight, italic);
}

Typeface* gDefaultTypeface = NULL;

Typeface* Typeface::resolveDefault(Typeface* src) {
    LOG_ALWAYS_FATAL_IF(gDefaultTypeface == nullptr);
    return src == nullptr ? gDefaultTypeface : src;
}

Typeface* Typeface::createFromTypeface(Typeface* src, SkTypeface::Style style) {
    Typeface* resolvedFace = Typeface::resolveDefault(src);
    Typeface* result = new Typeface;
    if (result != 0) {
        result->fFontCollection = resolvedFace->fFontCollection;
        result->fFontCollection->Ref();
        result->fSkiaStyle = style;
        result->fBaseWeight = resolvedFace->fBaseWeight;
        resolveStyle(result);
    }
    return result;
}

Typeface* Typeface::createWeightAlias(Typeface* src, int weight) {
    Typeface* resolvedFace = Typeface::resolveDefault(src);
    Typeface* result = new Typeface;
    if (result != 0) {
        result->fFontCollection = resolvedFace->fFontCollection;
        result->fFontCollection->Ref();
        result->fSkiaStyle = resolvedFace->fSkiaStyle;
        result->fBaseWeight = weight;
        resolveStyle(result);
    }
    return result;
}

Typeface* Typeface::createFromFamilies(const std::vector<minikin::FontFamily*>& families) {
    Typeface* result = new Typeface;
    result->fFontCollection = new minikin::FontCollection(families);
    if (families.empty()) {
        ALOGW("createFromFamilies creating empty collection");
        result->fSkiaStyle = SkTypeface::kNormal;
    } else {
        const minikin::FontStyle defaultStyle;
        minikin::FontFamily* firstFamily = reinterpret_cast<minikin::FontFamily*>(families[0]);
        minikin::MinikinFont* mf = firstFamily->getClosestMatch(defaultStyle).font;
        if (mf != NULL) {
            SkTypeface* skTypeface = reinterpret_cast<MinikinFontSkia*>(mf)->GetSkTypeface();
            // TODO: probably better to query more precise style from family, will be important
            // when we open up API to access 100..900 weights
            result->fSkiaStyle = skTypeface->style();
        } else {
            result->fSkiaStyle = SkTypeface::kNormal;
        }
    }
    result->fBaseWeight = 400;
    resolveStyle(result);
    return result;
}

void Typeface::unref() {
    fFontCollection->Unref();
    delete this;
}

void Typeface::setDefault(Typeface* face) {
    gDefaultTypeface = face;
}

}
