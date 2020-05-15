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

package android.telecom;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.ParcelFileDescriptor;

import com.android.internal.telecom.IVideoProvider;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Represents an ongoing phone call that the in-call app should present to the user.
 */
public final class Call {
    /**
     * The state of a {@code Call} when newly created.
     */
    public static final int STATE_NEW = 0;

    /**
     * The state of an outgoing {@code Call} when dialing the remote number, but not yet connected.
     */
    public static final int STATE_DIALING = 1;

    /**
     * The state of an incoming {@code Call} when ringing locally, but not yet connected.
     */
    public static final int STATE_RINGING = 2;

    /**
     * The state of a {@code Call} when in a holding state.
     */
    public static final int STATE_HOLDING = 3;

    /**
     * The state of a {@code Call} when actively supporting conversation.
     */
    public static final int STATE_ACTIVE = 4;

    /**
     * The state of a {@code Call} when no further voice or other communication is being
     * transmitted, the remote side has been or will inevitably be informed that the {@code Call}
     * is no longer active, and the local data transport has or inevitably will release resources
     * associated with this {@code Call}.
     */
    public static final int STATE_DISCONNECTED = 7;

    /**
     * The state of an outgoing {@code Call} when waiting on user to select a
     * {@link PhoneAccount} through which to place the call.
     */
    public static final int STATE_SELECT_PHONE_ACCOUNT = 8;

    /**
     * @hide
     * @deprecated use STATE_SELECT_PHONE_ACCOUNT.
     */
    @Deprecated
    @SystemApi
    public static final int STATE_PRE_DIAL_WAIT = STATE_SELECT_PHONE_ACCOUNT;

    /**
     * The initial state of an outgoing {@code Call}.
     * Common transitions are to {@link #STATE_DIALING} state for a successful call or
     * {@link #STATE_DISCONNECTED} if it failed.
     */
    public static final int STATE_CONNECTING = 9;

    /**
     * The state of a {@code Call} when the user has initiated a disconnection of the call, but the
     * call has not yet been disconnected by the underlying {@code ConnectionService}.  The next
     * state of the call is (potentially) {@link #STATE_DISCONNECTED}.
     */
    public static final int STATE_DISCONNECTING = 10;

    /**
     * The state of an external call which is in the process of being pulled from a remote device to
     * the local device.
     * <p>
     * A call can only be in this state if the {@link Details#PROPERTY_IS_EXTERNAL_CALL} property
     * and {@link Details#CAPABILITY_CAN_PULL_CALL} capability are set on the call.
     * <p>
     * An {@link InCallService} will only see this state if it has the
     * {@link TelecomManager#METADATA_INCLUDE_EXTERNAL_CALLS} metadata set to {@code true} in its
     * manifest.
     */
    public static final int STATE_PULLING_CALL = 11;

    /**
     * The state of a call that is active with the network, but the audio from the call is
     * being intercepted by an app on the local device. Telecom does not hold audio focus in this
     * state, and the call will be invisible to the user except for a persistent notification.
     */
    public static final int STATE_AUDIO_PROCESSING = 12;

    /**
     * The state of a call that is being presented to the user after being in
     * {@link #STATE_AUDIO_PROCESSING}. The call is still active with the network in this case, and
     * Telecom will hold audio focus and play a ringtone if appropriate.
     */
    public static final int STATE_SIMULATED_RINGING = 13;

    /**
     * The key to retrieve the optional {@code PhoneAccount}s Telecom can bundle with its Call
     * extras. Used to pass the phone accounts to display on the front end to the user in order to
     * select phone accounts to (for example) place a call.
     * @deprecated Use the list from {@link #EXTRA_SUGGESTED_PHONE_ACCOUNTS} instead.
     */
    @Deprecated
    public static final String AVAILABLE_PHONE_ACCOUNTS = "selectPhoneAccountAccounts";

    /**
     * Key for extra used to pass along a list of {@link PhoneAccountSuggestion}s to the in-call
     * UI when a call enters the {@link #STATE_SELECT_PHONE_ACCOUNT} state. The list included here
     * will have the same length and be in the same order as the list passed with
     * {@link #AVAILABLE_PHONE_ACCOUNTS}.
     */
    public static final String EXTRA_SUGGESTED_PHONE_ACCOUNTS =
            "android.telecom.extra.SUGGESTED_PHONE_ACCOUNTS";

    /**
     * Extra key used to indicate the time (in milliseconds since midnight, January 1, 1970 UTC)
     * when the last outgoing emergency call was made.  This is used to identify potential emergency
     * callbacks.
     */
    public static final String EXTRA_LAST_EMERGENCY_CALLBACK_TIME_MILLIS =
            "android.telecom.extra.LAST_EMERGENCY_CALLBACK_TIME_MILLIS";


    /**
     * Extra key used to indicate whether a {@link CallScreeningService} has requested to silence
     * the ringtone for a call.  If the {@link InCallService} declares
     * {@link TelecomManager#METADATA_IN_CALL_SERVICE_RINGING} in its manifest, it should not
     * play a ringtone for an incoming call with this extra key set.
     */
    public static final String EXTRA_SILENT_RINGING_REQUESTED =
            "android.telecom.extra.SILENT_RINGING_REQUESTED";

    /**
     * Call event sent from a {@link Call} via {@link #sendCallEvent(String, Bundle)} to inform
     * Telecom that the user has requested that the current {@link Call} should be handed over
     * to another {@link ConnectionService}.
     * <p>
     * The caller must specify the {@link #EXTRA_HANDOVER_PHONE_ACCOUNT_HANDLE} to indicate to
     * Telecom which {@link PhoneAccountHandle} the {@link Call} should be handed over to.
     * @hide
     * @deprecated Use {@link Call#handoverTo(PhoneAccountHandle, int, Bundle)} and its associated
     * APIs instead.
     */
    public static final String EVENT_REQUEST_HANDOVER =
            "android.telecom.event.REQUEST_HANDOVER";

    /**
     * Extra key used with the {@link #EVENT_REQUEST_HANDOVER} call event.  Specifies the
     * {@link PhoneAccountHandle} to which a call should be handed over to.
     * @hide
     * @deprecated Use {@link Call#handoverTo(PhoneAccountHandle, int, Bundle)} and its associated
     * APIs instead.
     */
    public static final String EXTRA_HANDOVER_PHONE_ACCOUNT_HANDLE =
            "android.telecom.extra.HANDOVER_PHONE_ACCOUNT_HANDLE";

    /**
     * Integer extra key used with the {@link #EVENT_REQUEST_HANDOVER} call event.  Specifies the
     * video state of the call when it is handed over to the new {@link PhoneAccount}.
     * <p>
     * Valid values: {@link VideoProfile#STATE_AUDIO_ONLY},
     * {@link VideoProfile#STATE_BIDIRECTIONAL}, {@link VideoProfile#STATE_RX_ENABLED}, and
     * {@link VideoProfile#STATE_TX_ENABLED}.
     * @hide
     * @deprecated Use {@link Call#handoverTo(PhoneAccountHandle, int, Bundle)} and its associated
     * APIs instead.
     */
    public static final String EXTRA_HANDOVER_VIDEO_STATE =
            "android.telecom.extra.HANDOVER_VIDEO_STATE";

    /**
     * Extra key used with the {@link #EVENT_REQUEST_HANDOVER} call event.  Used by the
     * {@link InCallService} initiating a handover to provide a {@link Bundle} with extra
     * information to the handover {@link ConnectionService} specified by
     * {@link #EXTRA_HANDOVER_PHONE_ACCOUNT_HANDLE}.
     * <p>
     * This {@link Bundle} is not interpreted by Telecom, but passed as-is to the
     * {@link ConnectionService} via the request extras when
     * {@link ConnectionService#onCreateOutgoingConnection(PhoneAccountHandle, ConnectionRequest)}
     * is called to initate the handover.
     * @hide
     * @deprecated Use {@link Call#handoverTo(PhoneAccountHandle, int, Bundle)} and its associated
     * APIs instead.
     */
    public static final String EXTRA_HANDOVER_EXTRAS = "android.telecom.extra.HANDOVER_EXTRAS";

    /**
     * Call event sent from Telecom to the handover {@link ConnectionService} via
     * {@link Connection#onCallEvent(String, Bundle)} to inform a {@link Connection} that a handover
     * to the {@link ConnectionService} has completed successfully.
     * <p>
     * A handover is initiated with the {@link #EVENT_REQUEST_HANDOVER} call event.
     * @hide
     * @deprecated Use {@link Call#handoverTo(PhoneAccountHandle, int, Bundle)} and its associated
     * APIs instead.
     */
    public static final String EVENT_HANDOVER_COMPLETE =
            "android.telecom.event.HANDOVER_COMPLETE";

    /**
     * Call event sent from Telecom to the handover destination {@link ConnectionService} via
     * {@link Connection#onCallEvent(String, Bundle)} to inform the handover destination that the
     * source connection has disconnected.  The {@link Bundle} parameter for the call event will be
     * {@code null}.
     * <p>
     * A handover is initiated with the {@link #EVENT_REQUEST_HANDOVER} call event.
     * @hide
     * @deprecated Use {@link Call#handoverTo(PhoneAccountHandle, int, Bundle)} and its associated
     * APIs instead.
     */
    public static final String EVENT_HANDOVER_SOURCE_DISCONNECTED =
            "android.telecom.event.HANDOVER_SOURCE_DISCONNECTED";

    /**
     * Call event sent from Telecom to the handover {@link ConnectionService} via
     * {@link Connection#onCallEvent(String, Bundle)} to inform a {@link Connection} that a handover
     * to the {@link ConnectionService} has failed.
     * <p>
     * A handover is initiated with the {@link #EVENT_REQUEST_HANDOVER} call event.
     * @hide
     * @deprecated Use {@link Call#handoverTo(PhoneAccountHandle, int, Bundle)} and its associated
     * APIs instead.
     */
    public static final String EVENT_HANDOVER_FAILED =
            "android.telecom.event.HANDOVER_FAILED";


    /**
     * Reject reason used with {@link #reject(int)} to indicate that the user is rejecting this
     * call because they have declined to answer it.  This typically means that they are unable
     * to answer the call at this time and would prefer it be sent to voicemail.
     */
    public static final int REJECT_REASON_DECLINED = 1;

    /**
     * Reject reason used with {@link #reject(int)} to indicate that the user is rejecting this
     * call because it is an unwanted call.  This allows the user to indicate that they are
     * rejecting a call because it is likely a nuisance call.
     */
    public static final int REJECT_REASON_UNWANTED = 2;

    /**
     * @hide
     */
    @IntDef(prefix = { "REJECT_REASON_" },
            value = {REJECT_REASON_DECLINED, REJECT_REASON_UNWANTED})
    @Retention(RetentionPolicy.SOURCE)
    public @interface RejectReason {};

    public static class Details {
        /** @hide */
        @Retention(RetentionPolicy.SOURCE)
        @IntDef(
                prefix = { "DIRECTION_" },
                value = {DIRECTION_UNKNOWN, DIRECTION_INCOMING, DIRECTION_OUTGOING})
        public @interface CallDirection {}

        /**
         * Indicates that the call is neither and incoming nor an outgoing call.  This can be the
         * case for calls reported directly by a {@link ConnectionService} in special cases such as
         * call handovers.
         */
        public static final int DIRECTION_UNKNOWN = -1;

        /**
         * Indicates that the call is an incoming call.
         */
        public static final int DIRECTION_INCOMING = 0;

        /**
         * Indicates that the call is an outgoing call.
         */
        public static final int DIRECTION_OUTGOING = 1;

        /** Call can currently be put on hold or unheld. */
        public static final int CAPABILITY_HOLD = 0x00000001;

        /** Call supports the hold feature. */
        public static final int CAPABILITY_SUPPORT_HOLD = 0x00000002;

        /**
         * Calls within a conference can be merged. A {@link ConnectionService} has the option to
         * add a {@link Conference} call before the child {@link Connection}s are merged. This is how
         * CDMA-based {@link Connection}s are implemented. For these unmerged {@link Conference}s, this
         * capability allows a merge button to be shown while the conference call is in the foreground
         * of the in-call UI.
         * <p>
         * This is only intended for use by a {@link Conference}.
         */
        public static final int CAPABILITY_MERGE_CONFERENCE = 0x00000004;

