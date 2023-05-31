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

package com.android.server.companion.datatransfer.contextsync;

import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.telecom.Call;
import android.telecom.Connection;
import android.telecom.ConnectionRequest;
import android.telecom.ConnectionService;
import android.telecom.DisconnectCause;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.LocalServices;
import com.android.server.companion.CompanionDeviceManagerServiceInternal;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Service for Telecom to bind to when call metadata is synced between devices. */
public class CallMetadataSyncConnectionService extends ConnectionService {

    private static final String TAG = "CallMetadataSyncConnectionService";

    @VisibleForTesting
    AudioManager mAudioManager;
    @VisibleForTesting
    TelecomManager mTelecomManager;
    private CompanionDeviceManagerServiceInternal mCdmsi;
    @VisibleForTesting
    final Map<CallMetadataSyncConnectionIdentifier, CallMetadataSyncConnection>
            mActiveConnections = new HashMap<>();
    @VisibleForTesting
    final CrossDeviceSyncControllerCallback
            mCrossDeviceSyncControllerCallback = new CrossDeviceSyncControllerCallback() {

                @Override
                void processContextSyncMessage(int associationId,
                        CallMetadataSyncData callMetadataSyncData) {
                    // Add new calls or update existing calls.
                    for (CallMetadataSyncData.Call call : callMetadataSyncData.getCalls()) {
                        final CallMetadataSyncConnection existingConnection =
                                mActiveConnections.get(new CallMetadataSyncConnectionIdentifier(
                                        associationId, call.getId()));
                        if (existingConnection != null) {
                            existingConnection.update(call);
                        } else {
                            // Check if this is an in-progress id being finalized.
                            CallMetadataSyncConnectionIdentifier key = null;
                            for (Map.Entry<CallMetadataSyncConnectionIdentifier,
                                    CallMetadataSyncConnection> e : mActiveConnections.entrySet()) {
                                if (e.getValue().getAssociationId() == associationId
                                        && !e.getValue().isIdFinalized()
                                        && call.getId().endsWith(e.getValue().getCallId())) {
                                    key = e.getKey();
                                    break;
                                }
                            }
                            if (key != null) {
                                final CallMetadataSyncConnection connection =
                                        mActiveConnections.remove(key);
                                connection.update(call);
                                mActiveConnections.put(
                                        new CallMetadataSyncConnectionIdentifier(associationId,
                                                call.getId()), connection);
                            }
                        }
                    }
                    // Remove obsolete calls.
                    mActiveConnections.values().removeIf(connection -> {
                        if (connection.isIdFinalized()
                                && associationId == connection.getAssociationId()
                                && !callMetadataSyncData.hasCall(connection.getCallId())) {
                            connection.setDisconnected(new DisconnectCause(DisconnectCause.REMOTE));
                            return true;
                        }
                        return false;
                    });
                }

                @Override
                void cleanUpCallIds(Set<String> callIds) {
                    mActiveConnections.values().removeIf(connection -> {
                        if (callIds.contains(connection.getCallId())) {
                            connection.setDisconnected(new DisconnectCause(DisconnectCause.REMOTE));
                            return true;
                        }
                        return false;
                    });
                }
            };

    @Override
    public void onCreate() {
        super.onCreate();

        mAudioManager = getSystemService(AudioManager.class);
        mTelecomManager = getSystemService(TelecomManager.class);
        mCdmsi = LocalServices.getService(CompanionDeviceManagerServiceInternal.class);
        mCdmsi.registerCallMetadataSyncCallback(mCrossDeviceSyncControllerCallback,
                CrossDeviceSyncControllerCallback.TYPE_CONNECTION_SERVICE);
    }

