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

StateTracker::StateTracker(const int atomId,
                           const util::StateAtomFieldOptions& stateAtomInfo)
  : mAtomId(atomId),
    mStateField(getSimpleMatcher(atomId, stateAtomInfo.exclusiveField)) {
    // create matcher for each primary field
    // TODO(tsaichristine): handle when primary field is first uid in chain
    for (const auto& primary : stateAtomInfo.primaryFields) {
        Matcher matcher = getSimpleMatcher(atomId, primary);
        mPrimaryFields.push_back(matcher);
    }

    // TODO(tsaichristine): set default state, reset state, and nesting
}

void StateTracker::onLogEvent(const LogEvent& event) {
    // parse event for primary field values i.e. primary key
    HashableDimensionKey primaryKey;
    if (mPrimaryFields.size() > 0) {
        if (!filterValues(mPrimaryFields, event.getValues(), &primaryKey) ||
            primaryKey.getValues().size() != mPrimaryFields.size()) {
            ALOGE("StateTracker error extracting primary key from log event.");
            handleReset();
            return;
        }
    } else {
        // atom has no primary fields
        primaryKey = DEFAULT_DIMENSION_KEY;
    }

    // parse event for state value
    Value state;
    int32_t stateValue;
    if (!filterValues(mStateField, event.getValues(), &state) || state.getType() != INT) {
        ALOGE("StateTracker error extracting state from log event. Type: %d", state.getType());
        handlePartialReset(primaryKey);
        return;
    }
    stateValue = state.int_value;

    if (stateValue == mResetState) {
        VLOG("StateTracker Reset state: %s", state.toString().c_str());
        handleReset();
    }

    // track and update state
    int32_t oldState = 0;
    int32_t newState = 0;
    updateState(primaryKey, stateValue, &oldState, &newState);

    // notify all listeners if state has changed
    if (oldState != newState) {
        VLOG("StateTracker updated state");
        for (auto listener : mListeners) {
            auto sListener = listener.promote();  // safe access to wp<>
            if (sListener != nullptr) {
                sListener->onStateChanged(mAtomId, primaryKey, oldState, newState);
            }
        }
    } else {
        VLOG("StateTracker NO updated state");
    }
}

void StateTracker::registerListener(wp<StateListener> listener) {
    mListeners.insert(listener);
}

void StateTracker::unregisterListener(wp<StateListener> listener) {
    mListeners.erase(listener);
}

int StateTracker::getState(const HashableDimensionKey& queryKey) const {
    if (queryKey.getValues().size() == mPrimaryFields.size()) {
        auto it = mStateMap.find(queryKey);
        if (it != mStateMap.end()) {
            return it->second.state;
        }
    } else if (queryKey.getValues().size() > mPrimaryFields.size()) {
        ALOGE("StateTracker query key size > primary key size is illegal");
    } else {
        ALOGE("StateTracker query key size < primary key size is not supported");
    }
    return mDefaultState;
}

void StateTracker::handleReset() {
    VLOG("StateTracker handle reset");
    for (const auto pair : mStateMap) {
        for (auto l : mListeners) {
            auto sl = l.promote();
            if (sl != nullptr) {
                sl->onStateChanged(mAtomId, pair.first, pair.second.state, mDefaultState);
            }
        }
    }
    mStateMap.clear();
}

void StateTracker::handlePartialReset(const HashableDimensionKey& primaryKey) {
    VLOG("StateTracker handle partial reset");
    if (mStateMap.find(primaryKey) != mStateMap.end()) {
        mStateMap.erase(primaryKey);
    }
}

void StateTracker::updateState(const HashableDimensionKey& primaryKey, const int32_t eventState,
                               int32_t* oldState, int32_t* newState) {
    // get old state (either current state in map or default state)
    auto it = mStateMap.find(primaryKey);
    if (it != mStateMap.end()) {
        *oldState = it->second.state;
    } else {
        *oldState = mDefaultState;
    }

    // update state map
    if (eventState == mDefaultState) {
        // remove (key, state) pair if state returns to default state
        VLOG("\t StateTracker changed to default state")
        mStateMap.erase(primaryKey);
    } else {
        mStateMap[primaryKey].state = eventState;
        mStateMap[primaryKey].count = 1;
    }
    *newState = eventState;

    // TODO(tsaichristine): support atoms with nested counting
}

}  // namespace statsd
}  // namespace os
}  // namespace android
