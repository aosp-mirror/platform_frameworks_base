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

#include <aidl/android/os/IPendingIntentRef.h>
#include <utils/RefBase.h>
#include <utils/String16.h>

#include "config/ConfigKey.h"
#include "frameworks/base/cmds/statsd/src/statsd_config.pb.h"  // subscription
#include "HashableDimensionKey.h"

#include <mutex>
#include <unordered_map>
#include <vector>

using aidl::android::os::IPendingIntentRef;
using std::mutex;
using std::shared_ptr;
using std::string;
using std::unordered_map;
using std::vector;

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
     * Stores the given intentSender, associating it with the given (configKey, subscriberId) pair.
     */
    void setBroadcastSubscriber(const ConfigKey& configKey,
                                int64_t subscriberId,
                                const shared_ptr<IPendingIntentRef>& pir);

    /**
     * Erases any intentSender information from the given (configKey, subscriberId) pair.
     */
    void unsetBroadcastSubscriber(const ConfigKey& configKey, int64_t subscriberId);

    /**
     * Sends a broadcast via the intentSender previously stored for the
     * given (configKey, subscriberId) pair by setBroadcastSubscriber.
     * Information about the subscriber, as well as information extracted from the dimKey, is sent.
     */
    void alertBroadcastSubscriber(const ConfigKey& configKey,
                                  const Subscription& subscription,
                                  const MetricDimensionKey& dimKey) const;

    shared_ptr<IPendingIntentRef> getBroadcastSubscriber(const ConfigKey& configKey,
                                                         int64_t subscriberId);

private:
    SubscriberReporter();

    mutable mutex mLock;

    /** Maps <ConfigKey, SubscriberId> -> IPendingIntentRef (which represents a PendingIntent). */
    unordered_map<ConfigKey, unordered_map<int64_t, shared_ptr<IPendingIntentRef>>> mIntentMap;

    /**
     * Sends a broadcast via the given intentSender (using mStatsCompanionService), along
     * with the information in the other parameters.
     */
    void sendBroadcastLocked(const shared_ptr<IPendingIntentRef>& pir,
                             const ConfigKey& configKey,
                             const Subscription& subscription,
                             const vector<string>& cookies,
                             const MetricDimensionKey& dimKey) const;

    ::ndk::ScopedAIBinder_DeathRecipient mBroadcastSubscriberDeathRecipient;

    /**
     * Death recipient callback that is called when a broadcast subscriber dies.
     * The cookie is a pointer to a BroadcastSubscriberDeathCookie.
     */
    static void broadcastSubscriberDied(void* cookie);
};

}  // namespace statsd
}  // namespace os
}  // namespace android
