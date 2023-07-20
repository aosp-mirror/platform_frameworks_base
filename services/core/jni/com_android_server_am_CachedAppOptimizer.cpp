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
#define ATRACE_TAG ATRACE_TAG_ACTIVITY_MANAGER
#define ATRACE_COMPACTION_TRACK "Compaction"

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
#include <sys/sysinfo.h>
#include <sys/types.h>
#include <unistd.h>
#include <utils/Trace.h>

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

#define MAX_RW_COUNT (INT_MAX & PAGE_MASK)

// Defines the maximum amount of VMAs we can send per process_madvise syscall.
// Currently this is set to UIO_MAXIOV which is the maximum segments allowed by
// iovec implementation used by process_madvise syscall
#define MAX_VMAS_PER_BATCH UIO_MAXIOV

// Maximum bytes that we can send per process_madvise syscall once this limit
// is reached we split the remaining VMAs into another syscall. The MAX_RW_COUNT
// limit is imposed by iovec implementation. However, if you want to use a smaller
// limit, it has to be a page aligned value.
#define MAX_BYTES_PER_BATCH MAX_RW_COUNT

// Selected a high enough number to avoid clashing with linux errno codes
#define ERROR_COMPACTION_CANCELLED -1000

namespace android {

// Signal happening in separate thread that would bail out compaction
// before starting next VMA batch
static std::atomic<bool> cancelRunningCompaction;

static bool inSystemCompaction = false;

// A VmaBatch represents a set of VMAs that can be processed
// as VMAs are processed by client code it is expected that the
// VMAs get consumed which means they are discarded as they are
// processed so that the first element always is the next element
// to be sent
struct VmaBatch {
    struct iovec* vmas;
    // total amount of VMAs to reach the end of iovec
    size_t totalVmas;
    // total amount of bytes that are remaining within iovec
    uint64_t totalBytes;
};

// Advances the iterator by the specified amount of bytes.
// This is used to remove already processed or no longer
// needed parts of the batch.
// Returns total bytes consumed
uint64_t consumeBytes(VmaBatch& batch, uint64_t bytesToConsume) {
    if (CC_UNLIKELY(bytesToConsume) < 0) {
        LOG(ERROR) << "Cannot consume negative bytes for VMA batch !";
        return 0;
    }

    if (CC_UNLIKELY(bytesToConsume > batch.totalBytes)) {
        // Avoid consuming more bytes than available
        bytesToConsume = batch.totalBytes;
    }

    uint64_t bytesConsumed = 0;
    while (bytesConsumed < bytesToConsume) {
        if (CC_UNLIKELY(batch.totalVmas == 0)) {
            // No more vmas to consume
            break;
        }
        if (CC_UNLIKELY(bytesConsumed + batch.vmas[0].iov_len > bytesToConsume)) {
            // This vma can't be fully consumed, do it partially.
            uint64_t bytesLeftToConsume = bytesToConsume - bytesConsumed;
            bytesConsumed += bytesLeftToConsume;
            batch.vmas[0].iov_base = (void*)((uint64_t)batch.vmas[0].iov_base + bytesLeftToConsume);
            batch.vmas[0].iov_len -= bytesLeftToConsume;
            batch.totalBytes -= bytesLeftToConsume;
            return bytesConsumed;
        }
        // This vma can be fully consumed
        bytesConsumed += batch.vmas[0].iov_len;
        batch.totalBytes -= batch.vmas[0].iov_len;
        --batch.totalVmas;
        ++batch.vmas;
    }

    return bytesConsumed;
}

// given a source of vmas this class will act as a factory
// of VmaBatch objects and it will allow generating batches
// until there are no more left in the source vector.
// Note: the class does not actually modify the given
// vmas vector, instead it iterates on it until the end.
class VmaBatchCreator {
    const std::vector<Vma>* sourceVmas;
    // This is the destination array where batched VMAs will be stored
    // it gets encapsulated into a VmaBatch which is the object
    // meant to be used by client code.
    struct iovec* destVmas;

    // Parameters to keep track of the iterator on the source vmas
    int currentIndex_;
    uint64_t currentOffset_;

public:
    VmaBatchCreator(const std::vector<Vma>* vmasToBatch, struct iovec* destVmasVec)
          : sourceVmas(vmasToBatch), destVmas(destVmasVec), currentIndex_(0), currentOffset_(0) {}

    int currentIndex() { return currentIndex_; }
    uint64_t currentOffset() { return currentOffset_; }

