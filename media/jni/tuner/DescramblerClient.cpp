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

// TODO: pending aidl interface
DescramblerClient::DescramblerClient() {
    //mTunerDescrambler = tunerDescrambler;
}

DescramblerClient::~DescramblerClient() {
    //mTunerDescrambler = NULL;
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

    // TODO: pending aidl interface

    if (mDescrambler != NULL) {
        return mDescrambler->setDemuxSource(demuxClient->getId());
    }

    return Result::INVALID_STATE;
}

Result DescramblerClient::setKeyToken(vector<uint8_t> keyToken) {
    // TODO: pending aidl interface

    if (mDescrambler != NULL) {
        return mDescrambler->setKeyToken(keyToken);
    }

    return Result::INVALID_STATE;
}

Result DescramblerClient::addPid(DemuxPid pid, sp<FilterClient> optionalSourceFilter) {
    // TODO: pending aidl interface

    if (mDescrambler != NULL) {
        return mDescrambler->addPid(pid, optionalSourceFilter->getHalFilter());
    }

    return Result::INVALID_STATE;}

Result DescramblerClient::removePid(DemuxPid pid, sp<FilterClient> optionalSourceFilter) {
    // TODO: pending aidl interface

    if (mDescrambler != NULL) {
        return mDescrambler->addPid(pid, optionalSourceFilter->getHalFilter());
    }

    return Result::INVALID_STATE;}

Result DescramblerClient::close() {
    // TODO: pending aidl interface

    if (mDescrambler != NULL) {
        return mDescrambler->close();
    }

    return Result::INVALID_STATE;}

/////////////// DescramblerClient Helper Methods ///////////////////////

}  // namespace android
