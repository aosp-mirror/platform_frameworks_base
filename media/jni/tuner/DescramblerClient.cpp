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

#define LOG_TAG "DescramblerClient"

#include <android-base/logging.h>
#include <utils/Log.h>

#include "DescramblerClient.h"

using ::android::hardware::tv::tuner::V1_0::Result;

namespace android {

/////////////// DescramblerClient ///////////////////////

DescramblerClient::DescramblerClient(shared_ptr<ITunerDescrambler> tunerDescrambler) {
    mTunerDescrambler = tunerDescrambler;
}

DescramblerClient::~DescramblerClient() {
    mTunerDescrambler = NULL;
    mDescrambler = NULL;
}

// TODO: remove after migration to Tuner Service is done.
void DescramblerClient::setHidlDescrambler(sp<IDescrambler> descrambler) {
    mDescrambler = descrambler;
}

Result DescramblerClient::setDemuxSource(sp<DemuxClient> demuxClient) {
    if (demuxClient == NULL) {
        return Result::INVALID_ARGUMENT;
    }

    if (mTunerDescrambler != NULL) {
        Status s = mTunerDescrambler->setDemuxSource(demuxClient->getAidlDemux());
        return ClientHelper::getServiceSpecificErrorCode(s);
    }

    if (mDescrambler != NULL) {
        return mDescrambler->setDemuxSource(demuxClient->getId());
    }

    return Result::INVALID_STATE;
}

Result DescramblerClient::setKeyToken(vector<uint8_t> keyToken) {
    if (mTunerDescrambler != NULL) {
        Status s = mTunerDescrambler->setKeyToken(keyToken);
        return ClientHelper::getServiceSpecificErrorCode(s);
    }

    if (mDescrambler != NULL) {
        return mDescrambler->setKeyToken(keyToken);
    }

    return Result::INVALID_STATE;
}

Result DescramblerClient::addPid(DemuxPid pid, sp<FilterClient> optionalSourceFilter) {
    if (mTunerDescrambler != NULL) {
        shared_ptr<ITunerFilter> aidlFilter = (optionalSourceFilter == NULL)
                ? NULL : optionalSourceFilter->getAidlFilter();
        Status s = mTunerDescrambler->addPid(getAidlDemuxPid(pid), aidlFilter);
        return ClientHelper::getServiceSpecificErrorCode(s);
    }

    if (mDescrambler != NULL) {
        sp<IFilter> halFilter = (optionalSourceFilter == NULL)
                ? NULL : optionalSourceFilter->getHalFilter();
        return mDescrambler->addPid(pid, halFilter);
    }

    return Result::INVALID_STATE;
}

Result DescramblerClient::removePid(DemuxPid pid, sp<FilterClient> optionalSourceFilter) {
    if (mTunerDescrambler != NULL) {
        shared_ptr<ITunerFilter> aidlFilter = (optionalSourceFilter == NULL)
                ? NULL : optionalSourceFilter->getAidlFilter();
        Status s = mTunerDescrambler->removePid(getAidlDemuxPid(pid), aidlFilter);
        return ClientHelper::getServiceSpecificErrorCode(s);
    }

    if (mDescrambler != NULL) {
        sp<IFilter> halFilter = (optionalSourceFilter == NULL)
                ? NULL : optionalSourceFilter->getHalFilter();
        return mDescrambler->removePid(pid, halFilter);
    }

    return Result::INVALID_STATE;
}

Result DescramblerClient::close() {
    if (mTunerDescrambler != NULL) {
        Status s = mTunerDescrambler->close();
        mTunerDescrambler = NULL;
        return ClientHelper::getServiceSpecificErrorCode(s);
    }

    if (mDescrambler != NULL) {
        Result res = mDescrambler->close();
        mDescrambler = NULL;
        return res;
    }

    return Result::INVALID_STATE;
}

/////////////// DescramblerClient Helper Methods ///////////////////////

TunerDemuxPid DescramblerClient::getAidlDemuxPid(DemuxPid& pid) {
    TunerDemuxPid aidlPid;
    switch (pid.getDiscriminator()) {
        case DemuxPid::hidl_discriminator::tPid:
            aidlPid.set<TunerDemuxPid::tPid>((int)pid.tPid());
            break;
        case DemuxPid::hidl_discriminator::mmtpPid:
            aidlPid.set<TunerDemuxPid::mmtpPid>((int)pid.mmtpPid());
            break;
    }
    return aidlPid;
}
}  // namespace android
