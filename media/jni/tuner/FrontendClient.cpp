/*
 * Copyright 2020 The Android Open Source Project
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

#include <android-base/logging.h>
#include <utils/Log.h>

#include "FrontendClient.h"

using ::aidl::android::media::tv::tuner::TunerFrontendSettings;

namespace android {

/////////////// FrontendClient ///////////////////////

FrontendClient::FrontendClient(shared_ptr<ITunerFrontend> tunerFrontend, int frontendHandle) {
    mTunerFrontend = tunerFrontend;
    mAidlCallback = NULL;
    mHidlCallback = NULL;
    mFrontendHandle = frontendHandle;
}

FrontendClient::~FrontendClient() {
    mTunerFrontend = NULL;
    mFrontend = NULL;
    mFrontend_1_1 = NULL;
    mAidlCallback = NULL;
    mHidlCallback = NULL;
    mFrontendHandle = -1;
}

Result FrontendClient::setCallback(sp<FrontendClientCallback> frontendClientCallback) {
    if (mTunerFrontend != NULL) {
        mAidlCallback = ::ndk::SharedRefBase::make<TunerFrontendCallback>(frontendClientCallback);
        mTunerFrontend->setCallback(mAidlCallback);
        return Result::SUCCESS;
    }

    mHidlCallback = new HidlFrontendCallback(frontendClientCallback);
    return mFrontend->setCallback(mHidlCallback);
}

void FrontendClient::setHidlFrontend(sp<IFrontend> frontend) {
    mFrontend = frontend;
    mFrontend_1_1 = ::android::hardware::tv::tuner::V1_1::IFrontend::castFrom(mFrontend);
}

Result FrontendClient::tune(const FrontendSettings& settings,
        const FrontendSettingsExt1_1& settingsExt1_1) {
    if (mTunerFrontend != NULL) {
        // TODO: parse hidl settings to aidl settings
        // TODO: aidl frontend settings to include Tuner HAL 1.1 settings
        TunerFrontendSettings settings;
        // TODO: handle error message.
        mTunerFrontend->tune(settings);
        return Result::SUCCESS;
    }

    Result result;
    if (mFrontend_1_1 != NULL) {
        result = mFrontend_1_1->tune_1_1(settings, settingsExt1_1);
        return result;
    }

    if (mFrontend != NULL) {
        result = mFrontend->tune(settings);
        return result;
    }

    return Result::INVALID_STATE;
}

Result FrontendClient::stopTune() {
    if (mTunerFrontend != NULL) {
        // TODO: handle error message.
        mTunerFrontend->stopTune();
        return Result::SUCCESS;
    }

    if (mFrontend != NULL) {
        Result result = mFrontend->stopTune();
        return result;
    }

    return Result::INVALID_STATE;
}

Result FrontendClient::close() {
    if (mTunerFrontend != NULL) {
        // TODO: handle error message.
        mTunerFrontend->close();
        return Result::SUCCESS;
    }

    if (mFrontend != NULL) {
        Result result = mFrontend->close();
        if (result == Result::SUCCESS) {
            mFrontend = NULL;
            mFrontend_1_1 = NULL;
        }
        return result;
    }

    return Result::INVALID_STATE;
}

shared_ptr<ITunerFrontend> FrontendClient::getAidlFrontend() {
    return mTunerFrontend;
}

int FrontendClient::getId() {
    return getResourceIdFromHandle(mFrontendHandle);
}

/////////////// TunerFrontendCallback ///////////////////////

TunerFrontendCallback::TunerFrontendCallback(sp<FrontendClientCallback> frontendClientCallback)
        : mFrontendClientCallback(frontendClientCallback) {}

Status TunerFrontendCallback::onEvent(int frontendEventType) {
    if (mFrontendClientCallback != NULL) {
        mFrontendClientCallback->onEvent(static_cast<FrontendEventType>(frontendEventType));
        return Status::ok();
    }
    return Status::fromServiceSpecificError(static_cast<int32_t>(Result::INVALID_STATE));
}

Status TunerFrontendCallback::onLocked() {
    return Status::ok();
}

Status TunerFrontendCallback::onScanStopped() {
    return Status::ok();
}

Status TunerFrontendCallback::onProgress(int /*percent*/) {
    return Status::ok();
}

Status TunerFrontendCallback::onFrequenciesReport(const vector<int>& /*frequency*/) {
    return Status::ok();
}

Status TunerFrontendCallback::onSymbolRates(const vector<int>& /*rates*/) {
    return Status::ok();
}

Status TunerFrontendCallback::onHierarchy(int /*hierarchy*/) {
    return Status::ok();
}

Status TunerFrontendCallback::onSignalType(int /*signalType*/) {
    return Status::ok();
}

Status TunerFrontendCallback::onPlpIds(const vector<int>& /*plpIds*/) {
    return Status::ok();
}

Status TunerFrontendCallback::onGroupIds(const vector<int>& /*groupIds*/) {
    return Status::ok();
}

Status TunerFrontendCallback::onInputStreamIds(const vector<int>& /*inputStreamIds*/) {
    return Status::ok();
}

Status TunerFrontendCallback::onDvbsStandard(int /*dvbsStandandard*/) {
    return Status::ok();
}

Status TunerFrontendCallback::onAnalogSifStandard(int /*sifStandandard*/) {
    return Status::ok();
}

Status TunerFrontendCallback::onAtsc3PlpInfos(const vector<TunerAtsc3PlpInfo>& /*atsc3PlpInfos*/) {
    return Status::ok();
}

/////////////// IFrontendCallback ///////////////////////

HidlFrontendCallback::HidlFrontendCallback(sp<FrontendClientCallback> frontendClientCallback)
        : mFrontendClientCallback(frontendClientCallback) {}

Return<void> HidlFrontendCallback::onEvent(FrontendEventType frontendEventType) {
    if (mFrontendClientCallback != NULL) {
        mFrontendClientCallback->onEvent(frontendEventType);
    }
    return Void();
}

Return<void> HidlFrontendCallback::onScanMessage(FrontendScanMessageType type,
        const FrontendScanMessage& message) {
    if (mFrontendClientCallback != NULL) {
        mFrontendClientCallback->onScanMessage(type, message);
    }
    return Void();
}

Return<void> HidlFrontendCallback::onScanMessageExt1_1(FrontendScanMessageTypeExt1_1 type,
        const FrontendScanMessageExt1_1& message) {
    if (mFrontendClientCallback != NULL) {
        mFrontendClientCallback->onScanMessageExt1_1(type, message);
    }
    return Void();
}
}  // namespace android
