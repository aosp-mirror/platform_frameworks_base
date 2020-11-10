/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.internal.jank;

import static com.android.internal.util.FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__LAUNCHER_APP_CLOSE_TO_HOME;
import static com.android.internal.util.FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__LAUNCHER_APP_CLOSE_TO_PIP;
import static com.android.internal.util.FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__LAUNCHER_APP_LAUNCH_FROM_ICON;
import static com.android.internal.util.FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__LAUNCHER_APP_LAUNCH_FROM_RECENTS;
import static com.android.internal.util.FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__LAUNCHER_QUICK_SWITCH;
import static com.android.internal.util.FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__NOTIFICATION_SHADE_SWIPE;
import static com.android.internal.util.FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__SHADE_APP_LAUNCH;
import static com.android.internal.util.FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__SHADE_EXPAND_COLLAPSE_LOCK;
import static com.android.internal.util.FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__SHADE_HEADS_UP_APPEAR;
import static com.android.internal.util.FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__SHADE_HEADS_UP_DISAPPEAR;
import static com.android.internal.util.FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__SHADE_NOTIFICATION_ADD;
import static com.android.internal.util.FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__SHADE_NOTIFICATION_REMOVE;
import static com.android.internal.util.FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__SHADE_QS_EXPAND_COLLAPSE;
import static com.android.internal.util.FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__SHADE_QS_SCROLL_SWIPE;
import static com.android.internal.util.FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__SHADE_ROW_EXPAND;
import static com.android.internal.util.FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__SHADE_ROW_SWIPE;
import static com.android.internal.util.FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__SHADE_SCROLL_FLING;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.os.HandlerThread;
import android.util.Log;
import android.util.SparseArray;
import android.view.View;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.jank.FrameTracker.FrameMetricsWrapper;
import com.android.internal.jank.FrameTracker.ThreadedRendererWrapper;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.concurrent.TimeUnit;

/**
 * This class let users to begin and end the always on tracing mechanism.
 * @hide
 */
public class InteractionJankMonitor {
    private static final String TAG = InteractionJankMonitor.class.getSimpleName();
    private static final boolean DEBUG = false;
    private static final String DEFAULT_WORKER_NAME = TAG + "-Worker";
    private static final long DEFAULT_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(5L);

    // Every value must have a corresponding entry in CUJ_STATSD_INTERACTION_TYPE.
    public static final int CUJ_NOTIFICATION_SHADE_EXPAND_COLLAPSE = 0;
    public static final int CUJ_NOTIFICATION_SHADE_EXPAND_COLLAPSE_LOCK = 1;
    public static final int CUJ_NOTIFICATION_SHADE_SCROLL_FLING = 2;
    public static final int CUJ_NOTIFICATION_SHADE_ROW_EXPAND = 3;
    public static final int CUJ_NOTIFICATION_SHADE_ROW_SWIPE = 4;
    public static final int CUJ_NOTIFICATION_SHADE_QS_EXPAND_COLLAPSE = 5;
    public static final int CUJ_NOTIFICATION_SHADE_QS_SCROLL_SWIPE = 6;
    public static final int CUJ_LAUNCHER_APP_LAUNCH_FROM_RECENTS = 7;
    public static final int CUJ_LAUNCHER_APP_LAUNCH_FROM_ICON = 8;
    public static final int CUJ_LAUNCHER_APP_CLOSE_TO_HOME = 9;
    public static final int CUJ_LAUNCHER_APP_CLOSE_TO_PIP = 10;
    public static final int CUJ_LAUNCHER_QUICK_SWITCH = 11;
    public static final int CUJ_NOTIFICATION_HEADS_UP_APPEAR = 12;
    public static final int CUJ_NOTIFICATION_HEADS_UP_DISAPPEAR = 13;
    public static final int CUJ_NOTIFICATION_ADD = 14;
    public static final int CUJ_NOTIFICATION_REMOVE = 15;
    public static final int CUJ_NOTIFICATION_APP_START = 16;

    private static final int NO_STATSD_LOGGING = -1;

