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

import android.annotation.CallbackExecutor;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.annotation.TestApi;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.media.PlaybackParams;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.Log;
import android.util.Pools.Pool;
import android.util.Pools.SimplePool;
import android.util.SparseArray;
import android.view.InputChannel;
import android.view.InputEvent;
import android.view.InputEventSender;
import android.view.KeyEvent;
import android.view.Surface;
import android.view.View;

import com.android.internal.util.Preconditions;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * Central system API to the overall TV input framework (TIF) architecture, which arbitrates
 * interaction between applications and the selected TV inputs.
 *
 * <p>There are three primary parties involved in the TV input framework (TIF) architecture:
 *
 * <ul>
 * <li>The <strong>TV input manager</strong> as expressed by this class is the central point of the
 * system that manages interaction between all other parts. It is expressed as the client-side API
 * here which exists in each application context and communicates with a global system service that
 * manages the interaction across all processes.
 * <li>A <strong>TV input</strong> implemented by {@link TvInputService} represents an input source
 * of TV, which can be a pass-through input such as HDMI, or a tuner input which provides broadcast
 * TV programs. The system binds to the TV input per application’s request.
 * on implementing TV inputs.
 * <li><strong>Applications</strong> talk to the TV input manager to list TV inputs and check their
 * status. Once an application find the input to use, it uses {@link TvView} or
 * {@link TvRecordingClient} for further interaction such as watching and recording broadcast TV
 * programs.
 * </ul>
 */
@SystemService(Context.TV_INPUT_SERVICE)
public final class TvInputManager {
    private static final String TAG = "TvInputManager";

    static final int DVB_DEVICE_START = 0;
    static final int DVB_DEVICE_END = 2;

    /**
     * A demux device of DVB API for controlling the filters of DVB hardware/software.
     * @hide
     */
    public static final int DVB_DEVICE_DEMUX = DVB_DEVICE_START;
     /**
     * A DVR device of DVB API for reading transport streams.
     * @hide
     */
    public static final int DVB_DEVICE_DVR = 1;
    /**
     * A frontend device of DVB API for controlling the tuner and DVB demodulator hardware.
     * @hide
     */
    public static final int DVB_DEVICE_FRONTEND = DVB_DEVICE_END;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({DVB_DEVICE_DEMUX, DVB_DEVICE_DVR, DVB_DEVICE_FRONTEND})
    public @interface DvbDeviceType {}


    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({VIDEO_UNAVAILABLE_REASON_UNKNOWN, VIDEO_UNAVAILABLE_REASON_TUNING,
        VIDEO_UNAVAILABLE_REASON_WEAK_SIGNAL, VIDEO_UNAVAILABLE_REASON_BUFFERING,
        VIDEO_UNAVAILABLE_REASON_AUDIO_ONLY, VIDEO_UNAVAILABLE_REASON_INSUFFICIENT_RESOURCE,
        VIDEO_UNAVAILABLE_REASON_CAS_INSUFFICIENT_OUTPUT_PROTECTION,
        VIDEO_UNAVAILABLE_REASON_CAS_PVR_RECORDING_NOT_ALLOWED,
        VIDEO_UNAVAILABLE_REASON_CAS_PVR_RECORDING_NOT_ALLOWED,
        VIDEO_UNAVAILABLE_REASON_CAS_NO_LICENSE, VIDEO_UNAVAILABLE_REASON_CAS_LICENSE_EXPIRED,
        VIDEO_UNAVAILABLE_REASON_CAS_NEED_ACTIVATION, VIDEO_UNAVAILABLE_REASON_CAS_NEED_PAIRING,
        VIDEO_UNAVAILABLE_REASON_CAS_NO_CARD, VIDEO_UNAVAILABLE_REASON_CAS_CARD_MUTE,
        VIDEO_UNAVAILABLE_REASON_CAS_CARD_INVALID, VIDEO_UNAVAILABLE_REASON_CAS_BLACKOUT,
        VIDEO_UNAVAILABLE_REASON_CAS_REBOOTING, VIDEO_UNAVAILABLE_REASON_CAS_UNKNOWN})
    public @interface VideoUnavailableReason {}

    static final int VIDEO_UNAVAILABLE_REASON_START = 0;
    static final int VIDEO_UNAVAILABLE_REASON_END = 18;

    /**
     * Reason for {@link TvInputService.Session#notifyVideoUnavailable(int)} and
     * {@link TvView.TvInputCallback#onVideoUnavailable(String, int)}: Video is unavailable due to
     * an unspecified error.
     */
    public static final int VIDEO_UNAVAILABLE_REASON_UNKNOWN = VIDEO_UNAVAILABLE_REASON_START;
    /**
     * Reason for {@link TvInputService.Session#notifyVideoUnavailable(int)} and
     * {@link TvView.TvInputCallback#onVideoUnavailable(String, int)}: Video is unavailable because
     * the corresponding TV input is in the middle of tuning to a new channel.
     */
    public static final int VIDEO_UNAVAILABLE_REASON_TUNING = 1;
    /**
     * Reason for {@link TvInputService.Session#notifyVideoUnavailable(int)} and
     * {@link TvView.TvInputCallback#onVideoUnavailable(String, int)}: Video is unavailable due to
     * weak TV signal.
     */
    public static final int VIDEO_UNAVAILABLE_REASON_WEAK_SIGNAL = 2;
    /**
     * Reason for {@link TvInputService.Session#notifyVideoUnavailable(int)} and
     * {@link TvView.TvInputCallback#onVideoUnavailable(String, int)}: Video is unavailable because
     * the corresponding TV input has stopped playback temporarily to buffer more data.
     */
    public static final int VIDEO_UNAVAILABLE_REASON_BUFFERING = 3;
    /**
     * Reason for {@link TvInputService.Session#notifyVideoUnavailable(int)} and
     * {@link TvView.TvInputCallback#onVideoUnavailable(String, int)}: Video is unavailable because
     * the current TV program is audio-only.
     */
    public static final int VIDEO_UNAVAILABLE_REASON_AUDIO_ONLY = 4;
    /**
     * Reason for {@link TvInputService.Session#notifyVideoUnavailable(int)} and
     * {@link TvView.TvInputCallback#onVideoUnavailable(String, int)}: Video is unavailable because
     * the source is not physically connected, for example the HDMI cable is not connected.
     */
    public static final int VIDEO_UNAVAILABLE_REASON_NOT_CONNECTED = 5;
    /**
     * Reason for {@link TvInputService.Session#notifyVideoUnavailable(int)} and
     * {@link TvView.TvInputCallback#onVideoUnavailable(String, int)}: Video is unavailable because
     * the resource is not enough to meet requirement.
     */
    public static final int VIDEO_UNAVAILABLE_REASON_INSUFFICIENT_RESOURCE = 6;
    /**
     * Reason for {@link TvInputService.Session#notifyVideoUnavailable(int)} and
     * {@link TvView.TvInputCallback#onVideoUnavailable(String, int)}: Video is unavailable because
     * the output protection level enabled on the device is not sufficient to meet the requirements
     * in the license policy.
     */
    public static final int VIDEO_UNAVAILABLE_REASON_CAS_INSUFFICIENT_OUTPUT_PROTECTION = 7;
    /**
     * Reason for {@link TvInputService.Session#notifyVideoUnavailable(int)} and
     * {@link TvView.TvInputCallback#onVideoUnavailable(String, int)}: Video is unavailable because
     * the PVR record is not allowed by the license policy.
     */
    public static final int VIDEO_UNAVAILABLE_REASON_CAS_PVR_RECORDING_NOT_ALLOWED = 8;
    /**
     * Reason for {@link TvInputService.Session#notifyVideoUnavailable(int)} and
     * {@link TvView.TvInputCallback#onVideoUnavailable(String, int)}: Video is unavailable because
     * no license keys have been provided.
     * @hide
     */
    public static final int VIDEO_UNAVAILABLE_REASON_CAS_NO_LICENSE = 9;
    /**
     * Reason for {@link TvInputService.Session#notifyVideoUnavailable(int)} and
     * {@link TvView.TvInputCallback#onVideoUnavailable(String, int)}: Video is unavailable because
     * Using a license in whhich the keys have expired.
     */
    public static final int VIDEO_UNAVAILABLE_REASON_CAS_LICENSE_EXPIRED = 10;
    /**
     * Reason for {@link TvInputService.Session#notifyVideoUnavailable(int)} and
     * {@link TvView.TvInputCallback#onVideoUnavailable(String, int)}: Video is unavailable because
     * the device need be activated.
     */
    public static final int VIDEO_UNAVAILABLE_REASON_CAS_NEED_ACTIVATION = 11;
    /**
     * Reason for {@link TvInputService.Session#notifyVideoUnavailable(int)} and
     * {@link TvView.TvInputCallback#onVideoUnavailable(String, int)}: Video is unavailable because
     * the device need be paired.
     */
    public static final int VIDEO_UNAVAILABLE_REASON_CAS_NEED_PAIRING = 12;
    /**
     * Reason for {@link TvInputService.Session#notifyVideoUnavailable(int)} and
     * {@link TvView.TvInputCallback#onVideoUnavailable(String, int)}: Video is unavailable because
     * smart card is missed.
     */
    public static final int VIDEO_UNAVAILABLE_REASON_CAS_NO_CARD = 13;
    /**
     * Reason for {@link TvInputService.Session#notifyVideoUnavailable(int)} and
     * {@link TvView.TvInputCallback#onVideoUnavailable(String, int)}: Video is unavailable because
     * smart card is muted.
     */
    public static final int VIDEO_UNAVAILABLE_REASON_CAS_CARD_MUTE = 14;
    /**
     * Reason for {@link TvInputService.Session#notifyVideoUnavailable(int)} and
     * {@link TvView.TvInputCallback#onVideoUnavailable(String, int)}: Video is unavailable because
     * smart card is invalid.
     */
    public static final int VIDEO_UNAVAILABLE_REASON_CAS_CARD_INVALID = 15;
    /**
     * Reason for {@link TvInputService.Session#notifyVideoUnavailable(int)} and
     * {@link TvView.TvInputCallback#onVideoUnavailable(String, int)}: Video is unavailable because
     * of a geographical blackout.
     */
    public static final int VIDEO_UNAVAILABLE_REASON_CAS_BLACKOUT = 16;
    /**
     * Reason for {@link TvInputService.Session#notifyVideoUnavailable(int)} and
     * {@link TvView.TvInputCallback#onVideoUnavailable(String, int)}: Video is unavailable because
     * CAS system is rebooting.
     */
    public static final int VIDEO_UNAVAILABLE_REASON_CAS_REBOOTING = 17;
    /**
     * Reason for {@link TvInputService.Session#notifyVideoUnavailable(int)} and
     * {@link TvView.TvInputCallback#onVideoUnavailable(String, int)}: Video is unavailable because
     * of unknown CAS error.
     */
    public static final int VIDEO_UNAVAILABLE_REASON_CAS_UNKNOWN = VIDEO_UNAVAILABLE_REASON_END;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({TIME_SHIFT_STATUS_UNKNOWN, TIME_SHIFT_STATUS_UNSUPPORTED,
            TIME_SHIFT_STATUS_UNAVAILABLE, TIME_SHIFT_STATUS_AVAILABLE})
    public @interface TimeShiftStatus {}

