/*
 * Copyright (C) 2010 The Android Open Source Project
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

package android.webkit;

import android.content.Context;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;

/**
 * HTML5 support class for Audio.
 *
 * This class runs almost entirely on the WebCore thread. The exception is when
 * accessing the WebView object to determine whether private browsing is
 * enabled.
 */
class HTML5Audio extends Handler
                 implements MediaPlayer.OnBufferingUpdateListener,
                            MediaPlayer.OnCompletionListener,
                            MediaPlayer.OnErrorListener,
                            MediaPlayer.OnPreparedListener,
                            MediaPlayer.OnSeekCompleteListener,
                            AudioManager.OnAudioFocusChangeListener {
    // Logging tag.
    private static final String LOGTAG = "HTML5Audio";

    private MediaPlayer mMediaPlayer;

    // The C++ MediaPlayerPrivateAndroid object.
    private int mNativePointer;
    // The private status of the view that created this player
    private IsPrivateBrowsingEnabledGetter mIsPrivateBrowsingEnabledGetter;

    private static int IDLE                =  0;
    private static int INITIALIZED         =  1;
    private static int PREPARED            =  2;
    private static int STARTED             =  4;
    private static int COMPLETE            =  5;
    private static int PAUSED              =  6;
    private static int PAUSED_TRANSITORILY =  7;
    private static int STOPPED             = -2;
    private static int ERROR               = -1;

    private int mState = IDLE;

    private String mUrl;
    private boolean mAskToPlay = false;
    private boolean mLoopEnabled = false;
    private boolean mProcessingOnEnd = false;
    private Context mContext;

    // Timer thread -> UI thread
    private static final int TIMEUPDATE = 100;

    private static final String COOKIE = "Cookie";
    private static final String HIDE_URL_LOGS = "x-hide-urls-from-log";

    // The spec says the timer should fire every 250 ms or less.
    private static final int TIMEUPDATE_PERIOD = 250;  // ms
    // The timer for timeupate events.
    // See http://www.whatwg.org/specs/web-apps/current-work/#event-media-timeupdate
    private Timer mTimer;
    private final class TimeupdateTask extends TimerTask {
        @Override
        public void run() {
            HTML5Audio.this.obtainMessage(TIMEUPDATE).sendToTarget();
        }
    }

    // Helper class to determine whether private browsing is enabled in the
    // given WebView. Queries the WebView on the UI thread. Calls to get()
    // block until the data is available.
    private class IsPrivateBrowsingEnabledGetter {
        private boolean mIsReady;
        private boolean mIsPrivateBrowsingEnabled;
        IsPrivateBrowsingEnabledGetter(Looper uiThreadLooper, final WebViewClassic webView) {
            new Handler(uiThreadLooper).post(new Runnable() {
                @Override
                public void run() {
                    synchronized(IsPrivateBrowsingEnabledGetter.this) {
                        mIsPrivateBrowsingEnabled = webView.isPrivateBrowsingEnabled();
                        mIsReady = true;
                        IsPrivateBrowsingEnabledGetter.this.notify();
                    }
                }
            });
        }
        synchronized boolean get() {
            while (!mIsReady) {
                try {
                    wait();
                } catch (InterruptedException e) {
                }
            }
            return mIsPrivateBrowsingEnabled;
        }
    };

    @Override
    public void handleMessage(Message msg) {
        switch (msg.what) {
            case TIMEUPDATE: {
                try {
                    if (mState != ERROR && mMediaPlayer.isPlaying()) {
                        int position = mMediaPlayer.getCurrentPosition();
                        nativeOnTimeupdate(position, mNativePointer);
                    }
                } catch (IllegalStateException e) {
                    mState = ERROR;
                }
            }
        }
    }

    // event listeners for MediaPlayer
    // Those are called from the same thread we created the MediaPlayer
    // (i.e. the webviewcore thread here)

    // MediaPlayer.OnBufferingUpdateListener
    @Override
    public void onBufferingUpdate(MediaPlayer mp, int percent) {
        nativeOnBuffering(percent, mNativePointer);
    }

    // MediaPlayer.OnCompletionListener;
    @Override
    public void onCompletion(MediaPlayer mp) {
        mState = COMPLETE;
        mProcessingOnEnd = true;
        nativeOnEnded(mNativePointer);
        mProcessingOnEnd = false;
        if (mLoopEnabled == true) {
            nativeOnRequestPlay(mNativePointer);
            mLoopEnabled = false;
        }
    }

    // MediaPlayer.OnErrorListener
    @Override
    public boolean onError(MediaPlayer mp, int what, int extra) {
        mState = ERROR;
        resetMediaPlayer();
        mState = IDLE;
        return false;
    }

    // MediaPlayer.OnPreparedListener
    @Override
    public void onPrepared(MediaPlayer mp) {
        mState = PREPARED;
        if (mTimer != null) {
            mTimer.schedule(new TimeupdateTask(),
                            TIMEUPDATE_PERIOD, TIMEUPDATE_PERIOD);
        }
        nativeOnPrepared(mp.getDuration(), 0, 0, mNativePointer);
        if (mAskToPlay) {
            mAskToPlay = false;
            play();
        }
    }

    // MediaPlayer.OnSeekCompleteListener
    @Override
    public void onSeekComplete(MediaPlayer mp) {
        nativeOnTimeupdate(mp.getCurrentPosition(), mNativePointer);
    }


    /**
     * @param nativePtr is the C++ pointer to the MediaPlayerPrivate object.
     */
    public HTML5Audio(WebViewCore webViewCore, int nativePtr) {
        // Save the native ptr
        mNativePointer = nativePtr;
        resetMediaPlayer();
        mContext = webViewCore.getContext();
        mIsPrivateBrowsingEnabledGetter = new IsPrivateBrowsingEnabledGetter(
                webViewCore.getContext().getMainLooper(), webViewCore.getWebViewClassic());
    }

    private void resetMediaPlayer() {
        if (mMediaPlayer == null) {
            mMediaPlayer = new MediaPlayer();
        } else {
            mMediaPlayer.reset();
        }
        mMediaPlayer.setOnBufferingUpdateListener(this);
        mMediaPlayer.setOnCompletionListener(this);
        mMediaPlayer.setOnErrorListener(this);
        mMediaPlayer.setOnPreparedListener(this);
        mMediaPlayer.setOnSeekCompleteListener(this);

        if (mTimer != null) {
            mTimer.cancel();
        }
        mTimer = new Timer();
        mState = IDLE;
    }

    private void setDataSource(String url) {
        mUrl = url;
        try {
            if (mState != IDLE) {
                resetMediaPlayer();
            }
            String cookieValue = CookieManager.getInstance().getCookie(
                    url, mIsPrivateBrowsingEnabledGetter.get());
            Map<String, String> headers = new HashMap<String, String>();

            if (cookieValue != null) {
                headers.put(COOKIE, cookieValue);
            }
            if (mIsPrivateBrowsingEnabledGetter.get()) {
                headers.put(HIDE_URL_LOGS, "true");
            }

            mMediaPlayer.setDataSource(mContext, Uri.parse(url), headers);
            mState = INITIALIZED;
            mMediaPlayer.prepareAsync();
        } catch (IOException e) {
            String debugUrl = url.length() > 128 ? url.substring(0, 128) + "..." : url;
            Log.e(LOGTAG, "couldn't load the resource: "+ debugUrl +" exc: " + e);
            resetMediaPlayer();
        }
    }

    @Override
    public void onAudioFocusChange(int focusChange) {
        switch (focusChange) {
        case AudioManager.AUDIOFOCUS_GAIN:
            // resume playback
            if (mMediaPlayer == null) {
                resetMediaPlayer();
            } else if (mState == PAUSED_TRANSITORILY && !mMediaPlayer.isPlaying()) {
                mMediaPlayer.start();
                mState = STARTED;
            }
            break;

        case AudioManager.AUDIOFOCUS_LOSS:
            // Lost focus for an unbounded amount of time: stop playback.
            if (mState != ERROR && mMediaPlayer.isPlaying()) {
                mMediaPlayer.stop();
                mState = STOPPED;
            }
            break;

        case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
        case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
            // Lost focus for a short time, but we have to stop
            // playback.
            if (mState != ERROR && mMediaPlayer.isPlaying()) {
                pause(PAUSED_TRANSITORILY);
            }
            break;
        }
    }


    private void play() {
        if (mState == COMPLETE && mLoopEnabled == true) {
            // Play it again, Sam
            mMediaPlayer.start();
            mState = STARTED;
            return;
        }

        if (((mState >= ERROR && mState < PREPARED)) && mUrl != null) {
            resetMediaPlayer();
            setDataSource(mUrl);
            mAskToPlay = true;
        }

        if (mState >= PREPARED) {
            AudioManager audioManager = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
            int result = audioManager.requestAudioFocus(this, AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN);

            if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                mMediaPlayer.start();
                mState = STARTED;
            }
        }
    }

    private void pause() {
        pause(PAUSED);
    }

    private void pause(int state) {
        if (mState == STARTED) {
            if (mTimer != null) {
                mTimer.purge();
            }
            mMediaPlayer.pause();
            mState = state;
        }
    }

    private void seek(int msec) {
        if (mProcessingOnEnd == true && mState == COMPLETE && msec == 0) {
            mLoopEnabled = true;
        }
        if (mState >= PREPARED) {
            mMediaPlayer.seekTo(msec);
        }
    }

    /**
     * Called only over JNI when WebKit is happy to
     * destroy the media player.
     */
    private void teardown() {
        mMediaPlayer.release();
        mMediaPlayer = null;
        mState = ERROR;
        mNativePointer = 0;
    }

    private float getMaxTimeSeekable() {
        if (mState >= PREPARED) {
            return mMediaPlayer.getDuration() / 1000.0f;
        } else {
            return 0;
        }
    }

    private native void nativeOnBuffering(int percent, int nativePointer);
    private native void nativeOnEnded(int nativePointer);
    private native void nativeOnRequestPlay(int nativePointer);
    private native void nativeOnPrepared(int duration, int width, int height, int nativePointer);
    private native void nativeOnTimeupdate(int position, int nativePointer);

}
