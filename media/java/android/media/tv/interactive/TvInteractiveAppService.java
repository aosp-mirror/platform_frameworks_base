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

import android.annotation.CallSuper;
import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.MainThread;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.Px;
import android.annotation.SdkConstant;
import android.annotation.StringDef;
import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.media.PlaybackParams;
import android.media.tv.AdBuffer;
import android.media.tv.AdRequest;
import android.media.tv.AdResponse;
import android.media.tv.BroadcastInfoRequest;
import android.media.tv.BroadcastInfoResponse;
import android.media.tv.TvContentRating;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.media.tv.TvRecordingInfo;
import android.media.tv.TvTrackInfo;
import android.media.tv.TvView;
import android.media.tv.flags.Flags;
import android.media.tv.interactive.TvInteractiveAppView.TvInteractiveAppCallback;
import android.net.Uri;
import android.net.http.SslCertificate;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Process;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.util.Log;
import android.view.Gravity;
import android.view.InputChannel;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.InputEventReceiver;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;

import com.android.internal.os.SomeArgs;

import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;

/**
 * A TV interactive application service is a service that provides runtime environment and runs TV
 * interactive applications.
 */
public abstract class TvInteractiveAppService extends Service {
    private static final boolean DEBUG = false;
    private static final String TAG = "TvInteractiveAppService";

    private static final int DETACH_MEDIA_VIEW_TIMEOUT_MS = 5000;

    /**
     * This is the interface name that a service implementing a TV Interactive App service should
     * say that it supports -- that is, this is the action it uses for its intent filter. To be
     * supported, the service must also require the
     * {@link android.Manifest.permission#BIND_TV_INTERACTIVE_APP} permission so that other
     * applications cannot abuse it.
     */
    @SdkConstant(SdkConstant.SdkConstantType.SERVICE_ACTION)
    public static final String SERVICE_INTERFACE =
            "android.media.tv.interactive.TvInteractiveAppService";

    /**
     * Name under which a TvInteractiveAppService component publishes information about itself. This
     * meta-data must reference an XML resource containing an
     * <code>&lt;{@link android.R.styleable#TvInteractiveAppService tv-interactive-app}&gt;</code>
     * tag.
     */
    public static final String SERVICE_META_DATA = "android.media.tv.interactive.app";

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @StringDef(prefix = "PLAYBACK_COMMAND_TYPE_", value = {
            PLAYBACK_COMMAND_TYPE_TUNE,
            PLAYBACK_COMMAND_TYPE_TUNE_NEXT,
            PLAYBACK_COMMAND_TYPE_TUNE_PREV,
            PLAYBACK_COMMAND_TYPE_STOP,
            PLAYBACK_COMMAND_TYPE_SET_STREAM_VOLUME,
            PLAYBACK_COMMAND_TYPE_SELECT_TRACK,
            PLAYBACK_COMMAND_TYPE_FREEZE
    })
    public @interface PlaybackCommandType {}

