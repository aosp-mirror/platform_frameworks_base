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

#ifndef _ANDROID_MEDIA_TV_DVR_CLIENT_H_
#define _ANDROID_MEDIA_TV_DVR_CLIENT_H_

#include <aidl/android/media/tv/tuner/BnTunerDvrCallback.h>
#include <aidl/android/media/tv/tuner/ITunerDvr.h>
#include <android/hardware/tv/tuner/1.0/IDvr.h>
#include <android/hardware/tv/tuner/1.0/IDvrCallback.h>
#include <android/hardware/tv/tuner/1.1/types.h>
#include <fmq/AidlMessageQueue.h>
#include <fmq/MessageQueue.h>

#include "DvrClientCallback.h"
#include "FilterClient.h"

using Status = ::ndk::ScopedAStatus;
using ::aidl::android::hardware::common::fmq::SynchronizedReadWrite;
using ::aidl::android::media::tv::tuner::BnTunerDvrCallback;
using ::aidl::android::media::tv::tuner::ITunerDvr;
using ::aidl::android::media::tv::tuner::TunerDvrSettings;

using ::android::hardware::EventFlag;
using ::android::hardware::MQDescriptorSync;
using ::android::hardware::MessageQueue;
using ::android::hardware::tv::tuner::V1_0::DvrSettings;
using ::android::hardware::tv::tuner::V1_0::IDvr;
using ::android::hardware::tv::tuner::V1_0::IDvrCallback;

using namespace std;

namespace android {

using MQ = MessageQueue<uint8_t, kSynchronizedReadWrite>;
using MQDesc = MQDescriptorSync<uint8_t>;
using AidlMQ = AidlMessageQueue<int8_t, SynchronizedReadWrite>;
using AidlMQDesc = MQDescriptor<int8_t, SynchronizedReadWrite>;

class TunerDvrCallback : public BnTunerDvrCallback {

public:
    TunerDvrCallback(sp<DvrClientCallback> dvrClientCallback);

    Status onRecordStatus(int status);
    Status onPlaybackStatus(int status);

private:
    sp<DvrClientCallback> mDvrClientCallback;
};

struct HidlDvrCallback : public IDvrCallback {

public:
    HidlDvrCallback(sp<DvrClientCallback> dvrClientCallback);
    virtual Return<void> onRecordStatus(const RecordStatus status);
    virtual Return<void> onPlaybackStatus(const PlaybackStatus status);

private:
    sp<DvrClientCallback> mDvrClientCallback;
};

struct DvrClient : public RefBase {

public:
    DvrClient(shared_ptr<ITunerDvr> tunerDvr);
    ~DvrClient();

    // TODO: remove after migration to Tuner Service is done.
    void setHidlDvr(sp<IDvr> dvr);

    /**
     * Set the DVR file descriptor.
     */
    void setFd(int fd);

    /**
     * Read data from file with given size. Return the actual read size.
     */
    long readFromFile(long size);

    /**
     * Read data from the given buffer with given size. Return the actual read size.
     */
    long readFromBuffer(int8_t* buffer, long size);

    /**
     * Write data to file with given size. Return the actual write size.
     */
    long writeToFile(long size);

    /**
     * Write data to the given buffer with given size. Return the actual write size.
     */
    long writeToBuffer(int8_t* buffer, long size);

    /**
     * Configure the DVR.
     */
    Result configure(DvrSettings settings);

    /**
     * Attach one filter to DVR interface for recording.
     */
    Result attachFilter(sp<FilterClient> filterClient);

    /**
     * Detach one filter from the DVR's recording.
     */
    Result detachFilter(sp<FilterClient> filterClient);

    /**
     * Start DVR.
     */
    Result start();

    /**
     * Stop DVR.
     */
    Result stop();

    /**
     * Flush DVR data.
     */
    Result flush();

    /**
     * close the DVR instance to release resource for DVR.
     */
    Result close();

private:
    Result getQueueDesc(MQDesc& dvrMQDesc);
    TunerDvrSettings getAidlDvrSettingsFromHidl(DvrSettings settings);

    /**
     * An AIDL Tuner Dvr Singleton assigned at the first time the Tuner Client
     * opens a dvr. Default null when dvr is not opened.
     */
    shared_ptr<ITunerDvr> mTunerDvr;

    /**
     * A Dvr HAL interface that is ready before migrating to the TunerDvr.
     * This is a temprary interface before Tuner Framework migrates to use TunerService.
     * Default null when the HAL service does not exist.
     */
    sp<IDvr> mDvr;

    AidlMQ* mDvrMQ;
    EventFlag* mDvrMQEventFlag;
    string mFilePath;
    int mFd;
};
}  // namespace android

#endif  // _ANDROID_MEDIA_TV_DVR_CLIENT_H_
