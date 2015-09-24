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

#include <utils/Log.h>
#include <utils/Mutex.h>
#include "android_runtime/AndroidRuntime.h"

#include "jni.h"
#include "JNIHelp.h"

#include "proxy_resolver_v8.h"

namespace android {

class ProxyErrorLogger : public net::ProxyErrorListener {
public:
    ~ProxyErrorLogger() {

    }
    void AlertMessage(String16 message) {
        String8 str(message);
        ALOGD("Alert: %s", str.string());
    }
    void ErrorMessage(String16 message) {
        String8 str(message);
        ALOGE("Error: %s", str.string());
    }
};

net::ProxyResolverV8* proxyResolver = NULL;
ProxyErrorLogger* logger = NULL;
bool pacSet = false;

String16 jstringToString16(JNIEnv* env, jstring jstr) {
    const jchar* str = env->GetStringCritical(jstr, 0);
    String16 str16(reinterpret_cast<const char16_t*>(str),
                   env->GetStringLength(jstr));
    env->ReleaseStringCritical(jstr, str);
    return str16;
}

jstring string16ToJstring(JNIEnv* env, String16 string) {
    const char16_t* str = string.string();
    size_t len = string.size();

    return env->NewString(reinterpret_cast<const jchar*>(str), len);
}

static jboolean com_android_pacprocessor_PacNative_createV8ParserNativeLocked(JNIEnv* /* env */,
        jobject) {
    if (proxyResolver == NULL) {
        logger = new ProxyErrorLogger();
        proxyResolver = new net::ProxyResolverV8(net::ProxyResolverJSBindings::CreateDefault(),
                logger);
        pacSet = false;
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

static jboolean com_android_pacprocessor_PacNative_destroyV8ParserNativeLocked(JNIEnv* /* env */,
        jobject) {
    if (proxyResolver != NULL) {
        delete logger;
        delete proxyResolver;
        logger = NULL;
        proxyResolver = NULL;
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

static jboolean com_android_pacprocessor_PacNative_setProxyScriptNativeLocked(JNIEnv* env, jobject,
        jstring script) {
    String16 script16 = jstringToString16(env, script);

    if (proxyResolver == NULL) {
        ALOGE("V8 Parser not started when setting PAC script");
        return JNI_TRUE;
    }

    if (proxyResolver->SetPacScript(script16) != OK) {
        ALOGE("Unable to set PAC script");
        return JNI_TRUE;
    }
    pacSet = true;

    return JNI_FALSE;
}

static jstring com_android_pacprocessor_PacNative_makeProxyRequestNativeLocked(JNIEnv* env, jobject,
        jstring url, jstring host) {
    String16 url16 = jstringToString16(env, url);
    String16 host16 = jstringToString16(env, host);
    String16 ret;

    if (proxyResolver == NULL) {
        ALOGE("V8 Parser not initialized when running PAC script");
        return NULL;
    }

    if (!pacSet) {
        ALOGW("Attempting to run PAC with no script set");
        return NULL;
    }

    if (proxyResolver->GetProxyForURL(url16, host16, &ret) != OK) {
        String8 ret8(ret);
        ALOGE("Error Running PAC: %s", ret8.string());
        return NULL;
    }

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
