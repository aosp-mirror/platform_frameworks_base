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

#include <android/util/ProtoOutputStream.h>
#include <errno.h>
#include <fcntl.h>
#include <google/protobuf/io/zero_copy_stream_impl_lite.h>
#include <inttypes.h>
#include <log/log.h>
#include <stats_annotations.h>
#include <stats_event.h>
#include <statslog_hwui.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

#include "JankTracker.h"
#include "protos/graphicsstats.pb.h"

namespace android {
namespace uirenderer {

using namespace google::protobuf;
using namespace uirenderer::protos;

constexpr int32_t sCurrentFileVersion = 1;
constexpr int32_t sHeaderSize = 4;
static_assert(sizeof(sCurrentFileVersion) == sHeaderSize, "Header size is wrong");

constexpr int sHistogramSize = ProfileData::HistogramSize();
constexpr int sGPUHistogramSize = ProfileData::GPUHistogramSize();

static bool mergeProfileDataIntoProto(protos::GraphicsStatsProto* proto, uid_t uid,
                                      const std::string& package, int64_t versionCode,
                                      int64_t startTime, int64_t endTime, const ProfileData* data);
static void dumpAsTextToFd(protos::GraphicsStatsProto* proto, int outFd);

class FileDescriptor {
public:
    explicit FileDescriptor(int fd) : mFd(fd) {}
    ~FileDescriptor() {
        if (mFd != -1) {
            close(mFd);
            mFd = -1;
        }
    }
    bool valid() { return mFd != -1; }
    operator int() { return mFd; } // NOLINT(google-explicit-constructor)

private:
    int mFd;
};

class FileOutputStreamLite : public io::ZeroCopyOutputStream {
public:
    explicit FileOutputStreamLite(int fd) : mCopyAdapter(fd), mImpl(&mCopyAdapter) {}
    virtual ~FileOutputStreamLite() {}

    int GetErrno() { return mCopyAdapter.mErrno; }

    virtual bool Next(void** data, int* size) override { return mImpl.Next(data, size); }

    virtual void BackUp(int count) override { mImpl.BackUp(count); }

    virtual int64 ByteCount() const override { return mImpl.ByteCount(); }

    bool Flush() { return mImpl.Flush(); }

private:
    struct FDAdapter : public io::CopyingOutputStream {
        int mFd;
        int mErrno = 0;

        explicit FDAdapter(int fd) : mFd(fd) {}
        virtual ~FDAdapter() {}

        virtual bool Write(const void* buffer, int size) override {
            int ret;
            while (size) {
                ret = TEMP_FAILURE_RETRY(write(mFd, buffer, size));
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

bool GraphicsStatsService::parseFromFile(const std::string& path,
                                         protos::GraphicsStatsProto* output) {
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
                  strerror(err), (int)sb.st_size);
        }
        return false;
    }
    void* addr = mmap(nullptr, sb.st_size, PROT_READ, MAP_SHARED, fd, 0);
    if (addr == MAP_FAILED) {
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
        munmap(addr, sb.st_size);
        return false;
    }

