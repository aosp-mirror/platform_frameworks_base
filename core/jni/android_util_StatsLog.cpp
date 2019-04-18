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

#include <assert.h>
#include <cutils/properties.h>

#include "jni.h"
#include <nativehelper/JNIHelp.h>
#include "utils/misc.h"
#include "core_jni_helpers.h"
#include "stats_event_list.h"

namespace android {

static void android_util_StatsLog_writeRaw(JNIEnv* env, jobject clazz, jbyteArray buf, jint size)
{
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
    const uint32_t statsEventTag = 1937006964;
    struct iovec vec[2];
    vec[0].iov_base = (void*) &statsEventTag;
    vec[0].iov_len = sizeof(statsEventTag);
    vec[1].iov_base = (void*) bufferArray;
    vec[1].iov_len = size;
    write_to_statsd(vec, 2);

    env->ReleaseByteArrayElements(buf, bufferArray, 0);
}

/*
 * JNI registration.
 */
static const JNINativeMethod gMethods[] = {
    /* name, signature, funcPtr */
    { "writeRaw", "([BI)V", (void*) android_util_StatsLog_writeRaw },
};

int register_android_util_StatsLog(JNIEnv* env)
{
    return RegisterMethodsOrDie(env, "android/util/StatsLog", gMethods, NELEM(gMethods));
}

}; // namespace android
