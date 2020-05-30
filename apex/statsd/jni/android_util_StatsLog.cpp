/*
 * Copyright (C) 2019 The Android Open Source Project
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

#define LOG_NAMESPACE "StatsLog.tag."
#define LOG_TAG "StatsLog_println"

#include <jni.h>
#include <log/log.h>
#include <nativehelper/scoped_local_ref.h>
#include "stats_buffer_writer.h"

namespace android {

static void android_util_StatsLog_write(JNIEnv* env, jobject clazz, jbyteArray buf, jint size,
        jint atomId) {
    if (buf == NULL) {
        return;
    }
    jint actualSize = env->GetArrayLength(buf);
    if (actualSize < size) {
        return;
    }

    jbyte* bufferArray = env->GetByteArrayElements(buf, NULL);
    if (bufferArray == NULL) {
        return;
    }

    write_buffer_to_statsd((void*) bufferArray, size, atomId);

    env->ReleaseByteArrayElements(buf, bufferArray, 0);
}

/*
 * JNI registration.
 */
static const JNINativeMethod gMethods[] = {
    /* name, signature, funcPtr */
    { "writeImpl", "([BII)V", (void*) android_util_StatsLog_write },
};

int register_android_util_StatsLog(JNIEnv* env)
{
    static const char* kStatsLogClass = "android/util/StatsLog";

    ScopedLocalRef<jclass> cls(env, env->FindClass(kStatsLogClass));
    if (cls.get() == nullptr) {
        ALOGE("jni statsd registration failure, class not found '%s'", kStatsLogClass);
        return JNI_ERR;
    }

    const jint count = sizeof(gMethods) / sizeof(gMethods[0]);
    int status = env->RegisterNatives(cls.get(), gMethods, count);
    if (status < 0) {
        ALOGE("jni statsd registration failure, status: %d", status);
        return JNI_ERR;
    }
    return JNI_VERSION_1_4;
}

}; // namespace android

/*
 * JNI Initialization
 */
jint JNI_OnLoad(JavaVM* jvm, void* reserved) {
    JNIEnv* e;

    ALOGV("statsd : loading JNI\n");
    // Check JNI version
    if (jvm->GetEnv((void**)&e, JNI_VERSION_1_4)) {
        ALOGE("JNI version mismatch error");
        return JNI_ERR;
    }

    return android::register_android_util_StatsLog(e);
}
