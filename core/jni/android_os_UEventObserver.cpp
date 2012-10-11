/*
 * Copyright (C) 2008 The Android Open Source Project
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

#define LOG_TAG "UEventObserver"
//#define LOG_NDEBUG 0

#include "utils/Log.h"

#include "hardware_legacy/uevent.h"
#include "jni.h"
#include "JNIHelp.h"
#include "android_runtime/AndroidRuntime.h"

#include <utils/Mutex.h>
#include <utils/Vector.h>
#include <utils/String8.h>
#include <ScopedUtfChars.h>

namespace android {

static Mutex gMatchesMutex;
static Vector<String8> gMatches;

static void nativeSetup(JNIEnv *env, jclass clazz) {
    if (!uevent_init()) {
        jniThrowException(env, "java/lang/RuntimeException",
                "Unable to open socket for UEventObserver");
    }
}

static bool isMatch(const char* buffer, size_t length) {
    AutoMutex _l(gMatchesMutex);

    for (size_t i = 0; i < gMatches.size(); i++) {
        const String8& match = gMatches.itemAt(i);

        // Consider all zero-delimited fields of the buffer.
        const char* field = buffer;
        const char* end = buffer + length + 1;
        do {
            if (strstr(field, match.string())) {
                ALOGV("Matched uevent message with pattern: %s", match.string());
                return true;
            }
            field += strlen(field) + 1;
        } while (field != end);
    }
    return false;
}

static jstring nativeWaitForNextEvent(JNIEnv *env, jclass clazz) {
    char buffer[1024];

    for (;;) {
        int length = uevent_next_event(buffer, sizeof(buffer) - 1);
        if (length <= 0) {
            return NULL;
        }
        buffer[length] = '\0';

        ALOGV("Received uevent message: %s", buffer);

        if (isMatch(buffer, length)) {
            // Assume the message is ASCII.
            jchar message[length];
            for (int i = 0; i < length; i++) {
                message[i] = buffer[i];
            }
            return env->NewString(message, length);
        }
    }
}

static void nativeAddMatch(JNIEnv* env, jclass clazz, jstring matchStr) {
    ScopedUtfChars match(env, matchStr);

    AutoMutex _l(gMatchesMutex);
    gMatches.add(String8(match.c_str()));
}

static void nativeRemoveMatch(JNIEnv* env, jclass clazz, jstring matchStr) {
    ScopedUtfChars match(env, matchStr);

    AutoMutex _l(gMatchesMutex);
    for (size_t i = 0; i < gMatches.size(); i++) {
        if (gMatches.itemAt(i) == match.c_str()) {
            gMatches.removeAt(i);
            break; // only remove first occurrence
        }
    }
}

static JNINativeMethod gMethods[] = {
    { "nativeSetup", "()V",
            (void *)nativeSetup },
    { "nativeWaitForNextEvent", "()Ljava/lang/String;",
            (void *)nativeWaitForNextEvent },
    { "nativeAddMatch", "(Ljava/lang/String;)V",
            (void *)nativeAddMatch },
    { "nativeRemoveMatch", "(Ljava/lang/String;)V",
            (void *)nativeRemoveMatch },
};


int register_android_os_UEventObserver(JNIEnv *env)
{
    jclass clazz;

    clazz = env->FindClass("android/os/UEventObserver");
    if (clazz == NULL) {
        ALOGE("Can't find android/os/UEventObserver");
        return -1;
    }

    return AndroidRuntime::registerNativeMethods(env,
            "android/os/UEventObserver", gMethods, NELEM(gMethods));
}

}   // namespace android
