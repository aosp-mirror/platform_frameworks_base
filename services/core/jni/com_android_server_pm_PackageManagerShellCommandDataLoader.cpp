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

#define ATRACE_TAG ATRACE_TAG_ADB
#define LOG_TAG "PackageManagerShellCommandDataLoader-jni"
#include <android-base/file.h>
#include <android-base/logging.h>
#include <android-base/no_destructor.h>
#include <android-base/stringprintf.h>
#include <android-base/unique_fd.h>
#include <core_jni_helpers.h>
#include <cutils/multiuser.h>
#include <cutils/trace.h>
#include <endian.h>
#include <nativehelper/JNIHelp.h>
#include <sys/eventfd.h>
#include <sys/poll.h>

#include <charconv>
#include <chrono>
#include <span>
#include <string>
#include <thread>
#include <unordered_map>
#include <unordered_set>

#include "dataloader.h"

// #define VERBOSE_READ_LOGS

namespace android {

namespace {

using android::base::borrowed_fd;
using android::base::unique_fd;

using namespace std::literals;

using BlockSize = int16_t;
using FileIdx = int16_t;
using BlockIdx = int32_t;
using NumBlocks = int32_t;
using BlockType = int8_t;
using CompressionType = int8_t;
using RequestType = int16_t;
using MagicType = uint32_t;

static constexpr int BUFFER_SIZE = 256 * 1024;
static constexpr int BLOCKS_COUNT = BUFFER_SIZE / INCFS_DATA_FILE_BLOCK_SIZE;

static constexpr int COMMAND_SIZE = 4 + 2 + 2 + 4; // bytes
static constexpr int HEADER_SIZE = 2 + 1 + 1 + 4 + 2; // bytes
static constexpr std::string_view OKAY = "OKAY"sv;
static constexpr MagicType INCR = 0x52434e49; // BE INCR

static constexpr auto PollTimeoutMs = 5000;
static constexpr auto TraceTagCheckInterval = 1s;

static constexpr auto WaitOnEofMinInterval = 10ms;
static constexpr auto WaitOnEofMaxInterval = 1s;

struct JniIds {
    jclass packageManagerShellCommandDataLoader;
    jmethodID pmscdLookupShellCommand;
    jmethodID pmscdGetStdIn;
    jmethodID pmscdGetLocalFile;

