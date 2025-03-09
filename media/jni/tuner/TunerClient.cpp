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

#define LOG_TAG "TunerClient"

#include <android/binder_manager.h>
#include <android-base/logging.h>
#include <utils/Log.h>

#include "TunerClient.h"

using ::aidl::android::hardware::tv::tuner::FrontendType;

namespace android {

int32_t TunerClient::mTunerVersion;

/////////////// TunerClient ///////////////////////

TunerClient::TunerClient() {
    ::ndk::SpAIBinder binder(AServiceManager_waitForService("media.tuner"));
    mTunerService = ITunerService::fromBinder(binder);
    if (mTunerService == nullptr) {
        ALOGE("Failed to get tuner service");
    } else {
        mTunerService->getTunerHalVersion(&mTunerVersion);
    }
}

TunerClient::~TunerClient() {
}

vector<int32_t> TunerClient::getFrontendIds() {
    vector<int32_t> ids;

    if (mTunerService != nullptr) {
        Status s = mTunerService->getFrontendIds(&ids);
        if (!s.isOk()) {
            ids.clear();
        }
    }

    return ids;
}

sp<FrontendClient> TunerClient::openFrontend(int64_t frontendHandle) {
    if (mTunerService != nullptr) {
        shared_ptr<ITunerFrontend> tunerFrontend;
        Status s = mTunerService->openFrontend(frontendHandle, &tunerFrontend);
        if (!s.isOk() || tunerFrontend == nullptr) {
            return nullptr;
        }
        int32_t id;
        s = tunerFrontend->getFrontendId(&id);
        if (!s.isOk()) {
            tunerFrontend->close();
            return nullptr;
        }
        FrontendInfo frontendInfo;
        s = mTunerService->getFrontendInfo(id, &frontendInfo);
        if (!s.isOk()) {
            tunerFrontend->close();
            return nullptr;
        }
        return new FrontendClient(tunerFrontend, frontendInfo.type);
    }

    return nullptr;
}

shared_ptr<FrontendInfo> TunerClient::getFrontendInfo(int32_t id) {
    if (mTunerService != nullptr) {
        FrontendInfo aidlFrontendInfo;
        Status s = mTunerService->getFrontendInfo(id, &aidlFrontendInfo);
        if (!s.isOk()) {
            return nullptr;
        }
        return make_shared<FrontendInfo>(aidlFrontendInfo);
    }

    return nullptr;
}

sp<DemuxClient> TunerClient::openDemux(int64_t demuxHandle) {
    if (mTunerService != nullptr) {
        shared_ptr<ITunerDemux> tunerDemux;
        Status s = mTunerService->openDemux(demuxHandle, &tunerDemux);
        if (!s.isOk()) {
            return nullptr;
        }
        return new DemuxClient(tunerDemux);
    }

    return nullptr;
}

shared_ptr<DemuxInfo> TunerClient::getDemuxInfo(int64_t demuxHandle) {
    if (mTunerService != nullptr) {
        DemuxInfo aidlDemuxInfo;
        Status s = mTunerService->getDemuxInfo(demuxHandle, &aidlDemuxInfo);
        if (!s.isOk()) {
            return nullptr;
        }
        return make_shared<DemuxInfo>(aidlDemuxInfo);
    }
    return nullptr;
}

void TunerClient::getDemuxInfoList(vector<DemuxInfo>* demuxInfoList) {
    if (mTunerService != nullptr) {
        Status s = mTunerService->getDemuxInfoList(demuxInfoList);
        if (!s.isOk()) {
            demuxInfoList->clear();
        }
    }
}

shared_ptr<DemuxCapabilities> TunerClient::getDemuxCaps() {
    if (mTunerService != nullptr) {
        DemuxCapabilities aidlCaps;
        Status s = mTunerService->getDemuxCaps(&aidlCaps);
        if (!s.isOk()) {
            return nullptr;
        }
        return make_shared<DemuxCapabilities>(aidlCaps);
    }

    return nullptr;
}

sp<DescramblerClient> TunerClient::openDescrambler(int64_t descramblerHandle) {
    if (mTunerService != nullptr) {
        shared_ptr<ITunerDescrambler> tunerDescrambler;
        Status s = mTunerService->openDescrambler(descramblerHandle, &tunerDescrambler);
        if (!s.isOk()) {
            return nullptr;
        }
        return new DescramblerClient(tunerDescrambler);
    }

    return nullptr;
}

sp<LnbClient> TunerClient::openLnb(int64_t lnbHandle) {
    if (mTunerService != nullptr) {
        shared_ptr<ITunerLnb> tunerLnb;
        Status s = mTunerService->openLnb(lnbHandle, &tunerLnb);
        if (!s.isOk()) {
            return nullptr;
        }
        return new LnbClient(tunerLnb);
    }

    return nullptr;
}

sp<LnbClient> TunerClient::openLnbByName(string lnbName) {
    if (mTunerService != nullptr) {
        shared_ptr<ITunerLnb> tunerLnb;
        Status s = mTunerService->openLnbByName(lnbName, &tunerLnb);
        if (!s.isOk()) {
            return nullptr;
        }
        return new LnbClient(tunerLnb);
    }

    return nullptr;
}

sp<FilterClient> TunerClient::openSharedFilter(const string& filterToken,
                                               sp<FilterClientCallback> cb) {
    if (cb == nullptr) {
        return nullptr;
    }

    if (mTunerService != nullptr) {
        shared_ptr<ITunerFilter> tunerFilter;
        shared_ptr<TunerFilterCallback> callback =
                ::ndk::SharedRefBase::make<TunerFilterCallback>(cb);
        Status s = mTunerService->openSharedFilter(filterToken, callback, &tunerFilter);
        if (!s.isOk()) {
            return nullptr;
        }
        DemuxFilterType type;
        tunerFilter->getFilterType(&type);
        return new FilterClient(type, tunerFilter);
    }

    return nullptr;
}

Result TunerClient::setLna(bool bEnable) {
    if (mTunerService != nullptr) {
        Status s = mTunerService->setLna(bEnable);
        return ClientHelper::getServiceSpecificErrorCode(s);
    }

    return Result::INVALID_STATE;
}

Result TunerClient::setMaxNumberOfFrontends(FrontendType frontendType, int32_t maxNumber) {
    if (mTunerService != nullptr) {
        Status s = mTunerService->setMaxNumberOfFrontends(frontendType, maxNumber);
        return ClientHelper::getServiceSpecificErrorCode(s);
    }

    return Result::INVALID_STATE;
}

int TunerClient::getMaxNumberOfFrontends(FrontendType frontendType) {
    if (mTunerService != nullptr) {
        int32_t maxNumber;
        mTunerService->getMaxNumberOfFrontends(frontendType, &maxNumber);
        return maxNumber;
    }

    return -1;
}

bool TunerClient::isLnaSupported() {
    if (mTunerService != nullptr) {
        bool lnaSupported;
        Status s = mTunerService->isLnaSupported(&lnaSupported);
        if (!s.isOk()) {
            return false;
        }
        return lnaSupported;
    }

    return false;
}

}  // namespace android
