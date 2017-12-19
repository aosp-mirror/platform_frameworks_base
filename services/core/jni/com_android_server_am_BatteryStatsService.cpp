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

#include <errno.h>
#include <fcntl.h>
#include <inttypes.h>
#include <semaphore.h>
#include <stddef.h>
#include <stdio.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

#include <android/hardware/power/1.0/IPower.h>
#include <android/hardware/power/1.1/IPower.h>
#include <android_runtime/AndroidRuntime.h>
#include <jni.h>

#include <nativehelper/ScopedLocalRef.h>
#include <nativehelper/ScopedPrimitiveArray.h>

#include <log/log.h>
#include <utils/misc.h>
#include <utils/Log.h>
#include <suspend/autosuspend.h>

using android::hardware::Return;
using android::hardware::Void;
using android::hardware::power::V1_0::PowerStatePlatformSleepState;
using android::hardware::power::V1_0::PowerStateVoter;
using android::hardware::power::V1_0::Status;
using android::hardware::power::V1_1::PowerStateSubsystem;
using android::hardware::power::V1_1::PowerStateSubsystemSleepState;
using android::hardware::hidl_vec;
using IPowerV1_1 = android::hardware::power::V1_1::IPower;
using IPowerV1_0 = android::hardware::power::V1_0::IPower;

