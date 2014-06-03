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

package android.media.tv;

import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.util.Log;
import android.util.Pools.Pool;
import android.util.Pools.SimplePool;
import android.util.SparseArray;
import android.view.InputChannel;
import android.view.InputEvent;
import android.view.InputEventSender;
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
    private final Map<String, List<TvInputListenerRecord>> mTvInputListenerRecordsMap =
            new HashMap<String, List<TvInputListenerRecord>>();

    // A mapping from the sequence number of a session to its SessionCallbackRecord.
    private final SparseArray<SessionCallbackRecord> mSessionCallbackRecordMap =
            new SparseArray<SessionCallbackRecord>();

    // A sequence number for the next session to be created. Should be protected by a lock
    // {@code mSessionCallbackRecordMap}.
    private int mNextSeq;

    private final ITvInputClient mClient;

    private final int mUserId;

    /**
     * Interface used to receive the created session.
     */
    public abstract static class SessionCallback {
        /**
         * This is called after {@link TvInputManager#createSession} has been processed.
         *
         * @param session A {@link TvInputManager.Session} instance created. This can be
         *            {@code null} if the creation request failed.
         */
        public void onSessionCreated(Session session) {
        }

        /**
         * This is called when {@link TvInputManager.Session} is released.
         * This typically happens when the process hosting the session has crashed or been killed.
         *
         * @param session A {@link TvInputManager.Session} instance released.
         */
        public void onSessionReleased(Session session) {
        }

        /**
         * This is called at the beginning of the playback of a channel and later when the size of
         * the video has been changed.
         *
         * @param session A {@link TvInputManager.Session} associated with this callback
         * @param width the width of the video
         * @param height the height of the video
         * @hide
         */
        public void onVideoSizeChanged(Session session, int width, int height) {
        }

        /**
         * This is called when a custom event has been sent from this session.
         *
         * @param session A {@link TvInputManager.Session} associated with this callback
         * @param eventType The type of the event.
         * @param eventArgs Optional arguments of the event.
         * @hide
         */
        public void onSessionEvent(Session session, String eventType, Bundle eventArgs) {
        }
    }

    private static final class SessionCallbackRecord {
        private final SessionCallback mSessionCallback;
        private final Handler mHandler;
        private Session mSession;

        public SessionCallbackRecord(SessionCallback sessionCallback,
                Handler handler) {
            mSessionCallback = sessionCallback;
            mHandler = handler;
        }

        public void postSessionCreated(final Session session) {
            mSession = session;
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mSessionCallback.onSessionCreated(session);
                }
            });
        }

        public void postSessionReleased() {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mSessionCallback.onSessionReleased(mSession);
                }
            });
        }

        public void postVideoSizeChanged(final int width, final int height) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mSessionCallback.onVideoSizeChanged(mSession, width, height);
                }
            });
        }

        public void postSessionEvent(final String eventType, final Bundle eventArgs) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mSessionCallback.onSessionEvent(mSession, eventType, eventArgs);
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
         * @param inputId the id of the TV input.
         * @param isAvailable {@code true} if the given TV input is available to show TV programs.
         *            {@code false} otherwise.
         */
        public void onAvailabilityChanged(String inputId, boolean isAvailable) {
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

        public void postAvailabilityChanged(final String inputId, final boolean isAvailable) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mListener.onAvailabilityChanged(inputId, isAvailable);
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
            public void onSessionCreated(String inputId, IBinder token, InputChannel channel,
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
            public void onVideoSizeChanged(int width, int height, int seq) {
                synchronized (mSessionCallbackRecordMap) {
                    SessionCallbackRecord record = mSessionCallbackRecordMap.get(seq);
                    if (record == null) {
                        Log.e(TAG, "Callback not found for seq " + seq);
                        return;
                    }
                    record.postVideoSizeChanged(width, height);
                }
            }

            @Override
            public void onSessionEvent(String eventType, Bundle eventArgs, int seq) {
                synchronized (mSessionCallbackRecordMap) {
                    SessionCallbackRecord record = mSessionCallbackRecordMap.get(seq);
                    if (record == null) {
                        Log.e(TAG, "Callback not found for seq " + seq);
                        return;
                    }
                    record.postSessionEvent(eventType, eventArgs);
                }
            }

            @Override
            public void onAvailabilityChanged(String inputId, boolean isAvailable) {
                synchronized (mTvInputListenerRecordsMap) {
                    List<TvInputListenerRecord> records = mTvInputListenerRecordsMap.get(inputId);
                    if (records == null) {
                        // Silently ignore - no listener is registered yet.
                        return;
                    }
                    int recordsCount = records.size();
                    for (int i = 0; i < recordsCount; i++) {
                        records.get(i).postAvailabilityChanged(inputId, isAvailable);
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
     * @param inputId the id of the TV input.
     * @throws IllegalArgumentException if the argument is {@code null}.
     * @throws IllegalStateException If there is no {@link TvInputListener} registered on the given
     *             TV input.
     */
    public boolean getAvailability(String inputId) {
        if (inputId == null) {
            throw new IllegalArgumentException("id cannot be null");
        }
        synchronized (mTvInputListenerRecordsMap) {
            List<TvInputListenerRecord> records = mTvInputListenerRecordsMap.get(inputId);
            if (records == null || records.size() == 0) {
                throw new IllegalStateException("At least one listener should be registered.");
            }
        }
        try {
            return mService.getAvailability(mClient, inputId, mUserId);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Registers a {@link TvInputListener} for a given TV input.
     *
     * @param inputId the id of the TV input.
     * @param listener a listener used to monitor status of the given TV input.
     * @param handler a {@link Handler} that the status change will be delivered to.
     * @throws IllegalArgumentException if any of the arguments is {@code null}.
     */
    public void registerListener(String inputId, TvInputListener listener, Handler handler) {
        if (inputId == null) {
            throw new IllegalArgumentException("id cannot be null");
        }
        if (listener == null) {
            throw new IllegalArgumentException("listener cannot be null");
        }
        if (handler == null) {
            throw new IllegalArgumentException("handler cannot be null");
        }
        synchronized (mTvInputListenerRecordsMap) {
            List<TvInputListenerRecord> records = mTvInputListenerRecordsMap.get(inputId);
            if (records == null) {
                records = new ArrayList<TvInputListenerRecord>();
                mTvInputListenerRecordsMap.put(inputId, records);
                try {
                    mService.registerCallback(mClient, inputId, mUserId);
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
     * @param inputId the id of the TV input.
     * @param listener the existing listener to remove for the given TV input.
     * @throws IllegalArgumentException if any of the arguments is {@code null}.
     */
    public void unregisterListener(String inputId, final TvInputListener listener) {
        if (inputId == null) {
            throw new IllegalArgumentException("id cannot be null");
        }
        if (listener == null) {
            throw new IllegalArgumentException("listener cannot be null");
        }
        synchronized (mTvInputListenerRecordsMap) {
            List<TvInputListenerRecord> records = mTvInputListenerRecordsMap.get(inputId);
            if (records == null) {
                Log.e(TAG, "No listener found for " + inputId);
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
                    mService.unregisterCallback(mClient, inputId, mUserId);
                } catch (RemoteException e) {
                    throw new RuntimeException(e);
                } finally {
                    mTvInputListenerRecordsMap.remove(inputId);
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
     * @param inputId the id of the TV input.
     * @param callback a callback used to receive the created session.
     * @param handler a {@link Handler} that the session creation will be delivered to.
     * @throws IllegalArgumentException if any of the arguments is {@code null}.
     */
    public void createSession(String inputId, final SessionCallback callback,
            Handler handler) {
        if (inputId == null) {
            throw new IllegalArgumentException("id cannot be null");
        }
        if (callback == null) {
            throw new IllegalArgumentException("callback cannot be null");
        }
        if (handler == null) {
            throw new IllegalArgumentException("handler cannot be null");
        }
        SessionCallbackRecord record = new SessionCallbackRecord(callback, handler);
        synchronized (mSessionCallbackRecordMap) {
            int seq = mNextSeq++;
            mSessionCallbackRecordMap.put(seq, record);
            try {
                mService.createSession(mClient, inputId, seq, mUserId);
            } catch (RemoteException e) {
                throw new RuntimeException(e);
            }
        }
    }

    /** The Session provides the per-session functionality of TV inputs. */
    public static final class Session {
        static final int DISPATCH_IN_PROGRESS = -1;
        static final int DISPATCH_NOT_HANDLED = 0;
        static final int DISPATCH_HANDLED = 1;

        private static final long INPUT_SESSION_NOT_RESPONDING_TIMEOUT = 2500;

        private final ITvInputManager mService;
        private final int mUserId;
        private final int mSeq;

        // For scheduling input event handling on the main thread. This also serves as a lock to
        // protect pending input events and the input channel.
        private final InputEventHandler mHandler = new InputEventHandler(Looper.getMainLooper());

        private final Pool<PendingEvent> mPendingEventPool = new SimplePool<PendingEvent>(20);
        private final SparseArray<PendingEvent> mPendingEvents = new SparseArray<PendingEvent>(20);
        private final SparseArray<SessionCallbackRecord> mSessionCallbackRecordMap;

        private IBinder mToken;
        private TvInputEventSender mSender;
        private InputChannel mChannel;

        /** @hide */
        private Session(IBinder token, InputChannel channel, ITvInputManager service, int userId,
                int seq, SparseArray<SessionCallbackRecord> sessionCallbackRecordMap) {
            mToken = token;
            mChannel = channel;
            mService = service;
            mUserId = userId;
            mSeq = seq;
            mSessionCallbackRecordMap = sessionCallbackRecordMap;
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
            } catch (RemoteException e) {
                throw new RuntimeException(e);
            }

            releaseInternal();
        }

        /**
         * Sets the {@link android.view.Surface} for this session.
         *
         * @param surface A {@link android.view.Surface} used to render video.
         * @throws IllegalStateException if the session has been already released.
         * @hide
         */
        public void setSurface(Surface surface) {
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

        /**
         * Dispatches an input event to this session.
         *
         * @param event {@link InputEvent} to dispatch.
         * @param token A token used to identify the input event later in the callback.
         * @param callback A callback used to receive the dispatch result.
         * @param handler {@link Handler} that the dispatch result will be delivered to.
         * @return Returns {@link #DISPATCH_HANDLED} if the event was handled. Returns
         *         {@link #DISPATCH_NOT_HANDLED} if the event was not handled. Returns
         *         {@link #DISPATCH_IN_PROGRESS} if the event is in progress and the callback will
         *         be invoked later.
         * @throws IllegalArgumentException if any of the necessary arguments is {@code null}.
         * @hide
         */
        public int dispatchInputEvent(InputEvent event, Object token,
                FinishedInputEventCallback callback, Handler handler) {
            if (event == null) {
                throw new IllegalArgumentException("event cannot be null");
            }
            if (callback != null && handler == null) {
                throw new IllegalArgumentException("handler cannot be null");
            }
            synchronized (mHandler) {
                if (mChannel == null) {
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
         * Callback that is invoked when an input event that was dispatched to this session has been
         * finished.
         *
         * @hide
         */
        public interface FinishedInputEventCallback {
            /**
             * Called when the dispatched input event is finished.
             *
             * @param token a token passed to {@link #dispatchInputEvent}.
             * @param handled {@code true} if the dispatched input event was handled properly.
             *            {@code false} otherwise.
             */
            public void onFinishedInputEvent(Object token, boolean handled);
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
            if (mChannel != null) {
                if (mSender == null) {
                    mSender = new TvInputEventSender(mChannel, mHandler.getLooper());
                }

                final InputEvent event = p.mEvent;
                final int seq = event.getSequenceNumber();
                if (mSender.sendInputEvent(seq, event)) {
                    mPendingEvents.put(seq, p);
                    Message msg = mHandler.obtainMessage(InputEventHandler.MSG_TIMEOUT_INPUT_EVENT, p);
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
                    Log.w(TAG, "Timeout waiting for seesion to handle input event after "
                            + INPUT_SESSION_NOT_RESPONDING_TIMEOUT + " ms: " + mToken);
                } else {
                    mHandler.removeMessages(InputEventHandler.MSG_TIMEOUT_INPUT_EVENT, p);
                }
            }

            invokeFinishedInputEventCallback(p, handled);
        }

        // Assumes the event has already been removed from the queue.
        void invokeFinishedInputEventCallback(PendingEvent p, boolean handled) {
            p.mHandled = handled;
            if (p.mHandler.getLooper().isCurrentThread()) {
                // Already running on the callback handler thread so we can send the callback
                // immediately.
                p.run();
            } else {
                // Post the event to the callback handler thread.
                // In this case, the callback will be responsible for recycling the event.
                Message msg = Message.obtain(p.mHandler, p);
                msg.setAsynchronous(true);
                msg.sendToTarget();
            }
        }

        private void flushPendingEventsLocked() {
            mHandler.removeMessages(InputEventHandler.MSG_FLUSH_INPUT_EVENT);

            final int count = mPendingEvents.size();
            for (int i = 0; i < count; i++) {
                int seq = mPendingEvents.keyAt(i);
                Message msg = mHandler.obtainMessage(InputEventHandler.MSG_FLUSH_INPUT_EVENT, seq, 0);
                msg.setAsynchronous(true);
                msg.sendToTarget();
            }
        }

        private PendingEvent obtainPendingEventLocked(InputEvent event, Object token,
                FinishedInputEventCallback callback, Handler handler) {
            PendingEvent p = mPendingEventPool.acquire();
            if (p == null) {
                p = new PendingEvent();
            }
            p.mEvent = event;
            p.mToken = token;
            p.mCallback = callback;
            p.mHandler = handler;
            return p;
        }

        private void recyclePendingEventLocked(PendingEvent p) {
            p.recycle();
            mPendingEventPool.release(p);
        }

        private void releaseInternal() {
            mToken = null;
            synchronized (mHandler) {
                if (mChannel != null) {
                    if (mSender != null) {
                        flushPendingEventsLocked();
                        mSender.dispose();
                        mSender = null;
                    }
                    mChannel.dispose();
                    mChannel = null;
                }
            }
            synchronized (mSessionCallbackRecordMap) {
                mSessionCallbackRecordMap.remove(mSeq);
            }
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
            public TvInputEventSender(InputChannel inputChannel, Looper looper) {
                super(inputChannel, looper);
            }

            @Override
            public void onInputEventFinished(int seq, boolean handled) {
                finishedInputEvent(seq, handled, false);
            }
        }

        private final class PendingEvent implements Runnable {
            public InputEvent mEvent;
            public Object mToken;
            public FinishedInputEventCallback mCallback;
            public Handler mHandler;
            public boolean mHandled;

            public void recycle() {
                mEvent = null;
                mToken = null;
                mCallback = null;
                mHandler = null;
                mHandled = false;
            }

            @Override
            public void run() {
                mCallback.onFinishedInputEvent(mToken, mHandled);

                synchronized (mHandler) {
                    recyclePendingEventLocked(this);
                }
            }
        }
    }
}
