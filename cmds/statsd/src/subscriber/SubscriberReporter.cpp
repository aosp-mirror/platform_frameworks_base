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

using std::lock_guard;

namespace android {
namespace os {
namespace statsd {

using std::vector;

class BroadcastSubscriberDeathRecipient : public android::IBinder::DeathRecipient {
    public:
        BroadcastSubscriberDeathRecipient(const ConfigKey& configKey, int64_t subscriberId):
            mConfigKey(configKey),
            mSubscriberId(subscriberId) {}
        ~BroadcastSubscriberDeathRecipient() override = default;
    private:
        ConfigKey mConfigKey;
        int64_t mSubscriberId;

    void binderDied(const android::wp<android::IBinder>& who) override {
        if (IInterface::asBinder(SubscriberReporter::getInstance().getBroadcastSubscriber(
              mConfigKey, mSubscriberId)) == who.promote()) {
            SubscriberReporter::getInstance().unsetBroadcastSubscriber(mConfigKey, mSubscriberId);
        }
    }
};

void SubscriberReporter::setBroadcastSubscriber(const ConfigKey& configKey,
                                                int64_t subscriberId,
                                                const sp<IPendingIntentRef>& pir) {
    VLOG("SubscriberReporter::setBroadcastSubscriber called.");
    lock_guard<std::mutex> lock(mLock);
    mIntentMap[configKey][subscriberId] = pir;
    IInterface::asBinder(pir)->linkToDeath(
        new BroadcastSubscriberDeathRecipient(configKey, subscriberId));
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

    vector<String16> cookies;
    cookies.reserve(subscription.broadcast_subscriber_details().cookie_size());
    for (auto& cookie : subscription.broadcast_subscriber_details().cookie()) {
        cookies.push_back(String16(cookie.c_str()));
    }

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
    sendBroadcastLocked(it2->second, configKey, subscription, cookies, dimKey);
}

void SubscriberReporter::sendBroadcastLocked(const sp<IPendingIntentRef>& pir,
                                             const ConfigKey& configKey,
                                             const Subscription& subscription,
                                             const vector<String16>& cookies,
                                             const MetricDimensionKey& dimKey) const {
    VLOG("SubscriberReporter::sendBroadcastLocked called.");
    pir->sendSubscriberBroadcast(
            configKey.GetUid(),
            configKey.GetId(),
            subscription.id(),
            subscription.rule_id(),
            cookies,
            dimKey.getDimensionKeyInWhat().toStatsDimensionsValueParcel());
}

sp<IPendingIntentRef> SubscriberReporter::getBroadcastSubscriber(const ConfigKey& configKey,
                                                                 int64_t subscriberId) {
    lock_guard<std::mutex> lock(mLock);
    auto subscriberMapIt = mIntentMap.find(configKey);
    if (subscriberMapIt == mIntentMap.end()) {
        return nullptr;
    }
    auto pirMapIt = subscriberMapIt->second.find(subscriberId);
    if (pirMapIt == subscriberMapIt->second.end()) {
        return nullptr;
    }
    return pirMapIt->second;
}

}  // namespace statsd
}  // namespace os
}  // namespace android