    JniIds(JNIEnv* env) {
        packageManagerShellCommandDataLoader = (jclass)env->NewGlobalRef(
                FindClassOrDie(env, "com/android/server/pm/PackageManagerShellCommandDataLoader"));
        pmscdLookupShellCommand =
                GetStaticMethodIDOrDie(env, packageManagerShellCommandDataLoader,
                                       "lookupShellCommand",
                                       "(Ljava/lang/String;)Landroid/os/ShellCommand;");
        pmscdGetStdIn = GetStaticMethodIDOrDie(env, packageManagerShellCommandDataLoader,
                                               "getStdIn", "(Landroid/os/ShellCommand;)I");
        pmscdGetLocalFile =
                GetStaticMethodIDOrDie(env, packageManagerShellCommandDataLoader, "getLocalFile",
                                       "(Landroid/os/ShellCommand;Ljava/lang/String;)I");
    }
};

const JniIds& jniIds(JNIEnv* env) {
    static const JniIds ids(env);
    return ids;
}

struct BlockHeader {
    FileIdx fileIdx = -1;
    BlockType blockType = -1;
    CompressionType compressionType = -1;
    BlockIdx blockIdx = -1;
    BlockSize blockSize = -1;
} __attribute__((packed));

static_assert(sizeof(BlockHeader) == HEADER_SIZE);

static constexpr RequestType EXIT = 0;
static constexpr RequestType BLOCK_MISSING = 1;
static constexpr RequestType PREFETCH = 2;

struct RequestCommand {
    MagicType magic;
    RequestType requestType;
    FileIdx fileIdx;
    BlockIdx blockIdx;
} __attribute__((packed));

static_assert(COMMAND_SIZE == sizeof(RequestCommand));

static bool sendRequest(int fd, RequestType requestType, FileIdx fileIdx = -1,
                        BlockIdx blockIdx = -1) {
    const RequestCommand command{.magic = INCR,
                                 .requestType = static_cast<int16_t>(be16toh(requestType)),
                                 .fileIdx = static_cast<int16_t>(be16toh(fileIdx)),
                                 .blockIdx = static_cast<int32_t>(be32toh(blockIdx))};
    return android::base::WriteFully(fd, &command, sizeof(command));
}

static bool readChunk(int fd, std::vector<uint8_t>& data) {
    int32_t size;
    if (!android::base::ReadFully(fd, &size, sizeof(size))) {
        return false;
    }
    size = int32_t(be32toh(size));
    if (size <= 0) {
        return false;
    }
    data.resize(size);
    return android::base::ReadFully(fd, data.data(), data.size());
}

BlockHeader readHeader(std::span<uint8_t>& data);

static inline int32_t readLEInt32(borrowed_fd fd) {
    int32_t result;
    ReadFully(fd, &result, sizeof(result));
    result = int32_t(le32toh(result));
    return result;
}

static inline bool skipBytes(borrowed_fd fd, int* max_size) {
    int32_t size = std::min(readLEInt32(fd), *max_size);
    if (size <= 0) {
        return false;
    }
    *max_size -= size;
    return (TEMP_FAILURE_RETRY(lseek64(fd.get(), size, SEEK_CUR)) >= 0);
}

static inline int32_t skipIdSigHeaders(borrowed_fd fd) {
    // version
    auto version = readLEInt32(fd);
    int max_size = INCFS_MAX_SIGNATURE_SIZE - sizeof(version);
    // hashingInfo and signingInfo
    if (!skipBytes(fd, &max_size) || !skipBytes(fd, &max_size)) {
        return -1;
    }
    return readLEInt32(fd); // size of the verity tree
}

static inline IncFsSize verityTreeSizeForFile(IncFsSize fileSize) {
    constexpr int SHA256_DIGEST_SIZE = 32;
    constexpr int digest_size = SHA256_DIGEST_SIZE;
    constexpr int hash_per_block = INCFS_DATA_FILE_BLOCK_SIZE / digest_size;

    IncFsSize total_tree_block_count = 0;

    auto block_count = 1 + (fileSize - 1) / INCFS_DATA_FILE_BLOCK_SIZE;
    auto hash_block_count = block_count;
    for (auto i = 0; hash_block_count > 1; i++) {
        hash_block_count = (hash_block_count + hash_per_block - 1) / hash_per_block;
        total_tree_block_count += hash_block_count;
    }
    return total_tree_block_count * INCFS_DATA_FILE_BLOCK_SIZE;
}

enum MetadataMode : int8_t {
    STDIN = 0,
    LOCAL_FILE = 1,
    DATA_ONLY_STREAMING = 2,
    STREAMING = 3,
};

struct InputDesc {
    unique_fd fd;
    IncFsSize size;
    IncFsBlockKind kind = INCFS_BLOCK_KIND_DATA;
    bool waitOnEof = false;
    bool streaming = false;
    MetadataMode mode = STDIN;
};
using InputDescs = std::vector<InputDesc>;

template <class T>
std::optional<T> read(IncFsSpan& data) {
    if (data.size < (int32_t)sizeof(T)) {
        return {};
    }
    T res;
    memcpy(&res, data.data, sizeof(res));
    data.data += sizeof(res);
    data.size -= sizeof(res);
    return res;
}

static inline unique_fd openLocalFile(JNIEnv* env, const JniIds& jni, jobject shellCommand,
                                      const std::string& path) {
    if (shellCommand) {
        return unique_fd{env->CallStaticIntMethod(jni.packageManagerShellCommandDataLoader,
                                                  jni.pmscdGetLocalFile, shellCommand,
                                                  env->NewStringUTF(path.c_str()))};
    }
    auto fd = unique_fd(::open(path.c_str(), O_RDONLY | O_CLOEXEC));
    if (!fd.ok()) {
        PLOG(ERROR) << "Failed to open file: " << path << ", error code: " << fd.get();
    }
    return fd;
}

static inline InputDescs openLocalFile(JNIEnv* env, const JniIds& jni, jobject shellCommand,
                                       IncFsSize size, const std::string& filePath) {
    InputDescs result;
    result.reserve(2);

    const std::string idsigPath = filePath + ".idsig";

    unique_fd idsigFd = openLocalFile(env, jni, shellCommand, idsigPath);
    if (idsigFd.ok()) {
        auto actualTreeSize = skipIdSigHeaders(idsigFd);
        if (actualTreeSize < 0) {
            ALOGE("Error reading .idsig file: wrong format.");
            return {};
        }
        auto treeSize = verityTreeSizeForFile(size);
        if (treeSize != actualTreeSize) {
            ALOGE("Verity tree size mismatch: %d vs .idsig: %d.", int(treeSize),
                  int(actualTreeSize));
            return {};
        }
        result.push_back(InputDesc{
                .fd = std::move(idsigFd),
                .size = treeSize,
                .kind = INCFS_BLOCK_KIND_HASH,
        });
    }

    unique_fd fileFd = openLocalFile(env, jni, shellCommand, filePath);
    if (fileFd.ok()) {
        result.push_back(InputDesc{
                .fd = std::move(fileFd),
                .size = size,
        });
    }

    return result;
}

static inline InputDescs openInputs(JNIEnv* env, const JniIds& jni, jobject shellCommand,
                                    IncFsSize size, IncFsSpan metadata) {
    auto mode = read<int8_t>(metadata).value_or(STDIN);
    if (mode == LOCAL_FILE) {
        // local file and possibly signature
        auto dataSize = le32toh(read<int32_t>(metadata).value_or(0));
        return openLocalFile(env, jni, shellCommand, size, std::string(metadata.data, dataSize));
    }

    if (!shellCommand) {
        ALOGE("Missing shell command.");
        return {};
    }

    unique_fd fd{env->CallStaticIntMethod(jni.packageManagerShellCommandDataLoader,
                                          jni.pmscdGetStdIn, shellCommand)};
    if (!fd.ok()) {
        return {};
    }

    InputDescs result;
    switch (mode) {
        case STDIN: {
            result.push_back(InputDesc{
                    .fd = std::move(fd),
                    .size = size,
                    .waitOnEof = true,
            });
            break;
        }
        case DATA_ONLY_STREAMING: {
            // verity tree from stdin, rest is streaming
            auto treeSize = verityTreeSizeForFile(size);
            result.push_back(InputDesc{
                    .fd = std::move(fd),
                    .size = treeSize,
                    .kind = INCFS_BLOCK_KIND_HASH,
                    .waitOnEof = true,
                    .streaming = true,
                    .mode = DATA_ONLY_STREAMING,
            });
            break;
        }
        case STREAMING: {
            result.push_back(InputDesc{
                    .fd = std::move(fd),
                    .size = 0,
                    .streaming = true,
                    .mode = STREAMING,
            });
            break;
        }
    }
    return result;
}

class PMSCDataLoader;

struct OnTraceChanged {
    OnTraceChanged();
    ~OnTraceChanged() {
        mRunning = false;
        mChecker.join();
    }

