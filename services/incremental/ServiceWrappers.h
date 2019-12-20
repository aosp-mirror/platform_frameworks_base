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

#include <android-base/strings.h>
#include <android-base/unique_fd.h>
#include <android/content/pm/DataLoaderParamsParcel.h>
#include <android/content/pm/FileSystemControlParcel.h>
#include <android/content/pm/IDataLoaderStatusListener.h>
#include <android/os/IVold.h>
#include <android/os/incremental/IIncrementalManager.h>
#include <binder/IServiceManager.h>
#include <incfs.h>

#include <string>
#include <string_view>

using namespace android::incfs;
using namespace android::content::pm;

namespace android::os::incremental {

// --- Wrapper interfaces ---

class VoldServiceWrapper {
public:
    virtual ~VoldServiceWrapper(){};
    virtual binder::Status mountIncFs(const std::string& imagePath, const std::string& targetDir,
                                      int32_t flags,
                                      IncrementalFileSystemControlParcel* _aidl_return) const = 0;
    virtual binder::Status unmountIncFs(const std::string& dir) const = 0;
    virtual binder::Status bindMount(const std::string& sourceDir,
                                     const std::string& targetDir) const = 0;
};

class IncrementalManagerWrapper {
public:
    virtual ~IncrementalManagerWrapper() {}
    virtual binder::Status prepareDataLoader(
            int32_t mountId, const FileSystemControlParcel& control,
            const DataLoaderParamsParcel& params,
            const sp<IDataLoaderStatusListener>& listener,
            bool* _aidl_return) const = 0;
    virtual binder::Status startDataLoader(int32_t mountId, bool* _aidl_return) const = 0;
    virtual binder::Status destroyDataLoader(int32_t mountId) const = 0;
    virtual binder::Status newFileForDataLoader(int32_t mountId, int64_t inode,
                                                const ::std::vector<uint8_t>& metadata) const = 0;
    virtual binder::Status showHealthBlockedUI(int32_t mountId) const = 0;
};

class IncFsWrapper {
public:
    virtual ~IncFsWrapper() {}
    virtual Inode makeFile(Control control, std::string_view name, Inode parent, Size size,
                           std::string_view metadata) const = 0;
    virtual Inode makeDir(Control control, std::string_view name, Inode parent,
                          std::string_view metadata, int mode = 0555) const = 0;
    virtual RawMetadata getMetadata(Control control, Inode inode) const = 0;
    virtual ErrorCode link(Control control, Inode item, Inode targetParent,
                           std::string_view name) const = 0;
    virtual ErrorCode unlink(Control control, Inode parent, std::string_view name) const = 0;
    virtual ErrorCode writeBlocks(Control control, const incfs_new_data_block blocks[],
                                  int blocksCount) const = 0;
};

class ServiceManagerWrapper {
public:
    virtual ~ServiceManagerWrapper() {}
    virtual std::shared_ptr<VoldServiceWrapper> getVoldService() const = 0;
    virtual std::shared_ptr<IncrementalManagerWrapper> getIncrementalManager() const = 0;
    virtual std::shared_ptr<IncFsWrapper> getIncFs() const = 0;
};

// --- Real stuff ---

class RealVoldService : public VoldServiceWrapper {
public:
    RealVoldService(const sp<os::IVold> vold) : mInterface(vold) {}
    ~RealVoldService() = default;
    binder::Status mountIncFs(const std::string& imagePath, const std::string& targetDir,
                              int32_t flags,
                              IncrementalFileSystemControlParcel* _aidl_return) const override {
        return mInterface->mountIncFs(imagePath, targetDir, flags, _aidl_return);
    }
    binder::Status unmountIncFs(const std::string& dir) const override {
        return mInterface->unmountIncFs(dir);
    }
    binder::Status bindMount(const std::string& sourceDir,
                             const std::string& targetDir) const override {
        return mInterface->bindMount(sourceDir, targetDir);
    }

private:
    sp<os::IVold> mInterface;
};

class RealIncrementalManager : public IncrementalManagerWrapper {
public:
    RealIncrementalManager(const sp<os::incremental::IIncrementalManager> manager)
          : mInterface(manager) {}
    ~RealIncrementalManager() = default;
    binder::Status prepareDataLoader(
            int32_t mountId, const FileSystemControlParcel& control,
            const DataLoaderParamsParcel& params,
            const sp<IDataLoaderStatusListener>& listener,
            bool* _aidl_return) const override {
        return mInterface->prepareDataLoader(mountId, control, params, listener, _aidl_return);
    }
    binder::Status startDataLoader(int32_t mountId, bool* _aidl_return) const override {
        return mInterface->startDataLoader(mountId, _aidl_return);
    }
    binder::Status destroyDataLoader(int32_t mountId) const override {
        return mInterface->destroyDataLoader(mountId);
    }
    binder::Status newFileForDataLoader(int32_t mountId, int64_t inode,
                                        const ::std::vector<uint8_t>& metadata) const override {
        return mInterface->newFileForDataLoader(mountId, inode, metadata);
    }
    binder::Status showHealthBlockedUI(int32_t mountId) const override {
        return mInterface->showHealthBlockedUI(mountId);
    }

private:
    sp<os::incremental::IIncrementalManager> mInterface;
};

class RealServiceManager : public ServiceManagerWrapper {
public:
    RealServiceManager(const sp<IServiceManager>& serviceManager);
    ~RealServiceManager() = default;
    std::shared_ptr<VoldServiceWrapper> getVoldService() const override;
    std::shared_ptr<IncrementalManagerWrapper> getIncrementalManager() const override;
    std::shared_ptr<IncFsWrapper> getIncFs() const override;

private:
    template <class INTERFACE>
    sp<INTERFACE> getRealService(std::string_view serviceName) const;
    sp<android::IServiceManager> mServiceManager;
};

class RealIncFs : public IncFsWrapper {
public:
    RealIncFs() = default;
    ~RealIncFs() = default;
    Inode makeFile(Control control, std::string_view name, Inode parent, Size size,
                   std::string_view metadata) const override {
        return incfs::makeFile(control, name, parent, size, metadata);
    }
    Inode makeDir(Control control, std::string_view name, Inode parent, std::string_view metadata,
                  int mode) const override {
        return incfs::makeDir(control, name, parent, metadata, mode);
    }
    RawMetadata getMetadata(Control control, Inode inode) const override {
        return incfs::getMetadata(control, inode);
    }
    ErrorCode link(Control control, Inode item, Inode targetParent,
                   std::string_view name) const override {
        return incfs::link(control, item, targetParent, name);
    }
    ErrorCode unlink(Control control, Inode parent, std::string_view name) const override {
        return incfs::unlink(control, parent, name);
    }
    ErrorCode writeBlocks(Control control, const incfs_new_data_block blocks[],
                          int blocksCount) const override {
        return incfs::writeBlocks(control, blocks, blocksCount);
    }
};

} // namespace android::os::incremental
