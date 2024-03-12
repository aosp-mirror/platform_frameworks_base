/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.media.tv.ad;

import android.annotation.CallbackExecutor;
import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.StringDef;
import android.annotation.SystemService;
import android.content.Context;
import android.graphics.Rect;
import android.media.tv.AdBuffer;
import android.media.tv.TvInputManager;
import android.media.tv.TvTrackInfo;
import android.media.tv.flags.Flags;
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
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * Central system API to the overall client-side TV AD architecture, which arbitrates interaction
 * between applications and AD services.
 */
@FlaggedApi(Flags.FLAG_ENABLE_AD_SERVICE_FW)
@SystemService(Context.TV_AD_SERVICE)
public final class TvAdManager {
    private static final String TAG = "TvAdManager";

    /**
     * Key for package name in app link.
     * <p>Type: String
     *
     * @see #sendAppLinkCommand(String, Bundle)
     */
    public static final String APP_LINK_KEY_PACKAGE_NAME = "package_name";

    /**
     * Key for class name in app link.
     * <p>Type: String
     *
     * @see #sendAppLinkCommand(String, Bundle)
     */
    public static final String APP_LINK_KEY_CLASS_NAME = "class_name";

    /**
     * Key for command type in app link command.
     * <p>Type: String
     *
     * @see #sendAppLinkCommand(String, Bundle)
     */
    public static final String APP_LINK_KEY_COMMAND_TYPE = "command_type";

    /**
     * Key for service ID in app link command.
     * <p>Type: String
     *
     * @see #sendAppLinkCommand(String, Bundle)
     */
    public static final String APP_LINK_KEY_SERVICE_ID = "service_id";

    /**
     * Key for back URI in app link command.
     * <p>Type: String
     *
     * @see #sendAppLinkCommand(String, Bundle)
     */
    public static final String APP_LINK_KEY_BACK_URI = "back_uri";

    /**
     * Broadcast intent action to send app command to TV app.
     *
     * @see #sendAppLinkCommand(String, Bundle)
     */
    public static final String ACTION_APP_LINK_COMMAND =
            "android.media.tv.ad.action.APP_LINK_COMMAND";

    /**
     * Intent key for TV input ID. It's used to send app command to TV app.
     * <p>Type: String
     *
     * @see #sendAppLinkCommand(String, Bundle)
     * @see #ACTION_APP_LINK_COMMAND
     */
    public static final String INTENT_KEY_TV_INPUT_ID = "tv_input_id";

    /**
     * Intent key for TV AD service ID. It's used to send app command to TV app.
     * <p>Type: String
     *
     * @see #sendAppLinkCommand(String, Bundle)
     * @see #ACTION_APP_LINK_COMMAND
     * @see TvAdServiceInfo#getId()
     */
    public static final String INTENT_KEY_AD_SERVICE_ID = "ad_service_id";

    /**
     * Intent key for TV channel URI. It's used to send app command to TV app.
     * <p>Type: android.net.Uri
     *
     * @see #sendAppLinkCommand(String, Bundle)
     * @see #ACTION_APP_LINK_COMMAND
     */
    public static final String INTENT_KEY_CHANNEL_URI = "channel_uri";

    /**
     * Intent key for command type. It's used to send app command to TV app. The value of this key
     * could vary according to TV apps.
     * <p>Type: String
     *
     * @see #sendAppLinkCommand(String, Bundle)
     * @see #ACTION_APP_LINK_COMMAND
     */
    public static final String INTENT_KEY_COMMAND_TYPE = "command_type";

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @StringDef(prefix = "SESSION_DATA_TYPE_", value = {
            SESSION_DATA_TYPE_AD_REQUEST,
            SESSION_DATA_TYPE_AD_BUFFER_READY,
            SESSION_DATA_TYPE_BROADCAST_INFO_REQUEST,
            SESSION_DATA_TYPE_REMOVE_BROADCAST_INFO_REQUEST})
    public @interface SessionDataType {}

    /**
     * Sends an advertisement request to be processed by the related TV input.
     *
     * @see TvAdService.Session#sendTvAdSessionData(String, Bundle)
     * @see SESSION_DATA_KEY_AD_REQUEST
     */
    public static final String SESSION_DATA_TYPE_AD_REQUEST = "ad_request";

