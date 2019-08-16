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

#define LOG_TAG "GraphicsStatsService"

#include <jni.h>
#include <log/log.h>
#include <nativehelper/JNIHelp.h>
#include <nativehelper/ScopedPrimitiveArray.h>
#include <nativehelper/ScopedUtfChars.h>
#include <JankTracker.h>
#include <service/GraphicsStatsService.h>
#include <stats_pull_atom_callback.h>
#include <stats_event.h>
#include <statslog.h>
#include <google/protobuf/io/zero_copy_stream_impl_lite.h>
#include <android/util/ProtoOutputStream.h>
#include "android/graphics/Utils.h"
#include "core_jni_helpers.h"
#include "protos/graphicsstats.pb.h"
#include <cstring>
#include <memory>

namespace android {

using namespace android::uirenderer;

static jint getAshmemSize(JNIEnv*, jobject) {
    return sizeof(ProfileData);
}

static jlong createDump(JNIEnv*, jobject, jint fd, jboolean isProto) {
    GraphicsStatsService::Dump* dump = GraphicsStatsService::createDump(fd, isProto
            ? GraphicsStatsService::DumpType::Protobuf : GraphicsStatsService::DumpType::Text);
    return reinterpret_cast<jlong>(dump);
}

static void addToDump(JNIEnv* env, jobject, jlong dumpPtr, jstring jpath, jstring jpackage,
        jlong versionCode, jlong startTime, jlong endTime, jbyteArray jdata) {
    std::string path;
    const ProfileData* data = nullptr;
    LOG_ALWAYS_FATAL_IF(jdata == nullptr && jpath == nullptr, "Path and data can't both be null");
    ScopedByteArrayRO buffer{env};
    if (jdata != nullptr) {
        buffer.reset(jdata);
        LOG_ALWAYS_FATAL_IF(buffer.size() != sizeof(ProfileData),
                "Buffer size %zu doesn't match expected %zu!", buffer.size(), sizeof(ProfileData));
        data = reinterpret_cast<const ProfileData*>(buffer.get());
    }
    if (jpath != nullptr) {
        ScopedUtfChars pathChars(env, jpath);
        LOG_ALWAYS_FATAL_IF(pathChars.size() <= 0 || !pathChars.c_str(), "Failed to get path chars");
        path.assign(pathChars.c_str(), pathChars.size());
    }
    ScopedUtfChars packageChars(env, jpackage);
    LOG_ALWAYS_FATAL_IF(packageChars.size() <= 0 || !packageChars.c_str(), "Failed to get path chars");
    GraphicsStatsService::Dump* dump = reinterpret_cast<GraphicsStatsService::Dump*>(dumpPtr);
    LOG_ALWAYS_FATAL_IF(!dump, "null passed for dump pointer");

    const std::string package(packageChars.c_str(), packageChars.size());
    GraphicsStatsService::addToDump(dump, path, package, versionCode, startTime, endTime, data);
}

static void addFileToDump(JNIEnv* env, jobject, jlong dumpPtr, jstring jpath) {
    ScopedUtfChars pathChars(env, jpath);
    LOG_ALWAYS_FATAL_IF(pathChars.size() <= 0 || !pathChars.c_str(), "Failed to get path chars");
    const std::string path(pathChars.c_str(), pathChars.size());
    GraphicsStatsService::Dump* dump = reinterpret_cast<GraphicsStatsService::Dump*>(dumpPtr);
    GraphicsStatsService::addToDump(dump, path);
}

static void finishDump(JNIEnv*, jobject, jlong dumpPtr) {
    GraphicsStatsService::Dump* dump = reinterpret_cast<GraphicsStatsService::Dump*>(dumpPtr);
    GraphicsStatsService::finishDump(dump);
}

static jlong finishDumpInMemory(JNIEnv* env, jobject, jlong dumpPtr) {
    GraphicsStatsService::Dump* dump = reinterpret_cast<GraphicsStatsService::Dump*>(dumpPtr);
    std::vector<uint8_t>* result = new std::vector<uint8_t>();
    GraphicsStatsService::finishDumpInMemory(dump,
        [](void* buffer, int bufferOffset, int bufferSize, int totalSize, void* param1, void* param2) {
            std::vector<uint8_t>* outBuffer = reinterpret_cast<std::vector<uint8_t>*>(param2);
            if (outBuffer->size() < totalSize) {
                outBuffer->resize(totalSize);
            }
            std::memcpy(outBuffer->data() + bufferOffset, buffer, bufferSize);
        }, env, result);
    return reinterpret_cast<jlong>(result);
}

static void saveBuffer(JNIEnv* env, jobject clazz, jstring jpath, jstring jpackage,
        jlong versionCode, jlong startTime, jlong endTime, jbyteArray jdata) {
    ScopedByteArrayRO buffer(env, jdata);
    LOG_ALWAYS_FATAL_IF(buffer.size() != sizeof(ProfileData),
            "Buffer size %zu doesn't match expected %zu!", buffer.size(), sizeof(ProfileData));
    ScopedUtfChars pathChars(env, jpath);
    LOG_ALWAYS_FATAL_IF(pathChars.size() <= 0 || !pathChars.c_str(), "Failed to get path chars");
    ScopedUtfChars packageChars(env, jpackage);
    LOG_ALWAYS_FATAL_IF(packageChars.size() <= 0 || !packageChars.c_str(), "Failed to get path chars");

    const std::string path(pathChars.c_str(), pathChars.size());
    const std::string package(packageChars.c_str(), packageChars.size());
    const ProfileData* data = reinterpret_cast<const ProfileData*>(buffer.get());
    GraphicsStatsService::saveBuffer(path, package, versionCode, startTime, endTime, data);
}

static jobject gGraphicsStatsServiceObject = nullptr;
static jmethodID gGraphicsStatsService_pullGraphicsStatsMethodID;

static JNIEnv* getJNIEnv() {
    JavaVM* vm = AndroidRuntime::getJavaVM();
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        if (vm->AttachCurrentThreadAsDaemon(&env, nullptr) != JNI_OK) {
            LOG_ALWAYS_FATAL("Failed to AttachCurrentThread!");
        }
    }
    return env;
}

