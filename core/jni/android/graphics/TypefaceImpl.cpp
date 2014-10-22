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

#define LOG_TAG "TypefaceImpl"

#include "jni.h"  // for jlong, remove when being passed proper type

#include "SkStream.h"
#include "SkTypeface.h"

#include <vector>
#include <minikin/FontCollection.h>
#include <minikin/FontFamily.h>
#include <minikin/Layout.h>
#include "SkPaint.h"
#include "MinikinSkia.h"

#include "TypefaceImpl.h"
#include "Utils.h"

namespace android {

// Resolve the 1..9 weight based on base weight and bold flag
static void resolveStyle(TypefaceImpl* typeface) {
    int weight = typeface->fBaseWeight / 100;
    if (typeface->fSkiaStyle & SkTypeface::kBold) {
        weight += 3;
    }
    if (weight > 9) {
        weight = 9;
    }
    bool italic = (typeface->fSkiaStyle & SkTypeface::kItalic) != 0;
    typeface->fStyle = FontStyle(weight, italic);
}

TypefaceImpl* gDefaultTypeface = NULL;
pthread_once_t gDefaultTypefaceOnce = PTHREAD_ONCE_INIT;

// This installs a default typeface (from a hardcoded path) that allows
// layouts to work (not crash on null pointer) before the default
// typeface is set.
// TODO: investigate why layouts are being created before Typeface.java
// class initialization.
static FontCollection *makeFontCollection() {
    std::vector<FontFamily *>typefaces;
    const char *fns[] = {
        "/system/fonts/Roboto-Regular.ttf",
    };

    FontFamily *family = new FontFamily();
    for (size_t i = 0; i < sizeof(fns)/sizeof(fns[0]); i++) {
        const char *fn = fns[i];
        ALOGD("makeFontCollection adding %s", fn);
        SkTypeface *skFace = SkTypeface::CreateFromFile(fn);
        if (skFace != NULL) {
            MinikinFont *font = new MinikinFontSkia(skFace);
            family->addFont(font);
            font->Unref();
        } else {
            ALOGE("failed to create font %s", fn);
        }
    }
    typefaces.push_back(family);

    FontCollection *result = new FontCollection(typefaces);
    family->Unref();
    return result;
}

static void getDefaultTypefaceOnce() {
    Layout::init();
    if (gDefaultTypeface == NULL) {
        // We expect the client to set a default typeface, but provide a
        // default so we can make progress before that happens.
        gDefaultTypeface = new TypefaceImpl;
        gDefaultTypeface->fFontCollection = makeFontCollection();
        gDefaultTypeface->fSkiaStyle = SkTypeface::kNormal;
        gDefaultTypeface->fBaseWeight = 400;
        resolveStyle(gDefaultTypeface);
    }
}

TypefaceImpl* TypefaceImpl_resolveDefault(TypefaceImpl* src) {
    if (src == NULL) {
        pthread_once(&gDefaultTypefaceOnce, getDefaultTypefaceOnce);
        return gDefaultTypeface;
    } else {
        return src;
    }
}

TypefaceImpl* TypefaceImpl_createFromTypeface(TypefaceImpl* src, SkTypeface::Style style) {
    TypefaceImpl* resolvedFace = TypefaceImpl_resolveDefault(src);
    TypefaceImpl* result = new TypefaceImpl;
    if (result != 0) {
        result->fFontCollection = resolvedFace->fFontCollection;
        result->fFontCollection->Ref();
        result->fSkiaStyle = style;
        result->fBaseWeight = resolvedFace->fBaseWeight;
        resolveStyle(result);
    }
    return result;
}

TypefaceImpl* TypefaceImpl_createWeightAlias(TypefaceImpl* src, int weight) {
    TypefaceImpl* resolvedFace = TypefaceImpl_resolveDefault(src);
    TypefaceImpl* result = new TypefaceImpl;
    if (result != 0) {
        result->fFontCollection = resolvedFace->fFontCollection;
        result->fFontCollection->Ref();
        result->fSkiaStyle = resolvedFace->fSkiaStyle;
        result->fBaseWeight = weight;
        resolveStyle(result);
    }
    return result;
}

TypefaceImpl* TypefaceImpl_createFromFamilies(const jlong* families, size_t size) {
    std::vector<FontFamily *>familyVec;
    for (size_t i = 0; i < size; i++) {
        FontFamily* family = reinterpret_cast<FontFamily*>(families[i]);
        familyVec.push_back(family);
    }
    TypefaceImpl* result = new TypefaceImpl;
    result->fFontCollection = new FontCollection(familyVec);
    if (size == 0) {
        ALOGW("createFromFamilies creating empty collection");
        result->fSkiaStyle = SkTypeface::kNormal;
    } else {
        const FontStyle defaultStyle;
        FontFamily* firstFamily = reinterpret_cast<FontFamily*>(families[0]);
        MinikinFont* mf = firstFamily->getClosestMatch(defaultStyle).font;
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

void TypefaceImpl_unref(TypefaceImpl* face) {
    if (face != NULL) {
        face->fFontCollection->Unref();
    }
    delete face;
}

int TypefaceImpl_getStyle(TypefaceImpl* face) {
    return face->fSkiaStyle;
}

void TypefaceImpl_setDefault(TypefaceImpl* face) {
    gDefaultTypeface = face;
}

}
