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

#define LOG_TAG "dataloader-jni"

#include <vector>

#include "core_jni_helpers.h"
#include "dataloader_ndk.h"
#include "jni.h"

namespace android {
namespace {

struct JniIds {
    jfieldID dataBlockFileIno;
    jfieldID dataBlockBlockIndex;
    jfieldID dataBlockDataBytes;
    jfieldID dataBlockCompressionType;

    JniIds(JNIEnv* env) {
        const auto dataBlock =
                FindClassOrDie(env,
                               "android/service/incremental/"
                               "IncrementalDataLoaderService$FileSystemConnector$DataBlock");
        dataBlockFileIno = GetFieldIDOrDie(env, dataBlock, "mFileIno", "J");
        dataBlockBlockIndex =
                GetFieldIDOrDie(env, dataBlock, "mBlockIndex", "I");
        dataBlockDataBytes = GetFieldIDOrDie(env, dataBlock, "mDataBytes", "[B");
        dataBlockCompressionType =
                GetFieldIDOrDie(env, dataBlock, "mCompressionType", "I");
    }
};

const JniIds& jniIds(JNIEnv* env) {
    static const JniIds ids(env);
    return ids;
}

class ScopedJniArrayCritical {
public:
    ScopedJniArrayCritical(JNIEnv* env, jarray array) : mEnv(env), mArr(array) {
        mPtr = array ? env->GetPrimitiveArrayCritical(array, nullptr) : nullptr;
    }
    ~ScopedJniArrayCritical() {
        if (mPtr) {
            mEnv->ReleasePrimitiveArrayCritical(mArr, mPtr, 0);
            mPtr = nullptr;
        }
    }

    ScopedJniArrayCritical(const ScopedJniArrayCritical&) = delete;
    void operator=(const ScopedJniArrayCritical&) = delete;

    ScopedJniArrayCritical(ScopedJniArrayCritical&& other)
        : mEnv(other.mEnv),
          mArr(std::exchange(mArr, nullptr)),
          mPtr(std::exchange(mPtr, nullptr)) {}
    ScopedJniArrayCritical& operator=(ScopedJniArrayCritical&& other) {
        mEnv = other.mEnv;
        mArr = std::exchange(other.mArr, nullptr);
        mPtr = std::exchange(other.mPtr, nullptr);
        return *this;
    }

