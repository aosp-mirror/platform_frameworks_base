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
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.ConditionVariable;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;

/**
 * A media player that allows blocking to wait for it to finish.
 */
class BlockingMediaPlayer {

    private static final String TAG = "BlockMediaPlayer";

    private static final String MEDIA_PLAYER_THREAD_NAME = "TTS-MediaPlayer";

    private final Context mContext;
    private final Uri mUri;
    private final int mStreamType;
    private final ConditionVariable mDone;
    // Only accessed on the Handler thread
    private MediaPlayer mPlayer;
    private volatile boolean mFinished;

    /**
     * Creates a new blocking media player.
     * Creating a blocking media player is a cheap operation.
     *
     * @param context
     * @param uri
     * @param streamType
     */
    public BlockingMediaPlayer(Context context, Uri uri, int streamType) {
        mContext = context;
        mUri = uri;
        mStreamType = streamType;
        mDone = new ConditionVariable();

    }

    /**
     * Starts playback and waits for it to finish.
     * Can be called from any thread.
     *
     * @return {@code true} if the playback finished normally, {@code false} if the playback
     *         failed or {@link #stop} was called before the playback finished.
     */
    public boolean startAndWait() {
        HandlerThread thread = new HandlerThread(MEDIA_PLAYER_THREAD_NAME);
        thread.start();
        Handler handler = new Handler(thread.getLooper());
        mFinished = false;
        handler.post(new Runnable() {
            @Override
            public void run() {
                startPlaying();
            }
        });
        mDone.block();
        handler.post(new Runnable() {
            @Override
            public void run() {
                finish();
                // No new messages should get posted to the handler thread after this
                Looper.myLooper().quit();
            }
        });
        return mFinished;
    }

    /**
     * Stops playback. Can be called multiple times.
     * Can be called from any thread.
     */
    public void stop() {
        mDone.open();
    }

    /**
     * Starts playback.
     * Called on the handler thread.
     */
    private void startPlaying() {
        mPlayer = MediaPlayer.create(mContext, mUri);
        if (mPlayer == null) {
            Log.w(TAG, "Failed to play " + mUri);
            mDone.open();
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
            mPlayer.setAudioStreamType(mStreamType);
            mPlayer.start();
        } catch (IllegalArgumentException ex) {
            Log.w(TAG, "MediaPlayer failed", ex);
            mDone.open();
        }
    }

    /**
     * Stops playback and release the media player.
     * Called on the handler thread.
     */
    private void finish() {
        try {
            mPlayer.stop();
        } catch (IllegalStateException ex) {
            // Do nothing, the player is already stopped
        }
        mPlayer.release();
    }

}