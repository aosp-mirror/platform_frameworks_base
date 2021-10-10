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

#ifndef _ANDROID_MEDIA_TV_TIME_FILTER_CLIENT_H_
#define _ANDROID_MEDIA_TV_TIME_FILTER_CLIENT_H_

#include <aidl/android/media/tv/tuner/ITunerTimeFilter.h>
#include <android/hardware/tv/tuner/1.0/ITimeFilter.h>
#include <android/hardware/tv/tuner/1.1/types.h>

using ::aidl::android::media::tv::tuner::ITunerTimeFilter;

using Status = ::ndk::ScopedAStatus;
using ::android::hardware::Return;
using ::android::hardware::Void;
using ::android::hardware::hidl_vec;
using ::android::hardware::tv::tuner::V1_0::ITimeFilter;
using ::android::hardware::tv::tuner::V1_0::Result;

using namespace std;

namespace android {

struct TimeFilterClient : public RefBase {

public:
    TimeFilterClient(shared_ptr<ITunerTimeFilter> tunerTimeFilter);
    ~TimeFilterClient();

    // TODO: remove after migration to Tuner Service is done.
    void setHidlTimeFilter(sp<ITimeFilter> timeFilter);

    /**
     * Set time stamp for time based filter.
     */
    Result setTimeStamp(long timeStamp);

    /**
     * Clear the time stamp in the time filter.
     */
    Result clearTimeStamp();

    /**
     * Get the current time in the time filter.
     */
    long getTimeStamp();

    /**
     * Get the time from the beginning of current data source.
     */
    long getSourceTime();

    /**
     * Releases the Time Filter instance.
     */
    Result close();

private:
    /**
     * An AIDL Tuner TimeFilter Singleton assigned at the first time the Tuner Client
     * opens an TimeFilter. Default null when time filter is not opened.
     */
    shared_ptr<ITunerTimeFilter> mTunerTimeFilter;

    /**
     * A TimeFilter HAL interface that is ready before migrating to the TunerTimeFilter.
     * This is a temprary interface before Tuner Framework migrates to use TunerService.
     * Default null when the HAL service does not exist.
     */
    sp<ITimeFilter> mTimeFilter;
};
}  // namespace android

#endif  // _ANDROID_MEDIA_TV_TIME_FILTER_CLIENT_H_
