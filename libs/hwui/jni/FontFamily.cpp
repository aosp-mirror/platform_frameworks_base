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

#undef LOG_TAG
#define LOG_TAG "Minikin"

#include <nativehelper/ScopedPrimitiveArray.h>
#include <nativehelper/ScopedUtfChars.h>
#include "FontUtils.h"
#include "GraphicsJNI.h"
#include "SkData.h"
#include "SkFontMgr.h"
#include "SkRefCnt.h"
#include "SkStream.h"
#include "SkTypeface.h"
#include "Utils.h"
#include "fonts/Font.h"

#include <hwui/MinikinSkia.h>
#include <hwui/Typeface.h>
#include <minikin/FontFamily.h>
#include <minikin/LocaleList.h>
#include <ui/FatVector.h>

#include <memory>

///////////////////////////////////////////////////////////////////////////////////////////////////
//
// The following JNI methods are kept only for compatibility reasons due to hidden API accesses.
//
///////////////////////////////////////////////////////////////////////////////////////////////////

namespace android {

namespace {
struct NativeFamilyBuilder {
    NativeFamilyBuilder(uint32_t langId, int variant)
        : langId(langId), variant(static_cast<minikin::FamilyVariant>(variant)) {}
    uint32_t langId;
    minikin::FamilyVariant variant;
    std::vector<std::shared_ptr<minikin::Font>> fonts;
    std::vector<minikin::FontVariation> axes;
};
}  // namespace

static inline NativeFamilyBuilder* toNativeBuilder(jlong ptr) {
    return reinterpret_cast<NativeFamilyBuilder*>(ptr);
}

static inline FontFamilyWrapper* toFamily(jlong ptr) {
    return reinterpret_cast<FontFamilyWrapper*>(ptr);
}

template<typename Ptr> static inline jlong toJLong(Ptr ptr) {
    return reinterpret_cast<jlong>(ptr);
}

static jlong FontFamily_initBuilder(JNIEnv* env, jobject clazz, jstring langs, jint variant) {
    NativeFamilyBuilder* builder;
    if (langs != nullptr) {
        ScopedUtfChars str(env, langs);
        builder = new NativeFamilyBuilder(minikin::registerLocaleList(str.c_str()), variant);
    } else {
        builder = new NativeFamilyBuilder(minikin::registerLocaleList(""), variant);
    }
    return toJLong(builder);
}

static jlong FontFamily_create(CRITICAL_JNI_PARAMS_COMMA jlong builderPtr) {
    if (builderPtr == 0) {
        return 0;
    }
    NativeFamilyBuilder* builder = toNativeBuilder(builderPtr);
    if (builder->fonts.empty()) {
        return 0;
    }
    std::shared_ptr<minikin::FontFamily> family = minikin::FontFamily::create(
            builder->langId, builder->variant, std::move(builder->fonts),
            true /* isCustomFallback */, false /* isDefaultFallback */);
    if (family->getCoverage().length() == 0) {
        return 0;
    }
    return toJLong(new FontFamilyWrapper(std::move(family)));
}

static void releaseBuilder(jlong builderPtr) {
    delete toNativeBuilder(builderPtr);
}

static jlong FontFamily_getBuilderReleaseFunc(CRITICAL_JNI_PARAMS) {
    return toJLong(&releaseBuilder);
}

static void releaseFamily(jlong familyPtr) {
    delete toFamily(familyPtr);
}

static jlong FontFamily_getFamilyReleaseFunc(CRITICAL_JNI_PARAMS) {
    return toJLong(&releaseFamily);
}

static bool addSkTypeface(NativeFamilyBuilder* builder, sk_sp<SkData>&& data, int ttcIndex,
        jint weight, jint italic) {
    FatVector<SkFontArguments::VariationPosition::Coordinate, 2> skVariation;
    for (const auto& axis : builder->axes) {
        skVariation.push_back({axis.axisTag, axis.value});
    }

    const size_t fontSize = data->size();
    const void* fontPtr = data->data();
    std::unique_ptr<SkStreamAsset> fontData(new SkMemoryStream(std::move(data)));

    SkFontArguments args;
    args.setCollectionIndex(ttcIndex);
    args.setVariationDesignPosition({skVariation.data(), static_cast<int>(skVariation.size())});

    sk_sp<SkFontMgr> fm(SkFontMgr::RefDefault());
    sk_sp<SkTypeface> face(fm->makeFromStream(std::move(fontData), args));
    if (face == NULL) {
        ALOGE("addFont failed to create font, invalid request");
        builder->axes.clear();
        return false;
    }
    std::shared_ptr<minikin::MinikinFont> minikinFont =
            std::make_shared<MinikinFontSkia>(std::move(face), fonts::getNewSourceId(), fontPtr,
                                              fontSize, "", ttcIndex, builder->axes);
    minikin::Font::Builder fontBuilder(minikinFont);

    if (weight != RESOLVE_BY_FONT_TABLE) {
        fontBuilder.setWeight(weight);
    }
    if (italic != RESOLVE_BY_FONT_TABLE) {
        fontBuilder.setSlant(static_cast<minikin::FontStyle::Slant>(italic != 0));
    }
    builder->fonts.push_back(fontBuilder.build());
    builder->axes.clear();
    return true;
}

static void release_global_ref(const void* /*data*/, void* context) {
    JNIEnv* env = GraphicsJNI::getJNIEnv();
    bool needToAttach = (env == NULL);
    if (needToAttach) {
        env = GraphicsJNI::attachJNIEnv("release_font_data");
        if (env == nullptr) {
            ALOGE("failed to attach to thread to release global ref.");
            return;
        }
    }

    jobject obj = reinterpret_cast<jobject>(context);
    env->DeleteGlobalRef(obj);

    if (needToAttach) {
       GraphicsJNI::detachJNIEnv();
    }
}

static jboolean FontFamily_addFont(JNIEnv* env, jobject clazz, jlong builderPtr, jobject bytebuf,
        jint ttcIndex, jint weight, jint isItalic) {
    NPE_CHECK_RETURN_ZERO(env, bytebuf);
    NativeFamilyBuilder* builder = reinterpret_cast<NativeFamilyBuilder*>(builderPtr);
    const void* fontPtr = env->GetDirectBufferAddress(bytebuf);
    if (fontPtr == NULL) {
        ALOGE("addFont failed to create font, buffer invalid");
        builder->axes.clear();
        return false;
    }
    jlong fontSize = env->GetDirectBufferCapacity(bytebuf);
    if (fontSize < 0) {
        ALOGE("addFont failed to create font, buffer size invalid");
        builder->axes.clear();
        return false;
    }
    jobject fontRef = MakeGlobalRefOrDie(env, bytebuf);
    sk_sp<SkData> data(SkData::MakeWithProc(fontPtr, fontSize,
            release_global_ref, reinterpret_cast<void*>(fontRef)));
    return addSkTypeface(builder, std::move(data), ttcIndex, weight, isItalic);
}

static jboolean FontFamily_addFontWeightStyle(JNIEnv* env, jobject clazz, jlong builderPtr,
        jobject font, jint ttcIndex, jint weight, jint isItalic) {
    NPE_CHECK_RETURN_ZERO(env, font);
    NativeFamilyBuilder* builder = toNativeBuilder(builderPtr);
    const void* fontPtr = env->GetDirectBufferAddress(font);
    if (fontPtr == NULL) {
        ALOGE("addFont failed to create font, buffer invalid");
        builder->axes.clear();
        return false;
    }
    jlong fontSize = env->GetDirectBufferCapacity(font);
    if (fontSize < 0) {
        ALOGE("addFont failed to create font, buffer size invalid");
        builder->axes.clear();
        return false;
    }
    jobject fontRef = MakeGlobalRefOrDie(env, font);
    sk_sp<SkData> data(SkData::MakeWithProc(fontPtr, fontSize,
            release_global_ref, reinterpret_cast<void*>(fontRef)));
    return addSkTypeface(builder, std::move(data), ttcIndex, weight, isItalic);
}

static void FontFamily_addAxisValue(CRITICAL_JNI_PARAMS_COMMA jlong builderPtr, jint tag, jfloat value) {
    NativeFamilyBuilder* builder = toNativeBuilder(builderPtr);
    builder->axes.push_back({static_cast<minikin::AxisTag>(tag), value});
}

///////////////////////////////////////////////////////////////////////////////

static const JNINativeMethod gFontFamilyMethods[] = {
    { "nInitBuilder",           "(Ljava/lang/String;I)J", (void*)FontFamily_initBuilder },
    { "nCreateFamily",          "(J)J", (void*)FontFamily_create },
    { "nGetBuilderReleaseFunc", "()J", (void*)FontFamily_getBuilderReleaseFunc },
    { "nGetFamilyReleaseFunc",  "()J", (void*)FontFamily_getFamilyReleaseFunc },
    { "nAddFont",               "(JLjava/nio/ByteBuffer;III)Z", (void*)FontFamily_addFont },
    { "nAddFontWeightStyle",    "(JLjava/nio/ByteBuffer;III)Z",
            (void*)FontFamily_addFontWeightStyle },
    { "nAddAxisValue",         "(JIF)V", (void*)FontFamily_addAxisValue },
};

int register_android_graphics_FontFamily(JNIEnv* env)
{
    int err = RegisterMethodsOrDie(env, "android/graphics/FontFamily", gFontFamilyMethods,
            NELEM(gFontFamilyMethods));

    init_FontUtils(env);
    return err;
}

}