    /**
     * Status for {@link TvInputService.Session#notifyTimeShiftStatusChanged(int)} and
     * {@link TvView.TvInputCallback#onTimeShiftStatusChanged(String, int)}: Unknown status. Also
     * the status prior to calling {@code notifyTimeShiftStatusChanged}.
     */
    public static final int TIME_SHIFT_STATUS_UNKNOWN = 0;

    /**
     * Status for {@link TvInputService.Session#notifyTimeShiftStatusChanged(int)} and
     * {@link TvView.TvInputCallback#onTimeShiftStatusChanged(String, int)}: The current TV input
     * does not support time shifting.
     */
    public static final int TIME_SHIFT_STATUS_UNSUPPORTED = 1;

    /**
     * Status for {@link TvInputService.Session#notifyTimeShiftStatusChanged(int)} and
     * {@link TvView.TvInputCallback#onTimeShiftStatusChanged(String, int)}: Time shifting is
     * currently unavailable but might work again later.
     */
    public static final int TIME_SHIFT_STATUS_UNAVAILABLE = 2;

    /**
     * Status for {@link TvInputService.Session#notifyTimeShiftStatusChanged(int)} and
     * {@link TvView.TvInputCallback#onTimeShiftStatusChanged(String, int)}: Time shifting is
     * currently available. In this status, the application assumes it can pause/resume playback,
     * seek to a specified time position and set playback rate and audio mode.
     */
    public static final int TIME_SHIFT_STATUS_AVAILABLE = 3;

    /**
     * Value returned by {@link TvInputService.Session#onTimeShiftGetCurrentPosition()} and
     * {@link TvInputService.Session#onTimeShiftGetStartPosition()} when time shifting has not
     * yet started.
     */
    public static final long TIME_SHIFT_INVALID_TIME = Long.MIN_VALUE;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({RECORDING_ERROR_UNKNOWN, RECORDING_ERROR_INSUFFICIENT_SPACE,
            RECORDING_ERROR_RESOURCE_BUSY})
    public @interface RecordingError {}

    static final int RECORDING_ERROR_START = 0;
    static final int RECORDING_ERROR_END = 2;

    /**
     * Error for {@link TvInputService.RecordingSession#notifyError(int)} and
     * {@link TvRecordingClient.RecordingCallback#onError(int)}: The requested operation cannot be
     * completed due to a problem that does not fit under any other error codes, or the error code
     * for the problem is defined on the higher version than application's
     * <code>android:targetSdkVersion</code>.
     */
    public static final int RECORDING_ERROR_UNKNOWN = RECORDING_ERROR_START;

    /**
     * Error for {@link TvInputService.RecordingSession#notifyError(int)} and
     * {@link TvRecordingClient.RecordingCallback#onError(int)}: Recording cannot proceed due to
     * insufficient storage space.
     */
    public static final int RECORDING_ERROR_INSUFFICIENT_SPACE = 1;

    /**
     * Error for {@link TvInputService.RecordingSession#notifyError(int)} and
     * {@link TvRecordingClient.RecordingCallback#onError(int)}: Recording cannot proceed because
     * a required recording resource was not able to be allocated.
     */
    public static final int RECORDING_ERROR_RESOURCE_BUSY = RECORDING_ERROR_END;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({INPUT_STATE_CONNECTED, INPUT_STATE_CONNECTED_STANDBY, INPUT_STATE_DISCONNECTED})
    public @interface InputState {}

    /**
     * State for {@link #getInputState(String)} and
     * {@link TvInputCallback#onInputStateChanged(String, int)}: The input source is connected.
     *
     * <p>This state indicates that a source device is connected to the input port and is in the
     * normal operation mode. It is mostly relevant to hardware inputs such as HDMI input.
     * Non-hardware inputs are considered connected all the time.
     */
    public static final int INPUT_STATE_CONNECTED = 0;

    /**
     * State for {@link #getInputState(String)} and
     * {@link TvInputCallback#onInputStateChanged(String, int)}: The input source is connected but
     * in standby mode.
     *
     * <p>This state indicates that a source device is connected to the input port but is in standby
     * or low power mode. It is mostly relevant to hardware inputs such as HDMI input and Component
     * inputs.
     */
    public static final int INPUT_STATE_CONNECTED_STANDBY = 1;

    /**
     * State for {@link #getInputState(String)} and
     * {@link TvInputCallback#onInputStateChanged(String, int)}: The input source is disconnected.
     *
     * <p>This state indicates that a source device is disconnected from the input port. It is
     * mostly relevant to hardware inputs such as HDMI input.
     *
     */
    public static final int INPUT_STATE_DISCONNECTED = 2;

    /**
     * An unknown state of the client pid gets from the TvInputManager. Client gets this value when
     * query through {@link getClientPid(String sessionId)} fails.
     *
     * @hide
     */
    public static final int UNKNOWN_CLIENT_PID = -1;

    /**
     * Broadcast intent action when the user blocked content ratings change. For use with the
     * {@link #isRatingBlocked}.
     */
    public static final String ACTION_BLOCKED_RATINGS_CHANGED =
            "android.media.tv.action.BLOCKED_RATINGS_CHANGED";

    /**
     * Broadcast intent action when the parental controls enabled state changes. For use with the
     * {@link #isParentalControlsEnabled}.
     */
    public static final String ACTION_PARENTAL_CONTROLS_ENABLED_CHANGED =
            "android.media.tv.action.PARENTAL_CONTROLS_ENABLED_CHANGED";

    /**
     * Broadcast intent action used to query available content rating systems.
     *
     * <p>The TV input manager service locates available content rating systems by querying
     * broadcast receivers that are registered for this action. An application can offer additional
     * content rating systems to the user by declaring a suitable broadcast receiver in its
     * manifest.
     *
     * <p>Here is an example broadcast receiver declaration that an application might include in its
     * AndroidManifest.xml to advertise custom content rating systems. The meta-data specifies a
     * resource that contains a description of each content rating system that is provided by the
     * application.
     *
     * <p><pre class="prettyprint">
     * {@literal
     * <receiver android:name=".TvInputReceiver">
     *     <intent-filter>
     *         <action android:name=
     *                 "android.media.tv.action.QUERY_CONTENT_RATING_SYSTEMS" />
     *     </intent-filter>
     *     <meta-data
     *             android:name="android.media.tv.metadata.CONTENT_RATING_SYSTEMS"
     *             android:resource="@xml/tv_content_rating_systems" />
     * </receiver>}</pre>
     *
     * <p>In the above example, the <code>@xml/tv_content_rating_systems</code> resource refers to an
     * XML resource whose root element is <code>&lt;rating-system-definitions&gt;</code> that
     * contains zero or more <code>&lt;rating-system-definition&gt;</code> elements. Each <code>
     * &lt;rating-system-definition&gt;</code> element specifies the ratings, sub-ratings and rating
     * orders of a particular content rating system.
     *
     * @see TvContentRating
     */
    public static final String ACTION_QUERY_CONTENT_RATING_SYSTEMS =
            "android.media.tv.action.QUERY_CONTENT_RATING_SYSTEMS";

    /**
     * Content rating systems metadata associated with {@link #ACTION_QUERY_CONTENT_RATING_SYSTEMS}.
     *
     * <p>Specifies the resource ID of an XML resource that describes the content rating systems
     * that are provided by the application.
     */
    public static final String META_DATA_CONTENT_RATING_SYSTEMS =
            "android.media.tv.metadata.CONTENT_RATING_SYSTEMS";

    /**
     * Activity action to set up channel sources i.e.&nbsp;TV inputs of type
     * {@link TvInputInfo#TYPE_TUNER}. When invoked, the system will display an appropriate UI for
     * the user to initiate the individual setup flow provided by
     * {@link android.R.attr#setupActivity} of each TV input service.
     */
    public static final String ACTION_SETUP_INPUTS = "android.media.tv.action.SETUP_INPUTS";

    /**
     * Activity action to display the recording schedules. When invoked, the system will display an
     * appropriate UI to browse the schedules.
     */
    public static final String ACTION_VIEW_RECORDING_SCHEDULES =
            "android.media.tv.action.VIEW_RECORDING_SCHEDULES";

