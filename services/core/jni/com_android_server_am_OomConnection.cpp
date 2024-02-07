/*
 * Copyright (C) 2023 The Android Open Source Project
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

#define LOG_TAG "OomConnection"

#include <core_jni_helpers.h>
#include <jni.h>
#include <memevents/memevents.h>

namespace android {

// Used to cache the results of the JNI name lookup
static struct {
    jclass clazz;
    jmethodID ctor;
} sOomKillRecordInfo;

static memevents::MemEventListener memevent_listener;

/**
 * Initialize listening and waiting for new out-of-memory (OOM) events to occur.
 * Once a OOM event is detected, we then fetch the list of OOM kills, and return
 * a corresponding java array with the information gathered.
 *
 * In the case that we encounter an error, we make sure to close the epfd, and
 * the OOM file descriptor, by calling `deregisterAllEvents()`.
 *
 * @return list of `android.os.OomKillRecord`
 * @throws java.lang.RuntimeException
 */
static jobjectArray android_server_am_OomConnection_waitOom(JNIEnv* env, jobject) {
    const memevents::MemEvent oom_event = memevents::MemEvent::OOM_KILL;
    if (!memevent_listener.registerEvent(oom_event)) {
        memevent_listener.deregisterAllEvents();
        jniThrowRuntimeException(env, "listener failed to register to OOM events");
        return nullptr;
    }

    memevents::MemEvent event_received;
    do {
        event_received = memevent_listener.listen();
        if (event_received == memevents::MemEvent::ERROR) {
            memevent_listener.deregisterAllEvents();
            jniThrowRuntimeException(env, "listener received error event");
            return nullptr;
        }
    } while (event_received != oom_event);

    std::vector<memevents::OomKill> oom_events;
    if (!memevent_listener.getOomEvents(oom_events)) {
        memevent_listener.deregisterAllEvents();
        jniThrowRuntimeException(env, "Failed to get OOM events");
        return nullptr;
    }

    jobjectArray java_oom_array =
            env->NewObjectArray(oom_events.size(), sOomKillRecordInfo.clazz, nullptr);
    if (java_oom_array == NULL) {
        memevent_listener.deregisterAllEvents();
        jniThrowRuntimeException(env, "Failed to create OomKillRecord array");
        return nullptr;
    }

    for (int i = 0; i < oom_events.size(); i++) {
        const memevents::OomKill oom_event = oom_events[i];
        jstring process_name = env->NewStringUTF(oom_event.process_name);
        if (process_name == NULL) {
            memevent_listener.deregisterAllEvents();
            jniThrowRuntimeException(env, "Failed creating java string for process name");
        }
        jobject java_oom_kill = env->NewObject(sOomKillRecordInfo.clazz, sOomKillRecordInfo.ctor,
                                               oom_event.timestamp_ms, oom_event.pid, oom_event.uid,
                                               process_name, oom_event.oom_score_adj);
        if (java_oom_kill == NULL) {
            memevent_listener.deregisterAllEvents();
            jniThrowRuntimeException(env, "Failed to create OomKillRecord object");
            return java_oom_array;
        }
        env->SetObjectArrayElement(java_oom_array, i, java_oom_kill);
    }
    return java_oom_array;
}

static const JNINativeMethod sOomConnectionMethods[] = {
        /* name, signature, funcPtr */
        {"waitOom", "()[Landroid/os/OomKillRecord;",
         (void*)android_server_am_OomConnection_waitOom},
};

int register_android_server_am_OomConnection(JNIEnv* env) {
    sOomKillRecordInfo.clazz = FindClassOrDie(env, "android/os/OomKillRecord");
    sOomKillRecordInfo.clazz = MakeGlobalRefOrDie(env, sOomKillRecordInfo.clazz);

    sOomKillRecordInfo.ctor =
            GetMethodIDOrDie(env, sOomKillRecordInfo.clazz, "<init>", "(JIILjava/lang/String;S)V");

    return RegisterMethodsOrDie(env, "com/android/server/am/OomConnection", sOomConnectionMethods,
                                NELEM(sOomConnectionMethods));
}
} // namespace android