        /**
         * Calls within a conference can be swapped between foreground and background.
         * See {@link #CAPABILITY_MERGE_CONFERENCE} for additional information.
         * <p>
         * This is only intended for use by a {@link Conference}.
         */
        public static final int CAPABILITY_SWAP_CONFERENCE = 0x00000008;

        /**
         * @hide
         */
        public static final int CAPABILITY_UNUSED_1 = 0x00000010;

        /** Call supports responding via text option. */
        public static final int CAPABILITY_RESPOND_VIA_TEXT = 0x00000020;

        /** Call can be muted. */
        public static final int CAPABILITY_MUTE = 0x00000040;

        /**
         * Call supports conference call management. This capability only applies to {@link Conference}
         * calls which can have {@link Connection}s as children.
         */
        public static final int CAPABILITY_MANAGE_CONFERENCE = 0x00000080;

        /**
         * Local device supports receiving video.
         */
        public static final int CAPABILITY_SUPPORTS_VT_LOCAL_RX = 0x00000100;

        /**
         * Local device supports transmitting video.
         */
        public static final int CAPABILITY_SUPPORTS_VT_LOCAL_TX = 0x00000200;

        /**
         * Local device supports bidirectional video calling.
         */
        public static final int CAPABILITY_SUPPORTS_VT_LOCAL_BIDIRECTIONAL =
                CAPABILITY_SUPPORTS_VT_LOCAL_RX | CAPABILITY_SUPPORTS_VT_LOCAL_TX;

        /**
         * Remote device supports receiving video.
         */
        public static final int CAPABILITY_SUPPORTS_VT_REMOTE_RX = 0x00000400;

        /**
         * Remote device supports transmitting video.
         */
        public static final int CAPABILITY_SUPPORTS_VT_REMOTE_TX = 0x00000800;

        /**
         * Remote device supports bidirectional video calling.
         */
        public static final int CAPABILITY_SUPPORTS_VT_REMOTE_BIDIRECTIONAL =
                CAPABILITY_SUPPORTS_VT_REMOTE_RX | CAPABILITY_SUPPORTS_VT_REMOTE_TX;

        /**
         * Call is able to be separated from its parent {@code Conference}, if any.
         */
        public static final int CAPABILITY_SEPARATE_FROM_CONFERENCE = 0x00001000;

        /**
         * Call is able to be individually disconnected when in a {@code Conference}.
         */
        public static final int CAPABILITY_DISCONNECT_FROM_CONFERENCE = 0x00002000;

        /**
         * Speed up audio setup for MT call.
         * @hide
         */
        public static final int CAPABILITY_SPEED_UP_MT_AUDIO = 0x00040000;

        /**
         * Call can be upgraded to a video call.
         * @hide
         * @deprecated Use {@link #CAPABILITY_SUPPORTS_VT_LOCAL_BIDIRECTIONAL} and
         * {@link #CAPABILITY_SUPPORTS_VT_REMOTE_BIDIRECTIONAL} to indicate for a call
         * whether or not video calling is supported.
         */
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 119305590)
        public static final int CAPABILITY_CAN_UPGRADE_TO_VIDEO = 0x00080000;

        /**
         * For video calls, indicates whether the outgoing video for the call can be paused using
         * the {@link android.telecom.VideoProfile#STATE_PAUSED} VideoState.
         */
        public static final int CAPABILITY_CAN_PAUSE_VIDEO = 0x00100000;

        /**
         * Call sends responses through connection.
         * @hide
         */
        public static final int CAPABILITY_CAN_SEND_RESPONSE_VIA_CONNECTION = 0x00200000;

        /**
         * When set, prevents a video {@code Call} from being downgraded to an audio-only call.
         * <p>
         * Should be set when the VideoState has the {@link VideoProfile#STATE_TX_ENABLED} or
         * {@link VideoProfile#STATE_RX_ENABLED} bits set to indicate that the connection cannot be
         * downgraded from a video call back to a VideoState of
         * {@link VideoProfile#STATE_AUDIO_ONLY}.
         * <p>
         * Intuitively, a call which can be downgraded to audio should also have local and remote
         * video
         * capabilities (see {@link #CAPABILITY_SUPPORTS_VT_LOCAL_BIDIRECTIONAL} and
         * {@link #CAPABILITY_SUPPORTS_VT_REMOTE_BIDIRECTIONAL}).
         */
        public static final int CAPABILITY_CANNOT_DOWNGRADE_VIDEO_TO_AUDIO = 0x00400000;

        /**
         * When set for an external call, indicates that this {@code Call} can be pulled from a
         * remote device to the current device.
         * <p>
         * Should only be set on a {@code Call} where {@link #PROPERTY_IS_EXTERNAL_CALL} is set.
         * <p>
         * An {@link InCallService} will only see calls with this capability if it has the
         * {@link TelecomManager#METADATA_INCLUDE_EXTERNAL_CALLS} metadata set to {@code true}
         * in its manifest.
         * <p>
         * See {@link Connection#CAPABILITY_CAN_PULL_CALL} and
         * {@link Connection#PROPERTY_IS_EXTERNAL_CALL}.
         */
        public static final int CAPABILITY_CAN_PULL_CALL = 0x00800000;

        /** Call supports the deflect feature. */
        public static final int CAPABILITY_SUPPORT_DEFLECT = 0x01000000;

        /**
         * Call supports adding participants to the call via
         * {@link #addConferenceParticipants(List)}.
         * @hide
         */
        public static final int CAPABILITY_ADD_PARTICIPANT = 0x02000000;

        /**
         * When set for a call, indicates that this {@code Call} can be transferred to another
         * number.
         * Call supports the blind and assured call transfer feature.
         *
         * @hide
         */
        public static final int CAPABILITY_TRANSFER = 0x04000000;

        /**
         * When set for a call, indicates that this {@code Call} can be transferred to another
         * ongoing call.
         * Call supports the consultative call transfer feature.
         *
         * @hide
         */
        public static final int CAPABILITY_TRANSFER_CONSULTATIVE = 0x08000000;

        //******************************************************************************************
        // Next CAPABILITY value: 0x10000000
        //******************************************************************************************

        /**
         * Whether the call is currently a conference.
         */
        public static final int PROPERTY_CONFERENCE = 0x00000001;

        /**
         * Whether the call is a generic conference, where we do not know the precise state of
         * participants in the conference (eg. on CDMA).
         */
        public static final int PROPERTY_GENERIC_CONFERENCE = 0x00000002;

        /**
         * Whether the call is made while the device is in emergency callback mode.
         */
        public static final int PROPERTY_EMERGENCY_CALLBACK_MODE = 0x00000004;

        /**
         * Connection is using WIFI.
         */
        public static final int PROPERTY_WIFI = 0x00000008;

        /**
         * When set, the UI should indicate to the user that a call is using high definition
         * audio.
         * <p>
         * The underlying {@link ConnectionService} is responsible for reporting this
         * property.  It is important to note that this property is not intended to report the
         * actual audio codec being used for a Call, but whether the call should be indicated
         * to the user as high definition.
         * <p>
         * The Android Telephony stack reports this property for calls based on a number
         * of factors, including which audio codec is used and whether a call is using an HD
         * codec end-to-end.  Some mobile operators choose to suppress display of an HD indication,
         * and in these cases this property will not be set for a call even if the underlying audio
         * codec is in fact "high definition".
         */
        public static final int PROPERTY_HIGH_DEF_AUDIO = 0x00000010;

        /**
         * Whether the call is associated with the work profile.
         */
        public static final int PROPERTY_ENTERPRISE_CALL = 0x00000020;

        /**
         * When set, indicates that this {@code Call} does not actually exist locally for the
         * {@link ConnectionService}.
         * <p>
         * Consider, for example, a scenario where a user has two phones with the same phone number.
         * When a user places a call on one device, the telephony stack can represent that call on
         * the other device by adding it to the {@link ConnectionService} with the
         * {@link Connection#PROPERTY_IS_EXTERNAL_CALL} property set.
         * <p>
         * An {@link InCallService} will only see calls with this property if it has the
         * {@link TelecomManager#METADATA_INCLUDE_EXTERNAL_CALLS} metadata set to {@code true}
         * in its manifest.
         * <p>
         * See {@link Connection#PROPERTY_IS_EXTERNAL_CALL}.
         */
        public static final int PROPERTY_IS_EXTERNAL_CALL = 0x00000040;

        /**
         * Indicates that the call has CDMA Enhanced Voice Privacy enabled.
         */
        public static final int PROPERTY_HAS_CDMA_VOICE_PRIVACY = 0x00000080;

        /**
         * Indicates that the call is from a self-managed {@link ConnectionService}.
         * <p>
         * See also {@link Connection#PROPERTY_SELF_MANAGED}
         */
        public static final int PROPERTY_SELF_MANAGED = 0x00000100;

        /**
         * Indicates the call used Assisted Dialing.
         *
         * @see TelecomManager#EXTRA_USE_ASSISTED_DIALING
         */
        public static final int PROPERTY_ASSISTED_DIALING = 0x00000200;

        /**
         * Indicates that the call is an RTT call. Use {@link #getRttCall()} to get the
         * {@link RttCall} object that is used to send and receive text.
         */
        public static final int PROPERTY_RTT = 0x00000400;

        /**
         * Indicates that the call has been identified as the network as an emergency call. This
         * property may be set for both incoming and outgoing calls which the network identifies as
         * emergency calls.
         */
        public static final int PROPERTY_NETWORK_IDENTIFIED_EMERGENCY_CALL = 0x00000800;

        /**
         * Indicates that the call is using VoIP audio mode.
         * <p>
         * When this property is set, the {@link android.media.AudioManager} audio mode for this
         * call will be {@link android.media.AudioManager#MODE_IN_COMMUNICATION}.  When this
         * property is not set, the audio mode for this call will be
         * {@link android.media.AudioManager#MODE_IN_CALL}.
         * <p>
         * This property reflects changes made using {@link Connection#setAudioModeIsVoip(boolean)}.
         * <p>
         * You can use this property to determine whether an un-answered incoming call or a held
         * call will use VoIP audio mode (if the call does not currently have focus, the system
         * audio mode may not reflect the mode the call will use).
         */
        public static final int PROPERTY_VOIP_AUDIO_MODE = 0x00001000;

        /**
         * Indicates that the call is an adhoc conference call. This property can be set for both
         * incoming and outgoing calls.
         * @hide
         */
        public static final int PROPERTY_IS_ADHOC_CONFERENCE = 0x00002000;

        //******************************************************************************************
        // Next PROPERTY value: 0x00004000
        //******************************************************************************************

        private final String mTelecomCallId;
        private final Uri mHandle;
        private final int mHandlePresentation;
        private final String mCallerDisplayName;
        private final int mCallerDisplayNamePresentation;
        private final PhoneAccountHandle mAccountHandle;
        private final int mCallCapabilities;
        private final int mCallProperties;
        private final int mSupportedAudioRoutes = CallAudioState.ROUTE_ALL;
        private final DisconnectCause mDisconnectCause;
        private final long mConnectTimeMillis;
        private final GatewayInfo mGatewayInfo;
        private final int mVideoState;
        private final StatusHints mStatusHints;
        private final Bundle mExtras;
        private final Bundle mIntentExtras;
        private final long mCreationTimeMillis;
        private final String mContactDisplayName;
        private final @CallDirection int mCallDirection;
        private final @Connection.VerificationStatus int mCallerNumberVerificationStatus;

        /**
         * Whether the supplied capabilities  supports the specified capability.
         *
         * @param capabilities A bit field of capabilities.
         * @param capability The capability to check capabilities for.
         * @return Whether the specified capability is supported.
         */
        public static boolean can(int capabilities, int capability) {
            return (capabilities & capability) == capability;
        }

        /**
         * Whether the capabilities of this {@code Details} supports the specified capability.
         *
         * @param capability The capability to check capabilities for.
         * @return Whether the specified capability is supported.
         */
        public boolean can(int capability) {
            return can(mCallCapabilities, capability);
        }

