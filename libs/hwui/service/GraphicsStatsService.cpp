/*
 * Copyright (C) 2017 The Android Open Source Project
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

#include "GraphicsStatsService.h"

#include "JankTracker.h"

#include <frameworks/base/core/proto/android/service/graphicsstats.pb.h>
#include <google/protobuf/io/zero_copy_stream_impl_lite.h>
#include <log/log.h>

#include <inttypes.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <unistd.h>
#include <sys/mman.h>

namespace android {
namespace uirenderer {

using namespace google::protobuf;

constexpr int32_t sCurrentFileVersion = 1;
constexpr int32_t sHeaderSize = 4;
static_assert(sizeof(sCurrentFileVersion) == sHeaderSize, "Header size is wrong");

constexpr int sHistogramSize = ProfileData::HistogramSize();

static void mergeProfileDataIntoProto(service::GraphicsStatsProto* proto,
        const std::string& package, int versionCode, int64_t startTime, int64_t endTime,
        const ProfileData* data);
static void dumpAsTextToFd(service::GraphicsStatsProto* proto, int outFd);

class FileDescriptor {
public:
    FileDescriptor(int fd) : mFd(fd) {}
    ~FileDescriptor() {
        if (mFd != -1) {
            close(mFd);
            mFd = -1;
        }
    }
    bool valid() { return mFd != -1; }
    operator int() { return mFd; }
private:
    int mFd;
};

class FileOutputStreamLite : public io::ZeroCopyOutputStream {
public:
    FileOutputStreamLite(int fd) : mCopyAdapter(fd), mImpl(&mCopyAdapter) {}
    virtual ~FileOutputStreamLite() {}

    int GetErrno() { return mCopyAdapter.mErrno; }

    virtual bool Next(void** data, int* size) override {
        return mImpl.Next(data, size);
    }

    virtual void BackUp(int count) override {
        mImpl.BackUp(count);
    }

    virtual int64 ByteCount() const override {
        return mImpl.ByteCount();
    }

    bool Flush() {
        return mImpl.Flush();
    }

private:
    struct FDAdapter : public io::CopyingOutputStream {
        int mFd;
        int mErrno = 0;

        FDAdapter(int fd) : mFd(fd) {}
        virtual ~FDAdapter() {}

        virtual bool Write(const void* buffer, int size) override {
            int ret;
            while (size) {
                ret = TEMP_FAILURE_RETRY( write(mFd, buffer, size) );
                if (ret <= 0) {
                    mErrno = errno;
                    return false;
                }
                size -= ret;
            }
            return true;
        }
    };

    FileOutputStreamLite::FDAdapter mCopyAdapter;
    io::CopyingOutputStreamAdaptor mImpl;
};

bool GraphicsStatsService::parseFromFile(const std::string& path, service::GraphicsStatsProto* output) {

    FileDescriptor fd{open(path.c_str(), O_RDONLY)};
    if (!fd.valid()) {
        int err = errno;
        // The file not existing is normal for addToDump(), so only log if
        // we get an unexpected error
        if (err != ENOENT) {
            ALOGW("Failed to open '%s', errno=%d (%s)", path.c_str(), err, strerror(err));
        }
        return false;
    }
    struct stat sb;
    if (fstat(fd, &sb) || sb.st_size < sHeaderSize) {
        int err = errno;
        // The file not existing is normal for addToDump(), so only log if
        // we get an unexpected error
        if (err != ENOENT) {
            ALOGW("Failed to fstat '%s', errno=%d (%s) (st_size %d)", path.c_str(), err,
                    strerror(err), (int) sb.st_size);
        }
        return false;
    }
    void* addr = mmap(nullptr, sb.st_size, PROT_READ, MAP_SHARED, fd, 0);
    if (!addr) {
        int err = errno;
        // The file not existing is normal for addToDump(), so only log if
        // we get an unexpected error
        if (err != ENOENT) {
            ALOGW("Failed to mmap '%s', errno=%d (%s)", path.c_str(), err, strerror(err));
        }
        return false;
    }
    uint32_t file_version = *reinterpret_cast<uint32_t*>(addr);
    if (file_version != sCurrentFileVersion) {
        ALOGW("file_version mismatch! expected %d got %d", sCurrentFileVersion, file_version);
        return false;
    }

    void* data = reinterpret_cast<uint8_t*>(addr) + sHeaderSize;
    int dataSize = sb.st_size - sHeaderSize;
    io::ArrayInputStream input{data, dataSize};
    bool success = output->ParseFromZeroCopyStream(&input);
    if (!success) {
        ALOGW("Parse failed on '%s' error='%s'",
                path.c_str(), output->InitializationErrorString().c_str());
    }
    return success;
}

void mergeProfileDataIntoProto(service::GraphicsStatsProto* proto, const std::string& package,
        int versionCode, int64_t startTime, int64_t endTime, const ProfileData* data) {
    if (proto->stats_start() == 0 || proto->stats_start() > startTime) {
        proto->set_stats_start(startTime);
    }
    if (proto->stats_end() == 0 || proto->stats_end() < endTime) {
        proto->set_stats_end(endTime);
    }
    proto->set_package_name(package);
    proto->set_version_code(versionCode);
    auto summary = proto->mutable_summary();
    summary->set_total_frames(summary->total_frames() + data->totalFrameCount());
    summary->set_janky_frames(summary->janky_frames() + data->jankFrameCount());
    summary->set_missed_vsync_count(
            summary->missed_vsync_count() + data->jankTypeCount(kMissedVsync));
    summary->set_high_input_latency_count(
            summary->high_input_latency_count() + data->jankTypeCount(kHighInputLatency));
    summary->set_slow_ui_thread_count(
            summary->slow_ui_thread_count() + data->jankTypeCount(kSlowUI));
    summary->set_slow_bitmap_upload_count(
            summary->slow_bitmap_upload_count() + data->jankTypeCount(kSlowSync));
    summary->set_slow_draw_count(
            summary->slow_draw_count() + data->jankTypeCount(kSlowRT));

    bool creatingHistogram = false;
    if (proto->histogram_size() == 0) {
        proto->mutable_histogram()->Reserve(sHistogramSize);
        creatingHistogram = true;
    } else if (proto->histogram_size() != sHistogramSize) {
        LOG_ALWAYS_FATAL("Histogram size mismatch, proto is %d expected %d",
                proto->histogram_size(), sHistogramSize);
    }
    int index = 0;
    data->histogramForEach([&](ProfileData::HistogramEntry entry) {
        service::GraphicsStatsHistogramBucketProto* bucket;
        if (creatingHistogram) {
            bucket = proto->add_histogram();
            bucket->set_render_millis(entry.renderTimeMs);
        } else {
            bucket = proto->mutable_histogram(index);
            LOG_ALWAYS_FATAL_IF(bucket->render_millis() != static_cast<int32_t>(entry.renderTimeMs),
                    "Frame time mistmatch %d vs. %u", bucket->render_millis(), entry.renderTimeMs);
        }
        bucket->set_frame_count(bucket->frame_count() + entry.frameCount);
        index++;
    });
}

static int32_t findPercentile(service::GraphicsStatsProto* proto, int percentile) {
    int32_t pos = percentile * proto->summary().total_frames() / 100;
    int32_t remaining = proto->summary().total_frames() - pos;
    for (auto it = proto->histogram().rbegin(); it != proto->histogram().rend(); ++it) {
        remaining -= it->frame_count();
        if (remaining <= 0) {
            return it->render_millis();
        }
    }
    return 0;
}

void dumpAsTextToFd(service::GraphicsStatsProto* proto, int fd) {
    // This isn't a full validation, just enough that we can deref at will
    LOG_ALWAYS_FATAL_IF(proto->package_name().empty()
            || !proto->has_summary(), "package_name() '%s' summary %d",
            proto->package_name().c_str(), proto->has_summary());
    dprintf(fd, "\nPackage: %s", proto->package_name().c_str());
    dprintf(fd, "\nVersion: %d", proto->version_code());
    dprintf(fd, "\nStats since: %lldns", proto->stats_start());
    dprintf(fd, "\nStats end: %lldns", proto->stats_end());
    auto summary = proto->summary();
    dprintf(fd, "\nTotal frames rendered: %d", summary.total_frames());
    dprintf(fd, "\nJanky frames: %d (%.2f%%)", summary.janky_frames(),
            (float) summary.janky_frames() / (float) summary.total_frames() * 100.0f);
    dprintf(fd, "\n50th percentile: %dms", findPercentile(proto, 50));
    dprintf(fd, "\n90th percentile: %dms", findPercentile(proto, 90));
    dprintf(fd, "\n95th percentile: %dms", findPercentile(proto, 95));
    dprintf(fd, "\n99th percentile: %dms", findPercentile(proto, 99));
    dprintf(fd, "\nNumber Missed Vsync: %d", summary.missed_vsync_count());
    dprintf(fd, "\nNumber High input latency: %d", summary.high_input_latency_count());
    dprintf(fd, "\nNumber Slow UI thread: %d", summary.slow_ui_thread_count());
    dprintf(fd, "\nNumber Slow bitmap uploads: %d", summary.slow_bitmap_upload_count());
    dprintf(fd, "\nNumber Slow issue draw commands: %d", summary.slow_draw_count());
    dprintf(fd, "\nHISTOGRAM:");
    for (const auto& it : proto->histogram()) {
        dprintf(fd, " %dms=%d", it.render_millis(), it.frame_count());
    }
    dprintf(fd, "\n");
}

void GraphicsStatsService::saveBuffer(const std::string& path, const std::string& package,
        int versionCode, int64_t startTime, int64_t endTime, const ProfileData* data) {
    service::GraphicsStatsProto statsProto;
    if (!parseFromFile(path, &statsProto)) {
        statsProto.Clear();
    }
    mergeProfileDataIntoProto(&statsProto, package, versionCode, startTime, endTime, data);
    // Although we might not have read any data from the file, merging the existing data
    // should always fully-initialize the proto
    LOG_ALWAYS_FATAL_IF(!statsProto.IsInitialized(), "%s",
            statsProto.InitializationErrorString().c_str());
    LOG_ALWAYS_FATAL_IF(statsProto.package_name().empty()
            || !statsProto.has_summary(), "package_name() '%s' summary %d",
            statsProto.package_name().c_str(), statsProto.has_summary());
    int outFd = open(path.c_str(), O_CREAT | O_RDWR | O_TRUNC, 0660);
    if (outFd <= 0) {
        int err = errno;
        ALOGW("Failed to open '%s', error=%d (%s)", path.c_str(), err, strerror(err));
        return;
    }
    int wrote = write(outFd, &sCurrentFileVersion, sHeaderSize);
    if (wrote != sHeaderSize) {
        int err = errno;
        ALOGW("Failed to write header to '%s', returned=%d errno=%d (%s)",
                path.c_str(), wrote, err, strerror(err));
        close(outFd);
        return;
    }
    {
        FileOutputStreamLite output(outFd);
        bool success = statsProto.SerializeToZeroCopyStream(&output) && output.Flush();
        if (output.GetErrno() != 0) {
            ALOGW("Error writing to fd=%d, path='%s' err=%d (%s)",
                    outFd, path.c_str(), output.GetErrno(), strerror(output.GetErrno()));
            success = false;
        } else if (!success) {
            ALOGW("Serialize failed on '%s' unknown error", path.c_str());
        }
    }
    close(outFd);
}

class GraphicsStatsService::Dump {
public:
    Dump(int outFd, DumpType type) : mFd(outFd), mType(type) {}
    int fd() { return mFd; }
    DumpType type() { return mType; }
    service::GraphicsStatsServiceDumpProto& proto() { return mProto; }
private:
    int mFd;
    DumpType mType;
    service::GraphicsStatsServiceDumpProto mProto;
};

GraphicsStatsService::Dump* GraphicsStatsService::createDump(int outFd, DumpType type) {
    return new Dump(outFd, type);
}

void GraphicsStatsService::addToDump(Dump* dump, const std::string& path, const std::string& package,
        int versionCode, int64_t startTime, int64_t endTime, const ProfileData* data) {
    service::GraphicsStatsProto statsProto;
    if (!path.empty() && !parseFromFile(path, &statsProto)) {
        statsProto.Clear();
    }
    if (data) {
        mergeProfileDataIntoProto(&statsProto, package, versionCode, startTime, endTime, data);
    }
    if (!statsProto.IsInitialized()) {
        ALOGW("Failed to load profile data from path '%s' and data %p",
                path.empty() ? "<empty>" : path.c_str(), data);
        return;
    }

    if (dump->type() == DumpType::Protobuf) {
        dump->proto().add_stats()->CopyFrom(statsProto);
    } else {
        dumpAsTextToFd(&statsProto, dump->fd());
    }
}

void GraphicsStatsService::addToDump(Dump* dump, const std::string& path) {
    service::GraphicsStatsProto statsProto;
    if (!parseFromFile(path, &statsProto)) {
        return;
    }
    if (dump->type() == DumpType::Protobuf) {
        dump->proto().add_stats()->CopyFrom(statsProto);
    } else {
        dumpAsTextToFd(&statsProto, dump->fd());
    }
}

void GraphicsStatsService::finishDump(Dump* dump) {
    if (dump->type() == DumpType::Protobuf) {
        FileOutputStreamLite stream(dump->fd());
        dump->proto().SerializeToZeroCopyStream(&stream);
    }
    delete dump;
}

} /* namespace uirenderer */
} /* namespace android */