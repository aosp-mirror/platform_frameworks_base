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

#include "graphics_jni_helpers.h"
#include <nativehelper/ScopedUtfChars.h>

#include "FontUtils.h"

#include <minikin/FontFamily.h>
#include <minikin/LocaleList.h>

#include <memory>

namespace android {

namespace {
struct NativeFamilyBuilder {
    std::vector<std::shared_ptr<minikin::Font>> fonts;
};
}  // namespace

static inline NativeFamilyBuilder* toBuilder(jlong ptr) {
    return reinterpret_cast<NativeFamilyBuilder*>(ptr);
}

static inline FontWrapper* toFontWrapper(jlong ptr) {
    return reinterpret_cast<FontWrapper*>(ptr);
}

static void releaseFontFamily(jlong family) {
    delete reinterpret_cast<FontFamilyWrapper*>(family);
}

// Regular JNI
static jlong FontFamily_Builder_initBuilder(JNIEnv*, jobject) {
    return reinterpret_cast<jlong>(new NativeFamilyBuilder());
}

// Critical Native
static void FontFamily_Builder_addFont(CRITICAL_JNI_PARAMS_COMMA jlong builderPtr, jlong fontPtr) {
    toBuilder(builderPtr)->fonts.push_back(toFontWrapper(fontPtr)->font);
}

// Regular JNI
static jlong FontFamily_Builder_build(JNIEnv* env, jobject clazz, jlong builderPtr,
                                      jstring langTags, jint variant, jboolean isCustomFallback,
                                      jboolean isDefaultFallback) {
    std::unique_ptr<NativeFamilyBuilder> builder(toBuilder(builderPtr));
    uint32_t localeId;
    if (langTags == nullptr) {
        localeId = minikin::registerLocaleList("");
    } else {
        ScopedUtfChars str(env, langTags);
        localeId = minikin::registerLocaleList(str.c_str());
    }
    std::shared_ptr<minikin::FontFamily> family = minikin::FontFamily::create(
            localeId, static_cast<minikin::FamilyVariant>(variant), std::move(builder->fonts),
            isCustomFallback, isDefaultFallback);
    if (family->getCoverage().length() == 0) {
        // No coverage means minikin rejected given font for some reasons.
        jniThrowException(env, "java/lang/IllegalArgumentException",
                          "Failed to create internal object. maybe invalid font data");
        return 0;
    }
    return reinterpret_cast<jlong>(new FontFamilyWrapper(std::move(family)));
}

// CriticalNative
static jlong FontFamily_Builder_GetReleaseFunc(CRITICAL_JNI_PARAMS) {
    return reinterpret_cast<jlong>(releaseFontFamily);
}

// FastNative
static jstring FontFamily_getLangTags(JNIEnv* env, jobject, jlong familyPtr) {
    FontFamilyWrapper* family = reinterpret_cast<FontFamilyWrapper*>(familyPtr);
    uint32_t localeListId = family->family->localeListId();
    if (localeListId == 0) {
        return nullptr;
    }
    std::string langTags = minikin::getLocaleString(localeListId);
    return env->NewStringUTF(langTags.c_str());
}

// CriticalNative
static jint FontFamily_getVariant(CRITICAL_JNI_PARAMS_COMMA jlong familyPtr) {
    FontFamilyWrapper* family = reinterpret_cast<FontFamilyWrapper*>(familyPtr);
    return static_cast<jint>(family->family->variant());
}

// CriticalNative
static jint FontFamily_getFontSize(CRITICAL_JNI_PARAMS_COMMA jlong familyPtr) {
    FontFamilyWrapper* family = reinterpret_cast<FontFamilyWrapper*>(familyPtr);
    return family->family->getNumFonts();
}

// CriticalNative
static jlong FontFamily_getFont(CRITICAL_JNI_PARAMS_COMMA jlong familyPtr, jint index) {
    FontFamilyWrapper* family = reinterpret_cast<FontFamilyWrapper*>(familyPtr);
    std::shared_ptr<minikin::Font> font = family->family->getFontRef(index);
    return reinterpret_cast<jlong>(new FontWrapper(std::move(font)));
}

///////////////////////////////////////////////////////////////////////////////

static const JNINativeMethod gFontFamilyBuilderMethods[] = {
        {"nInitBuilder", "()J", (void*)FontFamily_Builder_initBuilder},
        {"nAddFont", "(JJ)V", (void*)FontFamily_Builder_addFont},
        {"nBuild", "(JLjava/lang/String;IZZ)J", (void*)FontFamily_Builder_build},
        {"nGetReleaseNativeFamily", "()J", (void*)FontFamily_Builder_GetReleaseFunc},
};

static const JNINativeMethod gFontFamilyMethods[] = {
        {"nGetFontSize", "(J)I", (void*)FontFamily_getFontSize},
        {"nGetFont", "(JI)J", (void*)FontFamily_getFont},
        {"nGetLangTags", "(J)Ljava/lang/String;", (void*)FontFamily_getLangTags},
        {"nGetVariant", "(J)I", (void*)FontFamily_getVariant},
};

int register_android_graphics_fonts_FontFamily(JNIEnv* env) {
    return RegisterMethodsOrDie(env, "android/graphics/fonts/FontFamily$Builder",
                                gFontFamilyBuilderMethods, NELEM(gFontFamilyBuilderMethods)) +
           RegisterMethodsOrDie(env, "android/graphics/fonts/FontFamily", gFontFamilyMethods,
                                NELEM(gFontFamilyMethods));
}

}
