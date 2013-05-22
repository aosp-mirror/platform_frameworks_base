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

#include "SkStream.h"
#include "SkTypeface.h"

#ifdef USE_MINIKIN
#include <vector>
#include <minikin/FontCollection.h>
#include <minikin/FontFamily.h>
#include <minikin/Layout.h>
#include "MinikinSkia.h"
#endif

#include "TypefaceImpl.h"

namespace android {

class AssetStream : public SkStream {
public:
    AssetStream(Asset* asset, bool hasMemoryBase) : fAsset(asset)
    {
        fMemoryBase = hasMemoryBase ? fAsset->getBuffer(false) : NULL;
    }

    virtual ~AssetStream()
    {
        delete fAsset;
    }

    virtual const void* getMemoryBase()
    {
        return fMemoryBase;
    }

    virtual bool rewind()
    {
        off64_t pos = fAsset->seek(0, SEEK_SET);
        return pos != (off64_t)-1;
    }

    virtual size_t read(void* buffer, size_t size)
    {
        ssize_t amount;

        if (NULL == buffer)
        {
            if (0 == size)  // caller is asking us for our total length
                return fAsset->getLength();

            // asset->seek returns new total offset
            // we want to return amount that was skipped

            off64_t oldOffset = fAsset->seek(0, SEEK_CUR);
            if (-1 == oldOffset)
                return 0;
            off64_t newOffset = fAsset->seek(size, SEEK_CUR);
            if (-1 == newOffset)
                return 0;

            amount = newOffset - oldOffset;
        }
        else
        {
            amount = fAsset->read(buffer, size);
        }

        if (amount < 0)
            amount = 0;
        return amount;
    }

private:
    Asset*      fAsset;
    const void* fMemoryBase;
};

#ifdef USE_MINIKIN

// Any weight greater than or equal to this is considered "bold" for
// legacy API.
static const int kBoldThreshold = 6;

static FontStyle styleFromSkiaStyle(SkTypeface::Style skiaStyle) {
    int weight = (skiaStyle & SkTypeface::kBold) != 0 ? 7 : 4;
    bool italic = (skiaStyle & SkTypeface::kItalic) != 0;
    return FontStyle(weight, italic);
}

TypefaceImpl* gDefaultTypeface;
pthread_once_t gDefaultTypefaceOnce = PTHREAD_ONCE_INIT;

// TODO: this currently builds a font collection from hardcoded paths.
// It will get replaced by an implementation that parses the XML files.
static FontCollection *makeFontCollection() {
    std::vector<FontFamily *>typefaces;
    const char *fns[] = {
        "/system/fonts/Roboto-Regular.ttf",
        "/system/fonts/Roboto-Italic.ttf",
        "/system/fonts/Roboto-BoldItalic.ttf",
        "/system/fonts/Roboto-Light.ttf",
        "/system/fonts/Roboto-Thin.ttf",
        "/system/fonts/Roboto-Bold.ttf",
        "/system/fonts/Roboto-ThinItalic.ttf",
        "/system/fonts/Roboto-LightItalic.ttf"
    };

    FontFamily *family = new FontFamily();
    for (size_t i = 0; i < sizeof(fns)/sizeof(fns[0]); i++) {
        const char *fn = fns[i];
        SkTypeface *skFace = SkTypeface::CreateFromFile(fn);
        MinikinFont *font = new MinikinFontSkia(skFace);
        family->addFont(font);
    }
    typefaces.push_back(family);

    family = new FontFamily();
    const char *fn = "/system/fonts/NotoSansDevanagari-Regular.ttf";
    SkTypeface *skFace = SkTypeface::CreateFromFile(fn);
    MinikinFont *font = new MinikinFontSkia(skFace);
    family->addFont(font);
    typefaces.push_back(family);

    return new FontCollection(typefaces);
}

static void getDefaultTypefaceOnce() {
    Layout::init();
    gDefaultTypeface = new TypefaceImpl;
    gDefaultTypeface->fFontCollection = makeFontCollection();
    gDefaultTypeface->fStyle = FontStyle();
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
        result->fStyle = styleFromSkiaStyle(style);
    }
    return result;
}

static TypefaceImpl* createFromSkTypeface(SkTypeface* typeface) {
    MinikinFont* minikinFont = new MinikinFontSkia(typeface);
    std::vector<FontFamily *> typefaces;
    FontFamily* family = new FontFamily();
    family->addFont(minikinFont);
    typefaces.push_back(family);
    TypefaceImpl* result = new TypefaceImpl;
    result->fFontCollection = new FontCollection(typefaces);
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
    SkStream* stream = new AssetStream(asset, true);
    SkTypeface* face = SkTypeface::CreateFromStream(stream);
    // SkTypeFace::CreateFromStream calls ref() on the stream, so we
    // need to unref it here or it won't be freed later on
    stream->unref();
    return createFromSkTypeface(face);
}

void TypefaceImpl_unref(TypefaceImpl* face) {
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
    SkStream* stream = new AssetStream(asset, true);
    SkTypeface* face = SkTypeface::CreateFromStream(stream);
    // SkTypeFace::CreateFromStream calls ref() on the stream, so we
    // need to unref it here or it won't be freed later on
    stream->unref();

    return face;
}

void TypefaceImpl_unref(TypefaceImpl* face) {
    SkSafeUnref(face);
}

int TypefaceImpl_getStyle(TypefaceImpl* face) {
    return face->style();
}

#endif  // USE_MINIKIN

}
