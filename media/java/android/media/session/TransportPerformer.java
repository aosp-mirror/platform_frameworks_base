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

import android.media.AudioManager;
import android.media.Rating;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.util.Log;

import java.lang.ref.WeakReference;
import java.util.ArrayList;

/**
 * Allows broadcasting of playback changes.
 */
public final class TransportPerformer {
    private static final String TAG = "TransportPerformer";
    private final Object mLock = new Object();
    private final ArrayList<MessageHandler> mListeners = new ArrayList<MessageHandler>();

    private IMediaSession mBinder;

    /**
     * @hide
     */
    public TransportPerformer(IMediaSession binder) {
        mBinder = binder;
    }

    /**
     * Add a listener to receive updates on.
     *
     * @param listener The callback object
     */
    public void addListener(Listener listener) {
        addListener(listener, null);
    }

    /**
     * Add a listener to receive updates on. The updates will be posted to the
     * specified handler. If no handler is provided they will be posted to the
     * caller's thread.
     *
     * @param listener The listener to receive updates on
     * @param handler The handler to post the updates on
     */
    public void addListener(Listener listener, Handler handler) {
        if (listener == null) {
            throw new IllegalArgumentException("Listener cannot be null");
        }
        synchronized (mLock) {
            if (getHandlerForListenerLocked(listener) != null) {
                Log.w(TAG, "Listener is already added, ignoring");
            }
            if (handler == null) {
                handler = new Handler();
            }
            MessageHandler msgHandler = new MessageHandler(handler.getLooper(), listener);
            mListeners.add(msgHandler);
        }
    }

