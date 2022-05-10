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

namespace android {

/////////////// DescramblerClient ///////////////////////
DescramblerClient::DescramblerClient(shared_ptr<ITunerDescrambler> tunerDescrambler) {
    mTunerDescrambler = tunerDescrambler;
}

DescramblerClient::~DescramblerClient() {
    mTunerDescrambler = nullptr;
}

Result DescramblerClient::setDemuxSource(sp<DemuxClient> demuxClient) {
    if (demuxClient == nullptr) {
        return Result::INVALID_ARGUMENT;
    }

    if (mTunerDescrambler != nullptr) {
        Status s = mTunerDescrambler->setDemuxSource(demuxClient->getAidlDemux());
        return ClientHelper::getServiceSpecificErrorCode(s);
    }

    return Result::INVALID_STATE;
}

Result DescramblerClient::setKeyToken(vector<uint8_t> keyToken) {
    if (mTunerDescrambler != nullptr) {
        Status s = mTunerDescrambler->setKeyToken(keyToken);
        return ClientHelper::getServiceSpecificErrorCode(s);
    }

    return Result::INVALID_STATE;
}

Result DescramblerClient::addPid(DemuxPid pid, sp<FilterClient> optionalSourceFilter) {
    if (mTunerDescrambler != nullptr) {
        shared_ptr<ITunerFilter> aidlFilter =
                (optionalSourceFilter == nullptr) ? nullptr : optionalSourceFilter->getAidlFilter();
        Status s = mTunerDescrambler->addPid(pid, aidlFilter);
        return ClientHelper::getServiceSpecificErrorCode(s);
    }

    return Result::INVALID_STATE;
}

Result DescramblerClient::removePid(DemuxPid pid, sp<FilterClient> optionalSourceFilter) {
    if (mTunerDescrambler != nullptr) {
        shared_ptr<ITunerFilter> aidlFilter =
                (optionalSourceFilter == nullptr) ? nullptr : optionalSourceFilter->getAidlFilter();
        Status s = mTunerDescrambler->removePid(pid, aidlFilter);
        return ClientHelper::getServiceSpecificErrorCode(s);
    }

    return Result::INVALID_STATE;
}

Result DescramblerClient::close() {
    if (mTunerDescrambler != nullptr) {
        Status s = mTunerDescrambler->close();
        mTunerDescrambler = nullptr;
        return ClientHelper::getServiceSpecificErrorCode(s);
    }

    return Result::INVALID_STATE;
}

}  // namespace android
