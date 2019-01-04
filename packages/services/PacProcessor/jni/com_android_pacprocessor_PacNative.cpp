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

#define LOG_TAG "PacProcessor"

#include <stdlib.h>
#include <string>

#include <utils/Log.h>
#include <utils/Mutex.h>
#include "android_runtime/AndroidRuntime.h"

#include "jni.h"
#include <nativehelper/JNIHelp.h>

#include "proxy_resolver_v8_wrapper.h"

namespace android {

ProxyResolverV8Handle* proxyResolver = NULL;
bool pacSet = false;

std::u16string jstringToString16(JNIEnv* env, jstring jstr) {
    const jchar* str = env->GetStringCritical(jstr, 0);
    std::u16string str16(reinterpret_cast<const char16_t*>(str),
                   env->GetStringLength(jstr));
    env->ReleaseStringCritical(jstr, str);
    return str16;
}

jstring string16ToJstring(JNIEnv* env, std::u16string string) {
    const char16_t* str = string.data();
    size_t len = string.length();

    return env->NewString(reinterpret_cast<const jchar*>(str), len);
}

static jboolean com_android_pacprocessor_PacNative_createV8ParserNativeLocked(JNIEnv* /* env */,
        jobject) {
    if (proxyResolver == NULL) {
        proxyResolver = ProxyResolverV8Handle_new();
        pacSet = false;
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

static jboolean com_android_pacprocessor_PacNative_destroyV8ParserNativeLocked(JNIEnv* /* env */,
        jobject) {
    if (proxyResolver != NULL) {
        ProxyResolverV8Handle_delete(proxyResolver);
        proxyResolver = NULL;
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

static jboolean com_android_pacprocessor_PacNative_setProxyScriptNativeLocked(JNIEnv* env, jobject,
        jstring script) {
    std::u16string script16 = jstringToString16(env, script);

    if (proxyResolver == NULL) {
        ALOGE("V8 Parser not started when setting PAC script");
        return JNI_TRUE;
    }

    if (ProxyResolverV8Handle_SetPacScript(proxyResolver, script16.data()) != OK) {
        ALOGE("Unable to set PAC script");
        return JNI_TRUE;
    }
    pacSet = true;

    return JNI_FALSE;
}

static jstring com_android_pacprocessor_PacNative_makeProxyRequestNativeLocked(JNIEnv* env, jobject,
        jstring url, jstring host) {
    std::u16string url16 = jstringToString16(env, url);
    std::u16string host16 = jstringToString16(env, host);

    if (proxyResolver == NULL) {
        ALOGE("V8 Parser not initialized when running PAC script");
        return NULL;
    }

    if (!pacSet) {
        ALOGW("Attempting to run PAC with no script set");
        return NULL;
    }

    std::unique_ptr<char16_t, decltype(&free)> result = std::unique_ptr<char16_t, decltype(&free)>(
        ProxyResolverV8Handle_GetProxyForURL(proxyResolver, url16.data(), host16.data()), &free);
    if (result.get() == NULL) {
        ALOGE("Error Running PAC");
        return NULL;
    }

    std::u16string ret(result.get());
    jstring jret = string16ToJstring(env, ret);

    return jret;
}

static const JNINativeMethod gMethods[] = {
    { "createV8ParserNativeLocked", "()Z",
        (void*)com_android_pacprocessor_PacNative_createV8ParserNativeLocked},
    { "destroyV8ParserNativeLocked", "()Z",
        (void*)com_android_pacprocessor_PacNative_destroyV8ParserNativeLocked},
    { "setProxyScriptNativeLocked", "(Ljava/lang/String;)Z",
        (void*)com_android_pacprocessor_PacNative_setProxyScriptNativeLocked},
    { "makeProxyRequestNativeLocked", "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
        (void*)com_android_pacprocessor_PacNative_makeProxyRequestNativeLocked},
};

int register_com_android_pacprocessor_PacNative(JNIEnv* env) {
    return jniRegisterNativeMethods(env, "com/android/pacprocessor/PacNative",
            gMethods, NELEM(gMethods));
}

} /* namespace android */