        /**
         * Render a set of capability bits ({@code CAPABILITY_*}) as a human readable string.
         *
         * @param capabilities A capability bit field.
         * @return A human readable string representation.
         */
        public static String capabilitiesToString(int capabilities) {
            StringBuilder builder = new StringBuilder();
            builder.append("[Capabilities:");
            if (can(capabilities, CAPABILITY_HOLD)) {
                builder.append(" CAPABILITY_HOLD");
            }
            if (can(capabilities, CAPABILITY_SUPPORT_HOLD)) {
                builder.append(" CAPABILITY_SUPPORT_HOLD");
            }
            if (can(capabilities, CAPABILITY_MERGE_CONFERENCE)) {
                builder.append(" CAPABILITY_MERGE_CONFERENCE");
            }
            if (can(capabilities, CAPABILITY_SWAP_CONFERENCE)) {
                builder.append(" CAPABILITY_SWAP_CONFERENCE");
            }
            if (can(capabilities, CAPABILITY_RESPOND_VIA_TEXT)) {
                builder.append(" CAPABILITY_RESPOND_VIA_TEXT");
            }
            if (can(capabilities, CAPABILITY_MUTE)) {
                builder.append(" CAPABILITY_MUTE");
            }
            if (can(capabilities, CAPABILITY_MANAGE_CONFERENCE)) {
                builder.append(" CAPABILITY_MANAGE_CONFERENCE");
            }
            if (can(capabilities, CAPABILITY_SUPPORTS_VT_LOCAL_RX)) {
                builder.append(" CAPABILITY_SUPPORTS_VT_LOCAL_RX");
            }
            if (can(capabilities, CAPABILITY_SUPPORTS_VT_LOCAL_TX)) {
                builder.append(" CAPABILITY_SUPPORTS_VT_LOCAL_TX");
            }
            if (can(capabilities, CAPABILITY_SUPPORTS_VT_LOCAL_BIDIRECTIONAL)) {
                builder.append(" CAPABILITY_SUPPORTS_VT_LOCAL_BIDIRECTIONAL");
            }
            if (can(capabilities, CAPABILITY_SUPPORTS_VT_REMOTE_RX)) {
                builder.append(" CAPABILITY_SUPPORTS_VT_REMOTE_RX");
            }
            if (can(capabilities, CAPABILITY_SUPPORTS_VT_REMOTE_TX)) {
                builder.append(" CAPABILITY_SUPPORTS_VT_REMOTE_TX");
            }
            if (can(capabilities, CAPABILITY_CANNOT_DOWNGRADE_VIDEO_TO_AUDIO)) {
                builder.append(" CAPABILITY_CANNOT_DOWNGRADE_VIDEO_TO_AUDIO");
            }
            if (can(capabilities, CAPABILITY_SUPPORTS_VT_REMOTE_BIDIRECTIONAL)) {
                builder.append(" CAPABILITY_SUPPORTS_VT_REMOTE_BIDIRECTIONAL");
            }
            if (can(capabilities, CAPABILITY_SPEED_UP_MT_AUDIO)) {
                builder.append(" CAPABILITY_SPEED_UP_MT_AUDIO");
            }
            if (can(capabilities, CAPABILITY_CAN_UPGRADE_TO_VIDEO)) {
                builder.append(" CAPABILITY_CAN_UPGRADE_TO_VIDEO");
            }
            if (can(capabilities, CAPABILITY_CAN_PAUSE_VIDEO)) {
                builder.append(" CAPABILITY_CAN_PAUSE_VIDEO");
            }
            if (can(capabilities, CAPABILITY_CAN_PULL_CALL)) {
                builder.append(" CAPABILITY_CAN_PULL_CALL");
            }
            if (can(capabilities, CAPABILITY_SUPPORT_DEFLECT)) {
                builder.append(" CAPABILITY_SUPPORT_DEFLECT");
            }
            if (can(capabilities, CAPABILITY_ADD_PARTICIPANT)) {
                builder.append(" CAPABILITY_ADD_PARTICIPANT");
            }
            if (can(capabilities, CAPABILITY_TRANSFER)) {
                builder.append(" CAPABILITY_TRANSFER");
            }
            if (can(capabilities, CAPABILITY_TRANSFER_CONSULTATIVE)) {
                builder.append(" CAPABILITY_TRANSFER_CONSULTATIVE");
            }
            builder.append("]");
            return builder.toString();
        }

        /**
         * Whether the supplied properties includes the specified property.
         *
         * @param properties A bit field of properties.
         * @param property The property to check properties for.
         * @return Whether the specified property is supported.
         */
        public static boolean hasProperty(int properties, int property) {
            return (properties & property) == property;
        }

        /**
         * Whether the properties of this {@code Details} includes the specified property.
         *
         * @param property The property to check properties for.
         * @return Whether the specified property is supported.
         */
        public boolean hasProperty(int property) {
            return hasProperty(mCallProperties, property);
        }

        /**
         * Render a set of property bits ({@code PROPERTY_*}) as a human readable string.
         *
         * @param properties A property bit field.
         * @return A human readable string representation.
         */
        public static String propertiesToString(int properties) {
            StringBuilder builder = new StringBuilder();
            builder.append("[Properties:");
            if (hasProperty(properties, PROPERTY_CONFERENCE)) {
                builder.append(" PROPERTY_CONFERENCE");
            }
            if (hasProperty(properties, PROPERTY_GENERIC_CONFERENCE)) {
                builder.append(" PROPERTY_GENERIC_CONFERENCE");
            }
            if (hasProperty(properties, PROPERTY_WIFI)) {
                builder.append(" PROPERTY_WIFI");
            }
            if (hasProperty(properties, PROPERTY_HIGH_DEF_AUDIO)) {
                builder.append(" PROPERTY_HIGH_DEF_AUDIO");
            }
            if (hasProperty(properties, PROPERTY_EMERGENCY_CALLBACK_MODE)) {
                builder.append(" PROPERTY_EMERGENCY_CALLBACK_MODE");
            }
            if (hasProperty(properties, PROPERTY_IS_EXTERNAL_CALL)) {
                builder.append(" PROPERTY_IS_EXTERNAL_CALL");
            }
            if (hasProperty(properties, PROPERTY_HAS_CDMA_VOICE_PRIVACY)) {
                builder.append(" PROPERTY_HAS_CDMA_VOICE_PRIVACY");
            }
            if (hasProperty(properties, PROPERTY_ASSISTED_DIALING)) {
                builder.append(" PROPERTY_ASSISTED_DIALING_USED");
            }
            if (hasProperty(properties, PROPERTY_NETWORK_IDENTIFIED_EMERGENCY_CALL)) {
                builder.append(" PROPERTY_NETWORK_IDENTIFIED_EMERGENCY_CALL");
            }
            if (hasProperty(properties, PROPERTY_RTT)) {
                builder.append(" PROPERTY_RTT");
            }
            if (hasProperty(properties, PROPERTY_VOIP_AUDIO_MODE)) {
                builder.append(" PROPERTY_VOIP_AUDIO_MODE");
            }
            if (hasProperty(properties, PROPERTY_IS_ADHOC_CONFERENCE)) {
                builder.append(" PROPERTY_IS_ADHOC_CONFERENCE");
            }
            builder.append("]");
            return builder.toString();
        }

        /** {@hide} */
        @TestApi
        public String getTelecomCallId() {
            return mTelecomCallId;
        }

        /**
         * @return The handle (e.g., phone number) to which the {@code Call} is currently
         * connected.
         */
        public Uri getHandle() {
            return mHandle;
        }

        /**
         * @return The presentation requirements for the handle. See
         * {@link TelecomManager} for valid values.
         */
        public int getHandlePresentation() {
            return mHandlePresentation;
        }

        /**
         * The display name for the caller.
         * <p>
         * This is the name as reported by the {@link ConnectionService} associated with this call.
         *
         * @return The display name for the caller.
         */
        public String getCallerDisplayName() {
            return mCallerDisplayName;
        }

        /**
         * @return The presentation requirements for the caller display name. See
         * {@link TelecomManager} for valid values.
         */
        public int getCallerDisplayNamePresentation() {
            return mCallerDisplayNamePresentation;
        }

        /**
         * @return The {@code PhoneAccountHandle} whereby the {@code Call} is currently being
         * routed.
         */
        public PhoneAccountHandle getAccountHandle() {
            return mAccountHandle;
        }

        /**
         * @return A bitmask of the capabilities of the {@code Call}, as defined by the various
         *         {@code CAPABILITY_*} constants in this class.
         */
        public int getCallCapabilities() {
            return mCallCapabilities;
        }

        /**
         * @return A bitmask of the properties of the {@code Call}, as defined by the various
         *         {@code PROPERTY_*} constants in this class.
         */
        public int getCallProperties() {
            return mCallProperties;
        }

        /**
         * @return a bitmask of the audio routes available for the call.
         *
         * @hide
         */
        public int getSupportedAudioRoutes() {
            return mSupportedAudioRoutes;
        }

        /**
         * @return For a {@link #STATE_DISCONNECTED} {@code Call}, the disconnect cause expressed
         * by {@link android.telecom.DisconnectCause}.
         */
        public DisconnectCause getDisconnectCause() {
            return mDisconnectCause;
        }

        /**
         * Returns the time the {@link Call} connected (i.e. became active).  This information is
         * updated periodically, but user interfaces should not rely on this to display the "call
         * time clock".  For the time when the call was first added to Telecom, see
         * {@link #getCreationTimeMillis()}.
         *
         * @return The time the {@link Call} connected in milliseconds since the epoch.
         */
        public final long getConnectTimeMillis() {
            return mConnectTimeMillis;
        }

        /**
         * @return Information about any calling gateway the {@code Call} may be using.
         */
        public GatewayInfo getGatewayInfo() {
            return mGatewayInfo;
        }

        /**
         * @return The video state of the {@code Call}.
         */
        public int getVideoState() {
            return mVideoState;
        }

        /**
         * @return The current {@link android.telecom.StatusHints}, or {@code null} if none
         * have been set.
         */
        public StatusHints getStatusHints() {
            return mStatusHints;
        }

        /**
         * @return The extras associated with this call.
         */
        public Bundle getExtras() {
            return mExtras;
        }

        /**
         * @return The extras used with the original intent to place this call.
         */
        public Bundle getIntentExtras() {
            return mIntentExtras;
        }

        /**
         * Returns the time when the call was first created and added to Telecom.  This is the same
         * time that is logged as the start time in the Call Log (see
         * {@link android.provider.CallLog.Calls#DATE}).  To determine when the call was connected
         * (became active), see {@link #getConnectTimeMillis()}.
         *
         * @return The creation time of the call, in millis since the epoch.
         */
        public long getCreationTimeMillis() {
            return mCreationTimeMillis;
        }

        /**
         * Returns the name of the caller on the remote end, as derived from a
         * {@link android.provider.ContactsContract} lookup of the call's handle.
         * @return The name of the caller, or {@code null} if the lookup is not yet complete, if
         *         there's no contacts entry for the caller, or if the {@link InCallService} does
         *         not hold the {@link android.Manifest.permission#READ_CONTACTS} permission.
         */
        public @Nullable String getContactDisplayName() {
            return mContactDisplayName;
        }

        /**
         * Indicates whether the call is an incoming or outgoing call.
         * @return The call's direction.
         */
        public @CallDirection int getCallDirection() {
            return mCallDirection;
        }

        /**
         * Gets the verification status for the phone number of an incoming call as identified in
         * ATIS-1000082.
         * @return the verification status.
         */
        public @Connection.VerificationStatus int getCallerNumberVerificationStatus() {
            return mCallerNumberVerificationStatus;
        }

        @Override
        public boolean equals(Object o) {
            if (o instanceof Details) {
                Details d = (Details) o;
                return
                        Objects.equals(mHandle, d.mHandle) &&
                        Objects.equals(mHandlePresentation, d.mHandlePresentation) &&
                        Objects.equals(mCallerDisplayName, d.mCallerDisplayName) &&
                        Objects.equals(mCallerDisplayNamePresentation,
                                d.mCallerDisplayNamePresentation) &&
                        Objects.equals(mAccountHandle, d.mAccountHandle) &&
                        Objects.equals(mCallCapabilities, d.mCallCapabilities) &&
                        Objects.equals(mCallProperties, d.mCallProperties) &&
                        Objects.equals(mDisconnectCause, d.mDisconnectCause) &&
                        Objects.equals(mConnectTimeMillis, d.mConnectTimeMillis) &&
                        Objects.equals(mGatewayInfo, d.mGatewayInfo) &&
                        Objects.equals(mVideoState, d.mVideoState) &&
                        Objects.equals(mStatusHints, d.mStatusHints) &&
                        areBundlesEqual(mExtras, d.mExtras) &&
                        areBundlesEqual(mIntentExtras, d.mIntentExtras) &&
                        Objects.equals(mCreationTimeMillis, d.mCreationTimeMillis) &&
                        Objects.equals(mContactDisplayName, d.mContactDisplayName) &&
                        Objects.equals(mCallDirection, d.mCallDirection) &&
                        Objects.equals(mCallerNumberVerificationStatus,
                                d.mCallerNumberVerificationStatus);
            }
            return false;
        }

