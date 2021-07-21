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

#ifndef _ANDROID_MEDIA_TV_LNB_CLIENT_CALLBACK_H_
#define _ANDROID_MEDIA_TV_LNB_CLIENT_CALLBACK_H_

using ::android::hardware::hidl_vec;
using ::android::hardware::tv::tuner::V1_0::LnbEventType;

using namespace std;

namespace android {

struct LnbClientCallback : public RefBase {
    virtual void onEvent(const LnbEventType lnbEventType);
    virtual void onDiseqcMessage(const hidl_vec<uint8_t>& diseqcMessage);
};
}  // namespace android

#endif  // _ANDROID_MEDIA_TV_LNB_CLIENT_CALLBACK_H_