    void registerCallback(PMSCDataLoader* callback) {
        std::unique_lock lock(mMutex);
        mCallbacks.insert(callback);
    }

    void unregisterCallback(PMSCDataLoader* callback) {
        std::unique_lock lock(mMutex);
        mCallbacks.erase(callback);
    }

private:
    std::mutex mMutex;
    std::unordered_set<PMSCDataLoader*> mCallbacks;
    std::atomic<bool> mRunning{true};
    std::thread mChecker;
};

static OnTraceChanged& onTraceChanged() {
    static android::base::NoDestructor<OnTraceChanged> instance;
    return *instance;
}

class PMSCDataLoader : public android::dataloader::DataLoader {
public:
    PMSCDataLoader(JavaVM* jvm) : mJvm(jvm) { CHECK(mJvm); }
    ~PMSCDataLoader() {
        onTraceChanged().unregisterCallback(this);
        if (mReceiverThread.joinable()) {
            mReceiverThread.join();
        }
    }

    void updateReadLogsState(const bool enabled) {
        if (enabled != mReadLogsEnabled.exchange(enabled)) {
            mIfs->setParams({.readLogsEnabled = enabled});
        }
    }

private:
    // Bitmask of supported features.
    DataLoaderFeatures getFeatures() const final { return DATA_LOADER_FEATURE_UID; }

    // Lifecycle.
    bool onCreate(const android::dataloader::DataLoaderParams& params,
                  android::dataloader::FilesystemConnectorPtr ifs,
                  android::dataloader::StatusListenerPtr statusListener,
                  android::dataloader::ServiceConnectorPtr,
                  android::dataloader::ServiceParamsPtr) final {
        CHECK(ifs) << "ifs can't be null";
        CHECK(statusListener) << "statusListener can't be null";
        mArgs = params.arguments();
        mIfs = ifs;
        mStatusListener = statusListener;
        updateReadLogsState(atrace_is_tag_enabled(ATRACE_TAG));
        onTraceChanged().registerCallback(this);
        return true;
    }
    bool onStart() final { return true; }
    void onStop() final {
        mStopReceiving = true;
        eventfd_write(mEventFd, 1);
        if (mReceiverThread.joinable()) {
            mReceiverThread.join();
        }
    }
    void onDestroy() final {}

