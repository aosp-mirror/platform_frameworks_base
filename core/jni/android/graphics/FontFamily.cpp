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

#define LOG_TAG "Minikin"

#include "JNIHelp.h"
#include <core_jni_helpers.h>

#include "SkData.h"
#include "SkRefCnt.h"
#include "SkTypeface.h"
#include "GraphicsJNI.h"
#include <ScopedPrimitiveArray.h>
#include <ScopedUtfChars.h>
#include <android_runtime/android_util_AssetManager.h>
#include <androidfw/AssetManager.h>
#include "Utils.h"

#include "TypefaceImpl.h"
#include <minikin/FontFamily.h>
#include "MinikinSkia.h"

namespace android {

static jlong FontFamily_create(JNIEnv* env, jobject clazz, jstring lang, jint variant) {
    FontLanguage fontLanguage;
    if (lang != NULL) {
        ScopedUtfChars str(env, lang);
        fontLanguage = FontLanguage(str.c_str(), str.size());
    }
    return (jlong)new FontFamily(fontLanguage, variant);
}

static void FontFamily_unref(JNIEnv* env, jobject clazz, jlong familyPtr) {
    FontFamily* fontFamily = reinterpret_cast<FontFamily*>(familyPtr);
    fontFamily->Unref();
}

static jboolean addSkTypeface(FontFamily* family, SkTypeface* face) {
    MinikinFont* minikinFont = new MinikinFontSkia(face);
    bool result = family->addFont(minikinFont);
    minikinFont->Unref();
    return result;
}

static jboolean FontFamily_addFont(JNIEnv* env, jobject clazz, jlong familyPtr, jstring path) {
    NPE_CHECK_RETURN_ZERO(env, path);
    ScopedUtfChars str(env, path);
    SkTypeface* face = SkTypeface::CreateFromFile(str.c_str());
    if (face == NULL) {
        ALOGE("addFont failed to create font %s", str.c_str());
        return false;
    }
    FontFamily* fontFamily = reinterpret_cast<FontFamily*>(familyPtr);
    return addSkTypeface(fontFamily, face);
}

static jboolean FontFamily_addFontWeightStyle(JNIEnv* env, jobject clazz, jlong familyPtr,
        jstring path, jint weight, jboolean isItalic) {
    NPE_CHECK_RETURN_ZERO(env, path);
    ScopedUtfChars str(env, path);
    SkTypeface* face = SkTypeface::CreateFromFile(str.c_str());
    if (face == NULL) {
        ALOGE("addFont failed to create font %s", str.c_str());
        return false;
    }
    FontFamily* fontFamily = reinterpret_cast<FontFamily*>(familyPtr);
    MinikinFont* minikinFont = new MinikinFontSkia(face);
    fontFamily->addFont(minikinFont, FontStyle(weight / 100, isItalic));
    minikinFont->Unref();
    return true;
}

static void releaseAsset(const void* ptr, size_t length, void* context) {
    delete static_cast<Asset*>(context);
}

static jboolean FontFamily_addFontFromAsset(JNIEnv* env, jobject, jlong familyPtr,
        jobject jassetMgr, jstring jpath) {
    NPE_CHECK_RETURN_ZERO(env, jassetMgr);
    NPE_CHECK_RETURN_ZERO(env, jpath);

    AssetManager* mgr = assetManagerForJavaObject(env, jassetMgr);
    if (NULL == mgr) {
        return false;
    }

    ScopedUtfChars str(env, jpath);
    Asset* asset = mgr->open(str.c_str(), Asset::ACCESS_BUFFER);
    if (NULL == asset) {
        return false;
    }

    const void* buf = asset->getBuffer(false);
    if (NULL == buf) {
        delete asset;
        return false;
    }

    SkAutoTUnref<SkData> data(SkData::NewWithProc(buf, asset->getLength(), releaseAsset, asset));
    SkMemoryStream* stream = new SkMemoryStream(data);
    // CreateFromStream takes ownership of stream.
    SkTypeface* face = SkTypeface::CreateFromStream(stream);
    if (face == NULL) {
        ALOGE("addFontFromAsset failed to create font %s", str.c_str());
        return false;
    }
    FontFamily* fontFamily = reinterpret_cast<FontFamily*>(familyPtr);
    return addSkTypeface(fontFamily, face);
}

///////////////////////////////////////////////////////////////////////////////

static JNINativeMethod gFontFamilyMethods[] = {
    { "nCreateFamily",         "(Ljava/lang/String;I)J", (void*)FontFamily_create },
    { "nUnrefFamily",          "(J)V", (void*)FontFamily_unref },
    { "nAddFont",              "(JLjava/lang/String;)Z", (void*)FontFamily_addFont },
    { "nAddFontWeightStyle",   "(JLjava/lang/String;IZ)Z", (void*)FontFamily_addFontWeightStyle },
    { "nAddFontFromAsset",     "(JLandroid/content/res/AssetManager;Ljava/lang/String;)Z",
                                           (void*)FontFamily_addFontFromAsset },
};

int register_android_graphics_FontFamily(JNIEnv* env)
{
    return RegisterMethodsOrDie(env, "android/graphics/FontFamily", gFontFamilyMethods,
                                NELEM(gFontFamilyMethods));
}

}