        @Override
        public int hashCode() {
            return Objects.hash(mHandle,
                            mHandlePresentation,
                            mCallerDisplayName,
                            mCallerDisplayNamePresentation,
                            mAccountHandle,
                            mCallCapabilities,
                            mCallProperties,
                            mDisconnectCause,
                            mConnectTimeMillis,
                            mGatewayInfo,
                            mVideoState,
                            mStatusHints,
                            mExtras,
                            mIntentExtras,
                            mCreationTimeMillis,
                            mContactDisplayName,
                            mCallDirection,
                            mCallerNumberVerificationStatus);
        }

        /** {@hide} */
        public Details(
                String telecomCallId,
                Uri handle,
                int handlePresentation,
                String callerDisplayName,
                int callerDisplayNamePresentation,
                PhoneAccountHandle accountHandle,
                int capabilities,
                int properties,
                DisconnectCause disconnectCause,
                long connectTimeMillis,
                GatewayInfo gatewayInfo,
                int videoState,
                StatusHints statusHints,
                Bundle extras,
                Bundle intentExtras,
                long creationTimeMillis,
                String contactDisplayName,
                int callDirection,
                int callerNumberVerificationStatus) {
            mTelecomCallId = telecomCallId;
            mHandle = handle;
            mHandlePresentation = handlePresentation;
            mCallerDisplayName = callerDisplayName;
            mCallerDisplayNamePresentation = callerDisplayNamePresentation;
            mAccountHandle = accountHandle;
            mCallCapabilities = capabilities;
            mCallProperties = properties;
            mDisconnectCause = disconnectCause;
            mConnectTimeMillis = connectTimeMillis;
            mGatewayInfo = gatewayInfo;
            mVideoState = videoState;
            mStatusHints = statusHints;
            mExtras = extras;
            mIntentExtras = intentExtras;
            mCreationTimeMillis = creationTimeMillis;
            mContactDisplayName = contactDisplayName;
            mCallDirection = callDirection;
            mCallerNumberVerificationStatus = callerNumberVerificationStatus;
        }

