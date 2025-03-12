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

#define LOG_TAG "ADynamicInstrumentationManager"
#include <android-base/properties.h>
#include <android/dynamic_instrumentation_manager.h>
#include <android/os/instrumentation/BnOffsetCallback.h>
#include <android/os/instrumentation/ExecutableMethodFileOffsets.h>
#include <android/os/instrumentation/IDynamicInstrumentationManager.h>
#include <android/os/instrumentation/MethodDescriptor.h>
#include <android/os/instrumentation/TargetProcess.h>
#include <binder/Binder.h>
#include <binder/IServiceManager.h>
#include <utils/Log.h>
#include <utils/StrongPointer.h>

#include <future>
#include <mutex>
#include <optional>
#include <string>
#include <vector>

namespace android::dynamicinstrumentationmanager {

using android::os::instrumentation::BnOffsetCallback;
using android::os::instrumentation::ExecutableMethodFileOffsets;

// Global instance of IDynamicInstrumentationManager, service is obtained only on first use.
static std::mutex mLock;
static sp<os::instrumentation::IDynamicInstrumentationManager> mService;

sp<os::instrumentation::IDynamicInstrumentationManager> getService() {
    std::lock_guard<std::mutex> scoped_lock(mLock);
    if (mService == nullptr || !IInterface::asBinder(mService)->isBinderAlive()) {
        sp<IBinder> binder =
                defaultServiceManager()->waitForService(String16("dynamic_instrumentation"));
        mService = interface_cast<os::instrumentation::IDynamicInstrumentationManager>(binder);
    }
    return mService;
}

} // namespace android::dynamicinstrumentationmanager

using namespace android;
using namespace dynamicinstrumentationmanager;

struct ADynamicInstrumentationManager_TargetProcess {
    uid_t uid;
    uid_t pid;
    std::string processName;

    ADynamicInstrumentationManager_TargetProcess(uid_t uid, pid_t pid, const char* processName)
          : uid(uid), pid(pid), processName(processName) {}
};

ADynamicInstrumentationManager_TargetProcess* ADynamicInstrumentationManager_TargetProcess_create(
        uid_t uid, pid_t pid, const char* processName) {
    return new ADynamicInstrumentationManager_TargetProcess(uid, pid, processName);
}

void ADynamicInstrumentationManager_TargetProcess_destroy(
        const ADynamicInstrumentationManager_TargetProcess* instance) {
    delete instance;
}

struct ADynamicInstrumentationManager_MethodDescriptor {
    std::string fqcn;
    std::string methodName;
    std::vector<std::string> fqParameters;

    ADynamicInstrumentationManager_MethodDescriptor(const char* fqcn, const char* methodName,
                                                    const char* fullyQualifiedParameters[],
                                                    size_t numParameters)
          : fqcn(fqcn), methodName(methodName) {
        std::vector<std::string> fqParameters;
        fqParameters.reserve(numParameters);
        std::copy_n(fullyQualifiedParameters, numParameters, std::back_inserter(fqParameters));
        this->fqParameters = std::move(fqParameters);
    }
};

ADynamicInstrumentationManager_MethodDescriptor*
ADynamicInstrumentationManager_MethodDescriptor_create(const char* fullyQualifiedClassName,
                                                       const char* methodName,
                                                       const char* fullyQualifiedParameters[],
                                                       size_t numParameters) {
    return new ADynamicInstrumentationManager_MethodDescriptor(fullyQualifiedClassName, methodName,
                                                               fullyQualifiedParameters,
                                                               numParameters);
}

void ADynamicInstrumentationManager_MethodDescriptor_destroy(
        const ADynamicInstrumentationManager_MethodDescriptor* instance) {
    delete instance;
}

struct ADynamicInstrumentationManager_ExecutableMethodFileOffsets {
    std::string containerPath;
    uint64_t containerOffset;
    uint64_t methodOffset;
};

ADynamicInstrumentationManager_ExecutableMethodFileOffsets*
ADynamicInstrumentationManager_ExecutableMethodFileOffsets_create() {
    return new ADynamicInstrumentationManager_ExecutableMethodFileOffsets();
}

const char* ADynamicInstrumentationManager_ExecutableMethodFileOffsets_getContainerPath(
        const ADynamicInstrumentationManager_ExecutableMethodFileOffsets* instance) {
    return instance->containerPath.c_str();
}

uint64_t ADynamicInstrumentationManager_ExecutableMethodFileOffsets_getContainerOffset(
        const ADynamicInstrumentationManager_ExecutableMethodFileOffsets* instance) {
    return instance->containerOffset;
}

uint64_t ADynamicInstrumentationManager_ExecutableMethodFileOffsets_getMethodOffset(
        const ADynamicInstrumentationManager_ExecutableMethodFileOffsets* instance) {
    return instance->methodOffset;
}

void ADynamicInstrumentationManager_ExecutableMethodFileOffsets_destroy(
        const ADynamicInstrumentationManager_ExecutableMethodFileOffsets* instance) {
    delete instance;
}

class ResultCallback : public BnOffsetCallback {
public:
    ::android::binder::Status onResult(
            const ::std::optional<ExecutableMethodFileOffsets>& offsets) override {
        promise_.set_value(offsets);
        return android::binder::Status::ok();
    }

    std::optional<ExecutableMethodFileOffsets> waitForResult() {
        std::future<std::optional<ExecutableMethodFileOffsets>> futureResult =
                promise_.get_future();
        auto futureStatus = futureResult.wait_for(
                std::chrono::seconds(1 * android::base::HwTimeoutMultiplier()));
        if (futureStatus == std::future_status::ready) {
            return futureResult.get();
        } else {
            return std::nullopt;
        }
    }

private:
    std::promise<std::optional<ExecutableMethodFileOffsets>> promise_;
};

int32_t ADynamicInstrumentationManager_getExecutableMethodFileOffsets(
        const ADynamicInstrumentationManager_TargetProcess* targetProcess,
        const ADynamicInstrumentationManager_MethodDescriptor* methodDescriptor,
        const ADynamicInstrumentationManager_ExecutableMethodFileOffsets** out) {
    android::os::instrumentation::TargetProcess targetProcessParcel;
    targetProcessParcel.uid = targetProcess->uid;
    targetProcessParcel.pid = targetProcess->pid;
    targetProcessParcel.processName = targetProcess->processName;

    android::os::instrumentation::MethodDescriptor methodDescriptorParcel;
    methodDescriptorParcel.fullyQualifiedClassName = methodDescriptor->fqcn;
    methodDescriptorParcel.methodName = methodDescriptor->methodName;
    methodDescriptorParcel.fullyQualifiedParameters = methodDescriptor->fqParameters;

    sp<os::instrumentation::IDynamicInstrumentationManager> service = getService();
    if (service == nullptr) {
        return INVALID_OPERATION;
    }

    android::sp<ResultCallback> resultCallback = android::sp<ResultCallback>::make();
    binder_status_t result =
            service->getExecutableMethodFileOffsets(targetProcessParcel, methodDescriptorParcel,
                                                    resultCallback)
                    .exceptionCode();
    if (result != OK) {
        return result;
    }
    std::optional<ExecutableMethodFileOffsets> offsets = resultCallback->waitForResult();
    if (offsets != std::nullopt) {
        auto* value = new ADynamicInstrumentationManager_ExecutableMethodFileOffsets();
        value->containerPath = offsets->containerPath;
        value->containerOffset = offsets->containerOffset;
        value->methodOffset = offsets->methodOffset;
        *out = value;
    } else {
        *out = nullptr;
    }

    return result;
}
