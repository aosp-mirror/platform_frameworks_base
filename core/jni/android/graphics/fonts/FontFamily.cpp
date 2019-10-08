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

#define LOG_TAG "Minikin"

#include <nativehelper/JNIHelp.h>
#include <nativehelper/ScopedUtfChars.h>
#include <core_jni_helpers.h>

#include "FontUtils.h"

#include <minikin/FontFamily.h>
#include <minikin/LocaleList.h>

#include <memory>

namespace android {

struct NativeFamilyBuilder {
    std::vector<minikin::Font> fonts;
};

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
static void FontFamily_Builder_addFont(jlong builderPtr, jlong fontPtr) {
    toBuilder(builderPtr)->fonts.push_back(toFontWrapper(fontPtr)->font);
}

// Regular JNI
static jlong FontFamily_Builder_build(JNIEnv* env, jobject clazz, jlong builderPtr,
            jstring langTags, jint variant, jboolean isCustomFallback) {
    std::unique_ptr<NativeFamilyBuilder> builder(toBuilder(builderPtr));
    uint32_t localeId;
    if (langTags == nullptr) {
        localeId = minikin::registerLocaleList("");
    } else {
        ScopedUtfChars str(env, langTags);
        localeId = minikin::registerLocaleList(str.c_str());
    }
    std::shared_ptr<minikin::FontFamily> family = std::make_shared<minikin::FontFamily>(
            localeId, static_cast<minikin::FamilyVariant>(variant), std::move(builder->fonts),
            isCustomFallback);
    if (family->getCoverage().length() == 0) {
        // No coverage means minikin rejected given font for some reasons.
        jniThrowException(env, "java/lang/IllegalArgumentException",
                          "Failed to create internal object. maybe invalid font data");
        return 0;
    }
    return reinterpret_cast<jlong>(new FontFamilyWrapper(std::move(family)));
}

// CriticalNative
static jlong FontFamily_Builder_GetReleaseFunc() {
    return reinterpret_cast<jlong>(releaseFontFamily);
}

///////////////////////////////////////////////////////////////////////////////

static const JNINativeMethod gFontFamilyBuilderMethods[] = {
    { "nInitBuilder", "()J", (void*) FontFamily_Builder_initBuilder },
    { "nAddFont", "(JJ)V", (void*) FontFamily_Builder_addFont },
    { "nBuild", "(JLjava/lang/String;IZ)J", (void*) FontFamily_Builder_build },

    { "nGetReleaseNativeFamily", "()J", (void*) FontFamily_Builder_GetReleaseFunc },
};

int register_android_graphics_fonts_FontFamily(JNIEnv* env) {
    return RegisterMethodsOrDie(env, "android/graphics/fonts/FontFamily$Builder",
            gFontFamilyBuilderMethods, NELEM(gFontFamilyBuilderMethods));
}

}
