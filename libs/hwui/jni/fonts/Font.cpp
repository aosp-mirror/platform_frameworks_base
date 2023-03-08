/*
 * Copyright (C) 2018 The Android Open Source Project
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

#undef LOG_TAG
#define LOG_TAG "Minikin"

#include "Font.h"
#include "SkData.h"
#include "SkFont.h"
#include "SkFontMetrics.h"
#include "SkFontMgr.h"
#include "SkRefCnt.h"
#include "SkTypeface.h"
#include "GraphicsJNI.h"
#include <nativehelper/ScopedUtfChars.h>
#include "Utils.h"
#include "FontUtils.h"

#include <hwui/MinikinSkia.h>
#include <hwui/Paint.h>
#include <hwui/Typeface.h>
#include <minikin/FontFamily.h>
#include <minikin/FontFileParser.h>
#include <minikin/LocaleList.h>
#include <minikin/SystemFonts.h>
#include <ui/FatVector.h>

#include <memory>

namespace android {

struct NativeFontBuilder {
    std::vector<minikin::FontVariation> axes;
};

static inline NativeFontBuilder* toBuilder(jlong ptr) {
    return reinterpret_cast<NativeFontBuilder*>(ptr);
}

static void releaseFont(jlong font) {
    delete reinterpret_cast<FontWrapper*>(font);
}

static void release_global_ref(const void* /*data*/, void* context) {
    JNIEnv* env = GraphicsJNI::getJNIEnv();
    bool needToAttach = (env == nullptr);
    if (needToAttach) {
        env = GraphicsJNI::attachJNIEnv("release_font_data");
        if (env == nullptr) {
            ALOGE("failed to attach to thread to release global ref.");
            return;
        }
    }

    jobject obj = reinterpret_cast<jobject>(context);
    env->DeleteGlobalRef(obj);
}

// Regular JNI
static jlong Font_Builder_initBuilder(JNIEnv*, jobject) {
    return reinterpret_cast<jlong>(new NativeFontBuilder());
}

// Critical Native
static void Font_Builder_addAxis(CRITICAL_JNI_PARAMS_COMMA jlong builderPtr, jint tag, jfloat value) {
    toBuilder(builderPtr)->axes.emplace_back(static_cast<minikin::AxisTag>(tag), value);
}

// Regular JNI
static jlong Font_Builder_build(JNIEnv* env, jobject clazz, jlong builderPtr, jobject buffer,
                                jstring filePath, jstring langTags, jint weight, jboolean italic,
                                jint ttcIndex) {
    NPE_CHECK_RETURN_ZERO(env, buffer);
    std::unique_ptr<NativeFontBuilder> builder(toBuilder(builderPtr));
    const void* fontPtr = env->GetDirectBufferAddress(buffer);
    if (fontPtr == nullptr) {
        jniThrowException(env, "java/lang/IllegalArgumentException", "Not a direct buffer");
        return 0;
    }
    jlong fontSize = env->GetDirectBufferCapacity(buffer);
    if (fontSize <= 0) {
        jniThrowException(env, "java/lang/IllegalArgumentException",
                          "buffer size must not be zero or negative");
        return 0;
    }
    ScopedUtfChars fontPath(env, filePath);
    ScopedUtfChars langTagStr(env, langTags);
    jobject fontRef = MakeGlobalRefOrDie(env, buffer);
    sk_sp<SkData> data(SkData::MakeWithProc(fontPtr, fontSize,
            release_global_ref, reinterpret_cast<void*>(fontRef)));
    std::shared_ptr<minikin::MinikinFont> minikinFont = fonts::createMinikinFontSkia(
        std::move(data), std::string_view(fontPath.c_str(), fontPath.size()),
        fontPtr, fontSize, ttcIndex, builder->axes);
    if (minikinFont == nullptr) {
        jniThrowExceptionFmt(env, "java/lang/IllegalArgumentException",
                             "Failed to create internal object. maybe invalid font data. filePath %s",
                             fontPath.c_str());
        return 0;
    }
    uint32_t localeListId = minikin::registerLocaleList(langTagStr.c_str());
    std::shared_ptr<minikin::Font> font =
            minikin::Font::Builder(minikinFont)
                    .setWeight(weight)
                    .setSlant(static_cast<minikin::FontStyle::Slant>(italic))
                    .setLocaleListId(localeListId)
                    .build();
    return reinterpret_cast<jlong>(new FontWrapper(std::move(font)));
}