    /**
     * Playback command type: tune to the given channel.
     * @see #COMMAND_PARAMETER_KEY_CHANNEL_URI
     */
    public static final String PLAYBACK_COMMAND_TYPE_TUNE = "tune";
    /**
     * Playback command type: tune to the next channel.
     */
    public static final String PLAYBACK_COMMAND_TYPE_TUNE_NEXT = "tune_next";
    /**
     * Playback command type: tune to the previous channel.
     */
    public static final String PLAYBACK_COMMAND_TYPE_TUNE_PREV = "tune_previous";
    /**
     * Playback command type: stop the playback.
     */
    public static final String PLAYBACK_COMMAND_TYPE_STOP = "stop";
    /**
     * Playback command type: set the volume.
     */
    public static final String PLAYBACK_COMMAND_TYPE_SET_STREAM_VOLUME =
            "set_stream_volume";
    /**
     * Playback command type: select the given track.
     */
    public static final String PLAYBACK_COMMAND_TYPE_SELECT_TRACK = "select_track";
    /**
     * Playback command type: freeze the video playback on the current frame.
     * @hide
     */
    public static final String PLAYBACK_COMMAND_TYPE_FREEZE = "freeze";

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = "COMMAND_PARAMETER_VALUE_STOP_MODE_", value = {
            COMMAND_PARAMETER_VALUE_STOP_MODE_BLANK,
            COMMAND_PARAMETER_VALUE_STOP_MODE_FREEZE
    })
    public @interface PlaybackCommandStopMode {}

    /**
     * Playback command stop mode: show a blank screen.
     * @see #PLAYBACK_COMMAND_TYPE_STOP
     */
    public static final int COMMAND_PARAMETER_VALUE_STOP_MODE_BLANK = 1;

    /**
     * Playback command stop mode: freeze the video.
     * @see #PLAYBACK_COMMAND_TYPE_STOP
     */
    public static final int COMMAND_PARAMETER_VALUE_STOP_MODE_FREEZE = 2;

    /**
     * Playback command parameter: stop mode.
     * <p>Type: int
     *
     * @see #PLAYBACK_COMMAND_TYPE_STOP
     */
    public static final String COMMAND_PARAMETER_KEY_STOP_MODE = "command_stop_mode";

    /**
     * Playback command parameter: channel URI.
     * <p>Type: android.net.Uri
     *
     * @see #PLAYBACK_COMMAND_TYPE_TUNE
     */
    public static final String COMMAND_PARAMETER_KEY_CHANNEL_URI = "command_channel_uri";
    /**
     * Playback command parameter: TV input ID.
     * <p>Type: String
     *
     * @see TvInputInfo#getId()
     */
    public static final String COMMAND_PARAMETER_KEY_INPUT_ID = "command_input_id";
    /**
     * Playback command parameter: stream volume.
     * <p>Type: float
     *
     * @see #PLAYBACK_COMMAND_TYPE_SET_STREAM_VOLUME
     */
    public static final String COMMAND_PARAMETER_KEY_VOLUME = "command_volume";
    /**
     * Playback command parameter: track type.
     * <p>Type: int
     *
     * @see #PLAYBACK_COMMAND_TYPE_SELECT_TRACK
     * @see TvTrackInfo#getType()
     */
    public static final String COMMAND_PARAMETER_KEY_TRACK_TYPE = "command_track_type";
    /**
     * Playback command parameter: track ID.
     * <p>Type: String
     *
     * @see #PLAYBACK_COMMAND_TYPE_SELECT_TRACK
     * @see TvTrackInfo#getId()
     */
    public static final String COMMAND_PARAMETER_KEY_TRACK_ID = "command_track_id";
    /**
     * Command to quiet channel change. No channel banner or channel info is shown.
     * <p>Refer to HbbTV Spec 2.0.4 chapter A.2.4.3.
     */
    public static final String COMMAND_PARAMETER_KEY_CHANGE_CHANNEL_QUIETLY =
            "command_change_channel_quietly";

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @StringDef(prefix = "TIME_SHIFT_COMMAND_TYPE_", value = {
            TIME_SHIFT_COMMAND_TYPE_PLAY,
            TIME_SHIFT_COMMAND_TYPE_PAUSE,
            TIME_SHIFT_COMMAND_TYPE_RESUME,
            TIME_SHIFT_COMMAND_TYPE_SEEK_TO,
            TIME_SHIFT_COMMAND_TYPE_SET_PLAYBACK_PARAMS,
            TIME_SHIFT_COMMAND_TYPE_SET_MODE,
    })
    public @interface TimeShiftCommandType {}

    /**
     * Time shift command type: play.
     *
     * @see TvView#timeShiftPlay(String, Uri)
     */
    public static final String TIME_SHIFT_COMMAND_TYPE_PLAY = "play";
    /**
     * Time shift command type: pause.
     *
     * @see TvView#timeShiftPause()
     */
    public static final String TIME_SHIFT_COMMAND_TYPE_PAUSE = "pause";
    /**
     * Time shift command type: resume.
     *
     * @see TvView#timeShiftResume()
     */
    public static final String TIME_SHIFT_COMMAND_TYPE_RESUME = "resume";
    /**
     * Time shift command type: seek to.
     *
     * @see TvView#timeShiftSeekTo(long)
     */
    public static final String TIME_SHIFT_COMMAND_TYPE_SEEK_TO = "seek_to";
    /**
     * Time shift command type: set playback params.
     *
     * @see TvView#timeShiftSetPlaybackParams(PlaybackParams)
     */
    public static final String TIME_SHIFT_COMMAND_TYPE_SET_PLAYBACK_PARAMS = "set_playback_params";
    /**
     * Time shift command type: set time shift mode.
     */
    public static final String TIME_SHIFT_COMMAND_TYPE_SET_MODE = "set_mode";

    /**
     * Time shift command parameter: program URI.
     * <p>Type: android.net.Uri
     *
     * @see #TIME_SHIFT_COMMAND_TYPE_PLAY
     */
    public static final String COMMAND_PARAMETER_KEY_PROGRAM_URI = "command_program_uri";
    /**
     * Time shift command parameter: time position for time shifting, in milliseconds.
     * <p>Type: long
     *
     * @see #TIME_SHIFT_COMMAND_TYPE_SEEK_TO
     */
    public static final String COMMAND_PARAMETER_KEY_TIME_POSITION = "command_time_position";
    /**
     * Time shift command parameter: playback params.
     * <p>Type: android.media.PlaybackParams
     *
     * @see #TIME_SHIFT_COMMAND_TYPE_SET_PLAYBACK_PARAMS
     */
    public static final String COMMAND_PARAMETER_KEY_PLAYBACK_PARAMS = "command_playback_params";
    /**
     * Time shift command parameter: playback params.
     * <p>Type: Integer. One of {@link TvInputManager#TIME_SHIFT_MODE_OFF},
     * {@link TvInputManager#TIME_SHIFT_MODE_LOCAL},
     * {@link TvInputManager#TIME_SHIFT_MODE_NETWORK},
     * {@link TvInputManager#TIME_SHIFT_MODE_AUTO}.
     *
     * @see #TIME_SHIFT_COMMAND_TYPE_SET_MODE
     */
    public static final String COMMAND_PARAMETER_KEY_TIME_SHIFT_MODE = "command_time_shift_mode";

    private final Handler mServiceHandler = new ServiceHandler();
    private final RemoteCallbackList<ITvInteractiveAppServiceCallback> mCallbacks =
            new RemoteCallbackList<>();

    @Override
    @Nullable
    public final IBinder onBind(@NonNull Intent intent) {
        ITvInteractiveAppService.Stub tvIAppServiceBinder = new ITvInteractiveAppService.Stub() {
            @Override
            public void registerCallback(ITvInteractiveAppServiceCallback cb) {
                if (cb != null) {
                    mCallbacks.register(cb);
                }
            }

            @Override
            public void unregisterCallback(ITvInteractiveAppServiceCallback cb) {
                if (cb != null) {
                    mCallbacks.unregister(cb);
                }
            }

            @Override
            public void createSession(InputChannel channel, ITvInteractiveAppSessionCallback cb,
                    String iAppServiceId, int type) {
                if (cb == null) {
                    return;
                }
                SomeArgs args = SomeArgs.obtain();
                args.arg1 = channel;
                args.arg2 = cb;
                args.arg3 = iAppServiceId;
                args.arg4 = type;
                mServiceHandler.obtainMessage(ServiceHandler.DO_CREATE_SESSION, args)
                        .sendToTarget();
            }

            @Override
            public void registerAppLinkInfo(AppLinkInfo appLinkInfo) {
                onRegisterAppLinkInfo(appLinkInfo);
            }

            @Override
            public void unregisterAppLinkInfo(AppLinkInfo appLinkInfo) {
                onUnregisterAppLinkInfo(appLinkInfo);
            }

            @Override
            public void sendAppLinkCommand(Bundle command) {
                onAppLinkCommand(command);
            }
        };
        return tvIAppServiceBinder;
    }

    /**
     * Called when a request to register an Android application link info record is received.
     */
    public void onRegisterAppLinkInfo(@NonNull AppLinkInfo appLinkInfo) {
    }

    /**
     * Called when a request to unregister an Android application link info record is received.
     */
    public void onUnregisterAppLinkInfo(@NonNull AppLinkInfo appLinkInfo) {
    }

    /**
     * Called when app link command is received.
     *
     * @see android.media.tv.interactive.TvInteractiveAppManager#sendAppLinkCommand(String, Bundle)
     */
    public void onAppLinkCommand(@NonNull Bundle command) {
    }


    /**
     * Returns a concrete implementation of {@link Session}.
     *
     * <p>May return {@code null} if this TV Interactive App service fails to create a session for
     * some reason.
     *
     * @param iAppServiceId The ID of the TV Interactive App associated with the session.
     * @param type The type of the TV Interactive App associated with the session.
     */
    @Nullable
    public abstract Session onCreateSession(
            @NonNull String iAppServiceId,
            @TvInteractiveAppServiceInfo.InteractiveAppType int type);

    /**
     * Notifies the system when the state of the interactive app RTE has been changed.
     *
     * @param type the interactive app type
     * @param state the current state of the service of the given type
     * @param error the error code for error state. {@link TvInteractiveAppManager#ERROR_NONE} is
     *              used when the state is not
     *              {@link TvInteractiveAppManager#SERVICE_STATE_ERROR}.
     */
    public final void notifyStateChanged(
            @TvInteractiveAppServiceInfo.InteractiveAppType int type,
            @TvInteractiveAppManager.ServiceState int state,
            @TvInteractiveAppManager.ErrorCode int error) {
        SomeArgs args = SomeArgs.obtain();
        args.arg1 = type;
        args.arg2 = state;
        args.arg3 = error;
        mServiceHandler
                .obtainMessage(ServiceHandler.DO_NOTIFY_RTE_STATE_CHANGED, args).sendToTarget();
    }

    /**
     * Base class for derived classes to implement to provide a TV interactive app session.
     *
     * <p>A session is associated with a {@link TvInteractiveAppView} instance and handles
     * corresponding communications. It also handles the communications with
     * {@link android.media.tv.TvInputService.Session} if connected.
     *
     * @see TvInteractiveAppView#setTvView(TvView)
     */
    public abstract static class Session implements KeyEvent.Callback {
        private final KeyEvent.DispatcherState mDispatcherState = new KeyEvent.DispatcherState();

        private final Object mLock = new Object();
        // @GuardedBy("mLock")
        private ITvInteractiveAppSessionCallback mSessionCallback;
        // @GuardedBy("mLock")
        private final List<Runnable> mPendingActions = new ArrayList<>();

        private final Context mContext;
        final Handler mHandler;
        private final WindowManager mWindowManager;
        private WindowManager.LayoutParams mWindowParams;
        private Surface mSurface;
        private FrameLayout mMediaViewContainer;
        private View mMediaView;
        private MediaViewCleanUpTask mMediaViewCleanUpTask;
        private boolean mMediaViewEnabled;
        private IBinder mWindowToken;
        private Rect mMediaFrame;

        /**
         * Creates a new Session.
         *
         * @param context The context of the application
         */
        public Session(@NonNull Context context) {
            mContext = context;
            mWindowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            mHandler = new Handler(context.getMainLooper());
        }

        /**
         * Enables or disables the media view.
         *
         * <p>By default, the media view is disabled. Must be called explicitly after the
         * session is created to enable the media view.
         *
         * <p>The TV Interactive App service can disable its media view when needed.
         *
         * @param enable {@code true} if you want to enable the media view. {@code false}
         *            otherwise.
         */
        @CallSuper
        public void setMediaViewEnabled(final boolean enable) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (enable == mMediaViewEnabled) {
                        return;
                    }
                    mMediaViewEnabled = enable;
                    if (enable) {
                        if (mWindowToken != null) {
                            createMediaView(mWindowToken, mMediaFrame);
                        }
                    } else {
                        removeMediaView(false);
                    }
                }
            });
        }

        /**
         * Returns {@code true} if media view is enabled, {@code false} otherwise.
         *
         * @see #setMediaViewEnabled(boolean)
         */
        public boolean isMediaViewEnabled() {
            return mMediaViewEnabled;
        }

        /**
         * Starts TvInteractiveAppService session.
         */
        public void onStartInteractiveApp() {
        }

        /**
         * Stops TvInteractiveAppService session.
         */
        public void onStopInteractiveApp() {
        }

        /**
         * Resets TvInteractiveAppService session.
         */
        public void onResetInteractiveApp() {
        }

        /**
         * Creates broadcast-independent(BI) interactive application.
         *
         * <p>The implementation should call {@link #notifyBiInteractiveAppCreated(Uri, String)},
         * no matter if it's created successfully or not.
         *
         * @see #notifyBiInteractiveAppCreated(Uri, String)
         * @see #onDestroyBiInteractiveAppRequest(String)
         */
        public void onCreateBiInteractiveAppRequest(
                @NonNull Uri biIAppUri, @Nullable Bundle params) {
        }


        /**
         * Destroys broadcast-independent(BI) interactive application.
         *
         * @param biIAppId the BI interactive app ID from
         *                 {@link #onCreateBiInteractiveAppRequest(Uri, Bundle)}
         *
         * @see #onCreateBiInteractiveAppRequest(Uri, Bundle)
         */
        public void onDestroyBiInteractiveAppRequest(@NonNull String biIAppId) {
        }

        /**
         * To toggle Digital Teletext Application if there is one in AIT app list.
         * @param enable {@code true} to enable teletext app; {@code false} otherwise.
         */
        public void onSetTeletextAppEnabled(boolean enable) {
        }

        /**
         * Receives current video bounds.
         *
         * @param bounds the rectangle area for rendering the current video.
         */
        public void onCurrentVideoBounds(@NonNull Rect bounds) {
        }

        /**
         * Receives current channel URI.
         */
        public void onCurrentChannelUri(@Nullable Uri channelUri) {
        }

        /**
         * Receives logical channel number (LCN) of current channel.
         */
        public void onCurrentChannelLcn(int lcn) {
        }

        /**
         * Receives current stream volume.
         *
         * @param volume a volume value between {@code 0.0f} and {@code 1.0f}, inclusive.
         */
        public void onStreamVolume(float volume) {
        }

        /**
         * Receives track list.
         */
        public void onTrackInfoList(@NonNull List<TvTrackInfo> tracks) {
        }

        /**
         * Receives current TV input ID.
         */
        public void onCurrentTvInputId(@Nullable String inputId) {
        }

        /**
         * Receives current time shift mode.
         *
         * @param mode The current time shift mode. The value is one of the following:
         * {@link TvInputManager#TIME_SHIFT_MODE_OFF}, {@link TvInputManager#TIME_SHIFT_MODE_LOCAL},
         * {@link TvInputManager#TIME_SHIFT_MODE_NETWORK},
         * {@link TvInputManager#TIME_SHIFT_MODE_AUTO}.
         */
        public void onTimeShiftMode(@android.media.tv.TvInputManager.TimeShiftMode int mode) {
        }

        /**
         * Receives available playback speeds.
         *
         * @param speeds An ordered array of playback speeds, expressed as values relative to the
         *               normal playback speed (1.0), at which the current content can be played as
         *               a time-shifted broadcast. This is an empty array if the supported playback
         *               speeds are unknown or the video/broadcast is not in time shift mode. If
         *               currently in time shift mode, this array will normally include at least
         *               the values 1.0 (normal speed) and 0.0 (paused).
         */
        public void onAvailableSpeeds(@NonNull float[] speeds) {
        }

        /**
         * Receives the requested {@link android.media.tv.TvRecordingInfo}.
         *
         * @see #requestTvRecordingInfo(String)
         * @param recordingInfo The requested recording info. {@code null} if no recording found.
         */
        public void onTvRecordingInfo(@Nullable TvRecordingInfo recordingInfo) {
        }

        /**
         * Receives requested recording info list.
         *
         * @see #requestTvRecordingInfoList(int)
         * @param recordingInfoList The list of recording info requested. Returns an empty list if
         *                          no matching recording info found.
         */
        public void onTvRecordingInfoList(@NonNull List<TvRecordingInfo> recordingInfoList) {}

        /**
         * This is called when a recording has been started.
         *
         * <p>When a scheduled recording is started, this is also called, and the request ID in this
         * case is {@code null}.
         *
         * @param recordingId The ID of the recording started. The TV app should provide and
         *                    maintain this ID to identify the recording in the future.
         * @param requestId The ID of the request when
         *                  {@link #requestStartRecording(String, Uri)} is called.
         *                  {@code null} if the recording is not triggered by a
         *                  {@link #requestStartRecording(String, Uri)} request.
         *                  This ID should be created by the {@link TvInteractiveAppService} and
         *                  can be any string.
         * @see #onRecordingStopped(String)
         */
        public void onRecordingStarted(@NonNull String recordingId, @Nullable String requestId) {
        }

        /**
         * This is called when the recording has been stopped.
         *
         * @param recordingId The ID of the recording stopped. This ID is created and maintained by
         *                    the TV app when the recording was started.
         * @see #onRecordingStarted(String, String)
         */
        public void onRecordingStopped(@NonNull String recordingId) {
        }

        /**
         * This is called when an error occurred while establishing a connection to the recording
         * session for the corresponding TV input.
         *
         * @param recordingId The ID of the related recording which is sent via
         *                    {@link TvInteractiveAppView#notifyRecordingStarted(String, String)}
         * @param inputId The ID of the TV input bound to the current TvRecordingClient.
         * @see android.media.tv.TvRecordingClient.RecordingCallback#onConnectionFailed(String)
         */
        public void onRecordingConnectionFailed(
                @NonNull String recordingId, @NonNull String inputId) {
        }

        /**
         * This is called when the connection to the current recording session is lost.
         *
         * @param recordingId The ID of the related recording which is sent via
         *                    {@link TvInteractiveAppView#notifyRecordingStarted(String, String)}
         * @param inputId The ID of the TV input bound to the current TvRecordingClient.
         * @see android.media.tv.TvRecordingClient.RecordingCallback#onDisconnected(String)
         */
        public void onRecordingDisconnected(@NonNull String recordingId, @NonNull String inputId) {
        }

        /**
         * This is called when the recording session has been tuned to the given channel and is
         * ready to start recording.
         *
         * @param recordingId The ID of the related recording which is sent via
         *                    {@link TvInteractiveAppView#notifyRecordingStarted(String, String)}
         * @param channelUri The URI of the tuned channel.
         * @see android.media.tv.TvRecordingClient.RecordingCallback#onTuned(Uri)
         */
        public void onRecordingTuned(@NonNull String recordingId, @NonNull Uri channelUri) {
        }

        /**
         * This is called when an issue has occurred. It may be called at any time after the current
         * recording session is created until it is released.
         *
         * @param recordingId The ID of the related recording which is sent via
         *                    {@link TvInteractiveAppView#notifyRecordingStarted(String, String)}
         * @param err The error code. Should be one of the following.
         * <ul>
         * <li>{@link TvInputManager#RECORDING_ERROR_UNKNOWN}
         * <li>{@link TvInputManager#RECORDING_ERROR_INSUFFICIENT_SPACE}
         * <li>{@link TvInputManager#RECORDING_ERROR_RESOURCE_BUSY}
         * </ul>
         * @see android.media.tv.TvRecordingClient.RecordingCallback#onError(int)
         */
        public void onRecordingError(
                @NonNull String recordingId, @TvInputManager.RecordingError int err) {
        }

        /**
         * This is called when the recording has been scheduled.
         *
         * @param recordingId The ID assigned to this recording by the app. It can be used to send
         *                    recording related requests such as
         *                    {@link #requestStopRecording(String)}.
         * @param requestId The ID of the request when
         *                  {@link #requestScheduleRecording}  is called.
         *                  {@code null} if the recording is not triggered by a request.
         *                  This ID should be created by the {@link TvInteractiveAppService} and
         *                  can be any string.
         */
        public void onRecordingScheduled(@NonNull String recordingId, @Nullable String requestId) {
        }

        /**
         * Receives signing result.
         * @param signingId the ID to identify the request. It's the same as the corresponding ID in
         *        {@link Session#requestSigning(String, String, String, byte[])}
         * @param result the signed result.
         *
         * @see #requestSigning(String, String, String, byte[])
         */
        public void onSigningResult(@NonNull String signingId, @NonNull byte[] result) {
        }

        /**
         * Receives the requested Certificate
         *
         * @param host the host name of the SSL authentication server.
         * @param port the port of the SSL authentication server. E.g., 443
         * @param cert the SSL certificate received.
         */
        @FlaggedApi(Flags.FLAG_TIAF_V_APIS)
        public void onCertificate(@NonNull String host, int port, @NonNull SslCertificate cert) {
        }

        /**
         * Called when the application sends information of an error.
         *
         * @param errMsg the message of the error.
         * @param params additional parameters of the error. For example, the signingId of {@link
         *     TvInteractiveAppCallback#onRequestSigning(String, String, String, String, byte[])}
         *     can be included to identify the related signing request, and the method name
         *     "onRequestSigning" can also be added to the params.
         *
         * @see TvInteractiveAppView#ERROR_KEY_METHOD_NAME
         */
        public void onError(@NonNull String errMsg, @NonNull Bundle params) {
        }

        /**
         * Called when the time shift {@link android.media.PlaybackParams} is set or changed.
         *
         * @param params The new {@link PlaybackParams} that was set or changed.
         * @see TvView#timeShiftSetPlaybackParams(PlaybackParams)
         */
        public void onTimeShiftPlaybackParams(@NonNull PlaybackParams params) {
        }

        /**
         * Called when time shift status is changed.
         *
         * @see TvView.TvInputCallback#onTimeShiftStatusChanged(String, int)
         * @see android.media.tv.TvInputService.Session#notifyTimeShiftStatusChanged(int)
         * @param inputId The ID of the input for which the time shift status has changed.
         * @param status The status of which the input has changed to. Should be one of the
         *               following.
         *               <ul>
         *                  <li>{@link TvInputManager#TIME_SHIFT_STATUS_UNKNOWN}
         *                  <li>{@link TvInputManager#TIME_SHIFT_STATUS_UNSUPPORTED}
         *                  <li>{@link TvInputManager#TIME_SHIFT_STATUS_UNAVAILABLE}
         *                  <li>{@link TvInputManager#TIME_SHIFT_STATUS_AVAILABLE}
         *               </ul>
         */
        public void onTimeShiftStatusChanged(
                @NonNull String inputId, @TvInputManager.TimeShiftStatus int status) {}

        /**
         * Called when time shift start position is changed.
         *
         * @see TvView.TimeShiftPositionCallback#onTimeShiftStartPositionChanged(String, long)
         * @param inputId The ID of the input for which the time shift start position has changed.
         * @param timeMs The start position for time shifting, in milliseconds since the epoch.
         */
        public void onTimeShiftStartPositionChanged(@NonNull String inputId, long timeMs) {
        }

        /**
         * Called when time shift current position is changed.
         *
         * @see TvView.TimeShiftPositionCallback#onTimeShiftCurrentPositionChanged(String, long)
         * @param inputId The ID of the input for which the time shift current position has changed.
         * @param timeMs The current position for time shifting, in milliseconds since the epoch.
         */
        public void onTimeShiftCurrentPositionChanged(@NonNull String inputId, long timeMs) {
        }

        /**
         * Called when the application sets the surface.
         *
         * <p>The TV Interactive App service should render interactive app UI onto the given
         * surface. When called with {@code null}, the Interactive App service should immediately
         * free any references to the currently set surface and stop using it.
         *
         * @param surface The surface to be used for interactive app UI rendering. Can be
         *                {@code null}.
         * @return {@code true} if the surface was set successfully, {@code false} otherwise.
         */
        public abstract boolean onSetSurface(@Nullable Surface surface);

        /**
         * Called after any structural changes (format or size) have been made to the surface passed
         * in {@link #onSetSurface}. This method is always called at least once, after
         * {@link #onSetSurface} is called with non-null surface.
         *
         * @param format The new {@link PixelFormat} of the surface.
         * @param width The new width of the surface.
         * @param height The new height of the surface.
         */
        public void onSurfaceChanged(@PixelFormat.Format int format, int width, int height) {
        }

        /**
         * Called when the size of the media view is changed by the application.
         *
         * <p>This is always called at least once when the session is created regardless of whether
         * the media view is enabled or not. The media view container size is the same as the
         * containing {@link TvInteractiveAppView}. Note that the size of the underlying surface can
         * be different if the surface was changed by calling {@link #layoutSurface}.
         *
         * @param width The width of the media view, in pixels.
         * @param height The height of the media view, in pixels.
         */
        public void onMediaViewSizeChanged(@Px int width, @Px int height) {
        }

        /**
         * Called when the application requests to create an media view. Each session
         * implementation can override this method and return its own view.
         *
         * @return a view attached to the media window
         */
        @Nullable
        public View onCreateMediaView() {
            return null;
        }

        /**
         * Releases TvInteractiveAppService session.
         */
        public abstract void onRelease();

        /**
         * Called when the corresponding TV input tuned to a channel.
         *
         * @param channelUri The tuned channel URI.
         */
        public void onTuned(@NonNull Uri channelUri) {
        }

        /**
         * Called when the corresponding TV input selected to a track.
         */
        public void onTrackSelected(@TvTrackInfo.Type int type, @NonNull String trackId) {
        }

        /**
         * Called when the tracks are changed.
         */
        public void onTracksChanged(@NonNull List<TvTrackInfo> tracks) {
        }

        /**
         * Called when video is available.
         */
        public void onVideoAvailable() {
        }

        /**
         * Called when video is unavailable.
         */
        public void onVideoUnavailable(@TvInputManager.VideoUnavailableReason int reason) {
        }

        /**
         * Called when video becomes frozen or unfrozen. Audio playback will continue while
         * video will be frozen to the last frame if {@code true}.
         * @param isFrozen Whether or not the video is frozen.
         * @hide
         */
        public void onVideoFreezeUpdated(boolean isFrozen) {
        }

        /**
         * Called when content is allowed.
         */
        public void onContentAllowed() {
        }

        /**
         * Called when content is blocked.
         */
        public void onContentBlocked(@NonNull TvContentRating rating) {
        }

        /**
         * Called when signal strength is changed.
         */
        public void onSignalStrength(@TvInputManager.SignalStrength int strength) {
        }

        /**
         * Called when a broadcast info response is received.
         */
        public void onBroadcastInfoResponse(@NonNull BroadcastInfoResponse response) {
        }

        /**
         * Called when an advertisement response is received.
         */
        public void onAdResponse(@NonNull AdResponse response) {
        }

        /**
         * Called when an advertisement buffer is consumed.
         *
         * @param buffer The {@link AdBuffer} that was consumed.
         */
        public void onAdBufferConsumed(@NonNull AdBuffer buffer) {
        }

        /**
         * Called when a TV message is received
         *
         * @param type The type of message received, such as
         * {@link TvInputManager#TV_MESSAGE_TYPE_WATERMARK}
         * @param data The raw data of the message. The bundle keys are:
         *             {@link TvInputManager#TV_MESSAGE_KEY_STREAM_ID},
         *             {@link TvInputManager#TV_MESSAGE_KEY_GROUP_ID},
         *             {@link TvInputManager#TV_MESSAGE_KEY_SUBTYPE},
         *             {@link TvInputManager#TV_MESSAGE_KEY_RAW_DATA}.
         *             See {@link TvInputManager#TV_MESSAGE_KEY_SUBTYPE} for more information on
         *             how to parse this data.
         */
        public void onTvMessage(@TvInputManager.TvMessageType int type,
                @NonNull Bundle data) {
        }

        /**
         * Called when the TV App sends the selected track info as a response to
         * {@link #requestSelectedTrackInfo()}
         *
         * @param tracks A list of {@link TvTrackInfo} that are currently selected
         */
        @FlaggedApi(Flags.FLAG_TIAF_V_APIS)
        public void onSelectedTrackInfo(@NonNull List<TvTrackInfo> tracks) {
        }

        @Override
        public boolean onKeyDown(int keyCode, @NonNull KeyEvent event) {
            return false;
        }

        @Override
        public boolean onKeyLongPress(int keyCode, @NonNull KeyEvent event) {
            return false;
        }

        @Override
        public boolean onKeyMultiple(int keyCode, int count, @NonNull KeyEvent event) {
            return false;
        }

        @Override
        public boolean onKeyUp(int keyCode, @NonNull KeyEvent event) {
            return false;
        }

        /**
         * Implement this method to handle touch screen motion events on the current session.
         *
         * @param event The motion event being received.
         * @return If you handled the event, return {@code true}. If you want to allow the event to
         *         be handled by the next receiver, return {@code false}.
         * @see View#onTouchEvent
         */
        public boolean onTouchEvent(@NonNull MotionEvent event) {
            return false;
        }

        /**
         * Implement this method to handle trackball events on the current session.
         *
         * @param event The motion event being received.
         * @return If you handled the event, return {@code true}. If you want to allow the event to
         *         be handled by the next receiver, return {@code false}.
         * @see View#onTrackballEvent
         */
        public boolean onTrackballEvent(@NonNull MotionEvent event) {
            return false;
        }

        /**
         * Implement this method to handle generic motion events on the current session.
         *
         * @param event The motion event being received.
         * @return If you handled the event, return {@code true}. If you want to allow the event to
         *         be handled by the next receiver, return {@code false}.
         * @see View#onGenericMotionEvent
         */
        public boolean onGenericMotionEvent(@NonNull MotionEvent event) {
            return false;
        }

        /**
         * Assigns a size and position to the surface passed in {@link #onSetSurface}. The position
         * is relative to the overlay view that sits on top of this surface.
         *
         * @param left Left position in pixels, relative to the overlay view.
         * @param top Top position in pixels, relative to the overlay view.
         * @param right Right position in pixels, relative to the overlay view.
         * @param bottom Bottom position in pixels, relative to the overlay view.
         */
        @CallSuper
        public void layoutSurface(final int left, final int top, final int right,
                final int bottom) {
            if (left > right || top > bottom) {
                throw new IllegalArgumentException("Invalid parameter");
            }
            executeOrPostRunnableOnMainThread(new Runnable() {
                @MainThread
                @Override
                public void run() {
                    try {
                        if (DEBUG) {
                            Log.d(TAG, "layoutSurface (l=" + left + ", t=" + top
                                    + ", r=" + right + ", b=" + bottom + ",)");
                        }
                        if (mSessionCallback != null) {
                            mSessionCallback.onLayoutSurface(left, top, right, bottom);
                        }
                    } catch (RemoteException e) {
                        Log.w(TAG, "error in layoutSurface", e);
                    }
                }
            });
        }

        /**
         * Requests broadcast related information from the related TV input.
         * @param request the request for broadcast info
         */
        @CallSuper
        public void requestBroadcastInfo(@NonNull final BroadcastInfoRequest request) {
            executeOrPostRunnableOnMainThread(new Runnable() {
                @MainThread
                @Override
                public void run() {
                    try {
                        if (DEBUG) {
                            Log.d(TAG, "requestBroadcastInfo (requestId="
                                    + request.getRequestId() + ")");
                        }
                        if (mSessionCallback != null) {
                            mSessionCallback.onBroadcastInfoRequest(request);
                        }
                    } catch (RemoteException e) {
                        Log.w(TAG, "error in requestBroadcastInfo", e);
                    }
                }
            });
        }

        /**
         * Remove broadcast information request from the related TV input.
         * @param requestId the ID of the request
         */
        @CallSuper
        public void removeBroadcastInfo(final int requestId) {
            executeOrPostRunnableOnMainThread(new Runnable() {
                @MainThread
                @Override
                public void run() {
                    try {
                        if (DEBUG) {
                            Log.d(TAG, "removeBroadcastInfo (requestId="
                                    + requestId + ")");
                        }
                        if (mSessionCallback != null) {
                            mSessionCallback.onRemoveBroadcastInfo(requestId);
                        }
                    } catch (RemoteException e) {
                        Log.w(TAG, "error in removeBroadcastInfo", e);
                    }
                }
            });
        }

        /**
         * Sends a specific playback command to be processed by the related TV input.
         *
         * @param cmdType type of the specific command
         * @param parameters parameters of the specific command
         */
        @CallSuper
        public void sendPlaybackCommandRequest(
                @PlaybackCommandType @NonNull String cmdType, @Nullable Bundle parameters) {
            executeOrPostRunnableOnMainThread(new Runnable() {
                @MainThread
                @Override
                public void run() {
                    try {
                        if (DEBUG) {
                            Log.d(TAG, "requestCommand (cmdType=" + cmdType + ", parameters="
                                    + parameters.toString() + ")");
                        }
                        if (mSessionCallback != null) {
                            mSessionCallback.onCommandRequest(cmdType, parameters);
                        }
                    } catch (RemoteException e) {
                        Log.w(TAG, "error in requestCommand", e);
                    }
                }
            });
        }

        /**
         * Sends a specific time shift command to be processed by the related TV input.
         *
         * @param cmdType type of the specific command
         * @param parameters parameters of the specific command
         */
        @CallSuper
        public void sendTimeShiftCommandRequest(
                @TimeShiftCommandType @NonNull String cmdType, @Nullable Bundle parameters) {
            executeOrPostRunnableOnMainThread(new Runnable() {
                @MainThread
                @Override
                public void run() {
                    try {
                        if (DEBUG) {
                            Log.d(TAG, "requestTimeShiftCommand (cmdType=" + cmdType
                                    + ", parameters=" + parameters.toString() + ")");
                        }
                        if (mSessionCallback != null) {
                            mSessionCallback.onTimeShiftCommandRequest(cmdType, parameters);
                        }
                    } catch (RemoteException e) {
                        Log.w(TAG, "error in requestTimeShiftCommand", e);
                    }
                }
            });
        }

        /**
         * Sets broadcast video bounds.
         */
        @CallSuper
        public void setVideoBounds(@NonNull Rect rect) {
            executeOrPostRunnableOnMainThread(new Runnable() {
                @MainThread
                @Override
                public void run() {
                    try {
                        if (DEBUG) {
                            Log.d(TAG, "setVideoBounds (rect=" + rect + ")");
                        }
                        if (mSessionCallback != null) {
                            mSessionCallback.onSetVideoBounds(rect);
                        }
                    } catch (RemoteException e) {
                        Log.w(TAG, "error in setVideoBounds", e);
                    }
                }
            });
        }

        /**
         * Requests the bounds of the current video.
         */
        @CallSuper
        public void requestCurrentVideoBounds() {
            executeOrPostRunnableOnMainThread(new Runnable() {
                @MainThread
                @Override
                public void run() {
                    try {
                        if (DEBUG) {
                            Log.d(TAG, "requestCurrentVideoBounds");
                        }
                        if (mSessionCallback != null) {
                            mSessionCallback.onRequestCurrentVideoBounds();
                        }
                    } catch (RemoteException e) {
                        Log.w(TAG, "error in requestCurrentVideoBounds", e);
                    }
                }
            });
        }

        /**
         * Requests the URI of the current channel.
         */
        @CallSuper
        public void requestCurrentChannelUri() {
            executeOrPostRunnableOnMainThread(new Runnable() {
                @MainThread
                @Override
                public void run() {
                    try {
                        if (DEBUG) {
                            Log.d(TAG, "requestCurrentChannelUri");
                        }
                        if (mSessionCallback != null) {
                            mSessionCallback.onRequestCurrentChannelUri();
                        }
                    } catch (RemoteException e) {
                        Log.w(TAG, "error in requestCurrentChannelUri", e);
                    }
                }
            });
        }

        /**
         * Requests the logic channel number (LCN) of the current channel.
         */
        @CallSuper
        public void requestCurrentChannelLcn() {
            executeOrPostRunnableOnMainThread(new Runnable() {
                @MainThread
                @Override
                public void run() {
                    try {
                        if (DEBUG) {
                            Log.d(TAG, "requestCurrentChannelLcn");
                        }
                        if (mSessionCallback != null) {
                            mSessionCallback.onRequestCurrentChannelLcn();
                        }
                    } catch (RemoteException e) {
                        Log.w(TAG, "error in requestCurrentChannelLcn", e);
                    }
                }
            });
        }

        /**
         * Requests stream volume.
         */
        @CallSuper
        public void requestStreamVolume() {
            executeOrPostRunnableOnMainThread(new Runnable() {
                @MainThread
                @Override
                public void run() {
                    try {
                        if (DEBUG) {
                            Log.d(TAG, "requestStreamVolume");
                        }
                        if (mSessionCallback != null) {
                            mSessionCallback.onRequestStreamVolume();
                        }
                    } catch (RemoteException e) {
                        Log.w(TAG, "error in requestStreamVolume", e);
                    }
                }
            });
        }

        /**
         * Requests the list of {@link TvTrackInfo}.
         */
        @CallSuper
        public void requestTrackInfoList() {
            executeOrPostRunnableOnMainThread(new Runnable() {
                @MainThread
                @Override
                public void run() {
                    try {
                        if (DEBUG) {
                            Log.d(TAG, "requestTrackInfoList");
                        }
                        if (mSessionCallback != null) {
                            mSessionCallback.onRequestTrackInfoList();
                        }
                    } catch (RemoteException e) {
                        Log.w(TAG, "error in requestTrackInfoList", e);
                    }
                }
            });
        }

        /**
         * Requests current TV input ID.
         *
         * @see android.media.tv.TvInputInfo
         */
        @CallSuper
        public void requestCurrentTvInputId() {
            executeOrPostRunnableOnMainThread(new Runnable() {
                @MainThread
                @Override
                public void run() {
                    try {
                        if (DEBUG) {
                            Log.d(TAG, "requestCurrentTvInputId");
                        }
                        if (mSessionCallback != null) {
                            mSessionCallback.onRequestCurrentTvInputId();
                        }
                    } catch (RemoteException e) {
                        Log.w(TAG, "error in requestCurrentTvInputId", e);
                    }
                }
            });
        }

        /**
         * Requests time shift mode.
         */
        @CallSuper
        public void requestTimeShiftMode() {
            executeOrPostRunnableOnMainThread(new Runnable() {
                @MainThread
                @Override
                public void run() {
                    try {
                        if (DEBUG) {
                            Log.d(TAG, "requestTimeShiftMode");
                        }
                        if (mSessionCallback != null) {
                            mSessionCallback.onRequestTimeShiftMode();
                        }
                    } catch (RemoteException e) {
                        Log.w(TAG, "error in requestTimeShiftMode", e);
                    }
                }
            });
        }

        /**
         * Requests available speeds for time shift.
         */
        @CallSuper
        public void requestAvailableSpeeds() {
            executeOrPostRunnableOnMainThread(new Runnable() {
                @MainThread
                @Override
                public void run() {
                    try {
                        if (DEBUG) {
                            Log.d(TAG, "requestAvailableSpeeds");
                        }
                        if (mSessionCallback != null) {
                            mSessionCallback.onRequestAvailableSpeeds();
                        }
                    } catch (RemoteException e) {
                        Log.w(TAG, "error in requestAvailableSpeeds", e);
                    }
                }
            });
        }

        /**
         * Requests a list of the currently selected {@link TvTrackInfo} from the TV App.
         *
         * <p> Normally, track info cannot be synchronized until the channel has
         * been changed. This is used when the session of the {@link TvInteractiveAppService}
         * is newly created and the normal synchronization has not happened yet.
         */
        @FlaggedApi(Flags.FLAG_TIAF_V_APIS)
        @CallSuper
        public void requestSelectedTrackInfo() {
            executeOrPostRunnableOnMainThread(() -> {
                try {
                    if (DEBUG) {
                        Log.d(TAG, "requestSelectedTrackInfo");
                    }
                    if (mSessionCallback != null) {
                        mSessionCallback.onRequestSelectedTrackInfo();
                    }
                } catch (RemoteException e) {
                    Log.w(TAG, "error in requestSelectedTrackInfo", e);
                }
            });
        }

        /**
         * Requests starting of recording
         *
         * <p> This is used to request the active {@link android.media.tv.TvRecordingClient} to
         * call {@link android.media.tv.TvRecordingClient#startRecording(Uri)} with the provided
         * {@code programUri}.
         * A non-null {@code programUri} implies the started recording should be of that specific
         * program, whereas null {@code programUri} does not impose such a requirement and the
         * recording can span across multiple TV programs.
         *
         * @param requestId The ID of this request which is used to match the corresponding
         *                  response. The request ID in
         *                  {@link #onRecordingStarted(String, String)} for this request is the
         *                  same as the ID sent here. This should be defined by the
         *                  {@link TvInteractiveAppService} and can be any string.
         *                  Should this API be called with the same requestId twice, both 
         *                  requests should be handled regardless by the TV application.
         * @param programUri The URI for the TV program to record, built by
         *            {@link TvContract#buildProgramUri(long)}. Can be {@code null}.
         * @see android.media.tv.TvRecordingClient#startRecording(Uri)
         */
        @CallSuper
        public void requestStartRecording(@NonNull String requestId, @Nullable Uri programUri) {
            executeOrPostRunnableOnMainThread(() -> {
                try {
                    if (DEBUG) {
                        Log.d(TAG, "requestStartRecording");
                    }
                    if (mSessionCallback != null) {
                        mSessionCallback.onRequestStartRecording(requestId, programUri);
                    }
                } catch (RemoteException e) {
                    Log.w(TAG, "error in requestStartRecording", e);
                }
            });
        }

        /**
         * Requests the recording associated with the recordingId to stop.
         *
         * <p> This is used to request the associated {@link android.media.tv.TvRecordingClient} to
         * call {@link android.media.tv.TvRecordingClient#stopRecording()}.
         *
         * @param recordingId The ID of the recording to stop. This is provided by the TV app in
         *                    {@link TvInteractiveAppView#notifyRecordingStarted(String, String)}
         * @see android.media.tv.TvRecordingClient#stopRecording()
         */
        @CallSuper
        public void requestStopRecording(@NonNull String recordingId) {
            executeOrPostRunnableOnMainThread(() -> {
                try {
                    if (DEBUG) {
                        Log.d(TAG, "requestStopRecording");
                    }
                    if (mSessionCallback != null) {
                        mSessionCallback.onRequestStopRecording(recordingId);
                    }
                } catch (RemoteException e) {
                    Log.w(TAG, "error in requestStopRecording", e);
                }
            });
        }

        /**
         * Requests scheduling of a recording.
         *
         * @param requestId The ID of this request which is used to match the corresponding
         *                  response. The request ID in
         *                  {@link #onRecordingScheduled(String, String)} for this request is the
         *                  same as the ID sent here. This should be defined by the
         *                  {@link TvInteractiveAppService} and can be any string.
         *                  Should this API be called with the same requestId twice, both requests
         *                  should be handled regardless by the TV application.
         * @param inputId The ID of the TV input for the given channel.
         * @param channelUri The URI of a channel to be recorded.
         * @param programUri The URI of the TV program to be recorded.
         * @param params Domain-specific data for this tune request. Keys <em>must</em> be a scoped
         *            name, i.e. prefixed with a package name you own, so that different developers
         *            will not create conflicting keys.
         * @see android.media.tv.TvRecordingClient#tune(String, Uri, Bundle)
         * @see android.media.tv.TvRecordingClient#startRecording(Uri)
         */
        @CallSuper
        public void requestScheduleRecording(@NonNull String requestId, @NonNull String inputId,
                @NonNull Uri channelUri, @NonNull Uri programUri, @NonNull Bundle params) {
            executeOrPostRunnableOnMainThread(() -> {
                try {
                    if (DEBUG) {
                        Log.d(TAG, "requestScheduleRecording");
                    }
                    if (mSessionCallback != null) {
                        mSessionCallback.onRequestScheduleRecording(
                                requestId, inputId, channelUri, programUri, params);
                    }
                } catch (RemoteException e) {
                    Log.w(TAG, "error in requestScheduleRecording", e);
                }
            });
        }

        /**
         * Requests scheduling of a recording.
         *
         * @param requestId The ID of this request which is used to match the corresponding
         *                  response. The request ID in
         *                  {@link #onRecordingScheduled(String, String)} for this request is the
         *                  same as the ID sent here. This should be defined by the
         *                  {@link TvInteractiveAppService} and can be any string. Should this API
         *                  be called with the same requestId twice, both requests should be handled
         *                  regardless by the TV application.
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
        @CallSuper
        public void requestScheduleRecording(@NonNull String requestId, @NonNull String inputId,
                @NonNull Uri channelUri, long startTime, long duration, int repeatDays,
                @NonNull Bundle params) {
            executeOrPostRunnableOnMainThread(() -> {
                try {
                    if (DEBUG) {
                        Log.d(TAG, "requestScheduleRecording");
                    }
                    if (mSessionCallback != null) {
                        mSessionCallback.onRequestScheduleRecording2(requestId, inputId, channelUri,
                                startTime, duration, repeatDays, params);
                    }
                } catch (RemoteException e) {
                    Log.w(TAG, "error in requestScheduleRecording", e);
                }
            });
        }

        /**
         * Sets the recording info for the specified recording
         *
         * @param recordingId The ID of the recording to set the info for. This is provided by the
         *     TV app in {@link TvInteractiveAppView#notifyRecordingStarted(String, String)}
         * @param recordingInfo The {@link TvRecordingInfo} to set to the recording.
         */
        @CallSuper
        public void setTvRecordingInfo(
                @NonNull String recordingId, @NonNull TvRecordingInfo recordingInfo) {
            executeOrPostRunnableOnMainThread(() -> {
                try {
                    if (DEBUG) {
                        Log.d(TAG, "setTvRecordingInfo");
                    }
                    if (mSessionCallback != null) {
                        mSessionCallback.onSetTvRecordingInfo(recordingId, recordingInfo);
                    }
                } catch (RemoteException e) {
                    Log.w(TAG, "error in setTvRecordingInfo", e);
                }
            });
        }

        /**
         * Gets the recording info for the specified recording
         * @param recordingId The ID of the recording to set the info for. This is provided by the
         *                    TV app in
         *                    {@link TvInteractiveAppView#notifyRecordingStarted(String, String)}
         */
        @CallSuper
        public void requestTvRecordingInfo(@NonNull String recordingId) {
            executeOrPostRunnableOnMainThread(() -> {
                try {
                    if (DEBUG) {
                        Log.d(TAG, "requestTvRecordingInfo");
                    }
                    if (mSessionCallback != null) {
                        mSessionCallback.onRequestTvRecordingInfo(recordingId);
                    }
                } catch (RemoteException e) {
                    Log.w(TAG, "error in requestTvRecordingInfo", e);
                }
            });
        }

        /**
         * Gets a list of {@link TvRecordingInfo} for the specified recording type.
         *
         * @param type The type of recording to retrieve.
         */
        @CallSuper
        public void requestTvRecordingInfoList(@TvRecordingInfo.TvRecordingListType int type) {
            executeOrPostRunnableOnMainThread(() -> {
                try {
                    if (DEBUG) {
                        Log.d(TAG, "requestTvRecordingInfoList");
                    }
                    if (mSessionCallback != null) {
                        mSessionCallback.onRequestTvRecordingInfoList(type);
                    }
                } catch (RemoteException e) {
                    Log.w(TAG, "error in requestTvRecordingInfoList", e);
                }
            });
        }

        /**
         * Requests signing of the given data.
         *
         * <p>This is used when the corresponding server of the broadcast-independent interactive
         * app requires signing during handshaking, and the interactive app service doesn't have
         * the built-in private key. The private key is provided by the content providers and
         * pre-built in the related app, such as TV app.
         *
         * @param signingId the ID to identify the request. When a result is received, this ID can
         *                  be used to correlate the result with the request.
         * @param algorithm the standard name of the signature algorithm requested, such as
         *                  MD5withRSA, SHA256withDSA, etc. The name is from standards like
         *                  FIPS PUB 186-4 and PKCS #1.
         * @param alias the alias of the corresponding {@link java.security.KeyStore}.
         * @param data the original bytes to be signed.
         *
         * @see #onSigningResult(String, byte[])
         * @see TvInteractiveAppView#createBiInteractiveApp(Uri, Bundle)
         * @see TvInteractiveAppView#BI_INTERACTIVE_APP_KEY_ALIAS
         */
        @CallSuper
        public void requestSigning(@NonNull String signingId, @NonNull String algorithm,
                @NonNull String alias, @NonNull byte[] data) {
            executeOrPostRunnableOnMainThread(new Runnable() {
                @MainThread
                @Override
                public void run() {
                    try {
                        if (DEBUG) {
                            Log.d(TAG, "requestSigning");
                        }
                        if (mSessionCallback != null) {
                            mSessionCallback.onRequestSigning(signingId, algorithm, alias, data);
                        }
                    } catch (RemoteException e) {
                        Log.w(TAG, "error in requestSigning", e);
                    }
                }
            });
        }

        /**
         * Requests signing of the given data.
         *
         * <p>This is used when the corresponding server of the broadcast-independent interactive
         * app requires signing during handshaking, and the interactive app service doesn't have
         * the built-in private key. The private key is provided by the content providers and
         * pre-built in the related app, such as TV app.
         *
         * @param signingId the ID to identify the request. When a result is received, this ID can
         *                  be used to correlate the result with the request.
         * @param algorithm the standard name of the signature algorithm requested, such as
         *                  MD5withRSA, SHA256withDSA, etc. The name is from standards like
         *                  FIPS PUB 186-4 and PKCS #1.
         * @param host the host of the SSL client authentication server.
         * @param port the port of the SSL client authentication server.
         * @param data the original bytes to be signed.
         *
         * @see #onSigningResult(String, byte[])
         * @see TvInteractiveAppView#createBiInteractiveApp(Uri, Bundle)
         * @see TvInteractiveAppView#BI_INTERACTIVE_APP_KEY_ALIAS
         */
        @CallSuper
        @FlaggedApi(Flags.FLAG_TIAF_V_APIS)
        public void requestSigning(@NonNull String signingId, @NonNull String algorithm,
                @NonNull String host, int port, @NonNull byte[] data) {
            executeOrPostRunnableOnMainThread(new Runnable() {
                @MainThread
                @Override
                public void run() {
                    try {
                        if (DEBUG) {
                            Log.d(TAG, "requestSigning");
                        }
                        if (mSessionCallback != null) {
                            mSessionCallback.onRequestSigning2(signingId, algorithm,
                                    host, port, data);
                        }
                    } catch (RemoteException e) {
                        Log.w(TAG, "error in requestSigning", e);
                    }
                }
            });
        }

        /**
         * Requests a SSL certificate for client validation.
         *
         * @param host the host name of the SSL authentication server.
         * @param port the port of the SSL authentication server. E.g., 443
         */
        @CallSuper
        @FlaggedApi(Flags.FLAG_TIAF_V_APIS)
        public void requestCertificate(@NonNull String host, int port) {
            executeOrPostRunnableOnMainThread(new Runnable() {
                @MainThread
                @Override
                public void run() {
                    try {
                        if (DEBUG) {
                            Log.d(TAG, "requestCertificate");
                        }
                        if (mSessionCallback != null) {
                            mSessionCallback.onRequestCertificate(host, port);
                        }
                    } catch (RemoteException e) {
                        Log.w(TAG, "error in requestCertificate", e);
                    }
                }
            });
        }

        /**
         * Sends an advertisement request to be processed by the related TV input.
         *
         * @param request The advertisement request
         */
        @CallSuper
        public void requestAd(@NonNull final AdRequest request) {
            executeOrPostRunnableOnMainThread(new Runnable() {
                @MainThread
                @Override
                public void run() {
                    try {
                        if (DEBUG) {
                            Log.d(TAG, "requestAd (id=" + request.getId() + ")");
                        }
                        if (mSessionCallback != null) {
                            mSessionCallback.onAdRequest(request);
                        }
                    } catch (RemoteException e) {
                        Log.w(TAG, "error in requestAd", e);
                    }
                }
            });
        }

        void startInteractiveApp() {
            onStartInteractiveApp();
        }

        void stopInteractiveApp() {
            onStopInteractiveApp();
        }

        void resetInteractiveApp() {
            onResetInteractiveApp();
        }

        void createBiInteractiveApp(@NonNull Uri biIAppUri, @Nullable Bundle params) {
            onCreateBiInteractiveAppRequest(biIAppUri, params);
        }

        void destroyBiInteractiveApp(@NonNull String biIAppId) {
            onDestroyBiInteractiveAppRequest(biIAppId);
        }

        void setTeletextAppEnabled(boolean enable) {
            onSetTeletextAppEnabled(enable);
        }

        void sendCurrentVideoBounds(@NonNull Rect bounds) {
            onCurrentVideoBounds(bounds);
        }

        void sendCurrentChannelUri(@Nullable Uri channelUri) {
            onCurrentChannelUri(channelUri);
        }

        void sendCurrentChannelLcn(int lcn) {
            onCurrentChannelLcn(lcn);
        }

        void sendStreamVolume(float volume) {
            onStreamVolume(volume);
        }

        void sendTrackInfoList(@NonNull List<TvTrackInfo> tracks) {
            onTrackInfoList(tracks);
        }

        void sendCurrentTvInputId(@Nullable String inputId) {
            onCurrentTvInputId(inputId);
        }

        void sendTimeShiftMode(int mode) {
            onTimeShiftMode(mode);
        }

        void sendAvailableSpeeds(@NonNull float[] speeds) {
            onAvailableSpeeds(speeds);
        }

        void sendTvRecordingInfo(@Nullable TvRecordingInfo recordingInfo) {
            onTvRecordingInfo(recordingInfo);
        }

        void sendTvRecordingInfoList(@Nullable List<TvRecordingInfo> recordingInfoList) {
            onTvRecordingInfoList(recordingInfoList);
        }

        void sendSigningResult(String signingId, byte[] result) {
            onSigningResult(signingId, result);
        }

        void sendCertificate(String host, int port, Bundle certBundle) {
            SslCertificate cert = SslCertificate.restoreState(certBundle);
            onCertificate(host, port, cert);
        }

        void notifyError(String errMsg, Bundle params) {
            onError(errMsg, params);
        }

        void release() {
            onRelease();
            if (mSurface != null) {
                mSurface.release();
                mSurface = null;
            }
            synchronized (mLock) {
                mSessionCallback = null;
                mPendingActions.clear();
            }
            // Removes the media view lastly so that any hanging on the main thread can be handled
            // in {@link #scheduleMediaViewCleanup}.
            removeMediaView(true);
        }

        void notifyTuned(Uri channelUri) {
            if (DEBUG) {
                Log.d(TAG, "notifyTuned (channelUri=" + channelUri + ")");
            }
            onTuned(channelUri);
        }

        void notifyTrackSelected(int type, String trackId) {
            if (DEBUG) {
                Log.d(TAG, "notifyTrackSelected (type=" + type + "trackId=" + trackId + ")");
            }
            onTrackSelected(type, trackId);
        }

        void notifyTracksChanged(List<TvTrackInfo> tracks) {
            if (DEBUG) {
                Log.d(TAG, "notifyTracksChanged (tracks=" + tracks + ")");
            }
            onTracksChanged(tracks);
        }

        void notifyVideoAvailable() {
            if (DEBUG) {
                Log.d(TAG, "notifyVideoAvailable");
            }
            onVideoAvailable();
        }

        void notifyVideoUnavailable(int reason) {
            if (DEBUG) {
                Log.d(TAG, "notifyVideoAvailable (reason=" + reason + ")");
            }
            onVideoUnavailable(reason);
        }

        void notifyVideoFreezeUpdated(boolean isFrozen) {
            if (DEBUG) {
                Log.d(TAG, "notifyVideoFreezeUpdated (isFrozen=" + isFrozen + ")");
            }
            onVideoFreezeUpdated(isFrozen);
        }

        void notifyContentAllowed() {
            if (DEBUG) {
                Log.d(TAG, "notifyContentAllowed");
            }
            onContentAllowed();
        }

        void notifyContentBlocked(TvContentRating rating) {
            if (DEBUG) {
                Log.d(TAG, "notifyContentBlocked (rating=" + rating.flattenToString() + ")");
            }
            onContentBlocked(rating);
        }

        void notifySignalStrength(int strength) {
            if (DEBUG) {
                Log.d(TAG, "notifySignalStrength (strength=" + strength + ")");
            }
            onSignalStrength(strength);
        }

        /**
         * Calls {@link #onBroadcastInfoResponse}.
         */
        void notifyBroadcastInfoResponse(BroadcastInfoResponse response) {
            if (DEBUG) {
                Log.d(TAG, "notifyBroadcastInfoResponse (requestId="
                        + response.getRequestId() + ")");
            }
            onBroadcastInfoResponse(response);
        }

        /**
         * Calls {@link #onAdResponse}.
         */
        void notifyAdResponse(AdResponse response) {
            if (DEBUG) {
                Log.d(TAG, "notifyAdResponse (requestId=" + response.getId() + ")");
            }
            onAdResponse(response);
        }

        void notifyTvMessage(int type, Bundle data) {
            if (DEBUG) {
                Log.d(TAG, "notifyTvMessage (type=" + type + ", data= " + data + ")");
            }
            onTvMessage(type, data);
        }

        void sendSelectedTrackInfo(List<TvTrackInfo> tracks) {
            if (DEBUG) {
                Log.d(TAG, "notifySelectedTrackInfo (tracks= " + tracks + ")");
            }
            onSelectedTrackInfo(tracks);
        }

        /**
         * Calls {@link #onAdBufferConsumed}.
         */
        void notifyAdBufferConsumed(AdBuffer buffer) {
            if (DEBUG) {
                Log.d(TAG,
                        "notifyAdBufferConsumed (buffer=" + buffer + ")");
            }
            onAdBufferConsumed(buffer);
        }

        /**
         * Calls {@link #onRecordingStarted(String, String)}.
         */
        void notifyRecordingStarted(String recordingId, String requestId) {
            onRecordingStarted(recordingId, requestId);
        }

        /**
         * Calls {@link #onRecordingStopped(String)}.
         */
        void notifyRecordingStopped(String recordingId) {
            onRecordingStopped(recordingId);
        }

        /**
         * Calls {@link #onRecordingConnectionFailed(String, String)}.
         */
        void notifyRecordingConnectionFailed(String recordingId, String inputId) {
            onRecordingConnectionFailed(recordingId, inputId);
        }

        /**
         * Calls {@link #onRecordingDisconnected(String, String)}.
         */
        void notifyRecordingDisconnected(String recordingId, String inputId) {
            onRecordingDisconnected(recordingId, inputId);
        }

        /**
         * Calls {@link #onRecordingTuned(String, Uri)}.
         */
        void notifyRecordingTuned(String recordingId, Uri channelUri) {
            onRecordingTuned(recordingId, channelUri);
        }

        /**
         * Calls {@link #onRecordingError(String, int)}.
         */
        void notifyRecordingError(String recordingId, int err) {
            onRecordingError(recordingId, err);
        }

        /**
         * Calls {@link #onRecordingScheduled(String, String)}.
         */
        void notifyRecordingScheduled(String recordingId, String requestId) {
            onRecordingScheduled(recordingId, requestId);
        }

        /**
         * Calls {@link #onTimeShiftPlaybackParams(PlaybackParams)}.
         */
        void notifyTimeShiftPlaybackParams(PlaybackParams params) {
            onTimeShiftPlaybackParams(params);
        }

        /**
         * Calls {@link #onTimeShiftStatusChanged(String, int)}.
         */
        void notifyTimeShiftStatusChanged(String inputId, int status) {
            onTimeShiftStatusChanged(inputId, status);
        }

        /**
         * Calls {@link #onTimeShiftStartPositionChanged(String, long)}.
         */
        void notifyTimeShiftStartPositionChanged(String inputId, long timeMs) {
            onTimeShiftStartPositionChanged(inputId, timeMs);
        }

        /**
         * Calls {@link #onTimeShiftCurrentPositionChanged(String, long)}.
         */
        void notifyTimeShiftCurrentPositionChanged(String inputId, long timeMs) {
            onTimeShiftCurrentPositionChanged(inputId, timeMs);
        }

        /**
         * Notifies when the session state is changed.
         *
         * @param state the current session state.
         * @param err the error code for error state. {@link TvInteractiveAppManager#ERROR_NONE} is
         *            used when the state is not
         *            {@link TvInteractiveAppManager#INTERACTIVE_APP_STATE_ERROR}.
         */
        @CallSuper
        public void notifySessionStateChanged(
                @TvInteractiveAppManager.InteractiveAppState int state,
                @TvInteractiveAppManager.ErrorCode int err) {
            executeOrPostRunnableOnMainThread(new Runnable() {
                @MainThread
                @Override
                public void run() {
                    try {
                        if (DEBUG) {
                            Log.d(TAG, "notifySessionStateChanged (state="
                                    + state + "; err=" + err + ")");
                        }
                        if (mSessionCallback != null) {
                            mSessionCallback.onSessionStateChanged(state, err);
                        }
                    } catch (RemoteException e) {
                        Log.w(TAG, "error in notifySessionStateChanged", e);
                    }
                }
            });
        }

        /**
         * Notifies the broadcast-independent(BI) interactive application has been created.
         *
         * @param biIAppId BI interactive app ID, which can be used to destroy the BI interactive
         *                 app. {@code null} if it's not created successfully.
         *
         * @see #onCreateBiInteractiveAppRequest(Uri, Bundle)
         */
        @CallSuper
        public final void notifyBiInteractiveAppCreated(
                @NonNull Uri biIAppUri, @Nullable String biIAppId) {
            executeOrPostRunnableOnMainThread(new Runnable() {
                @MainThread
                @Override
                public void run() {
                    try {
                        if (DEBUG) {
                            Log.d(TAG, "notifyBiInteractiveAppCreated (biIAppId="
                                    + biIAppId + ")");
                        }
                        if (mSessionCallback != null) {
                            mSessionCallback.onBiInteractiveAppCreated(biIAppUri, biIAppId);
                        }
                    } catch (RemoteException e) {
                        Log.w(TAG, "error in notifyBiInteractiveAppCreated", e);
                    }
                }
            });
        }

        /**
         * Notifies when the digital teletext app state is changed.
         * @param state the current state.
         */
        @CallSuper
        public final void notifyTeletextAppStateChanged(
                @TvInteractiveAppManager.TeletextAppState int state) {
            executeOrPostRunnableOnMainThread(new Runnable() {
                @MainThread
                @Override
                public void run() {
                    try {
                        if (DEBUG) {
                            Log.d(TAG, "notifyTeletextAppState (state="
                                    + state + ")");
                        }
                        if (mSessionCallback != null) {
                            mSessionCallback.onTeletextAppStateChanged(state);
                        }
                    } catch (RemoteException e) {
                        Log.w(TAG, "error in notifyTeletextAppState", e);
                    }
                }
            });
        }


        /**
         * Notifies when the advertisement buffer is filled and ready to be read.
         *
         * @param buffer The {@link AdBuffer} to be received
         */
        @CallSuper
        public void notifyAdBufferReady(@NonNull AdBuffer buffer) {
            AdBuffer dupBuffer;
            try {
                dupBuffer = AdBuffer.dupAdBuffer(buffer);
            } catch (IOException e) {
                Log.w(TAG, "dup AdBuffer error in notifyAdBufferReady:", e);
                return;
            }
            executeOrPostRunnableOnMainThread(new Runnable() {
                @MainThread
                @Override
                public void run() {
                    try {
                        if (DEBUG) {
                            Log.d(TAG,
                                    "notifyAdBufferReady(buffer=" + buffer + ")");
                        }
                        if (mSessionCallback != null) {
                            mSessionCallback.onAdBufferReady(dupBuffer);
                        }
                    } catch (RemoteException e) {
                        Log.w(TAG, "error in notifyAdBuffer", e);
                    } finally {
                        if (dupBuffer != null) {
                            dupBuffer.getSharedMemory().close();
                        }
                    }
                }
            });
        }


        /**
         * Takes care of dispatching incoming input events and tells whether the event was handled.
         */
        int dispatchInputEvent(InputEvent event, InputEventReceiver receiver) {
            if (DEBUG) Log.d(TAG, "dispatchInputEvent(" + event + ")");
            if (event instanceof KeyEvent) {
                KeyEvent keyEvent = (KeyEvent) event;
                if (keyEvent.dispatch(this, mDispatcherState, this)) {
                    return TvInteractiveAppManager.Session.DISPATCH_HANDLED;
                }

                // TODO: special handlings of navigation keys and media keys
            } else if (event instanceof MotionEvent) {
                MotionEvent motionEvent = (MotionEvent) event;
                final int source = motionEvent.getSource();
                if (motionEvent.isTouchEvent()) {
                    if (onTouchEvent(motionEvent)) {
                        return TvInteractiveAppManager.Session.DISPATCH_HANDLED;
                    }
                } else if ((source & InputDevice.SOURCE_CLASS_TRACKBALL) != 0) {
                    if (onTrackballEvent(motionEvent)) {
                        return TvInteractiveAppManager.Session.DISPATCH_HANDLED;
                    }
                } else {
                    if (onGenericMotionEvent(motionEvent)) {
                        return TvInteractiveAppManager.Session.DISPATCH_HANDLED;
                    }
                }
            }
            // TODO: handle overlay view
            return TvInteractiveAppManager.Session.DISPATCH_NOT_HANDLED;
        }

        private void initialize(ITvInteractiveAppSessionCallback callback) {
            synchronized (mLock) {
                mSessionCallback = callback;
                for (Runnable runnable : mPendingActions) {
                    runnable.run();
                }
                mPendingActions.clear();
            }
        }

        /**
         * Calls {@link #onSetSurface}.
         */
        void setSurface(Surface surface) {
            onSetSurface(surface);
            if (mSurface != null) {
                mSurface.release();
            }
            mSurface = surface;
            // TODO: Handle failure.
        }

        /**
         * Calls {@link #onSurfaceChanged}.
         */
        void dispatchSurfaceChanged(int format, int width, int height) {
            if (DEBUG) {
                Log.d(TAG, "dispatchSurfaceChanged(format=" + format + ", width=" + width
                        + ", height=" + height + ")");
            }
            onSurfaceChanged(format, width, height);
        }

        private void executeOrPostRunnableOnMainThread(Runnable action) {
            synchronized (mLock) {
                if (mSessionCallback == null) {
                    // The session is not initialized yet.
                    mPendingActions.add(action);
                } else {
                    if (mHandler.getLooper().isCurrentThread()) {
                        action.run();
                    } else {
                        // Posts the runnable if this is not called from the main thread
                        mHandler.post(action);
                    }
                }
            }
        }

        /**
         * Creates an media view. This calls {@link #onCreateMediaView} to get a view to attach
         * to the media window.
         *
         * @param windowToken A window token of the application.
         * @param frame A position of the media view.
         */
        void createMediaView(IBinder windowToken, Rect frame) {
            if (mMediaViewContainer != null) {
                removeMediaView(false);
            }
            if (DEBUG) Log.d(TAG, "create media view(" + frame + ")");
            mWindowToken = windowToken;
            mMediaFrame = frame;
            onMediaViewSizeChanged(frame.right - frame.left, frame.bottom - frame.top);
            if (!mMediaViewEnabled) {
                return;
            }
            mMediaView = onCreateMediaView();
            if (mMediaView == null) {
                return;
            }
            if (mMediaViewCleanUpTask != null) {
                mMediaViewCleanUpTask.cancel(true);
                mMediaViewCleanUpTask = null;
            }
            // Creates a container view to check hanging on the media view detaching.
            // Adding/removing the media view to/from the container make the view attach/detach
            // logic run on the main thread.
            mMediaViewContainer = new FrameLayout(mContext.getApplicationContext());
            mMediaViewContainer.addView(mMediaView);

            int type = WindowManager.LayoutParams.TYPE_APPLICATION_MEDIA;
            // We make the overlay view non-focusable and non-touchable so that
            // the application that owns the window token can decide whether to consume or
            // dispatch the input events.
            int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
            if (ActivityManager.isHighEndGfx()) {
                flags |= WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
            }
            mWindowParams = new WindowManager.LayoutParams(
                    frame.right - frame.left, frame.bottom - frame.top,
                    frame.left, frame.top, type, flags, PixelFormat.TRANSPARENT);
            mWindowParams.privateFlags |=
                    WindowManager.LayoutParams.PRIVATE_FLAG_NO_MOVE_ANIMATION;
            mWindowParams.gravity = Gravity.START | Gravity.TOP;
            mWindowParams.token = windowToken;
            mWindowManager.addView(mMediaViewContainer, mWindowParams);
        }

        /**
         * Relayouts the current media view.
         *
         * @param frame A new position of the media view.
         */
        void relayoutMediaView(Rect frame) {
            if (DEBUG) Log.d(TAG, "relayoutMediaView(" + frame + ")");
            if (mMediaFrame == null || mMediaFrame.width() != frame.width()
                    || mMediaFrame.height() != frame.height()) {
                // Note: relayoutMediaView is called whenever TvInteractiveAppView's layout is
                // changed regardless of setMediaViewEnabled.
                onMediaViewSizeChanged(frame.right - frame.left, frame.bottom - frame.top);
            }
            mMediaFrame = frame;
            if (!mMediaViewEnabled || mMediaViewContainer == null) {
                return;
            }
            mWindowParams.x = frame.left;
            mWindowParams.y = frame.top;
            mWindowParams.width = frame.right - frame.left;
            mWindowParams.height = frame.bottom - frame.top;
            mWindowManager.updateViewLayout(mMediaViewContainer, mWindowParams);
        }

        /**
         * Removes the current media view.
         */
        void removeMediaView(boolean clearWindowToken) {
            if (DEBUG) Log.d(TAG, "removeMediaView(" + mMediaViewContainer + ")");
            if (clearWindowToken) {
                mWindowToken = null;
                mMediaFrame = null;
            }
            if (mMediaViewContainer != null) {
                // Removes the media view from the view hierarchy in advance so that it can be
                // cleaned up in the {@link MediaViewCleanUpTask} if the remove process is
                // hanging.
                mMediaViewContainer.removeView(mMediaView);
                mMediaView = null;
                mWindowManager.removeView(mMediaViewContainer);
                mMediaViewContainer = null;
                mWindowParams = null;
            }
        }

        /**
         * Schedules a task which checks whether the media view is detached and kills the process
         * if it is not. Note that this method is expected to be called in a non-main thread.
         */
        void scheduleMediaViewCleanup() {
            View mediaViewParent = mMediaViewContainer;
            if (mediaViewParent != null) {
                mMediaViewCleanUpTask = new MediaViewCleanUpTask();
                mMediaViewCleanUpTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR,
                        mediaViewParent);
            }
        }
    }

    private static final class MediaViewCleanUpTask extends AsyncTask<View, Void, Void> {
        @Override
        protected Void doInBackground(View... views) {
            View mediaViewParent = views[0];
            try {
                Thread.sleep(DETACH_MEDIA_VIEW_TIMEOUT_MS);
            } catch (InterruptedException e) {
                return null;
            }
            if (isCancelled()) {
                return null;
            }
            if (mediaViewParent.isAttachedToWindow()) {
                Log.e(TAG, "Time out on releasing media view. Killing "
                        + mediaViewParent.getContext().getPackageName());
                android.os.Process.killProcess(Process.myPid());
            }
            return null;
        }
    }

    @SuppressLint("HandlerLeak")
    private final class ServiceHandler extends Handler {
        private static final int DO_CREATE_SESSION = 1;
        private static final int DO_NOTIFY_SESSION_CREATED = 2;
        private static final int DO_NOTIFY_RTE_STATE_CHANGED = 3;

        private void broadcastRteStateChanged(int type, int state, int error) {
            int n = mCallbacks.beginBroadcast();
            for (int i = 0; i < n; ++i) {
                try {
                    mCallbacks.getBroadcastItem(i).onStateChanged(type, state, error);
                } catch (RemoteException e) {
                    Log.e(TAG, "error in broadcastRteStateChanged", e);
                }
            }
            mCallbacks.finishBroadcast();
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case DO_CREATE_SESSION: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    InputChannel channel = (InputChannel) args.arg1;
                    ITvInteractiveAppSessionCallback cb =
                            (ITvInteractiveAppSessionCallback) args.arg2;
                    String iAppServiceId = (String) args.arg3;
                    int type = (int) args.arg4;
                    args.recycle();
                    Session sessionImpl = onCreateSession(iAppServiceId, type);
                    if (sessionImpl == null) {
                        try {
                            // Failed to create a session.
                            cb.onSessionCreated(null);
                        } catch (RemoteException e) {
                            Log.e(TAG, "error in onSessionCreated", e);
                        }
                        return;
                    }
                    ITvInteractiveAppSession stub = new ITvInteractiveAppSessionWrapper(
                            TvInteractiveAppService.this, sessionImpl, channel);

                    SomeArgs someArgs = SomeArgs.obtain();
                    someArgs.arg1 = sessionImpl;
                    someArgs.arg2 = stub;
                    someArgs.arg3 = cb;
                    mServiceHandler.obtainMessage(ServiceHandler.DO_NOTIFY_SESSION_CREATED,
                            someArgs).sendToTarget();
                    return;
                }
                case DO_NOTIFY_SESSION_CREATED: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    Session sessionImpl = (Session) args.arg1;
                    ITvInteractiveAppSession stub = (ITvInteractiveAppSession) args.arg2;
                    ITvInteractiveAppSessionCallback cb =
                            (ITvInteractiveAppSessionCallback) args.arg3;
                    try {
                        cb.onSessionCreated(stub);
                    } catch (RemoteException e) {
                        Log.e(TAG, "error in onSessionCreated", e);
                    }
                    if (sessionImpl != null) {
                        sessionImpl.initialize(cb);
                    }
                    args.recycle();
                    return;
                }
                case DO_NOTIFY_RTE_STATE_CHANGED: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    int type = (int) args.arg1;
                    int state = (int) args.arg2;
                    int error = (int) args.arg3;
                    broadcastRteStateChanged(type, state, error);
                    return;
                }
                default: {
                    Log.w(TAG, "Unhandled message code: " + msg.what);
                    return;
                }
            }
        }

    }
}
