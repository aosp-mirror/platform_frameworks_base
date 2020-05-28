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

#include <MountRegistry.h>
#include <android-base/logging.h>
#include <android/content/pm/IDataLoaderManager.h>
#include <android/os/IVold.h>
#include <binder/AppOpsManager.h>
#include <utils/String16.h>

#include "IncrementalServiceValidation.h"

using namespace std::literals;

namespace android::incremental {

static constexpr auto kVoldServiceName = "vold"sv;
static constexpr auto kDataLoaderManagerName = "dataloader_manager"sv;

class RealVoldService : public VoldServiceWrapper {
public:
    RealVoldService(sp<os::IVold> vold) : mInterface(std::move(vold)) {}
    ~RealVoldService() = default;
    binder::Status mountIncFs(
            const std::string& backingPath, const std::string& targetDir, int32_t flags,
            os::incremental::IncrementalFileSystemControlParcel* _aidl_return) const final {
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
    RealDataLoaderManager(sp<content::pm::IDataLoaderManager> manager)
          : mInterface(std::move(manager)) {}
    ~RealDataLoaderManager() = default;
    binder::Status bindToDataLoader(MountId mountId,
                                    const content::pm::DataLoaderParamsParcel& params,
                                    const sp<content::pm::IDataLoaderStatusListener>& listener,
                                    bool* _aidl_return) const final {
        return mInterface->bindToDataLoader(mountId, params, listener, _aidl_return);
    }
    binder::Status getDataLoader(MountId mountId,
                                 sp<content::pm::IDataLoader>* _aidl_return) const final {
        return mInterface->getDataLoader(mountId, _aidl_return);
    }
    binder::Status unbindFromDataLoader(MountId mountId) const final {
        return mInterface->unbindFromDataLoader(mountId);
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

class RealLooperWrapper final : public LooperWrapper {
public:
    int addFd(int fd, int ident, int events, android::Looper_callbackFunc callback,
              void* data) final {
        return mLooper.addFd(fd, ident, events, callback, data);
    }
    int removeFd(int fd) final { return mLooper.removeFd(fd); }
    void wake() final { return mLooper.wake(); }
    int pollAll(int timeoutMillis) final { return mLooper.pollAll(timeoutMillis); }

private:
    struct Looper : public android::Looper {
        Looper() : android::Looper(/*allowNonCallbacks=*/false) {}
        ~Looper() {}
    } mLooper;
};

class RealIncFs : public IncFsWrapper {
public:
    RealIncFs() = default;
    ~RealIncFs() final = default;
    void listExistingMounts(const ExistingMountCallback& cb) const final {
        for (auto mount : incfs::defaultMountRegistry().copyMounts()) {
            auto binds = mount.binds(); // span() doesn't like rvalue containers, needs to save it.
            cb(mount.root(), mount.backingDir(), binds);
        }
    }
    Control openMount(std::string_view path) const final { return incfs::open(path); }
    Control createControl(IncFsFd cmd, IncFsFd pendingReads, IncFsFd logs) const final {
        return incfs::createControl(cmd, pendingReads, logs);
    }
    ErrorCode makeFile(const Control& control, std::string_view path, int mode, FileId id,
                       incfs::NewFileParams params) const final {
        return incfs::makeFile(control, path, mode, id, params);
    }
    ErrorCode makeDir(const Control& control, std::string_view path, int mode) const final {
        return incfs::makeDir(control, path, mode);
    }
    ErrorCode makeDirs(const Control& control, std::string_view path, int mode) const final {
        return incfs::makeDirs(control, path, mode);
    }
    incfs::RawMetadata getMetadata(const Control& control, FileId fileid) const final {
        return incfs::getMetadata(control, fileid);
    }
    incfs::RawMetadata getMetadata(const Control& control, std::string_view path) const final {
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
    ErrorCode writeBlocks(std::span<const incfs::DataBlock> blocks) const final {
        return incfs::writeBlocks({blocks.data(), size_t(blocks.size())});
    }
    WaitResult waitForPendingReads(const Control& control, std::chrono::milliseconds timeout,
                                   std::vector<incfs::ReadInfo>* pendingReadsBuffer) const final {
        return incfs::waitForPendingReads(control, timeout, pendingReadsBuffer);
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
    sp<content::pm::IDataLoaderManager> manager =
            RealServiceManager::getRealService<content::pm::IDataLoaderManager>(
                    kDataLoaderManagerName);
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

std::unique_ptr<LooperWrapper> RealServiceManager::getLooper() {
    return std::make_unique<RealLooperWrapper>();
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

} // namespace android::incremental
