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

#ifndef _ANDROID_MEDIA_TV_FRONTEND_CLIENT_CALLBACK_H_
#define _ANDROID_MEDIA_TV_FRONTEND_CLIENT_CALLBACK_H_

#include <utils/RefBase.h>

using ::aidl::android::hardware::tv::tuner::FrontendEventType;
using ::aidl::android::hardware::tv::tuner::FrontendScanMessage;
using ::aidl::android::hardware::tv::tuner::FrontendScanMessageType;

using namespace std;

namespace android {

struct FrontendClientCallback : public RefBase {
    virtual void onEvent(FrontendEventType frontendEventType);
    virtual void onScanMessage(FrontendScanMessageType type, const FrontendScanMessage& message);
};
}  // namespace android

#endif  // _ANDROID_MEDIA_TV_FRONTEND_CLIENT_CALLBACK_H_
