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

#define LOG_TAG "DemuxClient"

#include "DemuxClient.h"

#include <aidl/android/hardware/tv/tuner/Constant.h>
#include <aidl/android/hardware/tv/tuner/Constant64Bit.h>
#include <android-base/logging.h>
#include <utils/Log.h>

using ::aidl::android::hardware::tv::tuner::Constant;
using ::aidl::android::hardware::tv::tuner::Constant64Bit;

namespace android {
/////////////// DemuxClient ///////////////////////
DemuxClient::DemuxClient(shared_ptr<ITunerDemux> tunerDemux) {
    mTunerDemux = tunerDemux;
}

DemuxClient::~DemuxClient() {
    mTunerDemux = nullptr;
}

Result DemuxClient::setFrontendDataSource(sp<FrontendClient> frontendClient) {
    if (frontendClient == nullptr) {
        return Result::INVALID_ARGUMENT;
    }

    if (mTunerDemux != nullptr) {
        Status s = mTunerDemux->setFrontendDataSource(frontendClient->getAidlFrontend());
        return ClientHelper::getServiceSpecificErrorCode(s);
    }

    return Result::INVALID_STATE;
}

Result DemuxClient::setFrontendDataSourceById(int frontendId) {
    if (mTunerDemux != nullptr) {
        Status s = mTunerDemux->setFrontendDataSourceById(frontendId);
        return ClientHelper::getServiceSpecificErrorCode(s);
    }

    return Result::INVALID_STATE;
}

sp<FilterClient> DemuxClient::openFilter(const DemuxFilterType& type, int32_t bufferSize,
                                         sp<FilterClientCallback> cb) {
    if (cb == nullptr) {
        return nullptr;
    }

    if (mTunerDemux != nullptr) {
        shared_ptr<ITunerFilter> tunerFilter;
        shared_ptr<TunerFilterCallback> callback =
                ::ndk::SharedRefBase::make<TunerFilterCallback>(cb);
        Status s = mTunerDemux->openFilter(type, bufferSize, callback, &tunerFilter);
        if (!s.isOk()) {
            return nullptr;
        }
        return new FilterClient(type, tunerFilter);
    }

    return nullptr;
}

sp<TimeFilterClient> DemuxClient::openTimeFilter() {
    if (mTunerDemux != nullptr) {
        shared_ptr<ITunerTimeFilter> tunerTimeFilter;
        Status s = mTunerDemux->openTimeFilter(&tunerTimeFilter);
        if (!s.isOk()) {
            return nullptr;
        }
        return new TimeFilterClient(tunerTimeFilter);
    }

    return nullptr;
}

int32_t DemuxClient::getAvSyncHwId(sp<FilterClient> filterClient) {
    if (filterClient == nullptr) {
        return static_cast<int32_t>(Constant::INVALID_AV_SYNC_ID);
    }

    if (mTunerDemux != nullptr) {
        int32_t hwId;
        Status s = mTunerDemux->getAvSyncHwId(filterClient->getAidlFilter(), &hwId);
        if (!s.isOk()) {
            return static_cast<int32_t>(Constant::INVALID_AV_SYNC_ID);
        }
        return hwId;
    }

    return static_cast<int32_t>(Constant::INVALID_AV_SYNC_ID);
}

int64_t DemuxClient::getAvSyncTime(int32_t avSyncHwId) {
    if (mTunerDemux != nullptr) {
        int64_t time;
        Status s = mTunerDemux->getAvSyncTime(avSyncHwId, &time);
        if (!s.isOk()) {
            return static_cast<int64_t>(Constant64Bit::INVALID_PRESENTATION_TIME_STAMP);
        }
        return time;
    }

    return static_cast<int64_t>(Constant64Bit::INVALID_PRESENTATION_TIME_STAMP);
}

sp<DvrClient> DemuxClient::openDvr(DvrType dvbType, int32_t bufferSize, sp<DvrClientCallback> cb) {
    if (cb == nullptr) {
        return nullptr;
    }

    if (mTunerDemux != nullptr) {
        shared_ptr<ITunerDvr> tunerDvr;
        shared_ptr<TunerDvrCallback> callback =
                ::ndk::SharedRefBase::make<TunerDvrCallback>(cb);
        Status s = mTunerDemux->openDvr(dvbType, bufferSize, callback, &tunerDvr);
        if (!s.isOk()) {
            return nullptr;
        }
        return new DvrClient(tunerDvr);
    }

    return nullptr;
}

Result DemuxClient::connectCiCam(int32_t ciCamId) {
    if (mTunerDemux != nullptr) {
        Status s = mTunerDemux->connectCiCam(ciCamId);
        return ClientHelper::getServiceSpecificErrorCode(s);
    }

    return Result::INVALID_STATE;
}

Result DemuxClient::disconnectCiCam() {
    if (mTunerDemux != nullptr) {
        Status s = mTunerDemux->disconnectCiCam();
        return ClientHelper::getServiceSpecificErrorCode(s);
    }

    return Result::INVALID_STATE;
}

Result DemuxClient::close() {
    if (mTunerDemux != nullptr) {
        Status s = mTunerDemux->close();
        mTunerDemux = nullptr;
        return ClientHelper::getServiceSpecificErrorCode(s);
    }

    return Result::INVALID_STATE;
}

}  // namespace android
