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
#include <fcntl.h>  // For tests.
#include <sys/stat.h>  // For tests.
#include <sys/mman.h>  // For tests.

#include "MinikinSkia.h"
#include "SkTypeface.h"
#include "SkPaint.h"
#include "SkStream.h"  // Fot tests.

#include <minikin/FontCollection.h>
#include <minikin/FontFamily.h>
#include <minikin/Layout.h>
#include <utils/Log.h>
#include <utils/MathUtils.h>

namespace android {

static SkTypeface::Style computeSkiaStyle(int weight, bool italic) {
    // This bold detection comes from SkTypeface.h
    if (weight >= SkFontStyle::kSemiBold_Weight) {
        return italic ? SkTypeface::kBoldItalic : SkTypeface::kBold;
    } else {
        return italic ? SkTypeface::kItalic : SkTypeface::kNormal;
    }
}

static minikin::FontStyle computeMinikinStyle(int weight, bool italic) {
    // TODO: Better to use raw base weight value for font selection instead of dividing by 100.
    const int minikinWeight = uirenderer::MathUtils::clamp((weight + 50) / 100, 1, 10);
    return minikin::FontStyle(minikinWeight, italic);
}

// Resolve the relative weight from the baseWeight and target style.
static minikin::FontStyle computeRelativeStyle(int baseWeight, SkTypeface::Style relativeStyle) {
    int weight = baseWeight;
    if ((relativeStyle & SkTypeface::kBold) != 0) {
        weight += 300;
    }
    bool italic = (relativeStyle & SkTypeface::kItalic) != 0;
    return computeMinikinStyle(weight, italic);
}

Typeface* gDefaultTypeface = NULL;

Typeface* Typeface::resolveDefault(Typeface* src) {
    LOG_ALWAYS_FATAL_IF(gDefaultTypeface == nullptr);
    return src == nullptr ? gDefaultTypeface : src;
}

Typeface* Typeface::createRelative(Typeface* src, SkTypeface::Style style) {
    Typeface* resolvedFace = Typeface::resolveDefault(src);
    Typeface* result = new Typeface;
    if (result != nullptr) {
        result->fFontCollection = resolvedFace->fFontCollection;
        result->fBaseWeight = resolvedFace->fBaseWeight;
        result->fSkiaStyle = style;
        result->fStyle = computeRelativeStyle(result->fBaseWeight, style);
    }
    return result;
}

Typeface* Typeface::createAbsolute(Typeface* base, int weight, bool italic) {
    Typeface* resolvedFace = Typeface::resolveDefault(base);
    Typeface* result = new Typeface();
    if (result != nullptr) {
        result->fFontCollection = resolvedFace->fFontCollection;
        result->fBaseWeight = resolvedFace->fBaseWeight;
        result->fSkiaStyle = computeSkiaStyle(weight, italic);
        result->fStyle = computeMinikinStyle(weight, italic);
    }
    return result;
}

Typeface* Typeface::createFromTypefaceWithVariation(Typeface* src,
        const std::vector<minikin::FontVariation>& variations) {
    Typeface* resolvedFace = Typeface::resolveDefault(src);
    Typeface* result = new Typeface();
    if (result != nullptr) {
        result->fFontCollection =
                resolvedFace->fFontCollection->createCollectionWithVariation(variations);
        if (result->fFontCollection == nullptr) {
            // None of passed axes are supported by this collection.
            // So we will reuse the same collection with incrementing reference count.
            result->fFontCollection = resolvedFace->fFontCollection;
        }
        // Do not update styles.
        // TODO: We may want to update base weight if the 'wght' is specified.
        result->fBaseWeight = resolvedFace->fBaseWeight;
        result->fSkiaStyle = resolvedFace->fSkiaStyle;
        result->fStyle = resolvedFace->fStyle;
    }
    return result;
}

Typeface* Typeface::createWithDifferentBaseWeight(Typeface* src, int weight) {
    Typeface* resolvedFace = Typeface::resolveDefault(src);
    Typeface* result = new Typeface;
    if (result != nullptr) {
        result->fFontCollection = resolvedFace->fFontCollection;
        result->fBaseWeight = weight;
        result->fSkiaStyle = resolvedFace->fSkiaStyle;
        result->fStyle = computeRelativeStyle(weight, result->fSkiaStyle);
    }
    return result;
}

Typeface* Typeface::createFromFamilies(
        std::vector<std::shared_ptr<minikin::FontFamily>>&& families,
        int weight, int italic) {
    Typeface* result = new Typeface;
    result->fFontCollection.reset(new minikin::FontCollection(families));

    if (weight == RESOLVE_BY_FONT_TABLE || italic == RESOLVE_BY_FONT_TABLE) {
        int weightFromFont;
        bool italicFromFont;

        const minikin::FontStyle defaultStyle;
        const minikin::MinikinFont* mf =
                families.empty() ?  nullptr : families[0]->getClosestMatch(defaultStyle).font;
        if (mf != nullptr) {
            SkTypeface* skTypeface = reinterpret_cast<const MinikinFontSkia*>(mf)->GetSkTypeface();
            const SkFontStyle& style = skTypeface->fontStyle();
            weightFromFont = style.weight();
            italicFromFont = style.slant() != SkFontStyle::kUpright_Slant;
        } else {
            // We can't obtain any information from fonts. Just use default values.
            weightFromFont = SkFontStyle::kNormal_Weight;
            italicFromFont = false;
        }

        if (weight == RESOLVE_BY_FONT_TABLE) {
            weight = weightFromFont;
        }
        if (italic == RESOLVE_BY_FONT_TABLE) {
            italic = italicFromFont? 1 : 0;
        }
    }

    // Sanitize the invalid value passed from public API.
    if (weight < 0) {
        weight = SkFontStyle::kNormal_Weight;
    }

    result->fBaseWeight = weight;
    result->fSkiaStyle = computeSkiaStyle(weight, italic);
    result->fStyle = computeMinikinStyle(weight, italic);
    return result;
}

void Typeface::setDefault(Typeface* face) {
    gDefaultTypeface = face;
}

void Typeface::setRobotoTypefaceForTest() {
    const char* kRobotoFont = "/system/fonts/Roboto-Regular.ttf";

    int fd = open(kRobotoFont, O_RDONLY);
    LOG_ALWAYS_FATAL_IF(fd == -1, "Failed to open file %s", kRobotoFont);
    struct stat st = {};
    LOG_ALWAYS_FATAL_IF(fstat(fd, &st) == -1, "Failed to stat file %s", kRobotoFont);
    void* data = mmap(nullptr, st.st_size, PROT_READ, MAP_SHARED, fd, 0);
    std::unique_ptr<SkMemoryStream> fontData(new SkMemoryStream(data, st.st_size));
    sk_sp<SkTypeface> typeface = SkTypeface::MakeFromStream(fontData.release());
    LOG_ALWAYS_FATAL_IF(typeface == nullptr, "Failed to make typeface from %s", kRobotoFont);

    std::shared_ptr<minikin::MinikinFont> font = std::make_shared<MinikinFontSkia>(
            std::move(typeface), data, st.st_size, 0, std::vector<minikin::FontVariation>());
    std::shared_ptr<minikin::FontFamily> family = std::make_shared<minikin::FontFamily>(
            std::vector<minikin::Font>({ minikin::Font(std::move(font), minikin::FontStyle()) }));
    std::shared_ptr<minikin::FontCollection> collection =
            std::make_shared<minikin::FontCollection>(std::move(family));

    Typeface* hwTypeface = new Typeface();
    hwTypeface->fFontCollection = collection;
    hwTypeface->fSkiaStyle = SkTypeface::kNormal;
    hwTypeface->fBaseWeight = SkFontStyle::kNormal_Weight;
    hwTypeface->fStyle = minikin::FontStyle(4 /* weight */, false /* italic */);

    Typeface::setDefault(hwTypeface);
}

}
