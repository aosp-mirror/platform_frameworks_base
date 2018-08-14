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
package com.android.statsd.loadtest;

import android.util.Log;
import android.util.StatsLog;

import java.util.ArrayList;
import java.util.List;

/**
 * Manages the pushing of atoms into logd for loadtesting.
 * We rely on small number of pushed atoms, and a config with metrics based on those atoms.
 * The atoms are:
 * <ul>
 *   <li> BatteryLevelChanged   - For EventMetric, CountMetric and GaugeMetric (no dimensions).
 *   <li> BleScanResultReceived - For CountMetric and ValueMetric, sliced by uid.
 *   <li> ChargingStateChanged  - For DurationMetric (no dimension).
 *   <li> GpsScanStateChanged   - For DurationMetric, sliced by uid.
 *   <li> ScreenStateChanged    - For Conditions with no dimensions.
 *   <li> AudioStateChanged     - For Conditions with dimensions (uid).
 * </ul>
 * The sequence is played over and over at a given frequency.
 */
public class SequencePusher {
    private static final String TAG = "SequencePusher";

    /** Some atoms are pushed in burst of {@code mBurst} events. */
    private final int mBurst;

    /** If this is true, we don't log anything in logd. */
    private final boolean mPlacebo;

    /** Current state in the automaton. */
    private int mCursor = 0;

  public SequencePusher(int burst, boolean placebo) {
        mBurst = burst;
        mPlacebo = placebo;
    }

    /**
     * Pushes the next atom to logd.
     * This follows a small automaton which makes the right events and conditions overlap:
     *   (0)  Push a burst of BatteryLevelChanged atoms.
     *   (1)  Push a burst of BleScanResultReceived atoms.
     *   (2)  Push ChargingStateChanged with BATTERY_STATUS_CHARGING once.
     *   (3)  Push a burst of GpsScanStateChanged atoms with ON, with a different uid each time.
     *   (4)  Push ChargingStateChanged with BATTERY_STATUS_NOT_CHARGING once.
     *   (5)  Push a burst GpsScanStateChanged atoms with OFF, with a different uid each time.
     *   (6)  Push ScreenStateChanged with STATE_ON once.
     *   (7)  Push a burst of AudioStateChanged with ON, with a different uid each time.
     *   (8)  Repeat steps (0)-(5).
     *   (9)  Push ScreenStateChanged with STATE_OFF once.
     *   (10) Push a burst of AudioStateChanged with OFF, with a different uid each time.
     * and repeat.
     */
    public void next() {
        Log.d(TAG, "Next step: " + mCursor);
        if (mPlacebo) {
            return;
        }
        switch (mCursor) {
            case 0:
            case 8:
                for (int i = 0; i < mBurst; i++) {
                    StatsLog.write(StatsLog.BATTERY_LEVEL_CHANGED, 50 + i /* battery_level */);
                }
                break;
            case 1:
            case 9:
                for (int i = 0; i < mBurst; i++) {
                    StatsLog.write(StatsLog.BLE_SCAN_RESULT_RECEIVED, i /* uid */,
                        100 /* num_of_results */);
                }
                break;
            case 2:
            case 10:
                StatsLog.write(StatsLog.CHARGING_STATE_CHANGED,
                    StatsLog.CHARGING_STATE_CHANGED__STATE__BATTERY_STATUS_CHARGING
                    /* charging_state */);
                break;
            case 3:
            case 11:
                for (int i = 0; i < mBurst; i++) {
                    StatsLog.write(StatsLog.GPS_SCAN_STATE_CHANGED, i /* uid */,
                        StatsLog.GPS_SCAN_STATE_CHANGED__STATE__ON /* state */);
                }
                break;
            case 4:
            case 12:
                StatsLog.write(StatsLog.CHARGING_STATE_CHANGED,
                    StatsLog.CHARGING_STATE_CHANGED__STATE__BATTERY_STATUS_NOT_CHARGING
                    /* charging_state */);
                break;
            case 5:
            case 13:
                for (int i = 0; i < mBurst; i++) {
                    StatsLog.write(StatsLog.GPS_SCAN_STATE_CHANGED, i /* uid */,
                        StatsLog.GPS_SCAN_STATE_CHANGED__STATE__OFF /* state */);
                }
                break;
            case 6:
                StatsLog.write(StatsLog.SCREEN_STATE_CHANGED,
                    StatsLog.SCREEN_STATE_CHANGED__STATE__DISPLAY_STATE_ON /* display_state */);
                break;
            case 7:
                for (int i = 0; i < mBurst; i++) {
                    StatsLog.write(StatsLog.AUDIO_STATE_CHANGED, i /* uid */,
                        StatsLog.AUDIO_STATE_CHANGED__STATE__ON /* state */);
                }
                break;
            case 14:
                StatsLog.write(StatsLog.SCREEN_STATE_CHANGED,
                    StatsLog.SCREEN_STATE_CHANGED__STATE__DISPLAY_STATE_OFF /* display_state */);
                break;
            case 15:
                for (int i = 0; i < mBurst; i++) {
                    StatsLog.write(StatsLog.AUDIO_STATE_CHANGED, i /* uid */,
                        StatsLog.AUDIO_STATE_CHANGED__STATE__OFF /* state */);
                }
                break;
            default:
        }
        mCursor++;
        if (mCursor > 15) {
            mCursor = 0;
        }
    }

    /**
     * Properly finishes in order to be close all conditions and durations.
     */
    public void finish() {
        // Screen goes back to off. This will ensure that conditions get back to false.
        StatsLog.write(StatsLog.SCREEN_STATE_CHANGED,
            StatsLog.SCREEN_STATE_CHANGED__STATE__DISPLAY_STATE_OFF /* display_state */);
        for (int i = 0; i < mBurst; i++) {
          StatsLog.write(StatsLog.AUDIO_STATE_CHANGED, i /* uid */,
              StatsLog.AUDIO_STATE_CHANGED__STATE__OFF /* state */);
        }
        // Stop charging, to ensure the corresponding durations are closed.
        StatsLog.write(StatsLog.CHARGING_STATE_CHANGED,
            StatsLog.CHARGING_STATE_CHANGED__STATE__BATTERY_STATUS_NOT_CHARGING
            /* charging_state */);
        // Stop scanning GPS, to ensure the corresponding conditions get back to false.
        for (int i = 0; i < mBurst; i++) {
          StatsLog.write(StatsLog.GPS_SCAN_STATE_CHANGED, i /* uid */,
              StatsLog.GPS_SCAN_STATE_CHANGED__STATE__OFF /* state */);
        }
    }
}
