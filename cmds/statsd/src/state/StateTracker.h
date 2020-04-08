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
#pragma once

#include <utils/RefBase.h>
#include "HashableDimensionKey.h"
#include "logd/LogEvent.h"

#include "state/StateListener.h"

#include <unordered_map>

namespace android {
namespace os {
namespace statsd {

class StateTracker : public virtual RefBase {
public:
    StateTracker(const int32_t atomId);

    virtual ~StateTracker(){};

    // Updates state map and notifies all listeners if a state change occurs.
    // Checks if a state change has occurred by getting the state value from
    // the log event and comparing the old and new states.
    void onLogEvent(const LogEvent& event);

    // Adds new listeners to set of StateListeners. If a listener is already
    // registered, it is ignored.
    void registerListener(wp<StateListener> listener);

    void unregisterListener(wp<StateListener> listener);

    // The output is a FieldValue object that has mStateField as the field and
    // the original state value (found using the given query key) as the value.
    //
    // If the key isn't mapped to a state or the key size doesn't match the
    // number of primary fields, the output value is set to kStateUnknown.
    bool getStateValue(const HashableDimensionKey& queryKey, FieldValue* output) const;

    inline int getListenersCount() const {
        return mListeners.size();
    }

    const static int kStateUnknown = -1;

private:
    struct StateValueInfo {
        int32_t state = kStateUnknown;  // state value
        int count = 0;                  // nested count (only used for binary states)
    };

    Field mField;

    // Maps primary key to state value info
    std::unordered_map<HashableDimensionKey, StateValueInfo> mStateMap;

    // Set of all StateListeners (objects listening for state changes)
    std::set<wp<StateListener>> mListeners;

    // Reset all state values in map to the given state.
    void handleReset(const int64_t eventTimeNs, const int32_t newState);

    // Clears the state value mapped to the given primary key by setting it to kStateUnknown.
    void clearStateForPrimaryKey(const int64_t eventTimeNs, const HashableDimensionKey& primaryKey);

    // Update the StateMap based on the received state value.
    void updateStateForPrimaryKey(const int64_t eventTimeNs, const HashableDimensionKey& primaryKey,
                                  const int32_t newState, const bool nested,
                                  StateValueInfo* stateValueInfo);

    // Notify registered state listeners of state change.
    void notifyListeners(const int64_t eventTimeNs, const HashableDimensionKey& primaryKey,
                         const int32_t oldState, const int32_t newState);
};

bool getStateFieldValueFromLogEvent(const LogEvent& event, FieldValue* output);

}  // namespace statsd
}  // namespace os
}  // namespace android