    // Used to convert CujType to InteractionType enum value for statsd logging.
    // Use NO_STATSD_LOGGING in case the measurement for a given CUJ should not be logged to statsd.
    @VisibleForTesting
    public static final int[] CUJ_TO_STATSD_INTERACTION_TYPE = {
            // This should be mapping to CUJ_NOTIFICATION_SHADE_EXPAND_COLLAPSE.
            UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__NOTIFICATION_SHADE_SWIPE,
            UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__SHADE_EXPAND_COLLAPSE_LOCK,
            UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__SHADE_SCROLL_FLING,
            UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__SHADE_ROW_EXPAND,
            UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__SHADE_ROW_SWIPE,
            UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__SHADE_QS_EXPAND_COLLAPSE,
            UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__SHADE_QS_SCROLL_SWIPE,
            UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__LAUNCHER_APP_LAUNCH_FROM_RECENTS,
            UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__LAUNCHER_APP_LAUNCH_FROM_ICON,
            UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__LAUNCHER_APP_CLOSE_TO_HOME,
            UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__LAUNCHER_APP_CLOSE_TO_PIP,
            UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__LAUNCHER_QUICK_SWITCH,
            UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__SHADE_HEADS_UP_APPEAR,
            UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__SHADE_HEADS_UP_DISAPPEAR,
            UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__SHADE_NOTIFICATION_ADD,
            UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__SHADE_NOTIFICATION_REMOVE,
            UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__SHADE_APP_LAUNCH,
    };

    private static volatile InteractionJankMonitor sInstance;

    private ThreadedRendererWrapper mRenderer;
    private FrameMetricsWrapper mMetrics;
    private SparseArray<FrameTracker> mRunningTrackers;
    private SparseArray<Runnable> mTimeoutActions;
    private HandlerThread mWorker;
    private boolean mInitialized;