    @Override
    public Connection onCreateIncomingConnection(PhoneAccountHandle phoneAccountHandle,
            ConnectionRequest connectionRequest) {
        final int associationId = connectionRequest.getExtras().getInt(
                CrossDeviceSyncController.EXTRA_ASSOCIATION_ID);
        final CallMetadataSyncData.Call call = CallMetadataSyncData.Call.fromBundle(
                connectionRequest.getExtras().getBundle(CrossDeviceSyncController.EXTRA_CALL));
        call.setDirection(android.companion.Telecom.Call.INCOMING);
        connectionRequest.getExtras().remove(CrossDeviceSyncController.EXTRA_CALL);
        connectionRequest.getExtras().remove(CrossDeviceSyncController.EXTRA_CALL_FACILITATOR_ID);
        connectionRequest.getExtras().remove(CrossDeviceSyncController.EXTRA_ASSOCIATION_ID);
        final CallMetadataSyncConnection connection = new CallMetadataSyncConnection(
                mTelecomManager,
                mAudioManager,
                associationId,
                call,
                new CallMetadataSyncConnectionCallback() {
                    @Override
                    void sendCallAction(int associationId, String callId, int action) {
                        mCdmsi.sendCrossDeviceSyncMessage(associationId,
                                CrossDeviceSyncController.createCallControlMessage(callId, action));
                    }
                });
        connection.setConnectionProperties(Connection.PROPERTY_IS_EXTERNAL_CALL);
        connection.setInitializing();
        return connection;
    }

    @Override
    public void onCreateIncomingConnectionFailed(PhoneAccountHandle phoneAccountHandle,
            ConnectionRequest connectionRequest) {
        final String id =
                phoneAccountHandle != null ? phoneAccountHandle.getId() : "unknown PhoneAccount";
        Slog.e(TAG, "onCreateOutgoingConnectionFailed for: " + id);
    }

    @Override
    public Connection onCreateOutgoingConnection(PhoneAccountHandle phoneAccountHandle,
            ConnectionRequest connectionRequest) {
        final PhoneAccountHandle handle = phoneAccountHandle != null ? phoneAccountHandle
                : connectionRequest.getAccountHandle();
        final PhoneAccount phoneAccount = mTelecomManager.getPhoneAccount(handle);

        final CallMetadataSyncData.Call call = new CallMetadataSyncData.Call();
        call.setId(
                connectionRequest.getExtras().getString(CrossDeviceSyncController.EXTRA_CALL_ID));
        call.setStatus(android.companion.Telecom.Call.UNKNOWN_STATUS);
        final CallMetadataSyncData.CallFacilitator callFacilitator =
                new CallMetadataSyncData.CallFacilitator(phoneAccount != null
                        ? phoneAccount.getLabel().toString()
                        : handle.getComponentName().getShortClassName(),
                        phoneAccount != null ? phoneAccount.getExtras().getString(
                                CrossDeviceSyncController.EXTRA_CALL_FACILITATOR_ID)
                                : handle.getComponentName().getPackageName());
        call.setFacilitator(callFacilitator);
        call.setDirection(android.companion.Telecom.Call.OUTGOING);
        call.setCallerId(connectionRequest.getAddress().getSchemeSpecificPart());

        final int associationId = phoneAccount.getExtras().getInt(
                CrossDeviceSyncController.EXTRA_ASSOCIATION_ID);

        connectionRequest.getExtras().remove(CrossDeviceSyncController.EXTRA_CALL);
        connectionRequest.getExtras().remove(CrossDeviceSyncController.EXTRA_CALL_FACILITATOR_ID);
        connectionRequest.getExtras().remove(CrossDeviceSyncController.EXTRA_ASSOCIATION_ID);

        final CallMetadataSyncConnection connection = new CallMetadataSyncConnection(
                mTelecomManager,
                mAudioManager,
                associationId,
                call,
                new CallMetadataSyncConnectionCallback() {
                    @Override
                    void sendCallAction(int associationId, String callId, int action) {
                        mCdmsi.sendCrossDeviceSyncMessage(associationId,
                                CrossDeviceSyncController.createCallControlMessage(callId, action));
                    }
                });
        connection.setCallerDisplayName(call.getCallerId(), TelecomManager.PRESENTATION_ALLOWED);

        mCdmsi.addSelfOwnedCallId(call.getId());
        mCdmsi.sendCrossDeviceSyncMessage(associationId,
                CrossDeviceSyncController.createCallCreateMessage(call.getId(),
                        connectionRequest.getAddress().toString(),
                        call.getFacilitator().getIdentifier()));

        connection.setInitializing();
        return connection;
    }

