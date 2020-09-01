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

#include <inttypes.h>
#include <utils/RefBase.h>

#include <set>
#include <string>
#include <unordered_map>

#include "HashableDimensionKey.h"
#include "packages/UidMap.h"
#include "state/StateListener.h"
#include "state/StateTracker.h"

namespace android {
namespace os {
namespace statsd {

/**
 * This class is NOT thread safe.
 * It should only be used while StatsLogProcessor's lock is held.
 */
class StateManager : public virtual RefBase {
public:
    StateManager();

    ~StateManager(){};

    // Returns a pointer to the single, shared StateManager object.
    static StateManager& getInstance();

    // Unregisters all listeners and removes all trackers from StateManager.
    void clear();

    // Notifies the correct StateTracker of an event.
    void onLogEvent(const LogEvent& event);

    // Notifies the StateTracker for the given atomId to register listener.
    // If the correct StateTracker does not exist, a new StateTracker is created.
    // Note: StateTrackers can be created for non-state atoms. They are essentially empty and
    // do not perform any actions.
    void registerListener(const int32_t atomId, wp<StateListener> listener);

    // Notifies the correct StateTracker to unregister a listener
    // and removes the tracker if it no longer has any listeners.
    void unregisterListener(const int32_t atomId, wp<StateListener> listener);

    // Returns true if the StateTracker exists and queries for the
    // original state value mapped to the given query key. The state value is
    // stored and output in a FieldValue class.
    // Returns false if the StateTracker doesn't exist.
    bool getStateValue(const int32_t atomId, const HashableDimensionKey& queryKey,
                       FieldValue* output) const;

    // Updates mAllowedLogSources with the latest uids for the packages that are allowed to log.
    void updateLogSources(const sp<UidMap>& uidMap);

    void notifyAppChanged(const string& apk, const sp<UidMap>& uidMap);

    inline int getStateTrackersCount() const {
        return mStateTrackers.size();
    }

    inline int getListenersCount(const int32_t atomId) const {
        auto it = mStateTrackers.find(atomId);
        if (it != mStateTrackers.end()) {
            return it->second->getListenersCount();
        }
        return -1;
    }

private:
    mutable std::mutex mMutex;

    // Maps state atom ids to StateTrackers
    std::unordered_map<int32_t, sp<StateTracker>> mStateTrackers;

    // The package names that can log state events.
    const std::set<std::string> mAllowedPkg;

    // The combined uid sources (after translating pkg name to uid).
    // State events from uids that are not in the list will be ignored to avoid state pollution.
    std::set<int32_t> mAllowedLogSources;
};

}  // namespace statsd
}  // namespace os
}  // namespace android
