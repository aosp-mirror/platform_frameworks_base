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
package com.android.systemui.statusbar.notification.logging;

import android.content.Context;
import android.os.Handler;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.Trace;
import android.service.notification.NotificationListenerService;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.statusbar.IStatusBarService;
import com.android.internal.statusbar.NotificationVisibility;
import com.android.internal.statusbar.NotificationVisibility.NotificationLocation;
import com.android.systemui.CoreStartable;
import com.android.systemui.dagger.qualifiers.UiBackground;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.plugins.statusbar.StatusBarStateController.StateListener;
import com.android.systemui.scene.domain.interactor.WindowRootViewVisibilityInteractor;
import com.android.systemui.statusbar.NotificationListener;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.notification.collection.NotifLiveDataStore;
import com.android.systemui.statusbar.notification.collection.NotifPipeline;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifCollectionListener;
import com.android.systemui.statusbar.notification.collection.render.NotificationVisibilityProvider;
import com.android.systemui.statusbar.notification.dagger.NotificationsModule;
import com.android.systemui.statusbar.notification.shared.NotificationsLiveDataStoreRefactor;
import com.android.systemui.statusbar.notification.stack.ExpandableViewState;
import com.android.systemui.statusbar.notification.stack.NotificationListContainer;
import com.android.systemui.statusbar.notification.stack.ui.view.NotificationRowStatsLogger;
import com.android.systemui.util.Compile;
import com.android.systemui.util.kotlin.JavaAdapter;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;

import javax.inject.Inject;

/**
 * Handles notification logging, in particular, logging which notifications are visible and which
 * are not.
 */