    // Installation.
    bool onPrepareImage(dataloader::DataLoaderInstallationFiles addedFiles) final {
        ALOGE("onPrepareImage: start.");

        JNIEnv* env = GetOrAttachJNIEnvironment(mJvm, JNI_VERSION_1_6);
        const auto& jni = jniIds(env);

        jobject shellCommand = env->CallStaticObjectMethod(jni.packageManagerShellCommandDataLoader,
                                                           jni.pmscdLookupShellCommand,
                                                           env->NewStringUTF(mArgs.c_str()));

        std::vector<char> buffer;
        buffer.reserve(BUFFER_SIZE);

        std::vector<IncFsDataBlock> blocks;
        blocks.reserve(BLOCKS_COUNT);

        unique_fd streamingFd;
        MetadataMode streamingMode;
        for (auto&& file : addedFiles) {
            auto inputs = openInputs(env, jni, shellCommand, file.size, file.metadata);
            if (inputs.empty()) {
                ALOGE("Failed to open an input file for metadata: %.*s, final file name is: %s. "
                      "Error %d",
                      int(file.metadata.size), file.metadata.data, file.name, errno);
                return false;
            }

            const auto fileId = IncFs_FileIdFromMetadata(file.metadata);
            const base::unique_fd incfsFd(mIfs->openForSpecialOps(fileId).release());
            if (incfsFd < 0) {
                ALOGE("Failed to open an IncFS file for metadata: %.*s, final file name is: %s. "
                      "Error %d",
                      int(file.metadata.size), file.metadata.data, file.name, errno);
                return false;
            }

            for (auto&& input : inputs) {
                if (input.streaming && !streamingFd.ok()) {
                    streamingFd.reset(dup(input.fd));
                    streamingMode = input.mode;
                }
                if (!copyToIncFs(incfsFd, input.size, input.kind, input.fd, input.waitOnEof,
                                 &buffer, &blocks)) {
                    ALOGE("Failed to copy data to IncFS file for metadata: %.*s, final file name "
                          "is: %s. "
                          "Error %d",
                          int(file.metadata.size), file.metadata.data, file.name, errno);
                    return false;
                }
            }
        }

        if (streamingFd.ok()) {
            ALOGE("onPrepareImage: done, proceeding to streaming.");
            return initStreaming(std::move(streamingFd), streamingMode);
        }

        ALOGE("onPrepareImage: done.");
        return true;
    }

    bool copyToIncFs(borrowed_fd incfsFd, IncFsSize size, IncFsBlockKind kind,
                     borrowed_fd incomingFd, bool waitOnEof, std::vector<char>* buffer,
                     std::vector<IncFsDataBlock>* blocks) {
        IncFsSize remaining = size;
        IncFsSize totalSize = 0;
        IncFsBlockIndex blockIdx = 0;
        while (remaining > 0) {
            constexpr auto capacity = BUFFER_SIZE;
            auto size = buffer->size();
            if (capacity - size < INCFS_DATA_FILE_BLOCK_SIZE) {
                if (!flashToIncFs(incfsFd, kind, false, &blockIdx, buffer, blocks)) {
                    return false;
                }
                continue;
            }

            auto toRead = std::min<IncFsSize>(remaining, capacity - size);
            buffer->resize(size + toRead);
            auto read = ::read(incomingFd.get(), buffer->data() + size, toRead);
            if (read == 0) {
                if (waitOnEof) {
                    // eof of stdin, waiting...
                    if (doWaitOnEof()) {
                        continue;
                    } else {
                        return false;
                    }
                }
                break;
            }
            resetWaitOnEof();

            if (read < 0) {
                return false;
            }

            buffer->resize(size + read);
            remaining -= read;
            totalSize += read;
        }
        if (!buffer->empty() && !flashToIncFs(incfsFd, kind, true, &blockIdx, buffer, blocks)) {
            return false;
        }
        return true;
    }