namespace android
{

#define LAST_RESUME_REASON "/sys/kernel/wakeup_reasons/last_resume_reason"
#define MAX_REASON_SIZE 512

static bool wakeup_init = false;
static sem_t wakeup_sem;
extern sp<IPowerV1_0> getPowerHalV1_0();
extern sp<IPowerV1_1> getPowerHalV1_1();
extern bool processPowerHalReturn(const Return<void> &ret, const char* functionName);

// Java methods used in getLowPowerStats
static jmethodID jgetAndUpdatePlatformState = NULL;
static jmethodID jgetSubsystem = NULL;
static jmethodID jputVoter = NULL;
static jmethodID jputState = NULL;

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
        autosuspend_set_wakeup_callback(&wakeup_callback);
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

static void getLowPowerStats(JNIEnv* env, jobject /* clazz */, jobject jrpmStats) {
    if (jrpmStats == NULL) {
        jniThrowException(env, "java/lang/NullPointerException",
                "The rpmstats jni input jobject jrpmStats is null.");
        return;
    }
    if (jgetAndUpdatePlatformState == NULL || jgetSubsystem == NULL
            || jputVoter == NULL || jputState == NULL) {
        ALOGE("A rpmstats jni jmethodID is null.");
        return;
    }

    sp<IPowerV1_0> powerHalV1_0 = getPowerHalV1_0();
    if (powerHalV1_0 == nullptr) {
        ALOGE("Power Hal not loaded");
        return;
    }

    Return<void> ret = powerHalV1_0->getPlatformLowPowerStats(
            [&env, &jrpmStats](hidl_vec<PowerStatePlatformSleepState> states, Status status) {

            if (status != Status::SUCCESS) return;

            for (size_t i = 0; i < states.size(); i++) {
                const PowerStatePlatformSleepState& state = states[i];

                jobject jplatformState = env->CallObjectMethod(jrpmStats,
                        jgetAndUpdatePlatformState,
                        env->NewStringUTF(state.name.c_str()),
                        state.residencyInMsecSinceBoot,
                        state.totalTransitions);
                if (jplatformState == NULL) {
                    ALOGE("The rpmstats jni jobject jplatformState is null.");
                    return;
                }

                for (size_t j = 0; j < state.voters.size(); j++) {
                    const PowerStateVoter& voter = state.voters[j];
                    env->CallVoidMethod(jplatformState, jputVoter,
                            env->NewStringUTF(voter.name.c_str()),
                            voter.totalTimeInMsecVotedForSinceBoot,
                            voter.totalNumberOfTimesVotedSinceBoot);
                }
            }
    });
    if (!processPowerHalReturn(ret, "getPlatformLowPowerStats")) {
        return;
    }

    // Trying to get IPower 1.1, this will succeed only for devices supporting 1.1
    sp<IPowerV1_1> powerHal_1_1 = getPowerHalV1_1();
    if (powerHal_1_1 == nullptr) {
        // This device does not support IPower@1.1, exiting gracefully
        return;
    }
    ret = powerHal_1_1->getSubsystemLowPowerStats(
            [&env, &jrpmStats](hidl_vec<PowerStateSubsystem> subsystems, Status status) {

        if (status != Status::SUCCESS) return;

        if (subsystems.size() > 0) {
            for (size_t i = 0; i < subsystems.size(); i++) {
                const PowerStateSubsystem &subsystem = subsystems[i];

                jobject jsubsystem = env->CallObjectMethod(jrpmStats, jgetSubsystem,
                        env->NewStringUTF(subsystem.name.c_str()));
                if (jsubsystem == NULL) {
                    ALOGE("The rpmstats jni jobject jsubsystem is null.");
                    return;
                }

                for (size_t j = 0; j < subsystem.states.size(); j++) {
                    const PowerStateSubsystemSleepState& state = subsystem.states[j];
                    env->CallVoidMethod(jsubsystem, jputState,
                            env->NewStringUTF(state.name.c_str()),
                            state.residencyInMsecSinceBoot,
                            state.totalTransitions);
                }
            }
        }
    });
    processPowerHalReturn(ret, "getSubsystemLowPowerStats");
}

static jint getPlatformLowPowerStats(JNIEnv* env, jobject /* clazz */, jobject outBuf) {
    char *output = (char*)env->GetDirectBufferAddress(outBuf);
    char *offset = output;
    int remaining = (int)env->GetDirectBufferCapacity(outBuf);
    int total_added = -1;

    if (outBuf == NULL) {
        jniThrowException(env, "java/lang/NullPointerException", "null argument");
        return -1;
    }

    {
        sp<IPowerV1_0> powerHalV1_0 = getPowerHalV1_0();
        if (powerHalV1_0 == nullptr) {
            ALOGE("Power Hal not loaded");
            return -1;
        }

        Return<void> ret = powerHalV1_0->getPlatformLowPowerStats(
            [&offset, &remaining, &total_added](hidl_vec<PowerStatePlatformSleepState> states,
                    Status status) {
                if (status != Status::SUCCESS)
                    return;
                for (size_t i = 0; i < states.size(); i++) {
                    int added;
                    const PowerStatePlatformSleepState& state = states[i];

                    added = snprintf(offset, remaining,
                        "state_%zu name=%s time=%" PRIu64 " count=%" PRIu64 " ",
                        i + 1, state.name.c_str(), state.residencyInMsecSinceBoot,
                        state.totalTransitions);
                    if (added < 0) {
                        break;
                    }
                    if (added > remaining) {
                        added = remaining;
                    }
                    offset += added;
                    remaining -= added;
                    total_added += added;

                    for (size_t j = 0; j < state.voters.size(); j++) {
                        const PowerStateVoter& voter = state.voters[j];
                        added = snprintf(offset, remaining,
                                "voter_%zu name=%s time=%" PRIu64 " count=%" PRIu64 " ",
                                j + 1, voter.name.c_str(),
                                voter.totalTimeInMsecVotedForSinceBoot,
                                voter.totalNumberOfTimesVotedSinceBoot);
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
                        ALOGE("PowerHal: buffer not enough");
                        break;
                    }
                }
            }
        );

        if (!processPowerHalReturn(ret, "getPlatformLowPowerStats")) {
            return -1;
        }
    }
    *offset = 0;
    total_added += 1;
    return total_added;
}

static jint getSubsystemLowPowerStats(JNIEnv* env, jobject /* clazz */, jobject outBuf) {
    char *output = (char*)env->GetDirectBufferAddress(outBuf);
    char *offset = output;
    int remaining = (int)env->GetDirectBufferCapacity(outBuf);
    int total_added = -1;

    // This is a IPower 1.1 API
    sp<IPowerV1_1> powerHal_1_1 = nullptr;

    if (outBuf == NULL) {
        jniThrowException(env, "java/lang/NullPointerException", "null argument");
        return -1;
    }

    {
        // Trying to get 1.1, this will succeed only for devices supporting 1.1
        powerHal_1_1 = getPowerHalV1_1();
        if (powerHal_1_1 == nullptr) {
            //This device does not support IPower@1.1, exiting gracefully
            return 0;
        }

        Return<void> ret = powerHal_1_1->getSubsystemLowPowerStats(
           [&offset, &remaining, &total_added](hidl_vec<PowerStateSubsystem> subsystems,
                Status status) {

            if (status != Status::SUCCESS)
                return;

            if (subsystems.size() > 0) {
                int added = snprintf(offset, remaining, "SubsystemPowerState ");
                offset += added;
                remaining -= added;
                total_added += added;

                for (size_t i = 0; i < subsystems.size(); i++) {
                    const PowerStateSubsystem &subsystem = subsystems[i];

                    added = snprintf(offset, remaining,
                                     "subsystem_%zu name=%s ", i + 1, subsystem.name.c_str());
                    if (added < 0) {
                        break;
                    }

                    if (added > remaining) {
                        added = remaining;
                    }

                    offset += added;
                    remaining -= added;
                    total_added += added;

                    for (size_t j = 0; j < subsystem.states.size(); j++) {
                        const PowerStateSubsystemSleepState& state = subsystem.states[j];
                        added = snprintf(offset, remaining,
                                         "state_%zu name=%s time=%" PRIu64 " count=%" PRIu64 " last entry=%" PRIu64 " ",
                                         j + 1, state.name.c_str(), state.residencyInMsecSinceBoot,
                                         state.totalTransitions, state.lastEntryTimestampMs);
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
                        ALOGE("PowerHal: buffer not enough");
                        break;
                    }
                }
            }
        }
        );

        if (!processPowerHalReturn(ret, "getSubsystemLowPowerStats")) {
            return -1;
        }
    }

    *offset = 0;
    total_added += 1;
    return total_added;
}

static const JNINativeMethod method_table[] = {
    { "nativeWaitWakeup", "(Ljava/nio/ByteBuffer;)I", (void*)nativeWaitWakeup },
    { "getLowPowerStats", "(Lcom/android/internal/os/RpmStats;)V", (void*)getLowPowerStats },
    { "getPlatformLowPowerStats", "(Ljava/nio/ByteBuffer;)I", (void*)getPlatformLowPowerStats },
    { "getSubsystemLowPowerStats", "(Ljava/nio/ByteBuffer;)I", (void*)getSubsystemLowPowerStats },
};

int register_android_server_BatteryStatsService(JNIEnv *env)
{
    // get java classes and methods
    jclass clsRpmStats = env->FindClass("com/android/internal/os/RpmStats");
    jclass clsPowerStatePlatformSleepState =
            env->FindClass("com/android/internal/os/RpmStats$PowerStatePlatformSleepState");
    jclass clsPowerStateSubsystem =
            env->FindClass("com/android/internal/os/RpmStats$PowerStateSubsystem");
    if (clsRpmStats == NULL || clsPowerStatePlatformSleepState == NULL
            || clsPowerStateSubsystem == NULL) {
        ALOGE("A rpmstats jni jclass is null.");
    } else {
        jgetAndUpdatePlatformState = env->GetMethodID(clsRpmStats, "getAndUpdatePlatformState",
                "(Ljava/lang/String;JI)Lcom/android/internal/os/RpmStats$PowerStatePlatformSleepState;");
        jgetSubsystem = env->GetMethodID(clsRpmStats, "getSubsystem",
                "(Ljava/lang/String;)Lcom/android/internal/os/RpmStats$PowerStateSubsystem;");
        jputVoter = env->GetMethodID(clsPowerStatePlatformSleepState, "putVoter",
                "(Ljava/lang/String;JI)V");
        jputState = env->GetMethodID(clsPowerStateSubsystem, "putState",
                "(Ljava/lang/String;JI)V");
    }

    return jniRegisterNativeMethods(env, "com/android/server/am/BatteryStatsService",
            method_table, NELEM(method_table));
}

};
