/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.app.jank;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.util.Pools.SimplePool;
import android.view.Choreographer;

import androidx.annotation.VisibleForTesting;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * StateTracker is responsible for keeping track of currently active states as well as
 * previously encountered states. States are added, updated or removed by widgets that support state
 * tracking. When a state is first added it will get a vsyncid associated to it, when that state
 * is removed or updated to a different state it will have a second vsyncid associated with it. The
 * two vsyncids create a range of ids where that particular state was active.
 * @hide
 */
@FlaggedApi(Flags.FLAG_DETAILED_APP_JANK_METRICS_API)
public class StateTracker {

    // Used to synchronize access to mPreviousStates.
    private final Object mLock = new Object();
    private Choreographer mChoreographer;

    // The max number of StateData objects that will be stored in the pool for reuse.
    private static final int MAX_POOL_SIZE = 500;
    // The max number of currently active states to track.
    protected static final int MAX_CONCURRENT_STATE_COUNT = 25;
    // The maximum number of previously seen states that will be counted.
    protected static final int MAX_PREVIOUSLY_ACTIVE_STATE_COUNT = 1000;

    // Pool to store the previously used StateData objects to save recreating them each time.
    private final SimplePool<StateData> mStateDataObjectPool = new SimplePool<>(MAX_POOL_SIZE);
    // Previously encountered states that have not been associated to a frame.
    private ArrayList<StateData> mPreviousStates = new ArrayList<>();
    // Currently active widgets and widget states
    private ConcurrentHashMap<String, StateData> mActiveStates = new ConcurrentHashMap<>();

    public StateTracker(@NonNull Choreographer choreographer) {
        mChoreographer = choreographer;
    }

    /**
     * Updates the currentState to the nextState.
     * @param widgetCategory preselected general widget category.
     * @param widgetId developer defined widget id if available.
     * @param currentState current state of the widget.
     * @param nextState the state the widget will be in.
     */
    public void updateState(@NonNull String widgetCategory, @NonNull String widgetId,
            @NonNull String currentState, @NonNull String nextState) {
        // remove the now inactive state from the active states list
        removeState(widgetCategory, widgetId, currentState);

        // add the updated state to the active states list
        putState(widgetCategory, widgetId, nextState);
    }

    /**
     * Removes the state from the active state list and adds it to the previously encountered state
     * list. Associates an end vsync id to the state.
     * @param widgetCategory preselected general widget category.
     * @param widgetId developer defined widget id if available.
     * @param widgetState no longer active widget state.
     */
    public void removeState(@NonNull String widgetCategory, @NonNull String widgetId,
            @NonNull String widgetState) {

        String stateKey = getStateKey(widgetCategory, widgetId, widgetState);
        // Check if we have the active state
        StateData stateData = mActiveStates.remove(stateKey);

        // If there are no states that match just return.
        // This can happen if mActiveStates is at MAX_CONCURRENT_STATE_COUNT and a widget tries to
        // remove a state that was never added or if a widget tries to remove the same state twice.
        if (stateData == null) return;

        synchronized (mLock) {
            stateData.mVsyncIdEnd = mChoreographer.getVsyncId();
            // Add the StateData to the previous state list.  We  need to keep a list of all the
            // previously active states until we can process the next batch of frame data.
            if (mPreviousStates.size() < MAX_PREVIOUSLY_ACTIVE_STATE_COUNT) {
                mPreviousStates.add(stateData);
            }
        }
    }

    /**
     * Adds a new state to the active state list. Associates a start vsync id to the state.
     * @param widgetCategory preselected general widget category.
     * @param widgetId developer defined widget id if available.
     * @param widgetState the current active widget state.
     */
    public void putState(@NonNull String widgetCategory, @NonNull String widgetId,
            @NonNull String widgetState) {

        // Check if we can accept a new state
        if (mActiveStates.size() >= MAX_CONCURRENT_STATE_COUNT) return;

        String stateKey = getStateKey(widgetCategory, widgetId, widgetState);

        // Check if there is currently any active states
        // if there is already a state that matches then its presumed as still active.
        if (mActiveStates.containsKey(stateKey)) return;

        // Check if we have am unused state object in the pool
        StateData stateData = mStateDataObjectPool.acquire();
        if (stateData == null) {
            stateData = new StateData();
        }
        stateData.mVsyncIdStart = mChoreographer.getVsyncId();
        stateData.mStateDataKey = stateKey;
        stateData.mWidgetState = widgetState;
        stateData.mWidgetCategory = widgetCategory;
        stateData.mWidgetId = widgetId;
        stateData.mVsyncIdEnd = Long.MAX_VALUE;
        mActiveStates.put(stateKey, stateData);

    }

    /**
     * Will add all previously encountered states as well as all currently active states to the list
     * that was passed in.
     * @param allStates the list that will be populated with the widget states.
     */
    public void retrieveAllStates(ArrayList<StateData> allStates) {
        synchronized (mLock) {
            allStates.addAll(mPreviousStates);
            allStates.addAll(mActiveStates.values());
        }
    }

    /**
     * Call after processing a batch of JankData, will remove any processed states from the
     * previous state list.
     */
    public void stateProcessingComplete() {
        synchronized (mLock) {
            for (int i = mPreviousStates.size() - 1; i >= 0; i--) {
                StateData stateData = mPreviousStates.get(i);
                if (stateData.mProcessed) {
                    mPreviousStates.remove(stateData);
                    mStateDataObjectPool.release(stateData);
                }
            }
        }
    }

    /**
     * Only intended to be used for testing, this enables test methods to submit pending states
     * with known start and end vsyncids.  This allows testing methods to know the exact ranges
     * of vysncid and calculate exactly how many states should or should not be processed.
     * @param stateData the data that will be added.
     *
     */
    @VisibleForTesting
    public void addPendingStateData(List<StateData> stateData) {
        synchronized (mLock) {
            mPreviousStates.addAll(stateData);
        }
    }

    /**
     * Returns a concatenated string of the inputs. This key can be used to retrieve both pending
     * stats and the state that was used to create the pending stat.
     */
    public String getStateKey(String widgetCategory, String widgetId, String widgetState) {
        return widgetCategory + widgetId + widgetState;
    }

    /**
     * @hide
     */
    public static class StateData {

        // Concatenated string of widget category, widget state and widget id.
        public String mStateDataKey;
        public String mWidgetCategory;
        public String mWidgetState;
        public String mWidgetId;
        // vsyncid when the state was first added.
        public long mVsyncIdStart;
        // vsyncid for when the state was removed.
        public long mVsyncIdEnd;
        // Used to indicate whether this state has been processed and can be returned to the pool.
        public boolean mProcessed;
    }
}
