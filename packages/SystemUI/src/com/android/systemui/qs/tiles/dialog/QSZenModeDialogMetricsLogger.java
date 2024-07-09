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

package com.android.systemui.qs.tiles.dialog;

import android.content.Context;

import com.android.internal.logging.UiEventLogger;
import com.android.settingslib.notification.modes.ZenModeDialogMetricsLogger;
import com.android.systemui.qs.QSDndEvent;
import com.android.systemui.qs.QSEvents;

/**
 * Logs ui events for the DND dialog that may appear from tapping the QS DND tile.
 * To see the dialog from QS:
 *     Settings > Notifications > Do Not Disturb > Duration for Quick Settings >  Ask every time
 *
 * Other names for DND (Do Not Disturb) include "Zen" and "Priority only".
 */
public class QSZenModeDialogMetricsLogger extends ZenModeDialogMetricsLogger {
    private final UiEventLogger mUiEventLogger = QSEvents.INSTANCE.getQsUiEventsLogger();

    public QSZenModeDialogMetricsLogger(Context context) {
        super(context);
    }

    @Override
    public void logOnEnableZenModeForever() {
        super.logOnEnableZenModeForever();
        mUiEventLogger.log(QSDndEvent.QS_DND_DIALOG_ENABLE_FOREVER);
    }

    @Override
    public void logOnEnableZenModeUntilAlarm() {
        super.logOnEnableZenModeUntilAlarm();
        mUiEventLogger.log(QSDndEvent.QS_DND_DIALOG_ENABLE_UNTIL_ALARM);
    }

    @Override
    public void logOnEnableZenModeUntilCountdown() {
        super.logOnEnableZenModeUntilCountdown();
        mUiEventLogger.log(QSDndEvent.QS_DND_DIALOG_ENABLE_UNTIL_COUNTDOWN);
    }

    @Override
    public void logOnConditionSelected() {
        super.logOnConditionSelected();
        mUiEventLogger.log(QSDndEvent.QS_DND_CONDITION_SELECT);
    }

    @Override
    public void logOnClickTimeButton(boolean up) {
        super.logOnClickTimeButton(up);
        mUiEventLogger.log(up
                ? QSDndEvent.QS_DND_TIME_UP : QSDndEvent.QS_DND_TIME_DOWN);
    }
}

