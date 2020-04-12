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

#define LOG_TAG "IncrementalService"

#include "ServiceWrappers.h"

#include <android-base/logging.h>
#include <utils/String16.h>

using namespace std::literals;

namespace android::os::incremental {

static constexpr auto kVoldServiceName = "vold"sv;
static constexpr auto kDataLoaderManagerName = "dataloader_manager"sv;

class RealVoldService : public VoldServiceWrapper {
public:
    RealVoldService(const sp<os::IVold> vold) : mInterface(std::move(vold)) {}
    ~RealVoldService() = default;
    binder::Status mountIncFs(const std::string& backingPath, const std::string& targetDir,
                              int32_t flags,
                              IncrementalFileSystemControlParcel* _aidl_return) const final {
        return mInterface->mountIncFs(backingPath, targetDir, flags, _aidl_return);
    }
    binder::Status unmountIncFs(const std::string& dir) const final {
        return mInterface->unmountIncFs(dir);
    }
    binder::Status bindMount(const std::string& sourceDir,
                             const std::string& targetDir) const final {
        return mInterface->bindMount(sourceDir, targetDir);
    }
    binder::Status setIncFsMountOptions(
            const ::android::os::incremental::IncrementalFileSystemControlParcel& control,
            bool enableReadLogs) const final {
        return mInterface->setIncFsMountOptions(control, enableReadLogs);
    }

private:
    sp<os::IVold> mInterface;
};

class RealDataLoaderManager : public DataLoaderManagerWrapper {
public:
    RealDataLoaderManager(const sp<content::pm::IDataLoaderManager> manager)
          : mInterface(manager) {}
    ~RealDataLoaderManager() = default;
    binder::Status initializeDataLoader(MountId mountId, const DataLoaderParamsParcel& params,
                                        const FileSystemControlParcel& control,
                                        const sp<IDataLoaderStatusListener>& listener,
                                        bool* _aidl_return) const final {
        return mInterface->initializeDataLoader(mountId, params, control, listener, _aidl_return);
    }
    binder::Status getDataLoader(MountId mountId, sp<IDataLoader>* _aidl_return) const final {
        return mInterface->getDataLoader(mountId, _aidl_return);
    }
    binder::Status destroyDataLoader(MountId mountId) const final {
        return mInterface->destroyDataLoader(mountId);
    }

private:
    sp<content::pm::IDataLoaderManager> mInterface;
};

class RealAppOpsManager : public AppOpsManagerWrapper {
public:
    ~RealAppOpsManager() = default;
    binder::Status checkPermission(const char* permission, const char* operation,
                                   const char* package) const final {
        return android::incremental::CheckPermissionForDataDelivery(permission, operation, package);
    }
    void startWatchingMode(int32_t op, const String16& packageName,
                           const sp<IAppOpsCallback>& callback) final {
        mAppOpsManager.startWatchingMode(op, packageName, callback);
    }
    void stopWatchingMode(const sp<IAppOpsCallback>& callback) final {
        mAppOpsManager.stopWatchingMode(callback);
    }

private:
    android::AppOpsManager mAppOpsManager;
};

class RealJniWrapper final : public JniWrapper {
public:
    RealJniWrapper(JavaVM* jvm);
    void initializeForCurrentThread() const final;