public class NotificationLogger implements StateListener, CoreStartable,
        NotificationRowStatsLogger {
    static final String TAG = "NotificationLogger";
    private static final boolean DEBUG = Compile.IS_DEBUG && Log.isLoggable(TAG, Log.DEBUG);

    /** The minimum delay in ms between reports of notification visibility. */
    private static final int VISIBILITY_REPORT_MIN_DELAY_MS = 500;

    /** Keys of notifications currently visible to the user. */
    private final ArraySet<NotificationVisibility> mCurrentlyVisibleNotifications =
            new ArraySet<>();

    // Dependencies:
    private final NotificationListenerService mNotificationListener;
    private final Executor mUiBgExecutor;
    private final NotifLiveDataStore mNotifLiveDataStore;
    private final NotificationVisibilityProvider mVisibilityProvider;
    private final NotifPipeline mNotifPipeline;
    private final NotificationPanelLogger mNotificationPanelLogger;
    private final ExpansionStateLogger mExpansionStateLogger;
    private final WindowRootViewVisibilityInteractor mWindowRootViewVisibilityInteractor;
    private final JavaAdapter mJavaAdapter;

    protected Handler mHandler = new Handler();
    protected IStatusBarService mBarService;
    private long mLastVisibilityReportUptimeMs;
    private NotificationListContainer mListContainer;
    private final Object mDozingLock = new Object();
    @GuardedBy("mDozingLock")
    private Boolean mLockscreen = null;  // Use null to indicate state is not yet known
    private boolean mLogging = false;

    // Tracks notifications currently visible in mNotificationStackScroller and
    // emits visibility events via NoMan on changes.
    protected Runnable mVisibilityReporter = new Runnable() {
        private final ArraySet<NotificationVisibility> mTmpNewlyVisibleNotifications =
                new ArraySet<>();
        private final ArraySet<NotificationVisibility> mTmpCurrentlyVisibleNotifications =
                new ArraySet<>();
        private final ArraySet<NotificationVisibility> mTmpNoLongerVisibleNotifications =
                new ArraySet<>();

        @Override
        public void run() {
            mLastVisibilityReportUptimeMs = SystemClock.uptimeMillis();

            // 1. Loop over active entries:
            //   A. Keep list of visible notifications.
            //   B. Keep list of previously hidden, now visible notifications.
            // 2. Compute no-longer visible notifications by removing currently
            //    visible notifications from the set of previously visible
            //    notifications.
            // 3. Report newly visible and no-longer visible notifications.
            // 4. Keep currently visible notifications for next report.
            List<NotificationEntry> activeNotifications = getVisibleNotifications();
            int N = activeNotifications.size();
            for (int i = 0; i < N; i++) {
                NotificationEntry entry = activeNotifications.get(i);
                String key = entry.getSbn().getKey();
                boolean isVisible = mListContainer.isInVisibleLocation(entry);
                NotificationVisibility visObj = NotificationVisibility.obtain(key, i, N, isVisible,
                        getNotificationLocation(entry));
                boolean previouslyVisible = mCurrentlyVisibleNotifications.contains(visObj);
                if (isVisible) {
                    // Build new set of visible notifications.
                    mTmpCurrentlyVisibleNotifications.add(visObj);
                    if (!previouslyVisible) {
                        mTmpNewlyVisibleNotifications.add(visObj);
                    }
                } else {
                    // release object
                    visObj.recycle();
                }
            }
            mTmpNoLongerVisibleNotifications.addAll(mCurrentlyVisibleNotifications);
            mTmpNoLongerVisibleNotifications.removeAll(mTmpCurrentlyVisibleNotifications);

            logNotificationVisibilityChanges(
                    mTmpNewlyVisibleNotifications, mTmpNoLongerVisibleNotifications);

            recycleAllVisibilityObjects(mCurrentlyVisibleNotifications);
            mCurrentlyVisibleNotifications.addAll(mTmpCurrentlyVisibleNotifications);

            mExpansionStateLogger.onVisibilityChanged(
                    mTmpCurrentlyVisibleNotifications, mTmpCurrentlyVisibleNotifications);
            Trace.traceCounter(Trace.TRACE_TAG_APP, "Notifications [Active]", N);
            Trace.traceCounter(Trace.TRACE_TAG_APP, "Notifications [Visible]",
                    mCurrentlyVisibleNotifications.size());

            recycleAllVisibilityObjects(mTmpNoLongerVisibleNotifications);
            mTmpCurrentlyVisibleNotifications.clear();
            mTmpNewlyVisibleNotifications.clear();
            mTmpNoLongerVisibleNotifications.clear();
        }
    };

    private List<NotificationEntry> getVisibleNotifications() {
        return mNotifLiveDataStore.getActiveNotifList().getValue();
    }

    /**
     * Returns the location of the notification referenced by the given {@link NotificationEntry}.
     */
    public static NotificationLocation getNotificationLocation(
            NotificationEntry entry) {
        if (entry == null || entry.getRow() == null || entry.getRow().getViewState() == null) {
            return NotificationLocation.LOCATION_UNKNOWN;
        }
        return convertNotificationLocation(entry.getRow().getViewState().location);
    }

    private static NotificationLocation convertNotificationLocation(
            int location) {
        switch (location) {
            case ExpandableViewState.LOCATION_FIRST_HUN:
                return NotificationLocation.LOCATION_FIRST_HEADS_UP;
            case ExpandableViewState.LOCATION_HIDDEN_TOP:
                return NotificationLocation.LOCATION_HIDDEN_TOP;
            case ExpandableViewState.LOCATION_MAIN_AREA:
                return NotificationLocation.LOCATION_MAIN_AREA;
            case ExpandableViewState.LOCATION_BOTTOM_STACK_PEEKING:
                return NotificationLocation.LOCATION_BOTTOM_STACK_PEEKING;
            case ExpandableViewState.LOCATION_BOTTOM_STACK_HIDDEN:
                return NotificationLocation.LOCATION_BOTTOM_STACK_HIDDEN;
            case ExpandableViewState.LOCATION_GONE:
                return NotificationLocation.LOCATION_GONE;
            default:
                return NotificationLocation.LOCATION_UNKNOWN;
        }
    }

    /**
     * Injected constructor. See {@link NotificationsModule}.
     */
    public NotificationLogger(NotificationListener notificationListener,
            @UiBackground Executor uiBgExecutor,
            NotifLiveDataStore notifLiveDataStore,
            NotificationVisibilityProvider visibilityProvider,
            NotifPipeline notifPipeline,
            StatusBarStateController statusBarStateController,
            WindowRootViewVisibilityInteractor windowRootViewVisibilityInteractor,
            JavaAdapter javaAdapter,
            ExpansionStateLogger expansionStateLogger,
            NotificationPanelLogger notificationPanelLogger) {
        // Not expected to be constructed if the feature flag is on
        NotificationsLiveDataStoreRefactor.assertInLegacyMode();

        mNotificationListener = notificationListener;
        mUiBgExecutor = uiBgExecutor;
        mNotifLiveDataStore = notifLiveDataStore;
        mVisibilityProvider = visibilityProvider;
        mNotifPipeline = notifPipeline;
        mBarService = IStatusBarService.Stub.asInterface(
                ServiceManager.getService(Context.STATUS_BAR_SERVICE));
        mExpansionStateLogger = expansionStateLogger;
        mNotificationPanelLogger = notificationPanelLogger;
        mWindowRootViewVisibilityInteractor = windowRootViewVisibilityInteractor;
        mJavaAdapter = javaAdapter;
        // Not expected to be destroyed, don't need to unsubscribe
        statusBarStateController.addCallback(this);

        registerNewPipelineListener();
    }

    private void registerNewPipelineListener() {
        mNotifPipeline.addCollectionListener(new NotifCollectionListener() {
            @Override
            public void onEntryUpdated(@NonNull NotificationEntry entry, boolean fromSystem) {
                mExpansionStateLogger.onEntryUpdated(entry.getKey());
            }

            @Override
            public void onEntryRemoved(@NonNull NotificationEntry entry, int reason) {
                mExpansionStateLogger.onEntryRemoved(entry.getKey());
            }
        });
    }

    public void setUpWithContainer(NotificationListContainer listContainer) {
        mListContainer = listContainer;
        if (mLogging) {
            mListContainer.setChildLocationsChangedListener(this::onChildLocationsChanged);
        }
    }

    @Override
    public void start() {
        mJavaAdapter.alwaysCollectFlow(
                mWindowRootViewVisibilityInteractor.isLockscreenOrShadeVisibleAndInteractive(),
                this::onLockscreenOrShadeVisibleAndInteractiveChanged);
    }

    private void onLockscreenOrShadeVisibleAndInteractiveChanged(boolean visible) {
        if (visible) {
            startNotificationLogging();
        } else {
            // Ensure we stop notification logging when the device isn't interactive.
            stopNotificationLogging();
        }
    }

    public void stopNotificationLogging() {
        if (mLogging) {
            mLogging = false;
            if (DEBUG) {
                Log.i(TAG, "stopNotificationLogging: log notifications invisible");
            }
            // Report all notifications as invisible and turn down the
            // reporter.
            if (!mCurrentlyVisibleNotifications.isEmpty()) {
                logNotificationVisibilityChanges(
                        Collections.emptyList(), mCurrentlyVisibleNotifications);
                recycleAllVisibilityObjects(mCurrentlyVisibleNotifications);
            }
            mHandler.removeCallbacks(mVisibilityReporter);
            mListContainer.setChildLocationsChangedListener(null);
        }
    }

    public void startNotificationLogging() {
        if (!mLogging) {
            mLogging = true;
            if (DEBUG) {
                Log.i(TAG, "startNotificationLogging");
            }
            boolean lockscreen;
            synchronized (mDozingLock) {
                lockscreen = mLockscreen != null && mLockscreen;
            }
            mNotificationPanelLogger.logPanelShown(lockscreen, getVisibleNotifications());
            if (mListContainer != null) {
                mListContainer.setChildLocationsChangedListener(this::onChildLocationsChanged);
            }
            // Sometimes, the transition from lockscreenOrShadeVisible=false ->
            // lockscreenOrShadeVisible=true doesn't cause the scroller to emit child location
            // events. Hence generate one ourselves to guarantee that we're reporting visible
            // notifications.
            // (Note that in cases where the scroller does emit events, this
            // additional event doesn't break anything.)
            onChildLocationsChanged();
        }
    }

    private void logNotificationVisibilityChanges(
            Collection<NotificationVisibility> newlyVisible,
            Collection<NotificationVisibility> noLongerVisible) {
        if (newlyVisible.isEmpty() && noLongerVisible.isEmpty()) {
            return;
        }
        final NotificationVisibility[] newlyVisibleAr = cloneVisibilitiesAsArr(newlyVisible);
        final NotificationVisibility[] noLongerVisibleAr = cloneVisibilitiesAsArr(noLongerVisible);

        mUiBgExecutor.execute(() -> {
            try {
                mBarService.onNotificationVisibilityChanged(newlyVisibleAr, noLongerVisibleAr);
            } catch (RemoteException e) {
                // Ignore.
            }

            final int N = newlyVisibleAr.length;
            if (N > 0) {
                String[] newlyVisibleKeyAr = new String[N];
                for (int i = 0; i < N; i++) {
                    newlyVisibleKeyAr[i] = newlyVisibleAr[i].key;
                }
                // TODO: Call NotificationEntryManager to do this, once it exists.
                // TODO: Consider not catching all runtime exceptions here.
                try {
                    mNotificationListener.setNotificationsShown(newlyVisibleKeyAr);
                } catch (RuntimeException e) {
                    Log.d(TAG, "failed setNotificationsShown: ", e);
                }
            }
            recycleAllVisibilityObjects(newlyVisibleAr);
            recycleAllVisibilityObjects(noLongerVisibleAr);
        });
    }

    private void recycleAllVisibilityObjects(ArraySet<NotificationVisibility> array) {
        final int N = array.size();
        for (int i = 0 ; i < N; i++) {
            array.valueAt(i).recycle();
        }
        array.clear();
    }

    private void recycleAllVisibilityObjects(NotificationVisibility[] array) {
        final int N = array.length;
        for (int i = 0 ; i < N; i++) {
            if (array[i] != null) {
                array[i].recycle();
            }
        }
    }

    private static NotificationVisibility[] cloneVisibilitiesAsArr(
            Collection<NotificationVisibility> c) {
        final NotificationVisibility[] array = new NotificationVisibility[c.size()];
        int i = 0;
        for(NotificationVisibility nv: c) {
            if (nv != null) {
                array[i] = nv.clone();
            }
            i++;
        }
        return array;
    }

    @VisibleForTesting
    public Runnable getVisibilityReporter() {
        return mVisibilityReporter;
    }

    @Override
    public void onStateChanged(int newState) {
        if (DEBUG) {
            Log.i(TAG, "onStateChanged: new=" + newState);
        }
        synchronized (mDozingLock) {
            mLockscreen = (newState == StatusBarState.KEYGUARD
                    || newState == StatusBarState.SHADE_LOCKED);
        }
    }

    /**
     * Called when the notification is expanded / collapsed.
     */
    @Override
    public void onNotificationExpansionChanged(@NonNull String key, boolean isExpanded,
            int location, boolean isUserAction) {
        NotificationLocation notifLocation = mVisibilityProvider.getLocation(key);
        mExpansionStateLogger.onExpansionChanged(key, isUserAction, isExpanded, notifLocation);
    }

    @VisibleForTesting
    void onChildLocationsChanged() {
        if (mHandler.hasCallbacks(mVisibilityReporter)) {
            // Visibilities will be reported when the existing
            // callback is executed.
            return;
        }
        // Calculate when we're allowed to run the visibility
        // reporter. Note that this timestamp might already have
        // passed. That's OK, the callback will just be executed
        // ASAP.
        long nextReportUptimeMs =
                mLastVisibilityReportUptimeMs + VISIBILITY_REPORT_MIN_DELAY_MS;
        mHandler.postAtTime(mVisibilityReporter, nextReportUptimeMs);
    }

    @VisibleForTesting
    public void setVisibilityReporter(Runnable visibilityReporter) {
        mVisibilityReporter = visibilityReporter;
    }

    /**
     * A listener that is notified when some child locations might have changed.
     */
    public interface OnChildLocationsChangedListener {
        void onChildLocationsChanged();
    }

    /**
     * Logs the expansion state change when the notification is visible.
     */
    public static class ExpansionStateLogger {
        /** Notification key -> state, should be accessed in UI offload thread only. */
        private final Map<String, State> mExpansionStates = new ArrayMap<>();

        /**
         * Notification key -> last logged expansion state, should be accessed in UI thread only.
         */
        private final Map<String, Boolean> mLoggedExpansionState = new ArrayMap<>();
        private final Executor mUiBgExecutor;
        @VisibleForTesting
        IStatusBarService mBarService;

        @Inject
        public ExpansionStateLogger(@UiBackground Executor uiBgExecutor) {
            mUiBgExecutor = uiBgExecutor;
            mBarService =
                    IStatusBarService.Stub.asInterface(
                            ServiceManager.getService(Context.STATUS_BAR_SERVICE));
        }

        @VisibleForTesting
        void onExpansionChanged(String key, boolean isUserAction, boolean isExpanded,
                NotificationLocation location) {
            State state = getState(key);
            state.mIsUserAction = isUserAction;
            state.mIsExpanded = isExpanded;
            state.mLocation = location;
            maybeNotifyOnNotificationExpansionChanged(key, state);
        }

        @VisibleForTesting
        void onVisibilityChanged(
                Collection<NotificationVisibility> newlyVisible,
                Collection<NotificationVisibility> noLongerVisible) {
            final NotificationVisibility[] newlyVisibleAr =
                    cloneVisibilitiesAsArr(newlyVisible);
            final NotificationVisibility[] noLongerVisibleAr =
                    cloneVisibilitiesAsArr(noLongerVisible);

            for (NotificationVisibility nv : newlyVisibleAr) {
                State state = getState(nv.key);
                state.mIsVisible = true;
                state.mLocation = nv.location;
                maybeNotifyOnNotificationExpansionChanged(nv.key, state);
            }
            for (NotificationVisibility nv : noLongerVisibleAr) {
                State state = getState(nv.key);
                state.mIsVisible = false;
            }
        }

        @VisibleForTesting
        void onEntryRemoved(String key) {
            mExpansionStates.remove(key);
            mLoggedExpansionState.remove(key);
        }

        @VisibleForTesting
        void onEntryUpdated(String key) {
            // When the notification is updated, we should consider the notification as not
            // yet logged.
            mLoggedExpansionState.remove(key);
        }

        private State getState(String key) {
            State state = mExpansionStates.get(key);
            if (state == null) {
                state = new State();
                mExpansionStates.put(key, state);
            }
            return state;
        }

        private void maybeNotifyOnNotificationExpansionChanged(final String key, State state) {
            if (!state.isFullySet()) {
                return;
            }
            if (!state.mIsVisible) {
                return;
            }
            Boolean loggedExpansionState = mLoggedExpansionState.get(key);
            // Consider notification is initially collapsed, so only expanded is logged in the
            // first time.
            if (loggedExpansionState == null && !state.mIsExpanded) {
                return;
            }
            if (loggedExpansionState != null
                    && Objects.equals(state.mIsExpanded, loggedExpansionState)) {
                return;
            }
            mLoggedExpansionState.put(key, state.mIsExpanded);
            final State stateToBeLogged = new State(state);
            mUiBgExecutor.execute(() -> {
                try {
                    mBarService.onNotificationExpansionChanged(key, stateToBeLogged.mIsUserAction,
                            stateToBeLogged.mIsExpanded, stateToBeLogged.mLocation.ordinal());
                } catch (RemoteException e) {
                    Log.e(TAG, "Failed to call onNotificationExpansionChanged: ", e);
                }
            });
        }

        private static class State {
            @Nullable
            Boolean mIsUserAction;
            @Nullable
            Boolean mIsExpanded;
            @Nullable
            Boolean mIsVisible;
            @Nullable
            NotificationLocation mLocation;

            private State() {}

            private State(State state) {
                this.mIsUserAction = state.mIsUserAction;
                this.mIsExpanded = state.mIsExpanded;
                this.mIsVisible = state.mIsVisible;
                this.mLocation = state.mLocation;
            }

            private boolean isFullySet() {
                return mIsUserAction != null && mIsExpanded != null && mIsVisible != null
                        && mLocation != null;
            }
        }
    }
}
