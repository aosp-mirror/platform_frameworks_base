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

#include <android-base/macros.h>
#include <assert.h>
#include <log/log.h>               // For LOGGER_ENTRY_MAX_PAYLOAD.
#include <utils/Log.h>
#include <utils/String8.h>

#include "jni.h"
#include <nativehelper/JNIHelp.h>
#include "utils/misc.h"
#include "core_jni_helpers.h"
#include "android_util_Log.h"

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

static jboolean isLoggable(const char* tag, jint level) {
    return __android_log_is_loggable(level, tag, ANDROID_LOG_INFO);
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

    jboolean result = isLoggable(chars, level);

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
 * In class android.util.Log:
 *  private static native int logger_entry_max_payload_native()
 */
static jint android_util_Log_logger_entry_max_payload_native(JNIEnv* env ATTRIBUTE_UNUSED,
                                                             jobject clazz ATTRIBUTE_UNUSED)
{
    return static_cast<jint>(LOGGER_ENTRY_MAX_PAYLOAD);
}

/*
 * JNI registration.
 */
static const JNINativeMethod gMethods[] = {
    /* name, signature, funcPtr */
    { "isLoggable",      "(Ljava/lang/String;I)Z", (void*) android_util_Log_isLoggable },
    { "println_native",  "(IILjava/lang/String;Ljava/lang/String;)I", (void*) android_util_Log_println_native },
    { "logger_entry_max_payload_native",  "()I", (void*) android_util_Log_logger_entry_max_payload_native },
};

int register_android_util_Log(JNIEnv* env)
{
    jclass clazz = FindClassOrDie(env, "android/util/Log");

    levels.verbose = env->GetStaticIntField(clazz, GetStaticFieldIDOrDie(env, clazz, "VERBOSE", "I"));
    levels.debug = env->GetStaticIntField(clazz, GetStaticFieldIDOrDie(env, clazz, "DEBUG", "I"));
    levels.info = env->GetStaticIntField(clazz, GetStaticFieldIDOrDie(env, clazz, "INFO", "I"));
    levels.warn = env->GetStaticIntField(clazz, GetStaticFieldIDOrDie(env, clazz, "WARN", "I"));
    levels.error = env->GetStaticIntField(clazz, GetStaticFieldIDOrDie(env, clazz, "ERROR", "I"));
    levels.assert = env->GetStaticIntField(clazz, GetStaticFieldIDOrDie(env, clazz, "ASSERT", "I"));

    return RegisterMethodsOrDie(env, "android/util/Log", gMethods, NELEM(gMethods));
}

}; // namespace android
