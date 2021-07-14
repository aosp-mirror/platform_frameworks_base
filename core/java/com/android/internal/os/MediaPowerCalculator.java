/*
 * Copyright (C) 2018 The Android Open Source Project
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
package com.android.internal.os;

import android.os.BatteryStats;

/**
 * A {@link PowerCalculator} to calculate power consumed by audio and video hardware.
 *
 * Also see {@link PowerProfile#POWER_AUDIO} and {@link PowerProfile#POWER_VIDEO}.
 */
public class MediaPowerCalculator extends PowerCalculator {
    private static final int MS_IN_HR = 1000 * 60 * 60;
    private final double mAudioAveragePowerMa;
    private final double mVideoAveragePowerMa;

    public MediaPowerCalculator(PowerProfile profile) {
        mAudioAveragePowerMa = profile.getAveragePower(PowerProfile.POWER_AUDIO);
        mVideoAveragePowerMa = profile.getAveragePower(PowerProfile.POWER_VIDEO);
    }

    @Override
    protected void calculateApp(BatterySipper app, BatteryStats.Uid u, long rawRealtimeUs,
            long rawUptimeUs, int statsType) {
        // Calculate audio power usage, an estimate based on the average power routed to different
        // components like speaker, bluetooth, usb-c, earphone, etc.
        final BatteryStats.Timer audioTimer = u.getAudioTurnedOnTimer();
        if (audioTimer == null) {
            app.audioTimeMs = 0;
            app.audioPowerMah = 0;
        } else {
            final long totalTime = audioTimer.getTotalTimeLocked(rawRealtimeUs, statsType) / 1000;
            app.audioTimeMs = totalTime;
            app.audioPowerMah = (totalTime * mAudioAveragePowerMa) / MS_IN_HR;
        }

        // Calculate video power usage.
        final BatteryStats.Timer videoTimer = u.getVideoTurnedOnTimer();
        if (videoTimer == null) {
            app.videoTimeMs = 0;
            app.videoPowerMah = 0;
        } else {
            final long totalTime = videoTimer.getTotalTimeLocked(rawRealtimeUs, statsType) / 1000;
            app.videoTimeMs = totalTime;
            app.videoPowerMah = (totalTime * mVideoAveragePowerMa) / MS_IN_HR;
        }
    }
}
