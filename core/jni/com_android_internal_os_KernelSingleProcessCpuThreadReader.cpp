/*
 * Copyright (C) 2020 The Android Open Source Project
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

#include "core_jni_helpers.h"

#include <cputimeinstate.h>
#include <dirent.h>

#include <android-base/file.h>
#include <android-base/parseint.h>
#include <android-base/stringprintf.h>
#include <android-base/strings.h>
#include <android_runtime/Log.h>

#include <nativehelper/ScopedPrimitiveArray.h>
#include <nativehelper/ScopedUtfChars.h>

namespace android {

// Number of milliseconds in a jiffy - the unit of time measurement for processes and threads
static const uint32_t gJiffyMillis = (uint32_t)(1000 / sysconf(_SC_CLK_TCK));

// Given a PID, returns a vector of all TIDs for the process' tasks. Thread IDs are
// file names in the /proc/<pid>/task directory.
static bool getThreadIds(const std::string &procPath, const pid_t pid,
                         std::vector<pid_t> &outThreadIds) {
    std::string taskPath = android::base::StringPrintf("%s/%u/task", procPath.c_str(), pid);

    struct dirent **dirlist;
    int threadCount = scandir(taskPath.c_str(), &dirlist, NULL, NULL);
    if (threadCount == -1) {
        ALOGE("Cannot read directory %s", taskPath.c_str());
        return false;
    }

    outThreadIds.reserve(threadCount);

    for (int i = 0; i < threadCount; i++) {
        pid_t tid;
        if (android::base::ParseInt<pid_t>(dirlist[i]->d_name, &tid)) {
            outThreadIds.push_back(tid);
        }
        free(dirlist[i]);
    }
    free(dirlist);

    return true;
}

// Reads contents of a time_in_state file and returns times as a vector of times per frequency
// A time_in_state file contains pairs of frequency - time (in jiffies):
//
//    cpu0
//    300000 30
//    403200 0
//    cpu4
//    710400 10
//    825600 20
//    940800 30
//
static bool getThreadTimeInState(const std::string &procPath, const pid_t pid, const pid_t tid,
                                 const size_t frequencyCount,
                                 std::vector<uint64_t> &outThreadTimeInState) {
    std::string timeInStateFilePath =
            android::base::StringPrintf("%s/%u/task/%u/time_in_state", procPath.c_str(), pid, tid);
    std::string data;

    if (!android::base::ReadFileToString(timeInStateFilePath, &data)) {
        ALOGE("Cannot read file: %s", timeInStateFilePath.c_str());
        return false;
    }

    auto lines = android::base::Split(data, "\n");
    size_t index = 0;
    for (const auto &line : lines) {
        if (line.empty()) {
            continue;
        }

        auto numbers = android::base::Split(line, " ");
        if (numbers.size() != 2) {
            continue;
        }
        uint64_t timeInState;
        if (!android::base::ParseUint<uint64_t>(numbers[1], &timeInState)) {
            ALOGE("Invalid time_in_state file format: %s", timeInStateFilePath.c_str());
            return false;
        }
        if (index < frequencyCount) {
            outThreadTimeInState[index] = timeInState;
        }
        index++;
    }

    if (index != frequencyCount) {
        ALOGE("Incorrect number of frequencies %u in %s. Expected %u",
              (uint32_t)outThreadTimeInState.size(), timeInStateFilePath.c_str(),
              (uint32_t)frequencyCount);
        return false;
    }

    return true;
}

static int pidCompare(const void *a, const void *b) {
    return (*(pid_t *)a - *(pid_t *)b);
}

static inline bool isSelectedThread(const pid_t tid, const pid_t *selectedThreadIds,
                                    const size_t selectedThreadCount) {
    return bsearch(&tid, selectedThreadIds, selectedThreadCount, sizeof(pid_t), pidCompare) != NULL;
}

// Reads all /proc/<pid>/task/*/time_in_state files and aggregates per-frequency
// time in state data for all threads.  Also, separately aggregates time in state for
// selected threads whose TIDs are passes as selectedThreadIds.
static void aggregateThreadCpuTimes(const std::string &procPath, const pid_t pid,
                                    const std::vector<pid_t> &threadIds,
                                    const size_t frequencyCount, const pid_t *selectedThreadIds,
                                    const size_t selectedThreadCount,
                                    uint64_t *threadCpuTimesMillis,
                                    uint64_t *selectedThreadCpuTimesMillis) {
    for (size_t j = 0; j < frequencyCount; j++) {
        threadCpuTimesMillis[j] = 0;
        selectedThreadCpuTimesMillis[j] = 0;
    }

    for (size_t i = 0; i < threadIds.size(); i++) {
        pid_t tid = threadIds[i];
        std::vector<uint64_t> timeInState(frequencyCount);
        if (!getThreadTimeInState(procPath, pid, tid, frequencyCount, timeInState)) {
            continue;
        }

        bool selectedThread = isSelectedThread(tid, selectedThreadIds, selectedThreadCount);
        for (size_t j = 0; j < frequencyCount; j++) {
            threadCpuTimesMillis[j] += timeInState[j];
            if (selectedThread) {
                selectedThreadCpuTimesMillis[j] += timeInState[j];
            }
        }
    }
    for (size_t i = 0; i < frequencyCount; i++) {
        threadCpuTimesMillis[i] *= gJiffyMillis;
        selectedThreadCpuTimesMillis[i] *= gJiffyMillis;
    }
}

