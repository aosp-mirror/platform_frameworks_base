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

#ifndef _ANDROID_MEDIA_TV_FRONTEND_CLIENT_H_
#define _ANDROID_MEDIA_TV_FRONTEND_CLIENT_H_

#include <aidl/android/media/tv/tuner/ITunerFrontend.h>
#include <android/hardware/tv/tuner/1.1/IFrontend.h>
#include <android/hardware/tv/tuner/1.1/IFrontendCallback.h>
#include <android/hardware/tv/tuner/1.1/types.h>

//#include "FrontendClientCallback"

using ::aidl::android::media::tv::tuner::ITunerFrontend;

using ::android::hardware::tv::tuner::V1_0::FrontendInfo;
using ::android::hardware::tv::tuner::V1_0::IFrontend;

using namespace std;

namespace android {

struct FrontendClient : public RefBase {
    FrontendClient(shared_ptr<ITunerFrontend> tunerFrontend);
    ~FrontendClient();

    void setHidlFrontend(sp<IFrontend> frontend);

private:
    /**
     * An AIDL Tuner Frontend Singleton assigned at the first time when the Tuner Client
     * opens a frontend cient. Default null when the service does not exist.
     */
    shared_ptr<ITunerFrontend> mTunerFrontend;

    /**
     * A Frontend 1.0 HAL interface as a fall back interface when the Tuner Service does not exist.
     * This is a temprary connection before the Tuner Framework fully migrates to the TunerService.
     * Default null.
     */
    sp<IFrontend> mFrontend;

    /**
     * A Frontend 1.1 HAL interface as a fall back interface when the Tuner Service does not exist.
     * This is a temprary connection before the Tuner Framework fully migrates to the TunerService.
     * Default null.
     */
    sp<::android::hardware::tv::tuner::V1_1::IFrontend> mFrontend_1_1;
};
}  // namespace android

#endif  // _ANDROID_MEDIA_TV_FRONTEND_CLIENT_H_
