/* //device/libs/android_runtime/android_os_SystemProperties.cpp
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

#include "cutils/properties.h"
#include "jni.h"
#include "android_runtime/AndroidRuntime.h"
#include <nativehelper/JNIHelp.h>

namespace android
{

static jstring SystemProperties_getSS(JNIEnv *env, jobject clazz,
                                      jstring keyJ, jstring defJ)
{
    int len;
    const char* key;
    char buf[PROPERTY_VALUE_MAX];
    jstring rvJ = NULL;

    if (keyJ == NULL) {
        jniThrowException(env, "java/lang/NullPointerException",
                                "key must not be null.");
        goto error;
    }

    key = env->GetStringUTFChars(keyJ, NULL);

    len = property_get(key, buf, "");
    if ((len <= 0) && (defJ != NULL)) {
        rvJ = defJ;
    } else if (len >= 0) {
        rvJ = env->NewStringUTF(buf);
    } else {
        rvJ = env->NewStringUTF("");
    }

    env->ReleaseStringUTFChars(keyJ, key);

error:
    return rvJ;
}

static jstring SystemProperties_getS(JNIEnv *env, jobject clazz,
                                      jstring keyJ)
{
    return SystemProperties_getSS(env, clazz, keyJ, NULL);
}

static jint SystemProperties_get_int(JNIEnv *env, jobject clazz,
                                      jstring keyJ, jint defJ)
{
    int len;
    const char* key;
    char buf[PROPERTY_VALUE_MAX];
    jint result = defJ;

    if (keyJ == NULL) {
        jniThrowException(env, "java/lang/NullPointerException",
                                "key must not be null.");
        goto error;
    }

    key = env->GetStringUTFChars(keyJ, NULL);

    len = property_get(key, buf, "");
    if (len > 0) {
        jint temp;
        if (sscanf(buf, "%d", &temp) == 1)
            result = temp;
    }

    env->ReleaseStringUTFChars(keyJ, key);

error:
    return result;
}

static jlong SystemProperties_get_long(JNIEnv *env, jobject clazz,
                                      jstring keyJ, jlong defJ)
{
    int len;
    const char* key;
    char buf[PROPERTY_VALUE_MAX];
    jlong result = defJ;

    if (keyJ == NULL) {
        jniThrowException(env, "java/lang/NullPointerException",
                                "key must not be null.");
        goto error;
    }

    key = env->GetStringUTFChars(keyJ, NULL);

    len = property_get(key, buf, "");
    if (len > 0) {
        jlong temp;
        if (sscanf(buf, "%lld", &temp) == 1)
            result = temp;
    }

    env->ReleaseStringUTFChars(keyJ, key);

error:
    return result;
}

static jboolean SystemProperties_get_boolean(JNIEnv *env, jobject clazz,
                                      jstring keyJ, jboolean defJ)
{
    int len;
    const char* key;
    char buf[PROPERTY_VALUE_MAX];
    jboolean result = defJ;

    if (keyJ == NULL) {
        jniThrowException(env, "java/lang/NullPointerException",
                                "key must not be null.");
        goto error;
    }

    key = env->GetStringUTFChars(keyJ, NULL);

    len = property_get(key, buf, "");
    if (len == 1) {
        char ch = buf[0];
        if (ch == '0' || ch == 'n')
            result = false;
        else if (ch == '1' || ch == 'y')
            result = true;
    } else if (len > 1) {
         if (!strcmp(buf, "no") || !strcmp(buf, "false") || !strcmp(buf, "off")) {
            result = false;
        } else if (!strcmp(buf, "yes") || !strcmp(buf, "true") || !strcmp(buf, "on")) {
            result = true;
        }
    }

    env->ReleaseStringUTFChars(keyJ, key);

error:
    return result;
}

static void SystemProperties_set(JNIEnv *env, jobject clazz,
                                      jstring keyJ, jstring valJ)
{
    int err;
    const char* key;
    const char* val;

    if (keyJ == NULL) {
        jniThrowException(env, "java/lang/NullPointerException",
                                "key must not be null.");
        return ;
    }
    key = env->GetStringUTFChars(keyJ, NULL);

    if (valJ == NULL) {
        val = "";       /* NULL pointer not allowed here */
    } else {
        val = env->GetStringUTFChars(valJ, NULL);
    }
    
    err = property_set(key, val);
    
    env->ReleaseStringUTFChars(keyJ, key);
    
    if (valJ != NULL) {
    	env->ReleaseStringUTFChars(valJ, val);
    }
} 

static JNINativeMethod method_table[] = {
    { "native_get", "(Ljava/lang/String;)Ljava/lang/String;",
      (void*) SystemProperties_getS },
    { "native_get", "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
      (void*) SystemProperties_getSS },
    { "native_get_int", "(Ljava/lang/String;I)I",
      (void*) SystemProperties_get_int },
    { "native_get_long", "(Ljava/lang/String;J)J",
      (void*) SystemProperties_get_long },
    { "native_get_boolean", "(Ljava/lang/String;Z)Z",
      (void*) SystemProperties_get_boolean },
    { "native_set", "(Ljava/lang/String;Ljava/lang/String;)V",
      (void*) SystemProperties_set },
};

int register_android_os_SystemProperties(JNIEnv *env)
{
    return AndroidRuntime::registerNativeMethods(
        env, "android/os/SystemProperties",
        method_table, NELEM(method_table));
}

};
