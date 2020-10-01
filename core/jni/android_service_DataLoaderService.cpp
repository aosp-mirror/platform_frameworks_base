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

#include "core_jni_helpers.h"
#include "dataloader_ndk.h"

namespace android {
namespace {

static jboolean nativeCreateDataLoader(JNIEnv* env,
                                       jobject thiz,
                                       jint storageId,
                                       jobject control,
                                       jobject params,
                                       jobject callback) {
    return DataLoaderService_OnCreate(env, thiz,
                     storageId, control, params, callback);
}

static jboolean nativeStartDataLoader(JNIEnv* env,
                                      jobject thiz,
                                      jint storageId) {
    return DataLoaderService_OnStart(env, storageId);
}

static jboolean nativeStopDataLoader(JNIEnv* env,
                                     jobject thiz,
                                     jint storageId) {
    return DataLoaderService_OnStop(env, storageId);
}

static jboolean nativeDestroyDataLoader(JNIEnv* env,
                                        jobject thiz,
                                        jint storageId) {
    return DataLoaderService_OnDestroy(env, storageId);
}

static jboolean nativePrepareImage(JNIEnv* env, jobject thiz, jint storageId,
                                   jobjectArray addedFiles, jobjectArray removedFiles) {
    return DataLoaderService_OnPrepareImage(env, storageId, addedFiles, removedFiles);
}

static void nativeWriteData(JNIEnv* env,
                            jobject clazz,
                            jlong self,
                            jstring name,
                            jlong offsetBytes,
                            jlong lengthBytes,
                            jobject incomingFd) {
    auto connector = (DataLoaderFilesystemConnectorPtr)self;
    return DataLoader_FilesystemConnector_writeData(connector, name, offsetBytes, lengthBytes, incomingFd);
}

static const JNINativeMethod dlc_method_table[] = {
        {"nativeCreateDataLoader",
         "(ILandroid/content/pm/FileSystemControlParcel;"
         "Landroid/content/pm/DataLoaderParamsParcel;"
         "Landroid/content/pm/IDataLoaderStatusListener;)Z",
         (void*)nativeCreateDataLoader},
        {"nativeStartDataLoader", "(I)Z", (void*)nativeStartDataLoader},
        {"nativeStopDataLoader", "(I)Z", (void*)nativeStopDataLoader},
        {"nativeDestroyDataLoader", "(I)Z", (void*)nativeDestroyDataLoader},
        {"nativePrepareImage",
         "(I[Landroid/content/pm/InstallationFileParcel;[Ljava/lang/String;)Z",
         (void*)nativePrepareImage},
        {"nativeWriteData", "(JLjava/lang/String;JJLandroid/os/ParcelFileDescriptor;)V",
         (void*)nativeWriteData},
};

}  // namespace

int register_android_service_DataLoaderService(JNIEnv* env) {
    return jniRegisterNativeMethods(env,
                                    "android/service/dataloader/DataLoaderService",
                                    dlc_method_table, NELEM(dlc_method_table));
}

}  // namespace android
