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

struct BroadcastSubscriberDeathCookie {
    BroadcastSubscriberDeathCookie(const ConfigKey& configKey, int64_t subscriberId,
                                   const shared_ptr<IPendingIntentRef>& pir):
        mConfigKey(configKey),
        mSubscriberId(subscriberId),
        mPir(pir) {}

    ConfigKey mConfigKey;
    int64_t mSubscriberId;
    shared_ptr<IPendingIntentRef> mPir;
};

void SubscriberReporter::broadcastSubscriberDied(void* cookie) {
    auto cookie_ = static_cast<BroadcastSubscriberDeathCookie*>(cookie);
    ConfigKey& configKey = cookie_->mConfigKey;
    int64_t subscriberId = cookie_->mSubscriberId;
    shared_ptr<IPendingIntentRef>& pir = cookie_->mPir;

    SubscriberReporter& thiz = getInstance();

    // Erase the mapping from a (config_key, subscriberId) to a pir if the
    // mapping exists.
    lock_guard<mutex> lock(thiz.mLock);
    auto subscriberMapIt = thiz.mIntentMap.find(configKey);
    if (subscriberMapIt != thiz.mIntentMap.end()) {
        auto subscriberMap = subscriberMapIt->second;
        auto pirIt = subscriberMap.find(subscriberId);
        if (pirIt != subscriberMap.end() && pirIt->second == pir) {
            subscriberMap.erase(subscriberId);
            if (subscriberMap.empty()) {
                thiz.mIntentMap.erase(configKey);
            }
        }
    }

    // The death recipient corresponding to this specific pir can never be
    // triggered again, so free up resources.
    delete cookie_;
}

SubscriberReporter::SubscriberReporter() :
    mBroadcastSubscriberDeathRecipient(AIBinder_DeathRecipient_new(broadcastSubscriberDied)) {
}

void SubscriberReporter::setBroadcastSubscriber(const ConfigKey& configKey,
                                                int64_t subscriberId,
                                                const shared_ptr<IPendingIntentRef>& pir) {
    VLOG("SubscriberReporter::setBroadcastSubscriber called.");
    {
        lock_guard<mutex> lock(mLock);
        mIntentMap[configKey][subscriberId] = pir;
    }
    AIBinder_linkToDeath(pir->asBinder().get(), mBroadcastSubscriberDeathRecipient.get(),
                         new BroadcastSubscriberDeathCookie(configKey, subscriberId, pir));
}

void SubscriberReporter::unsetBroadcastSubscriber(const ConfigKey& configKey,
                                                  int64_t subscriberId) {
    VLOG("SubscriberReporter::unsetBroadcastSubscriber called.");
    lock_guard<mutex> lock(mLock);
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
    lock_guard<mutex> lock(mLock);

    if (!subscription.has_broadcast_subscriber_details()
            || !subscription.broadcast_subscriber_details().has_subscriber_id()) {
        ALOGE("Broadcast subscriber does not have an id.");
        return;
    }
    int64_t subscriberId = subscription.broadcast_subscriber_details().subscriber_id();

    vector<string> cookies;
    cookies.reserve(subscription.broadcast_subscriber_details().cookie_size());
    for (auto& cookie : subscription.broadcast_subscriber_details().cookie()) {
        cookies.push_back(cookie);
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

void SubscriberReporter::sendBroadcastLocked(const shared_ptr<IPendingIntentRef>& pir,
                                             const ConfigKey& configKey,
                                             const Subscription& subscription,
                                             const vector<string>& cookies,
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

shared_ptr<IPendingIntentRef> SubscriberReporter::getBroadcastSubscriber(const ConfigKey& configKey,
                                                                         int64_t subscriberId) {
    lock_guard<mutex> lock(mLock);
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
