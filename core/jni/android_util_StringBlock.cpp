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
#include <utils/misc.h>
#include <android_runtime/AndroidRuntime.h>
#include <utils/Log.h>

#include <utils/ResourceTypes.h>

#include <stdio.h>

namespace android {

// ----------------------------------------------------------------------------

static void doThrow(JNIEnv* env, const char* exc, const char* msg = NULL)
{
    jclass npeClazz;

    npeClazz = env->FindClass(exc);
    LOG_FATAL_IF(npeClazz == NULL, "Unable to find class %s", exc);

    env->ThrowNew(npeClazz, msg);
}

static jint android_content_StringBlock_nativeCreate(JNIEnv* env, jobject clazz,
                                                  jbyteArray bArray,
                                                  jint off, jint len)
{
    if (bArray == NULL) {
        doThrow(env, "java/lang/NullPointerException");
        return 0;
    }

    jsize bLen = env->GetArrayLength(bArray);
    if (off < 0 || off >= bLen || len < 0 || len > bLen || (off+len) > bLen) {
        doThrow(env, "java/lang/IndexOutOfBoundsException");
        return 0;
    }

    jbyte* b = env->GetByteArrayElements(bArray, NULL);
    ResStringPool* osb = new ResStringPool(b+off, len, true);
    env->ReleaseByteArrayElements(bArray, b, 0);

    if (osb == NULL || osb->getError() != NO_ERROR) {
        doThrow(env, "java/lang/IllegalArgumentException");
        return 0;
    }

    return (jint)osb;
}

static jint android_content_StringBlock_nativeGetSize(JNIEnv* env, jobject clazz,
                                                   jint token)
{
    ResStringPool* osb = (ResStringPool*)token;
    if (osb == NULL) {
        doThrow(env, "java/lang/NullPointerException");
        return 0;
    }

    return osb->size();
}

static jstring android_content_StringBlock_nativeGetString(JNIEnv* env, jobject clazz,
                                                        jint token, jint idx)
{
    ResStringPool* osb = (ResStringPool*)token;
    if (osb == NULL) {
        doThrow(env, "java/lang/NullPointerException");
        return 0;
    }

    size_t len;
    const char* str8 = osb->string8At(idx, &len);
    if (str8 != NULL) {
        return env->NewStringUTF(str8);
    }

    const char16_t* str = osb->stringAt(idx, &len);
    if (str == NULL) {
        doThrow(env, "java/lang/IndexOutOfBoundsException");
        return 0;
    }

    return env->NewString((const jchar*)str, len);
}

static jintArray android_content_StringBlock_nativeGetStyle(JNIEnv* env, jobject clazz,
                                                         jint token, jint idx)
{
    ResStringPool* osb = (ResStringPool*)token;
    if (osb == NULL) {
        doThrow(env, "java/lang/NullPointerException");
        return NULL;
    }

    const ResStringPool_span* spans = osb->styleAt(idx);
    if (spans == NULL) {
        return NULL;
    }

    const ResStringPool_span* pos = spans;
    int num = 0;
    while (pos->name.index != ResStringPool_span::END) {
        num++;
        pos++;
    }

    if (num == 0) {
        return NULL;
    }

    jintArray array = env->NewIntArray((num*sizeof(ResStringPool_span))/sizeof(jint));
    if (array == NULL) {
        doThrow(env, "java/lang/OutOfMemoryError");
        return NULL;
    }

    num = 0;
    static const int numInts = sizeof(ResStringPool_span)/sizeof(jint);
    while (spans->name.index != ResStringPool_span::END) {
        env->SetIntArrayRegion(array,
                                  num*numInts, numInts,
                                  (jint*)spans);
        spans++;
        num++;
    }

    return array;
}

static void android_content_StringBlock_nativeDestroy(JNIEnv* env, jobject clazz,
                                                   jint token)
{
    ResStringPool* osb = (ResStringPool*)token;
    if (osb == NULL) {
        doThrow(env, "java/lang/NullPointerException");
        return;
    }

    delete osb;
}

// ----------------------------------------------------------------------------

/*
 * JNI registration.
 */
static JNINativeMethod gStringBlockMethods[] = {
    /* name, signature, funcPtr */
    { "nativeCreate",      "([BII)I",
            (void*) android_content_StringBlock_nativeCreate },
    { "nativeGetSize",      "(I)I",
            (void*) android_content_StringBlock_nativeGetSize },
    { "nativeGetString",    "(II)Ljava/lang/String;",
            (void*) android_content_StringBlock_nativeGetString },
    { "nativeGetStyle",    "(II)[I",
            (void*) android_content_StringBlock_nativeGetStyle },
    { "nativeDestroy",      "(I)V",
            (void*) android_content_StringBlock_nativeDestroy },
};

int register_android_content_StringBlock(JNIEnv* env)
{
    return AndroidRuntime::registerNativeMethods(env,
            "android/content/res/StringBlock", gStringBlockMethods, NELEM(gStringBlockMethods));
}

}; // namespace android

