/*
 * Copyright 2021 The Android Open Source Project
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

import android.annotation.CallbackExecutor;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemService;
import android.content.Context;
import android.graphics.Rect;
import android.media.PlaybackParams;
import android.media.tv.AdBuffer;
import android.media.tv.AdRequest;
import android.media.tv.AdResponse;
import android.media.tv.BroadcastInfoRequest;
import android.media.tv.BroadcastInfoResponse;
import android.media.tv.TvContentRating;
import android.media.tv.TvInputManager;
import android.media.tv.TvRecordingInfo;
import android.media.tv.TvTrackInfo;
import android.net.Uri;
import android.net.http.SslCertificate;
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
 * Central system API to the overall TV interactive application framework (TIAF) architecture, which
 * arbitrates interaction between Android applications and TV interactive apps.
 */
@SystemService(Context.TV_INTERACTIVE_APP_SERVICE)
public final class TvInteractiveAppManager {
    // TODO: cleanup and unhide public APIs
    private static final String TAG = "TvInteractiveAppManager";

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(flag = false, prefix = "SERVICE_STATE_", value = {
            SERVICE_STATE_UNREALIZED,
            SERVICE_STATE_PREPARING,
            SERVICE_STATE_READY,
            SERVICE_STATE_ERROR})
    public @interface ServiceState {}

    /**
     * Unrealized state of interactive app service.
     */
    public static final int SERVICE_STATE_UNREALIZED = 1;
    /**
     * Preparing state of interactive app service.
     */
    public static final int SERVICE_STATE_PREPARING = 2;
    /**
     * Ready state of interactive app service.
     *
     * <p>In this state, the interactive app service is ready, and interactive apps can be started.
     *
     * @see TvInteractiveAppView#startInteractiveApp()
     */
    public static final int SERVICE_STATE_READY = 3;
    /**
     * Error state of interactive app service.
     */
    public static final int SERVICE_STATE_ERROR = 4;


    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(flag = false, prefix = "INTERACTIVE_APP_STATE_", value = {
            INTERACTIVE_APP_STATE_STOPPED,
            INTERACTIVE_APP_STATE_RUNNING,
            INTERACTIVE_APP_STATE_ERROR})
    public @interface InteractiveAppState {}

    /**
     * Stopped (or not started) state of interactive application.
     */
    public static final int INTERACTIVE_APP_STATE_STOPPED = 1;
    /**
     * Running state of interactive application.
     */
    public static final int INTERACTIVE_APP_STATE_RUNNING = 2;
    /**
     * Error state of interactive application.
     */
    public static final int INTERACTIVE_APP_STATE_ERROR = 3;


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

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(flag = false, prefix = "TELETEXT_APP_STATE_", value = {
            TELETEXT_APP_STATE_SHOW,
            TELETEXT_APP_STATE_HIDE,
            TELETEXT_APP_STATE_ERROR})
    public @interface TeletextAppState {}

    /**
     * State of Teletext app: show
     */
    public static final int TELETEXT_APP_STATE_SHOW = 1;
    /**
     * State of Teletext app: hide
     */
    public static final int TELETEXT_APP_STATE_HIDE = 2;
    /**
     * State of Teletext app: error
     */
    public static final int TELETEXT_APP_STATE_ERROR = 3;

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
            "android.media.tv.interactive.action.APP_LINK_COMMAND";

    /**
     * Intent key for TV input ID. It's used to send app command to TV app.
     * <p>Type: String
     *
     * @see #sendAppLinkCommand(String, Bundle)
     * @see #ACTION_APP_LINK_COMMAND
     */
    public static final String INTENT_KEY_TV_INPUT_ID = "tv_input_id";

    /**
     * Intent key for TV interactive app ID. It's used to send app command to TV app.
     * <p>Type: String
     *
     * @see #sendAppLinkCommand(String, Bundle)
     * @see #ACTION_APP_LINK_COMMAND
     * @see TvInteractiveAppServiceInfo#getId()
     */
    public static final String INTENT_KEY_INTERACTIVE_APP_SERVICE_ID = "interactive_app_id";

    /**
     * Intent key for TV channel URI. It's used to send app command to TV app.
     * <p>Type: android.net.Uri
     *
     * @see #sendAppLinkCommand(String, Bundle)
     * @see #ACTION_APP_LINK_COMMAND
     */
    public static final String INTENT_KEY_CHANNEL_URI = "channel_uri";

    /**
     * Intent key for broadcast-independent(BI) interactive app type. It's used to send app command
     * to TV app.
     * <p>Type: int
     *
     * @see #sendAppLinkCommand(String, Bundle)
     * @see #ACTION_APP_LINK_COMMAND
     * @see android.media.tv.interactive.TvInteractiveAppServiceInfo#getSupportedTypes()
     * @see android.media.tv.interactive.TvInteractiveAppView#createBiInteractiveApp(Uri, Bundle)
     */
    public static final String INTENT_KEY_BI_INTERACTIVE_APP_TYPE = "bi_interactive_app_type";

    /**
     * Intent key for broadcast-independent(BI) interactive app URI. It's used to send app command
     * to TV app.
     * <p>Type: android.net.Uri
     *
     * @see #sendAppLinkCommand(String, Bundle)
     * @see #ACTION_APP_LINK_COMMAND
     * @see android.media.tv.interactive.TvInteractiveAppView#createBiInteractiveApp(Uri, Bundle)
     */
    public static final String INTENT_KEY_BI_INTERACTIVE_APP_URI = "bi_interactive_app_uri";

    /**
     * Intent key for command type. It's used to send app command to TV app. The value of this key
     * could vary according to TV apps.
     * <p>Type: String
     *
     * @see #sendAppLinkCommand(String, Bundle)
     * @see #ACTION_APP_LINK_COMMAND
     */
    public static final String INTENT_KEY_COMMAND_TYPE = "command_type";

    private final ITvInteractiveAppManager mService;
    private final int mUserId;

    // A mapping from the sequence number of a session to its SessionCallbackRecord.
    private final SparseArray<SessionCallbackRecord> mSessionCallbackRecordMap =
            new SparseArray<>();

