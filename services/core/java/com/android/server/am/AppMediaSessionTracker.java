/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.am;

import static com.android.server.am.ActivityManagerDebugConfig.TAG_AM;
import static com.android.server.am.ActivityManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.am.AppRestrictionController.DEVICE_CONFIG_SUBNAMESPACE_PREFIX;
import static com.android.server.am.BaseAppStateTracker.ONE_DAY;
import static com.android.server.am.BaseAppStateTracker.STATE_TYPE_MEDIA_SESSION;

import android.annotation.NonNull;
import android.content.Context;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager.OnActiveSessionsChangedListener;
import android.os.HandlerExecutor;
import android.os.PowerExemptionManager.ReasonCode;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.app.ProcessMap;
import com.android.server.am.AppMediaSessionTracker.AppMediaSessionPolicy;
import com.android.server.am.AppRestrictionController.TrackerType;
import com.android.server.am.BaseAppStateDurationsTracker.SimplePackageDurations;
import com.android.server.am.BaseAppStateEventsTracker.BaseAppStateEventsPolicy;

import java.io.PrintWriter;
import java.lang.reflect.Constructor;
import java.util.List;

/**
 * The tracker for monitoring the active media sessions of apps.
 */
final class AppMediaSessionTracker
        extends BaseAppStateDurationsTracker<AppMediaSessionPolicy, SimplePackageDurations> {
    static final String TAG = TAG_WITH_CLASS_NAME ? "AppMediaSessionTracker" : TAG_AM;

    static final boolean DEBUG_MEDIA_SESSION_TRACKER = false;

    private final HandlerExecutor mHandlerExecutor;
    private final OnActiveSessionsChangedListener mSessionsChangedListener =
            this::handleMediaSessionChanged;

    // Unlocked since it's only accessed in single thread.
    private final ProcessMap<Boolean> mTmpMediaControllers = new ProcessMap<>();

    AppMediaSessionTracker(Context context, AppRestrictionController controller) {
        this(context, controller, null, null);
    }

    AppMediaSessionTracker(Context context, AppRestrictionController controller,
            Constructor<? extends Injector<AppMediaSessionPolicy>> injector, Object outerContext) {
        super(context, controller, injector, outerContext);
        mHandlerExecutor = new HandlerExecutor(mBgHandler);
        mInjector.setPolicy(new AppMediaSessionPolicy(mInjector, this));
    }

    @Override
    public SimplePackageDurations createAppStateEvents(int uid, String packageName) {
        return new SimplePackageDurations(uid, packageName, mInjector.getPolicy());
    }

    @Override
    public SimplePackageDurations createAppStateEvents(SimplePackageDurations other) {
        return new SimplePackageDurations(other);
    }

    private void onBgMediaSessionMonitorEnabled(boolean enabled) {
        if (enabled) {
            mInjector.getMediaSessionManager().addOnActiveSessionsChangedListener(
                    null, UserHandle.ALL, mHandlerExecutor, mSessionsChangedListener);
        } else {
            mInjector.getMediaSessionManager().removeOnActiveSessionsChangedListener(
                    mSessionsChangedListener);
        }
    }

    private void handleMediaSessionChanged(List<MediaController> controllers) {
        if (controllers != null) {
            synchronized (mLock) {
                final long now = SystemClock.elapsedRealtime();
                for (MediaController controller : controllers) {
                    final String packageName = controller.getPackageName();
                    final int uid = controller.getSessionToken().getUid();
                    SimplePackageDurations pkg = mPkgEvents.get(uid, packageName);
                    if (pkg == null) {
                        pkg = createAppStateEvents(uid, packageName);
                        mPkgEvents.put(uid, packageName, pkg);
                    }
                    if (!pkg.isActive()) {
                        pkg.addEvent(true, now);
                        notifyListenersOnStateChange(pkg.mUid, pkg.mPackageName, true, now,
                                STATE_TYPE_MEDIA_SESSION);
                    }
                    // Mark it as active, so we could filter out inactive ones below.
                    mTmpMediaControllers.put(packageName, uid, Boolean.TRUE);

                    if (DEBUG_MEDIA_SESSION_TRACKER) {
                        Slog.i(TAG, "Active media session from " + packageName + "/"
                                + UserHandle.formatUid(uid));
                    }
                }

                // Iterate the duration list and stop those inactive ones.
                final SparseArray<ArrayMap<String, SimplePackageDurations>> map =
                        mPkgEvents.getMap();
                for (int i = map.size() - 1; i >= 0; i--) {
                    final ArrayMap<String, SimplePackageDurations> val = map.valueAt(i);
                    for (int j = val.size() - 1; j >= 0; j--) {
                        final SimplePackageDurations pkg = val.valueAt(j);
                        if (pkg.isActive()
                                && mTmpMediaControllers.get(pkg.mPackageName, pkg.mUid) == null) {
                            // This package has removed its controller, issue a stop event.
                            pkg.addEvent(false, now);
                            notifyListenersOnStateChange(pkg.mUid, pkg.mPackageName, false, now,
                                    STATE_TYPE_MEDIA_SESSION);
                        }
                    }
                }
            }
            mTmpMediaControllers.clear();
        } else {
            synchronized (mLock) {
                // Issue stop event to all active trackers.
                final SparseArray<ArrayMap<String, SimplePackageDurations>> map =
                        mPkgEvents.getMap();
                final long now = SystemClock.elapsedRealtime();
                for (int i = map.size() - 1; i >= 0; i--) {
                    final ArrayMap<String, SimplePackageDurations> val = map.valueAt(i);
                    for (int j = val.size() - 1; j >= 0; j--) {
                        final SimplePackageDurations pkg = val.valueAt(j);
                        if (pkg.isActive()) {
                            pkg.addEvent(false, now);
                            notifyListenersOnStateChange(pkg.mUid, pkg.mPackageName, false, now,
                                    STATE_TYPE_MEDIA_SESSION);
                        }
                    }
                }
            }
        }
    }

    private void trimDurations() {
        final long now = SystemClock.elapsedRealtime();
        trim(Math.max(0, now - mInjector.getPolicy().getMaxTrackingDuration()));
    }

    @Override
    @TrackerType int getType() {
        return AppRestrictionController.TRACKER_TYPE_MEDIA_SESSION;
    }

    @Override
    void dump(PrintWriter pw, String prefix) {
        pw.print(prefix);
        pw.println("APP MEDIA SESSION TRACKER:");
        super.dump(pw, "  " + prefix);
    }

    static final class AppMediaSessionPolicy
            extends BaseAppStateEventsPolicy<AppMediaSessionTracker> {
        /**
         * Whether or not we should enable the monitoring on media sessions.
         */
        static final String KEY_BG_MEADIA_SESSION_MONITOR_ENABLED =
                DEVICE_CONFIG_SUBNAMESPACE_PREFIX + "media_session_monitor_enabled";

        /**
         * The maximum duration we'd keep tracking, events earlier than that will be discarded.
         */
        static final String KEY_BG_MEDIA_SESSION_MONITOR_MAX_TRACKING_DURATION =
                DEVICE_CONFIG_SUBNAMESPACE_PREFIX + "media_session_monitor_max_tracking_duration";

        /**
         * Default value to {@link #mTrackerEnabled}.
         */
        static final boolean DEFAULT_BG_MEDIA_SESSION_MONITOR_ENABLED = true;

        /**
         * Default value to {@link #mBgMediaSessionMonitorMaxTrackingDurationMs}.
         */
        static final long DEFAULT_BG_MEDIA_SESSION_MONITOR_MAX_TRACKING_DURATION =
                4 * ONE_DAY;

        AppMediaSessionPolicy(@NonNull Injector injector, @NonNull AppMediaSessionTracker tracker) {
            super(injector, tracker,
                    KEY_BG_MEADIA_SESSION_MONITOR_ENABLED,
                    DEFAULT_BG_MEDIA_SESSION_MONITOR_ENABLED,
                    KEY_BG_MEDIA_SESSION_MONITOR_MAX_TRACKING_DURATION,
                    DEFAULT_BG_MEDIA_SESSION_MONITOR_MAX_TRACKING_DURATION);
        }

        @Override
        public void onTrackerEnabled(boolean enabled) {
            mTracker.onBgMediaSessionMonitorEnabled(enabled);
        }

        @Override
        public void onMaxTrackingDurationChanged(long maxDuration) {
            mTracker.mBgHandler.post(mTracker::trimDurations);
        }

        @Override
        String getExemptionReasonString(String packageName, int uid, @ReasonCode int reason) {
            // This tracker is a helper class for other trackers, we don't track exemptions here.
            return "n/a";
        }

        @Override
        void dump(PrintWriter pw, String prefix) {
            pw.print(prefix);
            pw.println("APP MEDIA SESSION TRACKER POLICY SETTINGS:");
            super.dump(pw, "  " + prefix);
        }
    }
}
