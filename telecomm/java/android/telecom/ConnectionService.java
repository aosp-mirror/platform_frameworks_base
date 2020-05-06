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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SdkConstant;
import android.annotation.SystemApi;
import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.telecom.Logging.Session;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.os.SomeArgs;
import com.android.internal.telecom.IConnectionService;
import com.android.internal.telecom.IConnectionServiceAdapter;
import com.android.internal.telecom.RemoteServiceCallback;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * An abstract service that should be implemented by any apps which either:
 * <ol>
 *     <li>Can make phone calls (VoIP or otherwise) and want those calls to be integrated into the
 *     built-in phone app.  Referred to as a <b>system managed</b> {@link ConnectionService}.</li>
 *     <li>Are a standalone calling app and don't want their calls to be integrated into the
 *     built-in phone app.  Referred to as a <b>self managed</b> {@link ConnectionService}.</li>
 * </ol>
 * Once implemented, the {@link ConnectionService} needs to take the following steps so that Telecom
 * will bind to it:
 * <p>
 * 1. <i>Registration in AndroidManifest.xml</i>
 * <br/>
 * <pre>
 * &lt;service android:name="com.example.package.MyConnectionService"
 *    android:label="@string/some_label_for_my_connection_service"
 *    android:permission="android.permission.BIND_TELECOM_CONNECTION_SERVICE"&gt;
 *  &lt;intent-filter&gt;
 *   &lt;action android:name="android.telecom.ConnectionService" /&gt;
 *  &lt;/intent-filter&gt;
 * &lt;/service&gt;
 * </pre>
 * <p>
 * 2. <i> Registration of {@link PhoneAccount} with {@link TelecomManager}.</i>
 * <br/>
 * See {@link PhoneAccount} and {@link TelecomManager#registerPhoneAccount} for more information.
 * <p>
 * System managed {@link ConnectionService}s must be enabled by the user in the phone app settings
 * before Telecom will bind to them.  Self-managed {@link ConnectionService}s must be granted the
 * appropriate permission before Telecom will bind to them.
 * <p>
 * Once registered and enabled by the user in the phone app settings or granted permission, telecom
 * will bind to a {@link ConnectionService} implementation when it wants that
 * {@link ConnectionService} to place a call or the service has indicated that is has an incoming
 * call through {@link TelecomManager#addNewIncomingCall}. The {@link ConnectionService} can then
 * expect a call to {@link #onCreateIncomingConnection} or {@link #onCreateOutgoingConnection}
 * wherein it should provide a new instance of a {@link Connection} object.  It is through this
 * {@link Connection} object that telecom receives state updates and the {@link ConnectionService}
 * receives call-commands such as answer, reject, hold and disconnect.
 * <p>
 * When there are no more live calls, telecom will unbind from the {@link ConnectionService}.
 */
public abstract class ConnectionService extends Service {
    /**
     * The {@link Intent} that must be declared as handled by the service.
     */
    @SdkConstant(SdkConstant.SdkConstantType.SERVICE_ACTION)
    public static final String SERVICE_INTERFACE = "android.telecom.ConnectionService";

    /**
     * Boolean extra used by Telecom to inform a {@link ConnectionService} that the purpose of it
     * being asked to create a new outgoing {@link Connection} is to perform a handover of an
     * ongoing call on the device from another {@link PhoneAccount}/{@link ConnectionService}.  Will
     * be specified in the {@link ConnectionRequest#getExtras()} passed by Telecom when
     * {@link #onCreateOutgoingConnection(PhoneAccountHandle, ConnectionRequest)} is called.
     * <p>
     * When your {@link ConnectionService} receives this extra, it should communicate the fact that
     * this is a handover to the other device's matching {@link ConnectionService}.  That
     * {@link ConnectionService} will continue the handover using
     * {@link TelecomManager#addNewIncomingCall(PhoneAccountHandle, Bundle)}, specifying
     * {@link TelecomManager#EXTRA_IS_HANDOVER}.  Telecom will match the phone numbers of the
     * handover call on the other device with ongoing calls for {@link ConnectionService}s which
     * support {@link PhoneAccount#EXTRA_SUPPORTS_HANDOVER_FROM}.
     * @hide
     */
    public static final String EXTRA_IS_HANDOVER = TelecomManager.EXTRA_IS_HANDOVER;

    // Flag controlling whether PII is emitted into the logs
    private static final boolean PII_DEBUG = Log.isLoggable(android.util.Log.DEBUG);

    // Session Definitions
    private static final String SESSION_HANDLER = "H.";
    private static final String SESSION_ADD_CS_ADAPTER = "CS.aCSA";
    private static final String SESSION_REMOVE_CS_ADAPTER = "CS.rCSA";
    private static final String SESSION_CREATE_CONN = "CS.crCo";
    private static final String SESSION_CREATE_CONN_COMPLETE = "CS.crCoC";
    private static final String SESSION_CREATE_CONN_FAILED = "CS.crCoF";
    private static final String SESSION_ABORT = "CS.ab";
    private static final String SESSION_ANSWER = "CS.an";
    private static final String SESSION_ANSWER_VIDEO = "CS.anV";
    private static final String SESSION_DEFLECT = "CS.def";
    private static final String SESSION_TRANSFER = "CS.trans";
    private static final String SESSION_CONSULTATIVE_TRANSFER = "CS.cTrans";
    private static final String SESSION_REJECT = "CS.r";
    private static final String SESSION_REJECT_MESSAGE = "CS.rWM";
    private static final String SESSION_SILENCE = "CS.s";
    private static final String SESSION_DISCONNECT = "CS.d";
    private static final String SESSION_HOLD = "CS.h";
    private static final String SESSION_UNHOLD = "CS.u";
    private static final String SESSION_CALL_AUDIO_SC = "CS.cASC";
    private static final String SESSION_PLAY_DTMF = "CS.pDT";
    private static final String SESSION_STOP_DTMF = "CS.sDT";
    private static final String SESSION_CONFERENCE = "CS.c";
    private static final String SESSION_SPLIT_CONFERENCE = "CS.sFC";
    private static final String SESSION_MERGE_CONFERENCE = "CS.mC";
    private static final String SESSION_SWAP_CONFERENCE = "CS.sC";
    private static final String SESSION_ADD_PARTICIPANT = "CS.aP";
    private static final String SESSION_POST_DIAL_CONT = "CS.oPDC";
    private static final String SESSION_PULL_EXTERNAL_CALL = "CS.pEC";
    private static final String SESSION_SEND_CALL_EVENT = "CS.sCE";
    private static final String SESSION_HANDOVER_COMPLETE = "CS.hC";
    private static final String SESSION_EXTRAS_CHANGED = "CS.oEC";
    private static final String SESSION_START_RTT = "CS.+RTT";
    private static final String SESSION_UPDATE_RTT_PIPES = "CS.uRTT";
    private static final String SESSION_STOP_RTT = "CS.-RTT";
    private static final String SESSION_RTT_UPGRADE_RESPONSE = "CS.rTRUR";
    private static final String SESSION_CONNECTION_SERVICE_FOCUS_LOST = "CS.cSFL";
    private static final String SESSION_CONNECTION_SERVICE_FOCUS_GAINED = "CS.cSFG";
    private static final String SESSION_HANDOVER_FAILED = "CS.haF";
    private static final String SESSION_CREATE_CONF = "CS.crConf";
    private static final String SESSION_CREATE_CONF_COMPLETE = "CS.crConfC";
    private static final String SESSION_CREATE_CONF_FAILED = "CS.crConfF";

    private static final int MSG_ADD_CONNECTION_SERVICE_ADAPTER = 1;
    private static final int MSG_CREATE_CONNECTION = 2;
    private static final int MSG_ABORT = 3;
    private static final int MSG_ANSWER = 4;
    private static final int MSG_REJECT = 5;
    private static final int MSG_DISCONNECT = 6;
    private static final int MSG_HOLD = 7;
    private static final int MSG_UNHOLD = 8;
    private static final int MSG_ON_CALL_AUDIO_STATE_CHANGED = 9;
    private static final int MSG_PLAY_DTMF_TONE = 10;
    private static final int MSG_STOP_DTMF_TONE = 11;
    private static final int MSG_CONFERENCE = 12;
    private static final int MSG_SPLIT_FROM_CONFERENCE = 13;
    private static final int MSG_ON_POST_DIAL_CONTINUE = 14;
    private static final int MSG_REMOVE_CONNECTION_SERVICE_ADAPTER = 16;
    private static final int MSG_ANSWER_VIDEO = 17;
    private static final int MSG_MERGE_CONFERENCE = 18;
    private static final int MSG_SWAP_CONFERENCE = 19;
    private static final int MSG_REJECT_WITH_MESSAGE = 20;
    private static final int MSG_SILENCE = 21;
    private static final int MSG_PULL_EXTERNAL_CALL = 22;
    private static final int MSG_SEND_CALL_EVENT = 23;
    private static final int MSG_ON_EXTRAS_CHANGED = 24;
    private static final int MSG_CREATE_CONNECTION_FAILED = 25;
    private static final int MSG_ON_START_RTT = 26;
    private static final int MSG_ON_STOP_RTT = 27;
    private static final int MSG_RTT_UPGRADE_RESPONSE = 28;
    private static final int MSG_CREATE_CONNECTION_COMPLETE = 29;
    private static final int MSG_CONNECTION_SERVICE_FOCUS_LOST = 30;
    private static final int MSG_CONNECTION_SERVICE_FOCUS_GAINED = 31;
    private static final int MSG_HANDOVER_FAILED = 32;
    private static final int MSG_HANDOVER_COMPLETE = 33;
    private static final int MSG_DEFLECT = 34;
    private static final int MSG_CREATE_CONFERENCE = 35;
    private static final int MSG_CREATE_CONFERENCE_COMPLETE = 36;
    private static final int MSG_CREATE_CONFERENCE_FAILED = 37;
    private static final int MSG_REJECT_WITH_REASON = 38;
    private static final int MSG_ADD_PARTICIPANT = 39;
    private static final int MSG_EXPLICIT_CALL_TRANSFER = 40;
    private static final int MSG_EXPLICIT_CALL_TRANSFER_CONSULTATIVE = 41;

    private static Connection sNullConnection;

    private final Map<String, Connection> mConnectionById = new ConcurrentHashMap<>();
    private final Map<Connection, String> mIdByConnection = new ConcurrentHashMap<>();
    private final Map<String, Conference> mConferenceById = new ConcurrentHashMap<>();
    private final Map<Conference, String> mIdByConference = new ConcurrentHashMap<>();
    private final RemoteConnectionManager mRemoteConnectionManager =
            new RemoteConnectionManager(this);
    private final List<Runnable> mPreInitializationConnectionRequests = new ArrayList<>();
    private final ConnectionServiceAdapter mAdapter = new ConnectionServiceAdapter();

    private boolean mAreAccountsInitialized = false;
    private Conference sNullConference;
    private Object mIdSyncRoot = new Object();
    private int mId = 0;

    private final IBinder mBinder = new IConnectionService.Stub() {
        @Override
        public void addConnectionServiceAdapter(IConnectionServiceAdapter adapter,
                Session.Info sessionInfo) {
            Log.startSession(sessionInfo, SESSION_ADD_CS_ADAPTER);
            try {
                SomeArgs args = SomeArgs.obtain();
                args.arg1 = adapter;
                args.arg2 = Log.createSubsession();
                mHandler.obtainMessage(MSG_ADD_CONNECTION_SERVICE_ADAPTER, args).sendToTarget();
            } finally {
                Log.endSession();
            }
        }

        public void removeConnectionServiceAdapter(IConnectionServiceAdapter adapter,
                Session.Info sessionInfo) {
            Log.startSession(sessionInfo, SESSION_REMOVE_CS_ADAPTER);
            try {
                SomeArgs args = SomeArgs.obtain();
                args.arg1 = adapter;
                args.arg2 = Log.createSubsession();
                mHandler.obtainMessage(MSG_REMOVE_CONNECTION_SERVICE_ADAPTER, args).sendToTarget();
            } finally {
                Log.endSession();
            }
        }

        @Override
        public void createConnection(
                PhoneAccountHandle connectionManagerPhoneAccount,
                String id,
                ConnectionRequest request,
                boolean isIncoming,
                boolean isUnknown,
                Session.Info sessionInfo) {
            Log.startSession(sessionInfo, SESSION_CREATE_CONN);
            try {
                SomeArgs args = SomeArgs.obtain();
                args.arg1 = connectionManagerPhoneAccount;
                args.arg2 = id;
                args.arg3 = request;
                args.arg4 = Log.createSubsession();
                args.argi1 = isIncoming ? 1 : 0;
                args.argi2 = isUnknown ? 1 : 0;
                mHandler.obtainMessage(MSG_CREATE_CONNECTION, args).sendToTarget();
            } finally {
                Log.endSession();
            }
        }

        @Override
        public void createConnectionComplete(String id, Session.Info sessionInfo) {
            Log.startSession(sessionInfo, SESSION_CREATE_CONN_COMPLETE);
            try {
                SomeArgs args = SomeArgs.obtain();
                args.arg1 = id;
                args.arg2 = Log.createSubsession();
                mHandler.obtainMessage(MSG_CREATE_CONNECTION_COMPLETE, args).sendToTarget();
            } finally {
                Log.endSession();
            }
        }

        @Override
        public void createConnectionFailed(
                PhoneAccountHandle connectionManagerPhoneAccount,
                String callId,
                ConnectionRequest request,
                boolean isIncoming,
                Session.Info sessionInfo) {
            Log.startSession(sessionInfo, SESSION_CREATE_CONN_FAILED);
            try {
                SomeArgs args = SomeArgs.obtain();
                args.arg1 = callId;
                args.arg2 = request;
                args.arg3 = Log.createSubsession();
                args.arg4 = connectionManagerPhoneAccount;
                args.argi1 = isIncoming ? 1 : 0;
                mHandler.obtainMessage(MSG_CREATE_CONNECTION_FAILED, args).sendToTarget();
            } finally {
                Log.endSession();
            }
        }

        @Override
        public void createConference(
                PhoneAccountHandle connectionManagerPhoneAccount,
                String id,
                ConnectionRequest request,
                boolean isIncoming,
                boolean isUnknown,
                Session.Info sessionInfo) {
            Log.startSession(sessionInfo, SESSION_CREATE_CONF);
            try {
                SomeArgs args = SomeArgs.obtain();
                args.arg1 = connectionManagerPhoneAccount;
                args.arg2 = id;
                args.arg3 = request;
                args.arg4 = Log.createSubsession();
                args.argi1 = isIncoming ? 1 : 0;
                args.argi2 = isUnknown ? 1 : 0;
                mHandler.obtainMessage(MSG_CREATE_CONFERENCE, args).sendToTarget();
            } finally {
                Log.endSession();
            }
        }

        @Override
        public void createConferenceComplete(String id, Session.Info sessionInfo) {
            Log.startSession(sessionInfo, SESSION_CREATE_CONF_COMPLETE);
            try {
                SomeArgs args = SomeArgs.obtain();
                args.arg1 = id;
                args.arg2 = Log.createSubsession();
                mHandler.obtainMessage(MSG_CREATE_CONFERENCE_COMPLETE, args).sendToTarget();
            } finally {
                Log.endSession();
            }
        }

        @Override
        public void createConferenceFailed(
                PhoneAccountHandle connectionManagerPhoneAccount,
                String callId,
                ConnectionRequest request,
                boolean isIncoming,
                Session.Info sessionInfo) {
            Log.startSession(sessionInfo, SESSION_CREATE_CONF_FAILED);
            try {
                SomeArgs args = SomeArgs.obtain();
                args.arg1 = callId;
                args.arg2 = request;
                args.arg3 = Log.createSubsession();
                args.arg4 = connectionManagerPhoneAccount;
                args.argi1 = isIncoming ? 1 : 0;
                mHandler.obtainMessage(MSG_CREATE_CONFERENCE_FAILED, args).sendToTarget();
            } finally {
                Log.endSession();
            }
        }

        @Override
        public void handoverFailed(String callId, ConnectionRequest request, int reason,
                                   Session.Info sessionInfo) {
            Log.startSession(sessionInfo, SESSION_HANDOVER_FAILED);
            try {
                SomeArgs args = SomeArgs.obtain();
                args.arg1 = callId;
                args.arg2 = request;
                args.arg3 = Log.createSubsession();
                args.arg4 = reason;
                mHandler.obtainMessage(MSG_HANDOVER_FAILED, args).sendToTarget();
            } finally {
                Log.endSession();
            }
        }

        @Override
        public void handoverComplete(String callId, Session.Info sessionInfo) {
            Log.startSession(sessionInfo, SESSION_HANDOVER_COMPLETE);
            try {
                SomeArgs args = SomeArgs.obtain();
                args.arg1 = callId;
                args.arg2 = Log.createSubsession();
                mHandler.obtainMessage(MSG_HANDOVER_COMPLETE, args).sendToTarget();
            } finally {
                Log.endSession();
            }
        }

        @Override
        public void abort(String callId, Session.Info sessionInfo) {
            Log.startSession(sessionInfo, SESSION_ABORT);
            try {
                SomeArgs args = SomeArgs.obtain();
                args.arg1 = callId;
                args.arg2 = Log.createSubsession();
                mHandler.obtainMessage(MSG_ABORT, args).sendToTarget();
            } finally {
                Log.endSession();
            }
        }

        @Override
        public void answerVideo(String callId, int videoState, Session.Info sessionInfo) {
            Log.startSession(sessionInfo, SESSION_ANSWER_VIDEO);
            try {
                SomeArgs args = SomeArgs.obtain();
                args.arg1 = callId;
                args.arg2 = Log.createSubsession();
                args.argi1 = videoState;
                mHandler.obtainMessage(MSG_ANSWER_VIDEO, args).sendToTarget();
            } finally {
                Log.endSession();
            }
        }

        @Override
        public void answer(String callId, Session.Info sessionInfo) {
            Log.startSession(sessionInfo, SESSION_ANSWER);
            try {
                SomeArgs args = SomeArgs.obtain();
                args.arg1 = callId;
                args.arg2 = Log.createSubsession();
                mHandler.obtainMessage(MSG_ANSWER, args).sendToTarget();
            } finally {
                Log.endSession();
            }
        }

        @Override
        public void deflect(String callId, Uri address, Session.Info sessionInfo) {
            Log.startSession(sessionInfo, SESSION_DEFLECT);
            try {
                SomeArgs args = SomeArgs.obtain();
                args.arg1 = callId;
                args.arg2 = address;
                args.arg3 = Log.createSubsession();
                mHandler.obtainMessage(MSG_DEFLECT, args).sendToTarget();
            } finally {
                Log.endSession();
            }
        }

        @Override
        public void reject(String callId, Session.Info sessionInfo) {
            Log.startSession(sessionInfo, SESSION_REJECT);
            try {
                SomeArgs args = SomeArgs.obtain();
                args.arg1 = callId;
                args.arg2 = Log.createSubsession();
                mHandler.obtainMessage(MSG_REJECT, args).sendToTarget();
            } finally {
                Log.endSession();
            }
        }

        @Override
        public void rejectWithReason(String callId,
                @android.telecom.Call.RejectReason int rejectReason, Session.Info sessionInfo) {
            Log.startSession(sessionInfo, SESSION_REJECT);
            try {
                SomeArgs args = SomeArgs.obtain();
                args.arg1 = callId;
                args.argi1 = rejectReason;
                args.arg2 = Log.createSubsession();
                mHandler.obtainMessage(MSG_REJECT_WITH_REASON, args).sendToTarget();
            } finally {
                Log.endSession();
            }
        }

        @Override
        public void rejectWithMessage(String callId, String message, Session.Info sessionInfo) {
            Log.startSession(sessionInfo, SESSION_REJECT_MESSAGE);
            try {
                SomeArgs args = SomeArgs.obtain();
                args.arg1 = callId;
                args.arg2 = message;
                args.arg3 = Log.createSubsession();
                mHandler.obtainMessage(MSG_REJECT_WITH_MESSAGE, args).sendToTarget();
            } finally {
                Log.endSession();
            }
        }

        @Override
        public void transfer(@NonNull String callId, @NonNull Uri number,
                boolean isConfirmationRequired, Session.Info sessionInfo) {
            Log.startSession(sessionInfo, SESSION_TRANSFER);
            try {
                SomeArgs args = SomeArgs.obtain();
                args.arg1 = callId;
                args.arg2 = number;
                args.argi1 = isConfirmationRequired ? 1 : 0;
                args.arg3 = Log.createSubsession();
                mHandler.obtainMessage(MSG_EXPLICIT_CALL_TRANSFER, args).sendToTarget();
            } finally {
                Log.endSession();
            }
        }

        @Override
        public void consultativeTransfer(@NonNull String callId, @NonNull String otherCallId,
                Session.Info sessionInfo) {
            Log.startSession(sessionInfo, SESSION_CONSULTATIVE_TRANSFER);
            try {
                SomeArgs args = SomeArgs.obtain();
                args.arg1 = callId;
                args.arg2 = otherCallId;
                args.arg3 = Log.createSubsession();
                mHandler.obtainMessage(
                        MSG_EXPLICIT_CALL_TRANSFER_CONSULTATIVE, args).sendToTarget();
            } finally {
                Log.endSession();
            }
        }

        @Override
        public void silence(String callId, Session.Info sessionInfo) {
            Log.startSession(sessionInfo, SESSION_SILENCE);
            try {
                SomeArgs args = SomeArgs.obtain();
                args.arg1 = callId;
                args.arg2 = Log.createSubsession();
                mHandler.obtainMessage(MSG_SILENCE, args).sendToTarget();
            } finally {
                Log.endSession();
            }
        }

        @Override
        public void disconnect(String callId, Session.Info sessionInfo) {
            Log.startSession(sessionInfo, SESSION_DISCONNECT);
            try {
                SomeArgs args = SomeArgs.obtain();
                args.arg1 = callId;
                args.arg2 = Log.createSubsession();
                mHandler.obtainMessage(MSG_DISCONNECT, args).sendToTarget();
            } finally {
                Log.endSession();
            }
        }

        @Override
        public void hold(String callId, Session.Info sessionInfo) {
            Log.startSession(sessionInfo, SESSION_HOLD);
            try {
                SomeArgs args = SomeArgs.obtain();
                args.arg1 = callId;
                args.arg2 = Log.createSubsession();
                mHandler.obtainMessage(MSG_HOLD, args).sendToTarget();
            } finally {
                Log.endSession();
            }
        }

        @Override
        public void unhold(String callId, Session.Info sessionInfo) {
            Log.startSession(sessionInfo, SESSION_UNHOLD);
            try {
                SomeArgs args = SomeArgs.obtain();
                args.arg1 = callId;
                args.arg2 = Log.createSubsession();
                mHandler.obtainMessage(MSG_UNHOLD, args).sendToTarget();
            } finally {
                Log.endSession();
            }
        }

        @Override
        public void onCallAudioStateChanged(String callId, CallAudioState callAudioState,
                Session.Info sessionInfo) {
            Log.startSession(sessionInfo, SESSION_CALL_AUDIO_SC);
            try {
                SomeArgs args = SomeArgs.obtain();
                args.arg1 = callId;
                args.arg2 = callAudioState;
                args.arg3 = Log.createSubsession();
                mHandler.obtainMessage(MSG_ON_CALL_AUDIO_STATE_CHANGED, args).sendToTarget();
            } finally {
                Log.endSession();
            }
        }

        @Override
        public void playDtmfTone(String callId, char digit, Session.Info sessionInfo) {
            Log.startSession(sessionInfo, SESSION_PLAY_DTMF);
            try {
                SomeArgs args = SomeArgs.obtain();
                args.arg1 = digit;
                args.arg2 = callId;
                args.arg3 = Log.createSubsession();
                mHandler.obtainMessage(MSG_PLAY_DTMF_TONE, args).sendToTarget();
            } finally {
                Log.endSession();
            }
        }

        @Override
        public void stopDtmfTone(String callId, Session.Info sessionInfo) {
            Log.startSession(sessionInfo, SESSION_STOP_DTMF);
            try {
                SomeArgs args = SomeArgs.obtain();
                args.arg1 = callId;
                args.arg2 = Log.createSubsession();
                mHandler.obtainMessage(MSG_STOP_DTMF_TONE, args).sendToTarget();
            } finally {
                Log.endSession();
            }
        }

        @Override
        public void conference(String callId1, String callId2, Session.Info sessionInfo) {
            Log.startSession(sessionInfo, SESSION_CONFERENCE);
            try {
                SomeArgs args = SomeArgs.obtain();
                args.arg1 = callId1;
                args.arg2 = callId2;
                args.arg3 = Log.createSubsession();
                mHandler.obtainMessage(MSG_CONFERENCE, args).sendToTarget();
            } finally {
                Log.endSession();
            }
        }

        @Override
        public void splitFromConference(String callId, Session.Info sessionInfo) {
            Log.startSession(sessionInfo, SESSION_SPLIT_CONFERENCE);
            try {
                SomeArgs args = SomeArgs.obtain();
                args.arg1 = callId;
                args.arg2 = Log.createSubsession();
                mHandler.obtainMessage(MSG_SPLIT_FROM_CONFERENCE, args).sendToTarget();
            } finally {
                Log.endSession();
            }
        }

        @Override
        public void mergeConference(String callId, Session.Info sessionInfo) {
            Log.startSession(sessionInfo, SESSION_MERGE_CONFERENCE);
            try {
                SomeArgs args = SomeArgs.obtain();
                args.arg1 = callId;
                args.arg2 = Log.createSubsession();
                mHandler.obtainMessage(MSG_MERGE_CONFERENCE, args).sendToTarget();
            } finally {
                Log.endSession();
            }
        }

        @Override
        public void swapConference(String callId, Session.Info sessionInfo) {
            Log.startSession(sessionInfo, SESSION_SWAP_CONFERENCE);
            try {
                SomeArgs args = SomeArgs.obtain();
                args.arg1 = callId;
                args.arg2 = Log.createSubsession();
                mHandler.obtainMessage(MSG_SWAP_CONFERENCE, args).sendToTarget();
            } finally {
                Log.endSession();
            }
        }

        @Override
        public void addConferenceParticipants(String callId, List<Uri> participants,
                Session.Info sessionInfo) {
            Log.startSession(sessionInfo, SESSION_ADD_PARTICIPANT);
            try {
                SomeArgs args = SomeArgs.obtain();
                args.arg1 = callId;
                args.arg2 = participants;
                args.arg3 = Log.createSubsession();
                mHandler.obtainMessage(MSG_ADD_PARTICIPANT, args).sendToTarget();
            } finally {
                Log.endSession();
            }
        }

        @Override
        public void onPostDialContinue(String callId, boolean proceed, Session.Info sessionInfo) {
            Log.startSession(sessionInfo, SESSION_POST_DIAL_CONT);
            try {
                SomeArgs args = SomeArgs.obtain();
                args.arg1 = callId;
                args.arg2 = Log.createSubsession();
                args.argi1 = proceed ? 1 : 0;
                mHandler.obtainMessage(MSG_ON_POST_DIAL_CONTINUE, args).sendToTarget();
            } finally {
                Log.endSession();
            }
        }

        @Override
        public void pullExternalCall(String callId, Session.Info sessionInfo) {
            Log.startSession(sessionInfo, SESSION_PULL_EXTERNAL_CALL);
            try {
                SomeArgs args = SomeArgs.obtain();
                args.arg1 = callId;
                args.arg2 = Log.createSubsession();
                mHandler.obtainMessage(MSG_PULL_EXTERNAL_CALL, args).sendToTarget();
            } finally {
                Log.endSession();
            }
        }

        @Override
        public void sendCallEvent(String callId, String event, Bundle extras,
                Session.Info sessionInfo) {
            Log.startSession(sessionInfo, SESSION_SEND_CALL_EVENT);
            try {
                SomeArgs args = SomeArgs.obtain();
                args.arg1 = callId;
                args.arg2 = event;
                args.arg3 = extras;
                args.arg4 = Log.createSubsession();
                mHandler.obtainMessage(MSG_SEND_CALL_EVENT, args).sendToTarget();
            } finally {
                Log.endSession();
            }
        }

        @Override
        public void onExtrasChanged(String callId, Bundle extras, Session.Info sessionInfo) {
            Log.startSession(sessionInfo, SESSION_EXTRAS_CHANGED);
            try {
                SomeArgs args = SomeArgs.obtain();
                args.arg1 = callId;
                args.arg2 = extras;
                args.arg3 = Log.createSubsession();
                mHandler.obtainMessage(MSG_ON_EXTRAS_CHANGED, args).sendToTarget();
            } finally {
                Log.endSession();
            }
        }

        @Override
        public void startRtt(String callId, ParcelFileDescriptor fromInCall,
                ParcelFileDescriptor toInCall, Session.Info sessionInfo) throws RemoteException {
            Log.startSession(sessionInfo, SESSION_START_RTT);
            try {
                SomeArgs args = SomeArgs.obtain();
                args.arg1 = callId;
                args.arg2 = new Connection.RttTextStream(toInCall, fromInCall);
                args.arg3 = Log.createSubsession();
                mHandler.obtainMessage(MSG_ON_START_RTT, args).sendToTarget();
            } finally {
                Log.endSession();
            }
        }

        @Override
        public void stopRtt(String callId, Session.Info sessionInfo) throws RemoteException {
            Log.startSession(sessionInfo, SESSION_STOP_RTT);
            try {
                SomeArgs args = SomeArgs.obtain();
                args.arg1 = callId;
                args.arg2 = Log.createSubsession();
                mHandler.obtainMessage(MSG_ON_STOP_RTT, args).sendToTarget();
            } finally {
                Log.endSession();
            }
        }

        @Override
        public void respondToRttUpgradeRequest(String callId, ParcelFileDescriptor fromInCall,
                ParcelFileDescriptor toInCall, Session.Info sessionInfo) throws RemoteException {
            Log.startSession(sessionInfo, SESSION_RTT_UPGRADE_RESPONSE);
            try {
                SomeArgs args = SomeArgs.obtain();
                args.arg1 = callId;
                if (toInCall == null || fromInCall == null) {
                    args.arg2 = null;
                } else {
                    args.arg2 = new Connection.RttTextStream(toInCall, fromInCall);
                }
                args.arg3 = Log.createSubsession();
                mHandler.obtainMessage(MSG_RTT_UPGRADE_RESPONSE, args).sendToTarget();
            } finally {
                Log.endSession();
            }
        }

        @Override
        public void connectionServiceFocusLost(Session.Info sessionInfo) throws RemoteException {
            Log.startSession(sessionInfo, SESSION_CONNECTION_SERVICE_FOCUS_LOST);
            try {
                mHandler.obtainMessage(MSG_CONNECTION_SERVICE_FOCUS_LOST).sendToTarget();
            } finally {
                Log.endSession();
            }
        }

        @Override
        public void connectionServiceFocusGained(Session.Info sessionInfo) throws RemoteException {
            Log.startSession(sessionInfo, SESSION_CONNECTION_SERVICE_FOCUS_GAINED);
            try {
                mHandler.obtainMessage(MSG_CONNECTION_SERVICE_FOCUS_GAINED).sendToTarget();
            } finally {
                Log.endSession();
            }
        }
    };

    private final Handler mHandler = new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_ADD_CONNECTION_SERVICE_ADAPTER: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    try {
                        IConnectionServiceAdapter adapter = (IConnectionServiceAdapter) args.arg1;
                        Log.continueSession((Session) args.arg2,
                                SESSION_HANDLER + SESSION_ADD_CS_ADAPTER);
                        mAdapter.addAdapter(adapter);
                        onAdapterAttached();
                    } finally {
                        args.recycle();
                        Log.endSession();
                    }
                    break;
                }
                case MSG_REMOVE_CONNECTION_SERVICE_ADAPTER: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    try {
                        Log.continueSession((Session) args.arg2,
                                SESSION_HANDLER + SESSION_REMOVE_CS_ADAPTER);
                        mAdapter.removeAdapter((IConnectionServiceAdapter) args.arg1);
                    } finally {
                        args.recycle();
                        Log.endSession();
                    }
                    break;
                }
                case MSG_CREATE_CONNECTION: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    Log.continueSession((Session) args.arg4, SESSION_HANDLER + SESSION_CREATE_CONN);
                    try {
                        final PhoneAccountHandle connectionManagerPhoneAccount =
                                (PhoneAccountHandle) args.arg1;
                        final String id = (String) args.arg2;
                        final ConnectionRequest request = (ConnectionRequest) args.arg3;
                        final boolean isIncoming = args.argi1 == 1;
                        final boolean isUnknown = args.argi2 == 1;
                        if (!mAreAccountsInitialized) {
                            Log.d(this, "Enqueueing pre-init request %s", id);
                            mPreInitializationConnectionRequests.add(
                                    new android.telecom.Logging.Runnable(
                                            SESSION_HANDLER + SESSION_CREATE_CONN + ".pICR",
                                            null /*lock*/) {
                                @Override
                                public void loggedRun() {
                                    createConnection(
                                            connectionManagerPhoneAccount,
                                            id,
                                            request,
                                            isIncoming,
                                            isUnknown);
                                }
                            }.prepare());
                        } else {
                            createConnection(
                                    connectionManagerPhoneAccount,
                                    id,
                                    request,
                                    isIncoming,
                                    isUnknown);
                        }
                    } finally {
                        args.recycle();
                        Log.endSession();
                    }
                    break;
                }
                case MSG_CREATE_CONNECTION_COMPLETE: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    Log.continueSession((Session) args.arg2,
                            SESSION_HANDLER + SESSION_CREATE_CONN_COMPLETE);
                    try {
                        final String id = (String) args.arg1;
                        if (!mAreAccountsInitialized) {
                            Log.d(this, "Enqueueing pre-init request %s", id);
                            mPreInitializationConnectionRequests.add(
                                    new android.telecom.Logging.Runnable(
                                            SESSION_HANDLER + SESSION_CREATE_CONN_COMPLETE
                                                    + ".pICR",
                                            null /*lock*/) {
                                        @Override
                                        public void loggedRun() {
                                            notifyCreateConnectionComplete(id);
                                        }
                                    }.prepare());
                        } else {
                            notifyCreateConnectionComplete(id);
                        }
                    } finally {
                        args.recycle();
                        Log.endSession();
                    }
                    break;
                }
                case MSG_CREATE_CONNECTION_FAILED: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    Log.continueSession((Session) args.arg3, SESSION_HANDLER +
                            SESSION_CREATE_CONN_FAILED);
                    try {
                        final String id = (String) args.arg1;
                        final ConnectionRequest request = (ConnectionRequest) args.arg2;
                        final boolean isIncoming = args.argi1 == 1;
                        final PhoneAccountHandle connectionMgrPhoneAccount =
                                (PhoneAccountHandle) args.arg4;
                        if (!mAreAccountsInitialized) {
                            Log.d(this, "Enqueueing pre-init request %s", id);
                            mPreInitializationConnectionRequests.add(
                                    new android.telecom.Logging.Runnable(
                                            SESSION_HANDLER + SESSION_CREATE_CONN_FAILED + ".pICR",
                                            null /*lock*/) {
                                        @Override
                                        public void loggedRun() {
                                            createConnectionFailed(connectionMgrPhoneAccount, id,
                                                    request, isIncoming);
                                        }
                                    }.prepare());
                        } else {
                            Log.i(this, "createConnectionFailed %s", id);
                            createConnectionFailed(connectionMgrPhoneAccount, id, request,
                                    isIncoming);
                        }
                    } finally {
                        args.recycle();
                        Log.endSession();
                    }
                    break;
                }
                case MSG_CREATE_CONFERENCE: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    Log.continueSession((Session) args.arg4, SESSION_HANDLER + SESSION_CREATE_CONN);
                    try {
                        final PhoneAccountHandle connectionManagerPhoneAccount =
                                (PhoneAccountHandle) args.arg1;
                        final String id = (String) args.arg2;
                        final ConnectionRequest request = (ConnectionRequest) args.arg3;
                        final boolean isIncoming = args.argi1 == 1;
                        final boolean isUnknown = args.argi2 == 1;
                        if (!mAreAccountsInitialized) {
                            Log.d(this, "Enqueueing pre-initconference request %s", id);
                            mPreInitializationConnectionRequests.add(
                                    new android.telecom.Logging.Runnable(
                                            SESSION_HANDLER + SESSION_CREATE_CONF + ".pIConfR",
                                            null /*lock*/) {
                                @Override
                                public void loggedRun() {
                                    createConference(connectionManagerPhoneAccount,
                                            id,
                                            request,
                                            isIncoming,
                                            isUnknown);
                                }
                            }.prepare());
                        } else {
                            createConference(connectionManagerPhoneAccount,
                                    id,
                                    request,
                                    isIncoming,
                                    isUnknown);
                        }
                    } finally {
                        args.recycle();
                        Log.endSession();
                    }
                    break;
                }
                case MSG_CREATE_CONFERENCE_COMPLETE: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    Log.continueSession((Session) args.arg2,
                            SESSION_HANDLER + SESSION_CREATE_CONN_COMPLETE);
                    try {
                        final String id = (String) args.arg1;
                        if (!mAreAccountsInitialized) {
                            Log.d(this, "Enqueueing pre-init conference request %s", id);
                            mPreInitializationConnectionRequests.add(
                                    new android.telecom.Logging.Runnable(
                                            SESSION_HANDLER + SESSION_CREATE_CONF_COMPLETE
                                                    + ".pIConfR",
                                            null /*lock*/) {
                                        @Override
                                        public void loggedRun() {
                                            notifyCreateConferenceComplete(id);
                                        }
                                    }.prepare());
                        } else {
                            notifyCreateConferenceComplete(id);
                        }
                    } finally {
                        args.recycle();
                        Log.endSession();
                    }
                    break;
                }
                case MSG_CREATE_CONFERENCE_FAILED: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    Log.continueSession((Session) args.arg3, SESSION_HANDLER +
                            SESSION_CREATE_CONN_FAILED);
                    try {
                        final String id = (String) args.arg1;
                        final ConnectionRequest request = (ConnectionRequest) args.arg2;
                        final boolean isIncoming = args.argi1 == 1;
                        final PhoneAccountHandle connectionMgrPhoneAccount =
                                (PhoneAccountHandle) args.arg4;
                        if (!mAreAccountsInitialized) {
                            Log.d(this, "Enqueueing pre-init conference request %s", id);
                            mPreInitializationConnectionRequests.add(
                                    new android.telecom.Logging.Runnable(
                                            SESSION_HANDLER + SESSION_CREATE_CONF_FAILED
                                                    + ".pIConfR",
                                            null /*lock*/) {
                                        @Override
                                        public void loggedRun() {
                                            createConferenceFailed(connectionMgrPhoneAccount, id,
                                                    request, isIncoming);
                                        }
                                    }.prepare());
                        } else {
                            Log.i(this, "createConferenceFailed %s", id);
                            createConferenceFailed(connectionMgrPhoneAccount, id, request,
                                    isIncoming);
                        }
                    } finally {
                        args.recycle();
                        Log.endSession();
                    }
                    break;
                }

                case MSG_HANDOVER_FAILED: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    Log.continueSession((Session) args.arg3, SESSION_HANDLER +
                            SESSION_HANDOVER_FAILED);
                    try {
                        final String id = (String) args.arg1;
                        final ConnectionRequest request = (ConnectionRequest) args.arg2;
                        final int reason = (int) args.arg4;
                        if (!mAreAccountsInitialized) {
                            Log.d(this, "Enqueueing pre-init request %s", id);
                            mPreInitializationConnectionRequests.add(
                                    new android.telecom.Logging.Runnable(
                                            SESSION_HANDLER
                                                    + SESSION_HANDOVER_FAILED + ".pICR",
                                            null /*lock*/) {
                                        @Override
                                        public void loggedRun() {
                                            handoverFailed(id, request, reason);
                                        }
                                    }.prepare());
                        } else {
                            Log.i(this, "createConnectionFailed %s", id);
                            handoverFailed(id, request, reason);
                        }
                    } finally {
                        args.recycle();
                        Log.endSession();
                    }
                    break;
                }
                case MSG_ABORT: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    Log.continueSession((Session) args.arg2, SESSION_HANDLER + SESSION_ABORT);
                    try {
                        abort((String) args.arg1);
                    } finally {
                        args.recycle();
                        Log.endSession();
                    }
                    break;
                }
                case MSG_ANSWER: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    Log.continueSession((Session) args.arg2, SESSION_HANDLER + SESSION_ANSWER);
                    try {
                        answer((String) args.arg1);
                    } finally {
                        args.recycle();
                        Log.endSession();
                    }
                    break;
                }
                case MSG_ANSWER_VIDEO: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    Log.continueSession((Session) args.arg2,
                            SESSION_HANDLER + SESSION_ANSWER_VIDEO);
                    try {
                        String callId = (String) args.arg1;
                        int videoState = args.argi1;
                        answerVideo(callId, videoState);
                    } finally {
                        args.recycle();
                        Log.endSession();
                    }
                    break;
                }
                case MSG_DEFLECT: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    Log.continueSession((Session) args.arg3, SESSION_HANDLER + SESSION_DEFLECT);
                    try {
                        deflect((String) args.arg1, (Uri) args.arg2);
                    } finally {
                        args.recycle();
                        Log.endSession();
                    }
                    break;
                }
                case MSG_REJECT: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    Log.continueSession((Session) args.arg2, SESSION_HANDLER + SESSION_REJECT);
                    try {
                        reject((String) args.arg1);
                    } finally {
                        args.recycle();
                        Log.endSession();
                    }
                    break;
                }
                case MSG_REJECT_WITH_REASON: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    Log.continueSession((Session) args.arg2, SESSION_HANDLER + SESSION_REJECT);
                    try {
                        reject((String) args.arg1, args.argi1);
                    } finally {
                        args.recycle();
                        Log.endSession();
                    }
                    break;
                }
                case MSG_REJECT_WITH_MESSAGE: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    Log.continueSession((Session) args.arg3,
                            SESSION_HANDLER + SESSION_REJECT_MESSAGE);
                    try {
                        reject((String) args.arg1, (String) args.arg2);
                    } finally {
                        args.recycle();
                        Log.endSession();
                    }
                    break;
                }
                case MSG_EXPLICIT_CALL_TRANSFER: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    Log.continueSession((Session) args.arg3, SESSION_HANDLER + SESSION_TRANSFER);
                    try {
                        final boolean isConfirmationRequired = args.argi1 == 1;
                        transfer((String) args.arg1, (Uri) args.arg2, isConfirmationRequired);
                    } finally {
                        args.recycle();
                        Log.endSession();
                    }
                    break;
                }
                case MSG_EXPLICIT_CALL_TRANSFER_CONSULTATIVE: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    Log.continueSession(
                            (Session) args.arg3, SESSION_HANDLER + SESSION_CONSULTATIVE_TRANSFER);
                    try {
                        consultativeTransfer((String) args.arg1, (String) args.arg2);
                    } finally {
                        args.recycle();
                        Log.endSession();
                    }
                    break;
                }
                case MSG_DISCONNECT: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    Log.continueSession((Session) args.arg2, SESSION_HANDLER + SESSION_DISCONNECT);
                    try {
                        disconnect((String) args.arg1);
                    } finally {
                        args.recycle();
                        Log.endSession();
                    }
                    break;
                }
                case MSG_SILENCE: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    Log.continueSession((Session) args.arg2, SESSION_HANDLER + SESSION_SILENCE);
                    try {
                        silence((String) args.arg1);
                    } finally {
                        args.recycle();
                        Log.endSession();
                    }
                    break;
                }
                case MSG_HOLD: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    Log.continueSession((Session) args.arg2, SESSION_HANDLER + SESSION_REJECT);
                    try {
                        hold((String) args.arg1);
                    } finally {
                        args.recycle();
                        Log.endSession();
                    }
                    break;
                }
                case MSG_UNHOLD: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    Log.continueSession((Session) args.arg2, SESSION_HANDLER + SESSION_UNHOLD);
                    try {
                        unhold((String) args.arg1);
                    } finally {
                        args.recycle();
                        Log.endSession();
                    }
                    break;
                }
                case MSG_ON_CALL_AUDIO_STATE_CHANGED: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    Log.continueSession((Session) args.arg3,
                            SESSION_HANDLER + SESSION_CALL_AUDIO_SC);
                    try {
                        String callId = (String) args.arg1;
                        CallAudioState audioState = (CallAudioState) args.arg2;
                        onCallAudioStateChanged(callId, new CallAudioState(audioState));
                    } finally {
                        args.recycle();
                        Log.endSession();
                    }
                    break;
                }
                case MSG_PLAY_DTMF_TONE: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    try {
                        Log.continueSession((Session) args.arg3,
                                SESSION_HANDLER + SESSION_PLAY_DTMF);
                        playDtmfTone((String) args.arg2, (char) args.arg1);
                    } finally {
                        args.recycle();
                        Log.endSession();
                    }
                    break;
                }
                case MSG_STOP_DTMF_TONE: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    try {
                        Log.continueSession((Session) args.arg2,
                                SESSION_HANDLER + SESSION_STOP_DTMF);
                        stopDtmfTone((String) args.arg1);
                    } finally {
                        args.recycle();
                        Log.endSession();
                    }
                    break;
                }
                case MSG_CONFERENCE: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    try {
                        Log.continueSession((Session) args.arg3,
                                SESSION_HANDLER + SESSION_CONFERENCE);
                        String callId1 = (String) args.arg1;
                        String callId2 = (String) args.arg2;
                        conference(callId1, callId2);
                    } finally {
                        args.recycle();
                        Log.endSession();
                    }
                    break;
                }
                case MSG_SPLIT_FROM_CONFERENCE: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    try {
                        Log.continueSession((Session) args.arg2,
                                SESSION_HANDLER + SESSION_SPLIT_CONFERENCE);
                        splitFromConference((String) args.arg1);
                    } finally {
                        args.recycle();
                        Log.endSession();
                    }
                    break;
                }
                case MSG_MERGE_CONFERENCE: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    try {
                        Log.continueSession((Session) args.arg2,
                                SESSION_HANDLER + SESSION_MERGE_CONFERENCE);
                        mergeConference((String) args.arg1);
                    } finally {
                        args.recycle();
                        Log.endSession();
                    }
                    break;
                }
                case MSG_SWAP_CONFERENCE: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    try {
                        Log.continueSession((Session) args.arg2,
                                SESSION_HANDLER + SESSION_SWAP_CONFERENCE);
                        swapConference((String) args.arg1);
                    } finally {
                        args.recycle();
                        Log.endSession();
                    }
                    break;
                }
                case MSG_ADD_PARTICIPANT: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    try {
                        Log.continueSession((Session) args.arg3,
                                SESSION_HANDLER + SESSION_ADD_PARTICIPANT);
                        addConferenceParticipants((String) args.arg1, (List<Uri>)args.arg2);
                    } finally {
                        args.recycle();
                        Log.endSession();
                    }
                    break;
                }

                case MSG_ON_POST_DIAL_CONTINUE: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    try {
                        Log.continueSession((Session) args.arg2,
                                SESSION_HANDLER + SESSION_POST_DIAL_CONT);
                        String callId = (String) args.arg1;
                        boolean proceed = (args.argi1 == 1);
                        onPostDialContinue(callId, proceed);
                    } finally {
                        args.recycle();
                        Log.endSession();
                    }
                    break;
                }
                case MSG_PULL_EXTERNAL_CALL: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    try {
                        Log.continueSession((Session) args.arg2,
                                SESSION_HANDLER + SESSION_PULL_EXTERNAL_CALL);
                        pullExternalCall((String) args.arg1);
                    } finally {
                        args.recycle();
                        Log.endSession();
                    }
                    break;
                }
                case MSG_SEND_CALL_EVENT: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    try {
                        Log.continueSession((Session) args.arg4,
                                SESSION_HANDLER + SESSION_SEND_CALL_EVENT);
                        String callId = (String) args.arg1;
                        String event = (String) args.arg2;
                        Bundle extras = (Bundle) args.arg3;
                        sendCallEvent(callId, event, extras);
                    } finally {
                        args.recycle();
                        Log.endSession();
                    }
                    break;
                }
                case MSG_HANDOVER_COMPLETE: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    try {
                        Log.continueSession((Session) args.arg2,
                                SESSION_HANDLER + SESSION_HANDOVER_COMPLETE);
                        String callId = (String) args.arg1;
                        notifyHandoverComplete(callId);
                    } finally {
                        args.recycle();
                        Log.endSession();
                    }
                    break;
                }
                case MSG_ON_EXTRAS_CHANGED: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    try {
                        Log.continueSession((Session) args.arg3,
                                SESSION_HANDLER + SESSION_EXTRAS_CHANGED);
                        String callId = (String) args.arg1;
                        Bundle extras = (Bundle) args.arg2;
                        handleExtrasChanged(callId, extras);
                    } finally {
                        args.recycle();
                        Log.endSession();
                    }
                    break;
                }
                case MSG_ON_START_RTT: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    try {
                        Log.continueSession((Session) args.arg3,
                                SESSION_HANDLER + SESSION_START_RTT);
                        String callId = (String) args.arg1;
                        Connection.RttTextStream rttTextStream =
                                (Connection.RttTextStream) args.arg2;
                        startRtt(callId, rttTextStream);
                    } finally {
                        args.recycle();
                        Log.endSession();
                    }
                    break;
                }
                case MSG_ON_STOP_RTT: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    try {
                        Log.continueSession((Session) args.arg2,
                                SESSION_HANDLER + SESSION_STOP_RTT);
                        String callId = (String) args.arg1;
                        stopRtt(callId);
                    } finally {
                        args.recycle();
                        Log.endSession();
                    }
                    break;
                }
                case MSG_RTT_UPGRADE_RESPONSE: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    try {
                        Log.continueSession((Session) args.arg3,
                                SESSION_HANDLER + SESSION_RTT_UPGRADE_RESPONSE);
                        String callId = (String) args.arg1;
                        Connection.RttTextStream rttTextStream =
                                (Connection.RttTextStream) args.arg2;
                        handleRttUpgradeResponse(callId, rttTextStream);
                    } finally {
                        args.recycle();
                        Log.endSession();
                    }
                    break;
                }
                case MSG_CONNECTION_SERVICE_FOCUS_GAINED:
                    onConnectionServiceFocusGained();
                    break;
                case MSG_CONNECTION_SERVICE_FOCUS_LOST:
                    onConnectionServiceFocusLost();
                    break;
                default:
                    break;
            }
        }
    };

    private final Conference.Listener mConferenceListener = new Conference.Listener() {
        @Override
        public void onStateChanged(Conference conference, int oldState, int newState) {
            String id = mIdByConference.get(conference);
            switch (newState) {
                case Connection.STATE_RINGING:
                    mAdapter.setRinging(id);
                    break;
                case Connection.STATE_DIALING:
                    mAdapter.setDialing(id);
                    break;
                case Connection.STATE_ACTIVE:
                    mAdapter.setActive(id);
                    break;
                case Connection.STATE_HOLDING:
                    mAdapter.setOnHold(id);
                    break;
                case Connection.STATE_DISCONNECTED:
                    // handled by onDisconnected
                    break;
            }
        }

        @Override
        public void onDisconnected(Conference conference, DisconnectCause disconnectCause) {
            String id = mIdByConference.get(conference);
            mAdapter.setDisconnected(id, disconnectCause);
        }

        @Override
        public void onConnectionAdded(Conference conference, Connection connection) {
        }

        @Override
        public void onConnectionRemoved(Conference conference, Connection connection) {
        }

        @Override
        public void onConferenceableConnectionsChanged(
                Conference conference, List<Connection> conferenceableConnections) {
            mAdapter.setConferenceableConnections(
                    mIdByConference.get(conference),
                    createConnectionIdList(conferenceableConnections));
        }

        @Override
        public void onDestroyed(Conference conference) {
            removeConference(conference);
        }

        @Override
        public void onConnectionCapabilitiesChanged(
                Conference conference,
                int connectionCapabilities) {
            String id = mIdByConference.get(conference);
            Log.d(this, "call capabilities: conference: %s",
                    Connection.capabilitiesToString(connectionCapabilities));
            mAdapter.setConnectionCapabilities(id, connectionCapabilities);
        }

        @Override
        public void onConnectionPropertiesChanged(
                Conference conference,
                int connectionProperties) {
            String id = mIdByConference.get(conference);
            Log.d(this, "call capabilities: conference: %s",
                    Connection.propertiesToString(connectionProperties));
            mAdapter.setConnectionProperties(id, connectionProperties);
        }

        @Override
        public void onVideoStateChanged(Conference c, int videoState) {
            String id = mIdByConference.get(c);
            Log.d(this, "onVideoStateChanged set video state %d", videoState);
            mAdapter.setVideoState(id, videoState);
        }

        @Override
        public void onVideoProviderChanged(Conference c, Connection.VideoProvider videoProvider) {
            String id = mIdByConference.get(c);
            Log.d(this, "onVideoProviderChanged: Connection: %s, VideoProvider: %s", c,
                    videoProvider);
            mAdapter.setVideoProvider(id, videoProvider);
        }

        @Override
        public void onStatusHintsChanged(Conference conference, StatusHints statusHints) {
            String id = mIdByConference.get(conference);
            if (id != null) {
                mAdapter.setStatusHints(id, statusHints);
            }
        }

        @Override
        public void onExtrasChanged(Conference c, Bundle extras) {
            String id = mIdByConference.get(c);
            if (id != null) {
                mAdapter.putExtras(id, extras);
            }
        }

        @Override
        public void onExtrasRemoved(Conference c, List<String> keys) {
            String id = mIdByConference.get(c);
            if (id != null) {
                mAdapter.removeExtras(id, keys);
            }
        }

        @Override
        public void onConferenceStateChanged(Conference c, boolean isConference) {
            String id = mIdByConference.get(c);
            if (id != null) {
                mAdapter.setConferenceState(id, isConference);
            }
        }

        @Override
        public void onCallDirectionChanged(Conference c, int direction) {
            String id = mIdByConference.get(c);
            if (id != null) {
                mAdapter.setCallDirection(id, direction);
            }
        }

        @Override
        public void onAddressChanged(Conference c, Uri newAddress, int presentation) {
            String id = mIdByConference.get(c);
            if (id != null) {
                mAdapter.setAddress(id, newAddress, presentation);
            }
        }

        @Override
        public void onCallerDisplayNameChanged(Conference c, String callerDisplayName,
                int presentation) {
            String id = mIdByConference.get(c);
            if (id != null) {
                mAdapter.setCallerDisplayName(id, callerDisplayName, presentation);
            }
        }

        @Override
        public void onConnectionEvent(Conference c, String event, Bundle extras) {
            String id = mIdByConference.get(c);
            if (id != null) {
                mAdapter.onConnectionEvent(id, event, extras);
            }
        }

        @Override
        public void onRingbackRequested(Conference c, boolean ringback) {
            String id = mIdByConference.get(c);
            Log.d(this, "Adapter conference onRingback %b", ringback);
            mAdapter.setRingbackRequested(id, ringback);
        }
    };

    private final Connection.Listener mConnectionListener = new Connection.Listener() {
        @Override
        public void onStateChanged(Connection c, int state) {
            String id = mIdByConnection.get(c);
            Log.d(this, "Adapter set state %s %s", id, Connection.stateToString(state));
            switch (state) {
                case Connection.STATE_ACTIVE:
                    mAdapter.setActive(id);
                    break;
                case Connection.STATE_DIALING:
                    mAdapter.setDialing(id);
                    break;
                case Connection.STATE_PULLING_CALL:
                    mAdapter.setPulling(id);
                    break;
                case Connection.STATE_DISCONNECTED:
                    // Handled in onDisconnected()
                    break;
                case Connection.STATE_HOLDING:
                    mAdapter.setOnHold(id);
                    break;
                case Connection.STATE_NEW:
                    // Nothing to tell Telecom
                    break;
                case Connection.STATE_RINGING:
                    mAdapter.setRinging(id);
                    break;
            }
        }

        @Override
        public void onDisconnected(Connection c, DisconnectCause disconnectCause) {
            String id = mIdByConnection.get(c);
            Log.d(this, "Adapter set disconnected %s", disconnectCause);
            mAdapter.setDisconnected(id, disconnectCause);
        }

        @Override
        public void onVideoStateChanged(Connection c, int videoState) {
            String id = mIdByConnection.get(c);
            Log.d(this, "Adapter set video state %d", videoState);
            mAdapter.setVideoState(id, videoState);
        }

        @Override
        public void onAddressChanged(Connection c, Uri address, int presentation) {
            String id = mIdByConnection.get(c);
            mAdapter.setAddress(id, address, presentation);
        }

        @Override
        public void onCallerDisplayNameChanged(
                Connection c, String callerDisplayName, int presentation) {
            String id = mIdByConnection.get(c);
            mAdapter.setCallerDisplayName(id, callerDisplayName, presentation);
        }

        @Override
        public void onDestroyed(Connection c) {
            removeConnection(c);
        }

        @Override
        public void onPostDialWait(Connection c, String remaining) {
            String id = mIdByConnection.get(c);
            Log.d(this, "Adapter onPostDialWait %s, %s", c, remaining);
            mAdapter.onPostDialWait(id, remaining);
        }

        @Override
        public void onPostDialChar(Connection c, char nextChar) {
            String id = mIdByConnection.get(c);
            Log.d(this, "Adapter onPostDialChar %s, %s", c, nextChar);
            mAdapter.onPostDialChar(id, nextChar);
        }

        @Override
        public void onRingbackRequested(Connection c, boolean ringback) {
            String id = mIdByConnection.get(c);
            Log.d(this, "Adapter onRingback %b", ringback);
            mAdapter.setRingbackRequested(id, ringback);
        }

        @Override
        public void onConnectionCapabilitiesChanged(Connection c, int capabilities) {
            String id = mIdByConnection.get(c);
            Log.d(this, "capabilities: parcelableconnection: %s",
                    Connection.capabilitiesToString(capabilities));
            mAdapter.setConnectionCapabilities(id, capabilities);
        }

        @Override
        public void onConnectionPropertiesChanged(Connection c, int properties) {
            String id = mIdByConnection.get(c);
            Log.d(this, "properties: parcelableconnection: %s",
                    Connection.propertiesToString(properties));
            mAdapter.setConnectionProperties(id, properties);
        }

        @Override
        public void onVideoProviderChanged(Connection c, Connection.VideoProvider videoProvider) {
            String id = mIdByConnection.get(c);
            Log.d(this, "onVideoProviderChanged: Connection: %s, VideoProvider: %s", c,
                    videoProvider);
            mAdapter.setVideoProvider(id, videoProvider);
        }

        @Override
        public void onAudioModeIsVoipChanged(Connection c, boolean isVoip) {
            String id = mIdByConnection.get(c);
            mAdapter.setIsVoipAudioMode(id, isVoip);
        }

        @Override
        public void onStatusHintsChanged(Connection c, StatusHints statusHints) {
            String id = mIdByConnection.get(c);
            mAdapter.setStatusHints(id, statusHints);
        }

        @Override
        public void onConferenceablesChanged(
                Connection connection, List<Conferenceable> conferenceables) {
            mAdapter.setConferenceableConnections(
                    mIdByConnection.get(connection),
                    createIdList(conferenceables));
        }

        @Override
        public void onConferenceChanged(Connection connection, Conference conference) {
            String id = mIdByConnection.get(connection);
            if (id != null) {
                String conferenceId = null;
                if (conference != null) {
                    conferenceId = mIdByConference.get(conference);
                }
                mAdapter.setIsConferenced(id, conferenceId);
            }
        }

        @Override
        public void onConferenceMergeFailed(Connection connection) {
            String id = mIdByConnection.get(connection);
            if (id != null) {
                mAdapter.onConferenceMergeFailed(id);
            }
        }

        @Override
        public void onExtrasChanged(Connection c, Bundle extras) {
            String id = mIdByConnection.get(c);
            if (id != null) {
                mAdapter.putExtras(id, extras);
            }
        }

        @Override
        public void onExtrasRemoved(Connection c, List<String> keys) {
            String id = mIdByConnection.get(c);
            if (id != null) {
                mAdapter.removeExtras(id, keys);
            }
        }

        @Override
        public void onConnectionEvent(Connection connection, String event, Bundle extras) {
            String id = mIdByConnection.get(connection);
            if (id != null) {
                mAdapter.onConnectionEvent(id, event, extras);
            }
        }

        @Override
        public void onAudioRouteChanged(Connection c, int audioRoute, String bluetoothAddress) {
            String id = mIdByConnection.get(c);
            if (id != null) {
                mAdapter.setAudioRoute(id, audioRoute, bluetoothAddress);
            }
        }

        @Override
        public void onRttInitiationSuccess(Connection c) {
            String id = mIdByConnection.get(c);
            if (id != null) {
                mAdapter.onRttInitiationSuccess(id);
            }
        }

        @Override
        public void onRttInitiationFailure(Connection c, int reason) {
            String id = mIdByConnection.get(c);
            if (id != null) {
                mAdapter.onRttInitiationFailure(id, reason);
            }
        }

        @Override
        public void onRttSessionRemotelyTerminated(Connection c) {
            String id = mIdByConnection.get(c);
            if (id != null) {
                mAdapter.onRttSessionRemotelyTerminated(id);
            }
        }

        @Override
        public void onRemoteRttRequest(Connection c) {
            String id = mIdByConnection.get(c);
            if (id != null) {
                mAdapter.onRemoteRttRequest(id);
            }
        }

        @Override
        public void onPhoneAccountChanged(Connection c, PhoneAccountHandle pHandle) {
            String id = mIdByConnection.get(c);
            if (id != null) {
                mAdapter.onPhoneAccountChanged(id, pHandle);
            }
        }

        public void onConnectionTimeReset(Connection c) {
            String id = mIdByConnection.get(c);
            if (id != null) {
                mAdapter.resetConnectionTime(id);
            }
        }
    };

    /** {@inheritDoc} */
    @Override
    public final IBinder onBind(Intent intent) {
        return mBinder;
    }

    /** {@inheritDoc} */
    @Override
    public boolean onUnbind(Intent intent) {
        endAllConnections();
        return super.onUnbind(intent);
    }


    /**
     * This can be used by telecom to either create a new outgoing conference call or attach
     * to an existing incoming conference call. In either case, telecom will cycle through a
     * set of services and call createConference until a connection service cancels the process
     * or completes it successfully.
     */
    private void createConference(
            final PhoneAccountHandle callManagerAccount,
            final String callId,
            final ConnectionRequest request,
            boolean isIncoming,
            boolean isUnknown) {

        Conference conference = null;
        conference = isIncoming ? onCreateIncomingConference(callManagerAccount, request)
                    : onCreateOutgoingConference(callManagerAccount, request);

        Log.d(this, "createConference, conference: %s", conference);
        if (conference == null) {
            Log.i(this, "createConference, implementation returned null conference.");
            conference = Conference.createFailedConference(
                    new DisconnectCause(DisconnectCause.ERROR, "IMPL_RETURNED_NULL_CONFERENCE"),
                    request.getAccountHandle());
        }

        Bundle extras = request.getExtras();
        Bundle newExtras = new Bundle();
        newExtras.putString(Connection.EXTRA_ORIGINAL_CONNECTION_ID, callId);
        if (extras != null) {
            // If the request originated from a remote connection service, we will add some
            // tracking information that Telecom can use to keep informed of which package
            // made the remote request, and which remote connection service was used.
            if (extras.containsKey(Connection.EXTRA_REMOTE_CONNECTION_ORIGINATING_PACKAGE_NAME)) {
                newExtras.putString(
                        Connection.EXTRA_REMOTE_CONNECTION_ORIGINATING_PACKAGE_NAME,
                        extras.getString(
                                Connection.EXTRA_REMOTE_CONNECTION_ORIGINATING_PACKAGE_NAME));
                newExtras.putParcelable(Connection.EXTRA_REMOTE_PHONE_ACCOUNT_HANDLE,
                        request.getAccountHandle());
            }
        }
        conference.putExtras(newExtras);

        mConferenceById.put(callId, conference);
        mIdByConference.put(conference, callId);
        conference.addListener(mConferenceListener);
        ParcelableConference parcelableConference = new ParcelableConference.Builder(
                request.getAccountHandle(), conference.getState())
                .setConnectionCapabilities(conference.getConnectionCapabilities())
                .setConnectionProperties(conference.getConnectionProperties())
                .setVideoAttributes(conference.getVideoProvider() == null
                                ? null : conference.getVideoProvider().getInterface(),
                        conference.getVideoState())
                .setConnectTimeMillis(conference.getConnectTimeMillis(),
                        conference.getConnectionStartElapsedRealtimeMillis())
                .setStatusHints(conference.getStatusHints())
                .setExtras(conference.getExtras())
                .setAddress(conference.getAddress(), conference.getAddressPresentation())
                .setCallerDisplayName(conference.getCallerDisplayName(),
                        conference.getCallerDisplayNamePresentation())
                .setDisconnectCause(conference.getDisconnectCause())
                .setRingbackRequested(conference.isRingbackRequested())
                .build();
        if (conference.getState() != Connection.STATE_DISCONNECTED) {
            conference.setTelecomCallId(callId);
            mAdapter.setVideoProvider(callId, conference.getVideoProvider());
            mAdapter.setVideoState(callId, conference.getVideoState());
            onConferenceAdded(conference);
        }

        Log.d(this, "createConference, calling handleCreateConferenceSuccessful %s", callId);
        mAdapter.handleCreateConferenceComplete(
                callId,
                request,
                parcelableConference);
    }

    /**
     * This can be used by telecom to either create a new outgoing call or attach to an existing
     * incoming call. In either case, telecom will cycle through a set of services and call
     * createConnection util a connection service cancels the process or completes it successfully.
     */
    private void createConnection(
            final PhoneAccountHandle callManagerAccount,
            final String callId,
            final ConnectionRequest request,
            boolean isIncoming,
            boolean isUnknown) {
        boolean isLegacyHandover = request.getExtras() != null &&
                request.getExtras().getBoolean(TelecomManager.EXTRA_IS_HANDOVER, false);
        boolean isHandover = request.getExtras() != null && request.getExtras().getBoolean(
                TelecomManager.EXTRA_IS_HANDOVER_CONNECTION, false);
        Log.d(this, "createConnection, callManagerAccount: %s, callId: %s, request: %s, " +
                        "isIncoming: %b, isUnknown: %b, isLegacyHandover: %b, isHandover: %b",
                callManagerAccount, callId, request, isIncoming, isUnknown, isLegacyHandover,
                isHandover);

        Connection connection = null;
        if (isHandover) {
            PhoneAccountHandle fromPhoneAccountHandle = request.getExtras() != null
                    ? (PhoneAccountHandle) request.getExtras().getParcelable(
                    TelecomManager.EXTRA_HANDOVER_FROM_PHONE_ACCOUNT) : null;
            if (!isIncoming) {
                connection = onCreateOutgoingHandoverConnection(fromPhoneAccountHandle, request);
            } else {
                connection = onCreateIncomingHandoverConnection(fromPhoneAccountHandle, request);
            }
        } else {
            connection = isUnknown ? onCreateUnknownConnection(callManagerAccount, request)
                    : isIncoming ? onCreateIncomingConnection(callManagerAccount, request)
                    : onCreateOutgoingConnection(callManagerAccount, request);
        }
        Log.d(this, "createConnection, connection: %s", connection);
        if (connection == null) {
            Log.i(this, "createConnection, implementation returned null connection.");
            connection = Connection.createFailedConnection(
                    new DisconnectCause(DisconnectCause.ERROR, "IMPL_RETURNED_NULL_CONNECTION"));
        } else {
            try {
                Bundle extras = request.getExtras();
                if (extras != null) {
                    // If the request originated from a remote connection service, we will add some
                    // tracking information that Telecom can use to keep informed of which package
                    // made the remote request, and which remote connection service was used.
                    if (extras.containsKey(
                            Connection.EXTRA_REMOTE_CONNECTION_ORIGINATING_PACKAGE_NAME)) {
                        Bundle newExtras = new Bundle();
                        newExtras.putString(
                                Connection.EXTRA_REMOTE_CONNECTION_ORIGINATING_PACKAGE_NAME,
                                extras.getString(
                                        Connection.EXTRA_REMOTE_CONNECTION_ORIGINATING_PACKAGE_NAME
                                ));
                        newExtras.putParcelable(Connection.EXTRA_REMOTE_PHONE_ACCOUNT_HANDLE,
                                request.getAccountHandle());
                        connection.putExtras(newExtras);
                    }
                }
            } catch (UnsupportedOperationException ose) {
                // Do nothing; if the ConnectionService reported a failure it will be an instance
                // of an immutable Connection which we cannot edit, so we're out of luck.
            }
        }

        boolean isSelfManaged =
                (connection.getConnectionProperties() & Connection.PROPERTY_SELF_MANAGED)
                        == Connection.PROPERTY_SELF_MANAGED;
        // Self-managed Connections should always use voip audio mode; we default here so that the
        // local state within the ConnectionService matches the default we assume in Telecom.
        if (isSelfManaged) {
            connection.setAudioModeIsVoip(true);
        }
        connection.setTelecomCallId(callId);
        if (connection.getState() != Connection.STATE_DISCONNECTED) {
            addConnection(request.getAccountHandle(), callId, connection);
        }

        Uri address = connection.getAddress();
        String number = address == null ? "null" : address.getSchemeSpecificPart();
        Log.v(this, "createConnection, number: %s, state: %s, capabilities: %s, properties: %s",
                Connection.toLogSafePhoneNumber(number),
                Connection.stateToString(connection.getState()),
                Connection.capabilitiesToString(connection.getConnectionCapabilities()),
                Connection.propertiesToString(connection.getConnectionProperties()));

        Log.d(this, "createConnection, calling handleCreateConnectionSuccessful %s", callId);
        mAdapter.handleCreateConnectionComplete(
                callId,
                request,
                new ParcelableConnection(
                        request.getAccountHandle(),
                        connection.getState(),
                        connection.getConnectionCapabilities(),
                        connection.getConnectionProperties(),
                        connection.getSupportedAudioRoutes(),
                        connection.getAddress(),
                        connection.getAddressPresentation(),
                        connection.getCallerDisplayName(),
                        connection.getCallerDisplayNamePresentation(),
                        connection.getVideoProvider() == null ?
                                null : connection.getVideoProvider().getInterface(),
                        connection.getVideoState(),
                        connection.isRingbackRequested(),
                        connection.getAudioModeIsVoip(),
                        connection.getConnectTimeMillis(),
                        connection.getConnectionStartElapsedRealtimeMillis(),
                        connection.getStatusHints(),
                        connection.getDisconnectCause(),
                        createIdList(connection.getConferenceables()),
                        connection.getExtras(),
                        connection.getCallerNumberVerificationStatus()));

        if (isIncoming && request.shouldShowIncomingCallUi() && isSelfManaged) {
            // Tell ConnectionService to show its incoming call UX.
            connection.onShowIncomingCallUi();
        }
        if (isUnknown) {
            triggerConferenceRecalculate();
        }
    }

    private void createConnectionFailed(final PhoneAccountHandle callManagerAccount,
                                        final String callId, final ConnectionRequest request,
                                        boolean isIncoming) {

        Log.i(this, "createConnectionFailed %s", callId);
        if (isIncoming) {
            onCreateIncomingConnectionFailed(callManagerAccount, request);
        } else {
            onCreateOutgoingConnectionFailed(callManagerAccount, request);
        }
    }

    private void createConferenceFailed(final PhoneAccountHandle callManagerAccount,
                                        final String callId, final ConnectionRequest request,
                                        boolean isIncoming) {

        Log.i(this, "createConferenceFailed %s", callId);
        if (isIncoming) {
            onCreateIncomingConferenceFailed(callManagerAccount, request);
        } else {
            onCreateOutgoingConferenceFailed(callManagerAccount, request);
        }
    }

    private void handoverFailed(final String callId, final ConnectionRequest request,
                                        int reason) {

        Log.i(this, "handoverFailed %s", callId);
        onHandoverFailed(request, reason);
    }

    /**
     * Called by Telecom when the creation of a new Connection has completed and it is now added
     * to Telecom.
     * @param callId The ID of the connection.
     */
    private void notifyCreateConnectionComplete(final String callId) {
        Log.i(this, "notifyCreateConnectionComplete %s", callId);
        if (callId == null) {
            // This could happen if the connection fails quickly and is removed from the
            // ConnectionService before Telecom sends the create connection complete callback.
            Log.w(this, "notifyCreateConnectionComplete: callId is null.");
            return;
        }
        onCreateConnectionComplete(findConnectionForAction(callId,
                "notifyCreateConnectionComplete"));
    }

    /**
     * Called by Telecom when the creation of a new Conference has completed and it is now added
     * to Telecom.
     * @param callId The ID of the connection.
     */
    private void notifyCreateConferenceComplete(final String callId) {
        Log.i(this, "notifyCreateConferenceComplete %s", callId);
        if (callId == null) {
            // This could happen if the conference fails quickly and is removed from the
            // ConnectionService before Telecom sends the create conference complete callback.
            Log.w(this, "notifyCreateConferenceComplete: callId is null.");
            return;
        }
        onCreateConferenceComplete(findConferenceForAction(callId,
                "notifyCreateConferenceComplete"));
    }


    private void abort(String callId) {
        Log.i(this, "abort %s", callId);
        findConnectionForAction(callId, "abort").onAbort();
    }

    private void answerVideo(String callId, int videoState) {
        Log.i(this, "answerVideo %s", callId);
        if (mConnectionById.containsKey(callId)) {
            findConnectionForAction(callId, "answer").onAnswer(videoState);
        } else {
            findConferenceForAction(callId, "answer").onAnswer(videoState);
        }
    }

    private void answer(String callId) {
        Log.i(this, "answer %s", callId);
        if (mConnectionById.containsKey(callId)) {
            findConnectionForAction(callId, "answer").onAnswer();
        } else {
            findConferenceForAction(callId, "answer").onAnswer();
        }
    }

    private void deflect(String callId, Uri address) {
        Log.i(this, "deflect %s", callId);
        findConnectionForAction(callId, "deflect").onDeflect(address);
    }

    private void reject(String callId) {
        Log.i(this, "reject %s", callId);
        if (mConnectionById.containsKey(callId)) {
            findConnectionForAction(callId, "reject").onReject();
        } else {
            findConferenceForAction(callId, "reject").onReject();
        }
    }

    private void reject(String callId, String rejectWithMessage) {
        Log.i(this, "reject %s with message", callId);
        findConnectionForAction(callId, "reject").onReject(rejectWithMessage);
    }

    private void reject(String callId, @android.telecom.Call.RejectReason int rejectReason) {
        Log.i(this, "reject %s with reason %d", callId, rejectReason);
        findConnectionForAction(callId, "reject").onReject(rejectReason);
    }

    private void transfer(String callId, Uri number, boolean isConfirmationRequired) {
        Log.i(this, "transfer %s", callId);
        findConnectionForAction(callId, "transfer").onTransfer(number, isConfirmationRequired);
    }

    private void consultativeTransfer(String callId, String otherCallId) {
        Log.i(this, "consultativeTransfer %s", callId);
        Connection connection1 = findConnectionForAction(callId, "consultativeTransfer");
        Connection connection2 = findConnectionForAction(otherCallId, " consultativeTransfer");
        connection1.onTransfer(connection2);
    }

    private void silence(String callId) {
        Log.i(this, "silence %s", callId);
        findConnectionForAction(callId, "silence").onSilence();
    }

    private void disconnect(String callId) {
        Log.i(this, "disconnect %s", callId);
        if (mConnectionById.containsKey(callId)) {
            findConnectionForAction(callId, "disconnect").onDisconnect();
        } else {
            findConferenceForAction(callId, "disconnect").onDisconnect();
        }
    }

    private void hold(String callId) {
        Log.i(this, "hold %s", callId);
        if (mConnectionById.containsKey(callId)) {
            findConnectionForAction(callId, "hold").onHold();
        } else {
            findConferenceForAction(callId, "hold").onHold();
        }
    }

    private void unhold(String callId) {
        Log.i(this, "unhold %s", callId);
        if (mConnectionById.containsKey(callId)) {
            findConnectionForAction(callId, "unhold").onUnhold();
        } else {
            findConferenceForAction(callId, "unhold").onUnhold();
        }
    }

    private void onCallAudioStateChanged(String callId, CallAudioState callAudioState) {
        Log.i(this, "onAudioStateChanged %s %s", callId, callAudioState);
        if (mConnectionById.containsKey(callId)) {
            findConnectionForAction(callId, "onCallAudioStateChanged").setCallAudioState(
                    callAudioState);
        } else {
            findConferenceForAction(callId, "onCallAudioStateChanged").setCallAudioState(
                    callAudioState);
        }
    }

    private void playDtmfTone(String callId, char digit) {
        Log.i(this, "playDtmfTone %s %c", callId, digit);
        if (mConnectionById.containsKey(callId)) {
            findConnectionForAction(callId, "playDtmfTone").onPlayDtmfTone(digit);
        } else {
            findConferenceForAction(callId, "playDtmfTone").onPlayDtmfTone(digit);
        }
    }

    private void stopDtmfTone(String callId) {
        Log.i(this, "stopDtmfTone %s", callId);
        if (mConnectionById.containsKey(callId)) {
            findConnectionForAction(callId, "stopDtmfTone").onStopDtmfTone();
        } else {
            findConferenceForAction(callId, "stopDtmfTone").onStopDtmfTone();
        }
    }

    private void conference(String callId1, String callId2) {
        Log.i(this, "conference %s, %s", callId1, callId2);

        // Attempt to get second connection or conference.
        Connection connection2 = findConnectionForAction(callId2, "conference");
        Conference conference2 = getNullConference();
        if (connection2 == getNullConnection()) {
            conference2 = findConferenceForAction(callId2, "conference");
            if (conference2 == getNullConference()) {
                Log.w(this, "Connection2 or Conference2 missing in conference request %s.",
                        callId2);
                return;
            }
        }

        // Attempt to get first connection or conference and perform merge.
        Connection connection1 = findConnectionForAction(callId1, "conference");
        if (connection1 == getNullConnection()) {
            Conference conference1 = findConferenceForAction(callId1, "addConnection");
            if (conference1 == getNullConference()) {
                Log.w(this,
                        "Connection1 or Conference1 missing in conference request %s.",
                        callId1);
            } else {
                // Call 1 is a conference.
                if (connection2 != getNullConnection()) {
                    // Call 2 is a connection so merge via call 1 (conference).
                    conference1.onMerge(connection2);
                } else {
                    // Call 2 is ALSO a conference; this should never happen.
                    Log.wtf(this, "There can only be one conference and an attempt was made to " +
                            "merge two conferences.");
                    return;
                }
            }
        } else {
            // Call 1 is a connection.
            if (conference2 != getNullConference()) {
                // Call 2 is a conference, so merge via call 2.
                conference2.onMerge(connection1);
            } else {
                // Call 2 is a connection, so merge together.
                onConference(connection1, connection2);
            }
        }
    }

    private void splitFromConference(String callId) {
        Log.i(this, "splitFromConference(%s)", callId);

        Connection connection = findConnectionForAction(callId, "splitFromConference");
        if (connection == getNullConnection()) {
            Log.w(this, "Connection missing in conference request %s.", callId);
            return;
        }

        Conference conference = connection.getConference();
        if (conference != null) {
            conference.onSeparate(connection);
        }
    }

    private void mergeConference(String callId) {
        Log.i(this, "mergeConference(%s)", callId);
        Conference conference = findConferenceForAction(callId, "mergeConference");
        if (conference != null) {
            conference.onMerge();
        }
    }

    private void swapConference(String callId) {
        Log.i(this, "swapConference(%s)", callId);
        Conference conference = findConferenceForAction(callId, "swapConference");
        if (conference != null) {
            conference.onSwap();
        }
    }

    private void addConferenceParticipants(String callId, List<Uri> participants) {
        Log.i(this, "addConferenceParticipants(%s)", callId);
        if (mConnectionById.containsKey(callId)) {
            findConnectionForAction(callId, "addConferenceParticipants")
                    .onAddConferenceParticipants(participants);
        } else {
            findConferenceForAction(callId, "addConferenceParticipants")
                    .onAddConferenceParticipants(participants);
        }
    }

    /**
     * Notifies a {@link Connection} of a request to pull an external call.
     *
     * See {@link Call#pullExternalCall()}.
     *
     * @param callId The ID of the call to pull.
     */
    private void pullExternalCall(String callId) {
        Log.i(this, "pullExternalCall(%s)", callId);
        Connection connection = findConnectionForAction(callId, "pullExternalCall");
        if (connection != null) {
            connection.onPullExternalCall();
        }
    }

    /**
     * Notifies a {@link Connection} of a call event.
     *
     * See {@link Call#sendCallEvent(String, Bundle)}.
     *
     * @param callId The ID of the call receiving the event.
     * @param event The event.
     * @param extras Extras associated with the event.
     */
    private void sendCallEvent(String callId, String event, Bundle extras) {
        Log.i(this, "sendCallEvent(%s, %s)", callId, event);
        Connection connection = findConnectionForAction(callId, "sendCallEvent");
        if (connection != null) {
            connection.onCallEvent(event, extras);
        }
    }

    /**
     * Notifies a {@link Connection} that a handover has completed.
     *
     * @param callId The ID of the call which completed handover.
     */
    private void notifyHandoverComplete(String callId) {
        Log.i(this, "notifyHandoverComplete(%s)", callId);
        Connection connection = findConnectionForAction(callId, "notifyHandoverComplete");
        if (connection != null) {
            connection.onHandoverComplete();
        }
    }

    /**
     * Notifies a {@link Connection} or {@link Conference} of a change to the extras from Telecom.
     * <p>
     * These extra changes can originate from Telecom itself, or from an {@link InCallService} via
     * the {@link android.telecom.Call#putExtra(String, boolean)},
     * {@link android.telecom.Call#putExtra(String, int)},
     * {@link android.telecom.Call#putExtra(String, String)},
     * {@link Call#removeExtras(List)}.
     *
     * @param callId The ID of the call receiving the event.
     * @param extras The new extras bundle.
     */
    private void handleExtrasChanged(String callId, Bundle extras) {
        Log.i(this, "handleExtrasChanged(%s, %s)", callId, extras);
        if (mConnectionById.containsKey(callId)) {
            findConnectionForAction(callId, "handleExtrasChanged").handleExtrasChanged(extras);
        } else if (mConferenceById.containsKey(callId)) {
            findConferenceForAction(callId, "handleExtrasChanged").handleExtrasChanged(extras);
        }
    }

    private void startRtt(String callId, Connection.RttTextStream rttTextStream) {
        Log.i(this, "startRtt(%s)", callId);
        if (mConnectionById.containsKey(callId)) {
            findConnectionForAction(callId, "startRtt").onStartRtt(rttTextStream);
        } else if (mConferenceById.containsKey(callId)) {
            Log.w(this, "startRtt called on a conference.");
        }
    }

    private void stopRtt(String callId) {
        Log.i(this, "stopRtt(%s)", callId);
        if (mConnectionById.containsKey(callId)) {
            findConnectionForAction(callId, "stopRtt").onStopRtt();
        } else if (mConferenceById.containsKey(callId)) {
            Log.w(this, "stopRtt called on a conference.");
        }
    }

    private void handleRttUpgradeResponse(String callId, Connection.RttTextStream rttTextStream) {
        Log.i(this, "handleRttUpgradeResponse(%s, %s)", callId, rttTextStream == null);
        if (mConnectionById.containsKey(callId)) {
            findConnectionForAction(callId, "handleRttUpgradeResponse")
                    .handleRttUpgradeResponse(rttTextStream);
        } else if (mConferenceById.containsKey(callId)) {
            Log.w(this, "handleRttUpgradeResponse called on a conference.");
        }
    }

    private void onPostDialContinue(String callId, boolean proceed) {
        Log.i(this, "onPostDialContinue(%s)", callId);
        findConnectionForAction(callId, "stopDtmfTone").onPostDialContinue(proceed);
    }

    private void onAdapterAttached() {
        if (mAreAccountsInitialized) {
            // No need to query again if we already did it.
            return;
        }

        String callingPackage = getOpPackageName();

        mAdapter.queryRemoteConnectionServices(new RemoteServiceCallback.Stub() {
            @Override
            public void onResult(
                    final List<ComponentName> componentNames,
                    final List<IBinder> services) {
                mHandler.post(new android.telecom.Logging.Runnable("oAA.qRCS.oR", null /*lock*/) {
                    @Override
                    public void loggedRun() {
                        for (int i = 0; i < componentNames.size() && i < services.size(); i++) {
                            mRemoteConnectionManager.addConnectionService(
                                    componentNames.get(i),
                                    IConnectionService.Stub.asInterface(services.get(i)));
                        }
                        onAccountsInitialized();
                        Log.d(this, "remote connection services found: " + services);
                    }
                }.prepare());
            }

            @Override
            public void onError() {
                mHandler.post(new android.telecom.Logging.Runnable("oAA.qRCS.oE", null /*lock*/) {
                    @Override
                    public void loggedRun() {
                        mAreAccountsInitialized = true;
                    }
                }.prepare());
            }
        }, callingPackage);
    }

    /**
     * Ask some other {@code ConnectionService} to create a {@code RemoteConnection} given an
     * incoming request. This is used by {@code ConnectionService}s that are registered with
     * {@link PhoneAccount#CAPABILITY_CONNECTION_MANAGER} and want to be able to manage
     * SIM-based incoming calls.
     *
     * @param connectionManagerPhoneAccount See description at
     *         {@link #onCreateOutgoingConnection(PhoneAccountHandle, ConnectionRequest)}.
     * @param request Details about the incoming call.
     * @return The {@code Connection} object to satisfy this call, or {@code null} to
     *         not handle the call.
     */
    public final RemoteConnection createRemoteIncomingConnection(
            PhoneAccountHandle connectionManagerPhoneAccount,
            ConnectionRequest request) {
        return mRemoteConnectionManager.createRemoteConnection(
                connectionManagerPhoneAccount, request, true);
    }

    /**
     * Ask some other {@code ConnectionService} to create a {@code RemoteConnection} given an
     * outgoing request. This is used by {@code ConnectionService}s that are registered with
     * {@link PhoneAccount#CAPABILITY_CONNECTION_MANAGER} and want to be able to use the
     * SIM-based {@code ConnectionService} to place its outgoing calls.
     *
     * @param connectionManagerPhoneAccount See description at
     *         {@link #onCreateOutgoingConnection(PhoneAccountHandle, ConnectionRequest)}.
     * @param request Details about the outgoing call.
     * @return The {@code Connection} object to satisfy this call, or {@code null} to
     *         not handle the call.
     */
    public final RemoteConnection createRemoteOutgoingConnection(
            PhoneAccountHandle connectionManagerPhoneAccount,
            ConnectionRequest request) {
        return mRemoteConnectionManager.createRemoteConnection(
                connectionManagerPhoneAccount, request, false);
    }

    /**
     * Indicates to the relevant {@code RemoteConnectionService} that the specified
     * {@link RemoteConnection}s should be merged into a conference call.
     * <p>
     * If the conference request is successful, the method {@link #onRemoteConferenceAdded} will
     * be invoked.
     *
     * @param remoteConnection1 The first of the remote connections to conference.
     * @param remoteConnection2 The second of the remote connections to conference.
     */
    public final void conferenceRemoteConnections(
            RemoteConnection remoteConnection1,
            RemoteConnection remoteConnection2) {
        mRemoteConnectionManager.conferenceRemoteConnections(remoteConnection1, remoteConnection2);
    }

    /**
     * Adds a new conference call. When a conference call is created either as a result of an
     * explicit request via {@link #onConference} or otherwise, the connection service should supply
     * an instance of {@link Conference} by invoking this method. A conference call provided by this
     * method will persist until {@link Conference#destroy} is invoked on the conference instance.
     *
     * @param conference The new conference object.
     */
    public final void addConference(Conference conference) {
        Log.d(this, "addConference: conference=%s", conference);

        String id = addConferenceInternal(conference);
        if (id != null) {
            List<String> connectionIds = new ArrayList<>(2);
            for (Connection connection : conference.getConnections()) {
                if (mIdByConnection.containsKey(connection)) {
                    connectionIds.add(mIdByConnection.get(connection));
                }
            }
            conference.setTelecomCallId(id);
            ParcelableConference parcelableConference = new ParcelableConference.Builder(
                    conference.getPhoneAccountHandle(), conference.getState())
                    .setConnectionCapabilities(conference.getConnectionCapabilities())
                    .setConnectionProperties(conference.getConnectionProperties())
                    .setConnectionIds(connectionIds)
                    .setVideoAttributes(conference.getVideoProvider() == null
                                    ? null : conference.getVideoProvider().getInterface(),
                            conference.getVideoState())
                    .setConnectTimeMillis(conference.getConnectTimeMillis(),
                            conference.getConnectionStartElapsedRealtimeMillis())
                    .setStatusHints(conference.getStatusHints())
                    .setExtras(conference.getExtras())
                    .setAddress(conference.getAddress(), conference.getAddressPresentation())
                    .setCallerDisplayName(conference.getCallerDisplayName(),
                            conference.getCallerDisplayNamePresentation())
                    .setDisconnectCause(conference.getDisconnectCause())
                    .setRingbackRequested(conference.isRingbackRequested())
                    .setCallDirection(conference.getCallDirection())
                    .build();

            mAdapter.addConferenceCall(id, parcelableConference);
            mAdapter.setVideoProvider(id, conference.getVideoProvider());
            mAdapter.setVideoState(id, conference.getVideoState());
            // In some instances a conference can start its life as a standalone call with just a
            // single participant; ensure we signal to Telecom in this case.
            if (!conference.isMultiparty()) {
                mAdapter.setConferenceState(id, conference.isMultiparty());
            }

            // Go through any child calls and set the parent.
            for (Connection connection : conference.getConnections()) {
                String connectionId = mIdByConnection.get(connection);
                if (connectionId != null) {
                    mAdapter.setIsConferenced(connectionId, id);
                }
            }
            onConferenceAdded(conference);
        }
    }

    /**
     * Adds a connection created by the {@link ConnectionService} and informs telecom of the new
     * connection.
     *
     * @param phoneAccountHandle The phone account handle for the connection.
     * @param connection The connection to add.
     */
    public final void addExistingConnection(PhoneAccountHandle phoneAccountHandle,
            Connection connection) {
        addExistingConnection(phoneAccountHandle, connection, null /* conference */);
    }

    /**
     * Call to inform Telecom that your {@link ConnectionService} has released call resources (e.g
     * microphone, camera).
     *
     * <p>
     * The {@link ConnectionService} will be disconnected when it failed to call this method within
     * 5 seconds after {@link #onConnectionServiceFocusLost()} is called.
     *
     * @see ConnectionService#onConnectionServiceFocusLost()
     */
    public final void connectionServiceFocusReleased() {
        mAdapter.onConnectionServiceFocusReleased();
    }

    /**
     * Adds a connection created by the {@link ConnectionService} and informs telecom of the new
     * connection, as well as adding that connection to the specified conference.
     * <p>
     * Note: This API is intended ONLY for use by the Telephony stack to provide an easy way to add
     * IMS conference participants to be added to a conference in a single step; this helps ensure
     * UI updates happen atomically, rather than adding the connection and then adding it to
     * the conference in another step.
     *
     * @param phoneAccountHandle The phone account handle for the connection.
     * @param connection The connection to add.
     * @param conference The parent conference of the new connection.
     * @hide
     */
    @SystemApi
    public final void addExistingConnection(@NonNull PhoneAccountHandle phoneAccountHandle,
            @NonNull Connection connection, @NonNull Conference conference) {

        String id = addExistingConnectionInternal(phoneAccountHandle, connection);
        if (id != null) {
            List<String> emptyList = new ArrayList<>(0);
            String conferenceId = null;
            if (conference != null) {
                conferenceId = mIdByConference.get(conference);
            }

            ParcelableConnection parcelableConnection = new ParcelableConnection(
                    phoneAccountHandle,
                    connection.getState(),
                    connection.getConnectionCapabilities(),
                    connection.getConnectionProperties(),
                    connection.getSupportedAudioRoutes(),
                    connection.getAddress(),
                    connection.getAddressPresentation(),
                    connection.getCallerDisplayName(),
                    connection.getCallerDisplayNamePresentation(),
                    connection.getVideoProvider() == null ?
                            null : connection.getVideoProvider().getInterface(),
                    connection.getVideoState(),
                    connection.isRingbackRequested(),
                    connection.getAudioModeIsVoip(),
                    connection.getConnectTimeMillis(),
                    connection.getConnectionStartElapsedRealtimeMillis(),
                    connection.getStatusHints(),
                    connection.getDisconnectCause(),
                    emptyList,
                    connection.getExtras(),
                    conferenceId,
                    connection.getCallDirection(),
                    Connection.VERIFICATION_STATUS_NOT_VERIFIED);
            mAdapter.addExistingConnection(id, parcelableConnection);
        }
    }

    /**
     * Returns all the active {@code Connection}s for which this {@code ConnectionService}
     * has taken responsibility.
     *
     * @return A collection of {@code Connection}s created by this {@code ConnectionService}.
     */
    public final Collection<Connection> getAllConnections() {
        return mConnectionById.values();
    }

    /**
     * Returns all the active {@code Conference}s for which this {@code ConnectionService}
     * has taken responsibility.
     *
     * @return A collection of {@code Conference}s created by this {@code ConnectionService}.
     */
    public final Collection<Conference> getAllConferences() {
        return mConferenceById.values();
    }

    /**
     * Create a {@code Connection} given an incoming request. This is used to attach to existing
     * incoming calls.
     *
     * @param connectionManagerPhoneAccount See description at
     *         {@link #onCreateOutgoingConnection(PhoneAccountHandle, ConnectionRequest)}.
     * @param request Details about the incoming call.
     * @return The {@code Connection} object to satisfy this call, or {@code null} to
     *         not handle the call.
     */
    public Connection onCreateIncomingConnection(
            PhoneAccountHandle connectionManagerPhoneAccount,
            ConnectionRequest request) {
        return null;
    }
    /**
     * Create a {@code Connection} given an incoming request. This is used to attach to existing
     * incoming conference call.
     *
     * @param connectionManagerPhoneAccount See description at
     *         {@link #onCreateOutgoingConnection(PhoneAccountHandle, ConnectionRequest)}.
     * @param request Details about the incoming call.
     * @return The {@code Connection} object to satisfy this call, or {@code null} to
     *         not handle the call.
     * @hide
     */
    public @Nullable Conference onCreateIncomingConference(
            @Nullable PhoneAccountHandle connectionManagerPhoneAccount,
            @Nullable ConnectionRequest request) {
        return null;
    }

    /**
     * Called after the {@link Connection} returned by
     * {@link #onCreateIncomingConnection(PhoneAccountHandle, ConnectionRequest)}
     * or {@link #onCreateOutgoingConnection(PhoneAccountHandle, ConnectionRequest)} has been
     * added to the {@link ConnectionService} and sent to Telecom.
     *
     * @param connection the {@link Connection}.
     * @hide
     */
    public void onCreateConnectionComplete(Connection connection) {
    }

    /**
     * Called after the {@link Conference} returned by
     * {@link #onCreateIncomingConference(PhoneAccountHandle, ConnectionRequest)}
     * or {@link #onCreateOutgoingConference(PhoneAccountHandle, ConnectionRequest)} has been
     * added to the {@link ConnectionService} and sent to Telecom.
     *
     * @param conference the {@link Conference}.
     * @hide
     */
    public void onCreateConferenceComplete(Conference conference) {
    }


    /**
     * Called by Telecom to inform the {@link ConnectionService} that its request to create a new
     * incoming {@link Connection} was denied.
     * <p>
     * Used when a self-managed {@link ConnectionService} attempts to create a new incoming
     * {@link Connection}, but Telecom has determined that the call cannot be allowed at this time.
     * The {@link ConnectionService} is responsible for silently rejecting the new incoming
     * {@link Connection}.
     * <p>
     * See {@link TelecomManager#isIncomingCallPermitted(PhoneAccountHandle)} for more information.
     *
     * @param connectionManagerPhoneAccount See description at
     *         {@link #onCreateOutgoingConnection(PhoneAccountHandle, ConnectionRequest)}.
     * @param request The incoming connection request.
     */
    public void onCreateIncomingConnectionFailed(PhoneAccountHandle connectionManagerPhoneAccount,
                                                 ConnectionRequest request) {
    }

    /**
     * Called by Telecom to inform the {@link ConnectionService} that its request to create a new
     * outgoing {@link Connection} was denied.
     * <p>
     * Used when a self-managed {@link ConnectionService} attempts to create a new outgoing
     * {@link Connection}, but Telecom has determined that the call cannot be placed at this time.
     * The {@link ConnectionService} is responisible for informing the user that the
     * {@link Connection} cannot be made at this time.
     * <p>
     * See {@link TelecomManager#isOutgoingCallPermitted(PhoneAccountHandle)} for more information.
     *
     * @param connectionManagerPhoneAccount See description at
     *         {@link #onCreateOutgoingConnection(PhoneAccountHandle, ConnectionRequest)}.
     * @param request The outgoing connection request.
     */
    public void onCreateOutgoingConnectionFailed(PhoneAccountHandle connectionManagerPhoneAccount,
                                                 ConnectionRequest request) {
    }

    /**
     * Called by Telecom to inform the {@link ConnectionService} that its request to create a new
     * incoming {@link Conference} was denied.
     * <p>
     * Used when a self-managed {@link ConnectionService} attempts to create a new incoming
     * {@link Conference}, but Telecom has determined that the call cannot be allowed at this time.
     * The {@link ConnectionService} is responsible for silently rejecting the new incoming
     * {@link Conference}.
     * <p>
     * See {@link TelecomManager#isIncomingCallPermitted(PhoneAccountHandle)} for more information.
     *
     * @param connectionManagerPhoneAccount See description at
     *         {@link #onCreateOutgoingConnection(PhoneAccountHandle, ConnectionRequest)}.
     * @param request The incoming connection request.
     * @hide
     */
    public void onCreateIncomingConferenceFailed(
            @Nullable PhoneAccountHandle connectionManagerPhoneAccount,
            @Nullable ConnectionRequest request) {
    }

    /**
     * Called by Telecom to inform the {@link ConnectionService} that its request to create a new
     * outgoing {@link Conference} was denied.
     * <p>
     * Used when a self-managed {@link ConnectionService} attempts to create a new outgoing
     * {@link Conference}, but Telecom has determined that the call cannot be placed at this time.
     * The {@link ConnectionService} is responisible for informing the user that the
     * {@link Conference} cannot be made at this time.
     * <p>
     * See {@link TelecomManager#isOutgoingCallPermitted(PhoneAccountHandle)} for more information.
     *
     * @param connectionManagerPhoneAccount See description at
     *         {@link #onCreateOutgoingConnection(PhoneAccountHandle, ConnectionRequest)}.
     * @param request The outgoing connection request.
     * @hide
     */
    public void onCreateOutgoingConferenceFailed(
            @Nullable PhoneAccountHandle connectionManagerPhoneAccount,
            @Nullable ConnectionRequest request) {
    }


    /**
     * Trigger recalculate functinality for conference calls. This is used when a Telephony
     * Connection is part of a conference controller but is not yet added to Connection
     * Service and hence cannot be added to the conference call.
     *
     * @hide
     */
    public void triggerConferenceRecalculate() {
    }

    /**
     * Create a {@code Connection} given an outgoing request. This is used to initiate new
     * outgoing calls.
     *
     * @param connectionManagerPhoneAccount The connection manager account to use for managing
     *         this call.
     *         <p>
     *         If this parameter is not {@code null}, it means that this {@code ConnectionService}
     *         has registered one or more {@code PhoneAccount}s having
     *         {@link PhoneAccount#CAPABILITY_CONNECTION_MANAGER}. This parameter will contain
     *         one of these {@code PhoneAccount}s, while the {@code request} will contain another
     *         (usually but not always distinct) {@code PhoneAccount} to be used for actually
     *         making the connection.
     *         <p>
     *         If this parameter is {@code null}, it means that this {@code ConnectionService} is
     *         being asked to make a direct connection. The
     *         {@link ConnectionRequest#getAccountHandle()} of parameter {@code request} will be
     *         a {@code PhoneAccount} registered by this {@code ConnectionService} to use for
     *         making the connection.
     * @param request Details about the outgoing call.
     * @return The {@code Connection} object to satisfy this call, or the result of an invocation
     *         of {@link Connection#createFailedConnection(DisconnectCause)} to not handle the call.
     */
    public Connection onCreateOutgoingConnection(
            PhoneAccountHandle connectionManagerPhoneAccount,
            ConnectionRequest request) {
        return null;
    }

    /**
     * Create a {@code Conference} given an outgoing request. This is used to initiate new
     * outgoing conference call.
     *
     * @param connectionManagerPhoneAccount The connection manager account to use for managing
     *         this call.
     *         <p>
     *         If this parameter is not {@code null}, it means that this {@code ConnectionService}
     *         has registered one or more {@code PhoneAccount}s having
     *         {@link PhoneAccount#CAPABILITY_CONNECTION_MANAGER}. This parameter will contain
     *         one of these {@code PhoneAccount}s, while the {@code request} will contain another
     *         (usually but not always distinct) {@code PhoneAccount} to be used for actually
     *         making the connection.
     *         <p>
     *         If this parameter is {@code null}, it means that this {@code ConnectionService} is
     *         being asked to make a direct connection. The
     *         {@link ConnectionRequest#getAccountHandle()} of parameter {@code request} will be
     *         a {@code PhoneAccount} registered by this {@code ConnectionService} to use for
     *         making the connection.
     * @param request Details about the outgoing call.
     * @return The {@code Conference} object to satisfy this call, or the result of an invocation
     *         of {@link Connection#createFailedConnection(DisconnectCause)} to not handle the call.
     * @hide
     */
    public @Nullable Conference onCreateOutgoingConference(
            @Nullable PhoneAccountHandle connectionManagerPhoneAccount,
            @Nullable ConnectionRequest request) {
        return null;
    }


    /**
     * Called by Telecom to request that a {@link ConnectionService} creates an instance of an
     * outgoing handover {@link Connection}.
     * <p>
     * A call handover is the process where an ongoing call is transferred from one app (i.e.
     * {@link ConnectionService} to another app.  The user could, for example, choose to continue a
     * mobile network call in a video calling app.  The mobile network call via the Telephony stack
     * is referred to as the source of the handover, and the video calling app is referred to as the
     * destination.
     * <p>
     * When considering a handover scenario the <em>initiating</em> device is where a user initiated
     * the handover process (e.g. by calling {@link android.telecom.Call#handoverTo(
     * PhoneAccountHandle, int, Bundle)}, and the other device is considered the <em>receiving</em>
     * device.
     * <p>
     * This method is called on the destination {@link ConnectionService} on <em>initiating</em>
     * device when the user initiates a handover request from one app to another.  The user request
     * originates in the {@link InCallService} via
     * {@link android.telecom.Call#handoverTo(PhoneAccountHandle, int, Bundle)}.
     * <p>
     * For a full discussion of the handover process and the APIs involved, see
     * {@link android.telecom.Call#handoverTo(PhoneAccountHandle, int, Bundle)}.
     * <p>
     * Implementations of this method should return an instance of {@link Connection} which
     * represents the handover.  If your app does not wish to accept a handover to it at this time,
     * you can return {@code null}.  The code below shows an example of how this is done.
     * <pre>
     * {@code
     * public Connection onCreateIncomingHandoverConnection(PhoneAccountHandle
     *     fromPhoneAccountHandle, ConnectionRequest request) {
     *   if (!isHandoverAvailable()) {
     *       return null;
     *   }
     *   MyConnection connection = new MyConnection();
     *   connection.setAddress(request.getAddress(), TelecomManager.PRESENTATION_ALLOWED);
     *   connection.setVideoState(request.getVideoState());
     *   return connection;
     * }
     * }
     * </pre>
     *
     * @param fromPhoneAccountHandle {@link PhoneAccountHandle} associated with the
     *                               ConnectionService which needs to handover the call.
     * @param request Details about the call to handover.
     * @return {@link Connection} instance corresponding to the handover call.
     */
    public Connection onCreateOutgoingHandoverConnection(PhoneAccountHandle fromPhoneAccountHandle,
                                                         ConnectionRequest request) {
        return null;
    }

    /**
     * Called by Telecom to request that a {@link ConnectionService} creates an instance of an
     * incoming handover {@link Connection}.
     * <p>
     * A call handover is the process where an ongoing call is transferred from one app (i.e.
     * {@link ConnectionService} to another app.  The user could, for example, choose to continue a
     * mobile network call in a video calling app.  The mobile network call via the Telephony stack
     * is referred to as the source of the handover, and the video calling app is referred to as the
     * destination.
     * <p>
     * When considering a handover scenario the <em>initiating</em> device is where a user initiated
     * the handover process (e.g. by calling {@link android.telecom.Call#handoverTo(
     * PhoneAccountHandle, int, Bundle)}, and the other device is considered the <em>receiving</em>
     * device.
     * <p>
     * This method is called on the destination app on the <em>receiving</em> device when the
     * destination app calls {@link TelecomManager#acceptHandover(Uri, int, PhoneAccountHandle)} to
     * accept an incoming handover from the <em>initiating</em> device.
     * <p>
     * For a full discussion of the handover process and the APIs involved, see
     * {@link android.telecom.Call#handoverTo(PhoneAccountHandle, int, Bundle)}.
     * <p>
     * Implementations of this method should return an instance of {@link Connection} which
     * represents the handover.  The code below shows an example of how this is done.
     * <pre>
     * {@code
     * public Connection onCreateIncomingHandoverConnection(PhoneAccountHandle
     *     fromPhoneAccountHandle, ConnectionRequest request) {
     *   // Given that your app requested to accept the handover, you should not return null here.
     *   MyConnection connection = new MyConnection();
     *   connection.setAddress(request.getAddress(), TelecomManager.PRESENTATION_ALLOWED);
     *   connection.setVideoState(request.getVideoState());
     *   return connection;
     * }
     * }
     * </pre>
     *
     * @param fromPhoneAccountHandle {@link PhoneAccountHandle} associated with the
     *                               ConnectionService which needs to handover the call.
     * @param request Details about the call which needs to be handover.
     * @return {@link Connection} instance corresponding to the handover call.
     */
    public Connection onCreateIncomingHandoverConnection(PhoneAccountHandle fromPhoneAccountHandle,
                                                         ConnectionRequest request) {
        return null;
    }

    /**
     * Called by Telecom in response to a {@code TelecomManager#acceptHandover()}
     * invocation which failed.
     * <p>
     * For a full discussion of the handover process and the APIs involved, see
     * {@link android.telecom.Call#handoverTo(PhoneAccountHandle, int, Bundle)}
     *
     * @param request Details about the call which failed to handover.
     * @param error Reason for handover failure.  Will be one of the
     */
    public void onHandoverFailed(ConnectionRequest request,
            @Call.Callback.HandoverFailureErrors int error) {
        return;
    }

    /**
     * Create a {@code Connection} for a new unknown call. An unknown call is a call originating
     * from the ConnectionService that was neither a user-initiated outgoing call, nor an incoming
     * call created using
     * {@code TelecomManager#addNewIncomingCall(PhoneAccountHandle, android.os.Bundle)}.
     *
     * @hide
     */
    public Connection onCreateUnknownConnection(PhoneAccountHandle connectionManagerPhoneAccount,
            ConnectionRequest request) {
        return null;
    }

    /**
     * Conference two specified connections. Invoked when the user has made a request to merge the
     * specified connections into a conference call. In response, the connection service should
     * create an instance of {@link Conference} and pass it into {@link #addConference}.
     *
     * @param connection1 A connection to merge into a conference call.
     * @param connection2 A connection to merge into a conference call.
     */
    public void onConference(Connection connection1, Connection connection2) {}

    /**
     * Called when a connection is added.
     * @hide
     */
    public void onConnectionAdded(Connection connection) {}

    /**
     * Called when a connection is removed.
     * @hide
     */
    public void onConnectionRemoved(Connection connection) {}

    /**
     * Called when a conference is added.
     * @hide
     */
    public void onConferenceAdded(Conference conference) {}

    /**
     * Called when a conference is removed.
     * @hide
     */
    public void onConferenceRemoved(Conference conference) {}

    /**
     * Indicates that a remote conference has been created for existing {@link RemoteConnection}s.
     * When this method is invoked, this {@link ConnectionService} should create its own
     * representation of the conference call and send it to telecom using {@link #addConference}.
     * <p>
     * This is only relevant to {@link ConnectionService}s which are registered with
     * {@link PhoneAccount#CAPABILITY_CONNECTION_MANAGER}.
     *
     * @param conference The remote conference call.
     */
    public void onRemoteConferenceAdded(RemoteConference conference) {}

    /**
     * Called when an existing connection is added remotely.
     * @param connection The existing connection which was added.
     */
    public void onRemoteExistingConnectionAdded(RemoteConnection connection) {}

    /**
     * Called when the {@link ConnectionService} has lost the call focus.
     * The {@link ConnectionService} should release the call resources and invokes
     * {@link ConnectionService#connectionServiceFocusReleased()} to inform telecom that it has
     * released the call resources.
     */
    public void onConnectionServiceFocusLost() {}

    /**
     * Called when the {@link ConnectionService} has gained the call focus. The
     * {@link ConnectionService} can acquire the call resources at this time.
     */
    public void onConnectionServiceFocusGained() {}

    /**
     * @hide
     */
    public boolean containsConference(Conference conference) {
        return mIdByConference.containsKey(conference);
    }

    /** {@hide} */
    void addRemoteConference(RemoteConference remoteConference) {
        onRemoteConferenceAdded(remoteConference);
    }

    /** {@hide} */
    void addRemoteExistingConnection(RemoteConnection remoteConnection) {
        onRemoteExistingConnectionAdded(remoteConnection);
    }

    private void onAccountsInitialized() {
        mAreAccountsInitialized = true;
        for (Runnable r : mPreInitializationConnectionRequests) {
            r.run();
        }
        mPreInitializationConnectionRequests.clear();
    }

    /**
     * Adds an existing connection to the list of connections, identified by a new call ID unique
     * to this connection service.
     *
     * @param connection The connection.
     * @return The ID of the connection (e.g. the call-id).
     */
    private String addExistingConnectionInternal(PhoneAccountHandle handle, Connection connection) {
        String id;

        if (connection.getExtras() != null && connection.getExtras()
                .containsKey(Connection.EXTRA_ORIGINAL_CONNECTION_ID)) {
            id = connection.getExtras().getString(Connection.EXTRA_ORIGINAL_CONNECTION_ID);
            Log.d(this, "addExistingConnectionInternal - conn %s reusing original id %s",
                    connection.getTelecomCallId(), id);
        } else if (handle == null) {
            // If no phone account handle was provided, we cannot be sure the call ID is unique,
            // so just use a random UUID.
            id = UUID.randomUUID().toString();
        } else {
            // Phone account handle was provided, so use the ConnectionService class name as a
            // prefix for a unique incremental call ID.
            id = handle.getComponentName().getClassName() + "@" + getNextCallId();
        }
        addConnection(handle, id, connection);
        return id;
    }

    private void addConnection(PhoneAccountHandle handle, String callId, Connection connection) {
        connection.setTelecomCallId(callId);
        mConnectionById.put(callId, connection);
        mIdByConnection.put(connection, callId);
        connection.addConnectionListener(mConnectionListener);
        connection.setConnectionService(this);
        connection.setPhoneAccountHandle(handle);
        onConnectionAdded(connection);
    }

    /** {@hide} */
    protected void removeConnection(Connection connection) {
        connection.unsetConnectionService(this);
        connection.removeConnectionListener(mConnectionListener);
        String id = mIdByConnection.get(connection);
        if (id != null) {
            mConnectionById.remove(id);
            mIdByConnection.remove(connection);
            mAdapter.removeCall(id);
            onConnectionRemoved(connection);
        }
    }

    private String addConferenceInternal(Conference conference) {
        String originalId = null;
        if (conference.getExtras() != null && conference.getExtras()
                .containsKey(Connection.EXTRA_ORIGINAL_CONNECTION_ID)) {
            originalId = conference.getExtras().getString(Connection.EXTRA_ORIGINAL_CONNECTION_ID);
            Log.d(this, "addConferenceInternal: conf %s reusing original id %s",
                    conference.getTelecomCallId(),
                    originalId);
        }
        if (mIdByConference.containsKey(conference)) {
            Log.w(this, "Re-adding an existing conference: %s.", conference);
        } else if (conference != null) {
            // Conferences do not (yet) have a PhoneAccountHandle associated with them, so we
            // cannot determine a ConnectionService class name to associate with the ID, so use
            // a unique UUID (for now).
            String id = originalId == null ? UUID.randomUUID().toString() : originalId;
            mConferenceById.put(id, conference);
            mIdByConference.put(conference, id);
            conference.addListener(mConferenceListener);
            return id;
        }

        return null;
    }

    private void removeConference(Conference conference) {
        if (mIdByConference.containsKey(conference)) {
            conference.removeListener(mConferenceListener);

            String id = mIdByConference.get(conference);
            mConferenceById.remove(id);
            mIdByConference.remove(conference);
            mAdapter.removeCall(id);

            onConferenceRemoved(conference);
        }
    }

    private Connection findConnectionForAction(String callId, String action) {
        if (callId != null && mConnectionById.containsKey(callId)) {
            return mConnectionById.get(callId);
        }
        Log.w(this, "%s - Cannot find Connection %s", action, callId);
        return getNullConnection();
    }

    static synchronized Connection getNullConnection() {
        if (sNullConnection == null) {
            sNullConnection = new Connection() {};
        }
        return sNullConnection;
    }

    private Conference findConferenceForAction(String conferenceId, String action) {
        if (mConferenceById.containsKey(conferenceId)) {
            return mConferenceById.get(conferenceId);
        }
        Log.w(this, "%s - Cannot find conference %s", action, conferenceId);
        return getNullConference();
    }

    private List<String> createConnectionIdList(List<Connection> connections) {
        List<String> ids = new ArrayList<>();
        for (Connection c : connections) {
            if (mIdByConnection.containsKey(c)) {
                ids.add(mIdByConnection.get(c));
            }
        }
        Collections.sort(ids);
        return ids;
    }

    /**
     * Builds a list of {@link Connection} and {@link Conference} IDs based on the list of
     * {@link Conferenceable}s passed in.
     *
     * @param conferenceables The {@link Conferenceable} connections and conferences.
     * @return List of string conference and call Ids.
     */
    private List<String> createIdList(List<Conferenceable> conferenceables) {
        List<String> ids = new ArrayList<>();
        for (Conferenceable c : conferenceables) {
            // Only allow Connection and Conference conferenceables.
            if (c instanceof Connection) {
                Connection connection = (Connection) c;
                if (mIdByConnection.containsKey(connection)) {
                    ids.add(mIdByConnection.get(connection));
                }
            } else if (c instanceof Conference) {
                Conference conference = (Conference) c;
                if (mIdByConference.containsKey(conference)) {
                    ids.add(mIdByConference.get(conference));
                }
            }
        }
        Collections.sort(ids);
        return ids;
    }

    private Conference getNullConference() {
        if (sNullConference == null) {
            sNullConference = new Conference(null) {};
        }
        return sNullConference;
    }

    private void endAllConnections() {
        // Unbound from telecomm.  We should end all connections and conferences.
        for (Connection connection : mIdByConnection.keySet()) {
            // only operate on top-level calls. Conference calls will be removed on their own.
            if (connection.getConference() == null) {
                connection.onDisconnect();
            }
        }
        for (Conference conference : mIdByConference.keySet()) {
            conference.onDisconnect();
        }
    }

    /**
     * Retrieves the next call ID as maintainted by the connection service.
     *
     * @return The call ID.
     */
    private int getNextCallId() {
        synchronized (mIdSyncRoot) {
            return ++mId;
        }
    }

    /**
     * Returns this handler, ONLY FOR TESTING.
     * @hide
     */
    @VisibleForTesting
    public Handler getHandler() {
        return mHandler;
    }
}