    // Generates a batch and moves the iterator on the source vmas
    // past the last VMA in the batch.
    // Returns true on success, false on failure
    bool createNextBatch(VmaBatch& batch) {
        if (currentIndex_ >= MAX_VMAS_PER_BATCH && currentIndex_ >= sourceVmas->size()) {
            return false;
        }

        const std::vector<Vma>& vmas = *sourceVmas;
        batch.vmas = destVmas;
        uint64_t totalBytesInBatch = 0;
        int indexInBatch = 0;

        // Add VMAs to the batch up until we consumed all the VMAs or
        // reached any imposed limit of VMAs per batch.
        while (indexInBatch < MAX_VMAS_PER_BATCH && currentIndex_ < vmas.size()) {
            uint64_t vmaStart = vmas[currentIndex_].start + currentOffset_;
            uint64_t vmaSize = vmas[currentIndex_].end - vmaStart;
            uint64_t bytesAvailableInBatch = MAX_BYTES_PER_BATCH - totalBytesInBatch;

            batch.vmas[indexInBatch].iov_base = (void*)vmaStart;

            if (vmaSize > bytesAvailableInBatch) {
                // VMA would exceed the max available bytes in batch
                // clamp with available bytes and finish batch.
                vmaSize = bytesAvailableInBatch;
                currentOffset_ += bytesAvailableInBatch;
            }

            batch.vmas[indexInBatch].iov_len = vmaSize;
            totalBytesInBatch += vmaSize;

            ++indexInBatch;
            if (totalBytesInBatch >= MAX_BYTES_PER_BATCH) {
                // Reached max bytes quota so this marks
                // the end of the batch
                if (CC_UNLIKELY(vmaSize == (vmas[currentIndex_].end - vmaStart))) {
                    // we reached max bytes exactly at the end of the vma
                    // so advance to next one
                    currentOffset_ = 0;
                    ++currentIndex_;
                }
                break;
            }
            // Fully finished current VMA, move to next one
            currentOffset_ = 0;
            ++currentIndex_;
        }
        batch.totalVmas = indexInBatch;
        batch.totalBytes = totalBytesInBatch;
        if (batch.totalVmas == 0 || batch.totalBytes == 0) {
            // This is an empty batch, mark as failed creating.
            return false;
        }
        return true;
    }
};

// Madvise a set of VMAs given in a batch for a specific process
// The total number of bytes successfully madvised will be set on
// outBytesProcessed.
// Returns 0 on success and standard linux -errno code returned by
// process_madvise on failure
int madviseVmasFromBatch(unique_fd& pidfd, VmaBatch& batch, int madviseType,
                         uint64_t* outBytesProcessed) {
    if (batch.totalVmas == 0 || batch.totalBytes == 0) {
        // No VMAs in Batch, skip.
        *outBytesProcessed = 0;
        return 0;
    }

    ATRACE_BEGIN(StringPrintf("Madvise %d: %zu VMAs.", madviseType, batch.totalVmas).c_str());
    int64_t bytesProcessedInSend =
            process_madvise(pidfd, batch.vmas, batch.totalVmas, madviseType, 0);
    ATRACE_END();
    if (CC_UNLIKELY(bytesProcessedInSend == -1)) {
        bytesProcessedInSend = 0;
        if (errno != EINVAL) {
            // Forward irrecoverable errors and bail out compaction
            *outBytesProcessed = 0;
            return -errno;
        }
    }
    if (bytesProcessedInSend == 0) {
        // When we find a VMA with error, fully consume it as it
        // is extremely expensive to iterate on its pages one by one
        bytesProcessedInSend = batch.vmas[0].iov_len;
    } else if (bytesProcessedInSend < batch.totalBytes) {
        // Partially processed the bytes requested
        // skip last page which is where it failed.
        bytesProcessedInSend += PAGE_SIZE;
    }
    bytesProcessedInSend = consumeBytes(batch, bytesProcessedInSend);

    *outBytesProcessed = bytesProcessedInSend;
    return 0;
}

// Legacy method for compacting processes, any new code should
// use compactProcess instead.
static inline void compactProcessProcfs(int pid, const std::string& compactionType) {
    std::string reclaim_path = StringPrintf("/proc/%d/reclaim", pid);
    WriteStringToFile(compactionType, reclaim_path);
}

// Compacts a set of VMAs for pid using an madviseType accepted by process_madvise syscall
// Returns the total bytes that where madvised.
//
// If any VMA fails compaction due to -EINVAL it will be skipped and continue.
// However, if it fails for any other reason, it will bail out and forward the error
static int64_t compactMemory(const std::vector<Vma>& vmas, int pid, int madviseType) {
    if (vmas.empty()) {
        return 0;
    }

    unique_fd pidfd(pidfd_open(pid, 0));
    if (pidfd < 0) {
        // Skip compaction if failed to open pidfd with any error
        return -errno;
    }

    struct iovec destVmas[MAX_VMAS_PER_BATCH];

    VmaBatch batch;
    VmaBatchCreator batcher(&vmas, destVmas);

    int64_t totalBytesProcessed = 0;
    while (batcher.createNextBatch(batch)) {
        uint64_t bytesProcessedInSend;
        ScopedTrace batchTrace(ATRACE_TAG, "VMA Batch");
        do {
            if (CC_UNLIKELY(cancelRunningCompaction.load())) {
                // There could be a significant delay between when a compaction
                // is requested and when it is handled during this time our
                // OOM adjust could have improved.
                LOG(DEBUG) << "Cancelled running compaction for " << pid;
                ATRACE_INSTANT_FOR_TRACK(ATRACE_COMPACTION_TRACK,
                                         StringPrintf("Cancelled compaction for %d", pid).c_str());
                return ERROR_COMPACTION_CANCELLED;
            }
            int error = madviseVmasFromBatch(pidfd, batch, madviseType, &bytesProcessedInSend);
            if (error < 0) {
                // Returns standard linux errno code
                return error;
            }
            if (CC_UNLIKELY(bytesProcessedInSend == 0)) {
                // This means there was a problem consuming bytes,
                // bail out since no forward progress can be made with this batch
                break;
            }
            totalBytesProcessed += bytesProcessedInSend;
        } while (batch.totalBytes > 0 && batch.totalVmas > 0);
    }

    return totalBytesProcessed;
}

static int getFilePageAdvice(const Vma& vma) {
    if (vma.inode > 0 && !vma.is_shared) {
        return MADV_COLD;
    }
    return -1;
}
static int getAnonPageAdvice(const Vma& vma) {
    if (vma.inode == 0) {
        return MADV_PAGEOUT;
    }
    return -1;
}
static int getAnyPageAdvice(const Vma& vma) {
    if (inSystemCompaction == true) {
        return MADV_PAGEOUT;
    }

    if (vma.inode == 0 && !vma.is_shared) {
        return MADV_PAGEOUT;
    }
    return MADV_COLD;
}

// Perform a full process compaction using process_madvise syscall
// using the madvise behavior defined by vmaToAdviseFunc per VMA.
//
// Currently supported behaviors are MADV_COLD and MADV_PAGEOUT.
//
// Returns the total number of bytes compacted on success. On error
// returns process_madvise errno code or if compaction was cancelled
// it returns ERROR_COMPACTION_CANCELLED.
static int64_t compactProcess(int pid, VmaToAdviseFunc vmaToAdviseFunc) {
    cancelRunningCompaction.store(false);

    ATRACE_BEGIN("CollectVmas");
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
    ATRACE_END();

    int64_t pageoutBytes = compactMemory(pageoutVmas, pid, MADV_PAGEOUT);
    if (pageoutBytes < 0) {
        // Error, just forward it.
        cancelRunningCompaction.store(false);
        return pageoutBytes;
    }

    int64_t coldBytes = compactMemory(coldVmas, pid, MADV_COLD);
    if (coldBytes < 0) {
        // Error, just forward it.
        cancelRunningCompaction.store(false);
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
    inSystemCompaction = true;
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
    inSystemCompaction = false;
}

static void com_android_server_am_CachedAppOptimizer_cancelCompaction(JNIEnv*, jobject) {
    cancelRunningCompaction.store(true);
    ATRACE_INSTANT_FOR_TRACK(ATRACE_COMPACTION_TRACK, "Cancel compaction");
}

static jdouble com_android_server_am_CachedAppOptimizer_getFreeSwapPercent(JNIEnv*, jobject) {
    struct sysinfo memoryInfo;
    int error = sysinfo(&memoryInfo);
    if(error == -1) {
        LOG(ERROR) << "Could not check free swap space";
        return 0;
    }
    return (double)memoryInfo.freeswap / (double)memoryInfo.totalswap;
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
        {"cancelCompaction", "()V",
         (void*)com_android_server_am_CachedAppOptimizer_cancelCompaction},
        {"getFreeSwapPercent", "()D",
         (void*)com_android_server_am_CachedAppOptimizer_getFreeSwapPercent},
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
