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

#include <binder/IResultReceiver.h>
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

static bool incFsVersionValid(const sp<IVold>& vold) {
    int version = -1;
    auto status = vold->incFsVersion(&version);
    if (!status.isOk() || version <= 0) {
        return false;
    }
    return true;
}

BinderIncrementalService::BinderIncrementalService(const sp<IServiceManager>& sm)
      : mImpl(RealServiceManager(sm), getIncrementalDir()) {}

BinderIncrementalService* BinderIncrementalService::start() {
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
    if (!incFsVersionValid(vold)) {
        return nullptr;
    }

    sp<BinderIncrementalService> self(new BinderIncrementalService(sm));
    status_t ret = sm->addService(String16{getServiceName()}, self);
    if (ret != android::OK) {
        return nullptr;
    }
    sp<ProcessState> ps(ProcessState::self());
    ps->startThreadPool();
    ps->giveThreadPoolName();
    return self.get();
}

status_t BinderIncrementalService::dump(int fd, const Vector<String16>& args) {
    return OK;
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
        const std::string& path, const DataLoaderParamsParcel& params,
        int32_t createMode, int32_t* _aidl_return) {
    *_aidl_return =
            mImpl.createStorage(path, const_cast<DataLoaderParamsParcel&&>(params),
                                android::incremental::IncrementalService::CreateOptions(
                                        createMode));
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
                                                       const std::string& pathUnderStorage,
                                                       const std::string& targetFullPath,
                                                       int32_t bindType, int32_t* _aidl_return) {
    *_aidl_return = mImpl.bind(storageId, pathUnderStorage, targetFullPath,
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

binder::Status BinderIncrementalService::makeDirectory(int32_t storageId,
                                                       const std::string& pathUnderStorage,
                                                       int32_t* _aidl_return) {
    auto inode = mImpl.makeDir(storageId, pathUnderStorage);
    *_aidl_return = inode < 0 ? inode : 0;
    return ok();
}

binder::Status BinderIncrementalService::makeDirectories(int32_t storageId,
                                                         const std::string& pathUnderStorage,
                                                         int32_t* _aidl_return) {
    auto inode = mImpl.makeDirs(storageId, pathUnderStorage);
    *_aidl_return = inode < 0 ? inode : 0;
    return ok();
}

binder::Status BinderIncrementalService::makeFile(int32_t storageId,
                                                  const std::string& pathUnderStorage, int64_t size,
                                                  const std::vector<uint8_t>& metadata,
                                                  int32_t* _aidl_return) {
    auto inode = mImpl.makeFile(storageId, pathUnderStorage, size,
                                {(const char*)metadata.data(), metadata.size()}, {});
    *_aidl_return = inode < 0 ? inode : 0;
    return ok();
}
binder::Status BinderIncrementalService::makeFileFromRange(
        int32_t storageId, const std::string& pathUnderStorage,
        const std::string& sourcePathUnderStorage, int64_t start, int64_t end,
        int32_t* _aidl_return) {
    // TODO(b/136132412): implement this
    *_aidl_return = -1;
    return ok();
}
binder::Status BinderIncrementalService::makeLink(int32_t sourceStorageId,
                                                  const std::string& relativeSourcePath,
                                                  int32_t destStorageId,
                                                  const std::string& relativeDestPath,
                                                  int32_t* _aidl_return) {
    auto sourceInode = mImpl.nodeFor(sourceStorageId, relativeSourcePath);
    auto [targetParentInode, name] = mImpl.parentAndNameFor(destStorageId, relativeDestPath);
    *_aidl_return = mImpl.link(sourceStorageId, sourceInode, targetParentInode, name);
    return ok();
}
binder::Status BinderIncrementalService::unlink(int32_t storageId,
                                                const std::string& pathUnderStorage,
                                                int32_t* _aidl_return) {
    auto [parentNode, name] = mImpl.parentAndNameFor(storageId, pathUnderStorage);
    *_aidl_return = mImpl.unlink(storageId, parentNode, name);
    return ok();
}
binder::Status BinderIncrementalService::isFileRangeLoaded(int32_t storageId,
                                                           const std::string& relativePath,
                                                           int64_t start, int64_t end,
                                                           bool* _aidl_return) {
    *_aidl_return = false;
    return ok();
}
binder::Status BinderIncrementalService::getFileMetadata(int32_t storageId,
                                                         const std::string& relativePath,
                                                         std::vector<uint8_t>* _aidl_return) {
    auto inode = mImpl.nodeFor(storageId, relativePath);
    auto metadata = mImpl.getMetadata(storageId, inode);
    _aidl_return->assign(metadata.begin(), metadata.end());
    return ok();
}
binder::Status BinderIncrementalService::startLoading(int32_t storageId, bool* _aidl_return) {
    *_aidl_return = mImpl.startLoading(storageId);
    return ok();
}
} // namespace android::os::incremental

jlong Incremental_IncrementalService_Start() {
    return (jlong)android::os::incremental::BinderIncrementalService::start();
}
void Incremental_IncrementalService_OnSystemReady(jlong self) {
    if (self) {
        ((android::os::incremental::BinderIncrementalService*)self)->onSystemReady();
    }
}
