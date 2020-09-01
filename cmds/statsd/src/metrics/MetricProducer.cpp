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

#include "../guardrail/StatsdStats.h"
#include "state/StateTracker.h"

using android::util::FIELD_COUNT_REPEATED;
using android::util::FIELD_TYPE_ENUM;
using android::util::FIELD_TYPE_INT32;
using android::util::FIELD_TYPE_INT64;
using android::util::FIELD_TYPE_MESSAGE;
using android::util::ProtoOutputStream;

namespace android {
namespace os {
namespace statsd {


// for ActiveMetric
const int FIELD_ID_ACTIVE_METRIC_ID = 1;
const int FIELD_ID_ACTIVE_METRIC_ACTIVATION = 2;

// for ActiveEventActivation
const int FIELD_ID_ACTIVE_EVENT_ACTIVATION_ATOM_MATCHER_INDEX = 1;
const int FIELD_ID_ACTIVE_EVENT_ACTIVATION_REMAINING_TTL_NANOS = 2;
const int FIELD_ID_ACTIVE_EVENT_ACTIVATION_STATE = 3;

MetricProducer::MetricProducer(
        const int64_t& metricId, const ConfigKey& key, const int64_t timeBaseNs,
        const int conditionIndex, const vector<ConditionState>& initialConditionCache,
        const sp<ConditionWizard>& wizard,
        const std::unordered_map<int, std::shared_ptr<Activation>>& eventActivationMap,
        const std::unordered_map<int, std::vector<std::shared_ptr<Activation>>>&
                eventDeactivationMap,
        const vector<int>& slicedStateAtoms,
        const unordered_map<int, unordered_map<int, int64_t>>& stateGroupMap)
    : mMetricId(metricId),
      mConfigKey(key),
      mTimeBaseNs(timeBaseNs),
      mCurrentBucketStartTimeNs(timeBaseNs),
      mCurrentBucketNum(0),
      mCondition(initialCondition(conditionIndex, initialConditionCache)),
      mConditionTrackerIndex(conditionIndex),
      mConditionSliced(false),
      mWizard(wizard),
      mContainANYPositionInDimensionsInWhat(false),
      mSliceByPositionALL(false),
      mHasLinksToAllConditionDimensionsInTracker(false),
      mEventActivationMap(eventActivationMap),
      mEventDeactivationMap(eventDeactivationMap),
      mIsActive(mEventActivationMap.empty()),
      mSlicedStateAtoms(slicedStateAtoms),
      mStateGroupMap(stateGroupMap) {
}

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
    if (mConditionSliced) {
        for (const auto& link : mMetric2ConditionLinks) {
            getDimensionForCondition(event.getValues(), link, &conditionKey[link.conditionId]);
        }
        auto conditionState =
            mWizard->query(mConditionTrackerIndex, conditionKey,
                           !mHasLinksToAllConditionDimensionsInTracker);
        condition = (conditionState == ConditionState::kTrue);
    } else {
        // TODO: The unknown condition state is not handled here, we should fix it.
        condition = mCondition == ConditionState::kTrue;
    }

    // Stores atom id to primary key pairs for each state atom that the metric is
    // sliced by.
    std::map<int32_t, HashableDimensionKey> statePrimaryKeys;

    // For states with primary fields, use MetricStateLinks to get the primary
    // field values from the log event. These values will form a primary key
    // that will be used to query StateTracker for the correct state value.
    for (const auto& stateLink : mMetric2StateLinks) {
        getDimensionForState(event.getValues(), stateLink,
                             &statePrimaryKeys[stateLink.stateAtomId]);
    }

    // For each sliced state, query StateTracker for the state value using
    // either the primary key from the previous step or the DEFAULT_DIMENSION_KEY.
    //
    // Expected functionality: for any case where the MetricStateLinks are
    // initialized incorrectly (ex. # of state links != # of primary fields, no
    // links are provided for a state with primary fields, links are provided
    // in the wrong order, etc.), StateTracker will simply return kStateUnknown
    // when queried using an incorrect key.
    HashableDimensionKey stateValuesKey;
    for (auto atomId : mSlicedStateAtoms) {
        FieldValue value;
        if (statePrimaryKeys.find(atomId) != statePrimaryKeys.end()) {
            // found a primary key for this state, query using the key
            queryStateValue(atomId, statePrimaryKeys[atomId], &value);
        } else {
            // if no MetricStateLinks exist for this state atom,
            // query using the default dimension key (empty HashableDimensionKey)
            queryStateValue(atomId, DEFAULT_DIMENSION_KEY, &value);
        }
        mapStateValue(atomId, &value);
        stateValuesKey.addValue(value);
    }

    HashableDimensionKey dimensionInWhat;
    filterValues(mDimensionsInWhat, event.getValues(), &dimensionInWhat);
    MetricDimensionKey metricKey(dimensionInWhat, stateValuesKey);
    onMatchedLogEventInternalLocked(matcherIndex, metricKey, conditionKey, condition, event,
                                    statePrimaryKeys);
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

void MetricProducer::queryStateValue(const int32_t atomId, const HashableDimensionKey& queryKey,
                                     FieldValue* value) {
    if (!StateManager::getInstance().getStateValue(atomId, queryKey, value)) {
        value->mValue = Value(StateTracker::kStateUnknown);
        value->mField.setTag(atomId);
        ALOGW("StateTracker not found for state atom %d", atomId);
        return;
    }
}

void MetricProducer::mapStateValue(const int32_t atomId, FieldValue* value) {
    // check if there is a state map for this atom
    auto atomIt = mStateGroupMap.find(atomId);
    if (atomIt == mStateGroupMap.end()) {
        return;
    }
    auto valueIt = atomIt->second.find(value->mValue.int_value);
    if (valueIt == atomIt->second.end()) {
        // state map exists, but value was not put in a state group
        // so set mValue to kStateUnknown
        // TODO(tsaichristine): handle incomplete state maps
        value->mValue.setInt(StateTracker::kStateUnknown);
    } else {
        // set mValue to group_id
        value->mValue.setLong(valueIt->second);
    }
}

HashableDimensionKey MetricProducer::getUnknownStateKey() {
    HashableDimensionKey stateKey;
    for (auto atom : mSlicedStateAtoms) {
        FieldValue fieldValue;
        fieldValue.mField.setTag(atom);
        fieldValue.mValue.setInt(StateTracker::kStateUnknown);
        stateKey.addValue(fieldValue);
    }
    return stateKey;
}

DropEvent MetricProducer::buildDropEvent(const int64_t dropTimeNs, const BucketDropReason reason) {
    DropEvent event;
    event.reason = reason;
    event.dropTimeNs = dropTimeNs;
    return event;
}

bool MetricProducer::maxDropEventsReached() {
    return mCurrentSkippedBucket.dropEvents.size() >= StatsdStats::kMaxLoggedBucketDropEvents;
}

}  // namespace statsd
}  // namespace os
}  // namespace android