using namespace google::protobuf;

// Field ids taken from FrameTimingHistogram message in atoms.proto
#define TIME_MILLIS_BUCKETS_FIELD_NUMBER 1
#define FRAME_COUNTS_FIELD_NUMBER 2

static void writeCpuHistogram(stats_event* event,
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
    stats_event_write_byte_array(event, outVector.data(), outVector.size());
}

static void writeGpuHistogram(stats_event* event,
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
    stats_event_write_byte_array(event, outVector.data(), outVector.size());
}

// graphicsStatsPullCallback is invoked by statsd service to pull GRAPHICS_STATS atom.
static bool graphicsStatsPullCallback(int32_t atom_tag, pulled_stats_event_list* data,
                                      const void* cookie) {
    JNIEnv* env = getJNIEnv();
    if (!env) {
        return false;
    }
    if (gGraphicsStatsServiceObject == nullptr) {
        ALOGE("Failed to get graphicsstats service");
        return false;
    }

    for (bool lastFullDay : {true, false}) {
        jlong jdata = (jlong) env->CallLongMethod(
                    gGraphicsStatsServiceObject,
                    gGraphicsStatsService_pullGraphicsStatsMethodID,
                    (jboolean)(lastFullDay ? JNI_TRUE : JNI_FALSE));
        if (env->ExceptionCheck()) {
            env->ExceptionDescribe();
            env->ExceptionClear();
            ALOGE("Failed to invoke graphicsstats service");
            return false;
        }
        if (!jdata) {
            // null means data is not available for that day.
            continue;
        }
        android::uirenderer::protos::GraphicsStatsServiceDumpProto serviceDump;
        std::vector<uint8_t>* buffer = reinterpret_cast<std::vector<uint8_t>*>(jdata);
        std::unique_ptr<std::vector<uint8_t>> bufferRelease(buffer);
        int dataSize = buffer->size();
        if (!dataSize) {
            // Data is not available for that day.
            continue;
        }
        io::ArrayInputStream input{buffer->data(), dataSize};
        bool success = serviceDump.ParseFromZeroCopyStream(&input);
        if (!success) {
            ALOGW("Parse failed on GraphicsStatsPuller error='%s' dataSize='%d'",
                  serviceDump.InitializationErrorString().c_str(), dataSize);
            return false;
        }

        for (int stat_index = 0; stat_index < serviceDump.stats_size(); stat_index++) {
            auto& stat = serviceDump.stats(stat_index);
            stats_event* event = add_stats_event_to_pull_data(data);
            stats_event_set_atom_id(event, android::util::GRAPHICS_STATS);
            stats_event_write_string8(event, stat.package_name().c_str());
            stats_event_write_int64(event, (int64_t)stat.version_code());
            stats_event_write_int64(event, (int64_t)stat.stats_start());
            stats_event_write_int64(event, (int64_t)stat.stats_end());
            stats_event_write_int32(event, (int32_t)stat.pipeline());
            stats_event_write_int32(event, (int32_t)stat.summary().total_frames());
            stats_event_write_int32(event, (int32_t)stat.summary().missed_vsync_count());
            stats_event_write_int32(event, (int32_t)stat.summary().high_input_latency_count());
            stats_event_write_int32(event, (int32_t)stat.summary().slow_ui_thread_count());
            stats_event_write_int32(event, (int32_t)stat.summary().slow_bitmap_upload_count());
            stats_event_write_int32(event, (int32_t)stat.summary().slow_draw_count());
            stats_event_write_int32(event, (int32_t)stat.summary().missed_deadline_count());
            writeCpuHistogram(event, stat);
            writeGpuHistogram(event, stat);
            // TODO: fill in UI mainline module version, when the feature is available.
            stats_event_write_int64(event, (int64_t)0);
            stats_event_write_bool(event, !lastFullDay);
            stats_event_build(event);
        }
    }
    return true;
}

