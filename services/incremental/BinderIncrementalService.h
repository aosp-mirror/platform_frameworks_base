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

#pragma once

#include <binder/BinderService.h>
#include <binder/IServiceManager.h>
#include <binder/PersistableBundle.h>
#include <jni.h>

#include "IncrementalService.h"
#include "android/os/incremental/BnIncrementalService.h"
#include "incremental_service.h"

namespace android::os::incremental {

class BinderIncrementalService : public BnIncrementalService,
                                 public BinderService<BinderIncrementalService> {
public:
    BinderIncrementalService(const sp<IServiceManager>& sm, JNIEnv* env);

    static BinderIncrementalService* start(JNIEnv* env);
    static const char16_t* getServiceName() { return u"incremental"; }
    status_t dump(int fd, const Vector<String16>& args) final;

    void onSystemReady();
    void onInvalidStorage(int mountId);

    binder::Status openStorage(const std::string& path, int32_t* _aidl_return) final;
    binder::Status createStorage(const ::std::string& path,
                                 const ::android::content::pm::DataLoaderParamsParcel& params,
                                 int32_t createMode, int32_t* _aidl_return) final;
    binder::Status createLinkedStorage(const std::string& path, int32_t otherStorageId,
                                       int32_t createMode, int32_t* _aidl_return) final;
    binder::Status startLoading(
            int32_t storageId, const ::android::content::pm::DataLoaderParamsParcel& params,
            const ::android::sp<::android::content::pm::IDataLoaderStatusListener>& statusListener,
            const ::android::os::incremental::StorageHealthCheckParams& healthCheckParams,
            const ::android::sp<IStorageHealthListener>& healthListener,
            const ::std::vector<::android::os::incremental::PerUidReadTimeouts>& perUidReadTimeouts,
            bool* _aidl_return) final;
    binder::Status onInstallationComplete(int32_t storageId) final;

    binder::Status makeBindMount(int32_t storageId, const std::string& sourcePath,
                                 const std::string& targetFullPath, int32_t bindType,
                                 int32_t* _aidl_return) final;
    binder::Status deleteBindMount(int32_t storageId, const std::string& targetFullPath,
                                   int32_t* _aidl_return) final;
    binder::Status makeDirectory(int32_t storageId, const std::string& path,
                                 int32_t* _aidl_return) final;
    binder::Status makeDirectories(int32_t storageId, const std::string& path,
                                   int32_t* _aidl_return) final;
    binder::Status makeFile(int32_t storageId, const std::string& path,
                            const IncrementalNewFileParams& params,
                            const ::std::optional<::std::vector<uint8_t>>& content,
                            int32_t* _aidl_return) final;
    binder::Status makeFileFromRange(int32_t storageId, const std::string& targetPath,
                                     const std::string& sourcePath, int64_t start, int64_t end,
                                     int32_t* _aidl_return) final;
    binder::Status makeLink(int32_t sourceStorageId, const std::string& sourcePath,
                            int32_t destStorageId, const std::string& destPath,
                            int32_t* _aidl_return) final;
    binder::Status unlink(int32_t storageId, const std::string& path, int32_t* _aidl_return) final;
    binder::Status isFileFullyLoaded(int32_t storageId, const std::string& path,
                                     int32_t* _aidl_return) final;
    binder::Status isFullyLoaded(int32_t storageId, int32_t* _aidl_return) final;
    binder::Status getLoadingProgress(int32_t storageId, float* _aidl_return) final;
    binder::Status getMetadataByPath(int32_t storageId, const std::string& path,
                                     std::vector<uint8_t>* _aidl_return) final;
    binder::Status getMetadataById(int32_t storageId, const std::vector<uint8_t>& id,
                                   std::vector<uint8_t>* _aidl_return) final;
    binder::Status deleteStorage(int32_t storageId) final;
    binder::Status disallowReadLogs(int32_t storageId) final;
    binder::Status configureNativeBinaries(int32_t storageId, const std::string& apkFullPath,
                                           const std::string& libDirRelativePath,
                                           const std::string& abi, bool extractNativeLibs,
                                           bool* _aidl_return) final;
    binder::Status waitForNativeBinariesExtraction(int storageId, bool* _aidl_return) final;
    binder::Status registerLoadingProgressListener(
            int32_t storageId,
            const ::android::sp<::android::os::incremental::IStorageLoadingProgressListener>&
                    progressListener,
            bool* _aidl_return) final;
    binder::Status unregisterLoadingProgressListener(int32_t storageId, bool* _aidl_return) final;
    binder::Status getMetrics(int32_t storageId,
                              android::os::PersistableBundle* _aidl_return) final;

private:
    android::incremental::IncrementalService mImpl;
};

} // namespace android::os::incremental