    /**
     * Notifies the advertisement buffer is ready.
     *
     * @see TvAdService.Session#sendTvAdSessionData(String, Bundle)
     * @see SESSION_DATA_KEY_AD_BUFFER
     */
    public static final String SESSION_DATA_TYPE_AD_BUFFER_READY = "ad_buffer_ready";

    /**
     * Sends request for broadcast info.
     *
     * @see TvAdService.Session#sendTvAdSessionData(String, Bundle)
     * @see SESSION_DATA_KEY_BROADCAST_INFO_RESQUEST
     */
    public static final String SESSION_DATA_TYPE_BROADCAST_INFO_REQUEST = "broadcast_info_request";

    /**
     * Removes request for broadcast info.
     *
     * @see TvAdService.Session#sendTvAdSessionData(String, Bundle)
     * @see SESSION_DATA_KEY_BROADCAST_INFO_REQUEST_ID
     */
    public static final String SESSION_DATA_TYPE_REMOVE_BROADCAST_INFO_REQUEST =
            "remove_broadcast_info_request";


    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @StringDef(prefix = "SESSION_DATA_KEY_", value = {
            SESSION_DATA_KEY_AD_REQUEST,
            SESSION_DATA_KEY_AD_BUFFER,
            SESSION_DATA_KEY_BROADCAST_INFO_REQUEST,
            SESSION_DATA_KEY_REQUEST_ID})
    public @interface SessionDataKey {}

    /**
     * An object of {@link android.media.tv.AdRequest}.
     *
     * <p> Type: android.media.tv.AdRequest
     *
     * @see TvAdService.Session#sendTvAdSessionData(String, Bundle)
     */
    public static final String SESSION_DATA_KEY_AD_REQUEST = "ad_request";

    /**
     * An object of {@link AdBuffer}.
     *
     * <p> Type: android.media.tv.AdBuffer
     *
     * @see TvAdService.Session#sendTvAdSessionData(String, Bundle)
     */
    public static final String SESSION_DATA_KEY_AD_BUFFER = "ad_buffer";

    /**
     * An object of {@link android.media.tv.BroadcastInfoRequest}.
     *
     * <p> Type: android.media.tv.BroadcastInfoRequest
     *
     * @see TvAdService.Session#sendTvAdSessionData(String, Bundle)
     */
    public static final String SESSION_DATA_KEY_BROADCAST_INFO_REQUEST = "broadcast_info_request";

    /**
     * The ID of {@link android.media.tv.BroadcastInfoRequest}.
     *
     * <p> Type: Integer
     *
     * @see TvAdService.Session#sendTvAdSessionData(String, Bundle)
     */
    public static final String SESSION_DATA_KEY_REQUEST_ID = "request_id";


    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(flag = false, prefix = "SESSION_STATE_", value = {
            SESSION_STATE_STOPPED,
            SESSION_STATE_RUNNING,
            SESSION_STATE_ERROR})
    public @interface SessionState {}

    /**
     * Stopped (or not started) state of AD service session.
     */
    public static final int SESSION_STATE_STOPPED = 1;
    /**
     * Running state of AD service session.
     */
    public static final int SESSION_STATE_RUNNING = 2;
    /**
     * Error state of AD service session.
     */
    public static final int SESSION_STATE_ERROR = 3;


    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(flag = false, prefix = "ERROR_", value = {
            ERROR_NONE,
            ERROR_UNKNOWN,
            ERROR_NOT_SUPPORTED,
            ERROR_WEAK_SIGNAL,
            ERROR_RESOURCE_UNAVAILABLE,
            ERROR_BLOCKED,
            ERROR_ENCRYPTED,
            ERROR_UNKNOWN_CHANNEL,
    })
    public @interface ErrorCode {}

    /**
     * No error.
     */
    public static final int ERROR_NONE = 0;
    /**
     * Unknown error code.
     */
    public static final int ERROR_UNKNOWN = 1;
    /**
     * Error code for an unsupported channel.
     */
    public static final int ERROR_NOT_SUPPORTED = 2;
    /**
     * Error code for weak signal.
     */
    public static final int ERROR_WEAK_SIGNAL = 3;
    /**
     * Error code when resource (e.g. tuner) is unavailable.
     */
    public static final int ERROR_RESOURCE_UNAVAILABLE = 4;
    /**
     * Error code for blocked contents.
     */
    public static final int ERROR_BLOCKED = 5;
    /**
     * Error code when the key or module is missing for the encrypted channel.
     */
    public static final int ERROR_ENCRYPTED = 6;
    /**
     * Error code when the current channel is an unknown channel.
     */
    public static final int ERROR_UNKNOWN_CHANNEL = 7;

