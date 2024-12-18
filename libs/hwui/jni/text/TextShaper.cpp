/*
 * Copyright (C) 2020 The Android Open Source Project
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

#include "graphics_jni_helpers.h"
#include <nativehelper/ScopedStringChars.h>
#include <nativehelper/ScopedPrimitiveArray.h>
#include <set>
#include <algorithm>

#include <hwui/MinikinSkia.h>
#include <hwui/MinikinUtils.h>
#include <hwui/Paint.h>
#include <minikin/MinikinFont.h>
#include <minikin/MinikinPaint.h>
#include "FontUtils.h"
#include "SkPaint.h"
#include "SkTypeface.h"

namespace android {

struct FakedFontKey {
    uint32_t operator()(const minikin::FakedFont& fakedFont) const {
        return minikin::Hasher()
                .update(reinterpret_cast<uintptr_t>(fakedFont.font.get()))
                .update(fakedFont.fakery.bits())
                .update(fakedFont.fakery.variationSettings())
                .hash();
    }
};

struct LayoutWrapper {
    LayoutWrapper(minikin::Layout&& layout, float ascent, float descent)
        : layout(std::move(layout)), ascent(ascent), descent(descent)  {}

    LayoutWrapper(minikin::Layout&& layout, float ascent, float descent, std::vector<jlong>&& fonts,
                  std::vector<uint32_t>&& fontIds)
            : layout(std::move(layout))
            , ascent(ascent)
            , descent(descent)
            , fonts(std::move(fonts))
            , fontIds(std::move(fontIds)) {}

    minikin::Layout layout;
    float ascent;
    float descent;

    std::vector<jlong> fonts;
    std::vector<uint32_t> fontIds;  // per glyph
};

static void releaseLayout(jlong ptr) {
    delete reinterpret_cast<LayoutWrapper*>(ptr);
}

static jlong shapeTextRun(const uint16_t* text, int textSize, int start, int count,
    int contextStart, int contextCount, minikin::Bidi bidiFlags,
    const Paint& paint, const Typeface* typeface) {
    const Typeface* resolvedFace = Typeface::resolveDefault(typeface);
    minikin::MinikinPaint minikinPaint = MinikinUtils::prepareMinikinPaint(&paint, typeface);

    minikin::Layout layout = MinikinUtils::doLayout(&paint, bidiFlags, typeface,
        text, textSize, start, count, contextStart, contextCount, nullptr);

    std::set<const minikin::Font*> seenFonts;
    float overallAscent = 0;
    float overallDescent = 0;
    for (int i = 0; i < layout.nGlyphs(); ++i) {
        const minikin::Font* font = layout.getFont(i);
        if (seenFonts.find(font) != seenFonts.end()) continue;
        minikin::MinikinExtent extent = {};
        layout.typeface(i)->GetFontExtent(&extent, minikinPaint, layout.getFakery(i));
        overallAscent = std::min(overallAscent, extent.ascent);
        overallDescent = std::max(overallDescent, extent.descent);
    }

    if (text_feature::typeface_redesign_readonly()) {
        uint32_t runCount = layout.getFontRunCount();

        std::unordered_map<minikin::FakedFont, uint32_t, FakedFontKey> fakedToFontIds;
        std::vector<jlong> fonts;
        std::vector<uint32_t> fontIds;

        fontIds.resize(layout.nGlyphs());
        for (uint32_t ri = 0; ri < runCount; ++ri) {
            const minikin::FakedFont& fakedFont = layout.getFontRunFont(ri);

            auto it = fakedToFontIds.find(fakedFont);
            uint32_t fontId;
            if (it != fakedToFontIds.end()) {
                fontId = it->second;  // We've seen it.
            } else {
                fontId = fonts.size();  // This is new to us. Create new one.
                std::shared_ptr<minikin::Font> font;
                if (resolvedFace->fIsVariationInstance) {
                    // The optimization for target SDK 35 or before because the variation instance
                    // is already created and no runtime variation resolution happens on such
                    // environment.
                    font = fakedFont.font;
                } else {
                    font = std::make_shared<minikin::Font>(fakedFont.font,
                                                           fakedFont.fakery.variationSettings());
                }
                fonts.push_back(reinterpret_cast<jlong>(new FontWrapper(std::move(font))));
                fakedToFontIds.insert(std::make_pair(fakedFont, fontId));
            }

            const uint32_t runStart = layout.getFontRunStart(ri);
            const uint32_t runEnd = layout.getFontRunEnd(ri);
            for (uint32_t i = runStart; i < runEnd; ++i) {
                fontIds[i] = fontId;
            }
        }

        std::unique_ptr<LayoutWrapper> ptr =
                std::make_unique<LayoutWrapper>(std::move(layout), overallAscent, overallDescent,
                                                std::move(fonts), std::move(fontIds));

        return reinterpret_cast<jlong>(ptr.release());
    }

    std::unique_ptr<LayoutWrapper> ptr = std::make_unique<LayoutWrapper>(
        std::move(layout), overallAscent, overallDescent
    );

    return reinterpret_cast<jlong>(ptr.release());
}

static jlong TextShaper_shapeTextRunChars(JNIEnv *env, jobject, jcharArray charArray,
    jint start, jint count, jint contextStart, jint contextCount, jboolean isRtl,
    jlong paintPtr) {
    ScopedCharArrayRO text(env, charArray);
    Paint* paint = reinterpret_cast<Paint*>(paintPtr);
    const Typeface* typeface = paint->getAndroidTypeface();
    const minikin::Bidi bidiFlags = isRtl ? minikin::Bidi::FORCE_RTL : minikin::Bidi::FORCE_LTR;
    return shapeTextRun(
        text.get(), text.size(),
        start, count,
        contextStart, contextCount,
        bidiFlags,
        *paint, typeface);

}

static jlong TextShaper_shapeTextRunString(JNIEnv *env, jobject, jstring string,
    jint start, jint count, jint contextStart, jint contextCount, jboolean isRtl,
    jlong paintPtr) {
    ScopedStringChars text(env, string);
    Paint* paint = reinterpret_cast<Paint*>(paintPtr);
    const Typeface* typeface = paint->getAndroidTypeface();
    const minikin::Bidi bidiFlags = isRtl ? minikin::Bidi::FORCE_RTL : minikin::Bidi::FORCE_LTR;
    return shapeTextRun(
        text.get(), text.size(),
        start, count,
        contextStart, contextCount,
        bidiFlags,
        *paint, typeface);
}

// CriticalNative
static jint TextShaper_Result_getGlyphCount(CRITICAL_JNI_PARAMS_COMMA jlong ptr) {
    const LayoutWrapper* layout = reinterpret_cast<LayoutWrapper*>(ptr);
    return layout->layout.nGlyphs();
}

// CriticalNative
static jfloat TextShaper_Result_getTotalAdvance(CRITICAL_JNI_PARAMS_COMMA jlong ptr) {
    const LayoutWrapper* layout = reinterpret_cast<LayoutWrapper*>(ptr);
    return layout->layout.getAdvance();
}

// CriticalNative
static jfloat TextShaper_Result_getAscent(CRITICAL_JNI_PARAMS_COMMA jlong ptr) {
    const LayoutWrapper* layout = reinterpret_cast<LayoutWrapper*>(ptr);
    return layout->ascent;
}

// CriticalNative
static jfloat TextShaper_Result_getDescent(CRITICAL_JNI_PARAMS_COMMA jlong ptr) {
    const LayoutWrapper* layout = reinterpret_cast<LayoutWrapper*>(ptr);
    return layout->descent;
}

// CriticalNative
static jint TextShaper_Result_getGlyphId(CRITICAL_JNI_PARAMS_COMMA jlong ptr, jint i) {
    const LayoutWrapper* layout = reinterpret_cast<LayoutWrapper*>(ptr);
    return layout->layout.getGlyphId(i);
}

// CriticalNative
static jfloat TextShaper_Result_getX(CRITICAL_JNI_PARAMS_COMMA jlong ptr, jint i) {
    const LayoutWrapper* layout = reinterpret_cast<LayoutWrapper*>(ptr);
    return layout->layout.getX(i);
}

// CriticalNative
static jfloat TextShaper_Result_getY(CRITICAL_JNI_PARAMS_COMMA jlong ptr, jint i) {
    const LayoutWrapper* layout = reinterpret_cast<LayoutWrapper*>(ptr);
    return layout->layout.getY(i);
}

// CriticalNative
static jboolean TextShaper_Result_getFakeBold(CRITICAL_JNI_PARAMS_COMMA jlong ptr, jint i) {
    const LayoutWrapper* layout = reinterpret_cast<LayoutWrapper*>(ptr);
    return layout->layout.getFakery(i).isFakeBold();
}

// CriticalNative
static jboolean TextShaper_Result_getFakeItalic(CRITICAL_JNI_PARAMS_COMMA jlong ptr, jint i) {
    const LayoutWrapper* layout = reinterpret_cast<LayoutWrapper*>(ptr);
    return layout->layout.getFakery(i).isFakeItalic();
}

constexpr float NO_OVERRIDE = -1;

float findValueFromVariationSettings(const minikin::VariationSettings& axes, minikin::AxisTag tag) {
    for (const minikin::FontVariation& fv : axes) {
        if (fv.axisTag == tag) {
            return fv.value;
        }
    }
    return std::numeric_limits<float>::quiet_NaN();
}

// CriticalNative
static jfloat TextShaper_Result_getWeightOverride(CRITICAL_JNI_PARAMS_COMMA jlong ptr, jint i) {
    const LayoutWrapper* layout = reinterpret_cast<LayoutWrapper*>(ptr);
    if (text_feature::typeface_redesign_readonly()) {
        float value = findValueFromVariationSettings(layout->layout.typeface(i)->GetAxes(),
                                                     minikin::TAG_wght);
        return std::isnan(value) ? NO_OVERRIDE : value;
    } else {
        return layout->layout.getFakery(i).wghtAdjustment();
    }
}

// CriticalNative
static jfloat TextShaper_Result_getItalicOverride(CRITICAL_JNI_PARAMS_COMMA jlong ptr, jint i) {
    const LayoutWrapper* layout = reinterpret_cast<LayoutWrapper*>(ptr);
    if (text_feature::typeface_redesign_readonly()) {
        float value = findValueFromVariationSettings(layout->layout.typeface(i)->GetAxes(),
                                                     minikin::TAG_ital);
        return std::isnan(value) ? NO_OVERRIDE : value;
    } else {
        return layout->layout.getFakery(i).italAdjustment();
    }
}

// CriticalNative
static jlong TextShaper_Result_getFont(CRITICAL_JNI_PARAMS_COMMA jlong ptr, jint i) {
    const LayoutWrapper* layout = reinterpret_cast<LayoutWrapper*>(ptr);
    std::shared_ptr<minikin::Font> fontRef = layout->layout.getFontRef(i);
    return reinterpret_cast<jlong>(new FontWrapper(std::move(fontRef)));
}

// CriticalNative
static jint TextShaper_Result_getFontCount(CRITICAL_JNI_PARAMS_COMMA jlong ptr) {
    const LayoutWrapper* layout = reinterpret_cast<LayoutWrapper*>(ptr);
    return layout->fonts.size();
}

// CriticalNative
static jlong TextShaper_Result_getFontRef(CRITICAL_JNI_PARAMS_COMMA jlong ptr, jint fontId) {
    const LayoutWrapper* layout = reinterpret_cast<LayoutWrapper*>(ptr);
    return layout->fonts[fontId];
}

// CriticalNative
static jint TextShaper_Result_getFontId(CRITICAL_JNI_PARAMS_COMMA jlong ptr, jint glyphIdx) {
    const LayoutWrapper* layout = reinterpret_cast<LayoutWrapper*>(ptr);
    return layout->fontIds[glyphIdx];
}

// CriticalNative
static jlong TextShaper_Result_nReleaseFunc(CRITICAL_JNI_PARAMS) {
    return reinterpret_cast<jlong>(releaseLayout);
}

static const JNINativeMethod gMethods[] = {
    {"nativeShapeTextRun", "("
        "[C"  // text
        "I"  // start
        "I"  // count
        "I"  // contextStart
        "I"  // contextCount
        "Z"  // isRtl
        "J)"  // paint
        "J",  // LayoutPtr
        (void*) TextShaper_shapeTextRunChars},

    {"nativeShapeTextRun", "("
        "Ljava/lang/String;"  // text
        "I"  // start
        "I"  // count
        "I"  // contextStart
        "I"  // contextCount
        "Z"  // isRtl
        "J)"  // paint
        "J",  // LayoutPtr
        (void*) TextShaper_shapeTextRunString},

};

static const JNINativeMethod gResultMethods[] = {
        {"nGetGlyphCount", "(J)I", (void*)TextShaper_Result_getGlyphCount},
        {"nGetTotalAdvance", "(J)F", (void*)TextShaper_Result_getTotalAdvance},
        {"nGetAscent", "(J)F", (void*)TextShaper_Result_getAscent},
        {"nGetDescent", "(J)F", (void*)TextShaper_Result_getDescent},
        {"nGetGlyphId", "(JI)I", (void*)TextShaper_Result_getGlyphId},
        {"nGetX", "(JI)F", (void*)TextShaper_Result_getX},
        {"nGetY", "(JI)F", (void*)TextShaper_Result_getY},
        {"nGetFont", "(JI)J", (void*)TextShaper_Result_getFont},
        {"nGetFakeBold", "(JI)Z", (void*)TextShaper_Result_getFakeBold},
        {"nGetFakeItalic", "(JI)Z", (void*)TextShaper_Result_getFakeItalic},
        {"nGetWeightOverride", "(JI)F", (void*)TextShaper_Result_getWeightOverride},
        {"nGetItalicOverride", "(JI)F", (void*)TextShaper_Result_getItalicOverride},
        {"nReleaseFunc", "()J", (void*)TextShaper_Result_nReleaseFunc},

        {"nGetFontCount", "(J)I", (void*)TextShaper_Result_getFontCount},
        {"nGetFontRef", "(JI)J", (void*)TextShaper_Result_getFontRef},
        {"nGetFontId", "(JI)I", (void*)TextShaper_Result_getFontId},
};

int register_android_graphics_text_TextShaper(JNIEnv* env) {
    return RegisterMethodsOrDie(env, "android/graphics/text/TextRunShaper", gMethods,
                                NELEM(gMethods))
        + RegisterMethodsOrDie(env, "android/graphics/text/PositionedGlyphs",
            gResultMethods, NELEM(gResultMethods));
}

}

