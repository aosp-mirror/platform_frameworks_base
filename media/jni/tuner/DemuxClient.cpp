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

#include "DemuxClient.h"

using ::aidl::android::media::tv::tuner::TunerFrontendSettings;

using ::android::hardware::tv::tuner::V1_0::Result;

namespace android {

/////////////// DemuxClient ///////////////////////

// TODO: pending aidl interface
DemuxClient::DemuxClient() {
    //mTunerDemux = tunerDemux;
}

DemuxClient::~DemuxClient() {
    //mTunerDemux = NULL;
    mDemux = NULL;
}

// TODO: remove after migration to Tuner Service is done.
void DemuxClient::setHidlDemux(sp<IDemux> demux) {
    mDemux = demux;
}

Result DemuxClient::setFrontendDataSource(sp<FrontendClient> frontendClient) {
    // TODO: pending aidl interface
    /*if (mTunerDemux != NULL) {
        // TODO: handle error message
        mTunerDemux->setFrontendDataSource(frontendClient->getAidlFrontend());
        return (int) Result::SUCCESS;
    }*/

    if (mDemux != NULL) {
        Result res = mDemux->setFrontendDataSource(frontendClient->getId());
        return res;
    }

    return Result::INVALID_STATE;
}

sp<FilterClient> DemuxClient::openFilter(DemuxFilterType type, int bufferSize,
        sp<FilterClientCallback> cb) {
    // TODO: pending aidl interface

    if (mDemux != NULL) {
        sp<HidlFilterCallback> callback = new HidlFilterCallback(cb);
        sp<IFilter> hidlFilter = openHidlFilter(type, bufferSize, callback);
        if (hidlFilter != NULL) {
            sp<FilterClient> filterClient = new FilterClient(type);
            filterClient->setHidlFilter(hidlFilter);
            return filterClient;
        }
    }

    return NULL;
}

int DemuxClient::getAvSyncHwId(sp<FilterClient> filterClient) {
    // pending aidl interface

    if (mDemux != NULL) {
        uint32_t avSyncHwId;
        Result res;
        sp<IFilter> halFilter = filterClient->getHalFilter();
        mDemux->getAvSyncHwId(halFilter,
                [&](Result r, uint32_t id) {
                    res = r;
                    avSyncHwId = id;
                });
        if (res == Result::SUCCESS) {
            return (int) avSyncHwId;
        }
    }

    return -1;
}

long DemuxClient::getAvSyncTime(int avSyncHwId) {
    // pending aidl interface

    if (mDemux != NULL) {
        uint64_t time;
        Result res;
        mDemux->getAvSyncTime(static_cast<uint32_t>(avSyncHwId),
                [&](Result r, uint64_t ts) {
                    res = r;
                    time = ts;
                });
        if (res == Result::SUCCESS) {
            return (long) time;
        }
    }

    return -1;
}

//DvrClient openDvr(int dvbType, int bufferSize, DvrClientCallback cb);

Result DemuxClient::connectCiCam(int ciCamId) {
    // pending aidl interface

    if (mDemux != NULL) {
        return mDemux->connectCiCam(static_cast<uint32_t>(ciCamId));
    }

    return Result::INVALID_STATE;
}

Result DemuxClient::disconnectCiCam() {
    // pending aidl interface

    if (mDemux != NULL) {
        return mDemux->disconnectCiCam();
    }

    return Result::INVALID_STATE;
}

Result DemuxClient::close() {
    // TODO: pending aidl interface

    if (mDemux != NULL) {
        Result res = mDemux->close();
        if (res == Result::SUCCESS) {
            mDemux = NULL;
        }
        return res;
    }

    return Result::INVALID_STATE;
}

/////////////// DemuxClient Helper Methods ///////////////////////

sp<IFilter> DemuxClient::openHidlFilter(DemuxFilterType type, int bufferSize,
        sp<HidlFilterCallback> callback) {
    if (mDemux == NULL) {
        return NULL;
    }

    sp<IFilter> hidlFilter;
    Result res;
    mDemux->openFilter(type, bufferSize, callback,
            [&](Result r, const sp<IFilter>& filter) {
                hidlFilter = filter;
                res = r;
            });
    if (res != Result::SUCCESS || hidlFilter == NULL) {
        return NULL;
    }

    return hidlFilter;
}
}  // namespace android
