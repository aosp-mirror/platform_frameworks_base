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

package com.android.settingslib.notification;

import android.content.Context;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto;

/**
 * Logs ui events for {@link EnableZenModeDialog}.
 */
public class ZenModeDialogMetricsLogger {
    private final Context mContext;

    public ZenModeDialogMetricsLogger(Context context) {
        mContext = context;
    }

    /**
     * User enabled DND from the QS DND dialog to last until manually turned off
     */
    public void logOnEnableZenModeForever() {
        MetricsLogger.action(
                mContext,
                MetricsProto.MetricsEvent.NOTIFICATION_ZEN_MODE_TOGGLE_ON_FOREVER);
    }

    /**
     * User enabled DND from the QS DND dialog to last until the next alarm goes off
     */
    public void logOnEnableZenModeUntilAlarm() {
        MetricsLogger.action(
                mContext,
                MetricsProto.MetricsEvent.NOTIFICATION_ZEN_MODE_TOGGLE_ON_ALARM);
    }

    /**
     * User enabled DND from the QS DND dialog to last until countdown is done
     */
    public void logOnEnableZenModeUntilCountdown() {
        MetricsLogger.action(
                mContext,
                MetricsProto.MetricsEvent.NOTIFICATION_ZEN_MODE_TOGGLE_ON_COUNTDOWN);
    }

    /**
     * User selected an option on the DND dialog
     */
    public void logOnConditionSelected() {
        MetricsLogger.action(
                mContext,
                MetricsProto.MetricsEvent.QS_DND_CONDITION_SELECT);
    }

    /**
     * User increased or decreased countdown duration of DND from the DND dialog
     */
    public void logOnClickTimeButton(boolean up) {
        MetricsLogger.action(mContext, MetricsProto.MetricsEvent.QS_DND_TIME, up);
    }
}
