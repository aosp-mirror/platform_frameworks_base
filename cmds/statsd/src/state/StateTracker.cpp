/*
 * Copyright (C) 2019, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#define DEBUG true  // STOPSHIP if true
#include "Log.h"

#include "stats_util.h"

#include "StateTracker.h"

namespace android {
namespace os {
namespace statsd {

StateTracker::StateTracker(const int32_t atomId) : mField(atomId, 0) {
}

void StateTracker::onLogEvent(const LogEvent& event) {
    const int64_t eventTimeNs = event.GetElapsedTimestampNs();

    // Parse event for primary field values i.e. primary key.
    HashableDimensionKey primaryKey;
    filterPrimaryKey(event.getValues(), &primaryKey);

    FieldValue newState;
    if (!getStateFieldValueFromLogEvent(event, &newState)) {
        ALOGE("StateTracker error extracting state from log event. Missing exclusive state field.");
        clearStateForPrimaryKey(eventTimeNs, primaryKey);
        return;
    }

    mField.setField(newState.mField.getField());

    if (newState.mValue.getType() != INT) {
        ALOGE("StateTracker error extracting state from log event. Type: %d",
              newState.mValue.getType());
        clearStateForPrimaryKey(eventTimeNs, primaryKey);
        return;
    }

    if (int resetState = event.getResetState(); resetState != -1) {
        VLOG("StateTracker new reset state: %d", resetState);
        const FieldValue resetStateFieldValue(mField, Value(resetState));
        handleReset(eventTimeNs, resetStateFieldValue);
        return;
    }

    const bool nested = newState.mAnnotations.isNested();
    StateValueInfo* stateValueInfo = &mStateMap[primaryKey];
    updateStateForPrimaryKey(eventTimeNs, primaryKey, newState, nested, stateValueInfo);
}

void StateTracker::registerListener(wp<StateListener> listener) {
    mListeners.insert(listener);
}

void StateTracker::unregisterListener(wp<StateListener> listener) {
    mListeners.erase(listener);
}

bool StateTracker::getStateValue(const HashableDimensionKey& queryKey, FieldValue* output) const {
    output->mField = mField;

    if (const auto it = mStateMap.find(queryKey); it != mStateMap.end()) {
        output->mValue = it->second.state;
        return true;
    }

    // Set the state value to kStateUnknown if query key is not found in state map.
    output->mValue = kStateUnknown;
    return false;
}

void StateTracker::handleReset(const int64_t eventTimeNs, const FieldValue& newState) {
    VLOG("StateTracker handle reset");
    for (auto& [primaryKey, stateValueInfo] : mStateMap) {
        updateStateForPrimaryKey(eventTimeNs, primaryKey, newState,
                                 false /* nested; treat this state change as not nested */,
                                 &stateValueInfo);
    }
}

void StateTracker::clearStateForPrimaryKey(const int64_t eventTimeNs,
                                           const HashableDimensionKey& primaryKey) {
    VLOG("StateTracker clear state for primary key");
    const std::unordered_map<HashableDimensionKey, StateValueInfo>::iterator it =
            mStateMap.find(primaryKey);

    // If there is no entry for the primaryKey in mStateMap, then the state is already
    // kStateUnknown.
    const FieldValue state(mField, Value(kStateUnknown));
    if (it != mStateMap.end()) {
        updateStateForPrimaryKey(eventTimeNs, primaryKey, state,
                                 false /* nested; treat this state change as not nested */,
                                 &it->second);
    }
}

void StateTracker::updateStateForPrimaryKey(const int64_t eventTimeNs,
                                            const HashableDimensionKey& primaryKey,
                                            const FieldValue& newState, const bool nested,
                                            StateValueInfo* stateValueInfo) {
    FieldValue oldState;
    oldState.mField = mField;
    oldState.mValue.setInt(stateValueInfo->state);
    const int32_t oldStateValue = stateValueInfo->state;
    const int32_t newStateValue = newState.mValue.int_value;

    if (kStateUnknown == newStateValue) {
        mStateMap.erase(primaryKey);
    }

    // Update state map for non-nested counting case.
    // Every state event triggers a state overwrite.
    if (!nested) {
        stateValueInfo->state = newStateValue;
        stateValueInfo->count = 1;

        // Notify listeners if state has changed.
        if (oldStateValue != newStateValue) {
            notifyListeners(eventTimeNs, primaryKey, oldState, newState);
        }
        return;
    }

    // Update state map for nested counting case.
    //
    // Nested counting is only allowed for binary state events such as ON/OFF or
    // ACQUIRE/RELEASE. For example, WakelockStateChanged might have the state
    // events: ON, ON, OFF. The state will still be ON until we see the same
    // number of OFF events as ON events.
    //
    // In atoms.proto, a state atom with nested counting enabled
    // must only have 2 states. There is no enforcemnt here of this requirement.
    // The atom must be logged correctly.
    if (kStateUnknown == newStateValue) {
        if (kStateUnknown != oldStateValue) {
            notifyListeners(eventTimeNs, primaryKey, oldState, newState);
        }
    } else if (oldStateValue == kStateUnknown) {
        stateValueInfo->state = newStateValue;
        stateValueInfo->count = 1;
        notifyListeners(eventTimeNs, primaryKey, oldState, newState);
    } else if (oldStateValue == newStateValue) {
        stateValueInfo->count++;
    } else if (--stateValueInfo->count == 0) {
        stateValueInfo->state = newStateValue;
        stateValueInfo->count = 1;
        notifyListeners(eventTimeNs, primaryKey, oldState, newState);
    }
}

void StateTracker::notifyListeners(const int64_t eventTimeNs,
                                   const HashableDimensionKey& primaryKey,
                                   const FieldValue& oldState, const FieldValue& newState) {
    for (auto l : mListeners) {
        auto sl = l.promote();
        if (sl != nullptr) {
            sl->onStateChanged(eventTimeNs, mField.getTag(), primaryKey, oldState, newState);
        }
    }
}

bool getStateFieldValueFromLogEvent(const LogEvent& event, FieldValue* output) {
    const int exclusiveStateFieldIndex = event.getExclusiveStateFieldIndex();
    if (-1 == exclusiveStateFieldIndex) {
        ALOGE("error extracting state from log event. Missing exclusive state field.");
        return false;
    }

    *output = event.getValues()[exclusiveStateFieldIndex];
    return true;
}

}  // namespace statsd
}  // namespace os
}  // namespace android