    private final ITvInputManager mService;

    private final Object mLock = new Object();

    // @GuardedBy("mLock")
    private final List<TvInputCallbackRecord> mCallbackRecords = new LinkedList<>();

    // A mapping from TV input ID to the state of corresponding input.
    // @GuardedBy("mLock")
    private final Map<String, Integer> mStateMap = new ArrayMap<>();

    // A mapping from the sequence number of a session to its SessionCallbackRecord.
    private final SparseArray<SessionCallbackRecord> mSessionCallbackRecordMap =
            new SparseArray<>();

    // A sequence number for the next session to be created. Should be protected by a lock
    // {@code mSessionCallbackRecordMap}.
    private int mNextSeq;

    private final ITvInputClient mClient;

    private final int mUserId;

    /**
     * Interface used to receive the created session.
     * @hide
     */
    public abstract static class SessionCallback {
        /**
         * This is called after {@link TvInputManager#createSession} has been processed.
         *
         * @param session A {@link TvInputManager.Session} instance created. This can be
         *            {@code null} if the creation request failed.
         */
        public void onSessionCreated(@Nullable Session session) {
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
         * This is called when the channel of this session is changed by the underlying TV input
         * without any {@link TvInputManager.Session#tune(Uri)} request.
         *
         * @param session A {@link TvInputManager.Session} associated with this callback.
         * @param channelUri The URI of a channel.
         */
        public void onChannelRetuned(Session session, Uri channelUri) {
        }

        /**
         * This is called when the track information of the session has been changed.
         *
         * @param session A {@link TvInputManager.Session} associated with this callback.
         * @param tracks A list which includes track information.
         */
        public void onTracksChanged(Session session, List<TvTrackInfo> tracks) {
        }

        /**
         * This is called when a track for a given type is selected.
         *
         * @param session A {@link TvInputManager.Session} associated with this callback.
         * @param type The type of the selected track. The type can be
         *            {@link TvTrackInfo#TYPE_AUDIO}, {@link TvTrackInfo#TYPE_VIDEO} or
         *            {@link TvTrackInfo#TYPE_SUBTITLE}.
         * @param trackId The ID of the selected track. When {@code null} the currently selected
         *            track for a given type should be unselected.
         */
        public void onTrackSelected(Session session, int type, @Nullable String trackId) {
        }

        /**
         * This is invoked when the video size has been changed. It is also called when the first
         * time video size information becomes available after the session is tuned to a specific
         * channel.
         *
         * @param session A {@link TvInputManager.Session} associated with this callback.
         * @param width The width of the video.
         * @param height The height of the video.
         */
        public void onVideoSizeChanged(Session session, int width, int height) {
        }

        /**
         * This is called when the video is available, so the TV input starts the playback.
         *
         * @param session A {@link TvInputManager.Session} associated with this callback.
         */
        public void onVideoAvailable(Session session) {
        }

        /**
         * This is called when the video is not available, so the TV input stops the playback.
         *
         * @param session A {@link TvInputManager.Session} associated with this callback.
         * @param reason The reason why the TV input stopped the playback:
         * <ul>
         * <li>{@link TvInputManager#VIDEO_UNAVAILABLE_REASON_UNKNOWN}
         * <li>{@link TvInputManager#VIDEO_UNAVAILABLE_REASON_TUNING}
         * <li>{@link TvInputManager#VIDEO_UNAVAILABLE_REASON_WEAK_SIGNAL}
         * <li>{@link TvInputManager#VIDEO_UNAVAILABLE_REASON_BUFFERING}
         * <li>{@link TvInputManager#VIDEO_UNAVAILABLE_REASON_AUDIO_ONLY}
         * </ul>
         */
        public void onVideoUnavailable(Session session, int reason) {
        }

        /**
         * This is called when the current program content turns out to be allowed to watch since
         * its content rating is not blocked by parental controls.
         *
         * @param session A {@link TvInputManager.Session} associated with this callback.
         */
        public void onContentAllowed(Session session) {
        }

        /**
         * This is called when the current program content turns out to be not allowed to watch
         * since its content rating is blocked by parental controls.
         *
         * @param session A {@link TvInputManager.Session} associated with this callback.
         * @param rating The content ration of the blocked program.
         */
        public void onContentBlocked(Session session, TvContentRating rating) {
        }

        /**
         * This is called when {@link TvInputService.Session#layoutSurface} is called to change the
         * layout of surface.
         *
         * @param session A {@link TvInputManager.Session} associated with this callback.
         * @param left Left position.
         * @param top Top position.
         * @param right Right position.
         * @param bottom Bottom position.
         */
        public void onLayoutSurface(Session session, int left, int top, int right, int bottom) {
        }

        /**
         * This is called when a custom event has been sent from this session.
         *
         * @param session A {@link TvInputManager.Session} associated with this callback
         * @param eventType The type of the event.
         * @param eventArgs Optional arguments of the event.
         */
        public void onSessionEvent(Session session, String eventType, Bundle eventArgs) {
        }

        /**
         * This is called when the time shift status is changed.
         *
         * @param session A {@link TvInputManager.Session} associated with this callback.
         * @param status The current time shift status. Should be one of the followings.
         * <ul>
         * <li>{@link TvInputManager#TIME_SHIFT_STATUS_UNSUPPORTED}
         * <li>{@link TvInputManager#TIME_SHIFT_STATUS_UNAVAILABLE}
         * <li>{@link TvInputManager#TIME_SHIFT_STATUS_AVAILABLE}
         * </ul>
         */
        public void onTimeShiftStatusChanged(Session session, int status) {
        }

        /**
         * This is called when the start position for time shifting has changed.
         *
         * @param session A {@link TvInputManager.Session} associated with this callback.
         * @param timeMs The start position for time shifting, in milliseconds since the epoch.
         */
        public void onTimeShiftStartPositionChanged(Session session, long timeMs) {
        }

        /**
         * This is called when the current position for time shifting is changed.
         *
         * @param session A {@link TvInputManager.Session} associated with this callback.
         * @param timeMs The current position for time shifting, in milliseconds since the epoch.
         */
        public void onTimeShiftCurrentPositionChanged(Session session, long timeMs) {
        }

        // For the recording session only
        /**
         * This is called when the recording session has been tuned to the given channel and is
         * ready to start recording.
         *
         * @param channelUri The URI of a channel.
         */
        void onTuned(Session session, Uri channelUri) {
        }

        // For the recording session only
        /**
         * This is called when the current recording session has stopped recording and created a
         * new data entry in the {@link TvContract.RecordedPrograms} table that describes the newly
         * recorded program.
         *
         * @param recordedProgramUri The URI for the newly recorded program.
         **/
        void onRecordingStopped(Session session, Uri recordedProgramUri) {
        }

        // For the recording session only
        /**
         * This is called when an issue has occurred. It may be called at any time after the current
         * recording session is created until it is released.
         *
         * @param error The error code.
         */
        void onError(Session session, @TvInputManager.RecordingError int error) {
        }
    }

    private static final class SessionCallbackRecord {
        private final SessionCallback mSessionCallback;
        private final Handler mHandler;
        private Session mSession;

        SessionCallbackRecord(SessionCallback sessionCallback,
                Handler handler) {
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

        void postChannelRetuned(final Uri channelUri) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mSessionCallback.onChannelRetuned(mSession, channelUri);
                }
            });
        }

