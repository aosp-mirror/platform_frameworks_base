/*
 * Copyright (C) 2014 The Android Open Source Project
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
package com.android.onemedia.playback;

import org.apache.http.Header;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;

import android.content.Context;
import android.media.AudioManager;
import android.media.AudioManager.OnAudioFocusChangeListener;
import android.media.MediaPlayer;
import android.media.MediaPlayer.OnBufferingUpdateListener;
import android.media.MediaPlayer.OnCompletionListener;
import android.media.MediaPlayer.OnErrorListener;
import android.media.MediaPlayer.OnPreparedListener;
import android.net.Uri;
import android.net.http.AndroidHttpClient;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.SurfaceHolder;

import java.io.IOException;
import java.util.Map;

/**
 * Helper class for wrapping a MediaPlayer and doing a lot of the default work
 * to play audio. This class is not currently thread safe and all calls to it
 * should be made on the same thread.
 */
public class LocalRenderer extends Renderer implements OnPreparedListener,
        OnBufferingUpdateListener, OnCompletionListener, OnErrorListener,
        OnAudioFocusChangeListener {
    private static final String TAG = "MediaPlayerManager";
    private static final boolean DEBUG = true;
    private static long sDebugInstanceId = 0;

    private static final String[] SUPPORTED_FEATURES = {
            FEATURE_SET_CONTENT,
            FEATURE_SET_NEXT_CONTENT,
            FEATURE_PLAY,
            FEATURE_PAUSE,
            FEATURE_NEXT,
            FEATURE_PREVIOUS,
            FEATURE_SEEK_TO,
            FEATURE_STOP
    };

    /**
     * These are the states where it is valid to call play directly on the
     * MediaPlayer.
     */
    private static final int CAN_PLAY = STATE_READY | STATE_PAUSED | STATE_ENDED;
    /**
     * These are the states where we expect the MediaPlayer to be ready in the
     * future, so we can set a flag to start playing when it is.
     */
    private static final int CAN_READY_PLAY = STATE_INIT | STATE_PREPARING;
    /**
     * The states when it is valid to call pause on the MediaPlayer.
     */
    private static final int CAN_PAUSE = STATE_PLAYING;
    /**
     * The states where it is valid to call seek on the MediaPlayer.
     */
    private static final int CAN_SEEK = STATE_READY | STATE_PLAYING | STATE_PAUSED | STATE_ENDED;
    /**
     * The states where we expect the MediaPlayer to be ready in the future and
     * can store a seek position to set later.
     */
    private static final int CAN_READY_SEEK = STATE_INIT | STATE_PREPARING;
    /**
     * The states where it is valid to call stop on the MediaPlayer.
     */
    private static final int CAN_STOP = STATE_READY | STATE_PLAYING | STATE_PAUSED | STATE_ENDED;
    /**
     * The states where it is valid to get the current play position and the
     * duration from the MediaPlayer.
     */
    private static final int CAN_GET_POSITION = STATE_READY | STATE_PLAYING | STATE_PAUSED;



    private class PlayerContent {
        public final String source;
        public final Map<String, String> headers;

        public PlayerContent(String source, Map<String, String> headers) {
            this.source = source;
            this.headers = headers;
        }
    }

    private class AsyncErrorRetriever extends AsyncTask<HttpGet, Void, Void> {
        private final long errorId;
        private boolean closeHttpClient;

        public AsyncErrorRetriever(long errorId) {
            this.errorId = errorId;
            closeHttpClient = false;
        }

        public boolean cancelRequestLocked(boolean closeHttp) {
            closeHttpClient = closeHttp;
            return this.cancel(false);
        }

        @Override
        protected Void doInBackground(HttpGet[] params) {
            synchronized (mErrorLock) {
                if (isCancelled() || mHttpClient == null) {
                    if (mErrorRetriever == this) {
                        mErrorRetriever = null;
                    }
                    return null;
                }
                mSafeToCloseClient = false;
            }
            final PlaybackError error = new PlaybackError();
            try {
                HttpResponse response = mHttpClient.execute(params[0]);
                synchronized (mErrorLock) {
                    if (mErrorId != errorId || mError == null) {
                        // A new error has occurred, abort
                        return null;
                    }
                    error.type = mError.type;
                    error.extra = mError.extra;
                    error.errorMessage = mError.errorMessage;
                }
                final int code = response.getStatusLine().getStatusCode();
                if (code >= 300) {
                    error.extra = code;
                }
                final Bundle errorExtras = new Bundle();
                Header[] headers = response.getAllHeaders();
                if (headers != null && headers.length > 0) {
                    for (Header header : headers) {
                        errorExtras.putString(header.getName(), header.getValue());
                    }
                    error.errorExtras = errorExtras;
                }
            } catch (IOException e) {
                Log.e(TAG, "IOException requesting from server, unable to get more exact error");
            } finally {
                synchronized (mErrorLock) {
                    mSafeToCloseClient = true;
                    if (mErrorRetriever == this) {
                        mErrorRetriever = null;
                    }
                    if (isCancelled()) {
                        if (closeHttpClient) {
                            mHttpClient.close();
                            mHttpClient = null;
                        }
                        return null;
                    }
                }
            }
            mHandler.post(new Runnable() {
                    @Override
                public void run() {
                    synchronized (mErrorLock) {
                        if (mErrorId == errorId) {
                            setError(error.type, error.extra, error.errorExtras, null);
                        }
                    }
                }
            });
            return null;
        }
    }

    private int mState = STATE_INIT;

    private AudioManager mAudioManager;
    private MediaPlayer mPlayer;
    private PlayerContent mContent;
    private MediaPlayer mNextPlayer;
    private PlayerContent mNextContent;
    private SurfaceHolder mHolder;
    private SurfaceHolder.Callback mHolderCB;
    private Context mContext;

    private Handler mHandler = new Handler();

    private AndroidHttpClient mHttpClient = AndroidHttpClient.newInstance("TUQ");
    // The ongoing error request thread if there is one. This should only be
    // modified while mErrorLock is held.
    private AsyncErrorRetriever mErrorRetriever;
    // This is set to false while a server request is being made to retrieve
    // the current error. It should only be set while mErrorLock is held.
    private boolean mSafeToCloseClient = true;
    private final Object mErrorLock = new Object();
    // A tracking id for the current error. This should only be modified while
    // mErrorLock is held.
    private long mErrorId = 0;
    // The current error state of this player. This is cleared when the state
    // leaves an error state and set when it enters one. This should only be
    // modified when mErrorLock is held.
    private PlaybackError mError;

    private boolean mPlayOnReady;
    private int mSeekOnReady;
    private boolean mHasAudioFocus;
    private long mDebugId = sDebugInstanceId++;

    public LocalRenderer(Context context, Bundle params) {
        super(context, params);
        mContext = context;
        mAudioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
    }

    @Override
    protected void initFeatures(Bundle params) {
        for (String feature : SUPPORTED_FEATURES) {
            mFeatures.add(feature);
        }
    }

    /**
     * Call this when completely finished with the MediaPlayerManager to have it
     * clean up. The instance may not be used again after this is called.
     */
    @Override
    public void onDestroy() {
        synchronized (mErrorLock) {
            if (DEBUG) {
                Log.d(TAG, "onDestroy, error retriever? " + mErrorRetriever + " safe to close? "
                        + mSafeToCloseClient + " client? " + mHttpClient);
            }
            if (mErrorRetriever != null) {
                mErrorRetriever.cancelRequestLocked(true);
                mErrorRetriever = null;
            }
            // Increment the error id to ensure no errors are sent after this
            // point.
            mErrorId++;
            if (mSafeToCloseClient) {
                mHttpClient.close();
                mHttpClient = null;
            }
        }
    }

    @Override
    public void onPrepared(MediaPlayer player) {
        if (!isCurrentPlayer(player)) {
            return;
        }
        setState(STATE_READY);
        if (DEBUG) {
            Log.d(TAG, mDebugId + ": Finished preparing, seekOnReady is " + mSeekOnReady);
        }
        if (mSeekOnReady >= 0) {
            onSeekTo(mSeekOnReady);
            mSeekOnReady = -1;
        }
        if (mPlayOnReady) {
            player.start();
            setState(STATE_PLAYING);
        }
    }

    @Override
    public void onBufferingUpdate(MediaPlayer player, int percent) {
        if (!isCurrentPlayer(player)) {
            return;
        }
        pushOnBufferingUpdate(percent);
    }

    @Override
    public void onCompletion(MediaPlayer player) {
        if (!isCurrentPlayer(player)) {
            return;
        }
        if (DEBUG) {
            Log.d(TAG, mDebugId + ": Completed item. Have next item? " + (mNextPlayer != null));
        }
        if (mNextPlayer != null) {
            if (mPlayer != null) {
                mPlayer.release();
            }
            mPlayer = mNextPlayer;
            mContent = mNextContent;
            mNextPlayer = null;
            mNextContent = null;
            pushOnNextStarted();
            return;
        }
        setState(STATE_ENDED);
    }

    @Override
    public boolean onError(MediaPlayer player, int what, int extra) {
        if (!isCurrentPlayer(player)) {
            return false;
        }
        if (DEBUG) {
            Log.d(TAG, mDebugId + ": Entered error state, what: " + what + " extra: " + extra);
        }
        synchronized (mErrorLock) {
            ++mErrorId;
            mError = new PlaybackError();
            mError.type = what;
            mError.extra = extra;
        }

        if (what == MediaPlayer.MEDIA_ERROR_UNKNOWN && extra == MediaPlayer.MEDIA_ERROR_IO
                && mContent != null && mContent.source.startsWith("http")) {
            HttpGet request = new HttpGet(mContent.source);
            if (mContent.headers != null) {
                for (String key : mContent.headers.keySet()) {
                    request.addHeader(key, mContent.headers.get(key));
                }
            }
            synchronized (mErrorLock) {
                if (mErrorRetriever != null) {
                    mErrorRetriever.cancelRequestLocked(false);
                }
                mErrorRetriever = new AsyncErrorRetriever(mErrorId);
                mErrorRetriever.execute(request);
            }
        } else {
            setError(what, extra, null, null);
        }
        return true;
    }

    @Override
    public void onAudioFocusChange(int focusChange) {
        // TODO figure out appropriate logic for handling focus loss at the TUQ
        // level.
        switch (focusChange) {
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                if (mState == STATE_PLAYING) {
                    onPause();
                    mPlayOnReady = true;
                }
                mHasAudioFocus = false;
                break;
            case AudioManager.AUDIOFOCUS_LOSS:
                if (mState == STATE_PLAYING) {
                    onPause();
                    mPlayOnReady = false;
                }
                pushOnFocusLost();
                mHasAudioFocus = false;
                break;
            case AudioManager.AUDIOFOCUS_GAIN:
                mHasAudioFocus = true;
                if (mPlayOnReady) {
                    onPlay();
                }
                break;
            default:
                Log.d(TAG, "Unknown focus change event " + focusChange);
                break;
        }
    }

    @Override
    public void setContent(Bundle request) {
        setContent(request, null);
    }

    /**
     * Prepares the player for the given playback request. If the holder is null
     * it is assumed this is an audio only source. If playOnReady is set to true
     * the media will begin playing as soon as it can.
     *
     * @see RequestUtils for the set of valid keys.
     */
    public void setContent(Bundle request, SurfaceHolder holder) {
        String source = request.getString(RequestUtils.EXTRA_KEY_SOURCE);
        Map<String, String> headers = null; // request.mHeaders;
        boolean playOnReady = true; // request.mPlayOnReady;
        if (DEBUG) {
            Log.d(TAG, mDebugId + ": Settings new content. Have a player? " + (mPlayer != null)
                    + " have a next player? " + (mNextPlayer != null));
        }
        cleanUpPlayer();
        setState(STATE_PREPARING);
        mPlayOnReady = playOnReady;
        mSeekOnReady = -1;
        final MediaPlayer newPlayer = new MediaPlayer();

        requestAudioFocus();

        mPlayer = newPlayer;
        mContent = new PlayerContent(source, headers);
        try {
            if (headers != null) {
                Uri sourceUri = Uri.parse(source);
                newPlayer.setDataSource(mContext, sourceUri, headers);
            } else {
                newPlayer.setDataSource(source);
            }
        } catch (Exception e) {
            setError(Listener.ERROR_LOAD_FAILED, 0, null, e);
            return;
        }
        if (isHolderReady(holder, newPlayer)) {
            preparePlayer(newPlayer, true);
        }
    }

    @Override
    public void setNextContent(Bundle request) {
        String source = request.getString(RequestUtils.EXTRA_KEY_SOURCE);
        Map<String, String> headers = null; // request.mHeaders;

        // TODO support video

        if (DEBUG) {
            Log.d(TAG, mDebugId + ": Setting next content. Have player? " + (mPlayer != null)
                    + " have next player? " + (mNextPlayer != null));
        }

        if (mPlayer == null) {
            // The manager isn't being used to play anything, don't try to
            // set a next.
            return;
        }
        if (mNextPlayer != null) {
            // Before setting up the new one clear out the old one and release
            // it to ensure it doesn't play.
            mPlayer.setNextMediaPlayer(null);
            mNextPlayer.release();
            mNextPlayer = null;
            mNextContent = null;
        }
        if (source == null) {
            // If there's no new content we're done
            return;
        }
        final MediaPlayer newPlayer = new MediaPlayer();

        try {
            if (headers != null) {
                Uri sourceUri = Uri.parse(source);
                newPlayer.setDataSource(mContext, sourceUri, headers);
            } else {
                newPlayer.setDataSource(source);
            }
        } catch (Exception e) {
            newPlayer.release();
            // Don't return an error until we get to this item in playback
            return;
        }

        if (preparePlayer(newPlayer, false)) {
            mPlayer.setNextMediaPlayer(newPlayer);
            mNextPlayer = newPlayer;
            mNextContent = new PlayerContent(source, headers);
        }
    }

    private void requestAudioFocus() {
        int result = mAudioManager.requestAudioFocus(this, AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN);
        mHasAudioFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }

    /**
     * Start the player if possible or queue it to play when ready. If the
     * player is in a state where it will never be ready returns false.
     *
     * @return true if the content was started or will be started later
     */
    @Override
    public boolean onPlay() {
        MediaPlayer player = mPlayer;
        if (player != null && mState == STATE_PLAYING) {
            // already playing, just return
            return true;
        }
        if (!mHasAudioFocus) {
            requestAudioFocus();
        }
        if (player != null && canPlay()) {
            player.start();
            setState(STATE_PLAYING);
        } else if (canReadyPlay()) {
            mPlayOnReady = true;
        } else if (!isPlaying()) {
            return false;
        }
        return true;
    }

    /**
     * Pause the player if possible or set it to not play when ready. If the
     * player is in a state where it will never be ready returns false.
     *
     * @return true if the content was paused or will wait to play when ready
     *         later
     */
    @Override
    public boolean onPause() {
        MediaPlayer player = mPlayer;
        // If the user paused us make sure we won't start playing again until
        // asked to
        mPlayOnReady = false;
        if (player != null && (mState & CAN_PAUSE) != 0) {
            player.pause();
            setState(STATE_PAUSED);
        } else if (!isPaused()) {
            return false;
        }
        return true;
    }

    /**
     * Seek to a given position in the media. If the seek succeeded or will be
     * performed when loading is complete returns true. If the position is not
     * in range or the player will never be ready returns false.
     *
     * @param position The position to seek to in milliseconds
     * @return true if playback was moved or will be moved when ready
     */
    @Override
    public boolean onSeekTo(int position) {
        MediaPlayer player = mPlayer;
        if (player != null && (mState & CAN_SEEK) != 0) {
            if (position < 0 || position >= getDuration()) {
                return false;
            } else {
                if (mState == STATE_ENDED) {
                    player.start();
                    player.pause();
                    setState(STATE_PAUSED);
                }
                player.seekTo(position);
            }
        } else if ((mState & CAN_READY_SEEK) != 0) {
            mSeekOnReady = position;
        } else {
            return false;
        }
        return true;
    }

    /**
     * Stop the player. It cannot be used again until
     * {@link #setContent(String, boolean)} is called.
     *
     * @return true if stopping the player succeeded
     */
    @Override
    public boolean onStop() {
        cleanUpPlayer();
        setState(STATE_STOPPED);
        return true;
    }

    public boolean isPlaying() {
        return mState == STATE_PLAYING;
    }

    public boolean isPaused() {
        return mState == STATE_PAUSED;
    }

    @Override
    public long getSeekPosition() {
        return ((mState & CAN_GET_POSITION) == 0) ? -1 : mPlayer.getCurrentPosition();
    }

    @Override
    public long getDuration() {
        return ((mState & CAN_GET_POSITION) == 0) ? -1 : mPlayer.getDuration();
    }

    private boolean canPlay() {
        return ((mState & CAN_PLAY) != 0) && mHasAudioFocus;
    }

    private boolean canReadyPlay() {
        return (mState & CAN_PLAY) != 0 || (mState & CAN_READY_PLAY) != 0;
    }

    /**
     * Sends a state update if the listener exists
     */
    private void setState(int state) {
        if (state == mState) {
            return;
        }
        Log.d(TAG, "Entering state " + state + " from state " + mState);
        mState = state;
        if (state != STATE_ERROR) {
            // Don't notify error here, it'll get sent via onError
            pushOnStateChanged(state);
        }
    }

    private boolean preparePlayer(final MediaPlayer player, boolean current) {
        player.setOnPreparedListener(this);
        player.setOnBufferingUpdateListener(this);
        player.setOnCompletionListener(this);
        player.setOnErrorListener(this);
        try {
            player.prepareAsync();
            if (current) {
                setState(STATE_PREPARING);
            }
        } catch (IllegalStateException e) {
            if (current) {
                setError(Listener.ERROR_PREPARE_ERROR, 0, null, e);
            }
            return false;
        }
        return true;
    }

    /**
     * @param extra
     * @param e
     */
    private void setError(int type, int extra, Bundle extras, Exception e) {
        setState(STATE_ERROR);
        pushOnError(type, extra, extras, e);
        cleanUpPlayer();
        return;
    }

    /**
     * Checks if the holder is ready and either sets up a callback to wait for
     * it or sets it directly. If
     *
     * @param holder
     * @param player
     * @return
     */
    private boolean isHolderReady(final SurfaceHolder holder, final MediaPlayer player) {
        mHolder = holder;
        if (holder != null) {
            if (holder.getSurface() != null && holder.getSurface().isValid()) {
                player.setDisplay(holder);
                return true;
            } else {
                Log.w(TAG, "Holder not null, waiting for it to be ready");
                // If the holder isn't ready yet add a callback to set the
                // holder when it's ready.
                SurfaceHolder.Callback cb = new SurfaceHolder.Callback() {
                        @Override
                    public void surfaceDestroyed(SurfaceHolder arg0) {
                    }

                        @Override
                    public void surfaceCreated(SurfaceHolder arg0) {
                        if (player.equals(mPlayer)) {
                            player.setDisplay(arg0);
                            preparePlayer(player, true);
                        }
                    }

                        @Override
                    public void surfaceChanged(SurfaceHolder arg0, int arg1, int arg2, int arg3) {
                    }
                };
                mHolderCB = cb;
                holder.addCallback(cb);
                return false;
            }
        }
        return true;
    }

    private void cleanUpPlayer() {
        if (DEBUG) {
            Log.d(TAG, mDebugId + ": Cleaning up current player");
        }
        synchronized (mErrorLock) {
            mError = null;
            if (mErrorRetriever != null) {
                mErrorRetriever.cancelRequestLocked(false);
                // Don't set to null as we may need to cancel again with true if
                // the object gets destroyed.
            }
        }
        mAudioManager.abandonAudioFocus(this);

        SurfaceHolder.Callback cb = mHolderCB;
        mHolderCB = null;
        SurfaceHolder holder = mHolder;
        mHolder = null;
        if (holder != null && cb != null) {
            holder.removeCallback(cb);
        }

        MediaPlayer player = mPlayer;
        mPlayer = null;
        if (player != null) {
            player.reset();
            player.release();
        }
    }

    private boolean isCurrentPlayer(MediaPlayer player) {
        return player.equals(mPlayer);
    }
}