        /** {@hide} */
        public static Details createFromParcelableCall(ParcelableCall parcelableCall) {
            return new Details(
                    parcelableCall.getId(),
                    parcelableCall.getHandle(),
                    parcelableCall.getHandlePresentation(),
                    parcelableCall.getCallerDisplayName(),
                    parcelableCall.getCallerDisplayNamePresentation(),
                    parcelableCall.getAccountHandle(),
                    parcelableCall.getCapabilities(),
                    parcelableCall.getProperties(),
                    parcelableCall.getDisconnectCause(),
                    parcelableCall.getConnectTimeMillis(),
                    parcelableCall.getGatewayInfo(),
                    parcelableCall.getVideoState(),
                    parcelableCall.getStatusHints(),
                    parcelableCall.getExtras(),
                    parcelableCall.getIntentExtras(),
                    parcelableCall.getCreationTimeMillis(),
                    parcelableCall.getContactDisplayName(),
                    parcelableCall.getCallDirection(),
                    parcelableCall.getCallerNumberVerificationStatus());
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("[id: ");
            sb.append(mTelecomCallId);
            sb.append(", pa: ");
            sb.append(mAccountHandle);
            sb.append(", hdl: ");
            sb.append(Log.piiHandle(mHandle));
            sb.append(", hdlPres: ");
            sb.append(mHandlePresentation);
            sb.append(", videoState: ");
            sb.append(VideoProfile.videoStateToString(mVideoState));
            sb.append(", caps: ");
            sb.append(capabilitiesToString(mCallCapabilities));
            sb.append(", props: ");
            sb.append(propertiesToString(mCallProperties));
            sb.append("]");
            return sb.toString();
        }
    }

    /**
     * Defines callbacks which inform the {@link InCallService} of changes to a {@link Call}.
     * These callbacks can originate from the Telecom framework, or a {@link ConnectionService}
     * implementation.
     * <p>
     * You can handle these callbacks by extending the {@link Callback} class and overriding the
     * callbacks that your {@link InCallService} is interested in.  The callback methods include the
     * {@link Call} for which the callback applies, allowing reuse of a single instance of your
     * {@link Callback} implementation, if desired.
     * <p>
     * Use {@link Call#registerCallback(Callback)} to register your callback(s).  Ensure
     * {@link Call#unregisterCallback(Callback)} is called when you no longer require callbacks
     * (typically in {@link InCallService#onCallRemoved(Call)}).
     * Note: Callbacks which occur before you call {@link Call#registerCallback(Callback)} will not
     * reach your implementation of {@link Callback}, so it is important to register your callback
     * as soon as your {@link InCallService} is notified of a new call via
     * {@link InCallService#onCallAdded(Call)}.
     */
    public static abstract class Callback {
        /**
         * @hide
         */
        @IntDef(prefix = { "HANDOVER_" },
                value = {HANDOVER_FAILURE_DEST_APP_REJECTED, HANDOVER_FAILURE_NOT_SUPPORTED,
                HANDOVER_FAILURE_USER_REJECTED, HANDOVER_FAILURE_ONGOING_EMERGENCY_CALL,
                HANDOVER_FAILURE_UNKNOWN})
        @Retention(RetentionPolicy.SOURCE)
        public @interface HandoverFailureErrors {}

        /**
         * Handover failure reason returned via {@link #onHandoverFailed(Call, int)} when the app
         * to handover the call to rejects the handover request.
         * <p>
         * Will be returned when {@link Call#handoverTo(PhoneAccountHandle, int, Bundle)} is called
         * and the destination {@link PhoneAccountHandle}'s {@link ConnectionService} returns a
         * {@code null} {@link Connection} from
         * {@link ConnectionService#onCreateOutgoingHandoverConnection(PhoneAccountHandle,
         * ConnectionRequest)}.
         * <p>
         * For more information on call handovers, see
         * {@link #handoverTo(PhoneAccountHandle, int, Bundle)}.
         */
        public static final int HANDOVER_FAILURE_DEST_APP_REJECTED = 1;

        /**
         * Handover failure reason returned via {@link #onHandoverFailed(Call, int)} when a handover
         * is initiated but the source or destination app does not support handover.
         * <p>
         * Will be returned when a handover is requested via
         * {@link #handoverTo(PhoneAccountHandle, int, Bundle)} and the destination
         * {@link PhoneAccountHandle} does not declare
         * {@link PhoneAccount#EXTRA_SUPPORTS_HANDOVER_TO}.  May also be returned when a handover is
         * requested at the {@link PhoneAccountHandle} for the current call (i.e. the source call's
         * {@link Details#getAccountHandle()}) does not declare
         * {@link PhoneAccount#EXTRA_SUPPORTS_HANDOVER_FROM}.
         * <p>
         * For more information on call handovers, see
         * {@link #handoverTo(PhoneAccountHandle, int, Bundle)}.
         */
        public static final int HANDOVER_FAILURE_NOT_SUPPORTED = 2;

        /**
         * Handover failure reason returned via {@link #onHandoverFailed(Call, int)} when the remote
         * user rejects the handover request.
         * <p>
         * For more information on call handovers, see
         * {@link #handoverTo(PhoneAccountHandle, int, Bundle)}.
         */
        public static final int HANDOVER_FAILURE_USER_REJECTED = 3;

        /**
         * Handover failure reason returned via {@link #onHandoverFailed(Call, int)} when there
         * is ongoing emergency call.
         * <p>
         * This error code is returned when {@link #handoverTo(PhoneAccountHandle, int, Bundle)} is
         * called on an emergency call, or if any other call is an emergency call.
         * <p>
         * Handovers are not permitted while there are ongoing emergency calls.
         * <p>
         * For more information on call handovers, see
         * {@link #handoverTo(PhoneAccountHandle, int, Bundle)}.
         */
        public static final int HANDOVER_FAILURE_ONGOING_EMERGENCY_CALL = 4;

        /**
         * Handover failure reason returned via {@link #onHandoverFailed(Call, int)} when a handover
         * fails for an unknown reason.
         * <p>
         * For more information on call handovers, see
         * {@link #handoverTo(PhoneAccountHandle, int, Bundle)}.
         */
        public static final int HANDOVER_FAILURE_UNKNOWN = 5;

        /**
         * Invoked when the state of this {@code Call} has changed. See {@link #getState()}.
         *
         * @param call The {@code Call} invoking this method.
         * @param state The new state of the {@code Call}.
         */
        public void onStateChanged(Call call, int state) {}

        /**
         * Invoked when the parent of this {@code Call} has changed. See {@link #getParent()}.
         *
         * @param call The {@code Call} invoking this method.
         * @param parent The new parent of the {@code Call}.
         */
        public void onParentChanged(Call call, Call parent) {}

        /**
         * Invoked when the children of this {@code Call} have changed. See {@link #getChildren()}.
         *
         * @param call The {@code Call} invoking this method.
         * @param children The new children of the {@code Call}.
         */
        public void onChildrenChanged(Call call, List<Call> children) {}

        /**
         * Invoked when the details of this {@code Call} have changed. See {@link #getDetails()}.
         *
         * @param call The {@code Call} invoking this method.
         * @param details A {@code Details} object describing the {@code Call}.
         */
        public void onDetailsChanged(Call call, Details details) {}

        /**
         * Invoked when the text messages that can be used as responses to the incoming
         * {@code Call} are loaded from the relevant database.
         * See {@link #getCannedTextResponses()}.
         *
         * @param call The {@code Call} invoking this method.
         * @param cannedTextResponses The text messages useable as responses.
         */
        public void onCannedTextResponsesLoaded(Call call, List<String> cannedTextResponses) {}

        /**
         * Invoked when the post-dial sequence in the outgoing {@code Call} has reached a pause
         * character. This causes the post-dial signals to stop pending user confirmation. An
         * implementation should present this choice to the user and invoke
         * {@link #postDialContinue(boolean)} when the user makes the choice.
         *
         * @param call The {@code Call} invoking this method.
         * @param remainingPostDialSequence The post-dial characters that remain to be sent.
         */
        public void onPostDialWait(Call call, String remainingPostDialSequence) {}

        /**
         * Invoked when the {@code Call.VideoCall} of the {@code Call} has changed.
         *
         * @param call The {@code Call} invoking this method.
         * @param videoCall The {@code Call.VideoCall} associated with the {@code Call}.
         */
        public void onVideoCallChanged(Call call, InCallService.VideoCall videoCall) {}

        /**
         * Invoked when the {@code Call} is destroyed. Clients should refrain from cleaning
         * up their UI for the {@code Call} in response to state transitions. Specifically,
         * clients should not assume that a {@link #onStateChanged(Call, int)} with a state of
         * {@link #STATE_DISCONNECTED} is the final notification the {@code Call} will send. Rather,
         * clients should wait for this method to be invoked.
         *
         * @param call The {@code Call} being destroyed.
         */
        public void onCallDestroyed(Call call) {}

        /**
         * Invoked upon changes to the set of {@code Call}s with which this {@code Call} can be
         * conferenced.
         *
         * @param call The {@code Call} being updated.
         * @param conferenceableCalls The {@code Call}s with which this {@code Call} can be
         *          conferenced.
         */
        public void onConferenceableCallsChanged(Call call, List<Call> conferenceableCalls) {}

        /**
         * Invoked when a {@link Call} receives an event from its associated {@link Connection} or
         * {@link Conference}.
         * <p>
         * Where possible, the Call should make an attempt to handle {@link Connection} events which
         * are part of the {@code android.telecom.*} namespace.  The Call should ignore any events
         * it does not wish to handle.  Unexpected events should be handled gracefully, as it is
         * possible that a {@link ConnectionService} has defined its own Connection events which a
         * Call is not aware of.
         * <p>
         * See {@link Connection#sendConnectionEvent(String, Bundle)},
         * {@link Conference#sendConferenceEvent(String, Bundle)}.
         *
         * @param call The {@code Call} receiving the event.
         * @param event The event.
         * @param extras Extras associated with the connection event.
         */
        public void onConnectionEvent(Call call, String event, Bundle extras) {}

        /**
         * Invoked when the RTT mode changes for this call.
         * @param call The call whose RTT mode has changed.
         * @param mode the new RTT mode, one of
         * {@link RttCall#RTT_MODE_FULL}, {@link RttCall#RTT_MODE_HCO},
         *             or {@link RttCall#RTT_MODE_VCO}
         */
        public void onRttModeChanged(Call call, int mode) {}

        /**
         * Invoked when the call's RTT status changes, either from off to on or from on to off.
         * @param call The call whose RTT status has changed.
         * @param enabled whether RTT is now enabled or disabled
         * @param rttCall the {@link RttCall} object to use for reading and writing if RTT is now
         *                on, null otherwise.
         */
        public void onRttStatusChanged(Call call, boolean enabled, RttCall rttCall) {}

        /**
         * Invoked when the remote end of the connection has requested that an RTT communication
         * channel be opened. A response to this should be sent via {@link #respondToRttRequest}
         * with the same ID that this method is invoked with.
         * @param call The call which the RTT request was placed on
         * @param id The ID of the request.
         */
        public void onRttRequest(Call call, int id) {}

        /**
         * Invoked when the RTT session failed to initiate for some reason, including rejection
         * by the remote party.
         * @param call The call which the RTT initiation failure occurred on.
         * @param reason One of the status codes defined in
         *               {@link android.telecom.Connection.RttModifyStatus}, with the exception of
         *               {@link android.telecom.Connection.RttModifyStatus#SESSION_MODIFY_REQUEST_SUCCESS}.
         */
        public void onRttInitiationFailure(Call call, int reason) {}

        /**
         * Invoked when Call handover from one {@link PhoneAccount} to other {@link PhoneAccount}
         * has completed successfully.
         * <p>
         * For a full discussion of the handover process and the APIs involved, see
         * {@link android.telecom.Call#handoverTo(PhoneAccountHandle, int, Bundle)}.
         *
         * @param call The call which had initiated handover.
         */
        public void onHandoverComplete(Call call) {}

        /**
         * Invoked when Call handover from one {@link PhoneAccount} to other {@link PhoneAccount}
         * has failed.
         * <p>
         * For a full discussion of the handover process and the APIs involved, see
         * {@link android.telecom.Call#handoverTo(PhoneAccountHandle, int, Bundle)}.
         *
         * @param call The call which had initiated handover.
         * @param failureReason Error reason for failure.
         */
        public void onHandoverFailed(Call call, @HandoverFailureErrors int failureReason) {}
    }

    /**
     * A class that holds the state that describes the state of the RTT channel to the remote
     * party, if it is active.
     */
    public static final class RttCall {
        /** @hide */
        @Retention(RetentionPolicy.SOURCE)
        @IntDef({RTT_MODE_INVALID, RTT_MODE_FULL, RTT_MODE_HCO, RTT_MODE_VCO})
        public @interface RttAudioMode {}

        /**
         * For metrics use. Default value in the proto.
         * @hide
         */
        public static final int RTT_MODE_INVALID = 0;

        /**
         * Indicates that there should be a bidirectional audio stream between the two parties
         * on the call.
         */
        public static final int RTT_MODE_FULL = 1;

        /**
         * Indicates that the local user should be able to hear the audio stream from the remote
         * user, but not vice versa. Equivalent to muting the microphone.
         */
        public static final int RTT_MODE_HCO = 2;

        /**
         * Indicates that the remote user should be able to hear the audio stream from the local
         * user, but not vice versa. Equivalent to setting the volume to zero.
         */
        public static final int RTT_MODE_VCO = 3;

        private static final int READ_BUFFER_SIZE = 1000;

        private InputStreamReader mReceiveStream;
        private OutputStreamWriter mTransmitStream;
        private int mRttMode;
        private final InCallAdapter mInCallAdapter;
        private final String mTelecomCallId;
        private char[] mReadBuffer = new char[READ_BUFFER_SIZE];

        /**
         * @hide
         */
        public RttCall(String telecomCallId, InputStreamReader receiveStream,
                OutputStreamWriter transmitStream, int mode, InCallAdapter inCallAdapter) {
            mTelecomCallId = telecomCallId;
            mReceiveStream = receiveStream;
            mTransmitStream = transmitStream;
            mRttMode = mode;
            mInCallAdapter = inCallAdapter;
        }

        /**
         * Returns the current RTT audio mode.
         * @return Current RTT audio mode. One of {@link #RTT_MODE_FULL}, {@link #RTT_MODE_VCO}, or
         * {@link #RTT_MODE_HCO}.
         */
        public int getRttAudioMode() {
            return mRttMode;
        }

        /**
         * Sets the RTT audio mode. The requested mode change will be communicated through
         * {@link Callback#onRttModeChanged(Call, int)}.
         * @param mode The desired RTT audio mode, one of {@link #RTT_MODE_FULL},
         * {@link #RTT_MODE_VCO}, or {@link #RTT_MODE_HCO}.
         */
        public void setRttMode(@RttAudioMode int mode) {
            mInCallAdapter.setRttMode(mTelecomCallId, mode);
        }

        /**
         * Writes the string {@param input} into the outgoing text stream for this RTT call. Since
         * RTT transmits text in real-time, this method should be called once for each character
         * the user enters into the device.
         *
         * This method is not thread-safe -- calling it from multiple threads simultaneously may
         * lead to interleaved text.
         * @param input The message to send to the remote user.
         */
        public void write(String input) throws IOException {
            mTransmitStream.write(input);
            mTransmitStream.flush();
        }

        /**
         * Reads a string from the remote user, blocking if there is no data available. Returns
         * {@code null} if the RTT conversation has been terminated and there is no further data
         * to read.
         *
         * This method is not thread-safe -- calling it from multiple threads simultaneously may
         * lead to interleaved text.
         * @return A string containing text sent by the remote user, or {@code null} if the
         * conversation has been terminated or if there was an error while reading.
         */
        public String read() {
            try {
                int numRead = mReceiveStream.read(mReadBuffer, 0, READ_BUFFER_SIZE);
                if (numRead < 0) {
                    return null;
                }
                return new String(mReadBuffer, 0, numRead);
            } catch (IOException e) {
                Log.w(this, "Exception encountered when reading from InputStreamReader: %s", e);
                return null;
            }
        }

        /**
         * Non-blocking version of {@link #read()}. Returns {@code null} if there is nothing to
         * be read.
         * @return A string containing text entered by the user, or {@code null} if the user has
         * not entered any new text yet.
         */
        public String readImmediately() throws IOException {
            if (mReceiveStream.ready()) {
                int numRead = mReceiveStream.read(mReadBuffer, 0, READ_BUFFER_SIZE);
                if (numRead < 0) {
                    return null;
                }
                return new String(mReadBuffer, 0, numRead);
            } else {
                return null;
            }
        }

        /**
         * Closes the underlying file descriptors
         * @hide
         */
        public void close() {
            try {
                mReceiveStream.close();
            } catch (IOException e) {
                // ignore
            }
            try {
                mTransmitStream.close();
            } catch (IOException e) {
                // ignore
            }
        }
    }

    /**
     * @deprecated Use {@code Call.Callback} instead.
     * @hide
     */
    @Deprecated
    @SystemApi
    public static abstract class Listener extends Callback { }

    private final Phone mPhone;
    private final String mTelecomCallId;
    private final InCallAdapter mInCallAdapter;
    private final List<String> mChildrenIds = new ArrayList<>();
    private final List<Call> mChildren = new ArrayList<>();
    private final List<Call> mUnmodifiableChildren = Collections.unmodifiableList(mChildren);
    private final List<CallbackRecord<Callback>> mCallbackRecords = new CopyOnWriteArrayList<>();
    private final List<Call> mConferenceableCalls = new ArrayList<>();
    private final List<Call> mUnmodifiableConferenceableCalls =
            Collections.unmodifiableList(mConferenceableCalls);

    private boolean mChildrenCached;
    private String mParentId = null;
    private String mActiveGenericConferenceChild = null;
    private int mState;
    private List<String> mCannedTextResponses = null;
    private String mCallingPackage;
    private int mTargetSdkVersion;
    private String mRemainingPostDialSequence;
    private VideoCallImpl mVideoCallImpl;
    private RttCall mRttCall;
    private Details mDetails;
    private Bundle mExtras;

    /**
     * Obtains the post-dial sequence remaining to be emitted by this {@code Call}, if any.
     *
     * @return The remaining post-dial sequence, or {@code null} if there is no post-dial sequence
     * remaining or this {@code Call} is not in a post-dial state.
     */
    public String getRemainingPostDialSequence() {
        return mRemainingPostDialSequence;
    }

    /**
     * Instructs this {@link #STATE_RINGING} {@code Call} to answer.
     * @param videoState The video state in which to answer the call.
     */
    public void answer(@VideoProfile.VideoState int videoState) {
        mInCallAdapter.answerCall(mTelecomCallId, videoState);
    }

    /**
     * Instructs this {@link #STATE_RINGING} {@code Call} to deflect.
     *
     * @param address The address to which the call will be deflected.
     */
    public void deflect(Uri address) {
        mInCallAdapter.deflectCall(mTelecomCallId, address);
    }

    /**
     * Instructs this {@link #STATE_RINGING} {@code Call} to reject.
     *
     * @param rejectWithMessage Whether to reject with a text message.
     * @param textMessage An optional text message with which to respond.
     */
    public void reject(boolean rejectWithMessage, String textMessage) {
        mInCallAdapter.rejectCall(mTelecomCallId, rejectWithMessage, textMessage);
    }

    /**
     * Instructs the {@link ConnectionService} providing this {@link #STATE_RINGING} call that the
     * user has chosen to reject the call and has indicated a reason why the call is being rejected.
     *
     * @param rejectReason the reason the call is being rejected.
     */
    public void reject(@RejectReason int rejectReason) {
        mInCallAdapter.rejectCall(mTelecomCallId, rejectReason);
    }

    /**
     * Instructs this {@code Call} to be transferred to another number.
     *
     * @param targetNumber The address to which the call will be transferred.
     * @param isConfirmationRequired if {@code true} it will initiate ASSURED transfer,
     * if {@code false}, it will initiate BLIND transfer.
     *
     * @hide
     */
    public void transfer(@NonNull Uri targetNumber, boolean isConfirmationRequired) {
        mInCallAdapter.transferCall(mTelecomCallId, targetNumber, isConfirmationRequired);
    }

    /**
     * Instructs this {@code Call} to be transferred to another ongoing call.
     * This will initiate CONSULTATIVE transfer.
     * @param toCall The other ongoing {@code Call} to which this call will be transferred.
     *
     * @hide
     */
    public void transfer(@NonNull android.telecom.Call toCall) {
        mInCallAdapter.transferCall(mTelecomCallId, toCall.mTelecomCallId);
    }

    /**
     * Instructs this {@code Call} to disconnect.
     */
    public void disconnect() {
        mInCallAdapter.disconnectCall(mTelecomCallId);
    }

    /**
     * Instructs this {@code Call} to go on hold.
     */
    public void hold() {
        mInCallAdapter.holdCall(mTelecomCallId);
    }

    /**
     * Instructs this {@link #STATE_HOLDING} call to release from hold.
     */
    public void unhold() {
        mInCallAdapter.unholdCall(mTelecomCallId);
    }

    /**
     * Instructs Telecom to put the call into the background audio processing state.
     * <p>
     * This method can be called either when the call is in {@link #STATE_RINGING} or
     * {@link #STATE_ACTIVE}. After Telecom acknowledges the request by setting the call's state to
     * {@link #STATE_AUDIO_PROCESSING}, your app may setup the audio paths with the audio stack in
     * order to capture and play audio on the call stream.
     * <p>
     * This method can only be called by the default dialer app.
     * <p>
     * Apps built with SDK version {@link android.os.Build.VERSION_CODES#R} or later which are using
     * the microphone as part of audio processing should specify the foreground service type using
     * the attribute {@link android.R.attr#foregroundServiceType} in the {@link InCallService}
     * service element of the app's manifest file.
     * The {@link ServiceInfo#FOREGROUND_SERVICE_TYPE_MICROPHONE} attribute should be specified.
     * @see <a href="https://developer.android.com/preview/privacy/foreground-service-types">
     * the Android Developer Site</a> for more information.
     * @hide
     */
    @SystemApi
    @TestApi
    public void enterBackgroundAudioProcessing() {
        if (mState != STATE_ACTIVE && mState != STATE_RINGING) {
            throw new IllegalStateException("Call must be active or ringing");
        }
        mInCallAdapter.enterBackgroundAudioProcessing(mTelecomCallId);
    }

    /**
     * Instructs Telecom to come out of the background audio processing state requested by
     * {@link #enterBackgroundAudioProcessing()} or from the call screening service.
     *
     * This method can only be called when the call is in {@link #STATE_AUDIO_PROCESSING}.
     *
     * @param shouldRing If true, Telecom will put the call into the
     *                   {@link #STATE_SIMULATED_RINGING} state and notify other apps that there is
     *                   a ringing call. Otherwise, the call will go into {@link #STATE_ACTIVE}
     *                   immediately.
     * @hide
     */
    @SystemApi
    @TestApi
    public void exitBackgroundAudioProcessing(boolean shouldRing) {
        if (mState != STATE_AUDIO_PROCESSING) {
            throw new IllegalStateException("Call must in the audio processing state");
        }
        mInCallAdapter.exitBackgroundAudioProcessing(mTelecomCallId, shouldRing);
    }

    /**
     * Instructs this {@code Call} to play a dual-tone multi-frequency signaling (DTMF) tone.
     *
     * Any other currently playing DTMF tone in the specified call is immediately stopped.
     *
     * @param digit A character representing the DTMF digit for which to play the tone. This
     *         value must be one of {@code '0'} through {@code '9'}, {@code '*'} or {@code '#'}.
     */
    public void playDtmfTone(char digit) {
        mInCallAdapter.playDtmfTone(mTelecomCallId, digit);
    }

    /**
     * Instructs this {@code Call} to stop any dual-tone multi-frequency signaling (DTMF) tone
     * currently playing.
     *
     * DTMF tones are played by calling {@link #playDtmfTone(char)}. If no DTMF tone is
     * currently playing, this method will do nothing.
     */
    public void stopDtmfTone() {
        mInCallAdapter.stopDtmfTone(mTelecomCallId);
    }

    /**
     * Instructs this {@code Call} to continue playing a post-dial DTMF string.
     *
     * A post-dial DTMF string is a string of digits entered after a phone number, when dialed,
     * that are immediately sent as DTMF tones to the recipient as soon as the connection is made.
     *
     * If the DTMF string contains a {@link TelecomManager#DTMF_CHARACTER_PAUSE} symbol, this
     * {@code Call} will temporarily pause playing the tones for a pre-defined period of time.
     *
     * If the DTMF string contains a {@link TelecomManager#DTMF_CHARACTER_WAIT} symbol, this
     * {@code Call} will pause playing the tones and notify callbacks via
     * {@link Callback#onPostDialWait(Call, String)}. At this point, the in-call app
     * should display to the user an indication of this state and an affordance to continue
     * the postdial sequence. When the user decides to continue the postdial sequence, the in-call
     * app should invoke the {@link #postDialContinue(boolean)} method.
     *
     * @param proceed Whether or not to continue with the post-dial sequence.
     */
    public void postDialContinue(boolean proceed) {
        mInCallAdapter.postDialContinue(mTelecomCallId, proceed);
    }

    /**
     * Notifies this {@code Call} that an account has been selected and to proceed with placing
     * an outgoing call. Optionally sets this account as the default account.
     */
    public void phoneAccountSelected(PhoneAccountHandle accountHandle, boolean setDefault) {
        mInCallAdapter.phoneAccountSelected(mTelecomCallId, accountHandle, setDefault);

    }

    /**
     * Instructs this {@code Call} to enter a conference.
     *
     * @param callToConferenceWith The other call with which to conference.
     */
    public void conference(Call callToConferenceWith) {
        if (callToConferenceWith != null) {
            mInCallAdapter.conference(mTelecomCallId, callToConferenceWith.mTelecomCallId);
        }
    }

    /**
     * Instructs this {@code Call} to split from any conference call with which it may be
     * connected.
     */
    public void splitFromConference() {
        mInCallAdapter.splitFromConference(mTelecomCallId);
    }

    /**
     * Merges the calls within this conference. See {@link Details#CAPABILITY_MERGE_CONFERENCE}.
     */
    public void mergeConference() {
        mInCallAdapter.mergeConference(mTelecomCallId);
    }

    /**
     * Swaps the calls within this conference. See {@link Details#CAPABILITY_SWAP_CONFERENCE}.
     */
    public void swapConference() {
        mInCallAdapter.swapConference(mTelecomCallId);
    }

    /**
     * Pulls participants to existing call by forming a conference call.
     * See {@link Details#CAPABILITY_ADD_PARTICIPANT}.
     *
     * @param participants participants to be pulled to existing call.
     * @hide
     */
    public void addConferenceParticipants(@NonNull List<Uri> participants) {
        mInCallAdapter.addConferenceParticipants(mTelecomCallId, participants);
    }

    /**
     * Initiates a request to the {@link ConnectionService} to pull an external call to the local
     * device.
     * <p>
     * Calls to this method are ignored if the call does not have the
     * {@link Call.Details#PROPERTY_IS_EXTERNAL_CALL} property set.
     * <p>
     * An {@link InCallService} will only see calls which support this method if it has the
     * {@link TelecomManager#METADATA_INCLUDE_EXTERNAL_CALLS} metadata set to {@code true}
     * in its manifest.
     */
    public void pullExternalCall() {
        // If this isn't an external call, ignore the request.
        if (!mDetails.hasProperty(Details.PROPERTY_IS_EXTERNAL_CALL)) {
            return;
        }

        mInCallAdapter.pullExternalCall(mTelecomCallId);
    }

    /**
     * Sends a {@code Call} event from this {@code Call} to the associated {@link Connection} in
     * the {@link ConnectionService}.
     * <p>
     * Call events are used to communicate point in time information from an {@link InCallService}
     * to a {@link ConnectionService}.  A {@link ConnectionService} implementation could define
     * events which enable the {@link InCallService}, for example, toggle a unique feature of the
     * {@link ConnectionService}.
     * <p>
     * A {@link ConnectionService} can communicate to the {@link InCallService} using
     * {@link Connection#sendConnectionEvent(String, Bundle)}.
     * <p>
     * Events are exposed to {@link ConnectionService} implementations via
     * {@link android.telecom.Connection#onCallEvent(String, Bundle)}.
     * <p>
     * No assumptions should be made as to how a {@link ConnectionService} will handle these events.
     * The {@link InCallService} must assume that the {@link ConnectionService} could chose to
     * ignore some events altogether.
     * <p>
     * Events should be fully qualified (e.g., {@code com.example.event.MY_EVENT}) to avoid
     * conflicts between {@link InCallService} implementations.  Further, {@link InCallService}
     * implementations shall not re-purpose events in the {@code android.*} namespace, nor shall
     * they define their own event types in this namespace.  When defining a custom event type,
     * ensure the contents of the extras {@link Bundle} is clearly defined.  Extra keys for this
     * bundle should be named similar to the event type (e.g. {@code com.example.extra.MY_EXTRA}).
     * <p>
     * When defining events and the associated extras, it is important to keep their behavior
     * consistent when the associated {@link InCallService} is updated.  Support for deprecated
     * events/extras should me maintained to ensure backwards compatibility with older
     * {@link ConnectionService} implementations which were built to support the older behavior.
     *
     * @param event The connection event.
     * @param extras Bundle containing extra information associated with the event.
     */
    public void sendCallEvent(String event, Bundle extras) {
        mInCallAdapter.sendCallEvent(mTelecomCallId, event, mTargetSdkVersion, extras);
    }

    /**
     * Sends an RTT upgrade request to the remote end of the connection. Success is not
     * guaranteed, and notification of success will be via the
     * {@link Callback#onRttStatusChanged(Call, boolean, RttCall)} callback.
     */
    public void sendRttRequest() {
        mInCallAdapter.sendRttRequest(mTelecomCallId);
    }

    /**
     * Responds to an RTT request received via the {@link Callback#onRttRequest(Call, int)} )}
     * callback.
     * The ID used here should be the same as the ID that was received via the callback.
     * @param id The request ID received via {@link Callback#onRttRequest(Call, int)}
     * @param accept {@code true} if the RTT request should be accepted, {@code false} otherwise.
     */
    public void respondToRttRequest(int id, boolean accept) {
        mInCallAdapter.respondToRttRequest(mTelecomCallId, id, accept);
    }

    /**
     * Initiates a handover of this {@link Call} to the {@link ConnectionService} identified
     * by {@code toHandle}.  The videoState specified indicates the desired video state after the
     * handover.
     * <p>
     * A call handover is the process where an ongoing call is transferred from one app (i.e.
     * {@link ConnectionService} to another app.  The user could, for example, choose to continue a
     * mobile network call in a video calling app.  The mobile network call via the Telephony stack
     * is referred to as the source of the handover, and the video calling app is referred to as the
     * destination.
     * <p>
     * When considering a handover scenario the device this method is called on is considered the
     * <em>initiating</em> device (since the user initiates the handover from this device), and the
     * other device is considered the <em>receiving</em> device.
     * <p>
     * When this method is called on the <em>initiating</em> device, the Telecom framework will bind
     * to the {@link ConnectionService} defined by the {@code toHandle} {@link PhoneAccountHandle}
     * and invoke
     * {@link ConnectionService#onCreateOutgoingHandoverConnection(PhoneAccountHandle,
     * ConnectionRequest)} to inform the destination app that a request has been made to handover a
     * call to it.  The app returns an instance of {@link Connection} to represent the handover call
     * At this point the app should display UI to indicate to the user that a call
     * handover is in process.
     * <p>
     * The destination app is responsible for communicating the handover request from the
     * <em>initiating</em> device to the <em>receiving</em> device.
     * <p>
     * When the app on the <em>receiving</em> device receives the handover request, it calls
     * {@link TelecomManager#acceptHandover(Uri, int, PhoneAccountHandle)} to continue the handover
     * process from the <em>initiating</em> device to the <em>receiving</em> device.  At this point
     * the destination app on the <em>receiving</em> device should show UI to allow the user to
     * choose whether they want to continue their call in the destination app.
     * <p>
     * When the destination app on the <em>receiving</em> device calls
     * {@link TelecomManager#acceptHandover(Uri, int, PhoneAccountHandle)}, Telecom will bind to its
     * {@link ConnectionService} and call
     * {@link ConnectionService#onCreateIncomingHandoverConnection(PhoneAccountHandle,
     * ConnectionRequest)} to inform it of the handover request.  The app returns an instance of
     * {@link Connection} to represent the handover call.
     * <p>
     * If the user of the <em>receiving</em> device accepts the handover, the app calls
     * {@link Connection#setActive()} to complete the handover process; Telecom will disconnect the
     * original call.  If the user rejects the handover, the app calls
     * {@link Connection#setDisconnected(DisconnectCause)} and specifies a {@link DisconnectCause}
     * of {@link DisconnectCause#CANCELED} to indicate that the handover has been cancelled.
     * <p>
     * Telecom will only allow handovers from {@link PhoneAccount}s which declare
     * {@link PhoneAccount#EXTRA_SUPPORTS_HANDOVER_FROM}.  Similarly, the {@link PhoneAccount}
     * specified by {@code toHandle} must declare {@link PhoneAccount#EXTRA_SUPPORTS_HANDOVER_TO}.
     * <p>
     * Errors in the handover process are reported to the {@link InCallService} via
     * {@link Callback#onHandoverFailed(Call, int)}.  Errors in the handover process are reported to
     * the involved {@link ConnectionService}s via
     * {@link ConnectionService#onHandoverFailed(ConnectionRequest, int)}.
     *
     * @param toHandle {@link PhoneAccountHandle} of the {@link ConnectionService} to handover
     *                 this call to.
     * @param videoState Indicates the video state desired after the handover (see the
     *               {@code STATE_*} constants defined in {@link VideoProfile}).
     * @param extras Bundle containing extra information to be passed to the
     *               {@link ConnectionService}
     */
    public void handoverTo(PhoneAccountHandle toHandle, @VideoProfile.VideoState int videoState,
            Bundle extras) {
        mInCallAdapter.handoverTo(mTelecomCallId, toHandle, videoState, extras);
    }

    /**
     * Terminate the RTT session on this call. The resulting state change will be notified via
     * the {@link Callback#onRttStatusChanged(Call, boolean, RttCall)} callback.
     */
    public void stopRtt() {
        mInCallAdapter.stopRtt(mTelecomCallId);
    }

    /**
     * Adds some extras to this {@link Call}.  Existing keys are replaced and new ones are
     * added.
     * <p>
     * No assumptions should be made as to how an In-Call UI or service will handle these
     * extras.  Keys should be fully qualified (e.g., com.example.MY_EXTRA) to avoid conflicts.
     *
     * @param extras The extras to add.
     */
    public final void putExtras(Bundle extras) {
        if (extras == null) {
            return;
        }

        if (mExtras == null) {
            mExtras = new Bundle();
        }
        mExtras.putAll(extras);
        mInCallAdapter.putExtras(mTelecomCallId, extras);
    }

    /**
     * Adds a boolean extra to this {@link Call}.
     *
     * @param key The extra key.
     * @param value The value.
     * @hide
     */
    public final void putExtra(String key, boolean value) {
        if (mExtras == null) {
            mExtras = new Bundle();
        }
        mExtras.putBoolean(key, value);
        mInCallAdapter.putExtra(mTelecomCallId, key, value);
    }

    /**
     * Adds an integer extra to this {@link Call}.
     *
     * @param key The extra key.
     * @param value The value.
     * @hide
     */
    public final void putExtra(String key, int value) {
        if (mExtras == null) {
            mExtras = new Bundle();
        }
        mExtras.putInt(key, value);
        mInCallAdapter.putExtra(mTelecomCallId, key, value);
    }

    /**
     * Adds a string extra to this {@link Call}.
     *
     * @param key The extra key.
     * @param value The value.
     * @hide
     */
    public final void putExtra(String key, String value) {
        if (mExtras == null) {
            mExtras = new Bundle();
        }
        mExtras.putString(key, value);
        mInCallAdapter.putExtra(mTelecomCallId, key, value);
    }

    /**
     * Removes extras from this {@link Call}.
     *
     * @param keys The keys of the extras to remove.
     */
    public final void removeExtras(List<String> keys) {
        if (mExtras != null) {
            for (String key : keys) {
                mExtras.remove(key);
            }
            if (mExtras.size() == 0) {
                mExtras = null;
            }
        }
        mInCallAdapter.removeExtras(mTelecomCallId, keys);
    }

    /**
     * Removes extras from this {@link Call}.
     *
     * @param keys The keys of the extras to remove.
     */
    public final void removeExtras(String ... keys) {
        removeExtras(Arrays.asList(keys));
    }

    /**
     * Obtains the parent of this {@code Call} in a conference, if any.
     *
     * @return The parent {@code Call}, or {@code null} if this {@code Call} is not a
     * child of any conference {@code Call}s.
     */
    public Call getParent() {
        if (mParentId != null) {
            return mPhone.internalGetCallByTelecomId(mParentId);
        }
        return null;
    }

    /**
     * Obtains the children of this conference {@code Call}, if any.
     *
     * @return The children of this {@code Call} if this {@code Call} is a conference, or an empty
     * {@code List} otherwise.
     */
    public List<Call> getChildren() {
        if (!mChildrenCached) {
            mChildrenCached = true;
            mChildren.clear();

            for(String id : mChildrenIds) {
                Call call = mPhone.internalGetCallByTelecomId(id);
                if (call == null) {
                    // At least one child was still not found, so do not save true for "cached"
                    mChildrenCached = false;
                } else {
                    mChildren.add(call);
                }
            }
        }

        return mUnmodifiableChildren;
    }

    /**
     * Returns the list of {@code Call}s with which this {@code Call} is allowed to conference.
     *
     * @return The list of conferenceable {@code Call}s.
     */
    public List<Call> getConferenceableCalls() {
        return mUnmodifiableConferenceableCalls;
    }

    /**
     * Obtains the state of this {@code Call}.
     *
     * @return A state value, chosen from the {@code STATE_*} constants.
     */
    public int getState() {
        return mState;
    }

    /**
     * Returns the child {@link Call} in a generic conference that is currently active.
     *
     * A "generic conference" is the mechanism used to support two simultaneous calls on a device
     * in CDMA networks. It is effectively equivalent to having one call active and one call on hold
     * in GSM or IMS calls. This method returns the currently active call.
     *
     * In a generic conference, the network exposes the conference to us as a single call, and we
     * switch between talking to the two participants using a CDMA flash command. Since the network
     * exposes no additional information about the call, the only way we know which caller we're
     * currently talking to is by keeping track of the flash commands that we've sent to the
     * network.
     *
     * For calls that are not generic conferences, or when the generic conference has more than
     * 2 children, returns {@code null}.
     * @see Details#PROPERTY_GENERIC_CONFERENCE
     * @return The active child call.
     */
    public @Nullable Call getGenericConferenceActiveChildCall() {
        if (mActiveGenericConferenceChild != null) {
            return mPhone.internalGetCallByTelecomId(mActiveGenericConferenceChild);
        }
        return null;
    }

    /**
     * Obtains a list of canned, pre-configured message responses to present to the user as
     * ways of rejecting this {@code Call} using via a text message.
     *
     * @see #reject(boolean, String)
     *
     * @return A list of canned text message responses.
     */
    public List<String> getCannedTextResponses() {
        return mCannedTextResponses;
    }

    /**
     * Obtains an object that can be used to display video from this {@code Call}.
     *
     * @return An {@code Call.VideoCall}.
     */
    public InCallService.VideoCall getVideoCall() {
        return mVideoCallImpl;
    }

    /**
     * Obtains an object containing call details.
     *
     * @return A {@link Details} object. Depending on the state of the {@code Call}, the
     * result may be {@code null}.
     */
    public Details getDetails() {
        return mDetails;
    }

    /**
     * Returns this call's RttCall object. The {@link RttCall} instance is used to send and
     * receive RTT text data, as well as to change the RTT mode.
     * @return A {@link Call.RttCall}. {@code null} if there is no active RTT connection.
     */
    public @Nullable RttCall getRttCall() {
        return mRttCall;
    }

    /**
     * Returns whether this call has an active RTT connection.
     * @return true if there is a connection, false otherwise.
     */
    public boolean isRttActive() {
        return mRttCall != null && mDetails.hasProperty(Details.PROPERTY_RTT);
    }

    /**
     * Registers a callback to this {@code Call}.
     *
     * @param callback A {@code Callback}.
     */
    public void registerCallback(Callback callback) {
        registerCallback(callback, new Handler());
    }

    /**
     * Registers a callback to this {@code Call}.
     *
     * @param callback A {@code Callback}.
     * @param handler A handler which command and status changes will be delivered to.
     */
    public void registerCallback(Callback callback, Handler handler) {
        unregisterCallback(callback);
        // Don't allow new callback registration if the call is already being destroyed.
        if (callback != null && handler != null && mState != STATE_DISCONNECTED) {
            mCallbackRecords.add(new CallbackRecord<Callback>(callback, handler));
        }
    }

    /**
     * Unregisters a callback from this {@code Call}.
     *
     * @param callback A {@code Callback}.
     */
    public void unregisterCallback(Callback callback) {
        // Don't allow callback deregistration if the call is already being destroyed.
        if (callback != null && mState != STATE_DISCONNECTED) {
            for (CallbackRecord<Callback> record : mCallbackRecords) {
                if (record.getCallback() == callback) {
                    mCallbackRecords.remove(record);
                    break;
                }
            }
        }
    }

    @Override
    public String toString() {
        return new StringBuilder().
                append("Call [id: ").
                append(mTelecomCallId).
                append(", state: ").
                append(stateToString(mState)).
                append(", details: ").
                append(mDetails).
                append("]").toString();
    }

    /**
     * @param state An integer value of a {@code STATE_*} constant.
     * @return A string representation of the value.
     */
    private static String stateToString(int state) {
        switch (state) {
            case STATE_NEW:
                return "NEW";
            case STATE_RINGING:
                return "RINGING";
            case STATE_DIALING:
                return "DIALING";
            case STATE_ACTIVE:
                return "ACTIVE";
            case STATE_HOLDING:
                return "HOLDING";
            case STATE_DISCONNECTED:
                return "DISCONNECTED";
            case STATE_CONNECTING:
                return "CONNECTING";
            case STATE_DISCONNECTING:
                return "DISCONNECTING";
            case STATE_SELECT_PHONE_ACCOUNT:
                return "SELECT_PHONE_ACCOUNT";
            case STATE_SIMULATED_RINGING:
                return "SIMULATED_RINGING";
            case STATE_AUDIO_PROCESSING:
                return "AUDIO_PROCESSING";
            default:
                Log.w(Call.class, "Unknown state %d", state);
                return "UNKNOWN";
        }
    }

    /**
     * Adds a listener to this {@code Call}.
     *
     * @param listener A {@code Listener}.
     * @deprecated Use {@link #registerCallback} instead.
     * @hide
     */
    @Deprecated
    @SystemApi
    public void addListener(Listener listener) {
        registerCallback(listener);
    }

    /**
     * Removes a listener from this {@code Call}.
     *
     * @param listener A {@code Listener}.
     * @deprecated Use {@link #unregisterCallback} instead.
     * @hide
     */
    @Deprecated
    @SystemApi
    public void removeListener(Listener listener) {
        unregisterCallback(listener);
    }

    /** {@hide} */
    Call(Phone phone, String telecomCallId, InCallAdapter inCallAdapter, String callingPackage,
         int targetSdkVersion) {
        mPhone = phone;
        mTelecomCallId = telecomCallId;
        mInCallAdapter = inCallAdapter;
        mState = STATE_NEW;
        mCallingPackage = callingPackage;
        mTargetSdkVersion = targetSdkVersion;
    }

    /** {@hide} */
    Call(Phone phone, String telecomCallId, InCallAdapter inCallAdapter, int state,
            String callingPackage, int targetSdkVersion) {
        mPhone = phone;
        mTelecomCallId = telecomCallId;
        mInCallAdapter = inCallAdapter;
        mState = state;
        mCallingPackage = callingPackage;
        mTargetSdkVersion = targetSdkVersion;
    }

    /** {@hide} */
    final String internalGetCallId() {
        return mTelecomCallId;
    }

    /** {@hide} */
    final void internalUpdate(ParcelableCall parcelableCall, Map<String, Call> callIdMap) {

        // First, we update the internal state as far as possible before firing any updates.
        Details details = Details.createFromParcelableCall(parcelableCall);
        boolean detailsChanged = !Objects.equals(mDetails, details);
        if (detailsChanged) {
            mDetails = details;
        }

        boolean cannedTextResponsesChanged = false;
        if (mCannedTextResponses == null && parcelableCall.getCannedSmsResponses() != null
                && !parcelableCall.getCannedSmsResponses().isEmpty()) {
            mCannedTextResponses =
                    Collections.unmodifiableList(parcelableCall.getCannedSmsResponses());
            cannedTextResponsesChanged = true;
        }

        IVideoProvider previousVideoProvider = mVideoCallImpl == null ? null :
                mVideoCallImpl.getVideoProvider();
        IVideoProvider newVideoProvider = parcelableCall.getVideoProvider();

        // parcelableCall.isVideoCallProviderChanged is only true when we have a video provider
        // specified; so we should check if the actual IVideoProvider changes as well.
        boolean videoCallChanged = parcelableCall.isVideoCallProviderChanged()
                && !Objects.equals(previousVideoProvider, newVideoProvider);
        if (videoCallChanged) {
            if (mVideoCallImpl != null) {
                mVideoCallImpl.destroy();
            }
            mVideoCallImpl = parcelableCall.isVideoCallProviderChanged() ?
                    parcelableCall.getVideoCallImpl(mCallingPackage, mTargetSdkVersion) : null;
        }

        if (mVideoCallImpl != null) {
            mVideoCallImpl.setVideoState(getDetails().getVideoState());
        }

        int state = parcelableCall.getState();
        if (mTargetSdkVersion < Phone.SDK_VERSION_R && state == Call.STATE_SIMULATED_RINGING) {
            state = Call.STATE_RINGING;
        }
        boolean stateChanged = mState != state;
        if (stateChanged) {
            mState = state;
        }

        String parentId = parcelableCall.getParentCallId();
        boolean parentChanged = !Objects.equals(mParentId, parentId);
        if (parentChanged) {
            mParentId = parentId;
        }

        List<String> childCallIds = parcelableCall.getChildCallIds();
        boolean childrenChanged = !Objects.equals(childCallIds, mChildrenIds);
        if (childrenChanged) {
            mChildrenIds.clear();
            mChildrenIds.addAll(parcelableCall.getChildCallIds());
            mChildrenCached = false;
        }

        String activeChildCallId = parcelableCall.getActiveChildCallId();
        boolean activeChildChanged = !Objects.equals(activeChildCallId,
                mActiveGenericConferenceChild);
        if (activeChildChanged) {
            mActiveGenericConferenceChild = activeChildCallId;
        }

        List<String> conferenceableCallIds = parcelableCall.getConferenceableCallIds();
        List<Call> conferenceableCalls = new ArrayList<Call>(conferenceableCallIds.size());
        for (String otherId : conferenceableCallIds) {
            if (callIdMap.containsKey(otherId)) {
                conferenceableCalls.add(callIdMap.get(otherId));
            }
        }

        if (!Objects.equals(mConferenceableCalls, conferenceableCalls)) {
            mConferenceableCalls.clear();
            mConferenceableCalls.addAll(conferenceableCalls);
            fireConferenceableCallsChanged();
        }

        boolean isRttChanged = false;
        boolean rttModeChanged = false;
        if (parcelableCall.getIsRttCallChanged()
                && mDetails.hasProperty(Details.PROPERTY_RTT)) {
            ParcelableRttCall parcelableRttCall = parcelableCall.getParcelableRttCall();
            InputStreamReader receiveStream = new InputStreamReader(
                    new ParcelFileDescriptor.AutoCloseInputStream(
                            parcelableRttCall.getReceiveStream()),
                    StandardCharsets.UTF_8);
            OutputStreamWriter transmitStream = new OutputStreamWriter(
                    new ParcelFileDescriptor.AutoCloseOutputStream(
                            parcelableRttCall.getTransmitStream()),
                    StandardCharsets.UTF_8);
            RttCall newRttCall = new Call.RttCall(mTelecomCallId,
                    receiveStream, transmitStream, parcelableRttCall.getRttMode(), mInCallAdapter);
            if (mRttCall == null) {
                isRttChanged = true;
            } else if (mRttCall.getRttAudioMode() != newRttCall.getRttAudioMode()) {
                rttModeChanged = true;
            }
            mRttCall = newRttCall;
        } else if (mRttCall != null && parcelableCall.getParcelableRttCall() == null
                && parcelableCall.getIsRttCallChanged()) {
            isRttChanged = true;
            mRttCall = null;
        }

        // Now we fire updates, ensuring that any client who listens to any of these notifications
        // gets the most up-to-date state.

        if (stateChanged) {
            fireStateChanged(mState);
        }
        if (detailsChanged) {
            fireDetailsChanged(mDetails);
        }
        if (cannedTextResponsesChanged) {
            fireCannedTextResponsesLoaded(mCannedTextResponses);
        }
        if (videoCallChanged) {
            fireVideoCallChanged(mVideoCallImpl);
        }
        if (parentChanged) {
            fireParentChanged(getParent());
        }
        if (childrenChanged || activeChildChanged) {
            fireChildrenChanged(getChildren());
        }
        if (isRttChanged) {
            fireOnIsRttChanged(mRttCall != null, mRttCall);
        }
        if (rttModeChanged) {
            fireOnRttModeChanged(mRttCall.getRttAudioMode());
        }

        // If we have transitioned to DISCONNECTED, that means we need to notify clients and
        // remove ourselves from the Phone. Note that we do this after completing all state updates
        // so a client can cleanly transition all their UI to the state appropriate for a
        // DISCONNECTED Call while still relying on the existence of that Call in the Phone's list.
        if (mState == STATE_DISCONNECTED) {
            fireCallDestroyed();
        }
    }

    /** {@hide} */
    final void internalSetPostDialWait(String remaining) {
        mRemainingPostDialSequence = remaining;
        firePostDialWait(mRemainingPostDialSequence);
    }

    /** {@hide} */
    final void internalSetDisconnected() {
        if (mState != Call.STATE_DISCONNECTED) {
            mState = Call.STATE_DISCONNECTED;
            fireStateChanged(mState);
            fireCallDestroyed();
        }
    }

    /** {@hide} */
    final void internalOnConnectionEvent(String event, Bundle extras) {
        fireOnConnectionEvent(event, extras);
    }

    /** {@hide} */
    final void internalOnRttUpgradeRequest(final int requestId) {
        for (CallbackRecord<Callback> record : mCallbackRecords) {
            final Call call = this;
            final Callback callback = record.getCallback();
            record.getHandler().post(() -> callback.onRttRequest(call, requestId));
        }
    }

    /** @hide */
    final void internalOnRttInitiationFailure(int reason) {
        for (CallbackRecord<Callback> record : mCallbackRecords) {
            final Call call = this;
            final Callback callback = record.getCallback();
            record.getHandler().post(() -> callback.onRttInitiationFailure(call, reason));
        }
    }

    /** {@hide} */
    final void internalOnHandoverFailed(int error) {
        for (CallbackRecord<Callback> record : mCallbackRecords) {
            final Call call = this;
            final Callback callback = record.getCallback();
            record.getHandler().post(() -> callback.onHandoverFailed(call, error));
        }
    }

    /** {@hide} */
    final void internalOnHandoverComplete() {
        for (CallbackRecord<Callback> record : mCallbackRecords) {
            final Call call = this;
            final Callback callback = record.getCallback();
            record.getHandler().post(() -> callback.onHandoverComplete(call));
        }
    }

    private void fireStateChanged(final int newState) {
        for (CallbackRecord<Callback> record : mCallbackRecords) {
            final Call call = this;
            final Callback callback = record.getCallback();
            record.getHandler().post(new Runnable() {
                @Override
                public void run() {
                    callback.onStateChanged(call, newState);
                }
            });
        }
    }

    private void fireParentChanged(final Call newParent) {
        for (CallbackRecord<Callback> record : mCallbackRecords) {
            final Call call = this;
            final Callback callback = record.getCallback();
            record.getHandler().post(new Runnable() {
                @Override
                public void run() {
                    callback.onParentChanged(call, newParent);
                }
            });
        }
    }

    private void fireChildrenChanged(final List<Call> children) {
        for (CallbackRecord<Callback> record : mCallbackRecords) {
            final Call call = this;
            final Callback callback = record.getCallback();
            record.getHandler().post(new Runnable() {
                @Override
                public void run() {
                    callback.onChildrenChanged(call, children);
                }
            });
        }
    }

    private void fireDetailsChanged(final Details details) {
        for (CallbackRecord<Callback> record : mCallbackRecords) {
            final Call call = this;
            final Callback callback = record.getCallback();
            record.getHandler().post(new Runnable() {
                @Override
                public void run() {
                    callback.onDetailsChanged(call, details);
                }
            });
        }
    }

    private void fireCannedTextResponsesLoaded(final List<String> cannedTextResponses) {
        for (CallbackRecord<Callback> record : mCallbackRecords) {
            final Call call = this;
            final Callback callback = record.getCallback();
            record.getHandler().post(new Runnable() {
                @Override
                public void run() {
                    callback.onCannedTextResponsesLoaded(call, cannedTextResponses);
                }
            });
        }
    }

    private void fireVideoCallChanged(final InCallService.VideoCall videoCall) {
        for (CallbackRecord<Callback> record : mCallbackRecords) {
            final Call call = this;
            final Callback callback = record.getCallback();
            record.getHandler().post(new Runnable() {
                @Override
                public void run() {
                    callback.onVideoCallChanged(call, videoCall);
                }
            });
        }
    }

    private void firePostDialWait(final String remainingPostDialSequence) {
        for (CallbackRecord<Callback> record : mCallbackRecords) {
            final Call call = this;
            final Callback callback = record.getCallback();
            record.getHandler().post(new Runnable() {
                @Override
                public void run() {
                    callback.onPostDialWait(call, remainingPostDialSequence);
                }
            });
        }
    }

    private void fireCallDestroyed() {
        /**
         * To preserve the ordering of the Call's onCallDestroyed callback and Phone's
         * onCallRemoved callback, we remove this call from the Phone's record
         * only once all of the registered onCallDestroyed callbacks are executed.
         * All the callbacks get removed from our records as a part of this operation
         * since onCallDestroyed is the final callback.
         */
        final Call call = this;
        if (mCallbackRecords.isEmpty()) {
            // No callbacks registered, remove the call from Phone's record.
            mPhone.internalRemoveCall(call);
        }
        for (final CallbackRecord<Callback> record : mCallbackRecords) {
            final Callback callback = record.getCallback();
            record.getHandler().post(new Runnable() {
                @Override
                public void run() {
                    boolean isFinalRemoval = false;
                    RuntimeException toThrow = null;
                    try {
                        callback.onCallDestroyed(call);
                    } catch (RuntimeException e) {
                            toThrow = e;
                    }
                    synchronized(Call.this) {
                        mCallbackRecords.remove(record);
                        if (mCallbackRecords.isEmpty()) {
                            isFinalRemoval = true;
                        }
                    }
                    if (isFinalRemoval) {
                        mPhone.internalRemoveCall(call);
                    }
                    if (toThrow != null) {
                        throw toThrow;
                    }
                }
            });
        }
    }

    private void fireConferenceableCallsChanged() {
        for (CallbackRecord<Callback> record : mCallbackRecords) {
            final Call call = this;
            final Callback callback = record.getCallback();
            record.getHandler().post(new Runnable() {
                @Override
                public void run() {
                    callback.onConferenceableCallsChanged(call, mUnmodifiableConferenceableCalls);
                }
            });
        }
    }

    /**
     * Notifies listeners of an incoming connection event.
     * <p>
     * Connection events are issued via {@link Connection#sendConnectionEvent(String, Bundle)}.
     *
     * @param event
     * @param extras
     */
    private void fireOnConnectionEvent(final String event, final Bundle extras) {
        for (CallbackRecord<Callback> record : mCallbackRecords) {
            final Call call = this;
            final Callback callback = record.getCallback();
            record.getHandler().post(new Runnable() {
                @Override
                public void run() {
                    callback.onConnectionEvent(call, event, extras);
                }
            });
        }
    }

    /**
     * Notifies listeners of an RTT on/off change
     *
     * @param enabled True if RTT is now enabled, false otherwise
     */
    private void fireOnIsRttChanged(final boolean enabled, final RttCall rttCall) {
        for (CallbackRecord<Callback> record : mCallbackRecords) {
            final Call call = this;
            final Callback callback = record.getCallback();
            record.getHandler().post(() -> callback.onRttStatusChanged(call, enabled, rttCall));
        }
    }

    /**
     * Notifies listeners of a RTT mode change
     *
     * @param mode The new RTT mode
     */
    private void fireOnRttModeChanged(final int mode) {
        for (CallbackRecord<Callback> record : mCallbackRecords) {
            final Call call = this;
            final Callback callback = record.getCallback();
            record.getHandler().post(() -> callback.onRttModeChanged(call, mode));
        }
    }

    /**
     * Determines if two bundles are equal.
     *
     * @param bundle The original bundle.
     * @param newBundle The bundle to compare with.
     * @retrun {@code true} if the bundles are equal, {@code false} otherwise.
     */
    private static boolean areBundlesEqual(Bundle bundle, Bundle newBundle) {
        if (bundle == null || newBundle == null) {
            return bundle == newBundle;
        }

        if (bundle.size() != newBundle.size()) {
            return false;
        }

        for(String key : bundle.keySet()) {
            if (key != null) {
                final Object value = bundle.get(key);
                final Object newValue = newBundle.get(key);
                if (!Objects.equals(value, newValue)) {
                    return false;
                }
            }
        }
        return true;
    }
}
