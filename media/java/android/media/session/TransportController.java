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
package android.media.session;

import android.media.Rating;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.util.Log;

import java.util.ArrayList;

/**
 * Interface for controlling media playback on a session. This allows an app to
 * request changes in playback, retrieve the current playback state and
 * metadata, and listen for changes to the playback state and metadata.
 */
public final class TransportController {
    private static final String TAG = "TransportController";

    private final Object mLock = new Object();
    private final ArrayList<MessageHandler> mListeners = new ArrayList<MessageHandler>();
    private final IMediaController mBinder;

    /**
     * @hide
     */
    public TransportController(IMediaController binder) {
        mBinder = binder;
    }

    /**
     * Start listening to changes in playback state.
     */
    public void addStateListener(TransportStateListener listener) {
        addStateListener(listener, null);
    }

    public void addStateListener(TransportStateListener listener, Handler handler) {
        if (listener == null) {
            throw new IllegalArgumentException("Listener cannot be null");
        }
        synchronized (mLock) {
            if (getHandlerForListenerLocked(listener) != null) {
                Log.w(TAG, "Listener is already added, ignoring");
                return;
            }
            if (handler == null) {
                handler = new Handler();
            }

            MessageHandler msgHandler = new MessageHandler(handler.getLooper(), listener);
            mListeners.add(msgHandler);
        }
    }

