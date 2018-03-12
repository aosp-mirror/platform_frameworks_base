/*
 * Copyright (C) 2018 The Android Open Source Project
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

#pragma once

#include <android/os/IStatsCompanionService.h>
#include <utils/RefBase.h>

#include "config/ConfigKey.h"
#include "frameworks/base/cmds/statsd/src/statsd_config.pb.h"  // subscription
#include "android/os/StatsDimensionsValue.h"
#include "HashableDimensionKey.h"

#include <mutex>
#include <unordered_map>
#include <vector>

namespace android {
namespace os {
namespace statsd {

// Reports information to subscribers.
// Single instance shared across the process. All methods are thread safe.
class SubscriberReporter {
public:
    /** Get (singleton) instance of SubscriberReporter. */
    static SubscriberReporter& getInstance() {
        static SubscriberReporter subscriberReporter;
        return subscriberReporter;
    }

    ~SubscriberReporter(){};
    SubscriberReporter(SubscriberReporter const&) = delete;
    void operator=(SubscriberReporter const&) = delete;

    /**
     * Tells SubscriberReporter what IStatsCompanionService to use.
     * May be nullptr, but SubscriberReporter will not send broadcasts for any calls
     * to alertBroadcastSubscriber that occur while nullptr.
     */
    void setStatsCompanionService(sp<IStatsCompanionService> statsCompanionService) {
        std::lock_guard<std::mutex> lock(mLock);
        sp<IStatsCompanionService> tmpForLock = mStatsCompanionService;
        mStatsCompanionService = statsCompanionService;
    }

    /**
     * Stores the given intentSender, associating it with the given (configKey, subscriberId) pair.
     * intentSender must be convertible into an IntentSender (in Java) using IntentSender(IBinder).
     */
    void setBroadcastSubscriber(const ConfigKey& configKey,
                                int64_t subscriberId,
                                const sp<android::IBinder>& intentSender);

    /**
     * Erases any intentSender information from the given (configKey, subscriberId) pair.
     */
    void unsetBroadcastSubscriber(const ConfigKey& configKey, int64_t subscriberId);

    /** Remove all information stored by SubscriberReporter about the given config. */
    void removeConfig(const ConfigKey& configKey);

    /**
     * Sends a broadcast via the intentSender previously stored for the
     * given (configKey, subscriberId) pair by setBroadcastSubscriber.
     * Information about the subscriber, as well as information extracted from the dimKey, is sent.
     */
    void alertBroadcastSubscriber(const ConfigKey& configKey,
                                  const Subscription& subscription,
                                  const MetricDimensionKey& dimKey) const;

    static StatsDimensionsValue getStatsDimensionsValue(const HashableDimensionKey& dim);

private:
    SubscriberReporter() {};

    mutable std::mutex mLock;

    /** Binder interface for communicating with StatsCompanionService. */
    sp<IStatsCompanionService> mStatsCompanionService = nullptr;

    /** Maps <ConfigKey, SubscriberId> -> IBinder (which represents an IIntentSender). */
    std::unordered_map<ConfigKey,
            std::unordered_map<int64_t, sp<android::IBinder>>> mIntentMap;

    /**
     * Sends a broadcast via the given intentSender (using mStatsCompanionService), along
     * with the information in the other parameters.
     */
    void sendBroadcastLocked(const sp<android::IBinder>& intentSender,
                             const ConfigKey& configKey,
                             const Subscription& subscription,
                             const std::vector<String16>& cookies,
                             const MetricDimensionKey& dimKey) const;
};

}  // namespace statsd
}  // namespace os
}  // namespace android
