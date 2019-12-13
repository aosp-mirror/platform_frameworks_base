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

#define ATRACE_TAG ATRACE_TAG_ADB
#define LOG_TAG "NativeAdbDataLoaderService"

#include <android-base/file.h>
#include <android-base/logging.h>
#include <android-base/properties.h>
#include <android-base/stringprintf.h>
#include <android-base/thread_annotations.h>
#include <android-base/unique_fd.h>
#include <cutils/trace.h>
#include <fcntl.h>
#include <sys/eventfd.h>
#include <sys/poll.h>
#include <sys/stat.h>
#include <unistd.h>
#include <utils/Log.h>

#include <charconv>
#include <string>
#include <thread>
#include <type_traits>
#include <unordered_map>
#include <unordered_set>

#include "dataloader.h"

#ifndef _WIN32
#include <endian.h>
#include <sys/stat.h>
#include <unistd.h>
#else
#define be32toh(x) _byteswap_ulong(x)
#define be16toh(x) _byteswap_ushort(x)
#endif

namespace {

using android::base::unique_fd;

using namespace std::literals;

using BlockSize = int16_t;
using FileId = int16_t;
using BlockIdx = int32_t;
using NumBlocks = int32_t;
using CompressionType = int16_t;
using RequestType = int16_t;

static constexpr int COMMAND_SIZE = 2 + 2 + 4;     // bytes
static constexpr int HEADER_SIZE = 2 + 2 + 4 + 2;  // bytes
static constexpr std::string_view OKAY = "OKAY"sv;

static constexpr auto PollTimeoutMs = 5000;

static constexpr auto ReadLogBufferSize = 128 * 1024 * 1024;
static constexpr auto ReadLogMaxEntrySize = 128;

struct BlockHeader {
    FileId fileId = -1;
    CompressionType compressionType = -1;
    BlockIdx blockIdx = -1;
    BlockSize blockSize = -1;
} __attribute__((packed));

static_assert(sizeof(BlockHeader) == HEADER_SIZE);

static constexpr RequestType EXIT = 0;
static constexpr RequestType BLOCK_MISSING = 1;
static constexpr RequestType PREFETCH = 2;

struct RequestCommand {
    RequestType requestType;
    FileId fileId;
    BlockIdx blockIdx;
} __attribute__((packed));

static_assert(COMMAND_SIZE == sizeof(RequestCommand));

static bool sendRequest(int fd,
                        RequestType requestType,
                        FileId fileId = -1,
                        BlockIdx blockIdx = -1) {
    const RequestCommand command{
            .requestType = static_cast<int16_t>(be16toh(requestType)),
            .fileId = static_cast<int16_t>(be16toh(fileId)),
            .blockIdx = static_cast<int32_t>(be32toh(blockIdx))};
    return android::base::WriteFully(fd, &command, sizeof(command));
}

static int waitForDataOrSignal(int fd, int event_fd) {
    struct pollfd pfds[2] = {{fd, POLLIN, 0}, {event_fd, POLLIN, 0}};
    // Wait indefinitely until either data is ready or stop signal is received
    int res = poll(pfds, 2, PollTimeoutMs);
    if (res <= 0) {
        return res;
    }
    // First check if there is a stop signal
    if (pfds[1].revents == POLLIN) {
        return event_fd;
    }
    // Otherwise check if incoming data is ready
    if (pfds[0].revents == POLLIN) {
        return fd;
    }
    return -1;
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

static BlockHeader readHeader(std::span<uint8_t>& data) {
    BlockHeader header;
    if (data.size() < sizeof(header)) {
        return header;
    }

    header.fileId = static_cast<FileId>(
            be16toh(*reinterpret_cast<uint16_t*>(&data[0])));
    header.compressionType = static_cast<CompressionType>(
            be16toh(*reinterpret_cast<uint16_t*>(&data[2])));
    header.blockIdx = static_cast<BlockIdx>(
            be32toh(*reinterpret_cast<uint32_t*>(&data[4])));
    header.blockSize = static_cast<BlockSize>(
            be16toh(*reinterpret_cast<uint16_t*>(&data[8])));
    data = data.subspan(sizeof(header));

    return header;
}

static std::string extractPackageName(const std::string& staticArgs) {
    static constexpr auto kPrefix = "package="sv;
    static constexpr auto kSuffix = "&"sv;

    const auto startPos = staticArgs.find(kPrefix);
    if (startPos == staticArgs.npos || startPos + kPrefix.size() >= staticArgs.size()) {
        return {};
    }
    const auto endPos = staticArgs.find(kSuffix, startPos + kPrefix.size());
    return staticArgs.substr(startPos + kPrefix.size(),
                            endPos == staticArgs.npos ? staticArgs.npos
                                                     : (endPos - (startPos + kPrefix.size())));
}

class AdbDataLoader : public android::dataloader::DataLoader {
private:
    // Lifecycle.
    bool onCreate(const android::dataloader::DataLoaderParams& params,
                  android::dataloader::FilesystemConnectorPtr ifs,
                  android::dataloader::StatusListenerPtr statusListener,
                  android::dataloader::ServiceConnectorPtr,
                  android::dataloader::ServiceParamsPtr) final {
        CHECK(ifs) << "ifs can't be null";
        CHECK(statusListener) << "statusListener can't be null";
        ALOGE("[AdbDataLoader] onCreate: %s/%s/%d", params.staticArgs().c_str(),
              params.packageName().c_str(), (int)params.dynamicArgs().size());

        if (params.dynamicArgs().empty()) {
            ALOGE("[AdbDataLoader] Invalid DataLoaderParams. Need in/out FDs.");
            return false;
        }
        for (auto const& namedFd : params.dynamicArgs()) {
            if (namedFd.name == "inFd") {
                mInFd.reset(dup(namedFd.fd));
            }
            if (namedFd.name == "outFd") {
                mOutFd.reset(dup(namedFd.fd));
            }
        }
        if (mInFd < 0 || mOutFd < 0) {
            ALOGE("[AdbDataLoader] Failed to dup FDs.");
            return false;
        }

        mEventFd.reset(eventfd(0, EFD_CLOEXEC));
        if (mEventFd < 0) {
            ALOGE("[AdbDataLoader] Failed to create eventfd.");
            return false;
        }

        std::string logFile;
        if (const auto packageName = extractPackageName(params.staticArgs()); !packageName.empty()) {
            logFile = android::base::GetProperty("adb.readlog." + packageName, "");
        }
        if (logFile.empty()) {
            logFile = android::base::GetProperty("adb.readlog", "");
        }
        if (!logFile.empty()) {
            int flags = O_WRONLY | O_CREAT | O_CLOEXEC;
            mReadLogFd.reset(
                    TEMP_FAILURE_RETRY(open(logFile.c_str(), flags, 0666)));
        }

        mIfs = ifs;
        mStatusListener = statusListener;
        ALOGE("[AdbDataLoader] Successfully created data loader.");
        return true;
    }

    bool onStart() final {
        char okay_buf[OKAY.size()];
        if (!android::base::ReadFully(mInFd, okay_buf, OKAY.size())) {
            ALOGE("[AdbDataLoader] Failed to receive OKAY. Abort.");
            return false;
        }
        if (std::string_view(okay_buf, OKAY.size()) != OKAY) {
            ALOGE("[AdbDataLoader] Received '%.*s', expecting '%.*s'",
                  (int)OKAY.size(), okay_buf, (int)OKAY.size(), OKAY.data());
            return false;
        }

        mReceiverThread = std::thread([this]() { receiver(); });
        ALOGI("[AdbDataLoader] started loading...");
        return true;
    }

    void onStop() final {
        mStopReceiving = true;
        eventfd_write(mEventFd, 1);
        if (mReceiverThread.joinable()) {
            mReceiverThread.join();
        }
    }

    void onDestroy() final {
        ALOGE("[AdbDataLoader] Sending EXIT to server.");
        sendRequest(mOutFd, EXIT);
        // Make sure the receiver thread was stopped
        CHECK(!mReceiverThread.joinable());

        mInFd.reset();
        mOutFd.reset();

        mNodeToMetaMap.clear();
        mIdToNodeMap.clear();

        flushReadLog();
        mReadLogFd.reset();
    }

    // IFS callbacks.
    void onPendingReads(const android::dataloader::PendingReads& pendingReads) final {
        std::lock_guard lock{mMapsMutex};
        CHECK(mIfs);
        for (auto&& pendingRead : pendingReads) {
            const android::dataloader::Inode ino = pendingRead.file_ino;
            const auto blockIdx =
                    static_cast<BlockIdx>(pendingRead.block_index);
            /*
            ALOGI("[AdbDataLoader] Missing: %d", (int) blockIdx);
            */
            auto fileIdOr = getFileId(ino);
            if (!fileIdOr) {
                ALOGE("[AdbDataLoader] Failed to handle event for inode=%d. "
                      "Ignore.",
                      static_cast<int>(ino));
                continue;
            }
            const FileId fileId = *fileIdOr;
            if (mRequestedFiles.insert(fileId).second) {
                if (!sendRequest(mOutFd, PREFETCH, fileId, blockIdx)) {
                    ALOGE("[AdbDataLoader] Failed to request prefetch for "
                          "inode=%d. Ignore.",
                          static_cast<int>(ino));
                    mRequestedFiles.erase(fileId);
                    mStatusListener->reportStatus(
                            INCREMENTAL_DATA_LOADER_NO_CONNECTION);
                }
            }
            sendRequest(mOutFd, BLOCK_MISSING, fileId, blockIdx);
        }
    }

    struct TracedRead {
        uint64_t timestampUs;
        uint64_t fileIno;
        uint32_t firstBlockIdx;
        uint32_t count;
    };
    void onPageReads(const android::dataloader::PageReads& pageReads) final {
        auto trace = atrace_is_tag_enabled(ATRACE_TAG);
        auto log = mReadLogFd != -1;
        if (CC_LIKELY(!(trace || log))) {
            return;
        }

        TracedRead last = {0, 0, 0, 0};
        std::lock_guard lock{mMapsMutex};
        for (auto&& read : pageReads) {
            if (read.file_ino != last.fileIno ||
                read.block_index != last.firstBlockIdx + last.count) {
                traceOrLogRead(last, trace, log);
                last = {read.timestamp_us, read.file_ino, read.block_index, 1};
            } else {
                ++last.count;
            }
        }
        traceOrLogRead(last, trace, log);
    }
    void onFileCreated(android::dataloader::Inode inode, const android::dataloader::RawMetadata& metadata) {
    }

private:
    void receiver() {
        std::vector<uint8_t> data;
        std::vector<incfs_new_data_block> instructions;
        while (!mStopReceiving) {
            const int res = waitForDataOrSignal(mInFd, mEventFd);
            if (res == 0) {
                flushReadLog();
                continue;
            }
            if (res < 0) {
                ALOGE("[AdbDataLoader] failed to poll. Abort.");
                mStatusListener->reportStatus(INCREMENTAL_DATA_LOADER_NO_CONNECTION);
                break;
            }
            if (res == mEventFd) {
                ALOGE("[AdbDataLoader] received stop signal. Exit.");
                break;
            }
            if (!readChunk(mInFd, data)) {
                ALOGE("[AdbDataLoader] failed to read a message. Abort.");
                mStatusListener->reportStatus(INCREMENTAL_DATA_LOADER_NO_CONNECTION);
                break;
            }
            auto remainingData = std::span(data);
            while (!remainingData.empty()) {
                auto header = readHeader(remainingData);
                if (header.fileId == -1 && header.compressionType == 0 &&
                    header.blockIdx == 0 && header.blockSize == 0) {
                    ALOGI("[AdbDataLoader] stop signal received. Sending "
                          "exit command (remaining bytes: %d).",
                          int(remainingData.size()));

                    sendRequest(mOutFd, EXIT);
                    mStopReceiving = true;
                    break;
                }
                if (header.fileId < 0 || header.blockSize <= 0 ||
                    header.compressionType < 0 || header.blockIdx < 0) {
                    ALOGE("[AdbDataLoader] invalid header received. Abort.");
                    mStopReceiving = true;
                    break;
                }
                const android::dataloader::Inode ino = mIdToNodeMap[header.fileId];
                if (!ino) {
                    ALOGE("Unknown data destination for file ID %d. "
                          "Ignore.",
                          header.fileId);
                    continue;
                }
                auto inst = incfs_new_data_block{
                        .file_ino = static_cast<__aligned_u64>(ino),
                        .block_index = static_cast<uint32_t>(header.blockIdx),
                        .data_len = static_cast<uint16_t>(header.blockSize),
                        .data = reinterpret_cast<uint64_t>(
                                remainingData.data()),
                        .compression =
                                static_cast<uint8_t>(header.compressionType)};
                instructions.push_back(inst);
                remainingData = remainingData.subspan(header.blockSize);
            }
            writeInstructions(instructions);
        }
        writeInstructions(instructions);
        flushReadLog();
    }

    void writeInstructions(std::vector<incfs_new_data_block>& instructions) {
        auto res = this->mIfs->writeBlocks(instructions.data(),
                                           instructions.size());
        if (res != instructions.size()) {
            ALOGE("[AdbDataLoader] failed to write data to Incfs (res=%d when "
                  "expecting %d)",
                  res, int(instructions.size()));
        }
        instructions.clear();
    }

    struct MetaPair {
        android::dataloader::RawMetadata meta;
        FileId fileId;
    };

    MetaPair* updateMapsForFile(android::dataloader::Inode ino) {
        android::dataloader::RawMetadata meta = mIfs->getRawMetadata(ino);
        FileId fileId;
        auto res =
                std::from_chars(meta.data(), meta.data() + meta.size(), fileId);
        if (res.ec != std::errc{} || fileId < 0) {
            ALOGE("[AdbDataLoader] Invalid metadata for inode=%d (%s)",
                  static_cast<int>(ino), meta.data());
            return nullptr;
        }
        mIdToNodeMap[fileId] = ino;
        auto& metaPair = mNodeToMetaMap[ino];
        metaPair.meta = std::move(meta);
        metaPair.fileId = fileId;
        return &metaPair;
    }

    android::dataloader::RawMetadata* getMeta(android::dataloader::Inode ino) {
        auto it = mNodeToMetaMap.find(ino);
        if (it != mNodeToMetaMap.end()) {
            return &it->second.meta;
        }

        auto metaPair = updateMapsForFile(ino);
        if (!metaPair) {
            return nullptr;
        }

        return &metaPair->meta;
    }

    FileId* getFileId(android::dataloader::Inode ino) {
        auto it = mNodeToMetaMap.find(ino);
        if (it != mNodeToMetaMap.end()) {
            return &it->second.fileId;
        }

        auto* metaPair = updateMapsForFile(ino);
        if (!metaPair) {
            return nullptr;
        }

        return &metaPair->fileId;
    }

    void traceOrLogRead(const TracedRead& read, bool trace, bool log) {
        if (!read.count) {
            return;
        }
        if (trace) {
            auto* meta = getMeta(read.fileIno);
            auto str = android::base::StringPrintf(
                    "page_read: index=%lld count=%lld meta=%.*s",
                    static_cast<long long>(read.firstBlockIdx),
                    static_cast<long long>(read.count),
                    meta ? int(meta->size()) : 0, meta ? meta->data() : "");
            ATRACE_BEGIN(str.c_str());
            ATRACE_END();
        }
        if (log) {
            mReadLog.reserve(ReadLogBufferSize);

            auto fileId = getFileId(read.fileIno);
            android::base::StringAppendF(
                    &mReadLog, "%lld:%lld:%lld:%lld\n",
                    static_cast<long long>(read.timestampUs),
                    static_cast<long long>(fileId ? *fileId : -1),
                    static_cast<long long>(read.firstBlockIdx),
                    static_cast<long long>(read.count));

            if (mReadLog.size() >= mReadLog.capacity() - ReadLogMaxEntrySize) {
                flushReadLog();
            }
        }
    }

    void flushReadLog() {
        if (mReadLog.empty() || mReadLogFd == -1) {
            return;
        }

        android::base::WriteStringToFd(mReadLog, mReadLogFd);
        mReadLog.clear();
    }

private:
    android::dataloader::FilesystemConnectorPtr mIfs = nullptr;
    android::dataloader::StatusListenerPtr mStatusListener = nullptr;
    android::base::unique_fd mInFd;
    android::base::unique_fd mOutFd;
    android::base::unique_fd mEventFd;
    android::base::unique_fd mReadLogFd;
    std::string mReadLog;
    std::thread mReceiverThread;
    std::mutex mMapsMutex;
    std::unordered_map<android::dataloader::Inode, MetaPair> mNodeToMetaMap GUARDED_BY(mMapsMutex);
    std::unordered_map<FileId, android::dataloader::Inode> mIdToNodeMap GUARDED_BY(mMapsMutex);
    /** Tracks which files have been requested */
    std::unordered_set<FileId> mRequestedFiles;
    std::atomic<bool> mStopReceiving = false;
};

}  // namespace

int JNI_OnLoad(JavaVM* jvm, void* /* reserved */) {
  android::dataloader::DataLoader::initialize(
            [](auto) { return std::make_unique<AdbDataLoader>(); });
    return JNI_VERSION_1_6;
}
