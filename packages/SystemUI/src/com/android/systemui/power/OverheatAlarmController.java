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
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.view.accessibility.AccessibilityManager;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.R;
import com.android.systemui.media.NotificationPlayer;

/**
 * A Controller handle beep sound, vibration and TTS depend on state of OverheatAlarmDialog.
 */
@VisibleForTesting
public class OverheatAlarmController implements TextToSpeech.OnInitListener,
        TextToSpeech.OnUtteranceCompletedListener {
    private static final String TAG = OverheatAlarmController.class.getSimpleName();
    private static final String TTS_UTTERANCE_ID =
            "com.android.systemui.power.OverheatAlarmController.UTTERANCE_ID";
    private static final int SPEAK_TTS_DURATION = 4000;
    private static final int VIBRATION_INTERVAL = 2000;
    private static final long[] mVibratePattern = new long[]{0, 400, 200, 400, 200, 400, 200};

    private static OverheatAlarmController sInstance;

    private final Context mContext;
    private final Vibrator mVibrator;
    private final AccessibilityManager mAccessibilityManager;

    private NotificationPlayer mPlayer;
    private VibrationEffect mVibrationEffect;
    private TextToSpeech mTts;

    private boolean mShouldVibrate;
    private boolean mEnableTts;
    private Status mTtsStatus = Status.UNINITIALIZED;

    /** Status of the underlying TTS. */
    enum Status {
        /** Initialization TTS isn't complete yet, queue any text that is attempted to be spoken. */
        UNINITIALIZED,
        /** Initialization completed successfully. */
        INITIALIZED,
        /** Something went wrong initializing TTS, there is no additional error data available. */
        ERROR,
        /** While Beep Sound is alarming hook this state. */
        ALERTING,
        /** Keep in the state while TTS is speaking */
        SPEAKING
    }

    /**
     * The constructor only used to create singleton sInstance.
     */
    private OverheatAlarmController(Context context) {
        mContext = context;
        mVibrator = (Vibrator) context.getSystemService(VIBRATOR_SERVICE);
        mAccessibilityManager = (AccessibilityManager) context.getSystemService(
                context.ACCESSIBILITY_SERVICE);
    }

    @Override
    public void onInit(int status) {
        switch (status) {
            // Accessibility talkback will mix up tts speaking, file a new bug to
            // improve UX by arranged talkback, beep sound, and tts in sequence
            case TextToSpeech.SUCCESS:
                mTtsStatus = Status.INITIALIZED;
                mTts.setOnUtteranceCompletedListener(this);
                if (mContext != null) {
                    String speechContent = mContext.getString(
                            R.string.high_temp_alarm_notify_message);
                    Handler.getMain().sendMessageDelayed(
                            obtainMessage(OverheatAlarmController::speakTts, this,
                                    speechContent), SPEAK_TTS_DURATION);
                }
                break;
            case TextToSpeech.ERROR:
                Log.e(TAG, "Error initializing TTS");
                mTtsStatus = Status.ERROR;
                mTts = null;
                break;
            default:
                break;
        }
    }

    @Override
    public void onUtteranceCompleted(String utteranceId) {
        if (utteranceId.equals(TTS_UTTERANCE_ID) && mTtsStatus == Status.SPEAKING) {
            mTtsStatus = Status.INITIALIZED;
        }
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
    public void startAlarm() {
        if (mPlayer != null) {
            return;
        }
        playSound();
        startVibrate();
        if (!mAccessibilityManager.isTouchExplorationEnabled() && mEnableTts) {
            // Accessibility talkback will mix up tts speaking, file a new bug to
            // improve UX by arranged talkback, beep sound, and tts in sequence
            // TODO b/119004977 to implement alarm beep sound and tts in sequence
            initTts();
        }
    }

    /**
     * Stop alarming beep sound, vibrating, and TTS if initialized.
     */
    @VisibleForTesting
    public void stopAlarm() {
        stopPlayer();
        stopVibrate();
        stopTts();
    }

    /**
     * Allow runtime enable TTS since it disable by default in P.
     *
     * @param enable enable TTS while startAlarm() was called
     */
    public void setEnableTts(boolean enable) {
        mEnableTts = enable;
    }

    @VisibleForTesting
    protected void playSound() {
        Uri uri = Uri.parse(new Uri.Builder()
                .scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
                .authority(mContext.getBasePackageName())
                .appendPath(Integer.toString(R.raw.overheat_alarm)).build().toString());

        if (mPlayer == null) {
            mPlayer = new NotificationPlayer(TAG);
        }
        mPlayer.setUsesWakeLock(mContext);
        mPlayer.play(mContext, uri, true/*looping*/, getAlertAudioAttributes());
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
            mVibrationEffect = VibrationEffect.createWaveform(mVibratePattern, -1);
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

    private void speakTts(String message) {
        if (mTtsStatus == Status.INITIALIZED && mTts != null) {
            Bundle params = new Bundle();
            params.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_ALARM);
            int result = mTts.speak(message, TextToSpeech.QUEUE_FLUSH, params, TTS_UTTERANCE_ID);
            switch (result) {
                case TextToSpeech.SUCCESS:
                    mTtsStatus = Status.SPEAKING;
                case TextToSpeech.STOPPED:
                    mTtsStatus = Status.INITIALIZED;
                    break;
                case TextToSpeech.ERROR:
                default:
                    Log.e(TAG, "TTS engine speak() occur error, result=" + result);
                    mTtsStatus = Status.ERROR;
                    stopTts();
                    break;
            }
        }
    }

    private void initTts() {
        if (mTts == null) {
            mTts = new TextToSpeech(mContext, this, TTS_UTTERANCE_ID);
        }
    }

    private void stopTts() {
        if (mTts != null) {
            try {
                mTts.shutdown();
                mTts = null;
                mTtsStatus = Status.UNINITIALIZED;
            } catch (IllegalArgumentException e) {
                Log.e(TAG, "Shutdown TTS error", e);
            }
        }
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
