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

#define DEBUG false  // STOPSHIP if true
#include "Log.h"

#include "SubscriberReporter.h"

using android::IBinder;
using std::lock_guard;
using std::unordered_map;

namespace android {
namespace os {
namespace statsd {

void SubscriberReporter::setBroadcastSubscriber(const ConfigKey& configKey,
                                                int64_t subscriberId,
                                                const sp<IBinder>& intentSender) {
    VLOG("SubscriberReporter::setBroadcastSubscriber called.");
    lock_guard<std::mutex> lock(mLock);
    mIntentMap[configKey][subscriberId] = intentSender;
}

void SubscriberReporter::unsetBroadcastSubscriber(const ConfigKey& configKey,
                                                  int64_t subscriberId) {
    VLOG("SubscriberReporter::unsetBroadcastSubscriber called.");
    lock_guard<std::mutex> lock(mLock);
    auto subscriberMapIt = mIntentMap.find(configKey);
    if (subscriberMapIt != mIntentMap.end()) {
        subscriberMapIt->second.erase(subscriberId);
        if (subscriberMapIt->second.empty()) {
            mIntentMap.erase(configKey);
        }
    }
}

void SubscriberReporter::removeConfig(const ConfigKey& configKey) {
    VLOG("SubscriberReporter::removeConfig called.");
    lock_guard<std::mutex> lock(mLock);
    mIntentMap.erase(configKey);
}

void SubscriberReporter::alertBroadcastSubscriber(const ConfigKey& configKey,
                                                  const Subscription& subscription,
                                                  const MetricDimensionKey& dimKey) const {
    // Reminder about ids:
    //  subscription id - name of the Subscription (that ties the Alert to the broadcast)
    //  subscription rule_id - the name of the Alert (that triggers the broadcast)
    //  subscriber_id - name of the PendingIntent to use to send the broadcast
    //  config uid - the uid that uploaded the config (and therefore gave the PendingIntent,
    //                 although the intent may be to broadcast to a different uid)
    //  config id - the name of this config (for this particular uid)

    VLOG("SubscriberReporter::alertBroadcastSubscriber called.");
    lock_guard<std::mutex> lock(mLock);

    if (!subscription.has_broadcast_subscriber_details()
            || !subscription.broadcast_subscriber_details().has_subscriber_id()) {
        ALOGE("Broadcast subscriber does not have an id.");
        return;
    }
    int64_t subscriberId = subscription.broadcast_subscriber_details().subscriber_id();

    auto it1 = mIntentMap.find(configKey);
    if (it1 == mIntentMap.end()) {
        ALOGW("Cannot inform subscriber for missing config key %s ", configKey.ToString().c_str());
        return;
    }
    auto it2 = it1->second.find(subscriberId);
    if (it2 == it1->second.end()) {
        ALOGW("Cannot inform subscriber of config %s for missing subscriberId %lld ",
                configKey.ToString().c_str(), (long long)subscriberId);
        return;
    }
    sendBroadcastLocked(it2->second, configKey, subscription, dimKey);
}

void SubscriberReporter::sendBroadcastLocked(const sp<IBinder>& intentSender,
                                             const ConfigKey& configKey,
                                             const Subscription& subscription,
                                             const MetricDimensionKey& dimKey) const {
    VLOG("SubscriberReporter::sendBroadcastLocked called.");
    if (mStatsCompanionService == nullptr) {
        ALOGW("Failed to send subscriber broadcast: could not access StatsCompanionService.");
        return;
    }
    mStatsCompanionService->sendSubscriberBroadcast(intentSender,
                                                    configKey.GetUid(),
                                                    configKey.GetId(),
                                                    subscription.id(),
                                                    subscription.rule_id(),
                                                    protoToStatsDimensionsValue(dimKey));
}

StatsDimensionsValue SubscriberReporter::protoToStatsDimensionsValue(
        const MetricDimensionKey& dimKey) {
    return protoToStatsDimensionsValue(dimKey.getDimensionKeyInWhat().getDimensionsValue());
}

StatsDimensionsValue SubscriberReporter::protoToStatsDimensionsValue(
        const DimensionsValue& protoDimsVal) {
    int32_t field = protoDimsVal.field();

    switch (protoDimsVal.value_case()) {
        case DimensionsValue::ValueCase::kValueStr:
            return StatsDimensionsValue(field, String16(protoDimsVal.value_str().c_str()));
        case DimensionsValue::ValueCase::kValueInt:
            return StatsDimensionsValue(field, static_cast<int32_t>(protoDimsVal.value_int()));
        case DimensionsValue::ValueCase::kValueLong:
            return StatsDimensionsValue(field, static_cast<int64_t>(protoDimsVal.value_long()));
        case DimensionsValue::ValueCase::kValueBool:
            return StatsDimensionsValue(field, static_cast<bool>(protoDimsVal.value_bool()));
        case DimensionsValue::ValueCase::kValueFloat:
            return StatsDimensionsValue(field, static_cast<float>(protoDimsVal.value_float()));
        case DimensionsValue::ValueCase::kValueTuple:
            {
                int sz = protoDimsVal.value_tuple().dimensions_value_size();
                std::vector<StatsDimensionsValue> sdvVec(sz);
                for (int i = 0; i < sz; i++) {
                    sdvVec[i] = protoToStatsDimensionsValue(
                            protoDimsVal.value_tuple().dimensions_value(i));
                }
                return StatsDimensionsValue(field, sdvVec);
            }
        default:
            ALOGW("protoToStatsDimensionsValue failed: illegal type.");
            return StatsDimensionsValue();
    }
}

}  // namespace statsd
}  // namespace os
}  // namespace android
