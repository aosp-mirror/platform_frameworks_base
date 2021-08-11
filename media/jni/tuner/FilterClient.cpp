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

#define LOG_TAG "FilterClient"

#include "FilterClient.h"

#include <aidl/android/hardware/tv/tuner/DemuxFilterMainType.h>
#include <aidl/android/hardware/tv/tuner/DemuxQueueNotifyBits.h>
#include <aidlcommonsupport/NativeHandle.h>
#include <android-base/logging.h>
#include <utils/Log.h>

using ::aidl::android::hardware::common::NativeHandle;
using ::aidl::android::hardware::tv::tuner::DemuxFilterMainType;
using ::aidl::android::hardware::tv::tuner::DemuxFilterSubType;
using ::aidl::android::hardware::tv::tuner::DemuxMmtpFilterSettingsFilterSettings;
using ::aidl::android::hardware::tv::tuner::DemuxMmtpFilterType;
using ::aidl::android::hardware::tv::tuner::DemuxQueueNotifyBits;
using ::aidl::android::hardware::tv::tuner::DemuxTsFilterSettingsFilterSettings;
using ::aidl::android::hardware::tv::tuner::DemuxTsFilterType;
using ::aidl::android::hardware::tv::tuner::ScramblingStatus;

namespace android {
/////////////// FilterClient ///////////////////////
FilterClient::FilterClient(DemuxFilterType type, shared_ptr<ITunerFilter> tunerFilter) {
    mTunerFilter = tunerFilter;
    mAvSharedHandle = nullptr;
    checkIsMediaFilter(type);
}

FilterClient::~FilterClient() {
    mTunerFilter = nullptr;
    mAvSharedHandle = nullptr;
    mAvSharedMemSize = 0;
    mIsMediaFilter = false;
    mIsPassthroughFilter = false;
    mFilterMQ = nullptr;
    mFilterMQEventFlag = nullptr;
}

int64_t FilterClient::read(int8_t* buffer, int64_t size) {
    Result res = getFilterMq();
    if (res != Result::SUCCESS) {
        return -1;
    }
    return copyData(buffer, size);
}

SharedHandleInfo FilterClient::getAvSharedHandleInfo() {
    handleAvShareMemory();
    SharedHandleInfo info{
            .sharedHandle = (mIsMediaFilter && !mIsPassthroughFilter) ? mAvSharedHandle : nullptr,
            .size = mAvSharedMemSize,
    };

    return info;
}

Result FilterClient::configure(DemuxFilterSettings configure) {
    Result res;
    checkIsPassthroughFilter(configure);

    if (mTunerFilter != nullptr) {
        Status s = mTunerFilter->configure(configure);
        res = ClientHelper::getServiceSpecificErrorCode(s);
        if (res == Result::SUCCESS) {
            getAvSharedHandleInfo();
        }
        return res;
    }

    return Result::INVALID_STATE;
}

Result FilterClient::configureMonitorEvent(int32_t monitorEventType) {
    if (mTunerFilter != nullptr) {
        Status s = mTunerFilter->configureMonitorEvent(monitorEventType);
        return ClientHelper::getServiceSpecificErrorCode(s);
    }

    return Result::INVALID_STATE;
}

Result FilterClient::configureIpFilterContextId(int32_t cid) {
    if (mTunerFilter != nullptr) {
        Status s = mTunerFilter->configureIpFilterContextId(cid);
        return ClientHelper::getServiceSpecificErrorCode(s);
    }

    return Result::INVALID_STATE;
}

Result FilterClient::configureAvStreamType(AvStreamType avStreamType) {
    if (mTunerFilter != nullptr) {
        Status s = mTunerFilter->configureAvStreamType(avStreamType);
        return ClientHelper::getServiceSpecificErrorCode(s);
    }

    return Result::INVALID_STATE;
}

Result FilterClient::start() {
    if (mTunerFilter != nullptr) {
        Status s = mTunerFilter->start();
        return ClientHelper::getServiceSpecificErrorCode(s);
    }

    return Result::INVALID_STATE;
}

Result FilterClient::stop() {
    if (mTunerFilter != nullptr) {
        Status s = mTunerFilter->stop();
        return ClientHelper::getServiceSpecificErrorCode(s);
    }

    return Result::INVALID_STATE;
}

Result FilterClient::flush() {
    if (mTunerFilter != nullptr) {
        Status s = mTunerFilter->flush();
        return ClientHelper::getServiceSpecificErrorCode(s);
    }

    return Result::INVALID_STATE;
}

Result FilterClient::getId(int32_t& id) {
    if (mTunerFilter != nullptr) {
        Status s = mTunerFilter->getId(&id);
        return ClientHelper::getServiceSpecificErrorCode(s);
    }

    return Result::INVALID_STATE;
}

Result FilterClient::getId64Bit(int64_t& id) {
    if (mTunerFilter != nullptr) {
        Status s = mTunerFilter->getId64Bit(&id);
        return ClientHelper::getServiceSpecificErrorCode(s);
    }

    return Result::INVALID_STATE;
}

Result FilterClient::releaseAvHandle(native_handle_t* handle, uint64_t avDataId) {
    if (mTunerFilter != nullptr) {
        Status s = mTunerFilter->releaseAvHandle(dupToAidl(handle), avDataId);
        return ClientHelper::getServiceSpecificErrorCode(s);
    }

    return Result::INVALID_STATE;
}

Result FilterClient::setDataSource(sp<FilterClient> filterClient){
    if (mTunerFilter != nullptr) {
        Status s = mTunerFilter->setDataSource(filterClient->getAidlFilter());
        return ClientHelper::getServiceSpecificErrorCode(s);
    }

    return Result::INVALID_STATE;
}

Result FilterClient::close() {
    if (mFilterMQEventFlag) {
        EventFlag::deleteEventFlag(&mFilterMQEventFlag);
    }
    mFilterMQEventFlag = nullptr;
    mFilterMQ = nullptr;

    if (mTunerFilter != nullptr) {
        Status s = mTunerFilter->close();
        closeAvSharedMemory();
        mTunerFilter = nullptr;
        return ClientHelper::getServiceSpecificErrorCode(s);
    }

    return Result::INVALID_STATE;
}

/////////////// TunerFilterCallback ///////////////////////

TunerFilterCallback::TunerFilterCallback(sp<FilterClientCallback> filterClientCallback)
        : mFilterClientCallback(filterClientCallback) {}

Status TunerFilterCallback::onFilterStatus(DemuxFilterStatus status) {
    if (mFilterClientCallback != nullptr) {
        mFilterClientCallback->onFilterStatus(status);
        return Status::ok();
    }
    return Status::fromServiceSpecificError(static_cast<int32_t>(Result::INVALID_STATE));
}

Status TunerFilterCallback::onFilterEvent(const vector<DemuxFilterEvent>& filterEvents) {
    if (mFilterClientCallback != nullptr) {
        mFilterClientCallback->onFilterEvent(filterEvents);
        return Status::ok();
    }
    return Status::fromServiceSpecificError(static_cast<int32_t>(Result::INVALID_STATE));
}

Result FilterClient::getFilterMq() {
    if (mFilterMQ != NULL) {
        return Result::SUCCESS;
    }

    AidlMQDesc aidlMqDesc;
    Result res = Result::UNAVAILABLE;

    if (mTunerFilter != NULL) {
        Status s = mTunerFilter->getQueueDesc(&aidlMqDesc);
        if (s.isOk()) {
            mFilterMQ = new (nothrow) AidlMQ(aidlMqDesc, false/*resetPointer*/);
            EventFlag::createEventFlag(mFilterMQ->getEventFlagWord(), &mFilterMQEventFlag);
        }
    }

    return res;
}

int64_t FilterClient::copyData(int8_t* buffer, int64_t size) {
    if (mFilterMQ == nullptr || mFilterMQEventFlag == nullptr) {
        return -1;
    }

    int64_t available = mFilterMQ->availableToRead();
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
        if (type.subType.get<DemuxFilterSubType::Tag::mmtpFilterType>() ==
                    DemuxMmtpFilterType::AUDIO ||
            type.subType.get<DemuxFilterSubType::Tag::mmtpFilterType>() ==
                    DemuxMmtpFilterType::VIDEO) {
            mIsMediaFilter = true;
            return;
        }
    }