    @Override
    public void onCreateOutgoingConnectionFailed(PhoneAccountHandle phoneAccountHandle,
            ConnectionRequest connectionRequest) {
        final String id =
                phoneAccountHandle != null ? phoneAccountHandle.getId() : "unknown PhoneAccount";
        Slog.e(TAG, "onCreateOutgoingConnectionFailed for: " + id);
    }

    @Override
    public void onCreateConnectionComplete(Connection connection) {
        if (connection instanceof CallMetadataSyncConnection) {
            final CallMetadataSyncConnection callMetadataSyncConnection =
                    (CallMetadataSyncConnection) connection;
            callMetadataSyncConnection.initialize();
            mActiveConnections.put(new CallMetadataSyncConnectionIdentifier(
                            callMetadataSyncConnection.getAssociationId(),
                            callMetadataSyncConnection.getCallId()),
                    callMetadataSyncConnection);
        }
    }

    @VisibleForTesting
    static final class CallMetadataSyncConnectionIdentifier {
        private final int mAssociationId;
        private final String mCallId;

        CallMetadataSyncConnectionIdentifier(int associationId, String callId) {
            mAssociationId = associationId;
            mCallId = callId;
        }

        public int getAssociationId() {
            return mAssociationId;
        }

        public String getCallId() {
            return mCallId;
        }

        @Override
        public int hashCode() {
            return Objects.hash(mAssociationId, mCallId);
        }

        @Override
        public boolean equals(Object other) {
            if (other instanceof CallMetadataSyncConnectionIdentifier) {
                return ((CallMetadataSyncConnectionIdentifier) other).getAssociationId()
                        == mAssociationId
                        && mCallId != null && mCallId.equals(
                                ((CallMetadataSyncConnectionIdentifier) other).getCallId());
            }
            return false;
        }
    }

    @VisibleForTesting
    abstract static class CallMetadataSyncConnectionCallback {

        abstract void sendCallAction(int associationId, String callId, int action);
    }

    @VisibleForTesting
    static class CallMetadataSyncConnection extends Connection {

        private final TelecomManager mTelecomManager;
        private final AudioManager mAudioManager;
        private final int mAssociationId;
        private final CallMetadataSyncData.Call mCall;
        private final CallMetadataSyncConnectionCallback mCallback;
        private boolean mIsIdFinalized;

        CallMetadataSyncConnection(TelecomManager telecomManager, AudioManager audioManager,
                int associationId, CallMetadataSyncData.Call call,
                CallMetadataSyncConnectionCallback callback) {
            mTelecomManager = telecomManager;
            mAudioManager = audioManager;
            mAssociationId = associationId;
            mCall = call;
            mCallback = callback;
        }

        public String getCallId() {
            return mCall.getId();
        }

        public int getAssociationId() {
            return mAssociationId;
        }

        public boolean isIdFinalized() {
            return mIsIdFinalized;
        }

        private void initialize() {
            final int status = mCall.getStatus();
            if (status == android.companion.Telecom.Call.RINGING_SILENCED) {
                mTelecomManager.silenceRinger();
            }
            final int state = CrossDeviceCall.convertStatusToState(status);
            if (state == Call.STATE_RINGING) {
                setRinging();
            } else if (state == Call.STATE_ACTIVE) {
                setActive();
            } else if (state == Call.STATE_HOLDING) {
                setOnHold();
            } else if (state == Call.STATE_DISCONNECTED) {
                setDisconnected(new DisconnectCause(DisconnectCause.REMOTE));
            } else if (state == Call.STATE_DIALING) {
                setDialing();
            } else {
                setInitialized();
            }

            final String callerId = mCall.getCallerId();
            if (callerId != null) {
                setCallerDisplayName(callerId, TelecomManager.PRESENTATION_ALLOWED);
                setAddress(Uri.fromParts("custom", mCall.getCallerId(), null),
                        TelecomManager.PRESENTATION_ALLOWED);
            }

            final Bundle extras = new Bundle();
            extras.putString(CrossDeviceSyncController.EXTRA_CALL_ID, mCall.getId());
            putExtras(extras);

            int capabilities = getConnectionCapabilities();
            if (mCall.hasControl(android.companion.Telecom.PUT_ON_HOLD)) {
                capabilities |= CAPABILITY_HOLD;
            } else {
                capabilities &= ~CAPABILITY_HOLD;
            }
            if (mCall.hasControl(android.companion.Telecom.MUTE)) {
                capabilities |= CAPABILITY_MUTE;
            } else {
                capabilities &= ~CAPABILITY_MUTE;
            }
            mAudioManager.setMicrophoneMute(
                    mCall.hasControl(android.companion.Telecom.UNMUTE));
            if (capabilities != getConnectionCapabilities()) {
                setConnectionCapabilities(capabilities);
            }
        }

