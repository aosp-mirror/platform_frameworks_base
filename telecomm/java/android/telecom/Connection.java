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

import static android.Manifest.permission.MODIFY_PHONE_STATE;

import android.annotation.ElapsedRealtimeLong;
import android.annotation.IntDef;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.app.Notification;
import android.bluetooth.BluetoothDevice;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.Intent;
import android.hardware.camera2.CameraManager;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.SystemClock;
import android.telephony.ims.ImsStreamMediaProfile;
import android.util.ArraySet;
import android.view.Surface;

import com.android.internal.os.SomeArgs;
import com.android.internal.telecom.IVideoCallback;
import com.android.internal.telecom.IVideoProvider;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.nio.channels.Channels;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Represents a phone call or connection to a remote endpoint that carries voice and/or video
 * traffic.
 * <p>
 * Implementations create a custom subclass of {@code Connection} and return it to the framework
 * as the return value of
 * {@link ConnectionService#onCreateIncomingConnection(PhoneAccountHandle, ConnectionRequest)}
 * or
 * {@link ConnectionService#onCreateOutgoingConnection(PhoneAccountHandle, ConnectionRequest)}.
 * Implementations are then responsible for updating the state of the {@code Connection}, and
 * must call {@link #destroy()} to signal to the framework that the {@code Connection} is no
 * longer used and associated resources may be recovered.
 * <p>
 * Subclasses of {@code Connection} override the {@code on*} methods to provide the the
 * {@link ConnectionService}'s implementation of calling functionality.  The {@code on*} methods are
 * called by Telecom to inform an instance of a {@code Connection} of actions specific to that
 * {@code Connection} instance.
 * <p>
 * Basic call support requires overriding the following methods: {@link #onAnswer()},
 * {@link #onDisconnect()}, {@link #onReject()}, {@link #onAbort()}
 * <p>
 * Where a {@code Connection} has {@link #CAPABILITY_SUPPORT_HOLD}, the {@link #onHold()} and
 * {@link #onUnhold()} methods should be overridden to provide hold support for the
 * {@code Connection}.
 * <p>
 * Where a {@code Connection} supports a variation of video calling (e.g. the
 * {@code CAPABILITY_SUPPORTS_VT_*} capability bits), {@link #onAnswer(int)} should be overridden
 * to support answering a call as a video call.
 * <p>
 * Where a {@code Connection} has {@link #PROPERTY_IS_EXTERNAL_CALL} and
 * {@link #CAPABILITY_CAN_PULL_CALL}, {@link #onPullExternalCall()} should be overridden to provide
 * support for pulling the external call.
 * <p>
 * Where a {@code Connection} supports conference calling {@link #onSeparate()} should be
 * overridden.
 * <p>
 * There are a number of other {@code on*} methods which a {@code Connection} can choose to
 * implement, depending on whether it is concerned with the associated calls from Telecom.  If,
 * for example, call events from a {@link InCallService} are handled,
 * {@link #onCallEvent(String, Bundle)} should be overridden.  Another example is
 * {@link #onExtrasChanged(Bundle)}, which should be overridden if the {@code Connection} wishes to
 * make use of extra information provided via the {@link Call#putExtras(Bundle)} and
 * {@link Call#removeExtras(String...)} methods.
 */
public abstract class Connection extends Conferenceable {

    /**
     * The connection is initializing. This is generally the first state for a {@code Connection}
     * returned by a {@link ConnectionService}.
     */
    public static final int STATE_INITIALIZING = 0;

    /**
     * The connection is new and not connected.
     */
    public static final int STATE_NEW = 1;

    /**
     * An incoming connection is in the ringing state. During this state, the user's ringer or
     * vibration feature will be activated.
     */
    public static final int STATE_RINGING = 2;

    /**
     * An outgoing connection is in the dialing state. In this state the other party has not yet
     * answered the call and the user traditionally hears a ringback tone.
     */
    public static final int STATE_DIALING = 3;

    /**
     * A connection is active. Both parties are connected to the call and can actively communicate.
     */
    public static final int STATE_ACTIVE = 4;

    /**
     * A connection is on hold.
     */
    public static final int STATE_HOLDING = 5;

    /**
     * A connection has been disconnected. This is the final state once the user has been
     * disconnected from a call either locally, remotely or by an error in the service.
     */
    public static final int STATE_DISCONNECTED = 6;

    /**
     * The state of an external connection which is in the process of being pulled from a remote
     * device to the local device.
     * <p>
     * A connection can only be in this state if the {@link #PROPERTY_IS_EXTERNAL_CALL} property and
     * {@link #CAPABILITY_CAN_PULL_CALL} capability bits are set on the connection.
     */
    public static final int STATE_PULLING_CALL = 7;

    /**
     * Indicates that the network could not perform verification.
     */
    public static final int VERIFICATION_STATUS_NOT_VERIFIED = 0;

    /**
     * Indicates that verification by the network passed.  This indicates there is a high likelihood
     * that the call originated from a valid source.
     */
    public static final int VERIFICATION_STATUS_PASSED = 1;

    /**
     * Indicates that verification by the network failed.  This indicates there is a high likelihood
     * that the call did not originate from a valid source.
     */
    public static final int VERIFICATION_STATUS_FAILED = 2;

    /**@hide*/
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = "VERIFICATION_STATUS_", value = {
            VERIFICATION_STATUS_NOT_VERIFIED,
            VERIFICATION_STATUS_PASSED,
            VERIFICATION_STATUS_FAILED
    })
    public @interface VerificationStatus {}

    /**
     * Connection can currently be put on hold or unheld. This is distinct from
     * {@link #CAPABILITY_SUPPORT_HOLD} in that although a connection may support 'hold' most times,
     * it does not at the moment support the function. This can be true while the call is in the
     * state {@link #STATE_DIALING}, for example. During this condition, an in-call UI may
     * display a disabled 'hold' button.
     */
    public static final int CAPABILITY_HOLD = 0x00000001;

    /** Connection supports the hold feature. */
    public static final int CAPABILITY_SUPPORT_HOLD = 0x00000002;

    /**
     * Connections within a conference can be merged. A {@link ConnectionService} has the option to
     * add a {@link Conference} before the child {@link Connection}s are merged. This is how
     * CDMA-based {@link Connection}s are implemented. For these unmerged {@link Conference}s, this
     * capability allows a merge button to be shown while the conference is in the foreground
     * of the in-call UI.
     * <p>
     * This is only intended for use by a {@link Conference}.
     */
    public static final int CAPABILITY_MERGE_CONFERENCE = 0x00000004;

    /**
     * Connections within a conference can be swapped between foreground and background.
     * See {@link #CAPABILITY_MERGE_CONFERENCE} for additional information.
     * <p>
     * This is only intended for use by a {@link Conference}.
     */
    public static final int CAPABILITY_SWAP_CONFERENCE = 0x00000008;

    /**
     * @hide
     */
    public static final int CAPABILITY_UNUSED = 0x00000010;

    /** Connection supports responding via text option. */
    public static final int CAPABILITY_RESPOND_VIA_TEXT = 0x00000020;

    /** Connection can be muted. */
    public static final int CAPABILITY_MUTE = 0x00000040;

    /**
     * Connection supports conference management. This capability only applies to
     * {@link Conference}s which can have {@link Connection}s as children.
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
     * Connection is able to be separated from its parent {@code Conference}, if any.
     */
    public static final int CAPABILITY_SEPARATE_FROM_CONFERENCE = 0x00001000;

    /**
     * Connection is able to be individually disconnected when in a {@code Conference}.
     */
    public static final int CAPABILITY_DISCONNECT_FROM_CONFERENCE = 0x00002000;

    /**
     * Un-used.
     * @hide
     */
    public static final int CAPABILITY_UNUSED_2 = 0x00004000;

    /**
     * Un-used.
     * @hide
     */
    public static final int CAPABILITY_UNUSED_3 = 0x00008000;

    /**
     * Un-used.
     * @hide
     */
    public static final int CAPABILITY_UNUSED_4 = 0x00010000;

    /**
     * Un-used.
     * @hide
     */
    public static final int CAPABILITY_UNUSED_5 = 0x00020000;

    /**
     * Speed up audio setup for MT call.
     * <p>
     * Used for IMS calls to indicate that mobile-terminated (incoming) call audio setup should take
     * place as soon as the device answers the call, but prior to it being connected.  This is an
     * optimization some IMS stacks depend on to ensure prompt setup of call audio.
     * @hide
     */
    @SystemApi
    @TestApi
    public static final int CAPABILITY_SPEED_UP_MT_AUDIO = 0x00040000;

    /**
     * Call can be upgraded to a video call.
     * @deprecated Use {@link #CAPABILITY_SUPPORTS_VT_LOCAL_BIDIRECTIONAL} and
     * {@link #CAPABILITY_SUPPORTS_VT_REMOTE_BIDIRECTIONAL} to indicate for a call whether or not
     * video calling is supported.
     */
    public static final int CAPABILITY_CAN_UPGRADE_TO_VIDEO = 0x00080000;

    /**
     * For video calls, indicates whether the outgoing video for the call can be paused using
     * the {@link android.telecom.VideoProfile#STATE_PAUSED} VideoState.
     */
    public static final int CAPABILITY_CAN_PAUSE_VIDEO = 0x00100000;

    /**
     * For a conference, indicates the conference will not have child connections.
     * <p>
     * An example of a conference with child connections is a GSM conference call, where the radio
     * retains connections to the individual participants of the conference.  Another example is an
     * IMS conference call where conference event package functionality is supported; in this case
     * the conference server ensures the radio is aware of the participants in the conference, which
     * are represented by child connections.
     * <p>
     * An example of a conference with no child connections is an IMS conference call with no
     * conference event package support.  Such a conference is represented by the radio as a single
     * connection to the IMS conference server.
     * <p>
     * Indicating whether a conference has children or not is important to help user interfaces
     * visually represent a conference.  A conference with no children, for example, will have the
     * conference connection shown in the list of calls on a Bluetooth device, where if the
     * conference has children, only the children will be shown in the list of calls on a Bluetooth
     * device.
     * @hide
     */
    @SystemApi
    @TestApi
    public static final int CAPABILITY_CONFERENCE_HAS_NO_CHILDREN = 0x00200000;

    /**
     * Indicates that the connection itself wants to handle any sort of reply response, rather than
     * relying on SMS.
     */
    public static final int CAPABILITY_CAN_SEND_RESPONSE_VIA_CONNECTION = 0x00400000;

    /**
     * When set, prevents a video call from being downgraded to an audio-only call.
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
    public static final int CAPABILITY_CANNOT_DOWNGRADE_VIDEO_TO_AUDIO = 0x00800000;

    /**
     * When set for an external connection, indicates that this {@code Connection} can be pulled
     * from a remote device to the current device.
     * <p>
     * Should only be set on a {@code Connection} where {@link #PROPERTY_IS_EXTERNAL_CALL}
     * is set.
     */
    public static final int CAPABILITY_CAN_PULL_CALL = 0x01000000;

    /** Call supports the deflect feature. */
    public static final int CAPABILITY_SUPPORT_DEFLECT = 0x02000000;

    /**
     * When set, indicates that this {@link Connection} supports initiation of a conference call
     * by directly adding participants using {@link #onAddConferenceParticipants(List)}.
     * @hide
     */
    public static final int CAPABILITY_ADD_PARTICIPANT = 0x04000000;

    /**
     * Indicates that this {@code Connection} can be transferred to another
     * number.
     * Connection supports the blind and assured call transfer feature.
     * @hide
     */
    public static final int CAPABILITY_TRANSFER = 0x08000000;

    /**
     * Indicates that this {@code Connection} can be transferred to another
     * ongoing {@code Connection}.
     * Connection supports the consultative call transfer feature.
     * @hide
     */
    public static final int CAPABILITY_TRANSFER_CONSULTATIVE = 0x10000000;

    //**********************************************************************************************
    // Next CAPABILITY value: 0x20000000
    //**********************************************************************************************

    /**
     * Indicates that the current device callback number should be shown.
     * <p>
     * Supports Telephony calls where CDMA emergency callback mode is active.
     * @hide
     */
    @SystemApi
    @TestApi
    public static final int PROPERTY_EMERGENCY_CALLBACK_MODE = 1<<0;

    /**
     * Whether the call is a generic conference, where we do not know the precise state of
     * participants in the conference (eg. on CDMA).
     * <p>
     * Supports legacy telephony CDMA calls.
     * @hide
     */
    @SystemApi
    @TestApi
    public static final int PROPERTY_GENERIC_CONFERENCE = 1<<1;

    /**
     * Connection is using high definition audio.
     * <p>
     * Indicates that the {@link Connection} is using a "high definition" audio codec.  This usually
     * implies something like AMR wideband, but the interpretation of when a call is considered high
     * definition is left to the {@link ConnectionService} to decide.
     * <p>
     * Translates to {@link android.telecom.Call.Details#PROPERTY_HIGH_DEF_AUDIO}.
     */
    public static final int PROPERTY_HIGH_DEF_AUDIO = 1<<2;

    /**
     * Connection is using WIFI.
     * <p>
     * Used to indicate that a call is taking place over WIFI versus a carrier network.
     * <p>
     * Translates to {@link android.telecom.Call.Details#PROPERTY_WIFI}.
     */
    public static final int PROPERTY_WIFI = 1<<3;

    /**
     * When set, indicates that the {@code Connection} does not actually exist locally for the
     * {@link ConnectionService}.
     * <p>
     * Consider, for example, a scenario where a user has two devices with the same phone number.
     * When a user places a call on one devices, the telephony stack can represent that call on the
     * other device by adding is to the {@link ConnectionService} with the
     * {@link #PROPERTY_IS_EXTERNAL_CALL} capability set.
     * <p>
     * An {@link ConnectionService} should not assume that all {@link InCallService}s will handle
     * external connections.  Only those {@link InCallService}s which have the
     * {@link TelecomManager#METADATA_INCLUDE_EXTERNAL_CALLS} metadata set to {@code true} in its
     * manifest will see external connections.
     */
    public static final int PROPERTY_IS_EXTERNAL_CALL = 1<<4;

    /**
     * Indicates that the connection has CDMA Enhanced Voice Privacy enabled.
     */
    public static final int PROPERTY_HAS_CDMA_VOICE_PRIVACY = 1<<5;

    /**
     * Indicates that the connection represents a downgraded IMS conference.
     * <p>
     * This property is set when an IMS conference undergoes SRVCC and is re-added to Telecom as a
     * new entity to indicate that the new connection was a conference.
     * @hide
     */
    @SystemApi
    @TestApi
    public static final int PROPERTY_IS_DOWNGRADED_CONFERENCE = 1<<6;

    /**
     * Set by the framework to indicate that the {@link Connection} originated from a self-managed
     * {@link ConnectionService}.
     * <p>
     * See {@link PhoneAccount#CAPABILITY_SELF_MANAGED}.
     */
    public static final int PROPERTY_SELF_MANAGED = 1<<7;

    /**
     * Set by the framework to indicate that a connection has an active RTT session associated with
     * it.
     */
    public static final int PROPERTY_IS_RTT = 1 << 8;

    /**
     * Set by the framework to indicate that a connection is using assisted dialing.
     * <p>
     * This is used for outgoing calls.
     *
     * @see TelecomManager#EXTRA_USE_ASSISTED_DIALING
     */
    public static final int PROPERTY_ASSISTED_DIALING = 1 << 9;

    /**
     * Set by the framework to indicate that the network has identified a Connection as an emergency
     * call.
     * <p>
     * This is used for incoming (mobile-terminated) calls to indicate the call is from emergency
     * services.
     */
    public static final int PROPERTY_NETWORK_IDENTIFIED_EMERGENCY_CALL = 1 << 10;

    /**
     * Set by the framework to indicate that a Conference or Connection is hosted by a device other
     * than the current one.  Used in scenarios where the conference originator is the remote device
     * and the current device is a participant of that conference.
     * <p>
     * This property is specific to IMS conference calls originating in Telephony.
     * @hide
     */
    @SystemApi
    @TestApi
    public static final int PROPERTY_REMOTELY_HOSTED = 1 << 11;

    /**
     * Set by the framework to indicate that it is an adhoc conference call.
     * <p>
     * This is used for Outgoing and incoming conference calls.
     * @hide
     */
    public static final int PROPERTY_IS_ADHOC_CONFERENCE = 1 << 12;


    //**********************************************************************************************
    // Next PROPERTY value: 1<<13
    //**********************************************************************************************

    /**
     * Indicates that the audio codec is currently not specified or is unknown.
     */
    public static final int AUDIO_CODEC_NONE = ImsStreamMediaProfile.AUDIO_QUALITY_NONE; // 0
    /**
     * Adaptive Multi-rate audio codec.
     */
    public static final int AUDIO_CODEC_AMR = ImsStreamMediaProfile.AUDIO_QUALITY_AMR; // 1
    /**
     * Adaptive Multi-rate wideband audio codec.
     */
    public static final int AUDIO_CODEC_AMR_WB = ImsStreamMediaProfile.AUDIO_QUALITY_AMR_WB; // 2
    /**
     * Qualcomm code-excited linear prediction 13 kilobit audio codec.
     */
    public static final int AUDIO_CODEC_QCELP13K = ImsStreamMediaProfile.AUDIO_QUALITY_QCELP13K; //3
    /**
     * Enhanced Variable Rate Codec.  See 3GPP2 C.S0014-A.
     */
    public static final int AUDIO_CODEC_EVRC = ImsStreamMediaProfile.AUDIO_QUALITY_EVRC; // 4
    /**
     * Enhanced Variable Rate Codec B.  Commonly used on CDMA networks.
     */
    public static final int AUDIO_CODEC_EVRC_B = ImsStreamMediaProfile.AUDIO_QUALITY_EVRC_B; // 5
    /**
     * Enhanced Variable Rate Wideband Codec.  See RFC5188.
     */
    public static final int AUDIO_CODEC_EVRC_WB = ImsStreamMediaProfile.AUDIO_QUALITY_EVRC_WB; // 6
    /**
     * Enhanced Variable Rate Narrowband-Wideband Codec.
     */
    public static final int AUDIO_CODEC_EVRC_NW = ImsStreamMediaProfile.AUDIO_QUALITY_EVRC_NW; // 7
    /**
     * GSM Enhanced Full-Rate audio codec, also known as GSM-EFR, GSM 06.60, or simply EFR.
     */
    public static final int AUDIO_CODEC_GSM_EFR = ImsStreamMediaProfile.AUDIO_QUALITY_GSM_EFR; // 8
    /**
     * GSM Full-Rate audio codec, also known as GSM-FR, GSM 06.10, GSM, or simply FR.
     */
    public static final int AUDIO_CODEC_GSM_FR = ImsStreamMediaProfile.AUDIO_QUALITY_GSM_FR; // 9
    /**
     * GSM Half Rate audio codec.
     */
    public static final int AUDIO_CODEC_GSM_HR = ImsStreamMediaProfile.AUDIO_QUALITY_GSM_HR; // 10
    /**
     * ITU-T G711U audio codec.
     */
    public static final int AUDIO_CODEC_G711U = ImsStreamMediaProfile.AUDIO_QUALITY_G711U; // 11
    /**
     * ITU-T G723 audio codec.
     */
    public static final int AUDIO_CODEC_G723 = ImsStreamMediaProfile.AUDIO_QUALITY_G723; // 12
    /**
     * ITU-T G711A audio codec.
     */
    public static final int AUDIO_CODEC_G711A = ImsStreamMediaProfile.AUDIO_QUALITY_G711A; // 13
    /**
     * ITU-T G722 audio codec.
     */
    public static final int AUDIO_CODEC_G722 = ImsStreamMediaProfile.AUDIO_QUALITY_G722; // 14
    /**
     * ITU-T G711AB audio codec.
     */
    public static final int AUDIO_CODEC_G711AB = ImsStreamMediaProfile.AUDIO_QUALITY_G711AB; // 15
    /**
     * ITU-T G729 audio codec.
     */
    public static final int AUDIO_CODEC_G729 = ImsStreamMediaProfile.AUDIO_QUALITY_G729; // 16
    /**
     * Enhanced Voice Services Narrowband audio codec.  See 3GPP TS 26.441.
     */
    public static final int AUDIO_CODEC_EVS_NB = ImsStreamMediaProfile.AUDIO_QUALITY_EVS_NB; // 17
    /**
     * Enhanced Voice Services Wideband audio codec.  See 3GPP TS 26.441.
     */
    public static final int AUDIO_CODEC_EVS_WB = ImsStreamMediaProfile.AUDIO_QUALITY_EVS_WB; // 18
    /**
     * Enhanced Voice Services Super-Wideband audio codec.  See 3GPP TS 26.441.
     */
    public static final int AUDIO_CODEC_EVS_SWB = ImsStreamMediaProfile.AUDIO_QUALITY_EVS_SWB; // 19
    /**
     * Enhanced Voice Services Fullband audio codec.  See 3GPP TS 26.441.
     */
    public static final int AUDIO_CODEC_EVS_FB = ImsStreamMediaProfile.AUDIO_QUALITY_EVS_FB; // 20

    /**@hide*/
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = "AUDIO_CODEC_", value = {
            AUDIO_CODEC_NONE,
            AUDIO_CODEC_AMR,
            AUDIO_CODEC_AMR_WB,
            AUDIO_CODEC_QCELP13K,
            AUDIO_CODEC_EVRC,
            AUDIO_CODEC_EVRC_B,
            AUDIO_CODEC_EVRC_WB,
            AUDIO_CODEC_EVRC_NW,
            AUDIO_CODEC_GSM_EFR,
            AUDIO_CODEC_GSM_FR,
            AUDIO_CODEC_GSM_HR,
            AUDIO_CODEC_G711U,
            AUDIO_CODEC_G723,
            AUDIO_CODEC_G711A,
            AUDIO_CODEC_G722,
            AUDIO_CODEC_G711AB,
            AUDIO_CODEC_G729,
            AUDIO_CODEC_EVS_NB,
            AUDIO_CODEC_EVS_SWB,
            AUDIO_CODEC_EVS_FB
    })
    public @interface AudioCodec {}

    /**
     * Connection extra key used to store the last forwarded number associated with the current
     * connection.  Used to communicate to the user interface that the connection was forwarded via
     * the specified number.
     */
    public static final String EXTRA_LAST_FORWARDED_NUMBER =
            "android.telecom.extra.LAST_FORWARDED_NUMBER";

    /**
     * Connection extra key used to store a child number associated with the current connection.
     * Used to communicate to the user interface that the connection was received via
     * a child address (i.e. phone number) associated with the {@link PhoneAccount}'s primary
     * address.
     */
    public static final String EXTRA_CHILD_ADDRESS = "android.telecom.extra.CHILD_ADDRESS";

    /**
     * Connection extra key used to store the subject for an incoming call.  The user interface can
     * query this extra and display its contents for incoming calls.  Will only be used if the
     * {@link PhoneAccount} supports the capability {@link PhoneAccount#CAPABILITY_CALL_SUBJECT}.
     */
    public static final String EXTRA_CALL_SUBJECT = "android.telecom.extra.CALL_SUBJECT";

    /**
     * Boolean connection extra key set on a {@link Connection} in
     * {@link Connection#STATE_RINGING} state to indicate that answering the call will cause the
     * current active foreground call to be dropped.
     */
    public static final String EXTRA_ANSWERING_DROPS_FG_CALL =
            "android.telecom.extra.ANSWERING_DROPS_FG_CALL";

    /**
     * String connection extra key set on a {@link Connection} in {@link Connection#STATE_RINGING}
     * state to indicate the name of the third-party app which is responsible for the current
     * foreground call.
     * <p>
     * Used when {@link #EXTRA_ANSWERING_DROPS_FG_CALL} is true to ensure that the default Phone app
     * is able to inform the user that answering the new incoming call will cause a call owned by
     * another app to be dropped when the incoming call is answered.
     */
    public static final String EXTRA_ANSWERING_DROPS_FG_CALL_APP_NAME =
            "android.telecom.extra.ANSWERING_DROPS_FG_CALL_APP_NAME";

    /**
     * Boolean connection extra key on a {@link Connection} which indicates that adding an
     * additional call is disallowed.
     * <p>
     * Used for mobile-network calls to identify scenarios where carrier requirements preclude
     * adding another call at the current time.
     * @hide
     */
    @SystemApi
    @TestApi
    public static final String EXTRA_DISABLE_ADD_CALL =
            "android.telecom.extra.DISABLE_ADD_CALL";

    /**
     * String connection extra key on a {@link Connection} or {@link Conference} which contains the
     * original Connection ID associated with the connection.  Used in
     * {@link RemoteConnectionService} to track the Connection ID which was originally assigned to a
     * connection/conference added via
     * {@link ConnectionService#addExistingConnection(PhoneAccountHandle, Connection)} and
     * {@link ConnectionService#addConference(Conference)} APIs.  This is important to pass to
     * Telecom for when it deals with RemoteConnections.  When the ConnectionManager wraps the
     * {@link RemoteConnection} and {@link RemoteConference} and adds it to Telecom, there needs to
     * be a way to ensure that we don't add the connection again as a duplicate.
     * <p>
     * For example, the TelephonyCS calls addExistingConnection for a Connection with ID
     * {@code TelephonyCS@1}.  The ConnectionManager learns of this via
     * {@link ConnectionService#onRemoteExistingConnectionAdded(RemoteConnection)}, and wraps this
     * in a new {@link Connection} which it adds to Telecom via
     * {@link ConnectionService#addExistingConnection(PhoneAccountHandle, Connection)}.  As part of
     * this process, the wrapped RemoteConnection gets assigned a new ID (e.g. {@code ConnMan@1}).
     * The TelephonyCS will ALSO try to add the existing connection to Telecom, except with the
     * ID it originally referred to the connection as.  Thus Telecom needs to know that the
     * Connection with ID {@code ConnMan@1} is really the same as {@code TelephonyCS@1}.
     * <p>
     * This is an internal Telecom framework concept and is not exposed outside of the Telecom
     * framework.
     * @hide
     */
    public static final String EXTRA_ORIGINAL_CONNECTION_ID =
            "android.telecom.extra.ORIGINAL_CONNECTION_ID";

    /**
     * Extra key set on a {@link Connection} when it was created via a remote connection service.
     * For example, if a connection manager requests a remote connection service to create a call
     * using one of the remote connection service's phone account handle, this extra will be set so
     * that Telecom knows that the wrapped remote connection originated in a remote connection
     * service.  We stash this in the extras since connection managers will typically copy the
     * extras from a {@link RemoteConnection} to a {@link Connection} (there is ultimately not
     * other way to relate a {@link RemoteConnection} to a {@link Connection}.
     * @hide
     */
    public static final String EXTRA_REMOTE_PHONE_ACCOUNT_HANDLE =
            "android.telecom.extra.REMOTE_PHONE_ACCOUNT_HANDLE";

    /**
     * Extra key set from a {@link ConnectionService} when using the remote connection APIs
     * (e.g. {@link RemoteConnectionService#createRemoteConnection(PhoneAccountHandle,
     * ConnectionRequest, boolean)}) to create a remote connection.  Provides the receiving
     * {@link ConnectionService} with a means to know the package name of the requesting
     * {@link ConnectionService} so that {@link #EXTRA_REMOTE_PHONE_ACCOUNT_HANDLE} can be set for
     * better visibility in Telecom of where a connection ultimately originated.
     * @hide
     */
    public static final String EXTRA_REMOTE_CONNECTION_ORIGINATING_PACKAGE_NAME =
            "android.telecom.extra.REMOTE_CONNECTION_ORIGINATING_PACKAGE_NAME";

    /**
     * Boolean connection extra key set on the extras passed to
     * {@link Connection#sendConnectionEvent} which indicates that audio is present
     * on the RTT call when the extra value is true.
     */
    public static final String EXTRA_IS_RTT_AUDIO_PRESENT =
            "android.telecom.extra.IS_RTT_AUDIO_PRESENT";

    /**
     * The audio codec in use for the current {@link Connection}, if known.  Examples of valid
     * values include {@link #AUDIO_CODEC_AMR_WB} and {@link #AUDIO_CODEC_EVS_WB}.
     */
    public static final @AudioCodec String EXTRA_AUDIO_CODEC =
            "android.telecom.extra.AUDIO_CODEC";

    /**
     * Connection event used to inform Telecom that it should play the on hold tone.  This is used
     * to play a tone when the peer puts the current call on hold.  Sent to Telecom via
     * {@link #sendConnectionEvent(String, Bundle)}.
     */
    public static final String EVENT_ON_HOLD_TONE_START =
            "android.telecom.event.ON_HOLD_TONE_START";

    /**
     * Connection event used to inform Telecom that it should stop the on hold tone.  This is used
     * to stop a tone when the peer puts the current call on hold.  Sent to Telecom via
     * {@link #sendConnectionEvent(String, Bundle)}.
     */
    public static final String EVENT_ON_HOLD_TONE_END =
            "android.telecom.event.ON_HOLD_TONE_END";

    /**
     * Connection event used to inform {@link InCallService}s when pulling of an external call has
     * failed.  The user interface should inform the user of the error.
     * <p>
     * Expected to be used by the {@link ConnectionService} when the {@link Call#pullExternalCall()}
     * API is called on a {@link Call} with the properties
     * {@link Call.Details#PROPERTY_IS_EXTERNAL_CALL} and
     * {@link Call.Details#CAPABILITY_CAN_PULL_CALL}, but the {@link ConnectionService} could not
     * pull the external call due to an error condition.
     * <p>
     * Sent via {@link #sendConnectionEvent(String, Bundle)}.  The {@link Bundle} parameter is
     * expected to be null when this connection event is used.
     */
    public static final String EVENT_CALL_PULL_FAILED = "android.telecom.event.CALL_PULL_FAILED";

    /**
     * Connection event used to inform {@link InCallService}s when the merging of two calls has
     * failed. The User Interface should use this message to inform the user of the error.
     * <p>
     * Sent via {@link #sendConnectionEvent(String, Bundle)}.  The {@link Bundle} parameter is
     * expected to be null when this connection event is used.
     */
    public static final String EVENT_CALL_MERGE_FAILED = "android.telecom.event.CALL_MERGE_FAILED";

    /**
     * Connection event used to inform Telecom when a hold operation on a call has failed.
     * <p>
     * Sent via {@link #sendConnectionEvent(String, Bundle)}.  The {@link Bundle} parameter is
     * expected to be null when this connection event is used.
     */
    public static final String EVENT_CALL_HOLD_FAILED = "android.telecom.event.CALL_HOLD_FAILED";

    /**
     * Connection event used to inform Telecom when a switch operation on a call has failed.
     * <p>
     * Sent via {@link #sendConnectionEvent(String, Bundle)}.  The {@link Bundle} parameter is
     * expected to be null when this connection event is used.
     */
    public static final String EVENT_CALL_SWITCH_FAILED =
            "android.telecom.event.CALL_SWITCH_FAILED";

    /**
     * Connection event used to inform {@link InCallService}s when the process of merging a
     * Connection into a conference has begun.
     * <p>
     * Sent via {@link #sendConnectionEvent(String, Bundle)}.  The {@link Bundle} parameter is
     * expected to be null when this connection event is used.
     */
    public static final String EVENT_MERGE_START = "android.telecom.event.MERGE_START";

    /**
     * Connection event used to inform {@link InCallService}s when the process of merging a
     * Connection into a conference has completed.
     * <p>
     * Sent via {@link #sendConnectionEvent(String, Bundle)}.  The {@link Bundle} parameter is
     * expected to be null when this connection event is used.
     */
    public static final String EVENT_MERGE_COMPLETE = "android.telecom.event.MERGE_COMPLETE";

    /**
     * Connection event used to inform {@link InCallService}s when a call has been put on hold by
     * the remote party.
     * <p>
     * This is different than the {@link Connection#STATE_HOLDING} state which indicates that the
     * call is being held locally on the device.  When a capable {@link ConnectionService} receives
     * signalling to indicate that the remote party has put the call on hold, it can send this
     * connection event.
     */
    public static final String EVENT_CALL_REMOTELY_HELD =
            "android.telecom.event.CALL_REMOTELY_HELD";

    /**
     * Connection event used to inform {@link InCallService}s when a call which was remotely held
     * (see {@link #EVENT_CALL_REMOTELY_HELD}) has been un-held by the remote party.
     * <p>
     * This is different than the {@link Connection#STATE_HOLDING} state which indicates that the
     * call is being held locally on the device.  When a capable {@link ConnectionService} receives
     * signalling to indicate that the remote party has taken the call off hold, it can send this
     * connection event.
     */
    public static final String EVENT_CALL_REMOTELY_UNHELD =
            "android.telecom.event.CALL_REMOTELY_UNHELD";

    /**
     * Connection event used to inform an {@link InCallService} which initiated a call handover via
     * {@link Call#EVENT_REQUEST_HANDOVER} that the handover from this {@link Connection} has
     * successfully completed.
     * @hide
     * @deprecated Use {@link Call#handoverTo(PhoneAccountHandle, int, Bundle)} and its associated
     * APIs instead.
     */
    public static final String EVENT_HANDOVER_COMPLETE =
            "android.telecom.event.HANDOVER_COMPLETE";

    /**
     * Connection event used to inform an {@link InCallService} which initiated a call handover via
     * {@link Call#EVENT_REQUEST_HANDOVER} that the handover from this {@link Connection} has failed
     * to complete.
     * @hide
     * @deprecated Use {@link Call#handoverTo(PhoneAccountHandle, int, Bundle)} and its associated
     * APIs instead.
     */
    public static final String EVENT_HANDOVER_FAILED =
            "android.telecom.event.HANDOVER_FAILED";

    /**
     * String Connection extra key used to store SIP invite fields for an incoming call for IMS call
     */
    public static final String EXTRA_SIP_INVITE = "android.telecom.extra.SIP_INVITE";

    /**
     * Connection event used to inform an {@link InCallService} that the RTT audio indication
     * has changed.
     */
    public static final String EVENT_RTT_AUDIO_INDICATION_CHANGED =
            "android.telecom.event.RTT_AUDIO_INDICATION_CHANGED";

    // Flag controlling whether PII is emitted into the logs
    private static final boolean PII_DEBUG = Log.isLoggable(android.util.Log.DEBUG);

    /**
     * Renders a set of capability bits ({@code CAPABILITY_*}) as a human readable string.
     *
     * @param capabilities A capability bit field.
     * @return A human readable string representation.
     */
    public static String capabilitiesToString(int capabilities) {
        return capabilitiesToStringInternal(capabilities, true /* isLong */);
    }

    /**
     * Renders a set of capability bits ({@code CAPABILITY_*}) as a *short* human readable
     * string.
     *
     * @param capabilities A capability bit field.
     * @return A human readable string representation.
     * @hide
     */
    public static String capabilitiesToStringShort(int capabilities) {
        return capabilitiesToStringInternal(capabilities, false /* isLong */);
    }

    private static String capabilitiesToStringInternal(int capabilities, boolean isLong) {
        StringBuilder builder = new StringBuilder();
        builder.append("[");
        if (isLong) {
            builder.append("Capabilities:");
        }

        if ((capabilities & CAPABILITY_HOLD) == CAPABILITY_HOLD) {
            builder.append(isLong ? " CAPABILITY_HOLD" : " hld");
        }
        if ((capabilities & CAPABILITY_SUPPORT_HOLD) == CAPABILITY_SUPPORT_HOLD) {
            builder.append(isLong ? " CAPABILITY_SUPPORT_HOLD" : " sup_hld");
        }
        if ((capabilities & CAPABILITY_MERGE_CONFERENCE) == CAPABILITY_MERGE_CONFERENCE) {
            builder.append(isLong ? " CAPABILITY_MERGE_CONFERENCE" : " mrg_cnf");
        }
        if ((capabilities & CAPABILITY_SWAP_CONFERENCE) == CAPABILITY_SWAP_CONFERENCE) {
            builder.append(isLong ? " CAPABILITY_SWAP_CONFERENCE" : " swp_cnf");
        }
        if ((capabilities & CAPABILITY_RESPOND_VIA_TEXT) == CAPABILITY_RESPOND_VIA_TEXT) {
            builder.append(isLong ? " CAPABILITY_RESPOND_VIA_TEXT" : " txt");
        }
        if ((capabilities & CAPABILITY_MUTE) == CAPABILITY_MUTE) {
            builder.append(isLong ? " CAPABILITY_MUTE" : " mut");
        }
        if ((capabilities & CAPABILITY_MANAGE_CONFERENCE) == CAPABILITY_MANAGE_CONFERENCE) {
            builder.append(isLong ? " CAPABILITY_MANAGE_CONFERENCE" : " mng_cnf");
        }
        if ((capabilities & CAPABILITY_SUPPORTS_VT_LOCAL_RX) == CAPABILITY_SUPPORTS_VT_LOCAL_RX) {
            builder.append(isLong ? " CAPABILITY_SUPPORTS_VT_LOCAL_RX" : " VTlrx");
        }
        if ((capabilities & CAPABILITY_SUPPORTS_VT_LOCAL_TX) == CAPABILITY_SUPPORTS_VT_LOCAL_TX) {
            builder.append(isLong ? " CAPABILITY_SUPPORTS_VT_LOCAL_TX" : " VTltx");
        }
        if ((capabilities & CAPABILITY_SUPPORTS_VT_LOCAL_BIDIRECTIONAL)
                == CAPABILITY_SUPPORTS_VT_LOCAL_BIDIRECTIONAL) {
            builder.append(isLong ? " CAPABILITY_SUPPORTS_VT_LOCAL_BIDIRECTIONAL" : " VTlbi");
        }
        if ((capabilities & CAPABILITY_SUPPORTS_VT_REMOTE_RX) == CAPABILITY_SUPPORTS_VT_REMOTE_RX) {
            builder.append(isLong ? " CAPABILITY_SUPPORTS_VT_REMOTE_RX" : " VTrrx");
        }
        if ((capabilities & CAPABILITY_SUPPORTS_VT_REMOTE_TX) == CAPABILITY_SUPPORTS_VT_REMOTE_TX) {
            builder.append(isLong ? " CAPABILITY_SUPPORTS_VT_REMOTE_TX" : " VTrtx");
        }
        if ((capabilities & CAPABILITY_SUPPORTS_VT_REMOTE_BIDIRECTIONAL)
                == CAPABILITY_SUPPORTS_VT_REMOTE_BIDIRECTIONAL) {
            builder.append(isLong ? " CAPABILITY_SUPPORTS_VT_REMOTE_BIDIRECTIONAL" : " VTrbi");
        }
        if ((capabilities & CAPABILITY_CANNOT_DOWNGRADE_VIDEO_TO_AUDIO)
                == CAPABILITY_CANNOT_DOWNGRADE_VIDEO_TO_AUDIO) {
            builder.append(isLong ? " CAPABILITY_CANNOT_DOWNGRADE_VIDEO_TO_AUDIO" : " !v2a");
        }
        if ((capabilities & CAPABILITY_SPEED_UP_MT_AUDIO) == CAPABILITY_SPEED_UP_MT_AUDIO) {
            builder.append(isLong ? " CAPABILITY_SPEED_UP_MT_AUDIO" : " spd_aud");
        }
        if ((capabilities & CAPABILITY_CAN_UPGRADE_TO_VIDEO) == CAPABILITY_CAN_UPGRADE_TO_VIDEO) {
            builder.append(isLong ? " CAPABILITY_CAN_UPGRADE_TO_VIDEO" : " a2v");
        }
        if ((capabilities & CAPABILITY_CAN_PAUSE_VIDEO) == CAPABILITY_CAN_PAUSE_VIDEO) {
            builder.append(isLong ? " CAPABILITY_CAN_PAUSE_VIDEO" : " paus_VT");
        }
        if ((capabilities & CAPABILITY_CONFERENCE_HAS_NO_CHILDREN)
                == CAPABILITY_CONFERENCE_HAS_NO_CHILDREN) {
            builder.append(isLong ? " CAPABILITY_SINGLE_PARTY_CONFERENCE" : " 1p_cnf");
        }
        if ((capabilities & CAPABILITY_CAN_SEND_RESPONSE_VIA_CONNECTION)
                == CAPABILITY_CAN_SEND_RESPONSE_VIA_CONNECTION) {
            builder.append(isLong ? " CAPABILITY_CAN_SEND_RESPONSE_VIA_CONNECTION" : " rsp_by_con");
        }
        if ((capabilities & CAPABILITY_CAN_PULL_CALL) == CAPABILITY_CAN_PULL_CALL) {
            builder.append(isLong ? " CAPABILITY_CAN_PULL_CALL" : " pull");
        }
        if ((capabilities & CAPABILITY_SUPPORT_DEFLECT) == CAPABILITY_SUPPORT_DEFLECT) {
            builder.append(isLong ? " CAPABILITY_SUPPORT_DEFLECT" : " sup_def");
        }
        if ((capabilities & CAPABILITY_ADD_PARTICIPANT) == CAPABILITY_ADD_PARTICIPANT) {
            builder.append(isLong ? " CAPABILITY_ADD_PARTICIPANT" : " add_participant");
        }
        if ((capabilities & CAPABILITY_TRANSFER) == CAPABILITY_TRANSFER) {
            builder.append(isLong ? " CAPABILITY_TRANSFER" : " sup_trans");
        }
        if ((capabilities & CAPABILITY_TRANSFER_CONSULTATIVE)
                == CAPABILITY_TRANSFER_CONSULTATIVE) {
            builder.append(isLong ? " CAPABILITY_TRANSFER_CONSULTATIVE" : " sup_cTrans");
        }
        builder.append("]");
        return builder.toString();
    }

    /**
     * Renders a set of property bits ({@code PROPERTY_*}) as a human readable string.
     *
     * @param properties A property bit field.
     * @return A human readable string representation.
     */
    public static String propertiesToString(int properties) {
        return propertiesToStringInternal(properties, true /* isLong */);
    }

    /**
     * Renders a set of property bits ({@code PROPERTY_*}) as a *short* human readable string.
     *
     * @param properties A property bit field.
     * @return A human readable string representation.
     * @hide
     */
    public static String propertiesToStringShort(int properties) {
        return propertiesToStringInternal(properties, false /* isLong */);
    }

    private static String propertiesToStringInternal(int properties, boolean isLong) {
        StringBuilder builder = new StringBuilder();
        builder.append("[");
        if (isLong) {
            builder.append("Properties:");
        }

        if ((properties & PROPERTY_SELF_MANAGED) == PROPERTY_SELF_MANAGED) {
            builder.append(isLong ? " PROPERTY_SELF_MANAGED" : " self_mng");
        }

        if ((properties & PROPERTY_EMERGENCY_CALLBACK_MODE) == PROPERTY_EMERGENCY_CALLBACK_MODE) {
            builder.append(isLong ? " PROPERTY_EMERGENCY_CALLBACK_MODE" : " ecbm");
        }

        if ((properties & PROPERTY_HIGH_DEF_AUDIO) == PROPERTY_HIGH_DEF_AUDIO) {
            builder.append(isLong ? " PROPERTY_HIGH_DEF_AUDIO" : " HD");
        }

        if ((properties & PROPERTY_WIFI) == PROPERTY_WIFI) {
            builder.append(isLong ? " PROPERTY_WIFI" : " wifi");
        }

        if ((properties & PROPERTY_GENERIC_CONFERENCE) == PROPERTY_GENERIC_CONFERENCE) {
            builder.append(isLong ? " PROPERTY_GENERIC_CONFERENCE" : " gen_conf");
        }

        if ((properties & PROPERTY_IS_EXTERNAL_CALL) == PROPERTY_IS_EXTERNAL_CALL) {
            builder.append(isLong ? " PROPERTY_IS_EXTERNAL_CALL" : " xtrnl");
        }

        if ((properties & PROPERTY_HAS_CDMA_VOICE_PRIVACY) == PROPERTY_HAS_CDMA_VOICE_PRIVACY) {
            builder.append(isLong ? " PROPERTY_HAS_CDMA_VOICE_PRIVACY" : " priv");
        }

        if ((properties & PROPERTY_IS_RTT) == PROPERTY_IS_RTT) {
            builder.append(isLong ? " PROPERTY_IS_RTT" : " rtt");
        }

        if ((properties & PROPERTY_NETWORK_IDENTIFIED_EMERGENCY_CALL)
                == PROPERTY_NETWORK_IDENTIFIED_EMERGENCY_CALL) {
            builder.append(isLong ? " PROPERTY_NETWORK_IDENTIFIED_EMERGENCY_CALL" : " ecall");
        }

        if ((properties & PROPERTY_REMOTELY_HOSTED) == PROPERTY_REMOTELY_HOSTED) {
            builder.append(isLong ? " PROPERTY_REMOTELY_HOSTED" : " remote_hst");
        }

        if ((properties & PROPERTY_IS_ADHOC_CONFERENCE) == PROPERTY_IS_ADHOC_CONFERENCE) {
            builder.append(isLong ? " PROPERTY_IS_ADHOC_CONFERENCE" : " adhoc_conf");
        }

        builder.append("]");
        return builder.toString();
    }

    /** @hide */
    abstract static class Listener {
        public void onStateChanged(Connection c, int state) {}
        public void onAddressChanged(Connection c, Uri newAddress, int presentation) {}
        public void onCallerDisplayNameChanged(
                Connection c, String callerDisplayName, int presentation) {}
        public void onVideoStateChanged(Connection c, int videoState) {}
        public void onDisconnected(Connection c, DisconnectCause disconnectCause) {}
        public void onPostDialWait(Connection c, String remaining) {}
        public void onPostDialChar(Connection c, char nextChar) {}
        public void onRingbackRequested(Connection c, boolean ringback) {}
        public void onDestroyed(Connection c) {}
        public void onConnectionCapabilitiesChanged(Connection c, int capabilities) {}
        public void onConnectionPropertiesChanged(Connection c, int properties) {}
        public void onSupportedAudioRoutesChanged(Connection c, int supportedAudioRoutes) {}
        public void onVideoProviderChanged(
                Connection c, VideoProvider videoProvider) {}
        public void onAudioModeIsVoipChanged(Connection c, boolean isVoip) {}
        public void onStatusHintsChanged(Connection c, StatusHints statusHints) {}
        public void onConferenceablesChanged(
                Connection c, List<Conferenceable> conferenceables) {}
        public void onConferenceChanged(Connection c, Conference conference) {}
        public void onConferenceMergeFailed(Connection c) {}
        public void onExtrasChanged(Connection c, Bundle extras) {}
        public void onExtrasRemoved(Connection c, List<String> keys) {}
        public void onConnectionEvent(Connection c, String event, Bundle extras) {}
        public void onAudioRouteChanged(Connection c, int audioRoute, String bluetoothAddress) {}
        public void onRttInitiationSuccess(Connection c) {}
        public void onRttInitiationFailure(Connection c, int reason) {}
        public void onRttSessionRemotelyTerminated(Connection c) {}
        public void onRemoteRttRequest(Connection c) {}
        /** @hide */
        public void onPhoneAccountChanged(Connection c, PhoneAccountHandle pHandle) {}
        public void onConnectionTimeReset(Connection c) {}
    }

    /**
     * Provides methods to read and write RTT data to/from the in-call app.
     */
    public static final class RttTextStream {
        private static final int READ_BUFFER_SIZE = 1000;
        private final InputStreamReader mPipeFromInCall;
        private final OutputStreamWriter mPipeToInCall;
        private final ParcelFileDescriptor mFdFromInCall;
        private final ParcelFileDescriptor mFdToInCall;

        private final FileInputStream mFromInCallFileInputStream;
        private char[] mReadBuffer = new char[READ_BUFFER_SIZE];

        /**
         * @hide
         */
        public RttTextStream(ParcelFileDescriptor toInCall, ParcelFileDescriptor fromInCall) {
            mFdFromInCall = fromInCall;
            mFdToInCall = toInCall;
            mFromInCallFileInputStream = new FileInputStream(fromInCall.getFileDescriptor());

            // Wrap the FileInputStream in a Channel so that it's interruptible.
            mPipeFromInCall = new InputStreamReader(
                    Channels.newInputStream(Channels.newChannel(mFromInCallFileInputStream)));
            mPipeToInCall = new OutputStreamWriter(
                    new FileOutputStream(toInCall.getFileDescriptor()));
        }

        /**
         * Writes the string {@param input} into the text stream to the UI for this RTT call. Since
         * RTT transmits text in real-time, this method should be called as often as text snippets
         * are received from the remote user, even if it is only one character.
         * <p>
         * This method is not thread-safe -- calling it from multiple threads simultaneously may
         * lead to interleaved text.
         *
         * @param input The message to send to the in-call app.
         */
        public void write(String input) throws IOException {
            mPipeToInCall.write(input);
            mPipeToInCall.flush();
        }


        /**
         * Reads a string from the in-call app, blocking if there is no data available. Returns
         * {@code null} if the RTT conversation has been terminated and there is no further data
         * to read.
         * <p>
         * This method is not thread-safe -- calling it from multiple threads simultaneously may
         * lead to interleaved text.
         *
         * @return A string containing text entered by the user, or {@code null} if the
         * conversation has been terminated or if there was an error while reading.
         */
        public String read() throws IOException {
            int numRead = mPipeFromInCall.read(mReadBuffer, 0, READ_BUFFER_SIZE);
            if (numRead < 0) {
                return null;
            }
            return new String(mReadBuffer, 0, numRead);
        }

        /**
         * Non-blocking version of {@link #read()}. Returns {@code null} if there is nothing to
         * be read.
         *
         * @return A string containing text entered by the user, or {@code null} if the user has
         * not entered any new text yet.
         */
        public String readImmediately() throws IOException {
            if (mFromInCallFileInputStream.available() > 0) {
                return read();
            } else {
                return null;
            }
        }

        /** @hide */
        public ParcelFileDescriptor getFdFromInCall() {
            return mFdFromInCall;
        }

        /** @hide */
        public ParcelFileDescriptor getFdToInCall() {
            return mFdToInCall;
        }
    }

    /**
     * Provides constants to represent the results of responses to session modify requests sent via
     * {@link Call#sendRttRequest()}
     */
    public static final class RttModifyStatus {
        private RttModifyStatus() {}
        /**
         * Session modify request was successful.
         */
        public static final int SESSION_MODIFY_REQUEST_SUCCESS = 1;

        /**
         * Session modify request failed.
         */
        public static final int SESSION_MODIFY_REQUEST_FAIL = 2;

        /**
         * Session modify request ignored due to invalid parameters.
         */
        public static final int SESSION_MODIFY_REQUEST_INVALID = 3;

        /**
         * Session modify request timed out.
         */
        public static final int SESSION_MODIFY_REQUEST_TIMED_OUT = 4;

        /**
         * Session modify request rejected by remote user.
         */
        public static final int SESSION_MODIFY_REQUEST_REJECTED_BY_REMOTE = 5;
    }

    /**
     * Provides a means of controlling the video session associated with a {@link Connection}.
     * <p>
     * Implementations create a custom subclass of {@link VideoProvider} and the
     * {@link ConnectionService} creates an instance sets it on the {@link Connection} using
     * {@link Connection#setVideoProvider(VideoProvider)}.  Any connection which supports video
     * should set the {@link VideoProvider}.
     * <p>
     * The {@link VideoProvider} serves two primary purposes: it provides a means for Telecom and
     * {@link InCallService} implementations to issue requests related to the video session;
     * it provides a means for the {@link ConnectionService} to report events and information
     * related to the video session to Telecom and the {@link InCallService} implementations.
     * <p>
     * {@link InCallService} implementations interact with the {@link VideoProvider} via
     * {@link android.telecom.InCallService.VideoCall}.
     */
    public static abstract class VideoProvider {
        /**
         * Video is not being received (no protocol pause was issued).
         * @see #handleCallSessionEvent(int)
         */
        public static final int SESSION_EVENT_RX_PAUSE = 1;

        /**
         * Video reception has resumed after a {@link #SESSION_EVENT_RX_PAUSE}.
         * @see #handleCallSessionEvent(int)
         */
        public static final int SESSION_EVENT_RX_RESUME = 2;

        /**
         * Video transmission has begun. This occurs after a negotiated start of video transmission
         * when the underlying protocol has actually begun transmitting video to the remote party.
         * @see #handleCallSessionEvent(int)
         */
        public static final int SESSION_EVENT_TX_START = 3;

        /**
         * Video transmission has stopped. This occurs after a negotiated stop of video transmission
         * when the underlying protocol has actually stopped transmitting video to the remote party.
         * @see #handleCallSessionEvent(int)
         */
        public static final int SESSION_EVENT_TX_STOP = 4;

        /**
         * A camera failure has occurred for the selected camera.  The {@link VideoProvider} can use
         * this as a cue to inform the user the camera is not available.
         * @see #handleCallSessionEvent(int)
         */
        public static final int SESSION_EVENT_CAMERA_FAILURE = 5;

        /**
         * Issued after {@link #SESSION_EVENT_CAMERA_FAILURE} when the camera is once again ready
         * for operation.  The {@link VideoProvider} can use this as a cue to inform the user that
         * the camera has become available again.
         * @see #handleCallSessionEvent(int)
         */
        public static final int SESSION_EVENT_CAMERA_READY = 6;

        /**
         * Session event raised by Telecom when
         * {@link android.telecom.InCallService.VideoCall#setCamera(String)} is called and the
         * caller does not have the necessary {@link android.Manifest.permission#CAMERA} permission.
         * @see #handleCallSessionEvent(int)
         */
        public static final int SESSION_EVENT_CAMERA_PERMISSION_ERROR = 7;

        /**
         * Session modify request was successful.
         * @see #receiveSessionModifyResponse(int, VideoProfile, VideoProfile)
         */
        public static final int SESSION_MODIFY_REQUEST_SUCCESS = 1;

        /**
         * Session modify request failed.
         * @see #receiveSessionModifyResponse(int, VideoProfile, VideoProfile)
         */
        public static final int SESSION_MODIFY_REQUEST_FAIL = 2;

        /**
         * Session modify request ignored due to invalid parameters.
         * @see #receiveSessionModifyResponse(int, VideoProfile, VideoProfile)
         */
        public static final int SESSION_MODIFY_REQUEST_INVALID = 3;

        /**
         * Session modify request timed out.
         * @see #receiveSessionModifyResponse(int, VideoProfile, VideoProfile)
         */
        public static final int SESSION_MODIFY_REQUEST_TIMED_OUT = 4;

        /**
         * Session modify request rejected by remote user.
         * @see #receiveSessionModifyResponse(int, VideoProfile, VideoProfile)
         */
        public static final int SESSION_MODIFY_REQUEST_REJECTED_BY_REMOTE = 5;

        private static final int MSG_ADD_VIDEO_CALLBACK = 1;
        private static final int MSG_SET_CAMERA = 2;
        private static final int MSG_SET_PREVIEW_SURFACE = 3;
        private static final int MSG_SET_DISPLAY_SURFACE = 4;
        private static final int MSG_SET_DEVICE_ORIENTATION = 5;
        private static final int MSG_SET_ZOOM = 6;
        private static final int MSG_SEND_SESSION_MODIFY_REQUEST = 7;
        private static final int MSG_SEND_SESSION_MODIFY_RESPONSE = 8;
        private static final int MSG_REQUEST_CAMERA_CAPABILITIES = 9;
        private static final int MSG_REQUEST_CONNECTION_DATA_USAGE = 10;
        private static final int MSG_SET_PAUSE_IMAGE = 11;
        private static final int MSG_REMOVE_VIDEO_CALLBACK = 12;

        private static final String SESSION_EVENT_RX_PAUSE_STR = "RX_PAUSE";
        private static final String SESSION_EVENT_RX_RESUME_STR = "RX_RESUME";
        private static final String SESSION_EVENT_TX_START_STR = "TX_START";
        private static final String SESSION_EVENT_TX_STOP_STR = "TX_STOP";
        private static final String SESSION_EVENT_CAMERA_FAILURE_STR = "CAMERA_FAIL";
        private static final String SESSION_EVENT_CAMERA_READY_STR = "CAMERA_READY";
        private static final String SESSION_EVENT_CAMERA_PERMISSION_ERROR_STR =
                "CAMERA_PERMISSION_ERROR";
        private static final String SESSION_EVENT_UNKNOWN_STR = "UNKNOWN";

        private VideoProvider.VideoProviderHandler mMessageHandler;
        private final VideoProvider.VideoProviderBinder mBinder;

        /**
         * Stores a list of the video callbacks, keyed by IBinder.
         *
         * ConcurrentHashMap constructor params: 8 is initial table size, 0.9f is
         * load factor before resizing, 1 means we only expect a single thread to
         * access the map so make only a single shard
         */
        private ConcurrentHashMap<IBinder, IVideoCallback> mVideoCallbacks =
                new ConcurrentHashMap<IBinder, IVideoCallback>(8, 0.9f, 1);

        /**
         * Default handler used to consolidate binder method calls onto a single thread.
         */
        private final class VideoProviderHandler extends Handler {
            public VideoProviderHandler() {
                super();
            }

            public VideoProviderHandler(Looper looper) {
                super(looper);
            }

            @Override
            public void handleMessage(Message msg) {
                switch (msg.what) {
                    case MSG_ADD_VIDEO_CALLBACK: {
                        IBinder binder = (IBinder) msg.obj;
                        IVideoCallback callback = IVideoCallback.Stub
                                .asInterface((IBinder) msg.obj);
                        if (callback == null) {
                            Log.w(this, "addVideoProvider - skipped; callback is null.");
                            break;
                        }

                        if (mVideoCallbacks.containsKey(binder)) {
                            Log.i(this, "addVideoProvider - skipped; already present.");
                            break;
                        }
                        mVideoCallbacks.put(binder, callback);
                        break;
                    }
                    case MSG_REMOVE_VIDEO_CALLBACK: {
                        IBinder binder = (IBinder) msg.obj;
                        IVideoCallback callback = IVideoCallback.Stub
                                .asInterface((IBinder) msg.obj);
                        if (!mVideoCallbacks.containsKey(binder)) {
                            Log.i(this, "removeVideoProvider - skipped; not present.");
                            break;
                        }
                        mVideoCallbacks.remove(binder);
                        break;
                    }
                    case MSG_SET_CAMERA:
                    {
                        SomeArgs args = (SomeArgs) msg.obj;
                        try {
                            onSetCamera((String) args.arg1);
                            onSetCamera((String) args.arg1, (String) args.arg2, args.argi1,
                                    args.argi2, args.argi3);
                        } finally {
                            args.recycle();
                        }
                    }
                    break;
                    case MSG_SET_PREVIEW_SURFACE:
                        onSetPreviewSurface((Surface) msg.obj);
                        break;
                    case MSG_SET_DISPLAY_SURFACE:
                        onSetDisplaySurface((Surface) msg.obj);
                        break;
                    case MSG_SET_DEVICE_ORIENTATION:
                        onSetDeviceOrientation(msg.arg1);
                        break;
                    case MSG_SET_ZOOM:
                        onSetZoom((Float) msg.obj);
                        break;
                    case MSG_SEND_SESSION_MODIFY_REQUEST: {
                        SomeArgs args = (SomeArgs) msg.obj;
                        try {
                            onSendSessionModifyRequest((VideoProfile) args.arg1,
                                    (VideoProfile) args.arg2);
                        } finally {
                            args.recycle();
                        }
                        break;
                    }
                    case MSG_SEND_SESSION_MODIFY_RESPONSE:
                        onSendSessionModifyResponse((VideoProfile) msg.obj);
                        break;
                    case MSG_REQUEST_CAMERA_CAPABILITIES:
                        onRequestCameraCapabilities();
                        break;
                    case MSG_REQUEST_CONNECTION_DATA_USAGE:
                        onRequestConnectionDataUsage();
                        break;
                    case MSG_SET_PAUSE_IMAGE:
                        onSetPauseImage((Uri) msg.obj);
                        break;
                    default:
                        break;
                }
            }
        }

        /**
         * IVideoProvider stub implementation.
         */
        private final class VideoProviderBinder extends IVideoProvider.Stub {
            public void addVideoCallback(IBinder videoCallbackBinder) {
                mMessageHandler.obtainMessage(
                        MSG_ADD_VIDEO_CALLBACK, videoCallbackBinder).sendToTarget();
            }

            public void removeVideoCallback(IBinder videoCallbackBinder) {
                mMessageHandler.obtainMessage(
                        MSG_REMOVE_VIDEO_CALLBACK, videoCallbackBinder).sendToTarget();
            }

            public void setCamera(String cameraId, String callingPackageName,
                                  int targetSdkVersion) {

                SomeArgs args = SomeArgs.obtain();
                args.arg1 = cameraId;
                // Propagate the calling package; originally determined in
                // android.telecom.InCallService.VideoCall#setCamera(String) from the calling
                // process.
                args.arg2 = callingPackageName;
                // Pass along the uid and pid of the calling app; this gets lost when we put the
                // message onto the handler.  These are required for Telecom to perform a permission
                // check to see if the calling app is able to use the camera.
                args.argi1 = Binder.getCallingUid();
                args.argi2 = Binder.getCallingPid();
                // Pass along the target SDK version of the calling InCallService.  This is used to
                // maintain backwards compatibility of the API for older callers.
                args.argi3 = targetSdkVersion;
                mMessageHandler.obtainMessage(MSG_SET_CAMERA, args).sendToTarget();
            }

            public void setPreviewSurface(Surface surface) {
                mMessageHandler.obtainMessage(MSG_SET_PREVIEW_SURFACE, surface).sendToTarget();
            }

            public void setDisplaySurface(Surface surface) {
                mMessageHandler.obtainMessage(MSG_SET_DISPLAY_SURFACE, surface).sendToTarget();
            }

            public void setDeviceOrientation(int rotation) {
                mMessageHandler.obtainMessage(
                        MSG_SET_DEVICE_ORIENTATION, rotation, 0).sendToTarget();
            }

            public void setZoom(float value) {
                mMessageHandler.obtainMessage(MSG_SET_ZOOM, value).sendToTarget();
            }

            public void sendSessionModifyRequest(VideoProfile fromProfile, VideoProfile toProfile) {
                SomeArgs args = SomeArgs.obtain();
                args.arg1 = fromProfile;
                args.arg2 = toProfile;
                mMessageHandler.obtainMessage(MSG_SEND_SESSION_MODIFY_REQUEST, args).sendToTarget();
            }

            public void sendSessionModifyResponse(VideoProfile responseProfile) {
                mMessageHandler.obtainMessage(
                        MSG_SEND_SESSION_MODIFY_RESPONSE, responseProfile).sendToTarget();
            }

            public void requestCameraCapabilities() {
                mMessageHandler.obtainMessage(MSG_REQUEST_CAMERA_CAPABILITIES).sendToTarget();
            }

            public void requestCallDataUsage() {
                mMessageHandler.obtainMessage(MSG_REQUEST_CONNECTION_DATA_USAGE).sendToTarget();
            }

            public void setPauseImage(Uri uri) {
                mMessageHandler.obtainMessage(MSG_SET_PAUSE_IMAGE, uri).sendToTarget();
            }
        }

        public VideoProvider() {
            mBinder = new VideoProvider.VideoProviderBinder();
            mMessageHandler = new VideoProvider.VideoProviderHandler(Looper.getMainLooper());
        }

        /**
         * Creates an instance of the {@link VideoProvider}, specifying the looper to use.
         *
         * @param looper The looper.
         * @hide
         */
        @UnsupportedAppUsage
        public VideoProvider(Looper looper) {
            mBinder = new VideoProvider.VideoProviderBinder();
            mMessageHandler = new VideoProvider.VideoProviderHandler(looper);
        }

        /**
         * Returns binder object which can be used across IPC methods.
         * @hide
         */
        public final IVideoProvider getInterface() {
            return mBinder;
        }

        /**
         * Sets the camera to be used for the outgoing video.
         * <p>
         * The {@link VideoProvider} should respond by communicating the capabilities of the chosen
         * camera via
         * {@link VideoProvider#changeCameraCapabilities(VideoProfile.CameraCapabilities)}.
         * <p>
         * Sent from the {@link InCallService} via
         * {@link InCallService.VideoCall#setCamera(String)}.
         *
         * @param cameraId The id of the camera (use ids as reported by
         * {@link CameraManager#getCameraIdList()}).
         */
        public abstract void onSetCamera(String cameraId);

        /**
         * Sets the camera to be used for the outgoing video.
         * <p>
         * The {@link VideoProvider} should respond by communicating the capabilities of the chosen
         * camera via
         * {@link VideoProvider#changeCameraCapabilities(VideoProfile.CameraCapabilities)}.
         * <p>
         * This prototype is used internally to ensure that the calling package name, UID and PID
         * are sent to Telecom so that can perform a camera permission check on the caller.
         * <p>
         * Sent from the {@link InCallService} via
         * {@link InCallService.VideoCall#setCamera(String)}.
         *
         * @param cameraId The id of the camera (use ids as reported by
         * {@link CameraManager#getCameraIdList()}).
         * @param callingPackageName The AppOpps package name of the caller.
         * @param callingUid The UID of the caller.
         * @param callingPid The PID of the caller.
         * @param targetSdkVersion The target SDK version of the caller.
         * @hide
         */
        public void onSetCamera(String cameraId, String callingPackageName, int callingUid,
                int callingPid, int targetSdkVersion) {}

        /**
         * Sets the surface to be used for displaying a preview of what the user's camera is
         * currently capturing.  When video transmission is enabled, this is the video signal which
         * is sent to the remote device.
         * <p>
         * Sent from the {@link InCallService} via
         * {@link InCallService.VideoCall#setPreviewSurface(Surface)}.
         *
         * @param surface The {@link Surface}.
         */
        public abstract void onSetPreviewSurface(Surface surface);

        /**
         * Sets the surface to be used for displaying the video received from the remote device.
         * <p>
         * Sent from the {@link InCallService} via
         * {@link InCallService.VideoCall#setDisplaySurface(Surface)}.
         *
         * @param surface The {@link Surface}.
         */
        public abstract void onSetDisplaySurface(Surface surface);

        /**
         * Sets the device orientation, in degrees.  Assumes that a standard portrait orientation of
         * the device is 0 degrees.
         * <p>
         * Sent from the {@link InCallService} via
         * {@link InCallService.VideoCall#setDeviceOrientation(int)}.
         *
         * @param rotation The device orientation, in degrees.
         */
        public abstract void onSetDeviceOrientation(int rotation);

        /**
         * Sets camera zoom ratio.
         * <p>
         * Sent from the {@link InCallService} via {@link InCallService.VideoCall#setZoom(float)}.
         *
         * @param value The camera zoom ratio.
         */
        public abstract void onSetZoom(float value);

        /**
         * Issues a request to modify the properties of the current video session.
         * <p>
         * Example scenarios include: requesting an audio-only call to be upgraded to a
         * bi-directional video call, turning on or off the user's camera, sending a pause signal
         * when the {@link InCallService} is no longer the foreground application.
         * <p>
         * If the {@link VideoProvider} determines a request to be invalid, it should call
         * {@link #receiveSessionModifyResponse(int, VideoProfile, VideoProfile)} to report the
         * invalid request back to the {@link InCallService}.
         * <p>
         * Where a request requires confirmation from the user of the peer device, the
         * {@link VideoProvider} must communicate the request to the peer device and handle the
         * user's response.  {@link #receiveSessionModifyResponse(int, VideoProfile, VideoProfile)}
         * is used to inform the {@link InCallService} of the result of the request.
         * <p>
         * Sent from the {@link InCallService} via
         * {@link InCallService.VideoCall#sendSessionModifyRequest(VideoProfile)}.
         *
         * @param fromProfile The video profile prior to the request.
         * @param toProfile The video profile with the requested changes made.
         */
        public abstract void onSendSessionModifyRequest(VideoProfile fromProfile,
                VideoProfile toProfile);

        /**
         * Provides a response to a request to change the current video session properties.
         * <p>
         * For example, if the peer requests and upgrade from an audio-only call to a bi-directional
         * video call, could decline the request and keep the call as audio-only.
         * In such a scenario, the {@code responseProfile} would have a video state of
         * {@link VideoProfile#STATE_AUDIO_ONLY}.  If the user had decided to accept the request,
         * the video state would be {@link VideoProfile#STATE_BIDIRECTIONAL}.
         * <p>
         * Sent from the {@link InCallService} via
         * {@link InCallService.VideoCall#sendSessionModifyResponse(VideoProfile)} in response to
         * a {@link InCallService.VideoCall.Callback#onSessionModifyRequestReceived(VideoProfile)}
         * callback.
         *
         * @param responseProfile The response video profile.
         */
        public abstract void onSendSessionModifyResponse(VideoProfile responseProfile);

        /**
         * Issues a request to the {@link VideoProvider} to retrieve the camera capabilities.
         * <p>
         * The {@link VideoProvider} should respond by communicating the capabilities of the chosen
         * camera via
         * {@link VideoProvider#changeCameraCapabilities(VideoProfile.CameraCapabilities)}.
         * <p>
         * Sent from the {@link InCallService} via
         * {@link InCallService.VideoCall#requestCameraCapabilities()}.
         */
        public abstract void onRequestCameraCapabilities();

        /**
         * Issues a request to the {@link VideoProvider} to retrieve the current data usage for the
         * video component of the current {@link Connection}.
         * <p>
         * The {@link VideoProvider} should respond by communicating current data usage, in bytes,
         * via {@link VideoProvider#setCallDataUsage(long)}.
         * <p>
         * Sent from the {@link InCallService} via
         * {@link InCallService.VideoCall#requestCallDataUsage()}.
         */
        public abstract void onRequestConnectionDataUsage();

        /**
         * Provides the {@link VideoProvider} with the {@link Uri} of an image to be displayed to
         * the peer device when the video signal is paused.
         * <p>
         * Sent from the {@link InCallService} via
         * {@link InCallService.VideoCall#setPauseImage(Uri)}.
         *
         * @param uri URI of image to display.
         */
        public abstract void onSetPauseImage(Uri uri);

        /**
         * Used to inform listening {@link InCallService} implementations when the
         * {@link VideoProvider} receives a session modification request.
         * <p>
         * Received by the {@link InCallService} via
         * {@link InCallService.VideoCall.Callback#onSessionModifyRequestReceived(VideoProfile)},
         *
         * @param videoProfile The requested video profile.
         * @see #onSendSessionModifyRequest(VideoProfile, VideoProfile)
         */
        public void receiveSessionModifyRequest(VideoProfile videoProfile) {
            if (mVideoCallbacks != null) {
                for (IVideoCallback callback : mVideoCallbacks.values()) {
                    try {
                        callback.receiveSessionModifyRequest(videoProfile);
                    } catch (RemoteException ignored) {
                        Log.w(this, "receiveSessionModifyRequest callback failed", ignored);
                    }
                }
            }
        }

        /**
         * Used to inform listening {@link InCallService} implementations when the
         * {@link VideoProvider} receives a response to a session modification request.
         * <p>
         * Received by the {@link InCallService} via
         * {@link InCallService.VideoCall.Callback#onSessionModifyResponseReceived(int,
         * VideoProfile, VideoProfile)}.
         *
         * @param status Status of the session modify request.  Valid values are
         *               {@link VideoProvider#SESSION_MODIFY_REQUEST_SUCCESS},
         *               {@link VideoProvider#SESSION_MODIFY_REQUEST_FAIL},
         *               {@link VideoProvider#SESSION_MODIFY_REQUEST_INVALID},
         *               {@link VideoProvider#SESSION_MODIFY_REQUEST_TIMED_OUT},
         *               {@link VideoProvider#SESSION_MODIFY_REQUEST_REJECTED_BY_REMOTE}
         * @param requestedProfile The original request which was sent to the peer device.
         * @param responseProfile The actual profile changes agreed to by the peer device.
         * @see #onSendSessionModifyRequest(VideoProfile, VideoProfile)
         */
        public void receiveSessionModifyResponse(int status,
                VideoProfile requestedProfile, VideoProfile responseProfile) {
            if (mVideoCallbacks != null) {
                for (IVideoCallback callback : mVideoCallbacks.values()) {
                    try {
                        callback.receiveSessionModifyResponse(status, requestedProfile,
                                responseProfile);
                    } catch (RemoteException ignored) {
                        Log.w(this, "receiveSessionModifyResponse callback failed", ignored);
                    }
                }
            }
        }

        /**
         * Used to inform listening {@link InCallService} implementations when the
         * {@link VideoProvider} reports a call session event.
         * <p>
         * Received by the {@link InCallService} via
         * {@link InCallService.VideoCall.Callback#onCallSessionEvent(int)}.
         *
         * @param event The event.  Valid values are: {@link VideoProvider#SESSION_EVENT_RX_PAUSE},
         *      {@link VideoProvider#SESSION_EVENT_RX_RESUME},
         *      {@link VideoProvider#SESSION_EVENT_TX_START},
         *      {@link VideoProvider#SESSION_EVENT_TX_STOP},
         *      {@link VideoProvider#SESSION_EVENT_CAMERA_FAILURE},
         *      {@link VideoProvider#SESSION_EVENT_CAMERA_READY},
         *      {@link VideoProvider#SESSION_EVENT_CAMERA_FAILURE}.
         */
        public void handleCallSessionEvent(int event) {
            if (mVideoCallbacks != null) {
                for (IVideoCallback callback : mVideoCallbacks.values()) {
                    try {
                        callback.handleCallSessionEvent(event);
                    } catch (RemoteException ignored) {
                        Log.w(this, "handleCallSessionEvent callback failed", ignored);
                    }
                }
            }
        }

        /**
         * Used to inform listening {@link InCallService} implementations when the dimensions of the
         * peer's video have changed.
         * <p>
         * This could occur if, for example, the peer rotates their device, changing the aspect
         * ratio of the video, or if the user switches between the back and front cameras.
         * <p>
         * Received by the {@link InCallService} via
         * {@link InCallService.VideoCall.Callback#onPeerDimensionsChanged(int, int)}.
         *
         * @param width  The updated peer video width.
         * @param height The updated peer video height.
         */
        public void changePeerDimensions(int width, int height) {
            if (mVideoCallbacks != null) {
                for (IVideoCallback callback : mVideoCallbacks.values()) {
                    try {
                        callback.changePeerDimensions(width, height);
                    } catch (RemoteException ignored) {
                        Log.w(this, "changePeerDimensions callback failed", ignored);
                    }
                }
            }
        }

        /**
         * Used to inform listening {@link InCallService} implementations when the data usage of the
         * video associated with the current {@link Connection} has changed.
         * <p>
         * This could be in response to a preview request via
         * {@link #onRequestConnectionDataUsage()}, or as a periodic update by the
         * {@link VideoProvider}.  Where periodic updates of data usage are provided, they should be
         * provided at most for every 1 MB of data transferred and no more than once every 10 sec.
         * <p>
         * Received by the {@link InCallService} via
         * {@link InCallService.VideoCall.Callback#onCallDataUsageChanged(long)}.
         *
         * @param dataUsage The updated data usage (in bytes).  Reported as the cumulative bytes
         *                  used since the start of the call.
         */
        public void setCallDataUsage(long dataUsage) {
            if (mVideoCallbacks != null) {
                for (IVideoCallback callback : mVideoCallbacks.values()) {
                    try {
                        callback.changeCallDataUsage(dataUsage);
                    } catch (RemoteException ignored) {
                        Log.w(this, "setCallDataUsage callback failed", ignored);
                    }
                }
            }
        }

        /**
         * @see #setCallDataUsage(long)
         *
         * @param dataUsage The updated data usage (in byes).
         * @deprecated - Use {@link #setCallDataUsage(long)} instead.
         * @hide
         */
        public void changeCallDataUsage(long dataUsage) {
            setCallDataUsage(dataUsage);
        }

        /**
         * Used to inform listening {@link InCallService} implementations when the capabilities of
         * the current camera have changed.
         * <p>
         * The {@link VideoProvider} should call this in response to
         * {@link VideoProvider#onRequestCameraCapabilities()}, or when the current camera is
         * changed via {@link VideoProvider#onSetCamera(String)}.
         * <p>
         * Received by the {@link InCallService} via
         * {@link InCallService.VideoCall.Callback#onCameraCapabilitiesChanged(
         * VideoProfile.CameraCapabilities)}.
         *
         * @param cameraCapabilities The new camera capabilities.
         */
        public void changeCameraCapabilities(VideoProfile.CameraCapabilities cameraCapabilities) {
            if (mVideoCallbacks != null) {
                for (IVideoCallback callback : mVideoCallbacks.values()) {
                    try {
                        callback.changeCameraCapabilities(cameraCapabilities);
                    } catch (RemoteException ignored) {
                        Log.w(this, "changeCameraCapabilities callback failed", ignored);
                    }
                }
            }
        }

        /**
         * Used to inform listening {@link InCallService} implementations when the video quality
         * of the call has changed.
         * <p>
         * Received by the {@link InCallService} via
         * {@link InCallService.VideoCall.Callback#onVideoQualityChanged(int)}.
         *
         * @param videoQuality The updated video quality.  Valid values:
         *      {@link VideoProfile#QUALITY_HIGH},
         *      {@link VideoProfile#QUALITY_MEDIUM},
         *      {@link VideoProfile#QUALITY_LOW},
         *      {@link VideoProfile#QUALITY_DEFAULT}.
         */
        public void changeVideoQuality(int videoQuality) {
            if (mVideoCallbacks != null) {
                for (IVideoCallback callback : mVideoCallbacks.values()) {
                    try {
                        callback.changeVideoQuality(videoQuality);
                    } catch (RemoteException ignored) {
                        Log.w(this, "changeVideoQuality callback failed", ignored);
                    }
                }
            }
        }

        /**
         * Returns a string representation of a call session event.
         *
         * @param event A call session event passed to {@link #handleCallSessionEvent(int)}.
         * @return String representation of the call session event.
         * @hide
         */
        public static String sessionEventToString(int event) {
            switch (event) {
                case SESSION_EVENT_CAMERA_FAILURE:
                    return SESSION_EVENT_CAMERA_FAILURE_STR;
                case SESSION_EVENT_CAMERA_READY:
                    return SESSION_EVENT_CAMERA_READY_STR;
                case SESSION_EVENT_RX_PAUSE:
                    return SESSION_EVENT_RX_PAUSE_STR;
                case SESSION_EVENT_RX_RESUME:
                    return SESSION_EVENT_RX_RESUME_STR;
                case SESSION_EVENT_TX_START:
                    return SESSION_EVENT_TX_START_STR;
                case SESSION_EVENT_TX_STOP:
                    return SESSION_EVENT_TX_STOP_STR;
                case SESSION_EVENT_CAMERA_PERMISSION_ERROR:
                    return SESSION_EVENT_CAMERA_PERMISSION_ERROR_STR;
                default:
                    return SESSION_EVENT_UNKNOWN_STR + " " + event;
            }
        }
    }

    private final Listener mConnectionDeathListener = new Listener() {
        @Override
        public void onDestroyed(Connection c) {
            if (mConferenceables.remove(c)) {
                fireOnConferenceableConnectionsChanged();
            }
        }
    };

    private final Conference.Listener mConferenceDeathListener = new Conference.Listener() {
        @Override
        public void onDestroyed(Conference c) {
            if (mConferenceables.remove(c)) {
                fireOnConferenceableConnectionsChanged();
            }
        }
    };

    /**
     * ConcurrentHashMap constructor params: 8 is initial table size, 0.9f is
     * load factor before resizing, 1 means we only expect a single thread to
     * access the map so make only a single shard
     */
    private final Set<Listener> mListeners = Collections.newSetFromMap(
            new ConcurrentHashMap<Listener, Boolean>(8, 0.9f, 1));
    private final List<Conferenceable> mConferenceables = new ArrayList<>();
    private final List<Conferenceable> mUnmodifiableConferenceables =
            Collections.unmodifiableList(mConferenceables);

    // The internal telecom call ID associated with this connection.
    private String mTelecomCallId;
    // The PhoneAccountHandle associated with this connection.
    private PhoneAccountHandle mPhoneAccountHandle;
    private int mState = STATE_NEW;
    private CallAudioState mCallAudioState;
    private Uri mAddress;
    private int mAddressPresentation;
    private String mCallerDisplayName;
    private int mCallerDisplayNamePresentation;
    private boolean mRingbackRequested = false;
    private int mConnectionCapabilities;
    private int mConnectionProperties;
    private int mSupportedAudioRoutes = CallAudioState.ROUTE_ALL;
    private VideoProvider mVideoProvider;
    private boolean mAudioModeIsVoip;
    private long mConnectTimeMillis = Conference.CONNECT_TIME_NOT_SPECIFIED;
    private long mConnectElapsedTimeMillis = Conference.CONNECT_TIME_NOT_SPECIFIED;
    private StatusHints mStatusHints;
    private int mVideoState;
    private DisconnectCause mDisconnectCause;
    private Conference mConference;
    private ConnectionService mConnectionService;
    private Bundle mExtras;
    private final Object mExtrasLock = new Object();
    /**
     * The direction of the connection; used where an existing connection is created and we need to
     * communicate to Telecom whether its incoming or outgoing.
     */
    private @Call.Details.CallDirection int mCallDirection = Call.Details.DIRECTION_UNKNOWN;

    /**
     * Tracks the key set for the extras bundle provided on the last invocation of
     * {@link #setExtras(Bundle)}.  Used so that on subsequent invocations we can remove any extras
     * keys which were set previously but are no longer present in the replacement Bundle.
     */
    private Set<String> mPreviousExtraKeys;

    /**
     * The verification status for an incoming call's phone number.
     */
    private @VerificationStatus int mCallerNumberVerificationStatus;


    /**
     * Create a new Connection.
     */
    public Connection() {}

    /**
     * Returns the Telecom internal call ID associated with this connection.  Should only be used
     * for debugging and tracing purposes.
     * <p>
     * Note: Access to the Telecom internal call ID is used for logging purposes only; this API is
     * provided to facilitate debugging of the Telephony stack only.
     *
     * @return The Telecom call ID, or {@code null} if it was not set.
     * @hide
     */
    @SystemApi
    @TestApi
    public final @Nullable String getTelecomCallId() {
        return mTelecomCallId;
    }

    /**
     * @return The address (e.g., phone number) to which this Connection is currently communicating.
     */
    public final Uri getAddress() {
        return mAddress;
    }

    /**
     * @return The presentation requirements for the address.
     *         See {@link TelecomManager} for valid values.
     */
    public final int getAddressPresentation() {
        return mAddressPresentation;
    }

    /**
     * @return The caller display name (CNAP).
     */
    public final String getCallerDisplayName() {
        return mCallerDisplayName;
    }

    /**
     * @return The presentation requirements for the handle.
     *         See {@link TelecomManager} for valid values.
     */
    public final int getCallerDisplayNamePresentation() {
        return mCallerDisplayNamePresentation;
    }

    /**
     * @return The state of this Connection.
     */
    public final int getState() {
        return mState;
    }

    /**
     * Returns the video state of the connection.
     * Valid values: {@link VideoProfile#STATE_AUDIO_ONLY},
     * {@link VideoProfile#STATE_BIDIRECTIONAL},
     * {@link VideoProfile#STATE_TX_ENABLED},
     * {@link VideoProfile#STATE_RX_ENABLED}.
     *
     * @return The video state of the connection.
     */
    public final @VideoProfile.VideoState int getVideoState() {
        return mVideoState;
    }

    /**
     * @return The audio state of the connection, describing how its audio is currently
     *         being routed by the system. This is {@code null} if this Connection
     *         does not directly know about its audio state.
     * @deprecated Use {@link #getCallAudioState()} instead.
     * @hide
     */
    @SystemApi
    @Deprecated
    public final AudioState getAudioState() {
        if (mCallAudioState == null) {
          return null;
        }
        return new AudioState(mCallAudioState);
    }

    /**
     * @return The audio state of the connection, describing how its audio is currently
     *         being routed by the system. This is {@code null} if this Connection
     *         does not directly know about its audio state.
     */
    public final CallAudioState getCallAudioState() {
        return mCallAudioState;
    }

    /**
     * @return The conference that this connection is a part of.  Null if it is not part of any
     *         conference.
     */
    public final Conference getConference() {
        return mConference;
    }

    /**
     * Returns whether this connection is requesting that the system play a ringback tone
     * on its behalf.
     */
    public final boolean isRingbackRequested() {
        return mRingbackRequested;
    }

    /**
     * @return True if the connection's audio mode is VOIP.
     */
    public final boolean getAudioModeIsVoip() {
        return mAudioModeIsVoip;
    }

    /**
     * Retrieves the connection start time of the {@code Connnection}, if specified.  A value of
     * {@link Conference#CONNECT_TIME_NOT_SPECIFIED} indicates that Telecom should determine the
     * start time of the conference.
     * <p>
     * Note: This is an implementation detail specific to IMS conference calls over a mobile
     * network.
     *
     * @return The time at which the {@code Connnection} was connected. Will be a value as retrieved
     * from {@link System#currentTimeMillis()}.
     *
     * @hide
     */
    @SystemApi
    @TestApi
    public final @IntRange(from = 0) long getConnectTimeMillis() {
        return mConnectTimeMillis;
    }

    /**
     * Retrieves the connection start time of the {@link Connection}, if specified.  A value of
     * {@link Conference#CONNECT_TIME_NOT_SPECIFIED} indicates that Telecom should determine the
     * start time of the connection.
     * <p>
     * Based on the value of {@link SystemClock#elapsedRealtime()}, which ensures that wall-clock
     * changes do not impact the call duration.
     * <p>
     * Used internally in Telephony when migrating conference participant data for IMS conferences.
     * <p>
     * The value returned is the same one set using
     * {@link #setConnectionStartElapsedRealtimeMillis(long)}.  This value is never updated from
     * the Telecom framework, so no permission enforcement occurs when retrieving the value with
     * this method.
     *
     * @return The time at which the {@link Connection} was connected.
     *
     * @hide
     */
    @SystemApi
    @TestApi
    public final @ElapsedRealtimeLong long getConnectionStartElapsedRealtimeMillis() {
        return mConnectElapsedTimeMillis;
    }

    /**
     * @return The status hints for this connection.
     */
    public final StatusHints getStatusHints() {
        return mStatusHints;
    }

    /**
     * Returns the extras associated with this connection.
     * <p>
     * Extras should be updated using {@link #putExtras(Bundle)}.
     * <p>
     * Telecom or an {@link InCallService} can also update the extras via
     * {@link android.telecom.Call#putExtras(Bundle)}, and
     * {@link Call#removeExtras(List)}.
     * <p>
     * The connection is notified of changes to the extras made by Telecom or an
     * {@link InCallService} by {@link #onExtrasChanged(Bundle)}.
     *
     * @return The extras associated with this connection.
     */
    public final Bundle getExtras() {
        Bundle extras = null;
        synchronized (mExtrasLock) {
            if (mExtras != null) {
                extras = new Bundle(mExtras);
            }
        }
        return extras;
    }

    /**
     * Assign a listener to be notified of state changes.
     *
     * @param l A listener.
     * @return This Connection.
     *
     * @hide
     */
    final Connection addConnectionListener(Listener l) {
        mListeners.add(l);
        return this;
    }

    /**
     * Remove a previously assigned listener that was being notified of state changes.
     *
     * @param l A Listener.
     * @return This Connection.
     *
     * @hide
     */
    final Connection removeConnectionListener(Listener l) {
        if (l != null) {
            mListeners.remove(l);
        }
        return this;
    }

    /**
     * @return The {@link DisconnectCause} for this connection.
     */
    public final DisconnectCause getDisconnectCause() {
        return mDisconnectCause;
    }

    /**
     * Sets the telecom call ID associated with this Connection.  The Telecom Call ID should be used
     * ONLY for debugging purposes.
     * <p>
     * Note: Access to the Telecom internal call ID is used for logging purposes only; this API is
     * provided to facilitate debugging of the Telephony stack only.  Changing the ID via this
     * method does NOT change any functionality in Telephony or Telecom and impacts only logging.
     *
     * @param callId The telecom call ID.
     * @hide
     */
    @SystemApi
    @TestApi
    public void setTelecomCallId(@NonNull String callId) {
        mTelecomCallId = callId;
    }

    /**
     * Inform this Connection that the state of its audio output has been changed externally.
     *
     * @param state The new audio state.
     * @hide
     */
    final void setCallAudioState(CallAudioState state) {
        checkImmutable();
        Log.d(this, "setAudioState %s", state);
        mCallAudioState = state;
        onAudioStateChanged(getAudioState());
        onCallAudioStateChanged(state);
    }

    /**
     * @param state An integer value of a {@code STATE_*} constant.
     * @return A string representation of the value.
     */
    public static String stateToString(int state) {
        switch (state) {
            case STATE_INITIALIZING:
                return "INITIALIZING";
            case STATE_NEW:
                return "NEW";
            case STATE_RINGING:
                return "RINGING";
            case STATE_DIALING:
                return "DIALING";
            case STATE_PULLING_CALL:
                return "PULLING_CALL";
            case STATE_ACTIVE:
                return "ACTIVE";
            case STATE_HOLDING:
                return "HOLDING";
            case STATE_DISCONNECTED:
                return "DISCONNECTED";
            default:
                Log.wtf(Connection.class, "Unknown state %d", state);
                return "UNKNOWN";
        }
    }

    /**
     * Returns the connection's capabilities, as a bit mask of the {@code CAPABILITY_*} constants.
     */
    public final int getConnectionCapabilities() {
        return mConnectionCapabilities;
    }

    /**
     * Returns the connection's properties, as a bit mask of the {@code PROPERTY_*} constants.
     */
    public final int getConnectionProperties() {
        return mConnectionProperties;
    }

    /**
     * Returns the connection's supported audio routes.
     *
     * @hide
     */
    public final int getSupportedAudioRoutes() {
        return mSupportedAudioRoutes;
    }

    /**
     * Sets the value of the {@link #getAddress()} property.
     *
     * @param address The new address.
     * @param presentation The presentation requirements for the address.
     *        See {@link TelecomManager} for valid values.
     */
    public final void setAddress(Uri address, int presentation) {
        Log.d(this, "setAddress %s", address);
        mAddress = address;
        mAddressPresentation = presentation;
        for (Listener l : mListeners) {
            l.onAddressChanged(this, address, presentation);
        }
    }

    /**
     * Sets the caller display name (CNAP).
     *
     * @param callerDisplayName The new display name.
     * @param presentation The presentation requirements for the handle.
     *        See {@link TelecomManager} for valid values.
     */
    public final void setCallerDisplayName(String callerDisplayName, int presentation) {
        checkImmutable();
        Log.d(this, "setCallerDisplayName %s", callerDisplayName);
        mCallerDisplayName = callerDisplayName;
        mCallerDisplayNamePresentation = presentation;
        for (Listener l : mListeners) {
            l.onCallerDisplayNameChanged(this, callerDisplayName, presentation);
        }
    }

    /**
     * Set the video state for the connection.
     * Valid values: {@link VideoProfile#STATE_AUDIO_ONLY},
     * {@link VideoProfile#STATE_BIDIRECTIONAL},
     * {@link VideoProfile#STATE_TX_ENABLED},
     * {@link VideoProfile#STATE_RX_ENABLED}.
     *
     * @param videoState The new video state.
     */
    public final void setVideoState(int videoState) {
        checkImmutable();
        Log.d(this, "setVideoState %d", videoState);
        mVideoState = videoState;
        for (Listener l : mListeners) {
            l.onVideoStateChanged(this, mVideoState);
        }
    }

    /**
     * Sets state to active (e.g., an ongoing connection where two or more parties can actively
     * communicate).
     */
    public final void setActive() {
        checkImmutable();
        setRingbackRequested(false);
        setState(STATE_ACTIVE);
    }

    /**
     * Sets state to ringing (e.g., an inbound ringing connection).
     */
    public final void setRinging() {
        checkImmutable();
        setState(STATE_RINGING);
    }

    /**
     * Sets state to initializing (this Connection is not yet ready to be used).
     */
    public final void setInitializing() {
        checkImmutable();
        setState(STATE_INITIALIZING);
    }

    /**
     * Sets state to initialized (the Connection has been set up and is now ready to be used).
     */
    public final void setInitialized() {
        checkImmutable();
        setState(STATE_NEW);
    }

    /**
     * Sets state to dialing (e.g., dialing an outbound connection).
     */
    public final void setDialing() {
        checkImmutable();
        setState(STATE_DIALING);
    }

    /**
     * Sets state to pulling (e.g. the connection is being pulled to the local device from another
     * device).  Only applicable for {@link Connection}s with
     * {@link Connection#PROPERTY_IS_EXTERNAL_CALL} and {@link Connection#CAPABILITY_CAN_PULL_CALL}.
     */
    public final void setPulling() {
        checkImmutable();
        setState(STATE_PULLING_CALL);
    }

    /**
     * Sets state to be on hold.
     */
    public final void setOnHold() {
        checkImmutable();
        setState(STATE_HOLDING);
    }

    /**
     * Sets the video connection provider.
     * @param videoProvider The video provider.
     */
    public final void setVideoProvider(VideoProvider videoProvider) {
        checkImmutable();
        mVideoProvider = videoProvider;
        for (Listener l : mListeners) {
            l.onVideoProviderChanged(this, videoProvider);
        }
    }

    public final VideoProvider getVideoProvider() {
        return mVideoProvider;
    }

    /**
     * Sets state to disconnected.
     *
     * @param disconnectCause The reason for the disconnection, as specified by
     *         {@link DisconnectCause}.
     */
    public final void setDisconnected(DisconnectCause disconnectCause) {
        checkImmutable();
        mDisconnectCause = disconnectCause;
        setState(STATE_DISCONNECTED);
        Log.d(this, "Disconnected with cause %s", disconnectCause);
        for (Listener l : mListeners) {
            l.onDisconnected(this, disconnectCause);
        }
    }

    /**
     * Informs listeners that this {@code Connection} is in a post-dial wait state. This is done
     * when (a) the {@code Connection} is issuing a DTMF sequence; (b) it has encountered a "wait"
     * character; and (c) it wishes to inform the In-Call app that it is waiting for the end-user
     * to send an {@link #onPostDialContinue(boolean)} signal.
     *
     * @param remaining The DTMF character sequence remaining to be emitted once the
     *         {@link #onPostDialContinue(boolean)} is received, including any "wait" characters
     *         that remaining sequence may contain.
     */
    public final void setPostDialWait(String remaining) {
        checkImmutable();
        for (Listener l : mListeners) {
            l.onPostDialWait(this, remaining);
        }
    }

    /**
     * Informs listeners that this {@code Connection} has processed a character in the post-dial
     * started state. This is done when (a) the {@code Connection} is issuing a DTMF sequence;
     * and (b) it wishes to signal Telecom to play the corresponding DTMF tone locally.
     *
     * @param nextChar The DTMF character that was just processed by the {@code Connection}.
     */
    public final void setNextPostDialChar(char nextChar) {
        checkImmutable();
        for (Listener l : mListeners) {
            l.onPostDialChar(this, nextChar);
        }
    }

    /**
     * Requests that the framework play a ringback tone. This is to be invoked by implementations
     * that do not play a ringback tone themselves in the connection's audio stream.
     *
     * @param ringback Whether the ringback tone is to be played.
     */
    public final void setRingbackRequested(boolean ringback) {
        checkImmutable();
        if (mRingbackRequested != ringback) {
            mRingbackRequested = ringback;
            for (Listener l : mListeners) {
                l.onRingbackRequested(this, ringback);
            }
        }
    }

    /**
     * Sets the connection's capabilities as a bit mask of the {@code CAPABILITY_*} constants.
     *
     * @param connectionCapabilities The new connection capabilities.
     */
    public final void setConnectionCapabilities(int connectionCapabilities) {
        checkImmutable();
        if (mConnectionCapabilities != connectionCapabilities) {
            mConnectionCapabilities = connectionCapabilities;
            for (Listener l : mListeners) {
                l.onConnectionCapabilitiesChanged(this, mConnectionCapabilities);
            }
        }
    }

    /**
     * Sets the connection's properties as a bit mask of the {@code PROPERTY_*} constants.
     *
     * @param connectionProperties The new connection properties.
     */
    public final void setConnectionProperties(int connectionProperties) {
        checkImmutable();
        if (mConnectionProperties != connectionProperties) {
            mConnectionProperties = connectionProperties;
            for (Listener l : mListeners) {
                l.onConnectionPropertiesChanged(this, mConnectionProperties);
            }
        }
    }

    /**
     * Sets the supported audio routes.
     *
     * @param supportedAudioRoutes the supported audio routes as a bitmask.
     *                             See {@link CallAudioState}
     * @hide
     */
    public final void setSupportedAudioRoutes(int supportedAudioRoutes) {
        if ((supportedAudioRoutes
                & (CallAudioState.ROUTE_EARPIECE | CallAudioState.ROUTE_SPEAKER)) == 0) {
            throw new IllegalArgumentException(
                    "supported audio routes must include either speaker or earpiece");
        }

        if (mSupportedAudioRoutes != supportedAudioRoutes) {
            mSupportedAudioRoutes = supportedAudioRoutes;
            for (Listener l : mListeners) {
                l.onSupportedAudioRoutesChanged(this, mSupportedAudioRoutes);
            }
        }
    }

    /**
     * Tears down the Connection object.
     */
    public final void destroy() {
        for (Listener l : mListeners) {
            l.onDestroyed(this);
        }
    }

    /**
     * Requests that the framework use VOIP audio mode for this connection.
     *
     * @param isVoip True if the audio mode is VOIP.
     */
    public final void setAudioModeIsVoip(boolean isVoip) {
        checkImmutable();
        mAudioModeIsVoip = isVoip;
        for (Listener l : mListeners) {
            l.onAudioModeIsVoipChanged(this, isVoip);
        }
    }

    /**
     * Sets the time at which a call became active on this Connection. This is set only
     * when a conference call becomes active on this connection.
     * <p>
     * This time corresponds to the date/time of connection and is stored in the call log in
     * {@link android.provider.CallLog.Calls#DATE}.
     * <p>
     * Used by telephony to maintain calls associated with an IMS Conference.
     *
     * @param connectTimeMillis The connection time, in milliseconds.  Should be set using a value
     *                          obtained from {@link System#currentTimeMillis()}.
     *
     * @hide
     */
    @SystemApi
    @TestApi
    @RequiresPermission(MODIFY_PHONE_STATE)
    public final void setConnectTimeMillis(@IntRange(from = 0) long connectTimeMillis) {
        mConnectTimeMillis = connectTimeMillis;
    }

    /**
     * Sets the time at which a call became active on this Connection. This is set only
     * when a conference call becomes active on this connection.
     * <p>
     * This time is used to establish the duration of a call.  It uses
     * {@link SystemClock#elapsedRealtime()} to ensure that the call duration is not impacted by
     * time zone changes during a call.  The difference between the current
     * {@link SystemClock#elapsedRealtime()} and the value set at the connection start time is used
     * to populate {@link android.provider.CallLog.Calls#DURATION} in the call log.
     * <p>
     * Used by telephony to maintain calls associated with an IMS Conference.
     *
     * @param connectElapsedTimeMillis The connection time, in milliseconds.  Stored in the format
     *                              {@link SystemClock#elapsedRealtime()}.
     * @hide
     */
    @SystemApi
    @TestApi
    @RequiresPermission(MODIFY_PHONE_STATE)
    public final void setConnectionStartElapsedRealtimeMillis(
            @ElapsedRealtimeLong long connectElapsedTimeMillis) {
        mConnectElapsedTimeMillis = connectElapsedTimeMillis;
    }

    /**
     * Sets the label and icon status to display in the in-call UI.
     *
     * @param statusHints The status label and icon to set.
     */
    public final void setStatusHints(StatusHints statusHints) {
        checkImmutable();
        mStatusHints = statusHints;
        for (Listener l : mListeners) {
            l.onStatusHintsChanged(this, statusHints);
        }
    }

    /**
     * Sets the connections with which this connection can be conferenced.
     *
     * @param conferenceableConnections The set of connections this connection can conference with.
     */
    public final void setConferenceableConnections(List<Connection> conferenceableConnections) {
        checkImmutable();
        clearConferenceableList();
        for (Connection c : conferenceableConnections) {
            // If statement checks for duplicates in input. It makes it N^2 but we're dealing with a
            // small amount of items here.
            if (!mConferenceables.contains(c)) {
                c.addConnectionListener(mConnectionDeathListener);
                mConferenceables.add(c);
            }
        }
        fireOnConferenceableConnectionsChanged();
    }

    /**
     * Similar to {@link #setConferenceableConnections(java.util.List)}, sets a list of connections
     * or conferences with which this connection can be conferenced.
     *
     * @param conferenceables The conferenceables.
     */
    public final void setConferenceables(List<Conferenceable> conferenceables) {
        clearConferenceableList();
        for (Conferenceable c : conferenceables) {
            // If statement checks for duplicates in input. It makes it N^2 but we're dealing with a
            // small amount of items here.
            if (!mConferenceables.contains(c)) {
                if (c instanceof Connection) {
                    Connection connection = (Connection) c;
                    connection.addConnectionListener(mConnectionDeathListener);
                } else if (c instanceof Conference) {
                    Conference conference = (Conference) c;
                    conference.addListener(mConferenceDeathListener);
                }
                mConferenceables.add(c);
            }
        }
        fireOnConferenceableConnectionsChanged();
    }

    /**
     * Resets the CDMA connection time.
     * <p>
     * This is an implementation detail specific to legacy CDMA calls on mobile networks.
     * @hide
     */
    @SystemApi
    @TestApi
    public final void resetConnectionTime() {
        for (Listener l : mListeners) {
            l.onConnectionTimeReset(this);
        }
    }

    /**
     * Returns the connections or conferences with which this connection can be conferenced.
     */
    public final List<Conferenceable> getConferenceables() {
        return mUnmodifiableConferenceables;
    }

    /**
     * @hide
     */
    public final void setConnectionService(ConnectionService connectionService) {
        checkImmutable();
        if (mConnectionService != null) {
            Log.e(this, new Exception(), "Trying to set ConnectionService on a connection " +
                    "which is already associated with another ConnectionService.");
        } else {
            mConnectionService = connectionService;
        }
    }

    /**
     * @hide
     */
    public final void unsetConnectionService(ConnectionService connectionService) {
        if (mConnectionService != connectionService) {
            Log.e(this, new Exception(), "Trying to remove ConnectionService from a Connection " +
                    "that does not belong to the ConnectionService.");
        } else {
            mConnectionService = null;
        }
    }

    /**
     * Sets the conference that this connection is a part of. This will fail if the connection is
     * already part of a conference. {@link #resetConference} to un-set the conference first.
     *
     * @param conference The conference.
     * @return {@code true} if the conference was successfully set.
     * @hide
     */
    public final boolean setConference(Conference conference) {
        checkImmutable();
        // We check to see if it is already part of another conference.
        if (mConference == null) {
            mConference = conference;
            if (mConnectionService != null && mConnectionService.containsConference(conference)) {
                fireConferenceChanged();
            }
            return true;
        }
        return false;
    }

    /**
     * Resets the conference that this connection is a part of.
     * @hide
     */
    public final void resetConference() {
        if (mConference != null) {
            Log.d(this, "Conference reset");
            mConference = null;
            fireConferenceChanged();
        }
    }

    /**
     * Set some extras that can be associated with this {@code Connection}.
     * <p>
     * New or existing keys are replaced in the {@code Connection} extras.  Keys which are no longer
     * in the new extras, but were present the last time {@code setExtras} was called are removed.
     * <p>
     * Alternatively you may use the {@link #putExtras(Bundle)}, and
     * {@link #removeExtras(String...)} methods to modify the extras.
     * <p>
     * No assumptions should be made as to how an In-Call UI or service will handle these extras.
     * Keys should be fully qualified (e.g., com.example.MY_EXTRA) to avoid conflicts.
     *
     * @param extras The extras associated with this {@code Connection}.
     */
    public final void setExtras(@Nullable Bundle extras) {
        checkImmutable();

        // Add/replace any new or changed extras values.
        putExtras(extras);

        // If we have used "setExtras" in the past, compare the key set from the last invocation to
        // the current one and remove any keys that went away.
        if (mPreviousExtraKeys != null) {
            List<String> toRemove = new ArrayList<String>();
            for (String oldKey : mPreviousExtraKeys) {
                if (extras == null || !extras.containsKey(oldKey)) {
                    toRemove.add(oldKey);
                }
            }
            if (!toRemove.isEmpty()) {
                removeExtras(toRemove);
            }
        }

        // Track the keys the last time set called setExtras.  This way, the next time setExtras is
        // called we can see if the caller has removed any extras values.
        if (mPreviousExtraKeys == null) {
            mPreviousExtraKeys = new ArraySet<String>();
        }
        mPreviousExtraKeys.clear();
        if (extras != null) {
            mPreviousExtraKeys.addAll(extras.keySet());
        }
    }

    /**
     * Adds some extras to this {@code Connection}.  Existing keys are replaced and new ones are
     * added.
     * <p>
     * No assumptions should be made as to how an In-Call UI or service will handle these extras.
     * Keys should be fully qualified (e.g., com.example.MY_EXTRA) to avoid conflicts.
     *
     * @param extras The extras to add.
     */
    public final void putExtras(@NonNull Bundle extras) {
        checkImmutable();
        if (extras == null) {
            return;
        }
        // Creating a duplicate bundle so we don't have to synchronize on mExtrasLock while calling
        // the listeners.
        Bundle listenerExtras;
        synchronized (mExtrasLock) {
            if (mExtras == null) {
                mExtras = new Bundle();
            }
            mExtras.putAll(extras);
            listenerExtras = new Bundle(mExtras);
        }
        for (Listener l : mListeners) {
            // Create a new clone of the extras for each listener so that they don't clobber
            // each other
            l.onExtrasChanged(this, new Bundle(listenerExtras));
        }
    }

    /**
     * Removes extras from this {@code Connection}.
     *
     * @param keys The keys of the extras to remove.
     */
    public final void removeExtras(List<String> keys) {
        synchronized (mExtrasLock) {
            if (mExtras != null) {
                for (String key : keys) {
                    mExtras.remove(key);
                }
            }
        }
        List<String> unmodifiableKeys = Collections.unmodifiableList(keys);
        for (Listener l : mListeners) {
            l.onExtrasRemoved(this, unmodifiableKeys);
        }
    }

    /**
     * Removes extras from this {@code Connection}.
     *
     * @param keys The keys of the extras to remove.
     */
    public final void removeExtras(String ... keys) {
        removeExtras(Arrays.asList(keys));
    }

    /**
     * Sets the audio route (speaker, bluetooth, etc...).  When this request is honored, there will
     * be change to the {@link #getCallAudioState()}.
     * <p>
     * Used by self-managed {@link ConnectionService}s which wish to change the audio route for a
     * self-managed {@link Connection} (see {@link PhoneAccount#CAPABILITY_SELF_MANAGED}.)
     * <p>
     * See also {@link InCallService#setAudioRoute(int)}.
     *
     * @param route The audio route to use (one of {@link CallAudioState#ROUTE_BLUETOOTH},
     *              {@link CallAudioState#ROUTE_EARPIECE}, {@link CallAudioState#ROUTE_SPEAKER}, or
     *              {@link CallAudioState#ROUTE_WIRED_HEADSET}).
     */
    public final void setAudioRoute(int route) {
        for (Listener l : mListeners) {
            l.onAudioRouteChanged(this, route, null);
        }
    }

    /**
     * Request audio routing to a specific bluetooth device. Calling this method may result in
     * the device routing audio to a different bluetooth device than the one specified if the
     * bluetooth stack is unable to route audio to the requested device.
     * A list of available devices can be obtained via
     * {@link CallAudioState#getSupportedBluetoothDevices()}
     *
     * <p>
     * Used by self-managed {@link ConnectionService}s which wish to use bluetooth audio for a
     * self-managed {@link Connection} (see {@link PhoneAccount#CAPABILITY_SELF_MANAGED}.)
     * <p>
     * See also {@link InCallService#requestBluetoothAudio(BluetoothDevice)}
     * @param bluetoothDevice The bluetooth device to connect to.
     */
    public void requestBluetoothAudio(@NonNull BluetoothDevice bluetoothDevice) {
        for (Listener l : mListeners) {
            l.onAudioRouteChanged(this, CallAudioState.ROUTE_BLUETOOTH,
                    bluetoothDevice.getAddress());
        }
    }

    /**
     * Informs listeners that a previously requested RTT session via
     * {@link ConnectionRequest#isRequestingRtt()} or
     * {@link #onStartRtt(RttTextStream)} has succeeded.
     */
    public final void sendRttInitiationSuccess() {
        mListeners.forEach((l) -> l.onRttInitiationSuccess(Connection.this));
    }

    /**
     * Informs listeners that a previously requested RTT session via
     * {@link ConnectionRequest#isRequestingRtt()} or {@link #onStartRtt(RttTextStream)}
     * has failed.
     * @param reason One of the reason codes defined in {@link RttModifyStatus}, with the
     *               exception of {@link RttModifyStatus#SESSION_MODIFY_REQUEST_SUCCESS}.
     */
    public final void sendRttInitiationFailure(int reason) {
        mListeners.forEach((l) -> l.onRttInitiationFailure(Connection.this, reason));
    }

    /**
     * Informs listeners that a currently active RTT session has been terminated by the remote
     * side of the coll.
     */
    public final void sendRttSessionRemotelyTerminated() {
        mListeners.forEach((l) -> l.onRttSessionRemotelyTerminated(Connection.this));
    }

    /**
     * Informs listeners that the remote side of the call has requested an upgrade to include an
     * RTT session in the call.
     */
    public final void sendRemoteRttRequest() {
        mListeners.forEach((l) -> l.onRemoteRttRequest(Connection.this));
    }

    /**
     * Notifies this Connection that the {@link #getAudioState()} property has a new value.
     *
     * @param state The new connection audio state.
     * @deprecated Use {@link #onCallAudioStateChanged(CallAudioState)} instead.
     * @hide
     */
    @SystemApi
    @Deprecated
    public void onAudioStateChanged(AudioState state) {}

    /**
     * Notifies this Connection that the {@link #getCallAudioState()} property has a new value.
     *
     * @param state The new connection audio state.
     */
    public void onCallAudioStateChanged(CallAudioState state) {}

    /**
     * Notifies this Connection of an internal state change. This method is called after the
     * state is changed.
     *
     * @param state The new state, one of the {@code STATE_*} constants.
     */
    public void onStateChanged(int state) {}

    /**
     * Notifies this Connection of a request to play a DTMF tone.
     *
     * @param c A DTMF character.
     */
    public void onPlayDtmfTone(char c) {}

    /**
     * Notifies this Connection of a request to stop any currently playing DTMF tones.
     */
    public void onStopDtmfTone() {}

    /**
     * Notifies this Connection of a request to disconnect.
     */
    public void onDisconnect() {}

    /**
     * Notifies this Connection of a request to disconnect a participant of the conference managed
     * by the connection.
     *
     * @param endpoint the {@link Uri} of the participant to disconnect.
     * @hide
     */
    public void onDisconnectConferenceParticipant(Uri endpoint) {}

    /**
     * Notifies this Connection of a request to separate from its parent conference.
     */
    public void onSeparate() {}

    /**
     * Supports initiation of a conference call by directly adding participants to an ongoing call.
     *
     * @param participants with which conference call will be formed.
     * @hide
     */
    public void onAddConferenceParticipants(@NonNull List<Uri> participants) {}

    /**
     * Notifies this Connection of a request to abort.
     */
    public void onAbort() {}

    /**
     * Notifies this Connection of a request to hold.
     */
    public void onHold() {}

    /**
     * Notifies this Connection of a request to exit a hold state.
     */
    public void onUnhold() {}

    /**
     * Notifies this Connection, which is in {@link #STATE_RINGING}, of
     * a request to accept.
     * <p>
     * For managed {@link ConnectionService}s, this will be called when the user answers a call via
     * the default dialer's {@link InCallService}.
     * <p>
     * Although a self-managed {@link ConnectionService} provides its own incoming call UI, the
     * Telecom framework may request that the call is answered in the following circumstances:
     * <ul>
     *     <li>The user chooses to answer an incoming call via a Bluetooth device.</li>
     *     <li>A car mode {@link InCallService} is in use which has declared
     *     {@link TelecomManager#METADATA_INCLUDE_SELF_MANAGED_CALLS} in its manifest.  Such an
     *     {@link InCallService} will be able to see calls from self-managed
     *     {@link ConnectionService}s, and will be able to display an incoming call UI on their
     *     behalf.</li>
     * </ul>
     * @param videoState The video state in which to answer the connection.
     */
    public void onAnswer(int videoState) {}

    /**
     * Notifies this Connection, which is in {@link #STATE_RINGING}, of
     * a request to accept.
     * <p>
     * For managed {@link ConnectionService}s, this will be called when the user answers a call via
     * the default dialer's {@link InCallService}.
     * <p>
     * Although a self-managed {@link ConnectionService} provides its own incoming call UI, the
     * Telecom framework may request that the call is answered in the following circumstances:
     * <ul>
     *     <li>The user chooses to answer an incoming call via a Bluetooth device.</li>
     *     <li>A car mode {@link InCallService} is in use which has declared
     *     {@link TelecomManager#METADATA_INCLUDE_SELF_MANAGED_CALLS} in its manifest.  Such an
     *     {@link InCallService} will be able to see calls from self-managed
     *     {@link ConnectionService}s, and will be able to display an incoming call UI on their
     *     behalf.</li>
     * </ul>
     */
    public void onAnswer() {
        onAnswer(VideoProfile.STATE_AUDIO_ONLY);
    }

    /**
     * Notifies this Connection, which is in {@link #STATE_RINGING}, of
     * a request to deflect.
     */
    public void onDeflect(Uri address) {}

    /**
     * Notifies this Connection, which is in {@link #STATE_RINGING}, of
     * a request to reject.
     * <p>
     * For managed {@link ConnectionService}s, this will be called when the user rejects a call via
     * the default dialer's {@link InCallService}.
     * <p>
     * Although a self-managed {@link ConnectionService} provides its own incoming call UI, the
     * Telecom framework may request that the call is rejected in the following circumstances:
     * <ul>
     *     <li>The user chooses to reject an incoming call via a Bluetooth device.</li>
     *     <li>A car mode {@link InCallService} is in use which has declared
     *     {@link TelecomManager#METADATA_INCLUDE_SELF_MANAGED_CALLS} in its manifest.  Such an
     *     {@link InCallService} will be able to see calls from self-managed
     *     {@link ConnectionService}s, and will be able to display an incoming call UI on their
     *     behalf.</li>
     * </ul>
     */
    public void onReject() {}

    /**
     * Notifies this Connection, which is in {@link #STATE_RINGING}, of a request to reject.
     * <p>
     * For managed {@link ConnectionService}s, this will be called when the user rejects a call via
     * the default dialer's {@link InCallService} using {@link Call#reject(int)}.
     * @param rejectReason the reason the user provided for rejecting the call.
     */
    public void onReject(@android.telecom.Call.RejectReason int rejectReason) {
        // to be implemented by ConnectionService.
    }

    /**
     * Notifies this Connection, which is in {@link #STATE_RINGING}, of
     * a request to reject with a message.
     */
    public void onReject(String replyMessage) {}

    /**
     * Notifies this Connection, a request to transfer to a target number.
     * @param number the number to transfer this {@link Connection} to.
     * @param isConfirmationRequired when {@code true}, the {@link ConnectionService}
     * should wait until the transfer has successfully completed before disconnecting
     * the current {@link Connection}.
     * When {@code false}, the {@link ConnectionService} should signal the network to
     * perform the transfer, but should immediately disconnect the call regardless of
     * the outcome of the transfer.
     * @hide
     */
    public void onTransfer(@NonNull Uri number, boolean isConfirmationRequired) {}

    /**
     * Notifies this Connection, a request to transfer to another Connection.
     * @param otherConnection the {@link Connection} to transfer this call to.
     * @hide
     */
    public void onTransfer(@NonNull Connection otherConnection) {}

    /**
     * Notifies this Connection of a request to silence the ringer.
     * <p>
     * The ringer may be silenced by any of the following methods:
     * <ul>
     *     <li>{@link TelecomManager#silenceRinger()}</li>
     *     <li>The user presses the volume-down button while a call is ringing.</li>
     * </ul>
     * <p>
     * Self-managed {@link ConnectionService} implementations should override this method in their
     * {@link Connection} implementation and implement logic to silence their app's ringtone.  If
     * your app set the ringtone as part of the incoming call {@link Notification} (see
     * {@link #onShowIncomingCallUi()}), it should re-post the notification now, except call
     * {@link android.app.Notification.Builder#setOnlyAlertOnce(boolean)} with {@code true}.  This
     * will ensure the ringtone sound associated with your {@link android.app.NotificationChannel}
     * stops playing.
     */
    public void onSilence() {}

    /**
     * Notifies this Connection whether the user wishes to proceed with the post-dial DTMF codes.
     */
    public void onPostDialContinue(boolean proceed) {}

    /**
     * Notifies this Connection of a request to pull an external call to the local device.
     * <p>
     * The {@link InCallService} issues a request to pull an external call to the local device via
     * {@link Call#pullExternalCall()}.
     * <p>
     * For a Connection to be pulled, both the {@link Connection#CAPABILITY_CAN_PULL_CALL}
     * capability and {@link Connection#PROPERTY_IS_EXTERNAL_CALL} property bits must be set.
     * <p>
     * For more information on external calls, see {@link Connection#PROPERTY_IS_EXTERNAL_CALL}.
     */
    public void onPullExternalCall() {}

    /**
     * Notifies this Connection of a {@link Call} event initiated from an {@link InCallService}.
     * <p>
     * The {@link InCallService} issues a Call event via {@link Call#sendCallEvent(String, Bundle)}.
     * <p>
     * Where possible, the Connection should make an attempt to handle {@link Call} events which
     * are part of the {@code android.telecom.*} namespace.  The Connection should ignore any events
     * it does not wish to handle.  Unexpected events should be handled gracefully, as it is
     * possible that a {@link InCallService} has defined its own Call events which a Connection is
     * not aware of.
     * <p>
     * See also {@link Call#sendCallEvent(String, Bundle)}.
     *
     * @param event The call event.
     * @param extras Extras associated with the call event.
     */
    public void onCallEvent(String event, Bundle extras) {}

    /**
     * Notifies this {@link Connection} that a handover has completed.
     * <p>
     * A handover is initiated with {@link android.telecom.Call#handoverTo(PhoneAccountHandle, int,
     * Bundle)} on the initiating side of the handover, and
     * {@link TelecomManager#acceptHandover(Uri, int, PhoneAccountHandle)}.
     */
    public void onHandoverComplete() {}

    /**
     * Notifies this {@link Connection} of a change to the extras made outside the
     * {@link ConnectionService}.
     * <p>
     * These extras changes can originate from Telecom itself, or from an {@link InCallService} via
     * the {@link android.telecom.Call#putExtras(Bundle)} and
     * {@link Call#removeExtras(List)}.
     *
     * @param extras The new extras bundle.
     */
    public void onExtrasChanged(Bundle extras) {}

    /**
     * Notifies this {@link Connection} that its {@link ConnectionService} is responsible for
     * displaying its incoming call user interface for the {@link Connection}.
     * <p>
     * Will only be called for incoming calls added via a self-managed {@link ConnectionService}
     * (see {@link PhoneAccount#CAPABILITY_SELF_MANAGED}), where the {@link ConnectionService}
     * should show its own incoming call user interface.
     * <p>
     * Where there are ongoing calls in other self-managed {@link ConnectionService}s, or in a
     * regular {@link ConnectionService}, and it is not possible to hold these other calls, the
     * Telecom framework will display its own incoming call user interface to allow the user to
     * choose whether to answer the new incoming call and disconnect other ongoing calls, or to
     * reject the new incoming call.
     * <p>
     * You should trigger the display of the incoming call user interface for your application by
     * showing a {@link Notification} with a full-screen {@link Intent} specified.
     *
     * In your application code, you should create a {@link android.app.NotificationChannel} for
     * incoming call notifications from your app:
     * <pre><code>
     * NotificationChannel channel = new NotificationChannel(YOUR_CHANNEL_ID, "Incoming Calls",
     *          NotificationManager.IMPORTANCE_MAX);
     * // other channel setup stuff goes here.
     *
     * // We'll use the default system ringtone for our incoming call notification channel.  You can
     * // use your own audio resource here.
     * Uri ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
     * channel.setSound(ringtoneUri, new AudioAttributes.Builder()
     *          // Setting the AudioAttributes is important as it identifies the purpose of your
     *          // notification sound.
     *          .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
     *          .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
     *      .build());
     *
     * NotificationManager mgr = getSystemService(NotificationManager.class);
     * mgr.createNotificationChannel(channel);
     * </code></pre>
     * When it comes time to post a notification for your incoming call, ensure it uses your
     * incoming call {@link android.app.NotificationChannel}.
     * <pre><code>
     *     // Create an intent which triggers your fullscreen incoming call user interface.
     *     Intent intent = new Intent(Intent.ACTION_MAIN, null);
     *     intent.setFlags(Intent.FLAG_ACTIVITY_NO_USER_ACTION | Intent.FLAG_ACTIVITY_NEW_TASK);
     *     intent.setClass(context, YourIncomingCallActivity.class);
     *     PendingIntent pendingIntent = PendingIntent.getActivity(context, 1, intent, 0);
     *
     *     // Build the notification as an ongoing high priority item; this ensures it will show as
     *     // a heads up notification which slides down over top of the current content.
     *     final Notification.Builder builder = new Notification.Builder(context);
     *     builder.setOngoing(true);
     *     builder.setPriority(Notification.PRIORITY_HIGH);
     *
     *     // Set notification content intent to take user to fullscreen UI if user taps on the
     *     // notification body.
     *     builder.setContentIntent(pendingIntent);
     *     // Set full screen intent to trigger display of the fullscreen UI when the notification
     *     // manager deems it appropriate.
     *     builder.setFullScreenIntent(pendingIntent, true);
     *
     *     // Setup notification content.
     *     builder.setSmallIcon( yourIconResourceId );
     *     builder.setContentTitle("Your notification title");
     *     builder.setContentText("Your notification content.");
     *
     *     // Set notification as insistent to cause your ringtone to loop.
     *     Notification notification = builder.build();
     *     notification.flags |= Notification.FLAG_INSISTENT;
     *
     *     // Use builder.addAction(..) to add buttons to answer or reject the call.
     *     NotificationManager notificationManager = mContext.getSystemService(
     *         NotificationManager.class);
     *     notificationManager.notify(YOUR_CHANNEL_ID, YOUR_TAG, YOUR_ID, notification);
     * </code></pre>
     */
    public void onShowIncomingCallUi() {}

    /**
     * Notifies this {@link Connection} that the user has requested an RTT session.
     * The connection service should call {@link #sendRttInitiationSuccess} or
     * {@link #sendRttInitiationFailure} to inform Telecom of the success or failure of the
     * request, respectively.
     * @param rttTextStream The object that should be used to send text to or receive text from
     *                      the in-call app.
     */
    public void onStartRtt(@NonNull RttTextStream rttTextStream) {}

    /**
     * Notifies this {@link Connection} that it should terminate any existing RTT communication
     * channel. No response to Telecom is needed for this method.
     */
    public void onStopRtt() {}

    /**
     * Notifies this connection of a response to a previous remotely-initiated RTT upgrade
     * request sent via {@link #sendRemoteRttRequest}. Acceptance of the request is
     * indicated by the supplied {@link RttTextStream} being non-null, and rejection is
     * indicated by {@code rttTextStream} being {@code null}
     * @param rttTextStream The object that should be used to send text to or receive text from
     *                      the in-call app.
     */
    public void handleRttUpgradeResponse(@Nullable RttTextStream rttTextStream) {}

    static String toLogSafePhoneNumber(String number) {
        // For unknown number, log empty string.
        if (number == null) {
            return "";
        }

        if (PII_DEBUG) {
            // When PII_DEBUG is true we emit PII.
            return number;
        }

        // Do exactly same thing as Uri#toSafeString() does, which will enable us to compare
        // sanitized phone numbers.
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < number.length(); i++) {
            char c = number.charAt(i);
            if (c == '-' || c == '@' || c == '.') {
                builder.append(c);
            } else {
                builder.append('x');
            }
        }
        return builder.toString();
    }

    private void setState(int state) {
        checkImmutable();
        if (mState == STATE_DISCONNECTED && mState != state) {
            Log.d(this, "Connection already DISCONNECTED; cannot transition out of this state.");
            return;
        }
        if (mState != state) {
            Log.d(this, "setState: %s", stateToString(state));
            mState = state;
            onStateChanged(state);
            for (Listener l : mListeners) {
                l.onStateChanged(this, state);
            }
        }
    }

    private static class FailureSignalingConnection extends Connection {
        private boolean mImmutable = false;
        public FailureSignalingConnection(DisconnectCause disconnectCause) {
            setDisconnected(disconnectCause);
            mImmutable = true;
        }

        public void checkImmutable() {
            if (mImmutable) {
                throw new UnsupportedOperationException("Connection is immutable");
            }
        }
    }

    /**
     * Return a {@code Connection} which represents a failed connection attempt. The returned
     * {@code Connection} will have a {@link android.telecom.DisconnectCause} and as specified,
     * and a {@link #getState()} of {@link #STATE_DISCONNECTED}.
     * <p>
     * The returned {@code Connection} can be assumed to {@link #destroy()} itself when appropriate,
     * so users of this method need not maintain a reference to its return value to destroy it.
     *
     * @param disconnectCause The disconnect cause, ({@see android.telecomm.DisconnectCause}).
     * @return A {@code Connection} which indicates failure.
     */
    public static Connection createFailedConnection(DisconnectCause disconnectCause) {
        return new FailureSignalingConnection(disconnectCause);
    }

    /**
     * Override to throw an {@link UnsupportedOperationException} if this {@code Connection} is
     * not intended to be mutated, e.g., if it is a marker for failure. Only for framework use;
     * this should never be un-@hide-den.
     *
     * @hide
     */
    public void checkImmutable() {}

    /**
     * Return a {@code Connection} which represents a canceled connection attempt. The returned
     * {@code Connection} will have state {@link #STATE_DISCONNECTED}, and cannot be moved out of
     * that state. This connection should not be used for anything, and no other
     * {@code Connection}s should be attempted.
     * <p>
     * so users of this method need not maintain a reference to its return value to destroy it.
     *
     * @return A {@code Connection} which indicates that the underlying connection should
     * be canceled.
     */
    public static Connection createCanceledConnection() {
        return new FailureSignalingConnection(new DisconnectCause(DisconnectCause.CANCELED));
    }

    private final void fireOnConferenceableConnectionsChanged() {
        for (Listener l : mListeners) {
            l.onConferenceablesChanged(this, getConferenceables());
        }
    }

    private final void fireConferenceChanged() {
        for (Listener l : mListeners) {
            l.onConferenceChanged(this, mConference);
        }
    }

    private final void clearConferenceableList() {
        for (Conferenceable c : mConferenceables) {
            if (c instanceof Connection) {
                Connection connection = (Connection) c;
                connection.removeConnectionListener(mConnectionDeathListener);
            } else if (c instanceof Conference) {
                Conference conference = (Conference) c;
                conference.removeListener(mConferenceDeathListener);
            }
        }
        mConferenceables.clear();
    }

    /**
     * Handles a change to extras received from Telecom.
     *
     * @param extras The new extras.
     * @hide
     */
    final void handleExtrasChanged(Bundle extras) {
        Bundle b = null;
        synchronized (mExtrasLock) {
            mExtras = extras;
            if (mExtras != null) {
                b = new Bundle(mExtras);
            }
        }
        onExtrasChanged(b);
    }

    /**
     * Called by a {@link ConnectionService} to notify Telecom that a {@link Conference#onMerge()}
     * request failed.
     */
    public final void notifyConferenceMergeFailed() {
        for (Listener l : mListeners) {
            l.onConferenceMergeFailed(this);
        }
    }

    /**
     * Notifies listeners when phone account is changed. For example, when the PhoneAccount is
     * changed due to an emergency call being redialed.
     * @param pHandle The new PhoneAccountHandle for this connection.
     * @hide
     */
    public void notifyPhoneAccountChanged(PhoneAccountHandle pHandle) {
        for (Listener l : mListeners) {
            l.onPhoneAccountChanged(this, pHandle);
        }
    }

    /**
     * Sets the {@link PhoneAccountHandle} associated with this connection.
     * <p>
     * Used by the Telephony {@link ConnectionService} to handle changes to the {@link PhoneAccount}
     * which take place after call initiation (important for emergency calling scenarios).
     *
     * @param phoneAccountHandle the phone account handle to set.
     * @hide
     */
    @SystemApi
    @TestApi
    public void setPhoneAccountHandle(@NonNull PhoneAccountHandle phoneAccountHandle) {
        if (mPhoneAccountHandle != phoneAccountHandle) {
            mPhoneAccountHandle = phoneAccountHandle;
            notifyPhoneAccountChanged(phoneAccountHandle);
        }
    }

    /**
     * Returns the {@link PhoneAccountHandle} associated with this connection.
     * <p>
     * Used by the Telephony {@link ConnectionService} to handle changes to the {@link PhoneAccount}
     * which take place after call initiation (important for emergency calling scenarios).
     *
     * @return the phone account handle specified via
     * {@link #setPhoneAccountHandle(PhoneAccountHandle)}, or {@code null} if none was set.
     * @hide
     */
    @SystemApi
    @TestApi
    public @Nullable PhoneAccountHandle getPhoneAccountHandle() {
        return mPhoneAccountHandle;
    }

    /**
     * Sends an event associated with this {@code Connection} with associated event extras to the
     * {@link InCallService}.
     * <p>
     * Connection events are used to communicate point in time information from a
     * {@link ConnectionService} to a {@link InCallService} implementations.  An example of a
     * custom connection event includes notifying the UI when a WIFI call has been handed over to
     * LTE, which the InCall UI might use to inform the user that billing charges may apply.  The
     * Android Telephony framework will send the {@link #EVENT_CALL_MERGE_FAILED} connection event
     * when a call to {@link Call#mergeConference()} has failed to complete successfully.  A
     * connection event could also be used to trigger UI in the {@link InCallService} which prompts
     * the user to make a choice (e.g. whether they want to incur roaming costs for making a call),
     * which is communicated back via {@link Call#sendCallEvent(String, Bundle)}.
     * <p>
     * Events are exposed to {@link InCallService} implementations via
     * {@link Call.Callback#onConnectionEvent(Call, String, Bundle)}.
     * <p>
     * No assumptions should be made as to how an In-Call UI or service will handle these events.
     * The {@link ConnectionService} must assume that the In-Call UI could even chose to ignore
     * some events altogether.
     * <p>
     * Events should be fully qualified (e.g. {@code com.example.event.MY_EVENT}) to avoid
     * conflicts between {@link ConnectionService} implementations.  Further, custom
     * {@link ConnectionService} implementations shall not re-purpose events in the
     * {@code android.*} namespace, nor shall they define new event types in this namespace.  When
     * defining a custom event type, ensure the contents of the extras {@link Bundle} is clearly
     * defined.  Extra keys for this bundle should be named similar to the event type (e.g.
     * {@code com.example.extra.MY_EXTRA}).
     * <p>
     *  When defining events and the associated extras, it is important to keep their behavior
     * consistent when the associated {@link ConnectionService} is updated.  Support for deprecated
     * events/extras should me maintained to ensure backwards compatibility with older
     * {@link InCallService} implementations which were built to support the older behavior.
     *
     * @param event The connection event.
     * @param extras Optional bundle containing extra information associated with the event.
     */
    public void sendConnectionEvent(String event, Bundle extras) {
        for (Listener l : mListeners) {
            l.onConnectionEvent(this, event, extras);
        }
    }

    /**
     * @return The direction of the call.
     * @hide
     */
    public final @Call.Details.CallDirection int getCallDirection() {
        return mCallDirection;
    }

    /**
     * Sets the direction of this connection.
     * <p>
     * Used when calling {@link ConnectionService#addExistingConnection} to specify the existing
     * call direction.
     *
     * @param callDirection The direction of this connection.
     * @hide
     */
    @SystemApi
    @TestApi
    public void setCallDirection(@Call.Details.CallDirection int callDirection) {
        mCallDirection = callDirection;
    }

    /**
     * Gets the verification status for the phone number of an incoming call as identified in
     * ATIS-1000082.
     * @return the verification status.
     */
    public final @VerificationStatus int getCallerNumberVerificationStatus() {
        return mCallerNumberVerificationStatus;
    }

    /**
     * Sets the verification status for the phone number of an incoming call as identified in
     * ATIS-1000082.
     * <p>
     * This property can only be set at the time of creation of a {@link Connection} being returned
     * by
     * {@link ConnectionService#onCreateIncomingConnection(PhoneAccountHandle, ConnectionRequest)}.
     */
    public final void setCallerNumberVerificationStatus(
            @VerificationStatus int callerNumberVerificationStatus) {
        mCallerNumberVerificationStatus = callerNumberVerificationStatus;
    }
}
