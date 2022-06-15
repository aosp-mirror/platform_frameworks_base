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

#define LOG_TAG "DvrClient"

#include <android-base/logging.h>
#include <fmq/ConvertMQDescriptors.h>
#include <utils/Log.h>

#include "ClientHelper.h"
#include "DvrClient.h"

using ::android::hardware::tv::tuner::V1_0::DemuxQueueNotifyBits;
using ::android::hardware::tv::tuner::V1_0::Result;

namespace android {

/////////////// DvrClient ///////////////////////

DvrClient::DvrClient(shared_ptr<ITunerDvr> tunerDvr) {
    mTunerDvr = tunerDvr;
    mFd = -1;
    mDvrMQ = NULL;
    mDvrMQEventFlag = NULL;
}

DvrClient::~DvrClient() {
    mTunerDvr = NULL;
    mDvr = NULL;
    mFd = -1;
    mDvrMQ = NULL;
    mDvrMQEventFlag = NULL;
}

// TODO: remove after migration to Tuner Service is done.
void DvrClient::setHidlDvr(sp<IDvr> dvr) {
    mDvr = dvr;
}

void DvrClient::setFd(int fd) {
    mFd = fd;
}

long DvrClient::readFromFile(long size) {
    if (mDvrMQ == NULL || mDvrMQEventFlag == NULL) {
        ALOGE("Failed to readFromFile. DVR mq is not configured");
        return -1;
    }
    if (mFd < 0) {
        ALOGE("Failed to readFromFile. File is not configured");
        return -1;
    }

    long available = mDvrMQ->availableToWrite();
    long write = min(size, available);

    AidlMQ::MemTransaction tx;
    long ret = 0;
    if (mDvrMQ->beginWrite(write, &tx)) {
        auto first = tx.getFirstRegion();
        auto data = first.getAddress();
        long length = first.getLength();
        long firstToWrite = min(length, write);
        ret = read(mFd, data, firstToWrite);

        if (ret < 0) {
            ALOGE("Failed to read from FD: %s", strerror(errno));
            return -1;
        }
        if (ret < firstToWrite) {
            ALOGW("file to MQ, first region: %ld bytes to write, but %ld bytes written",
                    firstToWrite, ret);
        } else if (firstToWrite < write) {
            ALOGD("write second region: %ld bytes written, %ld bytes in total", ret, write);
            auto second = tx.getSecondRegion();
            data = second.getAddress();
            length = second.getLength();
            int secondToWrite = std::min(length, write - firstToWrite);
            ret += read(mFd, data, secondToWrite);
        }
        ALOGD("file to MQ: %ld bytes need to be written, %ld bytes written", write, ret);
        if (!mDvrMQ->commitWrite(ret)) {
            ALOGE("Error: failed to commit write!");
            return -1;
        }
    } else {
        ALOGE("dvrMq.beginWrite failed");
    }

    if (ret > 0) {
        mDvrMQEventFlag->wake(static_cast<uint32_t>(DemuxQueueNotifyBits::DATA_READY));
    }
    return ret;
}

long DvrClient::readFromBuffer(int8_t* buffer, long size) {
    if (mDvrMQ == NULL || mDvrMQEventFlag == NULL) {
        ALOGE("Failed to readFromBuffer. DVR mq is not configured");
        return -1;
    }
    if (buffer == nullptr) {
        ALOGE("Failed to readFromBuffer. Buffer can't be null");
        return -1;
    }

    long available = mDvrMQ->availableToWrite();
    size = min(size, available);

    if (mDvrMQ->write(buffer, size)) {
        mDvrMQEventFlag->wake(static_cast<uint32_t>(DemuxQueueNotifyBits::DATA_READY));
    } else {
        ALOGD("Failed to write FMQ");
        return -1;
    }
    return size;
}

long DvrClient::writeToFile(long size) {
    if (mDvrMQ == NULL || mDvrMQEventFlag == NULL) {
        ALOGE("Failed to writeToFile. DVR mq is not configured");
        return -1;
    }
    if (mFd < 0) {
        ALOGE("Failed to writeToFile. File is not configured");
        return -1;
    }

    long available = mDvrMQ->availableToRead();
    long toRead = min(size, available);

    long ret = 0;
    AidlMQ::MemTransaction tx;
    if (mDvrMQ->beginRead(toRead, &tx)) {
        auto first = tx.getFirstRegion();
        auto data = first.getAddress();
        long length = first.getLength();
        long firstToRead = std::min(length, toRead);
        ret = write(mFd, data, firstToRead);

        if (ret < 0) {
            ALOGE("Failed to write to FD: %s", strerror(errno));
            return -1;
        }
        if (ret < firstToRead) {
            ALOGW("MQ to file: %ld bytes read, but %ld bytes written", firstToRead, ret);
        } else if (firstToRead < toRead) {
            ALOGD("read second region: %ld bytes read, %ld bytes in total", ret, toRead);
            auto second = tx.getSecondRegion();
            data = second.getAddress();
            length = second.getLength();
            int secondToRead = toRead - firstToRead;
            ret += write(mFd, data, secondToRead);
        }
        ALOGD("MQ to file: %ld bytes to be read, %ld bytes written", toRead, ret);
        if (!mDvrMQ->commitRead(ret)) {
            ALOGE("Error: failed to commit read!");
            return 0;
        }
    } else {
        ALOGE("dvrMq.beginRead failed");
    }
    if (ret > 0) {
        mDvrMQEventFlag->wake(static_cast<uint32_t>(DemuxQueueNotifyBits::DATA_CONSUMED));
    }

    return ret;
}

long DvrClient::writeToBuffer(int8_t* buffer, long size) {
    if (mDvrMQ == NULL || mDvrMQEventFlag == NULL) {
        ALOGE("Failed to writetoBuffer. DVR mq is not configured");
        return -1;
    }
    if (buffer == nullptr) {
        ALOGE("Failed to writetoBuffer. Buffer can't be null");
        return -1;
    }

    long available = mDvrMQ->availableToRead();
    size = min(size, available);

    if (mDvrMQ->read(buffer, size)) {
        mDvrMQEventFlag->wake(static_cast<uint32_t>(DemuxQueueNotifyBits::DATA_CONSUMED));
    } else {
        ALOGD("Failed to write FMQ");
        return -1;
    }
    return size;
}

Result DvrClient::configure(DvrSettings settings) {
    if (mTunerDvr != NULL) {
        TunerDvrSettings dvrSettings = getAidlDvrSettingsFromHidl(settings);
        Status s = mTunerDvr->configure(dvrSettings);
        Result res = ClientHelper::getServiceSpecificErrorCode(s);
        if (res != Result::SUCCESS) {
            return res;
        }

        AidlMQDesc aidlMqDesc;
        s = mTunerDvr->getQueueDesc(&aidlMqDesc);
        res = ClientHelper::getServiceSpecificErrorCode(s);
        if (res != Result::SUCCESS) {
            return res;
        }
        mDvrMQ = new (nothrow) AidlMQ(aidlMqDesc);
        EventFlag::createEventFlag(mDvrMQ->getEventFlagWord(), &mDvrMQEventFlag);
        return res;
    }

    if (mDvr != NULL) {
        Result res = mDvr->configure(settings);
        if (res == Result::SUCCESS) {
            MQDescriptorSync<uint8_t> dvrMQDesc;
            res = getQueueDesc(dvrMQDesc);
            if (res == Result::SUCCESS) {
                AidlMQDesc aidlMQDesc;
                unsafeHidlToAidlMQDescriptor<uint8_t, int8_t, SynchronizedReadWrite>(
                        dvrMQDesc,  &aidlMQDesc);
                mDvrMQ = new (nothrow) AidlMessageQueue(aidlMQDesc);
                EventFlag::createEventFlag(mDvrMQ->getEventFlagWord(), &mDvrMQEventFlag);
            }
        }
        return res;
    }

    return Result::INVALID_STATE;
}

Result DvrClient::attachFilter(sp<FilterClient> filterClient) {
    if (mTunerDvr != NULL) {
        Status s = mTunerDvr->attachFilter(filterClient->getAidlFilter());
        return ClientHelper::getServiceSpecificErrorCode(s);
    }

    if (mDvr != NULL) {
        sp<IFilter> hidlFilter = filterClient->getHalFilter();
        if (hidlFilter == NULL) {
            return Result::INVALID_ARGUMENT;
        }
        return mDvr->attachFilter(hidlFilter);
    }

    return Result::INVALID_STATE;
}

Result DvrClient::detachFilter(sp<FilterClient> filterClient) {
    if (mTunerDvr != NULL) {
        Status s = mTunerDvr->detachFilter(filterClient->getAidlFilter());
        return ClientHelper::getServiceSpecificErrorCode(s);
    }

    if (mDvr != NULL) {
        sp<IFilter> hidlFilter = filterClient->getHalFilter();
        if (hidlFilter == NULL) {
            return Result::INVALID_ARGUMENT;
        }
        return mDvr->detachFilter(hidlFilter);
    }

    return Result::INVALID_STATE;
}

Result DvrClient::start() {
    if (mTunerDvr != NULL) {
        Status s = mTunerDvr->start();
        return ClientHelper::getServiceSpecificErrorCode(s);
    }

    if (mDvr != NULL) {
        return mDvr->start();
    }

    return Result::INVALID_STATE;
}

Result DvrClient::stop() {
    if (mTunerDvr != NULL) {
        Status s = mTunerDvr->stop();
        return ClientHelper::getServiceSpecificErrorCode(s);
    }

    if (mDvr != NULL) {
        return mDvr->stop();
    }

    return Result::INVALID_STATE;
}

Result DvrClient::flush() {
    if (mTunerDvr != NULL) {
        Status s = mTunerDvr->flush();
        return ClientHelper::getServiceSpecificErrorCode(s);
    }

    if (mDvr != NULL) {
        return mDvr->flush();
    }

    return Result::INVALID_STATE;
}

Result DvrClient::close() {
    if (mDvrMQEventFlag != NULL) {
        EventFlag::deleteEventFlag(&mDvrMQEventFlag);
    }
    mDvrMQ = NULL;

    if (mTunerDvr != NULL) {
        Status s = mTunerDvr->close();
        mTunerDvr = NULL;
        return ClientHelper::getServiceSpecificErrorCode(s);
    }

    if (mDvr != NULL) {
        Result res = mDvr->close();
        mDvr = NULL;
        return res;
    }

    return Result::INVALID_STATE;
}

/////////////// IDvrCallback ///////////////////////

HidlDvrCallback::HidlDvrCallback(sp<DvrClientCallback> dvrClientCallback)
        : mDvrClientCallback(dvrClientCallback) {}

Return<void> HidlDvrCallback::onRecordStatus(const RecordStatus status) {
    if (mDvrClientCallback != NULL) {
        mDvrClientCallback->onRecordStatus(status);
    }
    return Void();
}

Return<void> HidlDvrCallback::onPlaybackStatus(const PlaybackStatus status) {
    if (mDvrClientCallback != NULL) {
        mDvrClientCallback->onPlaybackStatus(status);
    }
    return Void();
}

/////////////// TunerDvrCallback ///////////////////////

TunerDvrCallback::TunerDvrCallback(sp<DvrClientCallback> dvrClientCallback)
        : mDvrClientCallback(dvrClientCallback) {}

Status TunerDvrCallback::onRecordStatus(int status) {
    if (mDvrClientCallback != NULL) {
        mDvrClientCallback->onRecordStatus(static_cast<RecordStatus>(status));
        return Status::ok();
    }
    return Status::fromServiceSpecificError(static_cast<int32_t>(Result::INVALID_STATE));
}

Status TunerDvrCallback::onPlaybackStatus(int status) {
    if (mDvrClientCallback != NULL) {
        mDvrClientCallback->onPlaybackStatus(static_cast<PlaybackStatus>(status));
        return Status::ok();
    }
    return Status::fromServiceSpecificError(static_cast<int32_t>(Result::INVALID_STATE));
}

/////////////// DvrClient Helper Methods ///////////////////////

Result DvrClient::getQueueDesc(MQDesc& dvrMQDesc) {
    if (mDvr != NULL) {
        Result res = Result::UNKNOWN_ERROR;
        mDvr->getQueueDesc([&](Result r, const MQDesc& desc) {
            dvrMQDesc = desc;
            res = r;
        });
        return res;
    }

    return Result::INVALID_STATE;
}

TunerDvrSettings DvrClient::getAidlDvrSettingsFromHidl(DvrSettings settings) {
    TunerDvrSettings s;
    switch (settings.getDiscriminator()) {
        case DvrSettings::hidl_discriminator::record: {
            s.statusMask = static_cast<int>(settings.record().statusMask);
            s.lowThreshold = static_cast<int>(settings.record().lowThreshold);
            s.highThreshold = static_cast<int>(settings.record().highThreshold);
            s.dataFormat = static_cast<int>(settings.record().dataFormat);
            s.packetSize = static_cast<int>(settings.record().packetSize);
            return s;
        }
        case DvrSettings::hidl_discriminator::playback: {
            s.statusMask = static_cast<int>(settings.playback().statusMask);
            s.lowThreshold = static_cast<int>(settings.playback().lowThreshold);
            s.highThreshold = static_cast<int>(settings.playback().highThreshold);
            s.dataFormat = static_cast<int>(settings.playback().dataFormat);
            s.packetSize = static_cast<int>(settings.playback().packetSize);
            return s;
        }
        default:
            break;
    }
    return s;
}
}  // namespace android