    // @GuardedBy("mLock")
    private final List<TvInteractiveAppCallbackRecord> mCallbackRecords = new ArrayList<>();

    // A sequence number for the next session to be created. Should be protected by a lock
    // {@code mSessionCallbackRecordMap}.
    private int mNextSeq;

    private final Object mLock = new Object();

    private final ITvInteractiveAppClient mClient;

    /** @hide */
    public TvInteractiveAppManager(ITvInteractiveAppManager service, int userId) {
        mService = service;
        mUserId = userId;
        mClient = new ITvInteractiveAppClient.Stub() {
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
            public void onRemoveBroadcastInfo(int requestId, int seq) {
                synchronized (mSessionCallbackRecordMap) {
                    SessionCallbackRecord record = mSessionCallbackRecordMap.get(seq);
                    if (record == null) {
                        Log.e(TAG, "Callback not found for seq " + seq);
                        return;
                    }
                    record.postRemoveBroadcastInfo(requestId);
                }
            }

            @Override
            public void onCommandRequest(
                    @TvInteractiveAppService.PlaybackCommandType String cmdType,
                    Bundle parameters,
                    int seq) {
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
            public void onTimeShiftCommandRequest(
                    @TvInteractiveAppService.TimeShiftCommandType String cmdType,
                    Bundle parameters,
                    int seq) {
                synchronized (mSessionCallbackRecordMap) {
                    SessionCallbackRecord record = mSessionCallbackRecordMap.get(seq);
                    if (record == null) {
                        Log.e(TAG, "Callback not found for seq " + seq);
                        return;
                    }
                    record.postTimeShiftCommandRequest(cmdType, parameters);
                }
            }

            @Override
            public void onSetVideoBounds(Rect rect, int seq) {
                synchronized (mSessionCallbackRecordMap) {
                    SessionCallbackRecord record = mSessionCallbackRecordMap.get(seq);
                    if (record == null) {
                        Log.e(TAG, "Callback not found for seq " + seq);
                        return;
                    }
                    record.postSetVideoBounds(rect);
                }
            }

            @Override
            public void onAdRequest(AdRequest request, int seq) {
                synchronized (mSessionCallbackRecordMap) {
                    SessionCallbackRecord record = mSessionCallbackRecordMap.get(seq);
                    if (record == null) {
                        Log.e(TAG, "Callback not found for seq " + seq);
                        return;
                    }
                    record.postAdRequest(request);
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
            public void onRequestCurrentChannelLcn(int seq) {
                synchronized (mSessionCallbackRecordMap) {
                    SessionCallbackRecord record = mSessionCallbackRecordMap.get(seq);
                    if (record == null) {
                        Log.e(TAG, "Callback not found for seq " + seq);
                        return;
                    }
                    record.postRequestCurrentChannelLcn();
                }
            }

            @Override
            public void onRequestStreamVolume(int seq) {
                synchronized (mSessionCallbackRecordMap) {
                    SessionCallbackRecord record = mSessionCallbackRecordMap.get(seq);
                    if (record == null) {
                        Log.e(TAG, "Callback not found for seq " + seq);
                        return;
                    }
                    record.postRequestStreamVolume();
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
            public void onRequestSelectedTrackInfo(int seq) {
                synchronized (mSessionCallbackRecordMap) {
                    SessionCallbackRecord record = mSessionCallbackRecordMap.get(seq);
                    if (record == null) {
                        Log.e(TAG, "Callback not found for seq " + seq);
                        return;
                    }
                    record.postRequestSelectedTrackInfo();
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
            public void onRequestTimeShiftMode(int seq) {
                synchronized (mSessionCallbackRecordMap) {
                    SessionCallbackRecord record = mSessionCallbackRecordMap.get(seq);
                    if (record == null) {
                        Log.e(TAG, "Callback not found for seq " + seq);
                        return;
                    }
                    record.postRequestTimeShiftMode();
                }
            }

            @Override
            public void onRequestAvailableSpeeds(int seq) {
                synchronized (mSessionCallbackRecordMap) {
                    SessionCallbackRecord record = mSessionCallbackRecordMap.get(seq);
                    if (record == null) {
                        Log.e(TAG, "Callback not found for seq " + seq);
                        return;
                    }
                    record.postRequestAvailableSpeeds();
                }
            }

            @Override
            public void onRequestStartRecording(String requestId, Uri programUri, int seq) {
                synchronized (mSessionCallbackRecordMap) {
                    SessionCallbackRecord record = mSessionCallbackRecordMap.get(seq);
                    if (record == null) {
                        Log.e(TAG, "Callback not found for seq " + seq);
                        return;
                    }
                    record.postRequestStartRecording(requestId, programUri);
                }
            }

            @Override
            public void onRequestStopRecording(String recordingId, int seq) {
                synchronized (mSessionCallbackRecordMap) {
                    SessionCallbackRecord record = mSessionCallbackRecordMap.get(seq);
                    if (record == null) {
                        Log.e(TAG, "Callback not found for seq " + seq);
                        return;
                    }
                    record.postRequestStopRecording(recordingId);
                }
            }

            @Override
            public void onRequestScheduleRecording(String requestId, String inputId, Uri channelUri,
                    Uri programUri, Bundle params, int seq) {
                synchronized (mSessionCallbackRecordMap) {
                    SessionCallbackRecord record = mSessionCallbackRecordMap.get(seq);
                    if (record == null) {
                        Log.e(TAG, "Callback not found for seq " + seq);
                        return;
                    }
                    record.postRequestScheduleRecording(
                            requestId, inputId, channelUri, programUri, params);
                }
            }

            @Override
            public void onRequestScheduleRecording2(String requestId, String inputId,
                    Uri channelUri, long startTime, long duration, int repeatDays, Bundle params,
                    int seq) {
                synchronized (mSessionCallbackRecordMap) {
                    SessionCallbackRecord record = mSessionCallbackRecordMap.get(seq);
                    if (record == null) {
                        Log.e(TAG, "Callback not found for seq " + seq);
                        return;
                    }
                    record.postRequestScheduleRecording(requestId, inputId, channelUri, startTime,
                            duration, repeatDays, params);
                }
            }

            @Override
            public void onSetTvRecordingInfo(String recordingId, TvRecordingInfo recordingInfo,
                    int seq) {
                synchronized (mSessionCallbackRecordMap) {
                    SessionCallbackRecord record = mSessionCallbackRecordMap.get(seq);
                    if (record == null) {
                        Log.e(TAG, "Callback not found for seq " + seq);
                        return;
                    }
                    record.postSetTvRecordingInfo(recordingId, recordingInfo);
                }
            }

            @Override
            public void onRequestTvRecordingInfo(String recordingId, int seq) {
                synchronized (mSessionCallbackRecordMap) {
                    SessionCallbackRecord record = mSessionCallbackRecordMap.get(seq);
                    if (record == null) {
                        Log.e(TAG, "Callback not found for seq " + seq);
                        return;
                    }
                    record.postRequestTvRecordingInfo(recordingId);
                }
            }

            @Override
            public void onRequestTvRecordingInfoList(int type, int seq) {
                synchronized (mSessionCallbackRecordMap) {
                    SessionCallbackRecord record = mSessionCallbackRecordMap.get(seq);
                    if (record == null) {
                        Log.e(TAG, "Callback not found for seq " + seq);
                        return;
                    }
                    record.postRequestTvRecordingInfoList(type);
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
            public void onRequestCertificate(String host, int port, int seq) {
                synchronized (mSessionCallbackRecordMap) {
                    SessionCallbackRecord record = mSessionCallbackRecordMap.get(seq);
                    if (record == null) {
                        Log.e(TAG, "Callback not found for seq " + seq);
                        return;
                    }
                    record.postRequestCertificate(host, port);
                }
            }

            @Override
            public void onSessionStateChanged(int state, int err, int seq) {
                synchronized (mSessionCallbackRecordMap) {
                    SessionCallbackRecord record = mSessionCallbackRecordMap.get(seq);
                    if (record == null) {
                        Log.e(TAG, "Callback not found for seq " + seq);
                        return;
                    }
                    record.postSessionStateChanged(state, err);
                }
            }

            @Override
            public void onBiInteractiveAppCreated(Uri biIAppUri, String biIAppId, int seq) {
                synchronized (mSessionCallbackRecordMap) {
                    SessionCallbackRecord record = mSessionCallbackRecordMap.get(seq);
                    if (record == null) {
                        Log.e(TAG, "Callback not found for seq " + seq);
                        return;
                    }
                    record.postBiInteractiveAppCreated(biIAppUri, biIAppId);
                }
            }

            @Override
            public void onTeletextAppStateChanged(int state, int seq) {
                synchronized (mSessionCallbackRecordMap) {
                    SessionCallbackRecord record = mSessionCallbackRecordMap.get(seq);
                    if (record == null) {
                        Log.e(TAG, "Callback not found for seq " + seq);
                        return;
                    }
                    record.postTeletextAppStateChanged(state);
                }
            }

            @Override
            public void onAdBufferReady(AdBuffer buffer, int seq) {
                synchronized (mSessionCallbackRecordMap) {
                    SessionCallbackRecord record = mSessionCallbackRecordMap.get(seq);
                    if (record == null) {
                        Log.e(TAG, "Callback not found for seq " + seq);
                        return;
                    }
                    record.postAdBufferReady(buffer);
                }
            }
        };
        ITvInteractiveAppManagerCallback managerCallback =
                new ITvInteractiveAppManagerCallback.Stub() {
            @Override
            public void onInteractiveAppServiceAdded(String iAppServiceId) {
                synchronized (mLock) {
                    for (TvInteractiveAppCallbackRecord record : mCallbackRecords) {
                        record.postInteractiveAppServiceAdded(iAppServiceId);
                    }
                }
            }

            @Override
            public void onInteractiveAppServiceRemoved(String iAppServiceId) {
                synchronized (mLock) {
                    for (TvInteractiveAppCallbackRecord record : mCallbackRecords) {
                        record.postInteractiveAppServiceRemoved(iAppServiceId);
                    }
                }
            }

            @Override
            public void onInteractiveAppServiceUpdated(String iAppServiceId) {
                synchronized (mLock) {
                    for (TvInteractiveAppCallbackRecord record : mCallbackRecords) {
                        record.postInteractiveAppServiceUpdated(iAppServiceId);
                    }
                }
            }

            @Override
            public void onTvInteractiveAppServiceInfoUpdated(TvInteractiveAppServiceInfo iAppInfo) {
                // TODO: add public API updateInteractiveAppInfo()
                synchronized (mLock) {
                    for (TvInteractiveAppCallbackRecord record : mCallbackRecords) {
                        record.postTvInteractiveAppServiceInfoUpdated(iAppInfo);
                    }
                }
            }

            @Override
            public void onStateChanged(String iAppServiceId, int type, int state, int err) {
                synchronized (mLock) {
                    for (TvInteractiveAppCallbackRecord record : mCallbackRecords) {
                        record.postStateChanged(iAppServiceId, type, state, err);
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
     * Callback used to monitor status of the TV Interactive App.
     */
    public abstract static class TvInteractiveAppCallback {
        /**
         * This is called when a TV Interactive App service is added to the system.
         *
         * <p>Normally it happens when the user installs a new TV Interactive App service package
         * that implements {@link TvInteractiveAppService} interface.
         *
         * @param iAppServiceId The ID of the TV Interactive App service.
         */
        public void onInteractiveAppServiceAdded(@NonNull String iAppServiceId) {
        }

        /**
         * This is called when a TV Interactive App service is removed from the system.
         *
         * <p>Normally it happens when the user uninstalls the previously installed TV Interactive
         * App service package.
         *
         * @param iAppServiceId The ID of the TV Interactive App service.
         */
        public void onInteractiveAppServiceRemoved(@NonNull String iAppServiceId) {
        }

        /**
         * This is called when a TV Interactive App service is updated on the system.
         *
         * <p>Normally it happens when a previously installed TV Interactive App service package is
         * re-installed or a newer version of the package exists becomes available/unavailable.
         *
         * @param iAppServiceId The ID of the TV Interactive App service.
         */
        public void onInteractiveAppServiceUpdated(@NonNull String iAppServiceId) {
        }

        /**
         * This is called when the information about an existing TV Interactive App service has been
         * updated.
         *
         * <p>Because the system automatically creates a <code>TvInteractiveAppServiceInfo</code>
         * object for each TV Interactive App service based on the information collected from the
         * <code>AndroidManifest.xml</code>, this method is only called back when such information
         * has changed dynamically.
         *
         * @param iAppInfo The <code>TvInteractiveAppServiceInfo</code> object that contains new
         *                 information.
         * @hide
         */
        public void onTvInteractiveAppServiceInfoUpdated(
                @NonNull TvInteractiveAppServiceInfo iAppInfo) {
        }

        /**
         * This is called when the state of the interactive app service is changed.
         *
         * @param iAppServiceId The ID of the TV Interactive App service.
         * @param type the interactive app type
         * @param state the current state of the service of the given type
         * @param err the error code for error state. {@link #ERROR_NONE} is used when the state is
         *            not {@link #SERVICE_STATE_ERROR}.
         */
        public void onTvInteractiveAppServiceStateChanged(
                @NonNull String iAppServiceId,
                @TvInteractiveAppServiceInfo.InteractiveAppType int type,
                @ServiceState int state,
                @ErrorCode int err) {
        }
    }

    private static final class TvInteractiveAppCallbackRecord {
        private final TvInteractiveAppCallback mCallback;
        private final Executor mExecutor;

        TvInteractiveAppCallbackRecord(TvInteractiveAppCallback callback, Executor executor) {
            mCallback = callback;
            mExecutor = executor;
        }

        public TvInteractiveAppCallback getCallback() {
            return mCallback;
        }

        public void postInteractiveAppServiceAdded(final String iAppServiceId) {
            mExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    mCallback.onInteractiveAppServiceAdded(iAppServiceId);
                }
            });
        }

        public void postInteractiveAppServiceRemoved(final String iAppServiceId) {
            mExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    mCallback.onInteractiveAppServiceRemoved(iAppServiceId);
                }
            });
        }

        public void postInteractiveAppServiceUpdated(final String iAppServiceId) {
            mExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    mCallback.onInteractiveAppServiceUpdated(iAppServiceId);
                }
            });
        }

        public void postTvInteractiveAppServiceInfoUpdated(
                final TvInteractiveAppServiceInfo iAppInfo) {
            mExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    mCallback.onTvInteractiveAppServiceInfoUpdated(iAppInfo);
                }
            });
        }

        public void postStateChanged(String iAppServiceId, int type, int state, int err) {
            mExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    mCallback.onTvInteractiveAppServiceStateChanged(
                            iAppServiceId, type, state, err);
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
     * Returns the complete list of TV Interactive App service on the system.
     *
     * @return List of {@link TvInteractiveAppServiceInfo} for each TV Interactive App service that
     *         describes its meta information.
     */
    @NonNull
    public List<TvInteractiveAppServiceInfo> getTvInteractiveAppServiceList() {
        try {
            return mService.getTvInteractiveAppServiceList(mUserId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns a list of available app link information.
     *
     * <P>A package must declare its app link info in its manifest using meta-data tag, so the info
     * can be detected by the system.
     *
     * @return List of {@link AppLinkInfo} for each package that deslares its app link information.
     */
    @NonNull
    public List<AppLinkInfo> getAppLinkInfoList() {
        try {
            return mService.getAppLinkInfoList(mUserId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Registers an Android application link info record which can be used to launch the specific
     * Android application by TV interactive App RTE.
     *
     * @param tvIAppServiceId The ID of TV interactive service which the command to be sent to. The
     *                        ID can be found in {@link TvInteractiveAppServiceInfo#getId()}.
     * @param appLinkInfo The Android application link info record to be registered.
     */
    public void registerAppLinkInfo(
            @NonNull String tvIAppServiceId, @NonNull AppLinkInfo appLinkInfo) {
        try {
            mService.registerAppLinkInfo(tvIAppServiceId, appLinkInfo, mUserId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Unregisters an Android application link info record which can be used to launch the specific
     * Android application by TV interactive App RTE.
     *
     * @param tvIAppServiceId The ID of TV interactive service which the command to be sent to. The
     *                        ID can be found in {@link TvInteractiveAppServiceInfo#getId()}.
     * @param appLinkInfo The Android application link info record to be unregistered.
     */
    public void unregisterAppLinkInfo(
            @NonNull String tvIAppServiceId, @NonNull AppLinkInfo appLinkInfo) {
        try {
            mService.unregisterAppLinkInfo(tvIAppServiceId, appLinkInfo, mUserId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Sends app link command.
     *
     * @param tvIAppServiceId The ID of TV interactive service which the command to be sent to. The
     *                        ID can be found in {@link TvInteractiveAppServiceInfo#getId()}.
     * @param command The command to be sent.
     */
    public void sendAppLinkCommand(@NonNull String tvIAppServiceId, @NonNull Bundle command) {
        try {
            mService.sendAppLinkCommand(tvIAppServiceId, command, mUserId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Registers a {@link TvInteractiveAppCallback}.
     *
     * @param callback A callback used to monitor status of the TV Interactive App services.
     * @param executor A {@link Executor} that the status change will be delivered to.
     */
    public void registerCallback(
            @CallbackExecutor @NonNull Executor executor,
            @NonNull TvInteractiveAppCallback callback) {
        Preconditions.checkNotNull(callback);
        Preconditions.checkNotNull(executor);
        synchronized (mLock) {
            mCallbackRecords.add(new TvInteractiveAppCallbackRecord(callback, executor));
        }
    }

    /**
     * Unregisters the existing {@link TvInteractiveAppCallback}.
     *
     * @param callback The existing callback to remove.
     */
    public void unregisterCallback(@NonNull final TvInteractiveAppCallback callback) {
        Preconditions.checkNotNull(callback);
        synchronized (mLock) {
            for (Iterator<TvInteractiveAppCallbackRecord> it = mCallbackRecords.iterator();
                    it.hasNext(); ) {
                TvInteractiveAppCallbackRecord record = it.next();
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

        private final ITvInteractiveAppManager mService;
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

        private Session(IBinder token, InputChannel channel, ITvInteractiveAppManager service,
                int userId, int seq, SparseArray<SessionCallbackRecord> sessionCallbackRecordMap) {
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

        void startInteractiveApp() {
            if (mToken == null) {
                Log.w(TAG, "The session has been already released");
                return;
            }
            try {
                mService.startInteractiveApp(mToken, mUserId);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        void stopInteractiveApp() {
            if (mToken == null) {
                Log.w(TAG, "The session has been already released");
                return;
            }
            try {
                mService.stopInteractiveApp(mToken, mUserId);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        void resetInteractiveApp() {
            if (mToken == null) {
                Log.w(TAG, "The session has been already released");
                return;
            }
            try {
                mService.resetInteractiveApp(mToken, mUserId);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        void createBiInteractiveApp(Uri biIAppUri, Bundle params) {
            if (mToken == null) {
                Log.w(TAG, "The session has been already released");
                return;
            }
            try {
                mService.createBiInteractiveApp(mToken, biIAppUri, params, mUserId);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        void destroyBiInteractiveApp(String biIAppId) {
            if (mToken == null) {
                Log.w(TAG, "The session has been already released");
                return;
            }
            try {
                mService.destroyBiInteractiveApp(mToken, biIAppId, mUserId);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        void setTeletextAppEnabled(boolean enable) {
            if (mToken == null) {
                Log.w(TAG, "The session has been already released");
                return;
            }
            try {
                mService.setTeletextAppEnabled(mToken, enable, mUserId);
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

        void sendCurrentChannelLcn(int lcn) {
            if (mToken == null) {
                Log.w(TAG, "The session has been already released");
                return;
            }
            try {
                mService.sendCurrentChannelLcn(mToken, lcn, mUserId);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        void sendStreamVolume(float volume) {
            if (mToken == null) {
                Log.w(TAG, "The session has been already released");
                return;
            }
            try {
                mService.sendStreamVolume(mToken, volume, mUserId);
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

        void sendSelectedTrackInfo(@NonNull List<TvTrackInfo> tracks) {
            if (mToken == null) {
                Log.w(TAG, "The session has been already released");
                return;
            }
            try {
                mService.sendSelectedTrackInfo(mToken, tracks, mUserId);
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

        void sendTimeShiftMode(int mode) {
            if (mToken == null) {
                Log.w(TAG, "The session has been already released");
                return;
            }
            try {
                mService.sendTimeShiftMode(mToken, mode, mUserId);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        void sendAvailableSpeeds(float[] speeds) {
            if (mToken == null) {
                Log.w(TAG, "The session has been already released");
                return;
            }
            try {
                mService.sendAvailableSpeeds(mToken, speeds, mUserId);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        void sendTvRecordingInfo(@Nullable TvRecordingInfo recordingInfo) {
            if (mToken == null) {
                Log.w(TAG, "The session has been already released");
                return;
            }
            try {
                mService.sendTvRecordingInfo(mToken, recordingInfo, mUserId);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        void sendTvRecordingInfoList(@Nullable List<TvRecordingInfo> recordingInfoList) {
            if (mToken == null) {
                Log.w(TAG, "The session has been already released");
                return;
            }
            try {
                mService.sendTvRecordingInfoList(mToken, recordingInfoList, mUserId);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        void notifyRecordingStarted(String recordingId, String requestId) {
            if (mToken == null) {
                Log.w(TAG, "The session has been already released");
                return;
            }
            try {
                mService.notifyRecordingStarted(mToken, recordingId, requestId, mUserId);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        void notifyRecordingStopped(String recordingId) {
            if (mToken == null) {
                Log.w(TAG, "The session has been already released");
                return;
            }
            try {
                mService.notifyRecordingStopped(mToken, recordingId, mUserId);
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

        void sendCertificate(@NonNull String host, int port, @NonNull SslCertificate cert) {
            if (mToken == null) {
                Log.w(TAG, "The session has been already released");
                return;
            }
            try {
                mService.sendCertificate(mToken, host, port, SslCertificate.saveState(cert),
                        mUserId);
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

        void notifyTimeShiftPlaybackParams(@NonNull PlaybackParams params) {
            if (mToken == null) {
                Log.w(TAG, "The session has been already released");
                return;
            }
            try {
                mService.notifyTimeShiftPlaybackParams(mToken, params, mUserId);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        void notifyTimeShiftStatusChanged(
                @NonNull String inputId, @TvInputManager.TimeShiftStatus int status) {
            if (mToken == null) {
                Log.w(TAG, "The session has been already released");
                return;
            }
            try {
                mService.notifyTimeShiftStatusChanged(mToken, inputId, status, mUserId);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        void notifyTimeShiftStartPositionChanged(@NonNull String inputId, long timeMs) {
            if (mToken == null) {
                Log.w(TAG, "The session has been already released");
                return;
            }
            try {
                mService.notifyTimeShiftStartPositionChanged(mToken, inputId, timeMs, mUserId);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        void notifyTimeShiftCurrentPositionChanged(@NonNull String inputId, long timeMs) {
            if (mToken == null) {
                Log.w(TAG, "The session has been already released");
                return;
            }
            try {
                mService.notifyTimeShiftCurrentPositionChanged(mToken, inputId, timeMs, mUserId);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        void notifyRecordingConnectionFailed(@NonNull String recordingId, @NonNull String inputId) {
            if (mToken == null) {
                Log.w(TAG, "The session has been already released");
                return;
            }
            try {
                mService.notifyRecordingConnectionFailed(mToken, recordingId, inputId, mUserId);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        void notifyRecordingDisconnected(@NonNull String recordingId, @NonNull String inputId) {
            if (mToken == null) {
                Log.w(TAG, "The session has been already released");
                return;
            }
            try {
                mService.notifyRecordingDisconnected(mToken, recordingId, inputId, mUserId);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        void notifyRecordingTuned(@NonNull String recordingId, @NonNull Uri channelUri) {
            if (mToken == null) {
                Log.w(TAG, "The session has been already released");
                return;
            }
            try {
                mService.notifyRecordingTuned(mToken, recordingId, channelUri, mUserId);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        void notifyRecordingError(@NonNull String recordingId, int err) {
            if (mToken == null) {
                Log.w(TAG, "The session has been already released");
                return;
            }
            try {
                mService.notifyRecordingError(mToken, recordingId, err, mUserId);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        void notifyRecordingScheduled(@NonNull String recordingId, @Nullable String requestId) {
            if (mToken == null) {
                Log.w(TAG, "The session has been already released");
                return;
            }
            try {
                mService.notifyRecordingScheduled(mToken, recordingId, requestId, mUserId);
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
         * Notifies of any advertisement response passed in from TIS.
         *
         * @param response response passed in from TIS.
         */
        public void notifyAdResponse(AdResponse response) {
            if (mToken == null) {
                Log.w(TAG, "The session has been already released");
                return;
            }
            try {
                mService.notifyAdResponse(mToken, response, mUserId);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        /**
         * Notifies the advertisement buffer is consumed.
         */
        public void notifyAdBufferConsumed(AdBuffer buffer) {
            if (mToken == null) {
                Log.w(TAG, "The session has been already released");
                return;
            }
            try {
                mService.notifyAdBufferConsumed(mToken, buffer, mUserId);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            } finally {
                if (buffer != null) {
                    buffer.getSharedMemory().close();
                }
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
         * Notifies Interactive APP session when a channel is tuned.
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

        /**
         * Notifies Interactive APP session when a track is selected.
         */
        public void notifyTrackSelected(int type, String trackId) {
            if (mToken == null) {
                Log.w(TAG, "The session has been already released");
                return;
            }
            try {
                mService.notifyTrackSelected(mToken, type, trackId, mUserId);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        /**
         * Notifies Interactive APP session when tracks are changed.
         */
        public void notifyTracksChanged(List<TvTrackInfo> tracks) {
            if (mToken == null) {
                Log.w(TAG, "The session has been already released");
                return;
            }
            try {
                mService.notifyTracksChanged(mToken, tracks, mUserId);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        /**
         * Notifies Interactive APP session when video is available.
         */
        public void notifyVideoAvailable() {
            if (mToken == null) {
                Log.w(TAG, "The session has been already released");
                return;
            }
            try {
                mService.notifyVideoAvailable(mToken, mUserId);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        /**
         * Notifies Interactive APP session when video is unavailable.
         */
        public void notifyVideoUnavailable(int reason) {
            if (mToken == null) {
                Log.w(TAG, "The session has been already released");
                return;
            }
            try {
                mService.notifyVideoUnavailable(mToken, reason, mUserId);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        /**
         * Notifies Interactive app session when the video freeze state is updated
         * @param isFrozen Whether or not the video is frozen
         */
        public void notifyVideoFreezeUpdated(boolean isFrozen) {
            if (mToken == null) {
                Log.w(TAG, "The session has been already released");
                return;
            }
            try {
                mService.notifyVideoFreezeUpdated(mToken, isFrozen, mUserId);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        /**
         * Notifies Interactive APP session when content is allowed.
         */
        public void notifyContentAllowed() {
            if (mToken == null) {
                Log.w(TAG, "The session has been already released");
                return;
            }
            try {
                mService.notifyContentAllowed(mToken, mUserId);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        /**
         * Notifies Interactive APP session when content is blocked.
         */
        public void notifyContentBlocked(TvContentRating rating) {
            if (mToken == null) {
                Log.w(TAG, "The session has been already released");
                return;
            }
            try {
                mService.notifyContentBlocked(mToken, rating.flattenToString(), mUserId);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        /**
         * Notifies Interactive APP session when signal strength is changed.
         */
        public void notifySignalStrength(int strength) {
            if (mToken == null) {
                Log.w(TAG, "The session has been already released");
                return;
            }
            try {
                mService.notifySignalStrength(mToken, strength, mUserId);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        /**
         * Notifies Interactive APP session when a new TV message is received.
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
                    if (mSession.getInputSession() != null) {
                        mSession.getInputSession().requestBroadcastInfo(request);
                    }
                }
            });
        }

        void postRemoveBroadcastInfo(final int requestId) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (mSession.getInputSession() != null) {
                        mSession.getInputSession().removeBroadcastInfo(requestId);
                    }
                }
            });
        }

        void postCommandRequest(
                final @TvInteractiveAppService.PlaybackCommandType String cmdType,
                final Bundle parameters) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mSessionCallback.onCommandRequest(mSession, cmdType, parameters);
                }
            });
        }

        void postTimeShiftCommandRequest(
                final @TvInteractiveAppService.TimeShiftCommandType String cmdType,
                final Bundle parameters) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mSessionCallback.onTimeShiftCommandRequest(mSession, cmdType, parameters);
                }
            });
        }

        void postSetVideoBounds(Rect rect) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mSessionCallback.onSetVideoBounds(mSession, rect);
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

        void postRequestCurrentChannelLcn() {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mSessionCallback.onRequestCurrentChannelLcn(mSession);
                }
            });
        }

        void postRequestStreamVolume() {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mSessionCallback.onRequestStreamVolume(mSession);
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

        void postRequestSelectedTrackInfo() {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mSessionCallback.onRequestSelectedTrackInfo(mSession);
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

        void postRequestTimeShiftMode() {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mSessionCallback.onRequestTimeShiftMode(mSession);
                }
            });
        }

        void postRequestAvailableSpeeds() {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mSessionCallback.onRequestAvailableSpeeds(mSession);
                }
            });
        }

        void postRequestStartRecording(String requestId, Uri programUri) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mSessionCallback.onRequestStartRecording(mSession, requestId, programUri);
                }
            });
        }

        void postRequestStopRecording(String recordingId) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mSessionCallback.onRequestStopRecording(mSession, recordingId);
                }
            });
        }

        void postRequestScheduleRecording(String requestId, String inputId, Uri channelUri,
                Uri programUri, Bundle params) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mSessionCallback.onRequestScheduleRecording(
                            mSession, requestId, inputId, channelUri, programUri, params);
                }
            });
        }

        void postRequestScheduleRecording(String requestId, String inputId, Uri channelUri,
                long startTime, long duration, int repeatDays, Bundle params) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mSessionCallback.onRequestScheduleRecording(mSession, requestId, inputId,
                            channelUri, startTime, duration, repeatDays, params);
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

        void postRequestCertificate(String host, int port) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mSessionCallback.onRequestCertificate(mSession, host, port);
                }
            });
        }

        void postRequestTvRecordingInfo(String recordingId) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mSessionCallback.onRequestTvRecordingInfo(mSession, recordingId);
                }
            });
        }

        void postRequestTvRecordingInfoList(int type) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mSessionCallback.onRequestTvRecordingInfoList(mSession, type);
                }
            });
        }

        void postSetTvRecordingInfo(String recordingId, TvRecordingInfo recordingInfo) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mSessionCallback.onSetTvRecordingInfo(mSession, recordingId, recordingInfo);
                }
            });
        }

        void postAdRequest(final AdRequest request) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (mSession.getInputSession() != null) {
                        mSession.getInputSession().requestAd(request);
                    }
                }
            });
        }

        void postSessionStateChanged(int state, int err) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mSessionCallback.onSessionStateChanged(mSession, state, err);
                }
            });
        }

        void postBiInteractiveAppCreated(Uri biIAppUri, String biIAppId) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mSessionCallback.onBiInteractiveAppCreated(mSession, biIAppUri, biIAppId);
                }
            });
        }

        void postTeletextAppStateChanged(int state) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mSessionCallback.onTeletextAppStateChanged(mSession, state);
                }
            });
        }

        void postAdBufferReady(AdBuffer buffer) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (mSession.getInputSession() != null) {
                        mSession.getInputSession().notifyAdBufferReady(buffer);
                    }
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
         * This is called after {@link TvInteractiveAppManager#createSession} has been processed.
         *
         * @param session A {@link TvInteractiveAppManager.Session} instance created. This can be
         *                {@code null} if the creation request failed.
         */
        public void onSessionCreated(@Nullable Session session) {
        }

        /**
         * This is called when {@link TvInteractiveAppManager.Session} is released.
         * This typically happens when the process hosting the session has crashed or been killed.
         *
         * @param session the {@link TvInteractiveAppManager.Session} instance released.
         */
        public void onSessionReleased(@NonNull Session session) {
        }

        /**
         * This is called when {@link TvInteractiveAppService.Session#layoutSurface} is called to
         * change the layout of surface.
         *
         * @param session A {@link TvInteractiveAppManager.Session} associated with this callback.
         * @param left Left position.
         * @param top Top position.
         * @param right Right position.
         * @param bottom Bottom position.
         */
        public void onLayoutSurface(Session session, int left, int top, int right, int bottom) {
        }

        /**
         * This is called when {@link TvInteractiveAppService.Session#requestCommand} is called.
         *
         * @param session A {@link TvInteractiveAppManager.Session} associated with this callback.
         * @param cmdType type of the command.
         * @param parameters parameters of the command.
         */
        public void onCommandRequest(
                Session session,
                @TvInteractiveAppService.PlaybackCommandType String cmdType,
                Bundle parameters) {
        }

        /**
         * This is called when {@link TvInteractiveAppService.Session#requestTimeShiftCommand} is
         * called.
         *
         * @param session A {@link TvInteractiveAppManager.Session} associated with this callback.
         * @param cmdType type of the time shift command.
         * @param parameters parameters of the command.
         */
        public void onTimeShiftCommandRequest(
                Session session,
                @TvInteractiveAppService.TimeShiftCommandType String cmdType,
                Bundle parameters) {
        }

        /**
         * This is called when {@link TvInteractiveAppService.Session#setVideoBounds} is called.
         *
         * @param session A {@link TvInteractiveAppManager.Session} associated with this callback.
         */
        public void onSetVideoBounds(Session session, Rect rect) {
        }

        /**
         * This is called when {@link TvInteractiveAppService.Session#requestCurrentVideoBounds} is
         * called.
         *
         * @param session A {@link TvInteractiveAppManager.Session} associated with this callback.
         */
        public void onRequestCurrentVideoBounds(Session session) {
        }

        /**
         * This is called when {@link TvInteractiveAppService.Session#requestCurrentChannelUri} is
         * called.
         *
         * @param session A {@link TvInteractiveAppManager.Session} associated with this callback.
         */
        public void onRequestCurrentChannelUri(Session session) {
        }

        /**
         * This is called when {@link TvInteractiveAppService.Session#requestCurrentChannelLcn} is
         * called.
         *
         * @param session A {@link TvInteractiveAppManager.Session} associated with this callback.
         */
        public void onRequestCurrentChannelLcn(Session session) {
        }

        /**
         * This is called when {@link TvInteractiveAppService.Session#requestStreamVolume} is
         * called.
         *
         * @param session A {@link TvInteractiveAppManager.Session} associated with this callback.
         */
        public void onRequestStreamVolume(Session session) {
        }

        /**
         * This is called when {@link TvInteractiveAppService.Session#requestTrackInfoList} is
         * called.
         *
         * @param session A {@link TvInteractiveAppManager.Session} associated with this callback.
         */
        public void onRequestTrackInfoList(Session session) {
        }

        /**
         * This is called when {@link TvInteractiveAppService.Session#requestSelectedTrackInfo()} is
         * called.
         *
         * @param session A {@link TvInteractiveAppManager.Session} associated with this callback.
         */
        public void onRequestSelectedTrackInfo(Session session) {
        }

        /**
         * This is called when {@link TvInteractiveAppService.Session#requestCurrentTvInputId} is
         * called.
         *
         * @param session A {@link TvInteractiveAppService.Session} associated with this callback.
         */
        public void onRequestCurrentTvInputId(Session session) {
        }

        /**
         * This is called when {@link TvInteractiveAppService.Session#requestTimeShiftMode()} is
         * called.
         *
         * @param session A {@link TvInteractiveAppService.Session} associated with this callback.
         */
        public void onRequestTimeShiftMode(Session session) {
        }

        /**
         * This is called when {@link TvInteractiveAppService.Session#requestAvailableSpeeds()} is
         * called.
         *
         * @param session A {@link TvInteractiveAppService.Session} associated with this callback.
         */
        public void onRequestAvailableSpeeds(Session session) {
        }

        /**
         * This is called when {@link TvInteractiveAppService.Session#requestStartRecording} is
         * called.
         *
         * @param session A {@link TvInteractiveAppService.Session} associated with this callback.
         * @param programUri The Uri of the program to be recorded.
         */
        public void onRequestStartRecording(Session session, String requestId, Uri programUri) {
        }

        /**
         * This is called when {@link TvInteractiveAppService.Session#requestStopRecording(String)}
         * is called.
         *
         * @param session A {@link TvInteractiveAppService.Session} associated with this callback.
         * @param recordingId The recordingId of the recording to be stopped.
         */
        public void onRequestStopRecording(Session session, String recordingId) {
        }

        /**
         * This is called when
         * {@link TvInteractiveAppService.Session#requestScheduleRecording(String, String, Uri, Uri, Bundle)}
         * is called.
         *
         * @param session A {@link TvInteractiveAppService.Session} associated with this callback.
         * @param inputId The ID of the TV input for the given channel.
         * @param channelUri The URI of a channel to be recorded.
         * @param programUri The URI of the TV program to be recorded.
         * @param params Domain-specific data for this tune request. Keys <em>must</em> be a scoped
         *            name, i.e. prefixed with a package name you own, so that different developers
         *            will not create conflicting keys.
         * @see android.media.tv.TvRecordingClient#tune(String, Uri, Bundle)
         * @see android.media.tv.TvRecordingClient#startRecording(Uri)
         */
        public void onRequestScheduleRecording(Session session, @NonNull String requestId,
                @NonNull String inputId, @NonNull Uri channelUri, @NonNull Uri programUri,
                @NonNull Bundle params) {
        }

        /**
         * This is called when
         * {@link TvInteractiveAppService.Session#requestScheduleRecording(String, String, Uri, long, long, int, Bundle)}
         * is called.
         *
         * @param session A {@link TvInteractiveAppService.Session} associated with this callback.
         * @param inputId The ID of the TV input for the given channel.
         * @param channelUri The URI of a channel to be recorded.
         * @param startTime The start time of the recording in milliseconds since epoch.
         * @param duration The duration of the recording in milliseconds.
         * @param repeatDays The repeated days. 0 if not repeated.
         * @param params Domain-specific data for this tune request. Keys <em>must</em> be a scoped
         *            name, i.e. prefixed with a package name you own, so that different developers
         *            will not create conflicting keys.
         * @see android.media.tv.TvRecordingClient#tune(String, Uri, Bundle)
         * @see android.media.tv.TvRecordingClient#startRecording(Uri)
         */
        public void onRequestScheduleRecording(Session session, @NonNull String requestId,
                @NonNull String inputId, @NonNull Uri channelUri, long startTime, long duration,
                int repeatDays, @NonNull Bundle params) {
        }

        /**
         * This is called when
         * {@link TvInteractiveAppService.Session#setTvRecordingInfo(String, TvRecordingInfo)} is
         * called.
         *
         * @param session A {@link TvInteractiveAppService.Session} associated with this callback.
         * @param recordingId The recordingId of the recording which will have the info set.
         * @param recordingInfo The recording info to set to the recording.
         */
        public void onSetTvRecordingInfo(Session session, String recordingId,
                TvRecordingInfo recordingInfo) {
        }

        /**
         * This is called when {@link TvInteractiveAppService.Session#requestTvRecordingInfo} is
         * called.
         *
         * @param session A {@link TvInteractiveAppService.Session} associated with this callback.
         * @param recordingId The recordingId of the recording to be stopped.
         */
        public void onRequestTvRecordingInfo(Session session, String recordingId) {
        }

        /**
         * This is called when {@link TvInteractiveAppService.Session#requestTvRecordingInfoList} is
         * called.
         *
         * @param session A {@link TvInteractiveAppService.Session} associated with this callback.
         * @param type The type of recordings to return
         */
        public void onRequestTvRecordingInfoList(Session session,
                @TvRecordingInfo.TvRecordingListType int type) {
        }

        /**
         * This is called when
         * {@link TvInteractiveAppService.Session#requestSigning(String, String, String, byte[])} is
         * called.
         *
         * @param session A {@link TvInteractiveAppService.Session} associated with this callback.
         * @param signingId the ID to identify the request.
         * @param algorithm the standard name of the signature algorithm requested, such as
         *                  MD5withRSA, SHA256withDSA, etc.
         * @param alias the alias of the corresponding {@link java.security.KeyStore}.
         * @param data the original bytes to be signed.
         */
        public void onRequestSigning(
                Session session, String signingId, String algorithm, String alias, byte[] data) {
        }

        /**
         * This is called when the service requests a SSL certificate for client validation.
         *
         * @param session A {@link TvInteractiveAppService.Session} associated with this callback.
         * @param host the host name of the SSL authentication server.
         * @param port the port of the SSL authentication server. E.g., 443
         * @hide
         */
        public void onRequestCertificate(Session session, String host, int port) {
        }

        /**
         * This is called when {@link TvInteractiveAppService.Session#notifySessionStateChanged} is
         * called.
         *
         * @param session A {@link TvInteractiveAppManager.Session} associated with this callback.
         * @param state the current state.
         */
        public void onSessionStateChanged(
                Session session,
                @InteractiveAppState int state,
                @ErrorCode int err) {
        }

        /**
         * This is called when {@link TvInteractiveAppService.Session#notifyBiInteractiveAppCreated}
         * is called.
         *
         * @param session A {@link TvInteractiveAppManager.Session} associated with this callback.
         * @param biIAppUri URI associated this BI interactive app. This is the same URI in
         *                  {@link Session#createBiInteractiveApp(Uri, Bundle)}
         * @param biIAppId BI interactive app ID, which can be used to destroy the BI interactive
         *                 app.
         */
        public void onBiInteractiveAppCreated(Session session, Uri biIAppUri, String biIAppId) {
        }

        /**
         * This is called when {@link TvInteractiveAppService.Session#notifyTeletextAppStateChanged}
         * is called.
         *
         * @param session A {@link TvInteractiveAppManager.Session} associated with this callback.
         * @param state the current state.
         */
        public void onTeletextAppStateChanged(
                Session session, @TvInteractiveAppManager.TeletextAppState int state) {
        }
    }
}
