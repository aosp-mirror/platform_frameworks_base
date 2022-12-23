/*
 * Copyright (C) 2021 The Android Open Source Project
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
#include <android-base/logging.h>
#include <android-base/stringprintf.h>
#include <dmabufinfo/dmabufinfo.h>
#include <jni.h>
#include <meminfo/sysmeminfo.h>
#include <procinfo/process.h>

#include "core_jni_helpers.h"

using DmaBuffer = ::android::dmabufinfo::DmaBuffer;
using android::base::ReadFileToString;
using android::base::StringPrintf;

namespace {
static jclass gProcessDmabufClazz;
static jmethodID gProcessDmabufCtor;
static jclass gProcessGpuMemClazz;
static jmethodID gProcessGpuMemCtor;
} // namespace

namespace android {

struct PidDmaInfo {
    uid_t uid;
    std::string cmdline;
    int oomScoreAdj;
};

static jobjectArray KernelAllocationStats_getDmabufAllocations(JNIEnv *env, jobject) {
    std::vector<DmaBuffer> buffers;

    if (!dmabufinfo::ReadDmaBufs(&buffers)) {
        return nullptr;
    }

    // Create a reverse map from pid to dmabufs
    // Store dmabuf inodes & sizes for later processing.
    std::unordered_map<pid_t, std::set<ino_t>> pidToInodes;
    std::unordered_map<ino_t, long> inodeToSize;
    for (auto &buf : buffers) {
        for (auto pid : buf.pids()) {
            pidToInodes[pid].insert(buf.inode());
        }
        inodeToSize[buf.inode()] = buf.size();
    }

    pid_t surfaceFlingerPid = -1;
    // The set of all inodes that are being retained by SurfaceFlinger. Buffers
    // shared between another process and SF will appear in this set.
    std::set<ino_t> surfaceFlingerBufferInodes;
    // The set of all inodes that are being retained by any process other
    // than SurfaceFlinger. Buffers shared between another process and SF will
    // appear in this set.
    std::set<ino_t> otherProcessBufferInodes;

    // Find SurfaceFlinger pid & get cmdlines, oomScoreAdj, etc for each pid
    // holding any DMA buffers.
    std::unordered_map<pid_t, PidDmaInfo> pidDmaInfos;
    for (const auto &pidToInodeEntry : pidToInodes) {
        pid_t pid = pidToInodeEntry.first;

        android::procinfo::ProcessInfo processInfo;
        if (!android::procinfo::GetProcessInfo(pid, &processInfo)) {
            continue;
        }

        std::string cmdline;
        if (!ReadFileToString(StringPrintf("/proc/%d/cmdline", pid), &cmdline)) {
            continue;
        }

        // cmdline strings are null-delimited, so we split on \0 here
        if (cmdline.substr(0, cmdline.find('\0')) == "/system/bin/surfaceflinger") {
            if (surfaceFlingerPid == -1) {
                surfaceFlingerPid = pid;
                surfaceFlingerBufferInodes = pidToInodes[pid];
            } else {
                LOG(ERROR) << "getDmabufAllocations found multiple SF processes; pid1: " << pid
                           << ", pid2:" << surfaceFlingerPid;
                surfaceFlingerPid = -2; // Used as a sentinel value below
            }
        } else {
            otherProcessBufferInodes.insert(pidToInodes[pid].begin(), pidToInodes[pid].end());
        }

        std::string oomScoreAdjStr;
        if (!ReadFileToString(StringPrintf("/proc/%d/oom_score_adj", pid), &oomScoreAdjStr)) {
            continue;
        }

        pidDmaInfos[pid] = PidDmaInfo{.uid = processInfo.uid,
                                      .cmdline = cmdline,
                                      .oomScoreAdj = atoi(oomScoreAdjStr.c_str())};
    }

    if (surfaceFlingerPid < 0) {
        LOG(ERROR) << "getDmabufAllocations could not identify SurfaceFlinger "
                   << "process via /proc/pid/cmdline";
    }

    jobjectArray ret = env->NewObjectArray(pidDmaInfos.size(), gProcessDmabufClazz, NULL);
    int retArrayIndex = 0;
    for (const auto &pidDmaInfosEntry : pidDmaInfos) {
        pid_t pid = pidDmaInfosEntry.first;

        // For all processes apart from SurfaceFlinger, this set will store the
        // dmabuf inodes that are shared with SF. For SF, it will store the inodes
        // that are shared with any other process.
        std::set<ino_t> sharedBuffers;
        if (pid == surfaceFlingerPid) {
            set_intersection(surfaceFlingerBufferInodes.begin(), surfaceFlingerBufferInodes.end(),
                             otherProcessBufferInodes.begin(), otherProcessBufferInodes.end(),
                             std::inserter(sharedBuffers, sharedBuffers.end()));
        } else if (surfaceFlingerPid > 0) {
            set_intersection(pidToInodes[pid].begin(), pidToInodes[pid].end(),
                             surfaceFlingerBufferInodes.begin(), surfaceFlingerBufferInodes.end(),
                             std::inserter(sharedBuffers, sharedBuffers.begin()));
        } // If surfaceFlingerPid < 0; it means we failed to identify it, and
        // the SF-related fields below should be left empty.

        long totalSize = 0;
        long sharedBuffersSize = 0;
        for (const auto &inode : pidToInodes[pid]) {
            totalSize += inodeToSize[inode];
            if (sharedBuffers.count(inode)) {
                sharedBuffersSize += inodeToSize[inode];
            }
        }

        jobject obj = env->NewObject(gProcessDmabufClazz, gProcessDmabufCtor,
                                     /* uid */ pidDmaInfos[pid].uid,
                                     /* process name */
                                     env->NewStringUTF(pidDmaInfos[pid].cmdline.c_str()),
                                     /* oomscore */ pidDmaInfos[pid].oomScoreAdj,
                                     /* retainedSize */ totalSize / 1024,
                                     /* retainedCount */ pidToInodes[pid].size(),
                                     /* sharedWithSurfaceFlinger size */ sharedBuffersSize / 1024,
                                     /* sharedWithSurfaceFlinger count */ sharedBuffers.size());

        env->SetObjectArrayElement(ret, retArrayIndex++, obj);
    }

    return ret;
}