        void postTracksChanged(final List<TvTrackInfo> tracks) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mSessionCallback.onTracksChanged(mSession, tracks);
                }
            });
        }

        void postTrackSelected(final int type, final String trackId) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mSessionCallback.onTrackSelected(mSession, type, trackId);
                }
            });
        }

        void postVideoSizeChanged(final int width, final int height) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mSessionCallback.onVideoSizeChanged(mSession, width, height);
                }
            });
        }

        void postVideoAvailable() {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mSessionCallback.onVideoAvailable(mSession);
                }
            });
        }

        void postVideoUnavailable(final int reason) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mSessionCallback.onVideoUnavailable(mSession, reason);
                }
            });
        }

        void postContentAllowed() {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mSessionCallback.onContentAllowed(mSession);
                }
            });
        }

        void postContentBlocked(final TvContentRating rating) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mSessionCallback.onContentBlocked(mSession, rating);
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

        void postSessionEvent(final String eventType, final Bundle eventArgs) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mSessionCallback.onSessionEvent(mSession, eventType, eventArgs);
                }
            });
        }

        void postTimeShiftStatusChanged(final int status) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mSessionCallback.onTimeShiftStatusChanged(mSession, status);
                }
            });
        }

        void postTimeShiftStartPositionChanged(final long timeMs) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mSessionCallback.onTimeShiftStartPositionChanged(mSession, timeMs);
                }
            });
        }

        void postTimeShiftCurrentPositionChanged(final long timeMs) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mSessionCallback.onTimeShiftCurrentPositionChanged(mSession, timeMs);
                }
            });
        }

        // For the recording session only
        void postTuned(final Uri channelUri) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mSessionCallback.onTuned(mSession, channelUri);
                }
            });
        }

        // For the recording session only
        void postRecordingStopped(final Uri recordedProgramUri) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mSessionCallback.onRecordingStopped(mSession, recordedProgramUri);
                }
            });
        }

        // For the recording session only
        void postError(final int error) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mSessionCallback.onError(mSession, error);
                }
            });
        }
    }

    /**
     * Callback used to monitor status of the TV inputs.
     */
    public abstract static class TvInputCallback {
        /**
         * This is called when the state of a given TV input is changed.
         *
         * @param inputId The ID of the TV input.
         * @param state State of the TV input. The value is one of the following:
         * <ul>
         * <li>{@link TvInputManager#INPUT_STATE_CONNECTED}
         * <li>{@link TvInputManager#INPUT_STATE_CONNECTED_STANDBY}
         * <li>{@link TvInputManager#INPUT_STATE_DISCONNECTED}
         * </ul>
         */
        public void onInputStateChanged(String inputId, @InputState int state) {
        }

        /**
         * This is called when a TV input is added to the system.
         *
         * <p>Normally it happens when the user installs a new TV input package that implements
         * {@link TvInputService} interface.
         *
         * @param inputId The ID of the TV input.
         */
        public void onInputAdded(String inputId) {
        }

        /**
         * This is called when a TV input is removed from the system.
         *
         * <p>Normally it happens when the user uninstalls the previously installed TV input
         * package.
         *
         * @param inputId The ID of the TV input.
         */
        public void onInputRemoved(String inputId) {
        }

        /**
         * This is called when a TV input is updated on the system.
         *
         * <p>Normally it happens when a previously installed TV input package is re-installed or
         * the media on which a newer version of the package exists becomes available/unavailable.
         *
         * @param inputId The ID of the TV input.
         */
        public void onInputUpdated(String inputId) {
        }

        /**
         * This is called when the information about an existing TV input has been updated.
         *
         * <p>Because the system automatically creates a <code>TvInputInfo</code> object for each TV
         * input based on the information collected from the <code>AndroidManifest.xml</code>, this
         * method is only called back when such information has changed dynamically.
         *
         * @param inputInfo The <code>TvInputInfo</code> object that contains new information.
         */
        public void onTvInputInfoUpdated(TvInputInfo inputInfo) {
        }

        /**
         * This is called when the information about current tuned information has been updated.
         *
         * @param tunedInfos a list of {@link TunedInfo} objects of new tuned information.
         * @hide
         */
        @SystemApi
        @RequiresPermission(android.Manifest.permission.ACCESS_TUNED_INFO)
        public void onCurrentTunedInfosUpdated(@NonNull List<TunedInfo> tunedInfos) {
        }
    }

    private static final class TvInputCallbackRecord {
        private final TvInputCallback mCallback;
        private final Handler mHandler;

        public TvInputCallbackRecord(TvInputCallback callback, Handler handler) {
            mCallback = callback;
            mHandler = handler;
        }

        public TvInputCallback getCallback() {
            return mCallback;
        }

        public void postInputAdded(final String inputId) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mCallback.onInputAdded(inputId);
                }
            });
        }

        public void postInputRemoved(final String inputId) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mCallback.onInputRemoved(inputId);
                }
            });
        }

        public void postInputUpdated(final String inputId) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mCallback.onInputUpdated(inputId);
                }
            });
        }

        public void postInputStateChanged(final String inputId, final int state) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mCallback.onInputStateChanged(inputId, state);
                }
            });
        }

        public void postTvInputInfoUpdated(final TvInputInfo inputInfo) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mCallback.onTvInputInfoUpdated(inputInfo);
                }
            });
        }

        public void postCurrentTunedInfosUpdated(final List<TunedInfo> currentTunedInfos) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mCallback.onCurrentTunedInfosUpdated(currentTunedInfos);
                }
            });
        }
    }

    /**
     * Interface used to receive events from Hardware objects.
     *
     * @hide
     */
    @SystemApi
    public abstract static class HardwareCallback {
        /**
         * This is called when {@link Hardware} is no longer available for the client.
         */
        public abstract void onReleased();

        /**
         * This is called when the underlying {@link TvStreamConfig} has been changed.
         *
         * @param configs The new {@link TvStreamConfig}s.
         */
        public abstract void onStreamConfigChanged(TvStreamConfig[] configs);
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
            public void onChannelRetuned(Uri channelUri, int seq) {
                synchronized (mSessionCallbackRecordMap) {
                    SessionCallbackRecord record = mSessionCallbackRecordMap.get(seq);
                    if (record == null) {
                        Log.e(TAG, "Callback not found for seq " + seq);
                        return;
                    }
                    record.postChannelRetuned(channelUri);
                }
            }

            @Override
            public void onTracksChanged(List<TvTrackInfo> tracks, int seq) {
                synchronized (mSessionCallbackRecordMap) {
                    SessionCallbackRecord record = mSessionCallbackRecordMap.get(seq);
                    if (record == null) {
                        Log.e(TAG, "Callback not found for seq " + seq);
                        return;
                    }
                    if (record.mSession.updateTracks(tracks)) {
                        record.postTracksChanged(tracks);
                        postVideoSizeChangedIfNeededLocked(record);
                    }
                }
            }

            @Override
            public void onTrackSelected(int type, String trackId, int seq) {
                synchronized (mSessionCallbackRecordMap) {
                    SessionCallbackRecord record = mSessionCallbackRecordMap.get(seq);
                    if (record == null) {
                        Log.e(TAG, "Callback not found for seq " + seq);
                        return;
                    }
                    if (record.mSession.updateTrackSelection(type, trackId)) {
                        record.postTrackSelected(type, trackId);
                        postVideoSizeChangedIfNeededLocked(record);
                    }
                }
            }

            private void postVideoSizeChangedIfNeededLocked(SessionCallbackRecord record) {
                TvTrackInfo track = record.mSession.getVideoTrackToNotify();
                if (track != null) {
                    record.postVideoSizeChanged(track.getVideoWidth(), track.getVideoHeight());
                }
            }

            @Override
            public void onVideoAvailable(int seq) {
                synchronized (mSessionCallbackRecordMap) {
                    SessionCallbackRecord record = mSessionCallbackRecordMap.get(seq);
                    if (record == null) {
                        Log.e(TAG, "Callback not found for seq " + seq);
                        return;
                    }
                    record.postVideoAvailable();
                }
            }

            @Override
            public void onVideoUnavailable(int reason, int seq) {
                synchronized (mSessionCallbackRecordMap) {
                    SessionCallbackRecord record = mSessionCallbackRecordMap.get(seq);
                    if (record == null) {
                        Log.e(TAG, "Callback not found for seq " + seq);
                        return;
                    }
                    record.postVideoUnavailable(reason);
                }
            }

            @Override
            public void onContentAllowed(int seq) {
                synchronized (mSessionCallbackRecordMap) {
                    SessionCallbackRecord record = mSessionCallbackRecordMap.get(seq);
                    if (record == null) {
                        Log.e(TAG, "Callback not found for seq " + seq);
                        return;
                    }
                    record.postContentAllowed();
                }
            }

            @Override
            public void onContentBlocked(String rating, int seq) {
                synchronized (mSessionCallbackRecordMap) {
                    SessionCallbackRecord record = mSessionCallbackRecordMap.get(seq);
                    if (record == null) {
                        Log.e(TAG, "Callback not found for seq " + seq);
                        return;
                    }
                    record.postContentBlocked(TvContentRating.unflattenFromString(rating));
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
            public void onTimeShiftStatusChanged(int status, int seq) {
                synchronized (mSessionCallbackRecordMap) {
                    SessionCallbackRecord record = mSessionCallbackRecordMap.get(seq);
                    if (record == null) {
                        Log.e(TAG, "Callback not found for seq " + seq);
                        return;
                    }
                    record.postTimeShiftStatusChanged(status);
                }
            }

            @Override
            public void onTimeShiftStartPositionChanged(long timeMs, int seq) {
                synchronized (mSessionCallbackRecordMap) {
                    SessionCallbackRecord record = mSessionCallbackRecordMap.get(seq);
                    if (record == null) {
                        Log.e(TAG, "Callback not found for seq " + seq);
                        return;
                    }
                    record.postTimeShiftStartPositionChanged(timeMs);
                }
            }

            @Override
            public void onTimeShiftCurrentPositionChanged(long timeMs, int seq) {
                synchronized (mSessionCallbackRecordMap) {
                    SessionCallbackRecord record = mSessionCallbackRecordMap.get(seq);
                    if (record == null) {
                        Log.e(TAG, "Callback not found for seq " + seq);
                        return;
                    }
                    record.postTimeShiftCurrentPositionChanged(timeMs);
                }
            }

            @Override
            public void onTuned(int seq, Uri channelUri) {
                synchronized (mSessionCallbackRecordMap) {
                    SessionCallbackRecord record = mSessionCallbackRecordMap.get(seq);
                    if (record == null) {
                        Log.e(TAG, "Callback not found for seq " + seq);
                        return;
                    }
                    record.postTuned(channelUri);
                }
            }

            @Override
            public void onRecordingStopped(Uri recordedProgramUri, int seq) {
                synchronized (mSessionCallbackRecordMap) {
                    SessionCallbackRecord record = mSessionCallbackRecordMap.get(seq);
                    if (record == null) {
                        Log.e(TAG, "Callback not found for seq " + seq);
                        return;
                    }
                    record.postRecordingStopped(recordedProgramUri);
                }
            }

            @Override
            public void onError(int error, int seq) {
                synchronized (mSessionCallbackRecordMap) {
                    SessionCallbackRecord record = mSessionCallbackRecordMap.get(seq);
                    if (record == null) {
                        Log.e(TAG, "Callback not found for seq " + seq);
                        return;
                    }
                    record.postError(error);
                }
            }
        };
        ITvInputManagerCallback managerCallback = new ITvInputManagerCallback.Stub() {
            @Override
            public void onInputAdded(String inputId) {
                synchronized (mLock) {
                    mStateMap.put(inputId, INPUT_STATE_CONNECTED);
                    for (TvInputCallbackRecord record : mCallbackRecords) {
                        record.postInputAdded(inputId);
                    }
                }
            }

            @Override
            public void onInputRemoved(String inputId) {
                synchronized (mLock) {
                    mStateMap.remove(inputId);
                    for (TvInputCallbackRecord record : mCallbackRecords) {
                        record.postInputRemoved(inputId);
                    }
                }
            }

            @Override
            public void onInputUpdated(String inputId) {
                synchronized (mLock) {
                    for (TvInputCallbackRecord record : mCallbackRecords) {
                        record.postInputUpdated(inputId);
                    }
                }
            }

            @Override
            public void onInputStateChanged(String inputId, int state) {
                synchronized (mLock) {
                    mStateMap.put(inputId, state);
                    for (TvInputCallbackRecord record : mCallbackRecords) {
                        record.postInputStateChanged(inputId, state);
                    }
                }
            }

            @Override
            public void onTvInputInfoUpdated(TvInputInfo inputInfo) {
                synchronized (mLock) {
                    for (TvInputCallbackRecord record : mCallbackRecords) {
                        record.postTvInputInfoUpdated(inputInfo);
                    }
                }
            }

            @Override
            public void onCurrentTunedInfosUpdated(List<TunedInfo> currentTunedInfos) {
                synchronized (mLock) {
                    for (TvInputCallbackRecord record : mCallbackRecords) {
                        record.postCurrentTunedInfosUpdated(currentTunedInfos);
                    }
                }
            }
        };
        try {
            if (mService != null) {
                mService.registerCallback(managerCallback, mUserId);
                List<TvInputInfo> infos = mService.getTvInputList(mUserId);
                synchronized (mLock) {
                    for (TvInputInfo info : infos) {
                        String inputId = info.getId();
                        mStateMap.put(inputId, mService.getTvInputState(inputId, mUserId));
                    }
                }
            }
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
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
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the {@link TvInputInfo} for a given TV input.
     *
     * @param inputId The ID of the TV input.
     * @return the {@link TvInputInfo} for a given TV input. {@code null} if not found.
     */
    @Nullable
    public TvInputInfo getTvInputInfo(@NonNull String inputId) {
        Preconditions.checkNotNull(inputId);
        try {
            return mService.getTvInputInfo(inputId, mUserId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Updates the <code>TvInputInfo</code> for an existing TV input. A TV input service
     * implementation may call this method to pass the application and system an up-to-date
     * <code>TvInputInfo</code> object that describes itself.
     *
     * <p>The system automatically creates a <code>TvInputInfo</code> object for each TV input,
     * based on the information collected from the <code>AndroidManifest.xml</code>, thus it is not
     * necessary to call this method unless such information has changed dynamically.
     * Use {@link TvInputInfo.Builder} to build a new <code>TvInputInfo</code> object.
     *
     * <p>Attempting to change information about a TV input that the calling package does not own
     * does nothing.
     *
     * @param inputInfo The <code>TvInputInfo</code> object that contains new information.
     * @throws IllegalArgumentException if the argument is {@code null}.
     * @see TvInputCallback#onTvInputInfoUpdated(TvInputInfo)
     */
    public void updateTvInputInfo(@NonNull TvInputInfo inputInfo) {
        Preconditions.checkNotNull(inputInfo);
        try {
            mService.updateTvInputInfo(inputInfo, mUserId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the state of a given TV input.
     *
     * <p>The state is one of the following:
     * <ul>
     * <li>{@link #INPUT_STATE_CONNECTED}
     * <li>{@link #INPUT_STATE_CONNECTED_STANDBY}
     * <li>{@link #INPUT_STATE_DISCONNECTED}
     * </ul>
     *
     * @param inputId The ID of the TV input.
     * @throws IllegalArgumentException if the argument is {@code null}.
     */
    @InputState
    public int getInputState(@NonNull String inputId) {
        Preconditions.checkNotNull(inputId);
        synchronized (mLock) {
            Integer state = mStateMap.get(inputId);
            if (state == null) {
                Log.w(TAG, "Unrecognized input ID: " + inputId);
                return INPUT_STATE_DISCONNECTED;
            }
            return state;
        }
    }

    /**
     * Registers a {@link TvInputCallback}.
     *
     * @param callback A callback used to monitor status of the TV inputs.
     * @param handler A {@link Handler} that the status change will be delivered to.
     */
    public void registerCallback(@NonNull TvInputCallback callback, @NonNull Handler handler) {
        Preconditions.checkNotNull(callback);
        Preconditions.checkNotNull(handler);
        synchronized (mLock) {
            mCallbackRecords.add(new TvInputCallbackRecord(callback, handler));
        }
    }

    /**
     * Unregisters the existing {@link TvInputCallback}.
     *
     * @param callback The existing callback to remove.
     */
    public void unregisterCallback(@NonNull final TvInputCallback callback) {
        Preconditions.checkNotNull(callback);
        synchronized (mLock) {
            for (Iterator<TvInputCallbackRecord> it = mCallbackRecords.iterator();
                    it.hasNext(); ) {
                TvInputCallbackRecord record = it.next();
                if (record.getCallback() == callback) {
                    it.remove();
                    break;
                }
            }
        }
    }

    /**
     * Returns the user's parental controls enabled state.
     *
     * @return {@code true} if the user enabled the parental controls, {@code false} otherwise.
     */
    public boolean isParentalControlsEnabled() {
        try {
            return mService.isParentalControlsEnabled(mUserId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Sets the user's parental controls enabled state.
     *
     * @param enabled The user's parental controls enabled state. {@code true} if the user enabled
     *            the parental controls, {@code false} otherwise.
     * @see #isParentalControlsEnabled
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MODIFY_PARENTAL_CONTROLS)
    public void setParentalControlsEnabled(boolean enabled) {
        try {
            mService.setParentalControlsEnabled(enabled, mUserId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Checks whether a given TV content rating is blocked by the user.
     *
     * @param rating The TV content rating to check. Can be {@link TvContentRating#UNRATED}.
     * @return {@code true} if the given TV content rating is blocked, {@code false} otherwise.
     */
    public boolean isRatingBlocked(@NonNull TvContentRating rating) {
        Preconditions.checkNotNull(rating);
        try {
            return mService.isRatingBlocked(rating.flattenToString(), mUserId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the list of blocked content ratings.
     *
     * @return the list of content ratings blocked by the user.
     */
    public List<TvContentRating> getBlockedRatings() {
        try {
            List<TvContentRating> ratings = new ArrayList<>();
            for (String rating : mService.getBlockedRatings(mUserId)) {
                ratings.add(TvContentRating.unflattenFromString(rating));
            }
            return ratings;
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Adds a user blocked content rating.
     *
     * @param rating The content rating to block.
     * @see #isRatingBlocked
     * @see #removeBlockedRating
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MODIFY_PARENTAL_CONTROLS)
    public void addBlockedRating(@NonNull TvContentRating rating) {
        Preconditions.checkNotNull(rating);
        try {
            mService.addBlockedRating(rating.flattenToString(), mUserId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Removes a user blocked content rating.
     *
     * @param rating The content rating to unblock.
     * @see #isRatingBlocked
     * @see #addBlockedRating
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MODIFY_PARENTAL_CONTROLS)
    public void removeBlockedRating(@NonNull TvContentRating rating) {
        Preconditions.checkNotNull(rating);
        try {
            mService.removeBlockedRating(rating.flattenToString(), mUserId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the list of all TV content rating systems defined.
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.READ_CONTENT_RATING_SYSTEMS)
    public List<TvContentRatingSystemInfo> getTvContentRatingSystemList() {
        try {
            return mService.getTvContentRatingSystemList(mUserId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Notifies the TV input of the given preview program that the program's browsable state is
     * disabled.
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.NOTIFY_TV_INPUTS)
    public void notifyPreviewProgramBrowsableDisabled(String packageName, long programId) {
        Intent intent = new Intent();
        intent.setAction(TvContract.ACTION_PREVIEW_PROGRAM_BROWSABLE_DISABLED);
        intent.putExtra(TvContract.EXTRA_PREVIEW_PROGRAM_ID, programId);
        intent.setPackage(packageName);
        try {
            mService.sendTvInputNotifyIntent(intent, mUserId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Notifies the TV input of the given watch next program that the program's browsable state is
     * disabled.
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.NOTIFY_TV_INPUTS)
    public void notifyWatchNextProgramBrowsableDisabled(String packageName, long programId) {
        Intent intent = new Intent();
        intent.setAction(TvContract.ACTION_WATCH_NEXT_PROGRAM_BROWSABLE_DISABLED);
        intent.putExtra(TvContract.EXTRA_WATCH_NEXT_PROGRAM_ID, programId);
        intent.setPackage(packageName);
        try {
            mService.sendTvInputNotifyIntent(intent, mUserId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Notifies the TV input of the given preview program that the program is added to watch next.
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.NOTIFY_TV_INPUTS)
    public void notifyPreviewProgramAddedToWatchNext(String packageName, long previewProgramId,
            long watchNextProgramId) {
        Intent intent = new Intent();
        intent.setAction(TvContract.ACTION_PREVIEW_PROGRAM_ADDED_TO_WATCH_NEXT);
        intent.putExtra(TvContract.EXTRA_PREVIEW_PROGRAM_ID, previewProgramId);
        intent.putExtra(TvContract.EXTRA_WATCH_NEXT_PROGRAM_ID, watchNextProgramId);
        intent.setPackage(packageName);
        try {
            mService.sendTvInputNotifyIntent(intent, mUserId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Creates a {@link Session} for a given TV input.
     *
     * <p>The number of sessions that can be created at the same time is limited by the capability
     * of the given TV input.
     *
     * @param inputId The ID of the TV input.
     * @param callback A callback used to receive the created session.
     * @param handler A {@link Handler} that the session creation will be delivered to.
     * @hide
     */
    public void createSession(@NonNull String inputId, @NonNull final SessionCallback callback,
            @NonNull Handler handler) {
        createSessionInternal(inputId, false, callback, handler);
    }

    /**
     * Get a the client pid when creating the session with the session id provided.
     *
     * @param sessionId a String of session id that is used to query the client pid.
     * @return the client pid when created the session. Returns {@link #UNKNOWN_CLIENT_PID}
     *         if the call fails.
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.TUNER_RESOURCE_ACCESS)
    public int getClientPid(@NonNull String sessionId) {
        return getClientPidInternal(sessionId);
    };

    /**
     * Creates a recording {@link Session} for a given TV input.
     *
     * <p>The number of sessions that can be created at the same time is limited by the capability
     * of the given TV input.
     *
     * @param inputId The ID of the TV input.
     * @param callback A callback used to receive the created session.
     * @param handler A {@link Handler} that the session creation will be delivered to.
     * @hide
     */
    public void createRecordingSession(@NonNull String inputId,
            @NonNull final SessionCallback callback, @NonNull Handler handler) {
        createSessionInternal(inputId, true, callback, handler);
    }

    private void createSessionInternal(String inputId, boolean isRecordingSession,
            SessionCallback callback, Handler handler) {
        Preconditions.checkNotNull(inputId);
        Preconditions.checkNotNull(callback);
        Preconditions.checkNotNull(handler);
        SessionCallbackRecord record = new SessionCallbackRecord(callback, handler);
        synchronized (mSessionCallbackRecordMap) {
            int seq = mNextSeq++;
            mSessionCallbackRecordMap.put(seq, record);
            try {
                mService.createSession(mClient, inputId, isRecordingSession, seq, mUserId);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    private int getClientPidInternal(String sessionId) {
        Preconditions.checkNotNull(sessionId);
        int clientPid = UNKNOWN_CLIENT_PID;
        try {
            clientPid = mService.getClientPid(sessionId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
        return clientPid;
    }

    /**
     * Returns the TvStreamConfig list of the given TV input.
     *
     * If you are using {@link Hardware} object from {@link
     * #acquireTvInputHardware}, you should get the list of available streams
     * from {@link HardwareCallback#onStreamConfigChanged} method, not from
     * here. This method is designed to be used with {@link #captureFrame} in
     * capture scenarios specifically and not suitable for any other use.
     *
     * @param inputId The ID of the TV input.
     * @return List of {@link TvStreamConfig} which is available for capturing
     *   of the given TV input.
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.CAPTURE_TV_INPUT)
    public List<TvStreamConfig> getAvailableTvStreamConfigList(String inputId) {
        try {
            return mService.getAvailableTvStreamConfigList(inputId, mUserId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Take a snapshot of the given TV input into the provided Surface.
     *
     * @param inputId The ID of the TV input.
     * @param surface the {@link Surface} to which the snapshot is captured.
     * @param config the {@link TvStreamConfig} which is used for capturing.
     * @return true when the {@link Surface} is ready to be captured.
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.CAPTURE_TV_INPUT)
    public boolean captureFrame(String inputId, Surface surface, TvStreamConfig config) {
        try {
            return mService.captureFrame(inputId, surface, config, mUserId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns true if there is only a single TV input session.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.CAPTURE_TV_INPUT)
    public boolean isSingleSessionActive() {
        try {
            return mService.isSingleSessionActive(mUserId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns a list of TvInputHardwareInfo objects representing available hardware.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.TV_INPUT_HARDWARE)
    public List<TvInputHardwareInfo> getHardwareList() {
        try {
            return mService.getHardwareList();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Acquires {@link Hardware} object for the given device ID.
     *
     * <p>A subsequent call to this method on the same {@code deviceId} will release the currently
     * acquired Hardware.
     *
     * @param deviceId The device ID to acquire Hardware for.
     * @param callback A callback to receive updates on Hardware.
     * @param info The TV input which will use the acquired Hardware.
     * @return Hardware on success, {@code null} otherwise.
     *
     * @hide
     * @removed
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.TV_INPUT_HARDWARE)
    public Hardware acquireTvInputHardware(int deviceId, final HardwareCallback callback,
            TvInputInfo info) {
        return acquireTvInputHardware(deviceId, info, callback);
    }

    /**
     * Acquires {@link Hardware} object for the given device ID.
     *
     * <p>A subsequent call to this method on the same {@code deviceId} could release the currently
     * acquired Hardware if TunerResourceManager(TRM) detects higher priority from the current
     * request.
     *
     * <p>If the client would like to provide information for the TRM to compare, use
     * {@link #acquireTvInputHardware(int, TvInputInfo, HardwareCallback, String, int)} instead.
     *
     * <p>Otherwise default priority will be applied.
     *
     * @param deviceId The device ID to acquire Hardware for.
     * @param info The TV input which will use the acquired Hardware.
     * @param callback A callback to receive updates on Hardware.
     * @return Hardware on success, {@code null} otherwise.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.TV_INPUT_HARDWARE)
    public Hardware acquireTvInputHardware(int deviceId, @NonNull TvInputInfo info,
            @NonNull final HardwareCallback callback) {
        Preconditions.checkNotNull(info);
        Preconditions.checkNotNull(callback);
        return acquireTvInputHardwareInternal(deviceId, info, null,
                TvInputService.PRIORITY_HINT_USE_CASE_TYPE_LIVE, new Executor() {
                    public void execute(Runnable r) {
                        r.run();
                    }
                }, callback);
    }

    /**
     * Acquires {@link Hardware} object for the given device ID.
     *
     * <p>A subsequent call to this method on the same {@code deviceId} could release the currently
     * acquired Hardware if TunerResourceManager(TRM) detects higher priority from the current
     * request.
     *
     * @param deviceId The device ID to acquire Hardware for.
     * @param info The TV input which will use the acquired Hardware.
     * @param tvInputSessionId a String returned to TIS when the session was created.
     *        {@see TvInputService#onCreateSession(String, String)}. If null, the client will be
     *        treated as a background app.
     * @param priorityHint The use case of the client. {@see TvInputService#PriorityHintUseCaseType}
     * @param executor the executor on which the listener would be invoked.
     * @param callback A callback to receive updates on Hardware.
     * @return Hardware on success, {@code null} otherwise. When the TRM decides to not grant
     *         resource, null is returned and the {@link IllegalStateException} is thrown with
     *         "No enough resources".
     *
     * @hide
     */
    @SystemApi
    @Nullable
    @RequiresPermission(android.Manifest.permission.TV_INPUT_HARDWARE)
    public Hardware acquireTvInputHardware(int deviceId, @NonNull TvInputInfo info,
            @Nullable String tvInputSessionId,
            @TvInputService.PriorityHintUseCaseType int priorityHint,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull final HardwareCallback callback) {
        Preconditions.checkNotNull(info);
        Preconditions.checkNotNull(callback);
        return acquireTvInputHardwareInternal(deviceId, info, tvInputSessionId, priorityHint,
                executor, callback);
    }

    /**
     * API to add a hardware device in the TvInputHardwareManager for CTS testing
     * purpose.
     *
     * @param deviceId Id of the adding hardware device.
     *
     * @hide
     */
    @TestApi
    public void addHardwareDevice(int deviceId) {
        try {
            mService.addHardwareDevice(deviceId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * API to remove a hardware device in the TvInputHardwareManager for CTS testing
     * purpose.
     *
     * @param deviceId Id of the removing hardware device.
     *
     * @hide
     */
    @TestApi
    public void removeHardwareDevice(int deviceId) {
        try {
            mService.removeHardwareDevice(deviceId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    private Hardware acquireTvInputHardwareInternal(int deviceId, TvInputInfo info,
            String tvInputSessionId, int priorityHint,
            Executor executor, final HardwareCallback callback) {
        try {
            ITvInputHardware hardware =
                    mService.acquireTvInputHardware(deviceId, new ITvInputHardwareCallback.Stub() {
                @Override
                public void onReleased() {
                            final long identity = Binder.clearCallingIdentity();
                            try {
                                executor.execute(() -> callback.onReleased());
                            } finally {
                                Binder.restoreCallingIdentity(identity);
                            }
                }

                @Override
                public void onStreamConfigChanged(TvStreamConfig[] configs) {
                            final long identity = Binder.clearCallingIdentity();
                            try {
                                executor.execute(() -> callback.onStreamConfigChanged(configs));
                            } finally {
                                Binder.restoreCallingIdentity(identity);
                            }
                }
                    }, info, mUserId, tvInputSessionId, priorityHint);
            if (hardware == null) {
                return null;
            }
            return new Hardware(hardware);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Releases previously acquired hardware object.
     *
     * @param deviceId The device ID this Hardware was acquired for
     * @param hardware Hardware to release.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.TV_INPUT_HARDWARE)
    public void releaseTvInputHardware(int deviceId, Hardware hardware) {
        try {
            mService.releaseTvInputHardware(deviceId, hardware.getInterface(), mUserId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the list of currently available DVB frontend devices on the system.
     *
     * @return the list of {@link DvbDeviceInfo} objects representing available DVB devices.
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.DVB_DEVICE)
    @NonNull
    public List<DvbDeviceInfo> getDvbDeviceList() {
        try {
            return mService.getDvbDeviceList();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns a {@link ParcelFileDescriptor} of a specified DVB device of a given type for a given
     * {@link DvbDeviceInfo}.
     *
     * @param info A {@link DvbDeviceInfo} to open a DVB device.
     * @param deviceType A DVB device type.
     * @return a {@link ParcelFileDescriptor} of a specified DVB device for a given
     * {@link DvbDeviceInfo}, or {@code null} if the given {@link DvbDeviceInfo}
     * failed to open.
     * @throws IllegalArgumentException if {@code deviceType} is invalid or the device is not found.

     * @see <a href="https://www.linuxtv.org/docs/dvbapi/dvbapi.html">Linux DVB API v3</a>
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.DVB_DEVICE)
    @Nullable
    public ParcelFileDescriptor openDvbDevice(@NonNull DvbDeviceInfo info,
            @DvbDeviceType int deviceType) {
        try {
            if (DVB_DEVICE_START > deviceType || DVB_DEVICE_END < deviceType) {
                throw new IllegalArgumentException("Invalid DVB device: " + deviceType);
            }
            return mService.openDvbDevice(info, deviceType);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Requests to make a channel browsable.
     *
     * <p>Once called, the system will review the request and make the channel browsable based on
     * its policy. The first request from a package is guaranteed to be approved.
     *
     * @param channelUri The URI for the channel to be browsable.
     * @hide
     */
    public void requestChannelBrowsable(Uri channelUri) {
        try {
            mService.requestChannelBrowsable(channelUri, mUserId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the list of session information for {@link TvInputService.Session} that are
     * currently in use.
     * <p> Permission com.android.providers.tv.permission.ACCESS_WATCHED_PROGRAMS is required to get
     * the channel URIs. If the permission is not granted,
     * {@link TunedInfo#getChannelUri()} returns {@code null}.
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.ACCESS_TUNED_INFO)
    @NonNull
    public List<TunedInfo> getCurrentTunedInfos() {
        try {
            return mService.getCurrentTunedInfos(mUserId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * The Session provides the per-session functionality of TV inputs.
     * @hide
     */
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

        private final Pool<PendingEvent> mPendingEventPool = new SimplePool<>(20);
        private final SparseArray<PendingEvent> mPendingEvents = new SparseArray<>(20);
        private final SparseArray<SessionCallbackRecord> mSessionCallbackRecordMap;

        private IBinder mToken;
        private TvInputEventSender mSender;
        private InputChannel mChannel;

        private final Object mMetadataLock = new Object();
        // @GuardedBy("mMetadataLock")
        private final List<TvTrackInfo> mAudioTracks = new ArrayList<>();
        // @GuardedBy("mMetadataLock")
        private final List<TvTrackInfo> mVideoTracks = new ArrayList<>();
        // @GuardedBy("mMetadataLock")
        private final List<TvTrackInfo> mSubtitleTracks = new ArrayList<>();
        // @GuardedBy("mMetadataLock")
        private String mSelectedAudioTrackId;
        // @GuardedBy("mMetadataLock")
        private String mSelectedVideoTrackId;
        // @GuardedBy("mMetadataLock")
        private String mSelectedSubtitleTrackId;
        // @GuardedBy("mMetadataLock")
        private int mVideoWidth;
        // @GuardedBy("mMetadataLock")
        private int mVideoHeight;

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
         * Sets this as the main session. The main session is a session whose corresponding TV
         * input determines the HDMI-CEC active source device.
         *
         * @see TvView#setMain
         */
        void setMain() {
            if (mToken == null) {
                Log.w(TAG, "The session has been already released");
                return;
            }
            try {
                mService.setMainSession(mToken, mUserId);
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
         * Sets the relative stream volume of this session to handle a change of audio focus.
         *
         * @param volume A volume value between 0.0f to 1.0f.
         * @throws IllegalArgumentException if the volume value is out of range.
         */
        public void setStreamVolume(float volume) {
            if (mToken == null) {
                Log.w(TAG, "The session has been already released");
                return;
            }
            try {
                if (volume < 0.0f || volume > 1.0f) {
                    throw new IllegalArgumentException("volume should be between 0.0f and 1.0f");
                }
                mService.setVolume(mToken, volume, mUserId);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        /**
         * Tunes to a given channel.
         *
         * @param channelUri The URI of a channel.
         */
        public void tune(Uri channelUri) {
            tune(channelUri, null);
        }

        /**
         * Tunes to a given channel.
         *
         * @param channelUri The URI of a channel.
         * @param params A set of extra parameters which might be handled with this tune event.
         */
        public void tune(@NonNull Uri channelUri, Bundle params) {
            Preconditions.checkNotNull(channelUri);
            if (mToken == null) {
                Log.w(TAG, "The session has been already released");
                return;
            }
            synchronized (mMetadataLock) {
                mAudioTracks.clear();
                mVideoTracks.clear();
                mSubtitleTracks.clear();
                mSelectedAudioTrackId = null;
                mSelectedVideoTrackId = null;
                mSelectedSubtitleTrackId = null;
                mVideoWidth = 0;
                mVideoHeight = 0;
            }
            try {
                mService.tune(mToken, channelUri, params, mUserId);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        /**
         * Enables or disables the caption for this session.
         *
         * @param enabled {@code true} to enable, {@code false} to disable.
         */
        public void setCaptionEnabled(boolean enabled) {
            if (mToken == null) {
                Log.w(TAG, "The session has been already released");
                return;
            }
            try {
                mService.setCaptionEnabled(mToken, enabled, mUserId);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        /**
         * Selects a track.
         *
         * @param type The type of the track to select. The type can be
         *            {@link TvTrackInfo#TYPE_AUDIO}, {@link TvTrackInfo#TYPE_VIDEO} or
         *            {@link TvTrackInfo#TYPE_SUBTITLE}.
         * @param trackId The ID of the track to select. When {@code null}, the currently selected
         *            track of the given type will be unselected.
         * @see #getTracks
         */
        public void selectTrack(int type, @Nullable String trackId) {
            synchronized (mMetadataLock) {
                if (type == TvTrackInfo.TYPE_AUDIO) {
                    if (trackId != null && !containsTrack(mAudioTracks, trackId)) {
                        Log.w(TAG, "Invalid audio trackId: " + trackId);
                        return;
                    }
                } else if (type == TvTrackInfo.TYPE_VIDEO) {
                    if (trackId != null && !containsTrack(mVideoTracks, trackId)) {
                        Log.w(TAG, "Invalid video trackId: " + trackId);
                        return;
                    }
                } else if (type == TvTrackInfo.TYPE_SUBTITLE) {
                    if (trackId != null && !containsTrack(mSubtitleTracks, trackId)) {
                        Log.w(TAG, "Invalid subtitle trackId: " + trackId);
                        return;
                    }
                } else {
                    throw new IllegalArgumentException("invalid type: " + type);
                }
            }
            if (mToken == null) {
                Log.w(TAG, "The session has been already released");
                return;
            }
            try {
                mService.selectTrack(mToken, type, trackId, mUserId);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        private boolean containsTrack(List<TvTrackInfo> tracks, String trackId) {
            for (TvTrackInfo track : tracks) {
                if (track.getId().equals(trackId)) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Returns the list of tracks for a given type. Returns {@code null} if the information is
         * not available.
         *
         * @param type The type of the tracks. The type can be {@link TvTrackInfo#TYPE_AUDIO},
         *            {@link TvTrackInfo#TYPE_VIDEO} or {@link TvTrackInfo#TYPE_SUBTITLE}.
         * @return the list of tracks for the given type.
         */
        @Nullable
        public List<TvTrackInfo> getTracks(int type) {
            synchronized (mMetadataLock) {
                if (type == TvTrackInfo.TYPE_AUDIO) {
                    if (mAudioTracks == null) {
                        return null;
                    }
                    return new ArrayList<>(mAudioTracks);
                } else if (type == TvTrackInfo.TYPE_VIDEO) {
                    if (mVideoTracks == null) {
                        return null;
                    }
                    return new ArrayList<>(mVideoTracks);
                } else if (type == TvTrackInfo.TYPE_SUBTITLE) {
                    if (mSubtitleTracks == null) {
                        return null;
                    }
                    return new ArrayList<>(mSubtitleTracks);
                }
            }
            throw new IllegalArgumentException("invalid type: " + type);
        }

        /**
         * Returns the selected track for a given type. Returns {@code null} if the information is
         * not available or any of the tracks for the given type is not selected.
         *
         * @return The ID of the selected track.
         * @see #selectTrack
         */
        @Nullable
        public String getSelectedTrack(int type) {
            synchronized (mMetadataLock) {
                if (type == TvTrackInfo.TYPE_AUDIO) {
                    return mSelectedAudioTrackId;
                } else if (type == TvTrackInfo.TYPE_VIDEO) {
                    return mSelectedVideoTrackId;
                } else if (type == TvTrackInfo.TYPE_SUBTITLE) {
                    return mSelectedSubtitleTrackId;
                }
            }
            throw new IllegalArgumentException("invalid type: " + type);
        }

        /**
         * Responds to onTracksChanged() and updates the internal track information. Returns true if
         * there is an update.
         */
        boolean updateTracks(List<TvTrackInfo> tracks) {
            synchronized (mMetadataLock) {
                mAudioTracks.clear();
                mVideoTracks.clear();
                mSubtitleTracks.clear();
                for (TvTrackInfo track : tracks) {
                    if (track.getType() == TvTrackInfo.TYPE_AUDIO) {
                        mAudioTracks.add(track);
                    } else if (track.getType() == TvTrackInfo.TYPE_VIDEO) {
                        mVideoTracks.add(track);
                    } else if (track.getType() == TvTrackInfo.TYPE_SUBTITLE) {
                        mSubtitleTracks.add(track);
                    }
                }
                return !mAudioTracks.isEmpty() || !mVideoTracks.isEmpty()
                        || !mSubtitleTracks.isEmpty();
            }
        }

        /**
         * Responds to onTrackSelected() and updates the internal track selection information.
         * Returns true if there is an update.
         */
        boolean updateTrackSelection(int type, String trackId) {
            synchronized (mMetadataLock) {
                if (type == TvTrackInfo.TYPE_AUDIO
                        && !TextUtils.equals(trackId, mSelectedAudioTrackId)) {
                    mSelectedAudioTrackId = trackId;
                    return true;
                } else if (type == TvTrackInfo.TYPE_VIDEO
                        && !TextUtils.equals(trackId, mSelectedVideoTrackId)) {
                    mSelectedVideoTrackId = trackId;
                    return true;
                } else if (type == TvTrackInfo.TYPE_SUBTITLE
                        && !TextUtils.equals(trackId, mSelectedSubtitleTrackId)) {
                    mSelectedSubtitleTrackId = trackId;
                    return true;
                }
            }
            return false;
        }

        /**
         * Returns the new/updated video track that contains new video size information. Returns
         * null if there is no video track to notify. Subsequent calls of this method results in a
         * non-null video track returned only by the first call and null returned by following
         * calls. The caller should immediately notify of the video size change upon receiving the
         * track.
         */
        TvTrackInfo getVideoTrackToNotify() {
            synchronized (mMetadataLock) {
                if (!mVideoTracks.isEmpty() && mSelectedVideoTrackId != null) {
                    for (TvTrackInfo track : mVideoTracks) {
                        if (track.getId().equals(mSelectedVideoTrackId)) {
                            int videoWidth = track.getVideoWidth();
                            int videoHeight = track.getVideoHeight();
                            if (mVideoWidth != videoWidth || mVideoHeight != videoHeight) {
                                mVideoWidth = videoWidth;
                                mVideoHeight = videoHeight;
                                return track;
                            }
                        }
                    }
                }
            }
            return null;
        }

        /**
         * Plays a given recorded TV program.
         */
        void timeShiftPlay(Uri recordedProgramUri) {
            if (mToken == null) {
                Log.w(TAG, "The session has been already released");
                return;
            }
            try {
                mService.timeShiftPlay(mToken, recordedProgramUri, mUserId);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        /**
         * Pauses the playback. Call {@link #timeShiftResume()} to restart the playback.
         */
        void timeShiftPause() {
            if (mToken == null) {
                Log.w(TAG, "The session has been already released");
                return;
            }
            try {
                mService.timeShiftPause(mToken, mUserId);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        /**
         * Resumes the playback. No-op if it is already playing the channel.
         */
        void timeShiftResume() {
            if (mToken == null) {
                Log.w(TAG, "The session has been already released");
                return;
            }
            try {
                mService.timeShiftResume(mToken, mUserId);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        /**
         * Seeks to a specified time position.
         *
         * <p>Normally, the position is given within range between the start and the current time,
         * inclusively.
         *
         * @param timeMs The time position to seek to, in milliseconds since the epoch.
         * @see TvView.TimeShiftPositionCallback#onTimeShiftStartPositionChanged
         */
        void timeShiftSeekTo(long timeMs) {
            if (mToken == null) {
                Log.w(TAG, "The session has been already released");
                return;
            }
            try {
                mService.timeShiftSeekTo(mToken, timeMs, mUserId);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        /**
         * Sets playback rate using {@link android.media.PlaybackParams}.
         *
         * @param params The playback params.
         */
        void timeShiftSetPlaybackParams(PlaybackParams params) {
            if (mToken == null) {
                Log.w(TAG, "The session has been already released");
                return;
            }
            try {
                mService.timeShiftSetPlaybackParams(mToken, params, mUserId);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        /**
         * Enable/disable position tracking.
         *
         * @param enable {@code true} to enable tracking, {@code false} otherwise.
         */
        void timeShiftEnablePositionTracking(boolean enable) {
            if (mToken == null) {
                Log.w(TAG, "The session has been already released");
                return;
            }
            try {
                mService.timeShiftEnablePositionTracking(mToken, enable, mUserId);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        /**
         * Starts TV program recording in the current recording session.
         *
         * @param programUri The URI for the TV program to record as a hint, built by
         *            {@link TvContract#buildProgramUri(long)}. Can be {@code null}.
         */
        void startRecording(@Nullable Uri programUri) {
            startRecording(programUri, null);
        }

        /**
         * Starts TV program recording in the current recording session.
         *
         * @param programUri The URI for the TV program to record as a hint, built by
         *            {@link TvContract#buildProgramUri(long)}. Can be {@code null}.
         * @param params A set of extra parameters which might be handled with this event.
         */
        void startRecording(@Nullable Uri programUri, @Nullable Bundle params) {
            if (mToken == null) {
                Log.w(TAG, "The session has been already released");
                return;
            }
            try {
                mService.startRecording(mToken, programUri, params, mUserId);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        /**
         * Stops TV program recording in the current recording session.
         */
        void stopRecording() {
            if (mToken == null) {
                Log.w(TAG, "The session has been already released");
                return;
            }
            try {
                mService.stopRecording(mToken, mUserId);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        /**
         * Pauses TV program recording in the current recording session.
         *
         * @param params Domain-specific data for this request. Keys <em>must</em> be a scoped
         *            name, i.e. prefixed with a package name you own, so that different developers
         *            will not create conflicting keys.
         *        {@link TvRecordingClient#pauseRecording(Bundle)}.
         */
        void pauseRecording(@NonNull Bundle params) {
            if (mToken == null) {
                Log.w(TAG, "The session has been already released");
                return;
            }
            try {
                mService.pauseRecording(mToken, params, mUserId);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        /**
         * Resumes TV program recording in the current recording session.
         *
         * @param params Domain-specific data for this request. Keys <em>must</em> be a scoped
         *            name, i.e. prefixed with a package name you own, so that different developers
         *            will not create conflicting keys.
         *        {@link TvRecordingClient#resumeRecording(Bundle)}.
         */
        void resumeRecording(@NonNull Bundle params) {
            if (mToken == null) {
                Log.w(TAG, "The session has been already released");
                return;
            }
            try {
                mService.resumeRecording(mToken, params, mUserId);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        /**
         * Calls {@link TvInputService.Session#appPrivateCommand(String, Bundle)
         * TvInputService.Session.appPrivateCommand()} on the current TvView.
         *
         * @param action Name of the command to be performed. This <em>must</em> be a scoped name,
         *            i.e. prefixed with a package name you own, so that different developers will
         *            not create conflicting commands.
         * @param data Any data to include with the command.
         */
        public void sendAppPrivateCommand(String action, Bundle data) {
            if (mToken == null) {
                Log.w(TAG, "The session has been already released");
                return;
            }
            try {
                mService.sendAppPrivateCommand(mToken, action, data, mUserId);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
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
         * @throws IllegalStateException if {@code view} is not attached to a window.
         */
        void createOverlayView(@NonNull View view, @NonNull Rect frame) {
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
                mService.createOverlayView(mToken, view.getWindowToken(), frame, mUserId);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        /**
         * Relayouts the current overlay view.
         *
         * @param frame A new position of the overlay view.
         */
        void relayoutOverlayView(@NonNull Rect frame) {
            Preconditions.checkNotNull(frame);
            if (mToken == null) {
                Log.w(TAG, "The session has been already released");
                return;
            }
            try {
                mService.relayoutOverlayView(mToken, frame, mUserId);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        /**
         * Removes the current overlay view.
         */
        void removeOverlayView() {
            if (mToken == null) {
                Log.w(TAG, "The session has been already released");
                return;
            }
            try {
                mService.removeOverlayView(mToken, mUserId);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        /**
         * Requests to unblock content blocked by parental controls.
         */
        void unblockContent(@NonNull TvContentRating unblockedRating) {
            Preconditions.checkNotNull(unblockedRating);
            if (mToken == null) {
                Log.w(TAG, "The session has been already released");
                return;
            }
            try {
                mService.unblockContent(mToken, unblockedRating.flattenToString(), mUserId);
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
             * @param token A token passed to {@link #dispatchInputEvent}.
             * @param handled {@code true} if the dispatched input event was handled properly.
             *            {@code false} otherwise.
             */
            void onFinishedInputEvent(Object token, boolean handled);
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
                    Log.w(TAG, "Timeout waiting for session to handle input event after "
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
            p.mEventToken = token;
            p.mCallback = callback;
            p.mEventHandler = handler;
            return p;
        }

        private void recyclePendingEventLocked(PendingEvent p) {
            p.recycle();
            mPendingEventPool.release(p);
        }

        IBinder getToken() {
            return mToken;
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
                mSessionCallbackRecordMap.delete(mSeq);
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

    /**
     * The Hardware provides the per-hardware functionality of TV hardware.
     *
     * <p>TV hardware is physical hardware attached to the Android device; for example, HDMI ports,
     * Component/Composite ports, etc. Specifically, logical devices such as HDMI CEC logical
     * devices don't fall into this category.
     *
     * @hide
     */
    @SystemApi
    public final static class Hardware {
        private final ITvInputHardware mInterface;

        private Hardware(ITvInputHardware hardwareInterface) {
            mInterface = hardwareInterface;
        }

        private ITvInputHardware getInterface() {
            return mInterface;
        }

        public boolean setSurface(Surface surface, TvStreamConfig config) {
            try {
                return mInterface.setSurface(surface, config);
            } catch (RemoteException e) {
                throw new RuntimeException(e);
            }
        }

        public void setStreamVolume(float volume) {
            try {
                mInterface.setStreamVolume(volume);
            } catch (RemoteException e) {
                throw new RuntimeException(e);
            }
        }

        /** @removed */
        @SystemApi
        public boolean dispatchKeyEventToHdmi(KeyEvent event) {
            return false;
        }

        public void overrideAudioSink(int audioType, String audioAddress, int samplingRate,
                int channelMask, int format) {
            try {
                mInterface.overrideAudioSink(audioType, audioAddress, samplingRate, channelMask,
                        format);
            } catch (RemoteException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
