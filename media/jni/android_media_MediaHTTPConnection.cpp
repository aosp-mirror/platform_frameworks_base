/*
 * Copyright 2013, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

//#define LOG_NDEBUG 0
#define LOG_TAG "MediaHTTPConnection-JNI"
#include <utils/Log.h>

#include <binder/MemoryDealer.h>
#include <media/stagefright/foundation/ADebug.h>
#include <nativehelper/ScopedLocalRef.h>

#include "android_media_MediaHTTPConnection.h"
#include "android_util_Binder.h"

#include "android_runtime/AndroidRuntime.h"
#include "jni.h"
#include <nativehelper/JNIHelp.h>

namespace android {

JMediaHTTPConnection::JMediaHTTPConnection(JNIEnv *env, jobject thiz)
    : mClass(NULL),
      mObject(NULL),
      mByteArrayObj(NULL) {
    jclass clazz = env->GetObjectClass(thiz);
    CHECK(clazz != NULL);

    mClass = (jclass)env->NewGlobalRef(clazz);
    mObject = env->NewWeakGlobalRef(thiz);

    mDealer = new MemoryDealer(kBufferSize, "MediaHTTPConnection");
    mMemory = mDealer->allocate(kBufferSize);

    ScopedLocalRef<jbyteArray> tmp(
            env, env->NewByteArray(JMediaHTTPConnection::kBufferSize));

    mByteArrayObj = (jbyteArray)env->NewGlobalRef(tmp.get());
}

JMediaHTTPConnection::~JMediaHTTPConnection() {
    JNIEnv *env = AndroidRuntime::getJNIEnv();

    env->DeleteGlobalRef(mByteArrayObj);
    mByteArrayObj = NULL;
    env->DeleteWeakGlobalRef(mObject);
    mObject = NULL;
    env->DeleteGlobalRef(mClass);
    mClass = NULL;
}

sp<IMemory> JMediaHTTPConnection::getIMemory() {
    return mMemory;
}

jbyteArray JMediaHTTPConnection::getByteArrayObj() {
    return mByteArrayObj;
}

}  // namespace android

using namespace android;

struct fields_t {
    jfieldID context;

    jmethodID readAtMethodID;
};

static fields_t gFields;

static sp<JMediaHTTPConnection> setObject(
        JNIEnv *env, jobject thiz, const sp<JMediaHTTPConnection> &conn) {
    sp<JMediaHTTPConnection> old =
        (JMediaHTTPConnection *)env->GetLongField(thiz, gFields.context);

    if (conn != NULL) {
        conn->incStrong(thiz);
    }
    if (old != NULL) {
        old->decStrong(thiz);
    }
    env->SetLongField(thiz, gFields.context, (jlong)conn.get());

    return old;
}

static sp<JMediaHTTPConnection> getObject(JNIEnv *env, jobject thiz) {
    return (JMediaHTTPConnection *)env->GetLongField(thiz, gFields.context);
}

static void android_media_MediaHTTPConnection_native_init(JNIEnv *env) {
    ScopedLocalRef<jclass> clazz(
            env, env->FindClass("android/media/MediaHTTPConnection"));
    CHECK(clazz.get() != NULL);

    gFields.context = env->GetFieldID(clazz.get(), "mNativeContext", "J");
    CHECK(gFields.context != NULL);

    gFields.readAtMethodID = env->GetMethodID(
            clazz.get(), "readAt", "(J[BILandroid/media/MediaHTTPConnection$ConnectionState;)I");
}

static void android_media_MediaHTTPConnection_native_setup(
        JNIEnv *env, jobject thiz) {
    sp<JMediaHTTPConnection> conn = new JMediaHTTPConnection(env, thiz);

    setObject(env, thiz, conn);
}

static void android_media_MediaHTTPConnection_native_finalize(
        JNIEnv *env, jobject thiz) {
    setObject(env, thiz, NULL);
}

static jobject android_media_MediaHTTPConnection_native_getIMemory(
        JNIEnv *env, jobject thiz) {
    sp<JMediaHTTPConnection> conn = getObject(env, thiz);

    return javaObjectForIBinder(env, IInterface::asBinder(conn->getIMemory()));
}

static jint android_media_MediaHTTPConnection_native_readAt(
        JNIEnv *env, jobject thiz, jlong offset, jint size, jobject connectionState) {
    sp<JMediaHTTPConnection> conn = getObject(env, thiz);
    if (size > JMediaHTTPConnection::kBufferSize) {
        size = JMediaHTTPConnection::kBufferSize;
    }

    jbyteArray byteArrayObj = conn->getByteArrayObj();

    jint n = env->CallIntMethod(
            thiz, gFields.readAtMethodID, offset, byteArrayObj, size, connectionState);

    if (n > 0) {
        env->GetByteArrayRegion(
                byteArrayObj,
                0,
                n,
                (jbyte *)conn->getIMemory()->pointer());
    }

    return n;
}

static const JNINativeMethod gMethods[] = {
    { "native_getIMemory", "()Landroid/os/IBinder;",
      (void *)android_media_MediaHTTPConnection_native_getIMemory },

    { "native_readAt", "(JILandroid/media/MediaHTTPConnection$ConnectionState;)I",
      (void *)android_media_MediaHTTPConnection_native_readAt },

    { "native_init", "()V",
      (void *)android_media_MediaHTTPConnection_native_init },

    { "native_setup", "()V",
      (void *)android_media_MediaHTTPConnection_native_setup },

    { "native_finalize", "()V",
      (void *)android_media_MediaHTTPConnection_native_finalize },
};

int register_android_media_MediaHTTPConnection(JNIEnv *env) {
    return AndroidRuntime::registerNativeMethods(env,
                "android/media/MediaHTTPConnection", gMethods, NELEM(gMethods));
}

