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

Result DemuxClient::setFrontendDataSource(sp<FrontendClient> tunerFrontend) {
    // TODO: pending aidl interface
    /*if (mTunerDemux != NULL) {
        // TODO: handle error message
        mTunerDemux->setFrontendDataSource(tunerFrontend->getAidlFrontend());
        return (int) Result::SUCCESS;
    }*/

    if (mDemux != NULL) {
        Result res = mDemux->setFrontendDataSource(tunerFrontend->getId());
        return res;
    }

    return Result::INVALID_STATE;
}

//FilterClient openFilter(int mainType, int subType, int bufferSize, FilterClientCallback cb);

int DemuxClient::getAvSyncHwId(FilterClient /*tunerFilter*/) {
    return 0;
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
    // pending aidl interface

    if (mDemux != NULL) {
        Result res = mDemux->close();
        if (res == Result::SUCCESS) {
            mDemux = NULL;
        }
        return res;
    }

    return Result::INVALID_STATE;
}
}  // namespace android
