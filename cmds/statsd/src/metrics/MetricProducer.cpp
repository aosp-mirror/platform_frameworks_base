/*
 * Copyright (C) 2017 The Android Open Source Project
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
#include "MetricProducer.h"

namespace android {
namespace os {
namespace statsd {

using std::map;

void MetricProducer::onMatchedLogEventLocked(const size_t matcherIndex, const LogEvent& event) {
    int64_t eventTimeNs = event.GetElapsedTimestampNs();
    // this is old event, maybe statsd restarted?
    if (eventTimeNs < mTimeBaseNs) {
        return;
    }

    bool condition;
    ConditionKey conditionKey;
    std::unordered_set<HashableDimensionKey> dimensionKeysInCondition;
    if (mConditionSliced) {
        for (const auto& link : mMetric2ConditionLinks) {
            getDimensionForCondition(event.getValues(), link, &conditionKey[link.conditionId]);
        }
        auto conditionState =
            mWizard->query(mConditionTrackerIndex, conditionKey, mDimensionsInCondition,
                           !mSameConditionDimensionsInTracker,
                           !mHasLinksToAllConditionDimensionsInTracker,
                           &dimensionKeysInCondition);
        condition = (conditionState == ConditionState::kTrue);
    } else {
        condition = mCondition;
    }

    if (mDimensionsInCondition.empty() && condition) {
        dimensionKeysInCondition.insert(DEFAULT_DIMENSION_KEY);
    }

    HashableDimensionKey dimensionInWhat;
    filterValues(mDimensionsInWhat, event.getValues(), &dimensionInWhat);
    MetricDimensionKey metricKey(dimensionInWhat, DEFAULT_DIMENSION_KEY);
    for (const auto& conditionDimensionKey : dimensionKeysInCondition) {
        metricKey.setDimensionKeyInCondition(conditionDimensionKey);
        onMatchedLogEventInternalLocked(
                matcherIndex, metricKey, conditionKey, condition, event);
    }
    if (dimensionKeysInCondition.empty()) {
        onMatchedLogEventInternalLocked(
                matcherIndex, metricKey, conditionKey, condition, event);
    }
}

bool MetricProducer::evaluateActiveStateLocked(int64_t elapsedTimestampNs) {
    bool isActive = mEventActivationMap.empty();
    for (auto& it : mEventActivationMap) {
        if (it.second.state == ActivationState::kActive &&
            elapsedTimestampNs > it.second.ttl_ns + it.second.activation_ns) {
            it.second.state = ActivationState::kNotActive;
        }
        if (it.second.state == ActivationState::kActive) {
            isActive = true;
        }
    }
    return isActive;
}

void MetricProducer::flushIfExpire(int64_t elapsedTimestampNs) {
    std::lock_guard<std::mutex> lock(mMutex);
    if (!mIsActive) {
        return;
    }
    mIsActive = evaluateActiveStateLocked(elapsedTimestampNs);
    if (!mIsActive) {
        flushLocked(elapsedTimestampNs);
    }
}

void MetricProducer::addActivation(int activationTrackerIndex, int64_t ttl_seconds) {
    std::lock_guard<std::mutex> lock(mMutex);
    // When a metric producer does not depend on any activation, its mIsActive is true.
    // Therefor, if this is the 1st activation, mIsActive will turn to false. Otherwise it does not
    // change.
    if  (mEventActivationMap.empty()) {
        mIsActive = false;
    }
    mEventActivationMap[activationTrackerIndex].ttl_ns = ttl_seconds * NS_PER_SEC;
}

void MetricProducer::activateLocked(int activationTrackerIndex, int64_t elapsedTimestampNs) {
    auto it = mEventActivationMap.find(activationTrackerIndex);
    if (it == mEventActivationMap.end()) {
        return;
    }
    if (mActivationType == MetricActivation::ACTIVATE_ON_BOOT) {
        it->second.state = ActivationState::kActiveOnBoot;
        return;
    }
    it->second.activation_ns = elapsedTimestampNs;
    it->second.state = ActivationState::kActive;
    mIsActive = true;
}

void MetricProducer::setActiveLocked(int64_t currentTimeNs, int64_t remainingTtlNs) {
    if (mEventActivationMap.size() == 0) {
        return;
    }
    for (auto& pair : mEventActivationMap) {
        auto& activation = pair.second;
        if (activation.ttl_ns >= remainingTtlNs) {
            activation.activation_ns = currentTimeNs + remainingTtlNs - activation.ttl_ns;
            activation.state = kActive;
            mIsActive = true;
            VLOG("setting new activation time to %lld, %lld, %lld",
                 (long long)activation.activation_ns, (long long)currentTimeNs,
                 (long long)remainingTtlNs);
            return;
        }
    }
    ALOGE("Required ttl is longer than all possible activations.");
}

int64_t MetricProducer::getRemainingTtlNsLocked(int64_t currentTimeNs) const {
    int64_t maxTtl = 0;
    for (const auto& activation : mEventActivationMap) {
        if (activation.second.state == kActive) {
            maxTtl = std::max(maxTtl, activation.second.ttl_ns + activation.second.activation_ns -
                                              currentTimeNs);
        }
    }
    return maxTtl;
}

void MetricProducer::prepActiveForBootIfNecessaryLocked(int64_t currentTimeNs) {
    if (mActivationType != MetricActivation::ACTIVATE_ON_BOOT) {
        return;
    }
    for (auto& activation : mEventActivationMap) {
        if (activation.second.state == kActiveOnBoot) {
            activation.second.state = kActive;
            activation.second.activation_ns = currentTimeNs;
            mIsActive = true;
        }
    }
}

}  // namespace statsd
}  // namespace os
}  // namespace android
