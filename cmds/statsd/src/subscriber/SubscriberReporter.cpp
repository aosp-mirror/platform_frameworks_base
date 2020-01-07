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
using std::unordered_map;

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
            getStatsDimensionsValue(dimKey.getDimensionKeyInWhat()));
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

void getStatsDimensionsValueHelper(const vector<FieldValue>& dims, size_t* index, int depth,
                                   int prefix, vector<StatsDimensionsValue>* output) {
    size_t count = dims.size();
    while (*index < count) {
        const auto& dim = dims[*index];
        const int valueDepth = dim.mField.getDepth();
        const int valuePrefix = dim.mField.getPrefix(depth);
        if (valueDepth > 2) {
            ALOGE("Depth > 2 not supported");
            return;
        }
        if (depth == valueDepth && valuePrefix == prefix) {
            switch (dim.mValue.getType()) {
                case INT:
                    output->push_back(StatsDimensionsValue(dim.mField.getPosAtDepth(depth),
                                                           dim.mValue.int_value));
                    break;
                case LONG:
                    output->push_back(StatsDimensionsValue(dim.mField.getPosAtDepth(depth),
                                                           dim.mValue.long_value));
                    break;
                case FLOAT:
                    output->push_back(StatsDimensionsValue(dim.mField.getPosAtDepth(depth),
                                                           dim.mValue.float_value));
                    break;
                case STRING:
                    output->push_back(StatsDimensionsValue(dim.mField.getPosAtDepth(depth),
                                                           String16(dim.mValue.str_value.c_str())));
                    break;
                default:
                    break;
            }
            (*index)++;
        } else if (valueDepth > depth && valuePrefix == prefix) {
            vector<StatsDimensionsValue> childOutput;
            getStatsDimensionsValueHelper(dims, index, depth + 1, dim.mField.getPrefix(depth + 1),
                                          &childOutput);
            output->push_back(StatsDimensionsValue(dim.mField.getPosAtDepth(depth), childOutput));
        } else {
            return;
        }
    }
}

StatsDimensionsValue SubscriberReporter::getStatsDimensionsValue(const HashableDimensionKey& dim) {
    if (dim.getValues().size() == 0) {
        return StatsDimensionsValue();
    }

    vector<StatsDimensionsValue> fields;
    size_t index = 0;
    getStatsDimensionsValueHelper(dim.getValues(), &index, 0, 0, &fields);
    return StatsDimensionsValue(dim.getValues()[0].mField.getTag(), fields);
}

}  // namespace statsd
}  // namespace os
}  // namespace android
