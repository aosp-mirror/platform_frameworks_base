/*
 * Copyright 2021 The Android Open Source Project
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

#define LOG_TAG "FrontendClient"

#include "FrontendClient.h"

#include <aidl/android/hardware/tv/tuner/Constant.h>
#include <android-base/logging.h>
#include <utils/Log.h>

using ::aidl::android::hardware::tv::tuner::Constant;

namespace android {
/////////////// FrontendClient ///////////////////////
FrontendClient::FrontendClient(shared_ptr<ITunerFrontend> tunerFrontend, FrontendType type) {
    mTunerFrontend = tunerFrontend;
    mType = type;
}

FrontendClient::~FrontendClient() {
    mTunerFrontend = nullptr;
    mType = FrontendType::UNDEFINED;
}

Result FrontendClient::setCallback(sp<FrontendClientCallback> frontendClientCallback) {
    if (mTunerFrontend != nullptr) {
        shared_ptr<TunerFrontendCallback> aidlCallback =
                ::ndk::SharedRefBase::make<TunerFrontendCallback>(frontendClientCallback);
        Status s = mTunerFrontend->setCallback(aidlCallback);
        return ClientHelper::getServiceSpecificErrorCode(s);
    }

    return Result::INVALID_STATE;
}

Result FrontendClient::tune(const FrontendSettings& settings) {
    if (mTunerFrontend != nullptr) {
        Status s = mTunerFrontend->tune(settings);
        return ClientHelper::getServiceSpecificErrorCode(s);
    }

    return Result::INVALID_STATE;
}

Result FrontendClient::stopTune() {
    if (mTunerFrontend != nullptr) {
        Status s = mTunerFrontend->stopTune();
        return ClientHelper::getServiceSpecificErrorCode(s);
    }

    return Result::INVALID_STATE;
}

Result FrontendClient::scan(const FrontendSettings& settings, FrontendScanType type) {
    if (mTunerFrontend != nullptr) {
        Status s = mTunerFrontend->scan(settings, type);
        return ClientHelper::getServiceSpecificErrorCode(s);
    }

    return Result::INVALID_STATE;
}

Result FrontendClient::stopScan() {
    if (mTunerFrontend != nullptr) {
        Status s = mTunerFrontend->stopScan();
        return ClientHelper::getServiceSpecificErrorCode(s);
    }

    return Result::INVALID_STATE;
}

vector<FrontendStatus> FrontendClient::getStatus(vector<FrontendStatusType> statusTypes) {
    vector<FrontendStatus> status;

    if (mTunerFrontend != nullptr) {
        mTunerFrontend->getStatus(statusTypes, &status);
    }

    return status;
}

Result FrontendClient::setLnb(sp<LnbClient> lnbClient) {
    if (mTunerFrontend != nullptr) {
        Status s = mTunerFrontend->setLnb(lnbClient->getAidlLnb());
        return ClientHelper::getServiceSpecificErrorCode(s);
    }

    return Result::INVALID_STATE;
}

int32_t FrontendClient::linkCiCamToFrontend(int32_t ciCamId) {
    int32_t ltsId = static_cast<int32_t>(Constant::INVALID_LTS_ID);

    if (mTunerFrontend != nullptr) {
        Status s = mTunerFrontend->linkCiCamToFrontend(ciCamId, &ltsId);
        if (!s.isOk()) {
            return static_cast<int32_t>(Constant::INVALID_LTS_ID);
        }
    }

    return ltsId;
}

Result FrontendClient::unlinkCiCamToFrontend(int32_t ciCamId) {
    if (mTunerFrontend != nullptr) {
        Status s = mTunerFrontend->unlinkCiCamToFrontend(ciCamId);
        return ClientHelper::getServiceSpecificErrorCode(s);
    }

    return Result::INVALID_STATE;
}

Result FrontendClient::close() {
    if (mTunerFrontend != nullptr) {
        Status s = mTunerFrontend->close();
        mTunerFrontend = nullptr;
        return ClientHelper::getServiceSpecificErrorCode(s);
    }

    return Result::INVALID_STATE;
}

Result FrontendClient::getHardwareInfo(string& info) {
    if (mTunerFrontend != nullptr) {
        Status s = mTunerFrontend->getHardwareInfo(&info);
        return ClientHelper::getServiceSpecificErrorCode(s);
    }

    return Result::INVALID_STATE;
}

Result FrontendClient::removeOutputPid(int32_t pid) {
    if (mTunerFrontend != nullptr) {
        Status s = mTunerFrontend->removeOutputPid(pid);
        return ClientHelper::getServiceSpecificErrorCode(s);
    }

    return Result::INVALID_STATE;
}

vector<FrontendStatusReadiness> FrontendClient::getStatusReadiness(
        const std::vector<FrontendStatusType>& statusTypes) {
    vector<FrontendStatusReadiness> readiness;
    if (mTunerFrontend != nullptr) {
        mTunerFrontend->getFrontendStatusReadiness(statusTypes, &readiness);
    }

    return readiness;
}

shared_ptr<ITunerFrontend> FrontendClient::getAidlFrontend() {
    return mTunerFrontend;
}

int32_t FrontendClient::getId() {
    if (mTunerFrontend != nullptr) {
        int32_t id;
        Status s = mTunerFrontend->getFrontendId(&id);
        if (s.isOk()) {
            return id;
        }
    }

    return static_cast<int32_t>(Constant::INVALID_FRONTEND_ID);
}

/////////////// IFrontendCallback ///////////////////////
TunerFrontendCallback::TunerFrontendCallback(sp<FrontendClientCallback> frontendClientCallback)
        : mFrontendClientCallback(frontendClientCallback) {}

Status TunerFrontendCallback::onEvent(FrontendEventType frontendEventType) {
    if (mFrontendClientCallback != nullptr) {
        mFrontendClientCallback->onEvent(frontendEventType);
        return Status::ok();
    }
    return Status::fromServiceSpecificError(static_cast<int32_t>(Result::INVALID_STATE));
}

Status TunerFrontendCallback::onScanMessage(FrontendScanMessageType messageType,
                                            const FrontendScanMessage& message) {
    if (mFrontendClientCallback != nullptr) {
        mFrontendClientCallback->onScanMessage(messageType, message);
        return Status::ok();
    }
    return Status::fromServiceSpecificError(static_cast<int32_t>(Result::INVALID_STATE));
}

}  // namespace android
