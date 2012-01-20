/* //device/libs/android_runtime/android_util_Log.cpp
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

#define LOG_NAMESPACE "log.tag."
#define LOG_TAG "Log_println"

#include <assert.h>
#include <cutils/properties.h>
#include <utils/Log.h>
#include <utils/String8.h>

#include "jni.h"
#include "JNIHelp.h"
#include "utils/misc.h"
#include "android_runtime/AndroidRuntime.h"
#include "android_util_Log.h"

#define MIN(a,b) ((a<b)?a:b)

namespace android {

struct levels_t {
    jint verbose;
    jint debug;
    jint info;
    jint warn;
    jint error;
    jint assert;
};
static levels_t levels;

static int toLevel(const char* value)
{
    switch (value[0]) {
        case 'V': return levels.verbose;
        case 'D': return levels.debug;
        case 'I': return levels.info;
        case 'W': return levels.warn;
        case 'E': return levels.error;
        case 'A': return levels.assert;
        case 'S': return -1; // SUPPRESS
    }
    return levels.info;
}

static jboolean isLoggable(const char* tag, jint level) {
    String8 key;
    key.append(LOG_NAMESPACE);
    key.append(tag);

    char buf[PROPERTY_VALUE_MAX];
    if (property_get(key.string(), buf, "") <= 0) {
        return false;
    }

    int logLevel = toLevel(buf);
    return logLevel >= 0 && level >= logLevel;
}

static jboolean android_util_Log_isLoggable(JNIEnv* env, jobject clazz, jstring tag, jint level)
{
    if (tag == NULL) {
        return false;
    }

    const char* chars = env->GetStringUTFChars(tag, NULL);
    if (!chars) {
        return false;
    }

    jboolean result = false;
    if ((strlen(chars)+sizeof(LOG_NAMESPACE)) > PROPERTY_KEY_MAX) {
        char buf2[200];
        snprintf(buf2, sizeof(buf2), "Log tag \"%s\" exceeds limit of %d characters\n",
                chars, PROPERTY_KEY_MAX - sizeof(LOG_NAMESPACE));

        jniThrowException(env, "java/lang/IllegalArgumentException", buf2);
    } else {
        result = isLoggable(chars, level);
    }

    env->ReleaseStringUTFChars(tag, chars);
    return result;
}

bool android_util_Log_isVerboseLogEnabled(const char* tag) {
    return isLoggable(tag, levels.verbose);
}

/*
 * In class android.util.Log:
 *  public static native int println_native(int buffer, int priority, String tag, String msg)
 */
static jint android_util_Log_println_native(JNIEnv* env, jobject clazz,
        jint bufID, jint priority, jstring tagObj, jstring msgObj)
{
    const char* tag = NULL;
    const char* msg = NULL;

    if (msgObj == NULL) {
        jniThrowNullPointerException(env, "println needs a message");
        return -1;
    }

    if (bufID < 0 || bufID >= LOG_ID_MAX) {
        jniThrowNullPointerException(env, "bad bufID");
        return -1;
    }

    if (tagObj != NULL)
        tag = env->GetStringUTFChars(tagObj, NULL);
    msg = env->GetStringUTFChars(msgObj, NULL);

    int res = __android_log_buf_write(bufID, (android_LogPriority)priority, tag, msg);

    if (tag != NULL)
        env->ReleaseStringUTFChars(tagObj, tag);
    env->ReleaseStringUTFChars(msgObj, msg);

    return res;
}

/*
 * JNI registration.
 */
static JNINativeMethod gMethods[] = {
    /* name, signature, funcPtr */
    { "isLoggable",      "(Ljava/lang/String;I)Z", (void*) android_util_Log_isLoggable },
    { "println_native",  "(IILjava/lang/String;Ljava/lang/String;)I", (void*) android_util_Log_println_native },
};

int register_android_util_Log(JNIEnv* env)
{
    jclass clazz = env->FindClass("android/util/Log");

    if (clazz == NULL) {
        ALOGE("Can't find android/util/Log");
        return -1;
    }

    levels.verbose = env->GetStaticIntField(clazz, env->GetStaticFieldID(clazz, "VERBOSE", "I"));
    levels.debug = env->GetStaticIntField(clazz, env->GetStaticFieldID(clazz, "DEBUG", "I"));
    levels.info = env->GetStaticIntField(clazz, env->GetStaticFieldID(clazz, "INFO", "I"));
    levels.warn = env->GetStaticIntField(clazz, env->GetStaticFieldID(clazz, "WARN", "I"));
    levels.error = env->GetStaticIntField(clazz, env->GetStaticFieldID(clazz, "ERROR", "I"));
    levels.assert = env->GetStaticIntField(clazz, env->GetStaticFieldID(clazz, "ASSERT", "I"));

    return AndroidRuntime::registerNativeMethods(env, "android/util/Log", gMethods, NELEM(gMethods));
}

}; // namespace android
