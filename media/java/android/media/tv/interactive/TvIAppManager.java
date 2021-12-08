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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemService;
import android.content.Context;
import android.graphics.Rect;
import android.media.tv.BroadcastInfoRequest;
import android.media.tv.BroadcastInfoResponse;
import android.media.tv.TvInputManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.util.Log;
import android.util.Pools;
import android.util.SparseArray;
import android.view.InputChannel;
import android.view.InputEvent;
import android.view.InputEventSender;
import android.view.Surface;
import android.view.View;

import com.android.internal.util.Preconditions;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

/**
 * Central system API to the overall TV interactive application framework (TIAF) architecture, which
 * arbitrates interaction between applications and interactive apps.
 */
@SystemService(Context.TV_IAPP_SERVICE)
public final class TvIAppManager {
    // TODO: cleanup and unhide public APIs
    private static final String TAG = "TvIAppManager";

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(flag = false, prefix = "TV_IAPP_RTE_STATE_", value = {
            TV_IAPP_RTE_STATE_UNREALIZED,
            TV_IAPP_RTE_STATE_PREPARING,
            TV_IAPP_RTE_STATE_READY,
            TV_IAPP_RTE_STATE_ERROR})
    public @interface TvIAppRteState {}

    /**
     * Unrealized state of interactive app RTE.
     * @hide
     */
    public static final int TV_IAPP_RTE_STATE_UNREALIZED = 1;
    /**
     * Preparing state of interactive app RTE.
     * @hide
     */
    public static final int TV_IAPP_RTE_STATE_PREPARING = 2;
    /**
     * Ready state of interactive app RTE.
     * @hide
     */
    public static final int TV_IAPP_RTE_STATE_READY = 3;
    /**
     * Error state of interactive app RTE.
     * @hide
     */
    public static final int TV_IAPP_RTE_STATE_ERROR = 4;

    private final ITvIAppManager mService;
    private final int mUserId;

    // A mapping from the sequence number of a session to its SessionCallbackRecord.
    private final SparseArray<SessionCallbackRecord> mSessionCallbackRecordMap =
            new SparseArray<>();

    // @GuardedBy("mLock")
    private final List<TvIAppCallbackRecord> mCallbackRecords = new LinkedList<>();

    // A sequence number for the next session to be created. Should be protected by a lock
    // {@code mSessionCallbackRecordMap}.
    private int mNextSeq;

    private final Object mLock = new Object();

    private final ITvIAppClient mClient;