// Fast Native
static jlong Font_Builder_clone(JNIEnv* env, jobject clazz, jlong fontPtr, jlong builderPtr,
                                jint weight, jboolean italic, jint ttcIndex) {
    FontWrapper* font = reinterpret_cast<FontWrapper*>(fontPtr);
    MinikinFontSkia* minikinSkia = static_cast<MinikinFontSkia*>(font->font->typeface().get());
    std::unique_ptr<NativeFontBuilder> builder(toBuilder(builderPtr));

    // Reconstruct SkTypeface with different arguments from existing SkTypeface.
    FatVector<SkFontArguments::VariationPosition::Coordinate, 2> skVariation;
    for (const auto& axis : builder->axes) {
        skVariation.push_back({axis.axisTag, axis.value});
    }
    SkFontArguments args;
    args.setCollectionIndex(ttcIndex);
    args.setVariationDesignPosition({skVariation.data(), static_cast<int>(skVariation.size())});

    sk_sp<SkTypeface> newTypeface = minikinSkia->GetSkTypeface()->makeClone(args);

    std::shared_ptr<minikin::MinikinFont> newMinikinFont = std::make_shared<MinikinFontSkia>(
            std::move(newTypeface), minikinSkia->GetSourceId(), minikinSkia->GetFontData(),
            minikinSkia->GetFontSize(), minikinSkia->getFilePath(), minikinSkia->GetFontIndex(),
            builder->axes);
    std::shared_ptr<minikin::Font> newFont = minikin::Font::Builder(newMinikinFont)
              .setWeight(weight)
              .setSlant(static_cast<minikin::FontStyle::Slant>(italic))
              .build();
    return reinterpret_cast<jlong>(new FontWrapper(std::move(newFont)));
}

///////////////////////////////////////////////////////////////////////////////
// Font JNI functions

// Fast Native
static jfloat Font_getGlyphBounds(JNIEnv* env, jobject, jlong fontHandle, jint glyphId,
                                  jlong paintHandle, jobject rect) {
    FontWrapper* font = reinterpret_cast<FontWrapper*>(fontHandle);
    MinikinFontSkia* minikinSkia = static_cast<MinikinFontSkia*>(font->font->typeface().get());
    Paint* paint = reinterpret_cast<Paint*>(paintHandle);

    SkFont* skFont = &paint->getSkFont();
    // We don't use populateSkFont since it is designed to be used for layout result with addressing
    // auto fake-bolding.
    skFont->setTypeface(minikinSkia->RefSkTypeface());

    uint16_t glyph16 = glyphId;
    SkRect skBounds;
    SkScalar skWidth;
    skFont->getWidthsBounds(&glyph16, 1, &skWidth, &skBounds, nullptr);
    GraphicsJNI::rect_to_jrectf(skBounds, env, rect);
    return SkScalarToFloat(skWidth);
}

// Fast Native
static jfloat Font_getFontMetrics(JNIEnv* env, jobject, jlong fontHandle, jlong paintHandle,
                                  jobject metricsObj) {
    FontWrapper* font = reinterpret_cast<FontWrapper*>(fontHandle);
    MinikinFontSkia* minikinSkia = static_cast<MinikinFontSkia*>(font->font->typeface().get());
    Paint* paint = reinterpret_cast<Paint*>(paintHandle);

    SkFont* skFont = &paint->getSkFont();
    // We don't use populateSkFont since it is designed to be used for layout result with addressing
    // auto fake-bolding.
    skFont->setTypeface(minikinSkia->RefSkTypeface());

    SkFontMetrics metrics;
    SkScalar spacing = skFont->getMetrics(&metrics);
    GraphicsJNI::set_metrics(env, metricsObj, metrics);
    return spacing;
}

// Critical Native
static jlong Font_getMinikinFontPtr(CRITICAL_JNI_PARAMS_COMMA jlong fontPtr) {
    FontWrapper* font = reinterpret_cast<FontWrapper*>(fontPtr);
    return reinterpret_cast<jlong>(font->font.get());
}

// Critical Native
static jlong Font_cloneFont(CRITICAL_JNI_PARAMS_COMMA jlong fontPtr) {
    FontWrapper* font = reinterpret_cast<FontWrapper*>(fontPtr);
    std::shared_ptr<minikin::Font> ref = font->font;
    return reinterpret_cast<jlong>(new FontWrapper(std::move(ref)));
}

