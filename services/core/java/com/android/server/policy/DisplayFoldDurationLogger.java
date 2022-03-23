/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.policy;

import android.annotation.IntDef;
import android.metrics.LogMaker;
import android.os.SystemClock;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Logger for tracking duration of usage in folded vs unfolded state.
 */
class DisplayFoldDurationLogger {
    static final int SCREEN_STATE_UNKNOWN = -1;
    static final int SCREEN_STATE_OFF = 0;
    static final int SCREEN_STATE_ON_UNFOLDED = 1;
    static final int SCREEN_STATE_ON_FOLDED = 2;

    @IntDef(flag = true, prefix = {"SCREEN_STATE_"}, value = {
            SCREEN_STATE_UNKNOWN,
            SCREEN_STATE_OFF,
            SCREEN_STATE_ON_UNFOLDED,
            SCREEN_STATE_ON_FOLDED
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ScreenState {}

    private @ScreenState int mScreenState = SCREEN_STATE_UNKNOWN;
    private Long mLastChanged = null;

    private static final int LOG_SUBTYPE_UNFOLDED = 0;
    private static final int LOG_SUBTYPE_FOLDED = 1;
    private static final int LOG_SUBTYPE_DURATION_MASK = 0x80000000;

    private final MetricsLogger mLogger = new MetricsLogger();

    void onFinishedWakingUp(Boolean folded) {
        if (folded == null) {
            mScreenState = SCREEN_STATE_UNKNOWN;
        } else if (folded) {
            mScreenState = SCREEN_STATE_ON_FOLDED;
        } else {
            mScreenState = SCREEN_STATE_ON_UNFOLDED;
        }
        mLastChanged = SystemClock.uptimeMillis();
    }

    void onFinishedGoingToSleep() {
        log();
        mScreenState = SCREEN_STATE_OFF;
        mLastChanged = null;
    }

    void setDeviceFolded(boolean folded) {
        // This function is called even when the screen is in ADO mode, but we're only
        // interested in the case that the screen is actually on.
        if (!isOn()) {
            return;
        }
        log();
        mScreenState = folded ? SCREEN_STATE_ON_FOLDED : SCREEN_STATE_ON_UNFOLDED;
        mLastChanged = SystemClock.uptimeMillis();
    }

    void logFocusedAppWithFoldState(boolean folded, String packageName) {
        mLogger.write(
                new LogMaker(MetricsProto.MetricsEvent.ACTION_DISPLAY_FOLD)
                        .setType(MetricsProto.MetricsEvent.TYPE_ACTION)
                        .setSubtype(folded ? LOG_SUBTYPE_FOLDED : LOG_SUBTYPE_UNFOLDED)
                        .setPackageName(packageName));
    }

    private void log() {
        if (mLastChanged == null) {
            return;
        }
        int subtype;
        switch (mScreenState) {
            case SCREEN_STATE_ON_UNFOLDED:
                subtype = LOG_SUBTYPE_UNFOLDED | LOG_SUBTYPE_DURATION_MASK;
                break;
            case SCREEN_STATE_ON_FOLDED:
                subtype = LOG_SUBTYPE_FOLDED | LOG_SUBTYPE_DURATION_MASK;
                break;
            default:
                return;
        }
        mLogger.write(
                new LogMaker(MetricsProto.MetricsEvent.ACTION_DISPLAY_FOLD)
                        .setType(MetricsProto.MetricsEvent.TYPE_ACTION)
                        .setSubtype(subtype)
                        .setLatency(SystemClock.uptimeMillis() - mLastChanged));
    }

    private boolean isOn() {
        return mScreenState == SCREEN_STATE_ON_UNFOLDED || mScreenState == SCREEN_STATE_ON_FOLDED;
    }
}