    /** @hide */
    @IntDef({
            CUJ_NOTIFICATION_SHADE_EXPAND_COLLAPSE,
            CUJ_NOTIFICATION_SHADE_EXPAND_COLLAPSE_LOCK,
            CUJ_NOTIFICATION_SHADE_SCROLL_FLING,
            CUJ_NOTIFICATION_SHADE_ROW_EXPAND,
            CUJ_NOTIFICATION_SHADE_ROW_SWIPE,
            CUJ_NOTIFICATION_SHADE_QS_EXPAND_COLLAPSE,
            CUJ_NOTIFICATION_SHADE_QS_SCROLL_SWIPE,
            CUJ_LAUNCHER_APP_LAUNCH_FROM_RECENTS,
            CUJ_LAUNCHER_APP_LAUNCH_FROM_ICON,
            CUJ_LAUNCHER_APP_CLOSE_TO_HOME,
            CUJ_LAUNCHER_APP_CLOSE_TO_PIP,
            CUJ_LAUNCHER_QUICK_SWITCH,
            CUJ_NOTIFICATION_HEADS_UP_APPEAR,
            CUJ_NOTIFICATION_HEADS_UP_DISAPPEAR,
            CUJ_NOTIFICATION_ADD,
            CUJ_NOTIFICATION_REMOVE,
            CUJ_NOTIFICATION_APP_START,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface CujType {}

    /**
     * Get the singleton of InteractionJankMonitor.
     * @return instance of InteractionJankMonitor
     */
    public static InteractionJankMonitor getInstance() {
        // Use DCL here since this method might be invoked very often.
        if (sInstance == null) {
            synchronized (InteractionJankMonitor.class) {
                if (sInstance == null) {
                    sInstance = new InteractionJankMonitor(new HandlerThread(DEFAULT_WORKER_NAME));
                }
            }
        }
        return sInstance;
    }

    /**
     * This constructor should be only public to tests.
     * @param worker the worker thread for the callbacks
     */
    @VisibleForTesting
    public InteractionJankMonitor(@NonNull HandlerThread worker) {
        mRunningTrackers = new SparseArray<>();
        mTimeoutActions = new SparseArray<>();
        mWorker = worker;
    }

    /**
     * Init InteractionJankMonitor for later instrumentation.
     * @param view Any view in the view tree to get context and ThreadedRenderer.
     * @return boolean true if the instance has been initialized successfully.
     */
    public boolean init(@NonNull View view) {
        //TODO (163505250): This should be no-op if not in droid food rom.
        if (!mInitialized) {
            synchronized (this) {
                if (!mInitialized) {
                    if (!view.isAttachedToWindow()) {
                        Log.d(TAG, "Expect an attached view!", new Throwable());
                        return false;
                    }
                    mRenderer = new ThreadedRendererWrapper(view.getThreadedRenderer());
                    mMetrics = new FrameMetricsWrapper();
                    mWorker.start();
                    mInitialized = true;
                }
            }
        }
        return true;
    }

    /**
     * Create a {@link FrameTracker} instance.
     * @param session the session associates with this tracker
     * @return instance of the FrameTracker
     */
    @VisibleForTesting
    public FrameTracker createFrameTracker(Session session) {
        synchronized (this) {
            if (!mInitialized) return null;
            return new FrameTracker(session, mWorker.getThreadHandler(), mRenderer, mMetrics);
        }
    }

    /**
     * Begin a trace session, must invoke {@link #init(View)} before invoking this method.
     * @param cujType the specific {@link InteractionJankMonitor.CujType}.
     * @return boolean true if the tracker is started successfully, false otherwise.
     */
    public boolean begin(@CujType int cujType) {
        //TODO (163505250): This should be no-op if not in droid food rom.
        synchronized (this) {
            return begin(cujType, 0L /* timeout */);
        }
    }

    /**
     * Begin a trace session, must invoke {@link #init(View)} before invoking this method.
     * @param cujType the specific {@link InteractionJankMonitor.CujType}.
     * @param timeout the elapsed time in ms until firing the timeout action.
     * @return boolean true if the tracker is started successfully, false otherwise.
     */
    public boolean begin(@CujType int cujType, long timeout) {
        //TODO (163505250): This should be no-op if not in droid food rom.
        synchronized (this) {
            if (!mInitialized) {
                Log.d(TAG, "Not initialized!", new Throwable());
                return false;
            }
            Session session = new Session(cujType);
            FrameTracker tracker = getTracker(session);
            // Skip subsequent calls if we already have an ongoing tracing.
            if (tracker != null) return false;

            // begin a new trace session.
            tracker = createFrameTracker(session);
            mRunningTrackers.put(cujType, tracker);
            tracker.begin();

            // Cancel the trace if we don't get an end() call in specified duration.
            timeout = timeout > 0L ? timeout : DEFAULT_TIMEOUT_MS;
            Runnable timeoutAction = () -> cancel(cujType);
            mTimeoutActions.put(cujType, timeoutAction);
            mWorker.getThreadHandler().postDelayed(timeoutAction, timeout);
            return true;
        }
    }

    /**
     * End a trace session, must invoke {@link #init(View)} before invoking this method.
     * @param cujType the specific {@link InteractionJankMonitor.CujType}.
     * @return boolean true if the tracker is ended successfully, false otherwise.
     */
    public boolean end(@CujType int cujType) {
        //TODO (163505250): This should be no-op if not in droid food rom.
        synchronized (this) {
            if (!mInitialized) {
                Log.d(TAG, "Not initialized!", new Throwable());
                return false;
            }
            // remove the timeout action first.
            Runnable timeout = mTimeoutActions.get(cujType);
            if (timeout != null) {
                mWorker.getThreadHandler().removeCallbacks(timeout);
                mTimeoutActions.remove(cujType);
            }

            Session session = new Session(cujType);
            FrameTracker tracker = getTracker(session);
            // Skip this call since we haven't started a trace yet.
            if (tracker == null) return false;
            tracker.end();
            mRunningTrackers.remove(session.getCuj());
            return true;
        }
    }

    /**
     * Cancel the trace session, must invoke {@link #init(View)} before invoking this method.
     * @return boolean true if the tracker is cancelled successfully, false otherwise.
     */
    public boolean cancel(@CujType int cujType) {
        //TODO (163505250): This should be no-op if not in droid food rom.
        synchronized (this) {
            if (!mInitialized) {
                Log.d(TAG, "Not initialized!", new Throwable());
                return false;
            }
            // remove the timeout action first.
            Runnable timeout = mTimeoutActions.get(cujType);
            if (timeout != null) {
                mWorker.getThreadHandler().removeCallbacks(timeout);
                mTimeoutActions.remove(cujType);
            }

            Session session = new Session(cujType);
            FrameTracker tracker = getTracker(session);
            // Skip this call since we haven't started a trace yet.
            if (tracker == null) return false;
            tracker.cancel();
            mRunningTrackers.remove(session.getCuj());
            return true;
        }
    }

    private void destroy() {
        synchronized (this) {
            int trackers = mRunningTrackers.size();
            for (int i = 0; i < trackers; i++) {
                mRunningTrackers.valueAt(i).cancel();
            }
            mRunningTrackers = null;
            mTimeoutActions.clear();
            mTimeoutActions = null;
            mWorker.quit();
            mWorker = null;
        }
    }

    /**
     * Abandon current instance.
     */
    @VisibleForTesting
    public static void abandon() {
        if (sInstance == null) return;
        synchronized (InteractionJankMonitor.class) {
            if (sInstance == null) return;
            sInstance.destroy();
            sInstance = null;
        }
    }

    private FrameTracker getTracker(Session session) {
        synchronized (this) {
            if (!mInitialized) return null;
            return mRunningTrackers.get(session.getCuj());
        }
    }

    /**
     * Trigger the perfetto daemon to collect and upload data.
     */
    @VisibleForTesting
    public void trigger(Session session) {
        synchronized (this) {
            if (!mInitialized) return;
            mWorker.getThreadHandler().post(
                    () -> PerfettoTrigger.trigger(session.getPerfettoTrigger()));
        }
    }

    /**
     * A helper method to translate interaction type to CUJ name.
     *
     * @param interactionType the interaction type defined in AtomsProto.java
     * @return the name of the interaction type
     */
    public static String getNameOfInteraction(int interactionType) {
        // There is an offset amount of 1 between cujType and interactionType.
        return getNameOfCuj(interactionType - 1);
    }

    private static String getNameOfCuj(int cujType) {
        switch (cujType) {
            case CUJ_NOTIFICATION_SHADE_EXPAND_COLLAPSE:
                return "SHADE_EXPAND_COLLAPSE";
            case CUJ_NOTIFICATION_SHADE_EXPAND_COLLAPSE_LOCK:
                return "SHADE_EXPAND_COLLAPSE_LOCK";
            case CUJ_NOTIFICATION_SHADE_SCROLL_FLING:
                return "SHADE_SCROLL_FLING";
            case CUJ_NOTIFICATION_SHADE_ROW_EXPAND:
                return "SHADE_ROW_EXPAND";
            case CUJ_NOTIFICATION_SHADE_ROW_SWIPE:
                return "SHADE_ROW_SWIPE";
            case CUJ_NOTIFICATION_SHADE_QS_EXPAND_COLLAPSE:
                return "SHADE_QS_EXPAND_COLLAPSE";
            case CUJ_NOTIFICATION_SHADE_QS_SCROLL_SWIPE:
                return "SHADE_QS_SCROLL_SWIPE";
            case CUJ_LAUNCHER_APP_LAUNCH_FROM_RECENTS:
                return "LAUNCHER_APP_LAUNCH_FROM_RECENTS";
            case CUJ_LAUNCHER_APP_LAUNCH_FROM_ICON:
                return "LAUNCHER_APP_LAUNCH_FROM_ICON";
            case CUJ_LAUNCHER_APP_CLOSE_TO_HOME:
                return "LAUNCHER_APP_CLOSE_TO_HOME";
            case CUJ_LAUNCHER_APP_CLOSE_TO_PIP:
                return "LAUNCHER_APP_CLOSE_TO_PIP";
            case CUJ_LAUNCHER_QUICK_SWITCH:
                return "LAUNCHER_QUICK_SWITCH";
            case CUJ_NOTIFICATION_HEADS_UP_APPEAR:
                return "NOTIFICATION_HEADS_UP_APPEAR";
            case CUJ_NOTIFICATION_HEADS_UP_DISAPPEAR:
                return "NOTIFICATION_HEADS_UP_DISAPPEAR";
            case CUJ_NOTIFICATION_ADD:
                return "NOTIFICATION_ADD";
            case CUJ_NOTIFICATION_REMOVE:
                return "NOTIFICATION_REMOVE";
            case CUJ_NOTIFICATION_APP_START:
                return "NOTIFICATION_APP_START";
        }
        return "UNKNOWN";
    }

    /**
     * A class to represent a session.
     */
    public static class Session {
        private @CujType int mCujType;

        public Session(@CujType int cujType) {
            mCujType = cujType;
        }

        public int getCuj() {
            return mCujType;
        }

        public int getStatsdInteractionType() {
            return CUJ_TO_STATSD_INTERACTION_TYPE[mCujType];
        }

        /** Describes whether the measurement from this session should be written to statsd. */
        public boolean logToStatsd() {
            return getStatsdInteractionType() != NO_STATSD_LOGGING;
        }

        public String getPerfettoTrigger() {
            return String.format("interaction-jank-monitor-%d", mCujType);
        }

        public String getName() {
            return "Cuj<" + getCuj() + ">";
        }
    }
}