    void* data = reinterpret_cast<uint8_t*>(addr) + sHeaderSize;
    int dataSize = sb.st_size - sHeaderSize;
    io::ArrayInputStream input{data, dataSize};
    bool success = output->ParseFromZeroCopyStream(&input);
    if (!success) {
        ALOGW("Parse failed on '%s' error='%s'", path.c_str(),
              output->InitializationErrorString().c_str());
    }
    munmap(addr, sb.st_size);
    return success;
}

bool mergeProfileDataIntoProto(protos::GraphicsStatsProto* proto, uid_t uid,
                               const std::string& package, int64_t versionCode, int64_t startTime,
                               int64_t endTime, const ProfileData* data) {
    if (proto->stats_start() == 0 || proto->stats_start() > startTime) {
        proto->set_stats_start(startTime);
    }
    if (proto->stats_end() == 0 || proto->stats_end() < endTime) {
        proto->set_stats_end(endTime);
    }
    proto->set_uid(static_cast<int32_t>(uid));
    proto->set_package_name(package);
    proto->set_version_code(versionCode);
    proto->set_pipeline(data->pipelineType() == RenderPipelineType::SkiaGL ?
            GraphicsStatsProto_PipelineType_GL : GraphicsStatsProto_PipelineType_VULKAN);
    auto summary = proto->mutable_summary();
    summary->set_total_frames(summary->total_frames() + data->totalFrameCount());
    summary->set_janky_frames(summary->janky_frames() + data->jankFrameCount());
    summary->set_missed_vsync_count(summary->missed_vsync_count() +
                                    data->jankTypeCount(kMissedVsync));
    summary->set_high_input_latency_count(summary->high_input_latency_count() +
                                          data->jankTypeCount(kHighInputLatency));
    summary->set_slow_ui_thread_count(summary->slow_ui_thread_count() +
                                      data->jankTypeCount(kSlowUI));
    summary->set_slow_bitmap_upload_count(summary->slow_bitmap_upload_count() +
                                          data->jankTypeCount(kSlowSync));
    summary->set_slow_draw_count(summary->slow_draw_count() + data->jankTypeCount(kSlowRT));
    summary->set_missed_deadline_count(summary->missed_deadline_count() +
                                       data->jankTypeCount(kMissedDeadline));

    bool creatingHistogram = false;
    if (proto->histogram_size() == 0) {
        proto->mutable_histogram()->Reserve(sHistogramSize);
        creatingHistogram = true;
    } else if (proto->histogram_size() != sHistogramSize) {
        ALOGE("Histogram size mismatch, proto is %d expected %d", proto->histogram_size(),
              sHistogramSize);
        return false;
    }
    int index = 0;
    bool hitMergeError = false;
    data->histogramForEach([&](ProfileData::HistogramEntry entry) {
        if (hitMergeError) return;

        protos::GraphicsStatsHistogramBucketProto* bucket;
        if (creatingHistogram) {
            bucket = proto->add_histogram();
            bucket->set_render_millis(entry.renderTimeMs);
        } else {
            bucket = proto->mutable_histogram(index);
            if (bucket->render_millis() != static_cast<int32_t>(entry.renderTimeMs)) {
                ALOGW("Frame time mistmatch %d vs. %u", bucket->render_millis(),
                      entry.renderTimeMs);
                hitMergeError = true;
                return;
            }
        }
        bucket->set_frame_count(bucket->frame_count() + entry.frameCount);
        index++;
    });
    if (hitMergeError) return false;
    // fill in GPU frame time histogram
    creatingHistogram = false;
    if (proto->gpu_histogram_size() == 0) {
        proto->mutable_gpu_histogram()->Reserve(sGPUHistogramSize);
        creatingHistogram = true;
    } else if (proto->gpu_histogram_size() != sGPUHistogramSize) {
        ALOGE("GPU histogram size mismatch, proto is %d expected %d", proto->gpu_histogram_size(),
              sGPUHistogramSize);
        return false;
    }
    index = 0;
    data->histogramGPUForEach([&](ProfileData::HistogramEntry entry) {
        if (hitMergeError) return;

        protos::GraphicsStatsHistogramBucketProto* bucket;
        if (creatingHistogram) {
            bucket = proto->add_gpu_histogram();
            bucket->set_render_millis(entry.renderTimeMs);
        } else {
            bucket = proto->mutable_gpu_histogram(index);
            if (bucket->render_millis() != static_cast<int32_t>(entry.renderTimeMs)) {
                ALOGW("GPU frame time mistmatch %d vs. %u", bucket->render_millis(),
                      entry.renderTimeMs);
                hitMergeError = true;
                return;
            }
        }
        bucket->set_frame_count(bucket->frame_count() + entry.frameCount);
        index++;
    });
    return !hitMergeError;
}

static int32_t findPercentile(protos::GraphicsStatsProto* proto, int percentile) {
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

static int32_t findGPUPercentile(protos::GraphicsStatsProto* proto, int percentile) {
    uint32_t totalGPUFrameCount = 0;  // this is usually  proto->summary().total_frames() - 3.
    for (auto it = proto->gpu_histogram().rbegin(); it != proto->gpu_histogram().rend(); ++it) {
        totalGPUFrameCount += it->frame_count();
    }
    int32_t pos = percentile * totalGPUFrameCount / 100;
    int32_t remaining = totalGPUFrameCount - pos;
    for (auto it = proto->gpu_histogram().rbegin(); it != proto->gpu_histogram().rend(); ++it) {
        remaining -= it->frame_count();
        if (remaining <= 0) {
            return it->render_millis();
        }
    }
    return 0;
}

void dumpAsTextToFd(protos::GraphicsStatsProto* proto, int fd) {
    // This isn't a full validation, just enough that we can deref at will
    if (proto->package_name().empty() || !proto->has_summary()) {
        ALOGW("Skipping dump, invalid package_name() '%s' or summary %d",
              proto->package_name().c_str(), proto->has_summary());
        return;
    }
    dprintf(fd, "\nUID: %d", proto->uid());
    dprintf(fd, "\nPackage: %s", proto->package_name().c_str());
    dprintf(fd, "\nVersion: %" PRId64, proto->version_code());
    dprintf(fd, "\nStats since: %" PRId64 "ns", proto->stats_start());
    dprintf(fd, "\nStats end: %" PRId64 "ns", proto->stats_end());
    auto summary = proto->summary();
    dprintf(fd, "\nTotal frames rendered: %d", summary.total_frames());
    dprintf(fd, "\nJanky frames: %d (%.2f%%)", summary.janky_frames(),
            (float)summary.janky_frames() / (float)summary.total_frames() * 100.0f);
    dprintf(fd, "\n50th percentile: %dms", findPercentile(proto, 50));
    dprintf(fd, "\n90th percentile: %dms", findPercentile(proto, 90));
    dprintf(fd, "\n95th percentile: %dms", findPercentile(proto, 95));
    dprintf(fd, "\n99th percentile: %dms", findPercentile(proto, 99));
    dprintf(fd, "\nNumber Missed Vsync: %d", summary.missed_vsync_count());
    dprintf(fd, "\nNumber High input latency: %d", summary.high_input_latency_count());
    dprintf(fd, "\nNumber Slow UI thread: %d", summary.slow_ui_thread_count());
    dprintf(fd, "\nNumber Slow bitmap uploads: %d", summary.slow_bitmap_upload_count());
    dprintf(fd, "\nNumber Slow issue draw commands: %d", summary.slow_draw_count());
    dprintf(fd, "\nNumber Frame deadline missed: %d", summary.missed_deadline_count());
    dprintf(fd, "\nHISTOGRAM:");
    for (const auto& it : proto->histogram()) {
        dprintf(fd, " %dms=%d", it.render_millis(), it.frame_count());
    }
    dprintf(fd, "\n50th gpu percentile: %dms", findGPUPercentile(proto, 50));
    dprintf(fd, "\n90th gpu percentile: %dms", findGPUPercentile(proto, 90));
    dprintf(fd, "\n95th gpu percentile: %dms", findGPUPercentile(proto, 95));
    dprintf(fd, "\n99th gpu percentile: %dms", findGPUPercentile(proto, 99));
    dprintf(fd, "\nGPU HISTOGRAM:");
    for (const auto& it : proto->gpu_histogram()) {
        dprintf(fd, " %dms=%d", it.render_millis(), it.frame_count());
    }
    dprintf(fd, "\n");
}

void GraphicsStatsService::saveBuffer(const std::string& path, uid_t uid,
                                      const std::string& package, int64_t versionCode,
                                      int64_t startTime, int64_t endTime, const ProfileData* data) {
    protos::GraphicsStatsProto statsProto;
    if (!parseFromFile(path, &statsProto)) {
        statsProto.Clear();
    }
    if (!mergeProfileDataIntoProto(&statsProto, uid, package, versionCode, startTime, endTime,
                                   data)) {
        return;
    }
    // Although we might not have read any data from the file, merging the existing data
    // should always fully-initialize the proto
    if (!statsProto.IsInitialized()) {
        ALOGE("proto initialization error %s", statsProto.InitializationErrorString().c_str());
        return;
    }
    if (statsProto.package_name().empty() || !statsProto.has_summary()) {
        ALOGE("missing package_name() '%s' summary %d", statsProto.package_name().c_str(),
              statsProto.has_summary());
        return;
    }
    int outFd = open(path.c_str(), O_CREAT | O_RDWR | O_TRUNC, 0660);
    if (outFd <= 0) {
        int err = errno;
        ALOGW("Failed to open '%s', error=%d (%s)", path.c_str(), err, strerror(err));
        return;
    }
    int wrote = write(outFd, &sCurrentFileVersion, sHeaderSize);
    if (wrote != sHeaderSize) {
        int err = errno;
        ALOGW("Failed to write header to '%s', returned=%d errno=%d (%s)", path.c_str(), wrote, err,
              strerror(err));
        close(outFd);
        return;
    }
    {
        FileOutputStreamLite output(outFd);
        bool success = statsProto.SerializeToZeroCopyStream(&output) && output.Flush();
        if (output.GetErrno() != 0) {
            ALOGW("Error writing to fd=%d, path='%s' err=%d (%s)", outFd, path.c_str(),
                  output.GetErrno(), strerror(output.GetErrno()));
            success = false;
        } else if (!success) {
            ALOGW("Serialize failed on '%s' unknown error", path.c_str());
        }
    }
    close(outFd);
}

class GraphicsStatsService::Dump {
public:
    Dump(int outFd, DumpType type) : mFd(outFd), mType(type) {
        if (mFd == -1 && mType == DumpType::Protobuf) {
            mType = DumpType::ProtobufStatsd;
        }
    }
    int fd() { return mFd; }
    DumpType type() { return mType; }
    protos::GraphicsStatsServiceDumpProto& proto() { return mProto; }
    void mergeStat(const protos::GraphicsStatsProto& stat);
    void updateProto();

private:
    // use package name and app version for a key
    typedef std::tuple<uid_t, std::string, int64_t> DumpKey;

    std::map<DumpKey, protos::GraphicsStatsProto> mStats;
    int mFd;
    DumpType mType;
    protos::GraphicsStatsServiceDumpProto mProto;
};

void GraphicsStatsService::Dump::mergeStat(const protos::GraphicsStatsProto& stat) {
    auto dumpKey = std::make_tuple(static_cast<uid_t>(stat.uid()), stat.package_name(),
                                   stat.version_code());
    auto findIt = mStats.find(dumpKey);
    if (findIt == mStats.end()) {
        mStats[dumpKey] = stat;
    } else {
        auto summary = findIt->second.mutable_summary();
        summary->set_total_frames(summary->total_frames() + stat.summary().total_frames());
        summary->set_janky_frames(summary->janky_frames() + stat.summary().janky_frames());
        summary->set_missed_vsync_count(summary->missed_vsync_count() +
                                        stat.summary().missed_vsync_count());
        summary->set_high_input_latency_count(summary->high_input_latency_count() +
                                              stat.summary().high_input_latency_count());
        summary->set_slow_ui_thread_count(summary->slow_ui_thread_count() +
                                          stat.summary().slow_ui_thread_count());
        summary->set_slow_bitmap_upload_count(summary->slow_bitmap_upload_count() +
                                              stat.summary().slow_bitmap_upload_count());
        summary->set_slow_draw_count(summary->slow_draw_count() + stat.summary().slow_draw_count());
        summary->set_missed_deadline_count(summary->missed_deadline_count() +
                                           stat.summary().missed_deadline_count());
        for (int bucketIndex = 0; bucketIndex < findIt->second.histogram_size(); bucketIndex++) {
            auto bucket = findIt->second.mutable_histogram(bucketIndex);
            bucket->set_frame_count(bucket->frame_count() +
                                    stat.histogram(bucketIndex).frame_count());
        }
        for (int bucketIndex = 0; bucketIndex < findIt->second.gpu_histogram_size();
             bucketIndex++) {
            auto bucket = findIt->second.mutable_gpu_histogram(bucketIndex);
            bucket->set_frame_count(bucket->frame_count() +
                                    stat.gpu_histogram(bucketIndex).frame_count());
        }
        findIt->second.set_stats_start(std::min(findIt->second.stats_start(), stat.stats_start()));
        findIt->second.set_stats_end(std::max(findIt->second.stats_end(), stat.stats_end()));
    }
}

void GraphicsStatsService::Dump::updateProto() {
    for (auto& stat : mStats) {
        mProto.add_stats()->CopyFrom(stat.second);
    }
}

GraphicsStatsService::Dump* GraphicsStatsService::createDump(int outFd, DumpType type) {
    return new Dump(outFd, type);
}

void GraphicsStatsService::addToDump(Dump* dump, const std::string& path, uid_t uid,
                                     const std::string& package, int64_t versionCode,
                                     int64_t startTime, int64_t endTime, const ProfileData* data) {
    protos::GraphicsStatsProto statsProto;
    if (!path.empty() && !parseFromFile(path, &statsProto)) {
        statsProto.Clear();
    }
    if (data && !mergeProfileDataIntoProto(&statsProto, uid, package, versionCode, startTime,
                                           endTime, data)) {
        return;
    }
    if (!statsProto.IsInitialized()) {
        ALOGW("Failed to load profile data from path '%s' and data %p",
              path.empty() ? "<empty>" : path.c_str(), data);
        return;
    }
    if (dump->type() == DumpType::ProtobufStatsd) {
        dump->mergeStat(statsProto);
    } else if (dump->type() == DumpType::Protobuf) {
        dump->proto().add_stats()->CopyFrom(statsProto);
    } else {
        dumpAsTextToFd(&statsProto, dump->fd());
    }
}

void GraphicsStatsService::addToDump(Dump* dump, const std::string& path) {
    protos::GraphicsStatsProto statsProto;
    if (!parseFromFile(path, &statsProto)) {
        return;
    }
    if (dump->type() == DumpType::ProtobufStatsd) {
        dump->mergeStat(statsProto);
    } else if (dump->type() == DumpType::Protobuf) {
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

using namespace google::protobuf;

// Field ids taken from FrameTimingHistogram message in atoms.proto
#define TIME_MILLIS_BUCKETS_FIELD_NUMBER 1
#define FRAME_COUNTS_FIELD_NUMBER 2

static void writeCpuHistogram(AStatsEvent* event,
                              const uirenderer::protos::GraphicsStatsProto& stat) {
    util::ProtoOutputStream proto;
    for (int bucketIndex = 0; bucketIndex < stat.histogram_size(); bucketIndex++) {
        auto& bucket = stat.histogram(bucketIndex);
        proto.write(android::util::FIELD_TYPE_INT32 | android::util::FIELD_COUNT_REPEATED |
                            TIME_MILLIS_BUCKETS_FIELD_NUMBER /* field id */,
                    (int)bucket.render_millis());
    }
    for (int bucketIndex = 0; bucketIndex < stat.histogram_size(); bucketIndex++) {
        auto& bucket = stat.histogram(bucketIndex);
        proto.write(android::util::FIELD_TYPE_INT64 | android::util::FIELD_COUNT_REPEATED |
                            FRAME_COUNTS_FIELD_NUMBER /* field id */,
                    (long long)bucket.frame_count());
    }
    std::vector<uint8_t> outVector;
    proto.serializeToVector(&outVector);
    AStatsEvent_writeByteArray(event, outVector.data(), outVector.size());
}

static void writeGpuHistogram(AStatsEvent* event,
                              const uirenderer::protos::GraphicsStatsProto& stat) {
    util::ProtoOutputStream proto;
    for (int bucketIndex = 0; bucketIndex < stat.gpu_histogram_size(); bucketIndex++) {
        auto& bucket = stat.gpu_histogram(bucketIndex);
        proto.write(android::util::FIELD_TYPE_INT32 | android::util::FIELD_COUNT_REPEATED |
                            TIME_MILLIS_BUCKETS_FIELD_NUMBER /* field id */,
                    (int)bucket.render_millis());
    }
    for (int bucketIndex = 0; bucketIndex < stat.gpu_histogram_size(); bucketIndex++) {
        auto& bucket = stat.gpu_histogram(bucketIndex);
        proto.write(android::util::FIELD_TYPE_INT64 | android::util::FIELD_COUNT_REPEATED |
                            FRAME_COUNTS_FIELD_NUMBER /* field id */,
                    (long long)bucket.frame_count());
    }
    std::vector<uint8_t> outVector;
    proto.serializeToVector(&outVector);
    AStatsEvent_writeByteArray(event, outVector.data(), outVector.size());
}


void GraphicsStatsService::finishDumpInMemory(Dump* dump, AStatsEventList* data,
                                              bool lastFullDay) {
    dump->updateProto();
    auto& serviceDump = dump->proto();
    for (int stat_index = 0; stat_index < serviceDump.stats_size(); stat_index++) {
        auto& stat = serviceDump.stats(stat_index);
        AStatsEvent* event = AStatsEventList_addStatsEvent(data);
        AStatsEvent_setAtomId(event, stats::GRAPHICS_STATS);
        AStatsEvent_writeString(event, stat.package_name().c_str());
        AStatsEvent_writeInt64(event, (int64_t)stat.version_code());
        AStatsEvent_writeInt64(event, (int64_t)stat.stats_start());
        AStatsEvent_writeInt64(event, (int64_t)stat.stats_end());
        AStatsEvent_writeInt32(event, (int32_t)stat.pipeline());
        AStatsEvent_writeInt32(event, (int32_t)stat.summary().total_frames());
        AStatsEvent_writeInt32(event, (int32_t)stat.summary().missed_vsync_count());
        AStatsEvent_writeInt32(event, (int32_t)stat.summary().high_input_latency_count());
        AStatsEvent_writeInt32(event, (int32_t)stat.summary().slow_ui_thread_count());
        AStatsEvent_writeInt32(event, (int32_t)stat.summary().slow_bitmap_upload_count());
        AStatsEvent_writeInt32(event, (int32_t)stat.summary().slow_draw_count());
        AStatsEvent_writeInt32(event, (int32_t)stat.summary().missed_deadline_count());
        writeCpuHistogram(event, stat);
        writeGpuHistogram(event, stat);
        // TODO: fill in UI mainline module version, when the feature is available.
        AStatsEvent_writeInt64(event, (int64_t)0);
        AStatsEvent_writeBool(event, !lastFullDay);
        AStatsEvent_writeInt32(event, stat.uid());
        AStatsEvent_addBoolAnnotation(event, ASTATSLOG_ANNOTATION_ID_IS_UID, true);
        AStatsEvent_build(event);
    }
    delete dump;
}


} /* namespace uirenderer */
} /* namespace android */
