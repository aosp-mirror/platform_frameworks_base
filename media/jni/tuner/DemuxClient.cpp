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

#define LOG_TAG "DemuxClient"

#include <android-base/logging.h>
#include <utils/Log.h>

#include "DemuxClient.h"

using ::aidl::android::media::tv::tuner::TunerFrontendSettings;

using ::android::hardware::tv::tuner::V1_0::DemuxFilterMainType;
using ::android::hardware::tv::tuner::V1_0::Result;

namespace android {

/////////////// DemuxClient ///////////////////////

DemuxClient::DemuxClient(shared_ptr<ITunerDemux> tunerDemux) {
    mTunerDemux = tunerDemux;
    mId = -1;
}

DemuxClient::~DemuxClient() {
    mTunerDemux = NULL;
    mDemux = NULL;
    mId = -1;
}

// TODO: remove after migration to Tuner Service is done.
void DemuxClient::setHidlDemux(sp<IDemux> demux) {
    mDemux = demux;
}

Result DemuxClient::setFrontendDataSource(sp<FrontendClient> frontendClient) {
    if (mTunerDemux != NULL) {
        Status s = mTunerDemux->setFrontendDataSource(frontendClient->getAidlFrontend());
        return ClientHelper::getServiceSpecificErrorCode(s);
    }

    if (mDemux != NULL) {
        Result res = mDemux->setFrontendDataSource(frontendClient->getId());
        return res;
    }

    return Result::INVALID_STATE;
}

sp<FilterClient> DemuxClient::openFilter(DemuxFilterType type, int bufferSize,
        sp<FilterClientCallback> cb) {
    if (mTunerDemux != NULL) {
        shared_ptr<ITunerFilter> tunerFilter;
        shared_ptr<TunerFilterCallback> callback =
                ::ndk::SharedRefBase::make<TunerFilterCallback>(cb);
        Status s = mTunerDemux->openFilter((int)type.mainType, getSubType(type),
                    bufferSize, callback, &tunerFilter);
        if (ClientHelper::getServiceSpecificErrorCode(s) != Result::SUCCESS) {
            return NULL;
        }
        return new FilterClient(type, tunerFilter);
    }

    if (mDemux != NULL) {
        sp<HidlFilterCallback> callback = new HidlFilterCallback(cb);
        sp<IFilter> hidlFilter = openHidlFilter(type, bufferSize, callback);
        if (hidlFilter != NULL) {
            sp<FilterClient> filterClient = new FilterClient(type, NULL);
            filterClient->setHidlFilter(hidlFilter);
            return filterClient;
        }
    }

    return NULL;
}

sp<TimeFilterClient> DemuxClient::openTimeFilter() {
    if (mTunerDemux != NULL) {
        shared_ptr<ITunerTimeFilter> tunerTimeFilter;
        Status s = mTunerDemux->openTimeFilter(&tunerTimeFilter);
        if (ClientHelper::getServiceSpecificErrorCode(s) != Result::SUCCESS) {
            return NULL;
        }
        return new TimeFilterClient(tunerTimeFilter);
    }

    if (mDemux != NULL) {
        sp<ITimeFilter> hidlTimeFilter = openHidlTimeFilter();
        if (hidlTimeFilter != NULL) {
            sp<TimeFilterClient> timeFilterClient = new TimeFilterClient(NULL);
            timeFilterClient->setHidlTimeFilter(hidlTimeFilter);
            return timeFilterClient;
        }
    }

    return NULL;
}

int DemuxClient::getAvSyncHwId(sp<FilterClient> filterClient) {
    if (mTunerDemux != NULL) {
        int hwId;
        Status s = mTunerDemux->getAvSyncHwId(filterClient->getAidlFilter(), &hwId);
        if (ClientHelper::getServiceSpecificErrorCode(s) != Result::SUCCESS) {
            return INVALID_AV_SYNC_HW_ID;
        }
        return hwId;
    }

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

    return INVALID_AV_SYNC_HW_ID;
}

long DemuxClient::getAvSyncTime(int avSyncHwId) {
    if (mTunerDemux != NULL) {
        int64_t time;
        Status s = mTunerDemux->getAvSyncTime(avSyncHwId, &time);
        if (ClientHelper::getServiceSpecificErrorCode(s) != Result::SUCCESS) {
            return INVALID_AV_SYNC_TIME;
        }
        return time;
    }

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

    return INVALID_AV_SYNC_TIME;
}

sp<DvrClient> DemuxClient::openDvr(DvrType dvbType, int bufferSize, sp<DvrClientCallback> cb) {
    if (mTunerDemux != NULL) {
        shared_ptr<ITunerDvr> tunerDvr;
        shared_ptr<TunerDvrCallback> callback =
                ::ndk::SharedRefBase::make<TunerDvrCallback>(cb);
        Status s = mTunerDemux->openDvr((int)dvbType, bufferSize, callback, &tunerDvr);
        if (ClientHelper::getServiceSpecificErrorCode(s) != Result::SUCCESS) {
            return NULL;
        }
        return new DvrClient(tunerDvr);
    }

    if (mDemux != NULL) {
        sp<HidlDvrCallback> callback = new HidlDvrCallback(cb);
        sp<IDvr> hidlDvr = openHidlDvr(dvbType, bufferSize, callback);
        if (hidlDvr != NULL) {
            sp<DvrClient> dvrClient = new DvrClient(NULL);
            dvrClient->setHidlDvr(hidlDvr);
            return dvrClient;
        }
    }

    return NULL;
}

Result DemuxClient::connectCiCam(int ciCamId) {
    if (mTunerDemux != NULL) {
        Status s = mTunerDemux->connectCiCam(ciCamId);
        return ClientHelper::getServiceSpecificErrorCode(s);
    }

    if (mDemux != NULL) {
        return mDemux->connectCiCam(static_cast<uint32_t>(ciCamId));
    }

    return Result::INVALID_STATE;
}

Result DemuxClient::disconnectCiCam() {
    if (mTunerDemux != NULL) {
        Status s = mTunerDemux->disconnectCiCam();
        return ClientHelper::getServiceSpecificErrorCode(s);
    }

    if (mDemux != NULL) {
        return mDemux->disconnectCiCam();
    }

    return Result::INVALID_STATE;
}

Result DemuxClient::close() {
    if (mTunerDemux != NULL) {
        Status s = mTunerDemux->close();
        mTunerDemux = NULL;
        return ClientHelper::getServiceSpecificErrorCode(s);
    }

    if (mDemux != NULL) {
        Result res = mDemux->close();
        mDemux = NULL;
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

sp<ITimeFilter> DemuxClient::openHidlTimeFilter() {
    if (mDemux == NULL) {
        return NULL;
    }

    sp<ITimeFilter> timeFilter;
    Result res;
    mDemux->openTimeFilter(
            [&](Result r, const sp<ITimeFilter>& timeFilterSp) {
                timeFilter = timeFilterSp;
                res = r;
            });

    if (res != Result::SUCCESS || timeFilter == NULL) {
        return NULL;
    }

    return timeFilter;
}

sp<IDvr> DemuxClient::openHidlDvr(DvrType dvrType, int bufferSize,
        sp<HidlDvrCallback> callback) {
    if (mDemux == NULL) {
        return NULL;
    }

    sp<IDvr> hidlDvr;
    Result res;
    mDemux->openDvr(dvrType, bufferSize, callback,
            [&](Result r, const sp<IDvr>& dvr) {
                hidlDvr = dvr;
                res = r;
            });
    if (res != Result::SUCCESS || hidlDvr == NULL) {
        return NULL;
    }

    return hidlDvr;
}

int DemuxClient::getSubType(DemuxFilterType filterType) {
    switch (filterType.mainType) {
        case DemuxFilterMainType::TS:
            return (int)filterType.subType.tsFilterType();
        case DemuxFilterMainType::MMTP:
            return (int)filterType.subType.mmtpFilterType();
        case DemuxFilterMainType::IP:
            return (int)filterType.subType.ipFilterType();
        case DemuxFilterMainType::TLV:
            return (int)filterType.subType.tlvFilterType();
        case DemuxFilterMainType::ALP:
            return (int)filterType.subType.alpFilterType();
        default:
            return -1;
    }
}
}  // namespace android
