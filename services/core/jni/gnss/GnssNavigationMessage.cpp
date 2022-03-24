/*
 * Copyright (C) 2021 The Android Open Source Project
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

// Define LOG_TAG before <log/log.h> to overwrite the default value.
#define LOG_TAG "GnssNavigationMessageJni"

#include "GnssNavigationMessage.h"

#include "Utils.h"

namespace android::gnss {

using hardware::gnss::IGnssNavigationMessageInterface;
using IGnssNavigationMessageHidl = hardware::gnss::V1_0::IGnssNavigationMessage;

// Implementation of GnssNavigationMessage (AIDL HAL)

GnssNavigationMessageAidl::GnssNavigationMessageAidl(
        const sp<IGnssNavigationMessageInterface>& iGnssNavigationMessage)
      : mIGnssNavigationMessage(iGnssNavigationMessage) {
    assert(mIGnssNavigationMessage != nullptr);
}

jboolean GnssNavigationMessageAidl::setCallback(
        const std::unique_ptr<GnssNavigationMessageCallback>& callback) {
    auto status = mIGnssNavigationMessage->setCallback(callback->getAidl());
    return checkAidlStatus(status, "IGnssNavigationMessageAidl setCallback() failed.");
}

jboolean GnssNavigationMessageAidl::close() {
    auto status = mIGnssNavigationMessage->close();
    return checkAidlStatus(status, "IGnssNavigationMessageAidl close() failed");
}

// Implementation of GnssNavigationMessageHidl

GnssNavigationMessageHidl::GnssNavigationMessageHidl(
        const sp<IGnssNavigationMessageHidl>& iGnssNavigationMessage)
      : mIGnssNavigationMessageHidl(iGnssNavigationMessage) {
    assert(mIGnssNavigationMessageHidl != nullptr);
}

jboolean GnssNavigationMessageHidl::setCallback(
        const std::unique_ptr<GnssNavigationMessageCallback>& callback) {
    auto result = mIGnssNavigationMessageHidl->setCallback(callback->getHidl());

    IGnssNavigationMessageHidl::GnssNavigationMessageStatus initRet = result;
    if (initRet != IGnssNavigationMessageHidl::GnssNavigationMessageStatus::SUCCESS) {
        ALOGE("An error has been found in %s: %d", __FUNCTION__, static_cast<int32_t>(initRet));
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

jboolean GnssNavigationMessageHidl::close() {
    auto result = mIGnssNavigationMessageHidl->close();
    return checkHidlReturn(result, "IGnssNavigationMessage close() failed.");
}

} // namespace android::gnss
