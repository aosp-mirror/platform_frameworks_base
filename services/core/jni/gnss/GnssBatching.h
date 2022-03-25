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

#ifndef _ANDROID_SERVER_GNSS_GNSSBATCHING_H
#define _ANDROID_SERVER_GNSS_GNSSBATCHING_H

#pragma once

#ifndef LOG_TAG
#error LOG_TAG must be defined before including this file.
#endif

#include <android/hardware/gnss/1.0/IGnssBatching.h>
#include <android/hardware/gnss/2.0/IGnssBatching.h>
#include <android/hardware/gnss/BnGnssBatching.h>
#include <log/log.h>

#include "GnssBatchingCallback.h"
#include "jni.h"

namespace android::gnss {

class GnssBatchingInterface {
public:
    virtual ~GnssBatchingInterface() {}
    virtual jboolean init(const std::unique_ptr<GnssBatchingCallback>& callback) = 0;
    virtual jint getBatchSize() = 0;
    virtual jboolean start(long periodNanos, float minUpdateDistanceMeters,
                           bool wakeupOnFifoFull) = 0;
    virtual jboolean stop() = 0;
    virtual jboolean flush() = 0;
    virtual jboolean cleanup() = 0;
};

class GnssBatching : public GnssBatchingInterface {
public:
    GnssBatching(const sp<android::hardware::gnss::IGnssBatching>& iGnssBatching);
    jboolean init(const std::unique_ptr<GnssBatchingCallback>& callback) override;
    jint getBatchSize() override;
    jboolean start(long periodNanos, float minUpdateDistanceMeters, bool wakeupOnFifoFull) override;
    jboolean stop() override;
    jboolean flush() override;
    jboolean cleanup() override;

private:
    const sp<android::hardware::gnss::IGnssBatching> mIGnssBatching;
};

class GnssBatching_V1_0 : public GnssBatchingInterface {
public:
    GnssBatching_V1_0(const sp<android::hardware::gnss::V1_0::IGnssBatching>& iGnssBatching);
    jboolean init(const std::unique_ptr<GnssBatchingCallback>& callback) override;
    jint getBatchSize() override;
    jboolean start(long periodNanos, float minUpdateDistanceMeters, bool wakeupOnFifoFull) override;
    jboolean stop() override;
    jboolean flush() override;
    jboolean cleanup() override;

private:
    const sp<android::hardware::gnss::V1_0::IGnssBatching> mIGnssBatching_V1_0;
};

class GnssBatching_V2_0 : public GnssBatching_V1_0 {
public:
    GnssBatching_V2_0(const sp<android::hardware::gnss::V2_0::IGnssBatching>& iGnssBatching);
    jboolean init(const std::unique_ptr<GnssBatchingCallback>& callback) override;

private:
    const sp<android::hardware::gnss::V2_0::IGnssBatching> mIGnssBatching_V2_0;
};

} // namespace android::gnss

#endif // _ANDROID_SERVER_GNSS_GNSSBATCHING_H
