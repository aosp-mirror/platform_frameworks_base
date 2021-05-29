/* //device/libs/android_runtime/android_util_StringBlock.cpp
**
** Copyright 2006, The Android Open Source Project
**
** Licensed under the Apache License, Version 2.0 (the "License");
** you may not use this file except in compliance with the License.
** You may obtain a copy of the License at
**
**     http://www.apache.org/licenses/LICENSE-2.0
**
** Unless required by applicable law or agreed to in writing, software
** distributed under the License is distributed on an "AS IS" BASIS,
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
** See the License for the specific language governing permissions and
** limitations under the License.
*/

#define LOG_TAG "StringBlock"

#include "jni.h"
#include <nativehelper/JNIHelp.h>
#include <utils/misc.h>
#include <core_jni_helpers.h>
#include <utils/Log.h>

#include <androidfw/ResourceTypes.h>

#include <stdio.h>

namespace android {

// ----------------------------------------------------------------------------

static jlong android_content_StringBlock_nativeCreate(JNIEnv* env, jobject clazz, jbyteArray bArray,
                                                      jint off, jint len) {
    if (bArray == NULL) {
        jniThrowNullPointerException(env, NULL);
        return 0;
    }

    jsize bLen = env->GetArrayLength(bArray);
    if (off < 0 || off >= bLen || len < 0 || len > bLen || (off+len) > bLen) {
        jniThrowException(env, "java/lang/IndexOutOfBoundsException", NULL);
        return 0;
    }

    jbyte* b = env->GetByteArrayElements(bArray, NULL);
    ResStringPool* osb = new ResStringPool(b+off, len, true);
    env->ReleaseByteArrayElements(bArray, b, 0);

    if (osb == NULL || osb->getError() != NO_ERROR) {
        jniThrowException(env, "java/lang/IllegalArgumentException", NULL);
        delete osb;
        return 0;
    }

    return reinterpret_cast<jlong>(osb);
}

static jint android_content_StringBlock_nativeGetSize(JNIEnv* env, jobject clazz, jlong token) {
    ResStringPool* osb = reinterpret_cast<ResStringPool*>(token);
    if (osb == NULL) {
        jniThrowNullPointerException(env, NULL);
        return 0;
    }

    return osb->size();
}

static jstring android_content_StringBlock_nativeGetString(JNIEnv* env, jobject clazz, jlong token,
                                                           jint idx) {
    ResStringPool* osb = reinterpret_cast<ResStringPool*>(token);
    if (osb == NULL) {
        jniThrowNullPointerException(env, NULL);
        return NULL;
    }

    if (auto str8 = osb->string8At(idx); str8.has_value()) {
        return env->NewStringUTF(str8->data());
    }

    auto str = osb->stringAt(idx);
    if (IsIOError(str)) {
        return NULL;
    } else if (UNLIKELY(!str.has_value())) {
        jniThrowException(env, "java/lang/IndexOutOfBoundsException", NULL);
        return NULL;
    }

    return env->NewString((const jchar*)str->data(), str->size());
}

static jintArray android_content_StringBlock_nativeGetStyle(JNIEnv* env, jobject clazz, jlong token,
                                                            jint idx) {
    ResStringPool* osb = reinterpret_cast<ResStringPool*>(token);
    if (osb == NULL) {
        jniThrowNullPointerException(env, NULL);
        return NULL;
    }

    auto spans = osb->styleAt(idx);
    if (!spans.has_value()) {
        return NULL;
    }

    jintArray array;
    {
        int num = 0;
        auto pos = *spans;
        while (true) {
            if (UNLIKELY(!pos)) {
                return NULL;
            }
            if (pos->name.index == ResStringPool_span::END) {
                break;
            }
            num++;
            pos++;
        }

        if (num == 0) {
            return NULL;
        }

        array = env->NewIntArray((num * sizeof(ResStringPool_span)) / sizeof(jint));
        if (array == NULL) { // NewIntArray already threw OutOfMemoryError.
            return NULL;
        }
    }
    {
        int num = 0;
        static const int numInts = sizeof(ResStringPool_span) / sizeof(jint);
        while ((*spans)->name.index != ResStringPool_span::END) {
            env->SetIntArrayRegion(array, num * numInts, numInts, (jint*)spans->unsafe_ptr());
            (*spans)++;
            num++;
        }
    }
    return array;
}

static void android_content_StringBlock_nativeDestroy(JNIEnv* env, jobject clazz, jlong token) {
    ResStringPool* osb = reinterpret_cast<ResStringPool*>(token);
    if (osb == NULL) {
        jniThrowNullPointerException(env, NULL);
        return;
    }

    delete osb;
}

// ----------------------------------------------------------------------------

/*
 * JNI registration.
 */
static const JNINativeMethod gStringBlockMethods[] = {
    /* name, signature, funcPtr */
    { "nativeCreate",      "([BII)J",
            (void*) android_content_StringBlock_nativeCreate },
    { "nativeGetSize",      "(J)I",
            (void*) android_content_StringBlock_nativeGetSize },
    { "nativeGetString",    "(JI)Ljava/lang/String;",
            (void*) android_content_StringBlock_nativeGetString },
    { "nativeGetStyle",    "(JI)[I",
            (void*) android_content_StringBlock_nativeGetStyle },
    { "nativeDestroy",      "(J)V",
            (void*) android_content_StringBlock_nativeDestroy },
};

int register_android_content_StringBlock(JNIEnv* env)
{
    return RegisterMethodsOrDie(env,
            "android/content/res/StringBlock", gStringBlockMethods, NELEM(gStringBlockMethods));
}

}; // namespace android
