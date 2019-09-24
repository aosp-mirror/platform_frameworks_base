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

#define DEBUG false  // STOPSHIP if true
#include "Log.h"

#include "StateManager.h"

namespace android {
namespace os {
namespace statsd {

StateManager& StateManager::getInstance() {
    static StateManager sStateManager;
    return sStateManager;
}

void StateManager::onLogEvent(const LogEvent& event) {
    std::lock_guard<std::mutex> lock(mMutex);
    if (mStateTrackers.find(event.GetTagId()) != mStateTrackers.end()) {
        mStateTrackers[event.GetTagId()]->onLogEvent(event);
    }
}

bool StateManager::registerListener(int stateAtomId, wp<StateListener> listener) {
    std::lock_guard<std::mutex> lock(mMutex);

    // Check if state tracker already exists
    if (mStateTrackers.find(stateAtomId) == mStateTrackers.end()) {
        // Create a new state tracker iff atom is a state atom
        auto it = android::util::AtomsInfo::kStateAtomsFieldOptions.find(stateAtomId);
        if (it != android::util::AtomsInfo::kStateAtomsFieldOptions.end()) {
            mStateTrackers[stateAtomId] = new StateTracker(stateAtomId, it->second);
        } else {
            ALOGE("StateManager cannot register listener, Atom %d is not a state atom",
                  stateAtomId);
            return false;
        }
    }
    mStateTrackers[stateAtomId]->registerListener(listener);
    return true;
}

void StateManager::unregisterListener(int stateAtomId, wp<StateListener> listener) {
    std::unique_lock<std::mutex> lock(mMutex);

    // Hold the sp<> until the lock is released so that ~StateTracker() is
    // not called while the lock is held.
    sp<StateTracker> toRemove;

    // Unregister listener from correct StateTracker
    auto it = mStateTrackers.find(stateAtomId);
    if (it != mStateTrackers.end()) {
        it->second->unregisterListener(listener);

        // Remove the StateTracker if it has no listeners
        if (it->second->getListenersCount() == 0) {
            toRemove = it->second;
            mStateTrackers.erase(it);
        }
    } else {
        ALOGE("StateManager cannot unregister listener, StateTracker for atom %d does not exist",
              stateAtomId);
    }
    lock.unlock();
}

int StateManager::getState(int stateAtomId, const HashableDimensionKey& key) {
    std::lock_guard<std::mutex> lock(mMutex);
    if (mStateTrackers.find(stateAtomId) != mStateTrackers.end()) {
        return mStateTrackers[stateAtomId]->getState(key);
    }

    return StateTracker::kStateUnknown;
}

}  // namespace statsd
}  // namespace os
}  // namespace android
