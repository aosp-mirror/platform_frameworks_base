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
#include <hardware/power.h>
#include <suspend/autosuspend.h>

#include <inttypes.h>
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
extern struct power_module* gPowerModule;

static void wakeup_callback(bool success)
{
    ALOGV("In wakeup_callback: %s", success ? "resumed from suspend" : "suspend aborted");
    int ret = sem_post(&wakeup_sem);
    if (ret < 0) {
        char buf[80];
        strerror_r(errno, buf, sizeof(buf));
        ALOGE("Error posting wakeup sem: %s\n", buf);
    }
}

static jint nativeWaitWakeup(JNIEnv *env, jobject clazz, jobject outBuf)
{
    if (outBuf == NULL) {
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
    }

    // Wait for wakeup.
    ALOGV("Waiting for wakeup...");
    int ret = sem_wait(&wakeup_sem);
    if (ret < 0) {
        char buf[80];
        strerror_r(errno, buf, sizeof(buf));
        ALOGE("Error waiting on semaphore: %s\n", buf);
        // Return 0 here to let it continue looping but not return results.
        return 0;
    }

    FILE *fp = fopen(LAST_RESUME_REASON, "r");
    if (fp == NULL) {
        ALOGE("Failed to open %s", LAST_RESUME_REASON);
        return -1;
    }

    char* mergedreason = (char*)env->GetDirectBufferAddress(outBuf);
    int remainreasonlen = (int)env->GetDirectBufferCapacity(outBuf);

    ALOGV("Reading wakeup reasons");
    char* mergedreasonpos = mergedreason;
    char reasonline[128];
    int i = 0;
    while (fgets(reasonline, sizeof(reasonline), fp) != NULL) {
        char* pos = reasonline;
        char* endPos;
        int len;
        // First field is the index or 'Abort'.
        int irq = (int)strtol(pos, &endPos, 10);
        if (pos != endPos) {
            // Write the irq number to the merged reason string.
            len = snprintf(mergedreasonpos, remainreasonlen, i == 0 ? "%d" : ":%d", irq);
        } else {
            // The first field is not an irq, it may be the word Abort.
            const size_t abortPrefixLen = strlen("Abort:");
            if (strncmp(pos, "Abort:", abortPrefixLen) != 0) {
                // Ooops.
                ALOGE("Bad reason line: %s", reasonline);
                continue;
            }

            // Write 'Abort' to the merged reason string.
            len = snprintf(mergedreasonpos, remainreasonlen, i == 0 ? "Abort" : ":Abort");
            endPos = pos + abortPrefixLen;
        }
        pos = endPos;

        if (len >= 0 && len < remainreasonlen) {
            mergedreasonpos += len;
            remainreasonlen -= len;
        }

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

        len = snprintf(mergedreasonpos, remainreasonlen, ":%s", pos);
        if (len >= 0 && len < remainreasonlen) {
            mergedreasonpos += len;
            remainreasonlen -= len;
        }
        i++;
    }

    ALOGV("Got %d reasons", i);
    if (i > 0) {
        *mergedreasonpos = 0;
    }

    if (fclose(fp) != 0) {
        ALOGE("Failed to close %s", LAST_RESUME_REASON);
        return -1;
    }
    return mergedreasonpos - mergedreason;
}

static jint getPlatformLowPowerStats(JNIEnv* env, jobject /* clazz */, jobject outBuf) {
    int num_modes = -1;
    char *output = (char*)env->GetDirectBufferAddress(outBuf), *offset = output;
    int remaining = (int)env->GetDirectBufferCapacity(outBuf);
    power_state_platform_sleep_state_t *list;
    size_t *voter_list;
    int total_added = -1;

    if (outBuf == NULL) {
        jniThrowException(env, "java/lang/NullPointerException", "null argument");
        goto error;
    }

    if (!gPowerModule) {
        ALOGE("%s: gPowerModule not loaded", POWER_HARDWARE_MODULE_ID);
        goto error;
    }

    if (! (gPowerModule->get_platform_low_power_stats && gPowerModule->get_number_of_platform_modes
       && gPowerModule->get_voter_list)) {
        ALOGE("%s: Missing API", POWER_HARDWARE_MODULE_ID);
        goto error;
    }

    if (gPowerModule->get_number_of_platform_modes) {
        num_modes = gPowerModule->get_number_of_platform_modes(gPowerModule);
    }

    if (num_modes < 1) {
        ALOGE("%s: Platform does not even have one low power mode", POWER_HARDWARE_MODULE_ID);
        goto error;
    }

    list = (power_state_platform_sleep_state_t *)calloc(num_modes,
        sizeof(power_state_platform_sleep_state_t));
    if (!list) {
        ALOGE("%s: power_state_platform_sleep_state_t allocation failed", POWER_HARDWARE_MODULE_ID);
        goto error;
    }

    voter_list = (size_t *)calloc(num_modes, sizeof(*voter_list));
    if (!voter_list) {
        ALOGE("%s: voter_list allocation failed", POWER_HARDWARE_MODULE_ID);
        goto err_free;
    }

    gPowerModule->get_voter_list(gPowerModule, voter_list);

    for (int i = 0; i < num_modes; i++) {
        list[i].voters = (power_state_voter_t *)calloc(voter_list[i],
                         sizeof(power_state_voter_t));
        if (!list[i].voters) {
            ALOGE("%s: voter_t allocation failed", POWER_HARDWARE_MODULE_ID);
            goto err_free;
        }
    }

    if (!gPowerModule->get_platform_low_power_stats(gPowerModule, list)) {
        for (int i = 0; i < num_modes; i++) {
            int added;

            added = snprintf(offset, remaining,
                    "state_%d name=%s time=%" PRIu64 " count=%" PRIu64 " ",
                    i + 1, list[i].name, list[i].residency_in_msec_since_boot,
                    list[i].total_transitions);
            if (added < 0) {
                break;
            }
            if (added > remaining) {
                added = remaining;
            }
            offset += added;
            remaining -= added;
            total_added += added;

            for (unsigned int j = 0; j < list[i].number_of_voters; j++) {
                added = snprintf(offset, remaining,
                        "voter_%d name=%s time=%" PRIu64 " count=%" PRIu64 " ",
                        j + 1, list[i].voters[j].name,
                        list[i].voters[j].total_time_in_msec_voted_for_since_boot,
                        list[i].voters[j].total_number_of_times_voted_since_boot);
                if (added < 0) {
                    break;
                }
                if (added > remaining) {
                    added = remaining;
                }
                offset += added;
                remaining -= added;
                total_added += added;
            }

            if (remaining <= 0) {
                /* rewrite NULL character*/
                offset--;
                total_added--;
                ALOGE("%s module: buffer not enough", POWER_HARDWARE_MODULE_ID);
                break;
            }
        }
    }
    *offset = 0;
    total_added += 1;

err_free:
    for (int i = 0; i < num_modes; i++) {
        free(list[i].voters);
    }
    free(list);
    free(voter_list);
error:
    return total_added;
}

static const JNINativeMethod method_table[] = {
    { "nativeWaitWakeup", "(Ljava/nio/ByteBuffer;)I", (void*)nativeWaitWakeup },
    { "getPlatformLowPowerStats", "(Ljava/nio/ByteBuffer;)I", (void*)getPlatformLowPowerStats },
};

int register_android_server_BatteryStatsService(JNIEnv *env)
{
    return jniRegisterNativeMethods(env, "com/android/server/am/BatteryStatsService",
            method_table, NELEM(method_table));
}

};
