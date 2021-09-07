/*
 * Copyright (C) 2019 The Android Open Source Project
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

#define LOG_TAG "CachedAppOptimizer"
//#define LOG_NDEBUG 0

#include <android-base/file.h>
#include <android-base/logging.h>
#include <android-base/stringprintf.h>
#include <android-base/unique_fd.h>
#include <android_runtime/AndroidRuntime.h>
#include <binder/IPCThreadState.h>
#include <cutils/compiler.h>
#include <dirent.h>
#include <jni.h>
#include <linux/errno.h>
#include <log/log.h>
#include <meminfo/procmeminfo.h>
#include <nativehelper/JNIHelp.h>
#include <processgroup/processgroup.h>
#include <stddef.h>
#include <stdio.h>
#include <sys/mman.h>
#include <sys/pidfd.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/types.h>
#include <unistd.h>

#include <algorithm>

using android::base::StringPrintf;
using android::base::WriteStringToFile;
using android::meminfo::ProcMemInfo;
using namespace android::meminfo;

#define COMPACT_ACTION_FILE_FLAG 1
#define COMPACT_ACTION_ANON_FLAG 2

using VmaToAdviseFunc = std::function<int(const Vma&)>;
using android::base::unique_fd;

#define SYNC_RECEIVED_WHILE_FROZEN (1)
#define ASYNC_RECEIVED_WHILE_FROZEN (2)
#define TXNS_PENDING_WHILE_FROZEN (4)

namespace android {

// Legacy method for compacting processes, any new code should
// use compactProcess instead.
static inline void compactProcessProcfs(int pid, const std::string& compactionType) {
    std::string reclaim_path = StringPrintf("/proc/%d/reclaim", pid);
    WriteStringToFile(compactionType, reclaim_path);
}

// Compacts a set of VMAs for pid using an madviseType accepted by process_madvise syscall
// On success returns the total bytes that where compacted. On failure it returns
// a negative error code from the standard linux error codes.
static int64_t compactMemory(const std::vector<Vma>& vmas, int pid, int madviseType) {
    // UIO_MAXIOV is currently a small value and we might have more addresses
    // we do multiple syscalls if we exceed its maximum
    static struct iovec vmasToKernel[UIO_MAXIOV];

    if (vmas.empty()) {
        return 0;
    }

    unique_fd pidfd(pidfd_open(pid, 0));
    if (pidfd < 0) {
        // Skip compaction if failed to open pidfd with any error
        return -errno;
    }

    int64_t totalBytesCompacted = 0;
    for (int iBase = 0; iBase < vmas.size(); iBase += UIO_MAXIOV) {
        int totalVmasToKernel = std::min(UIO_MAXIOV, (int)(vmas.size() - iBase));
        for (int iVec = 0, iVma = iBase; iVec < totalVmasToKernel; ++iVec, ++iVma) {
            vmasToKernel[iVec].iov_base = (void*)vmas[iVma].start;
            vmasToKernel[iVec].iov_len = vmas[iVma].end - vmas[iVma].start;
        }

        auto bytesCompacted =
                process_madvise(pidfd, vmasToKernel, totalVmasToKernel, madviseType, 0);
        if (CC_UNLIKELY(bytesCompacted == -1)) {
            return -errno;
        }

        totalBytesCompacted += bytesCompacted;
    }

    return totalBytesCompacted;
}

static int getFilePageAdvice(const Vma& vma) {
    if (vma.inode > 0 && !vma.is_shared) {
        return MADV_COLD;
    }
    return -1;
}
static int getAnonPageAdvice(const Vma& vma) {
    if (vma.inode == 0 && !vma.is_shared) {
        return MADV_PAGEOUT;
    }
    return -1;
}
static int getAnyPageAdvice(const Vma& vma) {
    if (vma.inode == 0 && !vma.is_shared) {
        return MADV_PAGEOUT;
    }
    return MADV_COLD;
}

// Perform a full process compaction using process_madvise syscall
// reading all filtering VMAs and filtering pages as specified by pageFilter
static int64_t compactProcess(int pid, VmaToAdviseFunc vmaToAdviseFunc) {
    ProcMemInfo meminfo(pid);
    std::vector<Vma> pageoutVmas, coldVmas;
    auto vmaCollectorCb = [&coldVmas,&pageoutVmas,&vmaToAdviseFunc](const Vma& vma) {
        int advice = vmaToAdviseFunc(vma);
        switch (advice) {
            case MADV_COLD:
                coldVmas.push_back(vma);
                break;
            case MADV_PAGEOUT:
                pageoutVmas.push_back(vma);
                break;
        }
    };
    meminfo.ForEachVmaFromMaps(vmaCollectorCb);

    int64_t pageoutBytes = compactMemory(pageoutVmas, pid, MADV_PAGEOUT);
    if (pageoutBytes < 0) {
        // Error, just forward it.
        return pageoutBytes;
    }

    int64_t coldBytes = compactMemory(coldVmas, pid, MADV_COLD);
    if (coldBytes < 0) {
        // Error, just forward it.
        return coldBytes;
    }

    return pageoutBytes + coldBytes;
}

// Compact process using process_madvise syscall or fallback to procfs in
// case syscall does not exist.
static void compactProcessOrFallback(int pid, int compactionFlags) {
    if ((compactionFlags & (COMPACT_ACTION_ANON_FLAG | COMPACT_ACTION_FILE_FLAG)) == 0) return;

    bool compactAnon = compactionFlags & COMPACT_ACTION_ANON_FLAG;
    bool compactFile = compactionFlags & COMPACT_ACTION_FILE_FLAG;

    // Set when the system does not support process_madvise syscall to avoid
    // gathering VMAs in subsequent calls prior to falling back to procfs
    static bool shouldForceProcFs = false;
    std::string compactionType;
    VmaToAdviseFunc vmaToAdviseFunc;

    if (compactAnon) {
        if (compactFile) {
            compactionType = "all";
            vmaToAdviseFunc = getAnyPageAdvice;
        } else {
            compactionType = "anon";
            vmaToAdviseFunc = getAnonPageAdvice;
        }
    } else {
        compactionType = "file";
        vmaToAdviseFunc = getFilePageAdvice;
    }

    if (shouldForceProcFs || compactProcess(pid, vmaToAdviseFunc) == -ENOSYS) {
        shouldForceProcFs = true;
        compactProcessProcfs(pid, compactionType);
    }
}

// This performs per-process reclaim on all processes belonging to non-app UIDs.
// For the most part, these are non-zygote processes like Treble HALs, but it
// also includes zygote-derived processes that run in system UIDs, like bluetooth
// or potentially some mainline modules. The only process that should definitely
// not be compacted is system_server, since compacting system_server around the
// time of BOOT_COMPLETE could result in perceptible issues.
static void com_android_server_am_CachedAppOptimizer_compactSystem(JNIEnv *, jobject) {
    std::unique_ptr<DIR, decltype(&closedir)> proc(opendir("/proc"), closedir);
    struct dirent* current;
    while ((current = readdir(proc.get()))) {
        if (current->d_type != DT_DIR) {
            continue;
        }

        // don't compact system_server, rely on persistent compaction during screen off
        // in order to avoid mmap_sem-related stalls
        if (atoi(current->d_name) == getpid()) {
            continue;
        }

        std::string status_name = StringPrintf("/proc/%s/status", current->d_name);
        struct stat status_info;

        if (stat(status_name.c_str(), &status_info) != 0) {
            // must be some other directory that isn't a pid
            continue;
        }

        // android.os.Process.FIRST_APPLICATION_UID
        if (status_info.st_uid >= 10000) {
            continue;
        }

        int pid = atoi(current->d_name);

        compactProcessOrFallback(pid, COMPACT_ACTION_ANON_FLAG | COMPACT_ACTION_FILE_FLAG);
    }
}

static void com_android_server_am_CachedAppOptimizer_compactProcess(JNIEnv*, jobject, jint pid,
                                                                    jint compactionFlags) {
    compactProcessOrFallback(pid, compactionFlags);
}

static jint com_android_server_am_CachedAppOptimizer_freezeBinder(
        JNIEnv *env, jobject clazz, jint pid, jboolean freeze) {

    jint retVal = IPCThreadState::freeze(pid, freeze, 100 /* timeout [ms] */);
    if (retVal != 0 && retVal != -EAGAIN) {
        jniThrowException(env, "java/lang/RuntimeException", "Unable to freeze/unfreeze binder");
    }

    return retVal;
}

