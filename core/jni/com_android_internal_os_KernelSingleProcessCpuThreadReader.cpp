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

namespace android {

static constexpr uint16_t DEFAULT_THREAD_AGGREGATION_KEY = 0;
static constexpr uint16_t SELECTED_THREAD_AGGREGATION_KEY = 1;

static constexpr uint64_t NSEC_PER_MSEC = 1000000;

// Number of milliseconds in a jiffy - the unit of time measurement for processes and threads
static const uint32_t gJiffyMillis = (uint32_t)(1000 / sysconf(_SC_CLK_TCK));

// Abstract class for readers of CPU time-in-state. There are two implementations of
// this class: BpfCpuTimeInStateReader and MockCpuTimeInStateReader.  The former is used
// by the production code. The latter is used by unit tests to provide mock
// CPU time-in-state data via a Java implementation.
class ICpuTimeInStateReader {
public:
    virtual ~ICpuTimeInStateReader() {}

    // Returns the overall number of cluser-frequency combinations
    virtual size_t getCpuFrequencyCount();

    // Marks the CPU time-in-state tracking for threads of the specified TGID
    virtual bool startTrackingProcessCpuTimes(pid_t) = 0;

    // Marks the thread specified by its PID for CPU time-in-state tracking.
    virtual bool startAggregatingTaskCpuTimes(pid_t, uint16_t) = 0;

    // Retrieves the accumulated time-in-state data, which is organized as a map
    // from aggregation keys to vectors of vectors using the format:
    // { aggKey0 -> [[t0_0_0, t0_0_1, ...], [t0_1_0, t0_1_1, ...], ...],
    //   aggKey1 -> [[t1_0_0, t1_0_1, ...], [t1_1_0, t1_1_1, ...], ...], ... }
    // where ti_j_k is the ns tid i spent running on the jth cluster at the cluster's kth lowest
    // freq.
    virtual std::optional<std::unordered_map<uint16_t, std::vector<std::vector<uint64_t>>>>
    getAggregatedTaskCpuFreqTimes(pid_t, const std::vector<uint16_t> &);
};

// ICpuTimeInStateReader that uses eBPF to provide a map of aggregated CPU time-in-state values.
// See cputtimeinstate.h/.cpp
class BpfCpuTimeInStateReader : public ICpuTimeInStateReader {
public:
    size_t getCpuFrequencyCount() {
        std::optional<std::vector<std::vector<uint32_t>>> cpuFreqs = android::bpf::getCpuFreqs();
        if (!cpuFreqs) {
            ALOGE("Cannot obtain CPU frequency count");
            return 0;
        }

        size_t freqCount = 0;
        for (auto cluster : *cpuFreqs) {
            freqCount += cluster.size();
        }

        return freqCount;
    }

    bool startTrackingProcessCpuTimes(pid_t tgid) {
        return android::bpf::startTrackingProcessCpuTimes(tgid);
    }

    bool startAggregatingTaskCpuTimes(pid_t pid, uint16_t aggregationKey) {
        return android::bpf::startAggregatingTaskCpuTimes(pid, aggregationKey);
    }

    std::optional<std::unordered_map<uint16_t, std::vector<std::vector<uint64_t>>>>
    getAggregatedTaskCpuFreqTimes(pid_t pid, const std::vector<uint16_t> &aggregationKeys) {
        return android::bpf::getAggregatedTaskCpuFreqTimes(pid, aggregationKeys);
    }
};

// ICpuTimeInStateReader that uses JNI to provide a map of aggregated CPU time-in-state
// values.
// This version of CpuTimeInStateReader is used exclusively for providing mock data in tests.
class MockCpuTimeInStateReader : public ICpuTimeInStateReader {
private:
    JNIEnv *mEnv;
    jobject mCpuTimeInStateReader;

public:
    MockCpuTimeInStateReader(JNIEnv *env, jobject cpuTimeInStateReader)
          : mEnv(env), mCpuTimeInStateReader(cpuTimeInStateReader) {}

    size_t getCpuFrequencyCount();

    bool startTrackingProcessCpuTimes(pid_t tgid);

    bool startAggregatingTaskCpuTimes(pid_t pid, uint16_t aggregationKey);

