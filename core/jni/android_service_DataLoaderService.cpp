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
    return DataLoaderService_OnStart(storageId);
}

static jboolean nativeStopDataLoader(JNIEnv* env,
                                     jobject thiz,
                                     jint storageId) {
    return DataLoaderService_OnStop(storageId);
}

static jboolean nativeDestroyDataLoader(JNIEnv* env,
                                        jobject thiz,
                                        jint storageId) {
    return DataLoaderService_OnDestroy(storageId);
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
         "(ILandroid/content/pm/FileSystemControlParcel;"
         "Landroid/content/pm/DataLoaderParamsParcel;"
         "Landroid/content/pm/IDataLoaderStatusListener;)Z",
         (void*)nativeCreateDataLoader},
        {"nativeStartDataLoader", "(I)Z", (void*)nativeStartDataLoader},
        {"nativeStopDataLoader", "(I)Z", (void*)nativeStopDataLoader},
        {"nativeDestroyDataLoader", "(I)Z", (void*)nativeDestroyDataLoader},
        {"nativeReportStatus", "(JI)Z", (void*)nativeReportStatus},
};

}  // namespace

int register_android_service_DataLoaderService(JNIEnv* env) {
    return jniRegisterNativeMethods(env,
                                    "android/service/dataloader/DataLoaderService",
                                    dlc_method_table, NELEM(dlc_method_table));
}

}  // namespace android
