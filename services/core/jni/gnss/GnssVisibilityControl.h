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

#ifndef _ANDROID_SERVER_GNSS_VISIBILITYCONTROL_H
#define _ANDROID_SERVER_GNSS_VISIBILITYCONTROL_H

#pragma once

#ifndef LOG_TAG
#error LOG_TAG must be defined before including this file.
#endif

#include <android/hardware/gnss/visibility_control/BnGnssVisibilityControl.h>
#include <log/log.h>

#include "GnssVisibilityControlCallback.h"
#include "jni.h"

namespace android::gnss {

class GnssVisibilityControlInterface {
public:
    virtual ~GnssVisibilityControlInterface() {}
    virtual jboolean enableNfwLocationAccess(JNIEnv* env, jobjectArray proxyApps) = 0;
    virtual jboolean setCallback(
            const std::unique_ptr<GnssVisibilityControlCallback>& callback) = 0;
};

class GnssVisibilityControlAidl : public GnssVisibilityControlInterface {
public:
    GnssVisibilityControlAidl(
            const sp<android::hardware::gnss::visibility_control::IGnssVisibilityControl>&
                    iGnssVisibilityControl);
    jboolean enableNfwLocationAccess(JNIEnv* env, jobjectArray proxyApps) override;
    jboolean setCallback(const std::unique_ptr<GnssVisibilityControlCallback>& callback) override;

private:
    const sp<android::hardware::gnss::visibility_control::IGnssVisibilityControl>
            mIGnssVisibilityControlAidl;
};

class GnssVisibilityControlHidl : public GnssVisibilityControlInterface {
public:
    GnssVisibilityControlHidl(
            const sp<android::hardware::gnss::visibility_control::V1_0::IGnssVisibilityControl>&
                    iGnssVisibilityControl);
    jboolean enableNfwLocationAccess(JNIEnv* env, jobjectArray proxyApps) override;
    jboolean setCallback(const std::unique_ptr<GnssVisibilityControlCallback>& callback) override;

private:
    const sp<android::hardware::gnss::visibility_control::V1_0::IGnssVisibilityControl>
            mIGnssVisibilityControlHidl;
};

} // namespace android::gnss

#endif // _ANDROID_SERVER_GNSS_VISIBILITYCONTROL_H
