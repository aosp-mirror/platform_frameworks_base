/*
 * Copyright 2017, The Android Open Source Project
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
#define LOG_TAG "Media2HTTPConnection-JNI"
#include <utils/Log.h>

#include <media/stagefright/foundation/ADebug.h>
#include <nativehelper/ScopedLocalRef.h>

#include "android_media_Media2HTTPConnection.h"
#include "android_util_Binder.h"

#include "android_runtime/AndroidRuntime.h"
#include "android_runtime/Log.h"
#include "jni.h"
#include <nativehelper/JNIHelp.h>

namespace android {

static const size_t kBufferSize = 32768;

JMedia2HTTPConnection::JMedia2HTTPConnection(JNIEnv *env, jobject thiz) {
    mMedia2HTTPConnectionObj = env->NewGlobalRef(thiz);
    CHECK(mMedia2HTTPConnectionObj != NULL);

    ScopedLocalRef<jclass> media2HTTPConnectionClass(
            env, env->GetObjectClass(mMedia2HTTPConnectionObj));
    CHECK(media2HTTPConnectionClass.get() != NULL);

    mConnectMethod = env->GetMethodID(
            media2HTTPConnectionClass.get(),
            "connect",
            "(Ljava/lang/String;Ljava/lang/String;)Z");
    CHECK(mConnectMethod != NULL);

    mDisconnectMethod = env->GetMethodID(
            media2HTTPConnectionClass.get(),
            "disconnect",
            "()V");
    CHECK(mDisconnectMethod != NULL);

    mReadAtMethod = env->GetMethodID(
            media2HTTPConnectionClass.get(),
            "readAt",
            "(J[BI)I");
    CHECK(mReadAtMethod != NULL);

    mGetSizeMethod = env->GetMethodID(
            media2HTTPConnectionClass.get(),
            "getSize",
            "()J");
    CHECK(mGetSizeMethod != NULL);

    mGetMIMETypeMethod = env->GetMethodID(
            media2HTTPConnectionClass.get(),
            "getMIMEType",
            "()Ljava/lang/String;");
    CHECK(mGetMIMETypeMethod != NULL);

    mGetUriMethod = env->GetMethodID(
            media2HTTPConnectionClass.get(),
            "getUri",
            "()Ljava/lang/String;");
    CHECK(mGetUriMethod != NULL);

    ScopedLocalRef<jbyteArray> tmp(
        env, env->NewByteArray(kBufferSize));
    mByteArrayObj = (jbyteArray)env->NewGlobalRef(tmp.get());
    CHECK(mByteArrayObj != NULL);
}

JMedia2HTTPConnection::~JMedia2HTTPConnection() {
    JNIEnv *env = AndroidRuntime::getJNIEnv();
    env->DeleteGlobalRef(mMedia2HTTPConnectionObj);
    env->DeleteGlobalRef(mByteArrayObj);
}

bool JMedia2HTTPConnection::connect(
        const char *uri, const KeyedVector<String8, String8> *headers) {
    String8 tmp("");
    if (headers != NULL) {
        for (size_t i = 0; i < headers->size(); ++i) {
            tmp.append(headers->keyAt(i));
            tmp.append(String8(": "));
            tmp.append(headers->valueAt(i));
            tmp.append(String8("\r\n"));
        }
    }

    JNIEnv* env = AndroidRuntime::getJNIEnv();
    jstring juri = env->NewStringUTF(uri);
    jstring jheaders = env->NewStringUTF(tmp.string());

    jboolean ret =
        env->CallBooleanMethod(mMedia2HTTPConnectionObj, mConnectMethod, juri, jheaders);

    env->DeleteLocalRef(juri);
    env->DeleteLocalRef(jheaders);

    return (bool)ret;
}

void JMedia2HTTPConnection::disconnect() {
    JNIEnv* env = AndroidRuntime::getJNIEnv();
    env->CallVoidMethod(mMedia2HTTPConnectionObj, mDisconnectMethod);
}

ssize_t JMedia2HTTPConnection::readAt(off64_t offset, void *data, size_t size) {
    JNIEnv* env = AndroidRuntime::getJNIEnv();

    if (size > kBufferSize) {
        size = kBufferSize;
    }

    jint n = env->CallIntMethod(
            mMedia2HTTPConnectionObj, mReadAtMethod, (jlong)offset, mByteArrayObj, (jint)size);

    if (n > 0) {
        env->GetByteArrayRegion(
                mByteArrayObj,
                0,
                n,
                (jbyte *)data);
    }

    return n;
}

off64_t JMedia2HTTPConnection::getSize() {
    JNIEnv* env = AndroidRuntime::getJNIEnv();
    return (off64_t)(env->CallLongMethod(mMedia2HTTPConnectionObj, mGetSizeMethod));
}

status_t JMedia2HTTPConnection::getMIMEType(String8 *mimeType) {
    JNIEnv* env = AndroidRuntime::getJNIEnv();
    jstring jmime = (jstring)env->CallObjectMethod(mMedia2HTTPConnectionObj, mGetMIMETypeMethod);
    jboolean flag = env->ExceptionCheck();
    if (flag) {
        env->ExceptionClear();
        return UNKNOWN_ERROR;
    }

    const char *str = env->GetStringUTFChars(jmime, 0);
    if (str != NULL) {
        *mimeType = String8(str);
    } else {
        *mimeType = "application/octet-stream";
    }
    env->ReleaseStringUTFChars(jmime, str);
    return OK;
}

status_t JMedia2HTTPConnection::getUri(String8 *uri) {
    JNIEnv* env = AndroidRuntime::getJNIEnv();
    jstring juri = (jstring)env->CallObjectMethod(mMedia2HTTPConnectionObj, mGetUriMethod);
    jboolean flag = env->ExceptionCheck();
    if (flag) {
        env->ExceptionClear();
        return UNKNOWN_ERROR;
    }

    const char *str = env->GetStringUTFChars(juri, 0);
    *uri = String8(str);
    env->ReleaseStringUTFChars(juri, str);
    return OK;
}

}  // namespace android
