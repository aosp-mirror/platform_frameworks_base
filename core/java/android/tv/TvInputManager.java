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

package android.tv;

import android.content.ComponentName;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import android.util.SparseArray;
import android.view.Surface;
import android.view.View;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Central system API to the overall TV input framework (TIF) architecture, which arbitrates
 * interaction between applications and the selected TV inputs.
 */
public final class TvInputManager {
    private static final String TAG = "TvInputManager";

    private final ITvInputManager mService;

    // A mapping from an input to the list of its TvInputListenerRecords.
    private final Map<ComponentName, List<TvInputListenerRecord>> mTvInputListenerRecordsMap =
            new HashMap<ComponentName, List<TvInputListenerRecord>>();

    // A mapping from the sequence number of a session to its SessionCreateCallbackRecord.
    private final SparseArray<SessionCreateCallbackRecord> mSessionCreateCallbackRecordMap =
            new SparseArray<SessionCreateCallbackRecord>();

    // A sequence number for the next session to be created. Should be protected by a lock
    // {@code mSessionCreateCallbackRecordMap}.
    private int mNextSeq;

    private final ITvInputClient mClient;

    private final int mUserId;

    /**
     * Interface used to receive the created session.
     */
    public interface SessionCreateCallback {
        /**
         * This is called after {@link TvInputManager#createSession} has been processed.
         *
         * @param session A {@link TvInputManager.Session} instance created. This can be
         *            {@code null} if the creation request failed.
         */
        void onSessionCreated(Session session);
    }

    private static final class SessionCreateCallbackRecord {
        private final SessionCreateCallback mSessionCreateCallback;
        private final Handler mHandler;

        public SessionCreateCallbackRecord(SessionCreateCallback sessionCreateCallback,
                Handler handler) {
            mSessionCreateCallback = sessionCreateCallback;
            mHandler = handler;
        }

