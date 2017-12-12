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

static const JNINativeMethod sMethods[] = {
    { "nGetAshmemSize", "()I", (void*) getAshmemSize },
    { "nCreateDump", "(IZ)J", (void*) createDump },
    { "nAddToDump", "(JLjava/lang/String;Ljava/lang/String;JJJ[B)V", (void*) addToDump },
    { "nAddToDump", "(JLjava/lang/String;)V", (void*) addFileToDump },
    { "nFinishDump", "(J)V", (void*) finishDump },
    { "nSaveBuffer", "(Ljava/lang/String;Ljava/lang/String;JJJ[B)V", (void*) saveBuffer },
};

int register_android_server_GraphicsStatsService(JNIEnv* env)
{
    return jniRegisterNativeMethods(env, "com/android/server/GraphicsStatsService",
                                    sMethods, NELEM(sMethods));
}

} // namespace android