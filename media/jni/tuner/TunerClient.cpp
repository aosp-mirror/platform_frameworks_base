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

#define LOG_TAG "TunerClient"

#include <android/binder_manager.h>
#include <android-base/logging.h>
#include <utils/Log.h>

#include "TunerClient.h"

using ::android::hardware::tv::tuner::V1_0::FrontendId;
using ::android::hardware::tv::tuner::V1_0::FrontendType;

namespace android {

sp<ITuner> TunerClient::mTuner;
sp<::android::hardware::tv::tuner::V1_1::ITuner> TunerClient::mTuner_1_1;
shared_ptr<ITunerService> TunerClient::mTunerService;
int TunerClient::mTunerVersion;

/////////////// TunerClient ///////////////////////

TunerClient::TunerClient() {
    // Get HIDL Tuner in migration stage.
    getHidlTuner();
    // Connect with Tuner Service.
    ::ndk::SpAIBinder binder(AServiceManager_getService("media.tuner"));
    mTunerService = ITunerService::fromBinder(binder);
    if (mTunerService == NULL) {
        ALOGE("Failed to get tuner service");
    }
}

TunerClient::~TunerClient() {
    mTuner = NULL;
    mTuner_1_1 = NULL;
    mTunerVersion = 0;
    mTunerService = NULL;
}

vector<FrontendId> TunerClient::getFrontendIds() {
    vector<FrontendId> ids;
    // TODO: pending aidl interface
    /*if (mTunerService != NULL) {
        return mTunerService->getFrontendIds();
    }*/

    if (mTuner != NULL) {
        Result res;
        mTuner->getFrontendIds([&](Result r, const hardware::hidl_vec<FrontendId>& frontendIds) {
            res = r;
            ids = frontendIds;
        });
        if (res != Result::SUCCESS || ids.size() == 0) {
            ALOGW("Frontend ids not available");
            ids.clear();
            return ids;
        }
        return ids;
    }

    return ids;
}


sp<FrontendClient> TunerClient::openFrontend(int frontendHandle) {
    if (mTunerService != NULL) {
        // TODO: handle error code
        shared_ptr<ITunerFrontend> tunerFrontend;
        mTunerService->openFrontend(frontendHandle, &tunerFrontend);
        return new FrontendClient(tunerFrontend);
    }

    if (mTuner != NULL) {
        sp<IFrontend> hidlFrontend = openHidlFrontendByHandle(frontendHandle);
        if (hidlFrontend != NULL) {
            sp<FrontendClient> frontendClient = new FrontendClient(NULL);
            frontendClient->setHidlFrontend(hidlFrontend);
            return frontendClient;
        }
    }

    return NULL;
}

shared_ptr<FrontendInfo> TunerClient::getFrontendInfo(int id) {
    if (mTunerService != NULL) {
        TunerServiceFrontendInfo aidlFrontendInfo;
        // TODO: handle error code
        mTunerService->getFrontendInfo(id, &aidlFrontendInfo);
        return make_shared<FrontendInfo>(FrontendInfoAidlToHidl(aidlFrontendInfo));
    }

    if (mTuner != NULL) {
        FrontendInfo hidlInfo;
        Result res = getHidlFrontendInfo(id, hidlInfo);
        if (res != Result::SUCCESS) {
            return NULL;
        }
        return make_shared<FrontendInfo>(hidlInfo);
    }

    return NULL;
}

shared_ptr<FrontendDtmbCapabilities> TunerClient::getFrontendDtmbCapabilities(int id) {
    // pending aidl interface

    if (mTuner_1_1 != NULL) {
        Result result;
        FrontendDtmbCapabilities dtmbCaps;
        mTuner_1_1->getFrontendDtmbCapabilities(id,
                [&](Result r, const FrontendDtmbCapabilities& caps) {
            dtmbCaps = caps;
            result = r;
        });
        if (result == Result::SUCCESS) {
            return make_shared<FrontendDtmbCapabilities>(dtmbCaps);
        }
    }

    return NULL;
}

/////////////// TunerClient Helper Methods ///////////////////////

sp<ITuner> TunerClient::getHidlTuner() {
    if (mTuner == NULL) {
        mTunerVersion = 0;
        mTuner_1_1 = ::android::hardware::tv::tuner::V1_1::ITuner::getService();

        if (mTuner_1_1 == NULL) {
            ALOGW("Failed to get tuner 1.1 service.");
            mTuner = ITuner::getService();
            if (mTuner == NULL) {
                ALOGW("Failed to get tuner 1.0 service.");
            } else {
                mTunerVersion = 1 << 16;
            }
        } else {
            mTuner = static_cast<sp<ITuner>>(mTuner_1_1);
            mTunerVersion = ((1 << 16) | 1);
         }
     }
     return mTuner;
}

sp<IFrontend> TunerClient::openHidlFrontendByHandle(int frontendHandle) {
    sp<IFrontend> fe;
    Result res;
    uint32_t id = getResourceIdFromHandle(frontendHandle);
    mTuner->openFrontendById(id, [&](Result r, const sp<IFrontend>& frontend) {
        fe = frontend;
        res = r;
    });
    if (res != Result::SUCCESS || fe == nullptr) {
        ALOGE("Failed to open frontend");
        return NULL;
    }
    return fe;
}

Result TunerClient::getHidlFrontendInfo(int id, FrontendInfo& feInfo) {
    Result res;
    mTuner->getFrontendInfo(id, [&](Result r, const FrontendInfo& info) {
        feInfo = info;
        res = r;
    });
    return res;
}

FrontendInfo TunerClient::FrontendInfoAidlToHidl(TunerServiceFrontendInfo aidlFrontendInfo) {
    FrontendInfo hidlFrontendInfo {
        .type = static_cast<FrontendType>(aidlFrontendInfo.type),
        .minFrequency = static_cast<uint32_t>(aidlFrontendInfo.minFrequency),
        .maxFrequency = static_cast<uint32_t>(aidlFrontendInfo.maxFrequency),
        .minSymbolRate = static_cast<uint32_t>(aidlFrontendInfo.minSymbolRate),
        .maxSymbolRate = static_cast<uint32_t>(aidlFrontendInfo.maxSymbolRate),
        .acquireRange = static_cast<uint32_t>(aidlFrontendInfo.acquireRange),
        .exclusiveGroupId = static_cast<uint32_t>(aidlFrontendInfo.exclusiveGroupId),
    };
    // TODO: handle Frontend caps

    return hidlFrontendInfo;
}
}  // namespace android
