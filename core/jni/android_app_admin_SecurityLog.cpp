/*
 * Copyright (C) 2016 The Android Open Source Project
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

#include <log/log_id.h>
#include <private/android_logger.h>

#include <nativehelper/JNIHelp.h>
#include "jni.h"

#include "core_jni_helpers.h"
#include "eventlog_helper.h"

namespace android {

constexpr char kSecurityLogEventClass[] = "android/app/admin/SecurityLog$SecurityEvent";
template class EventLogHelper<log_id_t::LOG_ID_SECURITY, kSecurityLogEventClass>;
using SLog = EventLogHelper<log_id_t::LOG_ID_SECURITY, kSecurityLogEventClass>;

static jboolean android_app_admin_SecurityLog_isLoggingEnabled(JNIEnv* env,
                                                    jobject /* clazz */) {
    return (bool)__android_log_security();
}

static void android_app_admin_SecurityLog_readEvents(JNIEnv* env, jobject /* clazz */,
                                             jobject out) {

    if (out == NULL) {
        jniThrowNullPointerException(env, NULL);
        return;
    }
    SLog::readEvents(env, ANDROID_LOG_NONBLOCK, 0, out);
}

static void android_app_admin_SecurityLog_readEventsSince(JNIEnv* env, jobject /* clazz */,
                                             jlong timestamp,
                                             jobject out) {

    if (out == NULL) {
        jniThrowNullPointerException(env, NULL);
        return;
    }
    SLog::readEvents(env, ANDROID_LOG_NONBLOCK, timestamp, out);
}

static void android_app_admin_SecurityLog_readPreviousEvents(JNIEnv* env, jobject /* clazz */,
                                             jobject out) {

    if (out == NULL) {
        jniThrowNullPointerException(env, NULL);
        return;
    }
    SLog::readEvents(env, ANDROID_LOG_NONBLOCK | ANDROID_LOG_PSTORE, 0, out);
}

static void android_app_admin_SecurityLog_readEventsOnWrapping(JNIEnv* env, jobject /* clazz */,
                                             jlong timestamp,
                                             jobject out) {
    if (out == NULL) {
        jniThrowNullPointerException(env, NULL);
        return;
    }
    SLog::readEvents(env, ANDROID_LOG_NONBLOCK | ANDROID_LOG_WRAP, timestamp, out);
}

/*
 * JNI registration.
 */
static const JNINativeMethod gRegisterMethods[] = {
    /* name, signature, funcPtr */
    { "isLoggingEnabled",
      "()Z",
      (void*) android_app_admin_SecurityLog_isLoggingEnabled
    },
    { "writeEvent",
      "(ILjava/lang/String;)I",
      (void*) SLog::writeEventString
    },
    { "writeEvent",
      "(I[Ljava/lang/Object;)I",
      (void*) SLog::writeEventArray
    },
    { "readEvents",
      "(Ljava/util/Collection;)V",
      (void*) android_app_admin_SecurityLog_readEvents
    },
    { "readEventsSince",
      "(JLjava/util/Collection;)V",
      (void*) android_app_admin_SecurityLog_readEventsSince
    },
    { "readPreviousEvents",
      "(Ljava/util/Collection;)V",
      (void*) android_app_admin_SecurityLog_readPreviousEvents
    },
    { "readEventsOnWrapping",
      "(JLjava/util/Collection;)V",
      (void*) android_app_admin_SecurityLog_readEventsOnWrapping
    },
};

int register_android_app_admin_SecurityLog(JNIEnv* env) {
    SLog::Init(env);

    return RegisterMethodsOrDie(
            env,
            "android/app/admin/SecurityLog",
            gRegisterMethods, NELEM(gRegisterMethods));
}

}; // namespace android
