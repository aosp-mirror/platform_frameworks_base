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

#include "BinderIncrementalService.h"

#include <android-base/logging.h>
#include <android-base/no_destructor.h>
#include <android/os/IVold.h>
#include <binder/IResultReceiver.h>
#include <binder/PermissionCache.h>
#include <incfs.h>

#include "ServiceWrappers.h"
#include "jni.h"
#include "nativehelper/JNIHelp.h"
#include "path.h"

using namespace std::literals;
using namespace android::incremental;

namespace android::os::incremental {

static constexpr auto kAndroidDataEnv = "ANDROID_DATA"sv;
static constexpr auto kDataDir = "/data"sv;
static constexpr auto kIncrementalSubDir = "incremental"sv;

static std::string getIncrementalDir() {
    const char* dataDir = getenv(kAndroidDataEnv.data());
    if (!dataDir || !*dataDir) {
        dataDir = kDataDir.data();
    }
    return path::normalize(path::join(dataDir, kIncrementalSubDir));
}

static bool incFsEnabled() {
    // TODO(b/136132412): use vold to check /sys/fs/incfs/version (per selinux compliance)
    return incfs::enabled();
}

static bool incFsValid(const sp<IVold>& vold) {
    bool enabled = false;
    auto status = vold->incFsEnabled(&enabled);
    if (!status.isOk() || !enabled) {
        return false;
    }
    return true;
}

BinderIncrementalService::BinderIncrementalService(const sp<IServiceManager>& sm, JNIEnv* env)
      : mImpl(RealServiceManager(sm, env), getIncrementalDir()) {}

BinderIncrementalService* BinderIncrementalService::start(JNIEnv* env) {
    if (!incFsEnabled()) {
        return nullptr;
    }

    IPCThreadState::self()->disableBackgroundScheduling(true);
    sp<IServiceManager> sm(defaultServiceManager());
    if (!sm) {
        return nullptr;
    }

    sp<IBinder> voldBinder(sm->getService(String16("vold")));
    if (voldBinder == nullptr) {
        return nullptr;
    }
    sp<IVold> vold = interface_cast<IVold>(voldBinder);
    if (!incFsValid(vold)) {
        return nullptr;
    }

    sp<BinderIncrementalService> self(new BinderIncrementalService(sm, env));
    status_t ret = sm->addService(String16{getServiceName()}, self);
    if (ret != android::OK) {
        return nullptr;
    }
    sp<ProcessState> ps(ProcessState::self());
    ps->startThreadPool();
    ps->giveThreadPoolName();
    // sm->addService increments the reference count, and now we're OK with returning the pointer.
    return self.get();
}

status_t BinderIncrementalService::dump(int fd, const Vector<String16>&) {
    static const android::base::NoDestructor<String16> kDump("android.permission.DUMP");
    if (!PermissionCache::checkCallingPermission(*kDump)) {
        return PERMISSION_DENIED;
    }
    mImpl.onDump(fd);
    return NO_ERROR;
}

void BinderIncrementalService::onSystemReady() {
    mImpl.onSystemReady();
}

static binder::Status ok() {
    return binder::Status::ok();
}

binder::Status BinderIncrementalService::openStorage(const std::string& path,
                                                     int32_t* _aidl_return) {
    *_aidl_return = mImpl.openStorage(path);
    return ok();
}

binder::Status BinderIncrementalService::createStorage(
        const ::std::string& path, const ::android::content::pm::DataLoaderParamsParcel& params,
        int32_t createMode,
        const ::android::sp<::android::content::pm::IDataLoaderStatusListener>& statusListener,
        const ::android::os::incremental::StorageHealthCheckParams& healthCheckParams,
        const ::android::sp<::android::os::incremental::IStorageHealthListener>& healthListener,
        int32_t* _aidl_return) {
    *_aidl_return =
            mImpl.createStorage(path, const_cast<content::pm::DataLoaderParamsParcel&&>(params),
                                android::incremental::IncrementalService::CreateOptions(createMode),
                                statusListener,
                                const_cast<StorageHealthCheckParams&&>(healthCheckParams),
                                healthListener);
    return ok();
}

binder::Status BinderIncrementalService::createLinkedStorage(const std::string& path,
                                                             int32_t otherStorageId,
                                                             int32_t createMode,
                                                             int32_t* _aidl_return) {
    *_aidl_return =
            mImpl.createLinkedStorage(path, otherStorageId,
                                      android::incremental::IncrementalService::CreateOptions(
                                              createMode));
    return ok();
}

binder::Status BinderIncrementalService::makeBindMount(int32_t storageId,
                                                       const std::string& sourcePath,
                                                       const std::string& targetFullPath,
                                                       int32_t bindType, int32_t* _aidl_return) {
    *_aidl_return = mImpl.bind(storageId, sourcePath, targetFullPath,
                               android::incremental::IncrementalService::BindKind(bindType));
    return ok();
}

binder::Status BinderIncrementalService::deleteBindMount(int32_t storageId,
                                                         const std::string& targetFullPath,
                                                         int32_t* _aidl_return) {
    *_aidl_return = mImpl.unbind(storageId, targetFullPath);
    return ok();
}

binder::Status BinderIncrementalService::deleteStorage(int32_t storageId) {
    mImpl.deleteStorage(storageId);
    return ok();
}

binder::Status BinderIncrementalService::makeDirectory(int32_t storageId, const std::string& path,
                                                       int32_t* _aidl_return) {
    *_aidl_return = mImpl.makeDir(storageId, path);
    return ok();
}

static std::tuple<int, incfs::FileId, incfs::NewFileParams> toMakeFileParams(
        const android::os::incremental::IncrementalNewFileParams& params) {
    incfs::FileId id;
    if (params.fileId.empty()) {
        if (params.metadata.empty()) {
            return {EINVAL, {}, {}};
        }
        id = IncrementalService::idFromMetadata(params.metadata);
    } else if (params.fileId.size() != sizeof(id)) {
        return {EINVAL, {}, {}};
    } else {
        memcpy(&id, params.fileId.data(), sizeof(id));
    }
    incfs::NewFileParams nfp;
    nfp.size = params.size;
    nfp.metadata = {(const char*)params.metadata.data(), (IncFsSize)params.metadata.size()};
    if (!params.signature) {
        nfp.signature = {};
    } else {
        nfp.signature = {(const char*)params.signature->data(),
                         (IncFsSize)params.signature->size()};
    }
    return {0, id, nfp};
}

binder::Status BinderIncrementalService::makeFile(
        int32_t storageId, const std::string& path,
        const ::android::os::incremental::IncrementalNewFileParams& params, int32_t* _aidl_return) {
    auto [err, fileId, nfp] = toMakeFileParams(params);
    if (err) {
        *_aidl_return = err;
        return ok();
    }

    *_aidl_return = mImpl.makeFile(storageId, path, 0777, fileId, nfp);
    return ok();
}
binder::Status BinderIncrementalService::makeFileFromRange(int32_t storageId,
                                                           const std::string& targetPath,
                                                           const std::string& sourcePath,
                                                           int64_t start, int64_t end,
                                                           int32_t* _aidl_return) {
    // TODO(b/136132412): implement this
    *_aidl_return = ENOSYS; // not implemented
    return ok();
}

binder::Status BinderIncrementalService::makeLink(int32_t sourceStorageId,
                                                  const std::string& sourcePath,
                                                  int32_t destStorageId,
                                                  const std::string& destPath,
                                                  int32_t* _aidl_return) {
    *_aidl_return = mImpl.link(sourceStorageId, sourcePath, destStorageId, destPath);
    return ok();
}

binder::Status BinderIncrementalService::unlink(int32_t storageId, const std::string& path,
                                                int32_t* _aidl_return) {
    *_aidl_return = mImpl.unlink(storageId, path);
    return ok();
}

binder::Status BinderIncrementalService::isFileRangeLoaded(int32_t storageId,
                                                           const std::string& path, int64_t start,
                                                           int64_t end, bool* _aidl_return) {
    // TODO: implement
    *_aidl_return = false;
    return ok();
}

binder::Status BinderIncrementalService::getMetadataByPath(int32_t storageId,
                                                           const std::string& path,
                                                           std::vector<uint8_t>* _aidl_return) {
    auto metadata = mImpl.getMetadata(storageId, path);
    _aidl_return->assign(metadata.begin(), metadata.end());
    return ok();
}

static FileId toFileId(const std::vector<uint8_t>& id) {
    FileId fid;
    memcpy(&fid, id.data(), id.size());
    return fid;
}

binder::Status BinderIncrementalService::getMetadataById(int32_t storageId,
                                                         const std::vector<uint8_t>& id,
                                                         std::vector<uint8_t>* _aidl_return) {
    if (id.size() != sizeof(incfs::FileId)) {
        return ok();
    }
    auto fid = toFileId(id);
    auto metadata = mImpl.getMetadata(storageId, fid);
    _aidl_return->assign(metadata.begin(), metadata.end());
    return ok();
}

binder::Status BinderIncrementalService::makeDirectories(int32_t storageId, const std::string& path,
                                                         int32_t* _aidl_return) {
    *_aidl_return = mImpl.makeDirs(storageId, path);
    return ok();
}

binder::Status BinderIncrementalService::startLoading(int32_t storageId, bool* _aidl_return) {
    *_aidl_return = mImpl.startLoading(storageId);
    return ok();
}

binder::Status BinderIncrementalService::configureNativeBinaries(
        int32_t storageId, const std::string& apkFullPath, const std::string& libDirRelativePath,
        const std::string& abi, bool* _aidl_return) {
    *_aidl_return = mImpl.configureNativeBinaries(storageId, apkFullPath, libDirRelativePath, abi);
    return ok();
}

binder::Status BinderIncrementalService::waitForNativeBinariesExtraction(int storageId,
                                                                         bool* _aidl_return) {
    *_aidl_return = mImpl.waitForNativeBinariesExtraction(storageId);
    return ok();
}

} // namespace android::os::incremental

jlong Incremental_IncrementalService_Start(JNIEnv* env) {
    return (jlong)android::os::incremental::BinderIncrementalService::start(env);
}
void Incremental_IncrementalService_OnSystemReady(jlong self) {
    if (self) {
        ((android::os::incremental::BinderIncrementalService*)self)->onSystemReady();
    }
}
void Incremental_IncrementalService_OnDump(jlong self, jint fd) {
    if (self) {
        ((android::os::incremental::BinderIncrementalService*)self)->dump(fd, {});
    } else {
        dprintf(fd, "BinderIncrementalService is stopped.");
    }
}
