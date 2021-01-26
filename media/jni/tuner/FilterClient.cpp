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

#define LOG_TAG "FilterClient"

#include <android-base/logging.h>
#include <utils/Log.h>

#include "FilterClient.h"

using ::android::hardware::tv::tuner::V1_0::DemuxQueueNotifyBits;
using ::android::hardware::tv::tuner::V1_0::DemuxFilterMainType;
using ::android::hardware::tv::tuner::V1_0::DemuxMmtpFilterType;
using ::android::hardware::tv::tuner::V1_0::DemuxTsFilterType;

namespace android {

/////////////// FilterClient ///////////////////////

FilterClient::FilterClient(DemuxFilterType type, shared_ptr<ITunerFilter> tunerFilter) {
    mTunerFilter = tunerFilter;
    mAvSharedHandle = NULL;
    checkIsMediaFilter(type);
}

FilterClient::~FilterClient() {
    mTunerFilter = NULL;
    mFilter = NULL;
    mFilter_1_1 = NULL;
    mAvSharedHandle = NULL;
    mAvSharedMemSize = 0;
}

// TODO: remove after migration to Tuner Service is done.
void FilterClient::setHidlFilter(sp<IFilter> filter) {
    mFilter = filter;
    mFilter_1_1 = ::android::hardware::tv::tuner::V1_1::IFilter::castFrom(mFilter);
    handleAvShareMemory();
}

int FilterClient::read(uint8_t* buffer, int size) {
    // TODO: pending aidl interface

    if (mFilter != NULL) {
        Result res = getFilterMq();
        if (res != Result::SUCCESS) {
            return -1;
        }
        return copyData(buffer, size);
    }

    return -1;
}

SharedHandleInfo FilterClient::getAvSharedHandleInfo() {
    SharedHandleInfo info{
        .sharedHandle = NULL,
        .size = 0,
    };

    // TODO: pending aidl interface

    if (mFilter_1_1 != NULL) {
        info.sharedHandle = mAvSharedHandle;
        info.size = mAvSharedMemSize;
    }

    return info;
}

Result FilterClient::configure(DemuxFilterSettings configure) {
    // TODO: pending aidl interface

    if (mFilter != NULL) {
        return mFilter->configure(configure);
    }

    return Result::INVALID_STATE;
}

Result FilterClient::configureMonitorEvent(int monitorEventType) {
    // TODO: pending aidl interface

    if (mFilter_1_1 != NULL) {
        return mFilter_1_1->configureMonitorEvent(monitorEventType);
    }

    return Result::INVALID_STATE;
}

Result FilterClient::configureIpFilterContextId(int cid) {
    // TODO: pending aidl interface

    if (mFilter_1_1 != NULL) {
        return mFilter_1_1->configureIpCid(cid);
    }

    return Result::INVALID_STATE;
}

Result FilterClient::configureAvStreamType(AvStreamType avStreamType) {
    // TODO: pending aidl interface

    if (mFilter_1_1 != NULL) {
        return mFilter_1_1->configureAvStreamType(avStreamType);
    }

    return Result::INVALID_STATE;
}

Result FilterClient::start() {
    // TODO: pending aidl interface

    if (mFilter != NULL) {
        return mFilter->start();
    }

    return Result::INVALID_STATE;
}

Result FilterClient::stop() {
    // TODO: pending aidl interface

    if (mFilter != NULL) {
        return mFilter->stop();
    }

    return Result::INVALID_STATE;
}

Result FilterClient::flush() {
    // TODO: pending aidl interface

    if (mFilter != NULL) {
        return mFilter->flush();
    }

    return Result::INVALID_STATE;
}

Result FilterClient::getId(uint32_t& id) {
    if (mTunerFilter != NULL) {
        int32_t id32Bit;
        Status s = mTunerFilter->getId(&id32Bit);
        id = static_cast<uint32_t>(id32Bit);
        return ClientHelper::getServiceSpecificErrorCode(s);
    }

    if (mFilter != NULL) {
        Result res;
        mFilter->getId([&](Result r, uint32_t filterId) {
            res = r;
            id = filterId;
        });
        return res;
    }

    return Result::INVALID_STATE;
}

Result FilterClient::getId64Bit(uint64_t& id) {
    if (mTunerFilter != NULL) {
        int64_t id64Bit;
        Status s = mTunerFilter->getId64Bit(&id64Bit);
        id = static_cast<uint64_t>(id64Bit);
        return ClientHelper::getServiceSpecificErrorCode(s);
    }

    if (mFilter_1_1 != NULL) {
        Result res;
        mFilter_1_1->getId64Bit([&](Result r, uint64_t filterId) {
            res = r;
            id = filterId;
        });
        return res;
    }

    return Result::INVALID_STATE;
}

Result FilterClient::releaseAvHandle(native_handle_t* handle, uint64_t avDataId) {
    // TODO: pending aidl interface

    if (mFilter != NULL) {
        return mFilter->releaseAvHandle(hidl_handle(handle), avDataId);
    }

    return Result::INVALID_STATE;
}

Result FilterClient::setDataSource(sp<FilterClient> filterClient){
    // TODO: pending aidl interface

    if (mFilter != NULL) {
        sp<IFilter> sourceFilter = filterClient->getHalFilter();
        if (sourceFilter == NULL) {
            return Result::INVALID_ARGUMENT;
        }
        return mFilter->setDataSource(sourceFilter);
    }

    return Result::INVALID_STATE;
}

Result FilterClient::close() {
    // TODO: pending aidl interface

    if (mFilter != NULL) {
        Result res = mFilter->close();
        if (res == Result::SUCCESS) {
            mFilter = NULL;
        }
        return res;
    }

    return Result::INVALID_STATE;
}

/////////////// IFilterCallback ///////////////////////

HidlFilterCallback::HidlFilterCallback(sp<FilterClientCallback> filterClientCallback)
        : mFilterClientCallback(filterClientCallback) {}

Return<void> HidlFilterCallback::onFilterStatus(const DemuxFilterStatus status) {
    if (mFilterClientCallback != NULL) {
        mFilterClientCallback->onFilterStatus(status);
    }
    return Void();
}

Return<void> HidlFilterCallback::onFilterEvent(const DemuxFilterEvent& filterEvent) {
    if (mFilterClientCallback != NULL) {
        mFilterClientCallback->onFilterEvent(filterEvent);
    }
    return Void();
}

Return<void> HidlFilterCallback::onFilterEvent_1_1(const DemuxFilterEvent& filterEvent,
        const DemuxFilterEventExt& filterEventExt) {
    if (mFilterClientCallback != NULL) {
        mFilterClientCallback->onFilterEvent_1_1(filterEvent, filterEventExt);
    }
    return Void();
}

/////////////// TunerFilterCallback ///////////////////////

TunerFilterCallback::TunerFilterCallback(sp<FilterClientCallback> filterClientCallback)
        : mFilterClientCallback(filterClientCallback) {}

Status TunerFilterCallback::onFilterStatus(int status) {
    if (mFilterClientCallback != NULL) {
        mFilterClientCallback->onFilterStatus(static_cast<DemuxFilterStatus>(status));
        return Status::ok();
    }
    return Status::fromServiceSpecificError(static_cast<int32_t>(Result::INVALID_STATE));
}

Status TunerFilterCallback::onFilterEvent(vector<TunerFilterEvent>* /*filterEvent*/) {
    // TODO: complete onFilterEvent
    return Status::ok();
}

/////////////// FilterClient Helper Methods ///////////////////////

Result FilterClient::getFilterMq() {
    if (mFilter == NULL) {
        return Result::INVALID_STATE;
    }

    if (mFilterMQ != NULL) {
        return Result::SUCCESS;
    }

    Result getQueueDescResult = Result::UNKNOWN_ERROR;
    MQDescriptorSync<uint8_t> filterMQDesc;
    mFilter->getQueueDesc(
            [&](Result r, const MQDescriptorSync<uint8_t>& desc) {
                filterMQDesc = desc;
                getQueueDescResult = r;
            });
    if (getQueueDescResult == Result::SUCCESS) {
        mFilterMQ = std::make_unique<MQ>(filterMQDesc, true);
        EventFlag::createEventFlag(mFilterMQ->getEventFlagWord(), &mFilterMQEventFlag);
    }
    return getQueueDescResult;
}

int FilterClient::copyData(uint8_t* buffer, int size) {
    if (mFilter == NULL || mFilterMQ == NULL || mFilterMQEventFlag == NULL) {
        return -1;
    }

    int available = mFilterMQ->availableToRead();
    size = min(size, available);

    if (mFilterMQ->read(buffer, size)) {
        mFilterMQEventFlag->wake(static_cast<uint32_t>(DemuxQueueNotifyBits::DATA_CONSUMED));
    } else {
        return -1;
    }

    return size;
}

void FilterClient::checkIsMediaFilter(DemuxFilterType type) {
    if (type.mainType == DemuxFilterMainType::MMTP) {
        if (type.subType.mmtpFilterType() == DemuxMmtpFilterType::AUDIO ||
                type.subType.mmtpFilterType() == DemuxMmtpFilterType::VIDEO) {
            mIsMediaFilter = true;
        }
    }

    if (type.mainType == DemuxFilterMainType::TS) {
        if (type.subType.tsFilterType() == DemuxTsFilterType::AUDIO ||
                type.subType.tsFilterType() == DemuxTsFilterType::VIDEO) {
            mIsMediaFilter = true;
        }
    }
}

void FilterClient::handleAvShareMemory() {
    if (mFilter_1_1 != NULL && mIsMediaFilter) {
        mFilter_1_1->getAvSharedHandle([&](Result r, hidl_handle avMemory, uint64_t avMemSize) {
            if (r == Result::SUCCESS) {
                mAvSharedHandle = native_handle_clone(avMemory.getNativeHandle());
                mAvSharedMemSize = avMemSize;
            }
        });
    }
}
}  // namespace android
