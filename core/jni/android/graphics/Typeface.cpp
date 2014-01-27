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

#include "jni.h"
#include <android_runtime/AndroidRuntime.h>

#include "GraphicsJNI.h"
#include "SkStream.h"
#include "SkTypeface.h"
#include "TypefaceImpl.h"
#include <android_runtime/android_util_AssetManager.h>
#include <androidfw/AssetManager.h>

using namespace android;

class AutoJavaStringToUTF8 {
public:
    AutoJavaStringToUTF8(JNIEnv* env, jstring str) : fEnv(env), fJStr(str)
    {
        fCStr = env->GetStringUTFChars(str, NULL);
    }
    ~AutoJavaStringToUTF8()
    {
        fEnv->ReleaseStringUTFChars(fJStr, fCStr);
    }
    const char* c_str() const { return fCStr; }

private:
    JNIEnv*     fEnv;
    jstring     fJStr;
    const char* fCStr;
};

static jlong Typeface_create(JNIEnv* env, jobject, jstring name,
                             jint styleHandle) {
    SkTypeface::Style style = static_cast<SkTypeface::Style>(styleHandle);
    TypefaceImpl* face = NULL;

    if (NULL != name) {
        AutoJavaStringToUTF8    str(env, name);
        face = TypefaceImpl_createFromName(str.c_str(), style);
    }

    // return the default font at the best style if no exact match exists
    if (NULL == face) {
        face = TypefaceImpl_createFromName(NULL, style);
    }
    return reinterpret_cast<jlong>(face);
}

static jlong Typeface_createFromTypeface(JNIEnv* env, jobject, jlong familyHandle, jint style) {
    SkTypeface* family = reinterpret_cast<SkTypeface*>(familyHandle);
    TypefaceImpl* face = TypefaceImpl_createFromTypeface(family, (SkTypeface::Style)style);
    // Try to find the closest matching font, using the standard heuristic
    if (NULL == face) {
        face = TypefaceImpl_createFromTypeface(family, (SkTypeface::Style)(style ^ SkTypeface::kItalic));
    }
    for (int i = 0; NULL == face && i < 4; i++) {
        face = TypefaceImpl_createFromTypeface(family, (SkTypeface::Style)i);
    }
    if (NULL == face) {
        face = TypefaceImpl_createFromName(NULL, (SkTypeface::Style)style);
    }
    return reinterpret_cast<jlong>(face);
}

static void Typeface_unref(JNIEnv* env, jobject obj, jlong faceHandle) {
    TypefaceImpl* face = reinterpret_cast<TypefaceImpl*>(faceHandle);
    TypefaceImpl_unref(face);
}

static jint Typeface_getStyle(JNIEnv* env, jobject obj, jlong faceHandle) {
    TypefaceImpl* face = reinterpret_cast<TypefaceImpl*>(faceHandle);
    return TypefaceImpl_getStyle(face);
}

static jlong Typeface_createFromAsset(JNIEnv* env, jobject,
                                      jobject jassetMgr,
                                      jstring jpath) {
    NPE_CHECK_RETURN_ZERO(env, jassetMgr);
    NPE_CHECK_RETURN_ZERO(env, jpath);

    AssetManager* mgr = assetManagerForJavaObject(env, jassetMgr);
    if (NULL == mgr) {
        return NULL;
    }

    AutoJavaStringToUTF8 str(env, jpath);
    Asset* asset = mgr->open(str.c_str(), Asset::ACCESS_BUFFER);
    if (NULL == asset) {
        return NULL;
    }

    return reinterpret_cast<jlong>(TypefaceImpl_createFromAsset(asset));
}

static jlong Typeface_createFromFile(JNIEnv* env, jobject, jstring jpath) {
    NPE_CHECK_RETURN_ZERO(env, jpath);

    AutoJavaStringToUTF8 str(env, jpath);
    return reinterpret_cast<jlong>(TypefaceImpl_createFromFile(str.c_str()));
}

///////////////////////////////////////////////////////////////////////////////

static JNINativeMethod gTypefaceMethods[] = {
    { "nativeCreate",        "(Ljava/lang/String;I)J", (void*)Typeface_create },
    { "nativeCreateFromTypeface", "(JI)J", (void*)Typeface_createFromTypeface },
    { "nativeUnref",              "(J)V",  (void*)Typeface_unref },
    { "nativeGetStyle",           "(J)I",  (void*)Typeface_getStyle },
    { "nativeCreateFromAsset",    "(Landroid/content/res/AssetManager;Ljava/lang/String;)J",
                                           (void*)Typeface_createFromAsset },
    { "nativeCreateFromFile",     "(Ljava/lang/String;)J",
                                           (void*)Typeface_createFromFile },
};

int register_android_graphics_Typeface(JNIEnv* env)
{
    return android::AndroidRuntime::registerNativeMethods(env,
                                                       "android/graphics/Typeface",
                                                       gTypefaceMethods,
                                                       SK_ARRAY_COUNT(gTypefaceMethods));
}