    std::optional<std::unordered_map<uint16_t, std::vector<std::vector<uint64_t>>>>
    getAggregatedTaskCpuFreqTimes(pid_t tgid, const std::vector<uint16_t> &aggregationKeys);
};

static ICpuTimeInStateReader *getCpuTimeInStateReader(JNIEnv *env,
                                                      jobject cpuTimeInStateReaderObject) {
    if (cpuTimeInStateReaderObject) {
        return new MockCpuTimeInStateReader(env, cpuTimeInStateReaderObject);
    } else {
        return new BpfCpuTimeInStateReader();
    }
}

static jint getCpuFrequencyCount(JNIEnv *env, jclass, jobject cpuTimeInStateReaderObject) {
    std::unique_ptr<ICpuTimeInStateReader> cpuTimeInStateReader(
            getCpuTimeInStateReader(env, cpuTimeInStateReaderObject));
    return cpuTimeInStateReader->getCpuFrequencyCount();
}

static jboolean startTrackingProcessCpuTimes(JNIEnv *env, jclass, jint tgid,
                                             jobject cpuTimeInStateReaderObject) {
    std::unique_ptr<ICpuTimeInStateReader> cpuTimeInStateReader(
            getCpuTimeInStateReader(env, cpuTimeInStateReaderObject));
    return cpuTimeInStateReader->startTrackingProcessCpuTimes(tgid);
}

static jboolean startAggregatingThreadCpuTimes(JNIEnv *env, jclass, jintArray selectedThreadIdArray,
                                               jobject cpuTimeInStateReaderObject) {
    ScopedIntArrayRO selectedThreadIds(env, selectedThreadIdArray);
    std::unique_ptr<ICpuTimeInStateReader> cpuTimeInStateReader(
            getCpuTimeInStateReader(env, cpuTimeInStateReaderObject));

    for (size_t i = 0; i < selectedThreadIds.size(); i++) {
        if (!cpuTimeInStateReader->startAggregatingTaskCpuTimes(selectedThreadIds[i],
                                                                SELECTED_THREAD_AGGREGATION_KEY)) {
            return false;
        }
    }
    return true;
}

// Converts time-in-state data from a vector of vectors to a flat array.
// Also converts from nanoseconds to milliseconds.
static bool flattenTimeInStateData(ScopedLongArrayRW &cpuTimesMillis,
                                   const std::vector<std::vector<uint64_t>> &data) {
    size_t frequencyCount = cpuTimesMillis.size();
    size_t index = 0;
    for (const auto &cluster : data) {
        for (const uint64_t &timeNanos : cluster) {
            if (index < frequencyCount) {
                cpuTimesMillis[index] = timeNanos / NSEC_PER_MSEC;
            }
            index++;
        }
    }
    if (index != frequencyCount) {
        ALOGE("CPU time-in-state reader returned data for %zu frequencies; expected: %zu", index,
              frequencyCount);
        return false;
    }

    return true;
}

// Reads all CPU time-in-state data accumulated by BPF and aggregates per-frequency
// time in state data for all threads.  Also, separately aggregates time in state for
// selected threads whose TIDs are passes as selectedThreadIds.
static jboolean readProcessCpuUsage(JNIEnv *env, jclass, jint pid,
                                    jlongArray threadCpuTimesMillisArray,
                                    jlongArray selectedThreadCpuTimesMillisArray,
                                    jobject cpuTimeInStateReaderObject) {
    ScopedLongArrayRW threadCpuTimesMillis(env, threadCpuTimesMillisArray);
    ScopedLongArrayRW selectedThreadCpuTimesMillis(env, selectedThreadCpuTimesMillisArray);
    std::unique_ptr<ICpuTimeInStateReader> cpuTimeInStateReader(
            getCpuTimeInStateReader(env, cpuTimeInStateReaderObject));

    const size_t frequencyCount = cpuTimeInStateReader->getCpuFrequencyCount();

    if (threadCpuTimesMillis.size() != frequencyCount) {
        ALOGE("Invalid threadCpuTimesMillis array length: %zu frequencies; expected: %zu",
              threadCpuTimesMillis.size(), frequencyCount);
        return false;
    }

    if (selectedThreadCpuTimesMillis.size() != frequencyCount) {
        ALOGE("Invalid selectedThreadCpuTimesMillis array length: %zu frequencies; expected: %zu",
              selectedThreadCpuTimesMillis.size(), frequencyCount);
        return false;
    }

    for (size_t i = 0; i < frequencyCount; i++) {
        threadCpuTimesMillis[i] = 0;
        selectedThreadCpuTimesMillis[i] = 0;
    }

    std::optional<std::unordered_map<uint16_t, std::vector<std::vector<uint64_t>>>> data =
            cpuTimeInStateReader->getAggregatedTaskCpuFreqTimes(pid,
                                                                {DEFAULT_THREAD_AGGREGATION_KEY,
                                                                 SELECTED_THREAD_AGGREGATION_KEY});
    if (!data) {
        ALOGE("Cannot read thread CPU times for PID %d", pid);
        return false;
    }

    if (!flattenTimeInStateData(threadCpuTimesMillis, (*data)[DEFAULT_THREAD_AGGREGATION_KEY])) {
        return false;
    }

    if (!flattenTimeInStateData(selectedThreadCpuTimesMillis,
                                (*data)[SELECTED_THREAD_AGGREGATION_KEY])) {
        return false;
    }

    // threadCpuTimesMillis returns CPU times for _all_ threads, including the selected ones
    for (size_t i = 0; i < frequencyCount; i++) {
        threadCpuTimesMillis[i] += selectedThreadCpuTimesMillis[i];
    }

    return true;
}

static const JNINativeMethod g_single_methods[] = {
        {"getCpuFrequencyCount",
         "(Lcom/android/internal/os/KernelSingleProcessCpuThreadReader$CpuTimeInStateReader;)I",
         (void *)getCpuFrequencyCount},
        {"startTrackingProcessCpuTimes",
         "(ILcom/android/internal/os/KernelSingleProcessCpuThreadReader$CpuTimeInStateReader;)Z",
         (void *)startTrackingProcessCpuTimes},
        {"startAggregatingThreadCpuTimes",
         "([ILcom/android/internal/os/KernelSingleProcessCpuThreadReader$CpuTimeInStateReader;)Z",
         (void *)startAggregatingThreadCpuTimes},
        {"readProcessCpuUsage",
         "(I[J[J"
         "Lcom/android/internal/os/KernelSingleProcessCpuThreadReader$CpuTimeInStateReader;)Z",
         (void *)readProcessCpuUsage},
};

int register_com_android_internal_os_KernelSingleProcessCpuThreadReader(JNIEnv *env) {
    return RegisterMethodsOrDie(env, "com/android/internal/os/KernelSingleProcessCpuThreadReader",
                                g_single_methods, NELEM(g_single_methods));
}

size_t MockCpuTimeInStateReader::getCpuFrequencyCount() {
    jclass cls = mEnv->GetObjectClass(mCpuTimeInStateReader);
    jmethodID mid = mEnv->GetMethodID(cls, "getCpuFrequencyCount", "()I");
    if (mid == 0) {
        ALOGE("Couldn't find the method getCpuFrequencyCount");
        return false;
    }
    return (size_t)mEnv->CallIntMethod(mCpuTimeInStateReader, mid);
}

bool MockCpuTimeInStateReader::startTrackingProcessCpuTimes(pid_t tgid) {
    jclass cls = mEnv->GetObjectClass(mCpuTimeInStateReader);
    jmethodID mid = mEnv->GetMethodID(cls, "startTrackingProcessCpuTimes", "(I)Z");
    if (mid == 0) {
        ALOGE("Couldn't find the method startTrackingProcessCpuTimes");
        return false;
    }
    return mEnv->CallBooleanMethod(mCpuTimeInStateReader, mid, tgid);
}

bool MockCpuTimeInStateReader::startAggregatingTaskCpuTimes(pid_t pid, uint16_t aggregationKey) {
    jclass cls = mEnv->GetObjectClass(mCpuTimeInStateReader);
    jmethodID mid = mEnv->GetMethodID(cls, "startAggregatingTaskCpuTimes", "(II)Z");
    if (mid == 0) {
        ALOGE("Couldn't find the method startAggregatingTaskCpuTimes");
        return false;
    }
    return mEnv->CallBooleanMethod(mCpuTimeInStateReader, mid, pid, aggregationKey);
}

std::optional<std::unordered_map<uint16_t, std::vector<std::vector<uint64_t>>>>
MockCpuTimeInStateReader::getAggregatedTaskCpuFreqTimes(
        pid_t pid, const std::vector<uint16_t> &aggregationKeys) {
    jclass cls = mEnv->GetObjectClass(mCpuTimeInStateReader);
    jmethodID mid =
            mEnv->GetMethodID(cls, "getAggregatedTaskCpuFreqTimes", "(I)[Ljava/lang/String;");
    if (mid == 0) {
        ALOGE("Couldn't find the method getAggregatedTaskCpuFreqTimes");
        return {};
    }

    std::unordered_map<uint16_t, std::vector<std::vector<uint64_t>>> map;

    jobjectArray stringArray =
            (jobjectArray)mEnv->CallObjectMethod(mCpuTimeInStateReader, mid, pid);
    int size = mEnv->GetArrayLength(stringArray);
    for (int i = 0; i < size; i++) {
        ScopedUtfChars line(mEnv, (jstring)mEnv->GetObjectArrayElement(stringArray, i));
        uint16_t aggregationKey;
        std::vector<std::vector<uint64_t>> times;

        // Each string is formatted like this: "aggKey:t0_0 t0_1...:t1_0 t1_1..."
        auto fields = android::base::Split(line.c_str(), ":");
        android::base::ParseUint(fields[0], &aggregationKey);

        for (size_t j = 1; j < fields.size(); j++) {
            auto numbers = android::base::Split(fields[j], " ");

            std::vector<uint64_t> chunk;
            for (size_t k = 0; k < numbers.size(); k++) {
                uint64_t time;
                android::base::ParseUint(numbers[k], &time);
                chunk.emplace_back(time);
            }
            times.emplace_back(chunk);
        }

        map.emplace(aggregationKey, times);
    }

    return map;
}

} // namespace android
