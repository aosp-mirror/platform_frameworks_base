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

#include <android-base/strings.h>
#include <android-base/unique_fd.h>
#include <binder/IServiceManager.h>
#include <utils/String16.h>

#include <string>
#include <string_view>

using namespace std::literals;

namespace android::os::incremental {

static constexpr auto kVoldServiceName = "vold"sv;
static constexpr auto kIncrementalManagerName = "incremental"sv;

RealServiceManager::RealServiceManager(const sp<IServiceManager>& serviceManager)
      : mServiceManager(serviceManager) {}

template <class INTERFACE>
sp<INTERFACE> RealServiceManager::getRealService(std::string_view serviceName) const {
    sp<IBinder> binder = mServiceManager->getService(String16(serviceName.data()));
    if (binder == 0) {
        return 0;
    }
    return interface_cast<INTERFACE>(binder);
}

std::shared_ptr<VoldServiceWrapper> RealServiceManager::getVoldService() const {
    sp<os::IVold> vold = RealServiceManager::getRealService<os::IVold>(kVoldServiceName);
    if (vold != 0) {
        return std::make_shared<RealVoldService>(vold);
    }
    return nullptr;
}

std::shared_ptr<IncrementalManagerWrapper> RealServiceManager::getIncrementalManager() const {
    sp<IIncrementalManager> manager =
            RealServiceManager::getRealService<IIncrementalManager>(kIncrementalManagerName);
    if (manager != 0) {
        return std::make_shared<RealIncrementalManager>(manager);
    }
    return nullptr;
}

std::shared_ptr<IncFsWrapper> RealServiceManager::getIncFs() const {
    return std::make_shared<RealIncFs>();
}

} // namespace android::os::incremental