    bool flashToIncFs(borrowed_fd incfsFd, IncFsBlockKind kind, bool eof, IncFsBlockIndex* blockIdx,
                      std::vector<char>* buffer, std::vector<IncFsDataBlock>* blocks) {
        int consumed = 0;
        const auto fullBlocks = buffer->size() / INCFS_DATA_FILE_BLOCK_SIZE;
        for (int i = 0; i < fullBlocks; ++i) {
            const auto inst = IncFsDataBlock{
                    .fileFd = incfsFd.get(),
                    .pageIndex = (*blockIdx)++,
                    .compression = INCFS_COMPRESSION_KIND_NONE,
                    .kind = kind,
                    .dataSize = INCFS_DATA_FILE_BLOCK_SIZE,
                    .data = buffer->data() + consumed,
            };
            blocks->push_back(inst);
            consumed += INCFS_DATA_FILE_BLOCK_SIZE;
        }
        const auto remain = buffer->size() - fullBlocks * INCFS_DATA_FILE_BLOCK_SIZE;
        if (remain && eof) {
            const auto inst = IncFsDataBlock{
                    .fileFd = incfsFd.get(),
                    .pageIndex = (*blockIdx)++,
                    .compression = INCFS_COMPRESSION_KIND_NONE,
                    .kind = kind,
                    .dataSize = static_cast<uint16_t>(remain),
                    .data = buffer->data() + consumed,
            };
            blocks->push_back(inst);
            consumed += remain;
        }

        auto res = mIfs->writeBlocks({blocks->data(), blocks->size()});

        blocks->clear();
        buffer->erase(buffer->begin(), buffer->begin() + consumed);

        if (res < 0) {
            ALOGE("Failed to write block to IncFS: %d", int(res));
            return false;
        }
        return true;
    }

    enum class WaitResult {
        DataAvailable,
        Timeout,
        Failure,
        StopRequested,
    };

    WaitResult waitForData(int fd) {
        using Clock = std::chrono::steady_clock;
        using Milliseconds = std::chrono::milliseconds;

        auto pollTimeoutMs = PollTimeoutMs;
        const auto waitEnd = Clock::now() + Milliseconds(pollTimeoutMs);
        while (!mStopReceiving) {
            struct pollfd pfds[2] = {{fd, POLLIN, 0}, {mEventFd, POLLIN, 0}};
            // Wait until either data is ready or stop signal is received
            int res = poll(pfds, std::size(pfds), pollTimeoutMs);

            if (res < 0) {
                if (errno == EINTR) {
                    pollTimeoutMs = std::chrono::duration_cast<Milliseconds>(waitEnd - Clock::now())
                                            .count();
                    if (pollTimeoutMs < 0) {
                        return WaitResult::Timeout;
                    }
                    continue;
                }
                ALOGE("Failed to poll. Error %d", errno);
                return WaitResult::Failure;
            }

            if (res == 0) {
                return WaitResult::Timeout;
            }

            // First check if there is a stop signal
            if (pfds[1].revents == POLLIN) {
                ALOGE("DataLoader requested to stop.");
                return WaitResult::StopRequested;
            }
            // Otherwise check if incoming data is ready
            if (pfds[0].revents == POLLIN) {
                return WaitResult::DataAvailable;
            }

            // Invalid case, just fail.
            ALOGE("Failed to poll. Result %d", res);
            return WaitResult::Failure;
        }

        ALOGE("DataLoader requested to stop.");
        return WaitResult::StopRequested;
    }

    // Streaming.
    bool initStreaming(unique_fd inout, MetadataMode mode) {
        mEventFd.reset(eventfd(0, EFD_CLOEXEC));
        if (mEventFd < 0) {
            ALOGE("Failed to create eventfd.");
            return false;
        }

        // Awaiting adb handshake.
        if (waitForData(inout) != WaitResult::DataAvailable) {
            ALOGE("Failure waiting for the handshake.");
            return false;
        }

        char okay_buf[OKAY.size()];
        if (!android::base::ReadFully(inout, okay_buf, OKAY.size())) {
            ALOGE("Failed to receive OKAY. Abort. Error %d", errno);
            return false;
        }
        if (std::string_view(okay_buf, OKAY.size()) != OKAY) {
            ALOGE("Received '%.*s', expecting '%.*s'", (int)OKAY.size(), okay_buf, (int)OKAY.size(),
                  OKAY.data());
            return false;
        }

        {
            std::lock_guard lock{mOutFdLock};
            mOutFd.reset(::dup(inout));
            if (mOutFd < 0) {
                ALOGE("Failed to create streaming fd.");
            }
        }

        if (mStopReceiving) {
            ALOGE("DataLoader requested to stop.");
            return false;
        }

        mReceiverThread = std::thread(
                [this, io = std::move(inout), mode]() mutable { receiver(std::move(io), mode); });

        ALOGI("Started streaming...");
        return true;
    }

