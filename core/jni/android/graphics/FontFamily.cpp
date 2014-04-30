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
#include <android_runtime/AndroidRuntime.h>

#include "SkTypeface.h"
#include "GraphicsJNI.h"
#include <ScopedPrimitiveArray.h>
#include <ScopedUtfChars.h>

#ifdef USE_MINIKIN
#include <minikin/FontFamily.h>
#include "MinikinSkia.h"
#endif

namespace android {

static jlong FontFamily_create(JNIEnv* env, jobject clazz) {
#ifdef USE_MINIKIN
    return (jlong)new FontFamily();
#else
    return 0;
#endif
}

static void FontFamily_destroy(JNIEnv* env, jobject clazz, jlong ptr) {
    // TODO: work out lifetime issues
}

static jboolean FontFamily_addFont(JNIEnv* env, jobject clazz, jlong familyPtr, jstring path) {
#ifdef USE_MINIKIN
    NPE_CHECK_RETURN_ZERO(env, path);
    ScopedUtfChars str(env, path);
    ALOGD("addFont %s", str.c_str());
    SkTypeface* face = SkTypeface::CreateFromFile(str.c_str());
    if (face == NULL) {
        ALOGE("addFont failed to create font %s", str.c_str());
        return false;
    }
    MinikinFont* minikinFont = new MinikinFontSkia(face);
    FontFamily* fontFamily = (FontFamily*)familyPtr;
    return fontFamily->addFont(minikinFont);
#else
    return false;
#endif
}

///////////////////////////////////////////////////////////////////////////////

static JNINativeMethod gFontFamilyMethods[] = {
    { "nCreateFamily",            "()J", (void*)FontFamily_create },
    { "nDestroyFamily",           "(J)V", (void*)FontFamily_destroy },
    { "nAddFont",                 "(JLjava/lang/String;)Z", (void*)FontFamily_addFont },
};

int register_android_graphics_FontFamily(JNIEnv* env)
{
    return android::AndroidRuntime::registerNativeMethods(env,
        "android/graphics/FontFamily",
        gFontFamilyMethods, NELEM(gFontFamilyMethods));
}

}
