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

#undef LOG_TAG
#define LOG_TAG "GraphicsStatsService"

#include <JankTracker.h>
#include <log/log.h>
#include <nativehelper/ScopedPrimitiveArray.h>
#include <nativehelper/ScopedUtfChars.h>
#include <service/GraphicsStatsService.h>
#include <stats_event.h>
#include <stats_pull_atom_callback.h>
#include <statslog.h>

#include "android/graphics/jni_runtime.h"
#include "GraphicsJNI.h"

namespace android {

using namespace android::uirenderer;

static jint getAshmemSize(JNIEnv*, jobject) {
    return sizeof(ProfileData);
}

static jlong createDump(JNIEnv*, jobject, jint fd, jboolean isProto) {
    GraphicsStatsService::Dump* dump =
            GraphicsStatsService::createDump(fd,
                                             isProto ? GraphicsStatsService::DumpType::Protobuf
                                                     : GraphicsStatsService::DumpType::Text);
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
                            "Buffer size %zu doesn't match expected %zu!", buffer.size(),
                            sizeof(ProfileData));
        data = reinterpret_cast<const ProfileData*>(buffer.get());
    }
    if (jpath != nullptr) {
        ScopedUtfChars pathChars(env, jpath);
        LOG_ALWAYS_FATAL_IF(pathChars.size() <= 0 || !pathChars.c_str(),
                            "Failed to get path chars");
        path.assign(pathChars.c_str(), pathChars.size());
    }
    ScopedUtfChars packageChars(env, jpackage);
    LOG_ALWAYS_FATAL_IF(packageChars.size() <= 0 || !packageChars.c_str(),
                        "Failed to get path chars");
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

static void finishDumpInMemory(JNIEnv* env, jobject, jlong dumpPtr, jlong pulledData,
                               jboolean lastFullDay) {
    GraphicsStatsService::Dump* dump = reinterpret_cast<GraphicsStatsService::Dump*>(dumpPtr);
    AStatsEventList* data = reinterpret_cast<AStatsEventList*>(pulledData);
    GraphicsStatsService::finishDumpInMemory(dump, data, lastFullDay == JNI_TRUE);
}

static void saveBuffer(JNIEnv* env, jobject clazz, jstring jpath, jstring jpackage,
                       jlong versionCode, jlong startTime, jlong endTime, jbyteArray jdata) {
    ScopedByteArrayRO buffer(env, jdata);
    LOG_ALWAYS_FATAL_IF(buffer.size() != sizeof(ProfileData),
                        "Buffer size %zu doesn't match expected %zu!", buffer.size(),
                        sizeof(ProfileData));
    ScopedUtfChars pathChars(env, jpath);
    LOG_ALWAYS_FATAL_IF(pathChars.size() <= 0 || !pathChars.c_str(), "Failed to get path chars");
    ScopedUtfChars packageChars(env, jpackage);
    LOG_ALWAYS_FATAL_IF(packageChars.size() <= 0 || !packageChars.c_str(),
                        "Failed to get path chars");

    const std::string path(pathChars.c_str(), pathChars.size());
    const std::string package(packageChars.c_str(), packageChars.size());
    const ProfileData* data = reinterpret_cast<const ProfileData*>(buffer.get());
    GraphicsStatsService::saveBuffer(path, package, versionCode, startTime, endTime, data);
}

static jobject gGraphicsStatsServiceObject = nullptr;
static jmethodID gGraphicsStatsService_pullGraphicsStatsMethodID;

static JNIEnv* getJNIEnv() {
    JavaVM* vm = GraphicsJNI::getJavaVM();
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        if (vm->AttachCurrentThreadAsDaemon(&env, nullptr) != JNI_OK) {
            LOG_ALWAYS_FATAL("Failed to AttachCurrentThread!");
        }
    }
    return env;
}

// graphicsStatsPullCallback is invoked by statsd service to pull GRAPHICS_STATS atom.
static AStatsManager_PullAtomCallbackReturn graphicsStatsPullCallback(int32_t atom_tag,
                                                                      AStatsEventList* data,
                                                                      void* cookie) {
    JNIEnv* env = getJNIEnv();
    if (!env) {
        return false;
    }
    if (gGraphicsStatsServiceObject == nullptr) {
        ALOGE("Failed to get graphicsstats service");
        return AStatsManager_PULL_SKIP;
    }

    for (bool lastFullDay : {true, false}) {
        env->CallVoidMethod(gGraphicsStatsServiceObject,
                            gGraphicsStatsService_pullGraphicsStatsMethodID,
                            (jboolean)(lastFullDay ? JNI_TRUE : JNI_FALSE),
                            reinterpret_cast<jlong>(data));
        if (env->ExceptionCheck()) {
            env->ExceptionDescribe();
            env->ExceptionClear();
            ALOGE("Failed to invoke graphicsstats service");
            return AStatsManager_PULL_SKIP;
        }
    }
    return AStatsManager_PULL_SUCCESS;
}

// Register a puller for GRAPHICS_STATS atom with the statsd service.
static void nativeInit(JNIEnv* env, jobject javaObject) {
    gGraphicsStatsServiceObject = env->NewGlobalRef(javaObject);
    AStatsManager_PullAtomMetadata* metadata = AStatsManager_PullAtomMetadata_obtain();
    AStatsManager_PullAtomMetadata_setCoolDownMillis(metadata, 10);             // 10 milliseconds
    AStatsManager_PullAtomMetadata_setTimeoutMillis(metadata, 2 * MS_PER_SEC);  // 2 seconds

    AStatsManager_setPullAtomCallback(android::util::GRAPHICS_STATS, metadata,
                                      &graphicsStatsPullCallback, nullptr);

    AStatsManager_PullAtomMetadata_release(metadata);
}

static void nativeDestructor(JNIEnv* env, jobject javaObject) {
    AStatsManager_clearPullAtomCallback(android::util::GRAPHICS_STATS);
    env->DeleteGlobalRef(gGraphicsStatsServiceObject);
    gGraphicsStatsServiceObject = nullptr;
}

} // namespace android
using namespace android;

static const JNINativeMethod sMethods[] =
        {{"nGetAshmemSize", "()I", (void*)getAshmemSize},
         {"nCreateDump", "(IZ)J", (void*)createDump},
         {"nAddToDump", "(JLjava/lang/String;Ljava/lang/String;JJJ[B)V", (void*)addToDump},
         {"nAddToDump", "(JLjava/lang/String;)V", (void*)addFileToDump},
         {"nFinishDump", "(J)V", (void*)finishDump},
         {"nFinishDumpInMemory", "(JJZ)V", (void*)finishDumpInMemory},
         {"nSaveBuffer", "(Ljava/lang/String;Ljava/lang/String;JJJ[B)V", (void*)saveBuffer},
         {"nativeInit", "()V", (void*)nativeInit},
         {"nativeDestructor", "()V", (void*)nativeDestructor}};

int register_android_graphics_GraphicsStatsService(JNIEnv* env) {
    jclass graphicsStatsService_class =
            FindClassOrDie(env, "android/graphics/GraphicsStatsService");
    gGraphicsStatsService_pullGraphicsStatsMethodID =
            GetMethodIDOrDie(env, graphicsStatsService_class, "pullGraphicsStats", "(ZJ)V");
    return jniRegisterNativeMethods(env, "android/graphics/GraphicsStatsService", sMethods,
                                    NELEM(sMethods));
}