// Reads process utime and stime from the /proc/<pid>/stat file.
// Format of this file is described in https://man7.org/linux/man-pages/man5/proc.5.html.
static bool getProcessCpuTime(const std::string &procPath, const pid_t pid,
                              uint64_t &outTimeMillis) {
    std::string statFilePath = android::base::StringPrintf("%s/%u/stat", procPath.c_str(), pid);
    std::string data;
    if (!android::base::ReadFileToString(statFilePath, &data)) {
        return false;
    }

    auto fields = android::base::Split(data, " ");
    uint64_t utime, stime;

    // Field 14 (counting from 1) is utime - process time in user space, in jiffies
    // Field 15 (counting from 1) is stime - process time in system space, in jiffies
    if (fields.size() < 15 || !android::base::ParseUint(fields[13], &utime) ||
        !android::base::ParseUint(fields[14], &stime)) {
        ALOGE("Invalid file format %s", statFilePath.c_str());
        return false;
    }

    outTimeMillis = (utime + stime) * gJiffyMillis;
    return true;
}

// Estimates per cluster per frequency CPU time for the entire process
// by distributing the total process CPU time proportionately to how much
// CPU time its threads took on those clusters/frequencies.  This algorithm
// works more accurately when when we have equally distributed concurrency.
// TODO(b/169279846): obtain actual process CPU times from the kernel
static void estimateProcessTimeInState(const uint64_t processCpuTimeMillis,
                                       const uint64_t *threadCpuTimesMillis,
                                       const size_t frequencyCount,
                                       uint64_t *processCpuTimesMillis) {
    uint64_t totalCpuTimeAllThreads = 0;
    for (size_t i = 0; i < frequencyCount; i++) {
        totalCpuTimeAllThreads += threadCpuTimesMillis[i];
    }

    if (totalCpuTimeAllThreads != 0) {
        for (size_t i = 0; i < frequencyCount; i++) {
            processCpuTimesMillis[i] =
                    processCpuTimeMillis * threadCpuTimesMillis[i] / totalCpuTimeAllThreads;
        }
    } else {
        for (size_t i = 0; i < frequencyCount; i++) {
            processCpuTimesMillis[i] = 0;
        }
    }
}

static jboolean readProcessCpuUsage(JNIEnv *env, jclass, jstring procPath, jint pid,
                                    jintArray selectedThreadIdArray,
                                    jlongArray processCpuTimesMillisArray,
                                    jlongArray threadCpuTimesMillisArray,
                                    jlongArray selectedThreadCpuTimesMillisArray) {
    ScopedUtfChars procPathChars(env, procPath);
    ScopedIntArrayRO selectedThreadIds(env, selectedThreadIdArray);
    ScopedLongArrayRW processCpuTimesMillis(env, processCpuTimesMillisArray);
    ScopedLongArrayRW threadCpuTimesMillis(env, threadCpuTimesMillisArray);
    ScopedLongArrayRW selectedThreadCpuTimesMillis(env, selectedThreadCpuTimesMillisArray);

    std::string procPathStr(procPathChars.c_str());

    // Get all thread IDs for the process.
    std::vector<pid_t> threadIds;
    if (!getThreadIds(procPathStr, pid, threadIds)) {
        ALOGE("Could not obtain thread IDs from: %s", procPathStr.c_str());
        return false;
    }

    size_t frequencyCount = processCpuTimesMillis.size();

    if (threadCpuTimesMillis.size() != frequencyCount) {
        ALOGE("Invalid array length: threadCpuTimesMillis");
        return false;
    }
    if (selectedThreadCpuTimesMillis.size() != frequencyCount) {
        ALOGE("Invalid array length: selectedThreadCpuTimesMillisArray");
        return false;
    }

    aggregateThreadCpuTimes(procPathStr, pid, threadIds, frequencyCount, selectedThreadIds.get(),
                            selectedThreadIds.size(),
                            reinterpret_cast<uint64_t *>(threadCpuTimesMillis.get()),
                            reinterpret_cast<uint64_t *>(selectedThreadCpuTimesMillis.get()));

    uint64_t processCpuTime;
    bool ret = getProcessCpuTime(procPathStr, pid, processCpuTime);
    if (ret) {
        estimateProcessTimeInState(processCpuTime,
                                   reinterpret_cast<uint64_t *>(threadCpuTimesMillis.get()),
                                   frequencyCount,
                                   reinterpret_cast<uint64_t *>(processCpuTimesMillis.get()));
    }
    return ret;
}

static const JNINativeMethod g_single_methods[] = {
        {"readProcessCpuUsage", "(Ljava/lang/String;I[I[J[J[J)Z", (void *)readProcessCpuUsage},
};

int register_com_android_internal_os_KernelSingleProcessCpuThreadReader(JNIEnv *env) {
    return RegisterMethodsOrDie(env, "com/android/internal/os/KernelSingleProcessCpuThreadReader",
                                g_single_methods, NELEM(g_single_methods));
}

} // namespace android