    static JavaVM* getJvm(JNIEnv* env);

private:
    JavaVM* const mJvm;
};

class RealIncFs : public IncFsWrapper {
public:
    RealIncFs() = default;
    ~RealIncFs() = default;
    Control createControl(IncFsFd cmd, IncFsFd pendingReads, IncFsFd logs) const final {
        return incfs::createControl(cmd, pendingReads, logs);
    }
    ErrorCode makeFile(const Control& control, std::string_view path, int mode, FileId id,
                       NewFileParams params) const final {
        return incfs::makeFile(control, path, mode, id, params);
    }
    ErrorCode makeDir(const Control& control, std::string_view path, int mode) const final {
        return incfs::makeDir(control, path, mode);
    }
    RawMetadata getMetadata(const Control& control, FileId fileid) const final {
        return incfs::getMetadata(control, fileid);
    }
    RawMetadata getMetadata(const Control& control, std::string_view path) const final {
        return incfs::getMetadata(control, path);
    }
    FileId getFileId(const Control& control, std::string_view path) const final {
        return incfs::getFileId(control, path);
    }
    ErrorCode link(const Control& control, std::string_view from, std::string_view to) const final {
        return incfs::link(control, from, to);
    }
    ErrorCode unlink(const Control& control, std::string_view path) const final {
        return incfs::unlink(control, path);
    }
    base::unique_fd openForSpecialOps(const Control& control, FileId id) const final {
        return base::unique_fd{incfs::openForSpecialOps(control, id).release()};
    }
    ErrorCode writeBlocks(Span<const DataBlock> blocks) const final {
        return incfs::writeBlocks(blocks);
    }
};

RealServiceManager::RealServiceManager(sp<IServiceManager> serviceManager, JNIEnv* env)
      : mServiceManager(std::move(serviceManager)), mJvm(RealJniWrapper::getJvm(env)) {}

template <class INTERFACE>
sp<INTERFACE> RealServiceManager::getRealService(std::string_view serviceName) const {
    sp<IBinder> binder =
            mServiceManager->getService(String16(serviceName.data(), serviceName.size()));
    if (!binder) {
        return nullptr;
    }
    return interface_cast<INTERFACE>(binder);
}

std::unique_ptr<VoldServiceWrapper> RealServiceManager::getVoldService() {
    sp<os::IVold> vold = RealServiceManager::getRealService<os::IVold>(kVoldServiceName);
    if (vold != 0) {
        return std::make_unique<RealVoldService>(vold);
    }
    return nullptr;
}

std::unique_ptr<DataLoaderManagerWrapper> RealServiceManager::getDataLoaderManager() {
    sp<IDataLoaderManager> manager =
            RealServiceManager::getRealService<IDataLoaderManager>(kDataLoaderManagerName);
    if (manager) {
        return std::make_unique<RealDataLoaderManager>(manager);
    }
    return nullptr;
}

std::unique_ptr<IncFsWrapper> RealServiceManager::getIncFs() {
    return std::make_unique<RealIncFs>();
}

std::unique_ptr<AppOpsManagerWrapper> RealServiceManager::getAppOpsManager() {
    return std::make_unique<RealAppOpsManager>();
}

std::unique_ptr<JniWrapper> RealServiceManager::getJni() {
    return std::make_unique<RealJniWrapper>(mJvm);
}

static JavaVM* getJavaVm(JNIEnv* env) {
    CHECK(env);
    JavaVM* jvm = nullptr;
    env->GetJavaVM(&jvm);
    CHECK(jvm);
    return jvm;
}

static JNIEnv* getJniEnv(JavaVM* vm) {
    JNIEnv* env;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return nullptr;
    }
    return env;
}

static JNIEnv* getOrAttachJniEnv(JavaVM* jvm) {
    if (!jvm) {
        LOG(ERROR) << "No JVM instance";
        return nullptr;
    }

    JNIEnv* env = getJniEnv(jvm);
    if (!env) {
        int result = jvm->AttachCurrentThread(&env, nullptr);
        if (result != JNI_OK) {
            LOG(ERROR) << "JVM thread attach failed: " << result;
            return nullptr;
        }
        struct VmDetacher {
            VmDetacher(JavaVM* vm) : mVm(vm) {}
            ~VmDetacher() { mVm->DetachCurrentThread(); }

        private:
            JavaVM* const mVm;
        };
        static thread_local VmDetacher detacher(jvm);
    }

    return env;
}

RealJniWrapper::RealJniWrapper(JavaVM* jvm) : mJvm(jvm) {
    CHECK(!!mJvm) << "JVM is unavailable";
}

void RealJniWrapper::initializeForCurrentThread() const {
    (void)getOrAttachJniEnv(mJvm);
}

JavaVM* RealJniWrapper::getJvm(JNIEnv* env) {
    return getJavaVm(env);
}

} // namespace android::os::incremental
