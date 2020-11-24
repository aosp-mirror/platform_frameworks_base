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

#define LOG_TAG "FrontendClient"

#include <android-base/logging.h>
#include <utils/Log.h>

#include "FrontendClient.h"

namespace android {

FrontendClient::FrontendClient(shared_ptr<ITunerFrontend> tunerFrontend) {
    mTunerFrontend = tunerFrontend;
}

FrontendClient::~FrontendClient() {
    mTunerFrontend = NULL;
    mFrontend = NULL;
    mFrontend_1_1 = NULL;
}

void FrontendClient::setHidlFrontend(sp<IFrontend> frontend) {
    mFrontend = frontend;
    mFrontend_1_1 = ::android::hardware::tv::tuner::V1_1::IFrontend::castFrom(mFrontend);
}
}
