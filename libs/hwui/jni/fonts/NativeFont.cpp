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
#include <minikin/LocaleList.h>
#include <ui/FatVector.h>

#include <memory>

namespace android {

// Critical Native
static jint NativeFont_getFamilyCount(CRITICAL_JNI_PARAMS_COMMA jlong typefaceHandle) {
    Typeface* tf = reinterpret_cast<Typeface*>(typefaceHandle);
    return tf->fFontCollection->getFamilies().size();
}

// Critical Native
static jlong NativeFont_getFamily(CRITICAL_JNI_PARAMS_COMMA jlong typefaceHandle, jint index) {
    Typeface* tf = reinterpret_cast<Typeface*>(typefaceHandle);
    return reinterpret_cast<jlong>(tf->fFontCollection->getFamilies()[index].get());

}

// Fast Native
static jstring NativeFont_getLocaleList(JNIEnv* env, jobject, jlong familyHandle) {
    minikin::FontFamily* family = reinterpret_cast<minikin::FontFamily*>(familyHandle);
    uint32_t localeListId = family->localeListId();
    return env->NewStringUTF(minikin::getLocaleString(localeListId).c_str());
}

// Critical Native
static jint NativeFont_getFontCount(CRITICAL_JNI_PARAMS_COMMA jlong familyHandle) {
    minikin::FontFamily* family = reinterpret_cast<minikin::FontFamily*>(familyHandle);
    return family->getNumFonts();
}

// Critical Native
static jlong NativeFont_getFont(CRITICAL_JNI_PARAMS_COMMA jlong familyHandle, jint index) {
    minikin::FontFamily* family = reinterpret_cast<minikin::FontFamily*>(familyHandle);
    return reinterpret_cast<jlong>(family->getFont(index));
}

// Critical Native
static jlong NativeFont_getFontInfo(CRITICAL_JNI_PARAMS_COMMA jlong fontHandle) {
    const minikin::Font* font = reinterpret_cast<minikin::Font*>(fontHandle);
    MinikinFontSkia* minikinSkia = static_cast<MinikinFontSkia*>(font->typeface().get());

    uint64_t result = font->style().weight();
    result |= font->style().slant() == minikin::FontStyle::Slant::ITALIC ? 0x10000 : 0x00000;
    result |= ((static_cast<uint64_t>(minikinSkia->GetFontIndex())) << 32);
    result |= ((static_cast<uint64_t>(minikinSkia->GetAxes().size())) << 48);
    return result;
}

// Critical Native
static jlong NativeFont_getAxisInfo(CRITICAL_JNI_PARAMS_COMMA jlong fontHandle, jint index) {
    const minikin::Font* font = reinterpret_cast<minikin::Font*>(fontHandle);
    MinikinFontSkia* minikinSkia = static_cast<MinikinFontSkia*>(font->typeface().get());
    const minikin::FontVariation& var = minikinSkia->GetAxes().at(index);
    uint32_t floatBinary = *reinterpret_cast<const uint32_t*>(&var.value);
    return (static_cast<uint64_t>(var.axisTag) << 32) | static_cast<uint64_t>(floatBinary);
}

// FastNative
static jstring NativeFont_getFontPath(JNIEnv* env, jobject, jlong fontHandle) {
    const minikin::Font* font = reinterpret_cast<minikin::Font*>(fontHandle);
    MinikinFontSkia* minikinSkia = static_cast<MinikinFontSkia*>(font->typeface().get());
    const std::string& filePath = minikinSkia->getFilePath();
    if (filePath.empty()) {
        return nullptr;
    }
    return env->NewStringUTF(filePath.c_str());
}

///////////////////////////////////////////////////////////////////////////////

static const JNINativeMethod gNativeFontMethods[] = {
    { "nGetFamilyCount", "(J)I", (void*) NativeFont_getFamilyCount },
    { "nGetFamily", "(JI)J", (void*) NativeFont_getFamily },
    { "nGetLocaleList", "(J)Ljava/lang/String;", (void*) NativeFont_getLocaleList },
    { "nGetFontCount", "(J)I", (void*) NativeFont_getFontCount },
    { "nGetFont", "(JI)J", (void*) NativeFont_getFont },
    { "nGetFontInfo", "(J)J", (void*) NativeFont_getFontInfo },
    { "nGetAxisInfo", "(JI)J", (void*) NativeFont_getAxisInfo },
    { "nGetFontPath", "(J)Ljava/lang/String;", (void*) NativeFont_getFontPath },
};

int register_android_graphics_fonts_NativeFont(JNIEnv* env) {
    return RegisterMethodsOrDie(env, "android/graphics/fonts/NativeFont", gNativeFontMethods,
            NELEM(gNativeFontMethods));
}

}  // namespace android
