/*
 * Copyright (C) 2012 The Android Open Source Project
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

import android.media.MediaPlayer;
import android.net.Uri;
import android.webkit.HTML5VideoViewProxy;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;

/**
 * @hide This is only used by the browser
 */
public class HTML5VideoView implements MediaPlayer.OnPreparedListener {

    protected static final String LOGTAG = "HTML5VideoView";

    protected static final String COOKIE = "Cookie";
    protected static final String HIDE_URL_LOGS = "x-hide-urls-from-log";

    // For handling the seekTo before prepared, we need to know whether or not
    // the video is prepared. Therefore, we differentiate the state between
    // prepared and not prepared.
    // When the video is not prepared, we will have to save the seekTo time,
    // and use it when prepared to play.
    // NOTE: these values are in sync with VideoLayerAndroid.h in webkit side.
    // Please keep them in sync when changed.
    static final int STATE_INITIALIZED        = 0;
    static final int STATE_PREPARING          = 1;
    static final int STATE_PREPARED           = 2;
    static final int STATE_PLAYING            = 3;
    static final int STATE_RESETTED           = 4;
    static final int STATE_RELEASED           = 5;

    protected HTML5VideoViewProxy mProxy;

    // Save the seek time when not prepared. This can happen when switching
    // video besides initial load.
    protected int mSaveSeekTime;

    // This is used to find the VideoLayer on the native side.
    protected int mVideoLayerId;

    // Given the fact we only have one SurfaceTexture, we cannot support multiple
    // player at the same time. We may recreate a new one and abandon the old
    // one at transition time.
    protected static MediaPlayer mPlayer = null;
    protected static int mCurrentState = -1;

    // We need to save such info.
    protected Uri mUri;
    protected Map<String, String> mHeaders;

    // The timer for timeupate events.
    // See http://www.whatwg.org/specs/web-apps/current-work/#event-media-timeupdate
    protected static Timer mTimer;

    protected boolean mPauseDuringPreparing;

    // The spec says the timer should fire every 250 ms or less.
    private static final int TIMEUPDATE_PERIOD = 250;  // ms
    private boolean mSkipPrepare = false;

    // common Video control FUNCTIONS:
    public void start() {
        if (mCurrentState == STATE_PREPARED) {
            // When replaying the same video, there is no onPrepared call.
            // Therefore, the timer should be set up here.
            if (mTimer == null)
            {
                mTimer = new Timer();
                mTimer.schedule(new TimeupdateTask(mProxy), TIMEUPDATE_PERIOD,
                        TIMEUPDATE_PERIOD);
            }
            mPlayer.start();
            setPlayerBuffering(false);
        }
    }

    public void pause() {
        if (isPlaying()) {
            mPlayer.pause();
        } else if (mCurrentState == STATE_PREPARING) {
            mPauseDuringPreparing = true;
        }
        // Delete the Timer to stop it since there is no stop call.
        if (mTimer != null) {
            mTimer.purge();
            mTimer.cancel();
            mTimer = null;
        }
    }

    public int getDuration() {
        if (mCurrentState == STATE_PREPARED) {
            return mPlayer.getDuration();
        } else {
            return -1;
        }
    }

    public int getCurrentPosition() {
        if (mCurrentState == STATE_PREPARED) {
            return mPlayer.getCurrentPosition();
        }
        return 0;
    }

    public void seekTo(int pos) {
        if (mCurrentState == STATE_PREPARED)
            mPlayer.seekTo(pos);
        else
            mSaveSeekTime = pos;
    }

    public boolean isPlaying() {
        if (mCurrentState == STATE_PREPARED) {
            return mPlayer.isPlaying();
        } else {
            return false;
        }
    }

    public void reset() {
        if (mCurrentState < STATE_RESETTED) {
            mPlayer.reset();
        }
        mCurrentState = STATE_RESETTED;
    }

    public void stopPlayback() {
        if (mCurrentState == STATE_PREPARED) {
            mPlayer.stop();
        }
    }

    public static void release() {
        if (mPlayer != null && mCurrentState != STATE_RELEASED) {
            mPlayer.release();
            mPlayer = null;
        }
        mCurrentState = STATE_RELEASED;
    }

    public boolean isReleased() {
        return mCurrentState == STATE_RELEASED;
    }

    public boolean getPauseDuringPreparing() {
        return mPauseDuringPreparing;
    }

    // Every time we start a new Video, we create a VideoView and a MediaPlayer
    public void init(int videoLayerId, int position, boolean skipPrepare) {
        if (mPlayer == null) {
            mPlayer = new MediaPlayer();
            mCurrentState = STATE_INITIALIZED;
        }
        mSkipPrepare = skipPrepare;
        // If we want to skip the prepare, then we keep the state.
        if (!mSkipPrepare) {
            mCurrentState = STATE_INITIALIZED;
        }
        mProxy = null;
        mVideoLayerId = videoLayerId;
        mSaveSeekTime = position;
        mTimer = null;
        mPauseDuringPreparing = false;
    }

    protected HTML5VideoView() {
    }