    /**
     * Stop listening to changes in playback state.
     */
    public void removeStateListener(TransportStateListener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("Listener cannot be null");
        }
        synchronized (mLock) {
            removeStateListenerLocked(listener);
        }
    }

    /**
     * Request that the player start its playback at its current position.
     */
    public void play() {
        try {
            mBinder.play();
        } catch (RemoteException e) {
            Log.wtf(TAG, "Error calling play.", e);
        }
    }

    /**
     * Request that the player pause its playback and stay at its current
     * position.
     */
    public void pause() {
        try {
            mBinder.pause();
        } catch (RemoteException e) {
            Log.wtf(TAG, "Error calling pause.", e);
        }
    }

    /**
     * Request that the player stop its playback; it may clear its state in
     * whatever way is appropriate.
     */
    public void stop() {
        try {
            mBinder.stop();
        } catch (RemoteException e) {
            Log.wtf(TAG, "Error calling stop.", e);
        }
    }

    /**
     * Move to a new location in the media stream.
     *
     * @param pos Position to move to, in milliseconds.
     */
    public void seekTo(long pos) {
        try {
            mBinder.seekTo(pos);
        } catch (RemoteException e) {
            Log.wtf(TAG, "Error calling seekTo.", e);
        }
    }

    /**
     * Start fast forwarding. If playback is already fast forwarding this may
     * increase the rate.
     */
    public void fastForward() {
        try {
            mBinder.fastForward();
        } catch (RemoteException e) {
            Log.wtf(TAG, "Error calling fastForward.", e);
        }
    }

    /**
     * Skip to the next item.
     */
    public void next() {
        try {
            mBinder.next();
        } catch (RemoteException e) {
            Log.wtf(TAG, "Error calling next.", e);
        }
    }

    /**
     * Start rewinding. If playback is already rewinding this may increase the
     * rate.
     */
    public void rewind() {
        try {
            mBinder.rewind();
        } catch (RemoteException e) {
            Log.wtf(TAG, "Error calling rewind.", e);
        }
    }

    /**
     * Skip to the previous item.
     */
    public void previous() {
        try {
            mBinder.previous();
        } catch (RemoteException e) {
            Log.wtf(TAG, "Error calling previous.", e);
        }
    }

    /**
     * Rate the current content. This will cause the rating to be set for the
     * current user. The Rating type must match the type returned by
     * {@link #getRatingType()}.
     *
     * @param rating The rating to set for the current content
     */
    public void rate(Rating rating) {
        try {
            mBinder.rate(rating);
        } catch (RemoteException e) {
            Log.wtf(TAG, "Error calling rate.", e);
        }
    }

    /**
     * Get the rating type supported by the session. One of:
     * <ul>
     * <li>{@link Rating#RATING_NONE}</li>
     * <li>{@link Rating#RATING_HEART}</li>
     * <li>{@link Rating#RATING_THUMB_UP_DOWN}</li>
     * <li>{@link Rating#RATING_3_STARS}</li>
     * <li>{@link Rating#RATING_4_STARS}</li>
     * <li>{@link Rating#RATING_5_STARS}</li>
     * <li>{@link Rating#RATING_PERCENTAGE}</li>
     * </ul>
     *
     * @return The supported rating type
     */
    public int getRatingType() {
        try {
            return mBinder.getRatingType();
        } catch (RemoteException e) {
            Log.wtf(TAG, "Error calling getRatingType.", e);
            return Rating.RATING_NONE;
        }
    }

    /**
     * Get the current playback state for this session.
     *
     * @return The current PlaybackState or null
     */
    public PlaybackState getPlaybackState() {
        try {
            return mBinder.getPlaybackState();
        } catch (RemoteException e) {
            Log.wtf(TAG, "Error calling getPlaybackState.", e);
            return null;
        }
    }

    /**
     * Get the current metadata for this session.
     *
     * @return The current MediaMetadata or null.
     */
    public MediaMetadata getMetadata() {
        try {
            return mBinder.getMetadata();
        } catch (RemoteException e) {
            Log.wtf(TAG, "Error calling getMetadata.", e);
            return null;
        }
    }

    /**
     * @hide
     */
    public final void postPlaybackStateChanged(PlaybackState state) {
        synchronized (mLock) {
            for (int i = mListeners.size() - 1; i >= 0; i--) {
                mListeners.get(i).post(MessageHandler.MSG_UPDATE_PLAYBACK_STATE, state);
            }
        }
    }

    /**
     * @hide
     */
    public final void postMetadataChanged(MediaMetadata metadata) {
        synchronized (mLock) {
            for (int i = mListeners.size() - 1; i >= 0; i--) {
                mListeners.get(i).post(MessageHandler.MSG_UPDATE_METADATA,
                        metadata);
            }
        }
    }

    private MessageHandler getHandlerForListenerLocked(TransportStateListener listener) {
        for (int i = mListeners.size() - 1; i >= 0; i--) {
            MessageHandler handler = mListeners.get(i);
            if (listener == handler.mListener) {
                return handler;
            }
        }
        return null;
    }

    private boolean removeStateListenerLocked(TransportStateListener listener) {
        for (int i = mListeners.size() - 1; i >= 0; i--) {
            if (listener == mListeners.get(i).mListener) {
                mListeners.remove(i);
                return true;
            }
        }
        return false;
    }

    /**
     * Register using {@link #addStateListener} to receive updates when there
     * are playback changes on the session.
     */
    public static abstract class TransportStateListener {
        private MessageHandler mHandler;
        /**
         * Override to handle changes in playback state.
         *
         * @param state The new playback state of the session
         */
        public void onPlaybackStateChanged(PlaybackState state) {
        }

        /**
         * Override to handle changes to the current metadata.
         *
         * @see MediaMetadata
         * @param metadata The current metadata for the session or null
         */
        public void onMetadataChanged(MediaMetadata metadata) {
        }

        private void setHandler(Handler handler) {
            mHandler = new MessageHandler(handler.getLooper(), this);
        }
    }

    private static class MessageHandler extends Handler {
        private static final int MSG_UPDATE_PLAYBACK_STATE = 1;
        private static final int MSG_UPDATE_METADATA = 2;

        private TransportStateListener mListener;

        public MessageHandler(Looper looper, TransportStateListener cb) {
            super(looper, null, true);
            mListener = cb;
        }

        public void post(int msg, Object obj) {
            obtainMessage(msg, obj).sendToTarget();
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_UPDATE_PLAYBACK_STATE:
                    mListener.onPlaybackStateChanged((PlaybackState) msg.obj);
                    break;
                case MSG_UPDATE_METADATA:
                    mListener.onMetadataChanged((MediaMetadata) msg.obj);
                    break;
            }
        }
    }

}