// Register a puller for GRAPHICS_STATS atom with the statsd service.
static void nativeInit(JNIEnv* env, jobject javaObject) {
    gGraphicsStatsServiceObject = env->NewGlobalRef(javaObject);
    pull_atom_metadata metadata = {.cool_down_ns = 10 * 1000000, // 10 milliseconds
                                   .timeout_ns = 2 * NS_PER_SEC, // 2 seconds
                                   .additive_fields = nullptr,
                                   .additive_fields_size = 0};
    register_stats_pull_atom_callback(android::util::GRAPHICS_STATS, &graphicsStatsPullCallback,
            &metadata, nullptr);
}

static void nativeDestructor(JNIEnv* env, jobject javaObject) {
    //TODO: Unregister the puller callback when a new API is available.
    env->DeleteGlobalRef(gGraphicsStatsServiceObject);
    gGraphicsStatsServiceObject = nullptr;
}

static const JNINativeMethod sMethods[] = {
    { "nGetAshmemSize", "()I", (void*) getAshmemSize },
    { "nCreateDump", "(IZ)J", (void*) createDump },
    { "nAddToDump", "(JLjava/lang/String;Ljava/lang/String;JJJ[B)V", (void*) addToDump },
    { "nAddToDump", "(JLjava/lang/String;)V", (void*) addFileToDump },
    { "nFinishDump", "(J)V", (void*) finishDump },
    { "nFinishDumpInMemory", "(J)J", (void*) finishDumpInMemory },
    { "nSaveBuffer", "(Ljava/lang/String;Ljava/lang/String;JJJ[B)V", (void*) saveBuffer },
    { "nativeInit", "()V", (void*) nativeInit },
    { "nativeDestructor",   "()V",     (void*)nativeDestructor }
};

int register_android_server_GraphicsStatsService(JNIEnv* env)
{
    jclass graphicsStatsService_class = FindClassOrDie(env,
            "com/android/server/GraphicsStatsService");
    gGraphicsStatsService_pullGraphicsStatsMethodID = GetMethodIDOrDie(env,
            graphicsStatsService_class, "pullGraphicsStats", "(Z)J");
    return jniRegisterNativeMethods(env, "com/android/server/GraphicsStatsService",
                                    sMethods, NELEM(sMethods));
}

} // namespace android