    private final ITvAdManager mService;
    private final int mUserId;

    // A mapping from the sequence number of a session to its SessionCallbackRecord.
    private final SparseArray<SessionCallbackRecord> mSessionCallbackRecordMap =
            new SparseArray<>();

    // @GuardedBy("mLock")
    private final List<TvAdServiceCallbackRecord> mCallbackRecords = new ArrayList<>();

    // A sequence number for the next session to be created. Should be protected by a lock
    // {@code mSessionCallbackRecordMap}.
    private int mNextSeq;

    private final Object mLock = new Object();
    private final ITvAdClient mClient;

    /** @hide */
    public TvAdManager(ITvAdManager service, int userId) {
        mService = service;
        mUserId = userId;
        mClient = new ITvAdClient.Stub() {
            @Override
            public void onSessionCreated(String serviceId, IBinder token, InputChannel channel,
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
            public void onRequestCurrentVideoBounds(int seq) {
                synchronized (mSessionCallbackRecordMap) {
                    SessionCallbackRecord record = mSessionCallbackRecordMap.get(seq);
                    if (record == null) {
                        Log.e(TAG, "Callback not found for seq " + seq);
                        return;
                    }
                    record.postRequestCurrentVideoBounds();
                }
            }

            @Override
            public void onRequestCurrentChannelUri(int seq) {
                synchronized (mSessionCallbackRecordMap) {
                    SessionCallbackRecord record = mSessionCallbackRecordMap.get(seq);
                    if (record == null) {
                        Log.e(TAG, "Callback not found for seq " + seq);
                        return;
                    }
                    record.postRequestCurrentChannelUri();
                }
            }

            @Override
            public void onRequestTrackInfoList(int seq) {
                synchronized (mSessionCallbackRecordMap) {
                    SessionCallbackRecord record = mSessionCallbackRecordMap.get(seq);
                    if (record == null) {
                        Log.e(TAG, "Callback not found for seq " + seq);
                        return;
                    }
                    record.postRequestTrackInfoList();
                }
            }

            @Override
            public void onRequestCurrentTvInputId(int seq) {
                synchronized (mSessionCallbackRecordMap) {
                    SessionCallbackRecord record = mSessionCallbackRecordMap.get(seq);
                    if (record == null) {
                        Log.e(TAG, "Callback not found for seq " + seq);
                        return;
                    }
                    record.postRequestCurrentTvInputId();
                }
            }

            @Override
            public void onRequestSigning(
                    String id, String algorithm, String alias, byte[] data, int seq) {
                synchronized (mSessionCallbackRecordMap) {
                    SessionCallbackRecord record = mSessionCallbackRecordMap.get(seq);
                    if (record == null) {
                        Log.e(TAG, "Callback not found for seq " + seq);
                        return;
                    }
                    record.postRequestSigning(id, algorithm, alias, data);
                }
            }

            @Override
            public void onTvAdSessionData(String type, Bundle data, int seq) {
                synchronized (mSessionCallbackRecordMap) {
                    SessionCallbackRecord record = mSessionCallbackRecordMap.get(seq);
                    if (record == null) {
                        Log.e(TAG, "Callback not found for seq " + seq);
                        return;
                    }
                    record.postTvAdSessionData(type, data);
                }
            }

        };

        ITvAdManagerCallback managerCallback =
                new ITvAdManagerCallback.Stub() {
                    @Override
                    public void onAdServiceAdded(String serviceId) {
                        synchronized (mLock) {
                            for (TvAdServiceCallbackRecord record : mCallbackRecords) {
                                record.postAdServiceAdded(serviceId);
                            }
                        }
                    }

                    @Override
                    public void onAdServiceRemoved(String serviceId) {
                        synchronized (mLock) {
                            for (TvAdServiceCallbackRecord record : mCallbackRecords) {
                                record.postAdServiceRemoved(serviceId);
                            }
                        }
                    }

                    @Override
                    public void onAdServiceUpdated(String serviceId) {
                        synchronized (mLock) {
                            for (TvAdServiceCallbackRecord record : mCallbackRecords) {
                                record.postAdServiceUpdated(serviceId);
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
     * Returns the complete list of TV AD services on the system.
     *
     * @return List of {@link TvAdServiceInfo} for each TV AD service that describes its meta
     * information.
     */
    @NonNull
    public List<TvAdServiceInfo> getTvAdServiceList() {
        try {
            return mService.getTvAdServiceList(mUserId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Creates a {@link Session} for a given TV AD service.
     *
     * <p>The number of sessions that can be created at the same time is limited by the capability
     * of the given AD service.
     *
     * @param serviceId The ID of the AD service.
     * @param callback A callback used to receive the created session.
     * @param handler A {@link Handler} that the session creation will be delivered to.
     * @hide
     */
    public void createSession(
            @NonNull String serviceId,
            @NonNull String type,
            @NonNull final TvAdManager.SessionCallback callback,
            @NonNull Handler handler) {
        createSessionInternal(serviceId, type, callback, handler);
    }

    private void createSessionInternal(String serviceId, String type,
            TvAdManager.SessionCallback callback, Handler handler) {
        Preconditions.checkNotNull(serviceId);
        Preconditions.checkNotNull(type);
        Preconditions.checkNotNull(callback);
        Preconditions.checkNotNull(handler);
        TvAdManager.SessionCallbackRecord
                record = new TvAdManager.SessionCallbackRecord(callback, handler);
        synchronized (mSessionCallbackRecordMap) {
            int seq = mNextSeq++;
            mSessionCallbackRecordMap.put(seq, record);
            try {
                mService.createSession(mClient, serviceId, type, seq, mUserId);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Sends app link command.
     *
     * @param serviceId The ID of TV AD service which the command to be sent to. The ID can be found
     *                  in {@link TvAdServiceInfo#getId()}.
     * @param command The command to be sent. The command is a bundle with the following keys:
     *                <ul>
     *                <li>{@link #APP_LINK_KEY_PACKAGE_NAME}: The package name of the app to be
     *                launched.
     *                <li>{@link #APP_LINK_KEY_CLASS_NAME}: The class name of the app to be
     *                launched.
     *                <li>{@link #APP_LINK_KEY_COMMAND_TYPE}: The command type.
     *                <li>{@link #APP_LINK_KEY_SERVICE_ID}: The ID of the TV AD service.
     *                <li>{@link #APP_LINK_KEY_BACK_URI}: The URI to be used to return to the
     *                previous app.
     *                </ul>
     */
    public void sendAppLinkCommand(@NonNull String serviceId, @NonNull Bundle command) {
        try {
            mService.sendAppLinkCommand(serviceId, command, mUserId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Registers a {@link TvAdServiceCallback}.
     *
     * @param callback A callback used to monitor status of the TV AD services.
     * @param executor A {@link Executor} that the status change will be delivered to.
     */
    public void registerCallback(
            @CallbackExecutor @NonNull Executor executor,
            @NonNull TvAdServiceCallback callback) {
        Preconditions.checkNotNull(callback);
        Preconditions.checkNotNull(executor);
        synchronized (mLock) {
            mCallbackRecords.add(new TvAdServiceCallbackRecord(callback, executor));
        }
    }

    /**
     * Unregisters the existing {@link TvAdServiceCallback}.
     *
     * @param callback The existing callback to remove.
     */
    public void unregisterCallback(@NonNull final TvAdServiceCallback callback) {
        Preconditions.checkNotNull(callback);
        synchronized (mLock) {
            for (Iterator<TvAdServiceCallbackRecord> it = mCallbackRecords.iterator();
                    it.hasNext(); ) {
                TvAdServiceCallbackRecord record = it.next();
                if (record.getCallback() == callback) {
                    it.remove();
                    break;
                }
            }
        }
    }

    /**
     * The Session provides the per-session functionality of AD service.
     * @hide
     */
    public static final class Session {
        static final int DISPATCH_IN_PROGRESS = -1;
        static final int DISPATCH_NOT_HANDLED = 0;
        static final int DISPATCH_HANDLED = 1;

        private static final long INPUT_SESSION_NOT_RESPONDING_TIMEOUT = 2500;
        private final ITvAdManager mService;
        private final int mUserId;
        private final int mSeq;
        private final SparseArray<SessionCallbackRecord> mSessionCallbackRecordMap;

        // For scheduling input event handling on the main thread. This also serves as a lock to
        // protect pending input events and the input channel.
        private final InputEventHandler mHandler = new InputEventHandler(Looper.getMainLooper());

        private TvInputManager.Session mInputSession;
        private final Pools.Pool<PendingEvent> mPendingEventPool = new Pools.SimplePool<>(20);
        private final SparseArray<PendingEvent> mPendingEvents = new SparseArray<>(20);
        private TvInputEventSender mSender;
        private InputChannel mInputChannel;
        private IBinder mToken;

        private Session(IBinder token, InputChannel channel, ITvAdManager service, int userId,
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
         * Sets the {@link android.view.Surface} for this session.
         *
         * @param surface A {@link android.view.Surface} used to render AD.
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
         * @param view A view for AD service.
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

        void startAdService() {
            if (mToken == null) {
                Log.w(TAG, "The session has been already released");
                return;
            }
            try {
                mService.startAdService(mToken, mUserId);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        void stopAdService() {
            if (mToken == null) {
                Log.w(TAG, "The session has been already released");
                return;
            }
            try {
                mService.stopAdService(mToken, mUserId);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        void resetAdService() {
            if (mToken == null) {
                Log.w(TAG, "The session has been already released");
                return;
            }
            try {
                mService.resetAdService(mToken, mUserId);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        void sendCurrentVideoBounds(@NonNull Rect bounds) {
            if (mToken == null) {
                Log.w(TAG, "The session has been already released");
                return;
            }
            try {
                mService.sendCurrentVideoBounds(mToken, bounds, mUserId);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        void sendCurrentChannelUri(@Nullable Uri channelUri) {
            if (mToken == null) {
                Log.w(TAG, "The session has been already released");
                return;
            }
            try {
                mService.sendCurrentChannelUri(mToken, channelUri, mUserId);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        void sendTrackInfoList(@NonNull List<TvTrackInfo> tracks) {
            if (mToken == null) {
                Log.w(TAG, "The session has been already released");
                return;
            }
            try {
                mService.sendTrackInfoList(mToken, tracks, mUserId);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        void sendCurrentTvInputId(@Nullable String inputId) {
            if (mToken == null) {
                Log.w(TAG, "The session has been already released");
                return;
            }
            try {
                mService.sendCurrentTvInputId(mToken, inputId, mUserId);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        void sendSigningResult(@NonNull String signingId, @NonNull byte[] result) {
            if (mToken == null) {
                Log.w(TAG, "The session has been already released");
                return;
            }
            try {
                mService.sendSigningResult(mToken, signingId, result, mUserId);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        void notifyError(@NonNull String errMsg, @NonNull Bundle params) {
            if (mToken == null) {
                Log.w(TAG, "The session has been already released");
                return;
            }
            try {
                mService.notifyError(mToken, errMsg, params, mUserId);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        /**
         * Notifies AD service session when a new TV message is received.
         */
        public void notifyTvMessage(int type, Bundle data) {
            if (mToken == null) {
                Log.w(TAG, "The session has been already released");
                return;
            }
            try {
                mService.notifyTvMessage(mToken, type, data, mUserId);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        /**
         * Notifies data from session of linked TvInputService.
         */
        public void notifyTvInputSessionData(String type, Bundle data) {
            if (mToken == null) {
                Log.w(TAG, "The session has been already released");
                return;
            }
            try {
                mService.notifyTvInputSessionData(mToken, type, data, mUserId);
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
            public Session.FinishedInputEventCallback mCallback;
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


    /**
     * Interface used to receive the created session.
     * @hide
     */
    public abstract static class SessionCallback {
        /**
         * This is called after {@link TvAdManager#createSession} has been processed.
         *
         * @param session A {@link TvAdManager.Session} instance created. This can be
         *                {@code null} if the creation request failed.
         */
        public void onSessionCreated(@Nullable Session session) {
        }

        /**
         * This is called when {@link TvAdManager.Session} is released.
         * This typically happens when the process hosting the session has crashed or been killed.
         *
         * @param session the {@link TvAdManager.Session} instance released.
         */
        public void onSessionReleased(@NonNull Session session) {
        }

        /**
         * This is called when {@link TvAdService.Session#layoutSurface} is called to
         * change the layout of surface.
         *
         * @param session A {@link TvAdManager.Session} associated with this callback.
         * @param left Left position.
         * @param top Top position.
         * @param right Right position.
         * @param bottom Bottom position.
         */
        public void onLayoutSurface(Session session, int left, int top, int right, int bottom) {
        }

        /**
         * This is called when {@link TvAdService.Session#requestCurrentVideoBounds} is
         * called.
         *
         * @param session A {@link TvAdService.Session} associated with this callback.
         */
        public void onRequestCurrentVideoBounds(Session session) {
        }

        /**
         * This is called when {@link TvAdService.Session#requestCurrentChannelUri} is
         * called.
         *
         * @param session A {@link TvAdService.Session} associated with this callback.
         */
        public void onRequestCurrentChannelUri(Session session) {
        }

        /**
         * This is called when {@link TvAdService.Session#requestTrackInfoList} is
         * called.
         *
         * @param session A {@link TvAdService.Session} associated with this callback.
         */
        public void onRequestTrackInfoList(Session session) {
        }

        /**
         * This is called when {@link TvAdService.Session#requestCurrentTvInputId} is
         * called.
         *
         * @param session A {@link TvAdService.Session} associated with this callback.
         */
        public void onRequestCurrentTvInputId(Session session) {
        }

        /**
         * This is called when
         * {@link TvAdService.Session#requestSigning(String, String, String, byte[])} is
         * called.
         *
         * @param session A {@link TvAdService.Session} associated with this callback.
         * @param signingId the ID to identify the request.
         * @param algorithm the standard name of the signature algorithm requested, such as
         *                  MD5withRSA, SHA256withDSA, etc.
         * @param alias the alias of the corresponding {@link java.security.KeyStore}.
         * @param data the original bytes to be signed.
         */
        public void onRequestSigning(
                Session session, String signingId, String algorithm, String alias, byte[] data) {
        }

    }

    /**
     * Callback used to monitor status of the TV advertisement service.
     */
    public abstract static class TvAdServiceCallback {
        /**
         * This is called when a TV AD service is added to the system.
         *
         * <p>Normally it happens when the user installs a new TV AD service package that implements
         * {@link TvAdService} interface.
         *
         * @param serviceId The ID of the TV AD service.
         */
        public void onAdServiceAdded(@NonNull String serviceId) {
        }

        /**
         * This is called when a TV AD service is removed from the system.
         *
         * <p>Normally it happens when the user uninstalls the previously installed TV AD service
         * package.
         *
         * @param serviceId The ID of the TV AD service.
         */
        public void onAdServiceRemoved(@NonNull String serviceId) {
        }

        /**
         * This is called when a TV AD service is updated on the system.
         *
         * <p>Normally it happens when a previously installed TV AD service package is re-installed
         * or a newer version of the package exists becomes available/unavailable.
         *
         * @param serviceId The ID of the TV AD service.
         */
        public void onAdServiceUpdated(@NonNull String serviceId) {
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

        void postRequestCurrentVideoBounds() {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mSessionCallback.onRequestCurrentVideoBounds(mSession);
                }
            });
        }

        void postRequestCurrentChannelUri() {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mSessionCallback.onRequestCurrentChannelUri(mSession);
                }
            });
        }

        void postRequestTrackInfoList() {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mSessionCallback.onRequestTrackInfoList(mSession);
                }
            });
        }

        void postRequestCurrentTvInputId() {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mSessionCallback.onRequestCurrentTvInputId(mSession);
                }
            });
        }

        void postRequestSigning(String id, String algorithm, String alias, byte[] data) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mSessionCallback.onRequestSigning(mSession, id, algorithm, alias, data);
                }
            });
        }

        void postTvAdSessionData(String type, Bundle data) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (mSession.getInputSession() != null) {
                        mSession.getInputSession().notifyTvAdSessionData(type, data);
                    }
                }
            });
        }
    }

    private static final class TvAdServiceCallbackRecord {
        private final TvAdServiceCallback mCallback;
        private final Executor mExecutor;

        TvAdServiceCallbackRecord(TvAdServiceCallback callback, Executor executor) {
            mCallback = callback;
            mExecutor = executor;
        }

        public TvAdServiceCallback getCallback() {
            return mCallback;
        }

        public void postAdServiceAdded(final String serviceId) {
            mExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    mCallback.onAdServiceAdded(serviceId);
                }
            });
        }

        public void postAdServiceRemoved(final String serviceId) {
            mExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    mCallback.onAdServiceRemoved(serviceId);
                }
            });
        }

        public void postAdServiceUpdated(final String serviceId) {
            mExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    mCallback.onAdServiceUpdated(serviceId);
                }
            });
        }
    }
}