// Fast Native
static jobject Font_newByteBuffer(JNIEnv* env, jobject, jlong fontPtr) {
    FontWrapper* font = reinterpret_cast<FontWrapper*>(fontPtr);
    const std::shared_ptr<minikin::MinikinFont>& minikinFont = font->font->typeface();
    return env->NewDirectByteBuffer(const_cast<void*>(minikinFont->GetFontData()),
                                    minikinFont->GetFontSize());
}

// Critical Native
static jlong Font_getBufferAddress(CRITICAL_JNI_PARAMS_COMMA jlong fontPtr) {
    FontWrapper* font = reinterpret_cast<FontWrapper*>(fontPtr);
    return reinterpret_cast<jlong>(font->font->typeface()->GetFontData());
}

// Critical Native
static jlong Font_getReleaseNativeFontFunc(CRITICAL_JNI_PARAMS) {
    return reinterpret_cast<jlong>(releaseFont);
}

// Fast Native
static jstring Font_getFontPath(JNIEnv* env, jobject, jlong fontPtr) {
    FontWrapper* font = reinterpret_cast<FontWrapper*>(fontPtr);
    minikin::BufferReader reader = font->font->typefaceMetadataReader();
    if (reader.data() != nullptr) {
        std::string path = std::string(reader.readString());
        if (path.empty()) {
            return nullptr;
        }
        return env->NewStringUTF(path.c_str());
    } else {
        const std::shared_ptr<minikin::MinikinFont>& minikinFont = font->font->typeface();
        const std::string& path = minikinFont->GetFontPath();
        if (path.empty()) {
            return nullptr;
        }
        return env->NewStringUTF(path.c_str());
    }
}

// Fast Native
static jstring Font_getLocaleList(JNIEnv* env, jobject, jlong fontPtr) {
    FontWrapper* font = reinterpret_cast<FontWrapper*>(fontPtr);
    uint32_t localeListId = font->font->getLocaleListId();
    if (localeListId == 0) {
        return nullptr;
    }
    std::string langTags = minikin::getLocaleString(localeListId);
    if (langTags.empty()) {
        return nullptr;
    }
    return env->NewStringUTF(langTags.c_str());
}

// Critical Native
static jint Font_getPackedStyle(CRITICAL_JNI_PARAMS_COMMA jlong fontPtr) {
    FontWrapper* font = reinterpret_cast<FontWrapper*>(fontPtr);
    uint32_t weight = font->font->style().weight();
    uint32_t isItalic = font->font->style().slant() == minikin::FontStyle::Slant::ITALIC ? 1 : 0;
    return (isItalic << 16) | weight;
}

// Critical Native
static jint Font_getIndex(CRITICAL_JNI_PARAMS_COMMA jlong fontPtr) {
    FontWrapper* font = reinterpret_cast<FontWrapper*>(fontPtr);
    minikin::BufferReader reader = font->font->typefaceMetadataReader();
    if (reader.data() != nullptr) {
        reader.skipString();  // fontPath
        return reader.read<int>();
    } else {
        const std::shared_ptr<minikin::MinikinFont>& minikinFont = font->font->typeface();
        return minikinFont->GetFontIndex();
    }
}

// Critical Native
static jint Font_getAxisCount(CRITICAL_JNI_PARAMS_COMMA jlong fontPtr) {
    FontWrapper* font = reinterpret_cast<FontWrapper*>(fontPtr);
    minikin::BufferReader reader = font->font->typefaceMetadataReader();
    if (reader.data() != nullptr) {
        reader.skipString();  // fontPath
        reader.skip<int>();   // fontIndex
        return reader.readArray<minikin::FontVariation>().second;
    } else {
        const std::shared_ptr<minikin::MinikinFont>& minikinFont = font->font->typeface();
        return minikinFont->GetAxes().size();
    }
}

// Critical Native
static jlong Font_getAxisInfo(CRITICAL_JNI_PARAMS_COMMA jlong fontPtr, jint index) {
    FontWrapper* font = reinterpret_cast<FontWrapper*>(fontPtr);
    minikin::BufferReader reader = font->font->typefaceMetadataReader();
    minikin::FontVariation var;
    if (reader.data() != nullptr) {
        reader.skipString();  // fontPath
        reader.skip<int>();   // fontIndex
        var = reader.readArray<minikin::FontVariation>().first[index];
    } else {
        const std::shared_ptr<minikin::MinikinFont>& minikinFont = font->font->typeface();
        var = minikinFont->GetAxes().at(index);
    }
    uint32_t floatBinary = *reinterpret_cast<const uint32_t*>(&var.value);
    return (static_cast<uint64_t>(var.axisTag) << 32) | static_cast<uint64_t>(floatBinary);
}

