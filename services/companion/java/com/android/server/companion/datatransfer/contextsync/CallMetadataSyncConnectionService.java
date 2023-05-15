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
import java.util.UUID;

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
                        if (existingConnection == null) {
                            final Bundle extras = new Bundle();
                            extras.putInt(CrossDeviceSyncController.EXTRA_ASSOCIATION_ID,
                                    associationId);
                            extras.putParcelable(CrossDeviceSyncController.EXTRA_CALL, call);
                            mTelecomManager.addNewIncomingCall(call.getPhoneAccountHandle(),
                                    extras);
                        } else {
                            existingConnection.update(call);
                        }
                    }
                    // Remove obsolete calls.
                    mActiveConnections.values().removeIf(connection -> {
                        if (!callMetadataSyncData.hasCall(connection.getCallId())) {
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
        mCdmsi.registerCallMetadataSyncCallback(mCrossDeviceSyncControllerCallback);
    }

    @Override
    public Connection onCreateIncomingConnection(PhoneAccountHandle phoneAccountHandle,
            ConnectionRequest connectionRequest) {
        final int associationId = connectionRequest.getExtras().getInt(
                CrossDeviceSyncController.EXTRA_ASSOCIATION_ID);
        final CallMetadataSyncData.Call call = connectionRequest.getExtras().getParcelable(
                CrossDeviceSyncController.EXTRA_CALL, CallMetadataSyncData.Call.class);
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
        connection.setConnectionProperties(
                Connection.PROPERTY_IS_EXTERNAL_CALL | Connection.PROPERTY_SELF_MANAGED);
        return connection;
    }

    @Override
    public void onCreateIncomingConnectionFailed(PhoneAccountHandle phoneAccountHandle,
            ConnectionRequest connectionRequest) {
        Slog.e(TAG, "onCreateIncomingConnectionFailed for: " + phoneAccountHandle.getId());
    }

    @Override
    public Connection onCreateOutgoingConnection(PhoneAccountHandle phoneAccountHandle,
            ConnectionRequest connectionRequest) {
        final PhoneAccount phoneAccount = mTelecomManager.getPhoneAccount(phoneAccountHandle);

        final CallMetadataSyncData.Call call = new CallMetadataSyncData.Call();
        call.setId(UUID.randomUUID().toString());
        call.setStatus(android.companion.Telecom.Call.UNKNOWN_STATUS);
        call.setPhoneAccountHandle(phoneAccountHandle);
        final CallMetadataSyncData.CallFacilitator callFacilitator =
                new CallMetadataSyncData.CallFacilitator(phoneAccount.getLabel().toString(),
                        phoneAccount.getExtras().getString(
                                CrossDeviceSyncController.EXTRA_CALL_FACILITATOR_ID));
        call.setFacilitator(callFacilitator);

        final int associationId = connectionRequest.getExtras().getInt(
                CrossDeviceSyncController.EXTRA_ASSOCIATION_ID);

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
        connection.setConnectionProperties(
                Connection.PROPERTY_IS_EXTERNAL_CALL | Connection.PROPERTY_SELF_MANAGED);

        mCdmsi.sendCrossDeviceSyncMessage(associationId,
                CrossDeviceSyncController.createCallCreateMessage(call.getId(),
                        connectionRequest.getAddress().toString(),
                        call.getFacilitator().getIdentifier()));

        return connection;
    }

    @Override
    public void onCreateOutgoingConnectionFailed(PhoneAccountHandle phoneAccountHandle,
            ConnectionRequest connectionRequest) {
        Slog.e(TAG, "onCreateIncomingConnectionFailed for: " + phoneAccountHandle.getId());
    }

    @Override
    public void onCreateConnectionComplete(Connection connection) {
        if (connection instanceof CallMetadataSyncConnection) {
            ((CallMetadataSyncConnection) connection).initialize();
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

        public void initialize() {
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
            } else {
                Slog.e(TAG, "Could not initialize call to unknown state");
            }

            final Bundle extras = new Bundle();
            extras.putString(CrossDeviceCall.EXTRA_CALL_ID, mCall.getId());
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

        public void update(CallMetadataSyncData.Call call) {
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
                } else {
                    Slog.e(TAG, "Could not update call to unknown state");
                }
            }

            int capabilities = getConnectionCapabilities();
            final boolean hasHoldControl = mCall.hasControl(
                    android.companion.Telecom.PUT_ON_HOLD)
                    || mCall.hasControl(android.companion.Telecom.TAKE_OFF_HOLD);
            if (hasHoldControl != ((getConnectionCapabilities() & CAPABILITY_HOLD)
                    == CAPABILITY_HOLD)) {
                if (hasHoldControl) {
                    capabilities |= CAPABILITY_HOLD;
                } else {
                    capabilities &= ~CAPABILITY_HOLD;
                }
            }
            final boolean hasMuteControl = mCall.hasControl(android.companion.Telecom.MUTE);
            if (hasMuteControl != ((getConnectionCapabilities() & CAPABILITY_MUTE)
                    == CAPABILITY_MUTE)) {
                if (hasMuteControl) {
                    capabilities |= CAPABILITY_MUTE;
                } else {
                    capabilities &= ~CAPABILITY_MUTE;
                }
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