    /**
     * Stop receiving updates on the specified handler. If an update has already
     * been posted you may still receive it after this call returns.
     *
     * @param listener The listener to stop receiving updates on
     */
    public void removeListener(Listener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("Listener cannot be null");
        }
        synchronized (mLock) {
            removeListenerLocked(listener);
        }
    }

    /**
     * Update the current playback state.
     *
     * @param state The current state of playback
     */
    public final void setPlaybackState(PlaybackState state) {
        try {
            mBinder.setPlaybackState(state);
        } catch (RemoteException e) {
            Log.wtf(TAG, "Dead object in setPlaybackState.", e);
        }
    }

    /**
     * Update the current metadata. New metadata can be created using
     * {@link MediaMetadata.Builder}.
     *
     * @param metadata The new metadata
     */
    public final void setMetadata(MediaMetadata metadata) {
        try {
            mBinder.setMetadata(metadata);
        } catch (RemoteException e) {
            Log.wtf(TAG, "Dead object in setPlaybackState.", e);
        }
    }

    /**
     * @hide
     */
    public final void onPlay() {
        post(MessageHandler.MESSAGE_PLAY);
    }

    /**
     * @hide
     */
    public final void onPause() {
        post(MessageHandler.MESSAGE_PAUSE);
    }

    /**
     * @hide
     */
    public final void onStop() {
        post(MessageHandler.MESSAGE_STOP);
    }

    /**
     * @hide
     */
    public final void onNext() {
        post(MessageHandler.MESSAGE_NEXT);
    }

    /**
     * @hide
     */
    public final void onPrevious() {
        post(MessageHandler.MESSAGE_PREVIOUS);
    }

    /**
     * @hide
     */
    public final void onFastForward() {
        post(MessageHandler.MESSAGE_FAST_FORWARD);
    }

    /**
     * @hide
     */
    public final void onRewind() {
        post(MessageHandler.MESSAGE_REWIND);
    }

    /**
     * @hide
     */
    public final void onSeekTo(long pos) {
        post(MessageHandler.MESSAGE_SEEK_TO, pos);
    }

    /**
     * @hide
     */
    public final void onRate(Rating rating) {
        post(MessageHandler.MESSAGE_RATE, rating);
    }

    private MessageHandler getHandlerForListenerLocked(Listener listener) {
        for (int i = mListeners.size() - 1; i >= 0; i--) {
            MessageHandler handler = mListeners.get(i);
            if (listener == handler.mListener) {
                return handler;
            }
        }
        return null;
    }

    private boolean removeListenerLocked(Listener listener) {
        for (int i = mListeners.size() - 1; i >= 0; i--) {
            if (listener == mListeners.get(i).mListener) {
                mListeners.remove(i);
                return true;
            }
        }
        return false;
    }

    private void post(int what, Object obj) {
        synchronized (mLock) {
            for (int i = mListeners.size() - 1; i >= 0; i--) {
                mListeners.get(i).post(what, obj);
            }
        }
    }

    private void post(int what) {
        post(what, null);
    }

    /**
     * Extend Listener to handle transport controls. Listeners can be registered
     * using {@link #addListener}.
     */
    public static abstract class Listener {

        /**
         * Override to handle requests to begin playback.
         */
        public void onPlay() {
        }

        /**
         * Override to handle requests to pause playback.
         */
        public void onPause() {
        }

        /**
         * Override to handle requests to skip to the next media item.
         */
        public void onNext() {
        }

        /**
         * Override to handle requests to skip to the previous media item.
         */
        public void onPrevious() {
        }

        /**
         * Override to handle requests to fast forward.
         */
        public void onFastForward() {
        }

        /**
         * Override to handle requests to rewind.
         */
        public void onRewind() {
        }

        /**
         * Override to handle requests to stop playback.
         */
        public void onStop() {
        }

        /**
         * Override to handle requests to seek to a specific position in ms.
         *
         * @param pos New position to move to, in milliseconds.
         */
        public void onSeekTo(long pos) {
        }

        /**
         * Override to handle the item being rated.
         *
         * @param rating
         */
        public void onRate(Rating rating) {
        }

        /**
         * Report that audio focus has changed on the app. This only happens if
         * you have indicated you have started playing with
         * {@link #setPlaybackState}. TODO figure out route focus apis/handling.
         *
         * @param focusChange The type of focus change, TBD. The default
         *            implementation will deliver a call to {@link #onPause}
         *            when focus is lost.
         */
        public void onRouteFocusChange(int focusChange) {
            switch (focusChange) {
                case AudioManager.AUDIOFOCUS_LOSS:
                    onPause();
                    break;
            }
        }
    }

    private class MessageHandler extends Handler {
        private static final int MESSAGE_PLAY = 1;
        private static final int MESSAGE_PAUSE = 2;
        private static final int MESSAGE_STOP = 3;
        private static final int MESSAGE_NEXT = 4;
        private static final int MESSAGE_PREVIOUS = 5;
        private static final int MESSAGE_FAST_FORWARD = 6;
        private static final int MESSAGE_REWIND = 7;
        private static final int MESSAGE_SEEK_TO = 8;
        private static final int MESSAGE_RATE = 9;

        private Listener mListener;

        public MessageHandler(Looper looper, Listener cb) {
            super(looper);
            mListener = cb;
        }

        public void post(int what, Object obj) {
            obtainMessage(what, obj).sendToTarget();
        }

        public void post(int what) {
            post(what, null);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MESSAGE_PLAY:
                    mListener.onPlay();
                    break;
                case MESSAGE_PAUSE:
                    mListener.onPause();
                    break;
                case MESSAGE_STOP:
                    mListener.onStop();
                    break;
                case MESSAGE_NEXT:
                    mListener.onNext();
                    break;
                case MESSAGE_PREVIOUS:
                    mListener.onPrevious();
                    break;
                case MESSAGE_FAST_FORWARD:
                    mListener.onFastForward();
                    break;
                case MESSAGE_REWIND:
                    mListener.onRewind();
                    break;
                case MESSAGE_SEEK_TO:
                    mListener.onSeekTo((Long) msg.obj);
                    break;
                case MESSAGE_RATE:
                    mListener.onRate((Rating) msg.obj);
                    break;
            }
        }
    }
}
