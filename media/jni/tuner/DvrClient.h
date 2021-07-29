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

#ifndef _ANDROID_MEDIA_TV_DVR_CLIENT_H_
#define _ANDROID_MEDIA_TV_DVR_CLIENT_H_

#include <aidl/android/hardware/tv/tuner/DvrSettings.h>
#include <aidl/android/hardware/tv/tuner/Result.h>
#include <aidl/android/media/tv/tuner/BnTunerDvrCallback.h>
#include <aidl/android/media/tv/tuner/ITunerDvr.h>
#include <fmq/AidlMessageQueue.h>

#include "DvrClientCallback.h"
#include "FilterClient.h"

using Status = ::ndk::ScopedAStatus;
using ::aidl::android::hardware::common::fmq::MQDescriptor;
using ::aidl::android::hardware::common::fmq::SynchronizedReadWrite;
using ::aidl::android::hardware::tv::tuner::DvrSettings;
using ::aidl::android::hardware::tv::tuner::PlaybackStatus;
using ::aidl::android::hardware::tv::tuner::RecordStatus;
using ::aidl::android::hardware::tv::tuner::Result;
using ::aidl::android::media::tv::tuner::BnTunerDvrCallback;
using ::aidl::android::media::tv::tuner::ITunerDvr;

using namespace std;

namespace android {

using AidlMQ = AidlMessageQueue<int8_t, SynchronizedReadWrite>;
using AidlMQDesc = MQDescriptor<int8_t, SynchronizedReadWrite>;

class TunerDvrCallback : public BnTunerDvrCallback {

public:
    TunerDvrCallback(sp<DvrClientCallback> dvrClientCallback);

    Status onRecordStatus(RecordStatus status);
    Status onPlaybackStatus(PlaybackStatus status);

private:
    sp<DvrClientCallback> mDvrClientCallback;
};

struct DvrClient : public RefBase {

public:
    DvrClient(shared_ptr<ITunerDvr> tunerDvr);
    ~DvrClient();

    /**
     * Set the DVR file descriptor.
     */
    void setFd(int32_t fd);

    /**
     * Read data from file with given size. Return the actual read size.
     */
    int64_t readFromFile(int64_t size);

    /**
     * Read data from the given buffer with given size. Return the actual read size.
     */
    int64_t readFromBuffer(int8_t* buffer, int64_t size);

    /**
     * Write data to file with given size. Return the actual write size.
     */
    int64_t writeToFile(int64_t size);

    /**
     * Write data to the given buffer with given size. Return the actual write size.
     */
    int64_t writeToBuffer(int8_t* buffer, int64_t size);

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
    /**
     * An AIDL Tuner Dvr Singleton assigned at the first time the Tuner Client
     * opens a dvr. Default null when dvr is not opened.
     */
    shared_ptr<ITunerDvr> mTunerDvr;

    AidlMQ* mDvrMQ;
    EventFlag* mDvrMQEventFlag;
    string mFilePath;
    int32_t mFd;
};
}  // namespace android

#endif  // _ANDROID_MEDIA_TV_DVR_CLIENT_H_
