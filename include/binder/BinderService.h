/*
 * Copyright (C) 2010 The Android Open Source Project
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

#ifndef ANDROID_BINDER_SERVICE_H
#define ANDROID_BINDER_SERVICE_H

#include <stdint.h>

#include <utils/Errors.h>
#include <utils/String16.h>

#include <binder/IServiceManager.h>
#include <binder/IPCThreadState.h>
#include <binder/ProcessState.h>
#include <binder/IServiceManager.h>

// ---------------------------------------------------------------------------
namespace android {

template<typename SERVICE>
class BinderService
{
public:
    static status_t publish() {
        sp<IServiceManager> sm(defaultServiceManager());
        return sm->addService(String16(SERVICE::getServiceName()), new SERVICE());
    }

    static void publishAndJoinThreadPool() {
        sp<ProcessState> proc(ProcessState::self());
        sp<IServiceManager> sm(defaultServiceManager());
        sm->addService(String16(SERVICE::getServiceName()), new SERVICE());
        ProcessState::self()->startThreadPool();
        IPCThreadState::self()->joinThreadPool();
    }

    static void instantiate() { publish(); }

    static status_t shutdown() {
        return NO_ERROR;
    }
};


}; // namespace android
// ---------------------------------------------------------------------------
#endif // ANDROID_BINDER_SERVICE_H
