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

void StateManager::clear() {
    mStateTrackers.clear();
}

void StateManager::onLogEvent(const LogEvent& event) {
    if (mStateTrackers.find(event.GetTagId()) != mStateTrackers.end()) {
        mStateTrackers[event.GetTagId()]->onLogEvent(event);
    }
}

void StateManager::registerListener(const int32_t atomId, wp<StateListener> listener) {
    // Check if state tracker already exists.
    if (mStateTrackers.find(atomId) == mStateTrackers.end()) {
        mStateTrackers[atomId] = new StateTracker(atomId);
    }
    mStateTrackers[atomId]->registerListener(listener);
}

void StateManager::unregisterListener(const int32_t atomId, wp<StateListener> listener) {
    std::unique_lock<std::mutex> lock(mMutex);

    // Hold the sp<> until the lock is released so that ~StateTracker() is
    // not called while the lock is held.
    sp<StateTracker> toRemove;

    // Unregister listener from correct StateTracker
    auto it = mStateTrackers.find(atomId);
    if (it != mStateTrackers.end()) {
        it->second->unregisterListener(listener);

        // Remove the StateTracker if it has no listeners
        if (it->second->getListenersCount() == 0) {
            toRemove = it->second;
            mStateTrackers.erase(it);
        }
    } else {
        ALOGE("StateManager cannot unregister listener, StateTracker for atom %d does not exist",
              atomId);
    }
    lock.unlock();
}

bool StateManager::getStateValue(const int32_t atomId, const HashableDimensionKey& key,
                                 FieldValue* output) const {
    auto it = mStateTrackers.find(atomId);
    if (it != mStateTrackers.end()) {
        return it->second->getStateValue(key, output);
    }
    return false;
}

}  // namespace statsd
}  // namespace os
}  // namespace android
