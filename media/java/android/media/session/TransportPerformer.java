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
import android.media.MediaMetadata;
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
    private final ArrayList<MessageHandler> mCallbacks = new ArrayList<MessageHandler>();

    private ISession mBinder;

    /**
     * @hide
     */
    public TransportPerformer(ISession binder) {
        mBinder = binder;
    }

    /**
     * Add a callback to receive updates on.
     *
     * @param callback The callback object
     */
    public void addCallback(Callback callback) {
        addCallback(callback, null);
    }

    /**
     * Add a callback to receive updates on. The updates will be posted to the
     * specified handler. If no handler is provided they will be posted to the
     * caller's thread.
     *
     * @param callback The callback to receive updates on
     * @param handler The handler to post the updates on
     */
    public void addCallback(Callback callback, Handler handler) {
        if (callback == null) {
            throw new IllegalArgumentException("Callback cannot be null");
        }
        synchronized (mLock) {
            if (getHandlerForCallbackLocked(callback) != null) {
                Log.w(TAG, "Callback is already added, ignoring");
            }
            if (handler == null) {
                handler = new Handler();
            }
            MessageHandler msgHandler = new MessageHandler(handler.getLooper(), callback);
            mCallbacks.add(msgHandler);
        }
    }

    /**
     * Stop receiving updates on the specified handler. If an update has already
     * been posted you may still receive it after this call returns.
     *
     * @param callback The callback to stop receiving updates on
     */
    public void removeCallback(Callback callback) {
        if (callback == null) {
            throw new IllegalArgumentException("Callback cannot be null");
        }
        synchronized (mLock) {
            removeCallbackLocked(callback);
        }
    }

    /**
     * Update the current playback state.
     *
     * @param state The current state of playback
     */
    public void setPlaybackState(PlaybackState state) {
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
    public void setMetadata(MediaMetadata metadata) {
        try {
            mBinder.setMetadata(metadata);
        } catch (RemoteException e) {
            Log.wtf(TAG, "Dead object in setPlaybackState.", e);
        }
    }

    /**
     * @hide
     */
    public void dispatchPlay() {
        post(MessageHandler.MESSAGE_PLAY);
    }

    /**
     * @hide
     */
    public void dispatchPause() {
        post(MessageHandler.MESSAGE_PAUSE);
    }

    /**
     * @hide
     */
    public void dispatchStop() {
        post(MessageHandler.MESSAGE_STOP);
    }

    /**
     * @hide
     */
    public void dispatchNext() {
        post(MessageHandler.MESSAGE_NEXT);
    }

    /**
     * @hide
     */
    public void dispatchPrevious() {
        post(MessageHandler.MESSAGE_PREVIOUS);
    }

    /**
     * @hide
     */
    public void dispatchFastForward() {
        post(MessageHandler.MESSAGE_FAST_FORWARD);
    }

    /**
     * @hide
     */
    public void dispatchRewind() {
        post(MessageHandler.MESSAGE_REWIND);
    }

    /**
     * @hide
     */
    public void dispatchSeekTo(long pos) {
        post(MessageHandler.MESSAGE_SEEK_TO, pos);
    }

    /**
     * @hide
     */
    public void dispatchRate(Rating rating) {
        post(MessageHandler.MESSAGE_RATE, rating);
    }

    private MessageHandler getHandlerForCallbackLocked(Callback callback) {
        for (int i = mCallbacks.size() - 1; i >= 0; i--) {
            MessageHandler handler = mCallbacks.get(i);
            if (callback == handler.mCallback) {
                return handler;
            }
        }
        return null;
    }

    private boolean removeCallbackLocked(Callback callback) {
        for (int i = mCallbacks.size() - 1; i >= 0; i--) {
            if (callback == mCallbacks.get(i).mCallback) {
                mCallbacks.remove(i);
                return true;
            }
        }
        return false;
    }

    private void post(int what, Object obj) {
        synchronized (mLock) {
            for (int i = mCallbacks.size() - 1; i >= 0; i--) {
                mCallbacks.get(i).post(what, obj);
            }
        }
    }

    private void post(int what) {
        post(what, null);
    }

    /**
     * Extend to handle transport controls. Callbacks can be registered using
     * {@link #addCallback}.
     */
    public static abstract class Callback {

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
        public void onSkipToNext() {
        }

        /**
         * Override to handle requests to skip to the previous media item.
         */
        public void onSkipToPrevious() {
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
        public void onSetRating(Rating rating) {
        }

        /**
         * Report that audio focus has changed on the app. This only happens if
         * you have indicated you have started playing with
         * {@link #setPlaybackState}.
         *
         * @param focusChange The type of focus change, TBD.
         * @hide
         */
        public void onRouteFocusChange(int focusChange) {
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

        private TransportPerformer.Callback mCallback;

        public MessageHandler(Looper looper, TransportPerformer.Callback cb) {
            super(looper);
            mCallback = cb;
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
                    mCallback.onPlay();
                    break;
                case MESSAGE_PAUSE:
                    mCallback.onPause();
                    break;
                case MESSAGE_STOP:
                    mCallback.onStop();
                    break;
                case MESSAGE_NEXT:
                    mCallback.onSkipToNext();
                    break;
                case MESSAGE_PREVIOUS:
                    mCallback.onSkipToPrevious();
                    break;
                case MESSAGE_FAST_FORWARD:
                    mCallback.onFastForward();
                    break;
                case MESSAGE_REWIND:
                    mCallback.onRewind();
                    break;
                case MESSAGE_SEEK_TO:
                    mCallback.onSeekTo((Long) msg.obj);
                    break;
                case MESSAGE_RATE:
                    mCallback.onSetRating((Rating) msg.obj);
                    break;
            }
        }
    }
}
