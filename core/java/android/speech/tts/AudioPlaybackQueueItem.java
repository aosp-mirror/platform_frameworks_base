/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package android.speech.tts;

import android.content.Context;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.ConditionVariable;
import android.speech.tts.TextToSpeechService.AudioOutputParams;
import android.speech.tts.TextToSpeechService.UtteranceProgressDispatcher;
import android.util.Log;

class AudioPlaybackQueueItem extends PlaybackQueueItem {
    private static final String TAG = "TTS.AudioQueueItem";

    private final Context mContext;
    private final Uri mUri;
    private final AudioOutputParams mAudioParams;

    private final ConditionVariable mDone;
    private MediaPlayer mPlayer;
    private volatile boolean mFinished;

    AudioPlaybackQueueItem(UtteranceProgressDispatcher dispatcher,
            Object callerIdentity,
            Context context, Uri uri, AudioOutputParams audioParams) {
        super(dispatcher, callerIdentity);

        mContext = context;
        mUri = uri;
        mAudioParams = audioParams;

        mDone = new ConditionVariable();
        mPlayer = null;
        mFinished = false;
    }
    @Override
    public void run() {
        final UtteranceProgressDispatcher dispatcher = getDispatcher();

        dispatcher.dispatchOnStart();

        int sessionId = mAudioParams.mSessionId;
        mPlayer = MediaPlayer.create(
                mContext, mUri, null, mAudioParams.mAudioAttributes,
                sessionId > 0 ? sessionId : AudioManager.AUDIO_SESSION_ID_GENERATE);
        if (mPlayer == null) {
            dispatcher.dispatchOnError(TextToSpeech.ERROR_OUTPUT);
            return;
        }

        try {
            mPlayer.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override
                public boolean onError(MediaPlayer mp, int what, int extra) {
                    Log.w(TAG, "Audio playback error: " + what + ", " + extra);
                    mDone.open();
                    return true;
                }
            });
            mPlayer.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override
                public void onCompletion(MediaPlayer mp) {
                    mFinished = true;
                    mDone.open();
                }
            });

            setupVolume(mPlayer, mAudioParams.mVolume, mAudioParams.mPan);
            mPlayer.start();
            mDone.block();
            finish();
        } catch (IllegalArgumentException ex) {
            Log.w(TAG, "MediaPlayer failed", ex);
            mDone.open();
        }

        if (mFinished) {
            dispatcher.dispatchOnSuccess();
        } else {
            dispatcher.dispatchOnStop();
        }
    }

    private static void setupVolume(MediaPlayer player, float volume, float pan) {
        final float vol = clip(volume, 0.0f, 1.0f);
        final float panning = clip(pan, -1.0f, 1.0f);

        float volLeft = vol, volRight = vol;
        if (panning > 0.0f) {
            volLeft *= (1.0f - panning);
        } else if (panning < 0.0f) {
            volRight *= (1.0f + panning);
        }
        player.setVolume(volLeft, volRight);
    }

    private static final float clip(float value, float min, float max) {
        return value < min ? min : (value < max ? value : max);
    }

    private void finish() {
        try {
            mPlayer.stop();
        } catch (IllegalStateException ex) {
            // Do nothing, the player is already stopped
        }
        mPlayer.release();
    }

    @Override
    void stop(int errorCode) {
        mDone.open();
    }
}