static jint com_android_server_am_CachedAppOptimizer_getBinderFreezeInfo(JNIEnv *env,
        jobject clazz, jint pid) {
    uint32_t syncReceived = 0, asyncReceived = 0;

    int error = IPCThreadState::getProcessFreezeInfo(pid, &syncReceived, &asyncReceived);

    if (error < 0) {
        jniThrowException(env, "java/lang/RuntimeException", strerror(error));
    }

    jint retVal = 0;

    // bit 0 of sync_recv goes to bit 0 of retVal
    retVal |= syncReceived & SYNC_RECEIVED_WHILE_FROZEN;
    // bit 0 of async_recv goes to bit 1 of retVal
    retVal |= (asyncReceived << 1) & ASYNC_RECEIVED_WHILE_FROZEN;
    // bit 1 of sync_recv goes to bit 2 of retVal
    retVal |= (syncReceived << 1) & TXNS_PENDING_WHILE_FROZEN;

    return retVal;
}

static jstring com_android_server_am_CachedAppOptimizer_getFreezerCheckPath(JNIEnv* env,
                                                                            jobject clazz) {
    std::string path;

    if (!getAttributePathForTask("FreezerState", getpid(), &path)) {
        path = "";
    }

    return env->NewStringUTF(path.c_str());
}

static const JNINativeMethod sMethods[] = {
        /* name, signature, funcPtr */
        {"compactSystem", "()V", (void*)com_android_server_am_CachedAppOptimizer_compactSystem},
        {"compactProcess", "(II)V", (void*)com_android_server_am_CachedAppOptimizer_compactProcess},
        {"freezeBinder", "(IZ)I", (void*)com_android_server_am_CachedAppOptimizer_freezeBinder},
        {"getBinderFreezeInfo", "(I)I",
         (void*)com_android_server_am_CachedAppOptimizer_getBinderFreezeInfo},
        {"getFreezerCheckPath", "()Ljava/lang/String;",
         (void*)com_android_server_am_CachedAppOptimizer_getFreezerCheckPath}};

int register_android_server_am_CachedAppOptimizer(JNIEnv* env)
{
    return jniRegisterNativeMethods(env, "com/android/server/am/CachedAppOptimizer",
                                    sMethods, NELEM(sMethods));
}

}