    // IFS callbacks.
    void onPendingReads(dataloader::PendingReads pendingReads) final {}
    void onPageReads(dataloader::PageReads pageReads) final {}

    void onPendingReadsWithUid(dataloader::PendingReadsWithUid pendingReads) final {
        std::lock_guard lock{mOutFdLock};
        if (mOutFd < 0) {
            return;
        }
        CHECK(mIfs);
        for (auto&& pendingRead : pendingReads) {
            const android::dataloader::FileId& fileId = pendingRead.id;
            const auto blockIdx = static_cast<BlockIdx>(pendingRead.block);
            /*
            ALOGI("Missing: %d", (int) blockIdx);
            */
            FileIdx fileIdx = convertFileIdToFileIndex(fileId);
            if (fileIdx < 0) {
                ALOGE("Failed to handle event for fileid=%s. Ignore.",
                      android::incfs::toString(fileId).c_str());
                continue;
            }
            if (mRequestedFiles.insert(fileIdx).second &&
                !sendRequest(mOutFd, PREFETCH, fileIdx, blockIdx)) {
                mRequestedFiles.erase(fileIdx);
            }
            sendRequest(mOutFd, BLOCK_MISSING, fileIdx, blockIdx);
        }
    }

    // Read tracing.
    struct TracedRead {
        uint64_t timestampUs;
        android::dataloader::FileId fileId;
        android::dataloader::Uid uid;
        uint32_t firstBlockIdx;
        uint32_t count;
    };

    void onPageReadsWithUid(dataloader::PageReadsWithUid pageReads) final {
        if (!pageReads.size()) {
            return;
        }

        auto trace = atrace_is_tag_enabled(ATRACE_TAG);
        if (CC_LIKELY(!trace)) {
            return;
        }

        TracedRead last = {};
        auto lastSerialNo = mLastSerialNo < 0 ? pageReads[0].serialNo : mLastSerialNo;
        for (auto&& read : pageReads) {
            const auto expectedSerialNo = lastSerialNo + last.count;
#ifdef VERBOSE_READ_LOGS
            {
                FileIdx fileIdx = convertFileIdToFileIndex(read.id);

                auto appId = multiuser_get_app_id(read.uid);
                auto userId = multiuser_get_user_id(read.uid);
                auto trace = android::base::
                        StringPrintf("verbose_page_read: serialNo=%lld (expected=%lld) index=%lld "
                                     "file=%d appid=%d userid=%d",
                                     static_cast<long long>(read.serialNo),
                                     static_cast<long long>(expectedSerialNo),
                                     static_cast<long long>(read.block), static_cast<int>(fileIdx),
                                     static_cast<int>(appId), static_cast<int>(userId));

                ATRACE_BEGIN(trace.c_str());
                ATRACE_END();
            }
#endif // VERBOSE_READ_LOGS

            if (read.serialNo == expectedSerialNo && read.id == last.fileId &&
                read.uid == last.uid && read.block == last.firstBlockIdx + last.count) {
                ++last.count;
                continue;
            }

            // First, trace the reads.
            traceRead(last);

            // Second, report missing reads, if any.
            if (read.serialNo != expectedSerialNo) {
                traceMissingReads(expectedSerialNo, read.serialNo);
            }

            last = TracedRead{
                    .timestampUs = read.bootClockTsUs,
                    .fileId = read.id,
                    .uid = read.uid,
                    .firstBlockIdx = (uint32_t)read.block,
                    .count = 1,
            };
            lastSerialNo = read.serialNo;
        }

        traceRead(last);
        mLastSerialNo = lastSerialNo + last.count;
    }

    void traceRead(const TracedRead& read) {
        if (!read.count) {
            return;
        }

        FileIdx fileIdx = convertFileIdToFileIndex(read.fileId);

        std::string trace;
        if (read.uid != kIncFsNoUid) {
            auto appId = multiuser_get_app_id(read.uid);
            auto userId = multiuser_get_user_id(read.uid);
            trace = android::base::
                    StringPrintf("page_read: index=%lld count=%lld file=%d appid=%d userid=%d",
                                 static_cast<long long>(read.firstBlockIdx),
                                 static_cast<long long>(read.count), static_cast<int>(fileIdx),
                                 static_cast<int>(appId), static_cast<int>(userId));
        } else {
            trace = android::base::StringPrintf("page_read: index=%lld count=%lld file=%d",
                                                static_cast<long long>(read.firstBlockIdx),
                                                static_cast<long long>(read.count),
                                                static_cast<int>(fileIdx));
        }

        ATRACE_BEGIN(trace.c_str());
        ATRACE_END();
    }

