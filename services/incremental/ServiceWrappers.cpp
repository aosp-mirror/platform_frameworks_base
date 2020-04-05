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

#include "ServiceWrappers.h"

#include <utils/String16.h>

using namespace std::literals;

namespace android::os::incremental {

static constexpr auto kVoldServiceName = "vold"sv;
static constexpr auto kDataLoaderManagerName = "dataloader_manager"sv;

RealServiceManager::RealServiceManager(sp<IServiceManager> serviceManager)
      : mServiceManager(std::move(serviceManager)) {}

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

} // namespace android::os::incremental
