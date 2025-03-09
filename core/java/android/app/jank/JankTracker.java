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
import android.os.Handler;
import android.os.HandlerThread;
import android.view.AttachedSurfaceControl;
import android.view.Choreographer;
import android.view.SurfaceControl;
import android.view.View;
import android.view.ViewTreeObserver;

import com.android.internal.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.HashMap;

/**
 * This class is responsible for registering callbacks that will receive JankData batches.
 * It handles managing the background thread that JankData will be processed on. As well as acting
 * as an intermediary between widgets and the state tracker, routing state changes to the tracker.
 * @hide
 */
@FlaggedApi(Flags.FLAG_DETAILED_APP_JANK_METRICS_API)
public class JankTracker {

    // Tracks states reported by widgets.
    private StateTracker mStateTracker;
    // Processes JankData batches and associates frames to widget states.
    private JankDataProcessor mJankDataProcessor;

    // Background thread responsible for processing JankData batches.
    private HandlerThread mHandlerThread = new HandlerThread("AppJankTracker");
    private Handler mHandler = null;

    // Needed so we know when the view is attached to a window.
    private ViewTreeObserver mViewTreeObserver;

    // Handle to a registered OnJankData listener.
    private SurfaceControl.OnJankDataListenerRegistration mJankDataListenerRegistration;

    // The interface to the windowing system that enables us to register for JankData.
    private AttachedSurfaceControl mSurfaceControl;
    // Name of the activity that is currently tracking Jank metrics.
    private String mActivityName;
    // The apps uid.
    private int mAppUid;
    // View that gives us access to ViewTreeObserver.
    private View mDecorView;

    /**
     * Set by the activity to enable or disable jank tracking. Activities may disable tracking if
     * they are paused or not enable tracking if they are not visible or if the app category is not
     * set.
     */
    private boolean mTrackingEnabled = false;
    /**
     * Set to true once listeners are registered and JankData will start to be received. Both
     * mTrackingEnabled and mListenersRegistered need to be true for JankData to be processed.
     */
    private boolean mListenersRegistered = false;


    public JankTracker(Choreographer choreographer, View decorView) {
        mStateTracker = new StateTracker(choreographer);
        mJankDataProcessor = new JankDataProcessor(mStateTracker);
        mDecorView = decorView;
        mHandlerThread.start();
        registerWindowListeners();
    }

    /**
     * Merges app jank stats reported by components outside the platform to the current pending
     * stats
     */
    public void mergeAppJankStats(AppJankStats appJankStats) {
        getHandler().post(new Runnable() {
            @Override
            public void run() {
                mJankDataProcessor.mergeJankStats(appJankStats, mActivityName);
            }
        });
    }

    public void setActivityName(@NonNull String activityName) {
        mActivityName = activityName;
    }

    public void setAppUid(int uid) {
        mAppUid = uid;
    }

    /**
     * Will add the widget category, id and state as a UI state to associate frames to it.
     * @param widgetCategory preselected general widget category
     * @param widgetId developer defined widget id if available.
     * @param widgetState the current active widget state.
     */
    public void addUiState(String widgetCategory, String widgetId, String widgetState) {
        if (!shouldTrack()) return;

        mStateTracker.putState(widgetCategory, widgetId, widgetState);
    }

    /**
     * Will remove the widget category, id and state as a ui state and no longer attribute frames
     * to it.
     * @param widgetCategory preselected general widget category
     * @param widgetId developer defined widget id if available.
     * @param widgetState no longer active widget state.
     */
    public void removeUiState(String widgetCategory, String widgetId, String widgetState) {
        if (!shouldTrack()) return;

        mStateTracker.removeState(widgetCategory, widgetId, widgetState);
    }

    /**
     * Call to update a jank state to a different state.
     * @param widgetCategory preselected general widget category.
     * @param widgetId developer defined widget id if available.
     * @param currentState current state of the widget.
     * @param nextState the state the widget will be in.
     */
    public void updateUiState(String widgetCategory, String widgetId, String currentState,
            String nextState) {
        if (!shouldTrack()) return;

        mStateTracker.updateState(widgetCategory, widgetId, currentState, nextState);
    }

    /**
     * Will enable jank tracking, and add the activity as a state to associate frames to.
     */
    public void enableAppJankTracking() {
        // Add the activity as a state, this will ensure we track frames to the activity without the
        // need of a decorated widget to be used.
        // TODO b/376116199 replace "NONE" with UNSPECIFIED once the API changes are merged.
        mStateTracker.putState("NONE", mActivityName, "NONE");
        mTrackingEnabled = true;
    }

    /**
     * Will disable jank tracking, and remove the activity as a state to associate frames to.
     */
    public void disableAppJankTracking() {
        mTrackingEnabled = false;
        // TODO b/376116199 replace "NONE" with UNSPECIFIED once the API changes are merged.
        mStateTracker.removeState("NONE", mActivityName, "NONE");
    }

    /**
     * Retrieve all pending widget states, this is intended for testing purposes only.
     * @param stateDataList the ArrayList that will be populated with the pending states.
     */
    @VisibleForTesting
    public void getAllUiStates(@NonNull ArrayList<StateTracker.StateData> stateDataList) {
        mStateTracker.retrieveAllStates(stateDataList);
    }

    /**
     * Retrieve all pending jank stats before they are logged, this is intended for testing
     * purposes only.
     */
    @VisibleForTesting
    public HashMap<String, JankDataProcessor.PendingJankStat> getPendingJankStats() {
        return mJankDataProcessor.getPendingJankStats();
    }

    /**
     * Only intended to be used by tests, the runnable that registers the listeners may not run
     * in time for tests to pass. This forces them to run immediately.
     */
    @VisibleForTesting
    public void forceListenerRegistration() {
        mSurfaceControl = mDecorView.getRootSurfaceControl();
        registerForJankData();
        // TODO b/376116199 Check if registration is good.
        mListenersRegistered = true;
    }

    private void registerForJankData() {
        if (mSurfaceControl == null) return;
        /*
        TODO b/376115668 Register for JankData batches from new JankTracking API
         */
    }

    /**
     * Returns whether jank tracking is enabled or not.
     */
    @VisibleForTesting
    public boolean shouldTrack() {
        return mTrackingEnabled && mListenersRegistered;
    }

    /**
     * Need to know when the decor view gets attached to the window in order to get
     * AttachedSurfaceControl. In order to register a callback for OnJankDataListener
     * AttachedSurfaceControl needs to be created which only happens after onWindowAttached is
     * called. This is why there is a delay in posting the runnable.
     */
    private void registerWindowListeners() {
        if (mDecorView == null) return;
        mViewTreeObserver = mDecorView.getViewTreeObserver();
        mViewTreeObserver.addOnWindowAttachListener(new ViewTreeObserver.OnWindowAttachListener() {
            @Override
            public void onWindowAttached() {
                getHandler().postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        forceListenerRegistration();
                    }
                }, 1000);
            }

            @Override
            public void onWindowDetached() {
                // TODO b/376116199  do we un-register the callback or just not process the data.
            }
        });
    }

    private Handler getHandler() {
        if (mHandler == null) {
            mHandler = new Handler(mHandlerThread.getLooper());
        }
        return mHandler;
    }
}