    /** @hide */
    public TvIAppManager(ITvIAppManager service, int userId) {
        mService = service;
        mUserId = userId;
        mClient = new ITvIAppClient.Stub() {
            @Override
            public void onSessionCreated(String iAppServiceId, IBinder token, InputChannel channel,
                    int seq) {
                synchronized (mSessionCallbackRecordMap) {
                    SessionCallbackRecord record = mSessionCallbackRecordMap.get(seq);
                    if (record == null) {
                        Log.e(TAG, "Callback not found for " + token);
                        return;
                    }
                    Session session = null;
                    if (token != null) {
                        session = new Session(token, channel, mService, mUserId, seq,
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

            @Override
            public void onLayoutSurface(int left, int top, int right, int bottom, int seq) {
                synchronized (mSessionCallbackRecordMap) {
                    SessionCallbackRecord record = mSessionCallbackRecordMap.get(seq);
                    if (record == null) {
                        Log.e(TAG, "Callback not found for seq " + seq);
                        return;
                    }
                    record.postLayoutSurface(left, top, right, bottom);
                }
            }

            @Override
            public void onBroadcastInfoRequest(BroadcastInfoRequest request, int seq) {
                synchronized (mSessionCallbackRecordMap) {
                    SessionCallbackRecord record = mSessionCallbackRecordMap.get(seq);
                    if (record == null) {
                        Log.e(TAG, "Callback not found for seq " + seq);
                        return;
                    }
                    record.postBroadcastInfoRequest(request);
                }
            }

            @Override
            public void onCommandRequest(@TvIAppService.IAppServiceCommandType String cmdType,
                    Bundle parameters, int seq) {
                synchronized (mSessionCallbackRecordMap) {
                    SessionCallbackRecord record = mSessionCallbackRecordMap.get(seq);
                    if (record == null) {
                        Log.e(TAG, "Callback not found for seq " + seq);
                        return;
                    }
                    record.postCommandRequest(cmdType, parameters);
                }
            }

            @Override
            public void onSessionStateChanged(int state, int seq) {
                synchronized (mSessionCallbackRecordMap) {
                    SessionCallbackRecord record = mSessionCallbackRecordMap.get(seq);
                    if (record == null) {
                        Log.e(TAG, "Callback not found for seq " + seq);
                        return;
                    }
                    record.postSessionStateChanged(state);
                }
            }
        };
        ITvIAppManagerCallback managerCallback = new ITvIAppManagerCallback.Stub() {
            @Override
            public void onIAppServiceAdded(String iAppServiceId) {
                synchronized (mLock) {
                    for (TvIAppCallbackRecord record : mCallbackRecords) {
                        record.postIAppServiceAdded(iAppServiceId);
                    }
                }
            }

            @Override
            public void onIAppServiceRemoved(String iAppServiceId) {
                synchronized (mLock) {
                    for (TvIAppCallbackRecord record : mCallbackRecords) {
                        record.postIAppServiceRemoved(iAppServiceId);
                    }
                }
            }

            @Override
            public void onIAppServiceUpdated(String iAppServiceId) {
                synchronized (mLock) {
                    for (TvIAppCallbackRecord record : mCallbackRecords) {
                        record.postIAppServiceUpdated(iAppServiceId);
                    }
                }
            }

            @Override
            public void onTvIAppInfoUpdated(TvIAppInfo iAppInfo) {
                // TODO: add public API updateIAppInfo()
                synchronized (mLock) {
                    for (TvIAppCallbackRecord record : mCallbackRecords) {
                        record.postTvIAppInfoUpdated(iAppInfo);
                    }
                }
            }

            @Override
            public void onStateChanged(String iAppServiceId, int type, int state) {
                synchronized (mLock) {
                    for (TvIAppCallbackRecord record : mCallbackRecords) {
                        record.postStateChanged(iAppServiceId, type, state);
                    }
                }
            }
        };
        try {
            if (mService != null) {
                mService.registerCallback(managerCallback, mUserId);
            }
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Callback used to monitor status of the TV IApp.
     * @hide
     */
    public abstract static class TvIAppCallback {
        /**
         * This is called when a TV IApp service is added to the system.
         *
         * <p>Normally it happens when the user installs a new TV IApp service package that
         * implements {@link TvIAppService} interface.
         *
         * @param iAppServiceId The ID of the TV IApp service.
         */
        public void onIAppServiceAdded(String iAppServiceId) {
        }

        /**
         * This is called when a TV IApp service is removed from the system.
         *
         * <p>Normally it happens when the user uninstalls the previously installed TV IApp service
         * package.
         *
         * @param iAppServiceId The ID of the TV IApp service.
         */
        public void onIAppServiceRemoved(String iAppServiceId) {
        }

        /**
         * This is called when a TV IApp service is updated on the system.
         *
         * <p>Normally it happens when a previously installed TV IApp service package is
         * re-installed or a newer version of the package exists becomes available/unavailable.
         *
         * @param iAppServiceId The ID of the TV IApp service.
         */
        public void onIAppServiceUpdated(String iAppServiceId) {
        }

        /**
         * This is called when the information about an existing TV IApp service has been updated.
         *
         * <p>Because the system automatically creates a <code>TvIAppInfo</code> object for each TV
         * IApp service based on the information collected from the
         * <code>AndroidManifest.xml</code>, this method is only called back when such information
         * has changed dynamically.
         *
         * @param iAppInfo The <code>TvIAppInfo</code> object that contains new information.
         */
        public void onTvIAppInfoUpdated(TvIAppInfo iAppInfo) {
        }


        /**
         * This is called when the state of the interactive app service is changed.
         * @hide
         */
        public void onTvIAppServiceStateChanged(
                String iAppServiceId, int type, @TvIAppRteState int state) {
        }
    }

    private static final class TvIAppCallbackRecord {
        private final TvIAppCallback mCallback;
        private final Handler mHandler;

        TvIAppCallbackRecord(TvIAppCallback callback, Handler handler) {
            mCallback = callback;
            mHandler = handler;
        }

        public TvIAppCallback getCallback() {
            return mCallback;
        }

        public void postIAppServiceAdded(final String iAppServiceId) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mCallback.onIAppServiceAdded(iAppServiceId);
                }
            });
        }

        public void postIAppServiceRemoved(final String iAppServiceId) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mCallback.onIAppServiceRemoved(iAppServiceId);
                }
            });
        }

        public void postIAppServiceUpdated(final String iAppServiceId) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mCallback.onIAppServiceUpdated(iAppServiceId);
                }
            });
        }

        public void postTvIAppInfoUpdated(final TvIAppInfo iAppInfo) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mCallback.onTvIAppInfoUpdated(iAppInfo);
                }
            });
        }

        public void postStateChanged(String iAppServiceId, int type, int state) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mCallback.onTvIAppServiceStateChanged(iAppServiceId, type, state);
                }
            });
        }
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
     * Returns the complete list of TV IApp service on the system.
     *
     * @return List of {@link TvIAppInfo} for each TV IApp service that describes its meta
     *         information.
     * @hide
     */
    public List<TvIAppInfo> getTvIAppServiceList() {
        try {
            return mService.getTvIAppServiceList(mUserId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Prepares TV IApp service for the given type.
     * @hide
     */
    public void prepare(String tvIAppServiceId, int type) {
        try {
            mService.prepare(tvIAppServiceId, type, mUserId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Registers a {@link TvIAppManager.TvIAppCallback}.
     *
     * @param callback A callback used to monitor status of the TV IApp services.
     * @param handler A {@link Handler} that the status change will be delivered to.
     * @hide
     */
    public void registerCallback(@NonNull TvIAppCallback callback, @NonNull Handler handler) {
        Preconditions.checkNotNull(callback);
        Preconditions.checkNotNull(handler);
        synchronized (mLock) {
            mCallbackRecords.add(new TvIAppCallbackRecord(callback, handler));
        }
    }

    /**
     * Unregisters the existing {@link TvIAppManager.TvIAppCallback}.
     *
     * @param callback The existing callback to remove.
     * @hide
     */
    public void unregisterCallback(@NonNull final TvIAppCallback callback) {
        Preconditions.checkNotNull(callback);
        synchronized (mLock) {
            for (Iterator<TvIAppCallbackRecord> it = mCallbackRecords.iterator();
                    it.hasNext(); ) {
                TvIAppCallbackRecord record = it.next();
                if (record.getCallback() == callback) {
                    it.remove();
                    break;
                }
            }
        }
    }

    /**
     * The Session provides the per-session functionality of interactive app.
     * @hide
     */
    public static final class Session {
        static final int DISPATCH_IN_PROGRESS = -1;
        static final int DISPATCH_NOT_HANDLED = 0;
        static final int DISPATCH_HANDLED = 1;

        private static final long INPUT_SESSION_NOT_RESPONDING_TIMEOUT = 2500;

        private final ITvIAppManager mService;
        private final int mUserId;
        private final int mSeq;
        private final SparseArray<SessionCallbackRecord> mSessionCallbackRecordMap;

        // For scheduling input event handling on the main thread. This also serves as a lock to
        // protect pending input events and the input channel.
        private final InputEventHandler mHandler = new InputEventHandler(Looper.getMainLooper());

        private TvInputManager.Session mInputSession;
        private final Pools.Pool<PendingEvent> mPendingEventPool = new Pools.SimplePool<>(20);
        private final SparseArray<PendingEvent> mPendingEvents = new SparseArray<>(20);

        private IBinder mToken;
        private TvInputEventSender mSender;
        private InputChannel mInputChannel;

        private Session(IBinder token, InputChannel channel, ITvIAppManager service, int userId,
                int seq, SparseArray<SessionCallbackRecord> sessionCallbackRecordMap) {
            mToken = token;
            mInputChannel = channel;
            mService = service;
            mUserId = userId;
            mSeq = seq;
            mSessionCallbackRecordMap = sessionCallbackRecordMap;
        }

        public TvInputManager.Session getInputSession() {
            return mInputSession;
        }

        public void setInputSession(TvInputManager.Session inputSession) {
            mInputSession = inputSession;
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
         * Sets the {@link android.view.Surface} for this session.
         *
         * @param surface A {@link android.view.Surface} used to render video.
         */
        public void setSurface(Surface surface) {
            if (mToken == null) {
                Log.w(TAG, "The session has been already released");
                return;
            }
            // surface can be null.
            try {
                mService.setSurface(mToken, surface, mUserId);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        /**
         * Creates a media view. Once the media view is created, {@link #relayoutMediaView}
         * should be called whenever the layout of its containing view is changed.
         * {@link #removeMediaView()} should be called to remove the media view.
         * Since a session can have only one media view, this method should be called only once
         * or it can be called again after calling {@link #removeMediaView()}.
         *
         * @param view A view for interactive app.
         * @param frame A position of the media view.
         * @throws IllegalStateException if {@code view} is not attached to a window.
         */
        void createMediaView(@NonNull View view, @NonNull Rect frame) {
            Preconditions.checkNotNull(view);
            Preconditions.checkNotNull(frame);
            if (view.getWindowToken() == null) {
                throw new IllegalStateException("view must be attached to a window");
            }
            if (mToken == null) {
                Log.w(TAG, "The session has been already released");
                return;
            }
            try {
                mService.createMediaView(mToken, view.getWindowToken(), frame, mUserId);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        /**
         * Relayouts the current media view.
         *
         * @param frame A new position of the media view.
         */
        void relayoutMediaView(@NonNull Rect frame) {
            Preconditions.checkNotNull(frame);
            if (mToken == null) {
                Log.w(TAG, "The session has been already released");
                return;
            }
            try {
                mService.relayoutMediaView(mToken, frame, mUserId);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        /**
         * Removes the current media view.
         */
        void removeMediaView() {
            if (mToken == null) {
                Log.w(TAG, "The session has been already released");
                return;
            }
            try {
                mService.removeMediaView(mToken, mUserId);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        /**
         * Notifies of any structural changes (format or size) of the surface passed in
         * {@link #setSurface}.
         *
         * @param format The new PixelFormat of the surface.
         * @param width The new width of the surface.
         * @param height The new height of the surface.
         */
        public void dispatchSurfaceChanged(int format, int width, int height) {
            if (mToken == null) {
                Log.w(TAG, "The session has been already released");
                return;
            }
            try {
                mService.dispatchSurfaceChanged(mToken, format, width, height, mUserId);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        /**
         * Dispatches an input event to this session.
         *
         * @param event An {@link InputEvent} to dispatch. Cannot be {@code null}.
         * @param token A token used to identify the input event later in the callback.
         * @param callback A callback used to receive the dispatch result. Cannot be {@code null}.
         * @param handler A {@link Handler} that the dispatch result will be delivered to. Cannot be
         *            {@code null}.
         * @return Returns {@link #DISPATCH_HANDLED} if the event was handled. Returns
         *         {@link #DISPATCH_NOT_HANDLED} if the event was not handled. Returns
         *         {@link #DISPATCH_IN_PROGRESS} if the event is in progress and the callback will
         *         be invoked later.
         * @hide
         */
        public int dispatchInputEvent(@NonNull InputEvent event, Object token,
                @NonNull FinishedInputEventCallback callback, @NonNull Handler handler) {
            Preconditions.checkNotNull(event);
            Preconditions.checkNotNull(callback);
            Preconditions.checkNotNull(handler);
            synchronized (mHandler) {
                if (mInputChannel == null) {
                    return DISPATCH_NOT_HANDLED;
                }
                PendingEvent p = obtainPendingEventLocked(event, token, callback, handler);
                if (Looper.myLooper() == Looper.getMainLooper()) {
                    // Already running on the main thread so we can send the event immediately.
                    return sendInputEventOnMainLooperLocked(p);
                }

                // Post the event to the main thread.
                Message msg = mHandler.obtainMessage(InputEventHandler.MSG_SEND_INPUT_EVENT, p);
                msg.setAsynchronous(true);
                mHandler.sendMessage(msg);
                return DISPATCH_IN_PROGRESS;
            }
        }

        /**
         * Notifies of any broadcast info response passed in from TIS.
         *
         * @param response response passed in from TIS.
         */
        public void notifyBroadcastInfoResponse(BroadcastInfoResponse response) {
            if (mToken == null) {
                Log.w(TAG, "The session has been already released");
                return;
            }
            try {
                mService.notifyBroadcastInfoResponse(mToken, response, mUserId);
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

        /**
         * Notifies IAPP session when a channels is tuned.
         */
        public void notifyTuned(Uri channelUri) {
            if (mToken == null) {
                Log.w(TAG, "The session has been already released");
                return;
            }
            try {
                mService.notifyTuned(mToken, channelUri, mUserId);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        private void flushPendingEventsLocked() {
            mHandler.removeMessages(InputEventHandler.MSG_FLUSH_INPUT_EVENT);

            final int count = mPendingEvents.size();
            for (int i = 0; i < count; i++) {
                int seq = mPendingEvents.keyAt(i);
                Message msg = mHandler.obtainMessage(
                        InputEventHandler.MSG_FLUSH_INPUT_EVENT, seq, 0);
                msg.setAsynchronous(true);
                msg.sendToTarget();
            }
        }

        private void releaseInternal() {
            mToken = null;
            synchronized (mHandler) {
                if (mInputChannel != null) {
                    if (mSender != null) {
                        flushPendingEventsLocked();
                        mSender.dispose();
                        mSender = null;
                    }
                    mInputChannel.dispose();
                    mInputChannel = null;
                }
            }
            synchronized (mSessionCallbackRecordMap) {
                mSessionCallbackRecordMap.delete(mSeq);
            }
        }

        private PendingEvent obtainPendingEventLocked(InputEvent event, Object token,
                FinishedInputEventCallback callback, Handler handler) {
            PendingEvent p = mPendingEventPool.acquire();
            if (p == null) {
                p = new PendingEvent();
            }
            p.mEvent = event;
            p.mEventToken = token;
            p.mCallback = callback;
            p.mEventHandler = handler;
            return p;
        }

        // Assumes the event has already been removed from the queue.
        void invokeFinishedInputEventCallback(PendingEvent p, boolean handled) {
            p.mHandled = handled;
            if (p.mEventHandler.getLooper().isCurrentThread()) {
                // Already running on the callback handler thread so we can send the callback
                // immediately.
                p.run();
            } else {
                // Post the event to the callback handler thread.
                // In this case, the callback will be responsible for recycling the event.
                Message msg = Message.obtain(p.mEventHandler, p);
                msg.setAsynchronous(true);
                msg.sendToTarget();
            }
        }

        // Must be called on the main looper
        private void sendInputEventAndReportResultOnMainLooper(PendingEvent p) {
            synchronized (mHandler) {
                int result = sendInputEventOnMainLooperLocked(p);
                if (result == DISPATCH_IN_PROGRESS) {
                    return;
                }
            }

            invokeFinishedInputEventCallback(p, false);
        }

        private int sendInputEventOnMainLooperLocked(PendingEvent p) {
            if (mInputChannel != null) {
                if (mSender == null) {
                    mSender = new TvInputEventSender(mInputChannel, mHandler.getLooper());
                }

                final InputEvent event = p.mEvent;
                final int seq = event.getSequenceNumber();
                if (mSender.sendInputEvent(seq, event)) {
                    mPendingEvents.put(seq, p);
                    Message msg = mHandler.obtainMessage(
                            InputEventHandler.MSG_TIMEOUT_INPUT_EVENT, p);
                    msg.setAsynchronous(true);
                    mHandler.sendMessageDelayed(msg, INPUT_SESSION_NOT_RESPONDING_TIMEOUT);
                    return DISPATCH_IN_PROGRESS;
                }

                Log.w(TAG, "Unable to send input event to session: " + mToken + " dropping:"
                        + event);
            }
            return DISPATCH_NOT_HANDLED;
        }

        void finishedInputEvent(int seq, boolean handled, boolean timeout) {
            final PendingEvent p;
            synchronized (mHandler) {
                int index = mPendingEvents.indexOfKey(seq);
                if (index < 0) {
                    return; // spurious, event already finished or timed out
                }

                p = mPendingEvents.valueAt(index);
                mPendingEvents.removeAt(index);

                if (timeout) {
                    Log.w(TAG, "Timeout waiting for session to handle input event after "
                            + INPUT_SESSION_NOT_RESPONDING_TIMEOUT + " ms: " + mToken);
                } else {
                    mHandler.removeMessages(InputEventHandler.MSG_TIMEOUT_INPUT_EVENT, p);
                }
            }

            invokeFinishedInputEventCallback(p, handled);
        }

        private void recyclePendingEventLocked(PendingEvent p) {
            p.recycle();
            mPendingEventPool.release(p);
        }

        /**
         * Callback that is invoked when an input event that was dispatched to this session has been
         * finished.
         *
         * @hide
         */
        public interface FinishedInputEventCallback {
            /**
             * Called when the dispatched input event is finished.
             *
             * @param token A token passed to {@link #dispatchInputEvent}.
             * @param handled {@code true} if the dispatched input event was handled properly.
             *            {@code false} otherwise.
             */
            void onFinishedInputEvent(Object token, boolean handled);
        }

        private final class InputEventHandler extends Handler {
            public static final int MSG_SEND_INPUT_EVENT = 1;
            public static final int MSG_TIMEOUT_INPUT_EVENT = 2;
            public static final int MSG_FLUSH_INPUT_EVENT = 3;

            InputEventHandler(Looper looper) {
                super(looper, null, true);
            }

            @Override
            public void handleMessage(Message msg) {
                switch (msg.what) {
                    case MSG_SEND_INPUT_EVENT: {
                        sendInputEventAndReportResultOnMainLooper((PendingEvent) msg.obj);
                        return;
                    }
                    case MSG_TIMEOUT_INPUT_EVENT: {
                        finishedInputEvent(msg.arg1, false, true);
                        return;
                    }
                    case MSG_FLUSH_INPUT_EVENT: {
                        finishedInputEvent(msg.arg1, false, false);
                        return;
                    }
                }
            }
        }

        private final class TvInputEventSender extends InputEventSender {
            TvInputEventSender(InputChannel inputChannel, Looper looper) {
                super(inputChannel, looper);
            }

            @Override
            public void onInputEventFinished(int seq, boolean handled) {
                finishedInputEvent(seq, handled, false);
            }
        }

        private final class PendingEvent implements Runnable {
            public InputEvent mEvent;
            public Object mEventToken;
            public FinishedInputEventCallback mCallback;
            public Handler mEventHandler;
            public boolean mHandled;

            public void recycle() {
                mEvent = null;
                mEventToken = null;
                mCallback = null;
                mEventHandler = null;
                mHandled = false;
            }

            @Override
            public void run() {
                mCallback.onFinishedInputEvent(mEventToken, mHandled);

                synchronized (mEventHandler) {
                    recyclePendingEventLocked(this);
                }
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

        void postLayoutSurface(final int left, final int top, final int right,
                final int bottom) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mSessionCallback.onLayoutSurface(mSession, left, top, right, bottom);
                }
            });
        }

        void postBroadcastInfoRequest(final BroadcastInfoRequest request) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mSession.getInputSession().requestBroadcastInfo(request);
                }
            });
        }

        void postCommandRequest(final @TvIAppService.IAppServiceCommandType String cmdType,
                final Bundle parameters) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mSessionCallback.onCommandRequest(mSession, cmdType, parameters);
                }
            });
        }

        void postSessionStateChanged(int state) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mSessionCallback.onSessionStateChanged(mSession, state);
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

        /**
         * This is called when {@link TvIAppService.Session#layoutSurface} is called to change the
         * layout of surface.
         *
         * @param session A {@link TvIAppManager.Session} associated with this callback.
         * @param left Left position.
         * @param top Top position.
         * @param right Right position.
         * @param bottom Bottom position.
         */
        public void onLayoutSurface(Session session, int left, int top, int right, int bottom) {
        }

        /**
         * This is called when {@link TvIAppService.Session#requestCommand} is called.
         *
         * @param session A {@link TvIAppManager.Session} associated with this callback.
         * @param cmdType type of the command.
         * @param parameters parameters of the command.
         */
        public void onCommandRequest(Session session,
                @TvIAppService.IAppServiceCommandType String cmdType, Bundle parameters) {
        }

        /**
         * This is called when {@link TvIAppService.Session#notifySessionStateChanged} is called.
         *
         * @param session A {@link TvIAppManager.Session} associated with this callback.
         * @param state the current state.
         */
        public void onSessionStateChanged(Session session, int state) {
        }
    }
}