    void traceMissingReads(int64_t expectedSerialNo, int64_t readSerialNo) {
        const auto readsMissing = readSerialNo - expectedSerialNo;
        const auto trace =
                android::base::StringPrintf("missing_page_reads: count=%lld, range [%lld,%lld)",
                                            static_cast<long long>(readsMissing),
                                            static_cast<long long>(expectedSerialNo),
                                            static_cast<long long>(readSerialNo));
        ATRACE_BEGIN(trace.c_str());
        ATRACE_END();
    }

    void receiver(unique_fd inout, MetadataMode mode) {
        std::vector<uint8_t> data;
        std::vector<IncFsDataBlock> instructions;
        std::unordered_map<FileIdx, unique_fd> writeFds;
        while (!mStopReceiving) {
            const auto res = waitForData(inout);
            if (res == WaitResult::Timeout) {
                continue;
            }
            if (res == WaitResult::Failure) {
                mStatusListener->reportStatus(DATA_LOADER_UNRECOVERABLE);
                break;
            }
            if (res == WaitResult::StopRequested) {
                ALOGE("Sending EXIT to server.");
                sendRequest(inout, EXIT);
                break;
            }
            if (!readChunk(inout, data)) {
                ALOGE("Failed to read a message. Abort.");
                mStatusListener->reportStatus(DATA_LOADER_UNRECOVERABLE);
                break;
            }
            auto remainingData = std::span(data);
            while (!remainingData.empty()) {
                auto header = readHeader(remainingData);
                if (header.fileIdx == -1 && header.blockType == 0 && header.compressionType == 0 &&
                    header.blockIdx == 0 && header.blockSize == 0) {
                    ALOGI("Stop command received. Sending exit command (remaining bytes: %d).",
                          int(remainingData.size()));

                    sendRequest(inout, EXIT);
                    mStopReceiving = true;
                    break;
                }
                if (header.fileIdx < 0 || header.blockSize <= 0 || header.blockType < 0 ||
                    header.compressionType < 0 || header.blockIdx < 0) {
                    ALOGE("Invalid header received. Abort.");
                    mStopReceiving = true;
                    break;
                }

                const FileIdx fileIdx = header.fileIdx;
                const android::dataloader::FileId fileId = convertFileIndexToFileId(mode, fileIdx);
                if (!android::incfs::isValidFileId(fileId)) {
                    ALOGE("Unknown data destination for file ID %d. Ignore.", header.fileIdx);
                    continue;
                }

                auto& writeFd = writeFds[fileIdx];
                if (writeFd < 0) {
                    writeFd.reset(this->mIfs->openForSpecialOps(fileId).release());
                    if (writeFd < 0) {
                        ALOGE("Failed to open file %d for writing (%d). Abort.", header.fileIdx,
                              -writeFd);
                        break;
                    }
                }

                const auto inst = IncFsDataBlock{
                        .fileFd = writeFd,
                        .pageIndex = static_cast<IncFsBlockIndex>(header.blockIdx),
                        .compression = static_cast<IncFsCompressionKind>(header.compressionType),
                        .kind = static_cast<IncFsBlockKind>(header.blockType),
                        .dataSize = static_cast<uint16_t>(header.blockSize),
                        .data = (const char*)remainingData.data(),
                };
                instructions.push_back(inst);
                remainingData = remainingData.subspan(header.blockSize);
            }
            writeInstructions(instructions);
        }
        writeInstructions(instructions);

        {
            std::lock_guard lock{mOutFdLock};
            mOutFd.reset();
        }
    }

    void writeInstructions(std::vector<IncFsDataBlock>& instructions) {
        auto res = this->mIfs->writeBlocks(instructions);
        if (res != instructions.size()) {
            ALOGE("Dailed to write data to Incfs (res=%d when expecting %d)", res,
                  int(instructions.size()));
        }
        instructions.clear();
    }

