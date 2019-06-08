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

using android::util::FIELD_COUNT_REPEATED;
using android::util::FIELD_TYPE_ENUM;
using android::util::FIELD_TYPE_INT32;
using android::util::FIELD_TYPE_INT64;
using android::util::FIELD_TYPE_MESSAGE;
using android::util::ProtoOutputStream;

namespace android {
namespace os {
namespace statsd {

using std::map;

// for ActiveMetric
const int FIELD_ID_ACTIVE_METRIC_ID = 1;
const int FIELD_ID_ACTIVE_METRIC_ACTIVATION = 2;

// for ActiveEventActivation
const int FIELD_ID_ACTIVE_EVENT_ACTIVATION_ATOM_MATCHER_INDEX = 1;
const int FIELD_ID_ACTIVE_EVENT_ACTIVATION_REMAINING_TTL_NANOS = 2;
const int FIELD_ID_ACTIVE_EVENT_ACTIVATION_STATE = 3;

void MetricProducer::onMatchedLogEventLocked(const size_t matcherIndex, const LogEvent& event) {
    if (!mIsActive) {
        return;
    }
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
        // TODO: The unknown condition state is not handled here, we should fix it.
        condition = mCondition == ConditionState::kTrue;
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
        if (it.second->state == ActivationState::kActive &&
            elapsedTimestampNs > it.second->ttl_ns + it.second->start_ns) {
            it.second->state = ActivationState::kNotActive;
        }
        if (it.second->state == ActivationState::kActive) {
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
        onActiveStateChangedLocked(elapsedTimestampNs);
    }
}

void MetricProducer::addActivation(int activationTrackerIndex, const ActivationType& activationType,
        int64_t ttl_seconds, int deactivationTrackerIndex) {
    std::lock_guard<std::mutex> lock(mMutex);
    // When a metric producer does not depend on any activation, its mIsActive is true.
    // Therefore, if this is the 1st activation, mIsActive will turn to false. Otherwise it does not
    // change.
    if  (mEventActivationMap.empty()) {
        mIsActive = false;
    }
    std::shared_ptr<Activation> activation =
            std::make_shared<Activation>(activationType, ttl_seconds * NS_PER_SEC);
    mEventActivationMap.emplace(activationTrackerIndex, activation);
    if (-1 != deactivationTrackerIndex) {
        auto& deactivationList = mEventDeactivationMap[deactivationTrackerIndex];
        deactivationList.push_back(activation);
    }
}

void MetricProducer::activateLocked(int activationTrackerIndex, int64_t elapsedTimestampNs) {
    auto it = mEventActivationMap.find(activationTrackerIndex);
    if (it == mEventActivationMap.end()) {
        return;
    }
    auto& activation = it->second;
    if (ACTIVATE_ON_BOOT == activation->activationType) {
        if (ActivationState::kNotActive == activation->state) {
            activation->state = ActivationState::kActiveOnBoot;
        }
        // If the Activation is already active or set to kActiveOnBoot, do nothing.
        return;
    }
    activation->start_ns = elapsedTimestampNs;
    activation->state = ActivationState::kActive;
    bool oldActiveState = mIsActive;
    mIsActive = true;
    if (!oldActiveState) { // Metric went from not active to active.
        onActiveStateChangedLocked(elapsedTimestampNs);
    }
}

void MetricProducer::cancelEventActivationLocked(int deactivationTrackerIndex) {
    auto it = mEventDeactivationMap.find(deactivationTrackerIndex);
    if (it == mEventDeactivationMap.end()) {
        return;
    }
    for (auto activationToCancelIt : it->second)  {
        activationToCancelIt->state = ActivationState::kNotActive;
    }
}

void MetricProducer::loadActiveMetricLocked(const ActiveMetric& activeMetric,
                                            int64_t currentTimeNs) {
    if (mEventActivationMap.size() == 0) {
        return;
    }
    for (int i = 0; i < activeMetric.activation_size(); i++) {
        const auto& activeEventActivation = activeMetric.activation(i);
        auto it = mEventActivationMap.find(activeEventActivation.atom_matcher_index());
        if (it == mEventActivationMap.end()) {
            ALOGE("Saved event activation not found");
            continue;
        }
        auto& activation = it->second;
        // If the event activation does not have a state, assume it is active.
        if (!activeEventActivation.has_state() ||
                activeEventActivation.state() == ActiveEventActivation::ACTIVE) {
            // We don't want to change the ttl for future activations, so we set the start_ns
            // such that start_ns + ttl_ns == currentTimeNs + remaining_ttl_nanos
            activation->start_ns =
                currentTimeNs + activeEventActivation.remaining_ttl_nanos() - activation->ttl_ns;
            activation->state = ActivationState::kActive;
            mIsActive = true;
        } else if (activeEventActivation.state() == ActiveEventActivation::ACTIVATE_ON_BOOT) {
            activation->state = ActivationState::kActiveOnBoot;
        }
    }
}

void MetricProducer::writeActiveMetricToProtoOutputStream(
        int64_t currentTimeNs, const DumpReportReason reason, ProtoOutputStream* proto) {
    proto->write(FIELD_TYPE_INT64 | FIELD_ID_ACTIVE_METRIC_ID, (long long)mMetricId);
    for (auto& it : mEventActivationMap) {
        const int atom_matcher_index = it.first;
        const std::shared_ptr<Activation>& activation = it.second;

        if (ActivationState::kNotActive == activation->state ||
                (ActivationState::kActive == activation->state &&
                 activation->start_ns + activation->ttl_ns < currentTimeNs)) {
            continue;
        }

        const uint64_t activationToken = proto->start(FIELD_TYPE_MESSAGE | FIELD_COUNT_REPEATED |
                FIELD_ID_ACTIVE_METRIC_ACTIVATION);
        proto->write(FIELD_TYPE_INT32 | FIELD_ID_ACTIVE_EVENT_ACTIVATION_ATOM_MATCHER_INDEX,
                atom_matcher_index);
        if (ActivationState::kActive == activation->state) {
            const int64_t remainingTtlNs =
                    activation->start_ns + activation->ttl_ns - currentTimeNs;
            proto->write(FIELD_TYPE_INT64 | FIELD_ID_ACTIVE_EVENT_ACTIVATION_REMAINING_TTL_NANOS,
                    (long long)remainingTtlNs);
            proto->write(FIELD_TYPE_ENUM | FIELD_ID_ACTIVE_EVENT_ACTIVATION_STATE,
                    ActiveEventActivation::ACTIVE);

        } else if (ActivationState::kActiveOnBoot == activation->state) {
            if (reason == DEVICE_SHUTDOWN || reason == TERMINATION_SIGNAL_RECEIVED) {
                proto->write(
                        FIELD_TYPE_INT64 | FIELD_ID_ACTIVE_EVENT_ACTIVATION_REMAINING_TTL_NANOS,
                        (long long)activation->ttl_ns);
                proto->write(FIELD_TYPE_ENUM | FIELD_ID_ACTIVE_EVENT_ACTIVATION_STATE,
                                    ActiveEventActivation::ACTIVE);
            } else if (reason == STATSCOMPANION_DIED) {
                // We are saving because of system server death, not due to a device shutdown.
                // Next time we load, we do not want to activate metrics that activate on boot.
                proto->write(FIELD_TYPE_ENUM | FIELD_ID_ACTIVE_EVENT_ACTIVATION_STATE,
                                                    ActiveEventActivation::ACTIVATE_ON_BOOT);
            }
        }
        proto->end(activationToken);
    }
}

}  // namespace statsd
}  // namespace os
}  // namespace android
