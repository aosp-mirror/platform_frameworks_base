/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.media.tv.interactive;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemService;
import android.content.Context;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import android.util.SparseArray;

import com.android.internal.util.Preconditions;

/**
 * Central system API to the overall TV interactive application framework (TIAF) architecture, which
 * arbitrates interaction between applications and interactive apps.
 */
@SystemService(Context.TV_IAPP_SERVICE)
public final class TvIAppManager {
    // TODO: cleanup and unhide public APIs
    private static final String TAG = "TvIAppManager";

    private final ITvIAppManager mService;
    private final int mUserId;

    // A mapping from the sequence number of a session to its SessionCallbackRecord.
    private final SparseArray<SessionCallbackRecord> mSessionCallbackRecordMap =
            new SparseArray<>();

    // A sequence number for the next session to be created. Should be protected by a lock
    // {@code mSessionCallbackRecordMap}.
    private int mNextSeq;

    private final ITvIAppClient mClient;

    /** @hide */
    public TvIAppManager(ITvIAppManager service, int userId) {
        mService = service;
        mUserId = userId;
        mClient = new ITvIAppClient.Stub() {
            @Override
            public void onSessionCreated(String iAppServiceId, IBinder token, int seq) {
                // TODO: use InputChannel for input events
                synchronized (mSessionCallbackRecordMap) {
                    SessionCallbackRecord record = mSessionCallbackRecordMap.get(seq);
                    if (record == null) {
                        Log.e(TAG, "Callback not found for " + token);
                        return;
                    }
                    Session session = null;
                    if (token != null) {
                        session = new Session(token, mService, mUserId, seq,
                                mSessionCallbackRecordMap);
                    } else {
                        mSessionCallbackRecordMap.delete(seq);
                    }
                    record.postSessionCreated(session);
                }
            }

            @Override
            public void onSessionReleased(int seq) {
                synchronized (mSessionCallbackRecordMap) {
                    SessionCallbackRecord record = mSessionCallbackRecordMap.get(seq);
                    mSessionCallbackRecordMap.delete(seq);
                    if (record == null) {
                        Log.e(TAG, "Callback not found for seq:" + seq);
                        return;
                    }
                    record.mSession.releaseInternal();
                    record.postSessionReleased();
                }
            }
        };
    }

    /**
     * Creates a {@link Session} for a given TV interactive application.
     *
     * <p>The number of sessions that can be created at the same time is limited by the capability
     * of the given interactive application.
     *
     * @param iAppServiceId The ID of the interactive application.
     * @param type the type of the interactive application.
     * @param callback A callback used to receive the created session.
     * @param handler A {@link Handler} that the session creation will be delivered to.
     * @hide
     */
    public void createSession(@NonNull String iAppServiceId, int type,
            @NonNull final SessionCallback callback, @NonNull Handler handler) {
        createSessionInternal(iAppServiceId, type, callback, handler);
    }

    private void createSessionInternal(String iAppServiceId, int type, SessionCallback callback,
            Handler handler) {
        Preconditions.checkNotNull(iAppServiceId);
        Preconditions.checkNotNull(callback);
        Preconditions.checkNotNull(handler);
        SessionCallbackRecord record = new SessionCallbackRecord(callback, handler);
        synchronized (mSessionCallbackRecordMap) {
            int seq = mNextSeq++;
            mSessionCallbackRecordMap.put(seq, record);
            try {
                mService.createSession(mClient, iAppServiceId, type, seq, mUserId);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * The Session provides the per-session functionality of interactive app.
     * @hide
     */
    public static final class Session {
        private final ITvIAppManager mService;
        private final int mUserId;
        private final int mSeq;
        private final SparseArray<SessionCallbackRecord> mSessionCallbackRecordMap;

        private IBinder mToken;

        private Session(IBinder token, ITvIAppManager service, int userId, int seq,
                SparseArray<SessionCallbackRecord> sessionCallbackRecordMap) {
            mToken = token;
            mService = service;
            mUserId = userId;
            mSeq = seq;
            mSessionCallbackRecordMap = sessionCallbackRecordMap;
        }

        void startIApp() {
            if (mToken == null) {
                Log.w(TAG, "The session has been already released");
                return;
            }
            try {
                mService.startIApp(mToken, mUserId);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        /**
         * Releases this session.
         */
        public void release() {
            if (mToken == null) {
                Log.w(TAG, "The session has been already released");
                return;
            }
            try {
                mService.releaseSession(mToken, mUserId);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }

            releaseInternal();
        }

        private void releaseInternal() {
            mToken = null;
            synchronized (mSessionCallbackRecordMap) {
                mSessionCallbackRecordMap.delete(mSeq);
            }
        }
    }

    private static final class SessionCallbackRecord {
        private final SessionCallback mSessionCallback;
        private final Handler mHandler;
        private Session mSession;

        SessionCallbackRecord(SessionCallback sessionCallback, Handler handler) {
            mSessionCallback = sessionCallback;
            mHandler = handler;
        }

        void postSessionCreated(final Session session) {
            mSession = session;
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mSessionCallback.onSessionCreated(session);
                }
            });
        }

        void postSessionReleased() {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mSessionCallback.onSessionReleased(mSession);
                }
            });
        }
    }

    /**
     * Interface used to receive the created session.
     * @hide
     */
    public abstract static class SessionCallback {
        /**
         * This is called after {@link TvIAppManager#createSession} has been processed.
         *
         * @param session A {@link TvIAppManager.Session} instance created. This can be {@code null}
         *                if the creation request failed.
         */
        public void onSessionCreated(@Nullable Session session) {
        }

        /**
         * This is called when {@link TvIAppManager.Session} is released.
         * This typically happens when the process hosting the session has crashed or been killed.
         *
         * @param session the {@link TvIAppManager.Session} instance released.
         */
        public void onSessionReleased(@NonNull Session session) {
        }
    }
}
