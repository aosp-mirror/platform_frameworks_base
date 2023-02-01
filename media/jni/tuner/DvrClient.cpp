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

//#define LOG_NDEBUG 0
#define LOG_TAG "DvrClient"

#include "DvrClient.h"

#include <aidl/android/hardware/tv/tuner/DemuxQueueNotifyBits.h>
#include <android-base/logging.h>
#include <inttypes.h>
#include <sys/types.h>
#include <unistd.h>
#include <utils/Log.h>

#include "ClientHelper.h"

using ::aidl::android::hardware::tv::tuner::DemuxQueueNotifyBits;

namespace android {
/////////////// DvrClient ///////////////////////
DvrClient::DvrClient(shared_ptr<ITunerDvr> tunerDvr) {
    mTunerDvr = tunerDvr;
    mFd = -1;
    mDvrMQ = nullptr;
    mDvrMQEventFlag = nullptr;
}

DvrClient::~DvrClient() {
    mTunerDvr = nullptr;
    mFd = -1;
    mDvrMQ = nullptr;
    mDvrMQEventFlag = nullptr;
}

void DvrClient::setFd(int32_t fd) {
    mFd = fd;
}

int64_t DvrClient::readFromFile(int64_t size) {
    if (mDvrMQ == nullptr || mDvrMQEventFlag == nullptr) {
        ALOGE("Failed to readFromFile. DVR mq is not configured");
        return -1;
    }
    if (mFd < 0) {
        ALOGE("Failed to readFromFile. File is not configured");
        return -1;
    }

    int64_t available = mDvrMQ->availableToWrite();
    int64_t write = min(size, available);

    AidlMQ::MemTransaction tx;
    int64_t ret = 0;
    if (mDvrMQ->beginWrite(write, &tx)) {
        auto first = tx.getFirstRegion();
        auto data = first.getAddress();
        int64_t length = first.getLength();
        int64_t firstToWrite = min(length, write);
        ret = read(mFd, data, firstToWrite);

        if (ret < 0) {
            ALOGE("Failed to read from FD: %s", strerror(errno));
            return -1;
        }
        if (ret < firstToWrite) {
            ALOGW("file to MQ, first region: %" PRIu64 " bytes to write, but %" PRIu64
                  " bytes written",
                  firstToWrite, ret);
        } else if (firstToWrite < write) {
            ALOGV("write second region: %" PRIu64 " bytes written, %" PRIu64 " bytes in total", ret,
                  write);
            auto second = tx.getSecondRegion();
            data = second.getAddress();
            length = second.getLength();
            int64_t secondToWrite = std::min(length, write - firstToWrite);
            ret += read(mFd, data, secondToWrite);
        }
        ALOGV("file to MQ: %" PRIu64 " bytes need to be written, %" PRIu64 " bytes written", write,
              ret);
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

int64_t DvrClient::readFromBuffer(int8_t* buffer, int64_t size) {
    if (mDvrMQ == nullptr || mDvrMQEventFlag == nullptr) {
        ALOGE("Failed to readFromBuffer. DVR mq is not configured");
        return -1;
    }
    if (buffer == nullptr) {
        ALOGE("Failed to readFromBuffer. Buffer can't be null");
        return -1;
    }

    int64_t available = mDvrMQ->availableToWrite();
    size = min(size, available);

    if (mDvrMQ->write(buffer, size)) {
        mDvrMQEventFlag->wake(static_cast<uint32_t>(DemuxQueueNotifyBits::DATA_READY));
    } else {
        ALOGD("Failed to write FMQ");
        return -1;
    }
    return size;
}

int64_t DvrClient::writeToFile(int64_t size) {
    if (mDvrMQ == nullptr || mDvrMQEventFlag == nullptr) {
        ALOGE("Failed to writeToFile. DVR mq is not configured");
        return -1;
    }
    if (mFd < 0) {
        ALOGE("Failed to writeToFile. File is not configured");
        return -1;
    }

    int64_t available = mDvrMQ->availableToRead();
    int64_t toRead = min(size, available);

    int64_t ret = 0;
    AidlMQ::MemTransaction tx;
    if (mDvrMQ->beginRead(toRead, &tx)) {
        auto first = tx.getFirstRegion();
        auto data = first.getAddress();
        int64_t length = first.getLength();
        int64_t firstToRead = std::min(length, toRead);
        ret = write(mFd, data, firstToRead);

        if (ret < 0) {
            ALOGE("Failed to write to FD: %s", strerror(errno));
            return -1;
        }
        if (ret < firstToRead) {
            ALOGW("MQ to file: %" PRIu64 " bytes read, but %" PRIu64 " bytes written", firstToRead,
                  ret);
        } else if (firstToRead < toRead) {
            ALOGV("read second region: %" PRIu64 " bytes read, %" PRIu64 " bytes in total", ret,
                  toRead);
            auto second = tx.getSecondRegion();
            data = second.getAddress();
            length = second.getLength();
            int32_t secondToRead = toRead - firstToRead;
            ret += write(mFd, data, secondToRead);
        }
        ALOGV("MQ to file: %" PRIu64 " bytes to be read, %" PRIu64 " bytes written", toRead, ret);
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

int64_t DvrClient::writeToBuffer(int8_t* buffer, int64_t size) {
    if (mDvrMQ == nullptr || mDvrMQEventFlag == nullptr) {
        ALOGE("Failed to writetoBuffer. DVR mq is not configured");
        return -1;
    }
    if (buffer == nullptr) {
        ALOGE("Failed to writetoBuffer. Buffer can't be null");
        return -1;
    }

    int64_t available = mDvrMQ->availableToRead();
    size = min(size, available);

    if (mDvrMQ->read(buffer, size)) {
        mDvrMQEventFlag->wake(static_cast<uint32_t>(DemuxQueueNotifyBits::DATA_CONSUMED));
    } else {
        ALOGD("Failed to write FMQ");
        return -1;
    }
    return size;
}

int64_t DvrClient::seekFile(int64_t pos) {
    if (mFd < 0) {
        ALOGE("Failed to seekFile. File is not configured");
        return -1;
    }
    return lseek64(mFd, pos, SEEK_SET);
}

Result DvrClient::configure(DvrSettings settings) {
    if (mTunerDvr != nullptr) {
        Status s = mTunerDvr->configure(settings);
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

    return Result::INVALID_STATE;
}

Result DvrClient::attachFilter(sp<FilterClient> filterClient) {
    if (filterClient == nullptr) {
        return Result::INVALID_ARGUMENT;
    }

    if (mTunerDvr != nullptr) {
        Status s = mTunerDvr->attachFilter(filterClient->getAidlFilter());
        return ClientHelper::getServiceSpecificErrorCode(s);
    }

    return Result::INVALID_STATE;
}

Result DvrClient::detachFilter(sp<FilterClient> filterClient) {
    if (filterClient == nullptr) {
        return Result::INVALID_ARGUMENT;
    }

    if (mTunerDvr != nullptr) {
        Status s = mTunerDvr->detachFilter(filterClient->getAidlFilter());
        return ClientHelper::getServiceSpecificErrorCode(s);
    }

    return Result::INVALID_STATE;
}

Result DvrClient::start() {
    if (mTunerDvr != nullptr) {
        Status s = mTunerDvr->start();
        return ClientHelper::getServiceSpecificErrorCode(s);
    }

    return Result::INVALID_STATE;
}

Result DvrClient::stop() {
    if (mTunerDvr != nullptr) {
        Status s = mTunerDvr->stop();
        return ClientHelper::getServiceSpecificErrorCode(s);
    }

    return Result::INVALID_STATE;
}

Result DvrClient::flush() {
    if (mTunerDvr != nullptr) {
        Status s = mTunerDvr->flush();
        return ClientHelper::getServiceSpecificErrorCode(s);
    }

    return Result::INVALID_STATE;
}

Result DvrClient::close() {
    if (mDvrMQEventFlag != nullptr) {
        EventFlag::deleteEventFlag(&mDvrMQEventFlag);
        mDvrMQEventFlag = nullptr;
    }
    if (mDvrMQ != nullptr) {
        delete mDvrMQ;
        mDvrMQ = nullptr;
    }

    if (mTunerDvr != nullptr) {
        Status s = mTunerDvr->close();
        mTunerDvr = nullptr;
        return ClientHelper::getServiceSpecificErrorCode(s);
    }

    return Result::INVALID_STATE;
}

/////////////// TunerDvrCallback ///////////////////////
TunerDvrCallback::TunerDvrCallback(sp<DvrClientCallback> dvrClientCallback)
        : mDvrClientCallback(dvrClientCallback) {}

Status TunerDvrCallback::onRecordStatus(RecordStatus status) {
    if (mDvrClientCallback != nullptr) {
        mDvrClientCallback->onRecordStatus(status);
        return Status::ok();
    }
    return Status::fromServiceSpecificError(static_cast<int32_t>(Result::INVALID_STATE));
}

Status TunerDvrCallback::onPlaybackStatus(PlaybackStatus status) {
    if (mDvrClientCallback != nullptr) {
        mDvrClientCallback->onPlaybackStatus(status);
        return Status::ok();
    }
    return Status::fromServiceSpecificError(static_cast<int32_t>(Result::INVALID_STATE));
}

}  // namespace android
