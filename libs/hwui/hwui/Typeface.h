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
#include <memory>
#include <vector>

namespace android {

// This indicates that the weight or italic information should be resolved by OS/2 table.
// This value must be the same as the android.graphics.Typeface$Builder.RESOLVE_BY_FONT_TABLE.
constexpr int RESOLVE_BY_FONT_TABLE = -1;

struct ANDROID_API Typeface {
public:
    std::shared_ptr<minikin::FontCollection> fFontCollection;

    // resolved style actually used for rendering
    minikin::FontStyle fStyle;

    // style used in the API
    enum Style : uint8_t { kNormal = 0, kBold = 0x01, kItalic = 0x02, kBoldItalic = 0x03 };
    Style fAPIStyle;

    static const Typeface* resolveDefault(const Typeface* src);

    // The following three functions create new Typeface from an existing Typeface with a different
    // style. There is a base weight concept which is used for calculating relative style from an
    // existing Typeface.
    // The createRelative method creates a new Typeface with a style relative to the base Typeface.
    // For example, if the base Typeface has a base weight of 400 and the desired style is bold, the
    // resulting Typeface renders the text with a weight of 700. This function doesn't change the
    // base weight, so even if you create a new Typeface from the bold Typeface specifying bold on
    // it again, the text is still rendered with a weight of 700.
    // You can create another base weight Typeface from an existing Typeface with
    // createWithDifferentBaseWeight. The Typeface created with this function renders the text with
    // a specified base weight.
    // The createAbsolute method creates a new Typeface ignoring the base weight.
    // Here is an example:
    //   Typeface* base = resolveDefault(nullptr);  // Usually this has a weight of 400.
    //   Typeface* bold = createRelative(base, Bold);  // Rendered with a weight of 700.
    //   Typeface* bold2 = createRelative(bold, Bold); // Rendered with a weight of 700.
    //
    //   Typeface* boldBase = createWithDifferentBaseWeight(base, 700);  // With a weight of 700.
    //   Typeface* boldBold = createRelative(boldBase, Bold);  // Rendered with a weight of 1000.
    //
    //   Typeface* lightBase = createWithDifferentBaseWeight(base, 300);  // With a weight of 300.
    //   Typeface* lightBold = createRelative(lightBase, Bold);  // Rendered with a weight of 600.
    //
    //   Typeface* black = createAbsolute(base, 900, false);  // Rendered with a weight of 900.
    static Typeface* createWithDifferentBaseWeight(Typeface* src, int baseweight);
    static Typeface* createRelative(Typeface* src, Style desiredStyle);
    static Typeface* createAbsolute(Typeface* base, int weight, bool italic);

    static Typeface* createFromTypefaceWithVariation(
            Typeface* src, const std::vector<minikin::FontVariation>& variations);

    static Typeface* createFromFamilies(
            std::vector<std::shared_ptr<minikin::FontFamily>>&& families, int weight, int italic);

    static void setDefault(const Typeface* face);

    // Sets roboto font as the default typeface for testing purpose.
    static void setRobotoTypefaceForTest();

private:
    // base weight in CSS-style units, 1..1000
    int fBaseWeight;
};
}

#endif  // _ANDROID_GRAPHICS_TYPEFACE_IMPL_H_
