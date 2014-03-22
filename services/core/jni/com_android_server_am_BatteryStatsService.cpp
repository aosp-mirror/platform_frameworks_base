/*
 * Copyright (C) 2014 The Android Open Source Project
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

#define LOG_TAG "BatteryStatsService"
//#define LOG_NDEBUG 0

#include <android_runtime/AndroidRuntime.h>
#include <jni.h>

#include <ScopedLocalRef.h>
#include <ScopedPrimitiveArray.h>

#include <cutils/log.h>
#include <utils/misc.h>
#include <utils/Log.h>
#include <hardware/hardware.h>
#include <suspend/autosuspend.h>

#include <stdio.h>
#include <errno.h>
#include <fcntl.h>
#include <semaphore.h>
#include <stddef.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

namespace android
{

#define LAST_RESUME_REASON "/sys/kernel/wakeup_reasons/last_resume_reason"
#define MAX_REASON_SIZE 512

static bool wakeup_init = false;
static sem_t wakeup_sem;

static void wakeup_callback(void)
{
    ALOGV("In wakeup_callback");
    int ret = sem_post(&wakeup_sem);
    if (ret < 0) {
        char buf[80];
        strerror_r(errno, buf, sizeof(buf));
        ALOGE("Error posting wakeup sem: %s\n", buf);
    }
}

static jint nativeWaitWakeup(JNIEnv *env, jobject clazz, jintArray outIrqs,
        jobjectArray outReasons)
{
    bool first_time = false;

    if (outIrqs == NULL || outReasons == NULL) {
        jniThrowException(env, "java/lang/NullPointerException", "null argument");
        return -1;
    }

    // Register our wakeup callback if not yet done.
    if (!wakeup_init) {
        wakeup_init = true;
        ALOGV("Creating semaphore...");
        int ret = sem_init(&wakeup_sem, 0, 0);
        if (ret < 0) {
            char buf[80];
            strerror_r(errno, buf, sizeof(buf));
            ALOGE("Error creating semaphore: %s\n", buf);
            jniThrowException(env, "java/lang/IllegalStateException", buf);
            return -1;
        }
        ALOGV("Registering callback...");
        set_wakeup_callback(&wakeup_callback);
        // First time through, we will just drain the current wakeup reasons.
        first_time = true;
    } else {
        // On following calls, we need to wait for wakeup.
        ALOGV("Waiting for wakeup...");
        int ret = sem_wait(&wakeup_sem);
        if (ret < 0) {
            char buf[80];
            strerror_r(errno, buf, sizeof(buf));
            ALOGE("Error waiting on semaphore: %s\n", buf);
            // Return 0 here to let it continue looping but not return results.
            return 0;
        }
    }

    FILE *fp = fopen(LAST_RESUME_REASON, "r");
    if (fp == NULL) {
        ALOGE("Failed to open %s", LAST_RESUME_REASON);
        return -1;
    }

    int numOut = env->GetArrayLength(outIrqs);
    ScopedIntArrayRW irqs(env, outIrqs);

    ALOGV("Reading up to %d wakeup reasons", numOut);

    char mergedreason[MAX_REASON_SIZE];
    char* mergedreasonpos = mergedreason;
    int remainreasonlen = MAX_REASON_SIZE;
    int firstirq = 0;
    char reasonline[128];
    int i = 0;
    while (fgets(reasonline, sizeof(reasonline), fp) != NULL && i < numOut) {
        char* pos = reasonline;
        char* endPos;
        // First field is the index.
        int irq = (int)strtol(pos, &endPos, 10);
        if (pos == endPos) {
            // Ooops.
            ALOGE("Bad reason line: %s", reasonline);
            continue;
        }
        pos = endPos;
        // Skip whitespace; rest of the buffer is the reason string.
        while (*pos == ' ') {
            pos++;
        }
        // Chop newline at end.
        char* endpos = pos;
        while (*endpos != 0) {
            if (*endpos == '\n') {
                *endpos = 0;
                break;
            }
            endpos++;
        }
        // For now we are not separating out the first irq.
        // This is because in practice there are always multiple
        // lines of wakeup reasons, so it is better to just treat
        // them all together as a single string.
        if (false && i == 0) {
            firstirq = irq;
        } else {
            int len = snprintf(mergedreasonpos, remainreasonlen,
                    i == 0 ? "%d" : ":%d", irq);
            if (len >= 0 && len < remainreasonlen) {
                mergedreasonpos += len;
                remainreasonlen -= len;
            }
        }
        int len = snprintf(mergedreasonpos, remainreasonlen, ":%s", pos);
        if (len >= 0 && len < remainreasonlen) {
            mergedreasonpos += len;
            remainreasonlen -= len;
        }
        // For now it is better to combine all of these in to one entry in the
        // battery history.  In the future, it might be nice to figure out a way
        // to efficiently store multiple lines as a single entry in the history.
        //irqs[i] = irq;
        //ScopedLocalRef<jstring> reasonString(env, env->NewStringUTF(pos));
        //env->SetObjectArrayElement(outReasons, i, reasonString.get());
        //ALOGV("Wakeup reason #%d: irw %d reason %s", i, irq, pos);
        i++;
    }

    ALOGV("Got %d reasons", i);
    if (first_time) {
        i = 0;
    }
    if (i > 0) {
        irqs[0] = firstirq;
        *mergedreasonpos = 0;
        ScopedLocalRef<jstring> reasonString(env, env->NewStringUTF(mergedreason));
        env->SetObjectArrayElement(outReasons, 0, reasonString.get());
        i = 1;
    }

    if (fclose(fp) != 0) {
        ALOGE("Failed to close %s", LAST_RESUME_REASON);
        return -1;
    }

    return first_time ? 0 : i;
}

static JNINativeMethod method_table[] = {
    { "nativeWaitWakeup", "([I[Ljava/lang/String;)I", (void*)nativeWaitWakeup },
};

int register_android_server_BatteryStatsService(JNIEnv *env)
{
    return jniRegisterNativeMethods(env, "com/android/server/am/BatteryStatsService",
            method_table, NELEM(method_table));
}

};