// Critical Native
static jint Font_getSourceId(CRITICAL_JNI_PARAMS_COMMA jlong fontPtr) {
    FontWrapper* font = reinterpret_cast<FontWrapper*>(fontPtr);
    return font->font->typeface()->GetSourceId();
}

static jlongArray Font_getAvailableFontSet(JNIEnv* env, jobject) {
    std::vector<jlong> refArray;
    minikin::SystemFonts::getFontSet(
            [&refArray](const std::vector<std::shared_ptr<minikin::Font>>& fontSet) {
                refArray.reserve(fontSet.size());
                for (const auto& font : fontSet) {
                    std::shared_ptr<minikin::Font> fontRef = font;
                    refArray.push_back(
                            reinterpret_cast<jlong>(new FontWrapper(std::move(fontRef))));
                }
            });
    jlongArray r = env->NewLongArray(refArray.size());
    env->SetLongArrayRegion(r, 0, refArray.size(), refArray.data());
    return r;
}

// Fast Native
static jlong FontFileUtil_getFontRevision(JNIEnv* env, jobject, jobject buffer, jint index) {
    NPE_CHECK_RETURN_ZERO(env, buffer);
    const void* fontPtr = env->GetDirectBufferAddress(buffer);
    if (fontPtr == nullptr) {
        jniThrowException(env, "java/lang/IllegalArgumentException", "Not a direct buffer");
        return 0;
    }
    jlong fontSize = env->GetDirectBufferCapacity(buffer);
    if (fontSize <= 0) {
        jniThrowException(env, "java/lang/IllegalArgumentException",
                          "buffer size must not be zero or negative");
        return 0;
    }
    minikin::FontFileParser parser(fontPtr, fontSize, index);
    std::optional<uint32_t> revision = parser.getFontRevision();
    if (!revision.has_value()) {
        return -1L;
    }
    return revision.value();
}

static jstring FontFileUtil_getFontPostScriptName(JNIEnv* env, jobject, jobject buffer,
                                                  jint index) {
    NPE_CHECK_RETURN_ZERO(env, buffer);
    const void* fontPtr = env->GetDirectBufferAddress(buffer);
    if (fontPtr == nullptr) {
        jniThrowException(env, "java/lang/IllegalArgumentException", "Not a direct buffer");
        return nullptr;
    }
    jlong fontSize = env->GetDirectBufferCapacity(buffer);
    if (fontSize <= 0) {
        jniThrowException(env, "java/lang/IllegalArgumentException",
                          "buffer size must not be zero or negative");
        return nullptr;
    }
    minikin::FontFileParser parser(fontPtr, fontSize, index);
    std::optional<std::string> psName = parser.getPostScriptName();
    if (!psName.has_value()) {
        return nullptr;  // null
    }
    return env->NewStringUTF(psName->c_str());
}

static jint FontFileUtil_isPostScriptType1Font(JNIEnv* env, jobject, jobject buffer, jint index) {
    NPE_CHECK_RETURN_ZERO(env, buffer);
    const void* fontPtr = env->GetDirectBufferAddress(buffer);
    if (fontPtr == nullptr) {
        jniThrowException(env, "java/lang/IllegalArgumentException", "Not a direct buffer");
        return -1;
    }
    jlong fontSize = env->GetDirectBufferCapacity(buffer);
    if (fontSize <= 0) {
        jniThrowException(env, "java/lang/IllegalArgumentException",
                          "buffer size must not be zero or negative");
        return -1;
    }
    minikin::FontFileParser parser(fontPtr, fontSize, index);
    std::optional<bool> isType1 = parser.isPostScriptType1Font();
    if (!isType1.has_value()) {
        return -1;  // not an OpenType font. HarfBuzz failed to parse it.
    }
    return isType1.value();
}

///////////////////////////////////////////////////////////////////////////////