        public void postSessionCreated(final Session session) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mSessionCreateCallback.onSessionCreated(session);
                }
            });
        }
    }

    /**
     * Interface used to monitor status of the TV input.
     */
    public abstract static class TvInputListener {
        /**
         * This is called when the availability status of a given TV input is changed.
         *
         * @param name {@link ComponentName} of {@link android.app.Service} that implements the
         *            given TV input.
         * @param isAvailable {@code true} if the given TV input is available to show TV programs.
         *            {@code false} otherwise.
         */
        public void onAvailabilityChanged(ComponentName name, boolean isAvailable) {
        }
    }

    private static final class TvInputListenerRecord {
        private final TvInputListener mListener;
        private final Handler mHandler;

        public TvInputListenerRecord(TvInputListener listener, Handler handler) {
            mListener = listener;
            mHandler = handler;
        }

        public TvInputListener getListener() {
            return mListener;
        }

        public void postAvailabilityChanged(final ComponentName name, final boolean isAvailable) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mListener.onAvailabilityChanged(name, isAvailable);
                }
            });
        }
    }

    /**
     * @hide
     */
    public TvInputManager(ITvInputManager service, int userId) {
        mService = service;
        mUserId = userId;
        mClient = new ITvInputClient.Stub() {
            @Override
            public void onSessionCreated(ComponentName name, IBinder token, int seq) {
                synchronized (mSessionCreateCallbackRecordMap) {
                    SessionCreateCallbackRecord record = mSessionCreateCallbackRecordMap.get(seq);
                    mSessionCreateCallbackRecordMap.delete(seq);
                    if (record == null) {
                        Log.e(TAG, "Callback not found for " + token);
                        return;
                    }
                    Session session = null;
                    if (token != null) {
                        session = new Session(name, token, mService, mUserId);
                    }
                    record.postSessionCreated(session);
                }
            }

            @Override
            public void onAvailabilityChanged(ComponentName name, boolean isAvailable) {
                synchronized (mTvInputListenerRecordsMap) {
                    List<TvInputListenerRecord> records = mTvInputListenerRecordsMap.get(name);
                    if (records == null) {
                        // Silently ignore - no listener is registered yet.
                        return;
                    }
                    int recordsCount = records.size();
                    for (int i = 0; i < recordsCount; i++) {
                        records.get(i).postAvailabilityChanged(name, isAvailable);
                    }
                }
            }
        };
    }

    /**
     * Returns the complete list of TV inputs on the system.
     *
     * @return List of {@link TvInputInfo} for each TV input that describes its meta information.
     */
    public List<TvInputInfo> getTvInputList() {
        try {
            return mService.getTvInputList(mUserId);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Returns the availability of a given TV input.
     *
     * @param name {@link ComponentName} of {@link android.app.Service} that implements the given TV
     *            input.
     * @throws IllegalArgumentException if the argument is {@code null}.
     * @throws IllegalStateException If there is no {@link TvInputListener} registered on the given
     *             TV input.
     */
    public boolean getAvailability(ComponentName name) {
        if (name == null) {
            throw new IllegalArgumentException("name cannot be null");
        }
        synchronized (mTvInputListenerRecordsMap) {
            List<TvInputListenerRecord> records = mTvInputListenerRecordsMap.get(name);
            if (records == null || records.size() == 0) {
                throw new IllegalStateException("At least one listener should be registered.");
            }
        }
        try {
            return mService.getAvailability(mClient, name, mUserId);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Registers a {@link TvInputListener} for a given TV input.
     *
     * @param name {@link ComponentName} of {@link android.app.Service} that implements the given TV
     *            input.
     * @param listener a listener used to monitor status of the given TV input.
     * @param handler a {@link Handler} that the status change will be delivered to.
     * @throws IllegalArgumentException if any of the arguments is {@code null}.
     */
    public void registerListener(ComponentName name, TvInputListener listener, Handler handler) {
        if (name == null) {
            throw new IllegalArgumentException("name cannot be null");
        }
        if (listener == null) {
            throw new IllegalArgumentException("listener cannot be null");
        }
        if (handler == null) {
            throw new IllegalArgumentException("handler cannot be null");
        }
        synchronized (mTvInputListenerRecordsMap) {
            List<TvInputListenerRecord> records = mTvInputListenerRecordsMap.get(name);
            if (records == null) {
                records = new ArrayList<TvInputListenerRecord>();
                mTvInputListenerRecordsMap.put(name, records);
                try {
                    mService.registerCallback(mClient, name, mUserId);
                } catch (RemoteException e) {
                    throw new RuntimeException(e);
                }
            }
            records.add(new TvInputListenerRecord(listener, handler));
        }
    }

    /**
     * Unregisters the existing {@link TvInputListener} for a given TV input.
     *
     * @param name {@link ComponentName} of {@link android.app.Service} that implements the given TV
     *            input.
     * @param listener the existing listener to remove for the given TV input.
     * @throws IllegalArgumentException if any of the arguments is {@code null}.
     */
    public void unregisterListener(ComponentName name, final TvInputListener listener) {
        if (name == null) {
            throw new IllegalArgumentException("name cannot be null");
        }
        if (listener == null) {
            throw new IllegalArgumentException("listener cannot be null");
        }
        synchronized (mTvInputListenerRecordsMap) {
            List<TvInputListenerRecord> records = mTvInputListenerRecordsMap.get(name);
            if (records == null) {
                Log.e(TAG, "No listener found for " + name.getClassName());
                return;
            }
            for (Iterator<TvInputListenerRecord> it = records.iterator(); it.hasNext();) {
                TvInputListenerRecord record = it.next();
                if (record.getListener() == listener) {
                    it.remove();
                }
            }
            if (records.isEmpty()) {
                try {
                    mService.unregisterCallback(mClient, name, mUserId);
                } catch (RemoteException e) {
                    throw new RuntimeException(e);
                } finally {
                    mTvInputListenerRecordsMap.remove(name);
                }
            }
        }
    }

    /**
     * Creates a {@link Session} for a given TV input.
     * <p>
     * The number of sessions that can be created at the same time is limited by the capability of
     * the given TV input.
     * </p>
     *
     * @param name {@link ComponentName} of {@link android.app.Service} that implements the given TV
     *            input.
     * @param callback a callback used to receive the created session.
     * @param handler a {@link Handler} that the session creation will be delivered to.
     * @throws IllegalArgumentException if any of the arguments is {@code null}.
     */
    public void createSession(ComponentName name, final SessionCreateCallback callback,
            Handler handler) {
        if (name == null) {
            throw new IllegalArgumentException("name cannot be null");
        }
        if (callback == null) {
            throw new IllegalArgumentException("callback cannot be null");
        }
        if (handler == null) {
            throw new IllegalArgumentException("handler cannot be null");
        }
        SessionCreateCallbackRecord record = new SessionCreateCallbackRecord(callback, handler);
        synchronized (mSessionCreateCallbackRecordMap) {
            int seq = mNextSeq++;
            mSessionCreateCallbackRecordMap.put(seq, record);
            try {
                mService.createSession(mClient, name, seq, mUserId);
            } catch (RemoteException e) {
                throw new RuntimeException(e);
            }
        }
    }

    /** The Session provides the per-session functionality of TV inputs. */
    public static final class Session {
        private final ITvInputManager mService;
        private final int mUserId;
        private IBinder mToken;

        /** @hide */
        private Session(ComponentName name, IBinder token, ITvInputManager service, int userId) {
            mToken = token;
            mService = service;
            mUserId = userId;
        }

        /**
         * Releases this session.
         *
         * @throws IllegalStateException if the session has been already released.
         */
        public void release() {
            if (mToken == null) {
                throw new IllegalStateException("the session has been already released");
            }
            try {
                mService.releaseSession(mToken, mUserId);
                mToken = null;
            } catch (RemoteException e) {
                throw new RuntimeException(e);
            }
        }

        /**
         * Sets the {@link android.view.Surface} for this session.
         *
         * @param surface A {@link android.view.Surface} used to render video.
         * @throws IllegalStateException if the session has been already released.
         */
        void setSurface(Surface surface) {
            if (mToken == null) {
                throw new IllegalStateException("the session has been already released");
            }
            // surface can be null.
            try {
                mService.setSurface(mToken, surface, mUserId);
            } catch (RemoteException e) {
                throw new RuntimeException(e);
            }
        }

        /**
         * Sets the relative volume of this session to handle a change of audio focus.
         *
         * @param volume A volume value between 0.0f to 1.0f.
         * @throws IllegalArgumentException if the volume value is out of range.
         * @throws IllegalStateException if the session has been already released.
         */
        public void setVolume(float volume) {
            if (mToken == null) {
                throw new IllegalStateException("the session has been already released");
            }
            try {
                if (volume < 0.0f || volume > 1.0f) {
                    throw new IllegalArgumentException("volume should be between 0.0f and 1.0f");
                }
                mService.setVolume(mToken, volume, mUserId);
            } catch (RemoteException e) {
                throw new RuntimeException(e);
            }
        }

        /**
         * Tunes to a given channel.
         *
         * @param channelUri The URI of a channel.
         * @throws IllegalArgumentException if the argument is {@code null}.
         * @throws IllegalStateException if the session has been already released.
         */
        public void tune(Uri channelUri) {
            if (channelUri == null) {
                throw new IllegalArgumentException("channelUri cannot be null");
            }
            if (mToken == null) {
                throw new IllegalStateException("the session has been already released");
            }
            try {
                mService.tune(mToken, channelUri, mUserId);
            } catch (RemoteException e) {
                throw new RuntimeException(e);
            }
        }

        /**
         * Creates an overlay view. Once the overlay view is created, {@link #relayoutOverlayView}
         * should be called whenever the layout of its containing view is changed.
         * {@link #removeOverlayView()} should be called to remove the overlay view.
         * Since a session can have only one overlay view, this method should be called only once
         * or it can be called again after calling {@link #removeOverlayView()}.
         *
         * @param view A view playing TV.
         * @param frame A position of the overlay view.
         * @throws IllegalArgumentException if any of the arguments is {@code null}.
         * @throws IllegalStateException if {@code view} is not attached to a window or
         *         if the session has been already released.
         */
        void createOverlayView(View view, Rect frame) {
            if (view == null) {
                throw new IllegalArgumentException("view cannot be null");
            }
            if (frame == null) {
                throw new IllegalArgumentException("frame cannot be null");
            }
            if (view.getWindowToken() == null) {
                throw new IllegalStateException("view must be attached to a window");
            }
            if (mToken == null) {
                throw new IllegalStateException("the session has been already released");
            }
            try {
                mService.createOverlayView(mToken, view.getWindowToken(), frame, mUserId);
            } catch (RemoteException e) {
                throw new RuntimeException(e);
            }
        }

        /**
         * Relayouts the current overlay view.
         *
         * @param frame A new position of the overlay view.
         * @throws IllegalArgumentException if the arguments is {@code null}.
         * @throws IllegalStateException if the session has been already released.
         */
        void relayoutOverlayView(Rect frame) {
            if (frame == null) {
                throw new IllegalArgumentException("frame cannot be null");
            }
            if (mToken == null) {
                throw new IllegalStateException("the session has been already released");
            }
            try {
                mService.relayoutOverlayView(mToken, frame, mUserId);
            } catch (RemoteException e) {
                throw new RuntimeException(e);
            }
        }

        /**
         * Removes the current overlay view.
         *
         * @throws IllegalStateException if the session has been already released.
         */
        void removeOverlayView() {
            if (mToken == null) {
                throw new IllegalStateException("the session has been already released");
            }
            try {
                mService.removeOverlayView(mToken, mUserId);
            } catch (RemoteException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