    FileIdx convertFileIdToFileIndex(android::dataloader::FileId fileId) {
        // FileId has format '\2FileIdx'.
        const char* meta = (const char*)&fileId;

        int8_t mode = *meta;
        if (mode != DATA_ONLY_STREAMING && mode != STREAMING) {
            return -1;
        }

        int fileIdx;
        auto res = std::from_chars(meta + 1, meta + sizeof(fileId), fileIdx);
        if (res.ec != std::errc{} || fileIdx < std::numeric_limits<FileIdx>::min() ||
            fileIdx > std::numeric_limits<FileIdx>::max()) {
            return -1;
        }

        return FileIdx(fileIdx);
    }

    android::dataloader::FileId convertFileIndexToFileId(MetadataMode mode, FileIdx fileIdx) {
        IncFsFileId fileId = {};
        char* meta = (char*)&fileId;
        *meta = mode;
        if (auto [p, ec] = std::to_chars(meta + 1, meta + sizeof(fileId), fileIdx);
            ec != std::errc()) {
            return {};
        }
        return fileId;
    }

    // Waiting with exponential backoff, maximum total time ~1.2sec.
    bool doWaitOnEof() {
        if (mWaitOnEofInterval >= WaitOnEofMaxInterval) {
            resetWaitOnEof();
            return false;
        }
        auto result = mWaitOnEofInterval;
        mWaitOnEofInterval =
                std::min<std::chrono::milliseconds>(mWaitOnEofInterval * 2, WaitOnEofMaxInterval);
        std::this_thread::sleep_for(result);
        return true;
    }

    void resetWaitOnEof() { mWaitOnEofInterval = WaitOnEofMinInterval; }

    JavaVM* const mJvm;
    std::string mArgs;
    android::dataloader::FilesystemConnectorPtr mIfs = nullptr;
    android::dataloader::StatusListenerPtr mStatusListener = nullptr;
    std::mutex mOutFdLock;
    android::base::unique_fd mOutFd;
    android::base::unique_fd mEventFd;
    std::thread mReceiverThread;
    std::atomic<bool> mStopReceiving = false;
    std::atomic<bool> mReadLogsEnabled = false;
    std::chrono::milliseconds mWaitOnEofInterval{WaitOnEofMinInterval};
    int64_t mLastSerialNo{-1};
    /** Tracks which files have been requested */
    std::unordered_set<FileIdx> mRequestedFiles;
};

OnTraceChanged::OnTraceChanged() {
    mChecker = std::thread([this]() {
        bool oldTrace = atrace_is_tag_enabled(ATRACE_TAG);
        while (mRunning) {
            bool newTrace = atrace_is_tag_enabled(ATRACE_TAG);
            if (oldTrace != newTrace) {
                std::unique_lock lock(mMutex);
                for (auto&& callback : mCallbacks) {
                    callback->updateReadLogsState(newTrace);
                }
            }
            oldTrace = newTrace;
            std::this_thread::sleep_for(TraceTagCheckInterval);
        }
    });
}

BlockHeader readHeader(std::span<uint8_t>& data) {
    BlockHeader header;
    if (data.size() < sizeof(header)) {
        return header;
    }

    header.fileIdx = static_cast<FileIdx>(be16toh(*reinterpret_cast<const uint16_t*>(&data[0])));
    header.blockType = static_cast<BlockType>(data[2]);
    header.compressionType = static_cast<CompressionType>(data[3]);
    header.blockIdx = static_cast<BlockIdx>(be32toh(*reinterpret_cast<const uint32_t*>(&data[4])));
    header.blockSize =
            static_cast<BlockSize>(be16toh(*reinterpret_cast<const uint16_t*>(&data[8])));
    data = data.subspan(sizeof(header));

    return header;
}

static void nativeInitialize(JNIEnv* env, jclass klass) {
    jniIds(env);
}

static const JNINativeMethod method_table[] = {
        {"nativeInitialize", "()V", (void*)nativeInitialize},
};

} // namespace

int register_android_server_com_android_server_pm_PackageManagerShellCommandDataLoader(
        JNIEnv* env) {
    android::dataloader::DataLoader::initialize(
            [](auto jvm, const auto& params) -> android::dataloader::DataLoaderPtr {
                if (params.type() == DATA_LOADER_TYPE_INCREMENTAL) {
                    // This DataLoader only supports incremental installations.
                    return std::make_unique<PMSCDataLoader>(jvm);
                }
                return {};
            });
    return jniRegisterNativeMethods(env,
                                    "com/android/server/pm/PackageManagerShellCommandDataLoader",
                                    method_table, NELEM(method_table));
}

} // namespace android
