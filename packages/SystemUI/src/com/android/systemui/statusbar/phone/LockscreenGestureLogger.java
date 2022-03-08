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
 * limitations under the License
 */

package com.android.systemui.statusbar.phone;

import android.metrics.LogMaker;
import android.util.ArrayMap;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.UiEvent;
import com.android.internal.logging.UiEventLogger;
import com.android.internal.logging.UiEventLoggerImpl;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.systemui.EventLogConstants;
import com.android.systemui.EventLogTags;
import com.android.systemui.dagger.SysUISingleton;

import javax.inject.Inject;

/**
 * Wrapper that emits both new- and old-style gesture logs.
 * TODO: delete this once the old logs are no longer needed.
 */
@SysUISingleton
public class LockscreenGestureLogger {

    /**
     * Contains Lockscreen related statsd UiEvent enums.
     */
    public enum LockscreenUiEvent implements UiEventLogger.UiEventEnum {
        @UiEvent(doc = "Lockscreen > Pull shade open")
        LOCKSCREEN_PULL_SHADE_OPEN(539),

        @UiEvent(doc = "Lockscreen > Tap on lock, locks phone")
        LOCKSCREEN_LOCK_TAP(540),

        @UiEvent(doc = "Lockscreen > Swipe down to open quick settings")
        LOCKSCREEN_QUICK_SETTINGS_OPEN(541),

        @UiEvent(doc = "Swipe down to open quick settings when unlocked")
        LOCKSCREEN_UNLOCKED_QUICK_SETTINGS_OPEN(542),

        @UiEvent(doc = "Lockscreen > Tap on lock, shows hint")
        LOCKSCREEN_LOCK_SHOW_HINT(543),

        @UiEvent(doc = "Notification shade > Tap to open quick settings")
        LOCKSCREEN_NOTIFICATION_SHADE_QUICK_SETTINGS_OPEN(544),

        @UiEvent(doc = "Lockscreen > Dialer")
        LOCKSCREEN_DIALER(545),

        @UiEvent(doc = "Lockscreen > Camera")
        LOCKSCREEN_CAMERA(546),

        @UiEvent(doc = "Lockscreen > Unlock gesture")
        LOCKSCREEN_UNLOCK(547),

        @UiEvent(doc = "Lockscreen > Tap on notification, false touch rejection")
        LOCKSCREEN_NOTIFICATION_FALSE_TOUCH(548),

        @UiEvent(doc = "Expand the notification panel while unlocked")
        LOCKSCREEN_UNLOCKED_NOTIFICATION_PANEL_EXPAND(549);

        private final int mId;

        LockscreenUiEvent(int id) {
            mId = id;
        }

        @Override
        public int getId() {
            return mId;
        }
    }

    private ArrayMap<Integer, Integer> mLegacyMap;
    private final MetricsLogger mMetricsLogger;

    @Inject
    public LockscreenGestureLogger(MetricsLogger metricsLogger) {
        mMetricsLogger = metricsLogger;
        mLegacyMap = new ArrayMap<>(EventLogConstants.METRICS_GESTURE_TYPE_MAP.length);
        for (int i = 0; i < EventLogConstants.METRICS_GESTURE_TYPE_MAP.length ; i++) {
            mLegacyMap.put(EventLogConstants.METRICS_GESTURE_TYPE_MAP[i], i);
        }
    }

    public void write(int gesture, int length, int velocity) {
        mMetricsLogger.write(new LogMaker(gesture)
                .setType(MetricsEvent.TYPE_ACTION)
                .addTaggedData(MetricsEvent.FIELD_GESTURE_LENGTH, length)
                .addTaggedData(MetricsEvent.FIELD_GESTURE_VELOCITY, velocity));
        // also write old-style logs for backward-0compatibility
        EventLogTags.writeSysuiLockscreenGesture(safeLookup(gesture), length, velocity);
    }

    /**
     * Logs {@link LockscreenUiEvent}.
     */
    public void log(LockscreenUiEvent lockscreenUiEvent) {
        new UiEventLoggerImpl().log(lockscreenUiEvent);
    }

    /**
     * Record the location of a swipe gesture, expressed as percentages of the whole screen
     * @param category the action
     * @param xPercent x-location / width * 100
     * @param yPercent y-location / height * 100
     */
    public void writeAtFractionalPosition(
            int category, int xPercent, int yPercent, int rotation) {
        mMetricsLogger.write(new LogMaker(category)
                .setType(MetricsEvent.TYPE_ACTION)
                .addTaggedData(MetricsEvent.FIELD_GESTURE_X_PERCENT, xPercent)
                .addTaggedData(MetricsEvent.FIELD_GESTURE_Y_PERCENT, yPercent)
                .addTaggedData(MetricsEvent.FIELD_DEVICE_ROTATION, rotation));
    }

    private int safeLookup(int gesture) {
        Integer value = mLegacyMap.get(gesture);
        if (value == null) {
            return MetricsEvent.VIEW_UNKNOWN;
        }
        return value;
    }
}
