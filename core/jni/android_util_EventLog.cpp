/*
 * Copyright (C) 2007-2014 The Android Open Source Project
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

#include <android-base/macros.h>
#include <log/log_id.h>

#include <nativehelper/JNIHelp.h>
#include "jni.h"

#include "core_jni_helpers.h"
#include "eventlog_helper.h"

namespace android {

constexpr char kEventLogEventClass[] = "android/util/EventLog$Event";
template class EventLogHelper<log_id_t::LOG_ID_EVENTS, kEventLogEventClass>;
using ELog = EventLogHelper<log_id_t::LOG_ID_EVENTS, kEventLogEventClass>;

/*
 * In class android.util.EventLog:
 *  static native void readEvents(int[] tags, Collection<Event> output)
 *
 *  Reads events from the event log
 */
static void android_util_EventLog_readEvents(JNIEnv* env, jobject clazz ATTRIBUTE_UNUSED,
                                             jintArray tags,
                                             jobject out) {

    if (tags == NULL || out == NULL) {
        jniThrowNullPointerException(env, NULL);
        return;
    }

    ELog::readEvents(env, ANDROID_LOG_NONBLOCK, tags, 0, out);
 }
/*
 * In class android.util.EventLog:
 *  static native void readEventsOnWrapping(int[] tags, long timestamp, Collection<Event> output)
 *
 *  Reads events from the event log, blocking until events after timestamp are to be overwritten.
 */
static void android_util_EventLog_readEventsOnWrapping(JNIEnv* env, jobject clazz ATTRIBUTE_UNUSED,
                                             jintArray tags,
                                             jlong timestamp,
                                             jobject out) {
    if (tags == NULL || out == NULL) {
        jniThrowNullPointerException(env, NULL);
        return;
    }
    ELog::readEvents(env, ANDROID_LOG_NONBLOCK | ANDROID_LOG_WRAP, tags, timestamp, out);
}

/*
 * JNI registration.
 */
static const JNINativeMethod gRegisterMethods[] = {
    /* name, signature, funcPtr */
    { "writeEvent", "(II)I", (void*) ELog::writeEventInteger },
    { "writeEvent", "(IJ)I", (void*) ELog::writeEventLong },
    { "writeEvent", "(IF)I", (void*) ELog::writeEventFloat },
    { "writeEvent", "(ILjava/lang/String;)I", (void*) ELog::writeEventString },
    { "writeEvent", "(I[Ljava/lang/Object;)I", (void*) ELog::writeEventArray },
    { "readEvents",
      "([ILjava/util/Collection;)V",
      (void*) android_util_EventLog_readEvents
    },
    { "readEventsOnWrapping",
      "([IJLjava/util/Collection;)V",
      (void*) android_util_EventLog_readEventsOnWrapping
    },
};

int register_android_util_EventLog(JNIEnv* env) {
    ELog::Init(env);

    return RegisterMethodsOrDie(
            env,
            "android/util/EventLog",
            gRegisterMethods, NELEM(gRegisterMethods));
}

}; // namespace android
