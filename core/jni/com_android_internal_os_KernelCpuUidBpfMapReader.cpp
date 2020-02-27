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

#include <sys/sysinfo.h>

#include <android-base/stringprintf.h>
#include <cputimeinstate.h>

namespace android {

static constexpr uint64_t NSEC_PER_MSEC = 1000000;

static struct {
    jclass clazz;
    jmethodID put;
    jmethodID get;
} gSparseArrayClassInfo;

static jfieldID gmData;

static jlongArray getUidArray(JNIEnv *env, jobject sparseAr, uint32_t uid, jsize sz) {
    jlongArray ar = (jlongArray)env->CallObjectMethod(sparseAr, gSparseArrayClassInfo.get, uid);
    if (!ar) {
        ar = env->NewLongArray(sz);
        if (ar == NULL) return ar;
        env->CallVoidMethod(sparseAr, gSparseArrayClassInfo.put, uid, ar);
    }
    return ar;
}

static void copy2DVecToArray(JNIEnv *env, jlongArray ar, std::vector<std::vector<uint64_t>> &vec) {
    jsize start = 0;
    for (auto &subVec : vec) {
        for (uint32_t i = 0; i < subVec.size(); ++i) subVec[i] /= NSEC_PER_MSEC;
        env->SetLongArrayRegion(ar, start, subVec.size(),
                                reinterpret_cast<const jlong *>(subVec.data()));
        start += subVec.size();
    }
}

static jboolean KernelCpuUidFreqTimeBpfMapReader_removeUidRange(JNIEnv *env, jclass, jint startUid,
                                                                jint endUid) {
    for (uint32_t uid = startUid; uid <= endUid; ++uid) {
        if (!android::bpf::clearUidTimes(uid)) return false;
    }
    return true;
}

static jboolean KernelCpuUidFreqTimeBpfMapReader_readBpfData(JNIEnv *env, jobject thiz) {
    static uint64_t lastUpdate = 0;
    uint64_t newLastUpdate = lastUpdate;
    auto sparseAr = env->GetObjectField(thiz, gmData);
    if (sparseAr == NULL) return false;
    auto data = android::bpf::getUidsUpdatedCpuFreqTimes(&newLastUpdate);
    if (!data.has_value()) return false;

    jsize s = 0;
    for (auto &[uid, times] : *data) {
        if (s == 0) {
            for (const auto &subVec : times) s += subVec.size();
        }
        jlongArray ar = getUidArray(env, sparseAr, uid, s);
        if (ar == NULL) return false;
        copy2DVecToArray(env, ar, times);
    }
    lastUpdate = newLastUpdate;
    return true;
}

static jlongArray KernelCpuUidFreqTimeBpfMapReader_getDataDimensions(JNIEnv *env, jobject) {
    auto freqs = android::bpf::getCpuFreqs();
    if (!freqs) return NULL;

    std::vector<uint64_t> allFreqs;
    for (const auto &vec : *freqs) std::copy(vec.begin(), vec.end(), std::back_inserter(allFreqs));

    auto ar = env->NewLongArray(allFreqs.size());
    if (ar != NULL) {
        env->SetLongArrayRegion(ar, 0, allFreqs.size(),
                                reinterpret_cast<const jlong *>(allFreqs.data()));
    }
    return ar;
}

static const JNINativeMethod gFreqTimeMethods[] = {
        {"removeUidRange", "(II)Z", (void *)KernelCpuUidFreqTimeBpfMapReader_removeUidRange},
        {"readBpfData", "()Z", (void *)KernelCpuUidFreqTimeBpfMapReader_readBpfData},
        {"getDataDimensions", "()[J", (void *)KernelCpuUidFreqTimeBpfMapReader_getDataDimensions},
};

static jboolean KernelCpuUidActiveTimeBpfMapReader_readBpfData(JNIEnv *env, jobject thiz) {
    static uint64_t lastUpdate = 0;
    uint64_t newLastUpdate = lastUpdate;
    auto sparseAr = env->GetObjectField(thiz, gmData);
    if (sparseAr == NULL) return false;
    auto data = android::bpf::getUidsUpdatedConcurrentTimes(&newLastUpdate);
    if (!data.has_value()) return false;

    for (auto &[uid, times] : *data) {
        // TODO: revise calling code so we can divide by NSEC_PER_MSEC here instead
        for (auto &time : times.active) time /= NSEC_PER_MSEC;
        jlongArray ar = getUidArray(env, sparseAr, uid, times.active.size());
        if (ar == NULL) return false;
        env->SetLongArrayRegion(ar, 0, times.active.size(),
                                reinterpret_cast<const jlong *>(times.active.data()));
    }
    lastUpdate = newLastUpdate;
    return true;
}

static jlongArray KernelCpuUidActiveTimeBpfMapReader_getDataDimensions(JNIEnv *env, jobject) {
    jlong nCpus = get_nprocs_conf();

    auto ar = env->NewLongArray(1);
    if (ar != NULL) env->SetLongArrayRegion(ar, 0, 1, &nCpus);
    return ar;
}

static const JNINativeMethod gActiveTimeMethods[] = {
        {"readBpfData", "()Z", (void *)KernelCpuUidActiveTimeBpfMapReader_readBpfData},
        {"getDataDimensions", "()[J", (void *)KernelCpuUidActiveTimeBpfMapReader_getDataDimensions},
};

static jboolean KernelCpuUidClusterTimeBpfMapReader_readBpfData(JNIEnv *env, jobject thiz) {
    static uint64_t lastUpdate = 0;
    uint64_t newLastUpdate = lastUpdate;
    auto sparseAr = env->GetObjectField(thiz, gmData);
    if (sparseAr == NULL) return false;
    auto data = android::bpf::getUidsUpdatedConcurrentTimes(&newLastUpdate);
    if (!data.has_value()) return false;

    jsize s = 0;
    for (auto &[uid, times] : *data) {
        if (s == 0) {
            for (const auto &subVec : times.policy) s += subVec.size();
        }
        jlongArray ar = getUidArray(env, sparseAr, uid, s);
        if (ar == NULL) return false;
        copy2DVecToArray(env, ar, times.policy);
    }
    lastUpdate = newLastUpdate;
    return true;
}

static jlongArray KernelCpuUidClusterTimeBpfMapReader_getDataDimensions(JNIEnv *env, jobject) {
    auto times = android::bpf::getUidConcurrentTimes(0);
    if (!times.has_value()) return NULL;

    std::vector<jlong> clusterCores;
    for (const auto &vec : times->policy) clusterCores.push_back(vec.size());
    auto ar = env->NewLongArray(clusterCores.size());
    if (ar != NULL) env->SetLongArrayRegion(ar, 0, clusterCores.size(), clusterCores.data());
    return ar;
}

static const JNINativeMethod gClusterTimeMethods[] = {
        {"readBpfData", "()Z", (void *)KernelCpuUidClusterTimeBpfMapReader_readBpfData},
        {"getDataDimensions", "()[J",
         (void *)KernelCpuUidClusterTimeBpfMapReader_getDataDimensions},
};

struct readerMethods {
    const char *name;
    const JNINativeMethod *methods;
    int numMethods;
};

static const readerMethods gAllMethods[] = {
        {"KernelCpuUidFreqTimeBpfMapReader", gFreqTimeMethods, NELEM(gFreqTimeMethods)},
        {"KernelCpuUidActiveTimeBpfMapReader", gActiveTimeMethods, NELEM(gActiveTimeMethods)},
        {"KernelCpuUidClusterTimeBpfMapReader", gClusterTimeMethods, NELEM(gClusterTimeMethods)},
};

static jboolean KernelCpuUidBpfMapReader_startTrackingBpfTimes(JNIEnv *, jobject) {
    return android::bpf::startTrackingUidTimes();
}

int register_com_android_internal_os_KernelCpuUidBpfMapReader(JNIEnv *env) {
    gSparseArrayClassInfo.clazz = FindClassOrDie(env, "android/util/SparseArray");
    gSparseArrayClassInfo.clazz = MakeGlobalRefOrDie(env, gSparseArrayClassInfo.clazz);
    gSparseArrayClassInfo.put =
            GetMethodIDOrDie(env, gSparseArrayClassInfo.clazz, "put", "(ILjava/lang/Object;)V");
    gSparseArrayClassInfo.get =
            GetMethodIDOrDie(env, gSparseArrayClassInfo.clazz, "get", "(I)Ljava/lang/Object;");
    constexpr auto readerName = "com/android/internal/os/KernelCpuUidBpfMapReader";
    constexpr JNINativeMethod method = {"startTrackingBpfTimes", "()Z",
                                        (void *)KernelCpuUidBpfMapReader_startTrackingBpfTimes};

    int ret = RegisterMethodsOrDie(env, readerName, &method, 1);
    if (ret < 0) return ret;
    auto c = FindClassOrDie(env, readerName);
    gmData = GetFieldIDOrDie(env, c, "mData", "Landroid/util/SparseArray;");

    for (const auto &m : gAllMethods) {
        auto fullName = android::base::StringPrintf("%s$%s", readerName, m.name);
        ret = RegisterMethodsOrDie(env, fullName.c_str(), m.methods, m.numMethods);
        if (ret < 0) break;
    }
    return ret;
}

} // namespace android