    void* ptr() const { return mPtr; }
    jsize size() const { return mArr ? mEnv->GetArrayLength(mArr) : 0; }

private:
    JNIEnv* mEnv;
    jarray mArr;
    void* mPtr;
};

static jboolean nativeCreateDataLoader(JNIEnv* env,
                                       jobject thiz,
                                       jint storageId,
                                       jobject control,
                                       jobject params,
                                       jobject callback) {
    ALOGE("nativeCreateDataLoader: %p/%d, %d, %p, %p, %p", thiz,
          env->GetObjectRefType(thiz), storageId, params, control, callback);
    return DataLoaderService_OnCreate(env, thiz,
                     storageId, control, params, callback);
}

static jboolean nativeStartDataLoader(JNIEnv* env,
                                      jobject thiz,
                                      jint storageId) {
    ALOGE("nativeStartDataLoader: %p/%d, %d", thiz, env->GetObjectRefType(thiz),
          storageId);
    return DataLoaderService_OnStart(storageId);
}

static jboolean nativeStopDataLoader(JNIEnv* env,
                                     jobject thiz,
                                     jint storageId) {
    ALOGE("nativeStopDataLoader: %p/%d, %d", thiz, env->GetObjectRefType(thiz),
          storageId);
    return DataLoaderService_OnStop(storageId);
}

static jboolean nativeDestroyDataLoader(JNIEnv* env,
                                        jobject thiz,
                                        jint storageId) {
    ALOGE("nativeDestroyDataLoader: %p/%d, %d", thiz,
          env->GetObjectRefType(thiz), storageId);
    return DataLoaderService_OnDestroy(storageId);
}


static jboolean nativeOnFileCreated(JNIEnv* env,
                                   jobject thiz,
                                   jint storageId,
                                   jlong inode,
                                   jbyteArray metadata) {
    ALOGE("nativeOnFileCreated: %p/%d, %d", thiz,
          env->GetObjectRefType(thiz), storageId);
    return DataLoaderService_OnFileCreated(storageId, inode, metadata);
}

static jboolean nativeIsFileRangeLoadedNode(JNIEnv* env,
                                            jobject clazz,
                                            jlong self,
                                            jlong node,
                                            jlong start,
                                            jlong end) {
    // TODO(b/136132412): implement this
    return JNI_FALSE;
}

static jboolean nativeWriteMissingData(JNIEnv* env,
                                       jobject clazz,
                                       jlong self,
                                       jobjectArray data_block,
                                       jobjectArray hash_blocks) {
    const auto& jni = jniIds(env);
    auto length = env->GetArrayLength(data_block);
    std::vector<incfs_new_data_block> instructions(length);

    // May not call back into Java after even a single jniArrayCritical, so
    // let's collect the Java pointers to byte buffers first and lock them in
    // memory later.

    std::vector<jbyteArray> blockBuffers(length);
    for (int i = 0; i != length; ++i) {
        auto& inst = instructions[i];
        auto jniBlock = env->GetObjectArrayElement(data_block, i);
        inst.file_ino = env->GetLongField(jniBlock, jni.dataBlockFileIno);
        inst.block_index = env->GetIntField(jniBlock, jni.dataBlockBlockIndex);
        blockBuffers[i] = (jbyteArray)env->GetObjectField(
                jniBlock, jni.dataBlockDataBytes);
        inst.compression = (incfs_compression_alg)env->GetIntField(
                jniBlock, jni.dataBlockCompressionType);
    }

    std::vector<ScopedJniArrayCritical> jniScopedArrays;
    jniScopedArrays.reserve(length);
    for (int i = 0; i != length; ++i) {
        auto buffer = blockBuffers[i];
        jniScopedArrays.emplace_back(env, buffer);
        auto& inst = instructions[i];
        inst.data = (uint64_t)jniScopedArrays.back().ptr();
        inst.data_len = jniScopedArrays.back().size();
    }

    auto connector = (DataLoaderFilesystemConnectorPtr)self;
    if (auto err = DataLoader_FilesystemConnector_writeBlocks(
                             connector, instructions.data(), length);
        err < 0) {
        jniScopedArrays.clear();
        return JNI_FALSE;
    }

    return JNI_TRUE;
}

static jboolean nativeWriteSignerDataNode(JNIEnv* env,
                                          jobject clazz,
                                          jlong self,
                                          jstring relative_path,
                                          jbyteArray signer_data) {
    // TODO(b/136132412): implement this
    return JNI_TRUE;
}

static jbyteArray nativeGetFileMetadataNode(JNIEnv* env,
                                            jobject clazz,
                                            jlong self,
                                            jlong inode) {
    auto connector = (DataLoaderFilesystemConnectorPtr)self;
    std::vector<char> metadata(INCFS_MAX_FILE_ATTR_SIZE);
    size_t size = metadata.size();
    if (DataLoader_FilesystemConnector_getRawMetadata(connector, inode,
                  metadata.data(), &size) < 0) {
        size = 0;
    }
    metadata.resize(size);

    auto buffer = env->NewByteArray(metadata.size());
    env->SetByteArrayRegion(buffer, 0, metadata.size(),
                            (jbyte*)metadata.data());
    return buffer;
}

static jbyteArray nativeGetFileInfoNode(JNIEnv* env,
                                        jobject clazz,
                                        jlong self,
                                        jlong inode) {
    // TODO(b/136132412): implement this
    return nullptr;
}

static jboolean nativeReportStatus(JNIEnv* env,
                                   jobject clazz,
                                   jlong self,
                                   jint status) {
    auto listener = (DataLoaderStatusListenerPtr)self;
    return DataLoader_StatusListener_reportStatus(listener,
                     (DataLoaderStatus)status);
}

static const JNINativeMethod dlc_method_table[] = {
        {"nativeCreateDataLoader",
         "(ILandroid/os/incremental/IncrementalFileSystemControlParcel;"
         "Landroid/os/incremental/IncrementalDataLoaderParamsParcel;"
         "Landroid/content/pm/IDataLoaderStatusListener;)Z",
         (void*)nativeCreateDataLoader},
        {"nativeStartDataLoader", "(I)Z", (void*)nativeStartDataLoader},
        {"nativeStopDataLoader", "(I)Z", (void*)nativeStopDataLoader},
        {"nativeDestroyDataLoader", "(I)Z", (void*)nativeDestroyDataLoader},
        {"nativeIsFileRangeLoadedNode", "(JJJJ)Z",
         (void*)nativeIsFileRangeLoadedNode},
        {"nativeWriteMissingData",
         "(J[Landroid/service/incremental/"
         "IncrementalDataLoaderService$FileSystemConnector$DataBlock;[Landroid/service/incremental/"
         "IncrementalDataLoaderService$FileSystemConnector$HashBlock;)Z",
         (void*)nativeWriteMissingData},
        {"nativeWriteSignerDataNode", "(JJ[B)Z",
         (void*)nativeWriteSignerDataNode},
        {"nativeGetFileMetadataNode", "(JJ)[B",
         (void*)nativeGetFileMetadataNode},
        {"nativeGetFileInfoNode", "(JJ)[B", (void*)nativeGetFileInfoNode},
        {"nativeReportStatus", "(JI)Z", (void*)nativeReportStatus},
        {"nativeOnFileCreated", "(IJ[B)Z", (void*)nativeOnFileCreated},
};

}  // namespace

int register_android_service_DataLoaderService(JNIEnv* env) {
    return jniRegisterNativeMethods(env,
                                    "android/service/incremental/IncrementalDataLoaderService",
                                    dlc_method_table, NELEM(dlc_method_table));
}

}  // namespace android
