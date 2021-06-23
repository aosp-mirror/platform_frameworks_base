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

#ifndef _ANDROID_MEDIA_TV_CLIENT_HELPER_H_
#define _ANDROID_MEDIA_TV_CLIENT_HELPER_H_

#include <android/binder_parcel_utils.h>
#include <android/hardware/tv/tuner/1.1/types.h>
#include <utils/Log.h>

using Status = ::ndk::ScopedAStatus;

using ::android::hardware::tv::tuner::V1_0::Result;

using namespace std;

namespace android {

struct ClientHelper {

public:
	static Result getServiceSpecificErrorCode(Status& s) {
        if (s.getExceptionCode() == EX_SERVICE_SPECIFIC) {
            return static_cast<Result>(s.getServiceSpecificError());
        } else if (s.isOk()) {
            return Result::SUCCESS;
        }
        ALOGE("Aidl exception code %s", s.getDescription().c_str());
        return Result::UNKNOWN_ERROR;
    }
};
}  // namespace android

#endif  // _ANDROID_MEDIA_TV_CLIENT_HELPER_H_