static jobject KernelAllocationStats_getGpuAllocations(JNIEnv *env) {
    std::unordered_map<uint32_t, uint64_t> out;
    meminfo::ReadPerProcessGpuMem(&out);
    jobjectArray result = env->NewObjectArray(out.size(), gProcessGpuMemClazz, nullptr);
    if (result == NULL) {
        jniThrowRuntimeException(env, "Cannot create result array");
        return nullptr;
    }
    int idx = 0;
    for (const auto &entry : out) {
        jobject pidStats =
                env->NewObject(gProcessGpuMemClazz, gProcessGpuMemCtor, entry.first, entry.second);
        env->SetObjectArrayElement(result, idx, pidStats);
        env->DeleteLocalRef(pidStats);
        ++idx;
    }
    return result;
}

static const JNINativeMethod methods[] = {
        {"getDmabufAllocations", "()[Lcom/android/internal/os/KernelAllocationStats$ProcessDmabuf;",
         (void *)KernelAllocationStats_getDmabufAllocations},
        {"getGpuAllocations", "()[Lcom/android/internal/os/KernelAllocationStats$ProcessGpuMem;",
         (void *)KernelAllocationStats_getGpuAllocations},
};

int register_com_android_internal_os_KernelAllocationStats(JNIEnv *env) {
    int res = RegisterMethodsOrDie(env, "com/android/internal/os/KernelAllocationStats", methods,
                                   NELEM(methods));
    jclass clazz =
            FindClassOrDie(env, "com/android/internal/os/KernelAllocationStats$ProcessDmabuf");
    gProcessDmabufClazz = MakeGlobalRefOrDie(env, clazz);
    gProcessDmabufCtor =
            GetMethodIDOrDie(env, gProcessDmabufClazz, "<init>", "(ILjava/lang/String;IIIII)V");

    clazz = FindClassOrDie(env, "com/android/internal/os/KernelAllocationStats$ProcessGpuMem");
    gProcessGpuMemClazz = MakeGlobalRefOrDie(env, clazz);
    gProcessGpuMemCtor = GetMethodIDOrDie(env, gProcessGpuMemClazz, "<init>", "(II)V");
    return res;
}

} // namespace android