    if (type.mainType == DemuxFilterMainType::TS) {
        if (type.subType.get<DemuxFilterSubType::Tag::tsFilterType>() == DemuxTsFilterType::AUDIO ||
            type.subType.get<DemuxFilterSubType::Tag::tsFilterType>() == DemuxTsFilterType::VIDEO) {
            mIsMediaFilter = true;
            return;
        }
    }

    mIsMediaFilter = false;
}

void FilterClient::checkIsPassthroughFilter(DemuxFilterSettings configure) {
    if (!mIsMediaFilter) {
        mIsPassthroughFilter = false;
        return;
    }

    if (configure.getTag() == DemuxFilterSettings::Tag::ts) {
        if (configure.get<DemuxFilterSettings::Tag::ts>()
                    .filterSettings.get<DemuxTsFilterSettingsFilterSettings::Tag::av>()
                    .isPassthrough) {
            mIsPassthroughFilter = true;
            return;
        }
    }

    if (configure.getTag() == DemuxFilterSettings::Tag::mmtp) {
        if (configure.get<DemuxFilterSettings::Tag::mmtp>()
                    .filterSettings.get<DemuxMmtpFilterSettingsFilterSettings::Tag::av>()
                    .isPassthrough) {
            mIsPassthroughFilter = true;
            return;
        }
    }

    mIsPassthroughFilter = false;
}

void FilterClient::handleAvShareMemory() {
    if (mAvSharedHandle != nullptr) {
        return;
    }
    if (mTunerFilter != nullptr && mIsMediaFilter && !mIsPassthroughFilter) {
        int64_t size;
        NativeHandle avMemory;
        Status s = mTunerFilter->getAvSharedHandle(&avMemory, &size);
        if (s.isOk()) {
            mAvSharedHandle = dupFromAidl(avMemory);
            mAvSharedMemSize = size;
        }
    }
}

void FilterClient::closeAvSharedMemory() {
    if (mAvSharedHandle == nullptr) {
        mAvSharedMemSize = 0;
        return;
    }
    native_handle_close(mAvSharedHandle);
    native_handle_delete(mAvSharedHandle);
    mAvSharedMemSize = 0;
    mAvSharedHandle = nullptr;
}
}  // namespace android
