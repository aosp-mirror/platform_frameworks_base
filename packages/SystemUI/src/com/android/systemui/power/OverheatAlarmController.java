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

package com.android.systemui.power;

import static android.content.Context.VIBRATOR_SERVICE;

import static com.android.internal.util.function.pooled.PooledLambda.obtainMessage;

import android.content.ContentResolver;
import android.content.Context;
import android.media.AudioAttributes;
import android.net.Uri;
import android.os.Handler;
import android.os.VibrationEffect;
import android.os.Vibrator;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.R;
import com.android.systemui.media.NotificationPlayer;

/**
 * A Controller handle beep sound, vibration and TTS depend on state of OverheatAlarmDialog.
 */
public class OverheatAlarmController {
    private static final String TAG = OverheatAlarmController.class.getSimpleName();

    private static final int VIBRATION_INTERVAL = 2000;
    private static final long[] VIBRATION_PATTERN = new long[]{0, 400, 200, 400, 200, 400, 200};

    private static OverheatAlarmController sInstance;

    private final Vibrator mVibrator;

    private NotificationPlayer mPlayer;
    private VibrationEffect mVibrationEffect;

    private boolean mShouldVibrate;

    /**
     * The constructor only used to create singleton sInstance.
     */
    private OverheatAlarmController(Context context) {
        mVibrator = (Vibrator) context.getSystemService(VIBRATOR_SERVICE);
    }

    /**
     * Get singleton OverheatAlarmController instance.
     */
    public static OverheatAlarmController getInstance(Context context) {
        if (context == null) {
            throw new IllegalArgumentException();
        }
        if (sInstance == null) {
            sInstance = new OverheatAlarmController(context);
        }
        return sInstance;
    }

    /**
     * Starting alarm beep sound and vibration.
     */
    @VisibleForTesting
    public void startAlarm(Context context) {
        if (mPlayer != null) {
            return;
        }
        playSound(context);
        startVibrate();
    }

    /**
     * Stop alarming beep sound, vibrating, and TTS if initialized.
     */
    @VisibleForTesting
    public void stopAlarm() {
        stopPlayer();
        stopVibrate();
    }

    @VisibleForTesting
    protected void playSound(Context context) {
        Uri uri = new Uri.Builder()
                .scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
                .authority(context.getBasePackageName())
                .appendPath(Integer.toString(R.raw.overheat_alarm)).build();

        if (mPlayer == null) {
            mPlayer = new NotificationPlayer(TAG);
        }
        mPlayer.setUsesWakeLock(context);
        mPlayer.play(context, uri, true /* looping */, getAlertAudioAttributes());
    }

    @VisibleForTesting
    protected void stopPlayer() {
        if (mPlayer != null) {
            mPlayer.stop();
            mPlayer = null;
        }
    }

    @VisibleForTesting
    protected void startVibrate() {
        mShouldVibrate = true;
        if (mVibrationEffect == null) {
            mVibrationEffect = VibrationEffect.createWaveform(VIBRATION_PATTERN, -1);
        }
        performVibrate();
    }

    @VisibleForTesting
    protected void performVibrate() {
        if (mShouldVibrate && mVibrator != null) {
            mVibrator.vibrate(mVibrationEffect, getAlertAudioAttributes());
            Handler.getMain().sendMessageDelayed(
                    obtainMessage(OverheatAlarmController::performVibrate, this),
                    VIBRATION_INTERVAL);
        }
    }

    @VisibleForTesting
    protected void stopVibrate() {
        if (mVibrator != null) {
            mVibrator.cancel();
        }
        mShouldVibrate = false;
    }

    /**
     * Build AudioAttributes for mPlayer(NotificationPlayer) and vibrator
     * Use the alarm channel so it can vibrate in DnD mode, unless alarms are
     * specifically disabled in DnD.
     */
    private static AudioAttributes getAlertAudioAttributes() {
        AudioAttributes.Builder builder = new AudioAttributes.Builder();
        builder.setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION);
        builder.setUsage(AudioAttributes.USAGE_ALARM);
        // Set FLAG_BYPASS_INTERRUPTION_POLICY and FLAG_BYPASS_MUTE so that it enables
        // audio in any DnD mode, even in total silence DnD mode (requires MODIFY_PHONE_STATE).
        builder.setFlags(
                AudioAttributes.FLAG_BYPASS_INTERRUPTION_POLICY | AudioAttributes.FLAG_BYPASS_MUTE);
        return builder.build();
    }
}
