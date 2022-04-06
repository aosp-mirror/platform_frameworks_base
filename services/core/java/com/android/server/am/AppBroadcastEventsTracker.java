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

import android.annotation.NonNull;
import android.app.ActivityManagerInternal.BroadcastEventListener;
import android.content.Context;

import com.android.server.am.AppBroadcastEventsTracker.AppBroadcastEventsPolicy;
import com.android.server.am.AppRestrictionController.TrackerType;
import com.android.server.am.BaseAppStateTimeSlotEventsTracker.SimpleAppStateTimeslotEvents;
import com.android.server.am.BaseAppStateTracker.Injector;

import java.io.PrintWriter;
import java.lang.reflect.Constructor;

final class AppBroadcastEventsTracker extends BaseAppStateTimeSlotEventsTracker
        <AppBroadcastEventsPolicy, SimpleAppStateTimeslotEvents> implements BroadcastEventListener {

    static final String TAG = TAG_WITH_CLASS_NAME ? "AppBroadcastEventsTracker" : TAG_AM;

    static final boolean DEBUG_APP_STATE_BROADCAST_EVENT_TRACKER = false;

    AppBroadcastEventsTracker(Context context, AppRestrictionController controller) {
        this(context, controller, null, null);
    }

    AppBroadcastEventsTracker(Context context, AppRestrictionController controller,
            Constructor<? extends Injector<AppBroadcastEventsPolicy>> injector,
            Object outerContext) {
        super(context, controller, injector, outerContext);
        mInjector.setPolicy(new AppBroadcastEventsPolicy(mInjector, this));
    }

    @Override
    public void onSendingBroadcast(String packageName, int uid) {
        if (mInjector.getPolicy().isEnabled()) {
            onNewEvent(packageName, uid);
        }
    }

    @Override
    @TrackerType int getType() {
        return AppRestrictionController.TRACKER_TYPE_BROADCAST_EVENTS;
    }

    @Override
    void onSystemReady() {
        super.onSystemReady();
        mInjector.getActivityManagerInternal().addBroadcastEventListener(this);
    }

    @Override
    public SimpleAppStateTimeslotEvents createAppStateEvents(int uid, String packageName) {
        return new SimpleAppStateTimeslotEvents(uid, packageName,
                mInjector.getPolicy().getTimeSlotSize(), TAG, mInjector.getPolicy());
    }

    @Override
    public SimpleAppStateTimeslotEvents createAppStateEvents(SimpleAppStateTimeslotEvents other) {
        return new SimpleAppStateTimeslotEvents(other);
    }

    @Override
    void dump(PrintWriter pw, String prefix) {
        pw.print(prefix);
        pw.println("APP BROADCAST EVENT TRACKER:");
        super.dump(pw, "  " + prefix);
    }

    static final class AppBroadcastEventsPolicy
            extends BaseAppStateTimeSlotEventsPolicy<AppBroadcastEventsTracker> {
        /**
         * Whether or not we should enable the monitoring on abusive broadcasts.
         */
        static final String KEY_BG_BROADCAST_MONITOR_ENABLED =
                DEVICE_CONFIG_SUBNAMESPACE_PREFIX + "broadcast_monitor_enabled";

        /**
         * The size of the sliding window in which the number of broadcasts is checked
         * against the threshold.
         */
        static final String KEY_BG_BROADCAST_WINDOW =
                DEVICE_CONFIG_SUBNAMESPACE_PREFIX + "broadcast_window";

        /**
         * The threshold at where the number of broadcasts are considered as "excessive"
         * within the given window.
         */
        static final String KEY_BG_EX_BROADCAST_THRESHOLD =
                DEVICE_CONFIG_SUBNAMESPACE_PREFIX + "ex_broadcast_threshold";

        /**
         * Default value to {@link #mTrackerEnabled}.
         */
        static final boolean DEFAULT_BG_BROADCAST_MONITOR_ENABLED = true;

        /**
         * Default value to {@link #mMaxTrackingDuration}.
         */
        static final long DEFAULT_BG_BROADCAST_WINDOW = ONE_DAY;

        /**
         * Default value to {@link #mNumOfEventsThreshold}.
         */
        static final int DEFAULT_BG_EX_BROADCAST_THRESHOLD = 10_000;

        AppBroadcastEventsPolicy(@NonNull Injector injector,
                @NonNull AppBroadcastEventsTracker tracker) {
            super(injector, tracker,
                    KEY_BG_BROADCAST_MONITOR_ENABLED, DEFAULT_BG_BROADCAST_MONITOR_ENABLED,
                    KEY_BG_BROADCAST_WINDOW, DEFAULT_BG_BROADCAST_WINDOW,
                    KEY_BG_EX_BROADCAST_THRESHOLD, DEFAULT_BG_EX_BROADCAST_THRESHOLD);
        }

        @Override
        String getEventName() {
            return "broadcast";
        }

        @Override
        void dump(PrintWriter pw, String prefix) {
            pw.print(prefix);
            pw.println("APP BROADCAST EVENT TRACKER POLICY SETTINGS:");
            super.dump(pw, "  " + prefix);
        }
    }
}
