/*
 * Copyright 2019 The Android Open Source Project
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

#ifndef _ANDROID_MEDIA_TV_TUNER_H_
#define _ANDROID_MEDIA_TV_TUNER_H_

#include <android/hardware/tv/tuner/1.0/ITuner.h>
#include <utils/RefBase.h>

#include "jni.h"

using ::android::hardware::tv::tuner::V1_0::FrontendId;
using ::android::hardware::tv::tuner::V1_0::IFrontend;
using ::android::hardware::tv::tuner::V1_0::ITuner;

namespace android {

struct JTuner : public RefBase {
    JTuner(JNIEnv *env, jobject thiz);
    sp<ITuner> getTunerService();
    jobject getFrontendIds();
    jobject openFrontendById(int id);
protected:
    virtual ~JTuner();

private:
    jclass mClass;
    jweak mObject;
    static sp<ITuner> mTuner;
    sp<IFrontend> mFe;
};

}  // namespace android

#endif  // _ANDROID_MEDIA_TV_TUNER_H_