    protected static Map<String, String> generateHeaders(String url,
            HTML5VideoViewProxy proxy) {
        boolean isPrivate = proxy.getWebView().isPrivateBrowsingEnabled();
        String cookieValue = CookieManager.getInstance().getCookie(url, isPrivate);
        Map<String, String> headers = new HashMap<String, String>();
        if (cookieValue != null) {
            headers.put(COOKIE, cookieValue);
        }
        if (isPrivate) {
            headers.put(HIDE_URL_LOGS, "true");
        }

        return headers;
    }

    public void setVideoURI(String uri, HTML5VideoViewProxy proxy) {
        // When switching players, surface texture will be reused.
        mUri = Uri.parse(uri);
        mHeaders = generateHeaders(uri, proxy);
    }

    // Listeners setup FUNCTIONS:
    public void setOnCompletionListener(HTML5VideoViewProxy proxy) {
        mPlayer.setOnCompletionListener(proxy);
    }

    public void setOnErrorListener(HTML5VideoViewProxy proxy) {
        mPlayer.setOnErrorListener(proxy);
    }

    public void setOnPreparedListener(HTML5VideoViewProxy proxy) {
        mProxy = proxy;
        mPlayer.setOnPreparedListener(this);
    }

    public void setOnInfoListener(HTML5VideoViewProxy proxy) {
        mPlayer.setOnInfoListener(proxy);
    }

    public void prepareDataCommon(HTML5VideoViewProxy proxy) {
        if (!mSkipPrepare) {
            try {
                mPlayer.reset();
                mPlayer.setDataSource(proxy.getContext(), mUri, mHeaders);
                mPlayer.prepareAsync();
            } catch (IllegalArgumentException e) {
                e.printStackTrace();
            } catch (IllegalStateException e) {
                e.printStackTrace();
            } catch (IOException e) {
                e.printStackTrace();
            }
            mCurrentState = STATE_PREPARING;
        } else {
            // If we skip prepare and the onPrepared happened in inline mode, we
            // don't need to call prepare again, we just need to call onPrepared
            // to refresh the state here.
            if (mCurrentState >= STATE_PREPARED) {
                onPrepared(mPlayer);
            }
            mSkipPrepare = false;
        }
    }

    public void reprepareData(HTML5VideoViewProxy proxy) {
        mPlayer.reset();
        prepareDataCommon(proxy);
    }

    // Normally called immediately after setVideoURI. But for full screen,
    // this should be after surface holder created
    public void prepareDataAndDisplayMode(HTML5VideoViewProxy proxy) {
        // SurfaceTexture will be created lazily here for inline mode
        decideDisplayMode();

        setOnCompletionListener(proxy);
        setOnPreparedListener(proxy);
        setOnErrorListener(proxy);
        setOnInfoListener(proxy);

        prepareDataCommon(proxy);
    }


    // Common code
    public int getVideoLayerId() {
        return mVideoLayerId;
    }


    public int getCurrentState() {
        if (isPlaying()) {
            return STATE_PLAYING;
        } else {
            return mCurrentState;
        }
    }

    private static final class TimeupdateTask extends TimerTask {
        private HTML5VideoViewProxy mProxy;

        public TimeupdateTask(HTML5VideoViewProxy proxy) {
            mProxy = proxy;
        }

        @Override
        public void run() {
            mProxy.onTimeupdate();
        }
    }

    @Override
    public void onPrepared(MediaPlayer mp) {
        mCurrentState = STATE_PREPARED;
        seekTo(mSaveSeekTime);
        if (mProxy != null) {
            mProxy.onPrepared(mp);
        }
        if (mPauseDuringPreparing) {
            pauseAndDispatch(mProxy);
            mPauseDuringPreparing = false;
        }
    }

    // Pause the play and update the play/pause button
    public void pauseAndDispatch(HTML5VideoViewProxy proxy) {
        pause();
        if (proxy != null) {
            proxy.dispatchOnPaused();
        }
    }

    // Below are functions that are different implementation on inline and full-
    // screen mode. Some are specific to one type, but currently are called
    // directly from the proxy.
    public void enterFullScreenVideoState(int layerId,
            HTML5VideoViewProxy proxy, WebViewClassic webView) {
    }

    public boolean isFullScreenMode() {
        return false;
    }

    public void decideDisplayMode() {
    }

    public boolean getReadyToUseSurfTex() {
        return false;
    }

    public void deleteSurfaceTexture() {
    }

    public int getTextureName() {
        return 0;
    }

    // This is true only when the player is buffering and paused
    public boolean mPlayerBuffering = false;

    public boolean getPlayerBuffering() {
        return mPlayerBuffering;
    }

    public void setPlayerBuffering(boolean playerBuffering) {
        mPlayerBuffering = playerBuffering;
        switchProgressView(playerBuffering);
    }


    protected void switchProgressView(boolean playerBuffering) {
        // Only used in HTML5VideoFullScreen
    }

    public boolean fullScreenExited() {
        // Only meaningful for HTML5VideoFullScreen
        return false;
    }

    private boolean mStartWhenPrepared = false;

    public void setStartWhenPrepared(boolean willPlay) {
        mStartWhenPrepared  = willPlay;
    }

    public boolean getStartWhenPrepared() {
        return mStartWhenPrepared;
    }

    public void showControllerInFullScreen() {
    }

}