        private void update(CallMetadataSyncData.Call call) {
            if (!mIsIdFinalized) {
                mCall.setId(call.getId());
                mIsIdFinalized = true;
            }
            final int status = call.getStatus();
            if (status == android.companion.Telecom.Call.RINGING_SILENCED
                    && mCall.getStatus() != android.companion.Telecom.Call.RINGING_SILENCED) {
                mTelecomManager.silenceRinger();
            }
            mCall.setStatus(status);
            final int state = CrossDeviceCall.convertStatusToState(status);
            if (state != getState()) {
                if (state == Call.STATE_RINGING) {
                    setRinging();
                } else if (state == Call.STATE_ACTIVE) {
                    setActive();
                } else if (state == Call.STATE_HOLDING) {
                    setOnHold();
                } else if (state == Call.STATE_DISCONNECTED) {
                    setDisconnected(new DisconnectCause(DisconnectCause.REMOTE));
                } else if (state == Call.STATE_DIALING) {
                    setDialing();
                } else {
                    Slog.e(TAG, "Could not update call to unknown state");
                }
            }

            int capabilities = getConnectionCapabilities();
            mCall.setControls(call.getControls());
            final boolean hasHoldControl = mCall.hasControl(
                    android.companion.Telecom.PUT_ON_HOLD)
                    || mCall.hasControl(android.companion.Telecom.TAKE_OFF_HOLD);
            if (hasHoldControl) {
                capabilities |= CAPABILITY_HOLD;
            } else {
                capabilities &= ~CAPABILITY_HOLD;
            }
            final boolean hasMuteControl = mCall.hasControl(android.companion.Telecom.MUTE)
                    || mCall.hasControl(android.companion.Telecom.UNMUTE);
            if (hasMuteControl) {
                capabilities |= CAPABILITY_MUTE;
            } else {
                capabilities &= ~CAPABILITY_MUTE;
            }
            mAudioManager.setMicrophoneMute(
                    mCall.hasControl(android.companion.Telecom.UNMUTE));
            if (capabilities != getConnectionCapabilities()) {
                setConnectionCapabilities(capabilities);
            }
        }

        @Override
        public void onAnswer(int videoState) {
            sendCallAction(android.companion.Telecom.ACCEPT);
        }

        @Override
        public void onReject() {
            sendCallAction(android.companion.Telecom.REJECT);
        }

        @Override
        public void onReject(int rejectReason) {
            onReject();
        }

        @Override
        public void onReject(String replyMessage) {
            onReject();
        }

        @Override
        public void onSilence() {
            sendCallAction(android.companion.Telecom.SILENCE);
        }

        @Override
        public void onHold() {
            sendCallAction(android.companion.Telecom.PUT_ON_HOLD);
        }

        @Override
        public void onUnhold() {
            sendCallAction(android.companion.Telecom.TAKE_OFF_HOLD);
        }

        @Override
        public void onMuteStateChanged(boolean isMuted) {
            sendCallAction(isMuted ? android.companion.Telecom.MUTE
                    : android.companion.Telecom.UNMUTE);
        }

        @Override
        public void onDisconnect() {
            sendCallAction(android.companion.Telecom.END);
        }

        private void sendCallAction(int action) {
            mCallback.sendCallAction(mAssociationId, mCall.getId(), action);
        }
    }
}
