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

#ifdef USE_MINIKIN
#include <vector>
#include <minikin/FontCollection.h>
#include <minikin/FontFamily.h>
#include <minikin/Layout.h>
#include "SkPaint.h"
#include "MinikinSkia.h"
#endif

#include "TypefaceImpl.h"
#include "Utils.h"

namespace android {

#ifdef USE_MINIKIN

// Any weight greater than or equal to this is considered "bold" for
// legacy API.
static const int kBoldThreshold = 6;

static FontStyle styleFromSkiaStyle(SkTypeface::Style skiaStyle) {
    int weight = (skiaStyle & SkTypeface::kBold) != 0 ? 7 : 4;
    bool italic = (skiaStyle & SkTypeface::kItalic) != 0;
    return FontStyle(weight, italic);
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
        gDefaultTypeface->fStyle = FontStyle();
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
        result->fStyle = styleFromSkiaStyle(style);
    }
    return result;
}

static TypefaceImpl* createFromSkTypeface(SkTypeface* typeface) {
    if (typeface == NULL) {
        return NULL;
    }
    MinikinFont* minikinFont = new MinikinFontSkia(typeface);
    std::vector<FontFamily *> typefaces;
    FontFamily* family = new FontFamily();
    family->addFont(minikinFont);
    minikinFont->Unref();
    typefaces.push_back(family);
    TypefaceImpl* result = new TypefaceImpl;
    result->fFontCollection = new FontCollection(typefaces);
    family->Unref();
    result->fStyle = FontStyle();  // TODO: improve
    return result;
}

TypefaceImpl* TypefaceImpl_createFromName(const char* name, SkTypeface::Style style) {
    // TODO: should create a font collection with all styles corresponding to
    // the name
    SkTypeface* face = SkTypeface::CreateFromName(name, style);
    return createFromSkTypeface(face);
}

TypefaceImpl* TypefaceImpl_createFromFile(const char* filename) {
    SkTypeface* face = SkTypeface::CreateFromFile(filename);
    return createFromSkTypeface(face);
}

TypefaceImpl* TypefaceImpl_createFromAsset(Asset* asset) {
    SkStream* stream = new AssetStreamAdaptor(asset,
                                              AssetStreamAdaptor::kYes_OwnAsset,
                                              AssetStreamAdaptor::kYes_HasMemoryBase);
    SkTypeface* face = SkTypeface::CreateFromStream(stream);
    // SkTypeFace::CreateFromStream calls ref() on the stream, so we
    // need to unref it here or it won't be freed later on
    stream->unref();
    return createFromSkTypeface(face);
}

TypefaceImpl* TypefaceImpl_createFromFamilies(const jlong* families, size_t size) {
    ALOGD("createFromFamilies size=%d", size);
    std::vector<FontFamily *>familyVec;
    for (size_t i = 0; i < size; i++) {
        FontFamily* family = reinterpret_cast<FontFamily*>(families[i]);
        familyVec.push_back(family);
    }
    TypefaceImpl* result = new TypefaceImpl;
    result->fFontCollection = new FontCollection(familyVec);
    result->fStyle = FontStyle();  // TODO: improve
    return result;
}

void TypefaceImpl_unref(TypefaceImpl* face) {
    if (face != NULL) {
        face->fFontCollection->Unref();
    }
    delete face;
}

int TypefaceImpl_getStyle(TypefaceImpl* face) {
    FontStyle style = face->fStyle;
    int result = style.getItalic() ? SkTypeface::kItalic : 0;
    if (style.getWeight() >= kBoldThreshold) {
        result |= SkTypeface::kBold;
    }
    return result;
}

void TypefaceImpl_setDefault(TypefaceImpl* face) {
    gDefaultTypeface = face;
}

#else  // USE_MINIKIN

/* Just use SkTypeface instead. */

typedef SkTypeface TypefaceImpl;

TypefaceImpl* TypefaceImpl_createFromTypeface(TypefaceImpl* src, SkTypeface::Style style) {
    return SkTypeface::CreateFromTypeface(src, style);
}

TypefaceImpl* TypefaceImpl_createFromName(const char* name, SkTypeface::Style style) {
    return SkTypeface::CreateFromName(name, style);
}

TypefaceImpl* TypefaceImpl_createFromFile(const char* filename) {
    return SkTypeface::CreateFromFile(filename);
}

TypefaceImpl* TypefaceImpl_createFromAsset(Asset* asset) {
    SkStream* stream = new AssetStreamAdaptor(asset,
                                              AssetStreamAdaptor::kYes_OwnAsset,
                                              AssetStreamAdaptor::kYes_HasMemoryBase);
    SkTypeface* face = SkTypeface::CreateFromStream(stream);
    // SkTypeFace::CreateFromStream calls ref() on the stream, so we
    // need to unref it here or it won't be freed later on
    stream->unref();

    return face;
}

TypefaceImpl* TypefaceImpl_createFromFamilies(const jlong* families, size_t size) {
    // Should never be called in non-Minikin builds
    return 0;
}

void TypefaceImpl_unref(TypefaceImpl* face) {
    SkSafeUnref(face);
}

int TypefaceImpl_getStyle(TypefaceImpl* face) {
    return face->style();
}

void TypefaceImpl_setDefault(TypefaceImpl* face) {
}

#endif  // USE_MINIKIN

}