static const JNINativeMethod gFontBuilderMethods[] = {
        {"nInitBuilder", "()J", (void*)Font_Builder_initBuilder},
        {"nAddAxis", "(JIF)V", (void*)Font_Builder_addAxis},
        {"nBuild", "(JLjava/nio/ByteBuffer;Ljava/lang/String;Ljava/lang/String;IZI)J",
         (void*)Font_Builder_build},
        {"nClone", "(JJIZI)J", (void*)Font_Builder_clone},
};

static const JNINativeMethod gFontMethods[] = {
        {"nGetMinikinFontPtr", "(J)J", (void*)Font_getMinikinFontPtr},
        {"nCloneFont", "(J)J", (void*)Font_cloneFont},
        {"nNewByteBuffer", "(J)Ljava/nio/ByteBuffer;", (void*)Font_newByteBuffer},
        {"nGetBufferAddress", "(J)J", (void*)Font_getBufferAddress},
        {"nGetReleaseNativeFont", "()J", (void*)Font_getReleaseNativeFontFunc},
        {"nGetGlyphBounds", "(JIJLandroid/graphics/RectF;)F", (void*)Font_getGlyphBounds},
        {"nGetFontMetrics", "(JJLandroid/graphics/Paint$FontMetrics;)F",
         (void*)Font_getFontMetrics},
        {"nGetFontPath", "(J)Ljava/lang/String;", (void*)Font_getFontPath},
        {"nGetLocaleList", "(J)Ljava/lang/String;", (void*)Font_getLocaleList},
        {"nGetPackedStyle", "(J)I", (void*)Font_getPackedStyle},
        {"nGetIndex", "(J)I", (void*)Font_getIndex},
        {"nGetAxisCount", "(J)I", (void*)Font_getAxisCount},
        {"nGetAxisInfo", "(JI)J", (void*)Font_getAxisInfo},
        {"nGetSourceId", "(J)I", (void*)Font_getSourceId},

        // System font accessors
        {"nGetAvailableFontSet", "()[J", (void*)Font_getAvailableFontSet},
};

static const JNINativeMethod gFontFileUtilMethods[] = {
    { "nGetFontRevision", "(Ljava/nio/ByteBuffer;I)J", (void*) FontFileUtil_getFontRevision },
    { "nGetFontPostScriptName", "(Ljava/nio/ByteBuffer;I)Ljava/lang/String;",
        (void*) FontFileUtil_getFontPostScriptName },
    { "nIsPostScriptType1Font", "(Ljava/nio/ByteBuffer;I)I",
        (void*) FontFileUtil_isPostScriptType1Font },
};

int register_android_graphics_fonts_Font(JNIEnv* env) {
    return RegisterMethodsOrDie(env, "android/graphics/fonts/Font$Builder", gFontBuilderMethods,
            NELEM(gFontBuilderMethods)) +
            RegisterMethodsOrDie(env, "android/graphics/fonts/Font", gFontMethods,
            NELEM(gFontMethods)) +
            RegisterMethodsOrDie(env, "android/graphics/fonts/FontFileUtil", gFontFileUtilMethods,
            NELEM(gFontFileUtilMethods));
}

namespace fonts {

std::shared_ptr<minikin::MinikinFont> createMinikinFontSkia(
        sk_sp<SkData>&& data, std::string_view fontPath, const void *fontPtr, size_t fontSize,
        int ttcIndex, const std::vector<minikin::FontVariation>& axes) {
    FatVector<SkFontArguments::VariationPosition::Coordinate, 2> skVariation;
    for (const auto& axis : axes) {
        skVariation.push_back({axis.axisTag, axis.value});
    }

    std::unique_ptr<SkStreamAsset> fontData(new SkMemoryStream(std::move(data)));

    SkFontArguments args;
    args.setCollectionIndex(ttcIndex);
    args.setVariationDesignPosition({skVariation.data(), static_cast<int>(skVariation.size())});

    sk_sp<SkFontMgr> fm(SkFontMgr::RefDefault());
    sk_sp<SkTypeface> face(fm->makeFromStream(std::move(fontData), args));
    if (face == nullptr) {
        return nullptr;
    }
    return std::make_shared<MinikinFontSkia>(std::move(face), getNewSourceId(), fontPtr, fontSize,
                                             fontPath, ttcIndex, axes);
}

int getNewSourceId() {
    static std::atomic<int> sSourceId = {0};
    return sSourceId++;
}

}  // namespace fonts

}  // namespace android
