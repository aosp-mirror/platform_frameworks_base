/*
 * Copyright (C) 2024 The Android Open Source Project
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

#ifndef FRAMEWORKS_BASE_COMMONPOOLBASE_H
#define FRAMEWORKS_BASE_COMMONPOOLBASE_H

#include <sys/resource.h>

#include "renderthread/RenderThread.h"

namespace android {
namespace uirenderer {

class CommonPoolBase {
    PREVENT_COPY_AND_ASSIGN(CommonPoolBase);

protected:
    CommonPoolBase() {}

    void setupThread(int i, std::mutex& mLock, std::vector<int>& tids,
                     std::vector<std::condition_variable>& tidConditionVars) {
        std::array<char, 20> name{"hwuiTask"};
        snprintf(name.data(), name.size(), "hwuiTask%d", i);
        auto self = pthread_self();
        pthread_setname_np(self, name.data());
        {
            std::unique_lock lock(mLock);
            tids[i] = pthread_gettid_np(self);
            tidConditionVars[i].notify_one();
        }
        setpriority(PRIO_PROCESS, 0, PRIORITY_FOREGROUND);
        auto startHook = renderthread::RenderThread::getOnStartHook();
        if (startHook) {
            startHook(name.data());
        }
    }

    bool supportsTid() { return true; }
};

}  // namespace uirenderer
}  // namespace android

#endif  // FRAMEWORKS_BASE_COMMONPOOLBASE_H
